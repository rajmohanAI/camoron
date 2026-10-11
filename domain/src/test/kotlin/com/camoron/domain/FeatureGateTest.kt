package com.camoron.domain

import com.camoron.domain.model.Entitlement
import com.camoron.domain.model.Feature
import com.camoron.domain.usecase.FeatureGate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeatureGateTest {
    private val gate = FeatureGate()

    @Test
    fun `pro and trial unlock every feature`() {
        Feature.entries.forEach { assertThat(gate.isEnabled(it, Entitlement.Pro)).isTrue() }
    }

    @Test
    fun `basic keeps distortion correction, noise reduction and saving only`() {
        val enabled = Feature.entries.filter { gate.isEnabled(it, Entitlement.Basic) }
        assertThat(enabled).containsExactly(
            Feature.LENS_DISTORTION_CORRECTION,
            Feature.CLASSICAL_NOISE_REDUCTION,
            Feature.SAVE_WITH_EXIF,
        )
    }
}
