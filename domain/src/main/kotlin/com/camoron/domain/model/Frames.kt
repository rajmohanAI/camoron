package com.camoron.domain.model

/**
 * One uncompressed camera frame in the sensor's orientation: full-resolution brightness ([y], one
 * byte per pixel, [width] x [height]) and colour at half resolution in each direction ([u], [v]).
 * Values are full range (0..255), as cameras deliver them.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
) {
    val chromaWidth: Int get() = (width + 1) / 2
    val chromaHeight: Int get() = (height + 1) / 2

    init {
        require(y.size == width * height) { "Brightness plane doesn't match ${width}x$height" }
        require(u.size == chromaWidth * chromaHeight && v.size == u.size) { "Colour planes don't match ${width}x$height" }
    }

    /** Bytes held, for deciding how many frames fit in memory. */
    val byteCount: Long get() = y.size.toLong() + u.size + v.size

    /**
     * A small opaque ARGB copy, no more than [maxSide] pixels on its long side, for showing the shot
     * straight away while the full photo is still being processed. Sensor orientation.
     */
    fun thumbnail(maxSide: Int): Thumbnail {
        val step = maxOf(1, (maxOf(width, height) + maxSide - 1) / maxSide)
        val w = maxOf(1, width / step)
        val h = maxOf(1, height / step)
        val pixels = IntArray(w * h)
        for (ty in 0 until h) {
            val sy = ty * step
            for (tx in 0 until w) {
                val sx = tx * step
                val l = y[sy * width + sx].toInt() and 0xFF
                val ci = (sy / 2) * chromaWidth + sx / 2
                val cu = (u[ci].toInt() and 0xFF) - 128
                val cv = (v[ci].toInt() and 0xFF) - 128
                val r = (l + ((1436 * cv + 512) shr 10)).coerceIn(0, 255)
                val g = (l - ((352 * cu + 731 * cv + 512) shr 10)).coerceIn(0, 255)
                val b = (l + ((1815 * cu + 512) shr 10)).coerceIn(0, 255)
                pixels[ty * w + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Thumbnail(w, h, pixels)
    }

    /**
     * The part of the frame from ([left], [top]), [cropWidth] x [cropHeight]. Edges are rounded down to
     * even pixels so the half-resolution colour stays lined up with the brightness.
     */
    fun crop(left: Int, top: Int, cropWidth: Int, cropHeight: Int): YuvFrame {
        val x0 = (left and 1.inv()).coerceIn(0, width)
        val y0 = (top and 1.inv()).coerceIn(0, height)
        val w = (minOf(cropWidth, width - x0) and 1.inv()).coerceAtLeast(2)
        val h = (minOf(cropHeight, height - y0) and 1.inv()).coerceAtLeast(2)
        if (x0 == 0 && y0 == 0 && w == width && h == height) return this
        val outY = ByteArray(w * h)
        for (row in 0 until h) System.arraycopy(y, (y0 + row) * width + x0, outY, row * w, w)
        val cw = w / 2
        val ch = h / 2
        val outU = ByteArray(cw * ch)
        val outV = ByteArray(cw * ch)
        for (row in 0 until ch) {
            val from = (y0 / 2 + row) * chromaWidth + x0 / 2
            System.arraycopy(u, from, outU, row * cw, cw)
            System.arraycopy(v, from, outV, row * cw, cw)
        }
        return YuvFrame(w, h, outY, outU, outV)
    }

    /**
     * The frame [factor] times smaller in each direction, each pixel the average of the block it
     * covers. For a frame the camera enlarged with digital zoom this keeps all the real detail while
     * turning the enlarged, blotchy noise back into fine noise that merging and cleaning can remove.
     */
    fun shrink(factor: Int): YuvFrame {
        if (factor <= 1) return this
        val w = (width / factor) and 1.inv()
        val h = (height / factor) and 1.inv()
        require(w >= 2 && h >= 2) { "Frame too small to shrink ${factor}x" }
        val outY = ByteArray(w * h)
        val area = factor * factor
        for (oy in 0 until h) {
            for (ox in 0 until w) {
                var sum = 0
                for (dy in 0 until factor) {
                    val row = (oy * factor + dy) * width + ox * factor
                    for (dx in 0 until factor) sum += y[row + dx].toInt() and 0xFF
                }
                outY[oy * w + ox] = ((sum + area / 2) / area).toByte()
            }
        }
        val cw = w / 2
        val ch = h / 2
        val outU = ByteArray(cw * ch)
        val outV = ByteArray(cw * ch)
        for (oy in 0 until ch) {
            for (ox in 0 until cw) {
                var su = 0
                var sv = 0
                for (dy in 0 until factor) {
                    val row = (oy * factor + dy).coerceAtMost(chromaHeight - 1) * chromaWidth
                    for (dx in 0 until factor) {
                        val i = row + (ox * factor + dx).coerceAtMost(chromaWidth - 1)
                        su += u[i].toInt() and 0xFF
                        sv += v[i].toInt() and 0xFF
                    }
                }
                outU[oy * cw + ox] = ((su + area / 2) / area).toByte()
                outV[oy * cw + ox] = ((sv + area / 2) / area).toByte()
            }
        }
        return YuvFrame(w, h, outY, outU, outV)
    }
}

/** Small ARGB image, row by row. */
class Thumbnail(val width: Int, val height: Int, val pixels: IntArray)

/** Camera details of a shot, written into the saved photo's EXIF. */
data class ShotInfo(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val aperture: Float? = null,
    val focalLengthMm: Float? = null,
    val focalLength35mm: Int? = null,
    val flashFired: Boolean = false,
    /**
     * How much of the zoom the camera made up by enlarging the picture rather than with a longer
     * lens: 1 when the lens covers it optically, 3 for 3x on a phone without a telephoto lens.
     */
    val digitalZoom: Float = 1f,
)

/**
 * A burst of same-exposure frames for merging into one photo (a single frame when flash fired).
 * [orientationDegrees] is the clockwise rotation that makes the frames upright, as stored in EXIF.
 */
class FrameBurst(
    val frames: List<YuvFrame>,
    val orientationDegrees: Int,
    val timestampMillis: Long,
    val info: ShotInfo = ShotInfo(),
) {
    init {
        require(frames.isNotEmpty()) { "A burst needs at least one frame" }
    }
}
