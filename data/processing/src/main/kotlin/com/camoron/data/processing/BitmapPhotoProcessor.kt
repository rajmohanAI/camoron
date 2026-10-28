package com.camoron.data.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.Process
import androidx.exifinterface.media.ExifInterface
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.common.timed
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.GeoPoint
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.model.LookMatrix
import com.camoron.domain.model.PhotoAdjustments
import com.camoron.domain.model.PixelSize
import com.camoron.domain.repository.PhotoProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Finishes the camera's photo to match the preview: centre crop for the taller aspect ratios, the
 * gentle on-device optimisation ([Enhancer], with [ZoomEnhancer] for digitally zoomed shots), the chosen colour look, and a mirror flag for selfies.
 * Mirroring only rewrites the EXIF orientation, so it costs no quality. Cropping, optimising or a
 * look re-encodes the pixels and carries the camera's EXIF across. Also merges night bursts.
 */
class BitmapPhotoProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val zoomEnhancer: ZoomEnhancer,
) : PhotoProcessor {

    private val processing: CoroutineDispatcher get() = pool

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
            if (crop == null && look == null && !enhance && !adjustments.mirror && adjustments.location == null) {
                return@withContext image
            }

            var bytes = image.bytes
            var size = bounds
            val reencode = crop != null || look != null || enhance
            if (reencode) {
                val transformed = try {
                    DiagnosticLog.timed(TAG, "Adjusting ${bounds.width}x${bounds.height}") {
                        transformJpeg(image.bytes, crop, look, enhance, adjustments.zoom)
                    }
                } catch (e: OutOfMemoryError) {
                    // Very large sensors can exceed the heap; keep the photo rather than lose it.
                    DiagnosticLog.record(TAG, "Not enough memory to adjust a ${bounds.width}x${bounds.height} photo", e)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A processing problem must never cost the photo: save it as the camera took it.
                    DiagnosticLog.record(TAG, "Couldn't adjust the photo; saving it unadjusted", e)
                    null
                }
                if (transformed != null) {
                    bytes = transformed
                    if (crop != null) size = PixelSize(crop.width, crop.height)
                }
            }
            val newOrientation = if (adjustments.mirror) PhotoMath.mirroredOrientation(orientation) else orientation
            bytes = writeJpegExif(context, bytes, original.takeIf { bytes !== image.bytes }, newOrientation, size, adjustments.location)
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
                DiagnosticLog.record(TAG, "Not enough memory to merge the night burst; keeping the first frame", e)
                reference
            }
        }
    }

    /** Decodes (the cropped part of) a JPEG, enhances it, applies the colour matrix and re-encodes it. */
    private suspend fun transformJpeg(
        bytes: ByteArray,
        crop: CropRect?,
        look: FloatArray?,
        enhance: Boolean,
        zoom: Float,
    ): ByteArray {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = if (crop != null) {
            val decoder = regionDecoder(bytes)
            try {
                decoder.decodeRegion(Rect(crop.left, crop.top, crop.right, crop.bottom), options)
            } finally {
                decoder.recycle()
            }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } ?: error("Could not decode the captured JPEG")
        // Region decoding ignores inMutable on many phones and hands back a read-only bitmap, which
        // can't be written to; that was why full-screen photos failed. Copy it to a writable one.
        val bitmap = if (decoded.isMutable) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
                ?: error("Could not copy the decoded photo")
        }
        try {
            return finishPixels(bitmap, look, enhance, zoom)
        } finally {
            bitmap.recycle()
        }
    }

    /** Optimisation, colour look and JPEG encoding. */
    private suspend fun finishPixels(bitmap: Bitmap, look: FloatArray?, enhance: Boolean, zoom: Float): ByteArray {
        if (enhance && zoomEnhancer.worthwhile(zoom)) zoomEnhancer.enhance(bitmap, zoom)
        if (enhance) Enhancer.enhance(bitmap, zoom)
        if (look != null) applyLook(bitmap, look)
        return DiagnosticLog.timed(TAG, "Encoding ${bitmap.width}x${bitmap.height}") {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
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

    private fun writeExif(bytes: ByteArray, copyFrom: ExifInterface?, orientation: Int, size: PixelSize): ByteArray =
        writeJpegExif(context, bytes, copyFrom, orientation, size)

    private companion object {
        const val TAG = "PhotoProcessor"
        const val POOL_THREADS = 4

        /**
         * Photo processing runs on its own threads at a lower priority than the screen and the
         * camera, so the preview and buttons stay responsive while a photo is being finished.
         */
        val pool: CoroutineDispatcher by lazy {
            Executors.newFixedThreadPool(POOL_THREADS) { task ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
                    task.run()
                }, "CamoronPhoto").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
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

/**
 * ExifInterface can only save to a file, so round-trip through the app's cache. When [copyFrom]
 * is given (the JPEG was re-encoded and lost its EXIF), the camera's tags are copied over first.
 */
internal fun writeJpegExif(
    context: Context,
    bytes: ByteArray,
    copyFrom: ExifInterface?,
    orientation: Int,
    size: PixelSize,
    location: GeoPoint? = null,
): ByteArray {
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
            location?.let { point ->
                setLatLong(point.latitude, point.longitude)
                point.altitude?.let(::setAltitude)
            }
            saveAttributes()
        }
        return file.readBytes()
    } finally {
        file.delete()
    }
}

private val COPIED_TAGS = listOf(
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
    // Where it was taken, so editing a geotagged photo keeps it.
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
)
