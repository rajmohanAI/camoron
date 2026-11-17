package com.camoron.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What the engine measured in a photo, before deciding what (if anything) to correct. Every
 * figure is from the photo itself; nothing is assumed about the camera that took it.
 */
data class Analysis(
    val width: Int,
    val height: Int,
    /** Brightness noise, as a standard deviation from 0 to 1, measured in the flattest areas. */
    val noise: Float,
    /** Colour noise, the same way. */
    val chromaNoise: Float,
    /** How much stronger JPEG's 8×8 block edges are than the picture around them (1 is none). */
    val blockiness: Float,
    /** Red and blue scale against green that lines up colour fringes, 1 when there are none. */
    val redScale: Float,
    val blueScale: Float,
    /** Per-channel gains that would remove a colour cast, or null when there is no clear cast. */
    val cast: FloatArray?,
    /** Brightness percentiles: darkest 0.5%, median, brightest 0.5%. */
    val shadows: Float,
    val median: Float,
    val highlights: Float,
)

internal object Analyser {

    private const val TILE = 160
    private const val BLOCK = 16

    fun analyse(source: PixelSource): Analysis {
        val w = source.width
        val h = source.height
        val tiles = sampleTiles(w, h, TILE, columns = 4, rows = 3).map { (x, y, tw, th) ->
            Planes.fromArgb(x, y, tw, th, source.read(x, y, tw, th))
        }
        val noise = flatNoise(tiles) { it.luma }
        val chroma = (flatNoise(tiles) { it.cb } + flatNoise(tiles) { it.cr }) / 2f
        val blockiness = blockiness(tiles)
        val (redScale, blueScale) = fringing(source)
        val stats = Histogram.sample(source)
        return Analysis(
            width = w,
            height = h,
            noise = noise,
            chromaNoise = chroma,
            blockiness = blockiness,
            redScale = redScale,
            blueScale = blueScale,
            cast = stats.cast,
            shadows = stats.percentile(0.005f),
            median = stats.percentile(0.5f),
            highlights = stats.percentile(0.995f),
        )
    }

    /** Positions of [columns]×[rows] tiles of up to [size] spread evenly over the photo. */
    fun sampleTiles(w: Int, h: Int, size: Int, columns: Int, rows: Int): List<IntArray> {
        val tw = min(size, w)
        val th = min(size, h)
        val out = mutableListOf<IntArray>()
        for (j in 0 until rows) {
            for (i in 0 until columns) {
                val x = ((w - tw) * (i + 0.5f) / columns).toInt().coerceIn(0, w - tw)
                val y = ((h - th) * (j + 0.5f) / rows).toInt().coerceIn(0, h - th)
                out += intArrayOf(x, y, tw, th)
            }
        }
        return out.distinctBy { it[0] to it[1] }
    }

    /**
     * Immerkær's noise estimate per 16×16 block, keeping the quieter quarter of blocks: those are
     * the flat areas, where what varies is noise rather than detail.
     */
    fun flatNoise(tiles: List<Planes>, plane: (Planes) -> FloatArray): Float {
        val estimates = mutableListOf<Float>()
        for (t in tiles) {
            val v = plane(t)
            for (by in 1 until t.height - BLOCK step BLOCK) {
                for (bx in 1 until t.width - BLOCK step BLOCK) {
                    var sum = 0.0
                    for (y in by until by + BLOCK - 2) {
                        for (x in bx until bx + BLOCK - 2) {
                            fun p(dx: Int, dy: Int) = v[(y + dy) * t.width + x + dx]
                            val r = p(-1, -1) - 2 * p(0, -1) + p(1, -1) -
                                2 * p(-1, 0) + 4 * p(0, 0) - 2 * p(1, 0) +
                                p(-1, 1) - 2 * p(0, 1) + p(1, 1)
                            sum += abs(r)
                        }
                    }
                    val n = (BLOCK - 2) * (BLOCK - 2)
                    estimates += (sqrt(PI / 2) * sum / (6.0 * n)).toFloat()
                }
            }
        }
        if (estimates.isEmpty()) return 0f
        estimates.sort()
        return estimates[estimates.size / 4]
    }

    /** Average step across JPEG's 8-pixel grid lines over the average step elsewhere. */
    fun blockiness(tiles: List<Planes>): Float {
        var onGrid = 0.0
        var onCount = 0
        var offGrid = 0.0
        var offCount = 0
        for (t in tiles) {
            for (y in 0 until t.height) {
                for (x in 1 until t.width) {
                    val d = abs(t.luma[y * t.width + x] - t.luma[y * t.width + x - 1])
                    // Only gentle areas: strong edges say nothing about compression.
                    if (d > 0.08f) continue
                    if ((t.x + x) % 8 == 0) {
                        onGrid += d
                        onCount++
                    } else {
                        offGrid += d
                        offCount++
                    }
                }
            }
            for (y in 1 until t.height) {
                for (x in 0 until t.width) {
                    val d = abs(t.luma[y * t.width + x] - t.luma[(y - 1) * t.width + x])
                    if (d > 0.08f) continue
                    if ((t.y + y) % 8 == 0) {
                        onGrid += d
                        onCount++
                    } else {
                        offGrid += d
                        offCount++
                    }
                }
            }
        }
        if (onCount == 0 || offCount == 0 || onGrid <= 0.0) return 1f
        return ((onGrid / onCount) / max(offGrid / offCount, 0.2 / 255)).toFloat()
    }

    /**
     * Measures lateral chromatic aberration near the corners and edges: finds the scale about the
     * centre at which red's and blue's fine detail lines up best with green's. Returns 1 for a
     * channel unless the fit is clearly better than leaving it.
     */
    fun fringing(source: PixelSource): Pair<Float, Float> {
        val w = source.width
        val h = source.height
        val size = 128
        if (w < size * 3 || h < size * 3) return 1f to 1f
        val cx = w / 2f
        val cy = h / 2f
        val margin = 8
        val spots = listOf(0.1f to 0.1f, 0.5f to 0.08f, 0.9f to 0.1f, 0.92f to 0.5f, 0.9f to 0.9f, 0.5f to 0.92f, 0.1f to 0.9f, 0.08f to 0.5f)
        val tiles = spots.map { (fx, fy) ->
            val x = (fx * w - size / 2).toInt().coerceIn(0, w - size - 2 * margin)
            val y = (fy * h - size / 2).toInt().coerceIn(0, h - size - 2 * margin)
            val tw = size + 2 * margin
            val pixels = source.read(x, y, tw, tw)
            val r = FloatArray(tw * tw) { ((pixels[it] shr 16) and 0xFF) / 255f }
            val g = FloatArray(tw * tw) { ((pixels[it] shr 8) and 0xFF) / 255f }
            val b = FloatArray(tw * tw) { (pixels[it] and 0xFF) / 255f }
            FringeTile(x, y, tw, highPass(r, tw), highPass(g, tw), highPass(b, tw))
        }
        fun fit(channel: (FringeTile) -> FloatArray): Float {
            fun error(s: Float): Double {
                var total = 0.0
                for (t in tiles) {
                    val c = channel(t)
                    var cross = 0.0
                    var gg = 0.0
                    val warped = FloatArray(size * size)
                    for (yy in 0 until size) {
                        for (xx in 0 until size) {
                            val px = t.x + margin + xx + 0.5f - cx
                            val py = t.y + margin + yy + 0.5f - cy
                            val sx = cx + px * s - t.x - 0.5f
                            val sy = cy + py * s - t.y - 0.5f
                            val v = c.bilinear(t.size, t.size, sx, sy)
                            warped[yy * size + xx] = v
                            val gv = t.green[(yy + margin) * t.size + xx + margin]
                            cross += v * gv
                            gg += gv * gv
                        }
                    }
                    if (gg < 1e-6) continue
                    val a = cross / gg
                    for (yy in 0 until size) {
                        for (xx in 0 until size) {
                            val d = warped[yy * size + xx] - a * t.green[(yy + margin) * t.size + xx + margin]
                            total += d * d
                        }
                    }
                }
                return total
            }
            val base = error(1f)
            var best = 1f
            var bestError = base
            for (k in -20..20) {
                if (k == 0) continue
                val s = 1f + k * 0.0001f
                val e = error(s)
                if (e < bestError) {
                    bestError = e
                    best = s
                }
            }
            // A fringe worth correcting makes a clear difference; noise alone moves it a little.
            return if (base > 0 && bestError < base * 0.93 && abs(best - 1f) >= 0.0002f) best else 1f
        }
        return fit { it.red } to fit { it.blue }
    }

    private class FringeTile(val x: Int, val y: Int, val size: Int, val red: FloatArray, val green: FloatArray, val blue: FloatArray)

    private fun highPass(v: FloatArray, n: Int): FloatArray = FloatArray(n * n) { i ->
        val x = i % n
        val y = i / n
        var blur = 0f
        for (dy in -1..1) for (dx in -1..1) blur += v.at(n, n, x + dx, y + dy)
        v[i] - blur / 9f
    }
}

/** A sparse look at the whole photo for its brightness spread and any colour cast. */
internal class Histogram(private val counts: IntArray, private val total: Int, val cast: FloatArray?) {

    fun percentile(p: Float): Float {
        val target = (p * total).toInt()
        var seen = 0
        for (i in counts.indices) {
            seen += counts[i]
            if (seen > target) return (i + 0.5f) / counts.size
        }
        return 1f
    }

    companion object {
        private const val BINS = 256

        fun sample(source: PixelSource): Histogram {
            val w = source.width
            val h = source.height
            val step = max(1, sqrt(w.toDouble() * h / 400_000).toInt())
            val counts = IntArray(BINS)
            var total = 0
            var neutral = 0
            var sr = 0.0
            var sg = 0.0
            var sb = 0.0
            for (y in 0 until h step step) {
                val row = source.read(0, y, w, 1)
                for (x in 0 until w step step) {
                    val c = row[x]
                    val r = ((c shr 16) and 0xFF) / 255f
                    val g = ((c shr 8) and 0xFF) / 255f
                    val b = (c and 0xFF) / 255f
                    val l = 0.299f * r + 0.587f * g + 0.114f * b
                    counts[(l * (BINS - 1)).toInt().coerceIn(0, BINS - 1)]++
                    total++
                    val hi = max(r, max(g, b))
                    val lo = min(r, min(g, b))
                    // Near-grey, well-exposed pixels: where a cast shows and nothing else colours them.
                    if (l in 0.15f..0.9f && hi - lo < 0.15f * hi) {
                        sr += r
                        sg += g
                        sb += b
                        neutral++
                    }
                }
            }
            return Histogram(counts, max(1, total), castGains(sr, sg, sb, neutral, total))
        }

        /**
         * Gains that move the near-grey pixels a third of the way back to grey, or null without a
         * clear cast (too few grey pixels to trust, or a cast under 6%). Only a third, so the
         * light's own warmth or coolness, which is part of the photo's mood, stays.
         */
        fun castGains(sr: Double, sg: Double, sb: Double, neutral: Int, total: Int): FloatArray? {
            if (neutral < total * 0.05 || neutral < 50) return null
            val mean = (sr + sg + sb) / 3
            val gains = doubleArrayOf(mean / sr, mean / sg, mean / sb)
            if (gains.maxOf { abs(it - 1) } < 0.06) return null
            val half = gains.map { (1 + (it - 1) / 3).coerceIn(0.95, 1.05) }
            // Keep overall brightness where it was.
            val luma = 0.299 * half[0] + 0.587 * half[1] + 0.114 * half[2]
            return half.map { (it / luma).toFloat() }.toFloatArray()
        }
    }
}

/** Exposure decisions from the brightness spread, each only for an obvious problem. */
internal object Exposure {

    /** A 256-entry brightness curve, or null when the exposure is fine as it is. */
    fun curve(a: Analysis): FloatArray? {
        var gamma = 1.0
        // Clearly too dark, with room above: lift the middle part of the way.
        if (a.median < 0.2f && a.highlights < 0.9f) {
            val target = min(0.28, a.median * 1.5)
            gamma = (ln(target) / ln(a.median.toDouble().coerceAtLeast(0.02))).coerceIn(0.7, 1.0)
        }
        // Clearly too bright with nothing in the shadows: bring the middle down a little.
        if (a.median > 0.75f && a.shadows > 0.25f) {
            val target = max(0.65, a.median - 0.1)
            gamma = (ln(target) / ln(a.median.toDouble())).coerceIn(1.0, 1.3)
        }
        // A grey veil (nothing anywhere near black): take a third of it away. Low-contrast
        // scenes (fog, a white wall) are often meant to look that way, so never all of it.
        val black = if (a.shadows > 0.16f) a.shadows / 3f else 0f
        if (gamma == 1.0 && black == 0f) return null
        return FloatArray(256) { i ->
            val y = i / 255f
            ((y - black) / (1f - black)).coerceAtLeast(0f).toDouble().pow(gamma).toFloat()
        }
    }
}
