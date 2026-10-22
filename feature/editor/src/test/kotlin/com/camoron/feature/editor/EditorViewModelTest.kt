package com.camoron.feature.editor

import com.camoron.domain.model.Adjustment
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.EditLook
import com.camoron.domain.model.PhotoEdit
import com.camoron.domain.repository.PhotoEditor
import com.camoron.domain.repository.PhotoLibrary
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorViewModelTest {

    private val library = object : PhotoLibrary {
        override suspend fun recentPhotos(limit: Int): List<String> = emptyList()
        override suspend fun read(uri: String): CapturedImage = error("Not in this test")
        override suspend fun saveEdited(image: CapturedImage): String = "content://edited"
    }
    private val editor = object : PhotoEditor {
        override suspend fun render(photo: CapturedImage, edit: PhotoEdit): CapturedImage = photo
    }
    private val viewModel = EditorViewModel(library, editor)

    @Test
    fun `a slider drag is one undo step`() {
        viewModel.setAdjustment(Adjustment.EXPOSURE, 0.1f)
        viewModel.setAdjustment(Adjustment.EXPOSURE, 0.2f)
        viewModel.setAdjustment(Adjustment.EXPOSURE, 0.3f)
        viewModel.commit()
        assertThat(viewModel.state.value.canUndo).isTrue()
        viewModel.undo()
        assertThat(viewModel.state.value.edit.value(Adjustment.EXPOSURE)).isEqualTo(0f)
        assertThat(viewModel.state.value.canUndo).isFalse()
        viewModel.redo()
        assertThat(viewModel.state.value.edit.value(Adjustment.EXPOSURE)).isEqualTo(0.3f)
    }

    @Test
    fun `a new change after undo drops the redo steps`() {
        viewModel.selectLook(EditLook.FILM)
        viewModel.selectLook(EditLook.NOIR)
        viewModel.undo()
        assertThat(viewModel.state.value.edit.look).isEqualTo(EditLook.FILM)
        viewModel.setAdjustment(Adjustment.GRAIN, 0.5f)
        viewModel.commit()
        assertThat(viewModel.state.value.canRedo).isFalse()
        assertThat(viewModel.state.value.hasChanges).isTrue()
    }

    @Test
    fun `resetting an adjustment removes it`() {
        viewModel.setAdjustment(Adjustment.CONTRAST, 0.4f)
        viewModel.commit()
        viewModel.resetAdjustment(Adjustment.CONTRAST)
        assertThat(viewModel.state.value.edit.adjustments).isEmpty()
        assertThat(viewModel.state.value.hasChanges).isFalse()
    }
}
