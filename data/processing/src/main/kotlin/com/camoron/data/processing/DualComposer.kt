package com.camoron.data.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import com.camoron.domain.model.PixelSize
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Dual capture: draws the other camera's photo in the top corner of the main one, as a rounded
 * picture with a white edge, like the phone's own dual capture. Both are turned upright first, so
 * the result is stored upright.
 */
internal object DualComposer {

    /** [main] turned upright by [mainRotation] (then [mainMirror]ed) with [inset] drawn on; and its size. */
    fun compose(
        main: ByteArray,
        mainRotation: Int,
        mainMirror: Boolean,
        inset: ByteArray,
        insetMirror: Boolean,
    ): Pair<ByteArray, PixelSize> {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(main, 0, main.size, options) ?: error("Could not decode the main photo")
        val picture = GeoStamper.upright(decoded, mainRotation, mainMirror)
        try {
            val insetRotation = ExifInterface(ByteArrayInputStream(inset)).rotationDegrees
            // The corner picture is small, so decode it at a fraction of its size.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                .also { BitmapFactory.decodeByteArray(inset, 0, inset.size, it) }
            val wanted = minOf(picture.width, picture.height) * INSET_FRACTION
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= wanted) sample *= 2
            val small = BitmapFactory.decodeByteArray(inset, 0, inset.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("Could not decode the second camera's photo")
            val corner = GeoStamper.upright(small, insetRotation, insetMirror)
            try {
                draw(Canvas(picture), picture.width, picture.height, corner)
            } finally {
                corner.recycle()
            }
            val out = ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            return out to PixelSize(picture.width, picture.height)
        } finally {
            picture.recycle()
        }
    }

    private fun draw(canvas: Canvas, width: Int, height: Int, corner: Bitmap) {
        val unit = minOf(width, height) / 100f
        // The short side of the corner picture is a third of the photo's short side.
        val scale = minOf(width, height) * INSET_FRACTION / minOf(corner.width, corner.height)
        val w = corner.width * scale
        val h = corner.height * scale
        val margin = 3f * unit
        val rect = RectF(margin, margin, margin + w, margin + h)
        val radius = 3f * unit
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(corner, null, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * unit
            color = Color.WHITE
        }
        canvas.drawRoundRect(rect, radius, radius, edge)
    }

    private const val INSET_FRACTION = 1f / 3
    private const val JPEG_QUALITY = 95
}
