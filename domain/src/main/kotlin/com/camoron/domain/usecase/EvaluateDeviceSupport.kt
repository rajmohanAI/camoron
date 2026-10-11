package com.camoron.domain.usecase

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.DeviceSupport
import com.camoron.domain.model.HardwareLevel
import com.camoron.domain.model.LensFacing
import javax.inject.Inject

/**
 * Checks the main back camera against the minimum compatible spec. Devices below it can still use
 * Basic features but are warned that enhancement results will be limited.
 */
class EvaluateDeviceSupport @Inject constructor() {

    operator fun invoke(cameras: List<CameraCapabilities>): DeviceSupport {
        val back = cameras
            .filter { it.lensFacing == LensFacing.BACK }
            .maxByOrNull { it.sensorSize.megapixels }
            ?: return DeviceSupport.BelowMinimum(listOf(DeviceSupport.Reason.NO_BACK_CAMERA))

        val reasons = buildList {
            if (back.hardwareLevel < MIN_HARDWARE_LEVEL) add(DeviceSupport.Reason.HARDWARE_LEVEL_TOO_LOW)
            if (back.sensorSize.megapixels < MIN_MEGAPIXELS) add(DeviceSupport.Reason.RESOLUTION_TOO_LOW)
        }
        return if (reasons.isEmpty()) DeviceSupport.Supported else DeviceSupport.BelowMinimum(reasons)
    }

    companion object {
        val MIN_HARDWARE_LEVEL = HardwareLevel.LIMITED
        const val MIN_MEGAPIXELS = 8.0
    }
}
