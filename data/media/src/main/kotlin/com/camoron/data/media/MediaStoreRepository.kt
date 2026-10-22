package com.camoron.data.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.camoron.core.common.IoDispatcher
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.model.PixelSize
import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PhotoLibrary
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
) : MediaRepository, PhotoLibrary {

    override suspend fun save(image: CapturedImage): String = withContext(io) { insertPhoto(image, image.timestampMillis) }

    /**
     * An edited copy is named for when it was saved, so it never collides with the original, but
     * keeps the original's date taken, so gallery apps show it right beside the original.
     */
    override suspend fun saveEdited(image: CapturedImage): String = withContext(io) {
        insertPhoto(image, System.currentTimeMillis())
    }

    private fun insertPhoto(image: CapturedImage, nameMillis: Long): String {
        val resolver = context.contentResolver
        val (mime, extension) = when (image.format) {
            ImageFormat.JPEG -> "image/jpeg" to "jpg"
            ImageFormat.DNG -> "image/x-adobe-dng" to "dng"
        }
        val name = PHOTO_PREFIX + SimpleDateFormat(NAME_TIME, Locale.US).format(Date(nameMillis))

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
        return uri.toString()
    }

    override suspend fun recentPhotos(limit: Int): List<String> = withContext(io) {
        val collection = imageCollection()
        runCatching {
            val (selection, args) = oursSelection("image/jpeg", PHOTO_PREFIX, "jpg")
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                args,
                "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC",
            )?.use { cursor ->
                buildList {
                    while (size < limit && cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0)).toString())
                }
            }
        }.getOrNull().orEmpty()
    }

    override suspend fun read(uri: String): CapturedImage = withContext(io) {
        val parsed = Uri.parse(uri)
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(parsed)?.use { it.readBytes() } ?: error("Could not open $uri")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Not a photo: $uri")
        // Photos from other apps may not be in the gallery's index; then the time is now.
        val taken = runCatching {
            resolver.query(parsed, arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 } else null
            }
        }.getOrNull()
        CapturedImage(bytes, ImageFormat.JPEG, PixelSize(bounds.outWidth, bounds.outHeight), taken ?: System.currentTimeMillis())
    }

    override suspend fun createVideo(timestampMillis: Long): PendingVideo = withContext(io) {
        val resolver = context.contentResolver
        val name = VIDEO_PREFIX + SimpleDateFormat(NAME_TIME, Locale.US).format(Date(timestampMillis)) + ".mp4"
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
        val photo = latestIn(imageCollection(), "image/jpeg", PHOTO_PREFIX, "jpg")
        val video = latestIn(videoCollection(), "video/mp4", VIDEO_PREFIX, "mp4")
        listOfNotNull(photo, video).maxByOrNull { it.second }?.first?.toString()
    }

    /** Newest Camoron item of [mime] in [collection], with the time it was taken. */
    private fun latestIn(collection: Uri, mime: String, prefix: String, extension: String): Pair<Uri, Long>? = runCatching {
        val (selection, args) = oursSelection(mime, prefix, extension)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.Images.ImageColumns.DATE_TAKEN),
            selection,
            args,
            "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) to cursor.getLong(1) else null
        }
    }.getOrNull()

    /**
     * Selects Camoron's own items of [mime]. Files are named like the phone camera's (IMG_..., VID_...),
     * so on Android 10+ ours are told apart by owner; older builds named them CAMORON_....
     */
    private fun oursSelection(mime: String, prefix: String, extension: String): Pair<String, Array<String>> {
        val name = MediaStore.MediaColumns.DISPLAY_NAME
        val ours = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        } else {
            "$name GLOB ?"
        }
        val oursArg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.packageName
        } else {
            "${prefix}[0-9]*_[0-9]*_[0-9][0-9][0-9].$extension"
        }
        val selection = "($ours OR $name LIKE ?) AND ${MediaStore.MediaColumns.MIME_TYPE} = ?"
        return selection to arrayOf(oursArg, "$LEGACY_PREFIX%", mime)
    }

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
        /** Same naming as the phone's own camera, so Camoron's shots sort and group with its. */
        const val PHOTO_PREFIX = "IMG_"
        const val VIDEO_PREFIX = "VID_"
        const val NAME_TIME = "yyyyMMdd_HHmmss_SSS"

        /** What earlier builds named their files. */
        const val LEGACY_PREFIX = "CAMORON_"
        const val SOFTWARE = "Camoron"
    }
}
