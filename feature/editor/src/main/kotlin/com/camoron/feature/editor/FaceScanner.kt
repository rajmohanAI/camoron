package com.camoron.feature.editor

import android.content.Context
import android.graphics.Bitmap
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.common.timed
import com.camoron.domain.model.FaceScene
import com.camoron.domain.model.FaceShape
import com.camoron.domain.model.HairMask
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteOrder
import javax.inject.Inject

/** Finds the faces and hair in a photo for the try-ons. */
interface SceneScanner {
    /** Faces and hair in [bitmap] (upright, ARGB_8888). Blocking; call off the main thread. */
    fun scan(bitmap: Bitmap): FaceScene

    /** Releases the models. */
    fun close()
}

/**
 * Finds faces (478 points each) and hair in a photo with Google's MediaPipe models, entirely on
 * the phone. The models load on first use and are released with [close].
 */
class FaceScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) : SceneScanner {
    private var landmarker: FaceLandmarker? = null
    private var segmenter: ImageSegmenter? = null

    @Synchronized
    override fun scan(bitmap: Bitmap): FaceScene {
        val image = BitmapImageBuilder(bitmap).build()
        val faces = runCatching {
            DiagnosticLog.timed(TAG, "Finding faces in ${bitmap.width}x${bitmap.height}") {
                faceLandmarker().detect(image).faceLandmarks().map { points ->
                    val xy = FloatArray(points.size * 2)
                    points.forEachIndexed { i, p ->
                        xy[2 * i] = p.x()
                        xy[2 * i + 1] = p.y()
                    }
                    FaceShape(xy)
                }
            }
        }.onFailure { DiagnosticLog.record(TAG, "Face tracking failed", it) }.getOrDefault(emptyList())

        val hair = runCatching {
            DiagnosticLog.timed(TAG, "Finding hair") {
                val masks = hairSegmenter().segment(image).confidenceMasks()
                if (!masks.isPresent || masks.get().size < 2) return@timed null
                // Category 1 is hair; 0 is everything else.
                val mask = masks.get()[1]
                val floats = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val alpha = ByteArray(mask.width * mask.height) { i ->
                    (floats.get(i).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                }
                HairMask(mask.width, mask.height, alpha)
            }
        }.onFailure { DiagnosticLog.record(TAG, "Hair finding failed", it) }.getOrNull()

        return FaceScene(faces, hair)
    }

    @Synchronized
    override fun close() {
        runCatching { landmarker?.close() }
        runCatching { segmenter?.close() }
        landmarker = null
        segmenter = null
    }

    private fun faceLandmarker(): FaceLandmarker = landmarker ?: FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACE_MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(MAX_FACES)
            .build(),
    ).also { landmarker = it }

    private fun hairSegmenter(): ImageSegmenter = segmenter ?: ImageSegmenter.createFromOptions(
        context,
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(HAIR_MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build(),
    ).also { segmenter = it }

    private companion object {
        const val TAG = "FaceScanner"
        const val FACE_MODEL = "face_landmarker.task"
        const val HAIR_MODEL = "hair_segmenter.tflite"
        const val MAX_FACES = 6
    }
}
