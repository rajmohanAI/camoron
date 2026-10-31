package com.camoron.data.processing

import kotlin.math.abs

/** Result of an alignment search: how far to shift the frame, and how well it then matches. */
internal data class Offset(val dx: Int, val dy: Int, val meanError: Float)

/** Pure arithmetic for night merging, kept free of Android types so it is unit-testable. */
internal object MergeMath {

    /**
     * Finds the shift (dx, dy) within ±[radius] of ([baseX], [baseY]) for which
     * `image(x + dx, y + dy)` best matches `ref(x, y)`, comparing luma on a [step] grid. Pixels that
     * fall outside [image] are skipped; a shift that leaves less than half the grid overlapping is
     * never chosen.
     */
    fun bestOffset(
        ref: IntArray,
        refWidth: Int,
        refHeight: Int,
        image: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        baseX: Int,
        baseY: Int,
        radius: Int,
        step: Int = 2,
    ): Offset {
        val gridPoints = ((refWidth + step - 1) / step) * ((refHeight + step - 1) / step)
        var best = Offset(baseX, baseY, Float.MAX_VALUE)
        for (oy in -radius..radius) {
            for (ox in -radius..radius) {
                val dx = baseX + ox
                val dy = baseY + oy
                var sum = 0L
                var count = 0
                var y = 0
                while (y < refHeight) {
                    val iy = y + dy
                    if (iy in 0 until imageHeight) {
                        var x = 0
                        while (x < refWidth) {
                            val ix = x + dx
                            if (ix in 0 until imageWidth) {
                                sum += abs(ref[y * refWidth + x] - image[iy * imageWidth + ix])
                                count++
                            }
                            x += step
                        }
                    }
                    y += step
                }
                if (count * 2 < gridPoints) continue
                val mean = sum.toFloat() / count
                if (mean < best.meanError) best = Offset(dx, dy, mean)
            }
        }
        return best
    }

    /** Rec. 601 luma of an ARGB pixel, 0..255. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    /** A luma image at half the width and height, each pixel the mean of the 2x2 block it covers. */
    fun halve(luma: IntArray, width: Int, height: Int): Triple<IntArray, Int, Int> {
        val w = maxOf(1, width / 2)
        val h = maxOf(1, height / 2)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = minOf(2 * y, height - 1)
            val y1 = minOf(2 * y + 1, height - 1)
            for (x in 0 until w) {
                val x0 = minOf(2 * x, width - 1)
                val x1 = minOf(2 * x + 1, width - 1)
                out[y * w + x] = (luma[y0 * width + x0] + luma[y0 * width + x1] + luma[y1 * width + x0] + luma[y1 * width + x1] + 2) / 4
            }
        }
        return Triple(out, w, h)
    }

    /** Average of channel sums over [count] frames, as an opaque ARGB pixel. */
    fun average(sumR: Int, sumG: Int, sumB: Int, count: Int): Int {
        val half = count / 2
        val r = ((sumR + half) / count).coerceIn(0, 255)
        val g = ((sumG + half) / count).coerceIn(0, 255)
        val b = ((sumB + half) / count).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * How much a frame's pixel counts in the merge, 0..[GHOST_THRESHOLD], against the reference's own
     * [GHOST_THRESHOLD]. Pixels that differ by no more than noise does count fully; beyond that the
     * weight fades out smoothly, so a slightly misaligned edge or a small movement blends away
     * instead of switching off abruptly, which left blotches in the old all-or-nothing merge.
     */
    fun weight(reference: Int, candidate: Int, threshold: Int = GHOST_THRESHOLD): Int {
        val dr = abs(((reference shr 16) and 0xFF) - ((candidate shr 16) and 0xFF))
        val dg = abs(((reference shr 8) and 0xFF) - ((candidate shr 8) and 0xFF))
        val db = abs((reference and 0xFF) - (candidate and 0xFF))
        val difference = dr + dg + db
        if (difference <= threshold) return threshold
        // Fades from full weight at [threshold] to nothing at [WEIGHT_FALLOFF] times it.
        val fade = (threshold * WEIGHT_FALLOFF).toInt() - difference
        return if (fade <= 0) 0 else fade * threshold / (threshold * WEIGHT_FALLOFF - threshold).toInt()
    }

    /**
     * Sharpness of a luma image: mean absolute Laplacian on a [step] grid. A frame blurred by hand
     * shake scores lower, so the sharpest frame of a burst can be picked as the reference.
     */
    fun sharpness(luma: IntArray, width: Int, height: Int, step: Int = 2): Float {
        var sum = 0L
        var count = 0
        var y = 1
        while (y < height - 1) {
            var x = 1
            while (x < width - 1) {
                val i = y * width + x
                sum += abs(4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - width] - luma[i + width])
                count++
                x += step
            }
            y += step
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    /**
     * One frame's strip of pixels for a band of the reference. Band pixel (x, y) is strip pixel
     * (x + [offsetX], y + [offsetY]) once the whole frame is aligned; each tile then refines that
     * by a few pixels of its own.
     */
    class Strip(val pixels: IntArray, val width: Int, val height: Int, val offsetX: Int, val offsetY: Int)

    /**
     * Merges a band of the reference ([reference], [width] x [height]) with the matching strips of
     * the other frames, added one at a time so only one strip needs to be in memory. Each frame is
     * aligned again tile by tile within ±[radius] px of its whole-frame alignment, because hand shake
     * also turns and tilts the phone slightly, so one shift for the whole frame leaves the edges and
     * corners blurred. Pixels are then averaged with [weight], so whatever moved stays as the
     * reference shows it.
     */
    class BandMerge(
        private val reference: IntArray,
        private val width: Int,
        private val height: Int,
        private val tile: Int = TILE,
        private val radius: Int = LOCAL_RADIUS,
    ) {
        private val refLuma = IntArray(reference.size) { luma(reference[it]) }
        private val sumR = IntArray(reference.size) { ((reference[it] shr 16) and 0xFF) * GHOST_THRESHOLD }
        private val sumG = IntArray(reference.size) { ((reference[it] shr 8) and 0xFF) * GHOST_THRESHOLD }
        private val sumB = IntArray(reference.size) { (reference[it] and 0xFF) * GHOST_THRESHOLD }
        private val total = IntArray(reference.size) { GHOST_THRESHOLD }

        fun add(strip: Strip) {
            val stripLuma = IntArray(strip.pixels.size) { luma(strip.pixels[it]) }
            var ty = 0
            while (ty < height) {
                val th = minOf(tile, height - ty)
                var tx = 0
                while (tx < width) {
                    val tw = minOf(tile, width - tx)
                    tileOffset(refLuma, width, tx, ty, tw, th, stripLuma, strip, radius)?.let { local ->
                        accumulate(strip, tx, ty, tw, th, strip.offsetX + local.dx, strip.offsetY + local.dy)
                    }
                    tx += tile
                }
                ty += tile
            }
        }

        private fun accumulate(strip: Strip, left: Int, top: Int, tileWidth: Int, tileHeight: Int, ox: Int, oy: Int) {
            for (y in top until top + tileHeight) {
                val sy = y + oy
                if (sy !in 0 until strip.height) continue
                for (x in left until left + tileWidth) {
                    val sx = x + ox
                    if (sx !in 0 until strip.width) continue
                    val i = y * width + x
                    val px = strip.pixels[sy * strip.width + sx]
                    val w = weight(reference[i], px)
                    if (w == 0) continue
                    sumR[i] += ((px shr 16) and 0xFF) * w
                    sumG[i] += ((px shr 8) and 0xFF) * w
                    sumB[i] += (px and 0xFF) * w
                    total[i] += w
                }
            }
        }

        fun result(): IntArray = IntArray(reference.size) { i -> average(sumR[i], sumG[i], sumB[i], total[i]) }
    }

    /** All strips at once; see [BandMerge]. */
    fun mergeBand(
        reference: IntArray,
        width: Int,
        height: Int,
        strips: List<Strip>,
        tile: Int = TILE,
        radius: Int = LOCAL_RADIUS,
    ): IntArray = BandMerge(reference, width, height, tile, radius).apply { strips.forEach(::add) }.result()

    /**
     * The best extra shift within ±[radius] for one tile of a strip, or null when the tile doesn't
     * match anywhere (it moved, or it is mostly outside the strip).
     */
    private fun tileOffset(
        refLuma: IntArray,
        width: Int,
        left: Int,
        top: Int,
        tileWidth: Int,
        tileHeight: Int,
        stripLuma: IntArray,
        strip: Strip,
        radius: Int,
    ): Offset? {
        val step = TILE_STEP
        val gridPoints = ((tileWidth + step - 1) / step) * ((tileHeight + step - 1) / step)
        var best: Offset? = null
        for (oy in -radius..radius) {
            for (ox in -radius..radius) {
                var sum = 0L
                var count = 0
                var y = top
                while (y < top + tileHeight) {
                    val sy = y + strip.offsetY + oy
                    if (sy in 0 until strip.height) {
                        var x = left
                        while (x < left + tileWidth) {
                            val sx = x + strip.offsetX + ox
                            if (sx in 0 until strip.width) {
                                sum += abs(refLuma[y * width + x] - stripLuma[sy * strip.width + sx])
                                count++
                            }
                            x += step
                        }
                    }
                    y += step
                }
                if (count * 2 < gridPoints) continue
                val mean = sum.toFloat() / count
                // Prefer no extra shift on a tie, so flat tiles don't wander.
                val current = best
                if (current == null || mean < current.meanError - TIE_MARGIN ||
                    (mean <= current.meanError + TIE_MARGIN && abs(ox) + abs(oy) < abs(current.dx) + abs(current.dy))
                ) {
                    best = Offset(ox, oy, mean)
                }
            }
        }
        return best?.takeIf { it.meanError <= FRAME_REJECT_ERROR }
    }

    /** Sum of absolute channel differences above which a pixel counts as moved. */
    const val GHOST_THRESHOLD = 90

    /** Tile size for the second, local alignment, and how far each tile may move on its own. */
    const val TILE = 128
    const val LOCAL_RADIUS = 3
    private const val TILE_STEP = 2
    private const val TIE_MARGIN = 0.05f
    private const val WEIGHT_FALLOFF = 1.6f

    /** Mean luma error above which a whole frame is too blurred or shifted to help. */
    const val FRAME_REJECT_ERROR = 28f
}
