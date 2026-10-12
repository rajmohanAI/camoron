package com.camoron.data.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.camoron.core.common.IoDispatcher
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Writes captures to the shared gallery under Pictures/Camoron. On Android 10+ the entry is marked
 * pending until fully written so the gallery never shows a half-saved file.
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
        val name = "CAMORON_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(image.timestampMillis))

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATE_TAKEN, image.timestampMillis)
            put(MediaStore.Images.Media.WIDTH, image.size.width)
            put(MediaStore.Images.Media.HEIGHT, image.size.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
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

    @Suppress("DEPRECATION") // MediaStore DATA is the only way to filter by folder before Android 10.
    override suspend fun latestCapture(): String? = withContext(io) {
        // Scoped storage lets us see the files we inserted; anything else is invisible, which is
        // what we want for the "last photo" thumbnail.
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?" to
                arrayOf("${Environment.DIRECTORY_PICTURES}/$ALBUM%", "image/jpeg")
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?" to
                arrayOf("%/${Environment.DIRECTORY_PICTURES}/$ALBUM/%", "image/jpeg")
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                selection,
                args,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)).toString() else null
            }
        }.getOrNull()
    }

    private companion object {
        const val ALBUM = "Camoron"
        const val SOFTWARE = "Camoron"
    }
}
