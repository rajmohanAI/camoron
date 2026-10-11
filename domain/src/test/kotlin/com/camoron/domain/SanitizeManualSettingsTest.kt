package com.camoron.domain

import com.camoron.domain.model.ManualControl
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.WhiteBalance
import com.camoron.domain.model.WhiteBalancePreset
import com.camoron.domain.usecase.SanitizeManualSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SanitizeManualSettingsTest {
    private val sanitize = SanitizeManualSettings()

    @Test
    fun `values are clamped into the camera's ranges`() {
        val result = sanitize(
            ManualSettings(iso = 50_000, exposureTimeNs = 10L, exposureCompensation = 40),
            fullCamera(),
        )
        assertThat(result.iso).isEqualTo(3200)
        assertThat(result.exposureTimeNs).isEqualTo(100_000L)
        assertThat(result.exposureCompensation).isEqualTo(12)
    }

    @Test
    fun `unsupported controls fall back to automatic`() {
        val result = sanitize(
            ManualSettings(
                iso = 400,
                exposureTimeNs = 1_000_000L,
                exposureCompensation = 3,
                whiteBalance = WhiteBalance.Kelvin(5000),
            ),
            legacyCamera(),
        )
        assertThat(result).isEqualTo(ManualSettings.AUTO)
    }

    @Test
    fun `supported preset is kept, kelvin dropped without manual post processing`() {
        val cam = fullCamera().copy(supportsManualWhiteBalance = false)
        assertThat(sanitize(ManualSettings(whiteBalance = WhiteBalance.Preset(WhiteBalancePreset.CLOUDY)), cam).whiteBalance)
            .isEqualTo(WhiteBalance.Preset(WhiteBalancePreset.CLOUDY))
        assertThat(sanitize(ManualSettings(whiteBalance = WhiteBalance.Kelvin(5000)), cam).whiteBalance)
            .isEqualTo(WhiteBalance.Auto)
    }

    @Test
    fun `legacy camera disables every manual control`() {
        val cam = legacyCamera()
        ManualControl.entries.forEach { assertThat(cam.supports(it)).isFalse() }
    }
}
