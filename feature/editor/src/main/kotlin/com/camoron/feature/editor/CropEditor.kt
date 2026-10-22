package com.camoron.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.camoron.domain.model.CropMath.Handle
import com.camoron.domain.model.CropRectF
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The crop screen's picture: the whole turned, straightened photo with the crop frame over it.
 * Drag a corner or edge to resize, or inside the frame to move it. Outside the frame is dimmed,
 * and a rule-of-thirds grid shows while dragging.
 */
@Composable
internal fun CropEditor(
    frame: ImageBitmap,
    rect: CropRectF,
    onDrag: (Handle, Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().padding(24.dp)) {
        val density = LocalDensity.current
        val boxWidth = with(density) { maxWidth.toPx() }
        val boxHeight = with(density) { maxHeight.toPx() }
        val scale = min(boxWidth / frame.width, boxHeight / frame.height)
        val picture = Rect(
            Offset((boxWidth - frame.width * scale) / 2, (boxHeight - frame.height * scale) / 2),
            Size(frame.width * scale, frame.height * scale),
        )
        val touch = with(density) { TOUCH_SLOP.toPx() }
        val currentRect by rememberUpdatedState(rect)
        val currentPicture by rememberUpdatedState(picture)
        val currentOnDrag by rememberUpdatedState(onDrag)
        val currentOnDragEnd by rememberUpdatedState(onDragEnd)
        var dragging by remember { mutableStateOf(false) }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var handle: Handle? = null
                    detectDragGestures(
                        onDragStart = { start ->
                            handle = hitTest(start, currentRect.toScreen(currentPicture), touch)
                            dragging = handle != null
                        },
                        onDragEnd = {
                            if (handle != null) currentOnDragEnd()
                            handle = null
                            dragging = false
                        },
                        onDragCancel = {
                            if (handle != null) currentOnDragEnd()
                            handle = null
                            dragging = false
                        },
                        onDrag = { change, amount ->
                            handle?.let {
                                change.consume()
                                currentOnDrag(it, amount.x / currentPicture.width, amount.y / currentPicture.height)
                            }
                        },
                    )
                },
        ) {
            drawImage(
                frame,
                dstOffset = IntOffset(picture.left.roundToInt(), picture.top.roundToInt()),
                dstSize = IntSize(picture.width.roundToInt(), picture.height.roundToInt()),
            )
            val crop = rect.toScreen(picture)
            val dim = Color.Black.copy(alpha = 0.6f)
            // Dim everything outside the crop.
            drawRect(dim, Offset(0f, 0f), Size(size.width, crop.top))
            drawRect(dim, Offset(0f, crop.bottom), Size(size.width, size.height - crop.bottom))
            drawRect(dim, Offset(0f, crop.top), Size(crop.left, crop.height))
            drawRect(dim, Offset(crop.right, crop.top), Size(size.width - crop.right, crop.height))

            val line = Color.White.copy(alpha = 0.9f)
            drawRect(line, crop.topLeft, crop.size, style = Stroke(width = 1.5.dp.toPx()))
            if (dragging) {
                val grid = Color.White.copy(alpha = 0.45f)
                for (i in 1..2) {
                    val x = crop.left + crop.width * i / 3
                    val y = crop.top + crop.height * i / 3
                    drawLine(grid, Offset(x, crop.top), Offset(x, crop.bottom), strokeWidth = 1.dp.toPx())
                    drawLine(grid, Offset(crop.left, y), Offset(crop.right, y), strokeWidth = 1.dp.toPx())
                }
            }
            // Corner brackets: the parts to grab.
            val arm = 18.dp.toPx()
            val thick = 3.dp.toPx()
            for ((corner, dirX, dirY) in listOf(
                Triple(crop.topLeft, 1f, 1f),
                Triple(crop.topRight, -1f, 1f),
                Triple(crop.bottomLeft, 1f, -1f),
                Triple(crop.bottomRight, -1f, -1f),
            )) {
                drawLine(Color.White, corner, corner + Offset(arm * dirX, 0f), strokeWidth = thick)
                drawLine(Color.White, corner, corner + Offset(0f, arm * dirY), strokeWidth = thick)
            }
        }
    }
}

private fun CropRectF.toScreen(picture: Rect) = Rect(
    picture.left + left * picture.width,
    picture.top + top * picture.height,
    picture.left + right * picture.width,
    picture.top + bottom * picture.height,
)

/** Which part of the crop frame a touch at [point] grabs, or null if it misses the frame. */
private fun hitTest(point: Offset, crop: Rect, slop: Float): Handle? {
    val nearLeft = abs(point.x - crop.left) < slop
    val nearRight = abs(point.x - crop.right) < slop
    val nearTop = abs(point.y - crop.top) < slop
    val nearBottom = abs(point.y - crop.bottom) < slop
    val withinX = point.x > crop.left - slop && point.x < crop.right + slop
    val withinY = point.y > crop.top - slop && point.y < crop.bottom + slop
    return when {
        nearLeft && nearTop -> Handle.TOP_LEFT
        nearRight && nearTop -> Handle.TOP_RIGHT
        nearLeft && nearBottom -> Handle.BOTTOM_LEFT
        nearRight && nearBottom -> Handle.BOTTOM_RIGHT
        nearLeft && withinY -> Handle.LEFT
        nearRight && withinY -> Handle.RIGHT
        nearTop && withinX -> Handle.TOP
        nearBottom && withinX -> Handle.BOTTOM
        crop.contains(point) -> Handle.MOVE
        else -> null
    }
}

private val TOUCH_SLOP = 28.dp
