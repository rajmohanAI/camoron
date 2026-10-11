package com.camoron.domain

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.HardwareLevel
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.WhiteBalancePreset

fun fullCamera(
    id: String = "0",
    facing: LensFacing = LensFacing.BACK,
    level: HardwareLevel = HardwareLevel.FULL,
    sensor: PixelSize = PixelSize(4000, 3000),
) = CameraCapabilities(
    cameraId = id,
    lensFacing = facing,
    hardwareLevel = level,
    sensorSize = sensor,
    maxJpegSize = sensor,
    isoRange = 100..3200,
    exposureTimeRangeNs = 100_000L..1_000_000_000L,
    exposureCompensationRange = -12..12,
    exposureCompensationStep = 1f / 6,
    whiteBalancePresets = WhiteBalancePreset.entries.toSet(),
    supportsManualWhiteBalance = true,
    supportsRaw = true,
    supportsTapToFocus = true,
    hasLensDistortionCalibration = true,
)

fun legacyCamera() = fullCamera(level = HardwareLevel.LEGACY).copy(
    isoRange = null,
    exposureTimeRangeNs = null,
    exposureCompensationRange = 0..0,
    whiteBalancePresets = setOf(WhiteBalancePreset.AUTO),
    supportsManualWhiteBalance = false,
    supportsRaw = false,
)
