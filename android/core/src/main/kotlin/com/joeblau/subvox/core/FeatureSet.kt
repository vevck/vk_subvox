package com.joeblau.subvox.core

/**
 * Which motion channels feed the classifier. Toggling these is the ablation experiment:
 * does articulation show up in acceleration, rotation, or slow head attitude?
 */
data class FeatureSet(val acceleration: Boolean, val rotation: Boolean, val attitude: Boolean) {
    val isEmpty: Boolean get() = !acceleration && !rotation && !attitude

    /** Drops channels the source can't measure, so they never enter the distance. */
    fun restrictedTo(capabilities: Set<Capability>) = FeatureSet(
        acceleration = acceleration && Capability.ACCELERATION in capabilities,
        rotation = rotation && Capability.ROTATION_RATE in capabilities,
        attitude = attitude && Capability.ATTITUDE in capabilities,
    )

    companion object {
        val ALL = FeatureSet(acceleration = true, rotation = true, attitude = true)
        val ROTATION = FeatureSet(acceleration = false, rotation = true, attitude = false)
        val ATTITUDE = FeatureSet(acceleration = false, rotation = false, attitude = true)
        val ACCELERATION = FeatureSet(acceleration = true, rotation = false, attitude = false)
    }
}
