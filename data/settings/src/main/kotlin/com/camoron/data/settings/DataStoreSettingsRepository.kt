package com.camoron.data.settings

import androidx.datastore.core.DataStore
import com.camoron.domain.model.CameraSettings
import com.camoron.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal class DataStoreSettingsRepository @Inject constructor(
    private val store: DataStore<StoredCameraSettings>,
) : SettingsRepository {

    override val settings: Flow<CameraSettings> = store.data.map { it.toDomain() }.distinctUntilChanged()

    override suspend fun update(transform: (CameraSettings) -> CameraSettings) {
        store.updateData { StoredCameraSettings.from(transform(it.toDomain())) }
    }

    override suspend fun reset() {
        store.updateData { StoredCameraSettings() }
    }
}
