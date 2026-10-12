package com.camoron.domain.model

/**
 * Everything Camoron learned about this phone's cameras. Built once at startup and used to decide
 * which menus, lenses and modes to show.
 */
data class DeviceProfile(
    val cameras: List<CameraCapabilities>,
    /** A front and back camera can stream at the same time (needed for dual capture). */
    val supportsConcurrentFrontBack: Boolean = false,
    /** Local law requires an audible shutter (Android 13+ reports this). */
    val mustPlayShutterSound: Boolean = false,
) {
    fun bestCamera(facing: LensFacing): CameraCapabilities? =
        cameras.filter { it.lensFacing == facing }.maxByOrNull { it.sensorSize.megapixels }
}
