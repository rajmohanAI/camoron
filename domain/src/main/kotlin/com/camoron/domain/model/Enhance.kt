package com.camoron.domain.model

import kotlin.math.ln
import kotlin.math.pow

/**
 * Scene-adaptive tone and colour for one picture, worked out from its brightness histogram:
 * where black and white really sit, how bright the midtones should be, how much to open the
 * shadows, and how much colour to add. The same numbers drive the live preview filter and the
 * saved photo, so what the user sees is what they get.
 *
 * Levels are 0..1 fractions of full scale.
 */
data class EnhanceParams(
    val black: Float,
    val white: Float,
    val gamma: Float,
    /** 0..1 blend towards an S-curve: more punch in the midtones. */
    val contrast: Float,
    /** Shadow opening, peaking around a third of full brightness; 0 leaves shadows alone. */
    val shadowLift: Float,
    /** Extra colour for muted pixels, leaving already-saturated ones alone. */
    val vibrance: Float,
) {
    companion object {
        /** A gentle default used until the camera has reported a histogram. */
        val DEFAULT = EnhanceParams(black = 0.01f, white = 0.99f, gamma = 1f, contrast = 0.2f, shadowLift = 0.04f, vibrance = 0.25f)
    }
}

object EnhanceMath {

    private const val CLIP_LOW = 0.004
    private const val CLIP_HIGH = 0.996
    private const val MAX_BLACK = 0.2f
    private const val MIN_WHITE = 0.6f
    private const val TARGET_MID = 0.47
    private const val DARK_SCENE_MID = 0.3

    /** Parameters for a picture whose brightness histogram (any number of equal-width bins) is [bins]. */
    fun fromHistogram(bins: IntArray): EnhanceParams {
        val total = bins.sumOf { it.toLong() }
        if (total == 0L || bins.size < 2) return EnhanceParams.DEFAULT
        val black = (percentile(bins, total, CLIP_LOW)).coerceAtMost(MAX_BLACK)
        val white = (percentile(bins, total, CLIP_HIGH) + 1f / bins.size).coerceIn(MIN_WHITE, 1f)

        // Mean brightness after levels decides the midtone gamma. Bright scenes (snow, a white wall)
        // are only darkened a little, so they stay bright.
        var weighted = 0.0
        bins.forEachIndexed { i, count ->
            val centre = (i + 0.5) / bins.size
            weighted += count * ((centre - black) / (white - black)).coerceIn(0.0, 1.0)
        }
        val mean = (weighted / total).coerceIn(0.02, 0.98)
        val gamma = (ln(TARGET_MID) / ln(mean)).toFloat().coerceIn(0.65f, 1.15f)

        // Dark scenes get their shadows opened; bright, flat ones get a little more punch instead.
        val quarter = percentile(bins, total, 0.25)
        val shadowLift = when {
            mean < DARK_SCENE_MID -> 0.1f
            quarter < 0.15f -> 0.06f
            else -> 0.03f
        }
        val spread = percentile(bins, total, 0.9) - percentile(bins, total, 0.1)
        val contrast = if (spread < 0.45f) 0.3f else 0.18f
        return EnhanceParams(black, white, gamma, contrast, shadowLift, vibrance = 0.28f)
    }

    /** Eases from [from] to [to] so the live preview doesn't flicker as the scene changes. */
    fun blend(from: EnhanceParams, to: EnhanceParams, amount: Float): EnhanceParams {
        fun mix(a: Float, b: Float) = a + (b - a) * amount
        return EnhanceParams(
            black = mix(from.black, to.black),
            white = mix(from.white, to.white),
            gamma = mix(from.gamma, to.gamma),
            contrast = mix(from.contrast, to.contrast),
            shadowLift = mix(from.shadowLift, to.shadowLift),
            vibrance = mix(from.vibrance, to.vibrance),
        )
    }

    /** The tone curve as a 256-entry lookup table, applied to each colour channel. */
    fun toneCurve(p: EnhanceParams): IntArray = IntArray(256) { i ->
        (curve(p, i / 255f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** One channel through levels, gamma, S-curve and shadow lift. Mirrors the preview shader. */
    fun curve(p: EnhanceParams, value: Float): Float {
        var x = ((value - p.black) / (p.white - p.black)).coerceIn(0f, 1f)
        x = x.pow(p.gamma)
        val s = x * x * (3f - 2f * x)
        x += (s - x) * p.contrast
        x += p.shadowLift * x * (1f - x) * (1f - x) * 4f
        return x.coerceIn(0f, 1f)
    }

    /** Tone curve then vibrance for one opaque ARGB pixel. */
    fun apply(lut: IntArray, vibrance: Float, argb: Int): Int {
        val r = lut[(argb shr 16) and 0xFF]
        val g = lut[(argb shr 8) and 0xFF]
        val b = lut[argb and 0xFF]
        return (argb and 0xFF000000.toInt()) or vibrant(r, g, b, vibrance)
    }

    /** Pushes each channel away from luma, more for muted colours than for saturated ones. */
    fun vibrant(r: Int, g: Int, b: Int, vibrance: Float): Int {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val saturation = (maxOf(r, g, b) - minOf(r, g, b)) / 255f
        val gain = 1f + vibrance * (1f - saturation)
        val nr = (luma + (r - luma) * gain).toInt().coerceIn(0, 255)
        val ng = (luma + (g - luma) * gain).toInt().coerceIn(0, 255)
        val nb = (luma + (b - luma) * gain).toInt().coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }

    /** Brightness (0..1) below which [fraction] of the pixels fall. */
    private fun percentile(bins: IntArray, total: Long, fraction: Double): Float {
        val target = total * fraction
        var running = 0L
        bins.forEachIndexed { i, count ->
            running += count
            if (running >= target) return i.toFloat() / bins.size
        }
        return 1f
    }
}
