package com.camoron.feature.editor

import android.content.Context
import android.graphics.Bitmap
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.common.timed
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteOrder
import javax.inject.Inject

/** An object picked out of a photo: [selected] is true for each of its [width] x [height] pixels. */
class PickedObject(val width: Int, val height: Int, val selected: BooleanArray)

/** Picks out the object at a point of a photo, for the magic eraser. */
interface ObjectPicker {
    /** The object at ([x], [y]), 0–1 across [bitmap] (upright, ARGB_8888), or null. Blocking. */
    fun pick(bitmap: Bitmap, x: Float, y: Float): PickedObject?

    /** Releases the model. */
    fun close()
}

/**
 * Picks out the object under a tap with Google's MediaPipe interactive segmenter (Magic Touch),
 * on the phone. The model loads on first use and is released with [close].
 */
class MagicTouchPicker @Inject constructor(
    @ApplicationContext private val context: Context,
) : ObjectPicker {
    private var segmenter: InteractiveSegmenter? = null

    @Synchronized
    override fun pick(bitmap: Bitmap, x: Float, y: Float): PickedObject? = runCatching {
        DiagnosticLog.timed(TAG, "Picking the object at $x,$y") {
            val roi = InteractiveSegmenter.RegionOfInterest.create(NormalizedKeypoint.create(x, y))
            val masks = segmenter().segment(BitmapImageBuilder(bitmap).build(), roi).confidenceMasks()
            if (!masks.isPresent || masks.get().isEmpty()) return@timed null
            // A single mask: how sure the model is that each pixel belongs to the object.
            val mask = masks.get()[0]
            val floats = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val selected = BooleanArray(mask.width * mask.height) { floats.get(it) > THRESHOLD }
            if (selected.none { it }) null else PickedObject(mask.width, mask.height, selected)
        }
    }.onFailure { DiagnosticLog.record(TAG, "Picking failed", it) }.getOrNull()

    @Synchronized
    override fun close() {
        runCatching { segmenter?.close() }
        segmenter = null
    }

    private fun segmenter(): InteractiveSegmenter = segmenter ?: InteractiveSegmenter.createFromOptions(
        context,
        InteractiveSegmenter.InteractiveSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build(),
    ).also { segmenter = it }

    private companion object {
        const val TAG = "ObjectPicker"
        const val MODEL = "magic_touch.tflite"
        const val THRESHOLD = 0.5f
    }
}
