package com.camoron.domain.usecase

import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.MediaRepository
import javax.inject.Inject

/**
 * MVP capture path: take the photo and save it. The processing pipeline (distortion correction,
 * noise reduction, upscaling) slots in between these two steps in a later milestone.
 */
class CaptureAndSavePhoto @Inject constructor(
    private val camera: CameraController,
    private val media: MediaRepository,
) {
    /** Returns the gallery URI of the JPEG; the DNG, if any, is saved alongside it. */
    suspend operator fun invoke(deviceRotationDegrees: Int, includeRaw: Boolean = false): String {
        val bundle = camera.capture(deviceRotationDegrees, includeRaw)
        val uri = media.save(bundle.primary)
        bundle.raw?.let { media.save(it) }
        return uri
    }
}
