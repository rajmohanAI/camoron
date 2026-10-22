package com.camoron.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camoron.domain.model.FrameColour
import com.camoron.domain.model.GlassesStyle
import com.camoron.domain.model.HairColour
import com.camoron.domain.model.LensTint
import com.camoron.domain.model.MaskStyle
import kotlin.math.roundToInt

/** The Try on tab: spectacles, masks and hair colour, drawn onto the faces found in the photo. */
@Composable
internal fun TryOnControls(state: EditorUiState, viewModel: EditorViewModel) {
    val tryOn = state.edit.tryOn
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        when {
            state.scanning || !state.scanned -> Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                Text(stringResource(R.string.finding_faces), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
            }
            state.tryOnSection == TryOnSection.HAIR && !state.hairFound -> Message(stringResource(R.string.no_hair), Modifier.weight(1f))
            state.tryOnSection != TryOnSection.HAIR && state.faceCount == 0 -> Message(stringResource(R.string.no_face), Modifier.weight(1f))
            else -> Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Bottom) {
                when (state.tryOnSection) {
                    TryOnSection.GLASSES -> {
                        ChipRow(
                            options = GlassesStyle.entries,
                            selected = tryOn.glasses,
                            label = { stringResource(it.label()) },
                            onSelect = { viewModel.setTryOn(tryOn.copy(glasses = it)) },
                        )
                        if (tryOn.glasses != GlassesStyle.NONE) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                if (tryOn.glasses != GlassesStyle.RIMLESS) {
                                    items(FrameColour.entries) { colour ->
                                        Swatch(Color(colour.argb), selected = colour == tryOn.frame) {
                                            viewModel.setTryOn(tryOn.copy(frame = colour))
                                        }
                                    }
                                    item { Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.3f))) }
                                }
                                items(LensTint.entries) { tint ->
                                    Chip(stringResource(tint.label()), selected = tint == tryOn.lens) {
                                        viewModel.setTryOn(tryOn.copy(lens = tint))
                                    }
                                }
                            }
                        }
                    }
                    TryOnSection.MASKS -> ChipRow(
                        options = MaskStyle.entries,
                        selected = tryOn.mask,
                        label = { stringResource(it.label()) },
                        onSelect = { viewModel.setTryOn(tryOn.copy(mask = it)) },
                    )
                    TryOnSection.HAIR -> {
                        if (tryOn.hair != HairColour.NONE) {
                            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.strength), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text("${(tryOn.hairStrength * 100).roundToInt()}", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                            }
                            Slider(
                                value = tryOn.hairStrength,
                                onValueChange = viewModel::setHairStrength,
                                onValueChangeFinished = viewModel::commit,
                                modifier = Modifier.padding(horizontal = 20.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                                ),
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            items(HairColour.entries) { colour ->
                                if (colour == HairColour.NONE) {
                                    NoneSwatch(selected = tryOn.hair == HairColour.NONE) { viewModel.setTryOn(tryOn.copy(hair = colour)) }
                                } else {
                                    Swatch(Color(colour.argb), selected = colour == tryOn.hair, size = 36) {
                                        viewModel.setTryOn(tryOn.copy(hair = colour))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        ChipRow(
            options = TryOnSection.entries,
            selected = state.tryOnSection,
            label = { stringResource(it.label()) },
            onSelect = viewModel::selectTryOnSection,
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            outlined = true,
        )
    }
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        items(options) { option ->
            Chip(label(option), selected = option == selected, outlined = outlined) { onSelect(option) }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, outlined: Boolean = false, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val background = when {
        selected && outlined -> accent.copy(alpha = 0.18f)
        selected -> accent
        else -> Color.White.copy(alpha = 0.1f)
    }
    val textColour = when {
        selected && outlined -> accent
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> Color.White
    }
    Text(
        text,
        color = textColour,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun Swatch(colour: Color, selected: Boolean, size: Int = 30, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f), CircleShape)
            .padding(if (selected) 4.dp else 1.dp)
            .clip(CircleShape)
            .background(colour)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun NoneSwatch(selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Block, contentDescription = stringResource(R.string.none), tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
    }
}

private fun TryOnSection.label() = when (this) {
    TryOnSection.GLASSES -> R.string.try_glasses
    TryOnSection.MASKS -> R.string.try_masks
    TryOnSection.HAIR -> R.string.try_hair
}

private fun GlassesStyle.label() = when (this) {
    GlassesStyle.NONE -> R.string.none
    GlassesStyle.ROUND -> R.string.glasses_round
    GlassesStyle.RECTANGLE -> R.string.glasses_rectangle
    GlassesStyle.AVIATOR -> R.string.glasses_aviator
    GlassesStyle.CAT_EYE -> R.string.glasses_cat_eye
    GlassesStyle.BOLD -> R.string.glasses_bold
    GlassesStyle.RIMLESS -> R.string.glasses_rimless
}

private fun LensTint.label() = when (this) {
    LensTint.CLEAR -> R.string.lens_clear
    LensTint.GREY -> R.string.lens_grey
    LensTint.BROWN -> R.string.lens_brown
    LensTint.GRADIENT -> R.string.lens_gradient
    LensTint.BLUE -> R.string.lens_blue
}

private fun MaskStyle.label() = when (this) {
    MaskStyle.NONE -> R.string.none
    MaskStyle.SURGICAL -> R.string.mask_surgical
    MaskStyle.N95 -> R.string.mask_n95
    MaskStyle.CLOTH -> R.string.mask_cloth
    MaskStyle.MASQUERADE -> R.string.mask_masquerade
    MaskStyle.HERO -> R.string.mask_hero
}
