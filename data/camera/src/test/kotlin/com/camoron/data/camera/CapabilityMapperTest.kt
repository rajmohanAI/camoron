package com.camoron.data.camera

import android.hardware.camera2.CameraMetadata
import com.camoron.domain.model.HardwareLevel
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.ManualControl
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.WhiteBalancePreset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapabilityMapperTest {
    private val mapper = CapabilityMapper()

    private val fullRaw = RawCharacteristics(
        lensFacing = CameraMetadata.LENS_FACING_BACK,
        hardwareLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
        capabilities = setOf(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING,
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW,
        ),
        pixelArray = PixelSize(4032, 3024),
        jpegSizes = listOf(PixelSize(4032, 3024), PixelSize(1920, 1080)),
        isoRange = 50..6400,
        exposureRangeNs = 10_000L..2_000_000_000L,
        aeCompensationRange = -12..12,
        aeCompensationStep = 1f / 6,
        awbModes = setOf(
            CameraMetadata.CONTROL_AWB_MODE_OFF,
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT,
        ),
        maxAfRegions = 1,
        hasDistortionCalibration = true,
    )

    @Test
    fun `full camera exposes every manual control`() {
        val caps = mapper.map("0", fullRaw)
        assertThat(caps.lensFacing).isEqualTo(LensFacing.BACK)
        assertThat(caps.hardwareLevel).isEqualTo(HardwareLevel.FULL)
        assertThat(caps.maxJpegSize).isEqualTo(PixelSize(4032, 3024))
        assertThat(caps.supportsRaw).isTrue()
        assertThat(caps.whiteBalancePresets).containsExactly(WhiteBalancePreset.AUTO, WhiteBalancePreset.DAYLIGHT)
        ManualControl.entries.forEach { assertThat(caps.supports(it)).isTrue() }
    }

    @Test
    fun `limited camera without manual sensor disables iso and shutter only`() {
        val caps = mapper.map(
            "0",
            fullRaw.copy(
                hardwareLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
                capabilities = emptySet(),
            ),
        )
        assertThat(caps.supports(ManualControl.ISO)).isFalse()
        assertThat(caps.supports(ManualControl.SHUTTER_SPEED)).isFalse()
        assertThat(caps.supports(ManualControl.EXPOSURE_COMPENSATION)).isTrue()
        assertThat(caps.supportsManualWhiteBalance).isFalse()
        assertThat(caps.supports(ManualControl.WHITE_BALANCE)).isTrue() // presets still work
    }

    @Test
    fun `legacy camera never trusts reported manual capabilities`() {
        val caps = mapper.map("0", fullRaw.copy(hardwareLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY))
        assertThat(caps.hardwareLevel).isEqualTo(HardwareLevel.LEGACY)
        assertThat(caps.supportsManualSensor).isFalse()
        assertThat(caps.supportsManualWhiteBalance).isFalse()
    }
}
