package com.camoron.feature.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.camoron.domain.model.CapturedImage
import java.io.ByteArrayInputStream

/** Turns the camera's photo into a small upright thumbnail, quick enough to show as the shot is taken. */
internal object InstantThumbnail {

    private const val MAX_SIDE = 256

    fun from(image: CapturedImage, mirror: Boolean): ImageBitmap? {
        val bytes = image.bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val degrees = when (
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val turn = Matrix().apply {
            postRotate(degrees)
            if (mirror) postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(small, 0, 0, small.width, small.height, turn, true)
        if (upright !== small) small.recycle()
        return upright.asImageBitmap()
    }
}
