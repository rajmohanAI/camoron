package com.camoron.data.processing

import com.camoron.domain.model.YuvFrame
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class BurstMergerTest {

    private val w = 384
    private val h = 256

    /** A textured scene: smooth gradients plus a few hard-edged shapes, so alignment has something to lock on. */
    private fun scene(x: Int, y: Int): Int {
        val base = 60 + (x * 90 / w) + (y * 50 / h)
        val square = if ((x / 48 + y / 48) % 2 == 0) 40 else 0
        val ring = if (((x - 200) * (x - 200) + (y - 120) * (y - 120)) in 900..1600) 50 else 0
        return (base + square + ring).coerceIn(0, 255)
    }

    private fun frame(shiftX: Int, shiftY: Int, noise: Double, seed: Long, overlay: ((Int, Int) -> Int?)? = null): YuvFrame {
        val random = Random(seed)
        val y = ByteArray(w * h) { i ->
            val px = i % w + shiftX
            val py = i / w + shiftY
            val clean = overlay?.invoke(i % w, i / w) ?: scene(px.coerceIn(0, w - 1), py.coerceIn(0, h - 1))
            (clean + random.nextGaussian() * noise).toInt().coerceIn(0, 255).toByte()
        }
        val c = ((w + 1) / 2) * ((h + 1) / 2)
        val u = ByteArray(c) { (128 + random.nextGaussian() * noise).toInt().coerceIn(0, 255).toByte() }
        val v = ByteArray(c) { (128 + random.nextGaussian() * noise).toInt().coerceIn(0, 255).toByte() }
        return YuvFrame(w, h, y, u, v)
    }

    /** Deviation of [plane] from the clean scene, away from the borders. */
    private fun error(plane: ByteArray): Double {
        var sum = 0.0
        var n = 0
        for (y in 20 until h - 20) for (x in 20 until w - 20) {
            val d = (plane[y * w + x].toInt() and 0xFF) - scene(x, y)
            sum += d * d
            n++
        }
        return sqrt(sum / n)
    }

    @Test
    fun `a clean burst merges only a few frames`() = runBlocking {
        val frames = (0 until 6).map { frame(0, 0, noise = 0.3, seed = it.toLong()) }
        val result = BurstMerger.merge(frames, Dispatchers.Default)
        assertThat(result.framesUsed).isEqualTo(3)
        assertThat(BurstMerger.framesFor(1.5f)).isEqualTo(4)
        assertThat(BurstMerger.framesFor(5f)).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `merging a shaky burst cuts noise and keeps the picture in place`() = runBlocking {
        val shifts = listOf(0 to 0, 3 to -2, -4 to 1, 2 to 5, -1 to -3, 5 to 2)
        val frames = shifts.mapIndexed { i, (dx, dy) -> frame(dx, dy, noise = 10.0, seed = i.toLong()) }
        val result = BurstMerger.merge(frames, Dispatchers.Default)

        assertThat(result.framesUsed).isEqualTo(6)
        assertThat(result.noise.toDouble()).isWithin(3.0).of(10.0)
        // The reference is one of the first three; measure against it in its own position.
        val (rx, ry) = shifts[result.reference]
        val single = error(frames[result.reference].y.shifted(-rx, -ry))
        val merged = error(result.frame.y.shifted(-rx, -ry))
        assertThat(merged).isLessThan(single * 0.6)
    }

    @Test
    fun `something that moved is taken from the reference instead of ghosting`() = runBlocking {
        // A bright block that is only in frames 1..3, as if a hand passed through after the first frame.
        val block = { x: Int, y: Int -> if (x in 150..230 && y in 80..160) 250 else null }
        val frames = listOf(frame(0, 0, 3.0, 1)) + (1..3).map { frame(0, 0, 3.0, it + 1L, overlay = block) }
        // Make frame 0 clearly the sharpest so it is the reference.
        val result = BurstMerger.merge(frames, Dispatchers.Default)
        val reference = frames[result.reference]
        val referenceHasBlock = (reference.y[120 * w + 190].toInt() and 0xFF) > 230
        val mergedValue = result.frame.y[120 * w + 190].toInt() and 0xFF
        // Whatever the reference shows, the merge shows the same, not a half-bright blend.
        if (referenceHasBlock) assertThat(mergedValue).isGreaterThan(230) else assertThat(mergedValue).isLessThan(200)
    }

    @Test
    fun `alignment finds a known shift`() {
        val ref = frame(0, 0, 0.0, 1)
        val moved = frame(4, -3, 0.0, 2)
        val refPlane = BurstMerger.Plane(ref.y, w, h)
        val quarter = BurstMerger.downsample(refPlane, 4)
        val alignment = BurstMerger.align(
            refPlane, quarter, BurstMerger.downsample(quarter, 4),
            moved, BurstMerger.downsample(BurstMerger.Plane(moved.y, w, h), 4), reject = 20f,
        )
        // Centre tile: content of the reference at x is at x - 4 in the moved frame.
        val tilesX = (w + BurstMerger.TILE - 1) / BurstMerger.TILE
        val centre = 1 * tilesX + 1
        assertThat(alignment.dx[centre]).isEqualTo(-4)
        assertThat(alignment.dy[centre]).isEqualTo(3)
    }

    @Test
    fun `a single frame comes back unchanged`() = runBlocking {
        val only = frame(0, 0, 5.0, 3)
        val result = BurstMerger.merge(listOf(only), Dispatchers.Default)
        assertThat(result.frame).isSameInstanceAs(only)
        assertThat(result.framesUsed).isEqualTo(1)
    }

    @Test
    fun `grey and primary colours convert as the JPEG standard defines`() {
        fun convert(y: Int, u: Int, v: Int): Int {
            val f = YuvFrame(2, 2, ByteArray(4) { y.toByte() }, byteArrayOf(u.toByte()), byteArrayOf(v.toByte()))
            val out = IntArray(4)
            YuvMath.toArgb(f, 0, 0, 2, 2, out)
            return out[3] and 0xFFFFFF
        }
        assertThat(convert(128, 128, 128)).isEqualTo(0x808080)
        // Pure red in full-range BT.601: Y 76, U 85, V 255.
        val red = convert(76, 85, 255)
        assertThat((red shr 16) and 0xFF).isAtLeast(250)
        assertThat((red shr 8) and 0xFF).isAtMost(5)
        assertThat(red and 0xFF).isAtMost(5)
    }

    /** Plane content moved by (dx, dy), edges clamped, for comparing against the unshifted scene. */
    private fun ByteArray.shifted(dx: Int, dy: Int): ByteArray = ByteArray(size) { i ->
        val x = (i % w - dx).coerceIn(0, w - 1)
        val y = (i / w - dy).coerceIn(0, h - 1)
        this[y * w + x]
    }
}
