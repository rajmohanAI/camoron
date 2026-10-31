package com.camoron.feature.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camoron.domain.model.GeoPoint
import com.camoron.domain.model.SkyCatalogue
import com.camoron.domain.model.SkyMath
import com.camoron.domain.model.SkyPosition
import com.camoron.domain.model.SolarSystemBody
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What Sky view reads each frame. */
internal class SkyFeed(
    val orientation: () -> FloatArray?,
    val location: () -> GeoPoint?,
    val compassUnreliable: () -> Boolean,
    val declination: (GeoPoint, Long) -> Float,
)

/**
 * Sky view: draws the stars, constellation figures, sun, moon and planets where they are in the
 * camera's picture, with the horizon and compass points, and names whatever is in the middle.
 * Fills the camera's whole (uncropped) picture, which a focal length of [focalLength35mm] zoomed by
 * [zoom] spans.
 */
@Composable
internal fun SkyOverlay(
    catalogue: SkyCatalogue?,
    feed: SkyFeed,
    focalLength35mm: Float?,
    zoom: Float,
    front: Boolean,
    modifier: Modifier = Modifier,
) {
    val sky = remember(catalogue) { catalogue?.let { SkyScene(it) } }
    var frame by remember { mutableLongStateOf(0L) }
    var location by remember { mutableStateOf<GeoPoint?>(null) }
    var unreliable by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<SkyScene.Target?>(null) }
    val smoothed = remember { FloatArray(9) }
    var haveOrientation by remember { mutableStateOf(false) }

    LaunchedEffect(sky) {
        var lastPlaced = 0L
        while (true) {
            withFrameMillis { }
            val now = System.currentTimeMillis()
            val here = feed.location()
            location = here
            unreliable = feed.compassUnreliable()
            feed.orientation()?.let { m ->
                // Ease towards each new reading so the labels don't shiver with the sensor's noise.
                val k = if (haveOrientation) SMOOTHING else 1f
                for (i in 0 until 9) smoothed[i] += (m[i] - smoothed[i]) * k
                haveOrientation = true
            }
            if (sky != null && here != null && (now - lastPlaced > PLACE_EVERY_MS || sky.placedFor != here)) {
                sky.place(here, now, feed.declination(here, now).toDouble())
                lastPlaced = now
            }
            if (sky != null && haveOrientation && sky.placedFor != null) target = sky.target(smoothed, front)
            frame++
        }
    }

    val measurer = rememberTextMeasurer()
    val compass = stringArrayResource(R.array.compass_points)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            // Reading the frame count redraws this on every frame.
            if (frame < 0 || sky == null || !haveOrientation || sky.placedFor == null) return@Canvas
            val focal = ((focalLength35mm ?: DEFAULT_FOCAL_35MM) / FULL_FRAME_DIAGONAL_MM * hypot(size.width, size.height)) * zoom
            sky.draw(this, measurer, smoothed, focal, front, compass)
        }
        // The crosshair in the middle, and what it is on.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.7f), radius = 18.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val hint = when {
                catalogue == null -> stringResource(R.string.sky_loading)
                location == null -> stringResource(R.string.sky_need_location)
                unreliable -> stringResource(R.string.sky_calibrate)
                else -> null
            }
            hint?.let { SkyLabel(it) }
            target?.let { t ->
                val bearing = compass[((t.azimuth / 45.0).roundToInt()) % 8]
                SkyLabel(stringResource(R.string.sky_target, t.name, t.altitude.roundToInt(), bearing))
            }
        }
    }
}

@Composable
private fun SkyLabel(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 15.sp,
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/**
 * The catalogue placed in the sky above one spot at one moment: every point turned into an
 * east/north/up direction (measured from magnetic north, as the phone's compass is), ready to be
 * projected onto the picture each frame.
 */
private class SkyScene(private val catalogue: SkyCatalogue) {

    class Target(val name: String, val altitude: Double, val azimuth: Double)

    private class Body(val name: String, val kind: SolarSystemBody.Kind, val magnitude: Double, val enu: DoubleArray)

    var placedFor: GeoPoint? = null
        private set

    private val stars = DoubleArray(catalogue.stars.size * 3)
    private val strokes = catalogue.constellations.flatMap { it.strokes }.map { DoubleArray(it.size * 3) }
    private val labels = DoubleArray(catalogue.constellations.size * 3)
    private var bodies: List<Body> = emptyList()
    private val horizon = DoubleArray(HORIZON_STEPS * 3)
    private val cardinals = DoubleArray(8 * 3)
    private var declination = 0.0
    private val scratch = DoubleArray(3)
    private val point = FloatArray(2)
    private val previous = FloatArray(2)

    fun place(here: GeoPoint, epochMillis: Long, declinationDegrees: Double) {
        declination = declinationDegrees
        val lst = SkyMath.localSiderealDegrees(epochMillis, here.longitude)
        val lat = here.latitude
        fun put(position: SkyPosition, into: DoubleArray, at: Int) {
            SkyMath.toEastNorthUp(position, lat, lst, scratch)
            SkyMath.towardsMagnetic(scratch, declinationDegrees)
            into[at] = scratch[0]; into[at + 1] = scratch[1]; into[at + 2] = scratch[2]
        }
        catalogue.stars.forEachIndexed { i, star -> put(star.position, stars, i * 3) }
        var s = 0
        for (constellation in catalogue.constellations) {
            for (stroke in constellation.strokes) {
                val out = strokes[s++]
                stroke.forEachIndexed { i, p -> put(p, out, i * 3) }
            }
        }
        catalogue.constellations.forEachIndexed { i, c -> put(c.label, labels, i * 3) }
        bodies = SkyMath.solarSystem(epochMillis).map { body ->
            Body(body.name, body.kind, body.magnitude, DoubleArray(3).also { put(body.position, it, 0) })
        }
        for (i in 0 until HORIZON_STEPS) bearing(i * 360.0 / HORIZON_STEPS, horizon, i * 3)
        for (i in 0 until 8) bearing(i * 45.0, cardinals, i * 3)
        placedFor = here
    }

    /** A point on the horizon at a true compass bearing. */
    private fun bearing(degrees: Double, into: DoubleArray, at: Int) {
        val r = Math.toRadians(degrees)
        scratch[0] = kotlin.math.sin(r); scratch[1] = kotlin.math.cos(r); scratch[2] = 0.0
        SkyMath.towardsMagnetic(scratch, declination)
        into[at] = scratch[0]; into[at + 1] = scratch[1]; into[at + 2] = scratch[2]
    }

    fun draw(scope: DrawScope, measurer: TextMeasurer, m: FloatArray, focal: Float, front: Boolean, compass: Array<String>) = with(scope) {
        val w = size.width
        val h = size.height
        val margin = 0.25f * maxOf(w, h)
        fun project(v: DoubleArray, at: Int, out: FloatArray): Boolean {
            scratch[0] = v[at]; scratch[1] = v[at + 1]; scratch[2] = v[at + 2]
            if (!SkyMath.project(scratch, m, focal, w, h, front, out)) return false
            return out[0] > -margin && out[0] < w + margin && out[1] > -margin && out[1] < h + margin
        }

        // The horizon, and the compass points along it.
        val horizonColor = Color(0xFF7FD69A).copy(alpha = 0.7f)
        var had = false
        for (i in 0..HORIZON_STEPS) {
            val ok = project(horizon, (i % HORIZON_STEPS) * 3, point)
            if (ok && had) drawLine(horizonColor, Offset(previous[0], previous[1]), Offset(point[0], point[1]), 1.5.dp.toPx())
            had = ok
            previous[0] = point[0]; previous[1] = point[1]
        }
        for (i in 0 until 8) {
            if (!project(cardinals, i * 3, point)) continue
            label(measurer, compass[i], point, horizonColor, if (i % 2 == 0) 14 else 11, below = true)
        }

        // Constellation figures.
        val figure = Color(0xFF9DB7FF).copy(alpha = 0.45f)
        val lineWidth = 1.dp.toPx()
        for (stroke in strokes) {
            had = false
            for (i in 0 until stroke.size / 3) {
                val ok = project(stroke, i * 3, point)
                if (ok && had) drawLine(figure, Offset(previous[0], previous[1]), Offset(point[0], point[1]), lineWidth)
                had = ok
                previous[0] = point[0]; previous[1] = point[1]
            }
        }
        catalogue.constellations.forEachIndexed { i, c ->
            if (project(labels, i * 3, point)) label(measurer, c.name, point, figure.copy(alpha = 0.8f), 12)
        }

        // Stars, sized by brightness and tinted by colour; those below the horizon dimmed.
        val unit = 1.dp.toPx()
        catalogue.stars.forEachIndexed { i, star ->
            if (!project(stars, i * 3, point)) return@forEachIndexed
            val below = stars[i * 3 + 2] < 0
            val radius = (3.4 - 0.55 * star.magnitude).coerceAtLeast(0.7).toFloat() * unit
            drawCircle(starColor(star.bv).copy(alpha = if (below) 0.35f else 1f), radius, Offset(point[0], point[1]))
            if (star.name != null && star.magnitude <= NAMED_STAR_MAGNITUDE) {
                label(measurer, star.name, point, Color.White.copy(alpha = if (below) 0.4f else 0.85f), 11, offset = radius)
            }
        }

        // The sun, the moon and the planets, always named.
        for (body in bodies) {
            if (!project(body.enu, 0, point)) continue
            val (color, radius) = when (body.kind) {
                SolarSystemBody.Kind.SUN -> Color(0xFFFFD54F) to 14f
                SolarSystemBody.Kind.MOON -> Color(0xFFE0E0E0) to 12f
                SolarSystemBody.Kind.PLANET -> Color(0xFFFFB74D) to (5.0 - 0.5 * body.magnitude).coerceIn(3.0, 7.0).toFloat()
            }
            val below = body.enu[2] < 0
            drawCircle(color.copy(alpha = if (below) 0.45f else 1f), radius * unit, Offset(point[0], point[1]))
            label(measurer, body.name, point, color.copy(alpha = if (below) 0.55f else 1f), 14, offset = radius * unit)
        }
    }

    private fun DrawScope.label(
        measurer: TextMeasurer,
        text: String,
        at: FloatArray,
        color: Color,
        sizeSp: Int,
        offset: Float = 0f,
        below: Boolean = false,
    ) {
        val layout = measurer.measure(text, TextStyle(color = color, fontSize = sizeSp.sp))
        val x = at[0] - layout.size.width / 2f
        val y = if (below) at[1] + 4.dp.toPx() else at[1] - offset - layout.size.height - 2.dp.toPx()
        drawText(layout, topLeft = Offset(x, y))
    }

    /** What the middle of the picture is on: a planet, the sun or moon first, then a named star, then a constellation. */
    fun target(m: FloatArray, front: Boolean): Target? {
        // The camera looks along the phone's −z (back) or +z (front), in east/north/up.
        val sign = if (front) 1.0 else -1.0
        val view = doubleArrayOf(sign * m[2], sign * m[5], sign * m[8])
        fun near(v: DoubleArray, at: Int, within: Double): Double? {
            scratch[0] = v[at]; scratch[1] = v[at + 1]; scratch[2] = v[at + 2]
            return SkyMath.separationDegrees(view, scratch).takeIf { it <= within }
        }
        fun describe(name: String, v: DoubleArray, at: Int): Target {
            scratch[0] = v[at]; scratch[1] = v[at + 1]; scratch[2] = v[at + 2]
            // Bearings are shown from true north.
            SkyMath.towardsMagnetic(scratch, -declination)
            val (alt, az) = SkyMath.altitudeAzimuth(scratch)
            return Target(name, alt, az)
        }
        bodies.mapNotNull { b -> near(b.enu, 0, BODY_RADIUS)?.let { it to b } }.minByOrNull { it.first }
            ?.let { (_, b) -> return describe(b.name, b.enu, 0) }
        var best: Pair<Double, Int>? = null
        catalogue.stars.forEachIndexed { i, star ->
            if (star.name == null || star.magnitude > NAMED_STAR_MAGNITUDE) return@forEachIndexed
            val d = near(stars, i * 3, STAR_RADIUS) ?: return@forEachIndexed
            if (best == null || d < best!!.first) best = d to i
        }
        best?.let { (_, i) -> return describe(catalogue.stars[i].name!!, stars, i * 3) }
        var closest: Pair<Double, Int>? = null
        catalogue.constellations.forEachIndexed { i, _ ->
            val d = near(labels, i * 3, CONSTELLATION_RADIUS) ?: return@forEachIndexed
            if (closest == null || d < closest!!.first) closest = d to i
        }
        return closest?.let { (_, i) -> describe(catalogue.constellations[i].name, labels, i * 3) }
    }

    private fun starColor(bv: Double): Color = when {
        bv < 0.0 -> Color(0xFFAFC8FF)
        bv < 0.3 -> Color(0xFFE4ECFF)
        bv < 0.6 -> Color(0xFFFFFFFF)
        bv < 1.0 -> Color(0xFFFFF1C9)
        bv < 1.4 -> Color(0xFFFFD9A0)
        else -> Color(0xFFFFB98A)
    }

    private companion object {
        const val HORIZON_STEPS = 180
        /** Stars this bright or brighter are named on the picture. */
        const val NAMED_STAR_MAGNITUDE = 1.6
        const val BODY_RADIUS = 6.0
        const val STAR_RADIUS = 4.0
        const val CONSTELLATION_RADIUS = 15.0
    }
}

private const val FULL_FRAME_DIAGONAL_MM = 43.27f
private const val DEFAULT_FOCAL_35MM = 26f
/** How much of each new compass reading to take per frame. */
private const val SMOOTHING = 0.2f
/** The sky turns about a quarter of a degree a minute; placing it every few seconds is plenty. */
private const val PLACE_EVERY_MS = 5_000L
