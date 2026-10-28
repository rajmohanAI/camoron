package com.camoron.feature.camera

import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camoron.core.common.DiagnosticLog
import com.camoron.domain.model.AspectRatioSetting
import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraError
import com.camoron.domain.model.CameraExtension
import com.camoron.domain.model.CameraSettings
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.DeviceProfile
import com.camoron.domain.model.EnhanceMath
import com.camoron.domain.model.EnhanceParams
import com.camoron.domain.model.FlashMode
import com.camoron.domain.model.FocusTarget
import com.camoron.domain.model.Histogram
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.LookMatrix
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.NightSetting
import com.camoron.domain.model.PanoramaPlan
import com.camoron.domain.model.PhotoAdjustments
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.ShotLook
import com.camoron.domain.model.TimelapseSpeed
import com.camoron.domain.model.TimerSetting
import com.camoron.domain.model.VideoKind
import com.camoron.domain.model.VideoResolution
import com.camoron.domain.model.VideoSpec
import com.camoron.domain.model.WhiteBalance
import com.camoron.domain.model.ZoomPreset
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.LocationProvider
import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PendingVideo
import com.camoron.domain.repository.PreviewTarget
import com.camoron.domain.repository.RotationSensor
import com.camoron.domain.repository.SettingsRepository
import com.camoron.domain.usecase.CaptureAndSavePhoto
import com.camoron.domain.usecase.PlanZoomPresets
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/** Capture modes in carousel order. Video modes record; the rest take photos. */
enum class CaptureMode(val video: VideoKind? = null) {
    TIMELAPSE(VideoKind.TIMELAPSE),
    CINEMATIC(VideoKind.CINEMATIC),
    VIDEO(VideoKind.STANDARD),
    PHOTO,
    PRO,
    PANORAMA;

    val isVideo: Boolean get() = video != null
}

data class CameraUiState(
    val cameraState: CameraState = CameraState.Closed,
    val selectedCamera: CameraCapabilities? = null,
    val canSwitchCamera: Boolean = false,
    val previewSize: PixelSize? = null,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val settings: ManualSettings = ManualSettings.AUTO,
    val cameraSettings: CameraSettings = CameraSettings(),
    val isCapturing: Boolean = false,
    /** Photos taken whose enhancement and saving are still running. */
    val photosSaving: Int = 0,
    /** The latest shot, shown in the thumbnail the moment it is taken, before the photo is saved. */
    val instantThumbnail: ImageBitmap? = null,
    /** The saved photo [instantThumbnail] stands for, once it is saved. */
    val instantThumbnailUri: String? = null,
    val rawEnabled: Boolean = false,
    /** Seconds left on the self-timer, or null when no countdown is running. */
    val countdown: Int? = null,
    /** The screen is lit white to act as a flash for the front camera. */
    val screenFlash: Boolean = false,
    val lastCaptureUri: String? = null,
    /** Local law requires the shutter sound, whatever the setting says. */
    val shutterSoundForced: Boolean = false,
    /** Zoom buttons for the current side (front or back), only those the hardware supports. */
    val zoomPresets: List<ZoomPreset> = emptyList(),
    /** Zoom of the open camera itself (1 = no zoom on that camera). */
    val zoomRatio: Float = 1f,
    /** How wide the open camera is relative to the main one: 1, or e.g. 0.5 for a separate ultra-wide. */
    val lensBase: Float = 1f,
    val macroAvailable: Boolean = false,
    val macro: Boolean = false,
    /** The phone maker's own mode (Night or HDR) the open session runs, or null for the normal camera. */
    val activeExtension: CameraExtension? = null,
    /** A night photo is being taken: the user should hold still. */
    val nightCapturing: Boolean = false,
    /** Frames taken so far while a panorama is being swept, or null when none is. */
    val panoramaFrames: Int? = null,
    /** System.nanoTime() when the current recording started, or null when not recording. */
    val recordingStartedNanos: Long? = null,
    val event: CameraEvent? = null,
) {
    val recording: Boolean get() = recordingStartedNanos != null

    val panoramaSweeping: Boolean get() = panoramaFrames != null

    /** How the camera is set up for the current video mode, or null in a photo mode. */
    val videoSpec: VideoSpec?
        get() = mode.video?.let { VideoSpec(it, cameraSettings.videoResolution, cameraSettings.timelapseSpeed) }

    /** 4K is offered where this camera and the phone's encoder can do it. */
    val showResolutionControl: Boolean
        get() = mode.isVideo && selectedCamera?.videoResolutions?.contains(VideoResolution.UHD) == true

    /** Timer and aspect ratio belong to photos; a panorama takes its own shape. */
    val showPhotoControls: Boolean get() = !mode.isVideo && mode != CaptureMode.PANORAMA

    /** Zoom as the user thinks of it, relative to the main camera. */
    val displayZoom: Float get() = lensBase * zoomRatio

    /** The zoom button to highlight: the widest preset at or below the current zoom, on this camera. */
    val activePreset: ZoomPreset?
        get() {
            val cameraId = selectedCamera?.cameraId ?: return null
            return zoomPresets.filter { it.cameraId == cameraId && it.zoomRatio <= zoomRatio + ZOOM_EPSILON }
                .maxByOrNull { it.zoomRatio }
        }

    /** RAW is a Pro mode option, and only on cameras that can deliver it. */
    val rawActive: Boolean
        get() = rawEnabled && mode == CaptureMode.PRO && selectedCamera?.supportsRaw == true

    val shutterSoundOn: Boolean get() = cameraSettings.shutterSound || shutterSoundForced

    /**
     * The maker's Night mode runs its own exposure, so there is no flash choice while it is open.
     * In video the flash is a steady light, which needs a real flash unit.
     */
    val showFlashControl: Boolean
        get() = if (mode.isVideo) {
            selectedCamera?.hasFlash == true
        } else if (mode == CaptureMode.PANORAMA) {
            false
        } else {
            selectedCamera?.supportsFlashControl == true && activeExtension == null
        }

    /** Night is a Photo mode option; Pro mode is for exposures the user sets by hand. */
    val showNightControl: Boolean get() = mode == CaptureMode.PHOTO

    /** The live exposure says it is dark: a long shutter at a high ISO. */
    val lowLight: Boolean
        get() {
            val exposure = (cameraState as? CameraState.Previewing)?.liveExposure ?: return false
            return exposure.iso * (exposure.exposureTimeNs / 1e9) >= LOW_LIGHT_EXPOSURE
        }

    /** Flash set to always fire wins over Night. */
    private val flashForced: Boolean
        get() = cameraSettings.flash == FlashMode.ON && selectedCamera?.supportsFlashControl == true

    /** The next photo is a night photo (the maker's Night mode, or our own multi-frame merge). */
    val nightActive: Boolean
        get() = showNightControl && !flashForced && when (cameraSettings.night) {
            NightSetting.OFF -> false
            NightSetting.ON -> true
            // Flash on Auto means the user wants the flash in the dark, so Night on Auto steps aside.
            NightSetting.AUTO -> cameraSettings.flash != FlashMode.AUTO && (activeExtension == CameraExtension.NIGHT || lowLight)
        }

    /** Indoors: dimmer than daylight, though not dark enough for Night. */
    private val indoorLight: Boolean
        get() {
            val exposure = (cameraState as? CameraState.Previewing)?.liveExposure ?: return false
            return exposure.iso * (exposure.exposureTimeNs / 1e9) >= INDOOR_EXPOSURE
        }

    /**
     * Frames to merge for the next photo, or 0 for a single frame: a long burst at night, and a
     * short one indoors, where a single frame is already noisy and smeared by noise reduction. The
     * maker's own modes merge frames themselves, and flash or the screen light needs a single frame.
     */
    val burstFrames: Int
        get() = when {
            activeExtension != null || mode != CaptureMode.PHOTO -> 0
            nightActive -> NIGHT_FRAMES
            indoorLight && cameraSettings.flash == FlashMode.OFF -> INDOOR_FRAMES
            else -> 0
        }

    /**
     * The maker's own mode to open, if any. Night set to On on a camera whose maker offers its own
     * Night mode uses that; Night on Auto keeps the normal camera, because deciding when it is dark
     * needs the live exposure only it reports. Otherwise Phone's HDR uses the maker's HDR mode (or
     * its automatic mode) for normal photos.
     */
    val wantedExtension: CameraExtension?
        get() {
            if (!showNightControl || flashForced || macro) return null
            val offered = selectedCamera?.extensions.orEmpty()
            if (cameraSettings.night == NightSetting.ON) return CameraExtension.NIGHT.takeIf { it in offered }
            if (!cameraSettings.phoneHdr) return null
            return listOf(CameraExtension.HDR, CameraExtension.AUTO).firstOrNull { it in offered }
        }
}

sealed interface CameraEvent {
    data class Saved(val uri: String) : CameraEvent
    data object CaptureFailed : CameraEvent
    data object RecordingFailed : CameraEvent
    /** The phone wasn't turned far enough for a second panorama frame. */
    data object PanoramaTooShort : CameraEvent
}

/** One-off cues for the UI, such as sounds. */
enum class CameraEffect { SHUTTER, TIMER_TICK, RECORDING_STARTED, RECORDING_STOPPED }

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val camera: CameraController,
    private val captureAndSave: CaptureAndSavePhoto,
    private val settingsRepository: SettingsRepository,
    private val media: MediaRepository,
    private val planZoomPresets: PlanZoomPresets,
    private val location: LocationProvider,
    private val rotation: RotationSensor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<CameraEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<CameraEffect> = _effects.asSharedFlow()

    val histogram: StateFlow<Histogram?> = camera.histogram

    val focusTarget: StateFlow<FocusTarget?> = camera.focusTarget

    /**
     * Live enhancement for the preview, worked out from the camera's brightness histogram and eased
     * so it doesn't flicker; null when Enhance is off.
     */
    val enhanceParams: StateFlow<EnhanceParams?> = combine(
        camera.histogram,
        _uiState.map { it.cameraSettings.enhance }.distinctUntilChanged(),
    ) { histogram, on ->
        if (!on) null else histogram?.let { EnhanceMath.fromHistogram(it.bins) } ?: EnhanceParams.DEFAULT
    }.scan(null as EnhanceParams?) { previous, next ->
        if (previous == null || next == null) next else EnhanceMath.blend(previous, next, ENHANCE_EASING)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The file the current recording goes into. */
    private var pendingVideo: PendingVideo? = null

    private var profile = DeviceProfile(emptyList())
    private var surface: PreviewTarget? = null
    private var timerJob: Job? = null

    /** Set to end the panorama being swept. */
    @Volatile
    private var panoramaStop = false

    /**
     * The app is on screen. Android takes the camera away from apps in the background (and reports
     * it as "disabled"), so the camera is closed when the app leaves and reopened when it returns.
     */
    private var foreground = true

    /** What the open (or opening) session was opened with, so repeated triggers don't reopen it. */
    private var openKey: OpenKey? = null
    private var retryJob: Job? = null
    private var retries = 0

    private data class OpenKey(
        val cameraId: String,
        val target: PreviewTarget,
        val raw: Boolean,
        val aspect: com.camoron.domain.model.StillAspect,
        val extension: CameraExtension?,
        val video: VideoSpec?,
    )

    @Volatile
    private var deviceRotation = 0

    /** Portrait width over height of the area the preview fills, used to crop full-screen photos. */
    private var displayAspect = DEFAULT_DISPLAY_ASPECT

    init {
        viewModelScope.launch {
            camera.state.collect { state ->
                _uiState.update { it.copy(cameraState = state) }
                when (state) {
                    is CameraState.Previewing -> retries = 0
                    is CameraState.Error -> {
                        // Keep what was filmed before the camera went away.
                        if (pendingVideo != null) viewModelScope.launch { finishRecording() }
                        onCameraError(state.error)
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            // Video frames get the same look and enhancement as the preview, drawn by the camera's renderer.
            combine(enhanceParams, _uiState.map { it.cameraSettings.look }.distinctUntilChanged()) { enhance, look ->
                LookMatrix.forLook(look) to enhance
            }.collect { (look, enhance) -> camera.setVideoFilter(look, enhance) }
        }
        viewModelScope.launch {
            val latest = runCatching { media.latestCapture() }.getOrNull()
            _uiState.update { it.copy(lastCaptureUri = it.lastCaptureUri ?: latest) }
        }
        viewModelScope.launch {
            // Settings first, so the first session already has the right aspect ratio and flash.
            onSettingsChanged(settingsRepository.settings.first(), initial = true)
            profile = camera.deviceProfile()
            _uiState.update {
                it.copy(
                    canSwitchCamera = profile.bestCamera(LensFacing.FRONT) != null &&
                        profile.bestCamera(LensFacing.BACK) != null,
                    shutterSoundForced = profile.mustPlayShutterSound,
                )
            }
            (profile.bestCamera(LensFacing.BACK) ?: profile.cameras.firstOrNull())?.let { select(it) }
            settingsRepository.settings.collect { onSettingsChanged(it) }
        }
    }

    /** The preview surface exists and has been sized; open the camera if it matches the selected one. */
    fun onSurfaceReady(target: PreviewTarget) {
        surface = target
        openIfReady()
    }

    fun onSurfaceDestroyed() {
        surface = null
        closeCamera()
    }

    /** The app came to the front (true) or went to the background (false). */
    fun onForegroundChanged(foreground: Boolean) {
        if (this.foreground == foreground) return
        this.foreground = foreground
        if (foreground) openIfReady() else closeCamera()
        syncLocation()
    }

    /** Follows the location only while the camera is on screen and Save location is on. */
    private fun syncLocation() {
        if (foreground && _uiState.value.cameraSettings.geotag) location.start() else location.stop()
    }

    override fun onCleared() {
        location.stop()
        rotation.stop()
    }

    /** The user tapped the error message: try the camera again. */
    fun retryCamera() {
        retries = 0
        openKey = null
        openIfReady()
    }

    private fun closeCamera() {
        cancelTimer()
        panoramaStop = true
        retryJob?.cancel()
        openKey = null
        viewModelScope.launch {
            // A recording in progress is saved before the camera goes.
            finishRecording()
            camera.close()
        }
    }

    /**
     * Losing the camera is usually temporary (another app grabbed it, or Android blocked it while the
     * app was briefly in the background), so try again a few times before leaving the error up.
     */
    private fun onCameraError(error: CameraError) {
        if (error == CameraError.PERMISSION_DENIED || error == CameraError.NO_CAMERA) return
        openKey = null
        if (!foreground || retries >= MAX_RETRIES) return
        retries++
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            delay(RETRY_DELAY_MS * retries)
            openIfReady()
        }
    }

    fun onDeviceRotation(degrees: Int) {
        deviceRotation = degrees
    }

    fun onDisplayAspect(aspect: Float) {
        if (aspect > 0f) displayAspect = aspect
    }

    fun switchCamera() {
        if (_uiState.value.recording || _uiState.value.panoramaSweeping) return
        val current = _uiState.value.selectedCamera ?: return
        val facing = if (current.lensFacing == LensFacing.FRONT) LensFacing.BACK else LensFacing.FRONT
        val target = profile.bestCamera(facing) ?: return
        viewModelScope.launch {
            if (_uiState.value.macro) {
                _uiState.update { it.copy(macro = false) }
                camera.setMacro(false)
            }
            select(target)
        }
    }

    /** A zoom button: zooms the open camera, or switches to a separate lens such as an ultra-wide. */
    fun selectZoomPreset(preset: ZoomPreset) {
        val state = _uiState.value
        if (state.selectedCamera?.cameraId == preset.cameraId) {
            setZoom(preset.zoomRatio)
            return
        }
        // Changing lens reopens the camera, which would end a recording.
        if (state.recording) return
        val target = profile.cameras.firstOrNull { it.cameraId == preset.cameraId } ?: return
        viewModelScope.launch { select(target, zoom = preset.zoomRatio) }
    }

    /** Pinch gesture: [scale] is the change since the last event (above 1 zooms in). */
    fun onPinch(scale: Float) {
        val selected = _uiState.value.selectedCamera ?: return
        val range = selected.zoomRange
        setZoom((_uiState.value.zoomRatio * scale).coerceIn(range.start, range.endInclusive))
    }

    /**
     * Macro: close-up focus. Uses the camera on this side that focuses closest, switching to it if
     * it isn't the open one, and back to the main camera when turned off.
     */
    fun toggleMacro() {
        val state = _uiState.value
        val selected = state.selectedCamera ?: return
        if (state.recording) return
        viewModelScope.launch {
            if (state.macro) {
                _uiState.update { it.copy(macro = false) }
                camera.setMacro(false)
                profile.bestCamera(selected.lensFacing)?.takeIf { it.cameraId != selected.cameraId }?.let { select(it) }
                    ?: syncExtensionSession()
                return@launch
            }
            val target = macroCamera(selected) ?: return@launch
            _uiState.update { it.copy(macro = true) }
            camera.setMacro(true)
            if (target.cameraId != selected.cameraId) select(target) else syncExtensionSession()
        }
    }

    fun setMode(mode: CaptureMode) {
        val before = _uiState.value
        if (before.recording || before.panoramaSweeping || mode == before.mode) return
        val rawBefore = before.rawActive
        _uiState.update { it.copy(mode = mode) }
        if (_uiState.value.videoSpec != before.videoSpec) {
            // Photo and video sessions are set up differently (and each video kind has its own frame rate).
            before.selectedCamera?.let { selected -> viewModelScope.launch { select(selected, zoom = before.zoomRatio) } }
            return
        }
        if (syncExtensionSession()) return
        if (_uiState.value.rawActive != rawBefore) {
            // Leaving or entering Pro with RAW on changes the session's streams.
            openIfReady()
            return
        }
        // Pro mode restores whatever the user dialled in; the other modes are fully automatic.
        applySettings(if (mode == CaptureMode.PRO) _uiState.value.settings else ManualSettings.AUTO)
    }

    /** In video the flash is a light that is on or off. */
    fun cycleFlash() {
        if (_uiState.value.mode.isVideo) {
            updateCameraSettings { it.copy(flash = if (it.flash == FlashMode.ON) FlashMode.OFF else FlashMode.ON) }
            return
        }
        updateCameraSettings { it.copy(flash = FlashMode.entries[(it.flash.ordinal + 1) % FlashMode.entries.size]) }
    }

    fun toggleVideoResolution() = updateCameraSettings {
        it.copy(videoResolution = if (it.videoResolution == VideoResolution.UHD) VideoResolution.FHD else VideoResolution.UHD)
    }

    fun cycleTimelapseSpeed() = updateCameraSettings {
        it.copy(timelapseSpeed = TimelapseSpeed.entries[(it.timelapseSpeed.ordinal + 1) % TimelapseSpeed.entries.size])
    }

    fun cycleTimer() = updateCameraSettings {
        it.copy(timer = TimerSetting.entries[(it.timer.ordinal + 1) % TimerSetting.entries.size])
    }

    fun cycleNight() = updateCameraSettings {
        it.copy(night = NightSetting.entries[(it.night.ordinal + 1) % NightSetting.entries.size])
    }

    fun setLook(look: ShotLook) = updateCameraSettings { it.copy(look = look) }

    fun toggleEnhance() = updateCameraSettings { it.copy(enhance = !it.enhance) }

    fun cycleAspectRatio() = updateCameraSettings {
        it.copy(aspectRatio = AspectRatioSetting.entries[(it.aspectRatio.ordinal + 1) % AspectRatioSetting.entries.size])
    }

    fun updateSettings(transform: (ManualSettings) -> ManualSettings) {
        val next = transform(_uiState.value.settings)
        _uiState.update { it.copy(settings = next) }
        if (_uiState.value.mode == CaptureMode.PRO) applySettings(next)
    }

    fun setIso(iso: Int?) = updateSettings { it.copy(iso = iso) }
    fun setShutter(exposureNs: Long?) = updateSettings { it.copy(exposureTimeNs = exposureNs) }
    fun setExposureCompensation(steps: Int) = updateSettings { it.copy(exposureCompensation = steps) }
    fun setWhiteBalance(wb: WhiteBalance) = updateSettings { it.copy(whiteBalance = wb) }

    fun setRawEnabled(enabled: Boolean) {
        val rawBefore = _uiState.value.rawActive
        _uiState.update { it.copy(rawEnabled = enabled) }
        if (_uiState.value.rawActive != rawBefore) openIfReady()
    }

    fun focusAt(x: Float, y: Float) {
        val track = _uiState.value.cameraSettings.autoFocusTracking
        viewModelScope.launch { camera.focusAt(x, y, track) }
    }

    /** Shutter button: takes the photo, starts the self-timer, or cancels a running countdown. In video, starts or stops recording. */
    fun onShutter() {
        if (_uiState.value.mode.isVideo) {
            toggleRecording()
            return
        }
        if (_uiState.value.mode == CaptureMode.PANORAMA) {
            togglePanorama()
            return
        }
        if (cancelTimer()) return
        val state = _uiState.value
        if (state.isCapturing || state.cameraState !is CameraState.Previewing) return
        val seconds = state.cameraSettings.timer.seconds
        if (seconds == 0) {
            viewModelScope.launch { takePhoto() }
            return
        }
        timerJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _uiState.update { it.copy(countdown = remaining) }
                if (_uiState.value.shutterSoundOn) _effects.tryEmit(CameraEffect.TIMER_TICK)
                delay(1_000)
            }
            _uiState.update { it.copy(countdown = null) }
            timerJob = null
            takePhoto()
        }
    }

    fun eventConsumed() = _uiState.update { it.copy(event = null) }

    /** Panorama: the first tap starts the sweep, the next one (or the last frame) finishes it. */
    private fun togglePanorama() {
        val state = _uiState.value
        if (state.panoramaSweeping) {
            panoramaStop = true
            return
        }
        if (state.isCapturing || state.cameraState !is CameraState.Previewing) return
        val selected = state.selectedCamera ?: return
        if (!rotation.start()) {
            DiagnosticLog.info(TAG, "No rotation sensor for a panorama")
            _uiState.update { it.copy(event = CameraEvent.CaptureFailed) }
            return
        }
        panoramaStop = false
        _uiState.update { it.copy(panoramaFrames = 0) }
        viewModelScope.launch { sweepPanorama(state, selected) }
    }

    /**
     * Takes a frame at the start and another each time the phone has turned on by [PanoramaPlan.step],
     * in whichever direction the user turns first, with exposure held so the frames match; then
     * stitches and saves them.
     */
    private suspend fun sweepPanorama(state: CameraUiState, selected: CameraCapabilities) {
        val front = selected.lensFacing == LensFacing.FRONT
        val frames = mutableListOf<CapturedImage>()
        val yaws = mutableListOf<Float>()
        val step = PanoramaPlan.step(
            selected.equivalentFocalLengthMm,
            state.zoomRatio,
            portrait = deviceRotation % 180 == 0,
            longOverShort = state.cameraSettings.aspectRatio.stillAspect.ratio.toFloat(),
        )
        val started = SystemClock.elapsedRealtime()
        try {
            camera.lockExposure(true)
            var last = awaitYaw(front) ?: run {
                DiagnosticLog.info(TAG, "The rotation sensor didn't report")
                return
            }
            var turned = 0f
            var shotAt = 0f
            var direction = 0f
            while (!panoramaStop && frames.size < PanoramaPlan.MAX_FRAMES) {
                val due = frames.isEmpty() || (direction != 0f && (turned - shotAt) * direction >= step)
                if (due) {
                    if (state.shutterSoundOn) _effects.tryEmit(CameraEffect.SHUTTER)
                    frames += captureAndSave.captureFrame(deviceRotation)
                    yaws += turned
                    shotAt = turned
                    _uiState.update { it.copy(panoramaFrames = frames.size) }
                }
                delay(PANORAMA_POLL_MS)
                val now = rotation.cameraYaw(front) ?: continue
                turned += PanoramaPlan.wrap(now - last)
                last = now
                if (direction == 0f && abs(turned - shotAt) >= step) direction = sign(turned - shotAt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the frames taken so far: the camera may have gone mid-sweep.
            DiagnosticLog.record(TAG, "Panorama frame failed", e)
        } finally {
            rotation.stop()
            runCatching { camera.lockExposure(false) }
            _uiState.update { it.copy(panoramaFrames = null) }
        }
        DiagnosticLog.info(TAG, "Panorama: ${frames.size} frames over %.0f° in ${SystemClock.elapsedRealtime() - started} ms".format(Math.toDegrees(abs(yaws.lastOrNull() ?: 0f).toDouble())))
        if (frames.size < 2) {
            _uiState.update { it.copy(event = CameraEvent.PanoramaTooShort) }
            return
        }
        _uiState.update { it.copy(photosSaving = it.photosSaving + 1) }
        val adjustments = PhotoAdjustments(
            look = state.cameraSettings.look,
            enhance = state.cameraSettings.enhance,
            zoom = state.zoomRatio,
            location = if (state.cameraSettings.geotag) location.current() else null,
        )
        val focal = selected.equivalentFocalLengthMm?.let { it * state.zoomRatio }
        val uri = runCatching { captureAndSave.savePanorama(frames, yaws, focal, adjustments) }
            .onFailure { DiagnosticLog.record(TAG, "Couldn't stitch the panorama", it) }
            .getOrNull()
        if (uri != null) DiagnosticLog.info(TAG, "Panorama saved ${SystemClock.elapsedRealtime() - started} ms after the first tap")
        _uiState.update {
            it.copy(
                photosSaving = (it.photosSaving - 1).coerceAtLeast(0),
                lastCaptureUri = uri ?: it.lastCaptureUri,
                instantThumbnail = if (uri != null) null else it.instantThumbnail,
                event = if (uri != null) CameraEvent.Saved(uri) else CameraEvent.CaptureFailed,
            )
        }
    }

    /** The first heading the sensor reports, or null if it says nothing for a while. */
    private suspend fun awaitYaw(front: Boolean): Float? = withTimeoutOrNull(SENSOR_WAIT_MS) {
        var yaw = rotation.cameraYaw(front)
        while (yaw == null) {
            delay(PANORAMA_POLL_MS)
            yaw = rotation.cameraYaw(front)
        }
        yaw
    }

    private fun toggleRecording() {
        val state = _uiState.value
        if (state.recording) {
            viewModelScope.launch { finishRecording() }
            return
        }
        if (state.isCapturing || state.cameraState !is CameraState.Previewing) return
        viewModelScope.launch { startRecording() }
    }

    private suspend fun startRecording() {
        _uiState.update { it.copy(isCapturing = true) }
        var video: PendingVideo? = null
        try {
            video = media.createVideo(System.currentTimeMillis())
            camera.startRecording(video.fileDescriptor, deviceRotation)
            pendingVideo = video
            if (_uiState.value.shutterSoundOn) _effects.tryEmit(CameraEffect.RECORDING_STARTED)
            _uiState.update { it.copy(isCapturing = false, recordingStartedNanos = System.nanoTime()) }
        } catch (e: CancellationException) {
            video?.finish(keep = false)
            throw e
        } catch (e: Exception) {
            DiagnosticLog.record(TAG, "Couldn't start recording", e)
            runCatching { video?.finish(keep = false) }
            _uiState.update { it.copy(isCapturing = false, event = CameraEvent.RecordingFailed) }
        }
    }

    /** Stops the recording in progress, if any, and saves it to the gallery. */
    private suspend fun finishRecording() {
        val video = pendingVideo ?: return
        pendingVideo = null
        _uiState.update { it.copy(recordingStartedNanos = null, isCapturing = true) }
        val recorded = runCatching { camera.stopRecording() }
            .onFailure { DiagnosticLog.record(TAG, "Couldn't stop recording", it) }
            .getOrDefault(false)
        val uri = runCatching { video.finish(keep = recorded) }
            .onFailure { DiagnosticLog.record(TAG, "Couldn't save the video", it) }
            .getOrNull()
        if (_uiState.value.shutterSoundOn) _effects.tryEmit(CameraEffect.RECORDING_STOPPED)
        _uiState.update {
            it.copy(
                isCapturing = false,
                lastCaptureUri = uri ?: it.lastCaptureUri,
                event = if (uri != null) CameraEvent.Saved(uri) else CameraEvent.RecordingFailed,
            )
        }
    }

    /** Returns true if a countdown was running. */
    private fun cancelTimer(): Boolean {
        val job = timerJob ?: return false
        job.cancel()
        timerJob = null
        _uiState.update { it.copy(countdown = null) }
        return true
    }

    private suspend fun takePhoto() {
        if (_uiState.value.isCapturing || _uiState.value.cameraState !is CameraState.Previewing) return
        _uiState.update { it.copy(isCapturing = true) }
        val state = _uiState.value
        val night = state.nightActive
        val frames = state.burstFrames
        if (night) _uiState.update { it.copy(nightCapturing = true) }
        if (!night && needsScreenFlash(state)) {
            // Light the screen first so auto exposure has time to adapt to it.
            _uiState.update { it.copy(screenFlash = true) }
            delay(SCREEN_FLASH_LEAD_MS)
        }
        if (state.shutterSoundOn) _effects.tryEmit(CameraEffect.SHUTTER)
        // The shutter is free again as soon as the frames are in; processing and saving carry on.
        val started = SystemClock.elapsedRealtime()
        var captured = false
        val adjustments = adjustmentsFor(state)
        val ready = { photo: CapturedImage ->
            captured = true
            DiagnosticLog.info(TAG, "Photo in after ${SystemClock.elapsedRealtime() - started} ms")
            _uiState.update {
                it.copy(isCapturing = false, screenFlash = false, nightCapturing = false, photosSaving = it.photosSaving + 1)
            }
            showInstantThumbnail(photo, adjustments.mirror)
        }
        val uri = runCatching {
            captureAndSave(deviceRotation, state.rawActive, adjustments, frames, onCaptured = ready)
        }.onFailure { DiagnosticLog.record(TAG, "Couldn't take the photo", it) }.getOrNull()
        if (uri != null) {
            DiagnosticLog.info(
                TAG,
                "Photo saved ${SystemClock.elapsedRealtime() - started} ms after the shutter " +
                    "(${state.cameraSettings.aspectRatio}, preview ${state.previewSize}, screen %.3f, crop ${adjustments.cropAspect}, ".format(displayAspect) +
                    "enhance ${adjustments.enhance}, zoom ${adjustments.zoom}, frames $frames, maker mode ${state.activeExtension ?: "none"})",
            )
        }
        _uiState.update {
            it.copy(
                isCapturing = false,
                screenFlash = false,
                nightCapturing = false,
                photosSaving = if (captured) (it.photosSaving - 1).coerceAtLeast(0) else it.photosSaving,
                lastCaptureUri = uri ?: it.lastCaptureUri,
                instantThumbnailUri = if (uri != null && it.instantThumbnail != null) uri else it.instantThumbnailUri,
                event = if (uri != null) CameraEvent.Saved(uri) else CameraEvent.CaptureFailed,
            )
        }
    }

    /** Shows the shot in the thumbnail straight away, upright, while it is being optimised. */
    private fun showInstantThumbnail(photo: CapturedImage, mirror: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            val image = runCatching { InstantThumbnail.from(photo, mirror) }
                .onFailure { DiagnosticLog.record(TAG, "Couldn't show the instant thumbnail", it) }
                .getOrNull() ?: return@launch
            _uiState.update { it.copy(instantThumbnail = image, instantThumbnailUri = null) }
        }
    }

    /** Front cameras without a flash unit use the screen instead. Auto fires it in low light. */
    private fun needsScreenFlash(state: CameraUiState): Boolean {
        val selected = state.selectedCamera ?: return false
        if (selected.lensFacing != LensFacing.FRONT || selected.hasFlash) return false
        return when (state.cameraSettings.flash) {
            FlashMode.OFF -> false
            FlashMode.ON -> true
            FlashMode.AUTO -> {
                val iso = (state.cameraState as? CameraState.Previewing)?.liveExposure?.iso ?: return false
                iso >= LOW_LIGHT_ISO
            }
        }
    }

    /** Makes the saved photo match the preview's shape, and mirror it for selfies when asked. */
    private fun adjustmentsFor(state: CameraUiState) = PhotoAdjustments(
        cropAspect = when (state.cameraSettings.aspectRatio) {
            AspectRatioSetting.RATIO_3_4 -> 3f / 4
            AspectRatioSetting.RATIO_9_16 -> 9f / 16
            AspectRatioSetting.FULL_SCREEN -> displayAspect
        },
        mirror = state.selectedCamera?.lensFacing == LensFacing.FRONT && state.cameraSettings.selfieMirror,
        look = state.cameraSettings.look,
        enhance = state.cameraSettings.enhance,
        zoom = state.zoomRatio,
        location = if (state.cameraSettings.geotag) location.current() else null,
    )

    private fun updateCameraSettings(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    private suspend fun onSettingsChanged(next: CameraSettings, initial: Boolean = false) {
        val previous = _uiState.value.cameraSettings
        if (!initial && next == previous) return
        _uiState.update { it.copy(cameraSettings = next) }
        if (initial || next.geotag != previous.geotag) syncLocation()
        if (initial || next.flash != previous.flash) camera.setFlashMode(next.flash)
        val videoChanged = next.videoResolution != previous.videoResolution || next.timelapseSpeed != previous.timelapseSpeed
        if (!initial && _uiState.value.mode.isVideo && videoChanged) {
            _uiState.value.selectedCamera?.let { select(it, zoom = _uiState.value.zoomRatio) }
        } else if (!initial && !_uiState.value.mode.isVideo && next.aspectRatio.stillAspect != previous.aspectRatio.stillAspect) {
            // A different sensor output shape needs a new preview size and session.
            _uiState.value.selectedCamera?.let { select(it) }
        } else if (!initial) {
            syncExtensionSession()
        }
    }

    private fun setZoom(ratio: Float) {
        _uiState.update { it.copy(zoomRatio = ratio) }
        viewModelScope.launch { camera.setZoom(ratio) }
    }

    /** The camera on [current]'s side that focuses closest, preferring the open one when it qualifies. */
    private fun macroCamera(current: CameraCapabilities): CameraCapabilities? {
        if (current.supportsMacro) return current
        return profile.cameras
            .filter { it.lensFacing == current.lensFacing && it.supportsMacro }
            .maxByOrNull { it.minFocusDistanceDiopters }
    }

    private suspend fun select(capabilities: CameraCapabilities, zoom: Float = 1f) {
        cancelTimer()
        val aspect = _uiState.value.cameraSettings.aspectRatio.stillAspect
        val extension = _uiState.value.copy(selectedCamera = capabilities).wantedExtension
        val video = _uiState.value.videoSpec
        val size = runCatching {
            if (video != null) {
                camera.videoPreviewSize(capabilities.cameraId, video)
            } else {
                camera.previewSize(capabilities.cameraId, aspect, extension)
            }
        }.getOrNull()
        val main = profile.bestCamera(capabilities.lensFacing) ?: capabilities
        val sameSide = profile.cameras.filter { it.lensFacing == capabilities.lensFacing }
        val presets = planZoomPresets(main, sameSide)
        // A separate lens's 1x is its button's label, e.g. 0.5 for an ultra-wide.
        val base = if (capabilities.cameraId == main.cameraId) {
            1f
        } else {
            presets.firstOrNull { it.cameraId == capabilities.cameraId }?.label ?: 1f
        }
        _uiState.update {
            // Manual values are per-sensor, so start each camera from automatic.
            it.copy(
                selectedCamera = capabilities,
                previewSize = size,
                settings = ManualSettings.AUTO,
                zoomPresets = presets,
                zoomRatio = zoom,
                lensBase = base,
                macroAvailable = sameSide.any { camera -> camera.supportsMacro },
                activeExtension = extension,
            )
        }
        // If the surface is already the right size the UI will not resize it, so open directly.
        openIfReady()
    }

    private fun openIfReady() {
        val state = _uiState.value
        if (!foreground) return
        val target = surface ?: return
        val selected = state.selectedCamera ?: return
        val size = state.previewSize ?: return
        if (target.width != size.width || target.height != size.height) return
        val key = OpenKey(
            selected.cameraId,
            target,
            raw = state.rawActive,
            aspect = state.cameraSettings.aspectRatio.stillAspect,
            extension = state.activeExtension,
            video = state.videoSpec,
        )
        // Several events (surface sized, camera selected, app resumed) can ask for the same session.
        if (key == openKey) return
        openKey = key
        viewModelScope.launch {
            val video = key.video
            if (video != null) {
                camera.openVideo(key.cameraId, target, video)
            } else {
                camera.open(
                    key.cameraId,
                    target,
                    rawEnabled = key.raw,
                    aspect = key.aspect,
                    extension = key.extension,
                )
            }
            if (state.mode == CaptureMode.PRO) camera.applySettings(_uiState.value.settings)
            val zoom = _uiState.value.zoomRatio
            if (zoom != 1f) camera.setZoom(zoom)
        }
    }

    /**
     * Reopens the camera when the maker's mode it should run changed (its Night or HDR mode turned
     * on or off). Returns true if it did.
     */
    private fun syncExtensionSession(): Boolean {
        val state = _uiState.value
        val selected = state.selectedCamera ?: return false
        val wanted = state.wantedExtension
        if (wanted == state.activeExtension) return false
        viewModelScope.launch {
            val aspect = state.cameraSettings.aspectRatio.stillAspect
            val size = runCatching { camera.previewSize(selected.cameraId, aspect, wanted) }.getOrNull()
            _uiState.update { it.copy(previewSize = size, activeExtension = wanted) }
            openIfReady()
        }
        return true
    }

    private fun applySettings(settings: ManualSettings) {
        viewModelScope.launch { camera.applySettings(settings) }
    }

    private companion object {
        const val DEFAULT_DISPLAY_ASPECT = 9f / 20
        const val SCREEN_FLASH_LEAD_MS = 600L
        const val LOW_LIGHT_ISO = 800
        const val ENHANCE_EASING = 0.35f
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 800L
        const val TAG = "Camera"
        const val PANORAMA_POLL_MS = 40L
        const val SENSOR_WAIT_MS = 1_000L
    }
}

private const val ZOOM_EPSILON = 0.01f

/**
 * ISO times shutter seconds at which Night on Auto kicks in, e.g. ISO 400 at 1/33 s or ISO 800 at
 * 1/66 s: ordinary indoor light, where a single frame is already visibly grainy.
 */
private const val LOW_LIGHT_EXPOSURE = 12.0

/** ISO times seconds from which a photo counts as indoors, e.g. ISO 100 at 1/60 s; daylight is far below. */
private const val INDOOR_EXPOSURE = 1.5

internal const val NIGHT_FRAMES = 8
internal const val INDOOR_FRAMES = 4
