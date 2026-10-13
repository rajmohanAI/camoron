package com.camoron.data.processing

import com.camoron.domain.model.EnhanceMath

/**
 * How strongly to clean and sharpen one photo, from the noise measured in it and the digital zoom
 * it was taken at. Noisy or zoomed photos get more noise reduction and less sharpening, so grain and
 * zoom blockiness are smoothed rather than exaggerated.
 */
internal class DetailTuning(
    /** Measured noise level (standard deviation, 0..1 of full scale). */
    val noise: Float,
    /** Digital zoom the photo was taken at (1 = none). */
    val zoom: Float = 1f,
) {
    /**
     * Whether the picture needs cleaning at all. A merged burst in good light is already clean, and
     * skipping the smoothing there saves most of the work.
     */
    val denoise: Boolean = noise >= CLEAN

    /** Window radius of the edge-preserving smoothing. */
    val radius: Int = if (noise > NOISY) 3 else 2

    /** Areas whose brightness varies less than this are treated as noise and smoothed. */
    val epsilon: Float = (EPSILON_SCALE * maxOf(noise, MIN_NOISE)).let { it * it }

    /** Window radius of the colour smoothing, which removes colour speckles. */
    val chromaRadius: Int = if (noise > SOMEWHAT_NOISY) 4 else 3

    val chromaEpsilon: Float = epsilon * CHROMA_EPSILON_FACTOR

    /** Unsharp-mask amount on the cleaned picture. */
    val sharpen: Float = (MAX_SHARPEN - SHARPEN_PER_NOISE * noise).coerceIn(MIN_SHARPEN, MAX_SHARPEN) /
        zoom.coerceAtLeast(1f)

    /** Local-contrast amount; off for very noisy photos, where it would lift grain. */
    val clarity: Float = (MAX_CLARITY - CLARITY_PER_NOISE * noise).coerceIn(0f, MAX_CLARITY)

    /** Rows of neighbours each band needs above and below to compute its own rows exactly. */
    val halo: Int get() = maxOf(3 * radius, 2 * chromaRadius)

    private companion object {
        const val CLEAN = 1.2f / 255
        const val NOISY = 0.02f
        const val SOMEWHAT_NOISY = 0.012f
        const val MIN_NOISE = 1.5f / 255
        const val EPSILON_SCALE = 1.6f
        const val CHROMA_EPSILON_FACTOR = 4f
        const val MAX_SHARPEN = 0.6f
        const val MIN_SHARPEN = 0.12f
        const val SHARPEN_PER_NOISE = 16f
        const val MAX_CLARITY = 0.22f
        const val CLARITY_PER_NOISE = 9f
    }
}

/**
 * Pure arithmetic for photo enhancement, kept free of Android types so it is unit-testable.
 *
 * The photo is split into brightness and colour. Brightness goes through a guided filter (an
 * edge-preserving smoothing: flat areas are averaged, edges and texture stronger than the noise are
 * kept), then a gentle unsharp mask on the cleaned result. Colour goes through a guided filter that
 * follows the brightness edges, which removes colour speckles without colour bleeding across
 * edges. Finally the scene-adaptive tone curve and vibrance ([EnhanceMath]) are applied.
 */
internal object DetailMath {

    /**
     * Noise level of a brightness plane (0..255 values, [width] x [height]), as a 0..1 standard
     * deviation. Uses the median of a Laplacian-like response, so edges and texture barely affect it.
     */
    fun estimateNoise(luma: ByteArray, width: Int, height: Int, step: Int = 2): Float {
        if (width < 3 || height < 3) return 0f
        val counts = IntArray(MAX_RESPONSE + 1)
        var total = 0
        var y = 1
        while (y < height - 1) {
            var x = 1
            val row = y * width
            while (x < width - 1) {
                fun at(dx: Int, dy: Int) = luma[row + dy * width + x + dx].toInt() and 0xFF
                val response = at(-1, -1) - 2 * at(0, -1) + at(1, -1) -
                    2 * at(-1, 0) + 4 * at(0, 0) - 2 * at(1, 0) +
                    at(-1, 1) - 2 * at(0, 1) + at(1, 1)
                counts[minOf(MAX_RESPONSE, kotlin.math.abs(response))]++
                total++
                x += step
            }
            y += step
        }
        if (total == 0) return 0f
        var running = 0
        var median = 0
        for (i in counts.indices) {
            running += counts[i]
            if (running * 2 >= total) {
                median = i
                break
            }
        }
        // The kernel's weights square-sum to 36, so its response to noise has a deviation of 6σ;
        // the median of |N(0, s)| is 0.6745 s.
        return median / (MEDIAN_OF_ABS_NORMAL * KERNEL_GAIN) / 255f
    }

    /**
     * Mean over a (2r+1)² window, clamping at the edges. [tmp] and [out] must hold w*h values; [out]
     * may not be [src].
     */
    fun boxFilter(src: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray, out: FloatArray) {
        val size = 2 * r + 1
        val inv = 1f / size
        // Horizontal pass.
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum * inv
                val add = (x + r + 1).coerceAtMost(w - 1)
                val drop = (x - r).coerceAtLeast(0)
                sum += src[row + add] - src[row + drop]
            }
        }
        // Vertical pass.
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum * inv
                val add = (y + r + 1).coerceAtMost(h - 1)
                val drop = (y - r).coerceAtLeast(0)
                sum += tmp[add * w + x] - tmp[drop * w + x]
            }
        }
    }

    /** Working arrays for [processBand], reused across bands to spare the garbage collector. */
    class Workspace(capacity: Int) {
        val y = FloatArray(capacity)
        val cb = FloatArray(capacity)
        val cr = FloatArray(capacity)
        val a = FloatArray(capacity)
        val b = FloatArray(capacity)
        val c = FloatArray(capacity)
        val d = FloatArray(capacity)
        val e = FloatArray(capacity)
        val clean = FloatArray(capacity)
        val tmp = FloatArray(capacity)
        val capacity: Int get() = y.size
    }

    /**
     * Enhances rows [coreTop] until [coreBottom] of [pixels] (ARGB, [w] wide, [h] rows, which
     * include the [DetailTuning.halo] rows around the core where the image has them). Writes the
     * core rows to [out] (w * core rows). [localMean] gives the average brightness (0..255) around a
     * pixel, by core-relative row, for local contrast.
     */
    fun processBand(
        pixels: IntArray,
        w: Int,
        h: Int,
        coreTop: Int,
        coreBottom: Int,
        tuning: DetailTuning,
        lut: IntArray,
        vibrance: Float,
        localMean: (x: Int, coreRow: Int) -> Float,
        work: Workspace,
        out: IntArray,
    ) {
        val n = w * h
        require(work.capacity >= n) { "Workspace too small" }
        val y = work.y
        val cb = work.cb
        val cr = work.cr
        for (i in 0 until n) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            y[i] = l
            cb[i] = b - l
            cr[i] = r - l
        }

        // Guided chroma reuses work.e, so keep the cleaned brightness in its own array.
        val luma = work.clean
        if (tuning.denoise) {
            smoothLuma(w, h, tuning, work).copyInto(luma, 0, 0, n)
            // Colour follows the original brightness edges (guided by y), into work.cb / work.cr.
            guidedChroma(cb, w, h, tuning, work)
            guidedChroma(cr, w, h, tuning, work)
        } else {
            y.copyInto(luma, 0, 0, n)
        }

        // Unsharp mask on the cleaned brightness: luma - mean(luma).
        val base = work.b
        boxFilter(luma, w, h, tuning.radius, work.tmp, base)
        for (row in coreTop until coreBottom) {
            val coreRow = row - coreTop
            for (x in 0 until w) {
                val i = row * w + x
                val clean = luma[i]
                var l = clean + tuning.sharpen * (clean - base[i])
                if (tuning.clarity > 0f) {
                    val l255 = clean * 255f
                    val mid = clean.coerceIn(0f, 1f)
                    l += tuning.clarity * (l255 - localMean(x, coreRow)) / 255f * 4f * mid * (1f - mid)
                }
                val red = l + cr[i]
                val blue = l + cb[i]
                val green = (l - 0.299f * red - 0.114f * blue) / 0.587f
                val rgb = ((red * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((green * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
                out[coreRow * w + x] = EnhanceMath.apply(lut, vibrance, (pixels[i] and ALPHA) or rgb)
            }
        }
    }

    /** Self-guided filter on work.y. Returns the cleaned plane (work.e); leaves work.y untouched. */
    private fun smoothLuma(w: Int, h: Int, tuning: DetailTuning, work: Workspace): FloatArray {
        val n = w * h
        val y = work.y
        val r = tuning.radius
        val mean = work.a
        val meanSq = work.b
        boxFilter(y, w, h, r, work.tmp, mean)
        for (i in 0 until n) work.c[i] = y[i] * y[i]
        boxFilter(work.c, w, h, r, work.tmp, meanSq)
        // a = var / (var + eps), b = mean (1 - a), stored over mean / meanSq.
        for (i in 0 until n) {
            val m = mean[i]
            val variance = (meanSq[i] - m * m).coerceAtLeast(0f)
            val a = variance / (variance + tuning.epsilon)
            work.c[i] = a
            work.d[i] = m * (1f - a)
        }
        boxFilter(work.c, w, h, r, work.tmp, mean)
        boxFilter(work.d, w, h, r, work.tmp, meanSq)
        val clean = work.e
        for (i in 0 until n) clean[i] = mean[i] * y[i] + meanSq[i]
        return clean
    }

    /** Guided filter of a colour-difference [plane] with brightness (work.y) as the guide, in place. */
    private fun guidedChroma(plane: FloatArray, w: Int, h: Int, tuning: DetailTuning, work: Workspace) {
        val n = w * h
        val y = work.y
        val r = tuning.chromaRadius
        val meanY = work.a
        val meanP = work.b
        val meanYP = work.c
        val meanYY = work.d
        boxFilter(y, w, h, r, work.tmp, meanY)
        boxFilter(plane, w, h, r, work.tmp, meanP)
        for (i in 0 until n) work.c[i] = y[i] * plane[i]
        // work.c is both input and output here, so filter through work.e.
        boxFilter(work.c, w, h, r, work.tmp, work.e)
        work.e.copyInto(meanYP, 0, 0, n)
        for (i in 0 until n) work.e[i] = y[i] * y[i]
        boxFilter(work.e, w, h, r, work.tmp, meanYY)
        for (i in 0 until n) {
            val my = meanY[i]
            val variance = (meanYY[i] - my * my).coerceAtLeast(0f)
            val covariance = meanYP[i] - my * meanP[i]
            val a = covariance / (variance + tuning.chromaEpsilon)
            meanYP[i] = a
            meanYY[i] = meanP[i] - a * my
        }
        boxFilter(meanYP, w, h, r, work.tmp, meanY)
        boxFilter(meanYY, w, h, r, work.tmp, meanP)
        for (i in 0 until n) plane[i] = meanY[i] * y[i] + meanP[i]
    }

    private const val ALPHA = 0xFF000000.toInt()
    private const val MAX_RESPONSE = 1023
    private const val MEDIAN_OF_ABS_NORMAL = 0.6745f
    private const val KERNEL_GAIN = 6f
}

/** Average brightness of [block]-sized tiles of a 0..255 plane, read back with bilinear interpolation. */
internal class CoarseLuma(plane: ByteArray, width: Int, height: Int, private val block: Int) {
    private val cols = (width + block - 1) / block
    private val rows = (height + block - 1) / block
    private val values = FloatArray(cols * rows)

    init {
        val counts = IntArray(cols * rows)
        for (y in 0 until height) {
            val tileRow = (y / block) * cols
            val offset = y * width
            for (x in 0 until width) {
                val i = tileRow + x / block
                values[i] += (plane[offset + x].toInt() and 0xFF).toFloat()
                counts[i]++
            }
        }
        for (i in values.indices) if (counts[i] > 0) values[i] /= counts[i]
    }

    fun at(x: Int, y: Int): Float {
        val fx = ((x + 0.5f) / block - 0.5f).coerceIn(0f, (cols - 1).toFloat())
        val fy = ((y + 0.5f) / block - 0.5f).coerceIn(0f, (rows - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = minOf(x0 + 1, cols - 1)
        val y1 = minOf(y0 + 1, rows - 1)
        val tx = fx - x0
        val ty = fy - y0
        val top = values[y0 * cols + x0] * (1 - tx) + values[y0 * cols + x1] * tx
        val bottom = values[y1 * cols + x0] * (1 - tx) + values[y1 * cols + x1] * tx
        return top * (1 - ty) + bottom * ty
    }
}
