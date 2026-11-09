package com.camoron.domain.model

/**
 * A burst of RAW frames for Detail mode (always the whole sensor, without zoom), as the sensor recorded them, with everything needed to
 * develop them into a photo ourselves.
 *
 * Each frame is [width]×[height] sensor values behind a 2×2 colour filter. Per-position arrays
 * ([blackLevel], [noiseScale], [noiseOffset]) are in the order the filter repeats: top left, top
 * right, bottom left, bottom right.
 */
class RawBurst(
    val frames: List<ShortArray>,
    val width: Int,
    val height: Int,
    /** Colour of each filter position, as [RawColour]s in the order above. */
    val pattern: List<RawColour>,
    val blackLevel: FloatArray,
    val whiteLevel: Int,
    /** White balance gains for red, green and blue, as the camera chose them. */
    val whiteBalance: FloatArray,
    /** Row-major 3×3 matrix from white-balanced sensor colour to linear sRGB. */
    val colourMatrix: FloatArray,
    /**
     * The sensor's noise for a value v between 0 (black) and 1 (white): its variance is
     * noiseScale × v + noiseOffset, per filter position. Null when the camera doesn't say.
     */
    val noiseScale: FloatArray?,
    val noiseOffset: FloatArray?,
    /** Gains that even out the lens's darker, tinted corners, or null when the camera gives none. */
    val shading: LensShading?,
    /** Clockwise turn that shows the frames upright. */
    val rotationDegrees: Int,
    /** The camera's own JPEG of the first frame: shown at once, and kept if developing fails. */
    val preview: CapturedImage,
)

enum class RawColour { RED, GREEN, BLUE }

/**
 * A coarse [columns]×[rows] grid of gains over the frame for red, green and blue, row-major,
 * interleaved as r, g, b per point.
 */
class LensShading(val columns: Int, val rows: Int, val gains: FloatArray)
