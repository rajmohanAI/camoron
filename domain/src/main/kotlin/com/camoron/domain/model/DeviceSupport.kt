package com.camoron.domain.model

/** Result of checking a camera against Camoron's minimum compatible specification. */
sealed interface DeviceSupport {
    data object Supported : DeviceSupport
    data class BelowMinimum(val reasons: List<Reason>) : DeviceSupport

    enum class Reason { HARDWARE_LEVEL_TOO_LOW, RESOLUTION_TOO_LOW, NO_BACK_CAMERA }
}
