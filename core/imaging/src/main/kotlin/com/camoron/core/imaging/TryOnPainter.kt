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
import com.camoron.domain.model.HairTint
import com.camoron.domain.model.Shape2D
import com.camoron.domain.model.TryOn
import com.camoron.domain.model.TryOnDrawing

/**
 * Puts try-ons onto an upright photo: recolours the hair, then draws the masks and spectacles
 * [TryOnDrawing] lays out. Used for both the editor's preview and the saved photo.
 */
object TryOnPainter {

    /** Paints [tryOn] onto [bitmap] (mutable, upright, the photo [scene] was found in). */
    fun paint(bitmap: Bitmap, tryOn: TryOn, scene: FaceScene) {
        if (tryOn.isNone) return
        val hair = scene.hair
        if (tryOn.hair != HairColour.NONE && hair != null && tryOn.hairStrength > 0f) recolourHair(bitmap, tryOn, hair)
        val shapes = TryOnDrawing.shapes(tryOn, scene, bitmap.width, bitmap.height)
        if (shapes.isNotEmpty()) {
            val canvas = Canvas(bitmap)
            shapes.forEach { draw(canvas, it) }
        }
    }

    private fun recolourHair(bitmap: Bitmap, tryOn: TryOn, hair: com.camoron.domain.model.HairMask) {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        val target = tryOn.hair.argb
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val ny = (y + 0.5f) / height
            var changed = false
            for (x in 0 until width) {
                val amount = HairTint.weight(hair.at((x + 0.5f) / width, ny)) * tryOn.hairStrength
                if (amount > 0f) {
                    row[x] = HairTint.apply(row[x], target, amount)
                    changed = true
                }
            }
            if (changed) bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }

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
