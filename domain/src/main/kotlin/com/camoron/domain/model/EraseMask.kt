package com.camoron.domain.model

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What one use of the magic eraser removes: a mask over the upright photo, kept at a reduced size
 * (at most [EDGE] cells along the longer side) so the same mask applies to the screen preview and
 * the full-size photo. Masks are immutable; painting returns a new one.
 */
class EraseMask private constructor(
    val width: Int,
    val height: Int,
    private val cells: BooleanArray,
) {
    val isEmpty: Boolean get() = cells.none { it }

    operator fun get(x: Int, y: Int): Boolean = cells[y * width + x]

    /** This mask with a brush stroke through [points] (x, y pairs, 0–1 across the photo) of [radius] (a fraction of the longer side). */
    fun withStroke(points: FloatArray, radius: Float): EraseMask {
        if (points.size < 2) return this
        val out = cells.copyOf()
        val r = max(0.5f, radius * max(width, height))
        // Stamp discs along each segment, close enough together to leave no gaps.
        var px = points[0] * width
        var py = points[1] * height
        stamp(out, px, py, r)
        for (i in 2 until points.size - 1 step 2) {
            val x = points[i] * width
            val y = points[i + 1] * height
            val steps = ceil(hypot(x - px, y - py) / (r * 0.5f)).toInt()
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                stamp(out, px + (x - px) * t, py + (y - py) * t, r)
            }
            px = x
            py = y
        }
        return EraseMask(width, height, out)
    }

    /**
     * This mask plus a selection found on a [selectionWidth] x [selectionHeight] picture of the
     * photo ([selected] true for each chosen pixel). A cell is chosen if any of its pixels is.
     */
    fun withSelection(selectionWidth: Int, selectionHeight: Int, selected: BooleanArray): EraseMask {
        val out = cells.copyOf()
        for (y in 0 until selectionHeight) {
            val cy = min(height - 1, y * height / selectionHeight)
            for (x in 0 until selectionWidth) {
                if (selected[y * selectionWidth + x]) out[cy * width + min(width - 1, x * width / selectionWidth)] = true
            }
        }
        return EraseMask(width, height, out)
    }

    /**
     * Where this mask's erase works on a [pictureWidth] x [pictureHeight] picture: the removed
     * area grown by [grow] pixels, plus the surroundings it copies from, as (left, top, right,
     * bottom exclusive) pixels; null if the mask is empty.
     */
    fun area(pictureWidth: Int, pictureHeight: Int, grow: Int): IntArray? {
        val box = Inpaint.bounds(cells, width, height) ?: return null
        val hole = intArrayOf(
            max(0, box[0] * pictureWidth / width - grow),
            max(0, box[1] * pictureHeight / height - grow),
            min(pictureWidth, ceilDiv(box[2] * pictureWidth, width) + grow),
            min(pictureHeight, ceilDiv(box[3] * pictureHeight, height) + grow),
        )
        return Inpaint.area(hole, pictureWidth, pictureHeight)
    }

    /**
     * The pixels to rebuild within [area] (as from [area]) of a [pictureWidth] x [pictureHeight]
     * picture, row by row: the mask scaled up and grown by [grow] pixels, so the edges of what
     * is removed (soft outlines, a halo, a thin shadow) go with it.
     */
    fun hole(pictureWidth: Int, pictureHeight: Int, grow: Int, area: IntArray): BooleanArray {
        val (x0, y0, x1, y1) = area
        val w = x1 - x0
        val h = y1 - y0
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val cy = min(height - 1, (y0 + y) * height / pictureHeight)
            for (x in 0 until w) {
                out[y * w + x] = cells[cy * width + min(width - 1, (x0 + x) * width / pictureWidth)]
            }
        }
        return if (grow > 0) Inpaint.dilate(out, w, h, grow) else out
    }

    private fun stamp(out: BooleanArray, cx: Float, cy: Float, r: Float) {
        val r2 = r * r
        for (y in max(0, (cy - r).toInt())..min(height - 1, (cy + r).toInt())) {
            for (x in max(0, (cx - r).toInt())..min(width - 1, (cx + r).toInt())) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                if (dx * dx + dy * dy <= r2) out[y * width + x] = true
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is EraseMask && other.width == width && other.height == height && other.cells.contentEquals(cells)

    override fun hashCode(): Int = 31 * (31 * width + height) + cells.contentHashCode()

    companion object {
        /** Cells along the mask's longer side. */
        const val EDGE = 512

        /** An empty mask shaped like a [pictureWidth] x [pictureHeight] upright photo. */
        fun empty(pictureWidth: Int, pictureHeight: Int): EraseMask {
            val scale = EDGE.toFloat() / max(pictureWidth, pictureHeight)
            val w = (pictureWidth * scale).roundToInt().coerceIn(1, EDGE)
            val h = (pictureHeight * scale).roundToInt().coerceIn(1, EDGE)
            return EraseMask(w, h, BooleanArray(w * h))
        }

        /** How far, in pixels, an erase reaches past its mask on a picture of this size. */
        fun grow(pictureWidth: Int, pictureHeight: Int): Int = max(2, (max(pictureWidth, pictureHeight) * GROW).roundToInt())

        private const val GROW = 0.006f

        private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
    }
}
