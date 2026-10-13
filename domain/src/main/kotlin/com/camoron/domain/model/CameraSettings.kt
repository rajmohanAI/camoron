package com.camoron.domain.model

enum class FlashMode { OFF, AUTO, ON }

enum class TimerSetting(val seconds: Int) { OFF(0), THREE(3), FIVE(5) }

/** Shape of the photo as the user sees it in portrait. */
enum class AspectRatioSetting {
    RATIO_3_4,
    RATIO_9_16,
    FULL_SCREEN;

    /** Sensor output to configure: 3:4 uses native 4:3, the taller shapes start from 16:9. */
    val stillAspect: StillAspect
        get() = if (this == RATIO_3_4) StillAspect.FOUR_THREE else StillAspect.SIXTEEN_NINE
}

/** User preferences for the camera screen, persisted on the device. */
data class CameraSettings(
    val flash: FlashMode = FlashMode.OFF,
    val timer: TimerSetting = TimerSetting.OFF,
    val aspectRatio: AspectRatioSetting = AspectRatioSetting.RATIO_3_4,
    val selfieMirror: Boolean = true,
    val shutterSound: Boolean = true,
    val autoFocusTracking: Boolean = true,
    val grid: Boolean = false,
    val leveller: Boolean = false,
    val night: NightSetting = NightSetting.OFF,
    val look: ShotLook = ShotLook.NORMAL,
    /** Scene-adaptive tone, colour and detail, live on the preview and on the photo. */
    val enhance: Boolean = true,
    /** Video and Cinematic recording size; 4K only where the camera and encoder support it. */
    val videoResolution: VideoResolution = VideoResolution.FHD,
    val timelapseSpeed: TimelapseSpeed = TimelapseSpeed.X15,
)

/** How a saved JPEG should be adjusted so it matches the preview. */
data class PhotoAdjustments(
    /** Portrait width/height to centre-crop to, or null to keep the sensor's shape. */
    val cropAspect: Float? = null,
    /** Mirror horizontally, as the front-camera preview appears. */
    val mirror: Boolean = false,
    /** Colour look, the same one the preview shows. */
    val look: ShotLook = ShotLook.NORMAL,
    /** Scene-adaptive tone, colour and detail ([EnhanceMath]), plus sharpening the preview can't show. */
    val enhance: Boolean = false,
    /** Digital zoom the photo was taken at; zoomed photos get gentler sharpening. */
    val zoom: Float = 1f,
) {
    val isIdentity: Boolean get() = cropAspect == null && !mirror && look == ShotLook.NORMAL && !enhance

    companion object {
        val NONE = PhotoAdjustments()
    }
}
