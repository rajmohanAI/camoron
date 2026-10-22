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
    fun `taller photo shapes keep the picture's size and narrow the frame instead`() {
        val photo = PreviewFrame.photo(4f / 3, 400f, 900f, topInset = 90f, bottomInset = 20f)
        val tall = PreviewFrame.photo(16f / 9, 400f, 900f, topInset = 90f, bottomInset = 20f)
        val full = PreviewFrame.photo(900f / 400, 400f, 900f, topInset = 90f, bottomInset = 20f)
        assertThat(tall.top).isEqualTo(photo.top)
        assertThat(tall.height).isEqualTo(photo.height)
        assertThat(tall.width).isWithin(0.01f).of(300f)
        assertThat(full.height).isEqualTo(photo.height)
        assertThat(full.height / full.width).isWithin(0.001f).of(900f / 400)
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
