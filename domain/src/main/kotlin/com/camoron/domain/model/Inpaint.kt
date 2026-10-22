package com.camoron.domain.model

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Content-aware fill: rebuilds the pixels under a hole from patches of the surrounding picture,
 * so a removed object is replaced by matching background (grass, sky, wall, sand) with its real
 * texture rather than a blur.
 *
 * This is multi-scale PatchMatch completion (Barnes et al. 2009; Wexler et al. 2007): the hole is
 * first filled roughly at a small scale, then at each larger scale every patch touching the hole
 * finds its most similar patch nearby outside it, and the hole's pixels become the weighted
 * average of what those patches say. Only a region around the hole is processed, and a large
 * region is solved at a reduced size and then rebuilt from the full-size pixels.
 */
object Inpaint {

    private const val RADIUS = 3
    private const val PATCH_AREA = ((2 * RADIUS + 1) * (2 * RADIUS + 1)).toFloat()
    private const val COARSE_SIZE = 32
    private const val MAX_LEVELS = 9
    private const val MIN_MARGIN = 24

    /** The most pixels searched at once; bigger regions are solved smaller and rebuilt at full size. */
    private const val MAX_WORK_PIXELS = 640_000

    /** How quickly trust falls off inside the hole, per pixel from its edge (Wexler et al.). */
    private const val DECAY = 1.3f

    /**
     * How strongly nearby patches are preferred to distant ones, as the colour error (squared, per
     * pixel) that a source one hole-width away must beat. Keeps the fill from importing unrelated
     * things (a face, a sign) from elsewhere in the picture.
     */
    private const val LOCALITY = 1000f

    private const val ALPHA = 0xFF shl 24
    private val BACK = listOf(-1 to 0, 0 to -1)
    private val AHEAD = listOf(1 to 0, 0 to 1)

    /**
     * Fills [hole] (true where a pixel must be rebuilt) in [pixels], a [width] x [height] ARGB
     * picture, and returns the filled picture. [seed] makes the result repeatable.
     */
    fun fill(pixels: IntArray, width: Int, height: Int, hole: BooleanArray, seed: Int = 1): IntArray {
        val box = bounds(hole, width, height) ?: return pixels.copyOf()
        val (x0, y0, x1, y1) = area(box, width, height)

        var scale = 1
        while ((x1 - x0).toLong() * (y1 - y0) / (scale.toLong() * scale) > MAX_WORK_PIXELS) scale *= 2

        val work = Level.of(pixels, width, hole, x0, y0, x1, y1, scale)
        val field = complete(work, Random(seed))

        val out = pixels.copyOf()
        if (scale == 1) {
            for (y in 0 until work.h) {
                for (x in 0 until work.w) {
                    val o = (y0 + y) * width + x0 + x
                    if (hole[o]) out[o] = (pixels[o] and ALPHA) or work.rgb(y * work.w + x)
                }
            }
        } else {
            rebuild(pixels, out, width, hole, intArrayOf(x0, y0, x1, y1), scale, work, field)
        }
        return out
    }

    /**
     * The part of a [width] x [height] picture that filling a hole within [box] (left, top, right,
     * bottom exclusive) uses: the hole plus a margin of surroundings, about the hole's size, to
     * copy from. Nothing outside it affects the fill, so a caller can pass just this part.
     */
    fun area(box: IntArray, width: Int, height: Int): IntArray {
        val margin = max(max(box[2] - box[0], box[3] - box[1]), MIN_MARGIN) + 2 * RADIUS
        return intArrayOf(max(0, box[0] - margin), max(0, box[1] - margin), min(width, box[2] + margin), min(height, box[3] + margin))
    }

    /** Smallest box (left, top, right, bottom exclusive) around the hole, or null if there is none. */
    internal fun bounds(hole: BooleanArray, width: Int, height: Int): IntArray? {
        var x0 = width
        var y0 = height
        var x1 = -1
        var y1 = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (hole[y * width + x]) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1 + 1, y1 + 1)
    }

    /** A picture at one scale: colour channels as floats, plus which pixels are hole. */
    private class Level(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val hole = BooleanArray(w * h)

        fun rgb(i: Int): Int {
            val rr = (r[i] + 0.5f).toInt().coerceIn(0, 255)
            val gg = (g[i] + 0.5f).toInt().coerceIn(0, 255)
            val bb = (b[i] + 0.5f).toInt().coerceIn(0, 255)
            return (rr shl 16) or (gg shl 8) or bb
        }

        /** Half size: each pixel averages four; it is hole if any of the four is. */
        fun down(): Level {
            val nw = max(1, w / 2)
            val nh = max(1, h / 2)
            val out = Level(nw, nh)
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    var sr = 0f
                    var sg = 0f
                    var sb = 0f
                    var n = 0
                    var isHole = false
                    for (dy in 0..1) {
                        for (dx in 0..1) {
                            val i = min(h - 1, 2 * y + dy) * w + min(w - 1, 2 * x + dx)
                            if (hole[i]) {
                                isHole = true
                            } else {
                                sr += r[i]
                                sg += g[i]
                                sb += b[i]
                                n++
                            }
                        }
                    }
                    val o = y * nw + x
                    out.hole[o] = isHole
                    if (n > 0) {
                        out.r[o] = sr / n
                        out.g[o] = sg / n
                        out.b[o] = sb / n
                    }
                }
            }
            return out
        }

        companion object {
            /**
             * The box [x0], [y0] to [x1], [y1] of an ARGB picture, shrunk by [scale]: each pixel
             * averages a [scale] x [scale] block and is hole if any pixel of the block is.
             */
            fun of(pixels: IntArray, width: Int, hole: BooleanArray, x0: Int, y0: Int, x1: Int, y1: Int, scale: Int): Level {
                val out = Level(max(1, (x1 - x0) / scale), max(1, (y1 - y0) / scale))
                for (y in 0 until out.h) {
                    for (x in 0 until out.w) {
                        var sr = 0
                        var sg = 0
                        var sb = 0
                        var n = 0
                        var isHole = false
                        for (dy in 0 until scale) {
                            val sy = min(y1 - 1, y0 + y * scale + dy)
                            for (dx in 0 until scale) {
                                val s = sy * width + min(x1 - 1, x0 + x * scale + dx)
                                if (hole[s]) {
                                    isHole = true
                                } else {
                                    val c = pixels[s]
                                    sr += (c shr 16) and 0xFF
                                    sg += (c shr 8) and 0xFF
                                    sb += c and 0xFF
                                    n++
                                }
                            }
                        }
                        val o = y * out.w + x
                        out.hole[o] = isHole
                        if (n > 0) {
                            out.r[o] = sr.toFloat() / n
                            out.g[o] = sg.toFloat() / n
                            out.b[o] = sb.toFloat() / n
                        }
                    }
                }
                return out
            }
        }
    }

    /** Fills [full]'s hole in place and returns its final nearest-neighbour field. */
    private fun complete(full: Level, random: Random): IntArray {
        val levels = mutableListOf(full)
        while (levels.size < MAX_LEVELS && min(levels.last().w, levels.last().h) > COARSE_SIZE) levels += levels.last().down()

        var current = levels.last()
        lines(current)
        var field: IntArray? = null
        for (index in levels.indices.reversed()) {
            val level = levels[index]
            if (level !== current) {
                upsampleInto(current, level)
                field = field?.let { upsampleField(it, current, level) }
                current = level
                // Rebuild the hole from the smaller scale's matches, which keeps their texture;
                // smoothly enlarged colours would draw the search towards smooth, dull patches.
                field?.let { reconstruct(level, it) }
            }
            val iterations = when (index) {
                levels.lastIndex -> 6
                0 -> 2
                else -> 4
            }
            field = solve(level, field, iterations, random)
        }
        return field ?: IntArray(full.w * full.h) { -1 }
    }

    /**
     * The first fill, at the smallest scale. Each hole pixel blends the known pixels at either end
     * of its row and of its column, the shorter span counting for more, so an edge that crosses
     * the hole (a horizon, a table top) carries on across it. Pixels with no known pixel in line
     * are filled by [diffuse].
     */
    private fun lines(level: Level) {
        val w = level.w
        val h = level.h
        val hole = level.hole
        val sr = FloatArray(w * h)
        val sg = FloatArray(w * h)
        val sb = FloatArray(w * h)
        val sw = FloatArray(w * h)
        fun spans(start: Int, count: Int, stride: Int) {
            var k = 0
            while (k < count) {
                if (!hole[start + k * stride]) {
                    k++
                    continue
                }
                var end = k
                while (end < count && hole[start + end * stride]) end++
                val before = if (k > 0) start + (k - 1) * stride else -1
                val after = if (end < count) start + end * stride else -1
                val length = (end - k + 1).toFloat()
                // A span closed at both ends is interpolated; one open at an end (the picture's
                // edge) repeats its known end, and counts for less.
                val a = if (before >= 0) before else after
                val b = if (after >= 0) after else before
                if (a >= 0) {
                    val weight = if (before >= 0 && after >= 0) 1f / (length * length) else 1f / (4f * length * length)
                    for (t in k until end) {
                        val i = start + t * stride
                        val f = (t - k + 1) / length
                        sr[i] += weight * (level.r[a] + (level.r[b] - level.r[a]) * f)
                        sg[i] += weight * (level.g[a] + (level.g[b] - level.g[a]) * f)
                        sb[i] += weight * (level.b[a] + (level.b[b] - level.b[a]) * f)
                        sw[i] += weight
                    }
                }
                k = end
            }
        }
        for (y in 0 until h) spans(y * w, w, 1)
        for (x in 0 until w) spans(x, h, w)
        val unknown = BooleanArray(w * h)
        for (i in 0 until w * h) {
            if (!hole[i]) continue
            if (sw[i] > 0f) {
                level.r[i] = sr[i] / sw[i]
                level.g[i] = sg[i] / sw[i]
                level.b[i] = sb[i] / sw[i]
            } else {
                unknown[i] = true
            }
        }
        diffuse(level, unknown)
    }

    /** Fills the [unknown] pixels with the average of their filled neighbours, from the edge inwards. */
    private fun diffuse(level: Level, unknown: BooleanArray) {
        val w = level.w
        val h = level.h
        val known = BooleanArray(w * h) { !unknown[it] }
        var remaining = unknown.count { it }
        while (remaining > 0) {
            val next = known.copyOf()
            var progress = false
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (known[i]) continue
                    var sr = 0f
                    var sg = 0f
                    var sb = 0f
                    var n = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                            val j = ny * w + nx
                            if (known[j]) {
                                sr += level.r[j]
                                sg += level.g[j]
                                sb += level.b[j]
                                n++
                            }
                        }
                    }
                    if (n > 0) {
                        level.r[i] = sr / n
                        level.g[i] = sg / n
                        level.b[i] = sb / n
                        next[i] = true
                        remaining--
                        progress = true
                    }
                }
            }
            System.arraycopy(next, 0, known, 0, known.size)
            if (!progress) break
        }
    }

    /** Hole pixels at the larger [to] scale take the smaller scale's colours, smoothly interpolated. */
    private fun upsampleInto(from: Level, to: Level) {
        val sx = from.w.toFloat() / to.w
        val sy = from.h.toFloat() / to.h
        for (y in 0 until to.h) {
            for (x in 0 until to.w) {
                val i = y * to.w + x
                if (!to.hole[i]) continue
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, from.w - 1f)
                val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, from.h - 1f)
                val x0 = fx.toInt()
                val y0 = fy.toInt()
                val x1 = min(x0 + 1, from.w - 1)
                val y1 = min(y0 + 1, from.h - 1)
                val ax = fx - x0
                val ay = fy - y0
                fun lerp(c: FloatArray): Float {
                    val top = c[y0 * from.w + x0] + (c[y0 * from.w + x1] - c[y0 * from.w + x0]) * ax
                    val bottom = c[y1 * from.w + x0] + (c[y1 * from.w + x1] - c[y1 * from.w + x0]) * ax
                    return top + (bottom - top) * ay
                }
                to.r[i] = lerp(from.r)
                to.g[i] = lerp(from.g)
                to.b[i] = lerp(from.b)
            }
        }
    }

    /** The nearest-neighbour field at the larger [to] scale: each match keeps its offset. */
    private fun upsampleField(field: IntArray, from: Level, to: Level): IntArray {
        val out = IntArray(to.w * to.h) { -1 }
        for (y in 0 until to.h) {
            for (x in 0 until to.w) {
                val cx = min(from.w - 1, x * from.w / to.w)
                val cy = min(from.h - 1, y * from.h / to.h)
                val m = field[cy * from.w + cx]
                if (m < 0) continue
                val mx = (m % from.w) * to.w / from.w + (x - cx * to.w / from.w)
                val my = (m / from.w) * to.h / from.h + (y - cy * to.h / from.h)
                if (mx in 0 until to.w && my in 0 until to.h) out[y * to.w + x] = my * to.w + mx
            }
        }
        return out
    }

    /** The hole rebuilt by voting with [field]'s matches as they stand, without searching. */
    private fun reconstruct(level: Level, field: IntArray) {
        val targets = IntArray(field.size)
        var count = 0
        for (i in field.indices) if (field[i] >= 0 && !level.hole[field[i]]) targets[count++] = i
        if (count > 0) vote(level, field, FloatArray(field.size), confidence(level.hole, level.w, level.h), targets, count)
    }

    /**
     * PatchMatch with expectation-maximisation at one scale. Returns the final nearest-neighbour
     * field (for each pixel, the index of its best source patch centre, or −1).
     */
    private fun solve(level: Level, initial: IntArray?, iterations: Int, random: Random): IntArray {
        val w = level.w
        val h = level.h
        val r = RADIUS

        // Pixels whose patch touches the hole are the ones to match; patches wholly outside the
        // hole (and inside the picture) are what they may copy from.
        val near = dilate(level.hole, w, h, r)
        val source = IntArray(w * h)
        var sourceCount = 0
        for (y in r until h - r) {
            for (x in r until w - r) {
                val i = y * w + x
                if (!near[i]) source[sourceCount++] = i
            }
        }
        val targets = IntArray(w * h)
        var targetCount = 0
        for (i in 0 until w * h) if (near[i]) targets[targetCount++] = i
        val field = IntArray(w * h) { -1 }
        val box = bounds(level.hole, w, h)
        if (sourceCount == 0 || targetCount == 0 || box == null) return field
        val valid = BooleanArray(w * h)
        for (k in 0 until sourceCount) valid[source[k]] = true
        val cost = FloatArray(w * h) { Float.MAX_VALUE }
        val confidence = confidence(level.hole, w, h)
        val locality = LOCALITY * PATCH_AREA / square(max(box[2] - box[0], box[3] - box[1]).toFloat())

        // Patch difference plus the preference for nearby sources, stopping early past [limit].
        fun cost(p: Int, q: Int, limit: Float): Float {
            val dx = (p % w - q % w).toFloat()
            val dy = (p / w - q / w).toFloat()
            val far = locality * (dx * dx + dy * dy)
            if (far >= limit) return Float.MAX_VALUE
            val d = distance(level, p, q, limit - far)
            return if (d == Float.MAX_VALUE) d else d + far
        }

        for (k in 0 until targetCount) {
            val p = targets[k]
            val guess = initial?.getOrNull(p) ?: -1
            field[p] = if (guess >= 0 && valid[guess]) guess else source[random.nextInt(sourceCount)]
        }

        repeat(iterations) { iteration ->
            for (k in 0 until targetCount) {
                val p = targets[k]
                cost[p] = cost(p, field[p], Float.MAX_VALUE)
            }
            // Two sweeps per iteration, alternating direction, as in PatchMatch.
            for (pass in 0..1) {
                val forward = (iteration + pass) % 2 == 0
                val step = if (forward) 1 else -1
                var k = if (forward) 0 else targetCount - 1
                while (k in 0 until targetCount) {
                    val p = targets[k]
                    val px = p % w
                    val py = p / w
                    // Propagation: try the neighbours' matches, shifted by one.
                    for ((dx, dy) in if (forward) BACK else AHEAD) {
                        val nx = px + dx
                        val ny = py + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val m = field[ny * w + nx]
                        if (m < 0) continue
                        val cx = m % w - dx
                        val cy = m / w - dy
                        if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue
                        val c = cy * w + cx
                        if (!valid[c]) continue
                        val d = cost(p, c, cost[p])
                        if (d < cost[p]) {
                            cost[p] = d
                            field[p] = c
                        }
                    }
                    // Random search in shrinking windows around the current match.
                    var radius = max(w, h)
                    val bx = field[p] % w
                    val by = field[p] / w
                    while (radius >= 1) {
                        val cx = (bx + random.nextInt(-radius, radius + 1)).coerceIn(0, w - 1)
                        val cy = (by + random.nextInt(-radius, radius + 1)).coerceIn(0, h - 1)
                        val c = cy * w + cx
                        if (valid[c]) {
                            val d = cost(p, c, cost[p])
                            if (d < cost[p]) {
                                cost[p] = d
                                field[p] = c
                            }
                        }
                        radius /= 2
                    }
                    k += step
                }
            }
            vote(level, field, cost, confidence, targets, targetCount)
        }
        return field
    }

    /**
     * Sum of squared colour differences between the patches centred at [p] and [q], stopping
     * early once it passes [limit]. Parts of the patch off the picture are skipped.
     */
    private fun distance(level: Level, p: Int, q: Int, limit: Float): Float {
        val w = level.w
        val h = level.h
        val px = p % w
        val py = p / w
        val qx = q % w
        val qy = q / w
        var sum = 0f
        var n = 0
        for (dy in -RADIUS..RADIUS) {
            val ay = py + dy
            val by = qy + dy
            if (ay < 0 || ay >= h || by < 0 || by >= h) continue
            for (dx in -RADIUS..RADIUS) {
                val ax = px + dx
                val bx = qx + dx
                if (ax < 0 || ax >= w || bx < 0 || bx >= w) continue
                val a = ay * w + ax
                val b = by * w + bx
                val dr = level.r[a] - level.r[b]
                val dg = level.g[a] - level.g[b]
                val db = level.b[a] - level.b[b]
                sum += dr * dr + dg * dg + db * db
                n++
            }
            if (sum > limit) return Float.MAX_VALUE
        }
        // Patches cut off by the edge are compared on what they share, scaled to a full patch.
        return if (n == 0) Float.MAX_VALUE else sum * PATCH_AREA / n
    }

    /**
     * Each hole pixel becomes the weighted average of what every overlapping patch's match says it
     * should be. Good matches count for more than poor ones, and patches near the hole's edge, which
     * overlap real picture, count for more than those deep inside (Wexler et al.).
     */
    private fun vote(level: Level, field: IntArray, cost: FloatArray, confidence: FloatArray, targets: IntArray, targetCount: Int) {
        val w = level.w
        val h = level.h
        // Match quality is judged against a typical match: the 75th percentile of the costs.
        val costs = FloatArray(targetCount) { cost[targets[it]] }.also { it.sort() }
        val typical = max(costs[(targetCount - 1) * 3 / 4], 1f)
        val sr = FloatArray(w * h)
        val sg = FloatArray(w * h)
        val sb = FloatArray(w * h)
        val n = FloatArray(w * h)
        for (k in 0 until targetCount) {
            val p = targets[k]
            val q = field[p]
            if (q < 0) continue
            val px = p % w
            val py = p / w
            val qx = q % w
            val qy = q / w
            val weight = confidence[p] * exp(-cost[p] / (2f * typical)) + 1e-6f
            for (dy in -RADIUS..RADIUS) {
                val ay = py + dy
                val by = qy + dy
                if (ay < 0 || ay >= h || by < 0 || by >= h) continue
                for (dx in -RADIUS..RADIUS) {
                    val ax = px + dx
                    val bx = qx + dx
                    if (ax < 0 || ax >= w || bx < 0 || bx >= w) continue
                    val a = ay * w + ax
                    if (!level.hole[a]) continue
                    val b = by * w + bx
                    sr[a] += weight * level.r[b]
                    sg[a] += weight * level.g[b]
                    sb[a] += weight * level.b[b]
                    n[a] += weight
                }
            }
        }
        for (i in 0 until w * h) {
            if (level.hole[i] && n[i] > 0f) {
                level.r[i] = sr[i] / n[i]
                level.g[i] = sg[i] / n[i]
                level.b[i] = sb[i] / n[i]
            }
        }
    }

    /**
     * Fills the full-size hole when the region [box] was solved [scale] times smaller as [work].
     * Each full-size pixel blends the four nearest small-scale matches: it copies the full-size
     * pixel at each match's offset, for full-size detail, shifted by how the solved colour there
     * differs from the source's, so the fill keeps the solved picture's smooth shading and meets
     * its surroundings without a seam.
     */
    private fun rebuild(pixels: IntArray, out: IntArray, width: Int, hole: BooleanArray, box: IntArray, scale: Int, work: Level, field: IntArray) {
        val (x0, y0, x1, y1) = box
        val rw = x1 - x0
        val rh = y1 - y0
        for (y in 0 until rh) {
            // Position among the small-scale pixel centres.
            val fy = ((y + 0.5f) / scale - 0.5f).coerceIn(0f, work.h - 1f)
            val cy0 = fy.toInt()
            val cy1 = min(cy0 + 1, work.h - 1)
            val ay = fy - cy0
            for (x in 0 until rw) {
                val o = (y0 + y) * width + x0 + x
                if (!hole[o]) continue
                val fx = ((x + 0.5f) / scale - 0.5f).coerceIn(0f, work.w - 1f)
                val cx0 = fx.toInt()
                val cx1 = min(cx0 + 1, work.w - 1)
                val ax = fx - cx0
                var sr = 0f
                var sg = 0f
                var sb = 0f
                var n = 0f
                for (corner in 0 until 4) {
                    val cx = if (corner and 1 == 0) cx0 else cx1
                    val cy = if (corner and 2 == 0) cy0 else cy1
                    val weight = (if (corner and 1 == 0) 1f - ax else ax) * (if (corner and 2 == 0) 1f - ay else ay)
                    if (weight <= 0f) continue
                    val cell = cy * work.w + cx
                    val m = field[cell]
                    if (m < 0) continue
                    val sx = x + (m % work.w - cx) * scale
                    val sy = y + (m / work.w - cy) * scale
                    if (sx < 0 || sy < 0 || sx >= rw || sy >= rh) continue
                    val s = (y0 + sy) * width + x0 + sx
                    if (hole[s]) continue
                    val c = pixels[s]
                    sr += weight * (((c shr 16) and 0xFF) + work.r[cell] - work.r[m])
                    sg += weight * (((c shr 8) and 0xFF) + work.g[cell] - work.g[m])
                    sb += weight * ((c and 0xFF) + work.b[cell] - work.b[m])
                    n += weight
                }
                val rgb = if (n > 0f) {
                    val rr = (sr / n + 0.5f).toInt().coerceIn(0, 255)
                    val gg = (sg / n + 0.5f).toInt().coerceIn(0, 255)
                    val bb = (sb / n + 0.5f).toInt().coerceIn(0, 255)
                    (rr shl 16) or (gg shl 8) or bb
                } else {
                    work.rgb(min(work.h - 1, y / scale) * work.w + min(work.w - 1, x / scale))
                }
                out[o] = (pixels[o] and ALPHA) or rgb
            }
        }
    }

    /** How much each pixel can be trusted: 1 outside the hole, falling by [DECAY] per pixel inwards. */
    private fun confidence(hole: BooleanArray, w: Int, h: Int): FloatArray {
        // Two-pass chamfer distance to the nearest known pixel.
        val far = (w + h).toFloat()
        val d = FloatArray(w * h) { if (hole[it]) far else 0f }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (x > 0) d[i] = min(d[i], d[i - 1] + 1f)
                if (y > 0) d[i] = min(d[i], d[i - w] + 1f)
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (x < w - 1) d[i] = min(d[i], d[i + 1] + 1f)
                if (y < h - 1) d[i] = min(d[i], d[i + w] + 1f)
            }
        }
        return FloatArray(w * h) { DECAY.pow(-d[it]) }
    }

    /** [mask] grown by [radius] pixels in every direction (a square). */
    internal fun dilate(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        // Separable: grow along rows, then along columns, each in two linear passes.
        val rows = BooleanArray(w * h)
        for (y in 0 until h) {
            var lastSeen = Int.MIN_VALUE / 2
            for (x in 0 until w) {
                if (mask[y * w + x]) lastSeen = x
                if (x - lastSeen <= radius) rows[y * w + x] = true
            }
            lastSeen = Int.MAX_VALUE / 2
            for (x in w - 1 downTo 0) {
                if (mask[y * w + x]) lastSeen = x
                if (lastSeen - x <= radius) rows[y * w + x] = true
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var lastSeen = Int.MIN_VALUE / 2
            for (y in 0 until h) {
                if (rows[y * w + x]) lastSeen = y
                if (y - lastSeen <= radius) out[y * w + x] = true
            }
            lastSeen = Int.MAX_VALUE / 2
            for (y in h - 1 downTo 0) {
                if (rows[y * w + x]) lastSeen = y
                if (lastSeen - y <= radius) out[y * w + x] = true
            }
        }
        return out
    }

    private fun square(v: Float) = v * v
}
