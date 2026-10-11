package com.camoron.domain.model

enum class WhiteBalancePreset { AUTO, DAYLIGHT, CLOUDY, SHADE, INCANDESCENT, FLUORESCENT }

sealed interface WhiteBalance {
    data object Auto : WhiteBalance
    data class Preset(val preset: WhiteBalancePreset) : WhiteBalance
    data class Kelvin(val temperature: Int) : WhiteBalance {
        init {
            require(temperature in MIN..MAX) { "Colour temperature $temperature K is out of range" }
        }

        companion object {
            const val MIN = 2000
            const val MAX = 10000
        }
    }
}

/**
 * User-chosen Pro mode values. A null [iso] or [exposureTimeNs] means "automatic".
 * Exposure compensation only applies while auto-exposure is running.
 */
data class ManualSettings(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val exposureCompensation: Int = 0,
    val whiteBalance: WhiteBalance = WhiteBalance.Auto,
) {
    val isManualExposure: Boolean get() = iso != null || exposureTimeNs != null

    companion object {
        val AUTO = ManualSettings()
    }
}

/** Exposure values the sensor actually used for the latest preview frame. */
data class LiveExposure(val iso: Int, val exposureTimeNs: Long)
