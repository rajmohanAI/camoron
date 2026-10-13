package com.camoron.domain.usecase

import com.camoron.domain.model.Entitlement
import com.camoron.domain.model.Feature
import javax.inject.Inject

/** Single source of truth for which features each subscription tier unlocks. */
class FeatureGate @Inject constructor() {

    fun isEnabled(feature: Feature, entitlement: Entitlement): Boolean = when (entitlement) {
        Entitlement.Pro -> true
        Entitlement.Basic -> feature in BASIC_FEATURES
    }

    companion object {
        val BASIC_FEATURES = setOf(
            Feature.LENS_DISTORTION_CORRECTION,
            Feature.CLASSICAL_NOISE_REDUCTION,
            Feature.SAVE_WITH_EXIF,
            Feature.NIGHT_MODE,
            Feature.BASIC_LOOKS,
        )
    }
}
