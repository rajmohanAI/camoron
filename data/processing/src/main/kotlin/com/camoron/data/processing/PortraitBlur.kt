package com.camoron.data.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.common.timed
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Portrait: keeps the subject of a photo sharp and blurs what is behind it, as a wide-aperture
 * lens would. An on-device model (MediaPipe's selfie multiclass segmenter, Apache 2.0) finds
 * people; when there are none, ML Kit's subject segmenter finds the main object instead (a
 * bottle, a cup, a pet). [PortraitMath] snaps the mask to the photo's edges and blurs the
 * background without the subject's colours bleeding into it.
 */
@Singleton
class PortraitBlur @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var interpreter: Interpreter? = null
    private var failed = false
    private var subjectSegmenter: SubjectSegmenter? = null
    private var subjectsFailed = false
    private var previewFailed = false

    /**
     * Blurs the background of [bitmap] (mutable ARGB_8888) in place. [rotationDegrees] turns its
     * pixels upright, as the model expects people the right way up. Returns false, leaving the
     * photo as it was, when nobody is in it or the model couldn't run. [strength] scales the blur:
     * 1 is about an f/1.8 lens, 2 twice as soft, 0.25 barely softened. Blocking.
     */
    @Synchronized
    fun blur(bitmap: Bitmap, rotationDegrees: Int, strength: Float = 1f): Boolean {
        if (failed) return false
        return try {
            DiagnosticLog.timed(TAG, "Portrait blur of ${bitmap.width}x${bitmap.height}") { run(bitmap, rotationDegrees, strength) }
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

    /**
     * The live Portrait look for the viewfinder: [pixels] ([width]×[height] ARGB, already upright)
     * come back as an overlay that holds the blurred background, with the subject left
     * transparent so the sharp preview shows through it. Null when there is nothing to keep sharp
     * or the models can't run. Blocking; meant for small frames (a few hundred pixels across).
     */
    @Synchronized
    fun preview(pixels: IntArray, width: Int, height: Int, strength: Float): IntArray? {
        if (failed || previewFailed || width <= 0 || height <= 0 || pixels.size < width * height) return null
        return try {
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            val probability = keepMask(bitmap, 0, quiet = true)
            bitmap.recycle()
            if (probability == null) return null
            val guide = FloatArray(width * height)
            val planes = Array(3) { FloatArray(width * height) }
            split(pixels, guide, planes)
            val mask = PortraitMath.refineMask(probability, MODEL_SIZE, guide, width, height)
            val radius = max(1, (max(width, height) * BLUR_RADIUS * strength).roundToInt())
            val blurred = PortraitMath.blurBackground(planes, mask, width, height, radius)
            IntArray(width * height) { i ->
                val alpha = ((1f - mask[i].coerceIn(0f, 1f)) * 255f).roundToInt()
                (alpha shl 24) or
                    (PortraitMath.toSrgb(blurred[0][i]) shl 16) or
                    (PortraitMath.toSrgb(blurred[1][i]) shl 8) or
                    PortraitMath.toSrgb(blurred[2][i])
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            // Only the live look is lost; photos still get their blur.
            DiagnosticLog.record(TAG, "Portrait preview failed; turning the live blur off", e)
            previewFailed = true
            null
        }
    }

    /**
     * What to keep sharp, on a [MODEL_SIZE]² grid laid over [bitmap] as stored: the people if
     * there are any, otherwise the main object. Null when neither finds anything.
     */
    private fun keepMask(bitmap: Bitmap, rotationDegrees: Int, quiet: Boolean = false): FloatArray? {
        val people = segment(bitmap, rotationDegrees)
        val peopleCoverage = PortraitMath.coverage(people)
        if (peopleCoverage >= MIN_COVERAGE) return people
        val objects = subjects(bitmap, rotationDegrees)
        val objectCoverage = objects?.let { PortraitMath.coverage(it) } ?: 0f
        if (objects != null && objectCoverage >= MIN_COVERAGE) {
            if (!quiet) DiagnosticLog.info(TAG, "No people; keeping the main object sharp (%.1f%% of the frame)".format(objectCoverage * 100))
            return objects
        }
        if (!quiet) DiagnosticLog.info(TAG, "Nothing to keep sharp for portrait (%.1f%% of the frame); kept as taken".format(max(peopleCoverage, objectCoverage) * 100))
        return null
    }

    /**
     * ML Kit's foreground confidence for [bitmap], on the same grid as [segment]. Null if the
     * segmenter isn't available (its model is fetched by Play services) or fails; that only turns
     * off the object fallback, never Portrait for people.
     */
    private fun subjects(bitmap: Bitmap, rotationDegrees: Int): FloatArray? {
        if (subjectsFailed) return null
        return try {
            val upright = uprightSquare(bitmap, rotationDegrees)
            val client = subjectSegmenter ?: SubjectSegmentation.getClient(
                SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
            ).also { subjectSegmenter = it }
            val result = Tasks.await(client.process(InputImage.fromBitmap(upright, 0)), SUBJECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            upright.recycle()
            val buffer = result.foregroundConfidenceMask ?: return null
            buffer.rewind()
            val grid = FloatArray(MODEL_SIZE * MODEL_SIZE)
            if (buffer.remaining() < grid.size) return null
            buffer.get(grid)
            PortraitMath.rotateSquare(grid, MODEL_SIZE, -rotationDegrees)
        } catch (e: Exception) {
            DiagnosticLog.record(TAG, "Object finder unavailable; Portrait keeps people only", e)
            subjectsFailed = true
            null
        }
    }

    private fun run(bitmap: Bitmap, rotationDegrees: Int, strength: Float): Boolean {
        val probability = keepMask(bitmap, rotationDegrees) ?: return false
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
        split(pixels, guide, planes)
        val mask = PortraitMath.refineMask(probability, MODEL_SIZE, guide, ww, wh)
        val radius = max(1, (max(ww, wh) * BLUR_RADIUS * strength).roundToInt())
        val blurred = PortraitMath.blurBackground(planes, mask, ww, wh, radius)
        composite(bitmap, mask, blurred, ww, wh)
        return true
    }

    /** Brightness guide and linear colour planes of [pixels], for the mask and the blur. */
    private fun split(pixels: IntArray, guide: FloatArray, planes: Array<FloatArray>) {
        for (i in guide.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            guide[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            planes[0][i] = PortraitMath.toLinear(r)
            planes[1][i] = PortraitMath.toLinear(g)
            planes[2][i] = PortraitMath.toLinear(b)
        }
    }

    /** [bitmap] squeezed to a new [MODEL_SIZE]² bitmap and turned upright. */
    private fun uprightSquare(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(bitmap, MODEL_SIZE, MODEL_SIZE, true)
        val upright = if (rotationDegrees % 360 == 0) {
            if (scaled === bitmap) scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
        } else {
            Bitmap.createBitmap(scaled, 0, 0, MODEL_SIZE, MODEL_SIZE, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
        }
        if (upright !== scaled && scaled !== bitmap) scaled.recycle()
        return upright
    }

    /** The model's person probability on a [MODEL_SIZE]² grid laid over [bitmap] as it is stored. */
    private fun segment(bitmap: Bitmap, rotationDegrees: Int): FloatArray {
        val upright = uprightSquare(bitmap, rotationDegrees)
        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        upright.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        upright.recycle()

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
        const val SUBJECT_TIMEOUT_SECONDS = 5L
        /** Mask values from here up count as fully sharp, and the pixel is left untouched. */
        const val KEEP = 0.998f
    }
}
