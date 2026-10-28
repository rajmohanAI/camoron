package com.camoron.data.processing

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Panorama stitching on plain pixel arrays, so it can be tested off the phone. Each upright frame
 * is projected onto a cylinder around the camera, placed where the phone's turn says it belongs,
 * nudged until it lines up with the frame before, matched in brightness, and cross-faded in over
 * a narrow band in the middle of the overlap.
 */
internal object PanoramaMath {

    /** An upright ARGB image; alpha 0 marks pixels with nothing in them. */
    class Frame(val pixels: IntArray, val width: Int, val height: Int)

    /** Focal length in pixels for a [width]×[height] frame from its 35 mm-equivalent focal length. */
    fun focalPixels(width: Int, height: Int, focalLength35mm: Float?): Float =
        (focalLength35mm ?: DEFAULT_FOCAL_35MM) / FULL_FRAME_DIAGONAL_MM * hypot(width.toFloat(), height.toFloat())

    /** [frame] projected onto a cylinder of radius [f] pixels; as tall, and narrower by the curve. */
    fun warp(frame: Frame, f: Float): Frame {
        val w = frame.width
        val h = frame.height
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val edge = atan((w / 2f) / f)
        val outWidth = max(1, (2 * f * edge).toInt())
        val ocx = (outWidth - 1) / 2f
        val out = IntArray(outWidth * h)
        for (u in 0 until outWidth) {
            val theta = (u - ocx) / f
            val sx = f * tan(theta) + cx
            val scale = 1f / cos(theta)
            if (sx < 0f || sx > w - 1) continue
            for (v in 0 until h) {
                val sy = (v - cy) * scale + cy
                if (sy < 0f || sy > h - 1) continue
                out[v * outWidth + u] = sampleArgb(frame, sx, sy)
            }
        }
        return Frame(out, outWidth, h)
    }

    /**
     * Stitches [frames] (upright, all the same size) taken at [yaws] (radians, increasing as the
     * phone turns right) into one panorama, or null when there are fewer than two.
     */
    fun stitch(frames: List<Frame>, yaws: List<Float>, f: Float): Frame? = stitchWarped(frames.map { warp(it, f) }, yaws, f)

    /** [stitch] for frames already [warp]ed, so the originals needn't all be held at once. */
    fun stitchWarped(cylinders: List<Frame>, yaws: List<Float>, f: Float): Frame? {
        if (cylinders.size < 2 || cylinders.size != yaws.size) return null
        val order = yaws.indices.sortedBy { yaws[it] }
        val warped = order.map { cylinders[it] }
        val sortedYaws = order.map { yaws[it] }
        val ww = warped[0].width
        val h = warped[0].height

        // Where each frame goes, from the turn between frames, refined by matching their overlap.
        val small = warped.map { downscaledLuma(it, ALIGN_SCALE) }
        val xs = IntArray(warped.size)
        val ys = IntArray(warped.size)
        for (i in 1 until warped.size) {
            val predicted = (f * (sortedYaws[i] - sortedYaws[i - 1])).roundToInt()
            val (dx, dy) = align(small[i - 1], small[i], predicted / ALIGN_SCALE, ww / ALIGN_SCALE)
                ?.let { (sx, sy) -> sx * ALIGN_SCALE to sy * ALIGN_SCALE }
                ?: (predicted to 0)
            xs[i] = xs[i - 1] + dx.coerceIn((ww * MIN_STEP).toInt(), (ww * MAX_STEP).toInt())
            ys[i] = ys[i - 1] + dy
        }

        // Exposure can drift as the phone turns: match each frame's brightness to its neighbour.
        val gains = FloatArray(warped.size) { 1f }
        for (i in 1 until warped.size) {
            gains[i] = gains[i - 1] * overlapRatio(small[i - 1], small[i], (xs[i] - xs[i - 1]) / ALIGN_SCALE, (ys[i] - ys[i - 1]) / ALIGN_SCALE)
        }
        val mean = gains.average().toFloat()
        for (i in gains.indices) gains[i] /= mean

        val minY = ys.min()
        val maxY = ys.max()
        val width = xs.last() + ww
        val height = h + maxY - minY
        val canvas = IntArray(width * height)
        for (i in warped.indices) {
            val left = xs[i]
            val top = ys[i] - minY
            // Cross-fade over a band in the middle of the overlap with the frame before.
            val overlapStart = left
            val overlapEnd = if (i == 0) left else xs[i - 1] + ww
            val band = min((ww * BLEND_BAND).toInt(), overlapEnd - overlapStart).coerceAtLeast(1)
            val bandStart = (overlapStart + overlapEnd - band) / 2
            paste(canvas, width, warped[i], left, top, gains[i], bandStart, band, first = i == 0)
        }

        // Keep only the rows every frame fills: the cylinder curves the top and bottom edges.
        val curve = ((h / 2f) * (1f - cos(atan((ww / 2f) / f)))).toInt() + 1
        val cropTop = (maxY - minY) + curve
        val cropBottom = h - curve
        if (cropBottom - cropTop < h / 4) return Frame(canvas, width, height)
        val out = canvas.copyOfRange(cropTop * width, cropBottom * width)
        return Frame(out, width, cropBottom - cropTop)
    }

    /**
     * Shift (dx, dy) at which [cur] sits relative to [prev] (both [Luma] at the same scale), searched
     * around [predicted]; null when nothing matches well.
     */
    fun align(prev: Luma, cur: Luma, predicted: Int, width: Int): Pair<Int, Int>? {
        // Compare the part of the previous frame the new one should overlap, away from the curved corners.
        val x0 = predicted.coerceIn(0, prev.width - 8)
        val y0 = (prev.height * ALIGN_MARGIN).toInt()
        val y1 = prev.height - y0
        val refWidth = prev.width - x0
        val refHeight = y1 - y0
        if (refWidth < 8 || refHeight < 8) return null
        val ref = IntArray(refWidth * refHeight) { prev.values[(y0 + it / refWidth) * prev.width + x0 + it % refWidth] }
        // ref(x, y) = prev(x0 + x, y0 + y) ≈ cur(x0 + x − D, y0 + y − Dy), so the search's shift is (x0 − D, y0 − Dy).
        val found = MergeMath.bestOffset(
            ref, refWidth, refHeight,
            cur.values, cur.width, cur.height,
            baseX = x0 - predicted, baseY = y0,
            radius = max(2, (width * SEARCH_X).toInt()),
        )
        if (found.meanError > MAX_ALIGN_ERROR) return null
        return (x0 - found.dx) to (y0 - found.dy)
    }

    /** Luma (0–255) averaged over [scale]×[scale] blocks; empty pixels count as 0. */
    class Luma(val values: IntArray, val width: Int, val height: Int)

    fun downscaledLuma(frame: Frame, scale: Int): Luma {
        val w = frame.width / scale
        val h = frame.height / scale
        val values = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in 0 until scale) for (dx in 0 until scale) {
                sum += MergeMath.luma(frame.pixels[(y * scale + dy) * frame.width + x * scale + dx])
            }
            values[y * w + x] = sum / (scale * scale)
        }
        return Luma(values, w, h)
    }

    /** Brightness of [prev] over [cur] where they overlap at shift ([dx], [dy]), within sensible limits. */
    private fun overlapRatio(prev: Luma, cur: Luma, dx: Int, dy: Int): Float {
        var a = 0L
        var b = 0L
        val y0 = (prev.height * ALIGN_MARGIN).toInt()
        for (y in y0 until prev.height - y0) {
            val cy = y - dy
            if (cy !in 0 until cur.height) continue
            for (x in max(0, dx) until prev.width) {
                val cx = x - dx
                if (cx !in 0 until cur.width) continue
                a += prev.values[y * prev.width + x]
                b += cur.values[cy * cur.width + cx]
            }
        }
        if (a == 0L || b == 0L) return 1f
        return (a.toFloat() / b).coerceIn(1f / MAX_GAIN, MAX_GAIN)
    }

    /**
     * Lays [frame] onto [canvas] at ([left], [top]) with its brightness scaled by [gain], fading in
     * from fully under to fully over the canvas across [band] columns from [bandStart].
     */
    private fun paste(canvas: IntArray, width: Int, frame: Frame, left: Int, top: Int, gain: Float, bandStart: Int, band: Int, first: Boolean) {
        for (y in 0 until frame.height) {
            val cy = top + y
            for (x in 0 until frame.width) {
                val c = frame.pixels[y * frame.width + x]
                if (c ushr 24 == 0) continue
                val cx = left + x
                val i = cy * width + cx
                val under = canvas[i]
                val t = if (first || under ushr 24 == 0) 1f else ((cx - bandStart + 0.5f) / band).coerceIn(0f, 1f)
                if (t <= 0f) continue
                val r = ((c shr 16) and 0xFF) * gain
                val g = ((c shr 8) and 0xFF) * gain
                val b = (c and 0xFF) * gain
                canvas[i] = if (t >= 1f) {
                    argb(r, g, b)
                } else {
                    argb(
                        ((under shr 16) and 0xFF) * (1 - t) + r * t,
                        ((under shr 8) and 0xFF) * (1 - t) + g * t,
                        (under and 0xFF) * (1 - t) + b * t,
                    )
                }
            }
        }
    }

    private fun argb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)

    private fun sampleArgb(frame: Frame, x: Float, y: Float): Int {
        val w = frame.width
        val x0 = x.toInt().coerceIn(0, w - 1)
        val y0 = y.toInt().coerceIn(0, frame.height - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, frame.height - 1)
        val fx = x - x0
        val fy = y - y0
        val p = frame.pixels
        fun channel(shift: Int): Float {
            val a = (p[y0 * w + x0] shr shift) and 0xFF
            val b = (p[y0 * w + x1] shr shift) and 0xFF
            val c = (p[y1 * w + x0] shr shift) and 0xFF
            val d = (p[y1 * w + x1] shr shift) and 0xFF
            return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy
        }
        return argb(channel(16), channel(8), channel(0))
    }

    private const val FULL_FRAME_DIAGONAL_MM = 43.27f
    private const val DEFAULT_FOCAL_35MM = 26f
    /** Alignment runs on frames shrunk this many times, which is plenty to place them. */
    const val ALIGN_SCALE = 4
    /** How far, as a share of the frame's width, alignment may move a frame from where the turn put it. */
    private const val SEARCH_X = 0.08f
    /** Rows near the top and bottom (curved by the cylinder) left out of matching. */
    private const val ALIGN_MARGIN = 0.15f
    private const val MAX_ALIGN_ERROR = 28f
    /** Each frame moves on by at least this, and at most this, share of a frame's width. */
    private const val MIN_STEP = 0.1f
    private const val MAX_STEP = 0.95f
    private const val BLEND_BAND = 0.2f
    private const val MAX_GAIN = 1.5f
}
