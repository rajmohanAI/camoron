package com.camoron.domain.usecase

import com.camoron.domain.model.PhotoAdjustments
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PhotoProcessor
import javax.inject.Inject

/**
 * MVP capture path: take the photo and save it. The processing pipeline (distortion correction,
 * noise reduction, upscaling) slots in between these two steps in a later milestone.
 */
class CaptureAndSavePhoto @Inject constructor(
    private val camera: CameraController,
    private val media: MediaRepository,
    private val processor: PhotoProcessor,
) {
    /**
     * Returns the gallery URI of the JPEG; the DNG, if any, is saved alongside it untouched (RAW keeps
     * the full sensor frame, so crop and mirror apply to the JPEG only).
     */
    suspend operator fun invoke(
        deviceRotationDegrees: Int,
        includeRaw: Boolean = false,
        adjustments: PhotoAdjustments = PhotoAdjustments.NONE,
    ): String {
        val bundle = camera.capture(deviceRotationDegrees, includeRaw)
        val jpeg = if (adjustments.isIdentity) bundle.primary else processor.apply(bundle.primary, adjustments)
        val uri = media.save(jpeg)
        bundle.raw?.let { media.save(it) }
        return uri
    }
}
