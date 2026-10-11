package com.camoron.domain.repository

import com.camoron.domain.model.ConsentState
import kotlinx.coroutines.flow.Flow

interface ConsentRepository {
    val consent: Flow<ConsentState>
    suspend fun acceptDisclaimer(version: Int)
}
