package com.camoron.feature.camera

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camoron.domain.model.AspectRatioSetting
import com.camoron.domain.model.FlashMode
import com.camoron.domain.model.NightSetting
import com.camoron.domain.model.ShotLook
import com.camoron.domain.model.TimerSetting
import com.camoron.domain.model.ZoomPreset
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Flash, timer, aspect ratio, Night and settings. Each button cycles through its options. */
@Composable
internal fun TopMenu(
    state: CameraUiState,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onAspectRatio: () -> Unit,
    onNight: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.cameraSettings
    Row(
        modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            .background(Color.Black.copy(alpha = 0.35f)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.showFlashControl) {
            val (icon, label) = when (settings.flash) {
                FlashMode.OFF -> Icons.Filled.FlashOff to R.string.option_off
                FlashMode.AUTO -> Icons.Filled.FlashAuto to R.string.option_auto
                FlashMode.ON -> Icons.Filled.FlashOn to R.string.option_on
            }
            TopMenuButton(icon, stringResource(label), stringResource(R.string.cd_flash), onFlash)
        }
        val timerLabel = when (settings.timer) {
            TimerSetting.OFF -> R.string.option_off
            TimerSetting.THREE -> R.string.timer_3s
            TimerSetting.FIVE -> R.string.timer_5s
        }
        TopMenuButton(
            if (settings.timer == TimerSetting.OFF) Icons.Filled.TimerOff else Icons.Filled.Timer,
            stringResource(timerLabel),
            stringResource(R.string.cd_timer),
            onTimer,
        )
        val ratioLabel = when (settings.aspectRatio) {
            AspectRatioSetting.RATIO_3_4 -> R.string.ratio_3_4
            AspectRatioSetting.RATIO_9_16 -> R.string.ratio_9_16
            AspectRatioSetting.FULL_SCREEN -> R.string.ratio_full
        }
        TopMenuButton(
            Icons.Filled.AspectRatio,
            stringResource(ratioLabel),
            stringResource(R.string.cd_aspect_ratio),
            onAspectRatio,
        )
        if (state.showNightControl) {
            val nightLabel = when (settings.night) {
                NightSetting.OFF -> R.string.option_off
                NightSetting.AUTO -> R.string.option_auto
                NightSetting.ON -> R.string.option_on
            }
            TopMenuButton(
                Icons.Filled.NightsStay,
                stringResource(nightLabel),
                stringResource(R.string.cd_night),
                onNight,
                tint = if (state.nightActive) MODE_SELECTED else Color.White,
            )
        }
        TopMenuButton(Icons.Filled.Settings, null, stringResource(R.string.cd_settings), onSettings)
    }
}

@Composable
private fun TopMenuButton(
    icon: ImageVector,
    label: String?,
    description: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Column(
        Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The label is read with the description, e.g. "Flash, Auto".
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
        if (label != null) Text(label, color = Color.White, fontSize = 11.sp)
    }
}

/**
 * Layer 1: Macro, the zoom buttons and shot modes (looks). Macro and zoom buttons appear only when
 * this side's hardware supports them; the active zoom button shows the exact current zoom, e.g.
 * 1.4× while pinching. The shot mode button opens a row of looks above it.
 */
@Composable
internal fun LensRow(
    state: CameraUiState,
    onMacro: () -> Unit,
    onPreset: (ZoomPreset) -> Unit,
    onLook: (ShotLook) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = state.zoomPresets.takeIf { it.size > 1 }.orEmpty()
    val active = state.activePreset
    var looksOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (looksOpen) LookPicker(state.cameraSettings.look, onLook)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val looksDescription = stringResource(R.string.cd_looks)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(role = Role.Button, onClickLabel = looksDescription) { looksOpen = !looksOpen },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Palette,
                    contentDescription = looksDescription,
                    tint = if (looksOpen || state.cameraSettings.look != ShotLook.NORMAL) MODE_SELECTED else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (state.macroAvailable) {
                val description = stringResource(R.string.cd_macro)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(role = Role.Switch, onClickLabel = description, onClick = onMacro),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.LocalFlorist,
                        contentDescription = description,
                        tint = if (state.macro) MODE_SELECTED else Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            presets.forEach { preset ->
                val isActive = preset == active && !state.macro
                val text = if (isActive) formatZoom(state.displayZoom) + "×" else formatZoom(preset.label)
                val description = stringResource(R.string.cd_zoom, formatZoom(preset.label))
                Box(
                    Modifier
                        .size(if (isActive) 44.dp else 36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(role = Role.Button, onClickLabel = description) { onPreset(preset) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text,
                        color = if (isActive) MODE_SELECTED else Color.White,
                        fontSize = if (isActive) 13.sp else 12.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Shot modes: colour looks shown live on the preview and applied to the saved photo. */
@Composable
private fun LookPicker(selected: ShotLook, onSelect: (ShotLook) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        ShotLook.entries.forEach { look ->
            val isSelected = look == selected
            Text(
                text = stringResource(lookLabel(look)),
                color = if (isSelected) Color.Black else Color.White,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) MODE_SELECTED else Color.Black.copy(alpha = 0.5f))
                    .clickable(role = Role.RadioButton) { onSelect(look) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

private fun lookLabel(look: ShotLook): Int = when (look) {
    ShotLook.NORMAL -> R.string.look_normal
    ShotLook.VIVID -> R.string.look_vivid
    ShotLook.BLACK_AND_WHITE -> R.string.look_black_and_white
    ShotLook.COOL -> R.string.look_cool
    ShotLook.WARM -> R.string.look_warm
    ShotLook.STUDIO -> R.string.look_studio
}

@Composable
internal fun NightBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.NightsStay, contentDescription = null, tint = MODE_SELECTED, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.night_label), color = Color.White, fontSize = 12.sp)
    }
}

/** "2" for whole numbers, "0.6" or "1.4" otherwise. */
internal fun formatZoom(value: Float): String {
    val tenths = Math.round(value * 10)
    return if (tenths % 10 == 0) (tenths / 10).toString() else String.format(Locale.getDefault(), "%.1f", tenths / 10f)
}

@Composable
internal fun MacroBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.LocalFlorist, contentDescription = null, tint = MODE_SELECTED, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.macro_label), color = Color.White, fontSize = 12.sp)
    }
}

/** Layer 2: capture modes. Only modes this phone supports are listed; Phase A ships Photo and Pro. */
@Composable
internal fun ModeCarousel(selected: CaptureMode, onSelect: (CaptureMode) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CaptureMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Text(
                text = stringResource(if (mode == CaptureMode.PHOTO) R.string.mode_photo else R.string.mode_pro),
                color = if (isSelected) MODE_SELECTED else Color.White,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Tab) { onSelect(mode) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** Last photo Camoron saved; tapping opens it in the phone's gallery app. */
@Composable
internal fun GalleryThumbnail(uri: String?) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = uri?.let { withContext(Dispatchers.IO) { loadThumbnail(context, Uri.parse(it)) } }
    }
    val description = stringResource(R.string.cd_last_photo)
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .background(Color.DarkGray)
            .clickable(enabled = uri != null, onClickLabel = description) { uri?.let { openInGallery(context, it) } },
    ) {
        bitmap?.let {
            Image(it, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun loadThumbnail(context: Context, uri: Uri): ImageBitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null)
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}.getOrNull()?.asImageBitmap()

private fun openInGallery(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No gallery app installed; nothing sensible to open.
    }
}

private const val THUMBNAIL_PX = 160
private val MODE_SELECTED = Color(0xFFFFD54F)
