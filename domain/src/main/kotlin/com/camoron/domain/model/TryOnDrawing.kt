package com.camoron.domain.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/** A colour ramp between two points, in pixels. */
class Gradient(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val from: Int, val to: Int)

/**
 * One thing to draw: closed outlines (filled with the even-odd rule, so an inner outline cuts a
 * hole) or open lines (stroked only). Coordinates are pixels of the upright photo.
 */
class Shape2D(
    val contours: List<FloatArray>,
    val closed: Boolean = true,
    val fill: Int? = null,
    val gradient: Gradient? = null,
    val stroke: Int? = null,
    val strokeWidth: Float = 0f,
    /** Soft blur of the edges in pixels, for shadows. */
    val blur: Float = 0f,
)

/**
 * Turns try-on choices and the face tracker's points into shapes to draw. Pure geometry, so the
 * preview, the saved photo and the tests all get the same drawing; an Android canvas just fills
 * the outlines.
 */
object TryOnDrawing {

    fun shapes(tryOn: TryOn, scene: FaceScene, width: Int, height: Int): List<Shape2D> {
        if (!tryOn.needsFaces) return emptyList()
        val out = mutableListOf<Shape2D>()
        scene.faces.forEachIndexed { index, face ->
            if (face.count < FACE_POINTS) return@forEachIndexed
            val f = Face(face, width.toFloat(), height.toFloat())
            if (tryOn.facialHair != FacialHair.NONE) facialHair(f, index, tryOn, out)
            when (tryOn.mask) {
                MaskStyle.NONE -> Unit
                MaskStyle.SURGICAL, MaskStyle.N95, MaskStyle.CLOTH -> faceMask(f, tryOn.mask, out)
                MaskStyle.MASQUERADE, MaskStyle.HERO -> eyeMask(f, tryOn.mask, out)
            }
            if (tryOn.glasses != GlassesStyle.NONE) glasses(f, tryOn, out)
        }
        return out
    }

    // --- Spectacles -------------------------------------------------------------------------

    /**
     * Frames are designed in "eye units": the pupils at x = −1 and +1, y pointing down the face.
     * The units map onto the photo through the eye line, so the frames follow the head's size,
     * position and tilt. Each lens is narrowed by how foreshortened its eye is, for turned heads.
     */
    private fun glasses(face: Face, tryOn: TryOn, out: MutableList<Shape2D>) {
        val left = face.mid(33, 133)
        val right = face.mid(362, 263)
        val frame = EyeFrame(left, right)
        val widthLeft = face.dist(33, 133)
        val widthRight = face.dist(362, 263)
        val squeezeLeft = (2 * widthLeft / (widthLeft + widthRight)).coerceIn(0.6f, 1.2f)
        val squeezeRight = (2 * widthRight / (widthLeft + widthRight)).coerceIn(0.6f, 1.2f)
        val style = tryOn.glasses
        val unit = frame.unit

        val lensRight = lens(style).map { (x, y) -> Pt(LENS_X + x * LENS_SCALE * squeezeRight, LENS_Y + y * LENS_SCALE) }
        val lensLeft = lens(style).map { (x, y) -> Pt(-LENS_X - x * LENS_SCALE * squeezeLeft, LENS_Y + y * LENS_SCALE) }
        val leftPx = frame.toPhoto(lensLeft)
        val rightPx = frame.toPhoto(lensRight)

        val rimWidth = unit * when (style) {
            GlassesStyle.BOLD -> 0.14f
            GlassesStyle.RIMLESS -> 0.018f
            GlassesStyle.ROUND, GlassesStyle.AVIATOR -> 0.06f
            else -> 0.085f
        }
        val frameColour = if (style == GlassesStyle.RIMLESS) withAlpha(FrameColour.SILVER.argb, 0.9f) else tryOn.frame.argb

        // Bridge over the nose and arms back to the ears.
        val bridge = frame.toPhoto(curve(Pt(-0.24f, -0.12f), Pt(0f, -0.3f), Pt(0.24f, -0.12f)))
        val armLeft = listOf(frame.toPhoto(Pt(-LENS_X - 0.8f * LENS_SCALE * squeezeLeft, LENS_Y - 0.25f)), face.pt(127))
        val armRight = listOf(frame.toPhoto(Pt(LENS_X + 0.8f * LENS_SCALE * squeezeRight, LENS_Y - 0.25f)), face.pt(356))

        // A soft shadow on the skin makes the frames sit on the face rather than float above it.
        val down = frame.down(0.07f)
        out += Shape2D(
            listOf(leftPx.shift(down), rightPx.shift(down), bridge.shift(down)).map { it.flat() },
            closed = false,
            stroke = 0x55000000,
            strokeWidth = rimWidth * 1.2f,
            blur = unit * 0.06f,
        )

        // Lenses: tint, then a faint reflection across the upper outer part.
        val tint = lensTint(tryOn.lens)
        for (lens in listOf(leftPx, rightPx)) {
            val top = frame.toPhoto(Pt(0f, LENS_Y - 0.6f))
            val bottom = frame.toPhoto(Pt(0f, LENS_Y + 0.6f))
            out += Shape2D(
                listOf(lens.flat()),
                fill = tint.first.takeIf { tint.second == null },
                gradient = tint.second?.let { Gradient(top.x, top.y, bottom.x, bottom.y, tint.first, it) },
            )
        }
        for ((side, squeeze) in listOf(-1f to squeezeLeft, 1f to squeezeRight)) {
            val glare = ellipse(0.32f * squeeze, 0.09f, rotation = -0.5f * side)
                .map { (x, y) -> Pt(side * (LENS_X + 0.22f * squeeze) + x, LENS_Y - 0.25f + y) }
            out += Shape2D(listOf(frame.toPhoto(glare).flat()), fill = if (tryOn.lens == LensTint.CLEAR) 0x24FFFFFF else 0x30FFFFFF)
        }

        // Rims, bridge and arms.
        out += Shape2D(listOf(leftPx.flat(), rightPx.flat()), closed = true, stroke = frameColour, strokeWidth = rimWidth)
        out += Shape2D(listOf(bridge.flat()), closed = false, stroke = frameColour, strokeWidth = max(rimWidth * 0.8f, unit * 0.04f))
        out += Shape2D(listOf(armLeft.flat(), armRight.flat()), closed = false, stroke = frameColour, strokeWidth = max(rimWidth * 0.9f, unit * 0.05f))
        if (style != GlassesStyle.RIMLESS) {
            // A lighter line along the top of the rims reads as the frame catching the light.
            val highlight = withAlpha(lighten(frameColour), 0.55f)
            for (lens in listOf(lensLeft, lensRight)) {
                val topArc = lens.filter { it.y < LENS_Y - 0.05f }.sortedBy { it.x }
                if (topArc.size > 2) {
                    out += Shape2D(
                        listOf(frame.toPhoto(topArc.map { Pt(it.x, it.y - 0.012f) }).flat()),
                        closed = false,
                        stroke = highlight,
                        strokeWidth = rimWidth * 0.3f,
                    )
                }
            }
        }
    }

    /** One right-hand lens outline in eye units, centred on (0, 0); the outer side is +x. */
    internal fun lens(style: GlassesStyle): List<Pt> {
        val n = LENS_POINTS
        return (0 until n).map { i ->
            val t = 2 * PI.toFloat() * i / n
            val c = cos(t)
            val s = sin(t)
            when (style) {
                GlassesStyle.ROUND -> Pt(0.68f * c, 0.66f * s)
                GlassesStyle.RECTANGLE -> superellipse(c, s, 0.8f, 0.5f, 4f)
                GlassesStyle.AVIATOR -> {
                    // Teardrop: flat top, dropping lowest towards the nose.
                    val depth = if (s > 0) 0.62f * (1f + 0.22f * -c) else 0.44f
                    Pt(0.8f * c, depth * s)
                }
                GlassesStyle.CAT_EYE -> {
                    val p = superellipse(c, s, 0.8f, 0.5f, 2.6f)
                    // Upswept outer top corner.
                    val lift = if (s < 0 && c > 0) 0.36f * c.pow(3) else 0f
                    Pt(p.x * (1f + 0.06f * c.coerceAtLeast(0f)), p.y - lift)
                }
                GlassesStyle.BOLD -> {
                    val p = superellipse(c, s, 0.8f, 0.52f, 5f)
                    // Wider at the top than the bottom.
                    Pt(p.x * (1f - 0.12f * (p.y / 0.52f)), p.y)
                }
                GlassesStyle.RIMLESS -> superellipse(c, s, 0.76f, 0.5f, 3f)
                GlassesStyle.NONE -> Pt(0f, 0f)
            }
        }
    }

    private fun lensTint(tint: LensTint): Pair<Int, Int?> = when (tint) {
        LensTint.CLEAR -> 0x14D8E8F0 to null
        LensTint.GREY -> 0xCC1E2124.toInt() to null
        LensTint.BROWN -> 0xC24A2C17.toInt() to null
        LensTint.GRADIENT -> 0xE01A1C1F.toInt() to 0x201A1C1F
        LensTint.BLUE -> 0xB81F3F73.toInt() to null
    }

    // --- Facial hair ------------------------------------------------------------------------

    /**
     * Beards and moustaches are drawn hair by hair: thousands of short strokes inside an outline
     * taken from the face points, each growing the way facial hair does (down the cheeks and chin,
     * out and down from the middle of the upper lip) and thinning out towards the edges, over a
     * soft shadow that gives density. A fixed seed per face gives the same hairs in the preview
     * and the saved photo, at any size.
     */
    private fun facialHair(face: Face, faceIndex: Int, tryOn: TryOn, out: MutableList<Shape2D>) {
        val style = tryOn.facialHair
        val frame = EyeFrame(face.mid(33, 133), face.mid(362, 263))
        val unit = frame.unit
        val region = beardOutline(face, frame, style)
        val look = BeardLook.of(style)
        val colour = tryOn.beard.argb

        out += Shape2D(region.map { it.flat() }, fill = withAlpha(darken(colour, 0.75f), look.shadow), blur = unit * look.blur)

        // Everything below is in eye units, so the hairs scale with the face.
        val shapes = region.map { contour -> contour.map { frame.fromPhoto(it) } }
        val area = abs(shapes.sumOf { signedArea(it).toDouble() }).toFloat()
        val count = (look.coverage * area / (look.length * look.width)).toInt().coerceIn(0, MAX_HAIRS)
        val mouth = frame.fromPhoto(face.mid(61, 291))
        val mouthHalf = abs(frame.fromPhoto(face.pt(291)).x - frame.fromPhoto(face.pt(61)).x) / 2f
        val noseLine = frame.fromPhoto(face.pt(164)).y
        val lipLine = frame.fromPhoto(face.pt(0)).y
        val minX = shapes.minOf { c -> c.minOf { it.x } }
        val maxX = shapes.maxOf { c -> c.maxOf { it.x } }
        val minY = shapes.minOf { c -> c.minOf { it.y } }
        val maxY = shapes.maxOf { c -> c.maxOf { it.y } }

        val random = Random(HAIR_SEED + faceIndex * 7919 + style.ordinal)
        val groups = List(3) { mutableListOf<FloatArray>() }
        var attempts = 0
        while (groups.sumOf { it.size } < count && attempts < count * 6) {
            attempts++
            val p = Pt(minX + random.nextFloat() * (maxX - minX), minY + random.nextFloat() * (maxY - minY))
            if (!inside(shapes, p)) continue
            // Fewer hairs towards the outline, for a soft, natural edge.
            val t = (edgeDistance(shapes, p) / look.fade).coerceIn(0f, 1f)
            if (random.nextFloat() > t * t * (3f - 2f * t)) continue

            val dx = p.x - mouth.x
            val overLip = p.y > noseLine - 0.08f && p.y < lipLine + 0.04f && abs(dx) < mouthHalf + 0.18f
            var angle = if (overLip) {
                // Moustache hair parts at the middle and sweeps out and down.
                atan2(0.75f, sign(dx) * 1f)
            } else {
                // Down the chin, slightly outwards on the cheeks.
                atan2(1f, dx * 0.3f)
            }
            angle += (random.nextFloat() - 0.5f) * 0.6f
            val length = look.length * (0.6f + 0.8f * random.nextFloat())
            val dir = Pt(cos(angle), sin(angle))
            val bend = Pt(-dir.y, dir.x) * (length * 0.18f * (random.nextFloat() - 0.5f))
            val strand = listOf(p, p + dir * (length * 0.5f) + bend, p + dir * length)
            val group = when (random.nextFloat()) {
                in 0f..0.4f -> 0
                in 0.4f..0.85f -> 1
                else -> 2
            }
            groups[group] += frame.toPhoto(strand).flat()
        }
        val tones = listOf(
            withAlpha(darken(colour, 0.6f), 0.85f * look.opacity) to 1.15f,
            withAlpha(colour, 0.8f * look.opacity) to 1f,
            withAlpha(mix(colour, 0xFFFFFFFF.toInt(), 0.35f), 0.6f * look.opacity) to 0.8f,
        )
        groups.forEachIndexed { i, strands ->
            if (strands.isNotEmpty()) {
                out += Shape2D(strands, closed = false, stroke = tones[i].first, strokeWidth = unit * look.width * tones[i].second)
            }
        }
    }

    /** How each style looks, in eye units: hair length and thickness, how densely it covers, its shadow. */
    private class BeardLook(
        val length: Float,
        val width: Float,
        val coverage: Float,
        val shadow: Float,
        val blur: Float,
        val fade: Float,
        val opacity: Float = 1f,
    ) {
        companion object {
            fun of(style: FacialHair) = when (style) {
                FacialHair.STUBBLE -> BeardLook(0.022f, 0.005f, 0.45f, 0.2f, 0.12f, 0.18f, opacity = 0.6f)
                FacialHair.MOUSTACHE -> BeardLook(0.1f, 0.01f, 3f, 0.42f, 0.06f, 0.06f)
                FacialHair.GOATEE, FacialHair.CIRCLE -> BeardLook(0.12f, 0.01f, 3f, 0.42f, 0.06f, 0.08f)
                FacialHair.SHORT_BEARD -> BeardLook(0.09f, 0.01f, 2.6f, 0.4f, 0.08f, 0.14f)
                FacialHair.FULL_BEARD -> BeardLook(0.19f, 0.011f, 3f, 0.5f, 0.08f, 0.14f)
                FacialHair.NONE -> BeardLook(0f, 1f, 0f, 0f, 0f, 1f)
            }
        }
    }

    /** The outline (photo pixels) each style fills, with the mouth cut out where it is surrounded. */
    private fun beardOutline(face: Face, frame: EyeFrame, style: FacialHair): List<List<Pt>> {
        val mouthCentre = face.mid(61, 291)
        val mouthHole = smoothClosed(LIPS_OUTER.map { face.pt(it).awayFrom(mouthCentre, 1.12f) })
        val upperLip = UPPER_LIP.map { face.pt(it) }
        val lowerLip = LOWER_LIP.map { face.pt(it) }
        val noseBase = NOSE_BASE.map { face.pt(it) }
        // Out from each mouth corner, in eye units across and down the face.
        fun corner(index: Int, side: Float, across: Float, down: Float) =
            face.pt(index) + frame.vector(side * across, down)

        val moustache = smoothClosed(
            listOf(corner(61, -1f, 0.1f, 0.06f)) +
                noseBase.map { it.lerp(face.pt(0), 0.12f) } +
                listOf(corner(291, 1f, 0.1f, 0.06f)) +
                upperLip.reversed().map { it + frame.vector(0f, -0.025f) },
        )
        // A goatee narrows from the lip to the point of the chin.
        val chinNarrow = CHIN.map { face.pt(it) }.let { points ->
            val point = face.pt(152)
            points.map { it.lerp(point, 0.55f).awayFrom(mouthCentre, 1.04f) }
        }
        return when (style) {
            FacialHair.MOUSTACHE -> listOf(moustache)
            FacialHair.GOATEE -> listOf(
                smoothClosed(lowerLip.subList(3, lowerLip.size - 3).map { it + frame.vector(0f, 0.03f) } + chinNarrow),
            )
            FacialHair.CIRCLE -> listOf(
                smoothClosed(
                    noseBase.map { it.lerp(face.pt(0), 0.12f) } +
                        listOf(corner(291, 1f, 0.12f, 0.05f), corner(291, 1f, 0.06f, 0.3f)) +
                        CHIN.map { face.pt(it).lerp(face.pt(152), 0.3f).awayFrom(mouthCentre, 1.05f) } +
                        listOf(corner(61, -1f, 0.06f, 0.3f), corner(61, -1f, 0.12f, 0.05f)),
                ),
                mouthHole,
            )
            FacialHair.STUBBLE, FacialHair.SHORT_BEARD, FacialHair.FULL_BEARD -> {
                val centre = face.pt(NOSE_TIP)
                // Full beards grow out past the jaw, most at the chin.
                val jaw = JAW.mapIndexed { i, index ->
                    val chinWeight = 1f - abs(i - JAW.size / 2f) / (JAW.size / 2f)
                    val grow = when (style) {
                        FacialHair.FULL_BEARD -> 1.04f + 0.12f * chinWeight
                        FacialHair.SHORT_BEARD -> 1.01f + 0.03f * chinWeight
                        else -> 1f
                    }
                    face.pt(index).awayFrom(centre, grow)
                }
                val top = BEARD_CHEEK_LEFT.map { face.pt(it) } +
                    noseBase.map { it.lerp(face.pt(0), 0.12f) } +
                    BEARD_CHEEK_RIGHT.map { face.pt(it) }
                listOf(smoothClosed(top + jaw.subList(1, jaw.size - 1).reversed()), mouthHole)
            }
            FacialHair.NONE -> emptyList()
        }
    }

    /** Whether [p] is inside [contours] by the even-odd rule. */
    private fun inside(contours: List<List<Pt>>, p: Pt): Boolean {
        var crossings = 0
        for (c in contours) {
            for (i in c.indices) {
                val a = c[i]
                val b = c[(i + 1) % c.size]
                if ((a.y > p.y) != (b.y > p.y) && p.x < a.x + (p.y - a.y) / (b.y - a.y) * (b.x - a.x)) crossings++
            }
        }
        return crossings % 2 == 1
    }

    /** Distance from [p] to the nearest edge of [contours]. */
    private fun edgeDistance(contours: List<List<Pt>>, p: Pt): Float {
        var best = Float.MAX_VALUE
        for (c in contours) {
            for (i in c.indices) {
                val a = c[i]
                val ab = c[(i + 1) % c.size] - a
                val len2 = ab.x * ab.x + ab.y * ab.y
                val t = if (len2 > 0f) (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f) else 0f
                val d = p - (a + ab * t)
                best = minOf(best, hypot(d.x, d.y))
            }
        }
        return best
    }

    private fun signedArea(c: List<Pt>): Float {
        var sum = 0f
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2f
    }

    // --- Face masks -------------------------------------------------------------------------

    /** A mask over the nose, mouth and chin, from the cheeks under the eyes down past the jaw. */
    private fun faceMask(face: Face, style: MaskStyle, out: MutableList<Shape2D>) {
        val centre = face.pt(NOSE_TIP)
        val top = MASK_TOP.map { face.pt(it) }
        val bottom = JAW.map { face.pt(it) }.map { it.awayFrom(centre, 1.05f) }
        // Top edge: cheek, over the bridge of the nose, cheek. Bottom edge: along and just past the jaw.
        val outline = smoothClosed(top + bottom.subList(1, bottom.size - 1).reversed())
        val unit = face.dist(33, 263) / 2f
        val (base, edge) = when (style) {
            MaskStyle.SURGICAL -> 0xFF9FC8E3.toInt() to 0xFF7EAFCF.toInt()
            MaskStyle.N95 -> 0xFFF2F1EC.toInt() to 0xFFCFCDC5.toInt()
            else -> 0xFF26282B.toInt() to 0xFF151617.toInt()
        }

        // Ear loops first, so the mask covers their ends.
        val leftEar = face.pt(234).awayFrom(centre, 1.08f)
        val rightEar = face.pt(454).awayFrom(centre, 1.08f)
        val loop = if (style == MaskStyle.CLOTH) 0xFF1B1C1E.toInt() else 0xFFEDEDED.toInt()
        out += Shape2D(
            listOf(
                listOf(top.first(), leftEar.lerp(face.pt(127), 0.5f), bottom.first()).flat(),
                listOf(top.last(), rightEar.lerp(face.pt(356), 0.5f), bottom.last()).flat(),
            ),
            closed = false,
            stroke = loop,
            strokeWidth = unit * 0.05f,
        )

        // Shadow, body with light from above, edge binding.
        out += Shape2D(listOf(outline.shift(face.down(unit * 0.06f)).flat()), fill = 0x50000000, blur = unit * 0.12f)
        val upper = face.pt(6)
        val lower = face.pt(152)
        val sheen = if (style == MaskStyle.CLOTH) mix(base, 0xFFFFFFFF.toInt(), 0.12f) else lighten(base)
        out += Shape2D(listOf(outline.flat()), gradient = Gradient(upper.x, upper.y, lower.x, lower.y, sheen, darken(base, 0.85f)))
        out += Shape2D(listOf(outline.flat()), stroke = edge, strokeWidth = unit * if (style == MaskStyle.SURGICAL) 0.07f else 0.04f)

        when (style) {
            MaskStyle.SURGICAL -> {
                // Three pleats across the mask, following its curve.
                val topLine = smoothOpen(top)
                val bottomLine = smoothOpen(bottom)
                for (t in listOf(0.3f, 0.5f, 0.7f)) {
                    val pleat = blend(topLine, bottomLine, t)
                    out += Shape2D(listOf(pleat.flat()), closed = false, stroke = withAlpha(edge, 0.8f), strokeWidth = unit * 0.035f)
                    out += Shape2D(listOf(pleat.shift(face.down(unit * 0.03f)).flat()), closed = false, stroke = 0x30FFFFFF, strokeWidth = unit * 0.025f)
                }
            }
            MaskStyle.N95 -> {
                // Centre seam from the nose to the chin.
                out += Shape2D(listOf(smoothOpen(listOf(face.pt(197), face.pt(NOSE_TIP), face.pt(0), face.pt(17), lower)).flat()), closed = false, stroke = edge, strokeWidth = unit * 0.03f)
            }
            else -> Unit
        }
    }

    /** A party or hero mask over the eyes, with eye holes. */
    private fun eyeMask(face: Face, style: MaskStyle, out: MutableList<Shape2D>) {
        val eyesCentre = face.mid(33, 263)
        val outline = smoothClosed(EYE_MASK.map { face.pt(it).awayFrom(eyesCentre, 1.1f) })
        val leftCentre = face.mid(33, 133)
        val rightCentre = face.mid(362, 263)
        val holeLeft = smoothClosed(LEFT_EYE.map { face.pt(it).awayFrom(leftCentre, 1.45f) })
        val holeRight = smoothClosed(RIGHT_EYE.map { face.pt(it).awayFrom(rightCentre, 1.45f) })
        val unit = face.dist(33, 263) / 2f
        val contours = listOf(outline.flat(), holeLeft.flat(), holeRight.flat())
        val top = face.pt(9)
        val bottom = face.pt(195)
        out += Shape2D(listOf(outline.shift(face.down(unit * 0.06f)).flat(), holeLeft.flat(), holeRight.flat()), fill = 0x55000000, blur = unit * 0.1f)
        if (style == MaskStyle.MASQUERADE) {
            out += Shape2D(contours, gradient = Gradient(top.x, top.y, bottom.x, bottom.y, 0xFFF3DE8A.toInt(), 0xFFB8892E.toInt()))
            out += Shape2D(contours, stroke = 0xFF6E4E12.toInt(), strokeWidth = unit * 0.05f)
        } else {
            out += Shape2D(contours, gradient = Gradient(top.x, top.y, bottom.x, bottom.y, 0xFF2A2B2F.toInt(), 0xFF0C0C0D.toInt()))
            out += Shape2D(contours, stroke = 0xFF000000.toInt(), strokeWidth = unit * 0.035f)
        }
    }

    // --- Geometry helpers ------------------------------------------------------------------

    internal data class Pt(val x: Float, val y: Float) {
        operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
        operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
        operator fun times(k: Float) = Pt(x * k, y * k)
        fun lerp(o: Pt, t: Float) = Pt(x + (o.x - x) * t, y + (o.y - y) * t)
        fun awayFrom(c: Pt, k: Float) = Pt(c.x + (x - c.x) * k, c.y + (y - c.y) * k)
    }

    private class Face(val shape: FaceShape, val width: Float, val height: Float) {
        fun pt(i: Int) = Pt(shape.x(i) * width, shape.y(i) * height)
        fun mid(a: Int, b: Int) = pt(a).lerp(pt(b), 0.5f)
        fun dist(a: Int, b: Int): Float {
            val d = pt(a) - pt(b)
            return hypot(d.x, d.y)
        }

        /** A step of [amount] pixels down the face (perpendicular to the eye line). */
        fun down(amount: Float): Pt {
            val across = pt(263) - pt(33)
            val len = hypot(across.x, across.y).coerceAtLeast(1e-3f)
            return Pt(-across.y / len * amount, across.x / len * amount)
        }
    }

    /** Eye units onto the photo: pupils at (∓1, 0), y down the face. */
    private class EyeFrame(left: Pt, right: Pt) {
        private val origin = left.lerp(right, 0.5f)
        private val u = (right - left) * 0.5f
        private val v = Pt(-u.y, u.x)
        val unit: Float = hypot(u.x, u.y)

        fun toPhoto(p: Pt) = origin + u * p.x + v * p.y
        fun toPhoto(points: List<Pt>) = points.map(::toPhoto)

        /** A photo point in eye units. */
        fun fromPhoto(p: Pt): Pt {
            val d = p - origin
            val len2 = unit * unit
            return Pt((d.x * u.x + d.y * u.y) / len2, (d.x * v.x + d.y * v.y) / len2)
        }

        /** A step of ([across], [down]) eye units, in photo pixels. */
        fun vector(across: Float, down: Float) = u * across + v * down
        fun down(amount: Float) = v * amount
    }

    private fun superellipse(c: Float, s: Float, a: Float, b: Float, n: Float) =
        Pt(a * sign(c) * abs(c).pow(2f / n), b * sign(s) * abs(s).pow(2f / n))

    private fun ellipse(a: Float, b: Float, rotation: Float): List<Pt> {
        val cr = cos(rotation)
        val sr = sin(rotation)
        return (0 until 24).map { i ->
            val t = 2 * PI.toFloat() * i / 24
            val x = a * cos(t)
            val y = b * sin(t)
            Pt(x * cr - y * sr, x * sr + y * cr)
        }
    }

    private fun curve(a: Pt, control: Pt, b: Pt): List<Pt> = (0..12).map { i ->
        val t = i / 12f
        a * ((1 - t) * (1 - t)) + control * (2 * (1 - t) * t) + b * (t * t)
    }

    /** A smooth closed outline through [points] (Catmull-Rom). */
    internal fun smoothClosed(points: List<Pt>, steps: Int = 6): List<Pt> {
        val n = points.size
        val out = ArrayList<Pt>(n * steps)
        for (i in 0 until n) {
            val p0 = points[(i - 1 + n) % n]
            val p1 = points[i]
            val p2 = points[(i + 1) % n]
            val p3 = points[(i + 2) % n]
            for (s in 0 until steps) out += catmullRom(p0, p1, p2, p3, s / steps.toFloat())
        }
        return out
    }

    /** A smooth line through [points], ending at the last one. */
    private fun smoothOpen(points: List<Pt>, steps: Int = 6): List<Pt> {
        val n = points.size
        val out = ArrayList<Pt>(n * steps)
        for (i in 0 until n - 1) {
            val p0 = points[maxOf(i - 1, 0)]
            val p3 = points[minOf(i + 2, n - 1)]
            for (s in 0 until steps) out += catmullRom(p0, points[i], points[i + 1], p3, s / steps.toFloat())
        }
        out += points.last()
        return out
    }

    private fun catmullRom(p0: Pt, p1: Pt, p2: Pt, p3: Pt, t: Float): Pt {
        val t2 = t * t
        val t3 = t2 * t
        return (p1 * 2f + (p2 - p0) * t + (p0 * 2f - p1 * 5f + p2 * 4f - p3) * t2 + (p1 * 3f - p0 - p2 * 3f + p3) * t3) * 0.5f
    }

    /** A line part way between two lines, matched by position along each. */
    private fun blend(a: List<Pt>, b: List<Pt>, t: Float): List<Pt> {
        val n = 24
        return (0..n).map { i ->
            val s = i / n.toFloat()
            sample(a, s).lerp(sample(b, s), t)
        }
    }

    private fun sample(line: List<Pt>, s: Float): Pt {
        val f = s * (line.size - 1)
        val i = f.toInt().coerceAtMost(line.size - 2)
        return line[i].lerp(line[i + 1], f - i)
    }

    private fun List<Pt>.shift(by: Pt) = map { it + by }

    private fun List<Pt>.flat(): FloatArray {
        val out = FloatArray(size * 2)
        forEachIndexed { i, p ->
            out[2 * i] = p.x
            out[2 * i + 1] = p.y
        }
        return out
    }

    private fun withAlpha(argb: Int, alpha: Float) = (((alpha * 255).toInt() and 0xFF) shl 24) or (argb and 0xFFFFFF)

    private fun lighten(argb: Int): Int = mix(argb, 0xFFFFFFFF.toInt(), 0.35f)

    private fun darken(argb: Int, k: Float): Int {
        val r = (((argb shr 16) and 0xFF) * k).toInt()
        val g = (((argb shr 8) and 0xFF) * k).toInt()
        val b = ((argb and 0xFF) * k).toInt()
        return (argb and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() and 0xFF) shl shift
        return (a and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }

    private const val FACE_POINTS = 468
    private const val LENS_POINTS = 64
    private const val LENS_X = 1f
    private const val LENS_SCALE = 1.08f
    private const val LENS_Y = 0.08f
    private const val NOSE_TIP = 1
    private const val MAX_HAIRS = 9000
    private const val HAIR_SEED = 20261026

    /** Outer edge of the lips, round from the left corner (in the photo) over the top. */
    private val LIPS_OUTER = listOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146)
    private val UPPER_LIP = listOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291)
    private val LOWER_LIP = listOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291)

    /** Under the nose, from beside the left nostril to beside the right. */
    private val NOSE_BASE = listOf(92, 165, 167, 164, 393, 391, 322)

    /** Under the lower lip round the point of the chin, right (in the photo) to left. */
    private val CHIN = listOf(378, 400, 377, 152, 148, 176, 149)

    /** Where a beard starts on the cheeks: sideburn down to beside the mouth, and back up. */
    private val BEARD_CHEEK_LEFT = listOf(234, 213, 192, 214, 212, 57)
    private val BEARD_CHEEK_RIGHT = listOf(287, 432, 434, 416, 433, 454)

    /** Mask top edge, from the face's left side (in the photo) over the nose to its right side. */
    private val MASK_TOP = listOf(234, 116, 197, 345, 454)

    /** The jaw line from the left of the face (in the photo) round the chin to the right. */
    private val JAW = listOf(234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152, 377, 400, 378, 379, 365, 397, 288, 361, 323, 454)

    /** Eye-mask outline: temple, along the brows over the bridge, temple, under the eyes over the nose. */
    private val EYE_MASK = listOf(
        127, 70, 63, 105, 66, 107, 9, 336, 296, 334, 293, 300, 356,
        346, 347, 348, 277, 6, 47, 119, 118, 117,
    )

    private val LEFT_EYE = listOf(33, 246, 161, 160, 159, 158, 157, 173, 133, 155, 154, 153, 145, 144, 163, 7)
    private val RIGHT_EYE = listOf(263, 466, 388, 387, 386, 385, 384, 398, 362, 382, 381, 380, 374, 373, 390, 249)
}
