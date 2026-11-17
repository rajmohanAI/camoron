package com.camoron.app.optimise

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.camoron.engine.PixelSource
import java.io.Closeable
import java.io.File

/**
 * A photo file the engine reads a block at a time. JPEG, PNG, WebP and HEIF are decoded region by
 * region, so even a 50 MP photo is never held whole; anything else is decoded once.
 */
internal sealed class PhotoFileSource : PixelSource, Closeable {

    /** Reads blocks straight from the file; [sample] 2 halves each side of a very large photo. */
    private class Regions(private val decoder: BitmapRegionDecoder, private val sample: Int) : PhotoFileSource() {
        override val width = decoder.width / sample
        override val height = decoder.height / sample

        override fun read(x: Int, y: Int, w: Int, h: Int): IntArray {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val rect = Rect(x * sample, y * sample, (x + w) * sample, (y + h) * sample)
            val block = decoder.decodeRegion(rect, options) ?: error("Could not read part of the photo")
            return try {
                copyOut(block, w, h)
            } finally {
                block.recycle()
            }
        }

        override fun close() = decoder.recycle()
    }

    /** A photo decoded whole, for formats the region decoder does not read. */
    private class Whole(private val bitmap: Bitmap) : PhotoFileSource() {
        override val width = bitmap.width
        override val height = bitmap.height

        override fun read(x: Int, y: Int, w: Int, h: Int): IntArray {
            val out = IntArray(w * h)
            bitmap.getPixels(out, 0, w, x, y, w, h)
            return out
        }

        override fun close() = bitmap.recycle()
    }

    companion object {
        /** Photos above this are read at half size, so the result still fits in memory. */
        private const val MAX_PIXELS = 64_000_000L

        fun open(file: File): PhotoFileSource {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val pixels = bounds.outWidth.toLong() * bounds.outHeight
            val sample = if (pixels > MAX_PIXELS) 2 else 1

            val decoder = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BitmapRegionDecoder.newInstance(file.path)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(file.path, false)
                }
            }.getOrNull()
            if (decoder != null) return Regions(decoder, sample)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.path, options) ?: error("Not a photo this phone can read")
            return Whole(bitmap)
        }

        /** The [w]×[h] pixels of [block], repeating its last row and column if it came back short. */
        private fun copyOut(block: Bitmap, w: Int, h: Int): IntArray {
            val bw = minOf(block.width, w)
            val bh = minOf(block.height, h)
            val out = IntArray(w * h)
            block.getPixels(out, 0, w, 0, 0, bw, bh)
            if (bw < w || bh < h) {
                for (row in 0 until h) {
                    val from = minOf(row, bh - 1) * w
                    if (row >= bh) System.arraycopy(out, from, out, row * w, bw)
                    for (col in bw until w) out[row * w + col] = out[row * w + bw - 1]
                }
            }
            return out
        }
    }
}
