package com.camoron.data.processing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure arithmetic for Detail mode, kept free of Android types so it is unit-testable: merging a
 * burst of RAW frames, in the spirit of Google's HDR+, and developing the result into colour.
 *
 * Frames are aligned on their 2×2 blocks (one red, two green and one blue value), so every shift
 * is a whole number of blocks and the colour filter stays in step. Each frame is aligned as a
 * whole first, on smaller and smaller copies, and then tile by tile, because hand shake also
 * turns the phone slightly. Tiles overlap by half and are blended with a raised cosine, so tile
 * edges never show. A block that differs from the reference by more than the sensor's noise
 * explains (something moved) fades out of the merge.
 */
internal object RawMath {

    /** Sum of each 2×2 block, less the black level: a half-size luma image for aligning. */
    fun blockLuma(raw: ShortArray, width: Int, height: Int, black: FloatArray): IntArray {
        val bw = width / 2
        val bh = height / 2
        val blackSum = black.sum()
        val out = IntArray(bw * bh)
        for (by in 0 until bh) {
            val top = 2 * by * width
            val bottom = top + width
            for (bx in 0 until bw) {
                val x = 2 * bx
                val sum = raw[top + x].toInt() + raw[top + x + 1].toInt() + raw[bottom + x].toInt() + raw[bottom + x + 1].toInt()
                out[by * bw + bx] = (sum - blackSum).toInt().coerceAtLeast(0)
            }
        }
        return out
    }

    /**
     * Whole-frame shift, in blocks, for which `alt(x + dx, y + dy)` best matches `ref(x, y)`:
     * found on a copy a quarter the size, then refined on the half and full size copies.
     */
    fun globalShift(ref: IntArray, alt: IntArray, width: Int, height: Int): Offset {
        val (refHalf, hw, hh) = MergeMath.halve(ref, width, height)
        val (altHalf) = MergeMath.halve(alt, width, height)
        val (refQuarter, qw, qh) = MergeMath.halve(refHalf, hw, hh)
        val (altQuarter) = MergeMath.halve(altHalf, hw, hh)
        val coarse = MergeMath.bestOffset(refQuarter, qw, qh, altQuarter, qw, qh, 0, 0, radius = GLOBAL_RADIUS, step = 2)
        val middle = MergeMath.bestOffset(refHalf, hw, hh, altHalf, hw, hh, coarse.dx * 2, coarse.dy * 2, radius = 2, step = 3)
        return MergeMath.bestOffset(ref, width, height, alt, width, height, middle.dx * 2, middle.dy * 2, radius = 2, step = 4)
    }

    /**
     * Tile grid over a [width]×[height] block image: tiles of [TILE] blocks starting every half
     * tile, beginning half a tile before the edge so the blend weights add up to one everywhere.
     */
    class TileGrid(val width: Int, val height: Int) {
        val columns = (width + HALF - 1) / HALF + 1
        val rows = (height + HALF - 1) / HALF + 1
        fun left(column: Int) = (column - 1) * HALF
        fun top(row: Int) = (row - 1) * HALF
    }

    /** Per-tile shifts of one frame, in blocks, row-major over a [TileGrid]. */
    class Alignment(val dx: IntArray, val dy: IntArray)

    /** Refines [global] for every tile of [grid] within ±[LOCAL_RADIUS] blocks. */
    fun alignTiles(ref: IntArray, alt: IntArray, grid: TileGrid, global: Offset, row: Int, into: Alignment) {
        val width = grid.width
        val height = grid.height
        for (column in 0 until grid.columns) {
            val x0 = maxOf(0, grid.left(column))
            val y0 = maxOf(0, grid.top(row))
            val x1 = minOf(width, grid.left(column) + TILE)
            val y1 = minOf(height, grid.top(row) + TILE)
            var bestDx = global.dx
            var bestDy = global.dy
            var bestError = Long.MAX_VALUE
            var bestCost = Int.MAX_VALUE
            for (oy in -LOCAL_RADIUS..LOCAL_RADIUS) {
                for (ox in -LOCAL_RADIUS..LOCAL_RADIUS) {
                    val dx = global.dx + ox
                    val dy = global.dy + oy
                    var sum = 0L
                    var count = 0
                    var y = y0
                    while (y < y1) {
                        val ay = y + dy
                        if (ay in 0 until height) {
                            var x = x0
                            while (x < x1) {
                                val ax = x + dx
                                if (ax in 0 until width) {
                                    sum += abs(ref[y * width + x] - alt[ay * width + ax])
                                    count++
                                }
                                x += 2
                            }
                        }
                        y += 2
                    }
                    if (count == 0) continue
                    // Compare means without dividing; prefer the smaller extra shift on a tie, so flat tiles don't wander.
                    val error = sum * 1024 / count
                    val cost = abs(ox) + abs(oy)
                    if (error < bestError || (error == bestError && cost < bestCost)) {
                        bestError = error
                        bestCost = cost
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
            into.dx[row * grid.columns + column] = bestDx
            into.dy[row * grid.columns + column] = bestDy
        }
    }

    /** Raised cosine over a tile: halves of neighbouring tiles always add up to one. */
    val window: FloatArray = FloatArray(TILE) { i -> sin(PI * (i + 0.5) / TILE).let { (it * it).toFloat() } }

    /**
     * The merge of one burst: starts as the reference (black level removed) and takes the other
     * frames in with [add]. [noiseScale] and [noiseOffset] describe the sensor's noise for values
     * between 0 and 1, averaged over the colours.
     */
    class Merge(
        reference: ShortArray,
        private val referenceLuma: IntArray,
        val width: Int,
        val height: Int,
        private val black: FloatArray,
        private val range: Float,
        private val noiseScale: Float,
        private val noiseOffset: Float,
    ) {
        val grid = TileGrid(width / 2, height / 2)
        private val blocksWide = width / 2
        private val blocksHigh = height / 2

        /** Sum of weighted values per pixel, black level removed. */
        val sum = FloatArray(width * height) { i -> reference[i] - black[position(i)] }

        /** Total weight per block; the reference counts once. */
        private val weight = FloatArray(blocksWide * blocksHigh) { 1f }

        private fun position(i: Int): Int = ((i / width) and 1) * 2 + ((i % width) and 1)

        /**
         * Adds one tile row of a frame, shifted per tile by [alignment]. Tile rows two apart never
         * overlap, so all even rows (or all odd rows) can be added at the same time.
         */
        fun add(frame: ShortArray, luma: IntArray, alignment: Alignment, row: Int) {
            val blockScale = 1f / (4 * range)
            for (column in 0 until grid.columns) {
                val tile = row * grid.columns + column
                val dx = alignment.dx[tile]
                val dy = alignment.dy[tile]
                val left = grid.left(column)
                val top = grid.top(row)
                for (j in 0 until TILE) {
                    val by = top + j
                    if (by !in 0 until blocksHigh) continue
                    val ay = by + dy
                    if (ay !in 0 until blocksHigh) continue
                    val wy = window[j]
                    for (i in 0 until TILE) {
                        val bx = left + i
                        if (bx !in 0 until blocksWide) continue
                        val ax = bx + dx
                        if (ax !in 0 until blocksWide) continue
                        val refBlock = referenceLuma[by * blocksWide + bx]
                        val altBlock = luma[ay * blocksWide + ax]
                        val mean = refBlock * blockScale
                        val difference = abs(refBlock - altBlock) * blockScale
                        val similarity = similarity(difference, mean)
                        if (similarity <= 0f) continue
                        val w = wy * window[i] * similarity
                        weight[by * blocksWide + bx] += w
                        val target = 2 * by * width + 2 * bx
                        val source = 2 * ay * width + 2 * ax
                        sum[target] += w * (frame[source] - black[0])
                        sum[target + 1] += w * (frame[source + 1] - black[1])
                        sum[target + width] += w * (frame[source + width] - black[2])
                        sum[target + width + 1] += w * (frame[source + width + 1] - black[3])
                    }
                }
            }
        }

        /**
         * 1 when two block means [difference] apart could differ by noise alone, fading to 0 when
         * they can't: the block moved, so it is left as the reference shows it.
         */
        fun similarity(difference: Float, mean: Float): Float {
            // Two block means, each of four values: the variance of their difference.
            val sigma = sqrt(2 * (noiseScale * mean + noiseOffset) / 4)
            val full = SAME * sigma + FLOOR
            if (difference <= full) return 1f
            val none = DIFFERENT * sigma + 2 * FLOOR
            return if (difference >= none) 0f else (none - difference) / (none - full)
        }

        /** The merged frame, black level removed, in place of [sum]. */
        fun result(): FloatArray {
            for (i in sum.indices) {
                val by = minOf(i / width / 2, blocksHigh - 1)
                val bx = minOf(i % width / 2, blocksWide - 1)
                sum[i] /= weight[by * blocksWide + bx]
            }
            return sum
        }
    }

    /**
     * Develops a merged frame (black level removed, values up to [range]) row by row: lens shading,
     * white balance, demosaicing (Malvar, He and Cutler's gradient-corrected interpolation), the
     * colour matrix, and a tone curve to sRGB.
     */
    class Develop(
        private val raw: FloatArray,
        private val width: Int,
        private val height: Int,
        /** Colour of each filter position: 0 red, 1 green, 2 blue. */
        private val pattern: IntArray,
        range: Float,
        whiteBalance: FloatArray,
        private val matrix: FloatArray,
        private val shading: Shading?,
    ) {
        private val gains = FloatArray(3) { whiteBalance[it] / range }
        private val tone = toneCurve()

        /** Scales every value to 0..1 with lens shading and white balance. Run once, before any row. */
        fun normalise(rows: IntRange) {
            for (y in rows) {
                for (x in 0 until width) {
                    val i = y * width + x
                    val colour = pattern[(y and 1) * 2 + (x and 1)]
                    val gain = gains[colour] * (shading?.gain(colour, x, y, width, height) ?: 1f)
                    raw[i] = (raw[i] * gain).coerceIn(0f, 1f)
                }
            }
        }

        /** Row [y] as opaque ARGB pixels in [out]. */
        fun row(y: Int, out: IntArray) {
            val rgb = FloatArray(3)
            for (x in 0 until width) {
                demosaic(x, y, rgb)
                val r = matrix[0] * rgb[0] + matrix[1] * rgb[1] + matrix[2] * rgb[2]
                val g = matrix[3] * rgb[0] + matrix[4] * rgb[1] + matrix[5] * rgb[2]
                val b = matrix[6] * rgb[0] + matrix[7] * rgb[1] + matrix[8] * rgb[2]
                out[x] = (0xFF shl 24) or (toned(r) shl 16) or (toned(g) shl 8) or toned(b)
            }
        }

        private fun toned(linear: Float): Int = tone[(linear.coerceIn(0f, 1f) * (TONE_STEPS - 1) + 0.5f).toInt()]

        private fun at(x: Int, y: Int): Float {
            // Mirror two pixels past the edge, which keeps the colour filter in step.
            val mx = if (x < 0) -x else if (x >= width) 2 * (width - 1) - x else x
            val my = if (y < 0) -y else if (y >= height) 2 * (height - 1) - y else y
            return raw[my * width + mx]
        }

        private fun colourAt(x: Int, y: Int): Int = pattern[(y and 1) * 2 + (x and 1)]

        fun demosaic(x: Int, y: Int, out: FloatArray) {
            val c = at(x, y)
            val colour = colourAt(x, y)
            val n = at(x, y - 1); val s = at(x, y + 1); val e = at(x + 1, y); val w = at(x - 1, y)
            val nn = at(x, y - 2); val ss = at(x, y + 2); val ee = at(x + 2, y); val ww = at(x - 2, y)
            val ne = at(x + 1, y - 1); val nw = at(x - 1, y - 1); val se = at(x + 1, y + 1); val sw = at(x - 1, y + 1)
            if (colour == GREEN) {
                // Red and blue sit either side: one colour left and right, the other above and below.
                val sideways = (10 * c + 8 * (e + w) - 2 * (nw + ne + sw + se) - 2 * (ee + ww) + (nn + ss)) / 16
                val upright = (10 * c + 8 * (n + s) - 2 * (nw + ne + sw + se) - 2 * (nn + ss) + (ee + ww)) / 16
                val sidewaysColour = colourAt(x + 1, y)
                out[GREEN] = c
                out[sidewaysColour] = clamp(sideways)
                out[2 - sidewaysColour] = clamp(upright)
            } else {
                val green = (4 * c + 2 * (n + s + e + w) - (nn + ss + ee + ww)) / 8
                val opposite = (6 * c + 2 * (ne + nw + se + sw) - 1.5f * (nn + ss + ee + ww)) / 8
                out[colour] = c
                out[GREEN] = clamp(green)
                out[2 - colour] = clamp(opposite)
            }
        }

        private fun clamp(v: Float) = v.coerceIn(0f, 1f)
    }

    /** Lens shading gains on a coarse grid, interleaved r, g, b per point, row-major. */
    class Shading(private val columns: Int, private val rows: Int, private val gains: FloatArray) {
        fun gain(colour: Int, x: Int, y: Int, width: Int, height: Int): Float {
            if (columns < 2 || rows < 2) return gains[colour]
            val gx = x.toFloat() * (columns - 1) / maxOf(1, width - 1)
            val gy = y.toFloat() * (rows - 1) / maxOf(1, height - 1)
            val x0 = minOf(gx.toInt(), columns - 2)
            val y0 = minOf(gy.toInt(), rows - 2)
            val fx = gx - x0
            val fy = gy - y0
            fun g(cx: Int, cy: Int) = gains[(cy * columns + cx) * 3 + colour]
            val top = g(x0, y0) + (g(x0 + 1, y0) - g(x0, y0)) * fx
            val bottom = g(x0, y0 + 1) + (g(x0 + 1, y0 + 1) - g(x0, y0 + 1)) * fx
            return top + (bottom - top) * fy
        }
    }

    /**
     * Linear light to 8-bit sRGB, with a little extra contrast in the mid-tones as camera photos
     * have, so the developed photo looks like the camera's own before our optimisation runs.
     */
    fun toneCurve(): IntArray = IntArray(TONE_STEPS) { i ->
        val linear = i.toDouble() / (TONE_STEPS - 1)
        val srgb = if (linear <= 0.0031308) 12.92 * linear else 1.055 * Math.pow(linear, 1 / 2.4) - 0.055
        val s = srgb * srgb * (3 - 2 * srgb)
        ((srgb + (s - srgb) * CONTRAST) * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    const val GREEN = 1
    const val TILE = 32
    private const val HALF = TILE / 2
    const val LOCAL_RADIUS = 2
    private const val GLOBAL_RADIUS = 8

    /** Within this many noise deviations a block counts fully; beyond [DIFFERENT] not at all. */
    private const val SAME = 2.5f
    private const val DIFFERENT = 5f

    /** Allowance for the tiny differences a sub-pixel misalignment leaves, as a share of white. */
    private const val FLOOR = 0.004f

    private const val TONE_STEPS = 4096
    private const val CONTRAST = 0.25

    /** Typical phone sensor noise, for cameras that don't report theirs. */
    const val DEFAULT_NOISE_SCALE = 2e-4f
    const val DEFAULT_NOISE_OFFSET = 2e-6f
}
