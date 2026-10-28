package com.camoron.domain.repository

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraExtension
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.DeviceProfile
import com.camoron.domain.model.FlashMode
import com.camoron.domain.model.FocusTarget
import com.camoron.domain.model.CaptureBundle
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.EnhanceParams
import com.camoron.domain.model.Histogram
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.StillAspect
import com.camoron.domain.model.VideoSpec
import java.io.FileDescriptor
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
    suspend fun previewSize(
        cameraId: String,
        aspect: StillAspect = StillAspect.FOUR_THREE,
        extension: CameraExtension? = null,
    ): PixelSize

    /** Brightness histogram of recent preview frames. Only produced while RAW is off. */
    val histogram: StateFlow<Histogram?>

    /**
     * Opens [cameraId] and starts streaming into [target]. Replaces any open session.
     *
     * With [rawEnabled] (and a RAW-capable camera) the session carries a RAW stream so captures can
     * include a DNG. Without it, a small analysis stream feeds [histogram] instead: Camera2 only
     * guarantees one of the two alongside preview and full-size JPEG.
     *
     * With an [extension] (Night or HDR, on a camera that reports the phone maker's version of it)
     * the session is a Camera2 Extensions session instead: captures come back already processed by
     * the phone's own pipeline,
     * and RAW, histogram, manual controls, zoom, flash and focus tracking are unavailable.
     */
    suspend fun open(
        cameraId: String,
        target: PreviewTarget,
        rawEnabled: Boolean = false,
        aspect: StillAspect = StillAspect.FOUR_THREE,
        extension: CameraExtension? = null,
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

    /**
     * Night and indoor photos without the maker extension: [frames] JPEGs back to back at one locked exposure,
     * without flash, for merging.
     */
    suspend fun captureBurst(deviceRotationDegrees: Int, frames: Int): List<CapturedImage>

    /**
     * Preview buffer size for a video session on [cameraId]. Video frames are drawn upright by the
     * app (so the enhancement and look can be applied), so this is portrait on most phones.
     */
    suspend fun videoPreviewSize(cameraId: String, spec: VideoSpec): PixelSize

    /**
     * Opens [cameraId] for video: the camera streams into the app's renderer, which draws the
     * enhanced picture to [target] and, while recording, to the video encoder. Replaces any open session.
     */
    suspend fun openVideo(cameraId: String, target: PreviewTarget, spec: VideoSpec)

    /**
     * Colour processing for video frames, live on the preview and in the recording: [look] is a 4x5
     * colour matrix ([com.camoron.domain.model.LookMatrix]) and [enhance] null when Enhance is off.
     * Kept for later video sessions too.
     */
    fun setVideoFilter(look: FloatArray, enhance: EnhanceParams?)

    /**
     * Starts recording the open video session into [output], an MP4 file. Sound is recorded when
     * the video kind has sound and the microphone permission is granted.
     */
    suspend fun startRecording(output: FileDescriptor, deviceRotationDegrees: Int)

    /** Stops recording. Returns false when nothing usable was recorded (for example, stopped at once). */
    suspend fun stopRecording(): Boolean

    suspend fun close()
}
