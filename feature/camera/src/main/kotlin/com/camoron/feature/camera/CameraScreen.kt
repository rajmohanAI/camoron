package com.camoron.feature.camera

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaActionSound
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camoron.core.common.SurfacePreviewTarget
import com.camoron.domain.model.AspectRatioSetting
import com.camoron.domain.model.CameraError
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.PixelSize

@Composable
fun CameraScreen(
    onOpenSettings: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val histogram by viewModel.histogram.collectAsStateWithLifecycle()
    val context = LocalContext.current
    TrackDeviceRotation(viewModel::onDeviceRotation)
    ShutterSounds(viewModel)
    ScreenFlashBrightness(state.screenFlash)

    val savedText = stringResource(R.string.saved_to_gallery)
    val failedText = stringResource(R.string.capture_failed)
    LaunchedEffect(state.event) {
        when (state.event) {
            is CameraEvent.Saved -> Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
            CameraEvent.CaptureFailed -> Toast.makeText(context, failedText, Toast.LENGTH_LONG).show()
            null -> return@LaunchedEffect
        }
        viewModel.eventConsumed()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .onSizeChanged { if (it.height > 0) viewModel.onDisplayAspect(it.width.toFloat() / it.height) },
    ) {
        val aspect = state.cameraSettings.aspectRatio
        val previewArea = when (aspect) {
            AspectRatioSetting.FULL_SCREEN -> Modifier.fillMaxSize()
            AspectRatioSetting.RATIO_9_16 -> Modifier.padding(top = TOP_BAR_HEIGHT).fillMaxWidth().aspectRatio(9f / 16)
            AspectRatioSetting.RATIO_3_4 -> Modifier.padding(top = TOP_BAR_HEIGHT).fillMaxWidth().aspectRatio(3f / 4)
        }
        PreviewArea(
            state = state,
            onSurfaceReady = viewModel::onSurfaceReady,
            onSurfaceDestroyed = viewModel::onSurfaceDestroyed,
            onTap = viewModel::focusAt,
            modifier = previewArea.align(Alignment.TopCenter),
        )

        (state.cameraState as? CameraState.Error)?.let { ErrorMessage(it.error, Modifier.align(Alignment.Center)) }

        TopMenu(
            state = state,
            onFlash = viewModel::cycleFlash,
            onTimer = viewModel::cycleTimer,
            onAspectRatio = viewModel::cycleAspectRatio,
            onSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val previewing = state.cameraState as? CameraState.Previewing
            if (state.mode == CaptureMode.PRO && previewing != null) {
                ProControls(
                    capabilities = previewing.capabilities,
                    settings = state.settings,
                    liveExposure = previewing.liveExposure,
                    rawEnabled = state.rawEnabled,
                    histogram = histogram,
                    viewModel = viewModel,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)),
                )
            }
            BottomBar(
                state = state,
                onModeChange = viewModel::setMode,
                onCapture = viewModel::onShutter,
                onSwitch = viewModel::switchCamera,
                translucent = aspect == AspectRatioSetting.FULL_SCREEN,
            )
        }

        if (state.screenFlash) Box(Modifier.fillMaxSize().background(Color.White))
    }
}

/**
 * The preview fills [modifier]'s area like a centre-cropped photo: the camera buffer keeps its own
 * aspect ratio and any overflow is clipped, which is exactly the crop applied to the saved photo.
 */
@Composable
private fun PreviewArea(
    state: CameraUiState,
    onSurfaceReady: (SurfacePreviewTarget) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val size = state.previewSize
        if (size != null) {
            val previewAspect = minOf(size.width, size.height).toFloat() / maxOf(size.width, size.height)
            val (width, height) = if (maxWidth / maxHeight > previewAspect) {
                maxWidth to maxWidth / previewAspect
            } else {
                maxHeight * previewAspect to maxHeight
            }
            CameraPreview(
                previewSize = size,
                onSurfaceReady = onSurfaceReady,
                onSurfaceDestroyed = onSurfaceDestroyed,
                onTap = onTap,
                modifier = Modifier.requiredSize(width, height),
            )
        }
        if (state.cameraSettings.grid) GridOverlay(Modifier.fillMaxSize())
        if (state.cameraSettings.leveller) LevellerOverlay(Modifier.fillMaxSize())
        state.countdown?.let { CountdownOverlay(it) }
    }
}

/** SurfaceView-backed preview. The buffer is fixed to the camera's preview size so it is never stretched. */
@Composable
private fun CameraPreview(
    previewSize: PixelSize,
    onSurfaceReady: (SurfacePreviewTarget) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready by rememberUpdatedState(onSurfaceReady)
    val destroyed by rememberUpdatedState(onSurfaceDestroyed)

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        ready(SurfacePreviewTarget(holder.surface, width, height))
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) = destroyed()
                })
            }
        },
        update = { view -> view.holder.setFixedSize(previewSize.width, previewSize.height) },
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                onTap(offset.x / size.width, offset.y / size.height)
            }
        },
    )
}

@Composable
private fun BottomBar(
    state: CameraUiState,
    onModeChange: (CaptureMode) -> Unit,
    onCapture: () -> Unit,
    onSwitch: () -> Unit,
    translucent: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (translucent) Color.Black.copy(alpha = 0.35f) else Color.Black)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeCarousel(selected = state.mode, onSelect = onModeChange)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                GalleryThumbnail(state.lastCaptureUri)
            }
            ShutterButton(
                busy = state.isCapturing,
                counting = state.countdown != null,
                enabled = state.cameraState is CameraState.Previewing,
                onClick = onCapture,
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (state.canSwitchCamera) {
                    IconButton(onClick = onSwitch) {
                        Icon(
                            Icons.Filled.Cameraswitch,
                            contentDescription = stringResource(R.string.cd_switch_camera),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(busy: Boolean, counting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(if (counting) R.string.cd_cancel_timer else R.string.cd_shutter)
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .background(
                when {
                    !enabled -> Color.Gray
                    counting -> Color(0xFFE53935)
                    else -> Color.White
                },
                CircleShape,
            )
            .clickable(enabled = enabled && !busy, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(32.dp), color = Color.Black, strokeWidth = 3.dp)
    }
}

@Composable
private fun ErrorMessage(error: CameraError, modifier: Modifier = Modifier) {
    val text = when (error) {
        CameraError.CAMERA_IN_USE -> R.string.error_in_use
        CameraError.CAMERA_DISABLED -> R.string.error_disabled
        CameraError.PERMISSION_DENIED -> R.string.error_permission
        CameraError.NO_CAMERA -> R.string.error_no_camera
        CameraError.DEVICE_ERROR -> R.string.error_device
    }
    Text(
        stringResource(text),
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.padding(32.dp),
    )
}

/** Reports the phone's physical rotation (0..359) for photo orientation while the UI stays portrait. */
@Composable
private fun TrackDeviceRotation(onRotation: (Int) -> Unit) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onRotation)
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) callback(orientation)
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
}

/** Plays the shutter click and timer ticks the view model asks for. */
@Composable
private fun ShutterSounds(viewModel: CameraViewModel) {
    val sound = remember { MediaActionSound() }
    DisposableEffect(sound) {
        sound.load(MediaActionSound.SHUTTER_CLICK)
        sound.load(MediaActionSound.FOCUS_COMPLETE)
        onDispose { sound.release() }
    }
    LaunchedEffect(viewModel, sound) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CameraEffect.SHUTTER -> sound.play(MediaActionSound.SHUTTER_CLICK)
                CameraEffect.TIMER_TICK -> sound.play(MediaActionSound.FOCUS_COMPLETE)
            }
        }
    }
}

/** Turns the display to full brightness while the white screen flash is showing. */
@Composable
private fun ScreenFlashBrightness(active: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(active, activity) {
        val window = activity?.window
        if (!active || window == null) return@DisposableEffect onDispose { }
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        onDispose {
            window.attributes = window.attributes.apply {
                screenBrightness = if (previous >= 0f) previous else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal val TOP_BAR_HEIGHT = 56.dp
