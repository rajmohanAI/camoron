package com.camoron.domain.model

enum class FlashMode { OFF, AUTO, ON }

enum class TimerSetting(val seconds: Int) { OFF(0), THREE(3), FIVE(5) }

/** Shape of the photo as the user sees it in portrait. */
enum class AspectRatioSetting {
    RATIO_3_4,
    RATIO_9_16,
    FULL_SCREEN;

    /**
     * Sensor output to configure: always the full 4:3 frame. The taller shapes are centre crops of
     * it (a phone's native 16:9 output is the same crop), so switching shape never restarts the
     * camera.
     */
    val stillAspect: StillAspect
        get() = StillAspect.FOUR_THREE
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
    /** Auto by default, so indoor and evening shots get a merged burst without anyone asking. */
    val night: NightSetting = NightSetting.AUTO,
    val look: ShotLook = ShotLook.NORMAL,
    /** Scene-adaptive tone, colour and detail, live on the preview and on the photo. */
    val enhance: Boolean = true,
    /**
     * Normal photos through the phone maker's own HDR mode, where the phone offers it to other
     * apps. Off by default while it is tried: that mode has no zoom, focus tap or Night Auto.
     */
    val phoneHdr: Boolean = false,
    /** Tag photos with where they were taken; off until the user turns it on and allows location. */
    val geotag: Boolean = false,
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
    /** Portrait: keep the people sharp and blur the background behind them. */
    val portrait: Boolean = false,
    /** Where the photo was taken, written into its EXIF; null to leave it out. */
    val location: GeoPoint? = null,
) {
    val isIdentity: Boolean
        get() = cropAspect == null && !mirror && look == ShotLook.NORMAL && !enhance && !portrait && location == null

    companion object {
        val NONE = PhotoAdjustments()
    }
}
