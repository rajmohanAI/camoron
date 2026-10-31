package com.camoron.domain

import com.camoron.domain.model.SkyMath
import com.camoron.domain.model.SkyPosition
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/** Reference positions from PyEphem (geocentric, J2000) for Bengaluru, 12.97° N 77.59° E. */
class SkyMathTest {

    private val lat = 12.97
    private val lon = 77.59

    private fun assertNear(actual: SkyPosition, ra: Double, dec: Double, tolerance: Double) {
        val dRa = abs(((actual.raDegrees - ra + 540) % 360) - 180)
        assertThat(dRa * kotlin.math.cos(Math.toRadians(dec))).isLessThan(tolerance)
        assertThat(abs(actual.decDegrees - dec)).isLessThan(tolerance)
    }

    private fun bodies(millis: Long) = SkyMath.solarSystem(millis).associateBy { it.name }

    @Test
    fun `sidereal time matches the reference`() {
        assertThat(SkyMath.localSiderealDegrees(1_793_412_000_000L, lon)).isWithin(0.01).of(146.986)
        assertThat(SkyMath.localSiderealDegrees(1_622_505_600_000L, lon)).isWithin(0.01).of(327.287)
    }

    @Test
    fun `sun, moon and planets land within a fraction of a degree`() {
        val b = bodies(1_793_412_000_000L)
        assertNear(b.getValue("Sun").position, 214.941, -13.943, 0.4)
        // The reference moon is seen from Bengaluru, up to a degree from the earth's centre.
        assertNear(b.getValue("Moon").position, 105.639, 26.098, 1.5)
        assertNear(b.getValue("Mercury").position, 224.251, -18.716, 0.5)
        assertNear(b.getValue("Venus").position, 202.530, -14.898, 0.5)
        assertNear(b.getValue("Mars").position, 140.730, 17.006, 0.5)
        assertNear(b.getValue("Jupiter").position, 146.387, 14.222, 0.5)
        assertNear(b.getValue("Saturn").position, 9.291, 1.082, 0.5)
        assertNear(b.getValue("Uranus").position, 62.379, 20.857, 0.5)
        assertNear(b.getValue("Neptune").position, 2.143, -0.614, 0.5)
    }

    @Test
    fun `other dates hold up too`() {
        val later = bodies(1_899_837_000_000L)
        assertNear(later.getValue("Jupiter").position, 235.188, -18.412, 0.5)
        assertNear(later.getValue("Venus").position, 310.110, -15.868, 0.5)
        assertNear(later.getValue("Sun").position, 355.301, -2.034, 0.4)
        val earlier = bodies(1_622_505_600_000L)
        assertNear(earlier.getValue("Saturn").position, 315.821, -17.491, 0.5)
        assertNear(earlier.getValue("Mars").position, 115.301, 22.782, 0.5)
        assertNear(earlier.getValue("Moon").position, 328.777, -18.088, 1.5)
    }

    @Test
    fun `altitude and bearing match the reference`() {
        val millis = 1_793_412_000_000L
        val lst = SkyMath.localSiderealDegrees(millis, lon)
        val sun = SkyMath.toEastNorthUp(SkyMath.sun(millis), lat, lst)
        val (alt, az) = SkyMath.altitudeAzimuth(sun)
        assertThat(alt).isWithin(0.6).of(17.14)
        assertThat(az).isWithin(0.6).of(109.39)
        val uranus = SkyMath.toEastNorthUp(bodies(millis).getValue("Uranus").position, lat, lst)
        val (uAlt, uAz) = SkyMath.altitudeAzimuth(uranus)
        assertThat(uAlt).isWithin(0.6).of(9.91)
        assertThat(uAz).isWithin(0.6).of(289.38)
    }

    @Test
    fun `a direction straight ahead of the back camera lands in the middle of the picture`() {
        // Phone upright, back camera facing north: phone x = east, y = up, z (screen) = south.
        val upright = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        val out = FloatArray(2)
        val north = doubleArrayOf(0.0, 1.0, 0.0)
        assertThat(SkyMath.project(north, upright, 1000f, 1080f, 1440f, front = false, out = out)).isTrue()
        assertThat(out[0]).isWithin(0.01f).of(540f)
        assertThat(out[1]).isWithin(0.01f).of(720f)

        // A little east of north is right of centre; a little up is above it.
        val eastish = doubleArrayOf(0.1, 1.0, 0.1)
        SkyMath.project(eastish, upright, 1000f, 1080f, 1440f, front = false, out = out)
        assertThat(out[0]).isWithin(0.01f).of(640f)
        assertThat(out[1]).isWithin(0.01f).of(620f)

        // South is behind the back camera.
        assertThat(SkyMath.project(doubleArrayOf(0.0, -1.0, 0.0), upright, 1000f, 1080f, 1440f, front = false, out = out)).isFalse()
    }

    @Test
    fun `with magnetic north east of true north, bearings shrink by the declination`() {
        val east = doubleArrayOf(1.0, 0.0, 0.0)
        SkyMath.towardsMagnetic(east, 10.0)
        assertThat(SkyMath.altitudeAzimuth(east).second).isWithin(1e-6).of(80.0)
    }
}
