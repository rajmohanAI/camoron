package com.camoron.feature.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewFrameTest {

    @Test
    fun `photo shapes fill the width below the top bar`() {
        val photo = PreviewFrame.of(4f / 3, 400f, 900f, topInset = 90f, bottomInset = 20f)
        assertThat(photo.top).isEqualTo(90f)
        assertThat(photo.width).isEqualTo(400f)
        assertThat(photo.height).isWithin(0.01f).of(533.33f)
        val tall = PreviewFrame.of(16f / 9, 400f, 900f, topInset = 90f, bottomInset = 20f)
        assertThat(tall.width).isEqualTo(400f)
        assertThat(tall.height).isWithin(0.01f).of(711.11f)
    }

    @Test
    fun `full screen is the whole screen`() {
        assertThat(PreviewFrame.of(null, 400f, 900f, topInset = 90f, bottomInset = 20f)).isEqualTo(PreviewFrame(0f, 400f, 900f))
    }

    @Test
    fun `a short screen narrows the frame but keeps its shape`() {
        val frame = PreviewFrame.of(16f / 9, 600f, 800f, topInset = 80f, bottomInset = 0f)
        assertThat(frame.height).isWithin(0.01f).of(720f)
        assertThat(frame.height / frame.width).isWithin(0.001f).of(16f / 9)
    }
}
