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
)
