package com.camoron.data.camera

import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.StillAspect
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Pure geometry and colour helpers for the Camera2 layer, kept free of Android types for testing. */
internal object CameraMath {

    /** Largest preview stream that fits within 1080p, matches [targetAspect] and is guaranteed alongside max JPEG. */
    fun choosePreviewSize(
        candidates: List<PixelSize>,
        targetAspect: Double,
        maxLongEdge: Int = 1920,
        maxShortEdge: Int = 1080,
    ): PixelSize {
        require(candidates.isNotEmpty()) { "Camera reports no preview sizes" }
        val fitting = candidates.filter {
            maxOf(it.width, it.height) <= maxLongEdge && minOf(it.width, it.height) <= maxShortEdge
        }.ifEmpty { candidates }
        val sameAspect = fitting.filter { abs(aspectOf(it) - targetAspect) < ASPECT_TOLERANCE }
        return (sameAspect.ifEmpty { fitting }).maxBy { it.width.toLong() * it.height }
    }

    fun aspectOf(size: PixelSize): Double =
        maxOf(size.width, size.height).toDouble() / minOf(size.width, size.height)

    /** Clockwise rotation to write into JPEG_ORIENTATION, per the Camera2 reference formula. */
    fun jpegOrientation(sensorOrientation: Int, deviceRotationDegrees: Int, isFrontFacing: Boolean): Int {
        val rounded = ((deviceRotationDegrees + 45) / 90 * 90) % 360
        val signed = if (isFrontFacing) -rounded else rounded
        return (sensorOrientation + signed + 360) % 360
    }

    /**
     * Converts a tap at normalised portrait-view coordinates into normalised sensor coordinates.
     * Front camera previews are mirrored, so x is flipped before rotating.
     */
    fun viewToSensor(x: Float, y: Float, sensorOrientation: Int, isFrontFacing: Boolean): Pair<Float, Float> {
        val vx = if (isFrontFacing) 1f - x else x
        return when (sensorOrientation % 360) {
            90 -> y to 1f - vx
            180 -> 1f - vx to 1f - y
            270 -> 1f - y to vx
            else -> vx to y
        }
    }

    /**
     * Per-channel white balance gains (R, G, B; G fixed at 1) that neutralise a light source of
     * [kelvin]. Uses Tanner Helland's black-body approximation, which is good to a few percent
     * across 2000–10000 K and is enough for a preview-driven manual control.
     */
    fun kelvinToGains(kelvin: Int): Triple<Float, Float, Float> {
        val t = kelvin / 100.0
        val r = if (t <= 66) 255.0 else (329.698727446 * (t - 60).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        val g = if (t <= 66) {
            (99.4708025861 * ln(t) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            (288.1221695283 * (t - 60).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> (138.5177312231 * ln(t - 10) - 305.0447927307).coerceIn(0.0, 255.0)
        }
        // Avoid division by zero at the very warm end where blue approaches 0.
        val safeB = b.coerceAtLeast(1.0)
        return Triple((g / r).toFloat(), 1f, (g / safeB).toFloat().coerceAtMost(MAX_GAIN))
    }

    /** Native aspect ratios with a still size of at least 40% of the largest output's area. */
    fun stillAspects(jpegSizes: List<PixelSize>): Set<StillAspect> {
        val maxArea = jpegSizes.maxOfOrNull { it.width.toLong() * it.height } ?: return setOf(StillAspect.FOUR_THREE)
        val aspects = StillAspect.entries.filter { aspect ->
            jpegSizes.any { abs(aspectOf(it) - aspect.ratio) < ASPECT_TOLERANCE && it.width.toLong() * it.height * 10 >= maxArea * 4 }
        }.toSet()
        return aspects.ifEmpty { setOf(StillAspect.FOUR_THREE) }
    }

    /** Largest still size of [aspect], or the largest overall if the camera has none (cropped later). */
    fun chooseStillSize(jpegSizes: List<PixelSize>, aspect: StillAspect): PixelSize {
        require(jpegSizes.isNotEmpty()) { "Camera reports no JPEG sizes" }
        val matching = jpegSizes.filter { abs(aspectOf(it) - aspect.ratio) < ASPECT_TOLERANCE }
        return matching.ifEmpty { jpegSizes }.maxBy { it.width.toLong() * it.height }
    }

    /** 35mm-equivalent focal length: scales by the ratio of a full-frame diagonal (43.27 mm) to the sensor's. */
    fun equivalentFocalLength(focalLengthMm: Float, sensorDiagonalMm: Float): Float =
        if (sensorDiagonalMm <= 0f) focalLengthMm else focalLengthMm * FULL_FRAME_DIAGONAL_MM / sensorDiagonalMm

    /** EXIF orientation tag value (as written into DNGs) for a clockwise rotation in degrees. */
    fun exifOrientation(rotationDegrees: Int): Int = when ((rotationDegrees % 360 + 360) % 360) {
        90 -> EXIF_ROTATE_90
        180 -> EXIF_ROTATE_180
        270 -> EXIF_ROTATE_270
        else -> EXIF_NORMAL
    }

    /** Smallest analysis stream at least [minWidth] wide; small keeps the histogram cheap. */
    fun chooseAnalysisSize(candidates: List<PixelSize>, minWidth: Int = 320): PixelSize? =
        candidates.filter { maxOf(it.width, it.height) >= minWidth }
            .minByOrNull { it.width.toLong() * it.height }
            ?: candidates.maxByOrNull { it.width.toLong() * it.height }

    /**
     * Luminance histogram from a YUV Y plane, sampling every [step]th pixel in both directions.
     * Reads by absolute index so the buffer's position is left untouched.
     */
    fun luminanceHistogram(
        yPlane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        bins: Int = HISTOGRAM_BINS,
        step: Int = 2,
    ): IntArray {
        val counts = IntArray(bins)
        var y = 0
        while (y < height) {
            val rowStart = y * rowStride
            var x = 0
            while (x < width) {
                val luma = yPlane.get(rowStart + x * pixelStride).toInt() and 0xFF
                counts[luma * bins / 256]++
                x += step
            }
            y += step
        }
        return counts
    }

    const val HISTOGRAM_BINS = 64
    private const val FULL_FRAME_DIAGONAL_MM = 43.27f
    private const val EXIF_NORMAL = 1
    private const val EXIF_ROTATE_180 = 3
    private const val EXIF_ROTATE_90 = 6
    private const val EXIF_ROTATE_270 = 8
    private const val ASPECT_TOLERANCE = 0.02
    private const val MAX_GAIN = 8f
}
