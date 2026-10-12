package com.camoron.data.processing

import com.camoron.domain.repository.PhotoProcessor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProcessingModule {
    @Binds
    @Singleton
    abstract fun bindPhotoProcessor(impl: BitmapPhotoProcessor): PhotoProcessor
}
