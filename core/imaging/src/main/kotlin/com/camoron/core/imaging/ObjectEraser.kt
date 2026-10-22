package com.camoron.core.imaging

import android.graphics.Bitmap
import com.camoron.domain.model.EraseMask
import com.camoron.domain.model.Inpaint

/**
 * The magic eraser: removes what each [EraseMask] covers from an upright photo and fills the
 * space with matching background ([Inpaint]). Works at any size, from the screen preview to the
 * full photo, touching only the area around each erase.
 */
object ObjectEraser {

    /** Removes each of [erases] from [bitmap] (upright, mutable ARGB_8888), in order. Blocking. */
    fun erase(bitmap: Bitmap, erases: List<EraseMask>) {
        for (mask in erases) erase(bitmap, mask)
    }

    /** Removes what [mask] covers from [bitmap] (upright, mutable ARGB_8888). Blocking. */
    fun erase(bitmap: Bitmap, mask: EraseMask) {
        val width = bitmap.width
        val height = bitmap.height
        val grow = EraseMask.grow(width, height)
        val area = mask.area(width, height, grow) ?: return
        val (x0, y0, x1, y1) = area
        val w = x1 - x0
        val h = y1 - y0
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x0, y0, w, h)
        val filled = Inpaint.fill(pixels, w, h, mask.hole(width, height, grow, area))
        bitmap.setPixels(filled, 0, w, x0, y0, w, h)
    }
}
