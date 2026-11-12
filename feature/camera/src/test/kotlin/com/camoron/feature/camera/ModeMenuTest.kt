package com.camoron.feature.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModeMenuTest {

    @Test
    fun `every mode is either on the row or under more`() {
        assertThat(MAIN_MODES + MORE_MODES).containsExactlyElementsIn(CaptureMode.entries)
    }

    @Test
    fun `a mode chosen from more joins the row before more`() {
        assertThat(modeRowEntries(CaptureMode.PHOTO)).containsExactly(
            CaptureMode.VIDEO, CaptureMode.PHOTO, CaptureMode.PORTRAIT, CaptureMode.PRO, null,
        ).inOrder()
        assertThat(modeRowEntries(CaptureMode.PANORAMA)).containsExactly(
            CaptureMode.VIDEO, CaptureMode.PHOTO, CaptureMode.PORTRAIT, CaptureMode.PRO, CaptureMode.PANORAMA, null,
        ).inOrder()
    }
}
