package com.camoron.domain.model

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Pro mode's ready-made starting points, like the mode dial on a camera: the exposure modes (Auto,
 * P, S, M) and a few scenes, each setting the Pro dials for the user, who can still fine-tune them.
 *
 * There is no "A" (aperture priority): phone lenses have a fixed aperture, so it would do nothing.
 */
enum class ProPreset {
    /** Everything automatic. */
    AUTO,

    /** Program: the camera picks the exposure; exposure compensation and white balance are the user's. */
    PROGRAM,

    /** Shutter priority: a fixed shutter speed to start from, with the ISO matched to the scene. */
    SHUTTER,

    /** Manual: the current exposure held, for the user to change ISO and shutter themselves. */
    MANUAL,

    /** People: auto exposure, a touch brighter for skin. */
    PORTRAIT,

    /** Views: the cleanest ISO, daylight colour. */
    LANDSCAPE,

    /** Movement: a fast shutter to freeze it. */
    SPORTS,

    /** Low light: a slower shutter, a stop brighter. */
    NIGHT,
    ;

    /**
     * The Pro settings this preset starts from, given what the camera can do and the [live]
     * exposure the camera is using now (to match the brightness of the scene), keeping the user's
     * [current] exposure compensation and white balance where the preset leaves them alone.
     */
    fun settings(capabilities: CameraCapabilities, live: LiveExposure?, current: ManualSettings): ManualSettings {
        val iso = capabilities.isoRange
        val shutter = capabilities.exposureTimeRangeNs
        val manual = capabilities.supportsManualSensor && iso != null && shutter != null
        // Fixes the shutter and picks the ISO that gives the scene the same brightness (times [brighter]).
        fun atShutter(timeNs: Long, brighter: Float = 1f): ManualSettings {
            if (!manual) return ManualSettings.AUTO
            val time = timeNs.coerceIn(shutter!!)
            val matched = live?.let { (it.iso.toDouble() * it.exposureTimeNs / time * brighter).roundToInt() } ?: iso!!.first
            return ManualSettings(iso = matched.coerceIn(iso!!), exposureTimeNs = time)
        }
        return when (this) {
            AUTO -> ManualSettings.AUTO
            PROGRAM -> ManualSettings(exposureCompensation = current.exposureCompensation, whiteBalance = current.whiteBalance)
            SHUTTER -> atShutter(SHUTTER_PRIORITY_NS)
            MANUAL -> if (manual && live != null) {
                ManualSettings(iso = live.iso.coerceIn(iso!!), exposureTimeNs = live.exposureTimeNs.coerceIn(shutter!!), whiteBalance = current.whiteBalance)
            } else {
                atShutter(SHUTTER_PRIORITY_NS)
            }
            PORTRAIT -> ManualSettings(exposureCompensation = steps(capabilities, PORTRAIT_EV))
            LANDSCAPE -> {
                val wb = if (WhiteBalancePreset.DAYLIGHT in capabilities.whiteBalancePresets) {
                    WhiteBalance.Preset(WhiteBalancePreset.DAYLIGHT)
                } else {
                    WhiteBalance.Auto
                }
                if (manual && live != null) {
                    // The lowest ISO, with the shutter slowed to make up for it.
                    val lowest = iso!!.first
                    val time = (live.exposureTimeNs.toDouble() * live.iso / lowest).roundToLong().coerceIn(shutter!!)
                    ManualSettings(iso = lowest, exposureTimeNs = time, whiteBalance = wb)
                } else {
                    ManualSettings(whiteBalance = wb)
                }
            }
            SPORTS -> atShutter(SPORTS_NS)
            NIGHT -> atShutter(NIGHT_NS, brighter = 2f)
        }
    }

    private fun steps(capabilities: CameraCapabilities, ev: Float): Int {
        val range = capabilities.exposureCompensationRange ?: return 0
        if (capabilities.exposureCompensationStep <= 0f) return 0
        return (ev / capabilities.exposureCompensationStep).roundToInt().coerceIn(range)
    }

    companion object {
        /** 1/250 s: sharp hand-held, the usual starting point for shutter priority. */
        const val SHUTTER_PRIORITY_NS = 4_000_000L

        /** 1/1000 s freezes running and ball sports. */
        const val SPORTS_NS = 1_000_000L

        /** 1/8 s: slow enough to gather light, short enough to hold with care. */
        const val NIGHT_NS = 125_000_000L

        /** A third of a stop brighter for faces. */
        const val PORTRAIT_EV = 0.33f
    }
}
