package com.camoron.data.processing

import com.camoron.domain.model.YuvFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

/**
 * Merges a handheld burst of uncompressed frames into one frame with less noise and more real
 * detail than any single frame, the way the best phone cameras do:
 *
 * 1. The sharpest of the first few frames becomes the reference (the one least blurred by shake).
 * 2. Every other frame is aligned to it tile by tile, coarse to fine (1/16, 1/4 and full scale), so
 *    a slight rotation or wobble of the hand is followed rather than averaged into blur.
 * 3. Pixels are averaged with a weight that falls to zero where a frame differs from the reference
 *    by more than noise can explain. Something that moved is taken from the reference only, so it
 *    stays sharp instead of ghosting, while still areas get the full noise reduction.
 *
 * Pure Kotlin, so it is unit-tested on the JVM.
 */
internal object BurstMerger {

    /** Side of the alignment tiles at full resolution. */
    const val TILE = 128

    private const val FINE_STEP = 4
    private const val GLOBAL_RADIUS = 6
    private const val TILE_RADIUS = 3
    private const val FINE_RADIUS = 2
    private const val BAND_ROWS = 64

    /** A frame's offset per tile, or [REJECTED] where it doesn't line up with the reference at all. */
    class Alignment(val frame: YuvFrame, val dx: IntArray, val dy: IntArray)

    /** Result of a merge: the merged frame, the noise measured before merging (0..255 deviation), frames used. */
    class Result(val frame: YuvFrame, val noise: Float, val framesUsed: Int, val reference: Int)

    suspend fun merge(frames: List<YuvFrame>, dispatcher: CoroutineDispatcher, workers: Int = 4): Result = coroutineScope {
        val first = frames.first()
        val usable = frames.filter { it.width == first.width && it.height == first.height }
        if (usable.size < 2) return@coroutineScope Result(first, noiseOf(first), 1, 0)

        val quarter = usable.map { async(dispatcher) { downsample(Plane(it.y, it.width, it.height), 4) } }.awaitAll()
        val referenceIndex = (0 until minOf(3, usable.size)).maxBy { sharpness(quarter[it]) }
        val reference = usable[referenceIndex]
        val noise = noiseOf(reference)
        val refFull = Plane(reference.y, reference.width, reference.height)
        val refQuarter = quarter[referenceIndex]
        val refSixteenth = downsample(refQuarter, 4)
        val reject = REJECT_BASE + REJECT_PER_NOISE * noise

        val alignments = usable.indices.filter { it != referenceIndex }.map { index ->
            async(dispatcher) { align(refFull, refQuarter, refSixteenth, usable[index], quarter[index], reject) }
        }.awaitAll()

        val outY = ByteArray(reference.y.size)
        val outU = ByteArray(reference.u.size)
        val outV = ByteArray(reference.v.size)
        val bands = (reference.height + BAND_ROWS - 1) / BAND_ROWS
        val threshold = weightThreshold(noise)
        (0 until workers).map { worker ->
            async(dispatcher) {
                var band = worker
                while (band < bands) {
                    val top = band * BAND_ROWS
                    mergeRows(reference, alignments, top, minOf(reference.height, top + BAND_ROWS), threshold, outY, outU, outV)
                    band += workers
                }
            }
        }.awaitAll()
        Result(YuvFrame(reference.width, reference.height, outY, outU, outV), noise, usable.size, referenceIndex)
    }

    /** Noise of a frame's brightness as a 0..255 deviation. */
    fun noiseOf(frame: YuvFrame): Float = DetailMath.estimateNoise(frame.y, frame.width, frame.height, step = 4) * 255f

    /** Differences up to this much count fully as noise; twice it and more, as a different picture. */
    fun weightThreshold(noise: Float): Float = WEIGHT_BASE + WEIGHT_PER_NOISE * noise

    class Plane(val data: ByteArray, val width: Int, val height: Int) {
        operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
    }

    /** Box-averaged [factor]x smaller copy. */
    fun downsample(plane: Plane, factor: Int): Plane {
        val w = maxOf(1, plane.width / factor)
        val h = maxOf(1, plane.height / factor)
        val out = ByteArray(w * h)
        val area = factor * factor
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until factor) {
                    val row = (y * factor + dy).coerceAtMost(plane.height - 1) * plane.width
                    for (dx in 0 until factor) sum += plane.data[row + (x * factor + dx).coerceAtMost(plane.width - 1)].toInt() and 0xFF
                }
                out[y * w + x] = (sum / area).toByte()
            }
        }
        return Plane(out, w, h)
    }

    /** How much fine detail a plane holds: sum of absolute Laplacian responses. */
    fun sharpness(plane: Plane): Long {
        var sum = 0L
        for (y in 1 until plane.height - 1) {
            for (x in 1 until plane.width - 1) {
                sum += abs(4 * plane[x, y] - plane[x - 1, y] - plane[x + 1, y] - plane[x, y - 1] - plane[x, y + 1])
            }
        }
        return sum
    }

    /**
     * Mean absolute difference between [ref] over the rectangle ([x0], [y0], [w], [h]) and [image]
     * shifted by ([dx], [dy]), sampled every [step] pixels; [Float.MAX_VALUE] when less than half of
     * the samples overlap.
     */
    fun meanDifference(ref: Plane, x0: Int, y0: Int, w: Int, h: Int, image: Plane, dx: Int, dy: Int, step: Int): Float {
        var sum = 0L
        var count = 0
        var total = 0
        var y = y0
        while (y < y0 + h) {
            val iy = y + dy
            var x = x0
            if (iy !in 0 until image.height) {
                total += (w + step - 1) / step
                y += step
                continue
            }
            val refRow = y * ref.width
            val imageRow = iy * image.width
            while (x < x0 + w) {
                total++
                val ix = x + dx
                if (ix in 0 until image.width) {
                    sum += abs((ref.data[refRow + x].toInt() and 0xFF) - (image.data[imageRow + ix].toInt() and 0xFF))
                    count++
                }
                x += step
            }
            y += step
        }
        return if (count * 2 < total || count == 0) Float.MAX_VALUE else sum.toFloat() / count
    }

    /** Best shift within ±[radius] of ([baseDx], [baseDy]), with its mean difference. */
    fun bestShift(
        ref: Plane, x0: Int, y0: Int, w: Int, h: Int,
        image: Plane, baseDx: Int, baseDy: Int, radius: Int, step: Int,
    ): Triple<Int, Int, Float> {
        var best = Triple(baseDx, baseDy, Float.MAX_VALUE)
        for (oy in -radius..radius) {
            for (ox in -radius..radius) {
                val error = meanDifference(ref, x0, y0, w, h, image, baseDx + ox, baseDy + oy, step)
                // On a tie prefer the smaller shift, so flat areas don't wander.
                if (error < best.third || (error == best.third && abs(ox) + abs(oy) < abs(best.first - baseDx) + abs(best.second - baseDy))) {
                    best = Triple(baseDx + ox, baseDy + oy, error)
                }
            }
        }
        return best
    }

    fun align(
        refFull: Plane,
        refQuarter: Plane,
        refSixteenth: Plane,
        frame: YuvFrame,
        frameQuarter: Plane,
        reject: Float,
    ): Alignment {
        val frameFull = Plane(frame.y, frame.width, frame.height)
        val frameSixteenth = downsample(frameQuarter, 4)
        val global = bestShift(
            refSixteenth, 0, 0, refSixteenth.width, refSixteenth.height,
            frameSixteenth, 0, 0, GLOBAL_RADIUS, 1,
        )
        val tilesX = (frame.width + TILE - 1) / TILE
        val tilesY = (frame.height + TILE - 1) / TILE
        val dx = IntArray(tilesX * tilesY)
        val dy = IntArray(tilesX * tilesY)
        val q = TILE / 4
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val i = ty * tilesX + tx
                val qx = tx * q
                val qy = ty * q
                val qw = minOf(q, refQuarter.width - qx)
                val qh = minOf(q, refQuarter.height - qy)
                val coarse = if (qw > 0 && qh > 0) {
                    bestShift(refQuarter, qx, qy, qw, qh, frameQuarter, global.first * 4, global.second * 4, TILE_RADIUS, 1)
                } else {
                    Triple(global.first * 4, global.second * 4, 0f)
                }
                val x0 = tx * TILE
                val y0 = ty * TILE
                val fine = bestShift(
                    refFull, x0, y0, minOf(TILE, frame.width - x0), minOf(TILE, frame.height - y0),
                    frameFull, coarse.first * 4, coarse.second * 4, FINE_RADIUS, FINE_STEP,
                )
                if (fine.third > reject) {
                    dx[i] = REJECTED
                    dy[i] = REJECTED
                } else {
                    dx[i] = fine.first
                    dy[i] = fine.second
                }
            }
        }
        return Alignment(frame, dx, dy)
    }

    /** Merges rows [top] until [bottom] of every plane into the output planes. */
    fun mergeRows(
        reference: YuvFrame,
        alignments: List<Alignment>,
        top: Int,
        bottom: Int,
        threshold: Float,
        outY: ByteArray,
        outU: ByteArray,
        outV: ByteArray,
    ) {
        val width = reference.width
        val height = reference.height
        val tilesX = (width + TILE - 1) / TILE
        val inverse = 1f / threshold
        val cw = reference.chromaWidth
        val ch = reference.chromaHeight
        for (y in top until bottom) {
            val ty = y / TILE
            val row = y * width
            // Colour has half the rows; the even brightness rows carry it.
            val chromaRow = if (y % 2 == 0 && y / 2 < ch) y / 2 else -1
            for (tx in 0 until tilesX) {
                val xStart = tx * TILE
                val xEnd = minOf(width, xStart + TILE)
                val tileIndex = ty * tilesX + tx
                for (x in xStart until xEnd) {
                    val r = reference.y[row + x].toInt() and 0xFF
                    var sumY = r.toFloat()
                    var weights = 1f
                    val doChroma = chromaRow >= 0 && x % 2 == 0 && x / 2 < cw
                    var sumU = 0f
                    var sumV = 0f
                    if (doChroma) {
                        val ci = chromaRow * cw + x / 2
                        sumU = (reference.u[ci].toInt() and 0xFF).toFloat()
                        sumV = (reference.v[ci].toInt() and 0xFF).toFloat()
                    }
                    for (a in alignments) {
                        val dx = a.dx[tileIndex]
                        if (dx == REJECTED) continue
                        val fx = x + dx
                        val fy = y + a.dy[tileIndex]
                        if (fx !in 0 until width || fy !in 0 until height) continue
                        val f = a.frame.y[fy * width + fx].toInt() and 0xFF
                        // Full weight within the threshold, fading to none at twice it.
                        val w = (2f - abs(f - r) * inverse).coerceIn(0f, 1f)
                        if (w == 0f) continue
                        sumY += w * f
                        weights += w
                        if (doChroma) {
                            val cx = (fx shr 1).coerceAtMost(cw - 1)
                            val cy = (fy shr 1).coerceAtMost(ch - 1)
                            val ci = cy * cw + cx
                            sumU += w * (a.frame.u[ci].toInt() and 0xFF)
                            sumV += w * (a.frame.v[ci].toInt() and 0xFF)
                        }
                    }
                    val inv = 1f / weights
                    outY[row + x] = (sumY * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
                    if (doChroma) {
                        val ci = chromaRow * cw + x / 2
                        outU[ci] = (sumU * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
                        outV[ci] = (sumV * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }

    const val REJECTED = Int.MIN_VALUE

    private const val WEIGHT_BASE = 3f
    private const val WEIGHT_PER_NOISE = 3.5f
    private const val REJECT_BASE = 8f
    private const val REJECT_PER_NOISE = 3f
}
