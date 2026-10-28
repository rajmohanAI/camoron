package com.camoron.domain.repository

import com.camoron.domain.model.GeoPoint

/** The phone's location, for tagging photos with where they were taken. */
interface LocationProvider {
    /** Starts following the location. Does nothing without the location permission. */
    fun start()

    fun stop()

    /** The latest recent fix, or null when it is unknown, too old or not allowed. */
    fun current(): GeoPoint?
}
