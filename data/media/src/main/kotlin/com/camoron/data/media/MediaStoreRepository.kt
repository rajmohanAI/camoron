package com.camoron.data.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.camoron.core.common.IoDispatcher
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PendingVideo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Writes photos and videos to DCIM/Camera, the folder the phone's own camera uses, so they sit with the
 * user's other photos in every gallery app. On Android 10+ the entry is marked pending until fully
 * written so the gallery never shows a half-saved file.
 *
 * The camera's own EXIF block is kept as-is; we only stamp the Software tag. Carrying EXIF across
 * processing stages arrives with the processing pipeline.
 */
class MediaStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MediaRepository {

    override suspend fun save(image: CapturedImage): String = withContext(io) {
        val resolver = context.contentResolver
        val (mime, extension) = when (image.format) {
            ImageFormat.JPEG -> "image/jpeg" to "jpg"
            ImageFormat.DNG -> "image/x-adobe-dng" to "dng"
        }
        val name = FILE_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(image.timestampMillis))

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATE_TAKEN, image.timestampMillis)
            put(MediaStore.Images.Media.WIDTH, image.size.width)
            put(MediaStore.Images.Media.HEIGHT, image.size.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$FOLDER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), FOLDER)
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, "$name.$extension").absolutePath)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore refused the insert")

        try {
            resolver.openOutputStream(uri)?.use { it.write(image.bytes) } ?: error("Could not open $uri")
            if (image.format == ImageFormat.JPEG) {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    ExifInterface(pfd.fileDescriptor).apply {
                        setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE)
                        saveAttributes()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        uri.toString()
    }

    override suspend fun createVideo(timestampMillis: Long): PendingVideo = withContext(io) {
        val resolver = context.contentResolver
        val name = FILE_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestampMillis)) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, timestampMillis)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), FOLDER)
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val uri = resolver.insert(videoCollection(), values) ?: error("MediaStore refused the insert")
        val file = try {
            resolver.openFileDescriptor(uri, "rw") ?: error("Could not open $uri")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        MediaStoreVideo(uri, file)
    }

    private inner class MediaStoreVideo(private val uri: Uri, private val file: ParcelFileDescriptor) : PendingVideo {
        override val fileDescriptor: FileDescriptor get() = file.fileDescriptor

        override suspend fun finish(keep: Boolean): String? = withContext(io) {
            val resolver = context.contentResolver
            runCatching { file.close() }
            if (!keep) {
                runCatching { resolver.delete(uri, null, null) }
                return@withContext null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            uri.toString()
        }
    }

    override suspend fun latestCapture(): String? = withContext(io) {
        // Camoron's own photos and videos, wherever they were saved (older builds used Pictures/Camoron).
        val photo = latestIn(imageCollection(), "image/jpeg")
        val video = latestIn(videoCollection(), "video/mp4")
        listOfNotNull(photo, video).maxByOrNull { it.second }?.first?.toString()
    }

    /** Newest Camoron item of [mime] in [collection], with the time it was taken. */
    private fun latestIn(collection: Uri, mime: String): Pair<Uri, Long>? = runCatching {
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?"
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.Images.ImageColumns.DATE_TAKEN),
            selection,
            arrayOf("$FILE_PREFIX%", mime),
            "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) to cursor.getLong(1) else null
        }
    }.getOrNull()

    private fun imageCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun videoCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    private companion object {
        const val FOLDER = "Camera"
        const val FILE_PREFIX = "CAMORON_"
        const val SOFTWARE = "Camoron"
    }
}
