package com.camoron.data.settings

import com.camoron.domain.model.AspectRatioSetting
import com.camoron.domain.model.CameraSettings
import com.camoron.domain.model.FlashMode
import com.camoron.domain.model.NightSetting
import com.camoron.domain.model.ShotLook
import com.camoron.domain.model.TimelapseSpeed
import com.camoron.domain.model.TimerSetting
import com.camoron.domain.model.VideoResolution
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class StoredCameraSettingsTest {

    @Test
    fun `round trips through the serializer`() = runBlocking {
        val settings = CameraSettings(
            flash = FlashMode.AUTO,
            timer = TimerSetting.FIVE,
            aspectRatio = AspectRatioSetting.FULL_SCREEN,
            selfieMirror = false,
            grid = true,
            night = NightSetting.AUTO,
            look = ShotLook.STUDIO,
            enhance = false,
            videoResolution = VideoResolution.UHD,
            timelapseSpeed = TimelapseSpeed.X60,
        )
        val out = ByteArrayOutputStream()
        StoredCameraSettingsSerializer.writeTo(StoredCameraSettings.from(settings), out)
        val read = StoredCameraSettingsSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.toDomain()).isEqualTo(settings)
    }

    @Test
    fun `unknown enum names fall back to defaults`() {
        val stored = StoredCameraSettings(flash = "STROBE", timer = "TEN", grid = true)
        assertThat(stored.toDomain()).isEqualTo(CameraSettings(grid = true))
    }

    @Test
    fun `empty record is the defaults`() {
        assertThat(StoredCameraSettings().toDomain()).isEqualTo(CameraSettings())
    }
}
