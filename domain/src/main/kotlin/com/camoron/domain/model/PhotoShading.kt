package com.camoron.domain.model

import com.camoron.domain.model.TryOnDrawing.Pt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// Shared pieces for rendering try-ons into photo pixels: the photo's light, distances to
// outlines and lines in face units, and linear-light pixel helpers.

/**
 * The light in the photo round a face, so things put on it are lit like it: the key light's
 * colour from the brightest skin, the ambient colour from the average, and its direction from
 * which cheek is brighter.
 */
internal class PhotoLight(val key: FloatArray, val ambient: FloatArray, val light: FloatArray) {

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

    companion object {
        fun of(argb: IntArray, w: Int, h: Int, face: TryOnDrawing.Face, unit: Float, x0: Int, y0: Int): PhotoLight {
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
            // Sample the cheeks: their brightness says which side the light comes from, their
            // colour what colour it is.
            val skinColour = FloatArray(3)
            fun skin(index: Int): Float {
                val p = face.pt(index)
                val cx = (p.x - x0).toInt()
                val cy = (p.y - y0).toInt()
                val r = max(2, (unit * 0.12f).toInt())
                var sum = 0f
                var n = 0
                for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                    if (x in 0 until w && y in 0 until h) {
                        val c = argb[y * w + x]
                        skinColour[0] += LINEAR[(c shr 16) and 0xFF]
                        skinColour[1] += LINEAR[(c shr 8) and 0xFF]
                        skinColour[2] += LINEAR[c and 0xFF]
                        sum += lum[y * w + x]
                        n++
                    }
                }
                return if (n == 0) 0f else sum / n
            }
            val left = skin(CHEEK_LEFT)
            val right = skin(CHEEK_RIGHT)
            // Skin is warm by itself, so its colour overstates how warm the light is: take typical
            // skin's colour out, and trust half of what is left. Everything else round the face
            // would mislead more, so the key and ambient keep only their brightness.
            val tint = lightTint(skinColour)
            for (c in listOf(key, ambient)) {
                val l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
                for (k in 0..2) c[k] = l * tint[k]
            }
            // Lift the key so a highlight on what is put on the face is as bright as the brightest skin.
            val keyLum = 0.2126f * key[0] + 0.7152f * key[1] + 0.0722f * key[2]
            val lift = if (keyLum > 1e-4f) (min(1f, keyLum * 1.5f) / keyLum) else 1f
            for (k in 0..2) key[k] = (key[k] * lift).coerceAtMost(1f)

            val side = if (left + right > 1e-4f) ((right - left) / (right + left) * 2f).coerceIn(-0.6f, 0.6f) else 0f
            val lx = side
            val ly = -0.7f
            val lz = 0.7f
            val ll = sqrt(lx * lx + ly * ly + lz * lz)
            return PhotoLight(key, ambient, floatArrayOf(lx / ll, ly / ll, lz / ll))
        }

        /** The light's colour, relative to its brightness, from the colour of the skin it falls on. */
        private fun lightTint(skin: FloatArray): FloatArray {
            val r = skin[0] / SKIN[0]
            val g = skin[1] / SKIN[1]
            val b = skin[2] / SKIN[2]
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            if (l < 1e-5f) return floatArrayOf(1f, 1f, 1f)
            return floatArrayOf(0.5f + 0.5f * r / l, 0.5f + 0.5f * g / l, 0.5f + 0.5f * b / l)
        }
    }
}

/**
 * Signed distance (negative inside) to closed outlines, in eye units. A coarse grid keeps
 * which edge of which outline is nearest, so each pixel only measures the few edges round it
 * rather than walking every outline.
 */
internal class DistanceField(private val outlines: List<List<Pt>>, private val cell: Float = FINE) {
    private val minX: Float
    private val minY: Float
    private val cols: Int
    private val rows: Int
    private val nearest: IntArray
    private val inside: BooleanArray
    private val cellDistance: FloatArray

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
        cols = ceil((all.maxOf { it.x } + PAD - minX) / cell).toInt() + 1
        rows = ceil((all.maxOf { it.y } + PAD - minY) / cell).toInt() + 1
        nearest = IntArray(cols * rows)
        inside = BooleanArray(cols * rows)
        cellDistance = FloatArray(cols * rows)
        for (j in 0 until rows) for (i in 0 until cols) {
            val p = Pt(minX + (i + 0.5f) * cell, minY + (j + 0.5f) * cell)
            var best = Float.MAX_VALUE
            var crossings = false
            for (o in outlines.indices) {
                val outline = outlines[o]
                for (k in outline.indices) {
                    val a = outline[k]
                    val b = outline[(k + 1) % outline.size]
                    val d = segmentDistance(a, b, p)
                    if (d < best) {
                        best = d
                        nearest[j * cols + i] = o * SLOTS + k
                    }
                    if ((a.y > p.y) != (b.y > p.y) && p.x < a.x + (p.y - a.y) / (b.y - a.y) * (b.x - a.x)) crossings = !crossings
                }
            }
            inside[j * cols + i] = crossings
            cellDistance[j * cols + i] = best
        }
    }

    private var lastOutline = 0
    private var lastK = 0
    private var lastT = 0f

    /** Finds the nearest edge to [p], leaving it in last*, and returns the unsigned distance; or -1 if [p] is off the grid. */
    private fun closest(p: Pt): Float {
        val i = ((p.x - minX) / cell).toInt()
        val j = ((p.y - minY) / cell).toInt()
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
        if (p.x < minX || p.y < minY) return FAR
        val i = ((p.x - minX) / cell).toInt()
        val j = ((p.y - minY) / cell).toInt()
        if (i >= cols || j >= rows) return FAR
        // Well away from every edge nothing needs the exact distance, so the cell's will do.
        val far = cellDistance[j * cols + i]
        if (far > ROUGH) return if (inside[j * cols + i]) -far else far
        val d = closest(p)
        if (d < 0f) return FAR
        // Away from the edge, the grid's own inside test decides; right by it, which side of the edge.
        if (d > cell * 1.5f) return if (inside[((p.y - minY) / cell).toInt() * cols + ((p.x - minX) / cell).toInt()]) -d else d
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
        /** Grid spacing for small outlines such as lenses; larger ones can use a coarser grid. */
        const val FINE = 0.01f
        const val PAD = 0.4f
        const val SLOTS = 1 shl 16
        const val SEARCH = 3
        const val ROUGH = 0.25f
    }
}

/** A frame part along [line] of up to [radius] eye units each side, with a box to skip far pixels quickly. */
internal class Stroke(val line: List<Pt>, radius: Float) {
    private val minX = line.minOf { it.x } - radius
    private val minY = line.minOf { it.y } - radius
    private val maxX = line.maxOf { it.x } + radius
    private val maxY = line.maxOf { it.y } + radius

    fun near(q: Pt, pad: Float) = q.x >= minX - pad && q.x <= maxX + pad && q.y >= minY - pad && q.y <= maxY + pad
}

internal fun segmentDistance(a: Pt, b: Pt, p: Pt): Float {
    val t = alongSegment(a, b, p)
    val dx = p.x - (a.x + (b.x - a.x) * t)
    val dy = p.y - (a.y + (b.y - a.y) * t)
    // Not hypot: that guards against overflow nobody here needs, and is several times slower.
    return sqrt(dx * dx + dy * dy)
}

internal fun alongSegment(a: Pt, b: Pt, p: Pt): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 < 1e-9f) return 0f
    return (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
}

/** How far [p] is to the side of the line through [a] and [b], signed. */
internal fun signedAcross(a: Pt, b: Pt, p: Pt): Float {
    val n = normalOf(a, b)
    return (p.x - a.x) * n.x + (p.y - a.y) * n.y
}

/** The unit normal of the line from [a] to [b], turned so it points up the face where it can. */
internal fun normalOf(a: Pt, b: Pt): Pt {
    val d = (b - a).unit()
    val n = Pt(d.y, -d.x)
    return if (n.y > 0f) Pt(-n.x, -n.y) else n
}

internal fun polylineDistance(line: List<Pt>, p: Pt): Float {
    var best = Float.MAX_VALUE
    for (k in 0 until line.size - 1) best = min(best, segmentDistance(line[k], line[k + 1], p))
    return best
}

/** The normal of the nearest piece of [line] and how far [p] is along it. */
internal fun polylineNormal(line: List<Pt>, p: Pt): Pair<Pt, Float> {
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

internal fun curvePoints(a: Pt, control: Pt, b: Pt): List<Pt> = (0..12).map { i ->
    val t = i / 12f
    val u = 1 - t
    Pt(u * u * a.x + 2 * u * t * control.x + t * t * b.x, u * u * a.y + 2 * u * t * control.y + t * t * b.y)
}

internal fun centroid(points: List<Pt>) = Pt(points.sumOf { it.x.toDouble() }.toFloat() / points.size, points.sumOf { it.y.toDouble() }.toFloat() / points.size)


/** How much of a pixel ([pixel] eye units across) lies within [inside] eye units of an edge. */
internal fun coverage(inside: Float, pixel: Float) = (inside / pixel + 0.5f).coerceIn(0f, 1f)

/** 1 well inside an edge ([d] < 0), 0 well outside, over a soft width of [blur]. */
internal fun soft(d: Float, blur: Float) = smooth(blur, -blur, d)

internal fun smooth(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

internal fun blend(r: FloatArray, g: FloatArray, b: FloatArray, i: Int, cr: Float, cg: Float, cb: Float, a: Float) {
    r[i] += (cr - r[i]) * a
    g[i] += (cg - g[i]) * a
    b[i] += (cb - b[i]) * a
}

/** Bilinear sample of a linear-light image at pixel position ([fx], [fy]). */
internal fun sample(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, fx: Float, fy: Float, out: FloatArray) {
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

internal fun fbm(x: Float, y: Float): Float {
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

internal fun valueNoise(x: Float, y: Float): Float {
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

internal fun hash(i: Int, j: Int): Float {
    var h = i * 374761393 + j * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777215f
}

internal val LINEAR = FloatArray(256) { i ->
    val c = i / 255f
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
}

internal val ENCODE = IntArray(4096) { i ->
    val c = i / 4095f
    val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    (s * 255f + 0.5f).toInt().coerceIn(0, 255)
}

internal fun encode(v: Float) = ENCODE[(v * 4095f).toInt().coerceIn(0, 4095)]

private const val CHEEK_LEFT = 205
private const val CHEEK_RIGHT = 425
private const val FAR = 10f
private const val HISTOGRAM = 1024

/** Skin's colour in linear light relative to its brightness: a little less warm than typical, so only a clear cast is corrected. */
private val SKIN = floatArrayOf(1.45f, 0.9f, 0.65f)
