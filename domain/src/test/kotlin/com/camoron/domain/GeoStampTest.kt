package com.camoron.domain

import com.camoron.domain.model.GeoPlace
import com.camoron.domain.model.GeoPoint
import com.camoron.domain.model.GeoStamp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class GeoStampTest {

    private val india = ZoneId.of("Asia/Kolkata")
    private val time = ZonedDateTime.of(2026, 11, 12, 15, 41, 0, 0, india).toInstant().toEpochMilli()
    private val point = GeoPoint(12.805902, 80.211211)

    @Test
    fun `the stamp reads place, address, coordinates and local time`() {
        val stamp = GeoStamp(point, GeoPlace("Pudupakkam, Tamil Nadu, India", "Villa 1, Pudupakkam, Tamil Nadu 603103, India"), time)
        assertThat(stamp.lines(india, Locale.US)).containsExactly(
            "Pudupakkam, Tamil Nadu, India",
            "Villa 1, Pudupakkam, Tamil Nadu 603103, India",
            "Lat 12.805902° Long 80.211211°",
            "Thursday, 12/11/2026 03:41 PM GMT +05:30",
        ).inOrder()
    }

    @Test
    fun `without a place name the stamp still has the coordinates and time`() {
        val stamp = GeoStamp(GeoPoint(-33.8688, 151.2093), place = null, timeMillis = time)
        assertThat(stamp.lines(india, Locale.US)).containsExactly(
            "Lat -33.868800° Long 151.209300°",
            "Thursday, 12/11/2026 03:41 PM GMT +05:30",
        ).inOrder()
    }
}
