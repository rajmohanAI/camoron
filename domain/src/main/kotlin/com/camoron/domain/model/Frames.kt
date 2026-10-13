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
