package com.camoron.domain.repository

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.CaptureBundle
import com.camoron.domain.model.Histogram
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

    /** Brightness histogram of recent preview frames. Only produced while RAW is off. */
    val histogram: StateFlow<Histogram?>

    /**
     * Opens [cameraId] and starts streaming into [target]. Replaces any open session.
     *
     * With [rawEnabled] (and a RAW-capable camera) the session carries a RAW stream so captures can
     * include a DNG. Without it, a small analysis stream feeds [histogram] instead: Camera2 only
     * guarantees one of the two alongside preview and full-size JPEG.
     */
    suspend fun open(cameraId: String, target: PreviewTarget, rawEnabled: Boolean = false)

    suspend fun applySettings(settings: ManualSettings)

    /** Focus and meter on a point given in normalised preview coordinates (0..1). */
    suspend fun focusAt(x: Float, y: Float)

    /**
     * [deviceRotationDegrees] is the physical rotation of the phone, used to orient the output.
     * [includeRaw] adds a DNG when the session was opened with RAW enabled.
     */
    suspend fun capture(deviceRotationDegrees: Int, includeRaw: Boolean = false): CaptureBundle

    suspend fun close()
}
