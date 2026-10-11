package com.camoron.domain.model

enum class LensFacing { BACK, FRONT, EXTERNAL }

/** Mirrors Camera2's INFO_SUPPORTED_HARDWARE_LEVEL, ordered from least to most capable. */
enum class HardwareLevel { LEGACY, EXTERNAL, LIMITED, FULL, LEVEL_3 }

data class PixelSize(val width: Int, val height: Int) {
    val megapixels: Double get() = width.toLong() * height / 1_000_000.0
}

/** Manual controls exposed in Pro mode. */
enum class ManualControl { ISO, SHUTTER_SPEED, EXPOSURE_COMPENSATION, WHITE_BALANCE }

/**
 * What one physical camera can do, derived once from its hardware characteristics.
 * A null range means the control is not supported and the UI must disable it.
 */
data class CameraCapabilities(
    val cameraId: String,
    val lensFacing: LensFacing,
    val hardwareLevel: HardwareLevel,
    val sensorSize: PixelSize,
    val maxJpegSize: PixelSize,
    val isoRange: IntRange?,
    val exposureTimeRangeNs: LongRange?,
    val exposureCompensationRange: IntRange?,
    val exposureCompensationStep: Float,
    val whiteBalancePresets: Set<WhiteBalancePreset>,
    val supportsManualWhiteBalance: Boolean,
    val supportsRaw: Boolean,
    val supportsTapToFocus: Boolean,
    val hasLensDistortionCalibration: Boolean,
) {
    /** ISO and shutter speed can only be set together, so both ranges are required. */
    val supportsManualSensor: Boolean get() = isoRange != null && exposureTimeRangeNs != null

    fun supports(control: ManualControl): Boolean = when (control) {
        ManualControl.ISO, ManualControl.SHUTTER_SPEED -> supportsManualSensor
        ManualControl.EXPOSURE_COMPENSATION ->
            exposureCompensationRange != null && exposureCompensationRange.first != exposureCompensationRange.last
        ManualControl.WHITE_BALANCE -> supportsManualWhiteBalance || whiteBalancePresets.size > 1
    }
}
