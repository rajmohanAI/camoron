package com.camoron.data.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MotionVotesTest {

    /** The shaders' encoding of one motion, for building fields by hand. */
    private fun encode(dx: Float, dy: Float): ByteArray {
        fun axis(d: Float): Pair<Byte, Byte> {
            val v = ((d + 64f) / 128f).coerceIn(0f, 1f) * 255f
            val hi = kotlin.math.floor(v)
            val lo = kotlin.math.floor((v - hi) * 255f + 0.5f).coerceIn(0f, 255f)
            return hi.toInt().toByte() to lo.toInt().toByte()
        }
        val (xh, xl) = axis(dx)
        val (yh, yl) = axis(dy)
        return byteArrayOf(xh, xl, yh, yl)
    }

    private fun field(vararg motions: Pair<Pair<Float, Float>, Int>): ByteArray =
        motions.flatMap { (m, n) -> List(n) { encode(m.first, m.second).toList() }.flatten() }.toByteArray()

    @Test
    fun `decoding undoes the shaders' encoding`() {
        val bytes = encode(-12.25f, 30.5f)
        assertThat(MotionVotes.decode(bytes[0], bytes[1])).isWithin(0.01f).of(-12.25f)
        assertThat(MotionVotes.decode(bytes[2], bytes[3])).isWithin(0.01f).of(30.5f)
    }

    @Test
    fun `the motion most of the frame shares wins`() {
        val shared = MotionVotes.shared(field((3f to -2f) to 60, (15f to 6f) to 25, (0f to 0f) to 15))
        assertThat(shared[0]).isWithin(0.01f).of(3f)
        assertThat(shared[1]).isWithin(0.01f).of(-2f)
    }

    @Test
    fun `nothing standing out means standing still`() {
        val scattered = field(*Array(10) { (it * 5f to it * -3f) to 10 })
        assertThat(MotionVotes.shared(scattered).toList()).containsExactly(0f, 0f)
        assertThat(MotionVotes.shared(ByteArray(0)).toList()).containsExactly(0f, 0f)
    }
}
