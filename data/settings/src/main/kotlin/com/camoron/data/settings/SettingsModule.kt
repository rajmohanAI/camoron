package com.camoron.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.camoron.domain.repository.ConsentRepository
import com.camoron.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SettingsModule {

    @Binds
    @Singleton
    abstract fun bindConsentRepository(impl: DataStoreConsentRepository): ConsentRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    companion object {
        @Provides
        @Singleton
        fun consentStore(@ApplicationContext context: Context): DataStore<StoredConsent> =
            DataStoreFactory.create(StoredConsentSerializer) { context.dataStoreFile("consent.json") }

        @Provides
        @Singleton
        fun cameraSettingsStore(@ApplicationContext context: Context): DataStore<StoredCameraSettings> =
            DataStoreFactory.create(StoredCameraSettingsSerializer) { context.dataStoreFile("camera_settings.json") }
    }
}
