package com.camoron.domain.usecase

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.ManualControl
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.WhiteBalance
import com.camoron.domain.model.WhiteBalancePreset
import javax.inject.Inject

/**
 * Drops settings the camera cannot honour and clamps the rest into its supported ranges, so the
 * camera layer never receives a request the hardware would reject.
 */
class SanitizeManualSettings @Inject constructor() {

    operator fun invoke(settings: ManualSettings, caps: CameraCapabilities): ManualSettings {
        val manualSensor = caps.supports(ManualControl.ISO)
        val iso = settings.iso?.takeIf { manualSensor }?.coerceIn(caps.isoRange!!)
        val exposure = settings.exposureTimeNs?.takeIf { manualSensor }?.coerceIn(caps.exposureTimeRangeNs!!)

        val evRange = caps.exposureCompensationRange
        val ev = if (evRange == null) 0 else settings.exposureCompensation.coerceIn(evRange)

        val wb = when (val requested = settings.whiteBalance) {
            WhiteBalance.Auto -> WhiteBalance.Auto
            is WhiteBalance.Preset ->
                if (requested.preset in caps.whiteBalancePresets && requested.preset != WhiteBalancePreset.AUTO) {
                    requested
                } else {
                    WhiteBalance.Auto
                }
            is WhiteBalance.Kelvin -> if (caps.supportsManualWhiteBalance) requested else WhiteBalance.Auto
        }

        return ManualSettings(iso = iso, exposureTimeNs = exposure, exposureCompensation = ev, whiteBalance = wb)
    }
}
