package com.joeblau.subvox.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit quaternion (w, x, y, z). */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(o: Quaternion) = Quaternion(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conjugate() = Quaternion(w, -x, -y, -z)

    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n == 0.0) IDENTITY else Quaternion(w / n, x / n, y / n, z / n)
    }

    /**
     * Euler angles for the head frame Android uses for head trackers
     * (X = towards the right ear, Y = towards the nose, Z = top of the head),
     * decomposed as R = Rz(yaw) · Rx(pitch) · Ry(roll):
     * pitch = nodding about X, roll = ear-to-shoulder tilt about Y, yaw = turning about Z.
     */
    fun eulerAngles(): Triple<Double, Double, Double> {
        val r01 = 2 * (x * y - w * z)
        val r11 = 1 - 2 * (x * x + z * z)
        val r20 = 2 * (x * z - w * y)
        val r21 = 2 * (y * z + w * x)
        val r22 = 1 - 2 * (x * x + y * y)
        val pitch = asin(r21.coerceIn(-1.0, 1.0))
        val roll = atan2(-r20, r22)
        val yaw = atan2(-r01, r11)
        return Triple(pitch, roll, yaw)
    }

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        /** From an Euler (rotation) vector: direction = axis, length = angle in radians. */
        fun fromRotationVector(vx: Double, vy: Double, vz: Double): Quaternion {
            val angle = sqrt(vx * vx + vy * vy + vz * vz)
            if (angle < 1e-12) return IDENTITY
            val s = sin(angle / 2) / angle
            return Quaternion(cos(angle / 2), vx * s, vy * s, vz * s)
        }
    }
}

/**
 * Turns Android `Sensor.TYPE_HEAD_TRACKER` events (what Pixel Buds Pro 2 report through the
 * head-tracker HID protocol) into [MotionSample]s.
 *
 * Event layout: `values[0..2]` = orientation as an Euler/rotation vector (rad),
 * `values[3..5]` = angular velocity (rad/s). A re-centred reference frame is signalled by
 * `SensorEvent.firstEventAfterDiscontinuity`; a discontinuity count in `values[6]`, if a
 * device reports one, is honoured too.
 *
 * Re-centring would make attitude jump mid-word, so on a discontinuity the converter
 * re-bases the orientation to stay continuous, and flags the reading so the capture
 * controller can drop a segment in progress.
 */
class HeadTrackerConverter : MotionSource {
    override val capabilities = setOf(Capability.ROTATION_RATE, Capability.ATTITUDE)

    data class Reading(val sample: MotionSample, val discontinuity: Boolean)

    private var offset = Quaternion.IDENTITY
    private var lastOutput: Quaternion? = null
    private var lastDiscontinuityCount: Int? = null

    fun reset() {
        offset = Quaternion.IDENTITY
        lastOutput = null
        lastDiscontinuityCount = null
    }

    /**
     * @param timestampNanos `SensorEvent.timestamp`.
     * @param values `SensorEvent.values`.
     * @param firstAfterDiscontinuity `SensorEvent.firstEventAfterDiscontinuity`.
     */
    fun convert(timestampNanos: Long, values: FloatArray, firstAfterDiscontinuity: Boolean = false): Reading? {
        if (values.size < 6) return null
        val raw = Quaternion.fromRotationVector(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
        val count = if (values.size >= 7) values[6].toInt() else null

        var discontinuity = false
        val previous = lastOutput
        val countChanged = count != null && lastDiscontinuityCount != null && count != lastDiscontinuityCount
        if ((firstAfterDiscontinuity || countChanged) && previous != null) {
            // Choose offset so that offset * raw == previous output: no visible jump.
            offset = (previous * raw.conjugate()).normalized()
            discontinuity = true
        }
        if (count != null) lastDiscontinuityCount = count

        val q = (offset * raw).normalized()
        lastOutput = q
        val (pitch, roll, yaw) = q.eulerAngles()

        val sample = MotionSample(
            t = timestampNanos / 1e9,
            rx = values[3].toDouble(), ry = values[4].toDouble(), rz = values[5].toDouble(),
            pitch = pitch, roll = roll, yaw = yaw,
            // Gravity is not reported by head trackers; left as a fixed "upright" vector.
            gx = 0.0, gy = 0.0, gz = -1.0,
            qw = q.w, qx = q.x, qy = q.y, qz = q.z,
        )
        return Reading(sample, discontinuity)
    }
}
