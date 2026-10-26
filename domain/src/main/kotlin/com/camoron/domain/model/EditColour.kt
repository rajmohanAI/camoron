package com.camoron.domain.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The colour side of a [PhotoEdit]. Every per-pixel colour change (exposure, white balance, tone,
 * look, saturation) is baked into one 3D lookup table, so the preview and the full-size photo
 * share exactly the same maths and each pixel costs a single table lookup. Vignette and grain
 * depend on where the pixel is, so they are applied separately in [process].
 *
 * Channels are 0..1 sRGB values throughout.
 */
class EditColour private constructor(
    private val lut: FloatArray?,
    private val vignette: Float,
    private val grain: Float,
) {
    val isIdentity: Boolean get() = lut == null && vignette == 0f && grain == 0f

    /**
     * Applies the edit to one row of opaque ARGB pixels in place. [y] is the row's position in a
     * picture [width] x [height], which places the vignette; [grainCell] is the size of one grain
     * speck in pixels, so a full-size photo and its smaller preview get grain of the same look.
     */
    fun process(row: IntArray, y: Int, width: Int, height: Int, grainCell: Int = 1, offset: Int = 0) {
        if (isIdentity) return
        val ny = if (height > 1) (2f * y / (height - 1) - 1f) else 0f
        val ny2 = ny * ny
        val cell = grainCell.coerceAtLeast(1)
        for (x in 0 until width) {
            val i = offset + x
            val argb = row[i]
            var r = ((argb shr 16) and 0xFF) / 255f
            var g = ((argb shr 8) and 0xFF) / 255f
            var b = (argb and 0xFF) / 255f
            if (lut != null) {
                lookup(lut, r, g, b, out)
                r = out[0]
                g = out[1]
                b = out[2]
            }
            if (vignette != 0f) {
                val nx = if (width > 1) (2f * x / (width - 1) - 1f) else 0f
                val gain = vignetteGain(vignette, sqrt(nx * nx + ny2))
                r *= gain
                g *= gain
                b *= gain
            }
            if (grain != 0f) {
                val luma = LUMA_R * r + LUMA_G * g + LUMA_B * b
                // Film grain shows most in the midtones and least in deep shadow and bright white.
                val amount = grain * MAX_GRAIN * (1f - 0.7f * abs(2f * luma - 1f))
                val n = noise(x / cell, y / cell) * amount
                r += n
                g += n
                b += n
            }
            row[i] = (argb and ALPHA) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
        }
    }

    // One scratch triple per instance; process runs one row at a time per instance.
    private val out = FloatArray(3)

    /** A copy that can run on another thread at the same time as this one. */
    fun copyForThread(): EditColour = EditColour(lut, vignette, grain)

    companion object {
        const val LUT_SIZE = 33
        private const val ALPHA = 0xFF shl 24
        private const val LUMA_R = 0.2126f
        private const val LUMA_G = 0.7152f
        private const val LUMA_B = 0.0722f
        private const val MAX_GRAIN = 0.09f
        private const val EXPOSURE_STOPS = 2f
        private const val WARMTH_GAIN = 0.14f
        private const val TINT_GAIN = 0.1f
        private const val SHOULDER = 0.8f

        /** Grain specks scale with the picture, so a preview and the saved photo look alike. */
        fun grainCell(width: Int, height: Int): Int = max(1, (max(width, height) / GRAIN_REFERENCE_EDGE).roundToInt())

        private const val GRAIN_REFERENCE_EDGE = 1600f

        fun of(edit: PhotoEdit): EditColour {
            val colourChanges = (edit.look != EditLook.ORIGINAL && edit.lookStrength > 0f) ||
                Adjustment.entries.any { it != Adjustment.VIGNETTE && it != Adjustment.GRAIN && edit.value(it) != 0f }
            val lut = if (colourChanges) buildLut(edit) else null
            return EditColour(lut, edit.value(Adjustment.VIGNETTE), edit.value(Adjustment.GRAIN))
        }

        /** The colour change for one pixel, written into [rgb]. Exposed for tests and the table. */
        fun transform(edit: PhotoEdit, rgb: FloatArray) {
            var r = rgb[0]
            var g = rgb[1]
            var b = rgb[2]

            // Exposure and white balance act on light, so they run in linear values.
            val stops = edit.value(Adjustment.EXPOSURE) * EXPOSURE_STOPS
            val warmth = edit.value(Adjustment.WARMTH)
            val tint = edit.value(Adjustment.TINT)
            if (stops != 0f || warmth != 0f || tint != 0f) {
                val gain = 2f.pow(stops)
                r = fromLinear(shoulder(toLinear(r) * gain * (1f + WARMTH_GAIN * warmth)))
                g = fromLinear(shoulder(toLinear(g) * gain * (1f - TINT_GAIN * tint)))
                b = fromLinear(shoulder(toLinear(b) * gain * (1f - WARMTH_GAIN * warmth)))
            }

            val contrast = edit.value(Adjustment.CONTRAST)
            val highlights = edit.value(Adjustment.HIGHLIGHTS)
            val shadows = edit.value(Adjustment.SHADOWS)
            if (contrast != 0f || highlights != 0f || shadows != 0f) {
                r = tone(r, contrast, highlights, shadows)
                g = tone(g, contrast, highlights, shadows)
                b = tone(b, contrast, highlights, shadows)
            }

            rgb[0] = r
            rgb[1] = g
            rgb[2] = b

            if (edit.look != EditLook.ORIGINAL && edit.lookStrength > 0f) {
                val s = edit.lookStrength.coerceIn(0f, 1f)
                look(edit.look, rgb)
                rgb[0] = r + (rgb[0] - r) * s
                rgb[1] = g + (rgb[1] - g) * s
                rgb[2] = b + (rgb[2] - b) * s
            }

            val saturation = edit.value(Adjustment.SATURATION)
            if (saturation != 0f) saturate(rgb, 1f + saturation)
            val vibrance = edit.value(Adjustment.VIBRANCE)
            if (vibrance != 0f) {
                val spread = max(rgb[0], max(rgb[1], rgb[2])) - min(rgb[0], min(rgb[1], rgb[2]))
                // Muted colours move the most; colours that are already strong barely change.
                saturate(rgb, 1f + vibrance * 0.8f * (1f - spread).coerceIn(0f, 1f))
            }
            for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
        }

        /** Contrast as an S-curve (or its inverse), then highlight and shadow recovery or boost. */
        internal fun tone(v: Float, contrast: Float, highlights: Float, shadows: Float): Float {
            var x = v.coerceIn(0f, 1f)
            if (contrast > 0f) {
                val s = if (x < 0.5f) 2f * x * x else 1f - 2f * (1f - x) * (1f - x)
                x += (s - x) * contrast
            } else if (contrast < 0f) {
                val s = if (x < 0.5f) sqrt(x / 2f) else 1f - sqrt((1f - x) / 2f)
                x += (s - x) * -contrast
            }
            if (highlights != 0f) {
                val w = smoothstep(0.4f, 1f, x)
                x += highlights * 0.3f * w * (if (highlights < 0f) x - 0.35f else 1f - x)
            }
            if (shadows != 0f) {
                val w = 1f - smoothstep(0f, 0.6f, x)
                x += shadows * 0.3f * w * (if (shadows > 0f) 0.65f - x else x)
            }
            return x.coerceIn(0f, 1f)
        }

        /** Each look as a full-strength change; [transform] blends it by the strength slider. */
        internal fun look(look: EditLook, rgb: FloatArray) {
            when (look) {
                EditLook.ORIGINAL -> Unit
                EditLook.VIVID -> matrix(LookMatrix.forLook(ShotLook.VIVID), rgb)
                EditLook.MONO -> matrix(LookMatrix.forLook(ShotLook.BLACK_AND_WHITE), rgb)
                EditLook.COOL -> matrix(LookMatrix.forLook(ShotLook.COOL), rgb)
                EditLook.WARM -> matrix(LookMatrix.forLook(ShotLook.WARM), rgb)
                EditLook.STUDIO -> matrix(LookMatrix.forLook(ShotLook.STUDIO), rgb)
                EditLook.FILM -> {
                    // Warm print film: soft contrast, lifted blacks with a hint of green, gentle highlights.
                    gains(rgb, 1.03f, 1f, 0.95f)
                    curves(rgb, contrast = 0.25f, black = 0.04f, white = 0.96f)
                    val shade = 1f - luma(rgb)
                    rgb[1] += 0.02f * shade * shade
                    saturate(rgb, 0.9f)
                }
                EditLook.FADE -> {
                    curves(rgb, contrast = -0.25f, black = 0.1f, white = 0.94f)
                    saturate(rgb, 0.72f)
                }
                EditLook.CINEMA -> {
                    // Teal in the shadows, warm skin and highlights.
                    curves(rgb, contrast = 0.2f)
                    val l = luma(rgb)
                    val split = l - 0.5f
                    rgb[0] += 0.12f * split
                    rgb[1] += 0.015f * split
                    rgb[2] -= 0.12f * split
                    saturate(rgb, 1.05f)
                }
                EditLook.NOIR -> {
                    // Black and white through a red filter: darker skies, brighter skin, strong contrast.
                    val l = (0.5f * rgb[0] + 0.4f * rgb[1] + 0.1f * rgb[2]).coerceIn(0f, 1f)
                    rgb[0] = l
                    rgb[1] = l
                    rgb[2] = l
                    curves(rgb, contrast = 0.55f, black = -0.02f)
                }
                EditLook.GOLDEN -> {
                    gains(rgb, 1.08f, 1.02f, 0.86f)
                    curves(rgb, contrast = 0.1f, black = 0.02f)
                    saturate(rgb, 1.1f)
                }
                EditLook.MATTE -> {
                    curves(rgb, contrast = 0.12f, black = 0.08f, white = 0.92f)
                    saturate(rgb, 0.85f)
                }
            }
            for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
        }

        internal fun buildLut(edit: PhotoEdit): FloatArray {
            val n = LUT_SIZE
            val table = FloatArray(n * n * n * 3)
            val rgb = FloatArray(3)
            var i = 0
            for (bi in 0 until n) {
                for (gi in 0 until n) {
                    for (ri in 0 until n) {
                        rgb[0] = ri / (n - 1f)
                        rgb[1] = gi / (n - 1f)
                        rgb[2] = bi / (n - 1f)
                        transform(edit, rgb)
                        table[i++] = rgb[0]
                        table[i++] = rgb[1]
                        table[i++] = rgb[2]
                    }
                }
            }
            return table
        }

        /** Trilinear lookup: red runs fastest in the table, then green, then blue. */
        internal fun lookup(lut: FloatArray, r: Float, g: Float, b: Float, out: FloatArray) {
            val n = LUT_SIZE
            val fr = r * (n - 1)
            val fg = g * (n - 1)
            val fb = b * (n - 1)
            val r0 = min(fr.toInt(), n - 2)
            val g0 = min(fg.toInt(), n - 2)
            val b0 = min(fb.toInt(), n - 2)
            val dr = fr - r0
            val dg = fg - g0
            val db = fb - b0
            val strideG = n * 3
            val strideB = n * n * 3
            val base = b0 * strideB + g0 * strideG + r0 * 3
            for (c in 0..2) {
                val p = base + c
                val c00 = lut[p] + (lut[p + 3] - lut[p]) * dr
                val c10 = lut[p + strideG] + (lut[p + strideG + 3] - lut[p + strideG]) * dr
                val c01 = lut[p + strideB] + (lut[p + strideB + 3] - lut[p + strideB]) * dr
                val c11 = lut[p + strideB + strideG] + (lut[p + strideB + strideG + 3] - lut[p + strideB + strideG]) * dr
                val c0 = c00 + (c10 - c00) * dg
                val c1 = c01 + (c11 - c01) * dg
                out[c] = c0 + (c1 - c0) * db
            }
        }

        /** Darkens (positive) or brightens (negative) towards the corners; [d] is 0 at the centre, ~1.41 in a corner. */
        internal fun vignetteGain(amount: Float, d: Float): Float {
            val w = smoothstep(0.35f, 1.3f, d)
            return if (amount > 0f) 1f - amount * 0.65f * w else 1f - amount * 0.4f * w
        }

        /** Repeatable noise in -1..1 for a grain cell, so the same photo always gets the same grain. */
        internal fun noise(x: Int, y: Int): Float {
            var h = x * 374761393 + y * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFF) / 32767.5f - 1f
        }

        private fun matrix(m: FloatArray, rgb: FloatArray) {
            val r = rgb[0]
            val g = rgb[1]
            val b = rgb[2]
            for (row in 0..2) {
                val o = row * 5
                // LookMatrix offsets are in 0..255 units and alpha is opaque (1).
                rgb[row] = m[o] * r + m[o + 1] * g + m[o + 2] * b + (m[o + 3] * 255f + m[o + 4]) / 255f
            }
        }

        private fun gains(rgb: FloatArray, r: Float, g: Float, b: Float) {
            rgb[0] *= r
            rgb[1] *= g
            rgb[2] *= b
        }

        /** S-curve [contrast], then maps 0..1 onto [black]..[white]. */
        private fun curves(rgb: FloatArray, contrast: Float = 0f, black: Float = 0f, white: Float = 1f) {
            for (i in 0..2) {
                val v = tone(rgb[i], contrast, 0f, 0f)
                rgb[i] = black + v * (white - black)
            }
        }

        private fun saturate(rgb: FloatArray, factor: Float) {
            val l = luma(rgb)
            for (i in 0..2) rgb[i] = l + (rgb[i] - l) * factor
        }

        private fun luma(rgb: FloatArray) = LUMA_R * rgb[0] + LUMA_G * rgb[1] + LUMA_B * rgb[2]

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun toLinear(v: Float) = v.coerceIn(0f, 1f).pow(2.2f)

        private fun fromLinear(v: Float) = v.coerceIn(0f, 1f).pow(1f / 2.2f)

        /** Rolls bright values off smoothly instead of clipping them flat when exposure is raised. */
        private fun shoulder(v: Float): Float =
            if (v <= SHOULDER) v else SHOULDER + (1f - SHOULDER) * (v - SHOULDER) / (v - SHOULDER + 1f - SHOULDER)

        private fun to8(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}
