package com.camoron.data.processing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.sqrt

class PanoramaMathTest {

    private val f = 300f
    private val frameWidth = 240
    private val frameHeight = 320

    /** A textured scene wrapped round the camera, as a cylinder f pixels in radius. */
    private fun scene(u: Float, v: Float): Int {
        val value = 128 + 50 * sin(u * 0.05f) * sin(v * 0.07f) + 40 * sin(u * 0.013f + v * 0.021f) + 30 * sin(u * 0.11f - v * 0.03f)
        val c = value.toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c shl 16) or ((255 - c) shl 8) or (c / 2)
    }

    /** What a camera of focal length f sees looking at [yaw] radians. */
    private fun frameAt(yaw: Float): PanoramaMath.Frame {
        val cx = (frameWidth - 1) / 2f
        val cy = (frameHeight - 1) / 2f
        val pixels = IntArray(frameWidth * frameHeight) { i ->
            val x = i % frameWidth - cx
            val y = i / frameWidth - cy
            val theta = yaw + atan(x / f)
            val height = y * f / sqrt(x * x + f * f)
            scene(theta * f + 2000f, height + 500f)
        }
        return PanoramaMath.Frame(pixels, frameWidth, frameHeight)
    }

    @Test
    fun `warped frames are narrower by the curve and keep their height`() {
        val warped = PanoramaMath.warp(frameAt(0f), f)
        assertThat(warped.height).isEqualTo(frameHeight)
        assertThat(warped.width).isLessThan(frameWidth)
        assertThat(warped.width).isAtLeast((2 * f * atan(frameWidth / 2f / f)).toInt() - 1)
        // The centre is filled; the corners curve away.
        assertThat(warped.pixels[(frameHeight / 2) * warped.width + warped.width / 2] ushr 24).isEqualTo(0xFF)
        assertThat(warped.pixels[0] ushr 24).isEqualTo(0)
    }

    @Test
    fun `frames are placed by matching even when the measured turn is off`() {
        val step = 0.35f
        val yaws = List(4) { it * step }
        val frames = yaws.map(::frameAt)
        // The phone's sensor reports each turn a few degrees out.
        val measured = yaws.mapIndexed { i, y -> y + if (i % 2 == 0) 0.04f else -0.03f }
        val pano = PanoramaMath.stitch(frames, measured, f)!!
        val warpedWidth = PanoramaMath.warp(frames[0], f).width
        val expected = (f * step * 3).toInt() + warpedWidth
        assertThat(pano.width).isIn(expected - 12..expected + 12)
        assertThat(pano.height).isGreaterThan(frameHeight / 2)
    }

    @Test
    fun `the stitched panorama matches the scene`() {
        val step = 0.3f
        val yaws = List(3) { it * step }
        val pano = PanoramaMath.stitch(yaws.map(::frameAt), yaws, f)!!
        // Compare a row through the middle against the scene, allowing for where the crop started.
        val row = pano.height / 2
        val warpedWidth = PanoramaMath.warp(frameAt(0f), f).width
        val left = 2000f - (warpedWidth - 1) / 2f
        val sceneRow = 500f + (row - pano.height / 2f)
        var error = 0L
        var count = 0
        for (x in 20 until pano.width - 20) {
            val got = (pano.pixels[row * pano.width + x] shr 16) and 0xFF
            val want = (scene(left + x, sceneRow) shr 16) and 0xFF
            error += kotlin.math.abs(got - want)
            count++
        }
        assertThat(error.toFloat() / count).isLessThan(12f)
    }

    @Test
    fun `fewer than two frames is no panorama`() {
        assertThat(PanoramaMath.stitch(listOf(frameAt(0f)), listOf(0f), f)).isNull()
    }
}
