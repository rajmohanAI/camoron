package com.camoron.domain.model

/** Where a photo was taken, in degrees (WGS 84), with the altitude in metres when known. */
data class GeoPoint(val latitude: Double, val longitude: Double, val altitude: Double? = null)
