package com.camoron.data.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.camoron.core.common.DefaultDispatcher
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.model.LookMatrix
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
 * Makes the saved JPEG match the preview: centre crop for the taller aspect ratios, on-device
 * enhancement ([Enhancer]), the chosen colour look, and a mirror flag for selfies. Mirroring only rewrites the EXIF orientation, so it
 * costs no quality. Cropping or a look re-encodes the pixels and carries the camera's EXIF across.
 * Also merges night bursts.
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

            val bounds = decodeBounds(image.bytes)
            val crop = adjustments.cropAspect?.let { aspect ->
                // cropAspect is portrait width over height; the geometry wants long over short.
                PhotoMath.centreCrop(bounds.width, bounds.height, 1f / aspect)
            }
            val look = LookMatrix.forLook(adjustments.look).takeUnless(LookMatrix::isIdentity)

            val enhance = adjustments.enhance

            // A crop request for a shape the sensor already delivers needs no work at all.
            if (crop == null && look == null && !enhance && !adjustments.mirror) return@withContext image

            var bytes = image.bytes
            var size = bounds
            val reencode = crop != null || look != null || enhance
            if (reencode) {
                val transformed = try {
                    transformJpeg(image.bytes, crop, look, enhance)
                } catch (e: OutOfMemoryError) {
                    // Very large sensors can exceed the heap; keep the photo rather than lose it.
                    Log.w(TAG, "Not enough memory to adjust a ${bounds.width}x${bounds.height} photo", e)
                    null
                }
                if (transformed != null) {
                    bytes = transformed
                    if (crop != null) size = PixelSize(crop.width, crop.height)
                }
            }
            val newOrientation = if (adjustments.mirror) PhotoMath.mirroredOrientation(orientation) else orientation
            bytes = writeExif(bytes, original.takeIf { bytes !== image.bytes }, newOrientation, size)
            image.withBytes(bytes, size)
        }
    }

    override suspend fun mergeNight(frames: List<CapturedImage>): CapturedImage {
        val reference = frames.firstOrNull() ?: error("No frames to merge")
        if (frames.size < 2) return reference
        return withContext(processing) {
            try {
                NightMerger(frames.map { it.bytes }).merge()?.let { (bytes, size) ->
                    val exif = ExifInterface(ByteArrayInputStream(reference.bytes))
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    reference.withBytes(writeExif(bytes, exif, orientation, size), size)
                } ?: reference
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Not enough memory to merge the night burst; keeping the first frame", e)
                reference
            }
        }
    }

    /** Decodes (the cropped part of) a JPEG, enhances it, applies the colour matrix and re-encodes it. */
    private suspend fun transformJpeg(bytes: ByteArray, crop: CropRect?, look: FloatArray?, enhance: Boolean): ByteArray {
        val options = BitmapFactory.Options().apply { inMutable = true }
        val bitmap = if (crop != null) {
            val decoder = regionDecoder(bytes)
            try {
                decoder.decodeRegion(Rect(crop.left, crop.top, crop.right, crop.bottom), options)
            } finally {
                decoder.recycle()
            }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } ?: error("Could not decode the captured JPEG")
        try {
            if (enhance) Enhancer.enhance(bitmap)
            if (look != null) applyLook(bitmap, look)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                .toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Row by row, so only one row of pixels is copied out at a time. */
    private fun applyLook(bitmap: Bitmap, matrix: FloatArray) {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) row[x] = LookMatrix.apply(matrix, row[x])
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
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
        const val TAG = "PhotoProcessor"

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

internal const val JPEG_QUALITY = 95

internal fun regionDecoder(bytes: ByteArray): BitmapRegionDecoder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
    } else {
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
    } ?: error("Could not decode the captured JPEG")

internal fun decodeBounds(bytes: ByteArray): PixelSize {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return PixelSize(bounds.outWidth, bounds.outHeight)
}
