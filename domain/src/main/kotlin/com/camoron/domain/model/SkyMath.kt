package com.camoron.domain.model

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A direction on the sky: right ascension and declination in degrees (J2000). */
data class SkyPosition(val raDegrees: Double, val decDegrees: Double)

/** Something in the solar system to point out, with where it is now. */
data class SolarSystemBody(val name: String, val position: SkyPosition, val magnitude: Double, val kind: Kind) {
    enum class Kind { SUN, MOON, PLANET }
}

/**
 * Positions in the sky for Sky view, accurate to a fraction of a degree, which is far better than
 * the phone's compass: the time of day as the stars see it, the sun, the moon and the planets from
 * their orbits, and the turn from the sky's coordinates to east/north/up where the user stands.
 */
object SkyMath {

    /** Days since noon on 1 January 2000 (UTC), the epoch the orbits below are given for. */
    fun daysSinceJ2000(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2_440_587.5 - 2_451_545.0

    /** Local sidereal time in degrees at [longitudeDegrees] (east positive). */
    fun localSiderealDegrees(epochMillis: Long, longitudeDegrees: Double): Double {
        val d = daysSinceJ2000(epochMillis)
        return norm(280.46061837 + 360.98564736629 * d + longitudeDegrees)
    }

    /**
     * Unit vector (east, north, up) towards [position] for an observer at [latitudeDegrees] when
     * the local sidereal time is [lstDegrees]; up below 0 means below the horizon.
     */
    fun toEastNorthUp(position: SkyPosition, latitudeDegrees: Double, lstDegrees: Double, out: DoubleArray = DoubleArray(3)): DoubleArray {
        val h = rad(lstDegrees - position.raDegrees)
        val dec = rad(position.decDegrees)
        val lat = rad(latitudeDegrees)
        out[0] = -cos(dec) * sin(h)
        out[1] = cos(lat) * sin(dec) - sin(lat) * cos(dec) * cos(h)
        out[2] = sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(h)
        return out
    }

    /** Altitude above the horizon and compass bearing (from north, east positive), in degrees, of an east/north/up vector. */
    fun altitudeAzimuth(enu: DoubleArray): Pair<Double, Double> =
        deg(asin(enu[2].coerceIn(-1.0, 1.0))) to norm(deg(atan2(enu[0], enu[1])))

    fun sun(epochMillis: Long): SkyPosition {
        val d = daysSinceJ2000(epochMillis)
        val g = rad(357.529 + 0.98560028 * d)
        val lambda = 280.459 + 0.98564736 * d + 1.915 * sin(g) + 0.020 * sin(2 * g)
        return eclipticToEquatorial(lambda, 0.0, d)
    }

    /** The moon, from the largest terms of its orbit (good to about a quarter of a degree). */
    fun moon(epochMillis: Long): SkyPosition {
        val d = daysSinceJ2000(epochMillis)
        val l = 218.316 + 13.176396 * d
        val m = rad(134.963 + 13.064993 * d)
        val ms = rad(357.529 + 0.98560028 * d)
        val f = rad(93.272 + 13.229350 * d)
        val elong = rad(297.850 + 12.190749 * d)
        val lambda = l + 6.289 * sin(m) + 1.274 * sin(2 * elong - m) + 0.658 * sin(2 * elong) +
            0.214 * sin(2 * m) - 0.186 * sin(ms) - 0.114 * sin(2 * f)
        val beta = 5.128 * sin(f) + 0.281 * sin(m + f) + 0.278 * sin(m - f) + 0.173 * sin(2 * elong - f)
        return eclipticToEquatorial(lambda, beta, d)
    }

    /** The sun, the moon and the planets visible without a telescope, plus Uranus and Neptune. */
    fun solarSystem(epochMillis: Long): List<SolarSystemBody> {
        val t = daysSinceJ2000(epochMillis) / 36_525.0
        val earth = heliocentric(EARTH, t)
        val planets = PLANETS.map { (name, elements) ->
            val p = heliocentric(elements, t)
            val x = p[0] - earth[0]
            val y = p[1] - earth[1]
            val z = p[2] - earth[2]
            val lambda = deg(atan2(y, x))
            val beta = deg(atan2(z, sqrt(x * x + y * y)))
            SolarSystemBody(name, eclipticToEquatorial(lambda, beta, t * 36_525.0), elements.magnitude, SolarSystemBody.Kind.PLANET)
        }
        return listOf(
            SolarSystemBody("Sun", sun(epochMillis), -26.7, SolarSystemBody.Kind.SUN),
            SolarSystemBody("Moon", moon(epochMillis), -12.0, SolarSystemBody.Kind.MOON),
        ) + planets
    }

    /**
     * Where a direction lands in a camera picture [width]×[height] pixels with a focal length of
     * [focalPixels], given [orientation]: the phone's rotation as a 3×3 row-major matrix turning
     * phone axes (x right, y up, z out of the screen) into east/north/up. Returns false when the
     * direction is behind the camera; otherwise [out] holds the x and y pixel. [front] is the selfie
     * camera, which looks out of the screen and shows the scene mirrored.
     */
    fun project(enu: DoubleArray, orientation: FloatArray, focalPixels: Float, width: Float, height: Float, front: Boolean, out: FloatArray): Boolean {
        // Phone coordinates are the transpose of the rotation applied to the direction.
        val x = orientation[0] * enu[0] + orientation[3] * enu[1] + orientation[6] * enu[2]
        val y = orientation[1] * enu[0] + orientation[4] * enu[1] + orientation[7] * enu[2]
        val z = orientation[2] * enu[0] + orientation[5] * enu[1] + orientation[8] * enu[2]
        val depth = if (front) z else -z
        if (depth <= MIN_DEPTH) return false
        out[0] = (width / 2 + focalPixels * x / depth).toFloat()
        out[1] = (height / 2 - focalPixels * y / depth).toFloat()
        return true
    }

    /**
     * Turns an east/north/up vector measured from true north into one measured from magnetic north,
     * which lies [declinationDegrees] east of it, as the phone's compass does. In place.
     */
    fun towardsMagnetic(enu: DoubleArray, declinationDegrees: Double): DoubleArray {
        val c = cos(rad(declinationDegrees))
        val s = sin(rad(declinationDegrees))
        val east = enu[0]
        val north = enu[1]
        enu[0] = east * c - north * s
        enu[1] = north * c + east * s
        return enu
    }

    /** Angle in degrees between two unit vectors. */
    fun separationDegrees(a: DoubleArray, b: DoubleArray): Double =
        deg(acosClamped(a[0] * b[0] + a[1] * b[1] + a[2] * b[2]))

    private fun eclipticToEquatorial(lambdaDegrees: Double, betaDegrees: Double, d: Double): SkyPosition {
        val eps = rad(23.439 - 0.0000004 * d)
        val l = rad(lambdaDegrees)
        val b = rad(betaDegrees)
        val x = cos(b) * cos(l)
        val y = cos(eps) * cos(b) * sin(l) - sin(eps) * sin(b)
        val z = sin(eps) * cos(b) * sin(l) + cos(eps) * sin(b)
        return SkyPosition(norm(deg(atan2(y, x))), deg(asin(z.coerceIn(-1.0, 1.0))))
    }

    /** Ecliptic x, y, z (astronomical units) of a planet [t] Julian centuries after J2000. */
    private fun heliocentric(e: Orbit, t: Double): DoubleArray {
        val a = e.a + e.aRate * t
        val ecc = e.e + e.eRate * t
        val inc = rad(e.i + e.iRate * t)
        val meanLong = e.l + e.lRate * t
        val peri = e.peri + e.periRate * t
        val node = e.node + e.nodeRate * t
        val m = rad(norm(meanLong - peri))
        val w = rad(peri - node)
        val om = rad(node)
        // Kepler's equation for the eccentric anomaly.
        var ea = m + ecc * sin(m)
        repeat(8) { ea -= (ea - ecc * sin(ea) - m) / (1 - ecc * cos(ea)) }
        val xp = a * (cos(ea) - ecc)
        val yp = a * sqrt(1 - ecc * ecc) * sin(ea)
        val cw = cos(w); val sw = sin(w); val co = cos(om); val so = sin(om); val ci = cos(inc); val si = sin(inc)
        return doubleArrayOf(
            (cw * co - sw * so * ci) * xp + (-sw * co - cw * so * ci) * yp,
            (cw * so + sw * co * ci) * xp + (-sw * so + cw * co * ci) * yp,
            (sw * si) * xp + (cw * si) * yp,
        )
    }

    /** Orbit elements at J2000 and their change per Julian century (JPL's approximate planet positions, 1800–2050). */
    private class Orbit(
        val a: Double, val aRate: Double,
        val e: Double, val eRate: Double,
        val i: Double, val iRate: Double,
        val l: Double, val lRate: Double,
        val peri: Double, val periRate: Double,
        val node: Double, val nodeRate: Double,
        /** Typical brightness, used to size its mark. */
        val magnitude: Double = 0.0,
    )

    private val EARTH = Orbit(1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668, 100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0)

    private val PLANETS = listOf(
        "Mercury" to Orbit(0.38709927, 0.00000037, 0.20563593, 0.00001906, 7.00497902, -0.00594749, 252.25032350, 149472.67411175, 77.45779628, 0.16047689, 48.33076593, -0.12534081, magnitude = 0.0),
        "Venus" to Orbit(0.72333566, 0.00000390, 0.00677672, -0.00004107, 3.39467605, -0.00078890, 181.97909950, 58517.81538729, 131.60246718, 0.00268329, 76.67984255, -0.27769418, magnitude = -4.2),
        "Mars" to Orbit(1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131, -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343, magnitude = 0.5),
        "Jupiter" to Orbit(5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714, 34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106, magnitude = -2.2),
        "Saturn" to Orbit(9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609, 49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794, magnitude = 0.6),
        "Uranus" to Orbit(19.18916464, -0.00196176, 0.04725744, -0.00004397, 0.77263783, -0.00242939, 313.23810451, 428.48202785, 170.95427630, 0.40805281, 74.01692503, 0.04240589, magnitude = 5.7),
        "Neptune" to Orbit(30.06992276, 0.00026291, 0.00859048, 0.00005105, 1.77004347, 0.00035372, -55.12002969, 218.45945325, 44.96476227, -0.32241464, 131.78422574, -0.00508664, magnitude = 7.8),
    )

    private const val MIN_DEPTH = 0.05
    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI
    private fun norm(d: Double): Double = ((d % 360) + 360) % 360
    private fun acosClamped(x: Double) = kotlin.math.acos(x.coerceIn(-1.0, 1.0))
}
