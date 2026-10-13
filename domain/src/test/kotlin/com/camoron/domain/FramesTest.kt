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

    @Test
    fun `crop keeps the chosen pixels and the colour lined up`() {
        val w = 8
        val h = 6
        val frame = YuvFrame(
            w, h,
            ByteArray(w * h) { it.toByte() },
            ByteArray(w * h / 4) { (100 + it).toByte() },
            ByteArray(w * h / 4) { (200 + it).toByte() },
        )
        // Odd edges round down to even ones.
        val crop = frame.crop(3, 1, 4, 4)
        assertThat(crop.width).isEqualTo(4)
        assertThat(crop.height).isEqualTo(4)
        assertThat(crop.y[0].toInt()).isEqualTo(0 * w + 2)
        assertThat(crop.y[crop.width + 1].toInt()).isEqualTo(1 * w + 3)
        // Colour pixel (1, 0) of the source.
        assertThat(crop.u[0].toInt() and 0xFF).isEqualTo(101)
        assertThat(crop.v[1].toInt() and 0xFF).isEqualTo(202)
        assertThat(frame.crop(0, 0, w, h)).isSameInstanceAs(frame)
    }

    @Test
    fun `shrink averages each block`() {
        val w = 12
        val h = 6
        // Columns alternate 0 and 90, so every 3x3 block averages to 30 or 60.
        val frame = YuvFrame(
            w, h,
            ByteArray(w * h) { if ((it % w) % 2 == 0) 0 else 90.toByte() },
            ByteArray(w * h / 4) { 128.toByte() },
            ByteArray(w * h / 4) { 77 },
        )
        val small = frame.shrink(3)
        assertThat(small.width).isEqualTo(4)
        assertThat(small.height).isEqualTo(2)
        assertThat(small.y.map { it.toInt() and 0xFF }).containsExactly(30, 60, 30, 60, 30, 60, 30, 60).inOrder()
        assertThat(small.u.distinct().single().toInt() and 0xFF).isEqualTo(128)
        assertThat(small.v.distinct().single().toInt()).isEqualTo(77)
        assertThat(frame.shrink(1)).isSameInstanceAs(frame)
    }
}
