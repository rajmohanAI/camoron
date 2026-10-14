package com.camoron.feature.camera

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import com.camoron.feature.camera.Glass.glass
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import com.camoron.domain.model.VideoMath
import com.camoron.domain.model.VideoResolution
import com.camoron.domain.model.ZoomPreset
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Flash, timer, aspect ratio, Night, Enhance and settings; in video, the light, resolution or
 * timelapse speed, and Enhance. Each button cycles through its options.
 */
@Composable
internal fun TopMenu(
    state: CameraUiState,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onAspectRatio: () -> Unit,
    onNight: () -> Unit,
    onEnhance: () -> Unit,
    onResolution: () -> Unit,
    onTimelapseSpeed: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.cameraSettings
    // A floating glass bar over the preview, not a black band across it.
    Row(
        modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .glass(RoundedCornerShape(22.dp)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.showFlashControl && state.mode.isVideo) {
            val on = settings.flash == FlashMode.ON
            TopMenuButton(
                if (on) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                stringResource(if (on) R.string.option_on else R.string.option_off),
                stringResource(R.string.cd_light),
                onFlash,
                tint = if (on) MODE_SELECTED else Color.White,
            )
        } else if (state.showFlashControl) {
            val (icon, label) = when (settings.flash) {
                FlashMode.OFF -> Icons.Filled.FlashOff to R.string.option_off
                FlashMode.AUTO -> Icons.Filled.FlashAuto to R.string.option_auto
                FlashMode.ON -> Icons.Filled.FlashOn to R.string.option_on
            }
            TopMenuButton(icon, stringResource(label), stringResource(R.string.cd_flash), onFlash)
        }
        if (state.showPhotoControls) {
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
        }
        // Changing these reopens the camera, so they wait until a recording ends.
        if (state.showResolutionControl && state.mode != CaptureMode.TIMELAPSE && !state.recording) {
            TopMenuButton(
                Icons.Filled.HighQuality,
                stringResource(
                    if (settings.videoResolution == VideoResolution.UHD) R.string.resolution_uhd else R.string.resolution_fhd,
                ),
                stringResource(R.string.cd_resolution),
                onResolution,
                tint = if (settings.videoResolution == VideoResolution.UHD) MODE_SELECTED else Color.White,
            )
        }
        if (state.mode == CaptureMode.TIMELAPSE && !state.recording) {
            TopMenuButton(
                Icons.Filled.Speed,
                stringResource(R.string.timelapse_speed, settings.timelapseSpeed.factor),
                stringResource(R.string.cd_timelapse_speed),
                onTimelapseSpeed,
            )
        }
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
        TopMenuButton(
            Icons.Filled.AutoAwesome,
            stringResource(if (settings.enhance) R.string.option_on else R.string.option_off),
            stringResource(R.string.cd_enhance),
            onEnhance,
            tint = if (settings.enhance) MODE_SELECTED else Color.White,
        )
        if (!state.recording) {
            TopMenuButton(Icons.Filled.Settings, null, stringResource(R.string.cd_settings), onSettings)
        }
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
    val haptics = rememberHaptics()
    val colour by animateColorAsState(tint, label = "menu tint")
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = description) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .upright(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The label is read with the description, e.g. "Flash, Auto".
        Icon(icon, contentDescription = description, tint = colour, modifier = Modifier.size(22.dp))
        if (label != null) {
            Text(label, color = if (tint == Color.White) Color.White.copy(alpha = 0.8f) else colour, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
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
    val haptics = rememberHaptics()
    var looksOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedVisibility(
            visible = looksOpen,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            LookPicker(state.cameraSettings.look) {
                haptics.tick()
                onLook(it)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = Icons.Filled.Palette,
                description = stringResource(R.string.cd_looks),
                active = looksOpen || state.cameraSettings.look != ShotLook.NORMAL,
                role = Role.Button,
            ) {
                haptics.tick()
                looksOpen = !looksOpen
            }
            if (state.macroAvailable) {
                GlassIconButton(
                    icon = Icons.Filled.LocalFlorist,
                    description = stringResource(R.string.cd_macro),
                    active = state.macro,
                    role = Role.Switch,
                ) {
                    haptics.tick()
                    onMacro()
                }
            }
            // All zoom steps share one glass capsule, like a lens dial.
            if (presets.isNotEmpty()) {
                Row(
                    Modifier.glass().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    presets.forEach { preset ->
                        val isActive = preset == active && !state.macro
                        ZoomChip(
                            text = if (isActive) formatZoom(state.displayZoom) + "×" else formatZoom(preset.label),
                            description = stringResource(R.string.cd_zoom, formatZoom(preset.label)),
                            active = isActive,
                        ) {
                            haptics.tick()
                            onPreset(preset)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassIconButton(icon: ImageVector, description: String, active: Boolean, role: Role, onClick: () -> Unit) {
    val tint by animateColorAsState(if (active) Glass.Accent else Color.White, label = "icon tint")
    Box(
        Modifier
            .size(42.dp)
            .glass()
            .clickable(role = role, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp).upright())
    }
}

@Composable
private fun ZoomChip(text: String, description: String, active: Boolean, onClick: () -> Unit) {
    val size by animateDpAsState(if (active) 42.dp else 34.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "zoom size")
    val fill by animateColorAsState(if (active) Color.White.copy(alpha = 0.16f) else Color.Transparent, label = "zoom fill")
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (active) Glass.Accent else Color.White,
            fontSize = if (active) 13.sp else 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.upright(),
        )
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
                    .then(if (isSelected) Modifier.clip(CircleShape).background(Glass.Accent) else Modifier.glass())
                    .clickable(role = Role.RadioButton) { onSelect(look) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
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
            .glass()
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
            .glass()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.LocalFlorist, contentDescription = null, tint = MODE_SELECTED, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.macro_label), color = Color.White, fontSize = 12.sp)
    }
}

/** Layer 2: capture modes, in the order camera apps use: video modes, then Photo, then Pro. */
@Composable
internal fun ModeCarousel(selected: CaptureMode, onSelect: (CaptureMode) -> Unit) {
    val listState = rememberLazyListState()
    val haptics = rememberHaptics()
    val index = CaptureMode.entries.indexOf(selected)
    // Keep the selected mode in the middle, like the phone's own camera, so modes at either end
    // (Pro, Timelapse) are always reachable and visible once chosen.
    LaunchedEffect(index) {
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return@LaunchedEffect
        val centre = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
        listState.animateScrollBy((item.offset + item.size / 2 - centre).toFloat())
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = maxWidth / 2),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(CaptureMode.entries, key = { it.name }) { mode ->
                val isSelected = mode == selected
                val colour by animateColorAsState(if (isSelected) Glass.Accent else Color.White.copy(alpha = 0.75f), label = "mode colour")
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Tab) {
                            if (!isSelected) haptics.tick()
                            onSelect(mode)
                        }
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(modeLabel(mode)).uppercase(Locale.getDefault()),
                        color = colour,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp,
                        letterSpacing = 1.2.sp,
                    )
                    // A small accent dot marks the current mode.
                    Box(
                        Modifier
                            .padding(top = 4.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Glass.Accent else Color.Transparent),
                    )
                }
            }
        }
    }
}

private fun modeLabel(mode: CaptureMode): Int = when (mode) {
    CaptureMode.TIMELAPSE -> R.string.mode_timelapse
    CaptureMode.CINEMATIC -> R.string.mode_cinematic
    CaptureMode.VIDEO -> R.string.mode_video
    CaptureMode.PHOTO -> R.string.mode_photo
    CaptureMode.PRO -> R.string.mode_pro
}

/** Red dot and running time while recording; timelapse also shows its speed, Cinematic its frame rate. */
@Composable
internal fun RecordingBadge(state: CameraUiState, modifier: Modifier = Modifier) {
    val started = state.recordingStartedNanos ?: return
    val elapsed by produceState(0L, started) {
        while (true) {
            value = (System.nanoTime() - started) / 1_000_000
            kotlinx.coroutines.delay(250)
        }
    }
    val extra = when (state.mode) {
        CaptureMode.TIMELAPSE -> stringResource(R.string.timelapse_speed, state.cameraSettings.timelapseSpeed.factor)
        CaptureMode.CINEMATIC -> stringResource(R.string.cinematic_fps)
        else -> null
    }
    Row(
        modifier
            .glass()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(RECORDING_RED))
        Text(VideoMath.formatDuration(elapsed), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (extra != null) Text(extra, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

/** Last photo or video Camoron saved; tapping opens it in the phone's gallery app. */
@Composable
internal fun GalleryThumbnail(uri: String?, saving: Boolean = false, instant: ImageBitmap? = null, instantUri: String? = null) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = uri?.let { withContext(Dispatchers.IO) { loadThumbnail(context, Uri.parse(it)) } }
    }
    val description = stringResource(R.string.cd_last_photo)
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            // While the newest photo is still saving, wait for it rather than open the previous one.
            .clickable(enabled = uri != null && !saving, onClickLabel = description) { uri?.let { openInGallery(context, it) } },
    ) {
        // The shot appears the moment it is taken; once it is optimised and saved, the saved photo's
        // own thumbnail takes over, so what shows here is the finished photo.
        val shown = when {
            instant != null && saving -> instant
            bitmap != null -> bitmap
            uri == instantUri -> instant
            else -> null
        }
        Crossfade(shown, animationSpec = tween(THUMBNAIL_FADE_MS), label = "thumbnail") { image ->
            image?.let {
                Image(it, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        if (saving) OptimisingLoader(Modifier.fillMaxSize())
    }
}

/** Over the newest shot while it is being optimised: the picture dims and a sparkle pulses inside a spinning ring. */
@Composable
private fun OptimisingLoader(modifier: Modifier) {
    val pulse by rememberInfiniteTransition(label = "optimising").animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(modifier.background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = stringResource(R.string.cd_optimising),
            tint = MODE_SELECTED,
            modifier = Modifier.size(20.dp).graphicsLayer {
                scaleX = pulse
                scaleY = pulse
                alpha = pulse
            },
        )
        CircularProgressIndicator(Modifier.fillMaxSize(), color = MODE_SELECTED, strokeWidth = 2.dp)
    }
}

private fun loadThumbnail(context: Context, uri: Uri): ImageBitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null)
    } else if (context.contentResolver.getType(uri)?.startsWith("video/") == true) {
        MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                getFrameAtTime(0)
            } finally {
                release()
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val small = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        // Before Android 10 the gallery's own thumbnails aren't used, so turn the photo upright here.
        val degrees = context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (small == null || degrees == 0f) {
            small
        } else {
            Bitmap.createBitmap(small, 0, 0, small.width, small.height, Matrix().apply { postRotate(degrees) }, true)
        }
    }
}.getOrNull()?.asImageBitmap()

private fun openInGallery(context: Context, uri: String) {
    val parsed = Uri.parse(uri)
    val type = context.contentResolver.getType(parsed) ?: "image/*"
    // Review is what the phone's own camera uses: the gallery opens the shot within the camera roll,
    // so you can swipe to your other photos, rather than in a lone viewer.
    val intents = listOf(MediaStore.ACTION_REVIEW, Intent.ACTION_VIEW).map { action ->
        Intent(action).setDataAndType(parsed, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // Try the next way of opening it; with no gallery app at all there is nothing to open.
        }
    }
}

private const val THUMBNAIL_PX = 160
private const val THUMBNAIL_FADE_MS = 300
private const val PULSE_MS = 600
private val MODE_SELECTED = Glass.Accent
internal val RECORDING_RED = Glass.Recording
