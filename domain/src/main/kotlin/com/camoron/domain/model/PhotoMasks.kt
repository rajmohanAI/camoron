package com.camoron.domain.model

import com.camoron.domain.model.TryOnDrawing.EyeFrame
import com.camoron.domain.model.TryOnDrawing.Face
import com.camoron.domain.model.TryOnDrawing.Pt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Masks rendered into the photo pixel by pixel: fabric that follows the face's curve and takes
 * the photo's light and the face's own shading, with pleats, seams, weave and fibres, ear loops,
 * and the shadow a mask casts. Eye masks get a padded edge, gold filigree or satin.
 *
 * Works in eye units (pupils at x = ∓1, y down the face), like [PhotoGlasses].
 */
object PhotoMasks {

    /** Renders [tryOn]'s mask for each face in [scene] into [photo]. */
    fun render(photo: PhotoPixels, tryOn: TryOn, scene: FaceScene) {
        if (tryOn.mask == MaskStyle.NONE) return
        for (shape in scene.faces) {
            if (shape.count < FACE_POINTS) continue
            val face = Face(shape, photo.width.toFloat(), photo.height.toFloat())
            when (tryOn.mask) {
                MaskStyle.SURGICAL, MaskStyle.N95, MaskStyle.CLOTH -> faceMask(photo, face, tryOn.mask, scene.hair)
                MaskStyle.MASQUERADE, MaskStyle.HERO -> eyeMask(photo, face, tryOn.mask)
                MaskStyle.NONE -> Unit
            }
        }
    }

    /** A rectangle of the photo in linear light, with the light found in it. */
    private class Patch(val photo: PhotoPixels, val x0: Int, val y0: Int, val w: Int, val h: Int, face: Face, unit: Float) {
        val argb = photo.read(x0, y0, w, h)
        val r = FloatArray(w * h) { LINEAR[(argb[it] shr 16) and 0xFF] }
        val g = FloatArray(w * h) { LINEAR[(argb[it] shr 8) and 0xFF] }
        val b = FloatArray(w * h) { LINEAR[argb[it] and 0xFF] }
        val light = PhotoLight.of(argb, w, h, face, unit, x0, y0)

        fun save() {
            for (i in argb.indices) argb[i] = (argb[i] and 0xFF000000.toInt()) or (encode(r[i]) shl 16) or (encode(g[i]) shl 8) or encode(b[i])
            photo.write(argb, x0, y0, w, h)
        }

        companion object {
            /** The patch round [points] (eye units) with [margin] eye units to spare, or null if it is off the photo. */
            fun around(photo: PhotoPixels, frame: EyeFrame, face: Face, points: List<Pt>, margin: Float): Patch? {
                val corners = points.flatMap { listOf(Pt(it.x - margin, it.y - margin), Pt(it.x + margin, it.y + margin)) }.map(frame::toPhoto)
                val x0 = floor(corners.minOf { it.x }).toInt().coerceIn(0, photo.width)
                val y0 = floor(corners.minOf { it.y }).toInt().coerceIn(0, photo.height)
                val x1 = ceil(corners.maxOf { it.x }).toInt().coerceIn(x0, photo.width)
                val y1 = ceil(corners.maxOf { it.y }).toInt().coerceIn(y0, photo.height)
                if (x1 - x0 < 2 || y1 - y0 < 2) return null
                return Patch(photo, x0, y0, x1 - x0, y1 - y0, face, frame.unit)
            }
        }
    }

    // --- Face masks ------------------------------------------------------------------------

    private class Fabric(val albedo: FloatArray, val sheen: Float, val loop: FloatArray)

    private fun fabricOf(style: MaskStyle) = when (style) {
        MaskStyle.SURGICAL -> Fabric(floatArrayOf(0.33f, 0.56f, 0.76f), sheen = 0.04f, loop = floatArrayOf(0.85f, 0.85f, 0.85f))
        MaskStyle.N95 -> Fabric(floatArrayOf(0.84f, 0.84f, 0.82f), sheen = 0.03f, loop = floatArrayOf(0.8f, 0.8f, 0.8f))
        else -> Fabric(floatArrayOf(0.028f, 0.03f, 0.034f), sheen = 0.035f, loop = floatArrayOf(0.025f, 0.025f, 0.028f))
    }

    private fun faceMask(photo: PhotoPixels, face: Face, style: MaskStyle, hair: HairMask?) {
        val frame = EyeFrame(face.mid(33, 133), face.mid(362, 263))
        val unit = frame.unit
        if (unit < 4f) return
        val (topPx, bottomPx) = TryOnDrawing.faceMaskEdges(face)
        val top = TryOnDrawing.smoothOpen(topPx).map(frame::fromPhoto)
        val bottom = TryOnDrawing.smoothOpen(bottomPx).map(frame::fromPhoto)
        val outline = TryOnDrawing.faceMaskOutline(topPx, bottomPx).map(frame::fromPhoto)
        val nose = frame.fromPhoto(face.pt(NOSE_TIP))
        val halfWidth = max(0.5f, (top.last().x - top.first().x) / 2f)
        val earLeft = frame.fromPhoto(face.pt(234).awayFrom(face.pt(NOSE_TIP), 1.08f).lerp(face.pt(127), 0.5f))
        val earRight = frame.fromPhoto(face.pt(454).awayFrom(face.pt(NOSE_TIP), 1.08f).lerp(face.pt(356), 0.5f))
        val loops = listOf(
            Stroke(listOf(top.first(), earLeft, bottom.first()), LOOP_RADIUS),
            Stroke(listOf(top.last(), earRight, bottom.last()), LOOP_RADIUS),
        )
        val patch = Patch.around(photo, frame, face, outline + listOf(earLeft, earRight), 0.35f) ?: return
        val field = DistanceField(listOf(outline), COARSE)
        val fabric = fabricOf(style)
        val pixel = 1f / unit
        val w = patch.w
        val h = patch.h

        // The face's own shading under the mask, smoothed: carried onto the fabric so it shows the
        // nose and cheeks and any side light the way the skin did.
        val shading = faceShading(patch, frame, field)

        // Where down the mask each point is, 0 at the top edge and 1 at the bottom: smooth, so kept
        // on a coarse grid rather than measured to both edges at every pixel.
        val down = SmoothGrid(outline, 0.015f) { q ->
            val dTop = polylineDistance(top, q)
            val dBottom = polylineDistance(bottom, q)
            dTop / max(1e-4f, dTop + dBottom)
        }
        val shadowDrop = Pt(-patch.light.light[0] * 0.08f, 0.13f)
        val normal = FloatArray(3)
        val colour = FloatArray(3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = Pt(patch.x0 + x + 0.5f, patch.y0 + y + 0.5f)
                val q = frame.fromPhoto(px)
                val i = y * w + x
                val d = field.distance(q)

                // Shadow on the skin below, and a little contact shadow all round.
                if (d > -pixel) {
                    val drop = soft(field.distance(q - shadowDrop), 0.12f)
                    val contact = smooth(0.06f, 0f, d)
                    val k = 1f - max(MASK_SHADOW * drop, CONTACT_SHADOW * contact)
                    patch.r[i] *= k
                    patch.g[i] *= k
                    patch.b[i] *= k
                }

                // Ear loops, behind the mask and under the hair.
                for (l in loops.indices) {
                    val loop = loops[l]
                    if (!loop.near(q, pixel)) continue
                    val dist = polylineDistance(loop.line, q)
                    var cover = coverage(LOOP_RADIUS - dist, pixel)
                    if (cover <= 0f) continue
                    if (hair != null) cover *= 1f - 0.85f * hair.at(px.x / photo.width, px.y / photo.height)
                    val (n, across) = polylineNormal(loop.line, q)
                    val s = (across / LOOP_RADIUS).coerceIn(-0.95f, 0.95f)
                    shadeFabric(fabric.loop, 0.05f, n.x * s, n.y * s, sqrt(1f - s * s), 1f, patch.light, colour)
                    blend(patch.r, patch.g, patch.b, i, colour[0], colour[1], colour[2], cover)
                }

                val cover = coverage(-d, pixel)
                if (cover <= 0f) continue
                // Where on the mask: u across (−1 left cheek, +1 right), v down (0 top edge, 1 bottom).
                val v = down.at(q)
                val u = ((q.x - nose.x) / halfWidth).coerceIn(-1.2f, 1.2f)
                maskNormal(style, u, v, normal)
                // Soft wrinkles where the fabric doesn't lie perfectly flat.
                val e = 0.02f
                val c0 = wrinkles(q.x, q.y)
                normal[0] += (wrinkles(q.x + e, q.y) - c0) / e * WRINKLE
                normal[1] += (wrinkles(q.x, q.y + e) - c0) / e * WRINKLE
                var texture = 1f
                when (style) {
                    MaskStyle.SURGICAL -> {
                        // The folds wander a little, as real pleats do.
                        pleat(v + 0.025f * (valueNoise(u * 3f + 7f, 1.3f) - 0.5f), normal)
                        // Non-woven fibres: fine, faint mottling.
                        texture = 1f + 0.05f * (valueNoise(q.x * 90f, q.y * 90f) - 0.5f)
                        // Bound edges: a raised folded strip round the outside.
                        val edge = smooth(0.07f, 0.0f, -d)
                        if (edge > 0f) {
                            val n = field.normal(q)
                            tilt(normal, n.x, n.y, 0.6f * edge)
                            texture *= 1f - 0.08f * smooth(0.075f, 0.065f, -d)
                        }
                    }
                    MaskStyle.N95 -> {
                        texture = 1f + 0.07f * (valueNoise(q.x * 60f, q.y * 60f) - 0.5f) + 0.03f * (valueNoise(q.x * 200f, q.y * 200f) - 0.5f)
                        // Moulded rim round the edge.
                        val edge = smooth(0.05f, 0.0f, -d)
                        if (edge > 0f) {
                            val n = field.normal(q)
                            tilt(normal, n.x, n.y, 0.5f * edge)
                        }
                    }
                    else -> {
                        // Woven cotton: a fine over-under weave with soft unevenness.
                        val weave = sin(q.x * 260f) * sin(q.y * 260f)
                        texture = 1f + 0.14f * weave + 0.1f * (valueNoise(q.x * 30f, q.y * 30f) - 0.5f)
                    }
                }
                normalise(normal)
                val k = shading[i]
                shadeFabric(fabric.albedo, fabric.sheen, normal[0], normal[1], normal[2], texture * k, patch.light, colour)
                if (style == MaskStyle.SURGICAL || style == MaskStyle.N95) {
                    // A centre seam (N95) or the nose wire (surgical) reads as a thin line.
                    val seam = if (style == MaskStyle.N95) seamLine(u, v) else noseWire(u, v)
                    for (c in 0..2) colour[c] *= 1f - seam
                }
                blend(patch.r, patch.g, patch.b, i, colour[0], colour[1], colour[2], cover)
            }
        }
        patch.save()
    }

    /** The mask's surface direction before pleats and edges: it wraps round the cheeks and under the chin. */
    private fun maskNormal(style: MaskStyle, u: Float, v: Float, out: FloatArray) {
        val wrap = if (style == MaskStyle.N95) 1.35f else 1.15f
        var nx = sin(wrap * u)
        var ny = when (style) {
            MaskStyle.N95 -> -0.65f + 1.3f * v
            else -> -0.45f + 1.05f * v.pow(1.4f)
        }
        // The nose pushes the top middle out into a ridge.
        val ridge = exp(-(u / 0.16f) * (u / 0.16f)) * (1f - smooth(0f, 0.45f, v))
        nx += 0.55f * u / 0.16f * ridge
        ny -= 0.25f * ridge
        if (style == MaskStyle.N95) {
            // Centre fold: two panels meeting at a crease down the middle.
            nx += 0.3f * (if (u < 0f) -1f else 1f) * smooth(0f, 0.06f, abs(u))
        }
        out[0] = nx
        out[1] = ny
        out[2] = 1f
        normalise(out)
    }

    /** Broad gentle undulation, no fine bumps. */
    private fun wrinkles(x: Float, y: Float) = 0.65f * valueNoise(x * 2.2f, y * 3.2f) + 0.35f * valueNoise(x * 4.4f + 5f, y * 6.4f + 3f)

    /** Three folds across the middle of a surgical mask, each facing slightly up with its underside in shade. */
    private fun pleat(v: Float, n: FloatArray) {
        val start = 0.24f
        val size = 0.17f
        val t = (v - start) / size
        if (t < 0f || t >= PLEATS) return
        val p = t - floor(t)
        // Most of each fold faces up; the last part turns down into its crease.
        val down = smooth(0.72f, 0.95f, p)
        n[1] += -0.35f * (1f - down) + 1.1f * down
        n[2] -= 0.2f * down
    }

    private fun seamLine(u: Float, v: Float) = 0.25f * smooth(0.012f, 0.004f, abs(u)) * smooth(0.02f, 0.08f, v)

    private fun noseWire(u: Float, v: Float) = 0.08f * smooth(0.4f, 0.25f, abs(u)) * smooth(0.05f, 0.03f, v) * smooth(0f, 0.02f, v)

    /** Tilts [n] by [amount] towards ([dx], [dy]) in the picture. */
    private fun tilt(n: FloatArray, dx: Float, dy: Float, amount: Float) {
        n[0] += dx * amount * 2f
        n[1] += dy * amount * 2f
    }

    private fun normalise(n: FloatArray) {
        val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-4f)
        n[0] /= l
        n[1] /= l
        n[2] /= l
    }

    /** Matte fabric: wrapped diffuse light with a faint soft sheen, scaled by [shade] (texture and the face's own shading). */
    private fun shadeFabric(albedo: FloatArray, sheen: Float, nx: Float, ny: Float, nz: Float, shade: Float, light: PhotoLight, out: FloatArray) {
        val l = light.light
        val diffuse = ((nx * l[0] + ny * l[1] + nz * l[2]) * 0.7f + 0.3f).coerceAtLeast(0f)
        val hz = l[2] + 1f
        val hl = sqrt(l[0] * l[0] + l[1] * l[1] + hz * hz)
        val spec = max(0f, (nx * l[0] + ny * l[1] + nz * hz) / hl).pow(6f)
        for (c in 0..2) out[c] = albedo[c] * (light.ambient[c] * 0.5f + light.key[c] * diffuse * 0.8f) * shade + light.key[c] * sheen * spec
    }

    /**
     * How much lighter or darker the skin is at each pixel than on average under the mask, heavily
     * smoothed so the nostrils and lips don't show through, only the broad shape and side light.
     */
    private fun faceShading(patch: Patch, frame: EyeFrame, field: DistanceField): FloatArray {
        val w = patch.w
        val h = patch.h
        val lum = FloatArray(w * h) { 0.2126f * patch.r[it] + 0.7152f * patch.g[it] + 0.0722f * patch.b[it] }
        val radius = max(1, (frame.unit * 0.22f).toInt())
        val smoothed = boxBlur(boxBlur(lum, w, h, radius), w, h, radius)
        var sum = 0.0
        var n = 0
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            val q = frame.fromPhoto(Pt(patch.x0 + x + 0.5f, patch.y0 + y + 0.5f))
            if (field.distance(q) < 0f) {
                sum += smoothed[y * w + x]
                n++
            }
        }
        val mean = if (n > 0) (sum / n).toFloat().coerceAtLeast(1e-4f) else 1f
        return FloatArray(w * h) { (smoothed[it] / mean).coerceIn(0.6f, 1.4f).pow(0.6f) }
    }

    /** A box blur of [radius] pixels, across then down, with running sums. */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val across = FloatArray(w * h)
        for (y in 0 until h) {
            var acc = 0f
            var count = 0
            for (x in -radius until w + radius) {
                val add = x + radius
                if (add in 0 until w) { acc += src[y * w + add]; count++ }
                val drop = x - radius - 1
                if (drop in 0 until w) { acc -= src[y * w + drop]; count-- }
                if (x in 0 until w) across[y * w + x] = acc / max(1, count)
            }
        }
        val out = FloatArray(w * h)
        for (x in 0 until w) {
            var acc = 0f
            var count = 0
            for (y in -radius until h + radius) {
                val add = y + radius
                if (add in 0 until h) { acc += across[add * w + x]; count++ }
                val drop = y - radius - 1
                if (drop in 0 until h) { acc -= across[drop * w + x]; count-- }
                if (y in 0 until h) out[y * w + x] = acc / max(1, count)
            }
        }
        return out
    }

    /** A smooth value over [outline]'s box (with some room), worked out every [cell] eye units and blended between. */
    private class SmoothGrid(outline: List<Pt>, private val cell: Float, f: (Pt) -> Float) {
        private val minX = outline.minOf { it.x } - PAD
        private val minY = outline.minOf { it.y } - PAD
        private val cols = ceil((outline.maxOf { it.x } + PAD - minX) / cell).toInt() + 2
        private val rows = ceil((outline.maxOf { it.y } + PAD - minY) / cell).toInt() + 2
        private val values = FloatArray(cols * rows) { f(Pt(minX + (it % cols) * cell, minY + (it / cols) * cell)) }

        fun at(q: Pt): Float {
            val fx = ((q.x - minX) / cell).coerceIn(0f, cols - 1.001f)
            val fy = ((q.y - minY) / cell).coerceIn(0f, rows - 1.001f)
            val i = fx.toInt()
            val j = fy.toInt()
            val tx = fx - i
            val ty = fy - j
            val a = j * cols + i
            return (values[a] * (1 - tx) + values[a + 1] * tx) * (1 - ty) + (values[a + cols] * (1 - tx) + values[a + cols + 1] * tx) * ty
        }

        private companion object {
            const val PAD = 0.05f
        }
    }

    // --- Eye masks -------------------------------------------------------------------------

    private fun eyeMask(photo: PhotoPixels, face: Face, style: MaskStyle) {
        val frame = EyeFrame(face.mid(33, 133), face.mid(362, 263))
        val unit = frame.unit
        if (unit < 4f) return
        val (outerPx, holeLeftPx, holeRightPx) = TryOnDrawing.eyeMaskOutlines(face)
        val outer = outerPx.map(frame::fromPhoto)
        val holes = listOf(holeLeftPx.map(frame::fromPhoto), holeRightPx.map(frame::fromPhoto))
        val patch = Patch.around(photo, frame, face, outer, 0.3f) ?: return
        val outerField = DistanceField(listOf(outer), COARSE)
        val holeField = DistanceField(holes)
        val pixel = 1f / unit
        val gold = style == MaskStyle.MASQUERADE
        val w = patch.w
        val h = patch.h
        val drop = Pt(-patch.light.light[0] * 0.05f, 0.07f)
        val env = FloatArray(3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val q = frame.fromPhoto(Pt(patch.x0 + x + 0.5f, patch.y0 + y + 0.5f))
                val i = y * w + x
                val dOuter = outerField.distance(q)
                val dHole = holeField.distance(q)
                // Negative on the mask itself: inside the outline and outside both eye holes.
                val d = max(dOuter, -dHole)

                if (d > -pixel) {
                    val s = q - drop
                    val shadow = soft(max(outerField.distance(s), -holeField.distance(s)), 0.07f)
                    val k = 1f - EYE_MASK_SHADOW * shadow
                    patch.r[i] *= k
                    patch.g[i] *= k
                    patch.b[i] *= k
                }
                val cover = coverage(-d, pixel)
                if (cover <= 0f) continue

                // Padded: the surface rolls away towards every edge, over the curve of the face.
                val edgeNormal = if (dOuter > -dHole) outerField.normal(q) else holeField.normal(q).let { Pt(-it.x, -it.y) }
                val roll = smooth(0.12f, 0f, -d)
                var nx = sin(q.x / 2.6f) + edgeNormal.x * roll * 1.6f
                var ny = -0.15f + edgeNormal.y * roll * 1.6f
                if (gold) {
                    // Embossed filigree: swirls from the slope of a smooth pattern.
                    val e = 0.01f
                    val c = filigree(q)
                    nx += (filigree(Pt(q.x + e, q.y)) - c) / e * 0.007f
                    ny += (filigree(Pt(q.x, q.y + e)) - c) / e * 0.007f
                }
                val nl = sqrt(nx * nx + ny * ny + 1f)
                nx /= nl
                ny /= nl
                val nz = 1f / nl
                val l = patch.light.light
                val diffuse = ((nx * l[0] + ny * l[1] + nz * l[2]) * 0.75f + 0.25f).coerceAtLeast(0f)
                val hz = l[2] + 1f
                val hl = sqrt(l[0] * l[0] + l[1] * l[1] + hz * hz)
                val halfway = max(0f, (nx * l[0] + ny * l[1] + nz * hz) / hl)
                patch.light.environment(2 * nz * nx, 2 * nz * ny, env)
                val window = patch.light.window(2 * nz * nx, 2 * nz * ny)
                val key = patch.light.key
                val cr: Float
                val cg: Float
                val cb: Float
                if (gold) {
                    val spec = halfway.pow(40f)
                    cr = GOLD[0] * (env[0] * 0.9f + key[0] * (spec * 1.4f + window * 0.5f) + patch.light.ambient[0] * 0.15f * diffuse)
                    cg = GOLD[1] * (env[1] * 0.9f + key[1] * (spec * 1.4f + window * 0.5f) + patch.light.ambient[1] * 0.15f * diffuse)
                    cb = GOLD[2] * (env[2] * 0.9f + key[2] * (spec * 1.4f + window * 0.5f) + patch.light.ambient[2] * 0.15f * diffuse)
                } else {
                    // Black satin: dark, with a soft broad sheen.
                    val spec = halfway.pow(14f) * 0.35f
                    val fresnel = 0.04f + 0.96f * (1f - nz).pow(5)
                    cr = SATIN * patch.light.ambient[0] * diffuse + key[0] * spec + env[0] * fresnel
                    cg = SATIN * patch.light.ambient[1] * diffuse + key[1] * spec + env[1] * fresnel
                    cb = SATIN * patch.light.ambient[2] * diffuse + key[2] * spec + env[2] * fresnel
                }
                blend(patch.r, patch.g, patch.b, i, cr, cg, cb, cover)
            }
        }
        patch.save()
    }

    /** A swirling pattern for gold filigree, symmetric across the nose. */
    private fun filigree(q: Pt): Float {
        val x = abs(q.x)
        return sin(x * 14f + 3f * sin(q.y * 11f + x * 4f)) * sin(q.y * 16f + 2.5f * sin(x * 9f)) * 0.5f + 0.5f * fbm(x * 6f, q.y * 6f)
    }

    private const val FACE_POINTS = 468
    /** Masks are large, so their outline grids can be coarser than a lens's. */
    private const val COARSE = 0.02f
    private const val NOSE_TIP = 1
    private const val PLEATS = 3
    private const val LOOP_RADIUS = 0.018f
    private const val MASK_SHADOW = 0.45f
    private const val CONTACT_SHADOW = 0.25f
    private const val EYE_MASK_SHADOW = 0.45f
    private const val SATIN = 0.035f
    private const val WRINKLE = 0.045f
    private val GOLD = floatArrayOf(0.95f, 0.7f, 0.3f)
}
