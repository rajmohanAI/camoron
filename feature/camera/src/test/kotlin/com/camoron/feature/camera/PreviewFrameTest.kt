package com.camoron.feature.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewFrameTest {

    @Test
    fun `3 by 4 photos fill the width below the top bar`() {
        val frame = PreviewFrame.photo(4f / 3, 400f, 900f, topInset = 90f, bottomInset = 20f)
        assertThat(frame.top).isEqualTo(90f)
        assertThat(frame.width).isEqualTo(400f)
        assertThat(frame.height).isWithin(0.01f).of(533.33f)
    }

    @Test
    fun `taller photo shapes keep the scene's scale and become narrower`() {
        val base = PreviewFrame.photo(4f / 3, 400f, 900f, topInset = 90f, bottomInset = 20f)
        val tall = PreviewFrame.photo(16f / 9, 400f, 900f, topInset = 90f, bottomInset = 20f)
        assertThat(tall.top).isEqualTo(base.top)
        assertThat(tall.height).isEqualTo(base.height)
        assertThat(tall.width).isWithin(0.01f).of(300f)
    }

    @Test
    fun `full screen is the screen's shape at the same scale`() {
        val base = PreviewFrame.photo(4f / 3, 400f, 900f, topInset = 90f, bottomInset = 20f)
        val full = PreviewFrame.photo(900f / 400, 400f, 900f, topInset = 90f, bottomInset = 20f)
        assertThat(full.height).isEqualTo(base.height)
        assertThat(full.height / full.width).isWithin(0.001f).of(900f / 400)
    }

    @Test
    fun `on a short screen 3 by 4 still fits`() {
        val frame = PreviewFrame.photo(4f / 3, 400f, 560f, topInset = 90f, bottomInset = 20f)
        assertThat(frame.top + frame.height).isAtMost(560f)
        assertThat(frame.top).isAtLeast(0f)
    }

    @Test
    fun `video fills the width with its own shape`() {
        val frame = PreviewFrame.video(16f / 9, 400f, 900f, topInset = 90f, bottomInset = 20f)
        assertThat(frame.width).isEqualTo(400f)
        assertThat(frame.height).isWithin(0.01f).of(711.11f)
    }

    @Test
    fun `a short screen narrows the frame but keeps its shape`() {
        val frame = PreviewFrame.video(16f / 9, 600f, 800f, topInset = 80f, bottomInset = 0f)
        assertThat(frame.height).isWithin(0.01f).of(720f)
        assertThat(frame.height / frame.width).isWithin(0.001f).of(16f / 9)
    }
}
