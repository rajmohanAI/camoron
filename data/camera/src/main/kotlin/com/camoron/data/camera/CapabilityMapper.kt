package com.camoron.data.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.HardwareLevel
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.WhiteBalancePreset
import javax.inject.Inject

/**
 * The plain values Camoron reads from [CameraCharacteristics]. Splitting extraction from mapping
 * keeps the capability rules unit-testable without a device.
 */
internal data class RawCharacteristics(
    val lensFacing: Int?,
    val hardwareLevel: Int?,
    val capabilities: Set<Int>,
    val pixelArray: PixelSize?,
    val jpegSizes: List<PixelSize>,
    val isoRange: IntRange?,
    val exposureRangeNs: LongRange?,
    val aeCompensationRange: IntRange?,
    val aeCompensationStep: Float,
    val awbModes: Set<Int>,
    val maxAfRegions: Int,
    val hasDistortionCalibration: Boolean,
    val flashAvailable: Boolean = false,
    val zoomRatioRange: ClosedFloatingPointRange<Float>? = null,
    val minFocusDistance: Float = 0f,
    val focalLengthsMm: List<Float> = emptyList(),
    val sensorPhysicalDiagonalMm: Float? = null,
    val physicalCameraCount: Int = 1,
    val fpsRangeUppers: Set<Int> = emptySet(),
    val faceDetectModes: Set<Int> = emptySet(),
    val maxDigitalZoom: Float = 1f,
    val afModes: Set<Int> = emptySet(),
    val noiseReductionModes: Set<Int> = emptySet(),
    val distortionCorrectionModes: Set<Int> = emptySet(),
)

class CapabilityMapper @Inject constructor() {

    fun map(cameraId: String, characteristics: CameraCharacteristics): CameraCapabilities =
        map(cameraId, extract(characteristics))

    internal fun map(cameraId: String, raw: RawCharacteristics): CameraCapabilities {
        val level = hardwareLevel(raw.hardwareLevel)
        // LEGACY devices report ranges but silently ignore manual values, so never trust them.
        val manualSensor = level != HardwareLevel.LEGACY &&
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in raw.capabilities
        val manualPostProcessing = level != HardwareLevel.LEGACY &&
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in raw.capabilities &&
            CameraMetadata.CONTROL_AWB_MODE_OFF in raw.awbModes
        val maxJpeg = raw.jpegSizes.maxByOrNull { it.width.toLong() * it.height } ?: PixelSize(0, 0)

        return CameraCapabilities(
            cameraId = cameraId,
            lensFacing = when (raw.lensFacing) {
                CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
                CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
                else -> LensFacing.EXTERNAL
            },
            hardwareLevel = level,
            sensorSize = raw.pixelArray ?: maxJpeg,
            maxJpegSize = maxJpeg,
            isoRange = raw.isoRange.takeIf { manualSensor },
            exposureTimeRangeNs = raw.exposureRangeNs.takeIf { manualSensor },
            exposureCompensationRange = raw.aeCompensationRange?.takeIf { it.first < it.last },
            exposureCompensationStep = raw.aeCompensationStep,
            whiteBalancePresets = raw.awbModes.mapNotNull(::presetFor).toSet(),
            supportsManualWhiteBalance = manualPostProcessing,
            supportsRaw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in raw.capabilities,
            supportsTapToFocus = raw.maxAfRegions > 0,
            hasLensDistortionCalibration = raw.hasDistortionCalibration,
            hasFlash = raw.flashAvailable,
            stillAspects = CameraMath.stillAspects(raw.jpegSizes),
            zoomRatioRange = raw.zoomRatioRange,
            minFocusDistanceDiopters = raw.minFocusDistance,
            equivalentFocalLengthMm = raw.focalLengthsMm.minOrNull()?.let { focal ->
                raw.sensorPhysicalDiagonalMm?.let { CameraMath.equivalentFocalLength(focal, it) }
            },
            physicalCameraCount = raw.physicalCameraCount,
            videoFrameRates = raw.fpsRangeUppers,
            supportsHighSpeedVideo =
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in raw.capabilities,
            supportsFaceDetection = raw.faceDetectModes.any { it != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF },
            maxDigitalZoom = raw.maxDigitalZoom,
            supportsMacroAf = CameraMetadata.CONTROL_AF_MODE_MACRO in raw.afModes,
            // LENS_FOCUS_DISTANCE needs AF off and real manual sensor control to be honoured.
            supportsManualFocus = manualSensor && raw.minFocusDistance > 0f &&
                CameraMetadata.CONTROL_AF_MODE_OFF in raw.afModes,
            supportsHqNoiseReduction = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY in raw.noiseReductionModes,
            supportsDistortionCorrection = DISTORTION_CORRECTION_HIGH_QUALITY in raw.distortionCorrectionModes,
        )
    }

    private fun hardwareLevel(value: Int?): HardwareLevel = when (value) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
        else -> HardwareLevel.LEGACY
    }

    private fun presetFor(awbMode: Int): WhiteBalancePreset? = when (awbMode) {
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> WhiteBalancePreset.AUTO
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> WhiteBalancePreset.DAYLIGHT
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> WhiteBalancePreset.CLOUDY
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> WhiteBalancePreset.SHADE
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> WhiteBalancePreset.INCANDESCENT
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> WhiteBalancePreset.FLUORESCENT
        else -> null
    }

    private fun extract(c: CameraCharacteristics): RawCharacteristics {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val distortion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            c.get(CameraCharacteristics.LENS_DISTORTION)
        } else {
            @Suppress("DEPRECATION")
            c.get(CameraCharacteristics.LENS_RADIAL_DISTORTION)
        }
        return RawCharacteristics(
            lensFacing = c.get(CameraCharacteristics.LENS_FACING),
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty(),
            pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { PixelSize(it.width, it.height) },
            jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.map { PixelSize(it.width, it.height) }.orEmpty(),
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper },
            exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper },
            aeCompensationRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { it.lower..it.upper },
            aeCompensationStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
            awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty(),
            maxAfRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            hasDistortionCalibration = distortion != null,
            flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower..it.upper }
            } else {
                null
            },
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
            sensorPhysicalDiagonalMm = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let {
                kotlin.math.hypot(it.width, it.height)
            },
            physicalCameraCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                c.physicalCameraIds.size.coerceAtLeast(1)
            } else {
                1
            },
            fpsRangeUppers = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { it.upper }?.toSet().orEmpty(),
            faceDetectModes = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.toSet().orEmpty(),
            maxDigitalZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty(),
            noiseReductionModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                ?.toSet().orEmpty(),
            distortionCorrectionModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)?.toSet().orEmpty()
            } else {
                emptySet()
            },
        )
    }

    internal companion object {
        /** CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY, added in Android 9. */
        const val DISTORTION_CORRECTION_HIGH_QUALITY = 2
    }
}
