package com.camoron.domain.model

/**
 * Colour looks applied live to the preview and to the saved photo. [isBasic] looks stay available
 * after the trial; the rest are Pro.
 *
 * "Studio" is Camoron's own soft, warm look (deliberately not named after any trademarked colour
 * system).
 */
enum class ShotLook(val isBasic: Boolean) {
    NORMAL(isBasic = true),
    VIVID(isBasic = false),
    BLACK_AND_WHITE(isBasic = true),
    COOL(isBasic = false),
    WARM(isBasic = false),
    STUDIO(isBasic = false),
}

/**
 * 4x5 colour matrices in Android's ColorMatrix layout (row-major; R' = m0·R + m1·G + m2·B + m3·A + m4,
 * offsets in 0..255 units), so the preview filter and the photo processor share one definition.
 */
object LookMatrix {

    fun forLook(look: ShotLook): FloatArray = when (look) {
        ShotLook.NORMAL -> identity()
        ShotLook.VIVID -> build(saturation = 1.35f, contrast = 1.08f)
        ShotLook.BLACK_AND_WHITE -> build(saturation = 0f, contrast = 1.12f)
        ShotLook.COOL -> build(gainR = 0.92f, gainB = 1.08f, contrast = 1.02f)
        ShotLook.WARM -> build(gainR = 1.08f, gainG = 1.01f, gainB = 0.9f)
        ShotLook.STUDIO -> build(saturation = 0.88f, contrast = 0.94f, gainR = 1.05f, gainB = 0.95f, lift = 10f)
    }

    fun isIdentity(matrix: FloatArray): Boolean = matrix.contentEquals(identity())

    /** Applies [matrix] to one opaque ARGB pixel. Alpha is kept as is. */
    fun apply(matrix: FloatArray, argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val a = (argb ushr 24) and 0xFF
        val nr = channel(matrix, 0, r, g, b, a)
        val ng = channel(matrix, 5, r, g, b, a)
        val nb = channel(matrix, 10, r, g, b, a)
        return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun channel(m: FloatArray, row: Int, r: Int, g: Int, b: Int, a: Int): Int =
        (m[row] * r + m[row + 1] * g + m[row + 2] * b + m[row + 3] * a + m[row + 4]).toInt().coerceIn(0, 255)

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /**
     * Saturation around Rec. 709 luma, then per-channel gains, then contrast around mid-grey, then a
     * shadow lift. Composed into one matrix.
     */
    private fun build(
        saturation: Float = 1f,
        contrast: Float = 1f,
        gainR: Float = 1f,
        gainG: Float = 1f,
        gainB: Float = 1f,
        lift: Float = 0f,
    ): FloatArray {
        val lr = 0.2126f
        val lg = 0.7152f
        val lb = 0.0722f
        val s = saturation
        val sat = arrayOf(
            floatArrayOf(lr * (1 - s) + s, lg * (1 - s), lb * (1 - s)),
            floatArrayOf(lr * (1 - s), lg * (1 - s) + s, lb * (1 - s)),
            floatArrayOf(lr * (1 - s), lg * (1 - s), lb * (1 - s) + s),
        )
        val gains = floatArrayOf(gainR, gainG, gainB)
        // Contrast c around 128: x' = c·x + 128·(1 − c); the lift then raises everything slightly.
        val offset = 128f * (1 - contrast) + lift
        val out = FloatArray(20)
        for (row in 0 until 3) {
            for (col in 0 until 3) out[row * 5 + col] = contrast * gains[row] * sat[row][col]
            out[row * 5 + 4] = offset
        }
        out[18] = 1f
        return out
    }
}

/** Night mode for the top menu. Auto turns it on in low light. */
enum class NightSetting { OFF, AUTO, ON }
