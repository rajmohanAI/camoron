package com.camoron.domain.model

enum class LensFacing { BACK, FRONT, EXTERNAL }

/** Mirrors Camera2's INFO_SUPPORTED_HARDWARE_LEVEL, ordered from least to most capable. */
enum class HardwareLevel { LEGACY, EXTERNAL, LIMITED, FULL, LEVEL_3 }

data class PixelSize(val width: Int, val height: Int) {
    val megapixels: Double get() = width.toLong() * height / 1_000_000.0
}

/** Aspect ratio of the sensor output the session is configured for (landscape, as the sensor sees it). */
enum class StillAspect(val ratio: Double) { FOUR_THREE(4.0 / 3), SIXTEEN_NINE(16.0 / 9) }

enum class CameraExtension { NIGHT, BOKEH, HDR, FACE_RETOUCH, AUTO }

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
    val hasFlash: Boolean = false,
    /** Native still-output aspect ratios with a full-resolution size available. */
    val stillAspects: Set<StillAspect> = setOf(StillAspect.FOUR_THREE),
    /** Zoom range the logical camera accepts (Android 11+); below 1.0 means an ultra-wide is reachable. */
    val zoomRatioRange: ClosedFloatingPointRange<Float>? = null,
    /** Closest focus in diopters (1/metres); 0 means fixed focus. */
    val minFocusDistanceDiopters: Float = 0f,
    /** 35mm-equivalent focal length of the primary lens, used to classify wide/ultra-wide/tele. */
    val equivalentFocalLengthMm: Float? = null,
    val physicalCameraCount: Int = 1,
    /** Upper bounds of the AE target fps ranges, e.g. 24, 30, 60. */
    val videoFrameRates: Set<Int> = emptySet(),
    val supportsHighSpeedVideo: Boolean = false,
    /** Phone-maker processing modes exposed through Camera2 Extensions (Android 12+). */
    val extensions: Set<CameraExtension> = emptySet(),
    val supportsFaceDetection: Boolean = false,
    /** Crop-based zoom limit, used where [zoomRatioRange] is unavailable (before Android 11). */
    val maxDigitalZoom: Float = 1f,
    /** The camera has a dedicated close-up autofocus mode (CONTROL_AF_MODE_MACRO). */
    val supportsMacroAf: Boolean = false,
    /** Focus distance can be set directly, so focus can be locked near the minimum distance. */
    val supportsManualFocus: Boolean = false,
    /** The camera's own high-quality noise reduction can be requested for stills. */
    val supportsHqNoiseReduction: Boolean = false,
    /** The camera can correct lens distortion itself (Android 9+). */
    val supportsDistortionCorrection: Boolean = false,
) {
    /** Zoom the camera can reach on its own, through its zoom ratio or by cropping. */
    val zoomRange: ClosedFloatingPointRange<Float>
        get() = zoomRatioRange ?: 1f..maxDigitalZoom.coerceAtLeast(1f)

    /** Focuses close enough to be worth a Macro mode, and has a way to hold focus there. */
    val supportsMacro: Boolean
        get() = minFocusDistanceDiopters >= MACRO_MIN_DIOPTERS && (supportsMacroAf || supportsManualFocus)

    /** Front cameras without an LED still get a screen flash. */
    val supportsFlashControl: Boolean get() = hasFlash || lensFacing == LensFacing.FRONT

    /** ISO and shutter speed can only be set together, so both ranges are required. */
    val supportsManualSensor: Boolean get() = isoRange != null && exposureTimeRangeNs != null

    fun supports(control: ManualControl): Boolean = when (control) {
        ManualControl.ISO, ManualControl.SHUTTER_SPEED -> supportsManualSensor
        ManualControl.EXPOSURE_COMPENSATION ->
            exposureCompensationRange != null && exposureCompensationRange.first != exposureCompensationRange.last
        ManualControl.WHITE_BALANCE -> supportsManualWhiteBalance || whiteBalancePresets.size > 1
    }

    companion object {
        /** 10 diopters is a 10 cm closest focus distance. */
        const val MACRO_MIN_DIOPTERS = 10f
    }
}
