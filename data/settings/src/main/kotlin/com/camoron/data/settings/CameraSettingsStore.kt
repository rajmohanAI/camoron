package com.camoron.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.camoron.domain.model.AspectRatioSetting
import com.camoron.domain.model.CameraSettings
import com.camoron.domain.model.FlashMode
import com.camoron.domain.model.NightSetting
import com.camoron.domain.model.ShotLook
import com.camoron.domain.model.TimerSetting
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Persisted camera preferences. Enums are stored by name so a renamed or removed value falls back
 * to its default instead of corrupting the file.
 */
@Serializable
internal data class StoredCameraSettings(
    val flash: String? = null,
    val timer: String? = null,
    val aspectRatio: String? = null,
    val selfieMirror: Boolean? = null,
    val shutterSound: Boolean? = null,
    val autoFocusTracking: Boolean? = null,
    val grid: Boolean? = null,
    val leveller: Boolean? = null,
    val night: String? = null,
    val look: String? = null,
) {
    fun toDomain(): CameraSettings {
        val defaults = CameraSettings()
        return CameraSettings(
            flash = enumOrNull<FlashMode>(flash) ?: defaults.flash,
            timer = enumOrNull<TimerSetting>(timer) ?: defaults.timer,
            aspectRatio = enumOrNull<AspectRatioSetting>(aspectRatio) ?: defaults.aspectRatio,
            selfieMirror = selfieMirror ?: defaults.selfieMirror,
            shutterSound = shutterSound ?: defaults.shutterSound,
            autoFocusTracking = autoFocusTracking ?: defaults.autoFocusTracking,
            grid = grid ?: defaults.grid,
            leveller = leveller ?: defaults.leveller,
            night = enumOrNull<NightSetting>(night) ?: defaults.night,
            look = enumOrNull<ShotLook>(look) ?: defaults.look,
        )
    }

    companion object {
        fun from(settings: CameraSettings) = StoredCameraSettings(
            flash = settings.flash.name,
            timer = settings.timer.name,
            aspectRatio = settings.aspectRatio.name,
            selfieMirror = settings.selfieMirror,
            shutterSound = settings.shutterSound,
            autoFocusTracking = settings.autoFocusTracking,
            grid = settings.grid,
            leveller = settings.leveller,
            night = settings.night.name,
            look = settings.look.name,
        )
    }
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }

internal object StoredCameraSettingsSerializer : Serializer<StoredCameraSettings> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue = StoredCameraSettings()

    override suspend fun readFrom(input: InputStream): StoredCameraSettings = try {
        json.decodeFromString(StoredCameraSettings.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable camera settings", e)
    }

    override suspend fun writeTo(t: StoredCameraSettings, output: OutputStream) {
        output.write(json.encodeToString(StoredCameraSettings.serializer(), t).encodeToByteArray())
    }
}
