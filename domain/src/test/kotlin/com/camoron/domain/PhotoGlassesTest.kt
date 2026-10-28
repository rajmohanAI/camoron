package com.camoron.domain

import com.camoron.domain.model.FaceScene
import com.camoron.domain.model.FaceShape
import com.camoron.domain.model.FrameColour
import com.camoron.domain.model.GlassesStyle
import com.camoron.domain.model.LensTint
import com.camoron.domain.model.PhotoGlasses
import com.camoron.domain.model.PhotoPixels
import com.camoron.domain.model.TryOn
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhotoGlassesTest {

    private class Pixels(override val width: Int, override val height: Int, colour: Int) : PhotoPixels {
        val argb = IntArray(width * height) { colour }
        override fun read(x0: Int, y0: Int, w: Int, h: Int) = IntArray(w * h) { i -> argb[(y0 + i / w) * width + x0 + i % w] }
        override fun write(pixels: IntArray, x0: Int, y0: Int, w: Int, h: Int) {
            for (i in pixels.indices) argb[(y0 + i / w) * width + x0 + i % w] = pixels[i]
        }
        fun grey(x: Int, y: Int) = (argb[y * width + x] shr 8) and 0xFF
    }

    /** Eyes 80 pixels apart on a 400 x 400 photo: one eye unit is 40 pixels, the right pupil at (240, 160). */
    private fun scene(): FaceScene {
        val p = FloatArray(478 * 2) { 0.5f }
        fun set(i: Int, x: Float, y: Float) {
            p[2 * i] = x
            p[2 * i + 1] = y
        }
        set(33, 0.35f, 0.4f)
        set(133, 0.45f, 0.4f)
        set(362, 0.55f, 0.4f)
        set(263, 0.65f, 0.4f)
        set(127, 0.25f, 0.4f)
        set(356, 0.75f, 0.4f)
        return FaceScene(listOf(FaceShape(p)), null)
    }

    private fun render(tryOn: TryOn) = Pixels(400, 400, GREY).also { PhotoGlasses.render(it, tryOn, scene()) }

    @Test
    fun `nothing changes without glasses or a face`() {
        assertThat(render(TryOn()).argb.all { it == GREY }).isTrue()
        val empty = Pixels(400, 400, GREY).also { PhotoGlasses.render(it, TryOn(glasses = GlassesStyle.ROUND), FaceScene.EMPTY) }
        assertThat(empty.argb.all { it == GREY }).isTrue()
    }

    @Test
    fun `sunglasses darken the eyes and clear lenses barely do`() {
        // Just below the pupil, clear of the reflection across the top of the lens.
        assertThat(render(TryOn(glasses = GlassesStyle.ROUND, lens = LensTint.GREY)).grey(240, 175)).isLessThan(0x50)
        assertThat(render(TryOn(glasses = GlassesStyle.ROUND, lens = LensTint.CLEAR)).grey(240, 175)).isAtLeast(0x70)
    }

    @Test
    fun `the rim is drawn round the lens in the frame colour`() {
        // The right lens's outer rim, 1.75 eye units right of the middle of the eyes.
        val black = render(TryOn(glasses = GlassesStyle.ROUND, frame = FrameColour.BLACK))
        val gold = render(TryOn(glasses = GlassesStyle.ROUND, frame = FrameColour.GOLD))
        assertThat(black.grey(270, 163)).isLessThan(0x40)
        val g = gold.argb[163 * 400 + 270]
        assertThat((g shr 16) and 0xFF).isGreaterThan(g and 0xFF)
    }

    @Test
    fun `pixels far from the glasses are left alone`() {
        val photo = render(TryOn(glasses = GlassesStyle.BOLD, lens = LensTint.GREY))
        assertThat(photo.argb[390 * 400 + 10]).isEqualTo(GREY)
        assertThat(photo.argb[10 * 400 + 200]).isEqualTo(GREY)
    }

    @Test
    fun `the same choice renders the same pixels`() {
        val tryOn = TryOn(glasses = GlassesStyle.RECTANGLE, frame = FrameColour.TORTOISE)
        assertThat(render(tryOn).argb).isEqualTo(render(tryOn).argb)
    }

    private companion object {
        const val GREY = 0xFF808080.toInt()
    }
}
