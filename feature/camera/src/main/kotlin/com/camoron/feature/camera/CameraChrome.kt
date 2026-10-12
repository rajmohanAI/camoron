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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.camoron.domain.model.TimerSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Flash, timer, aspect ratio and settings. Each button cycles through its options. */
@Composable
internal fun TopMenu(
    state: CameraUiState,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onAspectRatio: () -> Unit,
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
        TopMenuButton(Icons.Filled.Settings, null, stringResource(R.string.cd_settings), onSettings)
    }
}

@Composable
private fun TopMenuButton(icon: ImageVector, label: String?, description: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The label is read with the description, e.g. "Flash, Auto".
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(24.dp))
        if (label != null) Text(label, color = Color.White, fontSize = 11.sp)
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
