package com.camoron.domain.model

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Geometry for the editor's crop, rotate, flip and straighten. Pure maths, so the preview and the
 * full-size photo are cut in exactly the same place.
 *
 * Photos are measured upright (after their EXIF rotation). "Turned" sizes are after the quarter
 * turns, which is the frame the crop rectangle is measured in.
 */
object CropMath {

    const val MAX_STRAIGHTEN = 15f
    const val MIN_SIZE = 0.08f
    private const val FIT_STEPS = 24
    private const val EPSILON = 1e-5f

    /** Width and height of a [width] x [height] photo after the crop's quarter turns. */
    fun turnedSize(width: Int, height: Int, crop: EditCrop): Pair<Int, Int> =
        if (crop.sideways) height to width else width to height

    /** Size in pixels of the finished photo. */
    fun outputSize(width: Int, height: Int, crop: EditCrop): Pair<Int, Int> {
        val (w, h) = turnedSize(width, height, crop)
        return (crop.width(w)).coerceAtLeast(1) to (crop.height(h)).coerceAtLeast(1)
    }

    private fun EditCrop.width(w: Int) = Math.round((right - left) * w)
    private fun EditCrop.height(h: Int) = Math.round((bottom - top) * h)

    /**
     * The affine map from a source pixel (upright photo, [width] x [height]) to its place in the
     * finished photo, as [a, b, c, d, e, f] meaning x' = a·x + b·y + c and y' = d·x + e·y + f.
     * Matches android.graphics.Matrix's first six values.
     */
    fun transform(width: Int, height: Int, crop: EditCrop): FloatArray {
        val w = width.toFloat()
        val h = height.toFloat()
        // Quarter turns clockwise, keeping the picture in positive coordinates.
        var m = when (((crop.quarterTurns % 4) + 4) % 4) {
            1 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f)
            2 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h)
            3 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        }
        val (tw, th) = if (crop.sideways) h to w else w to h
        if (crop.mirrored) m = then(m, floatArrayOf(-1f, 0f, tw, 0f, 1f, 0f))
        if (crop.straighten != 0f) {
            val rad = Math.toRadians(crop.straighten.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val cx = tw / 2
            val cy = th / 2
            // Clockwise on screen (y points down) about the centre.
            m = then(m, floatArrayOf(c, -s, cx - c * cx + s * cy, s, c, cy - s * cx - c * cy))
        }
        return then(m, floatArrayOf(1f, 0f, -crop.left * tw, 0f, 1f, -crop.top * th))
    }

    /**
     * The map from a photo's stored pixels ([width] x [height]) to its upright picture, for an EXIF
     * orientation tag (1..8), in the same [a, b, c, d, e, f] form as [transform].
     */
    fun upright(orientation: Int, width: Int, height: Int): FloatArray {
        val w = width.toFloat()
        val h = height.toFloat()
        return when (orientation) {
            2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f)
            3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f)
            6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f)
            7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        }
    }

    /** Whether an EXIF orientation tag turns the picture on its side. */
    fun uprightSwapsSides(orientation: Int): Boolean = orientation in 5..8

    /** [first] followed by [second]. */
    fun then(first: FloatArray, second: FloatArray): FloatArray {
        val (a1, b1, c1, d1, e1) = first
        val f1 = first[5]
        val (a2, b2, c2, d2, e2) = second
        val f2 = second[5]
        return floatArrayOf(
            a2 * a1 + b2 * d1, a2 * b1 + b2 * e1, a2 * c1 + b2 * f1 + c2,
            d2 * a1 + e2 * d1, d2 * b1 + e2 * e1, d2 * c1 + e2 * f1 + f2,
        )
    }

    /**
     * Whether [rect] (fractions of a turned [width] x [height] frame) lies entirely on the picture
     * once it is straightened by [degrees], so the finished photo has no empty corners.
     */
    fun fits(rect: CropRectF, width: Float, height: Float, degrees: Float): Boolean {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val cx = width / 2
        val cy = height / 2
        val xs = floatArrayOf(rect.left * width, rect.right * width)
        val ys = floatArrayOf(rect.top * height, rect.bottom * height)
        for (x in xs) {
            for (y in ys) {
                // Undo the straightening to find where this corner falls on the picture.
                val dx = x - cx
                val dy = y - cy
                val px = c * dx + s * dy + cx
                val py = -s * dx + c * dy + cy
                if (px < -EPSILON * width || px > width * (1 + EPSILON) || py < -EPSILON * height || py > height * (1 + EPSILON)) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * [rect] shrunk towards the frame's centre just enough to sit on the straightened picture.
     * Unchanged if it already fits.
     */
    fun fitInside(rect: CropRectF, width: Float, height: Float, degrees: Float): CropRectF {
        if (fits(rect, width, height, degrees)) return rect
        var lo = 0f
        var hi = 1f
        repeat(FIT_STEPS) {
            val mid = (lo + hi) / 2
            if (fits(scaled(rect, mid), width, height, degrees)) lo = mid else hi = mid
        }
        return scaled(rect, lo)
    }

    private fun scaled(rect: CropRectF, s: Float) = CropRectF(
        0.5f + (rect.left - 0.5f) * s,
        0.5f + (rect.top - 0.5f) * s,
        0.5f + (rect.right - 0.5f) * s,
        0.5f + (rect.bottom - 0.5f) * s,
    )

    /**
     * The largest rectangle of [ratio] (width over height, in pixels) centred in a turned
     * [width] x [height] frame and on the straightened picture. A null ratio means the whole frame.
     */
    fun centred(ratio: Float?, width: Float, height: Float, degrees: Float): CropRectF {
        val rect = if (ratio == null) {
            CropRectF.FULL
        } else {
            val frameRatio = width / height
            if (ratio > frameRatio) {
                val h = frameRatio / ratio
                CropRectF(0f, (1 - h) / 2, 1f, (1 + h) / 2)
            } else {
                val w = ratio / frameRatio
                CropRectF((1 - w) / 2, 0f, (1 + w) / 2, 1f)
            }
        }
        return fitInside(rect, width, height, degrees)
    }

    /** The crop after turning the photo a quarter turn clockwise as the user sees it. */
    fun turnClockwise(crop: EditCrop): EditCrop {
        // Seen through a flip, a clockwise turn is an anticlockwise turn of the unflipped photo.
        val turns = if (crop.mirrored) crop.quarterTurns + 3 else crop.quarterTurns + 1
        return crop.copy(
            quarterTurns = turns % 4,
            left = 1f - crop.bottom,
            top = crop.left,
            right = 1f - crop.top,
            bottom = crop.right,
        )
    }

    /** The crop after flipping the photo left to right as the user sees it. */
    fun flip(crop: EditCrop): EditCrop = crop.copy(
        mirrored = !crop.mirrored,
        straighten = -crop.straighten,
        left = 1f - crop.right,
        right = 1f - crop.left,
    )

    /** The part of the crop frame a finger is dragging. */
    enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, TOP, RIGHT, BOTTOM, MOVE }

    /**
     * Moves [handle] of [rect] by [dx], [dy] (fractions of the turned frame). With a [ratio] lock
     * (width over height in pixels) corners keep the shape. The result never goes off the
     * straightened picture or below the minimum size: a drag that would is stopped at the edge.
     */
    fun drag(
        rect: CropRectF,
        handle: Handle,
        dx: Float,
        dy: Float,
        width: Float,
        height: Float,
        degrees: Float,
        ratio: Float?,
    ): CropRectF {
        if (handle == Handle.MOVE) {
            // Each direction goes as far as it can, so a move pressed against one edge still slides along it.
            val alongX = furthest(rect, handle, dx, 0f, width, height, degrees, ratio)
            return furthest(alongX, handle, 0f, dy, width, height, degrees, ratio)
        }
        return furthest(rect, handle, dx, dy, width, height, degrees, ratio)
    }

    /** [rect] with [handle] moved as far along (dx, dy) as stays valid. */
    private fun furthest(
        rect: CropRectF,
        handle: Handle,
        dx: Float,
        dy: Float,
        width: Float,
        height: Float,
        degrees: Float,
        ratio: Float?,
    ): CropRectF {
        val moved = moveHandle(rect, handle, dx, dy, width, height, ratio)
        if (valid(moved, width, height, degrees)) return moved
        var lo = 0f
        var hi = 1f
        repeat(FIT_STEPS) {
            val mid = (lo + hi) / 2
            if (valid(moveHandle(rect, handle, dx * mid, dy * mid, width, height, ratio), width, height, degrees)) lo = mid else hi = mid
        }
        val best = moveHandle(rect, handle, dx * lo, dy * lo, width, height, ratio)
        return if (valid(best, width, height, degrees)) best else rect
    }

    private fun valid(rect: CropRectF, width: Float, height: Float, degrees: Float) =
        rect.width >= MIN_SIZE - EPSILON && rect.height >= MIN_SIZE - EPSILON && fits(rect, width, height, degrees)

    private fun moveHandle(
        rect: CropRectF,
        handle: Handle,
        dx: Float,
        dy: Float,
        width: Float,
        height: Float,
        ratio: Float?,
    ): CropRectF {
        var (l, t, r, b) = rect
        when (handle) {
            Handle.MOVE -> return CropRectF(l + dx, t + dy, r + dx, b + dy)
            Handle.LEFT -> l += dx
            Handle.RIGHT -> r += dx
            Handle.TOP -> t += dy
            Handle.BOTTOM -> b += dy
            Handle.TOP_LEFT -> { l += dx; t += dy }
            Handle.TOP_RIGHT -> { r += dx; t += dy }
            Handle.BOTTOM_LEFT -> { l += dx; b += dy }
            Handle.BOTTOM_RIGHT -> { r += dx; b += dy }
        }
        if (ratio != null) {
            // Keep the shape: the height follows the width, anchored at the opposite corner or edge.
            val newHeight = (r - l) * width / ratio / height
            when (handle) {
                Handle.TOP_LEFT, Handle.TOP_RIGHT -> t = b - newHeight
                Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT -> b = t + newHeight
                Handle.LEFT, Handle.RIGHT -> {
                    val cy = (t + b) / 2
                    t = cy - newHeight / 2
                    b = cy + newHeight / 2
                }
                Handle.TOP, Handle.BOTTOM -> {
                    val newWidth = (b - t) * height * ratio / width
                    val cx = (l + r) / 2
                    l = cx - newWidth / 2
                    r = cx + newWidth / 2
                }
                Handle.MOVE -> Unit
            }
        }
        return CropRectF(min(l, r), min(t, b), maxOf(l, r), maxOf(t, b))
    }
}
