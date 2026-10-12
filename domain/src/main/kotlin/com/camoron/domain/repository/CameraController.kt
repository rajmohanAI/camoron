package com.camoron.domain.repository

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.DeviceProfile
import com.camoron.domain.model.FlashMode
import com.camoron.domain.model.FocusTarget
import com.camoron.domain.model.CaptureBundle
import com.camoron.domain.model.Histogram
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.StillAspect
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

    /** Full capability scan of every camera plus device-wide facts. */
    suspend fun deviceProfile(): DeviceProfile

    /**
     * Preview buffer size for [cameraId], already matched to the aspect ratio of its full-resolution
     * still output. The UI sizes its surface to this before calling [open].
     */
    suspend fun previewSize(cameraId: String, aspect: StillAspect = StillAspect.FOUR_THREE): PixelSize

    /** Brightness histogram of recent preview frames. Only produced while RAW is off. */
    val histogram: StateFlow<Histogram?>

    /**
     * Opens [cameraId] and starts streaming into [target]. Replaces any open session.
     *
     * With [rawEnabled] (and a RAW-capable camera) the session carries a RAW stream so captures can
     * include a DNG. Without it, a small analysis stream feeds [histogram] instead: Camera2 only
     * guarantees one of the two alongside preview and full-size JPEG.
     */
    suspend fun open(
        cameraId: String,
        target: PreviewTarget,
        rawEnabled: Boolean = false,
        aspect: StillAspect = StillAspect.FOUR_THREE,
    )

    /** Flash for still captures. ON/AUTO run an exposure precapture so the flash is metered. */
    suspend fun setFlashMode(mode: FlashMode)

    suspend fun applySettings(settings: ManualSettings)

    /**
     * Focus and meter on a point given in normalised preview coordinates (0..1). With [track], the
     * focus point follows the subject as it moves, until it is lost (needs the analysis stream, so
     * not while RAW is on).
     */
    suspend fun focusAt(x: Float, y: Float, track: Boolean = false)

    /** Current focus point for the UI, or null when the camera focuses on its own. */
    val focusTarget: StateFlow<FocusTarget?>

    /** Zoom of the open camera, within its [com.camoron.domain.model.CameraCapabilities.zoomRange]. Resets to 1 on open. */
    suspend fun setZoom(ratio: Float)

    /** Close-up focus. Survives reopening; ignored on cameras without macro support. */
    suspend fun setMacro(enabled: Boolean)

    /**
     * [deviceRotationDegrees] is the physical rotation of the phone, used to orient the output.
     * [includeRaw] adds a DNG when the session was opened with RAW enabled.
     */
    suspend fun capture(deviceRotationDegrees: Int, includeRaw: Boolean = false): CaptureBundle

    suspend fun close()
}
