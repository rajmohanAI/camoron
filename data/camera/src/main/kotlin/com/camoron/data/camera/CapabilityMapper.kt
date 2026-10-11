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
        )
    }
}
