package com.camoron.data.settings

import androidx.datastore.core.CorruptionException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class StoredConsentSerializerTest {

    @Test
    fun `round trips a consent record`() = runBlocking {
        val original = StoredConsent(acceptedDisclaimerVersion = 1, disclaimerAcceptedAtMillis = 42L)
        val out = ByteArrayOutputStream()
        StoredConsentSerializer.writeTo(original, out)
        val read = StoredConsentSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(read).isEqualTo(original)
    }

    @Test(expected = CorruptionException::class)
    fun `garbage is reported as corruption`() {
        runBlocking { StoredConsentSerializer.readFrom(ByteArrayInputStream("not json".toByteArray())) }
    }
}
