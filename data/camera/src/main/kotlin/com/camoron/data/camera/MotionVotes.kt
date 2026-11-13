package com.camoron.data.camera

import kotlin.math.abs
import kotlin.math.roundToInt

/** Finds the motion most of a frame shares from a coarse motion field, as [FrameInterpolator] stores it. */
internal object MotionVotes {

    /**
     * [rgba] holds one motion per four bytes (two per axis, as the interpolator's shaders encode
     * it). Votes in [BIN]-pixel bins for the most common motion, then returns the median of the
     * motions near it, so it is exact to a fraction of a pixel; standing still when nothing
     * stands out.
     */
    fun shared(rgba: ByteArray): FloatArray {
        val count = rgba.size / 4
        if (count == 0) return floatArrayOf(0f, 0f)
        val xs = FloatArray(count) { decode(rgba[it * 4], rgba[it * 4 + 1]) }
        val ys = FloatArray(count) { decode(rgba[it * 4 + 2], rgba[it * 4 + 3]) }
        val votes = HashMap<Long, Int>()
        for (i in 0 until count) {
            val key = ((xs[i] / BIN).roundToInt().toLong() shl 32) or ((ys[i] / BIN).roundToInt().toLong() and 0xFFFFFFFFL)
            votes[key] = (votes[key] ?: 0) + 1
        }
        val (key, n) = votes.maxByOrNull { it.value } ?: return floatArrayOf(0f, 0f)
        if (n < count * MIN_SHARE) return floatArrayOf(0f, 0f)
        val cx = (key shr 32).toInt() * BIN
        val cy = key.toInt() * BIN
        val near = (0 until count).filter { abs(xs[it] - cx) <= BIN && abs(ys[it] - cy) <= BIN }
        return floatArrayOf(median(near.map { xs[it] }), median(near.map { ys[it] }))
    }

    private fun median(values: List<Float>): Float = values.sorted()[values.size / 2]

    /** Inverse of the shaders' encodeMotion for one axis. */
    fun decode(hi: Byte, lo: Byte): Float {
        val v = ((hi.toInt() and 0xFF) + (lo.toInt() and 0xFF) / 255f) / 255f
        return v * 128f - 64f
    }

    /** A motion must be shared by at least this much of the frame to count. */
    private const val MIN_SHARE = 0.2f
    private const val BIN = 4f
}
