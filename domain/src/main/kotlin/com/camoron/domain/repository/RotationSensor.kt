package com.camoron.domain.repository

/** Which way the phone is pointing, for sweeping a panorama. */
interface RotationSensor {
    /** Starts listening; returns false when the phone has no sensor for it. */
    fun start(): Boolean

    fun stop()

    /**
     * Compass-style heading (radians, increasing as the phone turns right) of the direction the back
     * camera, or the front one when [front], looks in; null until the sensor has reported. Only
     * changes in it matter: zero needn't be north.
     */
    fun cameraYaw(front: Boolean): Float?
}
