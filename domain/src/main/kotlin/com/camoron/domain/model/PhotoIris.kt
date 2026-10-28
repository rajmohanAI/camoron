package com.camoron.domain.model

import com.camoron.domain.model.TryOnDrawing.Face
import com.camoron.domain.model.TryOnDrawing.Pt
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Eye colour like a coloured contact lens rather than a tint: a new iris with radial fibres, a
 * lighter ring round the pupil and a darker ring at its edge, lit as brightly as the whites of
 * the eye say it should be, keeping the person's own pupil, iris detail and catchlights, and
 * shaded under the upper lid.
 */
object PhotoIris {

    /** Renders [tryOn]'s eye colour for each face in [scene] into [photo]. */
    fun render(photo: PhotoPixels, tryOn: TryOn, scene: FaceScene) {
        if (tryOn.eyes == EyeColour.NONE) return
        // Real irises are less saturated than a swatch: pull the colour a quarter of the way to grey.
        val swatch = floatArrayOf(LINEAR[(tryOn.eyes.argb shr 16) and 0xFF], LINEAR[(tryOn.eyes.argb shr 8) and 0xFF], LINEAR[tryOn.eyes.argb and 0xFF])
        val grey = 0.2126f * swatch[0] + 0.7152f * swatch[1] + 0.0722f * swatch[2]
        val target = FloatArray(3) { (swatch[it] + (grey - swatch[it]) * 0.25f) * IRIS_ALBEDO }
        for (shape in scene.faces) {
            if (shape.count < IRIS_POINTS) continue
            val face = Face(shape, photo.width.toFloat(), photo.height.toFloat())
            for (left in listOf(true, false)) {
                val (centre, grown) = TryOnDrawing.iris(face, left)
                renderEye(photo, centre, grown * RADIUS, TryOnDrawing.smoothClosed(TryOnDrawing.eyeOpening(face, left)), target)
            }
        }
    }

    private fun renderEye(photo: PhotoPixels, c: Pt, radius: Float, lids: List<Pt>, target: FloatArray) {
        if (radius < 2f) return
        // The eye opening's box covers the iris and the whites beside it.
        val x0 = floor(minOf(lids.minOf { it.x }, c.x - radius) - 1).toInt().coerceIn(0, photo.width)
        val y0 = floor(minOf(lids.minOf { it.y }, c.y - radius) - 1).toInt().coerceIn(0, photo.height)
        val x1 = ceil(maxOf(lids.maxOf { it.x }, c.x + radius) + 1).toInt().coerceIn(x0, photo.width)
        val y1 = ceil(maxOf(lids.maxOf { it.y }, c.y + radius) + 1).toInt().coerceIn(y0, photo.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 3 || h < 3) return
        val argb = photo.read(x0, y0, w, h)
        val lum = FloatArray(w * h) { luminance(argb[it]) }

        // How far inside the lids each pixel is (pixels, negative outside), and where the iris shows.
        val open = FloatArray(w * h)
        var irisSum = 0f
        var irisCount = 0
        val whites = ArrayList<Float>()
        for (y in 0 until h) for (x in 0 until w) {
            val p = Pt(x0 + x + 0.5f, y0 + y + 0.5f)
            val inside = insideDistance(lids, p)
            open[y * w + x] = inside
            if (inside <= 0.5f) continue
            val r = distance(p, c) / radius
            if (r in PUPIL_EDGE..0.9f) {
                irisSum += lum[y * w + x]
                irisCount++
            } else if (r > 1.15f) {
                whites += lum[y * w + x]
            }
        }
        if (irisCount == 0) return
        val irisMean = (irisSum / irisCount).coerceAtLeast(1e-4f)
        // The whites of the eye stand in for a known bright surface: their brightest half shows the light.
        // Whites never darker than a few times the iris, should the eye be shaded or nearly shut.
        val measured = if (whites.size >= 8) whites.sorted().let { it.subList(it.size / 2, it.size).average().toFloat() } else 0f
        val white = max(measured, irisMean * 2.5f)
        val light = max(white / SCLERA, irisMean * 1.5f)

        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val p = Pt(x0 + x + 0.5f, y0 + y + 0.5f)
            val dx = p.x - c.x
            val dy = p.y - c.y
            val r = sqrt(dx * dx + dy * dy) / radius
            // Soft edges in proportion to the eye's size, so the new iris is no crisper than the photo.
            val soft = max(1f, radius * EDGE_SOFT)
            val cover = (radius * (1f - r) / soft + 0.5f).coerceIn(0f, 1f) * (open[i] / (soft * 1.6f) + 0.5f).coerceIn(0f, 1f)
            if (cover <= 0f) continue
            val theta = atan2(dy, dx)
            val ct = cos(theta)
            val st = sin(theta)
            // Radial fibres: fine streaks out from the pupil, and broader patches.
            val fine = valueNoise(ct * 26f + r * 0.8f, st * 26f + r * 0.8f)
            val broad = valueNoise(ct * 7f + r * 2.5f + 11f, st * 7f + r * 2.5f)
            val fibres = 0.78f + 0.3f * fine + 0.22f * (broad - 0.5f)
            // A lighter, warmer ring round the pupil, and a dark ring at the edge.
            val collar = smooth(PUPIL_EDGE, 0.45f, r) * (1f - smooth(0.45f, 0.62f, r))
            val limbus = 1f - 0.45f * smooth(0.75f, 1f, r)
            // The upper lid shades the top of the iris, and both lids darken it where they meet it.
            val lid = (1f - 0.5f * smooth(-0.1f, -0.85f, dy / radius)) * (1f - 0.3f * smooth(radius * 0.35f, 0f, open[i]))
            val detail = (lum[i] / irisMean).coerceIn(0.55f, 1.8f).pow(0.8f)
            val shade = light * fibres * limbus * lid * detail * (1f + 0.3f * collar)
            val o = argb[i]
            val or = LINEAR[(o shr 16) and 0xFF]
            val og = LINEAR[(o shr 8) and 0xFF]
            val ob = LINEAR[o and 0xFF]
            var nr = (target[0] + (WARM[0] - target[0]) * COLLAR * collar) * shade
            var ng = (target[1] + (WARM[1] - target[1]) * COLLAR * collar) * shade
            var nb = (target[2] + (WARM[2] - target[2]) * COLLAR * collar) * shade
            // Keep the person's own pupil and any catchlight.
            val bright = smooth(white * 0.75f, white * 1.05f, lum[i])
            val keep = max(1f - smooth(PUPIL_EDGE * 0.8f, PUPIL_EDGE, r), bright * (1f - smooth(0.6f, 0.75f, r)))
            nr += (or - nr) * keep
            ng += (og - ng) * keep
            nb += (ob - nb) * keep
            // Bright pixels at the rim are the whites showing past a slightly generous circle: mostly leave them.
            val a = cover * STRENGTH * (1f - 0.7f * bright * smooth(0.8f, 0.95f, r))
            val fr = or + (nr - or) * a
            val fg = og + (ng - og) * a
            val fb = ob + (nb - ob) * a
            argb[i] = (o and 0xFF000000.toInt()) or (encode(fr) shl 16) or (encode(fg) shl 8) or encode(fb)
        }
        photo.write(argb, x0, y0, w, h)
    }

    /** How far [p] is inside the closed outline [poly], in its units; negative outside. */
    private fun insideDistance(poly: List<Pt>, p: Pt): Float {
        var best = Float.MAX_VALUE
        var inside = false
        for (k in poly.indices) {
            val a = poly[k]
            val b = poly[(k + 1) % poly.size]
            best = minOf(best, segmentDistance(a, b, p))
            if ((a.y > p.y) != (b.y > p.y) && p.x < a.x + (p.y - a.y) / (b.y - a.y) * (b.x - a.x)) inside = !inside
        }
        return if (inside) best else -best
    }

    private fun distance(a: Pt, b: Pt): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun luminance(c: Int) = 0.2126f * LINEAR[(c shr 16) and 0xFF] + 0.7152f * LINEAR[(c shr 8) and 0xFF] + 0.0722f * LINEAR[c and 0xFF]

    private const val IRIS_POINTS = 478
    /** Where the pupil ends and the iris starts, as a fraction of the iris radius. */
    private const val PUPIL_EDGE = 0.34f
    /** How much light the whites of the eye reflect. */
    private const val SCLERA = 0.65f
    private const val COLLAR = 0.28f
    private const val STRENGTH = 0.94f
    /** The iris circle from [TryOnDrawing.iris] is drawn a touch large for a tint; a lens follows the iris itself. */
    private const val RADIUS = 0.93f
    /** How much of the swatch's light an iris reflects. */
    private const val IRIS_ALBEDO = 0.6f
    /** How soft the iris's edges are, as a fraction of its radius (more again under the lids). */
    private const val EDGE_SOFT = 0.05f
    private val WARM = floatArrayOf(0.42f, 0.26f, 0.08f)
}
