package com.joeblau.subvox.core

/**
 * One processed head-motion reading, field-for-field the same as the macOS `MotionSample`
 * so `motion.csv` files from both platforms can go through the same analysis.
 *
 * On Android `t` is seconds on the `SensorEvent.timestamp` clock (elapsedRealtimeNanos).
 *
 * Pixel Buds Pro 2, through Android's head-tracker sensor, report orientation and
 * angular velocity only. User acceleration and gravity are not available, so those fields
 * stay at their defaults; see [MotionSource.capabilities].
 */
data class MotionSample(
    val t: Double,
    // User acceleration (g), gravity removed.
    val ax: Double = 0.0, val ay: Double = 0.0, val az: Double = 0.0,
    // Rotation rate (rad/s).
    val rx: Double = 0.0, val ry: Double = 0.0, val rz: Double = 0.0,
    // Attitude (rad).
    val pitch: Double = 0.0, val roll: Double = 0.0, val yaw: Double = 0.0,
    // Gravity vector (g).
    val gx: Double = 0.0, val gy: Double = 0.0, val gz: Double = -1.0,
    // Attitude quaternion.
    val qw: Double = 1.0, val qx: Double = 0.0, val qy: Double = 0.0, val qz: Double = 0.0,
) {
    fun csvRow(t0: Double): String =
        doubleArrayOf(t - t0, ax, ay, az, rx, ry, rz, pitch, roll, yaw, gx, gy, gz, qw, qx, qy, qz)
            .joinToString(",") { String.format(java.util.Locale.ROOT, "%.6f", it) }

    companion object {
        const val CSV_HEADER = "t,ax,ay,az,rx,ry,rz,pitch,roll,yaw,gx,gy,gz,qw,qx,qy,qz"
    }
}

/** Which raw channels a motion source actually measures. */
enum class Capability { ACCELERATION, ROTATION_RATE, ATTITUDE, GRAVITY }

interface MotionSource {
    val capabilities: Set<Capability>
}
