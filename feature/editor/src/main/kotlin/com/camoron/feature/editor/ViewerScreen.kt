package com.camoron.feature.editor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Camoron's own photo viewer: swipe through your Camoron photos, pinch or double-tap to zoom, and
 * edit, share or open one in the gallery app.
 */
@Composable
fun ViewerScreen(
    startUri: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    LaunchedEffect(startUri) { viewModel.open(startUri) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val photos = state.photos
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
            photos.isEmpty() -> Text(
                stringResource(R.string.no_photos),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            else -> {
                val pager = rememberPagerState(initialPage = state.startIndex) { photos.size }
                var zoomed by remember { mutableStateOf(false) }
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = !zoomed,
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    ZoomablePhoto(
                        uri = photos[page],
                        onZoomChange = { if (page == pager.currentPage) zoomed = it },
                    )
                }
                val current = photos.getOrNull(pager.currentPage)
                // Top: back. Bottom: what you can do with the photo on screen.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back), tint = Color.White)
                    }
                }
                if (current != null) {
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                            .navigationBarsPadding()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ViewerAction(Icons.Filled.Share, stringResource(R.string.share)) { share(context, current) }
                        ViewerAction(Icons.Filled.Edit, stringResource(R.string.edit), highlight = true) { onEdit(current) }
                        ViewerAction(Icons.Filled.PhotoLibrary, stringResource(R.string.gallery)) { openInGallery(context, current) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, highlight: Boolean = false, onClick: () -> Unit) {
    val color = if (highlight) MaterialTheme.colorScheme.primary else Color.White
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, color = color, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * One photo, fitted to the screen. Two fingers zoom and pan; once zoomed, one finger pans too and
 * the pager stops swiping. Double-tap zooms in or back out.
 */
@Composable
private fun ZoomablePhoto(uri: String, onZoomChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { PhotoDecode.upright(context, Uri.parse(uri), VIEWER_EDGE)?.asImageBitmap() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun setZoom(s: Float, o: Offset) {
        val wasZoomed = scale > 1f
        scale = s
        offset = clamp(o, s)
        if ((s > 1f) != wasZoomed) onZoomChange(s > 1f)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            setZoom(newScale, offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        setZoom(1f, Offset.Zero)
                    } else {
                        // Zoom towards where was tapped.
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        setZoom(DOUBLE_TAP_ZOOM, (centre - tap) * (DOUBLE_TAP_ZOOM - 1f))
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) {
            CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
        } else {
            Image(
                image,
                contentDescription = stringResource(R.string.cd_photo),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

private fun share(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("image/jpeg")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

private fun openInGallery(context: Context, uri: String) {
    val parsed = Uri.parse(uri)
    // Review opens the photo within the camera roll, so you can swipe to your other photos.
    for (action in listOf(MediaStore.ACTION_REVIEW, Intent.ACTION_VIEW)) {
        try {
            context.startActivity(Intent(action).setDataAndType(parsed, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return
        } catch (e: ActivityNotFoundException) {
            // Try the next way of opening it.
        }
    }
}

private const val VIEWER_EDGE = 2560
private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
