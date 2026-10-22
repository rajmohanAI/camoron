package com.camoron.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camoron.core.common.DiagnosticLog
import com.camoron.core.imaging.TryOnPainter
import com.camoron.domain.model.Adjustment
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.CropAspect
import com.camoron.domain.model.CropMath
import com.camoron.domain.model.CropRectF
import com.camoron.domain.model.EditColour
import com.camoron.domain.model.EditCrop
import com.camoron.domain.model.EditLook
import com.camoron.domain.model.FaceScene
import com.camoron.domain.model.PhotoEdit
import com.camoron.domain.model.TryOn
import com.camoron.domain.repository.PhotoEditor
import com.camoron.domain.repository.PhotoLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class EditorTab { LOOKS, ADJUST, CROP, TRY_ON }

enum class TryOnSection { GLASSES, MASKS, HAIR }

data class EditorUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val edit: PhotoEdit = PhotoEdit(),
    /** The edited photo as it will be saved. */
    val preview: ImageBitmap? = null,
    /** The same crop without colour changes, shown while the photo is held. */
    val original: ImageBitmap? = null,
    /** On the crop tab: the whole turned, straightened picture the crop is chosen on. */
    val frame: ImageBitmap? = null,
    val lookThumbnails: Map<EditLook, ImageBitmap> = emptyMap(),
    val tab: EditorTab = EditorTab.LOOKS,
    val tool: Adjustment = Adjustment.EXPOSURE,
    val aspect: CropAspect = CropAspect.FREE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val saving: Boolean = false,
    val savedUri: String? = null,
    val saveFailed: Boolean = false,
    val tryOnSection: TryOnSection = TryOnSection.GLASSES,
    /** Looking for faces and hair, the first time the Try on tab opens. */
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    val faceCount: Int = 0,
    val hairFound: Boolean = false,
) {
    val hasChanges: Boolean get() = !edit.isIdentity
}

/**
 * The photo editor. Edits are previewed live on a screen-sized copy; saving renders them onto the
 * full-size photo and stores a new copy, never touching the original.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val library: PhotoLibrary,
    private val editor: PhotoEditor,
    private val scanner: SceneScanner,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val edits = MutableStateFlow(PhotoEdit())
    private val history = mutableListOf(PhotoEdit())
    private var historyIndex = 0

    private var openedUri: String? = null
    private var photo: CapturedImage? = null
    private var working: Bitmap? = null
    private var scene: FaceScene = FaceScene.EMPTY

    /** The crop rectangle when a straighten drag began, so repeated adjustments don't keep shrinking it. */
    private var straightenBase: CropRectF? = null

    fun open(uri: String) {
        if (openedUri == uri) return
        openedUri = uri
        viewModelScope.launch {
            try {
                val image = library.read(uri)
                val bitmap = withContext(Dispatchers.Default) { PhotoDecode.upright(image.bytes, PREVIEW_EDGE) }
                    ?: error("Could not decode $uri")
                photo = image
                working = bitmap
                _state.update { it.copy(loading = false) }
                startRendering(bitmap)
                if (_state.value.tab == EditorTab.TRY_ON) scanOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.record(TAG, "Couldn't open $uri for editing", e)
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private fun startRendering(working: Bitmap) {
        // The edited preview: re-rendered for every change, dropping frames a newer change overtakes.
        viewModelScope.launch(Dispatchers.Default) {
            var cutFor: Pair<EditCrop, TryOn>? = null
            var cut: Bitmap? = null
            var frameFor: Pair<EditCrop, TryOn>? = null
            var frame: Bitmap? = null
            var dressedFor: TryOn? = null
            var dressed: Bitmap = working
            combine(edits, _state.map { it.tab == EditorTab.CROP }.distinctUntilChanged()) { edit, cropping -> edit to cropping }
                .collectLatest { (edit, cropping) ->
                    val colour = EditColour.of(edit)
                    // Try-ons are painted onto the upright photo once per change of what is tried on.
                    if (dressedFor != edit.tryOn) {
                        dressed = if (edit.tryOn.isNone) {
                            working
                        } else {
                            working.copy(Bitmap.Config.ARGB_8888, true).also { TryOnPainter.paint(it, edit.tryOn, scene) }
                        }
                        dressedFor = edit.tryOn
                    }
                    if (cropping) {
                        // The frame only depends on the turn, flip and straightening, not on the crop rectangle.
                        val key = edit.crop.copy(left = 0f, top = 0f, right = 1f, bottom = 1f) to edit.tryOn
                        val base = frame.takeIf { frameFor == key } ?: EditPreview.frame(dressed, edit.crop).also {
                            frame = it
                            frameFor = key
                        }
                        val shown = EditPreview.colour(base, colour)
                        _state.update { it.copy(frame = shown.asImageBitmap()) }
                    } else {
                        val key = edit.crop to edit.tryOn
                        val base = cut.takeIf { cutFor == key } ?: EditPreview.cut(dressed, edit.crop).also {
                            cut = it
                            cutFor = key
                        }
                        val shown = EditPreview.colour(base, colour)
                        _state.update { it.copy(preview = shown.asImageBitmap(), original = base.asImageBitmap()) }
                    }
                }
        }
        // Look thumbnails follow the crop, so each shows the photo as it is framed.
        viewModelScope.launch(Dispatchers.Default) {
            edits.map { it.crop }.distinctUntilChanged().collectLatest { crop ->
                val small = EditPreview.scaled(EditPreview.cut(working, crop), THUMBNAIL_EDGE)
                val thumbnails = EditLook.entries.associateWith { look ->
                    EditPreview.colour(small, EditColour.of(PhotoEdit(look = look))).asImageBitmap()
                }
                _state.update { it.copy(lookThumbnails = thumbnails) }
            }
        }
    }

    fun selectTab(tab: EditorTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == EditorTab.TRY_ON) scanOnce()
    }

    fun selectTryOnSection(section: TryOnSection) = _state.update { it.copy(tryOnSection = section) }

    /** Changes what is being tried on, as one undo step. */
    fun setTryOn(tryOn: TryOn) {
        show(edits.value.copy(tryOn = tryOn))
        commit()
    }

    /** Hair colour strength while the slider moves; [commit] ends it. */
    fun setHairStrength(value: Float) = show(edits.value.copy(tryOn = edits.value.tryOn.copy(hairStrength = value.coerceIn(0f, 1f))))

    private fun scanOnce() {
        val bitmap = working ?: return
        val current = _state.value
        if (current.scanned || current.scanning) return
        _state.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.Default) { scanner.scan(bitmap) }
            scene = found
            _state.update {
                it.copy(scanning = false, scanned = true, faceCount = found.faces.size, hairFound = found.hair != null)
            }
        }
    }

    override fun onCleared() {
        scanner.close()
    }

    fun selectTool(tool: Adjustment) = _state.update { it.copy(tool = tool) }

    fun selectLook(look: EditLook) {
        val edit = edits.value
        if (edit.look == look) return
        show(edit.copy(look = look, lookStrength = 1f))
        commit()
    }

    fun setLookStrength(value: Float) = show(edits.value.copy(lookStrength = value.coerceIn(0f, 1f)))

    fun setAdjustment(adjustment: Adjustment, value: Float) = show(edits.value.with(adjustment, value))

    fun resetAdjustment(adjustment: Adjustment) {
        show(edits.value.with(adjustment, 0f))
        commit()
    }

    fun setAspect(aspect: CropAspect) {
        _state.update { it.copy(aspect = aspect) }
        if (aspect == CropAspect.FREE) return
        val crop = edits.value.crop
        val (w, h) = frameSize(crop) ?: return
        show(edits.value.copy(crop = crop.withRect(CropMath.centred(ratio(aspect, w, h), w, h, crop.straighten))))
        commit()
    }

    fun turn() {
        var crop = CropMath.turnClockwise(edits.value.crop)
        val aspect = _state.value.aspect
        if (aspect != CropAspect.FREE) {
            // A locked shape stays that shape on the turned photo.
            frameSize(crop)?.let { (w, h) -> crop = crop.withRect(CropMath.centred(ratio(aspect, w, h), w, h, crop.straighten)) }
        }
        show(edits.value.copy(crop = crop))
        commit()
    }

    fun flip() {
        show(edits.value.copy(crop = CropMath.flip(edits.value.crop)))
        commit()
    }

    fun setStraighten(degrees: Float) {
        val crop = edits.value.crop
        val (w, h) = frameSize(crop) ?: return
        val base = straightenBase ?: crop.rect.also { straightenBase = it }
        val angle = degrees.coerceIn(-CropMath.MAX_STRAIGHTEN, CropMath.MAX_STRAIGHTEN)
        show(edits.value.copy(crop = crop.copy(straighten = angle).withRect(CropMath.fitInside(base, w, h, angle))))
    }

    fun dragCrop(handle: CropMath.Handle, dx: Float, dy: Float) {
        val crop = edits.value.crop
        val (w, h) = frameSize(crop) ?: return
        val aspect = _state.value.aspect
        val ratio = if (aspect == CropAspect.FREE) null else ratio(aspect, w, h)
        show(edits.value.copy(crop = crop.withRect(CropMath.drag(crop.rect, handle, dx, dy, w, h, crop.straighten, ratio))))
    }

    fun resetCrop() {
        _state.update { it.copy(aspect = CropAspect.FREE) }
        show(edits.value.copy(crop = EditCrop()))
        commit()
    }

    /** Ends a slider or drag: the change becomes one undo step. */
    fun commit() {
        straightenBase = null
        val edit = edits.value
        if (edit == history[historyIndex]) return
        while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
        history += edit
        if (history.size > MAX_HISTORY) history.removeAt(0)
        historyIndex = history.lastIndex
        updateHistoryFlags()
    }

    fun undo() {
        if (historyIndex == 0) return
        historyIndex--
        show(history[historyIndex])
        updateHistoryFlags()
    }

    fun redo() {
        if (historyIndex == history.lastIndex) return
        historyIndex++
        show(history[historyIndex])
        updateHistoryFlags()
    }

    fun save() {
        val image = photo ?: return
        val edit = edits.value
        if (edit.isIdentity || _state.value.saving) return
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                val rendered = editor.render(image, edit, scene)
                val uri = library.saveEdited(rendered)
                _state.update { it.copy(saving = false, savedUri = uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                DiagnosticLog.record(TAG, "Not enough memory to save the edit", e)
                _state.update { it.copy(saving = false, saveFailed = true) }
            } catch (e: Exception) {
                DiagnosticLog.record(TAG, "Couldn't save the edit", e)
                _state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    fun saveFailureShown() = _state.update { it.copy(saveFailed = false) }

    private fun show(edit: PhotoEdit) {
        edits.value = edit
        _state.update { it.copy(edit = edit) }
    }

    private fun updateHistoryFlags() =
        _state.update { it.copy(canUndo = historyIndex > 0, canRedo = historyIndex < history.lastIndex) }

    /** The turned picture's size, which the crop rectangle is measured against. */
    private fun frameSize(crop: EditCrop): Pair<Float, Float>? {
        val bitmap = working ?: return null
        val (w, h) = CropMath.turnedSize(bitmap.width, bitmap.height, crop)
        return w.toFloat() to h.toFloat()
    }

    private fun ratio(aspect: CropAspect, width: Float, height: Float): Float? =
        if (aspect == CropAspect.ORIGINAL) width / height else aspect.ratio

    private companion object {
        const val TAG = "Editor"
        const val PREVIEW_EDGE = 1600
        const val THUMBNAIL_EDGE = 180
        const val MAX_HISTORY = 60
    }
}
