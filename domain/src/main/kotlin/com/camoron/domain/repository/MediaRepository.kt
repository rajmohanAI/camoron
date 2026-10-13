package com.camoron.domain.repository

import com.camoron.domain.model.CapturedImage
import java.io.FileDescriptor

interface MediaRepository {
    /** Saves the image to the shared gallery and returns its content URI as a string. */
    suspend fun save(image: CapturedImage): String

    /**
     * Creates a gallery entry for a video about to be recorded. It stays hidden from gallery apps
     * until [PendingVideo.finish] publishes it.
     */
    suspend fun createVideo(timestampMillis: Long): PendingVideo

    /** Content URI of the most recent photo or video Camoron saved, if any. */
    suspend fun latestCapture(): String?
}

/** A video file being recorded into. */
interface PendingVideo {
    /** Open for writing until [finish]. */
    val fileDescriptor: FileDescriptor

    /** Closes the file and publishes it ([keep]) or deletes it. Returns the content URI when kept. */
    suspend fun finish(keep: Boolean): String?
}
