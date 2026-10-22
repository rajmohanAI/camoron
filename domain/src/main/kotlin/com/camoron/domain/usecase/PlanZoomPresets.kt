package com.camoron.domain.usecase

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.ZoomPreset
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Works out which zoom buttons this phone gets for one side (front or back). A button only
 * appears when the hardware can deliver it:
 *
 * * **Ultra-wide**: the main camera's zoom ratio goes below 1 (Android 11+ multi-camera), or a
 *   separate camera on the same side has a clearly wider lens.
 * * **1×** is always there.
 * * **Telephoto and periscope lenses**: one button each at their own reach (3×, 5×, ...), whether
 *   the phone exposes them through the main camera's zoom or as separate cameras. A periscope of
 *   5× or more also gets a 10× button, as flagship cameras offer.
 * * **2×** whenever the main camera can zoom that far: a sharp crop on today's large sensors.
 * * **3×** as a digital step only on phones without any longer lens.
 */
class PlanZoomPresets @Inject constructor() {

    operator fun invoke(main: CameraCapabilities, sameSide: List<CameraCapabilities>): List<ZoomPreset> {
        val presets = mutableListOf<ZoomPreset>()
        ultraWide(main, sameSide)?.let(presets::add)
        presets += ZoomPreset(1f, main.cameraId, 1f)
        val maxZoom = main.zoomRange.endInclusive
        val reachable = { ratio: Float -> ratio <= maxZoom + EPSILON }

        val tele = mutableListOf<ZoomPreset>()
        main.opticalZoomRatios.filter(reachable).forEach { tele += ZoomPreset(it, main.cameraId, it) }
        separateTele(main, sameSide).forEach { (camera, ratio) ->
            if (tele.none { abs(it.label - ratio) < SAME_STEP }) tele += ZoomPreset(ratio, camera.cameraId, 1f)
        }
        val longest = tele.maxOfOrNull { it.label }
        if (longest != null && longest >= PERISCOPE && reachable(HYBRID_STEP) && longest < HYBRID_STEP - SAME_STEP) {
            tele += ZoomPreset(HYBRID_STEP, main.cameraId, HYBRID_STEP)
        }
        if (reachable(2f) && tele.none { abs(it.label - 2f) < SAME_STEP }) tele += ZoomPreset(2f, main.cameraId, 2f)
        if (longest == null && reachable(3f)) tele += ZoomPreset(3f, main.cameraId, 3f)
        return presets + tele.sortedBy { it.label }
    }

    /**
     * Cameras on the same side whose lens is clearly longer than the main one, with their reach in
     * whole steps, the way makers name their lenses (a 73 mm lens beside a 24 mm one is 3x).
     */
    private fun separateTele(main: CameraCapabilities, sameSide: List<CameraCapabilities>): List<Pair<CameraCapabilities, Float>> {
        val mainFocal = main.equivalentFocalLengthMm ?: return emptyList()
        return sameSide
            .filter { it.cameraId != main.cameraId }
            .mapNotNull { other -> other.equivalentFocalLengthMm?.let { other to (it / mainFocal).roundToInt().toFloat() } }
            .filter { (_, ratio) -> ratio >= TELE_MIN_RATIO }
            .distinctBy { (_, ratio) -> ratio }
    }

    private fun ultraWide(main: CameraCapabilities, sameSide: List<CameraCapabilities>): ZoomPreset? {
        val minRatio = main.zoomRatioRange?.start
        if (minRatio != null && minRatio < 1f - EPSILON) {
            return ZoomPreset(roundLabel(minRatio), main.cameraId, minRatio)
        }
        val mainFocal = main.equivalentFocalLengthMm ?: return null
        val wider = sameSide
            .filter { it.cameraId != main.cameraId }
            .mapNotNull { other -> other.equivalentFocalLengthMm?.let { other to it / mainFocal } }
            .filter { (_, ratio) -> ratio < ULTRA_WIDE_MAX_RATIO }
            .minByOrNull { (_, ratio) -> ratio }
            ?: return null
        return ZoomPreset(roundLabel(wider.second), wider.first.cameraId, 1f)
    }

    /** One decimal, so a 0.55 lens reads "0.6" and a 0.5 zoom ratio reads "0.5". */
    private fun roundLabel(ratio: Float): Float = (ratio * 10).roundToInt() / 10f

    private companion object {
        const val ULTRA_WIDE_MAX_RATIO = 0.8f
        const val TELE_MIN_RATIO = 1.8f
        const val PERISCOPE = 5f
        const val HYBRID_STEP = 10f
        const val SAME_STEP = 0.3f
        const val EPSILON = 0.01f
    }
}
