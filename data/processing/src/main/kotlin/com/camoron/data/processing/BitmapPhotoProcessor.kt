package com.camoron.data.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.common.timed
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.FrameBurst
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Makes the saved JPEG match the preview: centre crop for the taller aspect ratios, on-device
 * enhancement ([Enhancer]), the chosen colour look, and a mirror flag for selfies. Mirroring only rewrites the EXIF orientation, so it
 * costs no quality. Cropping or a look re-encodes the pixels and carries the camera's EXIF across.
 * Also merges night bursts.
 */
class BitmapPhotoProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
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
            if (crop == null && look == null && !enhance && !adjustments.mirror) return@withContext image

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
            bytes = writeExif(bytes, original.takeIf { bytes !== image.bytes }, newOrientation, size)
            image.withBytes(bytes, size)
        }
    }

    override suspend fun render(burst: FrameBurst, adjustments: PhotoAdjustments): CapturedImage = withContext(processing) {
        val started = SystemClock.elapsedRealtime()
        val first = burst.frames.first()
        // Crop before merging: pixels outside the photo's shape would be merged only to be thrown away.
        val crop = adjustments.cropAspect?.let { PhotoMath.centreCrop(first.width, first.height, 1f / it) }
            ?: CropRect(0, 0, first.width, first.height)
        // With digital zoom the camera enlarged a small part of the sensor. Work at that part's real
        // size, where the noise is fine-grained and easy to clean, and enlarge once at the end.
        val shrink = shrinkFor(burst.info.digitalZoom)
        val frames = burst.frames.map { it.crop(crop.left, crop.top, crop.width, crop.height).shrink(shrink) }
        val merged = try {
            BurstMerger.merge(frames, processing)
        } catch (e: OutOfMemoryError) {
            // Keep the photo: one frame is still a photo, just a noisier one.
            DiagnosticLog.record(TAG, "Not enough memory to merge ${frames.size} frames; using one", e)
            BurstMerger.Result(frames.first(), 0f, 1, 0)
        }
        val frame = merged.frame
        DiagnosticLog.info(
            TAG,
            "Merged ${merged.framesUsed} of ${frames.size} frames (${frame.width}x${frame.height}, shrunk ${shrink}x, " +
                "reference ${merged.reference}, noise %.1f) in ${SystemClock.elapsedRealtime() - started} ms".format(merged.noise),
        )

        val look = LookMatrix.forLook(adjustments.look).takeUnless(LookMatrix::isIdentity)
        // The photo keeps the full resolution of the crop; the sharpening knows how much is left to enlarge.
        val output = PixelSize(crop.width, crop.height).takeIf { shrink > 1 }
        val residualZoom = (burst.info.digitalZoom / shrink).coerceAtLeast(1f)
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val bytes = try {
            val rows = IntArray(frame.width * CONVERT_ROWS)
            var top = 0
            while (top < frame.height) {
                val count = minOf(CONVERT_ROWS, frame.height - top)
                YuvMath.toArgb(frame, 0, top, frame.width, count, rows)
                bitmap.setPixels(rows, 0, frame.width, 0, top, frame.width, count)
                top += count
            }
            try {
                finishPixels(bitmap, look, adjustments.enhance, residualZoom, output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.record(TAG, "Couldn't enhance the merged photo; saving it without", e)
                finishPixels(bitmap, null, enhance = false, zoom = 1f, output = output)
            } catch (e: OutOfMemoryError) {
                DiagnosticLog.record(TAG, "Not enough memory to enhance the merged photo; saving it without", e)
                finishPixels(bitmap, null, enhance = false, zoom = 1f, output = null)
            }
        } finally {
            bitmap.recycle()
        }

        val size = decodeBounds(bytes)
        val orientation = PhotoMath.orientationTag(burst.orientationDegrees)
            .let { if (adjustments.mirror) PhotoMath.mirroredOrientation(it) else it }
        val withExif = writeShotExif(bytes, burst, orientation, size)
        DiagnosticLog.info(TAG, "Rendered the burst in ${SystemClock.elapsedRealtime() - started} ms")
        CapturedImage(withExif, ImageFormat.JPEG, size, burst.timestampMillis)
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

    /** Enhancement, colour look and JPEG encoding, shared by single photos and merged bursts. */
    private suspend fun finishPixels(
        bitmap: Bitmap,
        look: FloatArray?,
        enhance: Boolean,
        zoom: Float,
        output: PixelSize? = null,
    ): ByteArray {
        if (enhance) Enhancer.enhance(bitmap, zoom)
        if (look != null) applyLook(bitmap, look)
        // Enlarge last, after cleaning and sharpening at the real resolution.
        val final = output?.takeIf { it.width > bitmap.width }
            ?.let { Bitmap.createScaledBitmap(bitmap, it.width, it.height, true) }
            ?: bitmap
        try {
            return DiagnosticLog.timed(TAG, "Encoding ${final.width}x${final.height}") {
                ByteArrayOutputStream().also { final.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            }
        } finally {
            if (final !== bitmap) final.recycle()
        }
    }

    /** EXIF for a photo we encoded ourselves from camera frames: what the camera's JPEG would carry. */
    private fun writeShotExif(bytes: ByteArray, burst: FrameBurst, orientation: Int, size: PixelSize): ByteArray {
        val file = File.createTempFile("render", ".jpg", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ExifInterface(file).apply {
                val date = Date(burst.timestampMillis)
                val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date)
                val offset = SimpleDateFormat("XXX", Locale.US).format(date)
                val subSec = (burst.timestampMillis % 1000).toString().padStart(3, '0')
                setAttribute(ExifInterface.TAG_DATETIME, stamp)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
                setAttribute(ExifInterface.TAG_SUBSEC_TIME, subSec)
                setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subSec)
                setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subSec)
                setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
                setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)
                setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                setAttribute(ExifInterface.TAG_SOFTWARE, "Camoron")
                setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
                setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                setAttribute(ExifInterface.TAG_COLOR_SPACE, ExifInterface.COLOR_SPACE_S_RGB.toString())
                val info = burst.info
                info.iso?.let { setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
                // Rationals, not decimals: ExifInterface can't parse "1.0E-4".
                info.exposureTimeNs?.let { setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "${it / 1000}/1000000") }
                info.aperture?.let { setAttribute(ExifInterface.TAG_F_NUMBER, "${(it * 100).toInt()}/100") }
                info.focalLengthMm?.let { setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).toInt()}/1000") }
                info.focalLength35mm?.let { setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.toString()) }
                setAttribute(ExifInterface.TAG_FLASH, if (info.flashFired) "1" else "0")
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
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
        const val CONVERT_ROWS = 64
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

        /** Whole-pixel shrink that undoes most of a [digitalZoom] enlargement; 1 for none. */
        fun shrinkFor(digitalZoom: Float): Int = if (digitalZoom < MIN_SHRINK_ZOOM) 1 else (digitalZoom + 0.25f).toInt()

        const val MIN_SHRINK_ZOOM = 1.75f

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
