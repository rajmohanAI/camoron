package com.camoron.domain

import com.camoron.domain.model.FaceScene
import com.camoron.domain.model.FacialHair
import com.camoron.domain.model.FaceShape
import com.camoron.domain.model.GlassesStyle
import com.camoron.domain.model.HairColour
import com.camoron.domain.model.HairMask
import com.camoron.domain.model.HairTint
import com.camoron.domain.model.Hairstyle
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

    /** A real face's points (a portrait photo, 880 x 1040), from the face tracker. */
    private val portrait: FaceShape by lazy {
        val text = javaClass.getResource("/face-points.txt")!!.readText()
        FaceShape(text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray())
    }

    private fun strandPoints(style: FacialHair): List<Pair<Float, Float>> =
        TryOnDrawing.shapes(TryOn(facialHair = style), FaceScene(listOf(portrait), null), 880, 1040)
            .filter { !it.closed }
            .flatMap { shape -> shape.contours.flatMap { c -> (c.indices step 2).map { c[it] to c[it + 1] } } }

    @Test
    fun `facial hair needs a face and is drawn hair by hair`() {
        val beard = TryOn(facialHair = FacialHair.FULL_BEARD)
        assertThat(beard.needsFaces).isTrue()
        assertThat(beard.isNone).isFalse()
        assertThat(TryOnDrawing.shapes(beard, FaceScene.EMPTY, 880, 1040)).isEmpty()
        for (style in FacialHair.entries - FacialHair.NONE) {
            assertThat(strandPoints(style)).isNotEmpty()
        }
    }

    @Test
    fun `facial hair is the same every time it is drawn`() {
        val scene = FaceScene(listOf(portrait), null)
        val a = TryOnDrawing.shapes(TryOn(facialHair = FacialHair.GOATEE), scene, 880, 1040)
        val b = TryOnDrawing.shapes(TryOn(facialHair = FacialHair.GOATEE), scene, 880, 1040)
        assertThat(a.size).isEqualTo(b.size)
        a.zip(b).forEach { (x, y) -> x.contours.zip(y.contours).forEach { (p, q) -> assertThat(p.contentEquals(q)).isTrue() } }
    }

    @Test
    fun `beards grow on the lower face and moustaches over the lip`() {
        fun px(i: Int) = portrait.x(i) * 880
        fun py(i: Int) = portrait.y(i) * 1040
        val left = px(234) - 20f
        val right = px(454) + 20f
        val noseTip = py(1)
        for ((x, y) in strandPoints(FacialHair.FULL_BEARD)) {
            assertThat(x).isIn(com.google.common.collect.Range.closed(left, right))
            assertThat(y).isGreaterThan(py(33))
        }
        val lipTop = minOf(py(0), py(37), py(267))
        for ((x, y) in strandPoints(FacialHair.MOUSTACHE)) {
            assertThat(y).isIn(com.google.common.collect.Range.closed(noseTip, py(17)))
            assertThat(x).isIn(com.google.common.collect.Range.closed(px(61) - 60f, px(291) + 60f))
        }
        assertThat(lipTop).isGreaterThan(noseTip)
    }

    @Test
    fun `every hairstyle is drawn round the head, in the chosen colour`() {
        val scene = FaceScene(listOf(portrait), null)
        for (style in Hairstyle.entries - Hairstyle.NONE) {
            val tryOn = TryOn(hairstyle = style, hair = HairColour.BLONDE)
            assertThat(tryOn.needsFaces).isTrue()
            val shapes = TryOnDrawing.shapes(tryOn, scene, 880, 1040)
            assertThat(shapes.count { !it.closed }).isGreaterThan(0)
            // The strands are drawn in shades of the chosen colour.
            val main = shapes.first { !it.closed && it.stroke != null && (it.stroke!! and 0xFFFFFF) == (HairColour.BLONDE.argb and 0xFFFFFF) }
            assertThat(main.contours).isNotEmpty()
        }
        // The same every time.
        val a = TryOnDrawing.shapes(TryOn(hairstyle = Hairstyle.BOB), scene, 880, 1040, ownHair = 0xFF553311.toInt())
        val b = TryOnDrawing.shapes(TryOn(hairstyle = Hairstyle.BOB), scene, 880, 1040, ownHair = 0xFF553311.toInt())
        assertThat(a.flatMap { it.contours }.zip(b.flatMap { it.contours }).all { (p, q) -> p.contentEquals(q) }).isTrue()
    }

    @Test
    fun `new hairstyles paint out the old hair but keep the face`() {
        val w = 220
        val h = 260
        val allHair = HairMask(4, 4, ByteArray(16) { 255.toByte() })
        val withHair = FaceScene(listOf(portrait), allHair)
        assertThat(TryOnDrawing.hairToRemove(TryOn(hairstyle = Hairstyle.PIXIE), FaceScene(listOf(portrait), null), w, h)).isNull()
        assertThat(TryOnDrawing.hairToRemove(TryOn(hairstyle = Hairstyle.BANGS), withHair, w, h)).isNull()
        assertThat(TryOnDrawing.hairToRemove(TryOn(), withHair, w, h)).isNull()
        val hole = TryOnDrawing.hairToRemove(TryOn(hairstyle = Hairstyle.PIXIE), withHair, w, h)!!
        fun at(i: Int) = hole[(portrait.y(i) * h).toInt() * w + (portrait.x(i) * w).toInt()]
        assertThat(at(1)).isFalse() // nose tip
        assertThat(at(13)).isFalse() // mouth
        assertThat(hole[(h - 1) * w]).isTrue() // hair down at the shoulder, gone with a pixie cut
    }
}
