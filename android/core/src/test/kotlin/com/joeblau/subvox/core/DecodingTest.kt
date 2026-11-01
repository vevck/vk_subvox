package com.joeblau.subvox.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Port of SubvoxTests/DecodingTests.swift, run against the rotation-only feature set Pixel Buds provide. */
class DecodingTest {
    private val rng = Random(42)

    /** A synthetic "word": a burst of rotation at `frequency` cycles over the segment. */
    private fun take(frequency: Double, axis: Int, frames: Int = 30, noise: Double = 0.01): List<MotionSample> =
        (0 until frames).map { i ->
            val t = i / 25.0
            val envelope = sin(PI * i / (frames - 1))
            val v = 0.2 * envelope * sin(2 * PI * frequency * i / frames)
            fun jitter() = if (noise == 0.0) 0.0 else rng.nextDouble(-noise, noise)
            var r = doubleArrayOf(jitter(), jitter(), jitter())
            r[axis.coerceAtMost(2)] += v
            MotionSample(t = t, rx = r[0], ry = r[1], rz = r[2])
        }

    @Test fun dtwIdenticalIsZeroAndWarpTolerant() {
        val a = MotionFeatures.frames(take(2.0, 0, noise = 0.0), FeatureSet.ROTATION)
        val stretched = MotionFeatures.frames(take(2.0, 0, frames = 40, noise = 0.0), FeatureSet.ROTATION)
        val other = MotionFeatures.frames(take(2.0, 1, noise = 0.0), FeatureSet.ROTATION)
        assertEquals(0.0, DTW.distance(a, a), 1e-12)
        assertTrue(DTW.distance(a, stretched) < DTW.distance(a, other))
    }

    @Test fun classifierSeparatesDistinctWordsWithHeadTrackerChannels() {
        val templates = (0 until 6).flatMap {
            listOf(Template("yes", take(2.0, 0)), Template("no", take(3.0, 1)))
        }
        val set = FeatureSet.ALL.restrictedTo(HeadTrackerConverter().capabilities)
        assertEquals(false, set.acceleration)
        val classifier = assertNotNull(TemplateClassifier.create(templates, set))
        val prediction = classifier.predict(take(3.0, 1), maxDistance = 10.0, minConfidence = 0.0)
        assertEquals("no", prediction.best?.label)
        assertTrue(prediction.accepted)
    }

    @Test fun evaluationBeatsChanceOnlyWhenSignalExists() {
        val real = (0 until 8).flatMap { listOf(Template("yes", take(2.0, 0)), Template("no", take(3.0, 2))) }
        val noise = (0 until 8).flatMap {
            listOf(Template("yes", take(0.0, 0, noise = 0.05)), Template("no", take(0.0, 0, noise = 0.05)))
        }
        val signal = assertNotNull(Evaluation.run(real, FeatureSet.ROTATION, 200, Random(1)))
        assertTrue(signal.accuracy > 0.9)
        assertTrue(signal.pValue < 0.01)

        val nullResult = assertNotNull(Evaluation.run(noise, FeatureSet.ROTATION, 200, Random(1)))
        assertTrue(nullResult.pValue > 0.01)
    }

    @Test fun trimDropsQuietPadding() {
        val quiet = (0 until 20).map { MotionSample(t = it / 25.0) }
        val burst = take(2.0, 0, noise = 0.0).map { it.copy(t = it.t + 0.8) }
        val trimmed = MotionFeatures.trim(quiet + burst + quiet.map { it.copy(t = it.t + 2) })
        assertTrue(trimmed.size < 40)
        assertTrue(trimmed.size >= 20)
    }

    @Test fun autoCaptureEmitsOneSegmentPerBurst() {
        val segments = mutableListOf<List<MotionSample>>()
        val capture = CaptureController { segments += it }
        capture.autoEnabled = true
        capture.calibrate()
        var t = 0.0
        fun feed(samples: List<MotionSample>) = samples.forEach { capture.ingest(it.copy(t = t)); t += 0.02 }
        feed(take(0.0, 0, frames = 150, noise = 0.005)) // 3 s of rest: calibration
        assertNotNull(capture.noiseFloor)
        feed(take(2.0, 0, frames = 40, noise = 0.005))
        feed(take(0.0, 0, frames = 50, noise = 0.005))
        assertEquals(1, segments.size)
    }
}
