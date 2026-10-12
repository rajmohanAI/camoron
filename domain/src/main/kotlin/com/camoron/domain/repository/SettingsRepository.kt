package com.camoron.domain.repository

import com.camoron.domain.model.CameraSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<CameraSettings>
    suspend fun update(transform: (CameraSettings) -> CameraSettings)
    suspend fun reset()
}
