package com.joeblau.subvox.core

/**
 * Cuts the continuous motion stream into word-sized segments, either while a button is held
 * or automatically when motion energy rises above a calibrated noise floor.
 * Platform-free port of the macOS controller: call [ingest] from one thread only.
 */
class CaptureController(var onSegment: (List<MotionSample>) -> Unit = {}) {
    enum class Mode { HOLD, AUTO }

    var autoEnabled = false
        set(value) {
            field = value
            if (!value && isCapturing && !isManual) finish()
        }
    var triggerRatio = 3.0

    var isCapturing = false; private set
    var isCalibrating = false; private set
    var energy = 0.0; private set
    var noiseFloor: Double? = null; private set

    val threshold: Double? get() = noiseFloor?.let { it * triggerRatio }

    private var isManual = false
    private var buffer = ArrayList<MotionSample>()
    private val preroll = ArrayDeque<MotionSample>()
    private val calibration = ArrayList<Double>()
    private var calibrationEnd: Double? = null
    private var quietSince: Double? = null

    fun begin() {
        if (isCapturing) return
        isManual = true
        isCapturing = true
        buffer = ArrayList(preroll)
    }

    fun end() {
        if (!isCapturing || !isManual) return
        finish()
    }

    fun calibrate() {
        calibration.clear()
        calibrationEnd = null
        isCalibrating = true
    }

    /**
     * Abandons a segment in progress without emitting it. Call this when the source
     * reports a discontinuity mid-word so a reference-frame jump never becomes a template.
     */
    fun cancel() {
        buffer = ArrayList()
        isCapturing = false
        isManual = false
        quietSince = null
    }

    fun ingest(sample: MotionSample) {
        val e = MotionFeatures.energy(sample)
        energy = energy * 0.6 + e * 0.4

        if (isCalibrating) {
            val end = calibrationEnd ?: (sample.t + CALIBRATION_SECONDS)
            calibrationEnd = end
            calibration += energy
            if (sample.t >= end) {
                val sorted = calibration.sorted()
                noiseFloor = maxOf(sorted[sorted.size / 2], 1e-4)
                isCalibrating = false
            }
        }

        preroll.addLast(sample)
        while (preroll.isNotEmpty() && preroll.first().t < sample.t - PREROLL_SECONDS) preroll.removeFirst()

        val threshold = threshold
        if (isCapturing) {
            buffer += sample
            val duration = sample.t - buffer.first().t
            if (isManual) {
                if (duration > MAX_HOLD_SECONDS) finish()
            } else if (threshold != null) {
                if (energy < threshold * 0.7) {
                    val since = quietSince ?: sample.t
                    quietSince = since
                    if (sample.t - since > HANGOVER_SECONDS) { finish(); return }
                } else {
                    quietSince = null
                }
                if (duration > MAX_AUTO_SECONDS) finish()
            }
        } else if (autoEnabled && !isCalibrating && threshold != null && energy > threshold) {
            isManual = false
            isCapturing = true
            quietSince = null
            buffer = ArrayList(preroll)
        }
    }

    private fun finish() {
        val segment = buffer
        cancel()
        if (segment.size < 4 || segment.last().t - segment.first().t < 0.15) return
        onSegment(segment)
    }

    companion object {
        const val CALIBRATION_SECONDS = 2.0
        const val PREROLL_SECONDS = 0.3
        const val HANGOVER_SECONDS = 0.35
        const val MAX_AUTO_SECONDS = 3.0
        const val MAX_HOLD_SECONDS = 6.0
    }
}
