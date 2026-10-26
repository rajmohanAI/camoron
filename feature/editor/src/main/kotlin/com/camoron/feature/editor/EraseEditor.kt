package com.camoron.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Erase tab's picture: the whole upright photo, with what is chosen for removal tinted over
 * it. With the tap tool a tap picks out the object there; with the brush, a finger paints over
 * what to remove.
 */
@Composable
internal fun EraseEditor(
    picture: ImageBitmap,
    selection: ImageBitmap?,
    tool: EraseTool,
    brushRadius: Float,
    enabled: Boolean,
    onTap: (Float, Float) -> Unit,
    onStroke: (FloatArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().padding(12.dp)) {
        val density = LocalDensity.current
        val boxWidth = with(density) { maxWidth.toPx() }
        val boxHeight = with(density) { maxHeight.toPx() }
        val scale = min(boxWidth / picture.width, boxHeight / picture.height)
        val shown = Rect(
            Offset((boxWidth - picture.width * scale) / 2, (boxHeight - picture.height * scale) / 2),
            Size(picture.width * scale, picture.height * scale),
        )
        val currentShown by rememberUpdatedState(shown)
        val currentEnabled by rememberUpdatedState(enabled)
        val currentOnTap by rememberUpdatedState(onTap)
        val currentOnStroke by rememberUpdatedState(onStroke)
        // The stroke being painted, in screen pixels.
        val stroke = remember { mutableStateListOf<Offset>() }

        fun normalised(points: List<Offset>): FloatArray {
            val out = FloatArray(points.size * 2)
            points.forEachIndexed { i, p ->
                out[2 * i] = ((p.x - currentShown.left) / currentShown.width).coerceIn(0f, 1f)
                out[2 * i + 1] = ((p.y - currentShown.top) / currentShown.height).coerceIn(0f, 1f)
            }
            return out
        }

        val gestures = when (tool) {
            EraseTool.TAP -> Modifier.pointerInput(tool) {
                detectTapGestures { point ->
                    if (currentEnabled && currentShown.contains(point)) {
                        currentOnTap((point.x - currentShown.left) / currentShown.width, (point.y - currentShown.top) / currentShown.height)
                    }
                }
            }
            EraseTool.BRUSH -> Modifier
                .pointerInput(tool) {
                    detectTapGestures { point ->
                        if (currentEnabled && currentShown.contains(point)) currentOnStroke(normalised(listOf(point)))
                    }
                }
                .pointerInput(tool) {
                    detectDragGestures(
                        onDragStart = { start ->
                            stroke.clear()
                            if (currentEnabled) stroke += start
                        },
                        onDrag = { change, _ ->
                            if (stroke.isNotEmpty()) {
                                change.consume()
                                stroke += change.position
                            }
                        },
                        onDragEnd = {
                            if (stroke.isNotEmpty()) currentOnStroke(normalised(stroke.toList()))
                            stroke.clear()
                        },
                        onDragCancel = { stroke.clear() },
                    )
                }
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val offset = IntOffset(shown.left.roundToInt(), shown.top.roundToInt())
            val extent = IntSize(shown.width.roundToInt(), shown.height.roundToInt())
            drawImage(picture, dstOffset = offset, dstSize = extent, filterQuality = FilterQuality.Medium)
            selection?.let { drawImage(it, dstOffset = offset, dstSize = extent, filterQuality = FilterQuality.Low) }
            if (stroke.isNotEmpty()) {
                val path = Path().apply {
                    moveTo(stroke[0].x, stroke[0].y)
                    for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                }
                val width = 2f * brushRadius * max(shown.width, shown.height)
                drawPath(path, BRUSH_COLOUR, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** The Erase tab's controls: tap or brush, the brush size, and the button that removes the selection. */
@Composable
internal fun EraseControls(state: EditorUiState, viewModel: EditorViewModel) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        val busy = state.picking || state.erasing
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            }
            val hint = when {
                state.erasing -> R.string.erasing
                state.picking -> R.string.picking
                state.hasSelection -> R.string.erase_ready
                state.eraseTool == EraseTool.TAP -> R.string.erase_tap_hint
                else -> R.string.erase_brush_hint
            }
            Text(
                stringResource(hint),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.padding(start = if (busy) 10.dp else 0.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolToggle(Icons.Filled.TouchApp, stringResource(R.string.erase_tap), state.eraseTool == EraseTool.TAP) {
                viewModel.selectEraseTool(EraseTool.TAP)
            }
            ToolToggle(Icons.Filled.Brush, stringResource(R.string.erase_brush), state.eraseTool == EraseTool.BRUSH) {
                viewModel.selectEraseTool(EraseTool.BRUSH)
            }
            if (state.eraseTool == EraseTool.BRUSH) {
                Slider(
                    value = state.brushSize,
                    onValueChange = viewModel::setBrushSize,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = viewModel::clearSelection, enabled = state.hasSelection && !busy) {
                Text(stringResource(R.string.erase_clear))
            }
            Button(
                onClick = viewModel::eraseSelection,
                enabled = state.hasSelection && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(stringResource(R.string.erase_action))
            }
        }
    }
}

@Composable
private fun ToolToggle(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    TextButton(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
            contentColor = if (selected) accent else Color.White,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

private val BRUSH_COLOUR = Color(0x99FF3D7F)
