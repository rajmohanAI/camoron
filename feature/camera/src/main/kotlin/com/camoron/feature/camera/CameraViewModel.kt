package com.camoron.feature.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camoron.domain.model.AspectRatioSetting
import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraSettings
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.DeviceProfile
import com.camoron.domain.model.FlashMode
import com.camoron.domain.model.Histogram
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.PhotoAdjustments
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.TimerSetting
import com.camoron.domain.model.WhiteBalance
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PreviewTarget
import com.camoron.domain.repository.SettingsRepository
import com.camoron.domain.usecase.CaptureAndSavePhoto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CaptureMode { PHOTO, PRO }

data class CameraUiState(
    val cameraState: CameraState = CameraState.Closed,
    val selectedCamera: CameraCapabilities? = null,
    val canSwitchCamera: Boolean = false,
    val previewSize: PixelSize? = null,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val settings: ManualSettings = ManualSettings.AUTO,
    val cameraSettings: CameraSettings = CameraSettings(),
    val isCapturing: Boolean = false,
    val rawEnabled: Boolean = false,
    /** Seconds left on the self-timer, or null when no countdown is running. */
    val countdown: Int? = null,
    /** The screen is lit white to act as a flash for the front camera. */
    val screenFlash: Boolean = false,
    val lastCaptureUri: String? = null,
    /** Local law requires the shutter sound, whatever the setting says. */
    val shutterSoundForced: Boolean = false,
    val event: CameraEvent? = null,
) {
    /** RAW is a Pro mode option, and only on cameras that can deliver it. */
    val rawActive: Boolean
        get() = rawEnabled && mode == CaptureMode.PRO && selectedCamera?.supportsRaw == true

    val shutterSoundOn: Boolean get() = cameraSettings.shutterSound || shutterSoundForced

    val showFlashControl: Boolean get() = selectedCamera?.supportsFlashControl == true
}

sealed interface CameraEvent {
    data class Saved(val uri: String) : CameraEvent
    data object CaptureFailed : CameraEvent
}

/** One-off cues for the UI, such as sounds. */
enum class CameraEffect { SHUTTER, TIMER_TICK }

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val camera: CameraController,
    private val captureAndSave: CaptureAndSavePhoto,
    private val settingsRepository: SettingsRepository,
    private val media: MediaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<CameraEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<CameraEffect> = _effects.asSharedFlow()

    val histogram: StateFlow<Histogram?> = camera.histogram

    private var profile = DeviceProfile(emptyList())
    private var surface: PreviewTarget? = null
    private var timerJob: Job? = null

    @Volatile
    private var deviceRotation = 0

    /** Portrait width over height of the area the preview fills, used to crop full-screen photos. */
    private var displayAspect = DEFAULT_DISPLAY_ASPECT

    init {
        viewModelScope.launch {
            camera.state.collect { state -> _uiState.update { it.copy(cameraState = state) } }
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
        cancelTimer()
        viewModelScope.launch { camera.close() }
    }

    fun onDeviceRotation(degrees: Int) {
        deviceRotation = degrees
    }

    fun onDisplayAspect(aspect: Float) {
        if (aspect > 0f) displayAspect = aspect
    }

    fun switchCamera() {
        val current = _uiState.value.selectedCamera ?: return
        val facing = if (current.lensFacing == LensFacing.FRONT) LensFacing.BACK else LensFacing.FRONT
        profile.bestCamera(facing)?.let { viewModelScope.launch { select(it) } }
    }

    fun setMode(mode: CaptureMode) {
        val rawBefore = _uiState.value.rawActive
        _uiState.update { it.copy(mode = mode) }
        if (_uiState.value.rawActive != rawBefore) {
            // Leaving or entering Pro with RAW on changes the session's streams.
            openIfReady()
            return
        }
        // Photo mode is fully automatic; Pro mode restores whatever the user dialled in.
        applySettings(if (mode == CaptureMode.PHOTO) ManualSettings.AUTO else _uiState.value.settings)
    }

    fun cycleFlash() = updateCameraSettings {
        it.copy(flash = FlashMode.entries[(it.flash.ordinal + 1) % FlashMode.entries.size])
    }

    fun cycleTimer() = updateCameraSettings {
        it.copy(timer = TimerSetting.entries[(it.timer.ordinal + 1) % TimerSetting.entries.size])
    }

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
        viewModelScope.launch { camera.focusAt(x, y) }
    }

    /** Shutter button: takes the photo, starts the self-timer, or cancels a running countdown. */
    fun onShutter() {
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
        if (needsScreenFlash(state)) {
            // Light the screen first so auto exposure has time to adapt to it.
            _uiState.update { it.copy(screenFlash = true) }
            delay(SCREEN_FLASH_LEAD_MS)
        }
        if (state.shutterSoundOn) _effects.tryEmit(CameraEffect.SHUTTER)
        val uri = runCatching { captureAndSave(deviceRotation, state.rawActive, adjustmentsFor(state)) }.getOrNull()
        _uiState.update {
            it.copy(
                isCapturing = false,
                screenFlash = false,
                lastCaptureUri = uri ?: it.lastCaptureUri,
                event = if (uri != null) CameraEvent.Saved(uri) else CameraEvent.CaptureFailed,
            )
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
    )

    private fun updateCameraSettings(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    private suspend fun onSettingsChanged(next: CameraSettings, initial: Boolean = false) {
        val previous = _uiState.value.cameraSettings
        if (!initial && next == previous) return
        _uiState.update { it.copy(cameraSettings = next) }
        if (initial || next.flash != previous.flash) camera.setFlashMode(next.flash)
        if (!initial && next.aspectRatio.stillAspect != previous.aspectRatio.stillAspect) {
            // A different sensor output shape needs a new preview size and session.
            _uiState.value.selectedCamera?.let { select(it) }
        }
    }

    private suspend fun select(capabilities: CameraCapabilities) {
        cancelTimer()
        val aspect = _uiState.value.cameraSettings.aspectRatio.stillAspect
        val size = runCatching { camera.previewSize(capabilities.cameraId, aspect) }.getOrNull()
        _uiState.update {
            // Manual values are per-sensor, so start each camera from automatic.
            it.copy(selectedCamera = capabilities, previewSize = size, settings = ManualSettings.AUTO)
        }
        // If the surface is already the right size the UI will not resize it, so open directly.
        openIfReady()
    }

    private fun openIfReady() {
        val state = _uiState.value
        val target = surface ?: return
        val selected = state.selectedCamera ?: return
        val size = state.previewSize ?: return
        if (target.width != size.width || target.height != size.height) return
        viewModelScope.launch {
            camera.open(
                selected.cameraId,
                target,
                rawEnabled = state.rawActive,
                aspect = state.cameraSettings.aspectRatio.stillAspect,
            )
            if (state.mode == CaptureMode.PRO) camera.applySettings(_uiState.value.settings)
        }
    }

    private fun applySettings(settings: ManualSettings) {
        viewModelScope.launch { camera.applySettings(settings) }
    }

    private companion object {
        const val DEFAULT_DISPLAY_ASPECT = 9f / 20
        const val SCREEN_FLASH_LEAD_MS = 600L
        const val LOW_LIGHT_ISO = 800
    }
}
