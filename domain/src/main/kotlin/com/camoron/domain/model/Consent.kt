package com.camoron.domain.model

/**
 * On-device record of what the user has agreed to. Versions let us re-ask when wording changes.
 * [biometricConsentVersion] stays null in MVP 1 because no face data is processed.
 */
data class ConsentState(
    val acceptedDisclaimerVersion: Int? = null,
    val biometricConsentVersion: Int? = null,
) {
    val hasAcceptedCurrentDisclaimer: Boolean
        get() = acceptedDisclaimerVersion != null && acceptedDisclaimerVersion >= CURRENT_DISCLAIMER_VERSION

    companion object {
        const val CURRENT_DISCLAIMER_VERSION = 1
    }
}
