package com.camoron.domain.usecase

import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.PhotoAdjustments
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PhotoProcessor
import javax.inject.Inject

/**
 * Capture path: take the photo (or a night burst and merge it), match it to the preview, save it.
 * AI enhancement (milestone 4) slots in before saving.
 */
class CaptureAndSavePhoto @Inject constructor(
    private val camera: CameraController,
    private val media: MediaRepository,
    private val processor: PhotoProcessor,
) {
    /**
     * Returns the gallery URI of the JPEG; the DNG, if any, is saved alongside it untouched (RAW keeps
     * the full sensor frame, so crop, look and mirror apply to the JPEG only).
     *
     * With [nightFrames] above 1 the photo is a merged night burst instead (no RAW).
     */
    suspend operator fun invoke(
        deviceRotationDegrees: Int,
        includeRaw: Boolean = false,
        adjustments: PhotoAdjustments = PhotoAdjustments.NONE,
        nightFrames: Int = 0,
    ): String {
        if (nightFrames > 1) {
            val frames = camera.captureBurst(deviceRotationDegrees, nightFrames)
            val merged = processor.mergeNight(frames)
            return media.save(adjust(merged, adjustments))
        }
        val bundle = camera.capture(deviceRotationDegrees, includeRaw)
        val uri = media.save(adjust(bundle.primary, adjustments))
        bundle.raw?.let { media.save(it) }
        return uri
    }

    private suspend fun adjust(image: CapturedImage, adjustments: PhotoAdjustments): CapturedImage =
        if (adjustments.isIdentity) image else processor.apply(image, adjustments)
}
