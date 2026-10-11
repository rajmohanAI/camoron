package com.camoron.data.settings

import androidx.datastore.core.DataStore
import com.camoron.domain.model.ConsentState
import com.camoron.domain.repository.ConsentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal class DataStoreConsentRepository @Inject constructor(
    private val store: DataStore<StoredConsent>,
) : ConsentRepository {

    override val consent: Flow<ConsentState> = store.data.map {
        ConsentState(
            acceptedDisclaimerVersion = it.acceptedDisclaimerVersion,
            biometricConsentVersion = it.biometricConsentVersion,
        )
    }

    override suspend fun acceptDisclaimer(version: Int) {
        store.updateData { it.copy(acceptedDisclaimerVersion = version, disclaimerAcceptedAtMillis = System.currentTimeMillis()) }
    }
}
