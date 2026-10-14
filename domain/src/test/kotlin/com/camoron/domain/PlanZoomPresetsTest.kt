package com.camoron.domain

import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.ZoomPreset
import com.camoron.domain.usecase.PlanZoomPresets
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlanZoomPresetsTest {

    private val plan = PlanZoomPresets()

    @Test
    fun `multi-camera phone gets ultra-wide and tele from the zoom ratio range`() {
        val main = fullCamera().copy(zoomRatioRange = 0.5f..10f, equivalentFocalLengthMm = 24f)
        assertThat(plan(main, listOf(main))).containsExactly(
            ZoomPreset(0.5f, "0", 0.5f),
            ZoomPreset(1f, "0", 1f),
            ZoomPreset(2f, "0", 2f),
            ZoomPreset(3f, "0", 3f),
        ).inOrder()
    }

    @Test
    fun `separate ultra-wide camera is reached by switching cameras`() {
        val main = fullCamera(id = "0").copy(equivalentFocalLengthMm = 26f, maxDigitalZoom = 2.5f)
        val wide = fullCamera(id = "2", sensor = PixelSize(4000, 3000)).copy(equivalentFocalLengthMm = 14f)
        val front = fullCamera(id = "1", facing = LensFacing.FRONT).copy(equivalentFocalLengthMm = 10f)
        assertThat(plan(main, listOf(main, wide, front).filter { it.lensFacing == main.lensFacing }))
            .containsExactly(ZoomPreset(0.5f, "2", 1f), ZoomPreset(1f, "0", 1f), ZoomPreset(2f, "0", 2f))
            .inOrder()
    }

    @Test
    fun `no zoom at all leaves just 1x`() {
        val main = fullCamera()
        assertThat(plan(main, listOf(main))).containsExactly(ZoomPreset(1f, "0", 1f))
    }

    @Test
    fun `a slightly wider second lens is not called ultra-wide`() {
        val main = fullCamera(id = "0").copy(equivalentFocalLengthMm = 26f)
        val similar = fullCamera(id = "3").copy(equivalentFocalLengthMm = 24f)
        assertThat(plan(main, listOf(main, similar)).map { it.label }).containsExactly(1f)
    }

    @Test
    fun `flagship with telephoto and periscope gets a button for each, plus 10x`() {
        val main = fullCamera().copy(zoomRatioRange = 0.6f..100f, equivalentFocalLengthMm = 23f, opticalZoomRatios = listOf(3f, 5f))
        assertThat(plan(main, listOf(main)).map { it.label }).containsExactly(0.6f, 1f, 2f, 3f, 5f, 10f).inOrder()
    }

    @Test
    fun `a telephoto exposed as its own camera is reached by switching to it`() {
        val main = fullCamera(id = "0").copy(zoomRatioRange = 1f..8f, equivalentFocalLengthMm = 24f)
        val tele = fullCamera(id = "2").copy(equivalentFocalLengthMm = 73f)
        assertThat(plan(main, listOf(main, tele))).containsExactly(
            ZoomPreset(1f, "0", 1f),
            ZoomPreset(2f, "0", 2f),
            ZoomPreset(3f, "2", 1f),
        ).inOrder()
    }
}
