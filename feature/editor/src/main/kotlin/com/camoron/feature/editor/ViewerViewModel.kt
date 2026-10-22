package com.camoron.feature.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camoron.domain.repository.PhotoLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ViewerUiState(
    val loading: Boolean = true,
    val photos: List<String> = emptyList(),
    val startIndex: Int = 0,
)

/** The viewer's list: Camoron's photos, newest first, opened at the one that was tapped. */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val library: PhotoLibrary,
) : ViewModel() {

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()
    private var opened: String? = null

    fun open(startUri: String) {
        if (opened == startUri) return
        opened = startUri
        viewModelScope.launch {
            val recent = library.recentPhotos(MAX_PHOTOS)
            val index = recent.indexOfFirst { sameItem(it, startUri) }
            // A photo that isn't one of Camoron's (or isn't indexed yet) is shown on its own.
            val photos = if (index >= 0) recent else listOf(startUri)
            _state.update { ViewerUiState(loading = false, photos = photos, startIndex = index.coerceAtLeast(0)) }
        }
    }

    private companion object {
        const val MAX_PHOTOS = 500

        /** Gallery URIs for one photo can differ in volume name; the item's id is what matters. */
        fun sameItem(a: String, b: String): Boolean {
            if (a == b) return true
            val ua = Uri.parse(a)
            val ub = Uri.parse(b)
            return ua.authority == ub.authority && ua.lastPathSegment != null && ua.lastPathSegment == ub.lastPathSegment &&
                ua.pathSegments.contains("images") == ub.pathSegments.contains("images")
        }
    }
}
