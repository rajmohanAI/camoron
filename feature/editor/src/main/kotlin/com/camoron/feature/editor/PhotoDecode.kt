package com.camoron.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.camoron.domain.model.CropMath
import java.io.ByteArrayInputStream
import kotlin.math.max

/** Decoding photos at screen size, turned upright by their EXIF orientation. */
internal object PhotoDecode {

    /** [bytes] decoded upright, with its longer edge at most [maxEdge] pixels. */
    fun upright(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge > maxEdge) {
            val scale = maxEdge.toFloat() / longEdge
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        if (orientation <= ExifInterface.ORIENTATION_NORMAL || orientation > ExifInterface.ORIENTATION_ROTATE_270) return bitmap
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, CropMath.upright(orientation, bitmap.width, bitmap.height).toMatrix(), true)
        if (upright !== bitmap) bitmap.recycle()
        return upright
    }

    /** The photo at [uri], decoded upright at most [maxEdge] pixels long. */
    fun upright(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { upright(it, maxEdge) }
    }.getOrNull()
}

/** An affine map in [a, b, c, d, e, f] form (as CropMath produces) as an Android matrix. */
internal fun FloatArray.toMatrix(): Matrix = Matrix().apply {
    setValues(floatArrayOf(this@toMatrix[0], this@toMatrix[1], this@toMatrix[2], this@toMatrix[3], this@toMatrix[4], this@toMatrix[5], 0f, 0f, 1f))
}
