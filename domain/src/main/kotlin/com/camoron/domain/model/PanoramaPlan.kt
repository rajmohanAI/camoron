package com.camoron.domain.model

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.hypot

/** When to take each panorama frame as the phone turns. */
object PanoramaPlan {
    /** Frames in one panorama at most; about 270° at a typical phone lens. */
    const val MAX_FRAMES = 12

    /** Each frame overlaps the one before by the rest of its width, which leaves room to line them up. */
    const val STEP_OF_VIEW = 0.4f

    /**
     * How far (radians) to turn between frames for a lens of [focalLength35mm] (35 mm equivalent;
     * null for a typical phone lens) zoomed by [zoom], with the photo [portrait] (held upright) and
     * [longOverShort] its long side over its short one.
     */
    fun step(focalLength35mm: Float?, zoom: Float, portrait: Boolean, longOverShort: Float): Float {
        val focal = (focalLength35mm ?: DEFAULT_FOCAL_35MM) * zoom.coerceAtLeast(1f)
        // Focal length in units of the photo's short side.
        val f = focal / FULL_FRAME_DIAGONAL_MM * hypot(1f, longOverShort)
        val across = if (portrait) 1f else longOverShort
        return 2 * atan(across / 2 / f) * STEP_OF_VIEW
    }

    /** [radians] brought into −π..π, for the turn between two headings. */
    fun wrap(radians: Float): Float {
        var a = radians
        while (a > PI) a -= (2 * PI).toFloat()
        while (a < -PI) a += (2 * PI).toFloat()
        return a
    }

    private const val FULL_FRAME_DIAGONAL_MM = 43.27f
    private const val DEFAULT_FOCAL_35MM = 26f
}
