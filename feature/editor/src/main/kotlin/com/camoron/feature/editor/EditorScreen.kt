package com.camoron.feature.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Brightness5
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camoron.domain.model.Adjustment
import com.camoron.domain.model.CropAspect
import com.camoron.domain.model.CropMath
import com.camoron.domain.model.EditLook
import java.util.Locale
import kotlin.math.roundToInt

/** The photo editor: looks, adjustments, crop, magic eraser and try-ons, saved as a new copy beside the original. */
@Composable
fun EditorScreen(
    uri: String,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(uri) { viewModel.open(uri) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDiscard by remember { mutableStateOf(false) }

    val close: () -> Unit = {
        val ask = state.hasChanges && !state.saving
        if (ask) confirmDiscard = true
        if (!ask) onClose()
    }
    BackHandler(enabled = !state.saving) { close() }

    state.savedUri?.let { saved ->
        LaunchedEffect(saved) {
            Toast.makeText(context, R.string.saved_copy, Toast.LENGTH_SHORT).show()
            onSaved(saved)
        }
    }
    if (state.pickMissed) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, R.string.pick_missed, Toast.LENGTH_SHORT).show()
            viewModel.pickMissShown()
        }
    }
    if (state.saveFailed) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, R.string.save_failed, Toast.LENGTH_LONG).show()
            viewModel.saveFailureShown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                canSave = state.hasChanges && !state.saving && !state.loading,
                onClose = close,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onSave = viewModel::save,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.failed -> Text(
                        stringResource(R.string.open_failed),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                    state.tab == EditorTab.CROP -> state.frame?.let { frame ->
                        CropEditor(
                            frame = frame,
                            rect = state.edit.crop.rect,
                            onDrag = viewModel::dragCrop,
                            onDragEnd = viewModel::commit,
                        )
                    } ?: CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    state.tab == EditorTab.ERASE -> state.eraseView?.let { picture ->
                        EraseEditor(
                            picture = picture,
                            selection = state.selection,
                            tool = state.eraseTool,
                            brushRadius = brushRadius(state.brushSize),
                            enabled = !state.picking && !state.erasing,
                            onTap = viewModel::pickAt,
                            onStroke = viewModel::brush,
                        )
                    } ?: CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    state.preview != null -> ComparablePhoto(state)
                    else -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                // A try-on being drawn: a small spinner, so a tap never looks ignored.
                if (state.dressing && state.preview != null) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.TopEnd).padding(12.dp).size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
            }
            if (!state.failed) {
                Box(Modifier.fillMaxWidth().height(CONTROLS_HEIGHT)) {
                    when (state.tab) {
                        EditorTab.LOOKS -> LooksControls(state, viewModel)
                        EditorTab.ADJUST -> AdjustControls(state, viewModel)
                        EditorTab.CROP -> CropControls(state, viewModel)
                        EditorTab.ERASE -> EraseControls(state, viewModel)
                        EditorTab.TRY_ON -> TryOnControls(state, viewModel)
                    }
                }
                Tabs(selected = state.tab, onSelect = viewModel::selectTab)
            }
        }
        if (state.saving) SavingOverlay()
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    }
}

@Composable
private fun TopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canSave: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, stringResource(R.string.cd_close_editor), tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.cd_undo), tint = Color.White.copy(alpha = if (canUndo) 1f else 0.35f))
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.cd_redo), tint = Color.White.copy(alpha = if (canRedo) 1f else 0.35f))
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSave,
            enabled = canSave,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.save_copy), fontSize = 15.sp) }
        Spacer(Modifier.width(8.dp))
    }
}

/** The edited photo; holding a finger on it shows the original for comparison. */
@Composable
private fun ComparablePhoto(state: EditorUiState) {
    var holding by remember { mutableStateOf(false) }
    val shown = if (holding) state.original ?: state.preview else state.preview
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        shown?.let {
            Image(
                it,
                contentDescription = stringResource(R.string.cd_photo),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            holding = true
                            tryAwaitRelease()
                            holding = false
                        })
                    },
            )
        }
        if (state.edit.changesColour) {
            Text(
                stringResource(R.string.hold_to_compare),
                color = Color.White.copy(alpha = if (holding) 0f else 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun LooksControls(state: EditorUiState, viewModel: EditorViewModel) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        if (state.edit.look != EditLook.ORIGINAL) {
            ValueSlider(
                label = stringResource(R.string.strength),
                value = state.edit.lookStrength,
                range = 0f..1f,
                valueText = "${(state.edit.lookStrength * 100).roundToInt()}",
                onChange = viewModel::setLookStrength,
                onDone = viewModel::commit,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            items(EditLook.entries) { look ->
                val selected = look == state.edit.look
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { viewModel.selectLook(look) },
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                if (selected) 2.dp else 0.dp,
                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(10.dp),
                            )
                            .background(Color.White.copy(alpha = 0.08f)),
                    ) {
                        state.lookThumbnails[look]?.let {
                            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Text(
                        stringResource(look.label()),
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AdjustControls(state: EditorUiState, viewModel: EditorViewModel) {
    val tool = state.tool
    val value = state.edit.value(tool)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        ValueSlider(
            label = stringResource(tool.label()),
            value = value,
            range = tool.min..1f,
            valueText = signed((value * 100).roundToInt()),
            onChange = { viewModel.setAdjustment(tool, it) },
            onDone = viewModel::commit,
            onReset = if (value != 0f) ({ viewModel.resetAdjustment(tool) }) else null,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            items(Adjustment.entries) { adjustment ->
                ToolButton(
                    icon = adjustment.icon(),
                    label = stringResource(adjustment.label()),
                    selected = adjustment == tool,
                    changed = state.edit.value(adjustment) != 0f,
                    onClick = { viewModel.selectTool(adjustment) },
                )
            }
        }
    }
}

@Composable
private fun CropControls(state: EditorUiState, viewModel: EditorViewModel) {
    val crop = state.edit.crop
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        ValueSlider(
            label = stringResource(R.string.straighten),
            value = crop.straighten,
            range = -CropMath.MAX_STRAIGHTEN..CropMath.MAX_STRAIGHTEN,
            valueText = stringResource(R.string.degrees, String.format(Locale.getDefault(), "%.1f", crop.straighten)),
            onChange = viewModel::setStraighten,
            onDone = viewModel::commit,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToolButton(Icons.Filled.Rotate90DegreesCw, stringResource(R.string.rotate), selected = false, changed = false, onClick = viewModel::turn)
            ToolButton(Icons.Filled.Flip, stringResource(R.string.flip), selected = false, changed = false, onClick = viewModel::flip)
            ToolButton(Icons.Filled.RestartAlt, stringResource(R.string.reset), selected = false, changed = false, onClick = viewModel::resetCrop)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            items(CropAspect.entries) { aspect ->
                val selected = aspect == state.aspect
                Text(
                    stringResource(aspect.label()),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f))
                        .clickable { viewModel.setAspect(aspect) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp)) {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (onReset != null) {
                Text(
                    stringResource(R.string.reset),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onReset).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Text(valueText, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, changed: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (selected) accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f))
                .border(if (changed) 1.5.dp else 0.dp, if (changed) accent else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) accent else Color.White, modifier = Modifier.size(22.dp))
        }
        Text(label, color = if (selected) accent else Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Tabs(selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (tab in EditorTab.entries) {
            val on = tab == selected
            val color = if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(tab.icon(), contentDescription = null, tint = color)
                Text(stringResource(tab.label()), color = color, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SavingOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // Swallow touches so nothing changes mid-save.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(56.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(R.string.saving_copy), color = Color.White, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

private fun signed(value: Int) = if (value > 0) "+$value" else "$value"

private fun EditorTab.label() = when (this) {
    EditorTab.LOOKS -> R.string.tab_looks
    EditorTab.ADJUST -> R.string.tab_adjust
    EditorTab.CROP -> R.string.tab_crop
    EditorTab.ERASE -> R.string.tab_erase
    EditorTab.TRY_ON -> R.string.tab_try_on
}

private fun EditorTab.icon() = when (this) {
    EditorTab.LOOKS -> Icons.Filled.Palette
    EditorTab.ADJUST -> Icons.Filled.Tune
    EditorTab.CROP -> Icons.Filled.Crop
    EditorTab.ERASE -> Icons.Filled.AutoFixHigh
    EditorTab.TRY_ON -> Icons.Filled.Face
}

private fun EditLook.label() = when (this) {
    EditLook.ORIGINAL -> R.string.look_original
    EditLook.VIVID -> R.string.look_vivid
    EditLook.MONO -> R.string.look_mono
    EditLook.COOL -> R.string.look_cool
    EditLook.WARM -> R.string.look_warm
    EditLook.STUDIO -> R.string.look_studio
    EditLook.FILM -> R.string.look_film
    EditLook.FADE -> R.string.look_fade
    EditLook.CINEMA -> R.string.look_cinema
    EditLook.NOIR -> R.string.look_noir
    EditLook.GOLDEN -> R.string.look_golden
    EditLook.MATTE -> R.string.look_matte
}

private fun Adjustment.label() = when (this) {
    Adjustment.EXPOSURE -> R.string.adjust_exposure
    Adjustment.CONTRAST -> R.string.adjust_contrast
    Adjustment.HIGHLIGHTS -> R.string.adjust_highlights
    Adjustment.SHADOWS -> R.string.adjust_shadows
    Adjustment.WARMTH -> R.string.adjust_warmth
    Adjustment.TINT -> R.string.adjust_tint
    Adjustment.SATURATION -> R.string.adjust_saturation
    Adjustment.VIBRANCE -> R.string.adjust_vibrance
    Adjustment.VIGNETTE -> R.string.adjust_vignette
    Adjustment.GRAIN -> R.string.adjust_grain
}

private fun Adjustment.icon() = when (this) {
    Adjustment.EXPOSURE -> Icons.Filled.WbSunny
    Adjustment.CONTRAST -> Icons.Filled.Contrast
    Adjustment.HIGHLIGHTS -> Icons.Filled.Brightness5
    Adjustment.SHADOWS -> Icons.Filled.Brightness6
    Adjustment.WARMTH -> Icons.Filled.Thermostat
    Adjustment.TINT -> Icons.Filled.InvertColors
    Adjustment.SATURATION -> Icons.Filled.Opacity
    Adjustment.VIBRANCE -> Icons.Filled.Tonality
    Adjustment.VIGNETTE -> Icons.Filled.Vignette
    Adjustment.GRAIN -> Icons.Filled.Grain
}

private fun CropAspect.label() = when (this) {
    CropAspect.FREE -> R.string.aspect_free
    CropAspect.ORIGINAL -> R.string.aspect_original
    CropAspect.SQUARE -> R.string.aspect_square
    CropAspect.FOUR_FIVE -> R.string.aspect_4_5
    CropAspect.THREE_FOUR -> R.string.aspect_3_4
    CropAspect.FOUR_THREE -> R.string.aspect_4_3
    CropAspect.NINE_SIXTEEN -> R.string.aspect_9_16
    CropAspect.SIXTEEN_NINE -> R.string.aspect_16_9
}

private val CONTROLS_HEIGHT = 168.dp
