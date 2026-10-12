package com.camoron.feature.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.Histogram
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.WhiteBalance
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.PreviewTarget
import com.camoron.domain.usecase.CaptureAndSavePhoto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val isCapturing: Boolean = false,
    val rawEnabled: Boolean = false,
    val event: CameraEvent? = null,
) {
    /** RAW is a Pro mode option, and only on cameras that can deliver it. */
    val rawActive: Boolean
        get() = rawEnabled && mode == CaptureMode.PRO && selectedCamera?.supportsRaw == true
}

sealed interface CameraEvent {
    data class Saved(val uri: String) : CameraEvent
    data object CaptureFailed : CameraEvent
}

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val camera: CameraController,
    private val captureAndSave: CaptureAndSavePhoto,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    val histogram: StateFlow<Histogram?> = camera.histogram

    private var cameras: List<CameraCapabilities> = emptyList()
    private var surface: PreviewTarget? = null

    init {
        viewModelScope.launch {
            camera.state.collect { state -> _uiState.update { it.copy(cameraState = state) } }
        }
        viewModelScope.launch {
            cameras = camera.cameras()
            val initial = bestCamera(LensFacing.BACK) ?: cameras.firstOrNull()
            _uiState.update {
                it.copy(canSwitchCamera = bestCamera(LensFacing.FRONT) != null && bestCamera(LensFacing.BACK) != null)
            }
            initial?.let { select(it) }
        }
    }

    /** The preview surface exists and has been sized; open the camera if it matches the selected one. */
    fun onSurfaceReady(target: PreviewTarget) {
        surface = target
        openIfReady()
    }

    fun onSurfaceDestroyed() {
        surface = null
        viewModelScope.launch { camera.close() }
    }

    fun switchCamera() {
        val current = _uiState.value.selectedCamera ?: return
        val facing = if (current.lensFacing == LensFacing.FRONT) LensFacing.BACK else LensFacing.FRONT
        bestCamera(facing)?.let { viewModelScope.launch { select(it) } }
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

    fun capture(deviceRotationDegrees: Int) {
        if (_uiState.value.isCapturing || _uiState.value.cameraState !is CameraState.Previewing) return
        _uiState.update { it.copy(isCapturing = true) }
        viewModelScope.launch {
            val includeRaw = _uiState.value.rawActive
            val event = runCatching { captureAndSave(deviceRotationDegrees, includeRaw) }
                .fold(onSuccess = { CameraEvent.Saved(it) }, onFailure = { CameraEvent.CaptureFailed })
            _uiState.update { it.copy(isCapturing = false, event = event) }
        }
    }

    fun eventConsumed() = _uiState.update { it.copy(event = null) }

    private suspend fun select(capabilities: CameraCapabilities) {
        val size = runCatching { camera.previewSize(capabilities.cameraId) }.getOrNull()
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
            camera.open(selected.cameraId, target, rawEnabled = state.rawActive)
            if (state.mode == CaptureMode.PRO) camera.applySettings(_uiState.value.settings)
        }
    }

    private fun applySettings(settings: ManualSettings) {
        viewModelScope.launch { camera.applySettings(settings) }
    }

    private fun bestCamera(facing: LensFacing): CameraCapabilities? =
        cameras.filter { it.lensFacing == facing }.maxByOrNull { it.sensorSize.megapixels }
}
