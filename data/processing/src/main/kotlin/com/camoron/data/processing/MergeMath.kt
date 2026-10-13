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

    /**
     * Whether a pixel from another frame shows the same thing as the reference pixel. Anything that
     * moved (or a badly aligned edge) differs by more than noise does, and is left out of the
     * average, which is what keeps moving subjects from ghosting.
     */
    fun similar(reference: Int, candidate: Int, threshold: Int = GHOST_THRESHOLD): Boolean {
        val dr = abs(((reference shr 16) and 0xFF) - ((candidate shr 16) and 0xFF))
        val dg = abs(((reference shr 8) and 0xFF) - ((candidate shr 8) and 0xFF))
        val db = abs((reference and 0xFF) - (candidate and 0xFF))
        return dr + dg + db <= threshold
    }

    /** Average of channel sums over [count] frames, as an opaque ARGB pixel. */
    fun average(sumR: Int, sumG: Int, sumB: Int, count: Int): Int {
        val half = count / 2
        val r = ((sumR + half) / count).coerceIn(0, 255)
        val g = ((sumG + half) / count).coerceIn(0, 255)
        val b = ((sumB + half) / count).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Sum of absolute channel differences above which a pixel counts as moved. */
    const val GHOST_THRESHOLD = 90

    /** Mean luma error above which a whole frame is too blurred or shifted to help. */
    const val FRAME_REJECT_ERROR = 28f
}
