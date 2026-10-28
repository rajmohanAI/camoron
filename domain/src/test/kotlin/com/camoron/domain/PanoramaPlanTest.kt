package com.camoron.domain

import com.camoron.domain.model.PanoramaPlan
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI

class PanoramaPlanTest {

    @Test
    fun `an upright phone turns about a fifth of a right angle per frame`() {
        val step = PanoramaPlan.step(24f, zoom = 1f, portrait = true, longOverShort = 4f / 3)
        assertThat(Math.toDegrees(step.toDouble())).isWithin(1.0).of(23.0)
    }

    @Test
    fun `held sideways or zoomed in the step changes with the view`() {
        val upright = PanoramaPlan.step(24f, 1f, portrait = true, longOverShort = 4f / 3)
        assertThat(PanoramaPlan.step(24f, 1f, portrait = false, longOverShort = 4f / 3)).isGreaterThan(upright)
        assertThat(PanoramaPlan.step(24f, 2f, portrait = true, longOverShort = 4f / 3)).isLessThan(upright)
    }

    @Test
    fun `turns across north wrap around`() {
        assertThat(PanoramaPlan.wrap(-3.0f - 3.2f)).isWithin(1e-4f).of((2 * PI).toFloat() - 6.2f)
        assertThat(PanoramaPlan.wrap(3.2f - (-3.0f))).isWithin(1e-4f).of(6.2f - (2 * PI).toFloat())
        assertThat(PanoramaPlan.wrap(0.3f)).isEqualTo(0.3f)
    }
}
