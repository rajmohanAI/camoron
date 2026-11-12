package com.camoron.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A named place: a short title (town, state, country) and the full street address. */
data class GeoPlace(val title: String, val address: String)

/**
 * What Geo Tag mode prints on a photo: where it was taken (with the place's name when the phone
 * could look it up) and when.
 */
data class GeoStamp(val point: GeoPoint, val place: GeoPlace?, val timeMillis: Long) {

    /** The stamp's lines, top to bottom: the place's title and address when known, then the coordinates and the time. */
    fun lines(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<String> = buildList {
        place?.title?.takeIf { it.isNotBlank() }?.let(::add)
        place?.address?.takeIf { it.isNotBlank() && it != place.title }?.let(::add)
        add(coordinates())
        add(time(zone, locale))
    }

    fun coordinates(): String =
        String.format(Locale.ROOT, "Lat %.6f° Long %.6f°", point.latitude, point.longitude)

    fun time(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy hh:mm a 'GMT' xxx", locale)
            .format(Instant.ofEpochMilli(timeMillis).atZone(zone))
}
