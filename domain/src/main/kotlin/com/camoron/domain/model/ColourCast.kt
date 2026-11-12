package com.camoron.domain.model

/**
 * Removes a colour cast the camera's own white balance left behind, such as the yellow of indoor
 * bulbs or the blue of shade. It looks at the picture's near-neutral pixels (greys, whites,
 * skin-free surfaces), which should average to grey; any tint they share is the cast.
 *
 * Only part of the cast is taken out, and only within a small range, so a warm sunset or a candlelit
 * room keeps its mood and a strongly coloured scene (a green lawn, a red wall) is left alone.
 * Brightness is kept the same, and the brightest highlights are left as they are, so white never
 * turns grey or tinted.
 */
class ColourCastEstimator {
    private var sumR = 0L
    private var sumG = 0L
    private var sumB = 0L
    private var neutral = 0L
    private var seen = 0L

    /** Adds one pixel (0..255 channels) to the estimate. */
    fun add(r: Int, g: Int, b: Int) {
        seen++
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < MIN_LEVEL || max > MAX_LEVEL) return
        if ((max - min).toFloat() / max > MAX_SATURATION) return
        sumR += r
        sumG += g
        sumB += b
        neutral++
    }

    /**
     * Per-channel gains (red, green, blue) that take out part of the cast, or null when there is
     * none worth correcting or too few neutral pixels to judge it.
     */
    fun gains(): FloatArray? {
        if (seen == 0L || neutral < seen * MIN_NEUTRAL_FRACTION || neutral < MIN_NEUTRAL_PIXELS) return null
        val r = sumR.toDouble() / neutral
        val g = sumG.toDouble() / neutral
        val b = sumB.toDouble() / neutral
        val grey = (r + g + b) / 3
        val raw = doubleArrayOf(grey / r, grey / g, grey / b)
        if (raw.all { kotlin.math.abs(it - 1) < MIN_CAST }) return null
        val partial = raw.map { (1 + (it - 1) * STRENGTH).coerceIn(MIN_GAIN, MAX_GAIN) }
        // Keep the overall brightness where it was.
        val luma = LUMA_R * partial[0] + LUMA_G * partial[1] + LUMA_B * partial[2]
        return FloatArray(3) { (partial[it] / luma).toFloat() }
    }

    companion object {
        private const val MIN_LEVEL = 40
        private const val MAX_LEVEL = 235
        private const val MAX_SATURATION = 0.35f
        private const val MIN_NEUTRAL_FRACTION = 0.05
        private const val MIN_NEUTRAL_PIXELS = 500L
        /** Casts under 2% per channel aren't visible. */
        private const val MIN_CAST = 0.02
        /** Take out about 60% of the cast: neutral enough, but a warm room still feels warm. */
        private const val STRENGTH = 0.6
        private const val MIN_GAIN = 0.88
        private const val MAX_GAIN = 1.12
        private const val LUMA_R = 0.2126
        private const val LUMA_G = 0.7152
        private const val LUMA_B = 0.0722
        private const val HIGHLIGHT_START = 215f

        /** [argb] with [gains] applied, eased off towards full brightness so highlights stay clean. */
        fun correct(argb: Int, gains: FloatArray): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val max = maxOf(r, g, b)
            val keep = ((max - HIGHLIGHT_START) / (255f - HIGHLIGHT_START)).coerceIn(0f, 1f)
            fun channel(v: Int, gain: Float): Int {
                val applied = 1f + (gain - 1f) * (1f - keep)
                return (v * applied + 0.5f).toInt().coerceIn(0, 255)
            }
            return (argb and 0xFF000000.toInt()) or
                (channel(r, gains[0]) shl 16) or (channel(g, gains[1]) shl 8) or channel(b, gains[2])
        }
    }
}
