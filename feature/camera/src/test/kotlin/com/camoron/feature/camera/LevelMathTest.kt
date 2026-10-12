package com.camoron.feature.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LevelMathTest {

    @Test
    fun `upright phone reads zero`() {
        assertThat(LevelMath.roll(0f, 9.81f)).isWithin(0.01f).of(0f)
    }

    @Test
    fun `landscape is straight too`() {
        val roll = LevelMath.roll(-9.81f, 0f)!!
        assertThat(roll).isWithin(0.01f).of(-90f)
        assertThat(LevelMath.deviation(roll)).isWithin(0.01f).of(0f)
    }

    @Test
    fun `small tilt shows as deviation`() {
        assertThat(LevelMath.deviation(3f)).isWithin(0.01f).of(3f)
        assertThat(LevelMath.deviation(-88f)).isWithin(0.01f).of(2f)
    }

    @Test
    fun `flat phone has no reading`() {
        assertThat(LevelMath.roll(0.5f, 0.5f)).isNull()
    }
}
