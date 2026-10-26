package com.camoron.core.imaging

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.camoron.domain.model.FaceScene
import com.camoron.domain.model.HairColour
import com.camoron.domain.model.HairMask
import com.camoron.domain.model.HairTint
import com.camoron.domain.model.Hairstyle
import com.camoron.domain.model.Inpaint
import com.camoron.domain.model.Shape2D
import com.camoron.domain.model.TryOn
import com.camoron.domain.model.TryOnDrawing

/**
 * Puts try-ons onto an upright photo: paints out hair a new hairstyle replaces, recolours the
 * rest of the hair, then draws what [TryOnDrawing] lays out. Used for both the editor's preview
 * and the saved photo.
 */
object TryOnPainter {

    /** What [clearHair] did: the pixels it rebuilt, and the person's own hair colour from before. */
    class ClearedHair(val removed: BooleanArray?, val ownHair: Int?) {
        companion object {
            val NONE = ClearedHair(null, null)
        }
    }

    /**
     * Paints out the hair that [hairstyle] replaces on [bitmap] (mutable, upright, the photo
     * [scene] was found in). This is the slow part of a new hairstyle (about a second) and only
     * depends on the style, so the editor keeps the result while other choices change.
     */
    fun clearHair(bitmap: Bitmap, hairstyle: Hairstyle, scene: FaceScene): ClearedHair {
        val hair = scene.hair ?: return ClearedHair.NONE
        if (hairstyle == Hairstyle.NONE) return ClearedHair.NONE
        val ownHair = averageHair(bitmap, hair)
        val hole = TryOnDrawing.hairToRemove(TryOn(hairstyle = hairstyle), scene, bitmap.width, bitmap.height)
        if (hole != null) removeHair(bitmap, hole)
        return ClearedHair(hole, ownHair)
    }

    /** Paints [tryOn] onto [bitmap] (mutable, upright, the photo [scene] was found in). */
    fun paint(bitmap: Bitmap, tryOn: TryOn, scene: FaceScene) {
        if (tryOn.isNone) return
        paint(bitmap, tryOn, scene, clearHair(bitmap, tryOn.hairstyle, scene))
    }

    /** Paints [tryOn] onto [bitmap], on which [clearHair] has already run for [tryOn]'s hairstyle and given [cleared]. */
    fun paint(bitmap: Bitmap, tryOn: TryOn, scene: FaceScene, cleared: ClearedHair) {
        if (tryOn.isNone) return
        val hair = scene.hair
        if (tryOn.hair != HairColour.NONE && hair != null && tryOn.hairStrength > 0f) recolourHair(bitmap, tryOn, hair, cleared.removed)
        // A new hairstyle with no colour chosen takes the person's own hair colour.
        val shapes = TryOnDrawing.shapes(tryOn, scene, bitmap.width, bitmap.height, cleared.ownHair)
        if (shapes.isNotEmpty()) {
            val canvas = Canvas(bitmap)
            shapes.forEach { draw(canvas, it) }
        }
    }

    /** The average colour of the surest hair pixels, or null if there are none. */
    private fun averageHair(bitmap: Bitmap, hair: HairMask): Int? {
        val width = bitmap.width
        val height = bitmap.height
        val stride = maxOf(1, maxOf(width, height) / 256)
        val row = IntArray(width)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in 0 until height step stride) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val ny = (y + 0.5f) / height
            for (x in 0 until width step stride) {
                if (hair.at((x + 0.5f) / width, ny) > SURE_HAIR) {
                    r += (row[x] shr 16) and 0xFF
                    g += (row[x] shr 8) and 0xFF
                    b += row[x] and 0xFF
                    n++
                }
            }
        }
        if (n == 0) return null
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Rebuilds [hole] (true per pixel of [bitmap]) from the background round it. */
    private fun removeHair(bitmap: Bitmap, hole: BooleanArray) {
        val width = bitmap.width
        val box = Inpaint.bounds(hole, width, bitmap.height) ?: return
        val (x0, y0, x1, y1) = Inpaint.area(box, width, bitmap.height)
        val w = x1 - x0
        val h = y1 - y0
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x0, y0, w, h)
        val part = BooleanArray(w * h) { i -> hole[(y0 + i / w) * width + x0 + i % w] }
        bitmap.setPixels(Inpaint.fill(pixels, w, h, part), 0, w, x0, y0, w, h)
    }

    /** Recolours the hair, apart from pixels [removed] already painted out. */
    private fun recolourHair(bitmap: Bitmap, tryOn: TryOn, hair: HairMask, removed: BooleanArray?) {
        val width = bitmap.width
        val height = bitmap.height
        // Only the part of the photo where there is hair to recolour, read and written in one go.
        val box = hair.bounds(HairTint.EDGE_LOW) ?: return
        val x0 = (box[0] * width).toInt().coerceIn(0, width)
        val y0 = (box[1] * height).toInt().coerceIn(0, height)
        val x1 = kotlin.math.ceil(box[2] * width).toInt().coerceIn(x0, width)
        val y1 = kotlin.math.ceil(box[3] * height).toInt().coerceIn(y0, height)
        val w = x1 - x0
        val h = y1 - y0
        if (w == 0 || h == 0) return
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x0, y0, w, h)
        val target = tryOn.hair.argb
        for (y in 0 until h) {
            val ny = (y0 + y + 0.5f) / height
            for (x in 0 until w) {
                if (removed != null && removed[(y0 + y) * width + x0 + x]) continue
                val amount = HairTint.weight(hair.at((x0 + x + 0.5f) / width, ny)) * tryOn.hairStrength
                if (amount > 0f) pixels[y * w + x] = HairTint.apply(pixels[y * w + x], target, amount)
            }
        }
        bitmap.setPixels(pixels, 0, w, x0, y0, w, h)
    }

    private const val SURE_HAIR = 0.9f

    private fun draw(canvas: Canvas, shape: Shape2D) {
        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
        for (contour in shape.contours) {
            if (contour.size < 4) continue
            path.moveTo(contour[0], contour[1])
            var i = 2
            while (i < contour.size) {
                path.lineTo(contour[i], contour[i + 1])
                i += 2
            }
            if (shape.closed) path.close()
        }
        val blur = if (shape.blur > 0f) BlurMaskFilter(shape.blur, BlurMaskFilter.Blur.NORMAL) else null
        if (shape.closed && (shape.fill != null || shape.gradient != null)) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                maskFilter = blur
                val gradient = shape.gradient
                if (gradient != null) {
                    shader = LinearGradient(gradient.x0, gradient.y0, gradient.x1, gradient.y1, gradient.from, gradient.to, Shader.TileMode.CLAMP)
                } else {
                    color = shape.fill ?: 0
                }
            }
            canvas.drawPath(path, paint)
        }
        val stroke = shape.stroke
        if (stroke != null && shape.strokeWidth > 0f) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = stroke
                strokeWidth = shape.strokeWidth
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                maskFilter = blur
            }
            canvas.drawPath(path, paint)
        }
    }
}
