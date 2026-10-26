package com.camoron.domain

import com.camoron.domain.model.FaceScene
import com.camoron.domain.model.FaceShape
import com.camoron.domain.model.GlassesStyle
import com.camoron.domain.model.HairColour
import com.camoron.domain.model.HairMask
import com.camoron.domain.model.HairTint
import com.camoron.domain.model.MaskStyle
import com.camoron.domain.model.PhotoEdit
import com.camoron.domain.model.TryOn
import com.camoron.domain.model.TryOnDrawing
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TryOnTest {

    /** A face whose points all sit at the centre, except the eye corners and temples. */
    private fun face(eyeY: Float = 0.4f, spread: Float = 0.1f): FaceShape {
        val p = FloatArray(478 * 2) { 0.5f }
        fun set(i: Int, x: Float, y: Float) {
            p[2 * i] = x
            p[2 * i + 1] = y
        }
        set(33, 0.5f - 1.5f * spread, eyeY)
        set(133, 0.5f - 0.5f * spread, eyeY)
        set(362, 0.5f + 0.5f * spread, eyeY)
        set(263, 0.5f + 1.5f * spread, eyeY)
        set(127, 0.5f - 2.5f * spread, eyeY)
        set(356, 0.5f + 2.5f * spread, eyeY)
        return FaceShape(p)
    }

    private fun bounds(contour: FloatArray): FloatArray {
        val xs = contour.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = contour.filterIndexed { i, _ -> i % 2 == 1 }
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    @Test
    fun `nothing is drawn without a face or a choice`() {
        assertThat(TryOnDrawing.shapes(TryOn(glasses = GlassesStyle.ROUND), FaceScene.EMPTY, 1000, 1000)).isEmpty()
        assertThat(TryOnDrawing.shapes(TryOn(), FaceScene(listOf(face()), null), 1000, 1000)).isEmpty()
        assertThat(TryOn().isNone).isTrue()
        assertThat(PhotoEdit(tryOn = TryOn(mask = MaskStyle.N95)).isIdentity).isFalse()
    }

    @Test
    fun `lenses sit over the eyes and grow with the face`() {
        val small = TryOnDrawing.shapes(TryOn(glasses = GlassesStyle.ROUND), FaceScene(listOf(face(spread = 0.05f)), null), 1000, 1000)
        val large = TryOnDrawing.shapes(TryOn(glasses = GlassesStyle.ROUND), FaceScene(listOf(face(spread = 0.1f)), null), 1000, 1000)
        // The rims are the closed, stroked shape with two outlines.
        val rims = large.first { it.closed && it.stroke != null && it.contours.size == 2 }
        val left = bounds(rims.contours[0])
        val right = bounds(rims.contours[1])
        // Left lens centred on the left eye (x = 0.4 of 1000 px), right on the right eye (0.6).
        assertThat((left[0] + left[2]) / 2).isWithin(5f).of(400f)
        assertThat((right[0] + right[2]) / 2).isWithin(5f).of(600f)
        assertThat((left[1] + left[3]) / 2).isGreaterThan(400f)
        val smallRims = small.first { it.closed && it.stroke != null && it.contours.size == 2 }
        val smallWidth = bounds(smallRims.contours[0]).let { it[2] - it[0] }
        assertThat(left[2] - left[0]).isWithin(1f).of(2 * smallWidth)
    }

    @Test
    fun `every frame shape is drawn`() {
        for (style in GlassesStyle.entries - GlassesStyle.NONE) {
            val shapes = TryOnDrawing.shapes(TryOn(glasses = style), FaceScene(listOf(face()), null), 800, 800)
            assertThat(shapes).isNotEmpty()
            shapes.forEach { s -> s.contours.forEach { c -> c.forEach { assertThat(it.isFinite()).isTrue() } } }
        }
    }

    @Test
    fun `eye masks cut holes for the eyes`() {
        val shapes = TryOnDrawing.shapes(TryOn(mask = MaskStyle.HERO), FaceScene(listOf(face()), null), 800, 800)
        assertThat(shapes.any { it.contours.size == 3 && it.gradient != null }).isTrue()
    }

    @Test
    fun `each face in the photo gets its own glasses`() {
        val two = FaceScene(listOf(face(eyeY = 0.3f), face(eyeY = 0.7f)), null)
        val one = FaceScene(listOf(face()), null)
        val countTwo = TryOnDrawing.shapes(TryOn(glasses = GlassesStyle.AVIATOR), two, 800, 800).size
        val countOne = TryOnDrawing.shapes(TryOn(glasses = GlassesStyle.AVIATOR), one, 800, 800).size
        assertThat(countTwo).isEqualTo(2 * countOne)
    }

    @Test
    fun `hair tint keeps light and shade`() {
        val dark = 0xFF2A1E14.toInt()
        val light = 0xFF8A6A50.toInt()
        assertThat(HairTint.apply(dark, HairColour.BLONDE.argb, 0f)).isEqualTo(dark)
        val darkBlonde = HairTint.apply(dark, HairColour.BLONDE.argb, 1f)
        val lightBlonde = HairTint.apply(light, HairColour.BLONDE.argb, 1f)
        // Dark hair goes noticeably lighter; lighter strands stay lighter than darker ones.
        assertThat((darkBlonde shr 16) and 0xFF).isGreaterThan(0x60)
        assertThat((lightBlonde shr 16) and 0xFF).isGreaterThan((darkBlonde shr 16) and 0xFF)
        val black = HairTint.apply(light, HairColour.BLACK.argb, 1f)
        assertThat((black shr 8) and 0xFF).isLessThan(0x30)
    }

    @Test
    fun `uncertain hair edges are left alone`() {
        assertThat(HairTint.weight(0.2f)).isEqualTo(0f)
        assertThat(HairTint.weight(0.95f)).isEqualTo(1f)
        assertThat(HairTint.weight(0.6f)).isWithin(0.2f).of(0.5f)
    }

    @Test
    fun `hair mask reads smoothly between pixels`() {
        val mask = HairMask(2, 1, byteArrayOf(0, 255.toByte()))
        assertThat(mask.at(0.25f, 0.5f)).isWithin(0.01f).of(0f)
        assertThat(mask.at(0.5f, 0.5f)).isWithin(0.01f).of(0.5f)
        assertThat(mask.at(0.75f, 0.5f)).isWithin(0.01f).of(1f)
    }
}
