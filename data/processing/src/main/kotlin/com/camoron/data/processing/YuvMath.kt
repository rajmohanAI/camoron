package com.camoron.data.processing

import com.camoron.domain.model.YuvFrame

/** Colour conversion for camera frames, kept free of Android types so it is unit-testable. */
internal object YuvMath {

    /**
     * Converts [rows] rows of [frame], starting at ([left], [top]) and [width] wide, to opaque ARGB
     * in [out]. Camera frames are full-range BT.601 (the JPEG convention), with colour shared by
     * each 2x2 block of pixels.
     */
    fun toArgb(frame: YuvFrame, left: Int, top: Int, width: Int, rows: Int, out: IntArray) {
        val cw = frame.chromaWidth
        for (row in 0 until rows) {
            val y = top + row
            val lumaRow = y * frame.width
            val chromaRow = (y shr 1) * cw
            val outRow = row * width
            for (col in 0 until width) {
                val x = left + col
                val l = frame.y[lumaRow + x].toInt() and 0xFF
                val ci = chromaRow + (x shr 1)
                val u = (frame.u[ci].toInt() and 0xFF) - 128
                val v = (frame.v[ci].toInt() and 0xFF) - 128
                // Fixed point (x1024) of R = Y + 1.402 V, G = Y - 0.344 U - 0.714 V, B = Y + 1.772 U.
                val r = l + ((1436 * v + 512) shr 10)
                val g = l - ((352 * u + 731 * v + 512) shr 10)
                val b = l + ((1815 * u + 512) shr 10)
                out[outRow + col] = (0xFF shl 24) or
                    (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or
                    b.coerceIn(0, 255)
            }
        }
    }
}
