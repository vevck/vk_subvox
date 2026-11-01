package com.joeblau.subvox.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dynamic time warping between two multichannel sequences, with a Sakoe–Chiba band.
 * Lets the same mouthed word match when said faster or slower.
 */
object DTW {
    fun distance(a: Frames, b: Frames, bandFraction: Double = 0.25): Double {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return Double.POSITIVE_INFINITY
        val band = max(abs(n - m), (max(n, m) * bandFraction).toInt())

        var previous = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var current = DoubleArray(m + 1)
        previous[0] = 0.0

        for (i in 1..n) {
            current.fill(Double.POSITIVE_INFINITY)
            val lo = max(1, i - band)
            val hi = min(m, i + band)
            for (j in lo..hi) {
                val cost = euclidean(a[i - 1], b[j - 1])
                current[j] = cost + minOf(previous[j], current[j - 1], previous[j - 1])
            }
            val tmp = previous; previous = current; current = tmp
        }
        // Normalize by the maximum path length so long and short words compare fairly.
        return previous[m] / (n + m)
    }

    private fun euclidean(x: DoubleArray, y: DoubleArray): Double {
        var sum = 0.0
        for (k in 0 until min(x.size, y.size)) {
            val d = x[k] - y[k]
            sum += d * d
        }
        return sqrt(sum)
    }
}
