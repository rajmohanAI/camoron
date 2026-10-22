package com.camoron.domain

import com.camoron.domain.model.EraseMask
import com.camoron.domain.model.Inpaint
import com.camoron.domain.model.PhotoEdit
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class InpaintTest {

    private fun picture(width: Int, height: Int, colour: (Int, Int) -> Int) =
        IntArray(width * height) { (0xFF shl 24) or colour(it % width, it / width) }

    private fun hole(width: Int, height: Int, inside: (Int, Int) -> Boolean) =
        BooleanArray(width * height) { inside(it % width, it / width) }

    private fun channel(argb: Int, shift: Int) = (argb shr shift) and 0xFF

    private fun near(a: Int, b: Int, tolerance: Int) =
        listOf(16, 8, 0).all { abs(channel(a, it) - channel(b, it)) <= tolerance }

    @Test
    fun `no hole leaves the picture as it is`() {
        val pixels = picture(40, 30) { x, y -> x * 6 shl 8 or y * 8 }
        assertThat(Inpaint.fill(pixels, 40, 30, BooleanArray(40 * 30))).isEqualTo(pixels)
    }

    @Test
    fun `only the hole changes and alpha is kept`() {
        val w = 80
        val h = 60
        val pixels = picture(w, h) { x, y -> ((x * 3) shl 16) or ((y * 4) shl 8) or 0x40 }
        pixels[0] = 0x80123456.toInt()
        val hole = hole(w, h) { x, y -> x in 30..45 && y in 20..35 }
        val out = Inpaint.fill(pixels, w, h, hole)
        for (i in pixels.indices) {
            if (!hole[i]) assertThat(out[i]).isEqualTo(pixels[i])
            assertThat(out[i] ushr 24).isEqualTo(pixels[i] ushr 24)
        }
    }

    @Test
    fun `a plain background is rebuilt exactly`() {
        val w = 120
        val h = 90
        val pixels = picture(w, h) { x, y -> if (x in 50..70 && y in 30..55) 0xD02020 else 0x3070B0 }
        val hole = hole(w, h) { x, y -> x in 48..72 && y in 28..57 }
        val out = Inpaint.fill(pixels, w, h, hole)
        for (i in out.indices) if (hole[i]) assertThat(near(out[i], 0xFF3070B0.toInt(), 1)).isTrue()
    }

    @Test
    fun `an edge across the hole carries on across it`() {
        // Sky over sand, with a post standing across the horizon.
        val w = 160
        val h = 120
        val sky = 0x6090E0
        val sand = 0xD8C090
        val pixels = picture(w, h) { x, y -> if (x in 70..85 && y in 20..100) 0x202020 else if (y < 60) sky else sand }
        val hole = hole(w, h) { x, y -> x in 66..89 && y in 16..104 }
        val out = Inpaint.fill(pixels, w, h, hole)
        for (y in 16..104) {
            for (x in 66..89) {
                val expected = if (y < 60) sky else sand
                // Allow the horizon itself a couple of rows of blending.
                if (abs(y - 60) > 3) assertThat(near(out[y * w + x], expected, 12)).isTrue()
            }
        }
    }

    @Test
    fun `stripes are continued rather than blurred`() {
        val w = 140
        val h = 100
        val pixels = picture(w, h) { x, _ -> if (x / 4 % 2 == 0) 0xF0F0F0 else 0x101010 }
        val hole = hole(w, h) { x, y -> x in 50..85 && y in 30..70 }
        val out = Inpaint.fill(pixels, w, h, hole)
        // Every filled pixel is close to one of the two stripe colours, not a grey average.
        var crisp = 0
        var total = 0
        for (i in out.indices) {
            if (!hole[i]) continue
            total++
            if (near(out[i], 0xF0F0F0, 40) || near(out[i], 0x101010, 40)) crisp++
        }
        assertThat(crisp.toFloat() / total).isGreaterThan(0.9f)
    }

    @Test
    fun `a large picture is solved smaller and rebuilt at full size`() {
        // Big enough that the work area is shrunk before searching.
        val w = 2400
        val h = 1200
        val sky = 0x5080D0
        val grass = 0x40A040
        val pixels = picture(w, h) { x, y -> if (x in 1000..1300 && y in 300..900) 0xFFFFFF else if (y < 600) sky else grass }
        val hole = hole(w, h) { x, y -> x in 990..1310 && y in 290..910 }
        val out = Inpaint.fill(pixels, w, h, hole)
        for (y in 290..910 step 7) {
            for (x in 990..1310 step 7) {
                if (abs(y - 600) > 24) assertThat(near(out[y * w + x], if (y < 600) sky else grass, 12)).isTrue()
            }
        }
    }

    @Test
    fun `the work area surrounds the hole and stays inside the picture`() {
        val area = Inpaint.area(intArrayOf(100, 20, 140, 70), 400, 300)
        assertThat(area[0]).isLessThan(100)
        assertThat(area[1]).isEqualTo(0)
        assertThat(area[2]).isGreaterThan(140)
        assertThat(area[3]).isGreaterThan(70)
        assertThat(area[2]).isAtMost(400)
        assertThat(area[3]).isAtMost(300)
    }

    @Test
    fun `a brush stroke marks the cells along it`() {
        val mask = EraseMask.empty(1000, 500).withStroke(floatArrayOf(0.1f, 0.5f, 0.9f, 0.5f), 0.01f)
        assertThat(mask.width).isEqualTo(EraseMask.EDGE)
        assertThat(mask.height).isEqualTo(EraseMask.EDGE / 2)
        val row = mask.height / 2
        for (x in (mask.width * 0.1f).toInt() + 1 until (mask.width * 0.9f).toInt()) assertThat(mask[x, row]).isTrue()
        assertThat(mask[mask.width / 2, 10]).isFalse()
        assertThat(mask[2, row]).isFalse()
    }

    @Test
    fun `a selection is scaled onto the mask`() {
        val selected = BooleanArray(100 * 50) { i -> i % 100 in 40..59 && i / 100 in 10..19 }
        val mask = EraseMask.empty(100, 50).withSelection(100, 50, selected)
        assertThat(mask[mask.width / 2, mask.height * 3 / 10]).isTrue()
        assertThat(mask[mask.width / 10, mask.height / 2]).isFalse()
    }

    @Test
    fun `the hole follows the mask at any size, grown`() {
        val mask = EraseMask.empty(400, 400).withStroke(floatArrayOf(0.5f, 0.5f), 0.05f)
        val grow = EraseMask.grow(2000, 2000)
        val area = mask.area(2000, 2000, grow)!!
        val hole = mask.hole(2000, 2000, grow, area)
        val w = area[2] - area[0]
        fun at(x: Int, y: Int) = hole[(y - area[1]) * w + (x - area[0])]
        assertThat(at(1000, 1000)).isTrue()
        // Radius 5% of 2000 px is 100 px, plus the growth.
        assertThat(at(1000 + 100 + grow / 2, 1000)).isTrue()
        assertThat(at(1000 + 100 + grow + 20, 1000)).isFalse()
        assertThat(area[0]).isAtMost(1000 - 100 - grow)
    }

    @Test
    fun `masks compare by content and make an edit non-identity`() {
        val a = EraseMask.empty(300, 200).withStroke(floatArrayOf(0.2f, 0.2f), 0.02f)
        val b = EraseMask.empty(300, 200).withStroke(floatArrayOf(0.2f, 0.2f), 0.02f)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(EraseMask.empty(300, 200).isEmpty).isTrue()
        assertThat(a.isEmpty).isFalse()
        assertThat(PhotoEdit(erases = listOf(a)).isIdentity).isFalse()
        assertThat(EraseMask.empty(300, 200).area(300, 200, 2)).isNull()
    }
}
