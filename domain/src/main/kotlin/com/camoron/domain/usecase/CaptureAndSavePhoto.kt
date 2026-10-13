package com.camoron.domain.usecase

import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.PhotoAdjustments
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PhotoProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Capture path: take a burst of frames and merge them (or a single photo where the session can't),
 * match it to the preview, enhance it and save it.
 */
class CaptureAndSavePhoto @Inject constructor(
    private val camera: CameraController,
    private val media: MediaRepository,
    private val processor: PhotoProcessor,
) {
    /** One photo is processed and saved at a time, so quick shots don't pile up full-size images in memory. */
    private val saving = Mutex()

    /**
     * Returns the gallery URI of the JPEG; the DNG, if any, is saved alongside it untouched (RAW keeps
     * the full sensor frame, so crop, look and mirror apply to the JPEG only).
     *
     * With [nightFrames] above 1 the photo is a merged night burst instead (no RAW).
     *
     * [onCaptured] runs as soon as the camera has delivered the frames, before processing and saving,
     * so the shutter can be ready for the next shot straight away. A failed capture is retried once:
     * some phones reject a still request issued while the session is still settling.
     */
    suspend operator fun invoke(
        deviceRotationDegrees: Int,
        includeRaw: Boolean = false,
        adjustments: PhotoAdjustments = PhotoAdjustments.NONE,
        nightFrames: Int = 0,
        onCaptured: () -> Unit = {},
    ): String {
        // Every photo is a merged burst where the camera allows it: night simply uses more frames.
        if (!includeRaw) {
            val count = if (nightFrames > 1) nightFrames else BURST_FRAMES
            val burst = retryOnce { camera.captureFrames(deviceRotationDegrees, count) }
            if (burst != null) {
                onCaptured()
                return saving.withLock { media.save(processor.render(burst, adjustments)) }
            }
        }
        if (nightFrames > 1) {
            val frames = retryOnce { camera.captureBurst(deviceRotationDegrees, nightFrames) }
            onCaptured()
            return saving.withLock { media.save(adjust(processor.mergeNight(frames), adjustments)) }
        }
        val bundle = retryOnce { camera.capture(deviceRotationDegrees, includeRaw) }
        onCaptured()
        return saving.withLock {
            val uri = media.save(adjust(bundle.primary, adjustments))
            bundle.raw?.let { media.save(it) }
            uri
        }
    }

    private suspend fun <T> retryOnce(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        delay(RETRY_DELAY_MS)
        block()
    }

    private suspend fun adjust(image: CapturedImage, adjustments: PhotoAdjustments): CapturedImage =
        if (adjustments.isIdentity) image else processor.apply(image, adjustments)

    private companion object {
        const val RETRY_DELAY_MS = 300L

        /** Frames merged for an ordinary photo: noise drops by about the square root of this. */
        const val BURST_FRAMES = 6
    }
}
