package com.camoron.data.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.camoron.domain.model.PixelSize
import java.io.ByteArrayOutputStream

/**
 * Merges a handheld night burst into one cleaner photo, entirely on the device.
 *
 * Every frame is aligned to the first one (a coarse search on small previews, then a fine search
 * on a full resolution centre crop), frames that are too blurred or shifted are dropped, and the
 * rest are averaged pixel by pixel. A pixel that differs too much from the reference (something
 * moved) is left out, so moving subjects stay sharp instead of ghosting. The work is done in bands
 * so only a strip of each frame is decoded at a time.
 */
internal class NightMerger(private val frames: List<ByteArray>) {

    private data class Shift(val dx: Int, val dy: Int)

    /** The merged JPEG and its size, or null when fewer than two frames were usable. */
    fun merge(): Pair<ByteArray, PixelSize>? {
        if (frames.size < 2) return null
        val size = decodeBounds(frames[0])
        if (size.width <= 0 || size.height <= 0) return null
        val shifts = align(size)
        if (shifts.size < 2) return null

        val decoders = shifts.keys.associateWith { regionDecoder(frames[it]) }
        try {
            val output = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                mergeInto(output, size, shifts, decoders)
                val bytes = ByteArrayOutputStream()
                    .also { output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    .toByteArray()
                return bytes to size
            } finally {
                output.recycle()
            }
        } finally {
            decoders.values.forEach(BitmapRegionDecoder::recycle)
        }
    }

    /** Shift for every usable frame index; the reference (0) is always present with no shift. */
    private fun align(size: PixelSize): Map<Int, Shift> {
        val coarseRef = sampledLuma(frames[0]) ?: return emptyMap()
        val cropWidth = minOf(FINE_CROP, size.width)
        val cropHeight = minOf(FINE_CROP, size.height)
        val cropLeft = (size.width - cropWidth) / 2
        val cropTop = (size.height - cropHeight) / 2
        val referenceDecoder = regionDecoder(frames[0])
        val fineRef = try {
            regionLuma(referenceDecoder, Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight))
        } finally {
            referenceDecoder.recycle()
        } ?: return emptyMap()

        val shifts = linkedMapOf(0 to Shift(0, 0))
        for (index in 1 until frames.size) {
            if (decodeBounds(frames[index]) != size) continue
            val coarse = sampledLuma(frames[index]) ?: continue
            val coarseShift = MergeMath.bestOffset(
                coarseRef.pixels, coarseRef.width, coarseRef.height,
                coarse.pixels, coarse.width, coarse.height,
                baseX = 0, baseY = 0, radius = COARSE_RADIUS,
            )
            val guessX = coarseShift.dx * COARSE_SAMPLE
            val guessY = coarseShift.dy * COARSE_SAMPLE

            // Decode just the area the reference crop could land on in this frame.
            val area = Rect(
                cropLeft + guessX - FINE_MARGIN,
                cropTop + guessY - FINE_MARGIN,
                cropLeft + guessX + cropWidth + FINE_MARGIN,
                cropTop + guessY + cropHeight + FINE_MARGIN,
            )
            if (!area.intersect(0, 0, size.width, size.height)) continue
            val decoder = regionDecoder(frames[index])
            val fine = try {
                regionLuma(decoder, area)
            } finally {
                decoder.recycle()
            } ?: continue
            val offset = MergeMath.bestOffset(
                fineRef.pixels, fineRef.width, fineRef.height,
                fine.pixels, fine.width, fine.height,
                baseX = cropLeft + guessX - area.left,
                baseY = cropTop + guessY - area.top,
                radius = FINE_RADIUS,
                step = FINE_STEP,
            )
            if (offset.meanError > MergeMath.FRAME_REJECT_ERROR) continue
            shifts[index] = Shift(offset.dx + area.left - cropLeft, offset.dy + area.top - cropTop)
        }
        return shifts
    }

    private fun mergeInto(
        output: Bitmap,
        size: PixelSize,
        shifts: Map<Int, Shift>,
        decoders: Map<Int, BitmapRegionDecoder>,
    ) {
        val width = size.width
        val sumR = IntArray(width * BAND)
        val sumG = IntArray(width * BAND)
        val sumB = IntArray(width * BAND)
        val count = IntArray(width * BAND)
        val result = IntArray(width * BAND)

        var top = 0
        while (top < size.height) {
            val bandHeight = minOf(BAND, size.height - top)
            val bandPixels = width * bandHeight
            val reference = decodeRegion(decoders.getValue(0), Rect(0, top, width, top + bandHeight))
                ?: error("Could not decode the reference frame")
            for (i in 0 until bandPixels) {
                val px = reference.pixels[i]
                sumR[i] = (px shr 16) and 0xFF
                sumG[i] = (px shr 8) and 0xFF
                sumB[i] = px and 0xFF
                count[i] = 1
            }

            for ((index, shift) in shifts) {
                if (index == 0) continue
                val area = Rect(shift.dx, top + shift.dy, width + shift.dx, top + bandHeight + shift.dy)
                if (!area.intersect(0, 0, width, size.height)) continue
                val strip = decodeRegion(decoders.getValue(index), area) ?: continue
                for (y in 0 until bandHeight) {
                    val fy = top + y + shift.dy - area.top
                    if (fy !in 0 until strip.height) continue
                    for (x in 0 until width) {
                        val fx = x + shift.dx - area.left
                        if (fx !in 0 until strip.width) continue
                        val i = y * width + x
                        val px = strip.pixels[fy * strip.width + fx]
                        if (!MergeMath.similar(reference.pixels[i], px)) continue
                        sumR[i] += (px shr 16) and 0xFF
                        sumG[i] += (px shr 8) and 0xFF
                        sumB[i] += px and 0xFF
                        count[i]++
                    }
                }
            }

            for (i in 0 until bandPixels) result[i] = MergeMath.average(sumR[i], sumG[i], sumB[i], count[i])
            output.setPixels(result, 0, width, 0, top, width, bandHeight)
            top += bandHeight
        }
    }

    private class Pixels(val pixels: IntArray, val width: Int, val height: Int)

    private fun sampledLuma(bytes: ByteArray): Pixels? {
        val options = BitmapFactory.Options().apply { inSampleSize = COARSE_SAMPLE }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return bitmap.toPixels().also { bitmap.recycle() }.toLuma()
    }

    private fun regionLuma(decoder: BitmapRegionDecoder, rect: Rect): Pixels? = decodeRegion(decoder, rect)?.toLuma()

    private fun decodeRegion(decoder: BitmapRegionDecoder, rect: Rect): Pixels? {
        val bitmap = decoder.decodeRegion(rect, null) ?: return null
        return bitmap.toPixels().also { bitmap.recycle() }
    }

    private fun Bitmap.toPixels(): Pixels {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return Pixels(pixels, width, height)
    }

    private fun Pixels.toLuma(): Pixels {
        for (i in pixels.indices) pixels[i] = MergeMath.luma(pixels[i])
        return this
    }

    private companion object {
        const val COARSE_SAMPLE = 8
        const val COARSE_RADIUS = 8
        const val FINE_CROP = 768
        const val FINE_MARGIN = 12
        const val FINE_RADIUS = 12
        const val FINE_STEP = 4
        const val BAND = 256
    }
}
