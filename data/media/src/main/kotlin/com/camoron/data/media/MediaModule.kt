package com.camoron.data.media

import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PhotoLibrary
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MediaModule {
    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaStoreRepository): MediaRepository

    @Binds
    @Singleton
    abstract fun bindPhotoLibrary(impl: MediaStoreRepository): PhotoLibrary
}
