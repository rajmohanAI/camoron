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
    fun `taller photo shapes fill the width and grow taller`() {
        val tall = PreviewFrame.photo(16f / 9, 400f, 900f, topInset = 90f, bottomInset = 20f)
        assertThat(tall.top).isEqualTo(90f)
        assertThat(tall.width).isEqualTo(400f)
        assertThat(tall.height).isWithin(0.01f).of(711.11f)
    }

    @Test
    fun `full screen covers the whole screen`() {
        val full = PreviewFrame.photo(900f / 400, 400f, 900f, topInset = 90f, bottomInset = 20f)
        assertThat(full.top).isEqualTo(0f)
        assertThat(full.width).isEqualTo(400f)
        assertThat(full.height).isWithin(0.01f).of(900f)
    }

    @Test
    fun `a shape too tall for the space under the top bar runs behind the controls`() {
        val tall = PreviewFrame.photo(16f / 9, 400f, 760f, topInset = 90f, bottomInset = 20f)
        assertThat(tall.width).isEqualTo(400f)
        assertThat(tall.top + tall.height).isAtMost(760f)
        assertThat(tall.top).isAtLeast(0f)
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
