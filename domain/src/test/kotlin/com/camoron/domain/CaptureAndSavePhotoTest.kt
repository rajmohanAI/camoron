package com.camoron.domain

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.CaptureBundle
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.DeviceProfile
import com.camoron.domain.model.FlashMode
import com.camoron.domain.model.Histogram
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.PhotoAdjustments
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.StillAspect
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PhotoProcessor
import com.camoron.domain.repository.PreviewTarget
import com.camoron.domain.usecase.CaptureAndSavePhoto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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
        override suspend fun latestCapture(): String? = null
    }

    private val processor = object : PhotoProcessor {
        override suspend fun apply(image: CapturedImage, adjustments: PhotoAdjustments): CapturedImage {
            processed += adjustments
            return image.withBytes(byteArrayOf(9))
        }
    }

    private fun useCase(includeDng: Boolean) = CaptureAndSavePhoto(camera(includeDng), media, processor)

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

    private fun camera(includeDng: Boolean) = object : CameraController {
        override val state = MutableStateFlow<CameraState>(CameraState.Closed)
        override val histogram = MutableStateFlow<Histogram?>(null)
        override suspend fun cameras(): List<CameraCapabilities> = listOf(fullCamera())
        override suspend fun deviceProfile(): DeviceProfile = DeviceProfile(cameras())
        override suspend fun previewSize(cameraId: String, aspect: StillAspect) = PixelSize(1440, 1080)
        override suspend fun open(cameraId: String, target: PreviewTarget, rawEnabled: Boolean, aspect: StillAspect) = Unit
        override suspend fun setFlashMode(mode: FlashMode) = Unit
        override suspend fun applySettings(settings: ManualSettings) = Unit
        override suspend fun focusAt(x: Float, y: Float) = Unit
        override suspend fun capture(deviceRotationDegrees: Int, includeRaw: Boolean) =
            CaptureBundle(jpeg, dng.takeIf { includeDng && includeRaw })
        override suspend fun close() = Unit
    }
}
