package com.camoron.domain

import com.camoron.domain.model.ColourCastEstimator
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColourCastTest {

    private fun estimate(vararg pixels: Triple<Int, Int, Int>, copies: Int = 1000): FloatArray? {
        val estimator = ColourCastEstimator()
        repeat(copies) { pixels.forEach { (r, g, b) -> estimator.add(r, g, b) } }
        return estimator.gains()
    }

    @Test
    fun `a neutral picture is left alone`() {
        assertThat(estimate(Triple(120, 120, 120), Triple(200, 200, 200), Triple(60, 61, 59))).isNull()
    }

    @Test
    fun `a yellow indoor cast is partly taken out`() {
        val gains = estimate(Triple(150, 130, 100), Triple(190, 170, 135))!!
        assertThat(gains[2]).isGreaterThan(1f) // more blue
        assertThat(gains[0]).isLessThan(1f) // less red
        val r = 150 * gains[0]
        val b = 100 * gains[2]
        // Closer to grey, but not all the way: the room keeps some warmth.
        assertThat(r - b).isLessThan(50f)
        assertThat(r - b).isGreaterThan(5f)
    }

    @Test
    fun `brightness stays the same`() {
        val gains = estimate(Triple(150, 130, 100))!!
        val luma = 0.2126f * gains[0] + 0.7152f * gains[1] + 0.0722f * gains[2]
        assertThat(luma).isWithin(0.001f).of(1f)
    }

    @Test
    fun `a strongly coloured scene is not judged`() {
        // A green lawn and a red wall: no near-neutral pixels to go by.
        assertThat(estimate(Triple(60, 170, 50), Triple(200, 40, 40))).isNull()
    }

    @Test
    fun `highlights keep their colour`() {
        val gains = floatArrayOf(0.9f, 1f, 1.1f)
        val white = 0xFFFFFFFF.toInt()
        assertThat(ColourCastEstimator.correct(white, gains)).isEqualTo(white)
        val mid = 0xFF808080.toInt()
        assertThat(ColourCastEstimator.correct(mid, gains)).isNotEqualTo(mid)
    }
}
