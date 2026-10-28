package com.camoron.data.processing

import com.google.common.collect.Range
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

    /** The same texture as opaque grey pixels. */
    private fun greyTexture(width: Int, height: Int): IntArray =
        texture(width, height).map { v -> (0xFF shl 24) or (v shl 16) or (v shl 8) or v }.toIntArray()

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

    @Test
    fun `noise counts fully and big differences fade to nothing`() {
        val grey = (0xFF shl 24) or (120 shl 16) or (120 shl 8) or 120
        val noisy = (0xFF shl 24) or (130 shl 16) or (112 shl 8) or 125
        val edge = (0xFF shl 24) or (160 shl 16) or (160 shl 8) or 160
        val bright = (0xFF shl 24) or (230 shl 16) or (220 shl 8) or 210
        assertThat(MergeMath.weight(grey, grey)).isEqualTo(MergeMath.GHOST_THRESHOLD)
        assertThat(MergeMath.weight(grey, noisy)).isEqualTo(MergeMath.GHOST_THRESHOLD)
        assertThat(MergeMath.weight(grey, edge)).isIn(Range.open(0, MergeMath.GHOST_THRESHOLD))
        assertThat(MergeMath.weight(grey, bright)).isEqualTo(0)
    }

    @Test
    fun `a blurred frame is less sharp`() {
        val sharp = greyTexture(64, 64).map(MergeMath::luma).toIntArray()
        val blurred = IntArray(sharp.size) { i ->
            val x = i % 64
            val y = i / 64
            var sum = 0
            for (dy in -1..1) for (dx in -1..1) sum += sharp[(y + dy).coerceIn(0, 63) * 64 + (x + dx).coerceIn(0, 63)]
            sum / 9
        }
        assertThat(MergeMath.sharpness(sharp, 64, 64)).isGreaterThan(MergeMath.sharpness(blurred, 64, 64) * 1.5f)
    }

    @Test
    fun `tiles that drifted from the whole-frame shift are realigned`() {
        val width = 256
        val height = 128
        val ref = greyTexture(width, height)
        // The other frame matches the reference, but its right half sits 2 px further right, as when
        // the phone turns slightly; the whole-frame shift is right for the left half only.
        val other = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val sx = if (x >= width / 2) x - 2 else x
            ref[y * width + sx.coerceIn(0, width - 1)]
        }
        val merged = MergeMath.mergeBand(ref, width, height, listOf(MergeMath.Strip(other, width, height, 0, 0)), tile = 64)
        // Away from the seam, every merged pixel is the reference's own: nothing was blurred.
        var different = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (x in width / 2 - 4..width / 2 + 4) continue
            if (merged[y * width + x] != ref[y * width + x]) different++
        }
        assertThat(different).isEqualTo(0)
    }

    @Test
    fun `merging noisy frames brings the picture closer to the truth`() {
        val width = 128
        val height = 128
        val truth = texture(width, height)
        val random = Random(11)
        fun noisy() = IntArray(truth.size) { i ->
            val v = (truth[i] and 0xFF) + random.nextInt(-20, 21)
            val c = v.coerceIn(0, 255)
            (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        }
        val reference = noisy()
        val strips = List(6) { MergeMath.Strip(noisy(), width, height, 0, 0) }
        val merged = MergeMath.mergeBand(reference, width, height, strips, tile = 64)
        fun error(image: IntArray) = image.indices.sumOf { kotlin.math.abs((image[it] and 0xFF) - (truth[it] and 0xFF)) }
        assertThat(error(merged)).isLessThan(error(reference) * 6 / 10)
    }
}
