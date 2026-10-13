package com.camoron.data.processing

import android.graphics.Bitmap
import android.os.SystemClock
import com.camoron.core.common.DiagnosticLog
import com.camoron.domain.model.EnhanceMath
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device photo enhancement, in place on a decoded photo:
 *
 * 1. Measures the photo's noise, so a clean daylight shot and a grainy indoor or zoomed one each get
 *    the right strength ([DetailTuning]).
 * 2. Noise and grain reduction that keeps edges and texture, and colour-speckle removal
 *    ([DetailMath]).
 * 3. Gentle sharpening and local contrast on the cleaned picture, held back for noisy or zoomed
 *    photos so grain and zoom blockiness are not exaggerated.
 * 4. Scene-adaptive tone curve and vibrance ([EnhanceMath]), the same ones the preview shows.
 *
 * The photo is processed in bands of rows on several cores; each band reads a few extra rows above
 * and below so the seams are invisible.
 */
internal object Enhancer {

    private const val CLARITY_BLOCK = 32
    private const val HISTOGRAM_BINS = 64
    private const val HISTOGRAM_STEP = 4
    private const val BAND_ROWS = 64
    private const val MAX_WORKERS = 4

    suspend fun enhance(bitmap: Bitmap, zoom: Float = 1f) {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 8 || height < 8) return
        val started = SystemClock.elapsedRealtime()

        val luma = ByteArray(width * height)
        val row = IntArray(width)
        val bins = IntArray(HISTOGRAM_BINS)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val offset = y * width
            for (x in 0 until width) {
                val l = MergeMath.luma(row[x])
                luma[offset + x] = l.toByte()
                if (y % HISTOGRAM_STEP == 0 && x % HISTOGRAM_STEP == 0) bins[l * HISTOGRAM_BINS / 256]++
            }
        }
        val params = EnhanceMath.fromHistogram(bins)
        val lut = EnhanceMath.toneCurve(params)
        val tuning = DetailTuning(DetailMath.estimateNoise(luma, width, height), zoom)
        val coarse = CoarseLuma(luma, width, height, CLARITY_BLOCK)

        val halo = tuning.halo
        val bands = (height + BAND_ROWS - 1) / BAND_ROWS
        val next = AtomicInteger(0)
        val workers = minOf(MAX_WORKERS, Runtime.getRuntime().availableProcessors(), bands).coerceAtLeast(1)
        val capacity = width * (BAND_ROWS + 2 * halo)

        // A band also reads [halo] rows of each neighbour, which that neighbour may already have
        // rewritten. Keep the original edge rows of every band so each one reads the untouched picture.
        val edgeRows = minOf(halo, BAND_ROWS)
        val topEdges = arrayOfNulls<IntArray>(bands)
        val bottomEdges = arrayOfNulls<IntArray>(bands)
        for (band in 0 until bands) {
            val coreStart = band * BAND_ROWS
            val coreEnd = minOf(height, coreStart + BAND_ROWS)
            val rows = minOf(edgeRows, coreEnd - coreStart)
            if (band > 0) topEdges[band] = IntArray(width * rows).also {
                bitmap.getPixels(it, 0, width, 0, coreStart, width, rows)
            }
            if (band < bands - 1) bottomEdges[band] = IntArray(width * rows).also {
                bitmap.getPixels(it, 0, width, 0, coreEnd - rows, width, rows)
            }
        }

        coroutineScope {
            (0 until workers).map {
                async {
                    val work = DetailMath.Workspace(capacity)
                    val out = IntArray(width * BAND_ROWS)
                    val pixels = IntArray(capacity)
                    while (true) {
                        val band = next.getAndIncrement()
                        if (band >= bands) break
                        val coreStart = band * BAND_ROWS
                        val coreEnd = minOf(height, coreStart + BAND_ROWS)
                        val top = maxOf(0, coreStart - halo)
                        val bottom = minOf(height, coreEnd + halo)
                        val rows = bottom - top
                        bottomEdges.getOrNull(band - 1)?.let { above ->
                            // The rows just above this band: the end of the previous band's edge.
                            val count = (coreStart - top) * width
                            above.copyInto(pixels, 0, above.size - count, above.size)
                        }
                        synchronized(bitmap) {
                            bitmap.getPixels(pixels, (coreStart - top) * width, width, 0, coreStart, width, coreEnd - coreStart)
                        }
                        topEdges.getOrNull(band + 1)?.let { below ->
                            val count = (bottom - coreEnd) * width
                            below.copyInto(pixels, (coreEnd - top) * width, 0, count)
                        }
                        DetailMath.processBand(
                            pixels = pixels,
                            w = width,
                            h = rows,
                            coreTop = coreStart - top,
                            coreBottom = coreEnd - top,
                            tuning = tuning,
                            lut = lut,
                            vibrance = params.vibrance,
                            localMean = { x, coreRow -> coarse.at(x, coreStart + coreRow) },
                            work = work,
                            out = out,
                        )
                        synchronized(bitmap) {
                            bitmap.setPixels(out, 0, width, 0, coreStart, width, coreEnd - coreStart)
                        }
                    }
                }
            }.awaitAll()
        }
        DiagnosticLog.info(
            TAG,
            "Enhanced ${width}x$height in ${SystemClock.elapsedRealtime() - started} ms " +
                "(noise %.4f, zoom %.1f, sharpen %.2f, workers $workers)".format(tuning.noise, zoom, tuning.sharpen),
        )
    }

    private const val TAG = "Enhancer"
}
