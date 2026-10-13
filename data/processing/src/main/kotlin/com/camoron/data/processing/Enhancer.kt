package com.camoron.data.processing

import android.graphics.Bitmap
import com.camoron.domain.model.EnhanceMath
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * On-device photo enhancement, in place on a decoded photo:
 *
 * 1. Scene-adaptive tone curve and vibrance ([EnhanceMath]), the same ones the preview shows.
 * 2. Local contrast ("clarity"): each pixel is lifted against the average brightness of its
 *    neighbourhood, which brings out texture in skies, faces and foliage without a halo.
 * 3. Fine detail: an unsharp mask on brightness with a noise threshold, so edges get crisper while
 *    flat, noisy areas are left alone.
 *
 * Rows are processed in parallel bands.
 */
internal object Enhancer {

    private const val SHARPEN = 0.6f
    private const val NOISE_FLOOR = 3
    private const val CLARITY = 0.3f
    private const val CLARITY_BLOCK = 32
    private const val HISTOGRAM_BINS = 64
    private const val HISTOGRAM_STEP = 4
    private const val BAND_COUNT = 4

    suspend fun enhance(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return

        val luma = ByteArray(width * height)
        val row = IntArray(width)
        val bins = IntArray(HISTOGRAM_BINS)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val offset = y * width
            for (x in 0 until width) {
                val l = MergeMath.luma(row[x])
                luma[offset + x] = l.toByte()
                if (y % HISTOGRAM_STEP == 0 && x % HISTOGRAM_STEP == 0) bins[l * HISTOGRAM_BINS / 256]++
            }
        }
        val params = EnhanceMath.fromHistogram(bins)
        val lut = EnhanceMath.toneCurve(params)
        val coarse = CoarseLuma(luma, width, height, CLARITY_BLOCK)

        coroutineScope {
            val bandHeight = (height + BAND_COUNT - 1) / BAND_COUNT
            (0 until BAND_COUNT).map { band ->
                async {
                    val top = band * bandHeight
                    val bottom = minOf(height, top + bandHeight)
                    val pixels = IntArray(width)
                    for (y in top until bottom) {
                        bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                        for (x in 0 until width) {
                            val l = luma(luma, width, height, x, y)
                            val blur = blur3x3(luma, width, height, x, y)
                            var detail = l - blur
                            detail = when {
                                detail > NOISE_FLOOR -> detail - NOISE_FLOOR
                                detail < -NOISE_FLOOR -> detail + NOISE_FLOOR
                                else -> 0
                            }
                            // Local contrast mostly in the midtones, never pushing shadows or highlights to clip.
                            val local = l - coarse.at(x, y)
                            val mid = l / 255f
                            val boost = SHARPEN * detail + CLARITY * local * 4f * mid * (1f - mid)
                            pixels[x] = addBrightness(EnhanceMath.apply(lut, params.vibrance, pixels[x]), boost)
                        }
                        bitmap.setPixels(pixels, 0, width, 0, y, width, 1)
                    }
                }
            }.awaitAll()
        }
    }

    private fun luma(plane: ByteArray, width: Int, height: Int, x: Int, y: Int): Int =
        plane[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toInt() and 0xFF

    private fun blur3x3(plane: ByteArray, width: Int, height: Int, x: Int, y: Int): Int {
        var sum = 0
        for (dy in -1..1) for (dx in -1..1) sum += luma(plane, width, height, x + dx, y + dy)
        return sum / 9
    }

    private fun addBrightness(argb: Int, amount: Float): Int {
        if (amount == 0f) return argb
        val delta = amount.toInt()
        val r = (((argb shr 16) and 0xFF) + delta).coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) + delta).coerceIn(0, 255)
        val b = ((argb and 0xFF) + delta).coerceIn(0, 255)
        return (argb and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    /** Average brightness of [block]-sized tiles, read back with bilinear interpolation. */
    private class CoarseLuma(plane: ByteArray, width: Int, height: Int, private val block: Int) {
        private val cols = (width + block - 1) / block
        private val rows = (height + block - 1) / block
        private val values = FloatArray(cols * rows)

        init {
            val counts = IntArray(cols * rows)
            for (y in 0 until height) {
                val tileRow = (y / block) * cols
                val offset = y * width
                for (x in 0 until width) {
                    val i = tileRow + x / block
                    values[i] += (plane[offset + x].toInt() and 0xFF).toFloat()
                    counts[i]++
                }
            }
            for (i in values.indices) if (counts[i] > 0) values[i] /= counts[i]
        }

        fun at(x: Int, y: Int): Float {
            val fx = ((x + 0.5f) / block - 0.5f).coerceIn(0f, (cols - 1).toFloat())
            val fy = ((y + 0.5f) / block - 0.5f).coerceIn(0f, (rows - 1).toFloat())
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = minOf(x0 + 1, cols - 1)
            val y1 = minOf(y0 + 1, rows - 1)
            val tx = fx - x0
            val ty = fy - y0
            val top = values[y0 * cols + x0] * (1 - tx) + values[y0 * cols + x1] * tx
            val bottom = values[y1 * cols + x0] * (1 - tx) + values[y1 * cols + x1] * tx
            return top * (1 - ty) + bottom * ty
        }
    }
}
