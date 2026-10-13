package com.camoron.feature.camera

import com.camoron.domain.model.AspectRatioSetting
import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraError
import com.camoron.domain.model.CameraExtension
import com.camoron.domain.model.CameraSettings
import com.camoron.domain.model.DeviceProfile
import com.camoron.domain.model.EnhanceParams
import com.camoron.domain.model.FlashMode
import com.camoron.domain.model.FocusTarget
import com.camoron.domain.model.ZoomPreset
import com.camoron.domain.model.LiveExposure
import com.camoron.domain.model.PhotoAdjustments
import com.camoron.domain.model.StillAspect
import com.camoron.domain.model.TimerSetting
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.CaptureBundle
import com.camoron.domain.model.Histogram
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.HardwareLevel
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.LookMatrix
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.NightSetting
import com.camoron.domain.model.ShotLook
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.VideoKind
import com.camoron.domain.model.VideoResolution
import com.camoron.domain.model.VideoSpec
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.MediaRepository
import com.camoron.domain.repository.PendingVideo
import com.camoron.domain.repository.PhotoProcessor
import com.camoron.domain.repository.SettingsRepository
import com.camoron.domain.repository.PreviewTarget
import com.camoron.domain.usecase.CaptureAndSavePhoto
import com.camoron.domain.usecase.PlanZoomPresets
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.FileDescriptor

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    private val back = camera("0", LensFacing.BACK, PixelSize(4000, 3000))
    private val backWide = camera("2", LensFacing.BACK, PixelSize(2000, 1500))
    private val front = camera("1", LensFacing.FRONT, PixelSize(3264, 2448))
    private val controller = FakeCameraController(listOf(backWide, back, front))
    private val media = object : MediaRepository {
        var saved = 0
        val finishedVideos = mutableListOf<Boolean>()
        override suspend fun save(image: CapturedImage): String = "content://saved/${++saved}"
        override suspend fun createVideo(timestampMillis: Long) = object : PendingVideo {
            override val fileDescriptor = FileDescriptor()
            override suspend fun finish(keep: Boolean): String? {
                finishedVideos += keep
                return if (keep) "content://video/${finishedVideos.size}" else null
            }
        }
        override suspend fun latestCapture(): String? = "content://saved/earlier"
    }
    private val processor = object : PhotoProcessor {
        val applied = mutableListOf<PhotoAdjustments>()
        val merged = mutableListOf<Int>()
        override suspend fun apply(image: CapturedImage, adjustments: PhotoAdjustments): CapturedImage {
            applied += adjustments
            return image
        }
        override suspend fun mergeNight(frames: List<CapturedImage>): CapturedImage {
            merged += frames.size
            return frames.first()
        }
    }
    private val settings = FakeSettingsRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() =
        CameraViewModel(controller, CaptureAndSavePhoto(controller, media, processor), settings, media, PlanZoomPresets())

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
        vm.onShutter()
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

        vm.onShutter()
        assertThat(controller.capturedWithRaw).containsExactly(true)
        assertThat(media.saved).isEqualTo(2) // JPEG and DNG

        vm.setMode(CaptureMode.PHOTO)
        assertThat(controller.openedWithRaw.last()).isFalse()
    }

    @Test
    fun `shows the last saved photo until a new one is taken`() = runTest {
        val vm = viewModel()
        assertThat(vm.uiState.value.lastCaptureUri).isEqualTo("content://saved/earlier")
        vm.onSurfaceReady(Target(1440, 1080))
        vm.onShutter()
        assertThat(vm.uiState.value.lastCaptureUri).isEqualTo("content://saved/1")
    }

    @Test
    fun `timer counts down before capturing and a second tap cancels`() = runTest {
        settings.state.update { it.copy(timer = TimerSetting.THREE) }
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))

        vm.onShutter()
        assertThat(vm.uiState.value.countdown).isEqualTo(3)
        advanceTimeBy(1_500)
        assertThat(vm.uiState.value.countdown).isEqualTo(2)
        vm.onShutter() // cancel
        assertThat(vm.uiState.value.countdown).isNull()
        advanceTimeBy(5_000)
        assertThat(controller.capturedWithRaw).isEmpty()

        vm.onShutter()
        advanceTimeBy(3_100)
        runCurrent()
        assertThat(controller.capturedWithRaw).hasSize(1)
        assertThat(vm.uiState.value.countdown).isNull()
    }

    @Test
    fun `flash setting reaches the camera`() = runTest {
        val vm = viewModel()
        assertThat(controller.flashModes).containsExactly(FlashMode.OFF)
        vm.cycleFlash()
        assertThat(controller.flashModes.last()).isEqualTo(FlashMode.AUTO)
    }

    @Test
    fun `changing the photo shape keeps the camera running`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        assertThat(controller.openedWithAspect).containsExactly(StillAspect.FOUR_THREE)

        vm.cycleAspectRatio() // 9:16: a crop of the same 4:3 frame
        vm.cycleAspectRatio() // full screen
        assertThat(controller.openedWithAspect).containsExactly(StillAspect.FOUR_THREE)
    }

    @Test
    fun `full screen photos are cropped to the display shape`() = runTest {
        settings.state.update { it.copy(aspectRatio = AspectRatioSetting.FULL_SCREEN) }
        val vm = viewModel()
        vm.onDisplayAspect(0.45f)
        vm.onSurfaceReady(Target(1920, 1080))
        vm.onShutter()
        assertThat(processor.applied.single()).isEqualTo(PhotoAdjustments(cropAspect = 0.45f, mirror = false, enhance = true))
    }

    @Test
    fun `front camera photos are mirrored only when selfie mirror is on`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.switchCamera()
        vm.onShutter()
        assertThat(processor.applied.last().mirror).isTrue()

        settings.state.update { it.copy(selfieMirror = false) }
        vm.onShutter()
        assertThat(processor.applied.last().mirror).isFalse()
    }

    @Test
    fun `front camera without flash lights the screen in low light on auto`() = runTest {
        settings.state.update { it.copy(flash = FlashMode.AUTO) }
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.switchCamera()
        controller.state.update {
            (it as CameraState.Previewing).copy(liveExposure = LiveExposure(iso = 1600, exposureTimeNs = 33_000_000L))
        }
        vm.onShutter()
        assertThat(vm.uiState.value.screenFlash).isTrue()
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(vm.uiState.value.screenFlash).isFalse()
        assertThat(controller.capturedWithRaw).hasSize(1)
    }

    @Test
    fun `shutter sound is forced where the law requires it`() = runTest {
        controller.mustPlayShutterSound = true
        settings.state.update { it.copy(shutterSound = false) }
        val vm = viewModel()
        assertThat(vm.uiState.value.shutterSoundOn).isTrue()
    }

    @Test
    fun `ultra-wide button switches to the separate wide camera and back`() = runTest {
        controller.all = listOf(
            back.copy(equivalentFocalLengthMm = 26f, maxDigitalZoom = 4f),
            backWide.copy(equivalentFocalLengthMm = 13f),
            front,
        )
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        val presets = vm.uiState.value.zoomPresets
        assertThat(presets.map { it.label }).containsExactly(0.5f, 1f, 2f, 3f).inOrder()

        vm.selectZoomPreset(presets.first())
        assertThat(controller.opened.last()).isEqualTo("2")
        assertThat(vm.uiState.value.displayZoom).isEqualTo(0.5f)
        assertThat(vm.uiState.value.activePreset).isEqualTo(presets.first())

        vm.selectZoomPreset(presets[2])
        assertThat(controller.opened.last()).isEqualTo("0")
        assertThat(controller.zooms.last()).isEqualTo(2f)
        assertThat(vm.uiState.value.displayZoom).isEqualTo(2f)
    }

    @Test
    fun `pinch zoom stays inside the camera's range`() = runTest {
        controller.all = listOf(back.copy(maxDigitalZoom = 4f), front)
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.onPinch(1.5f)
        assertThat(controller.zooms.last()).isEqualTo(1.5f)
        vm.onPinch(10f)
        assertThat(controller.zooms.last()).isEqualTo(4f)
        vm.onPinch(0.01f)
        assertThat(controller.zooms.last()).isEqualTo(1f)
    }

    @Test
    fun `macro uses the closest focusing camera and returns to the main one`() = runTest {
        controller.all = listOf(
            back,
            backWide.copy(minFocusDistanceDiopters = 33f, supportsMacroAf = true),
            front,
        )
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        assertThat(vm.uiState.value.macroAvailable).isTrue()

        vm.toggleMacro()
        assertThat(controller.macros).containsExactly(true)
        assertThat(controller.opened.last()).isEqualTo("2")

        vm.toggleMacro()
        assertThat(controller.macros).containsExactly(true, false).inOrder()
        assertThat(controller.opened.last()).isEqualTo("0")
    }

    @Test
    fun `tap tracks the subject only when the setting is on`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.focusAt(0.5f, 0.5f)
        settings.state.update { it.copy(autoFocusTracking = false) }
        vm.focusAt(0.5f, 0.5f)
        assertThat(controller.focused).containsExactly(true, false).inOrder()
    }

    @Test
    fun `the chosen look is applied to the saved photo`() = runTest {
        settings.state.update { it.copy(look = ShotLook.VIVID) }
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.onShutter()
        assertThat(processor.applied.last().look).isEqualTo(ShotLook.VIVID)

        vm.setLook(ShotLook.BLACK_AND_WHITE)
        vm.onShutter()
        assertThat(processor.applied.last().look).isEqualTo(ShotLook.BLACK_AND_WHITE)
    }

    @Test
    fun `night on merges a burst when the maker has no night mode`() = runTest {
        settings.state.update { it.copy(night = NightSetting.ON) }
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        assertThat(vm.uiState.value.nightActive).isTrue()
        vm.onShutter()
        assertThat(controller.bursts).containsExactly(8)
        assertThat(processor.merged).containsExactly(8)
        assertThat(controller.capturedWithRaw).isEmpty()
        assertThat(vm.uiState.value.nightCapturing).isFalse()
    }

    @Test
    fun `night auto only kicks in when it is dark`() = runTest {
        settings.state.update { it.copy(night = NightSetting.AUTO) }
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        controller.state.update {
            (it as CameraState.Previewing).copy(liveExposure = LiveExposure(iso = 100, exposureTimeNs = 10_000_000L))
        }
        vm.onShutter()
        assertThat(controller.bursts).isEmpty()

        controller.state.update {
            (it as CameraState.Previewing).copy(liveExposure = LiveExposure(iso = 3200, exposureTimeNs = 66_000_000L))
        }
        vm.onShutter()
        assertThat(controller.bursts).containsExactly(8)
    }

    @Test
    fun `night is off in pro mode and when the flash is forced on`() = runTest {
        controller.all = listOf(back.copy(hasFlash = true), front)
        settings.state.update { it.copy(night = NightSetting.ON, flash = FlashMode.ON) }
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.onShutter()
        assertThat(controller.bursts).isEmpty()

        settings.state.update { it.copy(flash = FlashMode.OFF) }
        vm.setMode(CaptureMode.PRO)
        vm.onShutter()
        assertThat(controller.bursts).isEmpty()
    }

    @Test
    fun `night on uses the maker's night mode where the camera has one`() = runTest {
        controller.all = listOf(back.copy(extensions = setOf(CameraExtension.NIGHT)), front)
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        assertThat(controller.openedWithNightExtension.last()).isFalse()

        settings.state.update { it.copy(night = NightSetting.ON) }
        assertThat(controller.openedWithNightExtension.last()).isTrue()
        assertThat(vm.uiState.value.showFlashControl).isFalse()
        vm.onShutter()
        assertThat(controller.bursts).isEmpty()
        assertThat(controller.capturedWithRaw).hasSize(1)

        settings.state.update { it.copy(night = NightSetting.OFF) }
        assertThat(controller.openedWithNightExtension.last()).isFalse()
    }

    @Test
    fun `enhance is on by default and can be turned off`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.onShutter()
        assertThat(processor.applied.last().enhance).isTrue()

        vm.toggleEnhance()
        vm.onShutter()
        assertThat(processor.applied.last().enhance).isFalse()
    }

    @Test
    fun `the camera closes in the background and reopens on return`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        val opens = controller.opened.size
        vm.onForegroundChanged(false)
        assertThat(controller.state.value).isEqualTo(CameraState.Closed)
        vm.onForegroundChanged(true)
        assertThat(controller.opened).hasSize(opens + 1)
    }

    @Test
    fun `the same surface reported twice opens the camera once`() = runTest {
        val vm = viewModel()
        val target = Target(1440, 1080)
        vm.onSurfaceReady(target)
        vm.onSurfaceReady(target)
        assertThat(controller.opened).hasSize(1)
    }

    @Test
    fun `video mode opens a video session with an upright preview`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        vm.setMode(CaptureMode.VIDEO)
        assertThat(vm.uiState.value.previewSize).isEqualTo(PixelSize(1080, 1920))
        assertThat(controller.videoOpened).isEmpty()

        vm.onSurfaceReady(Target(1080, 1920))
        assertThat(controller.videoOpened).containsExactly(VideoSpec(VideoKind.STANDARD))
    }

    @Test
    fun `each video mode gets its own session and photo mode goes back to stills`() = runTest {
        val vm = viewModel()
        vm.setMode(CaptureMode.VIDEO)
        vm.onSurfaceReady(Target(1080, 1920))
        vm.setMode(CaptureMode.CINEMATIC)
        vm.setMode(CaptureMode.TIMELAPSE)
        assertThat(controller.videoOpened.map { it.kind })
            .containsExactly(VideoKind.STANDARD, VideoKind.CINEMATIC, VideoKind.TIMELAPSE).inOrder()

        vm.setMode(CaptureMode.PHOTO)
        vm.onSurfaceReady(Target(1440, 1080))
        assertThat(controller.opened).hasSize(4)
        assertThat(controller.videoOpened).hasSize(3)
    }

    @Test
    fun `the shutter starts and stops a recording and saves it`() = runTest {
        val vm = recordingViewModel()
        assertThat(vm.uiState.value.recording).isTrue()
        assertThat(controller.recordingActive).isTrue()

        vm.onShutter()
        assertThat(vm.uiState.value.recording).isFalse()
        assertThat(controller.recordingActive).isFalse()
        assertThat(media.finishedVideos).containsExactly(true)
        assertThat(vm.uiState.value.event).isEqualTo(CameraEvent.Saved("content://video/1"))
        assertThat(vm.uiState.value.lastCaptureUri).isEqualTo("content://video/1")
    }

    @Test
    fun `a recording with nothing in it is discarded`() = runTest {
        val vm = recordingViewModel()
        controller.stopResult = false
        vm.onShutter()
        assertThat(media.finishedVideos).containsExactly(false)
        assertThat(vm.uiState.value.event).isEqualTo(CameraEvent.RecordingFailed)
    }

    @Test
    fun `a recording that fails to start leaves no file behind`() = runTest {
        controller.failRecording = true
        val vm = viewModel()
        vm.setMode(CaptureMode.VIDEO)
        vm.onSurfaceReady(Target(1080, 1920))
        vm.onShutter()
        assertThat(vm.uiState.value.recording).isFalse()
        assertThat(media.finishedVideos).containsExactly(false)
        assertThat(vm.uiState.value.event).isEqualTo(CameraEvent.RecordingFailed)
    }

    @Test
    fun `going to the background while recording saves the video first`() = runTest {
        val vm = recordingViewModel()
        vm.onForegroundChanged(false)
        assertThat(media.finishedVideos).containsExactly(true)
        assertThat(controller.state.value).isEqualTo(CameraState.Closed)
    }

    @Test
    fun `losing the camera while recording keeps what was filmed`() = runTest {
        val vm = recordingViewModel()
        controller.state.value = CameraState.Error(CameraError.CAMERA_IN_USE)
        assertThat(media.finishedVideos).containsExactly(true)
        assertThat(vm.uiState.value.recording).isFalse()
    }

    @Test
    fun `mode, camera and lens stay put while recording`() = runTest {
        controller.all = listOf(
            back.copy(equivalentFocalLengthMm = 26f, maxDigitalZoom = 4f),
            backWide.copy(equivalentFocalLengthMm = 13f),
            front,
        )
        val vm = recordingViewModel()
        val opens = controller.opened.size
        vm.setMode(CaptureMode.PHOTO)
        vm.switchCamera()
        vm.selectZoomPreset(vm.uiState.value.zoomPresets.first { it.cameraId == backWide.cameraId })
        assertThat(vm.uiState.value.mode).isEqualTo(CaptureMode.VIDEO)
        assertThat(controller.opened).hasSize(opens)
        assertThat(vm.uiState.value.recording).isTrue()
    }

    @Test
    fun `video frames get the chosen look and the live enhancement`() = runTest {
        val vm = viewModel()
        vm.setLook(ShotLook.VIVID)
        val (look, enhance) = controller.videoFilters.last()
        assertThat(look.toList()).isEqualTo(LookMatrix.forLook(ShotLook.VIVID).toList())
        assertThat(enhance).isNotNull()

        vm.toggleEnhance()
        assertThat(controller.videoFilters.last().second).isNull()
    }

    @Test
    fun `4k is offered only where supported and reopens the session`() = runTest {
        controller.all = listOf(backWide, back.copy(videoResolutions = setOf(VideoResolution.FHD, VideoResolution.UHD)), front)
        val vm = viewModel()
        vm.setMode(CaptureMode.VIDEO)
        vm.onSurfaceReady(Target(1080, 1920))
        assertThat(vm.uiState.value.showResolutionControl).isTrue()

        vm.toggleVideoResolution()
        assertThat(controller.videoOpened.last().resolution).isEqualTo(VideoResolution.UHD)

        vm.setMode(CaptureMode.PHOTO)
        assertThat(vm.uiState.value.showResolutionControl).isFalse()
    }

    @Test
    fun `in video the flash is a light that is on or off`() = runTest {
        controller.all = listOf(backWide, back.copy(hasFlash = true), front)
        val vm = viewModel()
        vm.setMode(CaptureMode.VIDEO)
        vm.cycleFlash()
        assertThat(vm.uiState.value.cameraSettings.flash).isEqualTo(FlashMode.ON)
        vm.cycleFlash()
        assertThat(vm.uiState.value.cameraSettings.flash).isEqualTo(FlashMode.OFF)
    }

    /** A view model in Video mode with a recording running. */
    private fun recordingViewModel(): CameraViewModel {
        val vm = viewModel()
        vm.setMode(CaptureMode.VIDEO)
        vm.onSurfaceReady(Target(1080, 1920))
        vm.onShutter()
        return vm
    }

    @Test
    fun `a camera taken away is reopened`() = runTest {
        val vm = viewModel()
        vm.onSurfaceReady(Target(1440, 1080))
        controller.state.value = CameraState.Error(CameraError.CAMERA_DISABLED)
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(controller.opened).hasSize(2)
        assertThat(vm.uiState.value.cameraState).isInstanceOf(CameraState.Previewing::class.java)
    }

    private class FakeSettingsRepository : SettingsRepository {
        val state = MutableStateFlow(CameraSettings())
        override val settings = state
        override suspend fun update(transform: (CameraSettings) -> CameraSettings) = state.update(transform)
        override suspend fun reset() {
            state.value = CameraSettings()
        }
    }

    private data class Target(override val width: Int, override val height: Int) : PreviewTarget

    private class FakeCameraController(var all: List<CameraCapabilities>) : CameraController {
        override val state = MutableStateFlow<CameraState>(CameraState.Closed)
        override val histogram = MutableStateFlow<Histogram?>(null)
        val opened = mutableListOf<String>()
        val openedWithRaw = mutableListOf<Boolean>()
        val capturedWithRaw = mutableListOf<Boolean>()
        val applied = mutableListOf<ManualSettings>()
        val flashModes = mutableListOf<FlashMode>()
        val previewAspects = mutableListOf<StillAspect>()
        val openedWithAspect = mutableListOf<StillAspect>()
        var mustPlayShutterSound = false

        override suspend fun cameras() = all
        override suspend fun deviceProfile() = DeviceProfile(all, mustPlayShutterSound = mustPlayShutterSound)
        val openedWithNightExtension = mutableListOf<Boolean>()
        val bursts = mutableListOf<Int>()
        override suspend fun previewSize(cameraId: String, aspect: StillAspect, nightExtension: Boolean): PixelSize {
            previewAspects += aspect
            return if (aspect == StillAspect.FOUR_THREE) PixelSize(1440, 1080) else PixelSize(1920, 1080)
        }
        override suspend fun open(
            cameraId: String,
            target: PreviewTarget,
            rawEnabled: Boolean,
            aspect: StillAspect,
            nightExtension: Boolean,
        ) {
            opened += cameraId
            openedWithNightExtension += nightExtension
            openedWithRaw += rawEnabled
            openedWithAspect += aspect
            state.value = CameraState.Previewing(all.first { it.cameraId == cameraId }, ManualSettings.AUTO, null)
        }
        override suspend fun setFlashMode(mode: FlashMode) {
            flashModes += mode
        }
        override suspend fun applySettings(settings: ManualSettings) {
            applied += settings
        }
        val focused = mutableListOf<Boolean>()
        val zooms = mutableListOf<Float>()
        val macros = mutableListOf<Boolean>()
        override val focusTarget = MutableStateFlow<FocusTarget?>(null)
        override suspend fun focusAt(x: Float, y: Float, track: Boolean) {
            focused += track
        }
        override suspend fun setZoom(ratio: Float) {
            zooms += ratio
        }
        override suspend fun setMacro(enabled: Boolean) {
            macros += enabled
        }
        override suspend fun capture(deviceRotationDegrees: Int, includeRaw: Boolean): CaptureBundle {
            capturedWithRaw += includeRaw
            val jpeg = CapturedImage(ByteArray(1), ImageFormat.JPEG, PixelSize(4000, 3000), 0L)
            val raw = CapturedImage(ByteArray(1), ImageFormat.DNG, PixelSize(4000, 3000), 0L).takeIf { includeRaw }
            return CaptureBundle(jpeg, raw)
        }
        override suspend fun captureBurst(deviceRotationDegrees: Int, frames: Int): List<CapturedImage> {
            bursts += frames
            return List(frames) { CapturedImage(ByteArray(1), ImageFormat.JPEG, PixelSize(4000, 3000), 0L) }
        }
        val videoOpened = mutableListOf<VideoSpec>()
        val videoFilters = mutableListOf<Pair<FloatArray, EnhanceParams?>>()
        var recordingActive = false
        var stopResult = true
        var failRecording = false
        override suspend fun videoPreviewSize(cameraId: String, spec: VideoSpec) = PixelSize(1080, 1920)
        override suspend fun openVideo(cameraId: String, target: PreviewTarget, spec: VideoSpec) {
            opened += cameraId
            videoOpened += spec
            state.value = CameraState.Previewing(all.first { it.cameraId == cameraId }, ManualSettings.AUTO, null)
        }
        override fun setVideoFilter(look: FloatArray, enhance: EnhanceParams?) {
            videoFilters += look to enhance
        }
        override suspend fun startRecording(output: FileDescriptor, deviceRotationDegrees: Int) {
            if (failRecording) error("Recorder didn't start")
            recordingActive = true
        }
        override suspend fun stopRecording(): Boolean {
            recordingActive = false
            return stopResult
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
