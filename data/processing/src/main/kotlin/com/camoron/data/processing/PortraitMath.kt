package com.camoron.data.processing

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The pixel work behind Portrait, on plain float planes so it can be tested off the phone: refining
 * the person mask to follow the photo's edges, and blurring the background without letting the
 * person's colours bleed into it.
 */
internal object PortraitMath {

    /** Mean over a (2r+1)² box, edges clamped, in two running-sum passes. */
    fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum * norm
                sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum * norm
                sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    /**
     * Edge-aware smoothing of [input] steered by [guide] (He et al.'s guided filter): the coarse
     * mask from the model snaps to the outline of the person in the photo.
     */
    fun guidedFilter(guide: FloatArray, input: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = boxBlur(guide, w, h, r)
        val meanP = boxBlur(input, w, h, r)
        val corrIP = boxBlur(FloatArray(n) { guide[it] * input[it] }, w, h, r)
        val corrII = boxBlur(FloatArray(n) { guide[it] * guide[it] }, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val variance = corrII[i] - meanI[i] * meanI[i]
            val covariance = corrIP[i] - meanI[i] * meanP[i]
            a[i] = covariance / (variance + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = boxBlur(a, w, h, r)
        val meanB = boxBlur(b, w, h, r)
        return FloatArray(n) { meanA[it] * guide[it] + meanB[it] }
    }

    /** The person mask at [w]×[h], from the model's [probability] grid, following [guide]'s edges. */
    fun refineMask(probability: FloatArray, size: Int, guide: FloatArray, w: Int, h: Int): FloatArray {
        val coarse = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            coarse[y * w + x] = bilinear(probability, size, size, (x + 0.5f) * size / w - 0.5f, (y + 0.5f) * size / h - 0.5f)
        }
        val radius = max(2, (max(w, h) * GUIDE_RADIUS).roundToInt())
        val refined = guidedFilter(guide, coarse, w, h, radius, GUIDE_EPS)
        // Firm up the transition so the person is fully sharp and the background fully blurred.
        for (i in refined.indices) {
            val t = ((refined[i] - EDGE_LOW) / (EDGE_HIGH - EDGE_LOW)).coerceIn(0f, 1f)
            refined[i] = t * t * (3 - 2 * t)
        }
        return refined
    }

    /**
     * The background of linear-light [planes] blurred as a lens would, weighted by 1 − [mask] so the
     * person's colours don't spread into it (a normalised, premultiplied blur). Where there is no
     * background nearby the original pixel is kept.
     */
    fun blurBackground(planes: Array<FloatArray>, mask: FloatArray, w: Int, h: Int, radius: Int): Array<FloatArray> {
        val weight = FloatArray(w * h) { 1f - mask[it] }
        val total = repeatBlur(weight, w, h, radius)
        return Array(planes.size) { c ->
            val plane = planes[c]
            val sum = repeatBlur(FloatArray(w * h) { plane[it] * weight[it] }, w, h, radius)
            FloatArray(w * h) { i -> if (total[i] > MIN_WEIGHT) sum[i] / total[i] else plane[i] }
        }
    }

    /** Three box blurs of radius r/√3 each come close to a smooth, round lens blur of radius r. */
    private fun repeatBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val r = max(1, (radius / 1.732f).roundToInt())
        return boxBlur(boxBlur(boxBlur(src, w, h, r), w, h, r), w, h, r)
    }

    /** Sample of [grid] at fractional pixel ([x], [y]), edges clamped. */
    fun bilinear(grid: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (w - 1).toFloat())
        val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = cx.toInt().coerceAtMost(w - 2).coerceAtLeast(0)
        val y0 = cy.toInt().coerceAtMost(h - 2).coerceAtLeast(0)
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = cx - x0
        val fy = cy - y0
        val top = grid[y0 * w + x0] + (grid[y0 * w + x1] - grid[y0 * w + x0]) * fx
        val bottom = grid[y1 * w + x0] + (grid[y1 * w + x1] - grid[y1 * w + x0]) * fx
        return top + (bottom - top) * fy
    }

    /** Person probability per cell from the model's six class scores (background is class 0). */
    fun personProbability(logits: FloatArray, cells: Int, classes: Int): FloatArray = FloatArray(cells) { i ->
        val base = i * classes
        var top = logits[base]
        for (k in 1 until classes) top = max(top, logits[base + k])
        var total = 0f
        var background = 0f
        for (k in 0 until classes) {
            val e = kotlin.math.exp(logits[base + k] - top)
            total += e
            if (k == 0) background = e
        }
        1f - background / total
    }

    /** [grid] (n×n) turned [degrees] clockwise, a multiple of 90. */
    fun rotateSquare(grid: FloatArray, n: Int, degrees: Int): FloatArray {
        val turns = ((degrees / 90) % 4 + 4) % 4
        if (turns == 0) return grid.copyOf()
        return FloatArray(n * n) { i ->
            val x = i % n
            val y = i / n
            when (turns) {
                1 -> grid[(n - 1 - x) * n + y]
                2 -> grid[(n - 1 - y) * n + (n - 1 - x)]
                else -> grid[x * n + (n - 1 - y)]
            }
        }
    }

    /** Share of the frame the person covers; too little means there is nobody to keep sharp. */
    fun coverage(probability: FloatArray): Float = probability.count { it > 0.5f }.toFloat() / probability.size

    fun toLinear(c: Int): Float = LINEAR_TABLE[c]

    fun toSrgb(v: Float): Int = (v.coerceIn(0f, 1f).pow(1f / GAMMA) * 255f + 0.5f).toInt()

    private const val GAMMA = 2.2f
    private val LINEAR_TABLE = FloatArray(256) { (it / 255f).pow(GAMMA) }

    /** Guided filter window, as a fraction of the long side of the working image. */
    private const val GUIDE_RADIUS = 0.008f
    private const val GUIDE_EPS = 1e-3f
    private const val EDGE_LOW = 0.3f
    private const val EDGE_HIGH = 0.7f
    private const val MIN_WEIGHT = 1e-3f
}
