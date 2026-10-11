package com.camoron.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camoron.domain.model.ConsentState
import com.camoron.domain.model.DeviceSupport
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.ConsentRepository
import com.camoron.domain.usecase.EvaluateDeviceSupport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val loading: Boolean = true,
    val disclaimerAccepted: Boolean = false,
    val deviceSupport: DeviceSupport? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val consentRepository: ConsentRepository,
    private val camera: CameraController,
    private val evaluateDeviceSupport: EvaluateDeviceSupport,
) : ViewModel() {

    private val deviceSupport = MutableStateFlow<DeviceSupport?>(null)

    val uiState: StateFlow<OnboardingUiState> =
        combine(consentRepository.consent, deviceSupport) { consent, support ->
            OnboardingUiState(
                loading = false,
                disclaimerAccepted = consent.hasAcceptedCurrentDisclaimer,
                deviceSupport = support,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    init {
        viewModelScope.launch {
            // Reading characteristics does not need the CAMERA permission.
            deviceSupport.value = runCatching { evaluateDeviceSupport(camera.cameras()) }.getOrNull()
        }
    }

    fun acceptDisclaimer() {
        viewModelScope.launch { consentRepository.acceptDisclaimer(ConsentState.CURRENT_DISCLAIMER_VERSION) }
    }
}
