package com.camoron.data.processing

import android.graphics.Bitmap
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.common.timed
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.RawBurst
import com.camoron.domain.model.RawColour
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream

/**
 * Detail mode's own pipeline: merges a RAW burst ([RawMath.Merge]) and develops it
 * ([RawMath.Develop]) into a JPEG, upright through its EXIF turn like the camera's own. The
 * sharpest frame is the reference; frames that don't line up at all are left out. Work is
 * shared between a few threads.
 */
internal class RawDeveloper(private val burst: RawBurst) {

    /** The developed JPEG and its size, or null when the burst can't be developed. */
    suspend fun develop(): Pair<ByteArray, PixelSize>? {
        val width = burst.width
        val height = burst.height
        // The colour filter repeats every two pixels, so a sensor is always an even size.
        if (width < 4 || height < 4 || width % 2 != 0 || height % 2 != 0) return null
        if (burst.frames.isEmpty() || burst.pattern.size != 4) return null
        val black = burst.blackLevel
        val range = burst.whiteLevel - black.average().toFloat()
        if (range <= 0f) return null
        val blocksWide = width / 2
        val blocksHigh = height / 2

        val lumas = DiagnosticLog.timed(TAG, "Previewing ${burst.frames.size} RAW frames") {
            parallel(burst.frames.indices.toList()) { RawMath.blockLuma(burst.frames[it], width, height, black) }
        }
        val reference = lumas.indices.maxByOrNull { MergeMath.sharpness(lumas[it], blocksWide, blocksHigh, step = 4) } ?: 0
        val merge = RawMath.Merge(
            burst.frames[reference], lumas[reference], width, height, black, range,
            noiseScale = burst.noiseScale?.average()?.toFloat() ?: RawMath.DEFAULT_NOISE_SCALE,
            noiseOffset = burst.noiseOffset?.average()?.toFloat() ?: RawMath.DEFAULT_NOISE_OFFSET,
        )
        val grid = merge.grid
        val referenceMean = lumas[reference].average().coerceAtLeast(1.0)
        var used = 1
        DiagnosticLog.timed(TAG, "Merging ${burst.frames.size} RAW frames ${width}x$height") {
            for (index in burst.frames.indices) {
                if (index == reference) continue
                val global = RawMath.globalShift(lumas[reference], lumas[index], blocksWide, blocksHigh)
                // Blocks are four values each, so the error is relative to the picture's brightness.
                if (global.meanError / referenceMean > MAX_FRAME_ERROR) continue
                val alignment = RawMath.Alignment(IntArray(grid.columns * grid.rows), IntArray(grid.columns * grid.rows))
                parallel((0 until grid.rows).toList()) { row -> RawMath.alignTiles(lumas[reference], lumas[index], grid, global, row, alignment) }
                // Tile rows two apart never overlap, so the even rows go together, then the odd ones.
                for (parity in 0..1) {
                    parallel((parity until grid.rows step 2).toList()) { row -> merge.add(burst.frames[index], lumas[index], alignment, row) }
                }
                used++
            }
        }
        val merged = merge.result()

        val develop = RawMath.Develop(
            merged, width, height,
            pattern = IntArray(4) { colourIndex(burst.pattern[it]) },
            range = range,
            whiteBalance = burst.whiteBalance,
            matrix = burst.colourMatrix,
            shading = burst.shading?.let { RawMath.Shading(it.columns, it.rows, it.gains) },
        )
        val bands = (0 until height step BAND).map { it until minOf(height, it + BAND) }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            DiagnosticLog.timed(TAG, "Developing $used merged RAW frames") {
                parallel(bands) { develop.normalise(it) }
                parallel(bands) { rows ->
                    val out = IntArray(width * (rows.last - rows.first + 1))
                    val row = IntArray(width)
                    for (y in rows) {
                        develop.row(y, row)
                        row.copyInto(out, (y - rows.first) * width)
                    }
                    synchronized(bitmap) { bitmap.setPixels(out, 0, width, 0, rows.first, width, rows.last - rows.first + 1) }
                }
            }
            val bytes = DiagnosticLog.timed(TAG, "Encoding the developed photo") {
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            }
            return bytes to PixelSize(width, height)
        } finally {
            bitmap.recycle()
        }
    }

    /** Runs [block] for every item on the caller's threads, a few at a time, keeping the order. */
    private suspend fun <T, R> parallel(items: List<T>, block: (T) -> R): List<R> = coroutineScope {
        items.map { async { block(it) } }.awaitAll()
    }

    private fun colourIndex(colour: RawColour): Int = when (colour) {
        RawColour.RED -> 0
        RawColour.GREEN -> RawMath.GREEN
        RawColour.BLUE -> 2
    }

    private companion object {
        const val TAG = "RawDeveloper"
        const val BAND = 128

        /** Mean block difference, against the picture's mean brightness, above which a frame doesn't line up. */
        const val MAX_FRAME_ERROR = 0.25
    }
}
