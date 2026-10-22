package com.camoron.domain.model

/**
 * Looks in the photo editor. The first six match the camera's live looks; the rest are film-style
 * looks only the editor offers.
 */
enum class EditLook {
    ORIGINAL,
    VIVID,
    MONO,
    COOL,
    WARM,
    STUDIO,
    FILM,
    FADE,
    CINEMA,
    NOIR,
    GOLDEN,
    MATTE,
}

/** The editor's sliders. Each runs from [min] to 1, with 0 meaning unchanged. */
enum class Adjustment(val min: Float = -1f) {
    EXPOSURE,
    CONTRAST,
    HIGHLIGHTS,
    SHADOWS,
    WARMTH,
    TINT,
    SATURATION,
    VIBRANCE,
    VIGNETTE,
    GRAIN(min = 0f),
}

/**
 * Where the photo is cut and how it is turned. Applied in this order: [quarterTurns] clockwise, the
 * left-right [mirrored] flip, then [straighten] degrees clockwise about the centre. The crop edges are
 * fractions (0..1) of the turned photo's width and height.
 */
data class EditCrop(
    val quarterTurns: Int = 0,
    val mirrored: Boolean = false,
    val straighten: Float = 0f,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val isIdentity: Boolean
        get() = quarterTurns % 4 == 0 && !mirrored && straighten == 0f &&
            left == 0f && top == 0f && right == 1f && bottom == 1f

    /** Whether the turned photo is on its side compared with the original. */
    val sideways: Boolean get() = quarterTurns % 2 == 1

    fun withRect(rect: CropRectF) = copy(left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom)

    val rect: CropRectF get() = CropRectF(left, top, right, bottom)
}

/** A crop rectangle in fractions of the turned photo. */
data class CropRectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    companion object {
        val FULL = CropRectF(0f, 0f, 1f, 1f)
    }
}

/** Shapes the crop can be locked to, as width over height. [ORIGINAL] is the photo's own shape. */
enum class CropAspect(val ratio: Float?) {
    FREE(null),
    ORIGINAL(null),
    SQUARE(1f),
    FOUR_FIVE(4f / 5),
    THREE_FOUR(3f / 4),
    FOUR_THREE(4f / 3),
    NINE_SIXTEEN(9f / 16),
    SIXTEEN_NINE(16f / 9),
}

/** Everything the editor changes about one photo. The original file is never modified. */
data class PhotoEdit(
    val look: EditLook = EditLook.ORIGINAL,
    val lookStrength: Float = 1f,
    val adjustments: Map<Adjustment, Float> = emptyMap(),
    val crop: EditCrop = EditCrop(),
    val tryOn: TryOn = TryOn(),
) {
    fun value(adjustment: Adjustment): Float = adjustments[adjustment] ?: 0f

    fun with(adjustment: Adjustment, value: Float): PhotoEdit {
        val clamped = value.coerceIn(adjustment.min, 1f)
        return copy(adjustments = if (clamped == 0f) adjustments - adjustment else adjustments + (adjustment to clamped))
    }

    /** Whether colours or tones change, as opposed to only the crop. */
    val changesColour: Boolean
        get() = (look != EditLook.ORIGINAL && lookStrength > 0f) || adjustments.values.any { it != 0f }

    val isIdentity: Boolean get() = !changesColour && crop.isIdentity && tryOn.isNone
}
