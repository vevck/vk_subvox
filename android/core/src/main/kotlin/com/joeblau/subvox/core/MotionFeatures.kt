package com.joeblau.subvox.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

typealias Frames = List<DoubleArray>

object MotionFeatures {
    /**
     * Activity score used for segmentation. Rotation (rad/s) and acceleration (g) live on
     * different scales; the 5× weight puts typical jaw-motion values in the same range.
     * With a rotation-only source (Pixel Buds) acceleration is zero and this is just |ω|.
     */
    fun energy(s: MotionSample): Double {
        val rotation = sqrt(s.rx * s.rx + s.ry * s.ry + s.rz * s.rz)
        val accel = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
        return rotation + 5 * accel
    }

    /**
     * Drops quiet lead-in/lead-out frames so templates align on the articulation itself.
     * Uses a threshold relative to the segment so it behaves the same for training and decoding.
     */
    fun trim(samples: List<MotionSample>, padding: Int = 2): List<MotionSample> {
        if (samples.size <= 6) return samples
        val energies = samples.map(::energy)
        val sorted = energies.sorted()
        val floor = sorted[sorted.size / 5]
        val peak = sorted.last()
        if (peak <= floor * 1.5) return samples
        val threshold = floor + 0.15 * (peak - floor)
        val first = energies.indexOfFirst { it > threshold }
        val last = energies.indexOfLast { it > threshold }
        if (first < 0 || last < 0) return samples
        val lo = max(0, first - padding)
        val hi = min(samples.size - 1, last + padding)
        if (hi - lo + 1 < 5) return samples
        return samples.subList(lo, hi + 1)
    }

    /** Per-frame feature vectors, mean-centered per channel (removes posture/heading bias). */
    fun frames(samples: List<MotionSample>, set: FeatureSet): Frames {
        val origin = samples.firstOrNull() ?: return emptyList()
        val frames = samples.map { s ->
            val v = ArrayList<Double>(9)
            if (set.acceleration) v += listOf(s.ax, s.ay, s.az)
            if (set.rotation) v += listOf(s.rx, s.ry, s.rz)
            if (set.attitude) v += listOf(
                wrapped(s.pitch - origin.pitch), wrapped(s.roll - origin.roll), wrapped(s.yaw - origin.yaw),
            )
            v.toDoubleArray()
        }
        val dims = frames.first().size
        for (d in 0 until dims) {
            val mean = frames.sumOf { it[d] } / frames.size
            for (f in frames) f[d] -= mean
        }
        return frames
    }

    /** Per-channel standard deviation across a corpus, so no channel dominates the distance. */
    fun channelScales(sequences: List<Frames>): DoubleArray {
        val dims = sequences.firstOrNull()?.firstOrNull()?.size ?: return DoubleArray(0)
        val sum = DoubleArray(dims)
        val sumSq = DoubleArray(dims)
        var n = 0.0
        for (sequence in sequences) for (frame in sequence) {
            for (d in 0 until dims) {
                sum[d] += frame[d]
                sumSq[d] += frame[d] * frame[d]
            }
            n += 1
        }
        if (n <= 1) return DoubleArray(dims) { 1.0 }
        return DoubleArray(dims) { d ->
            val mean = sum[d] / n
            max(sqrt(max(sumSq[d] / n - mean * mean, 0.0)), 1e-6)
        }
    }

    fun scaled(frames: Frames, scales: DoubleArray): Frames =
        frames.map { frame -> DoubleArray(frame.size) { frame[it] / scales[it] } }

    private fun wrapped(angle: Double) = atan2(sin(angle), cos(angle))
}
