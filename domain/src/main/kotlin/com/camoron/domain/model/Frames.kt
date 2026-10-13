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
}

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
