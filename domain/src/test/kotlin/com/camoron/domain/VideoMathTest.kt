package com.camoron.domain

import com.camoron.domain.model.FramePacer
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.TimelapseSpeed
import com.camoron.domain.model.VideoKind
import com.camoron.domain.model.VideoMath
import com.camoron.domain.model.VideoSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoMathTest {

    private val ranges = listOf(15..15, 15..30, 24..24, 30..30, 7..30)

    @Test
    fun `standard video runs at a fixed 30 fps`() {
        assertThat(VideoMath.chooseFpsRange(ranges, VideoKind.STANDARD)).isEqualTo(30..30)
    }

    @Test
    fun `cinematic uses a native 24 fps mode when the camera has one`() {
        assertThat(VideoMath.chooseFpsRange(ranges, VideoKind.CINEMATIC)).isEqualTo(24..24)
        assertThat(VideoMath.chooseFpsRange(listOf(15..30, 30..30), VideoKind.CINEMATIC)).isEqualTo(30..30)
    }

    @Test
    fun `timelapse takes the widest range for dim scenes`() {
        assertThat(VideoMath.chooseFpsRange(ranges, VideoKind.TIMELAPSE)).isEqualTo(7..30)
    }

    @Test
    fun `no usable range keeps the camera default`() {
        assertThat(VideoMath.chooseFpsRange(listOf(15..20), VideoKind.STANDARD)).isNull()
    }

    @Test
    fun `phone held upright plays upright`() {
        assertThat(VideoMath.orientationHint(0, isFrontFacing = false, contentTurned = true)).isEqualTo(90)
        assertThat(VideoMath.orientationHint(0, isFrontFacing = false, contentTurned = false)).isEqualTo(0)
    }

    @Test
    fun `landscape turns the opposite way for the mirrored selfie camera`() {
        assertThat(VideoMath.orientationHint(88, isFrontFacing = false, contentTurned = true)).isEqualTo(180)
        assertThat(VideoMath.orientationHint(88, isFrontFacing = true, contentTurned = true)).isEqualTo(0)
        assertThat(VideoMath.orientationHint(270, isFrontFacing = true, contentTurned = true)).isEqualTo(180)
    }

    @Test
    fun `preview of a 4k recording is upright and no bigger than 1080p`() {
        assertThat(VideoMath.previewSize(PixelSize(3840, 2160), contentTurned = true)).isEqualTo(PixelSize(1080, 1920))
        assertThat(VideoMath.previewSize(PixelSize(1280, 720), contentTurned = false)).isEqualTo(PixelSize(1280, 720))
    }

    @Test
    fun `bitrate grows with size and frame rate but is capped`() {
        val fhd = VideoMath.bitrate(PixelSize(1920, 1080), 30)
        assertThat(fhd).isGreaterThan(15_000_000)
        assertThat(VideoMath.bitrate(PixelSize(1920, 1080), 24)).isLessThan(fhd)
        assertThat(VideoMath.bitrate(PixelSize(3840, 2160), 30)).isEqualTo(50_000_000)
    }

    @Test
    fun `recording time reads like a clock`() {
        assertThat(VideoMath.formatDuration(7_400)).isEqualTo("0:07")
        assertThat(VideoMath.formatDuration(750_000)).isEqualTo("12:30")
        assertThat(VideoMath.formatDuration(3_723_000)).isEqualTo("1:02:03")
    }

    @Test
    fun `every frame keeps its own timestamp`() {
        val pacer = FramePacer.everyFrame()
        assertThat(frames(30, 10).map(pacer::next)).isEqualTo(frames(30, 10))
    }

    @Test
    fun `30 fps camera frames are thinned to 24 a second`() {
        val pacer = FramePacer.decimate(24)
        val kept = frames(30, 300).mapNotNull(pacer::next)
        assertThat(kept.size).isIn(239..241)
        // No two kept frames closer than one 30 fps frame apart, none further than two.
        kept.zipWithNext { a, b -> b - a }.forEach { gap -> assertThat(gap).isIn(33_000_000L..67_000_000L) }
    }

    @Test
    fun `timelapse keeps one frame per interval and plays them back at 30 fps`() {
        val pacer = VideoMath.pacerFor(VideoSpec(VideoKind.TIMELAPSE, timelapseSpeed = TimelapseSpeed.X15), null)
        val kept = frames(30, 300).mapNotNull(pacer::next)
        // 10 seconds at one frame every half second.
        assertThat(kept.size).isEqualTo(20)
        kept.zipWithNext { a, b -> b - a }.forEach { gap -> assertThat(gap).isEqualTo(33_333_333L) }
    }

    @Test
    fun `cinematic only thins frames when the camera has no 24 fps mode`() {
        val native = VideoMath.pacerFor(VideoSpec(VideoKind.CINEMATIC), 24..24)
        assertThat(frames(24, 24).mapNotNull(native::next)).hasSize(24)
        val thinned = VideoMath.pacerFor(VideoSpec(VideoKind.CINEMATIC), 30..30)
        assertThat(frames(30, 30).mapNotNull(thinned::next).size).isIn(23..25)
    }

    private fun frames(fps: Int, count: Int): List<Long> =
        List(count) { 5_000_000_000L + it * 1_000_000_000L / fps }
}
