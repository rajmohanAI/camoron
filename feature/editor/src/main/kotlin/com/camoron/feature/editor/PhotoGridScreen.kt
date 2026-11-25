package com.camoron.feature.editor

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.camoron.domain.repository.PhotoLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PhotoGridUiState(val loading: Boolean = true, val photos: List<String> = emptyList())

/** Camoron's own photos, newest first, for the Gallery and Edit tabs. */
@HiltViewModel
class PhotoGridViewModel @Inject constructor(
    private val library: PhotoLibrary,
) : ViewModel() {
    private val _state = MutableStateFlow(PhotoGridUiState())
    val state: StateFlow<PhotoGridUiState> = _state.asStateFlow()

    /** Called whenever the tab is shown, so photos taken since appear. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = PhotoGridUiState(loading = false, photos = library.recentPhotos(MAX_PHOTOS))
        }
    }

    private companion object {
        const val MAX_PHOTOS = 500
    }
}

/** The Gallery tab: Camoron's photos in a grid. A tap opens the photo in the viewer. */
@Composable
fun GalleryScreen(onOpenPhoto: (String) -> Unit, bottomPadding: Dp = 0.dp) {
    PhotoGrid(
        title = stringResource(R.string.gallery),
        subtitle = null,
        onPhoto = onOpenPhoto,
        bottomPadding = bottomPadding,
    )
}

/**
 * The Edit tab: pick one of Camoron's photos to edit, or any photo on the phone through the system
 * photo picker.
 */
@Composable
fun EditHomeScreen(onEdit: (String) -> Unit, bottomPadding: Dp = 0.dp) {
    val pickAny = rememberPhotoPicker(onEdit)
    PhotoGrid(
        title = stringResource(R.string.edit),
        subtitle = stringResource(R.string.edit_home_hint),
        onPhoto = onEdit,
        bottomPadding = bottomPadding,
        action = {
            TextButton(onClick = pickAny) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(stringResource(R.string.all_photos))
            }
        },
    )
}

/** Opens the system photo picker for one image; [onPicked] gets its URI. */
@Composable
internal fun rememberPhotoPicker(onPicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onPicked(it.toString()) }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
private fun PhotoGrid(
    title: String,
    subtitle: String?,
    onPhoto: (String) -> Unit,
    bottomPadding: Dp,
    action: (@Composable () -> Unit)? = null,
    viewModel: PhotoGridViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f)) }
                    }
                    action?.invoke()
                }
            }
            items(state.photos, key = { it }) { uri ->
                PhotoCell(uri, onClick = { onPhoto(uri) })
            }
        }
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
            state.photos.isEmpty() -> Text(
                stringResource(R.string.no_photos),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
    }
}

@Composable
private fun PhotoCell(uri: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { thumbnail(context, Uri.parse(uri)) }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
    ) {
        bitmap?.let {
            Image(it, contentDescription = stringResource(R.string.cd_photo), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun thumbnail(context: Context, uri: Uri): ImageBitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null).asImageBitmap()
    } else {
        PhotoDecode.upright(context, uri, THUMBNAIL_PX)?.asImageBitmap()
    }
}.getOrNull()

private const val COLUMNS = 3
private const val THUMBNAIL_PX = 360
