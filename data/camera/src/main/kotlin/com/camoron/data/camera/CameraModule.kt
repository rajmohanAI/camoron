package com.camoron.data.camera

import com.camoron.domain.repository.CameraController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CameraModule {
    @Binds
    abstract fun bindCameraController(impl: Camera2Controller): CameraController
}
