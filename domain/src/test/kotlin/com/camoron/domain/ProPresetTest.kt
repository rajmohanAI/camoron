package com.camoron.domain

import com.camoron.domain.model.LiveExposure
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.ProPreset
import com.camoron.domain.model.WhiteBalance
import com.camoron.domain.model.WhiteBalancePreset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProPresetTest {
    // ISO 400 at 1/100 s: an ordinary indoor scene.
    private val live = LiveExposure(iso = 400, exposureTimeNs = 10_000_000L)
    private val tweaked = ManualSettings(exposureCompensation = 3, whiteBalance = WhiteBalance.Preset(WhiteBalancePreset.CLOUDY))

    @Test
    fun `auto clears everything`() {
        assertThat(ProPreset.AUTO.settings(fullCamera(), live, tweaked)).isEqualTo(ManualSettings.AUTO)
    }

    @Test
    fun `program keeps exposure automatic but the user's compensation and white balance`() {
        val s = ProPreset.PROGRAM.settings(fullCamera(), live, tweaked)
        assertThat(s.isManualExposure).isFalse()
        assertThat(s.exposureCompensation).isEqualTo(3)
        assertThat(s.whiteBalance).isEqualTo(tweaked.whiteBalance)
    }

    @Test
    fun `shutter priority fixes 1 over 250 s and matches the scene's brightness with ISO`() {
        val s = ProPreset.SHUTTER.settings(fullCamera(), live, tweaked)
        assertThat(s.exposureTimeNs).isEqualTo(ProPreset.SHUTTER_PRIORITY_NS)
        // 400 × 1/100 = ISO × 1/250 → ISO 1000.
        assertThat(s.iso).isEqualTo(1000)
    }

    @Test
    fun `manual holds the current exposure`() {
        val s = ProPreset.MANUAL.settings(fullCamera(), live, tweaked)
        assertThat(s.iso).isEqualTo(400)
        assertThat(s.exposureTimeNs).isEqualTo(10_000_000L)
    }

    @Test
    fun `landscape uses the lowest ISO with a slower shutter, in daylight colour`() {
        val s = ProPreset.LANDSCAPE.settings(fullCamera(), live, tweaked)
        assertThat(s.iso).isEqualTo(100)
        assertThat(s.exposureTimeNs).isEqualTo(40_000_000L)
        assertThat(s.whiteBalance).isEqualTo(WhiteBalance.Preset(WhiteBalancePreset.DAYLIGHT))
    }

    @Test
    fun `sports freezes motion and night is a stop brighter, within the camera's ISO range`() {
        val sports = ProPreset.SPORTS.settings(fullCamera(), live, tweaked)
        assertThat(sports.exposureTimeNs).isEqualTo(ProPreset.SPORTS_NS)
        assertThat(sports.iso).isEqualTo(3200) // 4000 wanted, clamped to the top of the range
        val night = ProPreset.NIGHT.settings(fullCamera(), live, tweaked)
        assertThat(night.exposureTimeNs).isEqualTo(ProPreset.NIGHT_NS)
        assertThat(night.iso).isEqualTo(100) // 400 × 1/100 × 2 / (1/8) = 64, clamped up to 100
    }

    @Test
    fun `portrait brightens by about a third of a stop`() {
        val s = ProPreset.PORTRAIT.settings(fullCamera(), live, tweaked)
        assertThat(s.isManualExposure).isFalse()
        assertThat(s.exposureCompensation).isEqualTo(2) // 0.33 EV in 1/6 EV steps
    }

    @Test
    fun `cameras without manual sensor control fall back to automatic exposure`() {
        for (preset in listOf(ProPreset.SHUTTER, ProPreset.MANUAL, ProPreset.SPORTS, ProPreset.NIGHT)) {
            assertThat(preset.settings(legacyCamera(), live, tweaked).isManualExposure).isFalse()
        }
    }
}
