package com.camoron.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The restoration steps, each conservative and each skipped at strength 0. They work on a block
 * of the photo with a margin around it, so a step that looks at neighbours reads real pixels at
 * the block's edges; only the inside of the block is kept.
 */
internal object Restoration {

    /**
     * Softens the 8×8 block edges JPEG compression leaves, only where the step across a block
     * edge is small and both sides are smooth: a real edge in the picture is left alone.
     */
    fun deblock(p: Planes, strength: Float, noise: Float) {
        if (strength <= 0f) return
        // A step bigger than this across a block edge is a real edge.
        val alpha = 6f / 255f + 3f * noise
        val beta = 2f / 255f + 1.5f * noise
        for (plane in arrayOf(p.luma, p.cb, p.cr)) {
            deblockDirection(plane, p.width, p.height, p.x, strength, alpha, beta, horizontal = true)
            deblockDirection(plane, p.width, p.height, p.y, strength, alpha, beta, horizontal = false)
        }
    }

    private fun deblockDirection(
        plane: FloatArray, w: Int, h: Int, origin: Int, strength: Float, alpha: Float, beta: Float, horizontal: Boolean,
    ) {
        val across = if (horizontal) w else h
        val along = if (horizontal) h else w
        // First block edge inside this block, in its own coordinates: JPEG's grid is the photo's.
        var edge = (8 - Math.floorMod(origin, 8)) % 8
        if (edge < 2) edge += 8
        while (edge + 1 < across) {
            for (t in 0 until along) {
                fun index(k: Int) = if (horizontal) t * w + k else k * w + t
                val p1 = plane[index(edge - 2)]
                val p0 = plane[index(edge - 1)]
                val q0 = plane[index(edge)]
                val q1 = plane[index(edge + 1)]
                val d = q0 - p0
                if (abs(d) >= alpha || abs(p1 - p0) >= beta || abs(q1 - q0) >= beta) continue
                val s = d * strength
                plane[index(edge - 1)] = p0 + s * 0.375f
                plane[index(edge)] = q0 - s * 0.375f
                plane[index(edge - 2)] = p1 + s * 0.125f
                plane[index(edge + 1)] = q1 - s * 0.125f
            }
            edge += 8
        }
    }

    /**
     * Noise reduction that keeps texture: each brightness value is averaged only with nearby
     * values that are within the noise of it, so edges, pores and weave stay, and then [keepGrain]
     * of the removed grain is put back so surfaces never turn waxy. Colour speckle is averaged
     * over a wider area, guided by brightness so colours don't bleed across edges.
     */
    fun denoise(p: Planes, lumaStrength: Float, chromaStrength: Float, noise: Float, chromaNoise: Float, keepGrain: Float) {
        if (lumaStrength > 0f && noise > 0f) denoiseLuma(p, lumaStrength * (1f - keepGrain), noise)
        if (chromaStrength > 0f && chromaNoise > 0f) denoiseChroma(p, chromaStrength, noise, chromaNoise)
    }

    private val SPATIAL_5 = FloatArray(25) { i ->
        val dx = i % 5 - 2
        val dy = i / 5 - 2
        exp(-(dx * dx + dy * dy) / (2f * 1.3f * 1.3f))
    }

    private fun denoiseLuma(p: Planes, amount: Float, noise: Float) {
        val w = p.width
        val h = p.height
        val src = p.luma.copyOf()
        val range = 2.2f * noise
        val inv = 1f / (2f * range * range)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = src[y * w + x]
                var sum = 0f
                var weights = 0f
                for (k in 0 until 25) {
                    val v = src.at(w, h, x + k % 5 - 2, y + k / 5 - 2)
                    val d = v - c
                    val wt = SPATIAL_5[k] * exp(-d * d * inv)
                    sum += v * wt
                    weights += wt
                }
                p.luma[y * w + x] = c + amount * (sum / weights - c)
            }
        }
    }

    private fun denoiseChroma(p: Planes, amount: Float, noise: Float, chromaNoise: Float) {
        val w = p.width
        val h = p.height
        val cb = p.cb.copyOf()
        val cr = p.cr.copyOf()
        val lumaInv = 1f / (2f * (3f * noise + 1f / 255f).let { it * it })
        val chromaInv = 1f / (2f * (3f * chromaNoise).let { it * it })
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val cy = p.luma[i]
                val cbc = cb[i]
                val crc = cr[i]
                var sb = 0f
                var sr = 0f
                var weights = 0f
                // A 9×9 area sampled every other pixel: wide enough for blotchy colour noise.
                for (dy in -4..4 step 2) {
                    for (dx in -4..4 step 2) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val yy = (y + dy).coerceIn(0, h - 1)
                        val j = yy * w + xx
                        val dl = p.luma[j] - cy
                        val db = cb[j] - cbc
                        val dr = cr[j] - crc
                        val wt = exp(-dl * dl * lumaInv - (db * db + dr * dr) * chromaInv)
                        sb += cb[j] * wt
                        sr += cr[j] * wt
                        weights += wt
                    }
                }
                p.cb[i] = cbc + amount * (sb / weights - cbc)
                p.cr[i] = crc + amount * (sr / weights - crc)
            }
        }
    }

    /**
     * Lateral chromatic aberration: the lens focuses red and blue a little larger or smaller than
     * green, which shows as coloured fringes towards the corners. Red and blue are scaled about
     * the photo's centre by [redScale] and [blueScale] to line up with green again. Brightness is
     * rebuilt from the realigned colours, so nothing else moves.
     */
    fun correctFringing(p: Planes, redScale: Float, blueScale: Float, centreX: Float, centreY: Float) {
        if (redScale == 1f && blueScale == 1f) return
        val w = p.width
        val h = p.height
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        for (i in 0 until w * h) {
            r[i] = p.luma[i] + 1.402f * p.cr[i]
            g[i] = p.luma[i] - 0.344136f * p.cb[i] - 0.714136f * p.cr[i]
            b[i] = p.luma[i] + 1.772f * p.cb[i]
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = p.x + x + 0.5f - centreX
                val py = p.y + y + 0.5f - centreY
                val rr = r.bilinear(w, h, centreX + px * redScale - p.x - 0.5f, centreY + py * redScale - p.y - 0.5f)
                val bb = b.bilinear(w, h, centreX + px * blueScale - p.x - 0.5f, centreY + py * blueScale - p.y - 0.5f)
                val gg = g[y * w + x]
                val i = y * w + x
                p.luma[i] = 0.299f * rr + 0.587f * gg + 0.114f * bb
                p.cb[i] = -0.168736f * rr - 0.331264f * gg + 0.5f * bb
                p.cr[i] = 0.5f * rr - 0.418688f * gg - 0.081312f * bb
            }
        }
    }

    /**
     * Restrained sharpening of brightness only: fine detail is lifted by [amount], detail smaller
     * than the noise is left alone (so grain isn't sharpened), and the result can't go more than a
     * hair past its neighbours' range, so edges get no bright or dark halos.
     */
    fun sharpen(p: Planes, amount: Float, noise: Float) {
        if (amount <= 0f) return
        val w = p.width
        val h = p.height
        val src = p.luma.copyOf()
        val threshold = 0.6f * noise
        val overshoot = 1.5f / 255f
        for (y in 0 until h) {
            for (x in 0 until w) {
                var blur = 0f
                var lo = 1f
                var hi = 0f
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val v = src.at(w, h, x + dx, y + dy)
                        blur += v * KERNEL_3[(dy + 1) * 3 + dx + 1]
                        lo = min(lo, v)
                        hi = max(hi, v)
                    }
                }
                val c = src[y * w + x]
                val detail = c - blur
                val cored = if (abs(detail) <= threshold) 0f else detail - threshold * Math.signum(detail)
                p.luma[y * w + x] = (c + amount * cored).coerceIn(lo - overshoot, hi + overshoot)
            }
        }
    }

    private val KERNEL_3 = floatArrayOf(1f, 2f, 1f, 2f, 4f, 2f, 1f, 2f, 1f).map { it / 16f }.toFloatArray()

    /**
     * Enlarges with a Lanczos filter, the standard for faithful resizing: it adds no detail that
     * isn't in the photo. Its slight ringing next to hard edges is held back to the range of the
     * nearest source pixels. [src] is the processed source block; the output block starts at
     * ([outX], [outY]) in the enlarged photo and is [outW]×[outH].
     */
    fun upscale(src: Planes, scale: Float, outX: Int, outY: Int, outW: Int, outH: Int): Planes {
        val out = Planes(outX, outY, outW, outH)
        val sw = src.width
        val sh = src.height
        // Horizontal pass into an intermediate that keeps the source rows.
        val tmp = Array(3) { FloatArray(outW * sh) }
        val planes = arrayOf(src.luma, src.cb, src.cr)
        val xWeights = Array(outW) { FloatArray(6) }
        val xStart = IntArray(outW)
        for (ox in 0 until outW) {
            val u = (outX + ox + 0.5f) / scale - 0.5f - src.x
            val base = kotlin.math.floor(u).toInt() - 2
            xStart[ox] = base
            var total = 0f
            for (k in 0 until 6) {
                val wt = lanczos3(u - (base + k))
                xWeights[ox][k] = wt
                total += wt
            }
            for (k in 0 until 6) xWeights[ox][k] /= total
        }
        for (c in 0 until 3) {
            val plane = planes[c]
            val t = tmp[c]
            for (y in 0 until sh) {
                for (ox in 0 until outW) {
                    var sum = 0f
                    for (k in 0 until 6) sum += xWeights[ox][k] * plane.at(sw, sh, xStart[ox] + k, y)
                    t[y * outW + ox] = sum
                }
            }
        }
        val outPlanes = arrayOf(out.luma, out.cb, out.cr)
        for (oy in 0 until outH) {
            val v = (outY + oy + 0.5f) / scale - 0.5f - src.y
            val base = kotlin.math.floor(v).toInt() - 2
            val weights = FloatArray(6)
            var total = 0f
            for (k in 0 until 6) {
                weights[k] = lanczos3(v - (base + k))
                total += weights[k]
            }
            val y0 = kotlin.math.floor(v).toInt()
            for (c in 0 until 3) {
                val t = tmp[c]
                val o = outPlanes[c]
                for (ox in 0 until outW) {
                    var sum = 0f
                    for (k in 0 until 6) sum += weights[k] * t.at(outW, sh, ox, base + k)
                    sum /= total
                    // Anti-ringing: no further than a touch outside the four nearest source pixels.
                    val u = (outX + ox + 0.5f) / scale - 0.5f - src.x
                    val x0 = kotlin.math.floor(u).toInt()
                    val plane = planes[c]
                    val a = plane.at(sw, sh, x0, y0)
                    val b = plane.at(sw, sh, x0 + 1, y0)
                    val cc = plane.at(sw, sh, x0, y0 + 1)
                    val d = plane.at(sw, sh, x0 + 1, y0 + 1)
                    val lo = min(min(a, b), min(cc, d))
                    val hi = max(max(a, b), max(cc, d))
                    val clamped = sum.coerceIn(lo, hi)
                    o[oy * outW + ox] = clamped + (sum - clamped) * 0.35f
                }
            }
        }
        return out
    }

    private fun lanczos3(x: Float): Float {
        val ax = abs(x)
        if (ax < 1e-5f) return 1f
        if (ax >= 3f) return 0f
        val px = Math.PI.toFloat() * x
        return (3f * kotlin.math.sin(px) * kotlin.math.sin(px / 3f)) / (px * px)
    }

    /**
     * The colour and exposure step, per pixel: removes [castGains] (per-channel multipliers for
     * a colour cast) and applies the exposure [curve] (256 entries over brightness), each
     * already limited by the plan to what the photo needs.
     */
    fun tone(p: Planes, castGains: FloatArray?, curve: FloatArray?) {
        if (castGains == null && curve == null) return
        for (i in 0 until p.width * p.height) {
            var r = p.luma[i] + 1.402f * p.cr[i]
            var g = p.luma[i] - 0.344136f * p.cb[i] - 0.714136f * p.cr[i]
            var b = p.luma[i] + 1.772f * p.cb[i]
            if (castGains != null) {
                r *= castGains[0]
                g *= castGains[1]
                b *= castGains[2]
            }
            if (curve != null) {
                // Scaling all three channels by the brightness change keeps hue and saturation.
                val y = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
                val mapped = curveAt(curve, y)
                val k = if (y > 1e-4f) mapped / y else 1f
                r *= k
                g *= k
                b *= k
            }
            p.luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
            p.cb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b
            p.cr[i] = 0.5f * r - 0.418688f * g - 0.081312f * b
        }
    }

    fun curveAt(curve: FloatArray, y: Float): Float {
        val f = y.coerceIn(0f, 1f) * (curve.size - 1)
        val i = f.toInt().coerceAtMost(curve.size - 2)
        val t = f - i
        return curve[i] * (1 - t) + curve[i + 1] * t
    }

    /** Standard deviation of a plane's values, for the checks. */
    fun deviation(values: FloatArray): Float {
        var mean = 0.0
        for (v in values) mean += v
        mean /= values.size
        var sq = 0.0
        for (v in values) sq += (v - mean) * (v - mean)
        return sqrt(sq / values.size).toFloat()
    }
}
