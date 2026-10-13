package com.camoron.data.processing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class MergeMathTest {

    /** Smooth-ish random texture so alignment has something to lock on to. */
    private fun texture(width: Int, height: Int, seed: Int = 7): IntArray {
        val random = Random(seed)
        val coarse = IntArray((width / 4 + 2) * (height / 4 + 2)) { random.nextInt(256) }
        val cw = width / 4 + 2
        return IntArray(width * height) { i -> coarse[(i / width / 4) * cw + (i % width) / 4] }
    }

    private fun shifted(source: IntArray, width: Int, height: Int, dx: Int, dy: Int): IntArray =
        IntArray(width * height) { i ->
            val x = i % width - dx
            val y = i / width - dy
            if (x in 0 until width && y in 0 until height) source[y * width + x] else 0
        }

    @Test
    fun `finds the shift between two frames`() {
        val ref = texture(96, 72)
        val moved = shifted(ref, 96, 72, dx = 5, dy = -3)
        val offset = MergeMath.bestOffset(ref, 96, 72, moved, 96, 72, baseX = 0, baseY = 0, radius = 8, step = 1)
        assertThat(offset.dx).isEqualTo(5)
        assertThat(offset.dy).isEqualTo(-3)
        assertThat(offset.meanError).isLessThan(1f)
    }

    @Test
    fun `refines around a coarse estimate inside a larger window`() {
        val ref = texture(64, 64)
        // The image is the reference padded by 10 px and moved a further (2, 1).
        val padded = IntArray(84 * 84)
        for (y in 0 until 64) for (x in 0 until 64) padded[(y + 10 + 1) * 84 + (x + 10 + 2)] = ref[y * 64 + x]
        val offset = MergeMath.bestOffset(ref, 64, 64, padded, 84, 84, baseX = 10, baseY = 10, radius = 4, step = 1)
        assertThat(offset.dx).isEqualTo(12)
        assertThat(offset.dy).isEqualTo(11)
    }

    @Test
    fun `moved pixels are rejected but noise is not`() {
        val grey = (0xFF shl 24) or (120 shl 16) or (120 shl 8) or 120
        val noisy = (0xFF shl 24) or (130 shl 16) or (112 shl 8) or 125
        val bright = (0xFF shl 24) or (230 shl 16) or (220 shl 8) or 210
        assertThat(MergeMath.similar(grey, noisy)).isTrue()
        assertThat(MergeMath.similar(grey, bright)).isFalse()
    }

    @Test
    fun `average rounds and stays opaque`() {
        val px = MergeMath.average(sumR = 301, sumG = 0, sumB = 765, count = 3)
        assertThat((px shr 16) and 0xFF).isEqualTo(100)
        assertThat(px and 0xFF).isEqualTo(255)
        assertThat(px ushr 24).isEqualTo(0xFF)
    }

    @Test
    fun `luma weights green most`() {
        assertThat(MergeMath.luma(0xFF00FF00.toInt())).isGreaterThan(MergeMath.luma(0xFFFF0000.toInt()))
        assertThat(MergeMath.luma(0xFFFFFFFF.toInt())).isAtLeast(254)
    }
}
