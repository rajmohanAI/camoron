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

    /** An n×n mask with solid squares at the given (x0, y0, x1, y1) cells, x1 and y1 exclusive. */
    private fun squares(n: Int, vararg boxes: IntArray): FloatArray = FloatArray(n * n) { i ->
        val x = i % n
        val y = i / n
        if (boxes.any { x >= it[0] && y >= it[1] && x < it[2] && y < it[3] }) 1f else 0f
    }

    @Test
    fun `isolate keeps only the tapped region and drops stray patches`() {
        val n = 20
        val mask = squares(n, intArrayOf(2, 2, 8, 8), intArrayOf(12, 12, 18, 18))
        val tapped = PortraitMath.isolate(mask, n, seed = PortraitMath.cellAt(0.75f, 0.75f, n))
        assertThat(tapped[15 * n + 15]).isEqualTo(1f)
        assertThat(tapped[5 * n + 5]).isEqualTo(0f)

        // Without a tap the larger region wins.
        val big = squares(n, intArrayOf(1, 1, 10, 10), intArrayOf(14, 14, 17, 17))
        val kept = PortraitMath.isolate(big, n)
        assertThat(kept[5 * n + 5]).isEqualTo(1f)
        assertThat(kept[15 * n + 15]).isEqualTo(0f)
    }

    @Test
    fun `isolate fills holes inside the subject`() {
        val n = 20
        val mask = squares(n, intArrayOf(3, 3, 17, 17))
        for (y in 8 until 12) for (x in 8 until 12) mask[y * n + x] = 0f
        val kept = PortraitMath.isolate(mask, n)
        assertThat(kept[10 * n + 10]).isEqualTo(1f)
        assertThat(kept[0]).isEqualTo(0f)
    }

    @Test
    fun `pick subject prefers the tapped one, then the nearest, then the big central one`() {
        val n = 20
        val left = squares(n, intArrayOf(1, 8, 5, 12))
        val right = squares(n, intArrayOf(12, 4, 19, 16))
        val subjects = listOf(left, right)
        assertThat(PortraitMath.pickSubject(subjects, n, PortraitMath.cellAt(0.15f, 0.5f, n))).isEqualTo(0)
        assertThat(PortraitMath.pickSubject(subjects, n, PortraitMath.cellAt(0.35f, 0.5f, n))).isEqualTo(0)
        assertThat(PortraitMath.pickSubject(subjects, n, null)).isEqualTo(1)
        assertThat(PortraitMath.pickSubject(emptyList(), n, null)).isNull()
    }

    @Test
    fun `a tap maps into the centre crop of a taller photo shape`() {
        // A 3:4 frame cropped to 9:16 keeps the middle 75% of its width.
        val inside = PortraitMath.focusInCrop(0.5f, 0.3f, 3f / 4, 9f / 16)!!
        assertThat(inside.x).isWithin(1e-4f).of(0.5f)
        assertThat(inside.y).isWithin(1e-4f).of(0.3f)
        val right = PortraitMath.focusInCrop(0.8f, 0.5f, 3f / 4, 9f / 16)!!
        assertThat(right.x).isWithin(1e-4f).of(0.9f)
        assertThat(PortraitMath.focusInCrop(0.95f, 0.5f, 3f / 4, 9f / 16)).isNull()
        assertThat(PortraitMath.focusInCrop(0.2f, 0.7f, 3f / 4, null)).isEqualTo(com.camoron.domain.model.ImagePoint(0.2f, 0.7f))
    }
}
