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
    /** Multi-frame night merge (the phone maker's Night extension counts too). */
    NIGHT_MODE,
    /** Normal and Black & White looks. */
    BASIC_LOOKS,
    /** Vivid, Cool, Warm and Studio. */
    ALL_LOOKS,
    /** Scene-adaptive tone, colour and detail enhancement. */
    AUTO_ENHANCE,
}
