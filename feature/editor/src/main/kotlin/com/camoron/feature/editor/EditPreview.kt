package com.camoron.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.camoron.domain.model.CropMath
import com.camoron.domain.model.EditColour
import com.camoron.domain.model.EditCrop
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.math.max

/**
 * Renders edits onto the screen-sized working copy of the photo, with the same maths the saved
 * photo gets, so the preview is what will be saved.
 */
internal object EditPreview {

    /** [working] (upright) turned, flipped, straightened and cropped. */
    fun cut(working: Bitmap, crop: EditCrop): Bitmap {
        val (width, height) = CropMath.outputSize(working.width, working.height, crop)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(working, CropMath.transform(working.width, working.height, crop).toMatrix(), PAINT)
        return out
    }

    /** The whole turned and straightened picture, for choosing the crop on. */
    fun frame(working: Bitmap, crop: EditCrop): Bitmap = cut(working, crop.copy(left = 0f, top = 0f, right = 1f, bottom = 1f))

    /** A recoloured copy of [source]; cancellable between bands of rows. */
    suspend fun colour(source: Bitmap, colour: EditColour): Bitmap = coroutineScope {
        if (colour.isIdentity) return@coroutineScope source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val cell = EditColour.grainCell(width, height)
        val bandHeight = (height + BANDS - 1) / BANDS
        (0 until BANDS).map { band ->
            async {
                val mine = colour.copyForThread()
                for (y in band * bandHeight until minOf(height, (band + 1) * bandHeight)) {
                    if (y % CANCEL_CHECK_ROWS == 0) ensureActive()
                    mine.process(pixels, y, width, height, cell, offset = y * width)
                }
            }
        }.awaitAll()
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** [source] scaled so its longer edge is [edge] pixels. */
    fun scaled(source: Bitmap, edge: Int): Bitmap {
        val scale = edge.toFloat() / max(source.width, source.height)
        if (scale >= 1f) return source
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private val PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private const val BANDS = 4
    private const val CANCEL_CHECK_ROWS = 32
}
