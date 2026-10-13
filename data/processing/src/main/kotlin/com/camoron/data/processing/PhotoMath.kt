package com.camoron.data.processing

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Pure geometry for photo adjustments, kept free of Android types so it is unit-testable. */
internal object PhotoMath {

    /**
     * Centre crop of a [width]×[height] image so its long edge over short edge equals [longOverShort].
     *
     * The preview always crops along the sensor's axes, so working in long/short terms keeps the
     * result right whichever way the phone was held. Returns null when no crop is needed.
     */
    fun centreCrop(width: Int, height: Int, longOverShort: Float): CropRect? {
        if (width <= 0 || height <= 0 || longOverShort < 1f) return null
        val long = maxOf(width, height)
        val short = minOf(width, height)
        val current = long.toFloat() / short
        if (abs(current - longOverShort) / longOverShort < TOLERANCE) return null

        val (newLong, newShort) = if (current > longOverShort) {
            (short * longOverShort).roundToInt() to short
        } else {
            long to (long / longOverShort).roundToInt()
        }
        val (newWidth, newHeight) = if (width >= height) newLong to newShort else newShort to newLong
        val left = (width - newWidth) / 2
        val top = (height - newHeight) / 2
        return CropRect(left, top, left + newWidth, top + newHeight)
    }

    /**
     * EXIF orientation that shows the same picture flipped left-to-right as displayed. Combined with
     * the rotation already in the tag, so a selfie taken in any orientation mirrors correctly.
     */
    fun mirroredOrientation(orientation: Int): Int = when (orientation) {
        1 -> 2
        2 -> 1
        3 -> 4
        4 -> 3
        5 -> 6
        6 -> 5
        7 -> 8
        8 -> 7
        else -> 2
    }

    /** EXIF orientation tag for a clockwise rotation in degrees (rounded to a quarter turn). */
    fun orientationTag(degrees: Int): Int = when (((degrees % 360) + 360 + 45) / 90 % 4) {
        1 -> 6
        2 -> 3
        3 -> 8
        else -> 1
    }

    private const val TOLERANCE = 0.01f
}
