package com.camoron.domain

import com.camoron.domain.model.EnhanceMath
import com.camoron.domain.model.EnhanceParams
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnhanceMathTest {

    private fun histogram(vararg filled: IntRange): IntArray = IntArray(64).also { bins ->
        filled.forEach { range -> range.forEach { bins[it] += 100 } }
    }

    @Test
    fun `a dark scene is brightened and its shadows opened`() {
        val p = EnhanceMath.fromHistogram(histogram(2..18))
        assertThat(p.gamma).isLessThan(1f)
        assertThat(p.shadowLift).isGreaterThan(0.04f)
        assertThat(EnhanceMath.curve(p, 0.15f)).isGreaterThan(0.15f)
    }

    @Test
    fun `a bright scene is not brightened further`() {
        val p = EnhanceMath.fromHistogram(histogram(40..62))
        assertThat(p.gamma).isAtLeast(1f)
    }

    @Test
    fun `a washed out picture is stretched, without crushing its ends`() {
        val p = EnhanceMath.fromHistogram(histogram(12..44))
        assertThat(p.black).isWithin(1e-6f).of(0.1f)
        assertThat(p.white).isWithin(1e-6f).of(0.8f)
        assertThat(EnhanceMath.curve(p, p.black)).isEqualTo(0f)
        assertThat(EnhanceMath.curve(p, p.white)).isEqualTo(1f)
    }

    @Test
    fun `the tone curve never goes backwards`() {
        val lut = EnhanceMath.toneCurve(EnhanceMath.fromHistogram(histogram(0..63)))
        for (i in 1 until lut.size) assertThat(lut[i]).isAtLeast(lut[i - 1])
    }

    @Test
    fun `vibrance adds colour to muted pixels but leaves grey alone`() {
        val grey = EnhanceMath.vibrant(128, 128, 128, 0.3f)
        assertThat(grey).isEqualTo((128 shl 16) or (128 shl 8) or 128)

        val muted = EnhanceMath.vibrant(150, 120, 110, 0.3f)
        val r = (muted shr 16) and 0xFF
        val b = muted and 0xFF
        assertThat(r - b).isGreaterThan(150 - 110)
    }

    @Test
    fun `an empty histogram falls back to the default`() {
        assertThat(EnhanceMath.fromHistogram(IntArray(64))).isEqualTo(EnhanceParams.DEFAULT)
    }

    @Test
    fun `blending halfway lands between the two`() {
        val a = EnhanceParams.DEFAULT
        val b = a.copy(gamma = 2f)
        assertThat(EnhanceMath.blend(a, b, 0.5f).gamma).isWithin(1e-6f).of(1.5f)
    }
}
