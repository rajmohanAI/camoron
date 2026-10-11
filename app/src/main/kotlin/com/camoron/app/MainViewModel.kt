package com.camoron.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camoron.domain.repository.ConsentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(consentRepository: ConsentRepository) : ViewModel() {

    /** Null until the consent record has been read from disk. */
    val disclaimerAccepted: StateFlow<Boolean?> = consentRepository.consent
        .map { it.hasAcceptedCurrentDisclaimer }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
