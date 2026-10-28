package com.camoron.data.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat as AndroidImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.system.Os
import android.system.OsConstants
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import android.view.Surface
import com.camoron.core.common.DefaultDispatcher
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.common.SurfacePreviewTarget
import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraError
import com.camoron.domain.model.CameraExtension
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.DeviceProfile
import com.camoron.domain.model.EnhanceParams
import com.camoron.domain.model.FlashMode
import com.camoron.domain.model.FocusTarget
import com.camoron.domain.model.CaptureBundle
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.Histogram
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.LiveExposure
import com.camoron.domain.model.LookMatrix
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.ShotLook
import com.camoron.domain.model.StillAspect
import com.camoron.domain.model.VideoMath
import com.camoron.domain.model.VideoResolution
import com.camoron.domain.model.VideoSpec
import com.camoron.domain.model.WhiteBalance
import com.camoron.domain.model.WhiteBalancePreset
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.PreviewTarget
import com.camoron.domain.usecase.SanitizeManualSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Camera2 implementation of [CameraController]. All Camera2 callbacks run on one dedicated
 * thread; public methods are serialised with a mutex so open/close/capture never interleave.
 */
@Singleton
class Camera2Controller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mapper: CapabilityMapper,
    private val sanitize: SanitizeManualSettings,
    @DefaultDispatcher private val processing: CoroutineDispatcher,
) : CameraController {

    private val manager = context.getSystemService(CameraManager::class.java)

    // Reading a camera's characteristics goes through the camera service; they never change, so
    // read each camera's once instead of on every switch.
    private val characteristicsCache = java.util.concurrent.ConcurrentHashMap<String, CameraCharacteristics>()
    private val capabilitiesCache = java.util.concurrent.ConcurrentHashMap<String, CameraCapabilities>()

    private fun characteristicsOf(cameraId: String): CameraCharacteristics =
        characteristicsCache.getOrPut(cameraId) { manager.getCameraCharacteristics(cameraId) }

    private fun capabilitiesOf(cameraId: String): CameraCapabilities =
        capabilitiesCache.getOrPut(cameraId) { withOpticalZoom(mapper.map(cameraId, characteristicsOf(cameraId)), cameraId) }

    /** Adds the zoom ratios a multi-lens camera covers with its own longer lenses (Android 9+). */
    private fun withOpticalZoom(caps: CameraCapabilities, cameraId: String): CameraCapabilities {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return caps
        val main = caps.equivalentFocalLengthMm ?: return caps
        val ratios = characteristicsOf(cameraId).physicalCameraIds.mapNotNull { id ->
            runCatching {
                val physical = characteristicsOf(id)
                val focal = physical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
                val size = physical.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                if (focal == null || size == null) null else CameraMath.equivalentFocalLength(focal, kotlin.math.hypot(size.width, size.height)) / main
            }.getOrNull()
        }
        return caps.copy(opticalZoomRatios = CameraMath.opticalZoomSteps(ratios))
    }
    private val thread = HandlerThread("CamoronCamera").apply { start() }
    private val handler = Handler(thread.looper)

    private val executor = Executor { handler.post(it) }
    private val mutex = Mutex()

    /**
     * Runs a camera operation one at a time and off the main thread: opening, reconfiguring and
     * closing the camera can each take a noticeable moment, which froze the screen when done there.
     */
    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(processing) { mutex.withLock { block() } }

    private val _state = MutableStateFlow<CameraState>(CameraState.Closed)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _histogram = MutableStateFlow<Histogram?>(null)
    override val histogram: StateFlow<Histogram?> = _histogram.asStateFlow()
    private var lastHistogramAt = 0L

    @Volatile
    private var active: ActiveCamera? = null

    private val _focusTarget = MutableStateFlow<FocusTarget?>(null)
    override val focusTarget: StateFlow<FocusTarget?> = _focusTarget.asStateFlow()

    /** Survives reopening, like [flashMode]; only honoured on cameras that support macro. */
    @Volatile
    private var macroEnabled = false

    /** Survives reopening, so switching camera or aspect keeps the user's flash choice. */
    @Volatile
    private var flashMode: FlashMode = FlashMode.OFF

    /** Set while a still capture waits for the AE precapture sequence to settle. */
    @Volatile
    private var precapture: PrecaptureWaiter? = null

    private class PrecaptureWaiter {
        val done = CompletableDeferred<Unit>()
        var sawPrecapture = false
    }

    /**
     * The phone maker's own mode (Night or HDR), open instead of [active]. It runs its own pipeline,
     * so focus, zoom, flash and manual controls don't apply while it is open.
     */
    @Volatile
    private var activeExtension: ExtensionCamera? = null

    private class ExtensionCamera(
        val extension: CameraExtension,
        val capabilities: CameraCapabilities,
        val sensorOrientation: Int,
        val device: CameraDevice,
        /** A CameraExtensionSession; typed loosely so this class loads before Android 12. */
        val session: AutoCloseable,
        val previewSurface: Surface,
        val jpegReader: ImageReader,
    ) {
        val isFront: Boolean get() = capabilities.lensFacing == LensFacing.FRONT
    }

    /** Everything tied to one open camera; discarded as a unit on close. */
    private class ActiveCamera(
        val capabilities: CameraCapabilities,
        val characteristics: CameraCharacteristics,
        val device: CameraDevice,
        val session: CameraCaptureSession,
        /** Where the camera streams for preview: the screen, or the video renderer's texture. */
        val previewSurface: Surface,
        /** Absent in a video session, which has no still output. */
        val jpegReader: ImageReader?,
        val rawReader: ImageReader?,
        val analysisReader: ImageReader?,
        val video: VideoSession? = null,
    ) {
        var settings: ManualSettings = ManualSettings.AUTO
        @Volatile
        var focusRegion: MeteringRectangle? = null

        /** Zoom ratio, or the digital crop factor before Android 11. */
        @Volatile
        var zoomRatio: Float = 1f

        /** The user asked to follow the tapped subject; continuous AF on a moving region. */
        @Volatile
        var trackingWanted = false

        // Touched only on the camera thread.
        val tracker = SubjectTracker()
        var pendingTrack: Pair<Float, Float>? = null
        var lastTrackAt = 0L
        var lastTrackCenter: Pair<Float, Float>? = null
        var lastExposure: LiveExposure? = null
        var lastColorTransform: ColorSpaceTransform? = null
        var lastPublishedAt: Long = 0L

        val sensorOrientation: Int = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val activeArray: Rect? = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val afModes: Set<Int> = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        val maxAeRegions: Int = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

        /** Zoom through CONTROL_ZOOM_RATIO (Android 11+), which also switches lenses on multi-camera phones. */
        val usesZoomRatio: Boolean get() = capabilities.zoomRatioRange != null
        val isFront: Boolean get() = capabilities.lensFacing == LensFacing.FRONT

        /** False once the device reported an error or disconnect, so it is never reused. */
        @Volatile
        var healthy = true

        /** Last auto-exposure state from the preview, to know whether the flash would fire. */
        @Volatile
        var lastAeState: Int? = null

        val edgeModes: Set<Int> = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet().orEmpty()

        fun closeReaders() {
            runCatching { jpegReader?.close() }
            runCatching { rawReader?.close() }
            runCatching { analysisReader?.close() }
        }
    }

    /** How an open video session was set up. */
    private class VideoSession(
        val spec: VideoSpec,
        /** Camera stream and encoded video size (landscape). */
        val size: PixelSize,
        /** The camera frame-rate range in use, or null for the camera's default. */
        val fpsRange: IntRange?,
        /** The picture is turned a quarter turn in the encoded frame (sideways sensor). */
        val turned: Boolean,
        val renderer: VideoRenderer,
        val stabilization: Boolean,
    )

    /** A recording in progress. Kept apart from the session so it can be finished if the camera is lost. */
    private class Recording(val recorder: MediaRecorder, val renderer: VideoRenderer)

    @Volatile
    private var recording: Recording? = null

    /** The video renderer, released on close even when the camera itself was taken away. */
    @Volatile
    private var renderer: VideoRenderer? = null

    /** Look and enhancement for video frames; survives reopening. */
    @Volatile
    private var videoLook: FloatArray = LookMatrix.forLook(ShotLook.NORMAL)

    @Volatile
    private var videoEnhance: EnhanceParams? = null

    // Camera queries can take hundreds of milliseconds on some phones, so keep them off the UI thread.
    override suspend fun cameras(): List<CameraCapabilities> = withContext(processing) {
        manager.cameraIdList.mapNotNull { id ->
            runCatching { withVideoEncoderLimits(capabilitiesOf(id)).copy(extensions = extensionsFor(id)) }
                .onFailure { Log.w(TAG, "Skipping camera $id", it) }
                .getOrNull()
        }
    }

    override suspend fun deviceProfile(): DeviceProfile = withContext(processing) {
        val cameras = cameras()
        val facingById = cameras.associate { it.cameraId to it.lensFacing }
        val concurrent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                manager.concurrentCameraIds.any { ids ->
                    val facings = ids.mapNotNull(facingById::get)
                    LensFacing.FRONT in facings && LensFacing.BACK in facings
                }
            }.getOrDefault(false)
        } else {
            false
        }
        val mustPlayShutter = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            MediaActionSound.mustPlayShutterSound()
        DeviceProfile(cameras, supportsConcurrentFrontBack = concurrent, mustPlayShutterSound = mustPlayShutter)
    }

    /** Extension lookups are slow and never change while the app runs. */
    private val extensionCache = java.util.concurrent.ConcurrentHashMap<String, Set<CameraExtension>>()

    /** Phone-maker extensions (Night, Bokeh, ...) exposed through Camera2 on Android 12+. */
    private fun extensionsFor(cameraId: String): Set<CameraExtension> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptySet()
        return extensionCache.getOrPut(cameraId) { loadExtensions(cameraId) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun loadExtensions(cameraId: String): Set<CameraExtension> {
        return runCatching {
            manager.getCameraExtensionCharacteristics(cameraId).supportedExtensions.mapNotNull { ext ->
                when (ext) {
                    CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> CameraExtension.AUTO
                    EXTENSION_FACE_RETOUCH -> CameraExtension.FACE_RETOUCH
                    CameraExtensionCharacteristics.EXTENSION_BOKEH -> CameraExtension.BOKEH
                    CameraExtensionCharacteristics.EXTENSION_HDR -> CameraExtension.HDR
                    CameraExtensionCharacteristics.EXTENSION_NIGHT -> CameraExtension.NIGHT
                    else -> null
                }
            }.toSet()
        }.getOrDefault(emptySet())
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun extensionId(extension: CameraExtension): Int = when (extension) {
        CameraExtension.AUTO -> CameraExtensionCharacteristics.EXTENSION_AUTOMATIC
        CameraExtension.FACE_RETOUCH -> EXTENSION_FACE_RETOUCH
        CameraExtension.BOKEH -> CameraExtensionCharacteristics.EXTENSION_BOKEH
        CameraExtension.HDR -> CameraExtensionCharacteristics.EXTENSION_HDR
        CameraExtension.NIGHT -> CameraExtensionCharacteristics.EXTENSION_NIGHT
    }

    override suspend fun previewSize(cameraId: String, aspect: StillAspect, extension: CameraExtension?): PixelSize =
        withContext(processing) { previewSizeBlocking(cameraId, aspect, extension) }

    private fun previewSizeBlocking(cameraId: String, aspect: StillAspect, extension: CameraExtension?): PixelSize {
        val characteristics = characteristicsOf(cameraId)
        val caps = capabilitiesOf(cameraId)
        val ratio = effectiveAspect(caps, aspect).ratio
        if (extension != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            extensionPreviewSizes(cameraId, extension).takeIf { it.isNotEmpty() }?.let { return CameraMath.choosePreviewSize(it, ratio) }
        }
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.map { PixelSize(it.width, it.height) }
            .orEmpty()
        return CameraMath.choosePreviewSize(sizes, ratio)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun extensionPreviewSizes(cameraId: String, extension: CameraExtension): List<PixelSize> = runCatching {
        val id = extensionId(extension)
        val ext = manager.getCameraExtensionCharacteristics(cameraId)
        if (id !in ext.supportedExtensions) return emptyList()
        ext.getExtensionSupportedSizes(id, SurfaceTexture::class.java)
            .map { PixelSize(it.width, it.height) }
    }.getOrDefault(emptyList())

    /** Falls back to 4:3 when the sensor has no large native output in the requested shape. */
    private fun effectiveAspect(caps: CameraCapabilities, aspect: StillAspect): StillAspect =
        if (aspect in caps.stillAspects) aspect else StillAspect.FOUR_THREE

    override suspend fun open(
        cameraId: String,
        target: PreviewTarget,
        rawEnabled: Boolean,
        aspect: StillAspect,
        extension: CameraExtension?,
    ) {
        locked {
            val surface = (target as? SurfacePreviewTarget)?.surface
                ?: error("Camera2Controller needs a SurfacePreviewTarget")
            if (extension != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                openExtensionLocked(cameraId, surface, aspect, extension)
            ) {
                return@locked
            }
            openLocked(cameraId, surface, rawEnabled, aspect)
        }
    }

    /**
     * Opens the phone maker's [extension] (Night or HDR). Returns false (with everything released)
     * when the camera doesn't offer it or it fails to start, so the caller can fall back to a normal
     * session.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun openExtensionLocked(
        cameraId: String,
        surface: Surface,
        aspect: StillAspect,
        extension: CameraExtension,
    ): Boolean {
        closeLocked()
        _state.value = CameraState.Opening(cameraId)
        var device: CameraDevice? = null
        var reader: ImageReader? = null
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val caps = mapper.map(cameraId, characteristics).copy(extensions = extensionsFor(cameraId))
            if (extension !in caps.extensions) return false
            val id = extensionId(extension)
            val ext = manager.getCameraExtensionCharacteristics(cameraId)
            val jpegSize = ext.getExtensionSupportedSizes(id, AndroidImageFormat.JPEG)
                .map { PixelSize(it.width, it.height) }
                .takeIf { it.isNotEmpty() }
                ?.let { CameraMath.chooseStillSize(it, effectiveAspect(caps, aspect), MAX_STILL_PIXELS) }
                ?: return false
            device = openDevice(cameraId)
            val jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, AndroidImageFormat.JPEG, MAX_IMAGES)
            reader = jpegReader
            val session = createExtensionSession(device, surface, jpegReader.surface, id)
            val opened = ExtensionCamera(
                extension = extension,
                capabilities = caps,
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                device = device,
                session = session,
                previewSurface = surface,
                jpegReader = jpegReader,
            )
            activeExtension = opened
            DiagnosticLog.info(TAG, "Opened the phone's $extension mode on camera $cameraId, photos ${jpegSize.width}x${jpegSize.height}")
            val preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build()
            session.setRepeatingRequest(preview, executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
            _state.value = CameraState.Previewing(caps, ManualSettings.AUTO, liveExposure = null)
            return true
        } catch (e: CancellationException) {
            releaseFailedExtension(device, reader)
            _state.value = CameraState.Closed
            throw e
        } catch (e: Exception) {
            DiagnosticLog.record(TAG, "The phone's $extension mode didn't start on camera $cameraId; using the normal camera", e)
            releaseFailedExtension(device, reader)
            return false
        }
    }

    private fun releaseFailedExtension(device: CameraDevice?, reader: ImageReader?) {
        activeExtension?.let { runCatching { it.session.close() } }
        activeExtension = null
        runCatching { reader?.close() }
        runCatching { device?.close() }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun createExtensionSession(device: CameraDevice, preview: Surface, jpeg: Surface, id: Int): CameraExtensionSession =
        suspendCancellableCoroutine { cont ->
            device.createExtensionSession(
                ExtensionSessionConfiguration(
                    id,
                    listOf(OutputConfiguration(preview), OutputConfiguration(jpeg)),
                    executor,
                    object : CameraExtensionSession.StateCallback() {
                        override fun onConfigured(session: CameraExtensionSession) {
                            if (cont.isActive) cont.resume(session) else session.close()
                        }

                        override fun onConfigureFailed(session: CameraExtensionSession) {
                            if (cont.isActive) cont.resumeWithException(IllegalStateException("Extension session failed"))
                        }
                    },
                ),
            )
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun captureExtension(opened: ExtensionCamera, deviceRotationDegrees: Int): CaptureBundle {
        val session = opened.session as CameraExtensionSession
        val reader = opened.jpegReader
        reader.acquireLatestImage()?.close()
        val jpegBytes = CompletableDeferred<ByteArray>()
        reader.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use { image ->
                val buffer = image.planes[0].buffer
                jpegBytes.complete(ByteArray(buffer.remaining()).also(buffer::get))
            }
        }, handler)
        try {
            val request = opened.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
                set(
                    CaptureRequest.JPEG_ORIENTATION,
                    CameraMath.jpegOrientation(opened.sensorOrientation, deviceRotationDegrees, opened.isFront),
                )
            }.build()
            session.capture(request, executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                    jpegBytes.completeExceptionally(IllegalStateException("${opened.extension} capture failed"))
                }
            })
            // The maker's pipeline takes several exposures and merges them, which can take seconds.
            val bytes = withTimeoutOrNull(EXTENSION_CAPTURE_TIMEOUT_MS) { jpegBytes.await() }
                ?: error("${opened.extension} capture timed out")
            val primary = CapturedImage(
                bytes = bytes,
                format = ImageFormat.JPEG,
                size = PixelSize(reader.width, reader.height),
                timestampMillis = System.currentTimeMillis(),
            )
            return CaptureBundle(primary, raw = null)
        } finally {
            reader.setOnImageAvailableListener(null, null)
        }
    }

    override suspend fun videoPreviewSize(cameraId: String, spec: VideoSpec): PixelSize = withContext(processing) {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val size = videoSize(characteristics, spec.resolution)
        VideoMath.previewSize(size, isTurned(characteristics))
    }

    override suspend fun openVideo(cameraId: String, target: PreviewTarget, spec: VideoSpec) {
        locked {
            val surface = (target as? SurfacePreviewTarget)?.surface
                ?: error("Camera2Controller needs a SurfacePreviewTarget")
            openVideoLocked(cameraId, surface, PixelSize(target.width, target.height), spec)
        }
    }

    private suspend fun openVideoLocked(cameraId: String, surface: Surface, previewSize: PixelSize, spec: VideoSpec) {
        val reused = closeLocked(reuseFor = cameraId)
        _state.value = CameraState.Opening(cameraId)
        var device: CameraDevice? = null
        var analysisReader: ImageReader? = null
        try {
            val characteristics = characteristicsOf(cameraId)
            val caps = withVideoEncoderLimits(capabilitiesOf(cameraId))
            val resolution = spec.resolution.takeIf { it in caps.videoResolutions } ?: VideoResolution.FHD
            val size = videoSize(characteristics, resolution)
            val turned = isTurned(characteristics)
            val fpsRange = VideoMath.chooseFpsRange(
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.map { it.lower..it.upper }.orEmpty(),
                spec.kind,
            )
            val realtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val stabilization = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

            val videoRenderer = withContext(processing) {
                VideoRenderer(surface, previewSize, size, turned, realtime).also { renderer = it }.let { it to it.start() }
            }
            videoRenderer.first.setFilter(videoLook, videoEnhance)
            device = reused ?: openDevice(cameraId)

            // The analysis stream feeds the histogram (for Enhance) and subject tracking. If the
            // camera can't add it next to a large video stream, film without it.
            val streams = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            analysisReader = streams?.getOutputSizes(AndroidImageFormat.YUV_420_888)
                ?.map { PixelSize(it.width, it.height) }
                ?.let(CameraMath::chooseAnalysisSize)
                ?.let {
                    ImageReader.newInstance(it.width, it.height, AndroidImageFormat.YUV_420_888, ANALYSIS_IMAGES)
                        .apply { setOnImageAvailableListener(::onAnalysisFrame, handler) }
                }
            val cameraSurface = videoRenderer.second
            val session = try {
                createSession(device, listOfNotNull(cameraSurface, analysisReader?.surface))
            } catch (e: IllegalStateException) {
                if (analysisReader == null) throw e
                DiagnosticLog.record(TAG, "Filming without the analysis stream", e)
                analysisReader?.close()
                analysisReader = null
                createSession(device, listOf(cameraSurface))
            }
            val video = VideoSession(spec.copy(resolution = resolution), size, fpsRange, turned, videoRenderer.first, stabilization)
            val camera = ActiveCamera(caps, characteristics, device, session, cameraSurface, null, null, analysisReader, video)
            active = camera
            startRepeating(camera)
            _state.value = CameraState.Previewing(caps, camera.settings, liveExposure = null)
        } catch (e: Exception) {
            DiagnosticLog.record(TAG, "Failed to open camera $cameraId for video", e)
            runCatching { analysisReader?.close() }
            (device ?: reused)?.close()
            active = null
            renderer?.let { withContext(nonCancellableProcessing) { it.release() } }
            renderer = null
            if (e is CancellationException) {
                _state.value = CameraState.Closed
                throw e
            }
            _state.value = CameraState.Error(e.toCameraError())
        }
    }

    /** The recording size: the requested one, else the largest 16:9 stream up to 1080p. */
    private fun videoSize(characteristics: CameraCharacteristics, resolution: VideoResolution): PixelSize {
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.map { PixelSize(it.width, it.height) }
            .orEmpty()
        if (resolution.size in sizes) return resolution.size
        return sizes.filter { abs(it.width * 9 - it.height * 16) <= it.height && it.width <= VideoResolution.FHD.width }
            .maxByOrNull { it.width }
            ?: sizes.filter { it.width <= VideoResolution.FHD.width }.maxByOrNull { it.width.toLong() * it.height }
            ?: VideoResolution.FHD.size
    }

    private fun isTurned(characteristics: CameraCharacteristics): Boolean =
        (characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) % 180 != 0

    /** Drops 4K where the phone's H.264 encoder can't take it at 30 fps. */
    private fun withVideoEncoderLimits(caps: CameraCapabilities): CameraCapabilities {
        if (VideoResolution.UHD !in caps.videoResolutions || encoderHandles4k) return caps
        return caps.copy(videoResolutions = caps.videoResolutions - VideoResolution.UHD)
    }

    private val encoderHandles4k: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in info.supportedTypes &&
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                        ?.areSizeAndRateSupported(VideoResolution.UHD.width, VideoResolution.UHD.height, 30.0) == true
            }
        }.getOrDefault(false)
    }

    override fun setVideoFilter(look: FloatArray, enhance: EnhanceParams?) {
        videoLook = look
        videoEnhance = enhance
        renderer?.setFilter(look, enhance)
    }

    override suspend fun startRecording(output: FileDescriptor, deviceRotationDegrees: Int) {
        locked {
            check(recording == null) { "Already recording" }
            val camera = active ?: error("Camera is not open")
            val video = camera.video ?: error("The camera is not open for video")
            val hint = VideoMath.orientationHint(deviceRotationDegrees, camera.isFront, video.turned)
            val sound = video.spec.kind.recordsSound &&
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val recorder = withContext(processing) { startRecorder(output, video, hint, sound) }
            try {
                withContext(processing) { video.renderer.startEncoding(recorder.surface, VideoMath.pacerFor(video.spec, video.fpsRange)) }
            } catch (e: Exception) {
                runCatching { recorder.stop() }
                recorder.release()
                throw e
            }
            recording = Recording(recorder, video.renderer)
        }
    }

    /**
     * Starts a recorder, with stereo sound if the microphones allow it, else mono, else silent, so
     * a phone with an unusual microphone setup still records.
     */
    @SuppressLint("MissingPermission") // Sound is only requested once RECORD_AUDIO is confirmed granted.
    private fun startRecorder(output: FileDescriptor, video: VideoSession, hint: Int, sound: Boolean): MediaRecorder {
        val channelOptions = if (sound) listOf(2, 1, 0) else listOf(0)
        var failure: Exception? = null
        for (channels in channelOptions) {
            val recorder = newRecorder()
            try {
                // A failed attempt may have written a header; start the file afresh.
                runCatching {
                    Os.lseek(output, 0, OsConstants.SEEK_SET)
                    Os.ftruncate(output, 0)
                }
                if (channels > 0) recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setOutputFile(output)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setVideoSize(video.size.width, video.size.height)
                recorder.setVideoFrameRate(video.spec.kind.outputFps)
                recorder.setVideoEncodingBitRate(VideoMath.bitrate(video.size, video.spec.kind.outputFps))
                if (channels > 0) {
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                    recorder.setAudioChannels(channels)
                    recorder.setAudioEncodingBitRate(AUDIO_BITRATE_PER_CHANNEL * channels)
                }
                recorder.setOrientationHint(hint)
                recorder.prepare()
                recorder.start()
                return recorder
            } catch (e: Exception) {
                DiagnosticLog.record(TAG, "Recorder didn't start with $channels sound channels", e)
                recorder.release()
                failure = e
            }
        }
        throw failure ?: IllegalStateException("Recorder didn't start")
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    override suspend fun stopRecording(): Boolean = locked { stopRecordingLocked() }

    private suspend fun stopRecordingLocked(): Boolean {
        val current = recording ?: return false
        recording = null
        return withContext(nonCancellableProcessing) {
            runCatching { current.renderer.stopEncoding() }
                .onFailure { DiagnosticLog.record(TAG, "Couldn't detach the encoder", it) }
            // stop() throws when no frame was recorded, which leaves an unusable file.
            val ok = runCatching { current.recorder.stop() }
                .onFailure { DiagnosticLog.record(TAG, "Recording had nothing to save", it) }
                .isSuccess
            current.recorder.release()
            ok
        }
    }

    /** Off the caller's thread, and finishing even if the caller is cancelled, so files are never left half-written. */
    private val nonCancellableProcessing get() = processing + kotlinx.coroutines.NonCancellable

    override suspend fun setFlashMode(mode: FlashMode) {
        locked {
            flashMode = mode
            active?.let(::startRepeating)
        }
    }

    private suspend fun openLocked(cameraId: String, surface: Surface, rawEnabled: Boolean, aspect: StillAspect) {
        val reused = closeLocked(reuseFor = cameraId)
        _state.value = CameraState.Opening(cameraId)

        var device: CameraDevice? = null
        val readers = mutableListOf<ImageReader>()
        val started = SystemClock.elapsedRealtime()
        try {
            val characteristics = characteristicsOf(cameraId)
            val caps = capabilitiesOf(cameraId)
            val streams = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            device = reused ?: openDevice(cameraId)

            val jpegSizes = streams?.getOutputSizes(AndroidImageFormat.JPEG)?.map { PixelSize(it.width, it.height) }
            val jpegSize = jpegSizes?.takeIf { it.isNotEmpty() }
                ?.let { CameraMath.chooseStillSize(it, effectiveAspect(caps, aspect), MAX_STILL_PIXELS) }
                ?: caps.maxJpegSize

            // Camera2 guarantees preview + one full-size output + one more stream: RAW when the user
            // wants DNGs, otherwise a small YUV stream for the histogram.
            val rawSize = streams?.getOutputSizes(AndroidImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width.toLong() * it.height }
                ?.takeIf { rawEnabled && caps.supportsRaw }
            val analysisSize = if (rawSize == null) {
                streams?.getOutputSizes(AndroidImageFormat.YUV_420_888)
                    ?.map { PixelSize(it.width, it.height) }
                    ?.let(CameraMath::chooseAnalysisSize)
            } else {
                null
            }
            val analysisReader = analysisSize?.let {
                ImageReader.newInstance(it.width, it.height, AndroidImageFormat.YUV_420_888, ANALYSIS_IMAGES)
                    .also(readers::add)
                    .apply { setOnImageAvailableListener(::onAnalysisFrame, handler) }
            }

            // Photos are the camera's own fully processed JPEG: the maker's noise reduction,
            // sharpening and tuning, which our optimisation then only touches up.
            val jpegReader = ImageReader.newInstance(
                jpegSize.width,
                jpegSize.height,
                AndroidImageFormat.JPEG,
                MAX_IMAGES,
            ).also(readers::add)
            val rawReader = rawSize?.let {
                ImageReader.newInstance(it.width, it.height, AndroidImageFormat.RAW_SENSOR, MAX_IMAGES).also(readers::add)
            }
            val session = createSession(device, listOfNotNull(surface, jpegReader.surface, rawReader?.surface, analysisReader?.surface))
            val camera = ActiveCamera(caps, characteristics, device, session, surface, jpegReader, rawReader, analysisReader)
            active = camera
            startRepeating(camera)
            if (macroEnabled && caps.supportsMacro && caps.supportsMacroAf) triggerFocus(camera)
            _state.value = CameraState.Previewing(caps, camera.settings, liveExposure = null)
            DiagnosticLog.info(
                TAG,
                "Opened camera $cameraId in ${SystemClock.elapsedRealtime() - started} ms: asked $aspect, " +
                    "photos ${jpegSize.width}x${jpegSize.height}, " +
                    "analysis ${analysisSize?.let { "${it.width}x${it.height}" }}, " +
                    "maker modes ${caps.extensions.ifEmpty { "none" }}",
            )
        } catch (e: Exception) {
            DiagnosticLog.record(TAG, "Failed to open camera $cameraId", e)
            readers.forEach { runCatching { it.close() } }
            (device ?: reused)?.close()
            active = null
            if (e is CancellationException) {
                _state.value = CameraState.Closed
                throw e
            }
            _state.value = CameraState.Error(e.toCameraError())
        }
    }

    /** Runs on the camera thread for every analysis frame; throttled so the UI isn't flooded. */
    private fun onAnalysisFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        image.use {
            val now = SystemClock.elapsedRealtime()
            active?.takeIf { camera -> camera.analysisReader === reader }?.let { camera -> track(camera, it, now) }
            if (now - lastHistogramAt < HISTOGRAM_INTERVAL_MS) return
            lastHistogramAt = now
            val plane = it.planes[0]
            _histogram.value = Histogram(
                CameraMath.luminanceHistogram(plane.buffer, it.width, it.height, plane.rowStride, plane.pixelStride),
            )
        }
    }

    override suspend fun applySettings(settings: ManualSettings) {
        locked {
            val camera = active ?: return@locked
            camera.settings = sanitize(settings, camera.capabilities)
            startRepeating(camera)
            _state.update { current ->
                if (current is CameraState.Previewing) current.copy(settings = camera.settings) else current
            }
        }
    }

    /** Camera thread: follows the tracked subject and moves the focus region with it. */
    private fun track(camera: ActiveCamera, image: Image, now: Long) {
        val pending = camera.pendingTrack
        if (pending == null && !camera.tracker.isTracking) return
        if (now - camera.lastTrackAt < TRACK_INTERVAL_MS) return
        camera.lastTrackAt = now
        val frame = lumaFrame(image)

        if (pending != null) {
            camera.pendingTrack = null
            if (camera.tracker.start(frame, pending.first, pending.second)) {
                camera.lastTrackCenter = pending
            } else {
                // Nothing to follow (a blank wall, the sky): behave like a plain tap instead.
                camera.trackingWanted = false
                _focusTarget.update { it?.copy(tracking = false) }
                runCatching {
                    startRepeating(camera)
                    triggerFocus(camera)
                }
            }
            return
        }

        val position = camera.tracker.update(frame)
        if (position == null) {
            // Subject lost: go back to focusing on the whole scene.
            camera.trackingWanted = false
            camera.focusRegion = null
            _focusTarget.value = null
            runCatching { startRepeating(camera) }
            return
        }
        val (sx, sy) = position
        val (vx, vy) = CameraMath.sensorToView(sx, sy, camera.sensorOrientation, camera.isFront)
        _focusTarget.value = FocusTarget(vx, vy, tracking = true)
        val last = camera.lastTrackCenter
        if (last != null && abs(last.first - sx) < TRACK_MIN_MOVE && abs(last.second - sy) < TRACK_MIN_MOVE) return
        camera.lastTrackCenter = position
        camera.focusRegion = regionAt(camera, sx, sy) ?: return
        runCatching { startRepeating(camera) }
    }

    /** Copies the Y plane into a tightly packed frame for the tracker. */
    private fun lumaFrame(image: Image): LumaFrame {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height)
        if (plane.pixelStride == 1) {
            for (y in 0 until height) {
                buffer.position(y * plane.rowStride)
                buffer.get(out, y * width, width)
            }
        } else {
            for (y in 0 until height) {
                val row = y * plane.rowStride
                for (x in 0 until width) out[y * width + x] = buffer.get(row + x * plane.pixelStride)
            }
        }
        return LumaFrame(out, width, height)
    }

    override suspend fun focusAt(x: Float, y: Float, track: Boolean) {
        locked {
            val camera = active ?: return@locked
            if (!camera.capabilities.supportsTapToFocus) return@locked

            val (sx, sy) = CameraMath.viewToSensor(x, y, camera.sensorOrientation, camera.isFront)
            val region = regionAt(camera, sx, sy) ?: return@locked
            // Tracking needs the analysis stream (absent while RAW is on), and macro holds focus close.
            val canTrack = track && camera.analysisReader != null && !macroActive(camera)
            camera.focusRegion = region
            camera.trackingWanted = canTrack
            handler.post {
                camera.tracker.stop()
                camera.pendingTrack = if (canTrack) sx to sy else null
            }
            _focusTarget.value = FocusTarget(x, y, tracking = canTrack)

            startRepeating(camera)
            if (!canTrack) triggerFocus(camera)
        }
    }

    override suspend fun setZoom(ratio: Float) {
        locked {
            val camera = active ?: return@locked
            val range = camera.capabilities.zoomRange
            val next = ratio.coerceIn(range.start, range.endInclusive)
            if (next == camera.zoomRatio) return@locked
            camera.zoomRatio = next
            // The field of view changed, so a tapped point or tracked subject no longer lines up.
            clearFocus(camera)
            startRepeating(camera)
        }
    }

    override suspend fun setMacro(enabled: Boolean) {
        locked {
            macroEnabled = enabled
            val camera = active ?: return@locked
            clearFocus(camera)
            startRepeating(camera)
            if (macroActive(camera) && camera.capabilities.supportsMacroAf) triggerFocus(camera)
        }
    }

    private fun macroActive(camera: ActiveCamera): Boolean = macroEnabled && camera.capabilities.supportsMacro

    private fun clearFocus(camera: ActiveCamera) {
        camera.trackingWanted = false
        camera.focusRegion = null
        handler.post {
            camera.tracker.stop()
            camera.pendingTrack = null
        }
        _focusTarget.value = null
    }

    /** Starts a one-shot focus scan (AUTO and MACRO modes only scan when triggered). */
    private fun triggerFocus(camera: ActiveCamera) {
        val trigger = previewRequest(camera).apply {
            set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        }
        camera.session.capture(trigger.build(), null, handler)
        trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        camera.session.capture(trigger.build(), null, handler)
    }

    /**
     * Metering rectangle around normalised sensor point ([sx], [sy]) of what the preview shows.
     * Regions use active-array coordinates; with crop-based zoom the visible area is the crop.
     */
    private fun regionAt(camera: ActiveCamera, sx: Float, sy: Float): MeteringRectangle? {
        val array = camera.activeArray ?: return null
        val bounds = if (!camera.usesZoomRatio && camera.zoomRatio > 1f) {
            CameraMath.cropForZoom(array.width(), array.height(), camera.zoomRatio)
        } else {
            intArrayOf(0, 0, array.width(), array.height())
        }
        val (left, top, right, bottom) = bounds
        val width = right - left
        val height = bottom - top
        val half = (minOf(width, height) * FOCUS_REGION_FRACTION / 2).toInt()
        val cx = left + (sx.coerceIn(0f, 1f) * width).toInt()
        val cy = top + (sy.coerceIn(0f, 1f) * height).toInt()
        return MeteringRectangle(
            Rect(
                (cx - half).coerceAtLeast(left),
                (cy - half).coerceAtLeast(top),
                (cx + half).coerceAtMost(right - 1),
                (cy + half).coerceAtMost(bottom - 1),
            ),
            MeteringRectangle.METERING_WEIGHT_MAX,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class) // getCompleted() to release an unconsumed RAW frame
    override suspend fun capture(deviceRotationDegrees: Int, includeRaw: Boolean): CaptureBundle = locked {
        activeExtension?.let { opened ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return@locked captureExtension(opened, deviceRotationDegrees)
        }
        val camera = active ?: error("Camera is not open")
        val jpegReader = camera.jpegReader ?: error("Photos can't be taken while filming")
        val rawReader = camera.rawReader?.takeIf { includeRaw }
        val rotation = CameraMath.jpegOrientation(camera.sensorOrientation, deviceRotationDegrees, camera.isFront)

        if (usesAutoFlash(camera)) runPrecapture(camera)

        // Drop any stale frames so we only return the ones this request produces.
        jpegReader.acquireLatestImage()?.close()
        rawReader?.acquireLatestImage()?.close()

        val jpegBytes = CompletableDeferred<ByteArray>()
        val rawImage = rawReader?.let { CompletableDeferred<Image>() }
        val result = CompletableDeferred<TotalCaptureResult>()
        var rawConsumed = false
        fun failAll(cause: Throwable) {
            jpegBytes.completeExceptionally(cause)
            rawImage?.completeExceptionally(cause)
            result.completeExceptionally(cause)
        }

        jpegReader.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use { image ->
                val buffer = image.planes[0].buffer
                jpegBytes.complete(ByteArray(buffer.remaining()).also(buffer::get))
            }
        }, handler)
        rawReader?.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            if (rawImage?.complete(image) != true) image.close()
        }, handler)

        val request = camera.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jpegReader.surface)
            rawReader?.let { addTarget(it.surface) }
            applyControls(camera, this)
            set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
            set(CaptureRequest.JPEG_ORIENTATION, rotation)
            applyStillProcessing(camera, this)
            // With manual exposure the AE routine is off, so fire the flash directly for this frame.
            if (camera.capabilities.hasFlash && flashMode == FlashMode.ON && isManualExposure(camera)) {
                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
            }
        }.build()

        try {
            camera.session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    totalResult: TotalCaptureResult,
                ) {
                    result.complete(totalResult)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure,
                ) {
                    DiagnosticLog.record(TAG, "Capture failed, reason ${failure.reason}")
                    failAll(IllegalStateException("Capture failed, reason ${failure.reason}"))
                }
            }, handler)

            val timestamp = System.currentTimeMillis()
            val primary = CapturedImage(
                bytes = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { jpegBytes.await() }
                    ?: error("The camera did not deliver the photo"),
                format = ImageFormat.JPEG,
                size = PixelSize(jpegReader.width, jpegReader.height),
                timestampMillis = timestamp,
            )
            val raw = rawImage?.let { deferred ->
                val captureResult = result.await()
                val image = deferred.await()
                rawConsumed = true
                // DNG encoding of a full sensor frame is heavy; keep it off the caller's thread.
                withContext(processing) {
                    image.use { encodeDng(camera, captureResult, it, rotation, timestamp) }
                }
            }
            CaptureBundle(primary, raw)
        } catch (e: CameraAccessException) {
            failAll(e)
            throw e
        } finally {
            jpegReader.setOnImageAvailableListener(null, null)
            rawReader?.setOnImageAvailableListener(null, null)
            // If we bailed out before consuming the RAW frame, release its buffer.
            if (rawImage != null && !rawConsumed) {
                rawImage.cancel()
                if (rawImage.isCompleted && !rawImage.isCancelled) {
                    runCatching { rawImage.getCompleted().close() }
                }
            }
        }
    }

    override suspend fun captureBurst(deviceRotationDegrees: Int, frames: Int): List<CapturedImage> = locked {
        val camera = active ?: error("Camera is not open")
        val reader = camera.jpegReader ?: error("Photos can't be taken while filming")
        val rotation = CameraMath.jpegOrientation(camera.sensorOrientation, deviceRotationDegrees, camera.isFront)
        reader.acquireLatestImage()?.close()

        // Each frame (or an empty marker for a failed one) lands here as soon as it is ready, which
        // frees the reader's buffer for the next frame of the burst.
        val results = Channel<ByteArray>(Channel.UNLIMITED)
        val started = SystemClock.elapsedRealtime()
        // When each frame arrived and what the first one was exposed with, to see where a slow burst spends its time.
        val arrivals = java.util.concurrent.CopyOnWriteArrayList<Long>()
        var exposure: String? = null
        reader.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use { image ->
                val buffer = image.planes[0].buffer
                arrivals += SystemClock.elapsedRealtime() - started
                results.trySend(ByteArray(buffer.remaining()).also(buffer::get))
            }
        }, handler)
        try {
            val requests = List(frames) {
                camera.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    applyControls(camera, this)
                    // Identical exposures without flash merge cleanly.
                    if (!isManualExposure(camera)) {
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AE_LOCK, true)
                    }
                    if (camera.capabilities.hasFlash) set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                    set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
                    set(CaptureRequest.JPEG_ORIENTATION, rotation)
                    applyStillProcessing(camera, this)
                }.build()
            }
            camera.session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (exposure != null) return
                    val time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { it / 1_000_000.0 }
                    exposure = "exposure %.1f ms, ISO %s, noise reduction %s".format(
                        time ?: 0.0,
                        result.get(CaptureResult.SENSOR_SENSITIVITY),
                        result.get(CaptureResult.NOISE_REDUCTION_MODE),
                    )
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure,
                ) {
                    results.trySend(ByteArray(0))
                }
            }, handler)

            val timestamp = System.currentTimeMillis()
            val size = PixelSize(reader.width, reader.height)
            val collected = mutableListOf<CapturedImage>()
            withTimeoutOrNull(BURST_TIMEOUT_MS) {
                repeat(frames) {
                    val bytes = results.receive()
                    if (bytes.isNotEmpty()) collected += CapturedImage(bytes, ImageFormat.JPEG, size, timestamp)
                }
            }
            DiagnosticLog.info(TAG, "Burst of $frames frames ${size.width}x${size.height}: in at $arrivals ms (${exposure ?: "no result"})")
            if (collected.isEmpty()) error("The burst produced no frames")
            collected
        } finally {
            reader.setOnImageAvailableListener(null, null)
            results.close()
            // Unlock auto exposure for the preview again.
            runCatching { startRepeating(camera) }
        }
    }

    /** The camera's best noise reduction and sharpening for stills, where offered. */
    private fun applyStillProcessing(camera: ActiveCamera, builder: CaptureRequest.Builder) {
        if (camera.capabilities.supportsHqNoiseReduction) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        }
        if (CameraMetadata.EDGE_MODE_HIGH_QUALITY in camera.edgeModes) {
            builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        }
    }

    private fun isManualExposure(camera: ActiveCamera): Boolean =
        camera.settings.isManualExposure && camera.capabilities.supportsManualSensor

    /** Flash handled by the AE routine, which needs a precapture metering pass before the still. */
    private fun usesAutoFlash(camera: ActiveCamera): Boolean =
        camera.capabilities.hasFlash && flashMode != FlashMode.OFF && !isManualExposure(camera)

    /**
     * Runs the AE precapture sequence (the metering pre-flash) and waits for it to settle, so the
     * flash exposure is right. Gives up after a timeout: some devices never report the transition.
     */
    private suspend fun runPrecapture(camera: ActiveCamera) {
        val waiter = PrecaptureWaiter()
        precapture = waiter
        try {
            val trigger = previewRequest(camera).apply {
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }
            camera.session.capture(trigger.build(), previewCallback, handler)
            withTimeoutOrNull(PRECAPTURE_TIMEOUT_MS) { waiter.done.await() }
        } finally {
            precapture = null
        }
    }

    private fun encodeDng(
        camera: ActiveCamera,
        result: TotalCaptureResult,
        image: Image,
        rotation: Int,
        timestamp: Long,
    ): CapturedImage {
        val bytes = DngCreator(camera.characteristics, result).use { dng ->
            dng.setOrientation(CameraMath.exifOrientation(rotation))
            ByteArrayOutputStream().also { dng.writeImage(it, image) }.toByteArray()
        }
        return CapturedImage(
            bytes = bytes,
            format = ImageFormat.DNG,
            size = PixelSize(image.width, image.height),
            timestampMillis = timestamp,
        )
    }

    override suspend fun close() {
        locked { closeLocked() }
    }

    /**
     * Closes the open session. When the next session is for [reuseFor], the same camera, its device
     * is kept open and returned: switching between photo, Pro and video then skips reopening the
     * camera hardware, the slowest part of a switch.
     */
    private suspend fun closeLocked(reuseFor: String? = null): CameraDevice? {
        var kept: CameraDevice? = null
        if (recording != null) stopRecordingLocked()
        activeExtension?.let { opened ->
            activeExtension = null
            runCatching { opened.session.close() }
            runCatching { opened.device.close() }
            runCatching { opened.jpegReader.close() }
            _state.value = CameraState.Closed
        }
        val camera = active
        active = null
        if (camera != null) {
            runCatching { camera.session.stopRepeating() }
            runCatching { camera.session.close() }
            if (reuseFor != null && camera.device.id == reuseFor && camera.healthy) {
                kept = camera.device
            } else {
                runCatching { camera.device.close() }
            }
            camera.closeReaders()
            _histogram.value = null
            _focusTarget.value = null
            _state.value = CameraState.Closed
        }
        // After the camera, so it has stopped streaming into the renderer's texture.
        renderer?.let { withContext(nonCancellableProcessing) { it.release() } }
        renderer = null
        return kept
    }

    @SuppressLint("MissingPermission") // Onboarding guarantees CAMERA is granted before the camera opens.
    private suspend fun openDevice(cameraId: String): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (cont.isActive) cont.resume(device) else device.close()
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (cont.isActive) {
                    cont.resumeWithException(CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED))
                } else {
                    onLostDevice(device, CameraError.CAMERA_IN_USE)
                }
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                val mapped = when (error) {
                    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
                    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
                    -> CameraError.CAMERA_IN_USE
                    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> CameraError.CAMERA_DISABLED
                    else -> CameraError.DEVICE_ERROR
                }
                if (cont.isActive) {
                    cont.resumeWithException(CameraDeviceException(mapped))
                } else {
                    onLostDevice(device, mapped)
                }
            }
        }, handler)
    }

    /** Called on the camera thread when an already-open device is taken away from us. */
    private fun onLostDevice(device: CameraDevice, error: CameraError) {
        DiagnosticLog.record(TAG, "Camera ${device.id} was taken away: $error")
        active?.takeIf { it.device == device }?.healthy = false
        activeExtension?.takeIf { it.device == device }?.let { opened ->
            activeExtension = null
            runCatching { opened.jpegReader.close() }
            _state.value = CameraState.Error(error)
            return
        }
        val camera = active
        if (camera != null && camera.device == device) {
            active = null
            camera.closeReaders()
            _histogram.value = null
            _focusTarget.value = null
            _state.value = CameraState.Error(error)
        }
    }

    private suspend fun createSession(device: CameraDevice, surfaces: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Session configuration failed"))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        surfaces.map(::OutputConfiguration),
                        executor,
                        callback,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, callback, handler)
            }
        }

    private fun previewRequest(camera: ActiveCamera): CaptureRequest.Builder =
        camera.device.createCaptureRequest(
            if (camera.video != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW,
        ).apply {
            addTarget(camera.previewSurface)
            camera.analysisReader?.let { addTarget(it.surface) }
            applyControls(camera, this)
        }

    private fun startRepeating(camera: ActiveCamera) {
        camera.session.setRepeatingRequest(previewRequest(camera).build(), previewCallback, handler)
    }

    /** Translates the user's settings into Camera2 controls, shared by preview and still capture. */
    private fun applyControls(camera: ActiveCamera, builder: CaptureRequest.Builder) {
        val settings = camera.settings
        val caps = camera.capabilities
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Lens distortion correction on every request, so preview and photo match and the HAL never
        // has to switch modes between frames.
        // Video uses the fast mode, which keeps up with every frame.
        val video = camera.video
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && caps.supportsDistortionCorrection) {
            builder.set(
                CaptureRequest.DISTORTION_CORRECTION_MODE,
                if (video != null) DISTORTION_CORRECTION_FAST else CapabilityMapper.DISTORTION_CORRECTION_HIGH_QUALITY,
            )
        }
        if (video != null) {
            video.fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(it.first, it.last)) }
            if (video.stabilization) {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
        }

        // Zoom applies to every output, so preview, analysis and still all match.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && camera.usesZoomRatio) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, camera.zoomRatio)
        } else if (camera.zoomRatio > 1f) {
            camera.activeArray?.let { array ->
                val (l, t, r, b) = CameraMath.cropForZoom(array.width(), array.height(), camera.zoomRatio)
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, r, b))
            }
        }

        // Focus: continuous by default; after a tap, either a one-shot scan on that spot or, when
        // tracking, continuous focus on a region that follows the subject. Macro overrides both.
        val region = camera.focusRegion
        // Video refocuses smoothly instead of hunting the way a photo autofocus may.
        val continuous = if (video != null && CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in camera.afModes) {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        } else {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        when {
            macroActive(camera) && caps.supportsMacroAf -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_MACRO)
                if (region != null) builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            macroActive(camera) -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, caps.minFocusDistanceDiopters)
            }
            region != null && camera.trackingWanted && continuous in camera.afModes -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, continuous)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            region != null && CameraMetadata.CONTROL_AF_MODE_AUTO in camera.afModes -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            continuous in camera.afModes -> builder.set(CaptureRequest.CONTROL_AF_MODE, continuous)
        }
        if (region != null && camera.maxAeRegions > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }

        // Exposure: Camera2 only accepts ISO and shutter together, so fill the one the user left on
        // auto from the last value the sensor chose.
        if (settings.isManualExposure && caps.supportsManualSensor) {
            val live = camera.lastExposure
            val iso = settings.iso ?: live?.iso ?: caps.isoRange!!.first
            val exposure = settings.exposureTimeNs ?: live?.exposureTimeNs ?: DEFAULT_EXPOSURE_NS
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(caps.isoRange!!))
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.coerceIn(caps.exposureTimeRangeNs!!))
        } else if (video != null) {
            // Flash On is a steady light while filming.
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            if (caps.hasFlash) {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (flashMode == FlashMode.ON) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
                )
            }
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.exposureCompensation)
        } else {
            val aeMode = when {
                !caps.hasFlash -> CameraMetadata.CONTROL_AE_MODE_ON
                flashMode == FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                flashMode == FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            if (caps.hasFlash) builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.exposureCompensation)
        }

        when (val wb = settings.whiteBalance) {
            WhiteBalance.Auto ->
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            is WhiteBalance.Preset ->
                builder.set(CaptureRequest.CONTROL_AWB_MODE, awbModeFor(wb.preset))
            is WhiteBalance.Kelvin -> {
                val (r, g, b) = CameraMath.kelvinToGains(wb.temperature)
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                builder.set(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(r, g, g, b))
                // Keep the sensor's own colour matrix (captured while AWB was auto) and only swap gains.
                builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, camera.lastColorTransform ?: IDENTITY_TRANSFORM)
            }
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val camera = active?.takeIf { it.session == session } ?: return
            precapture?.let { waiter ->
                when (result.get(CaptureResult.CONTROL_AE_STATE)) {
                    null -> waiter.done.complete(Unit)
                    CameraMetadata.CONTROL_AE_STATE_PRECAPTURE -> waiter.sawPrecapture = true
                    else -> if (waiter.sawPrecapture) waiter.done.complete(Unit)
                }
            }
            camera.lastAeState = result.get(CaptureResult.CONTROL_AE_STATE)
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            if (iso != null && exposure != null) camera.lastExposure = LiveExposure(iso, exposure)
            if (camera.settings.whiteBalance !is WhiteBalance.Kelvin) {
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { camera.lastColorTransform = it }
            }

            val now = SystemClock.elapsedRealtime()
            if (now - camera.lastPublishedAt >= LIVE_EXPOSURE_INTERVAL_MS) {
                camera.lastPublishedAt = now
                _state.update { current ->
                    if (current is CameraState.Previewing) current.copy(liveExposure = camera.lastExposure) else current
                }
            }
        }
    }

    private fun awbModeFor(preset: WhiteBalancePreset): Int = when (preset) {
        WhiteBalancePreset.AUTO -> CameraMetadata.CONTROL_AWB_MODE_AUTO
        WhiteBalancePreset.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        WhiteBalancePreset.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WhiteBalancePreset.SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
        WhiteBalancePreset.INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        WhiteBalancePreset.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
    }

    private fun Exception.toCameraError(): CameraError = when (this) {
        is CameraDeviceException -> error
        is SecurityException -> CameraError.PERMISSION_DENIED
        is CameraAccessException -> when (reason) {
            CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE -> CameraError.CAMERA_IN_USE
            CameraAccessException.CAMERA_DISABLED -> CameraError.CAMERA_DISABLED
            else -> CameraError.DEVICE_ERROR
        }
        is IllegalArgumentException -> CameraError.NO_CAMERA
        else -> CameraError.DEVICE_ERROR
    }

    private class CameraDeviceException(val error: CameraError) : Exception("Camera device error: $error")

    private companion object {
        const val TAG = "Camera2Controller"
        const val MAX_IMAGES = 2

        /**
         * Largest photo taken, about 16 MP. 50 to 200 MP sensors give their cleanest, best-exposed
         * photos binned to around 12.5 MP, as their own camera apps do by default; full-size photos
         * would be noisier, slow to take and too big to optimise on the phone.
         */
        const val MAX_STILL_PIXELS = 16_800_000L
        const val JPEG_QUALITY: Byte = 95
        const val FOCUS_REGION_FRACTION = 0.15f
        const val DEFAULT_EXPOSURE_NS = 16_666_667L // 1/60 s
        const val LIVE_EXPOSURE_INTERVAL_MS = 250L
        const val ANALYSIS_IMAGES = 2
        const val HISTOGRAM_INTERVAL_MS = 200L
        const val PRECAPTURE_TIMEOUT_MS = 1500L
        const val BURST_TIMEOUT_MS = 15_000L
        const val CAPTURE_TIMEOUT_MS = 10_000L
        const val EXTENSION_CAPTURE_TIMEOUT_MS = 20_000L
        const val TRACK_INTERVAL_MS = 66L
        /** Normalised movement below which the focus region is left where it is. */
        const val TRACK_MIN_MOVE = 0.015f

        /** CameraMetadata.DISTORTION_CORRECTION_MODE_FAST, added in Android 9. */
        const val DISTORTION_CORRECTION_FAST = 1
        const val AUDIO_SAMPLE_RATE = 48_000
        const val AUDIO_BITRATE_PER_CHANNEL = 96_000

        /** EXTENSION_FACE_RETOUCH (named EXTENSION_BEAUTY before Android 14). */
        const val EXTENSION_FACE_RETOUCH = 1
        val IDENTITY_TRANSFORM = ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))
    }
}
