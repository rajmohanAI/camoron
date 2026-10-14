package com.camoron.data.processing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class DetailMathTest {

    private val identityLut = IntArray(256) { it }

    @Test
    fun `noise estimate finds the grain in a flat grey picture`() {
        val random = Random(1)
        val sigma = 6.0
        val luma = ByteArray(400 * 300) { (128 + random.nextGaussian() * sigma).toInt().coerceIn(0, 255).toByte() }
        val noise = DetailMath.estimateNoise(luma, 400, 300) * 255
        assertThat(noise.toDouble()).isWithin(1.5).of(sigma)
    }

    @Test
    fun `noise estimate is near zero for a clean picture with strong edges`() {
        // Clean vertical stripes: lots of edges, no noise.
        val luma = ByteArray(400 * 300) { i -> (if ((i % 400) / 20 % 2 == 0) 40 else 210).toByte() }
        assertThat(DetailMath.estimateNoise(luma, 400, 300) * 255).isLessThan(1f)
    }

    @Test
    fun `box filter averages its window and clamps at the edges`() {
        val w = 5
        val h = 4
        val src = FloatArray(w * h) { it.toFloat() }
        val out = FloatArray(w * h)
        DetailMath.boxFilter(src, w, h, 1, FloatArray(w * h), out)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            for (dy in -1..1) for (dx in -1..1) {
                sum += src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
            }
            assertThat(out[y * w + x]).isWithin(1e-3f).of(sum / 9)
        }
    }

    @Test
    fun `grain is smoothed away while an edge stays sharp`() {
        val w = 120
        val h = 80
        val random = Random(7)
        val clean = IntArray(w * h) { i -> if (i % w < w / 2) 70 else 180 }
        val noisy = IntArray(w * h) { i -> (clean[i] + random.nextGaussian() * 8).toInt().coerceIn(0, 255) }
        val pixels = IntArray(w * h) { grey(noisy[it]) }
        val luma = ByteArray(w * h) { noisy[it].toByte() }
        val tuning = DetailTuning(DetailMath.estimateNoise(luma, w, h))
        val out = IntArray(w * h)
        DetailMath.processBand(
            pixels, w, h, 0, h, tuning, identityLut, 0f,
            localMean = { _, _ -> 128f }, work = DetailMath.Workspace(w * h), out = out,
        )

        // Flat areas away from the edge: much less grain than before.
        fun deviation(values: IntArray): Double {
            val flat = (0 until w * h).filter { val x = it % w; x in 10 until w / 2 - 10 }.map { values[it].toDouble() }
            val mean = flat.average()
            return sqrt(flat.sumOf { (it - mean) * (it - mean) } / flat.size)
        }
        val before = deviation(noisy)
        val after = deviation(IntArray(w * h) { out[it] and 0xFF })
        assertThat(after).isLessThan(before * 0.5)

        // The edge is still a step: two pixels either side differ by nearly the full contrast.
        val row = h / 2
        val left = out[row * w + w / 2 - 2] and 0xFF
        val right = out[row * w + w / 2 + 1] and 0xFF
        assertThat(right - left).isGreaterThan(90)
    }

    @Test
    fun `colour speckles are removed`() {
        val w = 80
        val h = 60
        val random = Random(3)
        val pixels = IntArray(w * h) {
            val r = (120 + random.nextGaussian() * 10).toInt().coerceIn(0, 255)
            val b = (120 + random.nextGaussian() * 10).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (120 shl 8) or b
        }
        val out = IntArray(w * h)
        DetailMath.processBand(
            pixels, w, h, 0, h, DetailTuning(0.03f), identityLut, 0f,
            localMean = { _, _ -> 128f }, work = DetailMath.Workspace(w * h), out = out,
        )
        fun spread(values: IntArray) = values.map { abs(((it shr 16) and 0xFF) - (it and 0xFF)) }.average()
        assertThat(spread(out)).isLessThan(spread(pixels) * 0.4)
    }

    @Test
    fun `a band with halo rows matches processing the whole picture`() {
        val w = 64
        val h = 96
        val random = Random(11)
        val pixels = IntArray(w * h) { grey((100 + random.nextGaussian() * 20 + (it % w)).toInt().coerceIn(0, 255)) }
        val tuning = DetailTuning(0.02f)
        val whole = IntArray(w * h)
        DetailMath.processBand(pixels, w, h, 0, h, tuning, identityLut, 0f, { _, _ -> 128f }, DetailMath.Workspace(w * h), whole)

        val coreTop = 40
        val coreBottom = 60
        val top = coreTop - tuning.halo
        val bottom = coreBottom + tuning.halo
        val band = pixels.copyOfRange(top * w, bottom * w)
        val out = IntArray(w * (coreBottom - coreTop))
        DetailMath.processBand(
            band, w, bottom - top, coreTop - top, coreBottom - top, tuning, identityLut, 0f,
            { _, _ -> 128f }, DetailMath.Workspace(band.size), out,
        )
        for (i in out.indices) {
            assertThat(abs((out[i] and 0xFF) - (whole[coreTop * w + i] and 0xFF))).isAtMost(1)
        }
    }

    @Test
    fun `noisy and zoomed photos are sharpened less`() {
        assertThat(DetailTuning(0.03f).sharpen).isLessThan(DetailTuning(0.005f).sharpen)
        assertThat(DetailTuning(0.005f, zoom = 4f).sharpen).isLessThan(DetailTuning(0.005f).sharpen)
        assertThat(DetailTuning(0.05f).clarity).isEqualTo(0f)
    }

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun `clean photos skip the smoothing`() {
        assertThat(DetailTuning(noise = 0.5f / 255).denoise).isFalse()
        assertThat(DetailTuning(noise = 4f / 255).denoise).isTrue()
    }
}
