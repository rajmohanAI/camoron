package com.camoron.domain.model

import com.camoron.domain.model.TryOnDrawing.EyeFrame
import com.camoron.domain.model.TryOnDrawing.Face
import com.camoron.domain.model.TryOnDrawing.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Read and write access to a photo's ARGB pixels, a rectangle at a time. */
interface PhotoPixels {
    val width: Int
    val height: Int
    fun read(x0: Int, y0: Int, w: Int, h: Int): IntArray
    fun write(pixels: IntArray, x0: Int, y0: Int, w: Int, h: Int)
}

/**
 * Glasses rendered into the photo pixel by pixel, so they look like real glasses rather than
 * drawn ones: rims with depth lit the way the face is lit, metal that reflects its surroundings,
 * lenses that bend and tint what is behind them and catch reflections, and shadows on the skin.
 *
 * Shapes come from the same eye-unit layout as [TryOnDrawing]: pupils at x = ∓1, y down the face.
 */
object PhotoGlasses {

    /** Renders [tryOn]'s glasses for each face in [scene] into [photo]. */
    fun render(photo: PhotoPixels, tryOn: TryOn, scene: FaceScene) {
        if (tryOn.glasses == GlassesStyle.NONE) return
        for (shape in scene.faces) {
            if (shape.count < FACE_POINTS) continue
            val face = Face(shape, photo.width.toFloat(), photo.height.toFloat())
            renderFace(photo, Layout(face, tryOn), scene.hair)
        }
    }

    /** Where the frames sit on one face, in eye units. */
    private class Layout(val face: Face, tryOn: TryOn) {
        val style = tryOn.glasses
        val tint = tryOn.lens
        val colour = if (style == GlassesStyle.RIMLESS) FrameColour.SILVER else tryOn.frame
        val frame = EyeFrame(face.mid(33, 133), face.mid(362, 263))
        private val widthLeft = face.dist(33, 133)
        private val widthRight = face.dist(362, 263)
        val squeezeLeft = (2 * widthLeft / (widthLeft + widthRight)).coerceIn(0.6f, 1.2f)
        val squeezeRight = (2 * widthRight / (widthLeft + widthRight)).coerceIn(0.6f, 1.2f)
        val lensRight = TryOnDrawing.lens(style).map { (x, y) ->
            Pt(TryOnDrawing.LENS_X + x * TryOnDrawing.LENS_SCALE * squeezeRight, TryOnDrawing.LENS_Y + y * TryOnDrawing.LENS_SCALE)
        }
        val lensLeft = TryOnDrawing.lens(style).map { (x, y) ->
            Pt(-TryOnDrawing.LENS_X - x * TryOnDrawing.LENS_SCALE * squeezeLeft, TryOnDrawing.LENS_Y + y * TryOnDrawing.LENS_SCALE)
        }
        val centreLeft = centroid(lensLeft)
        val centreRight = centroid(lensRight)
        val metal = colour == FrameColour.GOLD || colour == FrameColour.SILVER
        /** Half the rim's visible width. */
        val rim = when (style) {
            GlassesStyle.BOLD -> 0.1f
            GlassesStyle.RIMLESS -> 0f
            GlassesStyle.ROUND, GlassesStyle.AVIATOR -> if (metal) 0.028f else 0.05f
            else -> if (metal) 0.032f else 0.068f
        }
        /** How far out from the lens edge the middle of the rim sits. */
        val rimCentre = rim * 0.35f
        val bridge: List<Pt> = curvePoints(Pt(-0.27f, -0.08f), Pt(0f, -0.27f), Pt(0.27f, -0.08f))
        val bridgeRadius = if (metal || style == GlassesStyle.RIMLESS) max(rim * 0.8f, 0.022f) else rim * 0.85f
        /** The top bar across the brow that aviators have. */
        val browBar: List<Pt>? = if (style == GlassesStyle.AVIATOR) {
            val y = TryOnDrawing.LENS_Y - 0.44f * TryOnDrawing.LENS_SCALE
            curvePoints(Pt(-0.55f, y + 0.02f), Pt(0f, y - 0.02f), Pt(0.55f, y + 0.02f))
        } else null
        val hingeLeft = Pt(-TryOnDrawing.LENS_X - 0.82f * TryOnDrawing.LENS_SCALE * squeezeLeft, TryOnDrawing.LENS_Y - 0.24f)
        val hingeRight = Pt(TryOnDrawing.LENS_X + 0.82f * TryOnDrawing.LENS_SCALE * squeezeRight, TryOnDrawing.LENS_Y - 0.24f)
        val earLeft = frame.fromPhoto(face.pt(127))
        val earRight = frame.fromPhoto(face.pt(356))
        val armRadius = max(rim * 0.55f, 0.022f)
    }

    private fun renderFace(photo: PhotoPixels, layout: Layout, hair: HairMask?) {
        val frame = layout.frame
        val unit = frame.unit
        if (unit < 4f) return
        // The photo rectangle the frames, arms and shadows can reach.
        val reach = layout.lensLeft + layout.lensRight + listOf(layout.earLeft, layout.earRight)
        val margin = 0.3f
        val corners = reach.flatMap { listOf(Pt(it.x - margin, it.y - margin), Pt(it.x + margin, it.y + margin)) }.map(frame::toPhoto)
        val x0 = floor(corners.minOf { it.x }).toInt().coerceIn(0, photo.width)
        val y0 = floor(corners.minOf { it.y }).toInt().coerceIn(0, photo.height)
        val x1 = ceil(corners.maxOf { it.x }).toInt().coerceIn(x0, photo.width)
        val y1 = ceil(corners.maxOf { it.y }).toInt().coerceIn(y0, photo.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 2 || h < 2) return
        val argb = photo.read(x0, y0, w, h)
        val scene = Lighting.of(argb, w, h, layout, x0, y0)

        // Linear light, so shading and tints mix the way light does.
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        for (i in argb.indices) {
            r[i] = LINEAR[(argb[i] shr 16) and 0xFF]
            g[i] = LINEAR[(argb[i] shr 8) and 0xFF]
            b[i] = LINEAR[argb[i] and 0xFF]
        }

        val lenses = DistanceField(listOf(layout.lensLeft, layout.lensRight))
        val pixel = 1f / unit
        val material = Material.of(layout.colour)

        // Pass 1: shadows the frames cast on the skin.
        val shadowOffset = Pt(-scene.light[0] * 0.07f, 0.09f)
        val shadowBlur = 0.05f
        val arms = listOf(Stroke(listOf(layout.hingeLeft, layout.earLeft), layout.armRadius), Stroke(listOf(layout.hingeRight, layout.earRight), layout.armRadius))
        val bars = listOfNotNull(layout.bridge, layout.browBar).map { Stroke(it, layout.bridgeRadius) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val q = frame.fromPhoto(Pt(x0 + x + 0.5f, y0 + y + 0.5f)) - shadowOffset
                var shade = 0f
                val d = lenses.distance(q)
                if (layout.rim > 0f) shade = max(shade, soft(abs(d - layout.rimCentre) - layout.rim, shadowBlur))
                for (bar in bars) if (bar.near(q, shadowBlur)) shade = max(shade, soft(polylineDistance(bar.line, q) - layout.bridgeRadius, shadowBlur))
                for (arm in arms) if (arm.near(q, shadowBlur * 1.6f)) shade = max(shade, soft(polylineDistance(arm.line, q) - layout.armRadius, shadowBlur * 1.6f))
                var k = 1f - SHADOW * shade
                val i = y * w + x
                var kr = k
                var kg = k
                var kb = k
                if (d < 0f && layout.tint != LensTint.CLEAR) {
                    // Light through a tinted lens is dimmed and coloured on the way to the skin.
                    val t = transmittance(layout.tint, lensY(layout, q))
                    val inside = soft(d, shadowBlur)
                    kr *= 1f - LENS_SHADOW * inside * (1f - t[0])
                    kg *= 1f - LENS_SHADOW * inside * (1f - t[1])
                    kb *= 1f - LENS_SHADOW * inside * (1f - t[2])
                }
                r[i] *= kr
                g[i] *= kg
                b[i] *= kb
            }
        }
        val baseR = r.copyOf()
        val baseG = g.copyOf()
        val baseB = b.copyOf()

        val colour = FloatArray(3)
        val env = FloatArray(3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = Pt(x0 + x + 0.5f, y0 + y + 0.5f)
                val q = frame.fromPhoto(px)
                val i = y * w + x
                val d = lenses.distance(q)

                // Arms first: lenses and rims are in front of them.
                for (arm in arms) {
                    if (!arm.near(q, pixel)) continue
                    val hinge = arm.line[0]
                    val ear = arm.line[1]
                    val along = alongSegment(hinge, ear, q)
                    val radius = layout.armRadius * (1f - 0.3f * along)
                    val dist = segmentDistance(hinge, ear, q)
                    var cover = coverage(radius - dist, pixel)
                    if (cover <= 0f) continue
                    // The arm tucks behind the ear and under the hair at its far end.
                    cover *= 1f - smooth(0.82f, 1f, along)
                    if (hair != null) cover *= 1f - 0.9f * hair.at(px.x / photo.width, px.y / photo.height) * smooth(0.3f, 0.6f, along)
                    if (cover <= 0f) continue
                    val across = signedAcross(hinge, ear, q) / radius
                    shadeTube(material, scene, across.coerceIn(-1f, 1f), normalOf(hinge, ear), q, colour)
                    val depth = 1f - 0.45f * along
                    blend(r, g, b, i, colour[0] * depth, colour[1] * depth, colour[2] * depth, cover)
                }

                // Lens: what is behind it, bent a little, tinted, with reflections on top.
                val lensCover = coverage(-d, pixel)
                if (lensCover > 0f) {
                    val centre = if (q.x < 0f) layout.centreLeft else layout.centreRight
                    val seen = frame.toPhoto(centre + (q - centre) * LENS_MINIFY)
                    sample(baseR, baseG, baseB, w, h, seen.x - x0 - 0.5f, seen.y - y0 - 0.5f, colour)
                    val t = transmittance(layout.tint, lensY(layout, q))
                    // The lens edge is thicker glass: a faint darker ring just inside the rim.
                    val edge = 1f - 0.12f * smooth(-0.06f, 0f, d)
                    val local = (q - centre)
                    val nx = local.x * LENS_CURVE
                    val ny = local.y * LENS_CURVE
                    val nz = sqrt(max(0.05f, 1f - nx * nx - ny * ny))
                    // Tinted lenses carry a mirror-like coating, so they show more of what is in front.
                    val reflect = if (layout.tint == LensTint.CLEAR) LENS_REFLECT else TINTED_REFLECT
                    val fresnel = reflect + (1f - reflect) * (1f - nz).pow(5)
                    scene.environment(2 * nz * nx, 2 * nz * ny, env)
                    val window = scene.lensWindow(2 * nz * nx, 2 * nz * ny) * fresnel * WINDOW
                    val lr = colour[0] * t[0] * edge + env[0] * fresnel + window * scene.key[0]
                    val lg = colour[1] * t[1] * edge + env[1] * fresnel + window * scene.key[1]
                    val lb = colour[2] * t[2] * edge + env[2] * fresnel + window * scene.key[2]
                    blend(r, g, b, i, lr, lg, lb, lensCover)
                    if (layout.style == GlassesStyle.RIMLESS) {
                        // Without a rim, the polished lens edge is what shows.
                        val bevel = coverage(0.018f - abs(d + 0.012f), pixel)
                        if (bevel > 0f) {
                            val nxy = lenses.normal(q)
                            shadeTube(Material.EDGE, scene, ((d + 0.012f) / 0.018f).coerceIn(-1f, 1f), nxy, q, colour)
                            blend(r, g, b, i, colour[0], colour[1], colour[2], bevel * 0.85f)
                        }
                    }
                }

                // Rims.
                if (layout.rim > 0f) {
                    val across = d - layout.rimCentre
                    val cover = coverage(layout.rim - abs(across), pixel)
                    if (cover > 0f) {
                        shadeRim(material, scene, layout, across / layout.rim, lenses.normal(q), q, x0, y0, w, h, baseR, baseG, baseB, colour)
                        blend(r, g, b, i, colour[0], colour[1], colour[2], cover)
                    }
                }

                // Bridge and brow bar.
                for (bar in bars) {
                    if (!bar.near(q, pixel)) continue
                    val line = bar.line
                    val dist = polylineDistance(line, q)
                    // It joins the rims rather than crossing the lenses.
                    val cover = coverage(layout.bridgeRadius - dist, pixel) * coverage(d - layout.rimCentre, pixel)
                    if (cover <= 0f) continue
                    val (n, across) = polylineNormal(line, q)
                    shadeRim(material, scene, layout, (across / layout.bridgeRadius).coerceIn(-1f, 1f), n, q, x0, y0, w, h, baseR, baseG, baseB, colour)
                    blend(r, g, b, i, colour[0], colour[1], colour[2], cover)
                }
            }
        }

        for (i in argb.indices) {
            argb[i] = (argb[i] and 0xFF000000.toInt()) or (encode(r[i]) shl 16) or (encode(g[i]) shl 8) or encode(b[i])
        }
        photo.write(argb, x0, y0, w, h)
    }

    // --- Materials and shading -----------------------------------------------------------

    private class Material(
        val albedo: FloatArray,
        val metal: Boolean,
        val shine: Float,
        val translucent: Float = 0f,
        val tortoise: Boolean = false,
    ) {
        companion object {
            fun of(colour: FrameColour) = when (colour) {
                FrameColour.BLACK -> Material(floatArrayOf(0.012f, 0.012f, 0.013f), metal = false, shine = 120f)
                FrameColour.TORTOISE -> Material(floatArrayOf(0.3f, 0.12f, 0.03f), metal = false, shine = 120f, translucent = 0.25f, tortoise = true)
                FrameColour.GOLD -> Material(floatArrayOf(0.95f, 0.72f, 0.33f), metal = true, shine = 60f)
                FrameColour.SILVER -> Material(floatArrayOf(0.85f, 0.86f, 0.88f), metal = true, shine = 60f)
                FrameColour.RED -> Material(floatArrayOf(0.42f, 0.025f, 0.03f), metal = false, shine = 120f, translucent = 0.15f)
                FrameColour.CLEAR -> Material(floatArrayOf(0.85f, 0.83f, 0.8f), metal = false, shine = 140f, translucent = 0.8f)
            }

            /** A polished glass edge, for rimless lenses. */
            val EDGE = Material(floatArrayOf(0.9f, 0.95f, 0.95f), metal = false, shine = 150f, translucent = 0.9f)
        }
    }

    /** A rim's cross-section: flat across the front with rounded edges for plastic, round for metal. */
    private fun shadeRim(
        material: Material, scene: Lighting, layout: Layout, across: Float, outward: Pt, q: Pt,
        x0: Int, y0: Int, w: Int, h: Int, baseR: FloatArray, baseG: FloatArray, baseB: FloatArray, out: FloatArray,
    ) {
        if (material.metal) {
            shadeTube(material, scene, across, outward, q, out)
            return
        }
        val bevel = smooth(0.4f, 1f, abs(across))
        val tilt = (PI.toFloat() / 2f) * 0.85f * bevel.pow(1.5f) * (if (across < 0f) -1f else 1f)
        shadeSurface(material, scene, outward.x * sin(tilt), outward.y * sin(tilt), kotlin.math.cos(tilt), q, out)
        if (material.translucent > 0f) {
            // See-through plastic shows the skin behind it, darker towards its edges.
            val behind = FloatArray(3)
            val p = layout.frame.toPhoto(q + outward * (across * layout.rim * 0.5f))
            sample(baseR, baseG, baseB, w, h, p.x - x0 - 0.5f, p.y - y0 - 0.5f, behind)
            val k = material.translucent * (1f - 0.5f * bevel)
            for (c in 0..2) out[c] = out[c] * (1f - k) + behind[c] * material.albedo[c] * k
        }
    }

    private fun shadeTube(material: Material, scene: Lighting, across: Float, outward: Pt, q: Pt, out: FloatArray) {
        val s = across.coerceIn(-0.98f, 0.98f)
        val z = sqrt(1f - s * s)
        shadeSurface(material, scene, outward.x * s, outward.y * s, z, q, out)
    }

    /** Lights a surface point with normal ([nx], [ny], [nz]) in face space: x across, y down, z to the camera. */
    private fun shadeSurface(material: Material, scene: Lighting, nx: Float, ny: Float, nz: Float, q: Pt, out: FloatArray) {
        val l = scene.light
        val diffuse = max(0f, nx * l[0] + ny * l[1] + nz * l[2])
        // Halfway between the light and the camera.
        var hx = l[0]
        var hy = l[1]
        var hz = l[2] + 1f
        val hl = sqrt(hx * hx + hy * hy + hz * hz)
        hx /= hl; hy /= hl; hz /= hl
        val spec = max(0f, nx * hx + ny * hy + nz * hz).pow(material.shine)
        // The direction the camera sees reflected in the surface.
        val rx = 2 * nz * nx
        val ry = 2 * nz * ny
        val env = FloatArray(3)
        scene.environment(rx, ry, env)
        val window = scene.window(rx, ry)
        val albedo = if (material.tortoise) tortoise(q) else material.albedo
        if (material.metal) {
            for (c in 0..2) {
                out[c] = albedo[c] * (env[c] * 0.85f + scene.key[c] * (window * 0.6f + spec * 1.4f) + scene.ambient[c] * 0.1f * diffuse)
            }
        } else {
            val fresnel = 0.04f + 0.96f * (1f - nz).coerceIn(0f, 1f).pow(5)
            for (c in 0..2) {
                val body = albedo[c] * (scene.ambient[c] * 0.55f + scene.key[c] * diffuse * 0.6f)
                out[c] = body + env[c] * fresnel + scene.key[c] * (spec * 1.6f + window * fresnel * 2f)
            }
        }
    }

    /** Tortoiseshell: dark brown with amber patches, from smooth noise across the frame. */
    private fun tortoise(q: Pt): FloatArray {
        val n = fbm(q.x * 5f + q.y * 2f, q.y * 13f)
        val amber = smooth(0.48f, 0.68f, n)
        val dark = 1f - smooth(0.25f, 0.42f, n)
        val r = 0.06f + 0.42f * amber - 0.04f * dark
        val g = 0.022f + 0.17f * amber - 0.015f * dark
        val b = 0.008f + 0.035f * amber - 0.005f * dark
        return floatArrayOf(max(r, 0.01f), max(g, 0.004f), max(b, 0.002f))
    }

    /** How much of each colour channel a lens lets through, [y] from 0 at its top to 1 at its bottom. */
    private fun transmittance(tint: LensTint, y: Float): FloatArray = when (tint) {
        LensTint.CLEAR -> floatArrayOf(0.95f, 0.96f, 0.96f)
        LensTint.GREY -> floatArrayOf(0.13f, 0.135f, 0.14f)
        LensTint.BROWN -> floatArrayOf(0.26f, 0.15f, 0.07f)
        LensTint.BLUE -> floatArrayOf(0.1f, 0.17f, 0.32f)
        LensTint.GRADIENT -> {
            val k = 0.1f + 0.75f * smooth(0.15f, 0.95f, y)
            floatArrayOf(k, k * 1.01f, k * 1.03f)
        }
    }

    /** Where [q] is between the top (0) and bottom (1) of its lens. */
    private fun lensY(layout: Layout, q: Pt): Float {
        val top = TryOnDrawing.LENS_Y - 0.55f * TryOnDrawing.LENS_SCALE
        val bottom = TryOnDrawing.LENS_Y + 0.6f * TryOnDrawing.LENS_SCALE
        return ((q.y - top) / (bottom - top)).coerceIn(0f, 1f)
    }

    /**
     * The light in the photo round the eyes, so the frames are lit like the face: the key light's
     * colour from the brightest skin, the ambient colour from the average, and its direction from
     * which cheek is brighter.
     */
    private class Lighting(val key: FloatArray, val ambient: FloatArray, val light: FloatArray) {

        /** What a shiny surface reflects in direction ([rx], [ry]) (y down): brighter above, darker below. */
        fun environment(rx: Float, ry: Float, out: FloatArray) {
            val up = smooth(-0.1f, 0.7f, -ry)
            val down = smooth(0f, 0.8f, ry)
            for (c in 0..2) {
                out[c] = ambient[c] * (0.55f * (1f - up) * (1f - down) + 0.18f * down) + key[c] * 0.75f * up
            }
        }

        /** A soft bright window up towards the light, the reflection that makes glass and metal read as real. */
        fun window(rx: Float, ry: Float): Float {
            val dx = rx - light[0] * 0.9f
            val dy = ry - light[1] * 0.9f
            val across = smooth(0.32f, 0.18f, abs(dx))
            val down = smooth(0.3f, 0.14f, abs(dy))
            return across * down
        }

        /** The same window seen in a lens: broader, softer and higher, as a lens is only gently curved. */
        fun lensWindow(rx: Float, ry: Float): Float {
            // A soft slanted streak across the upper outer part, clear of the eyes.
            val dx = rx - (light[0] * 0.4f + 0.3f)
            val dy = ry + 0.52f
            val along = dx * 0.8f - dy * 0.6f
            val across = dx * 0.6f + dy * 0.8f
            return smooth(0.32f, 0.1f, abs(along)) * smooth(0.1f, 0.03f, abs(across))
        }

        companion object {
            fun of(argb: IntArray, w: Int, h: Int, layout: Layout, x0: Int, y0: Int): Lighting {
                val lum = FloatArray(argb.size)
                val histogram = IntArray(HISTOGRAM)
                for (i in argb.indices) {
                    lum[i] = 0.2126f * LINEAR[(argb[i] shr 16) and 0xFF] + 0.7152f * LINEAR[(argb[i] shr 8) and 0xFF] + 0.0722f * LINEAR[argb[i] and 0xFF]
                    histogram[(lum[i] * (HISTOGRAM - 1)).toInt().coerceIn(0, HISTOGRAM - 1)]++
                }
                // The level the brightest 3% of pixels are above.
                var bin = HISTOGRAM - 1
                var above = 0
                while (bin > 0 && above + histogram[bin] < argb.size * 0.03f) above += histogram[bin--]
                val high = bin.toFloat() / (HISTOGRAM - 1)
                val key = FloatArray(3)
                val ambient = FloatArray(3)
                var keyCount = 0
                for (i in argb.indices) {
                    val cr = LINEAR[(argb[i] shr 16) and 0xFF]
                    val cg = LINEAR[(argb[i] shr 8) and 0xFF]
                    val cb = LINEAR[argb[i] and 0xFF]
                    ambient[0] += cr
                    ambient[1] += cg
                    ambient[2] += cb
                    if (lum[i] >= high) {
                        key[0] += cr
                        key[1] += cg
                        key[2] += cb
                        keyCount++
                    }
                }
                for (k in 0..2) {
                    ambient[k] /= argb.size
                    key[k] = if (keyCount > 0) key[k] / keyCount else ambient[k]
                }
                // Lift the key so a highlight on the frames is as bright as the brightest skin.
                val keyLum = 0.2126f * key[0] + 0.7152f * key[1] + 0.0722f * key[2]
                val lift = if (keyLum > 1e-4f) (min(1f, keyLum * 1.5f) / keyLum) else 1f
                for (k in 0..2) key[k] = (key[k] * lift).coerceAtMost(1f)

                // Which side the light comes from: compare the cheeks.
                fun skin(index: Int): Float {
                    val p = layout.face.pt(index)
                    val cx = (p.x - x0).toInt()
                    val cy = (p.y - y0).toInt()
                    val r = max(2, (layout.frame.unit * 0.12f).toInt())
                    var sum = 0f
                    var n = 0
                    for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                        if (x in 0 until w && y in 0 until h) {
                            sum += lum[y * w + x]
                            n++
                        }
                    }
                    return if (n == 0) 0f else sum / n
                }
                val left = skin(CHEEK_LEFT)
                val right = skin(CHEEK_RIGHT)
                val side = if (left + right > 1e-4f) ((right - left) / (right + left) * 2f).coerceIn(-0.6f, 0.6f) else 0f
                val lx = side
                val ly = -0.7f
                val lz = 0.7f
                val ll = sqrt(lx * lx + ly * ly + lz * lz)
                return Lighting(key, ambient, floatArrayOf(lx / ll, ly / ll, lz / ll))
            }
        }
    }

    // --- Distance helpers ----------------------------------------------------------------

    /**
     * Signed distance (negative inside) to the lens outlines, in eye units. A coarse grid keeps
     * which edge of which outline is nearest, so each pixel only measures the few edges round it
     * rather than walking every outline.
     */
    private class DistanceField(private val outlines: List<List<Pt>>) {
        private val minX: Float
        private val minY: Float
        private val cols: Int
        private val rows: Int
        private val nearest: IntArray

        /** Each outline's outward normal at each of its points, averaged from the two edges there. */
        private val pointNormals: List<List<Pt>> = outlines.map { outline ->
            val n = outline.size
            var area = 0f
            for (k in 0 until n) {
                val a = outline[k]
                val b = outline[(k + 1) % n]
                area += a.x * b.y - b.x * a.y
            }
            // With y down, a positive area runs clockwise on screen, so the outside is to the left of travel.
            val turn = if (area > 0f) 1f else -1f
            val edges = (0 until n).map { k ->
                val d = (outline[(k + 1) % n] - outline[k]).unit()
                Pt(d.y * turn, -d.x * turn)
            }
            (0 until n).map { k -> (edges[(k + n - 1) % n] + edges[k]).unit() }
        }

        init {
            val all = outlines.flatten()
            minX = all.minOf { it.x } - PAD
            minY = all.minOf { it.y } - PAD
            cols = ceil((all.maxOf { it.x } + PAD - minX) / CELL).toInt() + 1
            rows = ceil((all.maxOf { it.y } + PAD - minY) / CELL).toInt() + 1
            nearest = IntArray(cols * rows)
            for (j in 0 until rows) for (i in 0 until cols) {
                val p = Pt(minX + (i + 0.5f) * CELL, minY + (j + 0.5f) * CELL)
                var best = Float.MAX_VALUE
                for ((o, outline) in outlines.withIndex()) {
                    for (k in outline.indices) {
                        val d = segmentDistance(outline[k], outline[(k + 1) % outline.size], p)
                        if (d < best) {
                            best = d
                            nearest[j * cols + i] = o * SLOTS + k
                        }
                    }
                }
            }
        }

        private var lastOutline = 0
        private var lastK = 0
        private var lastT = 0f

        /** Finds the nearest edge to [p], leaving it in last*, and returns the unsigned distance; or -1 if [p] is off the grid. */
        private fun closest(p: Pt): Float {
            val i = ((p.x - minX) / CELL).toInt()
            val j = ((p.y - minY) / CELL).toInt()
            if (p.x < minX || p.y < minY || i >= cols || j >= rows) return -1f
            val code = nearest[j * cols + i]
            val o = code / SLOTS
            val outline = outlines[o]
            val n = outline.size
            var best = Float.MAX_VALUE
            for (step in -SEARCH..SEARCH) {
                val k = (code % SLOTS + step + n) % n
                val d = segmentDistance(outline[k], outline[(k + 1) % n], p)
                if (d < best) {
                    best = d
                    lastK = k
                }
            }
            lastOutline = o
            lastT = alongSegment(outline[lastK], outline[(lastK + 1) % n], p)
            return best
        }

        private fun lastNormal(): Pt {
            val normals = pointNormals[lastOutline]
            val next = (lastK + 1) % normals.size
            return (normals[lastK] * (1 - lastT) + normals[next] * lastT).unit()
        }

        fun distance(p: Pt): Float {
            val d = closest(p)
            if (d < 0f) return FAR
            val outline = outlines[lastOutline]
            val a = outline[lastK]
            val b = outline[(lastK + 1) % outline.size]
            val n = lastNormal()
            val side = (p.x - (a.x + (b.x - a.x) * lastT)) * n.x + (p.y - (a.y + (b.y - a.y) * lastT)) * n.y
            return if (side < 0f) -d else d
        }

        /** The direction out of the lens at the nearest point of its outline, smooth along it. */
        fun normal(p: Pt): Pt = if (closest(p) < 0f) Pt(0f, -1f) else lastNormal()

        private companion object {
            const val CELL = 0.01f
            const val PAD = 0.4f
            const val SLOTS = 1 shl 16
            const val SEARCH = 3
        }
    }

    /** A frame part along [line] of up to [radius] eye units each side, with a box to skip far pixels quickly. */
    private class Stroke(val line: List<Pt>, radius: Float) {
        private val minX = line.minOf { it.x } - radius
        private val minY = line.minOf { it.y } - radius
        private val maxX = line.maxOf { it.x } + radius
        private val maxY = line.maxOf { it.y } + radius

        fun near(q: Pt, pad: Float) = q.x >= minX - pad && q.x <= maxX + pad && q.y >= minY - pad && q.y <= maxY + pad
    }

    private fun segmentDistance(a: Pt, b: Pt, p: Pt): Float {
        val t = alongSegment(a, b, p)
        return hypot(p.x - (a.x + (b.x - a.x) * t), p.y - (a.y + (b.y - a.y) * t))
    }

    private fun alongSegment(a: Pt, b: Pt, p: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9f) return 0f
        return (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    }

    /** How far [p] is to the side of the line through [a] and [b], signed. */
    private fun signedAcross(a: Pt, b: Pt, p: Pt): Float {
        val n = normalOf(a, b)
        return (p.x - a.x) * n.x + (p.y - a.y) * n.y
    }

    /** The unit normal of the line from [a] to [b], turned so it points up the face where it can. */
    private fun normalOf(a: Pt, b: Pt): Pt {
        val d = (b - a).unit()
        val n = Pt(d.y, -d.x)
        return if (n.y > 0f) Pt(-n.x, -n.y) else n
    }

    private fun polylineDistance(line: List<Pt>, p: Pt): Float {
        var best = Float.MAX_VALUE
        for (k in 0 until line.size - 1) best = min(best, segmentDistance(line[k], line[k + 1], p))
        return best
    }

    /** The normal of the nearest piece of [line] and how far [p] is along it. */
    private fun polylineNormal(line: List<Pt>, p: Pt): Pair<Pt, Float> {
        var best = Float.MAX_VALUE
        var k0 = 0
        for (k in 0 until line.size - 1) {
            val d = segmentDistance(line[k], line[k + 1], p)
            if (d < best) {
                best = d
                k0 = k
            }
        }
        val n = normalOf(line[k0], line[k0 + 1])
        return n to signedAcross(line[k0], line[k0 + 1], p)
    }

    private fun curvePoints(a: Pt, control: Pt, b: Pt): List<Pt> = (0..12).map { i ->
        val t = i / 12f
        val u = 1 - t
        Pt(u * u * a.x + 2 * u * t * control.x + t * t * b.x, u * u * a.y + 2 * u * t * control.y + t * t * b.y)
    }

    private fun centroid(points: List<Pt>) = Pt(points.sumOf { it.x.toDouble() }.toFloat() / points.size, points.sumOf { it.y.toDouble() }.toFloat() / points.size)

    // --- Pixel helpers -------------------------------------------------------------------

    /** How much of a pixel ([pixel] eye units across) lies within [inside] eye units of an edge. */
    private fun coverage(inside: Float, pixel: Float) = (inside / pixel + 0.5f).coerceIn(0f, 1f)

    /** 1 well inside an edge ([d] < 0), 0 well outside, over a soft width of [blur]. */
    private fun soft(d: Float, blur: Float) = smooth(blur, -blur, d)

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun blend(r: FloatArray, g: FloatArray, b: FloatArray, i: Int, cr: Float, cg: Float, cb: Float, a: Float) {
        r[i] += (cr - r[i]) * a
        g[i] += (cg - g[i]) * a
        b[i] += (cb - b[i]) * a
    }

    /** Bilinear sample of a linear-light image at pixel position ([fx], [fy]). */
    private fun sample(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, fx: Float, fy: Float, out: FloatArray) {
        val x = fx.coerceIn(0f, w - 1.001f)
        val y = fy.coerceIn(0f, h - 1.001f)
        val i = x.toInt()
        val j = y.toInt()
        val tx = x - i
        val ty = y - j
        val a = j * w + i
        val c = a + w
        fun mix(v: FloatArray) = (v[a] * (1 - tx) + v[a + 1] * tx) * (1 - ty) + (v[c] * (1 - tx) + v[c + 1] * tx) * ty
        out[0] = mix(r)
        out[1] = mix(g)
        out[2] = mix(b)
    }

    private fun fbm(x: Float, y: Float): Float {
        var sum = 0f
        var amp = 0.5f
        var fx = x
        var fy = y
        repeat(4) {
            sum += amp * valueNoise(fx, fy)
            fx = fx * 2.03f + 1.7f
            fy = fy * 2.03f + 4.1f
            amp *= 0.5f
        }
        return sum / 0.9375f
    }

    private fun valueNoise(x: Float, y: Float): Float {
        val i = floor(x).toInt()
        val j = floor(y).toInt()
        val tx = x - i
        val ty = y - j
        val sx = tx * tx * (3 - 2 * tx)
        val sy = ty * ty * (3 - 2 * ty)
        val a = hash(i, j)
        val b = hash(i + 1, j)
        val c = hash(i, j + 1)
        val d = hash(i + 1, j + 1)
        return (a * (1 - sx) + b * sx) * (1 - sy) + (c * (1 - sx) + d * sx) * sy
    }

    private fun hash(i: Int, j: Int): Float {
        var h = i * 374761393 + j * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private val LINEAR = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    private val ENCODE = IntArray(4096) { i ->
        val c = i / 4095f
        val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
        (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun encode(v: Float) = ENCODE[(v * 4095f).toInt().coerceIn(0, 4095)]

    private const val FACE_POINTS = 468
    private const val CHEEK_LEFT = 205
    private const val CHEEK_RIGHT = 425
    private const val FAR = 10f
    private const val HISTOGRAM = 1024
    private const val SHADOW = 0.42f
    private const val LENS_SHADOW = 0.45f
    private const val LENS_MINIFY = 0.96f
    private const val LENS_CURVE = 0.55f
    private const val LENS_REFLECT = 0.035f
    private const val TINTED_REFLECT = 0.07f
    private const val WINDOW = 1.4f
}
