package com.camoron.feature.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExposureScaleTest {

    @Test
    fun `iso slider ends hit the camera limits and midpoint is the geometric mean`() {
        val range = 100..6400
        assertThat(ExposureScale.fromSlider(0f, range)).isEqualTo(100)
        assertThat(ExposureScale.fromSlider(1f, range)).isEqualTo(6400)
        assertThat(ExposureScale.fromSlider(0.5f, range)).isEqualTo(800)
    }

    @Test
    fun `slider round trips`() {
        val range = 100_000L..1_000_000_000L
        val value = 4_000_000L
        assertThat(ExposureScale.fromSlider(ExposureScale.toSlider(value, range), range)).isEqualTo(value)
    }

    @Test
    fun `shutter speeds read like a camera`() {
        assertThat(ExposureScale.formatShutter(4_000_000L)).isEqualTo("1/250")
        assertThat(ExposureScale.formatShutter(500_000_000L)).isEqualTo("0.5s")
        assertThat(ExposureScale.formatShutter(2_000_000_000L)).isEqualTo("2s")
    }

    @Test
    fun `ev is shown in stops with sign`() {
        assertThat(ExposureScale.formatEv(3, 1f / 3)).isEqualTo("+1.0")
        assertThat(ExposureScale.formatEv(-2, 1f / 3)).isEqualTo("-0.7")
        assertThat(ExposureScale.formatEv(0, 1f / 3)).isEqualTo("0.0")
    }
}
