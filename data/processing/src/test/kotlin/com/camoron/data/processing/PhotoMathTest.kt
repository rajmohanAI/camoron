package com.camoron.data.processing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhotoMathTest {

    @Test
    fun `no crop when the image already has the target shape`() {
        assertThat(PhotoMath.centreCrop(4000, 2250, 16f / 9)).isNull()
    }

    @Test
    fun `4 by 3 to 16 by 9 trims the short edge`() {
        val crop = PhotoMath.centreCrop(4000, 3000, 16f / 9)!!
        assertThat(crop.width).isEqualTo(4000)
        assertThat(crop.height).isEqualTo(2250)
        assertThat(crop.top).isEqualTo(375)
    }

    @Test
    fun `16 by 9 to 20 by 9 trims the short edge of a portrait-stored image`() {
        val crop = PhotoMath.centreCrop(2250, 4000, 20f / 9)!!
        assertThat(crop.height).isEqualTo(4000)
        assertThat(crop.width).isEqualTo(1800)
        assertThat(crop.left).isEqualTo(225)
    }

    @Test
    fun `wider target trims the long edge`() {
        val crop = PhotoMath.centreCrop(4000, 2250, 4f / 3)!!
        assertThat(crop.width).isEqualTo(3000)
        assertThat(crop.height).isEqualTo(2250)
        assertThat(crop.left).isEqualTo(500)
    }

    @Test
    fun `mirroring is its own inverse and keeps the rotation`() {
        (1..8).forEach { o -> assertThat(PhotoMath.mirroredOrientation(PhotoMath.mirroredOrientation(o))).isEqualTo(o) }
        assertThat(PhotoMath.mirroredOrientation(6)).isEqualTo(5) // rotate 90 -> transpose
        assertThat(PhotoMath.mirroredOrientation(8)).isEqualTo(7) // rotate 270 -> transverse
        assertThat(PhotoMath.mirroredOrientation(3)).isEqualTo(4) // rotate 180 -> flip vertical
        assertThat(PhotoMath.mirroredOrientation(0)).isEqualTo(2) // undefined behaves as normal
    }
}
