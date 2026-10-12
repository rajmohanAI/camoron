package com.camoron.domain.model

sealed interface CameraState {
    data object Closed : CameraState
    data class Opening(val cameraId: String) : CameraState
    data class Previewing(
        val capabilities: CameraCapabilities,
        val settings: ManualSettings,
        val liveExposure: LiveExposure?,
    ) : CameraState
    data class Error(val error: CameraError) : CameraState
}

enum class CameraError { PERMISSION_DENIED, CAMERA_IN_USE, CAMERA_DISABLED, DEVICE_ERROR, NO_CAMERA }

enum class ImageFormat { JPEG, DNG }

/** Encoded image bytes as delivered by the camera, before any Camoron processing. */
class CapturedImage(
    val bytes: ByteArray,
    val format: ImageFormat,
    val size: PixelSize,
    val timestampMillis: Long,
) {
    /** Same shot with replaced pixels, after a processing step. */
    fun withBytes(bytes: ByteArray, size: PixelSize = this.size) = CapturedImage(bytes, format, size, timestampMillis)
}

/** One shot: the processed-quality JPEG plus, when requested, the sensor's RAW frame as a DNG. */
class CaptureBundle(
    val primary: CapturedImage,
    val raw: CapturedImage? = null,
)

/** Luminance distribution of a preview frame, [bins] buckets from black to white. */
class Histogram(val bins: IntArray) {
    val max: Int = bins.maxOrNull() ?: 0
}
