package com.camoron.data.processing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class RawMathTest {

    private val black = floatArrayOf(64f, 64f, 64f, 64f)

    /** Smooth random scene, 100..800, so alignment has something to lock on to. */
    private fun scene(width: Int, height: Int, seed: Int = 3): FloatArray {
        val random = Random(seed)
        val cell = 7
        val cw = width / cell + 2
        val coarse = FloatArray(cw * (height / cell + 2)) { 100f + random.nextFloat() * 700f }
        return FloatArray(width * height) { i ->
            val gx = (i % width).toFloat() / cell
            val gy = (i / width).toFloat() / cell
            val x0 = gx.toInt()
            val y0 = gy.toInt()
            val fx = gx - x0
            val fy = gy - y0
            fun c(x: Int, y: Int) = coarse[y * cw + x]
            val top = c(x0, y0) + (c(x0 + 1, y0) - c(x0, y0)) * fx
            val bottom = c(x0, y0 + 1) + (c(x0 + 1, y0 + 1) - c(x0, y0 + 1)) * fx
            top + (bottom - top) * fy
        }
    }

    /** The scene seen by a frame moved [dx], [dy] pixels, with sensor noise, black level added. */
    private fun frame(scene: FloatArray, width: Int, height: Int, dx: Int, dy: Int, seed: Int, noise: Float = 20f): ShortArray {
        val random = Random(seed)
        return ShortArray(width * height) { i ->
            val x = (i % width + dx).coerceIn(0, width - 1)
            val y = (i / width + dy).coerceIn(0, height - 1)
            (scene[y * width + x] + 64 + (random.nextFloat() * 2 - 1) * noise * 1.7f).toInt().coerceIn(0, 1023).toShort()
        }
    }

    @Test
    fun `the blend windows of neighbouring tiles add up to one`() {
        val half = RawMath.TILE / 2
        for (i in 0 until half) {
            assertThat(RawMath.window[i] + RawMath.window[i + half]).isWithin(1e-5f).of(1f)
        }
    }

    @Test
    fun `finds how far a frame moved, in blocks`() {
        val width = 256
        val height = 192
        val truth = scene(width, height)
        val ref = RawMath.blockLuma(frame(truth, width, height, 0, 0, seed = 1), width, height, black)
        val alt = RawMath.blockLuma(frame(truth, width, height, 10, -6, seed = 2), width, height, black)
        val shift = RawMath.globalShift(ref, alt, width / 2, height / 2)
        // The frame moved right and up, so the reference's pixels sit left and down in it.
        assertThat(shift.dx).isEqualTo(-5)
        assertThat(shift.dy).isEqualTo(3)
    }

    @Test
    fun `merging aligned noisy frames brings the picture closer to the truth`() {
        val width = 128
        val height = 128
        val truth = scene(width, height)
        val shifts = listOf(0 to 0, 4 to 2, -2 to 6, 6 to -4)
        val frames = shifts.mapIndexed { i, (dx, dy) -> frame(truth, width, height, dx, dy, seed = 10 + i) }
        val lumas = frames.map { RawMath.blockLuma(it, width, height, black) }
        val merge = RawMath.Merge(frames[0], lumas[0], width, height, black, 959f, 2e-3f, 1e-4f)
        for (index in 1 until frames.size) {
            val global = RawMath.globalShift(lumas[0], lumas[index], width / 2, height / 2)
            val alignment = RawMath.Alignment(IntArray(merge.grid.columns * merge.grid.rows), IntArray(merge.grid.columns * merge.grid.rows))
            for (row in 0 until merge.grid.rows) RawMath.alignTiles(lumas[0], lumas[index], merge.grid, global, row, alignment)
            for (row in 0 until merge.grid.rows) merge.add(frames[index], lumas[index], alignment, row)
        }
        val merged = merge.result()

        // Compare away from the edges, where shifted frames repeat their border.
        fun error(values: (Int) -> Float): Double {
            var sum = 0.0
            for (y in 16 until height - 16) for (x in 16 until width - 16) sum += abs(values(y * width + x) - truth[y * width + x])
            return sum
        }
        val single = error { frames[0][it] - 64f }
        val combined = error { merged[it] }
        assertThat(combined).isLessThan(single * 0.7)
    }

    @Test
    fun `a block that moved is left as the reference shows it`() {
        val merge = RawMath.Merge(ShortArray(16), IntArray(4), 4, 4, black, 959f, 2e-4f, 2e-6f)
        assertThat(merge.similarity(0.001f, 0.2f)).isEqualTo(1f)
        assertThat(merge.similarity(0.3f, 0.2f)).isEqualTo(0f)
    }

    @Test
    fun `a flat grey frame develops to flat grey`() {
        val width = 8
        val height = 8
        val raw = FloatArray(width * height) { 0.2f }
        val develop = RawMath.Develop(
            raw, width, height,
            pattern = intArrayOf(0, 1, 1, 2),
            range = 1f,
            whiteBalance = floatArrayOf(1f, 1f, 1f),
            matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            shading = null,
        )
        develop.normalise(0 until height)
        val out = IntArray(width)
        for (y in 0 until height) {
            develop.row(y, out)
            val first = out[0]
            assertThat(out.toList()).containsExactlyElementsIn(List(width) { first })
            assertThat((first shr 16) and 0xFF).isEqualTo(first and 0xFF)
            assertThat((first shr 8) and 0xFF).isEqualTo(first and 0xFF)
        }
    }

    @Test
    fun `demosaicing recovers a colour from its filter`() {
        // A uniform orange scene seen through an RGGB filter.
        val width = 8
        val height = 8
        val colour = floatArrayOf(0.8f, 0.4f, 0.1f)
        val pattern = intArrayOf(0, 1, 1, 2)
        val raw = FloatArray(width * height) { i -> colour[pattern[((i / width) and 1) * 2 + ((i % width) and 1)]] }
        val develop = RawMath.Develop(raw, width, height, pattern, 1f, floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), null)
        val rgb = FloatArray(3)
        for ((x, y) in listOf(2 to 2, 3 to 2, 2 to 3, 3 to 3)) {
            develop.demosaic(x, y, rgb)
            assertThat(rgb[0]).isWithin(1e-4f).of(0.8f)
            assertThat(rgb[1]).isWithin(1e-4f).of(0.4f)
            assertThat(rgb[2]).isWithin(1e-4f).of(0.1f)
        }
    }

    @Test
    fun `lens shading is interpolated between its grid points`() {
        // Two by two points: gains 1 on the left, 3 on the right, the same for every colour.
        val shading = RawMath.Shading(2, 2, floatArrayOf(1f, 1f, 1f, 3f, 3f, 3f, 1f, 1f, 1f, 3f, 3f, 3f))
        assertThat(shading.gain(0, 0, 0, 101, 11)).isWithin(1e-5f).of(1f)
        assertThat(shading.gain(2, 100, 10, 101, 11)).isWithin(1e-5f).of(3f)
        assertThat(shading.gain(1, 50, 5, 101, 11)).isWithin(1e-5f).of(2f)
    }

    @Test
    fun `the tone curve runs from black to white and never goes back`() {
        val curve = RawMath.toneCurve()
        assertThat(curve.first()).isEqualTo(0)
        assertThat(curve.last()).isEqualTo(255)
        for (i in 1 until curve.size) assertThat(curve[i]).isAtLeast(curve[i - 1])
        // Mid grey (18%) lands near the middle, as in camera photos.
        assertThat(curve[(0.18 * (curve.size - 1)).toInt()]).isIn(com.google.common.collect.Range.closed(100, 135))
    }
}
