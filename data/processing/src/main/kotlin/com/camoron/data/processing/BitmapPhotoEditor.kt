package com.camoron.data.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.common.timed
import com.camoron.core.imaging.ObjectEraser
import com.camoron.core.imaging.TryOnPainter
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.CropMath
import com.camoron.domain.model.EditColour
import com.camoron.domain.model.FaceScene
import com.camoron.domain.model.PhotoEdit
import com.camoron.domain.model.PixelSize
import com.camoron.domain.repository.PhotoEditor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Applies an editor [PhotoEdit] to the full-size photo: stands it upright, erases and draws any try-ons,
 * then turns, flips, straightens and crops it in one filtered draw, then runs the colour edit over the pixels in
 * parallel bands and encodes a high-quality JPEG with the original's camera details.
 */
class BitmapPhotoEditor @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhotoEditor {

    override suspend fun render(photo: CapturedImage, edit: PhotoEdit, scene: FaceScene): CapturedImage = withContext(Dispatchers.Default) {
        val exif = ExifInterface(ByteArrayInputStream(photo.bytes))
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val bounds = decodeBounds(photo.bytes)
        // Photos shared from other apps can be enormous; very large ones are halved to stay within memory.
        var sample = 1
        while (bounds.width.toLong() * bounds.height / (sample.toLong() * sample) > MAX_PIXELS) sample *= 2
        val source = BitmapFactory.decodeByteArray(
            photo.bytes,
            0,
            photo.bytes.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            },
        ) ?: error("Could not decode the photo")

        // Erases and try-ons are placed on the upright photo, where they were chosen, before cropping.
        var stored = source
        var storedOrientation = orientation
        if (edit.erases.isNotEmpty() || !edit.tryOn.isNone) {
            stored = try {
                cut(source, orientation, PhotoEdit())
            } finally {
                source.recycle()
            }
            storedOrientation = ExifInterface.ORIENTATION_NORMAL
            try {
                if (edit.erases.isNotEmpty()) {
                    DiagnosticLog.timed(TAG, "Erasing ${edit.erases.size} at ${stored.width}x${stored.height}") {
                        for (mask in edit.erases) {
                            ensureActive()
                            ObjectEraser.erase(stored, mask)
                        }
                    }
                }
                if (!edit.tryOn.isNone) {
                    DiagnosticLog.timed(TAG, "Trying on at ${stored.width}x${stored.height}") { TryOnPainter.paint(stored, edit.tryOn, scene) }
                }
            } catch (e: Throwable) {
                stored.recycle()
                throw e
            }
        }
        val bitmap = try {
            DiagnosticLog.timed(TAG, "Cropping ${stored.width}x${stored.height}") { cut(stored, storedOrientation, edit) }
        } finally {
            stored.recycle()
        }
        try {
            val colour = EditColour.of(edit)
            if (!colour.isIdentity) DiagnosticLog.timed(TAG, "Colouring ${bitmap.width}x${bitmap.height}") { paint(bitmap, colour) }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            val size = PixelSize(bitmap.width, bitmap.height)
            photo.withBytes(writeJpegExif(context, bytes, exif, ExifInterface.ORIENTATION_NORMAL, size), size)
        } finally {
            bitmap.recycle()
        }
    }

    /** The upright, turned, straightened and cropped picture as a new bitmap. */
    private fun cut(source: Bitmap, orientation: Int, edit: PhotoEdit): Bitmap {
        val swapped = CropMath.uprightSwapsSides(orientation)
        val uprightWidth = if (swapped) source.height else source.width
        val uprightHeight = if (swapped) source.width else source.height
        val (outWidth, outHeight) = CropMath.outputSize(uprightWidth, uprightHeight, edit.crop)
        val map = CropMath.then(
            CropMath.upright(orientation, source.width, source.height),
            CropMath.transform(uprightWidth, uprightHeight, edit.crop),
        )
        val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, map.toMatrix(), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private suspend fun paint(bitmap: Bitmap, colour: EditColour) = coroutineScope {
        val width = bitmap.width
        val height = bitmap.height
        val cell = EditColour.grainCell(width, height)
        val bandHeight = (height + BANDS - 1) / BANDS
        (0 until BANDS).map { band ->
            async {
                val mine = colour.copyForThread()
                val row = IntArray(width)
                val end = minOf(height, (band + 1) * bandHeight)
                for (y in band * bandHeight until end) {
                    if (y % CANCEL_CHECK_ROWS == 0) ensureActive()
                    bitmap.getPixels(row, 0, width, 0, y, width, 1)
                    mine.process(row, y, width, height, cell)
                    bitmap.setPixels(row, 0, width, 0, y, width, 1)
                }
            }
        }.awaitAll()
    }

    private companion object {
        const val TAG = "PhotoEditor"
        const val MAX_PIXELS = 50_000_000L
        const val BANDS = 4
        const val CANCEL_CHECK_ROWS = 64
    }
}

internal fun FloatArray.toMatrix(): Matrix = Matrix().apply {
    setValues(floatArrayOf(this@toMatrix[0], this@toMatrix[1], this@toMatrix[2], this@toMatrix[3], this@toMatrix[4], this@toMatrix[5], 0f, 0f, 1f))
}
