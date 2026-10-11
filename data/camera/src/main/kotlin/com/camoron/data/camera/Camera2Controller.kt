package com.camoron.data.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat as AndroidImageFormat
import android.graphics.Rect
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
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.camoron.core.common.SurfacePreviewTarget
import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.CameraError
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.ImageFormat
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.LiveExposure
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.WhiteBalance
import com.camoron.domain.model.WhiteBalancePreset
import com.camoron.domain.repository.CameraController
import com.camoron.domain.repository.PreviewTarget
import com.camoron.domain.usecase.SanitizeManualSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2 implementation of [CameraController]. All Camera2 callbacks run on one dedicated
 * thread; public methods are serialised with a mutex so open/close/capture never interleave.
 */
@Singleton
class Camera2Controller @Inject constructor(
    @ApplicationContext context: Context,
    private val mapper: CapabilityMapper,
    private val sanitize: SanitizeManualSettings,
) : CameraController {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("CamoronCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val mutex = Mutex()

    private val _state = MutableStateFlow<CameraState>(CameraState.Closed)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    @Volatile
    private var active: ActiveCamera? = null

    /** Everything tied to one open camera; discarded as a unit on close. */
    private class ActiveCamera(
        val capabilities: CameraCapabilities,
        val characteristics: CameraCharacteristics,
        val device: CameraDevice,
        val session: CameraCaptureSession,
        val previewSurface: Surface,
        val jpegReader: ImageReader,
    ) {
        var settings: ManualSettings = ManualSettings.AUTO
        var focusRegion: MeteringRectangle? = null
        var lastExposure: LiveExposure? = null
        var lastColorTransform: ColorSpaceTransform? = null
        var lastPublishedAt: Long = 0L

        val sensorOrientation: Int = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val activeArray: Rect? = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val afModes: Set<Int> = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        val maxAeRegions: Int = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val isFront: Boolean get() = capabilities.lensFacing == LensFacing.FRONT
    }

    override suspend fun cameras(): List<CameraCapabilities> = manager.cameraIdList.mapNotNull { id ->
        runCatching { mapper.map(id, manager.getCameraCharacteristics(id)) }
            .onFailure { Log.w(TAG, "Skipping camera $id", it) }
            .getOrNull()
    }

    override suspend fun previewSize(cameraId: String): PixelSize {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val caps = mapper.map(cameraId, characteristics)
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceHolder::class.java)
            ?.map { PixelSize(it.width, it.height) }
            .orEmpty()
        return CameraMath.choosePreviewSize(sizes, CameraMath.aspectOf(caps.maxJpegSize))
    }

    override suspend fun open(cameraId: String, target: PreviewTarget) {
        mutex.withLock {
            val surface = (target as? SurfacePreviewTarget)?.surface
                ?: error("Camera2Controller needs a SurfacePreviewTarget")
            openLocked(cameraId, surface)
        }
    }

    private suspend fun openLocked(cameraId: String, surface: Surface) {
        closeLocked()
        _state.value = CameraState.Opening(cameraId)

        var device: CameraDevice? = null
        var reader: ImageReader? = null
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val caps = mapper.map(cameraId, characteristics)
            device = openDevice(cameraId)
            reader = ImageReader.newInstance(
                caps.maxJpegSize.width,
                caps.maxJpegSize.height,
                AndroidImageFormat.JPEG,
                MAX_IMAGES,
            )
            val session = createSession(device, listOf(surface, reader.surface))
            val camera = ActiveCamera(caps, characteristics, device, session, surface, reader)
            active = camera
            startRepeating(camera)
            _state.value = CameraState.Previewing(caps, camera.settings, liveExposure = null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera $cameraId", e)
            reader?.close()
            device?.close()
            active = null
            if (e is CancellationException) {
                _state.value = CameraState.Closed
                throw e
            }
            _state.value = CameraState.Error(e.toCameraError())
        }
    }

    override suspend fun applySettings(settings: ManualSettings) {
        mutex.withLock {
            val camera = active ?: return@withLock
            camera.settings = sanitize(settings, camera.capabilities)
            startRepeating(camera)
            _state.update { current ->
                if (current is CameraState.Previewing) current.copy(settings = camera.settings) else current
            }
        }
    }

    override suspend fun focusAt(x: Float, y: Float) {
        mutex.withLock {
            val camera = active ?: return@withLock
            val array = camera.activeArray ?: return@withLock
            if (!camera.capabilities.supportsTapToFocus) return@withLock

            val (sx, sy) = CameraMath.viewToSensor(x, y, camera.sensorOrientation, camera.isFront)
            val half = (minOf(array.width(), array.height()) * FOCUS_REGION_FRACTION / 2).toInt()
            val cx = array.left + (sx * array.width()).toInt()
            val cy = array.top + (sy * array.height()).toInt()
            val region = MeteringRectangle(
                Rect(
                    (cx - half).coerceAtLeast(array.left),
                    (cy - half).coerceAtLeast(array.top),
                    (cx + half).coerceAtMost(array.right),
                    (cy + half).coerceAtMost(array.bottom),
                ),
                MeteringRectangle.METERING_WEIGHT_MAX,
            )
            camera.focusRegion = region

            // Cancel any running scan, then trigger a fresh one on the new region.
            val trigger = previewRequest(camera).apply {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            }
            camera.session.capture(trigger.build(), null, handler)
            trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            camera.session.capture(trigger.build(), null, handler)
            startRepeating(camera)
        }
    }

    override suspend fun capture(deviceRotationDegrees: Int): CapturedImage = mutex.withLock {
        val camera = active ?: error("Camera is not open")
        val reader = camera.jpegReader
        // Drop any stale frame so we only return the one this request produces.
        reader.acquireLatestImage()?.close()

        val request = camera.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            applyControls(camera, this)
            set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
            set(
                CaptureRequest.JPEG_ORIENTATION,
                CameraMath.jpegOrientation(camera.sensorOrientation, deviceRotationDegrees, camera.isFront),
            )
        }.build()

        suspendCancellableCoroutine { cont ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                r.setOnImageAvailableListener(null, null)
                val bytes = image.use {
                    val buffer = it.planes[0].buffer
                    ByteArray(buffer.remaining()).also(buffer::get)
                }
                if (cont.isActive) {
                    cont.resume(
                        CapturedImage(
                            bytes = bytes,
                            format = ImageFormat.JPEG,
                            size = PixelSize(reader.width, reader.height),
                            timestampMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            }, handler)
            cont.invokeOnCancellation { reader.setOnImageAvailableListener(null, null) }

            try {
                camera.session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure,
                    ) {
                        reader.setOnImageAvailableListener(null, null)
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("Capture failed, reason ${failure.reason}"))
                        }
                    }
                }, handler)
            } catch (e: CameraAccessException) {
                reader.setOnImageAvailableListener(null, null)
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    override suspend fun close() {
        mutex.withLock { closeLocked() }
    }

    private fun closeLocked() {
        val camera = active ?: return
        active = null
        runCatching { camera.session.stopRepeating() }
        runCatching { camera.session.close() }
        runCatching { camera.device.close() }
        runCatching { camera.jpegReader.close() }
        _state.value = CameraState.Closed
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
        val camera = active
        if (camera != null && camera.device == device) {
            active = null
            runCatching { camera.jpegReader.close() }
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
        camera.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(camera.previewSurface)
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

        // Focus: continuous by default, single-shot on the tapped region after a tap.
        val region = camera.focusRegion
        when {
            region != null && CameraMetadata.CONTROL_AF_MODE_AUTO in camera.afModes -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in camera.afModes ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
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
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
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
        const val JPEG_QUALITY: Byte = 95
        const val FOCUS_REGION_FRACTION = 0.15f
        const val DEFAULT_EXPOSURE_NS = 16_666_667L // 1/60 s
        const val LIVE_EXPOSURE_INTERVAL_MS = 250L
        val IDENTITY_TRANSFORM = ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))
    }
}
