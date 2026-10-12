package com.camoron.feature.camera

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.CaptureBundle
import com.camoron.domain.model.Histogram
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.HardwareLevel
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.PixelSize
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PreviewTarget
import com.camoron.domain.usecase.CaptureAndSavePhoto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    private val back = camera("0", LensFacing.BACK, PixelSize(4000, 3000))
    private val backWide = camera("2", LensFacing.BACK, PixelSize(2000, 1500))
    private val front = camera("1", LensFacing.FRONT, PixelSize(3264, 2448))
    private val controller = FakeCameraController(listOf(backWide, back, front))
    private val media = object : MediaRepository {
        var saved = 0
        override suspend fun save(image: CapturedImage): String = "content://saved/${++saved}"
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CameraViewModel(controller, CaptureAndSavePhoto(controller, media))

    @Test
    fun `starts on the highest resolution back camera`() = runTest {
        val vm = viewModel()
        assertThat(vm.uiState.value.selectedCamera).isEqualTo(back)
        assertThat(vm.uiState.value.canSwitchCamera).isTrue()
    }

    @Test
    fun `opens only once the surface matches the preview size`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(640, 480))
        assertThat(controller.opened).isEmpty()

        vm.onSurfaceReady(Target(1440, 1080))
        assertThat(controller.opened).containsExactly("0")
    }

    @Test
    fun `switching camera with an already sized surface reopens directly`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.switchCamera()
        assertThat(controller.opened).containsExactly("0", "1").inOrder()
    }

    @Test
    fun `manual values only reach the camera in pro mode`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.setIso(800)
        assertThat(controller.applied).isEmpty()

        vm.setMode(CaptureMode.PRO)
        assertThat(controller.applied.last().iso).isEqualTo(800)

        vm.setMode(CaptureMode.PHOTO)
        assertThat(controller.applied.last()).isEqualTo(ManualSettings.AUTO)
    }

    @Test
    fun `capture saves and reports the uri`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.capture(0)
        assertThat(vm.uiState.value.event).isEqualTo(CameraEvent.Saved("content://saved/1"))
        assertThat(vm.uiState.value.isCapturing).isFalse()
    }

    @Test
    fun `raw toggles the session only in pro mode on a raw capable camera`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.setRawEnabled(true) // photo mode: no effect on the session
        assertThat(controller.openedWithRaw).containsExactly(false)

        vm.setMode(CaptureMode.PRO)
        assertThat(controller.openedWithRaw).containsExactly(false, true).inOrder()

        vm.capture(0)
        assertThat(controller.capturedWithRaw).containsExactly(true)
        assertThat(media.saved).isEqualTo(2) // JPEG and DNG

        vm.setMode(CaptureMode.PHOTO)
        assertThat(controller.openedWithRaw.last()).isFalse()
    }

    private data class Target(override val width: Int, override val height: Int) : PreviewTarget

    private class FakeCameraController(private val all: List<CameraCapabilities>) : CameraController {
        override val state = MutableStateFlow<CameraState>(CameraState.Closed)
        override val histogram = MutableStateFlow<Histogram?>(null)
        val opened = mutableListOf<String>()
        val openedWithRaw = mutableListOf<Boolean>()
        val capturedWithRaw = mutableListOf<Boolean>()
        val applied = mutableListOf<ManualSettings>()

        override suspend fun cameras() = all
        override suspend fun previewSize(cameraId: String) = PixelSize(1440, 1080)
        override suspend fun open(cameraId: String, target: PreviewTarget, rawEnabled: Boolean) {
            opened += cameraId
            openedWithRaw += rawEnabled
            state.value = CameraState.Previewing(all.first { it.cameraId == cameraId }, ManualSettings.AUTO, null)
        }
        override suspend fun applySettings(settings: ManualSettings) {
            applied += settings
        }
        override suspend fun focusAt(x: Float, y: Float) = Unit
        override suspend fun capture(deviceRotationDegrees: Int, includeRaw: Boolean): CaptureBundle {
            capturedWithRaw += includeRaw
            val jpeg = CapturedImage(ByteArray(1), ImageFormat.JPEG, PixelSize(4000, 3000), 0L)
            val raw = CapturedImage(ByteArray(1), ImageFormat.DNG, PixelSize(4000, 3000), 0L).takeIf { includeRaw }
            return CaptureBundle(jpeg, raw)
        }
        override suspend fun close() {
            state.value = CameraState.Closed
        }
    }

    private fun camera(id: String, facing: LensFacing, size: PixelSize) = CameraCapabilities(
        cameraId = id,
        lensFacing = facing,
        hardwareLevel = HardwareLevel.FULL,
        sensorSize = size,
        maxJpegSize = size,
        isoRange = 100..3200,
        exposureTimeRangeNs = 100_000L..1_000_000_000L,
        exposureCompensationRange = -6..6,
        exposureCompensationStep = 1f / 3,
        whiteBalancePresets = emptySet(),
        supportsManualWhiteBalance = false,
        supportsRaw = true,
        supportsTapToFocus = true,
        hasLensDistortionCalibration = false,
    )
}
