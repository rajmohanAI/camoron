package com.camoron.domain.repository

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.PixelSize
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific handle to the surface the preview is drawn into. The data layer supplies the
 * concrete type; the domain only passes it through.
 */
interface PreviewTarget {
    val width: Int
    val height: Int
}

interface CameraController {
    val state: StateFlow<CameraState>

    suspend fun cameras(): List<CameraCapabilities>

    /**
     * Preview buffer size for [cameraId], already matched to the aspect ratio of its full-resolution
     * still output. The UI sizes its surface to this before calling [open].
     */
    suspend fun previewSize(cameraId: String): PixelSize

    /** Opens [cameraId] and starts streaming into [target]. Replaces any open session. */
    suspend fun open(cameraId: String, target: PreviewTarget)

    suspend fun applySettings(settings: ManualSettings)

    /** Focus and meter on a point given in normalised preview coordinates (0..1). */
    suspend fun focusAt(x: Float, y: Float)

    /** [deviceRotationDegrees] is the physical rotation of the phone, used to orient the JPEG. */
    suspend fun capture(deviceRotationDegrees: Int): CapturedImage

    suspend fun close()
}
