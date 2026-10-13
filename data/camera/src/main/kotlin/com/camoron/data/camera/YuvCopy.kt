package com.camoron.data.camera

import android.graphics.ImageFormat
import android.media.Image
import com.camoron.domain.model.YuvFrame
import java.nio.ByteBuffer

/** Copies a camera YUV_420_888 image into a compact [YuvFrame], whatever the phone's row and pixel layout. */
internal object YuvCopy {

    fun copy(image: Image): YuvFrame {
        require(image.format == ImageFormat.YUV_420_888) { "Not a YUV frame: ${image.format}" }
        val width = image.width
        val height = image.height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val planes = image.planes
        val y = ByteArray(width * height)
        val u = ByteArray(chromaWidth * chromaHeight)
        val v = ByteArray(chromaWidth * chromaHeight)
        copyPlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, width, height, y)
        copyPlane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, chromaWidth, chromaHeight, u)
        copyPlane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, chromaWidth, chromaHeight, v)
        return YuvFrame(width, height, y, u, v)
    }

    private fun copyPlane(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int, out: ByteArray) {
        val source = buffer.duplicate()
        if (pixelStride == 1) {
            if (rowStride == width) {
                source.position(0)
                source.get(out, 0, width * height)
                return
            }
            for (row in 0 until height) {
                source.position(row * rowStride)
                source.get(out, row * width, width)
            }
            return
        }
        // Interleaved colour (U and V alternate): read each row once, then pick every pixelStride-th byte.
        val rowBytes = ByteArray(rowStride)
        for (row in 0 until height) {
            val start = row * rowStride
            // The last row may stop right after its last pixel.
            val length = minOf(rowStride, source.limit() - start)
            source.position(start)
            source.get(rowBytes, 0, length)
            val outRow = row * width
            for (x in 0 until width) out[outRow + x] = rowBytes[x * pixelStride]
        }
    }
}
