package com.camoron.domain.model

sealed interface Entitlement {
    /** Paid subscriber or inside the 1-month free trial. */
    data object Pro : Entitlement
    /** Trial ended or never started. */
    data object Basic : Entitlement
}

enum class Feature {
    PRO_MODE_CONTROLS,
    RAW_CAPTURE,
    LENS_DISTORTION_CORRECTION,
    CLASSICAL_NOISE_REDUCTION,
    AI_NOISE_REDUCTION,
    AI_UPSCALING,
    SAVE_WITH_EXIF,
}
