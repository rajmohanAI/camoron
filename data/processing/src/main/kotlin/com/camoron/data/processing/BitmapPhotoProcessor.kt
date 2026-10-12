package com.camoron.data.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.camoron.core.common.DefaultDispatcher
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.model.PhotoAdjustments
import com.camoron.domain.model.PixelSize
import com.camoron.domain.repository.PhotoProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Makes the saved JPEG match the preview: centre crop for the taller aspect ratios, and a mirror
 * flag for selfies. Mirroring only rewrites the EXIF orientation, so it costs no quality.
 * Cropping re-encodes the pixels and carries the camera's EXIF tags across.
 */
class BitmapPhotoProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val processing: CoroutineDispatcher,
) : PhotoProcessor {

    override suspend fun apply(image: CapturedImage, adjustments: PhotoAdjustments): CapturedImage {
        if (image.format != ImageFormat.JPEG || adjustments.isIdentity) return image
        return withContext(processing) {
            val original = ExifInterface(ByteArrayInputStream(image.bytes))
            val orientation = original.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
            val crop = adjustments.cropAspect?.let { aspect ->
                // cropAspect is portrait width over height; the geometry wants long over short.
                PhotoMath.centreCrop(bounds.outWidth, bounds.outHeight, 1f / aspect)
            }

            // A crop request for a shape the sensor already delivers needs no work at all.
            if (crop == null && !adjustments.mirror) return@withContext image

            var bytes = image.bytes
            var size = PixelSize(bounds.outWidth, bounds.outHeight)
            if (crop != null) {
                bytes = cropJpeg(image.bytes, crop)
                size = PixelSize(crop.width, crop.height)
            }
            val newOrientation = if (adjustments.mirror) PhotoMath.mirroredOrientation(orientation) else orientation
            bytes = writeExif(bytes, original.takeIf { crop != null }, newOrientation, size)
            image.withBytes(bytes, size)
        }
    }

    private fun cropJpeg(bytes: ByteArray, crop: CropRect): ByteArray {
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } ?: error("Could not decode the captured JPEG")
        try {
            val bitmap = decoder.decodeRegion(Rect(crop.left, crop.top, crop.right, crop.bottom), null)
            try {
                return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    .toByteArray()
            } finally {
                bitmap.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }

    /**
     * ExifInterface can only save to a file, so round-trip through the app's cache. When [copyFrom]
     * is given (the JPEG was re-encoded and lost its EXIF), the camera's tags are copied over first.
     */
    private fun writeExif(bytes: ByteArray, copyFrom: ExifInterface?, orientation: Int, size: PixelSize): ByteArray {
        val file = File.createTempFile("adjust", ".jpg", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ExifInterface(file).apply {
                copyFrom?.let { source ->
                    COPIED_TAGS.forEach { tag -> source.getAttribute(tag)?.let { setAttribute(tag, it) } }
                    setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
                    setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
                }
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 95

        val COPIED_TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_COLOR_SPACE,
        )
    }
}
