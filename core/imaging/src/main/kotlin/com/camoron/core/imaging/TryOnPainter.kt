package com.camoron.core.imaging

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.camoron.domain.model.FaceScene
import com.camoron.domain.model.GlassesStyle
import com.camoron.domain.model.HairColour
import com.camoron.domain.model.HairMask
import com.camoron.domain.model.HairTint
import com.camoron.domain.model.Hairstyle
import com.camoron.domain.model.Inpaint
import com.camoron.domain.model.MakeupArea
import com.camoron.domain.model.MakeupKind
import com.camoron.domain.model.MakeupTint
import com.camoron.domain.model.PhotoGlasses
import com.camoron.domain.model.PhotoPixels
import com.camoron.domain.model.Shape2D
import com.camoron.domain.model.TryOn
import com.camoron.domain.model.TryOnDrawing

/**
 * Puts try-ons onto an upright photo: paints out hair a new hairstyle replaces, recolours the
 * rest of the hair, then draws what [TryOnDrawing] lays out and renders glasses with
 * [PhotoGlasses]. Used for both the editor's preview and the saved photo.
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
        for (area in TryOnDrawing.makeup(tryOn, scene, bitmap.width, bitmap.height)) applyMakeup(bitmap, area)
        // A new hairstyle with no colour chosen takes the person's own hair colour. Glasses are
        // rendered into the pixels afterwards, on top, rather than drawn as shapes.
        val shapes = TryOnDrawing.shapes(tryOn.copy(glasses = GlassesStyle.NONE), scene, bitmap.width, bitmap.height, cleared.ownHair)
        if (shapes.isNotEmpty()) {
            val canvas = Canvas(bitmap)
            shapes.forEach { draw(canvas, it) }
        }
        if (tryOn.glasses != GlassesStyle.NONE) PhotoGlasses.render(BitmapPixels(bitmap), tryOn, scene)
    }

    /** [bitmap]'s pixels for the domain's pixel renderers. */
    private class BitmapPixels(private val bitmap: Bitmap) : PhotoPixels {
        override val width get() = bitmap.width
        override val height get() = bitmap.height
        override fun read(x0: Int, y0: Int, w: Int, h: Int) = IntArray(w * h).also { bitmap.getPixels(it, 0, w, x0, y0, w, h) }
        override fun write(pixels: IntArray, x0: Int, y0: Int, w: Int, h: Int) = bitmap.setPixels(pixels, 0, w, x0, y0, w, h)
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

    /** Lays [area]'s colour into the photo through a soft-edged mask of its outline. */
    private fun applyMakeup(bitmap: Bitmap, area: MakeupArea) {
        val margin = area.feather * 2f + 2f
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (c in area.contours) {
            for (i in c.indices step 2) {
                minX = minOf(minX, c[i])
                maxX = maxOf(maxX, c[i])
                minY = minOf(minY, c[i + 1])
                maxY = maxOf(maxY, c[i + 1])
            }
        }
        val x0 = (minX - margin).toInt().coerceIn(0, bitmap.width)
        val y0 = (minY - margin).toInt().coerceIn(0, bitmap.height)
        val x1 = (maxX + margin).toInt().coerceIn(x0, bitmap.width)
        val y1 = (maxY + margin).toInt().coerceIn(y0, bitmap.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return
        val mask = mask(area.contours, x0, y0, w, h, area.feather)
        val clip = area.clip?.let { mask(it, x0, y0, w, h, area.feather * 0.5f) }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x0, y0, w, h)
        for (i in pixels.indices) {
            var amount = (mask[i].toInt() and 0xFF) / 255f
            if (clip != null) amount *= (clip[i].toInt() and 0xFF) / 255f
            if (amount <= 0f) continue
            pixels[i] = when (area.kind) {
                MakeupKind.LIPS -> MakeupTint.lips(pixels[i], area.colour, amount)
                MakeupKind.BLUSH -> MakeupTint.blush(pixels[i], area.colour, amount)
                MakeupKind.EYES -> MakeupTint.eyes(pixels[i], area.colour, amount)
            }
        }
        bitmap.setPixels(pixels, 0, w, x0, y0, w, h)
    }

    /** [contours] (even-odd) filled into a [w] x [h] coverage mask at ([x0], [y0]), edges blurred by [feather] pixels. */
    private fun mask(contours: List<FloatArray>, x0: Int, y0: Int, w: Int, h: Int, feather: Float): ByteArray {
        val alpha = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
        for (c in contours) {
            if (c.size < 6) continue
            path.moveTo(c[0] - x0, c[1] - y0)
            for (i in 2 until c.size step 2) path.lineTo(c[i] - x0, c[i + 1] - y0)
            path.close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            if (feather >= 0.5f) maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(alpha).drawPath(path, paint)
        // Rows may be padded, so read them as stored and pack them.
        val stride = alpha.rowBytes
        val raw = ByteArray(stride * h)
        alpha.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(raw))
        alpha.recycle()
        if (stride == w) return raw
        val out = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(raw, y * stride, out, y * w, w)
        return out
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
