package com.camoron.domain

import com.camoron.domain.model.DeviceSupport
import com.camoron.domain.model.DeviceSupport.Reason
import com.camoron.domain.model.HardwareLevel
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.PixelSize
import com.camoron.domain.usecase.EvaluateDeviceSupport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EvaluateDeviceSupportTest {
    private val evaluate = EvaluateDeviceSupport()

    @Test
    fun `full 12MP back camera is supported`() {
        assertThat(evaluate(listOf(fullCamera()))).isEqualTo(DeviceSupport.Supported)
    }

    @Test
    fun `legacy low resolution camera reports both reasons`() {
        val cam = fullCamera(level = HardwareLevel.LEGACY, sensor = PixelSize(2592, 1944))
        assertThat(evaluate(listOf(cam)))
            .isEqualTo(DeviceSupport.BelowMinimum(listOf(Reason.HARDWARE_LEVEL_TOO_LOW, Reason.RESOLUTION_TOO_LOW)))
    }

    @Test
    fun `only front camera means no back camera`() {
        assertThat(evaluate(listOf(fullCamera(facing = LensFacing.FRONT))))
            .isEqualTo(DeviceSupport.BelowMinimum(listOf(Reason.NO_BACK_CAMERA)))
    }

    @Test
    fun `highest resolution back camera is the one evaluated`() {
        val small = fullCamera(id = "2", level = HardwareLevel.LEGACY, sensor = PixelSize(1600, 1200))
        assertThat(evaluate(listOf(small, fullCamera(id = "0")))).isEqualTo(DeviceSupport.Supported)
    }
}
