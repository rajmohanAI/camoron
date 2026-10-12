package com.camoron.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camoron.domain.model.CameraSettings
import com.camoron.domain.model.DeviceProfile
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: CameraSettings = CameraSettings(),
    val profile: DeviceProfile? = null,
) {
    /** Japan, Korea and some other regions require an audible shutter; the switch is locked there. */
    val shutterSoundLocked: Boolean get() = profile?.mustPlayShutterSound == true
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val camera: CameraController,
) : ViewModel() {

    private val profile = MutableStateFlow<DeviceProfile?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(repository.settings, profile, ::SettingsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { profile.value = runCatching { camera.deviceProfile() }.getOrNull() }
    }

    fun setSelfieMirror(on: Boolean) = update { it.copy(selfieMirror = on) }
    fun setShutterSound(on: Boolean) {
        if (uiState.value.shutterSoundLocked) return
        update { it.copy(shutterSound = on) }
    }
    fun setAutoFocusTracking(on: Boolean) = update { it.copy(autoFocusTracking = on) }
    fun setGrid(on: Boolean) = update { it.copy(grid = on) }
    fun setLeveller(on: Boolean) = update { it.copy(leveller = on) }

    fun reset() {
        viewModelScope.launch { repository.reset() }
    }

    private fun update(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
