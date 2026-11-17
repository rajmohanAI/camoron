package com.camoron.engine

import kotlin.math.roundToInt

/**
 * Where the engine reads a photo from, a region at a time, so a large photo never has to be
 * decoded whole. Pixels are ARGB, row by row.
 */
interface PixelSource {
    val width: Int
    val height: Int

    /** The [w]×[h] block at ([x], [y]), which always lies inside the photo. */
    fun read(x: Int, y: Int, w: Int, h: Int): IntArray
}

/** Where the engine writes the optimised photo, a block at a time (ARGB, row by row). */
fun interface PixelSink {
    fun write(x: Int, y: Int, w: Int, h: Int, pixels: IntArray)
}

/** A whole photo held in memory, for tests and small photos. */
class ArgbImage(override val width: Int, override val height: Int, val pixels: IntArray = IntArray(width * height)) : PixelSource, PixelSink {
    override fun read(x: Int, y: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (row in 0 until h) System.arraycopy(pixels, (y + row) * width + x, out, row * w, w)
        return out
    }

    @Synchronized
    override fun write(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) {
        for (row in 0 until h) System.arraycopy(pixels, row * w, this.pixels, (y + row) * width + x, w)
    }
}

/**
 * A block of a photo as brightness (Y) and colour (Cb, Cr) planes from 0 to 1, as JPEG stores
 * them (BT.601, full range). [x] and [y] place the block in the whole photo, which the steps that
 * depend on position (JPEG's 8×8 grid, lens fringing around the centre) need.
 */
class Planes(val x: Int, val y: Int, val width: Int, val height: Int) {
    val luma = FloatArray(width * height)
    val cb = FloatArray(width * height)
    val cr = FloatArray(width * height)

    fun toArgb(): IntArray = IntArray(width * height) { i -> argb(luma[i], cb[i], cr[i]) }

    fun copy(): Planes = Planes(x, y, width, height).also {
        luma.copyInto(it.luma)
        cb.copyInto(it.cb)
        cr.copyInto(it.cr)
    }

    /** The part of this block from ([cx], [cy]) in photo coordinates, [cw]×[ch] in size. */
    fun crop(cx: Int, cy: Int, cw: Int, ch: Int): Planes {
        val out = Planes(cx, cy, cw, ch)
        for (row in 0 until ch) {
            val from = (cy - y + row) * width + (cx - x)
            System.arraycopy(luma, from, out.luma, row * cw, cw)
            System.arraycopy(cb, from, out.cb, row * cw, cw)
            System.arraycopy(cr, from, out.cr, row * cw, cw)
        }
        return out
    }

    companion object {
        fun fromArgb(x: Int, y: Int, width: Int, height: Int, pixels: IntArray): Planes {
            val p = Planes(x, y, width, height)
            for (i in 0 until width * height) {
                val c = pixels[i]
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                p.luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
                p.cb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b
                p.cr[i] = 0.5f * r - 0.418688f * g - 0.081312f * b
            }
            return p
        }

        fun argb(y: Float, cb: Float, cr: Float): Int {
            val r = y + 1.402f * cr
            val g = y - 0.344136f * cb - 0.714136f * cr
            val b = y + 1.772f * cb
            return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
        }

        fun byte(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)

        fun lumaOf(c: Int): Float =
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
    }
}

/** Reads (x, y) of a plane [w] wide and [h] tall, holding to the nearest edge outside it. */
internal fun FloatArray.at(w: Int, h: Int, x: Int, y: Int): Float =
    this[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

/** Bilinear read at a fractional position, holding to the edge. */
internal fun FloatArray.bilinear(w: Int, h: Int, fx: Float, fy: Float): Float {
    val x0 = kotlin.math.floor(fx).toInt()
    val y0 = kotlin.math.floor(fy).toInt()
    val tx = fx - x0
    val ty = fy - y0
    val a = at(w, h, x0, y0)
    val b = at(w, h, x0 + 1, y0)
    val c = at(w, h, x0, y0 + 1)
    val d = at(w, h, x0 + 1, y0 + 1)
    return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
}
