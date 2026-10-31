package com.camoron.domain.repository

import com.camoron.domain.model.GeoPoint

/** Which way the phone is pointing: for sweeping a panorama, and for Sky view. */
interface RotationSensor {
    /**
     * Starts listening; returns false when the phone has no sensor for it. With [compass] the
     * readings are tied to north (using the magnetometer), which Sky view needs; without, they only
     * follow turns, smoothly and unaffected by nearby metal, which is all a panorama needs.
     */
    fun start(compass: Boolean = false): Boolean

    fun stop()

    /**
     * Compass-style heading (radians, increasing as the phone turns right) of the direction the back
     * camera, or the front one when [front], looks in; null until the sensor has reported. Only
     * changes in it matter: zero needn't be north.
     */
    fun cameraYaw(front: Boolean): Float?

    /**
     * The phone's latest rotation, as a 3×3 row-major matrix turning phone axes (x right, y up, z out
     * of the screen) into east/north/up, with north magnetic; null until the sensor has reported.
     */
    fun orientation(): FloatArray?

    /** The compass says it needs calibrating (the figure-of-eight wave). */
    fun compassUnreliable(): Boolean

    /** Degrees magnetic north lies east of true north at [point] (west of it when negative). */
    fun declinationDegrees(point: GeoPoint, epochMillis: Long): Float
}
