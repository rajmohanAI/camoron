package com.camoron.feature.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaActionSound
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camoron.core.common.SurfacePreviewTarget
import com.camoron.domain.model.AspectRatioSetting
import com.camoron.domain.model.CameraError
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.EnhanceParams
import com.camoron.domain.model.FocusTarget
import com.camoron.domain.model.ShotLook
import com.camoron.domain.model.PixelSize

@Composable
fun CameraScreen(
    onOpenSettings: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // These change many times a second; they are passed down as lambdas and read only where they
    // are drawn, so they don't recompose the whole screen each time.
    val histogram = viewModel.histogram.collectAsStateWithLifecycle()
    val focusTarget by viewModel.focusTarget.collectAsStateWithLifecycle()
    val enhance = viewModel.enhanceParams.collectAsStateWithLifecycle()
    val context = LocalContext.current
    TrackDeviceRotation(viewModel::onDeviceRotation)
    TrackForeground(viewModel::onForegroundChanged)
    HideStatusBar()
    ShutterSounds(viewModel)
    ScreenFlashBrightness(state.screenFlash)
    KeepScreenOn(state.recording)
    RequestMicrophone(state.mode.video?.recordsSound == true)

    val failedText = stringResource(R.string.capture_failed)
    val recordingFailedText = stringResource(R.string.recording_failed)
    LaunchedEffect(state.event) {
        when (state.event) {
            // Like the phone's own camera, a saved photo just shows up in the thumbnail.
            is CameraEvent.Saved -> Unit
            CameraEvent.CaptureFailed -> Toast.makeText(context, failedText, Toast.LENGTH_LONG).show()
            CameraEvent.RecordingFailed -> Toast.makeText(context, recordingFailedText, Toast.LENGTH_LONG).show()
            null -> return@LaunchedEffect
        }
        viewModel.eventConsumed()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { if (it.height > 0) viewModel.onDisplayAspect(it.width.toFloat() / it.height) },
    ) {
        val aspect = state.cameraSettings.aspectRatio
        // Full screen really is the whole screen, under the (hidden) status bar and the camera cutout.
        // Video is always 16:9, so its preview shows exactly the frame being recorded.
        val previewArea = when {
            state.mode.isVideo || aspect == AspectRatioSetting.RATIO_9_16 ->
                Modifier.safeDrawingPadding().padding(top = TOP_BAR_HEIGHT).fillMaxWidth().aspectRatio(9f / 16)
            aspect == AspectRatioSetting.FULL_SCREEN -> Modifier.fillMaxSize()
            else -> Modifier.safeDrawingPadding().padding(top = TOP_BAR_HEIGHT).fillMaxWidth().aspectRatio(3f / 4)
        }
        PreviewArea(
            state = state,
            enhance = { enhance.value },
            focusTarget = focusTarget,
            onSurfaceReady = viewModel::onSurfaceReady,
            onSurfaceDestroyed = viewModel::onSurfaceDestroyed,
            onTap = viewModel::focusAt,
            onPinch = viewModel::onPinch,
            modifier = Modifier.align(Alignment.TopCenter).then(previewArea),
        )

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            (state.cameraState as? CameraState.Error)?.let {
                ErrorMessage(it.error, onRetry = viewModel::retryCamera, modifier = Modifier.align(Alignment.Center))
            }

            TopMenu(
                state = state,
                onFlash = viewModel::cycleFlash,
                onTimer = viewModel::cycleTimer,
                onAspectRatio = viewModel::cycleAspectRatio,
                onNight = viewModel::cycleNight,
                onEnhance = viewModel::toggleEnhance,
                onResolution = viewModel::toggleVideoResolution,
                onTimelapseSpeed = viewModel::cycleTimelapseSpeed,
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
                        histogram = { histogram.value },
                        viewModel = viewModel,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)),
                    )
                }
                LensRow(
                    state = state,
                    onMacro = viewModel::toggleMacro,
                    onPreset = viewModel::selectZoomPreset,
                    onLook = viewModel::setLook,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                BottomBar(
                    state = state,
                    onModeChange = viewModel::setMode,
                    onCapture = viewModel::onShutter,
                    onSwitch = viewModel::switchCamera,
                    translucent = aspect == AspectRatioSetting.FULL_SCREEN && !state.mode.isVideo,
                )
            }
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
    enhance: () -> EnhanceParams?,
    focusTarget: FocusTarget?,
    onSurfaceReady: (SurfacePreviewTarget) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onPinch: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val bufferSize = state.previewSize
        if (bufferSize != null) {
            val previewAspect = minOf(bufferSize.width, bufferSize.height).toFloat() /
                maxOf(bufferSize.width, bufferSize.height)
            val (width, height) = if (maxWidth / maxHeight > previewAspect) {
                maxWidth to maxWidth / previewAspect
            } else {
                maxHeight * previewAspect to maxHeight
            }
            // Taps and the focus box share the full (partly clipped) preview's coordinates.
            Box(
                Modifier
                    .requiredSize(width, height)
                    .pointerInput(Unit) {
                        detectTapGestures { offset -> onTap(offset.x / size.width, offset.y / size.height) }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onPinch(zoom) }
                    },
            ) {
                // Video frames arrive already enhanced by the camera's renderer.
                CameraPreview(
                    previewSize = bufferSize,
                    look = if (state.mode.isVideo) ShotLook.NORMAL else state.cameraSettings.look,
                    enhance = if (state.mode.isVideo) ({ null }) else enhance,
                    iso = { (state.cameraState as? CameraState.Previewing)?.liveExposure?.iso },
                    live = state.cameraState is CameraState.Previewing,
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceDestroyed = onSurfaceDestroyed,
                    modifier = Modifier.fillMaxSize(),
                )
                FocusIndicator(focusTarget, Modifier.fillMaxSize())
            }
        }
        if (state.cameraSettings.grid) GridOverlay(Modifier.fillMaxSize())
        if (state.cameraSettings.leveller) LevellerOverlay(Modifier.fillMaxSize())
        // In full screen the preview runs under the top menu, so keep the badges below it.
        val badgeTop = if (state.cameraSettings.aspectRatio == AspectRatioSetting.FULL_SCREEN && !state.mode.isVideo) {
            WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + TOP_BAR_HEIGHT + 8.dp
        } else {
            12.dp
        }
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = badgeTop),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.recording) RecordingBadge(state)
            if (state.macro) MacroBadge()
            if (state.nightActive) NightBadge()
        }
        state.countdown?.let { CountdownOverlay(it) }
        if (state.nightCapturing) HoldStillOverlay()
    }
}

/**
 * TextureView-backed preview, so the shot look can be drawn live as a colour filter. The buffer is
 * fixed to the camera's preview size so it is never stretched.
 *
 * While the camera switches (mode, lens, shape) the last frame stays on screen, softly blurred,
 * until the first new frame arrives, so a switch never flashes black or shows a squeezed picture.
 */
@Composable
private fun CameraPreview(
    previewSize: PixelSize,
    look: ShotLook,
    enhance: () -> EnhanceParams?,
    iso: () -> Int?,
    live: Boolean,
    onSurfaceReady: (SurfacePreviewTarget) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready by rememberUpdatedState(onSurfaceReady)
    val destroyed by rememberUpdatedState(onSurfaceDestroyed)
    var freeze by remember { mutableStateOf<ImageBitmap?>(null) }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                PreviewTextureView(
                    ctx,
                    onReady = { ready(it) },
                    onDestroyed = { destroyed() },
                    onFreeze = { freeze = it?.asImageBitmap() },
                )
            },
            update = { view ->
                view.setBufferSize(previewSize)
                view.setLive(live)
                // Read here, so new parameters only re-run this update, not the composition.
                view.setFilter(look, enhance(), iso())
            },
            modifier = Modifier.fillMaxSize(),
        )
        freeze?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().blur(FREEZE_BLUR),
            )
        }
    }
}

private val FREEZE_BLUR = 12.dp

private class PreviewTextureView(
    context: Context,
    private val onReady: (SurfacePreviewTarget) -> Unit,
    private val onDestroyed: () -> Unit,
    private val onFreeze: (Bitmap?) -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {

    private var bufferSize: PixelSize? = null
    private var surface: Surface? = null
    private val filter = PreviewFilter(this)

    init {
        surfaceTextureListener = this
    }

    /** Showing the last frame (or, if it couldn't be copied, nothing) until the restarted camera sends one. */
    private var frozen = false
    private var sawRestart = false
    private var awaitingFrame = false
    private val unfreezeFallback = Runnable { unfreeze() }

    fun setBufferSize(size: PixelSize) {
        if (size == bufferSize) return
        // Until the camera reopens, the old size's frames would show squeezed into the new buffer,
        // which looks like the picture stretching. Hold the last frame instead.
        if (bufferSize != null) freeze()
        bufferSize = size
        surfaceTexture?.let(::report)
    }

    fun setLive(live: Boolean) {
        if (!live) {
            freeze()
            sawRestart = true
        } else if (frozen && sawRestart && !awaitingFrame) {
            awaitingFrame = true
            // Never leave the picture frozen if the first frame is somehow missed.
            removeCallbacks(unfreezeFallback)
            postDelayed(unfreezeFallback, UNFREEZE_FALLBACK_MS)
        }
    }

    fun setFilter(look: ShotLook, enhance: EnhanceParams?, iso: Int?) = filter.update(look, enhance, iso)

    private fun freeze() {
        if (frozen || !isAvailable || width == 0 || height == 0) return
        frozen = true
        sawRestart = false
        awaitingFrame = false
        removeCallbacks(unfreezeFallback)
        // A small copy is plenty under the blur, and quick to take.
        val snapshot = runCatching {
            getBitmap((width / FREEZE_SCALE).coerceAtLeast(1), (height / FREEZE_SCALE).coerceAtLeast(1))
        }.getOrNull()
        if (snapshot != null) onFreeze(snapshot) else alpha = 0f
    }

    private fun unfreeze() {
        if (!frozen) return
        frozen = false
        sawRestart = false
        awaitingFrame = false
        removeCallbacks(unfreezeFallback)
        alpha = 1f
        onFreeze(null)
    }

    private fun report(texture: SurfaceTexture) {
        val size = bufferSize ?: return
        texture.setDefaultBufferSize(size.width, size.height)
        val target = surface ?: Surface(texture).also { surface = it }
        onReady(SurfacePreviewTarget(target, size.width, size.height))
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = report(texture)

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        onDestroyed()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (awaitingFrame) unfreeze()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(unfreezeFallback)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val FREEZE_SCALE = 4
        const val UNFREEZE_FALLBACK_MS = 1500L
    }
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
        // While recording the mode can't change; keep the space so the shutter doesn't jump.
        Box(Modifier.height(MODE_ROW_HEIGHT), contentAlignment = Alignment.Center) {
            if (!state.recording) ModeCarousel(selected = state.mode, onSelect = onModeChange)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (!state.recording) GalleryThumbnail(
                    state.lastCaptureUri,
                    saving = state.photosSaving > 0,
                    instant = state.instantThumbnail,
                    instantUri = state.instantThumbnailUri,
                )
            }
            if (state.mode.isVideo) {
                RecordButton(
                    recording = state.recording,
                    busy = state.isCapturing,
                    enabled = state.cameraState is CameraState.Previewing || state.recording,
                    onClick = onCapture,
                )
            } else {
                ShutterButton(
                    busy = state.isCapturing,
                    counting = state.countdown != null,
                    enabled = state.cameraState is CameraState.Previewing,
                    onClick = onCapture,
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (state.canSwitchCamera && !state.recording) {
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

/** Red record button; while recording, a red square to stop. */
@Composable
private fun RecordButton(recording: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(if (recording) R.string.cd_stop_recording else R.string.cd_start_recording)
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled && !busy, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (enabled) RECORDING_RED else Color.Gray
        if (recording) {
            Box(Modifier.size(28.dp).background(color, RoundedCornerShape(6.dp)))
        } else {
            Box(Modifier.size(60.dp).background(color, CircleShape))
        }
        if (busy) CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
    }
}

@Composable
private fun ErrorMessage(error: CameraError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (error) {
        CameraError.CAMERA_IN_USE -> R.string.error_in_use
        CameraError.CAMERA_DISABLED -> R.string.error_disabled
        CameraError.PERMISSION_DENIED -> R.string.error_permission
        CameraError.NO_CAMERA -> R.string.error_no_camera
        CameraError.DEVICE_ERROR -> R.string.error_device
    }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(onClick = onRetry)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(text), color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.error_tap_to_retry), color = Color.White.copy(alpha = 0.7f))
    }
}

/** Tells the view model when the app comes to the front or goes to the background. */
@Composable
private fun TrackForeground(onForegroundChanged: (Boolean) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onForegroundChanged)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> callback(true)
                Lifecycle.Event.ON_STOP -> callback(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** Camera apps own the whole screen: the status bar hides, and a swipe from the top shows it briefly. */
@Composable
private fun HideStatusBar() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { controller.show(WindowInsetsCompat.Type.statusBars()) }
    }
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
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)
        onDispose { sound.release() }
    }
    LaunchedEffect(viewModel, sound) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CameraEffect.SHUTTER -> sound.play(MediaActionSound.SHUTTER_CLICK)
                CameraEffect.TIMER_TICK -> sound.play(MediaActionSound.FOCUS_COMPLETE)
                CameraEffect.RECORDING_STARTED -> sound.play(MediaActionSound.START_VIDEO_RECORDING)
                CameraEffect.RECORDING_STOPPED -> sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
            }
        }
    }
}

/** The screen doesn't sleep in the middle of a recording. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Asks for the microphone once, the first time a video mode with sound is opened. Declining is
 * fine: videos are then recorded silent.
 */
@Composable
private fun RequestMicrophone(wanted: Boolean) {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(wanted) {
        if (!wanted || asked) return@LaunchedEffect
        asked = true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
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
private val MODE_ROW_HEIGHT = 36.dp
