package com.camoron.domain.usecase

import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.ZoomPreset
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Works out which zoom buttons this phone gets for one side (front or back). A button only
 * appears when the hardware can deliver it:
 *
 * * **Ultra-wide**: the main camera's zoom ratio goes below 1 (Android 11+ multi-camera), or a
 *   separate camera on the same side has a clearly wider lens.
 * * **1×** is always there.
 * * **2× and 3×**: the main camera can zoom that far (optical through its tele lens on
 *   multi-camera phones, otherwise digital).
 */
class PlanZoomPresets @Inject constructor() {

    operator fun invoke(main: CameraCapabilities, sameSide: List<CameraCapabilities>): List<ZoomPreset> {
        val presets = mutableListOf<ZoomPreset>()
        ultraWide(main, sameSide)?.let(presets::add)
        presets += ZoomPreset(1f, main.cameraId, 1f)
        val maxZoom = main.zoomRange.endInclusive
        TELE_STEPS.filter { it <= maxZoom + EPSILON }.forEach { presets += ZoomPreset(it, main.cameraId, it) }
        return presets
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
        val TELE_STEPS = listOf(2f, 3f)
        const val ULTRA_WIDE_MAX_RATIO = 0.8f
        const val EPSILON = 0.01f
    }
}
