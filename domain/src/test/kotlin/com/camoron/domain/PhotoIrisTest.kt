package com.camoron.domain

import com.camoron.domain.model.EyeColour
import com.camoron.domain.model.FaceScene
import com.camoron.domain.model.FaceShape
import com.camoron.domain.model.PhotoIris
import com.camoron.domain.model.PhotoPixels
import com.camoron.domain.model.TryOn
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhotoIrisTest {

    private class Pixels(override val width: Int, override val height: Int, colour: Int) : PhotoPixels {
        val argb = IntArray(width * height) { colour }
        override fun read(x0: Int, y0: Int, w: Int, h: Int) = IntArray(w * h) { i -> argb[(y0 + i / w) * width + x0 + i % w] }
        override fun write(pixels: IntArray, x0: Int, y0: Int, w: Int, h: Int) {
            for (i in pixels.indices) argb[(y0 + i / w) * width + x0 + i % w] = pixels[i]
        }
    }

    /** A real face's points (a portrait photo, 880 x 1040), from the face tracker, with the irises. */
    private val portrait: FaceShape by lazy {
        val text = javaClass.getResource("/face-points.txt")!!.readText()
        FaceShape(text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray())
    }

    private fun render(colour: EyeColour) = Pixels(WIDTH, HEIGHT, BROWN).also {
        PhotoIris.render(it, TryOn(eyes = colour), FaceScene(listOf(portrait), null))
    }

    /** The colour [across] iris radii right of the photo's left iris centre (about 16 pixels across). */
    private fun Pixels.iris(across: Float): Int {
        val x = (portrait.x(468) * WIDTH + across * 16f).toInt()
        val y = (portrait.y(468) * HEIGHT).toInt()
        return argb[y * WIDTH + x]
    }

    @Test
    fun `nothing changes without an eye colour or a face`() {
        assertThat(render(EyeColour.NONE).argb.all { it == BROWN }).isTrue()
        val empty = Pixels(WIDTH, HEIGHT, BROWN).also { PhotoIris.render(it, TryOn(eyes = EyeColour.BLUE), FaceScene.EMPTY) }
        assertThat(empty.argb.all { it == BROWN }).isTrue()
    }

    @Test
    fun `the iris takes the new colour but the pupil and the skin round the eye stay`() {
        val photo = render(EyeColour.BLUE)
        val iris = photo.iris(0.6f)
        assertThat(iris and 0xFF).isGreaterThan((iris shr 16) and 0xFF)
        assertThat(photo.iris(0f)).isEqualTo(BROWN)
        assertThat(photo.iris(4f)).isEqualTo(BROWN)
    }

    private companion object {
        const val WIDTH = 880
        const val HEIGHT = 1040
        const val BROWN = 0xFF5A3A28.toInt()
    }
}
