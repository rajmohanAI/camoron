package com.camoron.feature.editor

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
internal abstract class EditorModule {
    /** One scanner per editor, so its models are released when the editor closes. */
    @Binds
    abstract fun bindSceneScanner(impl: FaceScanner): SceneScanner

    @Binds
    abstract fun bindObjectPicker(impl: MagicTouchPicker): ObjectPicker
}
