package com.camoron.data.processing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PortraitMathTest {

    @Test
    fun `box blur keeps a flat image flat and spreads an edge`() {
        val flat = FloatArray(20 * 10) { 0.4f }
        assertThat(PortraitMath.boxBlur(flat, 20, 10, 3).all { kotlin.math.abs(it - 0.4f) < 1e-5f }).isTrue()

        val edge = FloatArray(20 * 10) { if (it % 20 < 10) 0f else 1f }
        val blurred = PortraitMath.boxBlur(edge, 20, 10, 2)
        assertThat(blurred[5 * 20 + 9]).isWithin(1e-5f).of(0.4f)
        assertThat(blurred[5 * 20 + 2]).isEqualTo(0f)
        assertThat(blurred[5 * 20 + 17]).isWithin(1e-5f).of(1f)
    }

    @Test
    fun `rotating a square grid four quarter turns gives it back`() {
        val grid = FloatArray(9) { it.toFloat() }
        // 0 1 2 / 3 4 5 / 6 7 8 turned clockwise is 6 3 0 / 7 4 1 / 8 5 2.
        assertThat(PortraitMath.rotateSquare(grid, 3, 90).toList()).containsExactly(6f, 3f, 0f, 7f, 4f, 1f, 8f, 5f, 2f).inOrder()
        assertThat(PortraitMath.rotateSquare(PortraitMath.rotateSquare(grid, 3, 90), 3, -90).toList()).isEqualTo(grid.toList())
        assertThat(PortraitMath.rotateSquare(grid, 3, 180).toList()).isEqualTo(grid.reversed().toList())
    }

    @Test
    fun `person probability is one minus the background share`() {
        // Background scores high in the first cell and low in the second.
        val logits = floatArrayOf(5f, 0f, 0f, 0f, 0f, 0f, -5f, 0f, 0f, 3f, 0f, 0f)
        val p = PortraitMath.personProbability(logits, 2, 6)
        assertThat(p[0]).isLessThan(0.1f)
        assertThat(p[1]).isGreaterThan(0.95f)
    }

    @Test
    fun `refined mask is sharp and sits on the photo's edge`() {
        // The person fills the left half of the photo; the model's grid is soft about the edge.
        val w = 512
        val h = 32
        val guide = FloatArray(w * h) { if (it % w < 256) 0.9f else 0.1f }
        val size = 128
        val probability = FloatArray(size * size) { val x = it % size; (0.5f - (x - 63.5f) / 6f).coerceIn(0f, 1f) }
        val mask = PortraitMath.refineMask(probability, size, guide, w, h)
        assertThat(mask[16 * w + 100]).isGreaterThan(0.99f)
        assertThat(mask[16 * w + 400]).isLessThan(0.01f)
        assertThat(mask[16 * w + 250]).isGreaterThan(0.9f)
        assertThat(mask[16 * w + 262]).isLessThan(0.1f)
    }

    @Test
    fun `background blur does not pull the person's colour into the background`() {
        val w = 40
        val h = 10
        // A bright person on the left, a dark background on the right.
        val plane = FloatArray(w * h) { if (it % w < 20) 1f else 0.1f }
        val mask = FloatArray(w * h) { if (it % w < 20) 1f else 0f }
        val blurred = PortraitMath.blurBackground(arrayOf(plane), mask, w, h, 6)[0]
        assertThat(blurred[5 * w + 21]).isWithin(1e-4f).of(0.1f)
        assertThat(blurred[5 * w + 35]).isWithin(1e-4f).of(0.1f)
    }
}
