package com.camoron.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** Persisted shape of the user's consent. Stored on-device only, never synced. */
@Serializable
internal data class StoredConsent(
    val acceptedDisclaimerVersion: Int? = null,
    val disclaimerAcceptedAtMillis: Long? = null,
    val biometricConsentVersion: Int? = null,
)

internal object StoredConsentSerializer : Serializer<StoredConsent> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue = StoredConsent()

    override suspend fun readFrom(input: InputStream): StoredConsent = try {
        json.decodeFromString(StoredConsent.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable consent record", e)
    }

    override suspend fun writeTo(t: StoredConsent, output: OutputStream) {
        output.write(json.encodeToString(StoredConsent.serializer(), t).encodeToByteArray())
    }
}
