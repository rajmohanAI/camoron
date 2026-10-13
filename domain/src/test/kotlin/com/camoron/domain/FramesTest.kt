package com.camoron.domain

import com.camoron.domain.model.YuvFrame
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FramesTest {

    @Test
    fun `thumbnail is small, keeps the shape and converts grey exactly`() {
        val w = 4000
        val h = 3000
        val frame = YuvFrame(w, h, ByteArray(w * h) { 128.toByte() }, ByteArray(w * h / 4) { 128.toByte() }, ByteArray(w * h / 4) { 128.toByte() })
        val thumb = frame.thumbnail(256)
        assertThat(maxOf(thumb.width, thumb.height)).isAtMost(256)
        assertThat(thumb.width.toFloat() / thumb.height).isWithin(0.05f).of(4f / 3)
        assertThat(thumb.pixels.distinct()).containsExactly(0xFF808080.toInt())
    }

    @Test
    fun `frames reject planes of the wrong size`() {
        val result = runCatching { YuvFrame(4, 4, ByteArray(15), ByteArray(4), ByteArray(4)) }
        assertThat(result.isFailure).isTrue()
    }
}
