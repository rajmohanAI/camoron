package com.camoron.data.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.common.timed
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sharper digital zoom: when a photo was taken zoomed in past the lens, the camera has enlarged a
 * small part of the sensor and the photo is soft. This runs an on-device AI upscaler (Real-ESRGAN
 * general, BSD-3 licence) over the detail the sensor really captured and lays its crisper result
 * over the photo, mostly but not entirely, so natural texture survives. Runs on the GPU where the
 * phone supports it, otherwise on the CPU.
 */
@Singleton
class ZoomEnhancer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var interpreter: Interpreter? = null
    private var gpu: GpuDelegate? = null
    private var failed = false

    /** Whether a photo taken at [zoom] (digital zoom on its lens) is worth enhancing. */
    fun worthwhile(zoom: Float): Boolean = zoom >= MIN_ZOOM && !failed

    /**
     * Sharpens [bitmap] (mutable ARGB_8888, the whole photo) in place, for a photo taken at [zoom].
     * Returns false, leaving it as it was, if the model couldn't run. Blocking.
     */
    @Synchronized
    fun enhance(bitmap: Bitmap, zoom: Float): Boolean {
        if (!worthwhile(zoom)) return false
        return try {
            DiagnosticLog.timed(TAG, "AI zoom enhance of ${bitmap.width}x${bitmap.height} at ${"%.1f".format(zoom)}x") {
                run(interpreter(), bitmap, zoom)
            }
            true
        } catch (e: OutOfMemoryError) {
            DiagnosticLog.record(TAG, "Not enough memory for AI zoom enhance", e)
            false
        } catch (e: Exception) {
            // A model problem must never cost the photo; stop trying for this session.
            DiagnosticLog.record(TAG, "AI zoom enhance failed; turning it off", e)
            failed = true
            close()
            false
        }
    }

    private fun run(model: Interpreter, bitmap: Bitmap, zoom: Float) {
        val width = bitmap.width
        val height = bitmap.height
        // The camera enlarged about 1/zoom of the sensor to this size, so shrinking back by the
        // zoom gives the detail it really captured, which the model then enlarges 4x.
        var shrink = minOf(zoom, MAX_SHRINK)
        val tilesAt = { s: Float -> ceil(width / s / STRIDE) * ceil(height / s / STRIDE) }
        if (tilesAt(shrink) > MAX_TILES) shrink *= sqrt(tilesAt(shrink) / MAX_TILES)
        val sw = max(TILE, (width / shrink).toInt())
        val sh = max(TILE, (height / shrink).toInt())
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val xs = starts(sw)
        val ys = starts(sh)
        val cutsX = cuts(xs, sw)
        val cutsY = cuts(ys, sh)

        val input = ByteBuffer.allocateDirect(4 * TILE * TILE * 3).order(ByteOrder.nativeOrder())
        val output = ByteBuffer.allocateDirect(4 * OUT * OUT * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small !== bitmap) small.recycle()
        val tileOut = IntArray(OUT * OUT)
        val tile = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (STRENGTH * 255).toInt() }
        val scaleX = width.toFloat() / sw
        val scaleY = height.toFloat() / sh
        try {
            for ((j, ty) in ys.withIndex()) {
                for ((i, tx) in xs.withIndex()) {
                    input.rewind()
                    val floats = input.asFloatBuffer()
                    for (y in 0 until TILE) {
                        val sy = minOf(sh - 1, ty + y)
                        for (x in 0 until TILE) {
                            val p = pixels[sy * sw + minOf(sw - 1, tx + x)]
                            floats.put(((p shr 16) and 0xFF) / 255f)
                            floats.put(((p shr 8) and 0xFF) / 255f)
                            floats.put((p and 0xFF) / 255f)
                        }
                    }
                    output.rewind()
                    model.run(input, output)
                    output.rewind()
                    val result = output.asFloatBuffer()
                    for (k in 0 until OUT * OUT) {
                        val r = channel(result.get())
                        val g = channel(result.get())
                        val b = channel(result.get())
                        tileOut[k] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    tile.setPixels(tileOut, 0, OUT, 0, 0, OUT, OUT)
                    // Only this tile's own part (overlaps are split half way), placed at full size.
                    val left = cutsX[i]
                    val right = cutsX[i + 1]
                    val top = cutsY[j]
                    val bottom = cutsY[j + 1]
                    val src = Rect((left - tx) * SCALE, (top - ty) * SCALE, (right - tx) * SCALE, (bottom - ty) * SCALE)
                    val dst = RectF(left * scaleX, top * scaleY, right * scaleX, bottom * scaleY)
                    canvas.drawBitmap(tile, src, dst, paint)
                }
            }
        } finally {
            tile.recycle()
        }
    }

    /** Tile starts along a side of [size] pixels: every [STRIDE], the last one flush with the end. */
    private fun starts(size: Int): List<Int> {
        val out = mutableListOf<Int>()
        var p = 0
        while (p + TILE < size) {
            out += p
            p += STRIDE
        }
        out += max(0, size - TILE)
        return out.distinct()
    }

    /** Where each tile's own part begins: half way through its overlap with the one before. */
    private fun cuts(starts: List<Int>, size: Int): IntArray {
        val out = IntArray(starts.size + 1)
        for (i in 1 until starts.size) out[i] = (starts[i] + starts[i - 1] + TILE) / 2
        out[starts.size] = size
        return out
    }

    private fun channel(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun interpreter(): Interpreter = interpreter ?: run {
        val model = context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        val options = Interpreter.Options()
        val compatibility = CompatibilityList()
        if (compatibility.isDelegateSupportedOnThisDevice) {
            gpu = GpuDelegate(compatibility.bestOptionsForThisDevice).also { options.addDelegate(it) }
        } else {
            options.setNumThreads(CPU_THREADS)
        }
        compatibility.close()
        DiagnosticLog.record(TAG, "AI zoom enhance runs on the ${if (gpu != null) "GPU" else "CPU"}")
        Interpreter(model, options).also { interpreter = it }
    }

    @Synchronized
    fun close() {
        runCatching { interpreter?.close() }
        runCatching { gpu?.close() }
        interpreter = null
        gpu = null
    }

    private companion object {
        const val TAG = "ZoomEnhancer"
        const val MODEL = "zoom_enhance.tflite"
        const val TILE = 128
        const val SCALE = 4
        const val OUT = TILE * SCALE
        const val STRIDE = 112
        const val MIN_ZOOM = 2f
        const val MAX_SHRINK = 4f

        /** Keeps the work to a few seconds on a mid-range phone. */
        const val MAX_TILES = 60f

        /** How much of the AI result shows over the photo. */
        const val STRENGTH = 0.8f
        const val CPU_THREADS = 4
    }
}
