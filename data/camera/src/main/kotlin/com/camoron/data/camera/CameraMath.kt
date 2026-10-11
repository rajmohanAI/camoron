package com.camoron.data.camera

import com.camoron.domain.model.PixelSize
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

    private const val ASPECT_TOLERANCE = 0.02
    private const val MAX_GAIN = 8f
}
