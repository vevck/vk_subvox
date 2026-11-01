package com.joeblau.subvox.core

import java.util.UUID
import kotlin.math.max
import kotlin.random.Random

data class Template(
    val label: String,
    val samples: List<MotionSample>,
    val id: UUID = UUID.randomUUID(),
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    val duration: Double
        get() = if (samples.isEmpty()) 0.0 else samples.last().t - samples.first().t
}

data class RankedLabel(val label: String, val distance: Double)

data class Prediction(
    val ranked: List<RankedLabel>,
    /** 0…1 margin between the best and second-best class. */
    val confidence: Double,
    val accepted: Boolean,
) {
    val best: RankedLabel? get() = ranked.firstOrNull()
}

/**
 * Small-vocabulary recognizer: nearest-neighbour DTW against user-recorded templates.
 * Deliberately simple — the question this prototype answers is whether the signal is
 * there at all, not how far a big model can push it.
 */
class TemplateClassifier private constructor(
    val featureSet: FeatureSet,
    private val entries: List<Pair<String, Frames>>,
    private val scales: DoubleArray,
) {
    val labels: List<String> get() = entries.map { it.first }.distinct().sorted()

    fun rank(samples: List<MotionSample>): List<RankedLabel> {
        val query = MotionFeatures.scaled(MotionFeatures.frames(MotionFeatures.trim(samples), featureSet), scales)
        val best = HashMap<String, Double>()
        for ((label, frames) in entries) {
            val d = DTW.distance(query, frames)
            best[label] = minOf(best[label] ?: Double.POSITIVE_INFINITY, d)
        }
        return best.map { RankedLabel(it.key, it.value) }.sortedBy { it.distance }
    }

    fun predict(samples: List<MotionSample>, maxDistance: Double, minConfidence: Double): Prediction {
        val ranked = rank(samples)
        val confidence = margin(ranked.map { it.distance })
        val accepted = (ranked.firstOrNull()?.distance ?: Double.POSITIVE_INFINITY) <= maxDistance &&
            confidence >= minConfidence
        return Prediction(ranked, confidence, accepted)
    }

    companion object {
        fun create(templates: List<Template>, featureSet: FeatureSet): TemplateClassifier? {
            if (featureSet.isEmpty) return null
            if (templates.map { it.label }.toSet().size < 2) return null
            val raw = templates.map { MotionFeatures.frames(MotionFeatures.trim(it.samples), featureSet) }
            val scales = MotionFeatures.channelScales(raw)
            val entries = templates.zip(raw).map { (t, f) -> t.label to MotionFeatures.scaled(f, scales) }
            return TemplateClassifier(featureSet, entries, scales)
        }

        fun margin(sortedDistances: List<Double>): Double {
            if (sortedDistances.size < 2 || sortedDistances[1] <= 0) return 0.0
            return max(0.0, 1 - sortedDistances[0] / sortedDistances[1])
        }
    }
}

/**
 * Leave-one-out accuracy plus a label-permutation test: the honest answer to
 * "is the motion stream statistically distinguishable between words?"
 */
data class Evaluation(
    val labels: List<String>,
    /** confusion[actual][predicted] */
    val confusion: List<IntArray>,
    val accuracy: Double,
    /** Accuracy of always guessing the most common label. */
    val chance: Double,
    val permutationMean: Double,
    val permutationP95: Double,
    val pValue: Double,
    val permutations: Int,
    val templateCount: Int,
) {
    companion object {
        fun run(
            templates: List<Template>,
            featureSet: FeatureSet,
            permutations: Int = 500,
            random: Random = Random.Default,
        ): Evaluation? {
            val labels = templates.map { it.label }.distinct().sorted()
            val counts = templates.groupingBy { it.label }.eachCount()
            if (labels.size < 2 || counts.values.any { it < 2 } || featureSet.isEmpty) return null

            val raw = templates.map { MotionFeatures.frames(MotionFeatures.trim(it.samples), featureSet) }
            val scales = MotionFeatures.channelScales(raw)
            val seqs = raw.map { MotionFeatures.scaled(it, scales) }
            val n = seqs.size

            // Pairwise distances once; every labelling (true or permuted) reuses them.
            val distance = Array(n) { DoubleArray(n) }
            for (i in 0 until n) for (j in i + 1 until n) {
                val d = DTW.distance(seqs[i], seqs[j])
                distance[i][j] = d
                distance[j][i] = d
            }

            val index = labels.withIndex().associate { it.value to it.index }
            val truth = IntArray(n) { index.getValue(templates[it].label) }

            fun leaveOneOut(assignment: IntArray) = IntArray(n) { i ->
                val best = DoubleArray(labels.size) { Double.POSITIVE_INFINITY }
                for (j in 0 until n) if (j != i) {
                    best[assignment[j]] = minOf(best[assignment[j]], distance[i][j])
                }
                best.indices.minBy { best[it] }
            }

            fun accuracy(predicted: IntArray, actual: IntArray) =
                predicted.indices.count { predicted[it] == actual[it] }.toDouble() / n

            val predicted = leaveOneOut(truth)
            val confusion = List(labels.size) { IntArray(labels.size) }
            for (k in 0 until n) confusion[truth[k]][predicted[k]] += 1
            val observed = accuracy(predicted, truth)

            val nullAccuracies = DoubleArray(permutations) {
                val shuffled = truth.copyOf().also { arr -> arr.shuffle(random) }
                accuracy(leaveOneOut(shuffled), shuffled)
            }.sorted()
            val atLeast = nullAccuracies.count { it >= observed }

            return Evaluation(
                labels = labels,
                confusion = confusion,
                accuracy = observed,
                chance = counts.values.max().toDouble() / n,
                permutationMean = nullAccuracies.sum() / max(permutations, 1),
                permutationP95 = if (nullAccuracies.isEmpty()) 0.0
                else nullAccuracies[((nullAccuracies.size - 1) * 0.95).toInt()],
                pValue = (atLeast + 1).toDouble() / (permutations + 1),
                permutations = permutations,
                templateCount = n,
            )
        }
    }
}
