package com.camoron.data.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.camoron.domain.model.GeoStamp
import com.camoron.domain.model.PixelSize
import java.io.ByteArrayOutputStream

/**
 * Prints Geo Tag's card on a photo: a map pin, the place's name and address, the coordinates and
 * the time, on a dark rounded card along the bottom of the upright picture. The photo comes back
 * upright (its EXIF turn applied), since the card has to read the right way up in every viewer.
 */
internal object GeoStamper {

    /** [bytes] turned upright by [rotationDegrees] (then [mirror]ed), with the card drawn on; and its size. */
    fun stamp(bytes: ByteArray, rotationDegrees: Int, mirror: Boolean, stamp: GeoStamp): Pair<ByteArray, PixelSize> {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Could not decode the photo to stamp")
        val upright = upright(decoded, rotationDegrees, mirror)
        try {
            draw(Canvas(upright), upright.width, upright.height, stamp.lines())
            val out = ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            return out to PixelSize(upright.width, upright.height)
        } finally {
            upright.recycle()
        }
    }

    /** A writable upright copy, or [bitmap] itself when it is already upright and writable. */
    private fun upright(bitmap: Bitmap, rotationDegrees: Int, mirror: Boolean): Bitmap {
        val turn = ((rotationDegrees % 360) + 360) % 360
        if (turn == 0 && !mirror && bitmap.isMutable) return bitmap
        val sideways = turn == 90 || turn == 270
        val width = if (sideways) bitmap.height else bitmap.width
        val height = if (sideways) bitmap.width else bitmap.height
        val matrix = Matrix().apply {
            postRotate(turn.toFloat())
            if (mirror) postScale(-1f, 1f)
            // Move the turned picture back to the origin.
            val box = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()).also { mapRect(it) }
            postTranslate(-box.left, -box.top)
        }
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { out ->
                Canvas(out).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun draw(canvas: Canvas, width: Int, height: Int, lines: List<String>) {
        val unit = minOf(width, height) / 100f
        val margin = 2.5f * unit
        val padding = 2.2f * unit
        val pinBox = 13f * unit
        val gap = 1.2f * unit
        val textWidth = (width - 2 * margin - 2 * padding - pinBox - padding).toInt().coerceAtLeast(1)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 3.6f * unit
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 2.7f * unit
        }
        // The first line is the place's title when there is more than coordinates and time to show.
        val hasTitle = lines.size > 2
        val layouts = lines.mapIndexed { index, line ->
            val paint = if (index == 0 && hasTitle) titlePaint else bodyPaint
            StaticLayout.Builder.obtain(line, 0, line.length, paint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(MAX_LINES_EACH)
                .build()
        }
        val textHeight = layouts.sumOf { it.height } + gap * (layouts.size - 1)
        val cardHeight = maxOf(textHeight, pinBox) + 2 * padding
        val card = RectF(margin, height - margin - cardHeight, width - margin, height - margin)
        canvas.drawRoundRect(card, 2f * unit, 2f * unit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_COLOUR })

        // A map pin in its own tile on the left.
        val tile = RectF(card.left + padding, card.centerY() - pinBox / 2, card.left + padding + pinBox, card.centerY() + pinBox / 2)
        canvas.drawRoundRect(tile, 1.5f * unit, 1.5f * unit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TILE_COLOUR })
        drawPin(canvas, tile.centerX(), tile.centerY(), pinBox * 0.62f)

        var y = card.top + padding + (cardHeight - 2 * padding - textHeight) / 2
        val x = tile.right + padding
        for (layout in layouts) {
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gap
        }
    }

    /** A red map pin [size] tall, centred on ([cx], [cy]). */
    private fun drawPin(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val radius = size * 0.32f
        val headY = cy - size / 2 + radius
        val tipY = cy + size / 2
        val pin = Path().apply {
            addCircle(cx, headY, radius, Path.Direction.CW)
            moveTo(cx - radius * 0.86f, headY + radius * 0.5f)
            lineTo(cx, tipY)
            lineTo(cx + radius * 0.86f, headY + radius * 0.5f)
            close()
        }
        canvas.drawPath(pin, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PIN_COLOUR })
        canvas.drawCircle(cx, headY, radius * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    private const val MAX_LINES_EACH = 3
    private const val CARD_COLOUR = 0xA6000000.toInt()
    private const val TILE_COLOUR = 0xFFF1F3F4.toInt()
    private const val PIN_COLOUR = 0xFFE53935.toInt()
}
