package com.camoron.domain

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.CaptureBundle
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.DeviceProfile
import com.camoron.domain.model.EnhanceParams
import com.camoron.domain.model.FlashMode
import com.camoron.domain.model.FocusTarget
import com.camoron.domain.model.Histogram
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.PhotoAdjustments
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.StillAspect
import com.camoron.domain.model.VideoSpec
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PendingVideo
import com.camoron.domain.repository.PhotoProcessor
import com.camoron.domain.repository.PreviewTarget
import com.camoron.domain.usecase.CaptureAndSavePhoto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.io.FileDescriptor
import org.junit.Test

class CaptureAndSavePhotoTest {

    private val jpeg = CapturedImage(byteArrayOf(1), ImageFormat.JPEG, PixelSize(4000, 3000), 0L)
    private val dng = CapturedImage(byteArrayOf(2), ImageFormat.DNG, PixelSize(4000, 3000), 0L)
    private val saved = mutableListOf<CapturedImage>()
    private val processed = mutableListOf<PhotoAdjustments>()

    private val media = object : MediaRepository {
        override suspend fun save(image: CapturedImage): String {
            saved += image
            return "content://${saved.size}"
        }
        override suspend fun createVideo(timestampMillis: Long): PendingVideo = error("No video here")
        override suspend fun latestCapture(): String? = null
    }

    private val processor = object : PhotoProcessor {
        override suspend fun apply(image: CapturedImage, adjustments: PhotoAdjustments): CapturedImage {
            processed += adjustments
            return image.withBytes(byteArrayOf(9))
        }

        override suspend fun mergeNight(frames: List<CapturedImage>): CapturedImage {
            merged += frames.size
            return frames.first().withBytes(byteArrayOf(7))
        }
    }
    private val merged = mutableListOf<Int>()

    /** Captures that fail before one succeeds, as some phones do right after the session opens. */
    private var failuresLeft = 0

    private fun useCase(includeDng: Boolean) = CaptureAndSavePhoto(camera(includeDng), media, processor)

    @Test
    fun `a failed capture is retried once`() = runTest {
        failuresLeft = 1
        var captured: CapturedImage? = null
        val uri = useCase(includeDng = false)(0, onCaptured = { captured = it })
        assertThat(uri).isEqualTo("content://1")
        assertThat(captured).isEqualTo(jpeg)
    }

    @Test
    fun `a second failure is reported`() = runTest {
        failuresLeft = 2
        val result = runCatching { useCase(includeDng = false)(0) }
        assertThat(result.isFailure).isTrue()
        assertThat(saved).isEmpty()
    }

    @Test
    fun `no adjustments saves the camera's jpeg untouched`() = runTest {
        val uri = useCase(includeDng = false)(0)
        assertThat(uri).isEqualTo("content://1")
        assertThat(processed).isEmpty()
        assertThat(saved.single().bytes.toList()).containsExactly(1.toByte())
    }

    @Test
    fun `adjustments apply to the jpeg but never the dng`() = runTest {
        val adjustments = PhotoAdjustments(cropAspect = 9f / 16, mirror = true)
        val uri = useCase(includeDng = true)(0, includeRaw = true, adjustments = adjustments)
        assertThat(uri).isEqualTo("content://1")
        assertThat(processed).containsExactly(adjustments)
        assertThat(saved.map { it.bytes.single() }).containsExactly(9.toByte(), 2.toByte()).inOrder()
    }

    @Test
    fun `night burst is merged then adjusted and saved without raw`() = runTest {
        val uri = useCase(includeDng = true)(0, includeRaw = true, nightFrames = 6)
        assertThat(uri).isEqualTo("content://1")
        assertThat(merged).containsExactly(6)
        assertThat(saved.single().bytes.single()).isEqualTo(7.toByte())
    }

    @Test
    fun `an ordinary photo is the camera's own photo, optimised`() = runTest {
        var shown: CapturedImage? = null
        val adjustments = PhotoAdjustments(cropAspect = 3f / 4, enhance = true)
        val uri = useCase(includeDng = false)(0, adjustments = adjustments, onCaptured = { shown = it })
        assertThat(shown).isEqualTo(jpeg)
        assertThat(uri).isEqualTo("content://1")
        assertThat(processed).containsExactly(adjustments)
        assertThat(saved.single().bytes.single()).isEqualTo(9.toByte())
    }

    @Test
    fun `night shows its first frame while merging`() = runTest {
        var shown: CapturedImage? = null
        useCase(includeDng = false)(0, nightFrames = 8, onCaptured = { shown = it })
        assertThat(shown).isEqualTo(jpeg)
        assertThat(merged).containsExactly(8)
    }

    private fun camera(includeDng: Boolean) = object : CameraController {
        override val state = MutableStateFlow<CameraState>(CameraState.Closed)
        override val histogram = MutableStateFlow<Histogram?>(null)
        override suspend fun cameras(): List<CameraCapabilities> = listOf(fullCamera())
        override suspend fun deviceProfile(): DeviceProfile = DeviceProfile(cameras())
        override suspend fun previewSize(cameraId: String, aspect: StillAspect, nightExtension: Boolean) =
            PixelSize(1440, 1080)
        override suspend fun open(
            cameraId: String,
            target: PreviewTarget,
            rawEnabled: Boolean,
            aspect: StillAspect,
            nightExtension: Boolean,
        ) = Unit
        override suspend fun captureBurst(deviceRotationDegrees: Int, frames: Int) = List(frames) { jpeg }
        override suspend fun setFlashMode(mode: FlashMode) = Unit
        override suspend fun applySettings(settings: ManualSettings) = Unit
        override suspend fun focusAt(x: Float, y: Float, track: Boolean) = Unit
        override val focusTarget = MutableStateFlow<FocusTarget?>(null)
        override suspend fun setZoom(ratio: Float) = Unit
        override suspend fun setMacro(enabled: Boolean) = Unit
        override suspend fun capture(deviceRotationDegrees: Int, includeRaw: Boolean): CaptureBundle {
            if (failuresLeft > 0) {
                failuresLeft--
                error("Capture failed")
            }
            return CaptureBundle(jpeg, dng.takeIf { includeDng && includeRaw })
        }
        override suspend fun videoPreviewSize(cameraId: String, spec: VideoSpec) = PixelSize(1080, 1920)
        override suspend fun openVideo(cameraId: String, target: PreviewTarget, spec: VideoSpec) = Unit
        override fun setVideoFilter(look: FloatArray, enhance: EnhanceParams?) = Unit
        override suspend fun startRecording(output: FileDescriptor, deviceRotationDegrees: Int) = Unit
        override suspend fun stopRecording() = false
        override suspend fun close() = Unit
    }
}
