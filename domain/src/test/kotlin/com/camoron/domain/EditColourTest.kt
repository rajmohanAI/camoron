package com.camoron.domain

import com.camoron.domain.model.Adjustment
import com.camoron.domain.model.EditColour
import com.camoron.domain.model.EditLook
import com.camoron.domain.model.PhotoEdit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditColourTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    private fun run(edit: PhotoEdit, vararg pixels: Int): IntArray {
        val row = pixels.copyOf()
        EditColour.of(edit).process(row, 0, row.size, 1)
        return row
    }

    @Test
    fun `an unchanged edit leaves pixels alone`() {
        val colour = EditColour.of(PhotoEdit())
        assertThat(colour.isIdentity).isTrue()
        val row = intArrayOf(argb(10, 128, 250))
        colour.process(row, 0, 1, 1)
        assertThat(row[0]).isEqualTo(argb(10, 128, 250))
    }

    @Test
    fun `the lookup table reproduces the direct maths closely`() {
        val edit = PhotoEdit(look = EditLook.CINEMA)
            .with(Adjustment.EXPOSURE, 0.3f)
            .with(Adjustment.CONTRAST, 0.4f)
            .with(Adjustment.WARMTH, -0.5f)
            .with(Adjustment.SATURATION, 0.2f)
        val samples = listOf(argb(12, 40, 200), argb(128, 128, 128), argb(240, 180, 90), argb(3, 3, 3))
        val out = run(edit, *samples.toIntArray())
        samples.forEachIndexed { i, c ->
            val rgb = floatArrayOf(red(c) / 255f, green(c) / 255f, blue(c) / 255f)
            EditColour.transform(edit, rgb)
            assertThat(red(out[i]).toFloat()).isWithin(3f).of(rgb[0] * 255)
            assertThat(green(out[i]).toFloat()).isWithin(3f).of(rgb[1] * 255)
            assertThat(blue(out[i]).toFloat()).isWithin(3f).of(rgb[2] * 255)
        }
    }

    @Test
    fun `exposure brightens and darkens without clipping white flat`() {
        val grey = argb(100, 100, 100)
        assertThat(red(run(PhotoEdit().with(Adjustment.EXPOSURE, 0.5f), grey)[0])).isGreaterThan(120)
        assertThat(red(run(PhotoEdit().with(Adjustment.EXPOSURE, -0.5f), grey)[0])).isLessThan(80)
        // Two near-white tones stay distinct after a big exposure boost.
        val bright = run(PhotoEdit().with(Adjustment.EXPOSURE, 1f), argb(200, 200, 200), argb(230, 230, 230))
        assertThat(red(bright[1])).isGreaterThan(red(bright[0]))
    }

    @Test
    fun `warmth moves colour towards orange or blue`() {
        val grey = argb(128, 128, 128)
        val warm = run(PhotoEdit().with(Adjustment.WARMTH, 1f), grey)[0]
        val cool = run(PhotoEdit().with(Adjustment.WARMTH, -1f), grey)[0]
        assertThat(red(warm)).isGreaterThan(blue(warm))
        assertThat(blue(cool)).isGreaterThan(red(cool))
    }

    @Test
    fun `tone curves keep the order of brightness`() {
        for (contrast in listOf(-1f, 0f, 1f)) {
            for (highlights in listOf(-1f, 1f)) {
                for (shadows in listOf(-1f, 1f)) {
                    var last = -1f
                    for (i in 0..100) {
                        val v = EditColour.tone(i / 100f, contrast, highlights, shadows)
                        assertThat(v).isAtLeast(last)
                        last = v
                    }
                }
            }
        }
    }

    @Test
    fun `highlights and shadows act on their own end of the range`() {
        assertThat(EditColour.tone(0.9f, 0f, -1f, 0f)).isLessThan(0.85f)
        assertThat(EditColour.tone(0.1f, 0f, -1f, 0f)).isWithin(0.001f).of(0.1f)
        assertThat(EditColour.tone(0.15f, 0f, 0f, 1f)).isGreaterThan(0.2f)
        assertThat(EditColour.tone(0.95f, 0f, 0f, 1f)).isWithin(0.001f).of(0.95f)
    }

    @Test
    fun `mono looks remove colour`() {
        for (look in listOf(EditLook.MONO, EditLook.NOIR)) {
            val c = run(PhotoEdit(look = look), argb(200, 40, 60))[0]
            assertThat(red(c)).isEqualTo(green(c))
            assertThat(green(c)).isEqualTo(blue(c))
        }
    }

    @Test
    fun `look strength blends between the original and the full look`() {
        val pixel = argb(200, 40, 60)
        val none = run(PhotoEdit(look = EditLook.MONO, lookStrength = 0f), pixel)[0]
        val half = run(PhotoEdit(look = EditLook.MONO, lookStrength = 0.5f), pixel)[0]
        val full = run(PhotoEdit(look = EditLook.MONO, lookStrength = 1f), pixel)[0]
        assertThat(none).isEqualTo(pixel)
        assertThat(red(half)).isLessThan(200)
        assertThat(red(half)).isGreaterThan(red(full))
    }

    @Test
    fun `every look changes a colourful pixel and stays in range`() {
        val pixel = argb(180, 120, 70)
        for (look in EditLook.entries - EditLook.ORIGINAL) {
            assertThat(run(PhotoEdit(look = look), pixel)[0]).isNotEqualTo(pixel)
        }
    }

    @Test
    fun `vignette darkens corners but not the centre`() {
        val row = IntArray(101) { argb(150, 150, 150) }
        EditColour.of(PhotoEdit().with(Adjustment.VIGNETTE, 1f)).process(row, 50, 101, 101)
        assertThat(red(row[50])).isEqualTo(150)
        assertThat(red(row[0])).isLessThan(150)
        val corner = IntArray(101) { argb(150, 150, 150) }
        EditColour.of(PhotoEdit().with(Adjustment.VIGNETTE, 1f)).process(corner, 0, 101, 101)
        assertThat(red(corner[0])).isLessThan(red(row[0]))
    }

    @Test
    fun `grain is repeatable and averages out`() {
        val edit = PhotoEdit().with(Adjustment.GRAIN, 1f)
        val a = IntArray(1000) { argb(128, 128, 128) }
        val b = a.copyOf()
        EditColour.of(edit).process(a, 7, 1000, 100)
        EditColour.of(edit).process(b, 7, 1000, 100)
        assertThat(a).isEqualTo(b)
        assertThat(a.distinct().size).isGreaterThan(5)
        assertThat(a.map { red(it) }.average()).isWithin(3.0).of(128.0)
    }

    @Test
    fun `adjustments are clamped and zero removes them`() {
        val edit = PhotoEdit().with(Adjustment.GRAIN, -1f).with(Adjustment.CONTRAST, 3f)
        assertThat(edit.value(Adjustment.GRAIN)).isEqualTo(0f)
        assertThat(edit.value(Adjustment.CONTRAST)).isEqualTo(1f)
        assertThat(edit.with(Adjustment.CONTRAST, 0f).adjustments).isEmpty()
        assertThat(PhotoEdit().isIdentity).isTrue()
    }
}
