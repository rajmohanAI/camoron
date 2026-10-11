package com.camoron.feature.camera

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * ISO and shutter speed are perceived logarithmically (each doubling is one stop), so sliders map
 * 0..1 onto a log scale between the camera's limits.
 */
internal object ExposureScale {

    fun fromSlider(fraction: Float, range: IntRange): Int =
        logInterpolate(fraction, range.first.toDouble(), range.last.toDouble()).roundToInt().coerceIn(range)

    fun fromSlider(fraction: Float, range: LongRange): Long =
        logInterpolate(fraction, range.first.toDouble(), range.last.toDouble()).roundToLong().coerceIn(range)

    fun toSlider(value: Int, range: IntRange): Float = logFraction(value.toDouble(), range.first.toDouble(), range.last.toDouble())

    fun toSlider(value: Long, range: LongRange): Float = logFraction(value.toDouble(), range.first.toDouble(), range.last.toDouble())

    /** "1/250", "1/4", "0.5s", "2s" — the way photographers read shutter speeds. */
    fun formatShutter(exposureNs: Long): String {
        val seconds = exposureNs / 1_000_000_000.0
        return if (seconds < 0.3) {
            "1/${(1 / seconds).roundToInt()}"
        } else {
            val rounded = (seconds * 10).roundToInt() / 10.0
            if (rounded % 1.0 == 0.0) "${rounded.toInt()}s" else "${rounded}s"
        }
    }

    fun formatEv(steps: Int, stepSize: Float): String {
        val ev = steps * stepSize
        val rounded = (ev * 10).roundToInt() / 10f
        return when {
            rounded > 0 -> "+$rounded"
            rounded == 0f -> "0.0"
            else -> rounded.toString()
        }
    }

    private fun logInterpolate(fraction: Float, min: Double, max: Double): Double {
        val lo = min.coerceAtLeast(1.0)
        return lo * (max / lo).pow(fraction.coerceIn(0f, 1f).toDouble())
    }

    private fun logFraction(value: Double, min: Double, max: Double): Float {
        val lo = min.coerceAtLeast(1.0)
        if (max <= lo) return 0f
        return (ln(value.coerceIn(lo, max) / lo) / ln(max / lo)).toFloat()
    }
}
