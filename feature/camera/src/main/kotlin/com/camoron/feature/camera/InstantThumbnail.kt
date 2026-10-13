package com.camoron.feature.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.camoron.domain.model.FrameBurst

/** Turns the first frame of a burst into an upright thumbnail, in a few milliseconds. */
internal object InstantThumbnail {

    private const val MAX_SIDE = 256

    fun from(burst: FrameBurst, mirror: Boolean): ImageBitmap {
        val small = burst.frames.first().thumbnail(MAX_SIDE)
        val bitmap = Bitmap.createBitmap(small.pixels, small.width, small.height, Bitmap.Config.ARGB_8888)
        val turn = Matrix().apply {
            postRotate(burst.orientationDegrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, turn, true)
        if (upright !== bitmap) bitmap.recycle()
        return upright.asImageBitmap()
    }
}
