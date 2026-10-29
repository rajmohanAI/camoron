package com.camoron.data.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.common.timed
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Portrait: keeps the people in a photo sharp and blurs what is behind them, as a wide-aperture
 * lens would. An on-device model (MediaPipe's selfie multiclass segmenter, Apache 2.0) finds the
 * people; [PortraitMath] snaps its mask to the photo's edges and blurs the background without
 * the person's colours bleeding into it.
 */
@Singleton
class PortraitBlur @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var interpreter: Interpreter? = null
    private var failed = false

    /**
     * Blurs the background of [bitmap] (mutable ARGB_8888) in place. [rotationDegrees] turns its
     * pixels upright, as the model expects people the right way up. Returns false, leaving the
     * photo as it was, when nobody is in it or the model couldn't run. Blocking.
     */
    @Synchronized
    fun blur(bitmap: Bitmap, rotationDegrees: Int): Boolean {
        if (failed) return false
        return try {
            DiagnosticLog.timed(TAG, "Portrait blur of ${bitmap.width}x${bitmap.height}") { run(bitmap, rotationDegrees) }
        } catch (e: OutOfMemoryError) {
            DiagnosticLog.record(TAG, "Not enough memory for the portrait blur", e)
            false
        } catch (e: Exception) {
            // A model problem must never cost the photo; stop trying for this session.
            DiagnosticLog.record(TAG, "Portrait blur failed; turning it off", e)
            failed = true
            runCatching { interpreter?.close() }
            interpreter = null
            false
        }
    }

    private fun run(bitmap: Bitmap, rotationDegrees: Int): Boolean {
        val probability = segment(bitmap, rotationDegrees)
        val coverage = PortraitMath.coverage(probability)
        if (coverage < MIN_COVERAGE) {
            DiagnosticLog.info(TAG, "Nobody found for portrait (%.1f%% of the frame); kept as taken".format(coverage * 100))
            return false
        }
        val width = bitmap.width
        val height = bitmap.height
        val scale = WORK_SIZE.toFloat() / max(width, height)
        val ww = max(1, (width * scale).roundToInt())
        val wh = max(1, (height * scale).roundToInt())
        val work = Bitmap.createScaledBitmap(bitmap, ww, wh, true)
        val pixels = IntArray(ww * wh)
        work.getPixels(pixels, 0, ww, 0, 0, ww, wh)
        if (work !== bitmap) work.recycle()

        val guide = FloatArray(ww * wh)
        val planes = Array(3) { FloatArray(ww * wh) }
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            guide[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            planes[0][i] = PortraitMath.toLinear(r)
            planes[1][i] = PortraitMath.toLinear(g)
            planes[2][i] = PortraitMath.toLinear(b)
        }
        val mask = PortraitMath.refineMask(probability, MODEL_SIZE, guide, ww, wh)
        val radius = max(1, (max(ww, wh) * BLUR_RADIUS).roundToInt())
        val blurred = PortraitMath.blurBackground(planes, mask, ww, wh, radius)
        composite(bitmap, mask, blurred, ww, wh)
        return true
    }

    /** The model's person probability on a [MODEL_SIZE]² grid laid over [bitmap] as it is stored. */
    private fun segment(bitmap: Bitmap, rotationDegrees: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, MODEL_SIZE, MODEL_SIZE, true)
        val upright = if (rotationDegrees % 360 == 0) {
            scaled
        } else {
            Bitmap.createBitmap(scaled, 0, 0, MODEL_SIZE, MODEL_SIZE, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
        }
        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        upright.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        if (upright !== scaled) upright.recycle()
        if (scaled !== bitmap) scaled.recycle()

        val input = ByteBuffer.allocateDirect(pixels.size * 3 * 4).order(ByteOrder.nativeOrder())
        for (c in pixels) {
            input.putFloat(((c shr 16) and 0xFF) / 127.5f - 1f)
            input.putFloat(((c shr 8) and 0xFF) / 127.5f - 1f)
            input.putFloat((c and 0xFF) / 127.5f - 1f)
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(pixels.size * CLASSES * 4).order(ByteOrder.nativeOrder())
        interpreter().run(input, output)
        output.rewind()
        val logits = FloatArray(pixels.size * CLASSES)
        output.asFloatBuffer().get(logits)
        val person = PortraitMath.personProbability(logits, pixels.size, CLASSES)
        // Back from upright to the way the pixels are stored.
        return PortraitMath.rotateSquare(person, MODEL_SIZE, -rotationDegrees)
    }

    /** Lays the blurred background under the sharp person at full resolution, row by row. */
    private fun composite(bitmap: Bitmap, mask: FloatArray, blurred: Array<FloatArray>, ww: Int, wh: Int) {
        val width = bitmap.width
        val height = bitmap.height
        // Where each output column falls in the working image.
        val sx = FloatArray(width) { ((it + 0.5f) * ww / width - 0.5f).coerceIn(0f, (ww - 1).toFloat()) }
        val row = IntArray(width)
        for (y in 0 until height) {
            val wy = (y + 0.5f) * wh / height - 0.5f
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var changed = false
            for (x in 0 until width) {
                val m = PortraitMath.bilinear(mask, ww, wh, sx[x], wy)
                if (m >= KEEP) continue
                val c = row[x]
                val keep = m.coerceAtLeast(0f)
                val r = PortraitMath.toLinear((c shr 16) and 0xFF) * keep + PortraitMath.bilinear(blurred[0], ww, wh, sx[x], wy) * (1 - keep)
                val g = PortraitMath.toLinear((c shr 8) and 0xFF) * keep + PortraitMath.bilinear(blurred[1], ww, wh, sx[x], wy) * (1 - keep)
                val b = PortraitMath.toLinear(c and 0xFF) * keep + PortraitMath.bilinear(blurred[2], ww, wh, sx[x], wy) * (1 - keep)
                row[x] = (c and 0xFF000000.toInt()) or (PortraitMath.toSrgb(r) shl 16) or (PortraitMath.toSrgb(g) shl 8) or PortraitMath.toSrgb(b)
                changed = true
            }
            if (changed) bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }

    private fun interpreter(): Interpreter = interpreter ?: run {
        val model = context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        Interpreter(model, Interpreter.Options().setNumThreads(CPU_THREADS)).also { interpreter = it }
    }

    private companion object {
        const val TAG = "PortraitBlur"
        const val MODEL = "selfie_multiclass.tflite"
        const val MODEL_SIZE = 256
        const val CLASSES = 6
        const val CPU_THREADS = 4
        /** Long side of the image the mask and blur are worked out on. */
        const val WORK_SIZE = 1024
        /** Blur radius as a fraction of the photo's long side, roughly an f/1.8 portrait lens. */
        const val BLUR_RADIUS = 0.012f
        const val MIN_COVERAGE = 0.01f
        /** Mask values from here up count as fully sharp, and the pixel is left untouched. */
        const val KEEP = 0.998f
    }
}
