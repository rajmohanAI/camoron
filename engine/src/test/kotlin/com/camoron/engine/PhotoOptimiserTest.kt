package com.camoron.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class PhotoOptimiserTest {

    /** A photo-like test picture: smooth shading, edges and fine texture, in colour. */
    private fun scene(w: Int, h: Int, seed: Int = 1): ArgbImage {
        val rnd = Random(seed)
        val texture = FloatArray(w * h) { rnd.nextFloat() }
        val img = ArgbImage(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val shade = 0.35f + 0.25f * sin(x / 37f) * sin(y / 53f)
                val edge = if ((x / 40 + y / 40) % 2 == 0) 0.15f else 0f
                val fine = 0.04f * (texture[y * w + x] - 0.5f) * (if (x > w / 2) 1f else 0f)
                val l = (shade + edge + fine).coerceIn(0f, 1f)
                img.pixels[y * w + x] = rgb(l * 1.02f, l, l * 0.98f)
            }
        }
        return img
    }

    private fun rgb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (Planes.byte(r) shl 16) or (Planes.byte(g) shl 8) or Planes.byte(b)

    private fun withNoise(img: ArgbImage, sigma: Float, seed: Int = 7): ArgbImage {
        val rnd = Random(seed)
        val out = ArgbImage(img.width, img.height)
        for (i in img.pixels.indices) {
            val n = (rnd.nextGaussian() * sigma).toFloat()
            val c = img.pixels[i]
            out.pixels[i] = rgb(
                ((c shr 16) and 0xFF) / 255f + n,
                ((c shr 8) and 0xFF) / 255f + n,
                (c and 0xFF) / 255f + n,
            )
        }
        return out
    }

    private fun Random.nextGaussian(): Double {
        val u = nextDouble().coerceAtLeast(1e-12)
        val v = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }

    private fun optimise(img: ArgbImage, options: OptimiseOptions = OptimiseOptions(threads = 2)): Pair<ArgbImage, OptimiseReport> {
        var out: ArgbImage? = null
        val report = PhotoOptimiser(options).optimise(img) { w, h -> ArgbImage(w, h).also { out = it } }
        return out!! to report
    }

    private fun meanAbsDiff(a: ArgbImage, b: ArgbImage): Float {
        var sum = 0.0
        for (i in a.pixels.indices) sum += abs(Planes.lumaOf(a.pixels[i]) - Planes.lumaOf(b.pixels[i]))
        return (sum / a.pixels.size).toFloat()
    }

    @Test
    fun `measures the noise a photo really has`() {
        val clean = scene(480, 360)
        assertThat(PhotoOptimiser().prepare(clean).first.noise * 255).isLessThan(1f)
        val noisy = withNoise(clean, 6f / 255f)
        assertThat(PhotoOptimiser().prepare(noisy).first.noise * 255).isWithin(1.5f).of(6f)
    }

    @Test
    fun `a clean photo comes back nearly untouched`() {
        val clean = scene(480, 360)
        val (out, report) = optimise(clean, OptimiseOptions(targetLongSide = 480, threads = 2))
        assertThat(report.plan.lumaDenoise).isEqualTo(0f)
        assertThat(report.plan.deblock).isEqualTo(0f)
        assertThat(report.plan.castGains).isNull()
        assertThat(report.plan.curve).isNull()
        assertThat(meanAbsDiff(clean, out) * 255).isLessThan(1.5f)
    }

    @Test
    fun `noise is reduced towards the clean picture, with some grain kept`() {
        val clean = scene(480, 360)
        val noisy = withNoise(clean, 6f / 255f)
        val (out, report) = optimise(noisy, OptimiseOptions(targetLongSide = 480, threads = 2))
        assertThat(report.plan.lumaDenoise).isGreaterThan(0f)
        assertThat(meanAbsDiff(clean, out)).isLessThan(meanAbsDiff(clean, noisy) * 0.85f)
        // Not scrubbed flat: the output still varies where the noisy input did.
        assertThat(PhotoOptimiser().prepare(out).first.noise).isGreaterThan(0.5f / 255f)
    }

    @Test
    fun `small photos are enlarged at most twice, large ones keep their size`() {
        val (small, smallReport) = optimise(scene(400, 300))
        assertThat(smallReport.plan.scale).isEqualTo(2f)
        assertThat(small.width).isEqualTo(800)
        assertThat(small.height).isEqualTo(600)

        val (_, fourK) = optimise(scene(1200, 900), OptimiseOptions(targetLongSide = 1800, threads = 2))
        assertThat(fourK.plan.scale).isWithin(0.001f).of(1.5f)

        val (same, keep) = optimise(scene(500, 400), OptimiseOptions(targetLongSide = 480, threads = 2))
        assertThat(keep.plan.scale).isEqualTo(1f)
        assertThat(same.width).isEqualTo(500)
    }

    @Test
    fun `enlarging adds no new picture, so shrunk back it matches the input`() {
        val input = scene(320, 240)
        val (big, _) = optimise(input, OptimiseOptions(targetLongSide = 640, threads = 2))
        val back = ArgbImage(320, 240)
        for (y in 0 until 240) for (x in 0 until 320) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val c = big.pixels[(2 * y + dy) * 640 + 2 * x + dx]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            back.pixels[y * 320 + x] = (0xFF shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
        }
        assertThat(meanAbsDiff(input, back) * 255).isLessThan(2.5f)
    }

    @Test
    fun `processing in blocks gives the same picture as processing it whole`() {
        val noisy = withNoise(scene(900, 500), 4f / 255f)
        val optimiser = PhotoOptimiser(OptimiseOptions(targetLongSide = 900, threads = 3))
        val (analysis, plan) = optimiser.prepare(noisy)
        val tiled = ArgbImage(900, 500)
        optimiser.run(noisy, analysis, plan, tiled)

        val whole = Planes.fromArgb(0, 0, 900, 500, noisy.pixels.copyOf())
        PhotoOptimiser.restore(whole, analysis, plan)
        Restoration.tone(whole, plan.castGains, plan.curve)
        assertThat(tiled.pixels.toList()).isEqualTo(whole.toArgb().toList())
    }

    @Test
    fun `strong JPEG-like blocking is found and smoothed`() {
        val clean = scene(480, 360)
        val blocky = ArgbImage(480, 360)
        // Each 8×8 block flattened to its average, as heavy compression does, with a little
        // variation left inside.
        val rnd = Random(3)
        for (by in 0 until 360 step 8) for (bx in 0 until 480 step 8) {
            var sum = 0f
            for (y in by until by + 8) for (x in bx until bx + 8) sum += Planes.lumaOf(clean.pixels[y * 480 + x])
            val v = sum / 64
            for (y in by until by + 8) for (x in bx until bx + 8) {
                val n = (rnd.nextFloat() - 0.5f) * 2f / 255f
                blocky.pixels[y * 480 + x] = rgb(v + n, v + n, v + n)
            }
        }
        val (_, report) = optimise(blocky, OptimiseOptions(targetLongSide = 480, threads = 2))
        assertThat(report.analysis.blockiness).isGreaterThan(1.2f)
        assertThat(report.plan.deblock).isGreaterThan(0f)
    }

    @Test
    fun `a clear colour cast is eased a third of the way, a faint one is left`() {
        val greyish = ArgbImage(400, 300)
        for (i in greyish.pixels.indices) {
            val l = 0.3f + 0.4f * ((i % 400) / 400f)
            greyish.pixels[i] = rgb(l, l, l * 1.14f)
        }
        val gains = PhotoOptimiser().prepare(greyish).first.cast
        assertThat(gains).isNotNull()
        assertThat(gains!![2]).isLessThan(1f)
        assertThat(gains[2]).isGreaterThan(0.9f)

        val faint = ArgbImage(400, 300)
        for (i in faint.pixels.indices) {
            val l = 0.3f + 0.4f * ((i % 400) / 400f)
            faint.pixels[i] = rgb(l, l, l * 1.03f)
        }
        assertThat(PhotoOptimiser().prepare(faint).first.cast).isNull()
    }

    @Test
    fun `the report says what was done in plain words`() {
        val (_, report) = optimise(withNoise(scene(400, 300), 5f / 255f))
        assertThat(report.notes.joinToString("\n")).contains("Reduced noise")
        assertThat(report.notes.joinToString("\n")).contains("no generated detail")
    }
}
