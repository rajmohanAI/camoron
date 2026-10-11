package com.camoron.feature.camera

import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camoron.core.common.SurfacePreviewTarget
import com.camoron.domain.model.CameraError
import com.camoron.domain.model.CameraState
import com.camoron.domain.model.PixelSize
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun CameraScreen(viewModel: CameraViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val rotation = rememberDeviceRotation()

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
            .safeDrawingPadding(),
    ) {
        state.previewSize?.let { size ->
            CameraPreview(
                previewSize = size,
                onSurfaceReady = viewModel::onSurfaceReady,
                onSurfaceDestroyed = viewModel::onSurfaceDestroyed,
                onTap = viewModel::focusAt,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        (state.cameraState as? CameraState.Error)?.let { ErrorMessage(it.error, Modifier.align(Alignment.Center)) }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val previewing = state.cameraState as? CameraState.Previewing
            if (state.mode == CaptureMode.PRO && previewing != null) {
                ProControls(
                    capabilities = previewing.capabilities,
                    settings = state.settings,
                    liveExposure = previewing.liveExposure,
                    viewModel = viewModel,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)),
                )
            }
            BottomBar(
                state = state,
                onModeChange = viewModel::setMode,
                onCapture = { viewModel.capture(rotation.get()) },
                onSwitch = viewModel::switchCamera,
            )
        }
    }
}

/**
 * SurfaceView-backed preview. The buffer is fixed to the camera's preview size, and the view is
 * laid out at the same (portrait) aspect ratio so the image is never stretched.
 */
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
    val shortEdge = minOf(previewSize.width, previewSize.height)
    val longEdge = maxOf(previewSize.width, previewSize.height)

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
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(shortEdge.toFloat() / longEdge)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onTap(offset.x / size.width, offset.y / size.height)
                }
            },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomBar(
    state: CameraUiState,
    onModeChange: (CaptureMode) -> Unit,
    onCapture: () -> Unit,
    onSwitch: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SingleChoiceSegmentedButtonRow {
            CaptureMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { onModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, CaptureMode.entries.size),
                ) {
                    Text(stringResource(if (mode == CaptureMode.PHOTO) R.string.mode_photo else R.string.mode_pro))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            ShutterButton(
                busy = state.isCapturing,
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
private fun ShutterButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.cd_shutter)
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .background(if (enabled) Color.White else Color.Gray, CircleShape)
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

/** Tracks the phone's physical rotation (0/90/180/270) for JPEG orientation while the UI stays portrait. */
@Composable
private fun rememberDeviceRotation(): AtomicInteger {
    val context = LocalContext.current
    val rotation = remember { AtomicInteger(0) }
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) rotation.set(orientation)
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    return rotation
}
