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
    suspend operator fun invoke(deviceRotationDegrees: Int): String =
        media.save(camera.capture(deviceRotationDegrees))
}
