package com.camoron.domain

import com.camoron.domain.model.FaceScene
import com.camoron.domain.model.FaceShape
import com.camoron.domain.model.MaskStyle
import com.camoron.domain.model.PhotoMasks
import com.camoron.domain.model.PhotoPixels
import com.camoron.domain.model.TryOn
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhotoMasksTest {

    private class Pixels(override val width: Int, override val height: Int, colour: Int) : PhotoPixels {
        val argb = IntArray(width * height) { colour }
        override fun read(x0: Int, y0: Int, w: Int, h: Int) = IntArray(w * h) { i -> argb[(y0 + i / w) * width + x0 + i % w] }
        override fun write(pixels: IntArray, x0: Int, y0: Int, w: Int, h: Int) {
            for (i in pixels.indices) argb[(y0 + i / w) * width + x0 + i % w] = pixels[i]
        }
    }

    /** A real face's points (a portrait photo, 880 x 1040), from the face tracker. */
    private val portrait: FaceShape by lazy {
        val text = javaClass.getResource("/face-points.txt")!!.readText()
        FaceShape(text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray())
    }

    private fun render(style: MaskStyle) = Pixels(WIDTH, HEIGHT, SKIN).also {
        PhotoMasks.render(it, TryOn(mask = style), FaceScene(listOf(portrait), null))
    }

    /** The colour at face point [index], or halfway between two points. */
    private fun Pixels.at(index: Int, other: Int = index): Int {
        val x = ((portrait.x(index) + portrait.x(other)) / 2 * WIDTH).toInt()
        val y = ((portrait.y(index) + portrait.y(other)) / 2 * HEIGHT).toInt()
        return argb[y * WIDTH + x]
    }

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun `nothing changes without a mask or a face`() {
        assertThat(render(MaskStyle.NONE).argb.all { it == SKIN }).isTrue()
        val empty = Pixels(WIDTH, HEIGHT, SKIN).also { PhotoMasks.render(it, TryOn(mask = MaskStyle.N95), FaceScene.EMPTY) }
        assertThat(empty.argb.all { it == SKIN }).isTrue()
    }

    @Test
    fun `face masks cover the mouth in their own colour`() {
        val surgical = render(MaskStyle.SURGICAL).at(MOUTH)
        assertThat(blue(surgical)).isGreaterThan(red(surgical))
        val n95 = render(MaskStyle.N95).at(MOUTH)
        assertThat(green(n95)).isGreaterThan(0x90)
        assertThat(green(render(MaskStyle.CLOTH).at(MOUTH))).isLessThan(0x40)
    }

    @Test
    fun `face masks leave the eyes and forehead alone`() {
        val photo = render(MaskStyle.SURGICAL)
        assertThat(photo.at(FOREHEAD)).isEqualTo(SKIN)
        assertThat(photo.at(33, 133)).isEqualTo(SKIN)
    }

    @Test
    fun `eye masks cover the bridge of the nose but not the eyes`() {
        val hero = render(MaskStyle.HERO)
        assertThat(green(hero.at(NOSE_BRIDGE))).isLessThan(0x40)
        val gold = render(MaskStyle.MASQUERADE).at(NOSE_BRIDGE)
        assertThat(red(gold)).isGreaterThan(blue(gold))
        // Through the eye hole the skin shows, at most a little shaded.
        assertThat(green(hero.at(33, 133))).isAtLeast(green(SKIN) - 0x30)
        assertThat(hero.at(MOUTH)).isEqualTo(SKIN)
    }

    @Test
    fun `the same mask renders the same pixels`() {
        assertThat(render(MaskStyle.SURGICAL).argb).isEqualTo(render(MaskStyle.SURGICAL).argb)
    }

    private companion object {
        const val WIDTH = 880
        const val HEIGHT = 1040
        const val SKIN = 0xFFC8A088.toInt()
        const val MOUTH = 13
        const val FOREHEAD = 10
        const val NOSE_BRIDGE = 6
    }
}
