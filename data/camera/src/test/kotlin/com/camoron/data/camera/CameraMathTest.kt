package com.camoron.data.camera

import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.StillAspect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CameraMathTest {

    @Test
    fun `preview size matches still aspect ratio and stays within 1080p`() {
        val sizes = listOf(
            PixelSize(3840, 2160),
            PixelSize(1920, 1080),
            PixelSize(1440, 1080),
            PixelSize(1280, 960),
            PixelSize(640, 480),
        )
        assertThat(CameraMath.choosePreviewSize(sizes, targetAspect = 4.0 / 3)).isEqualTo(PixelSize(1440, 1080))
        assertThat(CameraMath.choosePreviewSize(sizes, targetAspect = 16.0 / 9)).isEqualTo(PixelSize(1920, 1080))
    }

    @Test
    fun `preview size falls back to largest fitting size when no aspect matches`() {
        val sizes = listOf(PixelSize(1920, 1080), PixelSize(1280, 720))
        assertThat(CameraMath.choosePreviewSize(sizes, targetAspect = 4.0 / 3)).isEqualTo(PixelSize(1920, 1080))
    }

    @Test
    fun `jpeg orientation for a typical back camera`() {
        assertThat(CameraMath.jpegOrientation(90, 0, isFrontFacing = false)).isEqualTo(90)
        assertThat(CameraMath.jpegOrientation(90, 90, isFrontFacing = false)).isEqualTo(180)
        assertThat(CameraMath.jpegOrientation(90, 268, isFrontFacing = false)).isEqualTo(0)
    }

    @Test
    fun `jpeg orientation for a typical front camera`() {
        assertThat(CameraMath.jpegOrientation(270, 0, isFrontFacing = true)).isEqualTo(270)
        assertThat(CameraMath.jpegOrientation(270, 90, isFrontFacing = true)).isEqualTo(180)
    }

    @Test
    fun `tap in portrait view maps onto landscape sensor`() {
        // Top-left of a portrait preview is the sensor's bottom-left for a 90 degree back camera.
        assertThat(CameraMath.viewToSensor(0f, 0f, 90, isFrontFacing = false)).isEqualTo(0f to 1f)
        assertThat(CameraMath.viewToSensor(0.5f, 0.5f, 90, isFrontFacing = false)).isEqualTo(0.5f to 0.5f)
        assertThat(CameraMath.viewToSensor(0.25f, 0f, 0, isFrontFacing = false)).isEqualTo(0.25f to 0f)
    }

    @Test
    fun `warm light boosts blue and cuts red, cool light the opposite`() {
        val (warmR, _, warmB) = CameraMath.kelvinToGains(3000)
        assertThat(warmB).isGreaterThan(1f)
        assertThat(warmR).isLessThan(1f)

        val (coolR, _, coolB) = CameraMath.kelvinToGains(9000)
        assertThat(coolR).isGreaterThan(1f)
        assertThat(coolB).isLessThan(1f)
    }

    @Test
    fun `daylight is close to neutral`() {
        val (r, g, b) = CameraMath.kelvinToGains(6500)
        assertThat(g).isEqualTo(1f)
        assertThat(r).isWithin(0.1f).of(1f)
        assertThat(b).isWithin(0.1f).of(1f)
    }

    @Test
    fun `rotation maps to exif orientation tags`() {
        assertThat(CameraMath.exifOrientation(0)).isEqualTo(1)
        assertThat(CameraMath.exifOrientation(90)).isEqualTo(6)
        assertThat(CameraMath.exifOrientation(180)).isEqualTo(3)
        assertThat(CameraMath.exifOrientation(270)).isEqualTo(8)
    }

    @Test
    fun `analysis stream is the smallest size at least 320 wide`() {
        val sizes = listOf(PixelSize(1920, 1080), PixelSize(640, 480), PixelSize(320, 240), PixelSize(176, 144))
        assertThat(CameraMath.chooseAnalysisSize(sizes)).isEqualTo(PixelSize(320, 240))
    }

    @Test
    fun `histogram buckets luma and respects row stride`() {
        // 4x2 image with a 6-byte row stride (2 bytes padding per row).
        val bytes = byteArrayOf(
            0, 0, -1, -1, 9, 9, // row 0: black, black, white, white, padding
            0, 0, -1, -1, 9, 9, // row 1
        )
        val counts = CameraMath.luminanceHistogram(
            java.nio.ByteBuffer.wrap(bytes),
            width = 4,
            height = 2,
            rowStride = 6,
            pixelStride = 1,
            bins = 4,
            step = 1,
        )
        assertThat(counts.toList()).containsExactly(4, 0, 0, 4).inOrder()
    }

    @Test
    fun `still aspects ignore tiny sizes`() {
        val sizes = listOf(PixelSize(4000, 3000), PixelSize(4000, 2250), PixelSize(1280, 720))
        assertThat(CameraMath.stillAspects(sizes)).containsExactly(StillAspect.FOUR_THREE, StillAspect.SIXTEEN_NINE)
        val only43 = listOf(PixelSize(4000, 3000), PixelSize(640, 360))
        assertThat(CameraMath.stillAspects(only43)).containsExactly(StillAspect.FOUR_THREE)
    }

    @Test
    fun `still size picks the largest of the requested aspect`() {
        val sizes = listOf(PixelSize(4000, 3000), PixelSize(4000, 2250), PixelSize(1920, 1080))
        assertThat(CameraMath.chooseStillSize(sizes, StillAspect.SIXTEEN_NINE)).isEqualTo(PixelSize(4000, 2250))
        assertThat(CameraMath.chooseStillSize(listOf(PixelSize(4000, 3000)), StillAspect.SIXTEEN_NINE))
            .isEqualTo(PixelSize(4000, 3000))
    }

    @Test
    fun `equivalent focal length for a typical phone main camera`() {
        // 6.9 mm lens on a 1/1.3" sensor (about 12.3 mm diagonal) is roughly a 24 mm equivalent.
        assertThat(CameraMath.equivalentFocalLength(6.9f, 12.3f)).isWithin(1f).of(24.3f)
    }
}
