package com.camoron.data.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.common.timed
import com.camoron.domain.model.PixelSize
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Merges a handheld night burst into one cleaner photo, entirely on the device.
 *
 * The sharpest frame of the burst is the reference. Every other frame is aligned to it as a whole
 * (a coarse search on small previews, then a fine search on a full resolution centre crop), frames
 * that are too blurred or shifted are dropped, and the rest are aligned again tile by tile, since
 * hand shake also turns the phone slightly. Pixels are then averaged with a weight that fades out
 * anything that differs by more than noise does, so moving subjects stay sharp instead of ghosting
 * ([MergeMath.BandMerge]). The work is done in bands so only a strip of each frame is decoded at a
 * time, and the bands are shared out between a few threads.
 *
 * With [cropAspect] (portrait width over height, as in the photo adjustments) only the part of the
 * frame the photo keeps is merged, and the result is already that shape.
 */
internal class NightMerger(private val burst: List<ByteArray>, private val cropAspect: Float? = null) {

    private data class Shift(val dx: Int, val dy: Int)

    /** The merged JPEG and its size, or null when fewer than two frames were usable. */
    suspend fun merge(): Pair<ByteArray, PixelSize>? {
        if (burst.size < 2) return null
        val size = decodeBounds(burst[0])
        if (size.width <= 0 || size.height <= 0) return null
        val area = cropAspect
            ?.let { PhotoMath.centreCrop(size.width, size.height, 1f / it) }
            ?.let { Rect(it.left, it.top, it.right, it.bottom) }
            ?: Rect(0, 0, size.width, size.height)

        // One small preview of each frame serves both for picking the sharpest and for coarse alignment.
        val previews = DiagnosticLog.timed(TAG, "Previewing ${burst.size} frames") {
            parallel(burst.size) { previewLuma(burst[it]) }
        }
        val order = sharpestFirst(previews)
        val frames = order.map { burst[it] }
        val coarse = order.map { previews[it]?.let(::halved) }

        val shifts = DiagnosticLog.timed(TAG, "Aligning ${frames.size} frames") { align(frames, coarse, size) }
        if (shifts.size < 2) return null

        val outputSize = PixelSize(area.width(), area.height())
        val output = Bitmap.createBitmap(outputSize.width, outputSize.height, Bitmap.Config.ARGB_8888)
        try {
            DiagnosticLog.timed(TAG, "Merging ${shifts.size} frames ${outputSize.width}x${outputSize.height}") {
                mergeInto(output, frames, size, area, shifts)
            }
            val bytes = DiagnosticLog.timed(TAG, "Encoding the merged photo") {
                ByteArrayOutputStream()
                    .also { output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    .toByteArray()
            }
            return bytes to outputSize
        } finally {
            output.recycle()
        }
    }

    /** Shift for every usable frame index; the reference (0) is always present with no shift. */
    private suspend fun align(frames: List<ByteArray>, coarse: List<Pixels?>, size: PixelSize): Map<Int, Shift> {
        val coarseRef = coarse[0] ?: return emptyMap()
        val cropWidth = minOf(FINE_CROP, size.width)
        val cropHeight = minOf(FINE_CROP, size.height)
        val cropLeft = (size.width - cropWidth) / 2
        val cropTop = (size.height - cropHeight) / 2
        val fineRef = regionLuma(frames[0], Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight))
            ?: return emptyMap()

        val found = parallel(frames.size - 1) { i ->
            val index = i + 1
            if (decodeBounds(frames[index]) != size) return@parallel null
            val preview = coarse[index] ?: return@parallel null
            val coarseShift = MergeMath.bestOffset(
                coarseRef.pixels, coarseRef.width, coarseRef.height,
                preview.pixels, preview.width, preview.height,
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
            if (!area.intersect(0, 0, size.width, size.height)) return@parallel null
            val fine = regionLuma(frames[index], area) ?: return@parallel null
            val offset = MergeMath.bestOffset(
                fineRef.pixels, fineRef.width, fineRef.height,
                fine.pixels, fine.width, fine.height,
                baseX = cropLeft + guessX - area.left,
                baseY = cropTop + guessY - area.top,
                radius = FINE_RADIUS,
                step = FINE_STEP,
            )
            if (offset.meanError > MergeMath.FRAME_REJECT_ERROR) return@parallel null
            Shift(offset.dx + area.left - cropLeft, offset.dy + area.top - cropTop)
        }
        val shifts = linkedMapOf(0 to Shift(0, 0))
        found.forEachIndexed { i, shift -> if (shift != null) shifts[i + 1] = shift }
        return shifts
    }

    /**
     * Merges [area] of the frames into [output], band by band. Each worker has its own decoders,
     * since a decoder handles one region at a time.
     */
    private suspend fun mergeInto(
        output: Bitmap,
        frames: List<ByteArray>,
        size: PixelSize,
        area: Rect,
        shifts: Map<Int, Shift>,
    ) {
        val width = area.width()
        val margin = MergeMath.LOCAL_RADIUS
        val bands = (area.height() + BAND - 1) / BAND
        val next = AtomicInteger(0)
        val workers = minOf(MAX_WORKERS, Runtime.getRuntime().availableProcessors(), bands).coerceAtLeast(1)
        coroutineScope {
            (0 until workers).map {
                async {
                    val decoders = shifts.keys.associateWith { regionDecoder(frames[it]) }
                    try {
                        while (true) {
                            val band = next.getAndIncrement()
                            if (band >= bands) break
                            val top = area.top + band * BAND
                            val bandHeight = minOf(BAND, area.bottom - top)
                            val reference = decodeRegion(decoders.getValue(0), Rect(area.left, top, area.right, top + bandHeight))
                                ?: error("Could not decode the reference frame")
                            val merge = MergeMath.BandMerge(reference.pixels, width, bandHeight)
                            for ((index, shift) in shifts) {
                                if (index == 0) continue
                                // The frame's matching strip, with room for each tile to move a little on its own.
                                val strip = Rect(
                                    area.left + shift.dx - margin,
                                    top + shift.dy - margin,
                                    area.right + shift.dx + margin,
                                    top + bandHeight + shift.dy + margin,
                                )
                                if (!strip.intersect(0, 0, size.width, size.height)) continue
                                val pixels = decodeRegion(decoders.getValue(index), strip) ?: continue
                                merge.add(
                                    MergeMath.Strip(
                                        pixels.pixels, pixels.width, pixels.height,
                                        area.left + shift.dx - strip.left, top + shift.dy - strip.top,
                                    ),
                                )
                            }
                            val merged = merge.result()
                            synchronized(output) {
                                output.setPixels(merged, 0, width, 0, top - area.top, width, bandHeight)
                            }
                        }
                    } finally {
                        decoders.values.forEach(BitmapRegionDecoder::recycle)
                    }
                }
            }.awaitAll()
        }
    }

    private class Pixels(val pixels: IntArray, val width: Int, val height: Int)

    /** Luma of the frame at 1/[PREVIEW_SAMPLE] scale. */
    private fun previewLuma(bytes: ByteArray): Pixels? {
        val options = BitmapFactory.Options().apply { inSampleSize = PREVIEW_SAMPLE }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return bitmap.toPixels().also { bitmap.recycle() }.toLuma()
    }

    /** A preview at half its scale again, for the coarse alignment. */
    private fun halved(preview: Pixels): Pixels {
        val (pixels, width, height) = MergeMath.halve(preview.pixels, preview.width, preview.height)
        return Pixels(pixels, width, height)
    }

    private fun regionLuma(bytes: ByteArray, rect: Rect): Pixels? {
        val decoder = regionDecoder(bytes)
        return try {
            decodeRegion(decoder, rect)?.toLuma()
        } finally {
            decoder.recycle()
        }
    }

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

    /** Runs [block] for 0 until [count] on the caller's threads, a few at a time, keeping the order. */
    private suspend fun <T> parallel(count: Int, block: (Int) -> T): List<T> = coroutineScope {
        (0 until count).map { async { block(it) } }.awaitAll()
    }

    private companion object {
        /**
         * Frame indices with the burst's sharpest frame (least hand shake) first; the first stays
         * first on a tie, and a frame without a preview is never picked.
         */
        fun sharpestFirst(previews: List<Pixels?>): List<Int> {
            val scores = previews.map { it?.let { p -> MergeMath.sharpness(p.pixels, p.width, p.height) } ?: -1f }
            val best = scores.indices.maxByOrNull { scores[it] } ?: 0
            return listOf(best) + previews.indices.filter { it != best }
        }

        const val TAG = "NightMerger"
        const val PREVIEW_SAMPLE = 4
        /** The coarse alignment runs on the previews halved again. */
        const val COARSE_SAMPLE = PREVIEW_SAMPLE * 2
        const val COARSE_RADIUS = 8
        const val FINE_CROP = 768
        const val FINE_MARGIN = 12
        const val FINE_RADIUS = 12
        const val FINE_STEP = 4
        const val BAND = 256
        const val MAX_WORKERS = 4
    }
}
