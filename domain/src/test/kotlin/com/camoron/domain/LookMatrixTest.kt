package com.camoron.domain

import com.camoron.domain.model.LookMatrix
import com.camoron.domain.model.ShotLook
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LookMatrixTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun Int.r() = (this shr 16) and 0xFF
    private fun Int.g() = (this shr 8) and 0xFF
    private fun Int.b() = this and 0xFF

    @Test
    fun `normal leaves pixels untouched`() {
        val m = LookMatrix.forLook(ShotLook.NORMAL)
        assertThat(LookMatrix.isIdentity(m)).isTrue()
        assertThat(LookMatrix.apply(m, rgb(12, 200, 99))).isEqualTo(rgb(12, 200, 99))
    }

    @Test
    fun `black and white makes channels equal`() {
        val out = LookMatrix.apply(LookMatrix.forLook(ShotLook.BLACK_AND_WHITE), rgb(200, 60, 30))
        assertThat(out.r()).isEqualTo(out.g())
        assertThat(out.g()).isEqualTo(out.b())
    }

    @Test
    fun `warm and cool shift red against blue`() {
        val grey = rgb(128, 128, 128)
        val warm = LookMatrix.apply(LookMatrix.forLook(ShotLook.WARM), grey)
        val cool = LookMatrix.apply(LookMatrix.forLook(ShotLook.COOL), grey)
        assertThat(warm.r()).isGreaterThan(warm.b())
        assertThat(cool.b()).isGreaterThan(cool.r())
    }

    @Test
    fun `vivid pushes colours apart and keeps alpha`() {
        val input = rgb(180, 120, 90)
        val out = LookMatrix.apply(LookMatrix.forLook(ShotLook.VIVID), input)
        assertThat(out.r() - out.b()).isGreaterThan(180 - 90)
        assertThat(out ushr 24).isEqualTo(0xFF)
    }

    @Test
    fun `values are clamped`() {
        val out = LookMatrix.apply(LookMatrix.forLook(ShotLook.VIVID), rgb(255, 0, 0))
        assertThat(out.r()).isEqualTo(255)
        assertThat(out.g()).isEqualTo(0)
    }

    @Test
    fun `only normal and black and white are basic`() {
        assertThat(ShotLook.entries.filter { it.isBasic }).containsExactly(ShotLook.NORMAL, ShotLook.BLACK_AND_WHITE)
    }
}
