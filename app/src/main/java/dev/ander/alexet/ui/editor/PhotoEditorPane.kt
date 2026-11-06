package dev.ander.alexet.ui.editor

import androidx.compose.foundation.layout.requiredSize
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.ander.alexet.ui.components.AnimatedVisibility
import androidx.compose.animation.core.Spring
import dev.ander.alexet.ui.components.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.ui.draw.clipToBounds
import dev.ander.alexet.ui.theme.AlexetTheme
import dev.ander.alexet.foldable.rememberFoldableState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.ander.alexet.R
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.AlbumIds
import dev.ander.alexet.data.BlurType
import dev.ander.alexet.data.BrushStroke
import dev.ander.alexet.data.CropRect
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.ImageEditEngine
import dev.ander.alexet.data.ImageEditState
import dev.ander.alexet.data.NormPoint
import dev.ander.alexet.data.StickerOverlay
import dev.ander.alexet.data.TextOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.roundToInt

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ander.alexet.ui.gallery.FolderCover

data class EditorHistoryEntry(
    val editState: ImageEditState,
    val workingBitmap: Bitmap? = null,
    val committedCrop: CropRect = CropRect(),
)

/** Overall undo/redo depth for lightweight (state-only) entries. */
private const val MAX_EDITOR_HISTORY = 30
/** Longer side of the photo copy the effects run on while their slider is dragged. */
private const val DRAG_PREVIEW_MAX_DIM = 720
/** Cap full-bitmap history entries to limit editor OOM risk. */
private const val MAX_BITMAP_HISTORY = 3

/**
 * Bitmaps a live preview is reading on another thread right now (main thread only). Recycling one
 * of them crashed the preview's blur; such a bitmap is recycled when its preview ends instead.
 */
private val previewBusyBitmaps = HashMap<Bitmap, Int>()

/** Live previews are computed one at a time (see the preview effect). */
private val previewMutex = Mutex()
private val recycleAfterPreview = HashSet<Bitmap>()

private fun recycleEvictedBitmaps(
    candidates: Iterable<Bitmap?>,
    keepAlive: Set<Bitmap?>,
) {
    val seen = HashSet<Bitmap>()
    for (bmp in candidates) {
        if (bmp == null || bmp in keepAlive || bmp in seen) continue
        seen += bmp
        if (previewBusyBitmaps.containsKey(bmp)) {
            recycleAfterPreview += bmp
            continue
        }
        if (!bmp.isRecycled) {
            runCatching { bmp.recycle() }
        }
    }
}

/**
 * Trim history depth and drop oldest bitmap-backed payloads beyond [MAX_BITMAP_HISTORY],
 * recycling bitmaps that are no longer referenced by [keepAlive] or the returned list.
 */
private fun pruneEditorHistory(
    stack: List<EditorHistoryEntry>,
    keepAlive: Set<Bitmap?>,
): List<EditorHistoryEntry> {
    val trimmed = stack.takeLast(MAX_EDITOR_HISTORY)
    val dropped = if (stack.size > trimmed.size) stack.dropLast(trimmed.size) else emptyList()

    val bitmapIndices = trimmed.mapIndexedNotNull { index, entry ->
        index.takeIf { entry.workingBitmap != null }
    }
    // An entry without its bitmap would restore committedCrop over the wrong image, so instead of
    // stripping the bitmap the whole older part of the history (up to that entry) is dropped.
    val cutIndex = if (bitmapIndices.size > MAX_BITMAP_HISTORY) {
        bitmapIndices[bitmapIndices.size - MAX_BITMAP_HISTORY - 1]
    } else {
        -1
    }
    val next = if (cutIndex >= 0) trimmed.drop(cutIndex + 1) else trimmed
    val cut = if (cutIndex >= 0) trimmed.take(cutIndex + 1) else emptyList()

    val stillHeld = keepAlive.toMutableSet()
    next.forEach { entry -> stillHeld += entry.workingBitmap }
    val toRecycle = buildList {
        dropped.forEach { add(it.workingBitmap) }
        cut.forEach { add(it.workingBitmap) }
    }
    recycleEvictedBitmaps(toRecycle, stillHeld)
    return next
}

private enum class DragHandle {
    NONE,
    BODY,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
}

private fun computeAspectCrop(imageAspect: Float, targetRatio: Float?): CropRect {
    if (targetRatio == null || targetRatio <= 0.01f || imageAspect <= 0.01f) {
        return CropRect(0f, 0f, 1f, 1f)
    }
    return if (imageAspect > targetRatio) {
        val wNorm = (targetRatio / imageAspect).coerceIn(0.05f, 1f)
        val left = ((1f - wNorm) / 2f).coerceIn(0f, 1f - wNorm)
        CropRect(left = left, top = 0f, right = left + wNorm, bottom = 1f)
    } else {
        val hNorm = (imageAspect / targetRatio).coerceIn(0.05f, 1f)
        val top = ((1f - hNorm) / 2f).coerceIn(0f, 1f - hNorm)
        CropRect(left = 0f, top = top, right = 1f, bottom = top + hNorm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditorPane(
    media: GalleryMedia,
    albums: List<Album> = emptyList(),
    allAlbums: List<Album> = albums,
    hiddenAlbumIds: Set<Long> = emptySet(),
    favoriteMediaIds: Set<Long> = emptySet(),
    availableMedia: List<GalleryMedia> = emptyList(),
    onRevealHidden: (Boolean) -> Unit = {},
    wide: Boolean = false,
    isSaving: Boolean,
    /** 0..1 while a GIF is saved frame by frame; null otherwise. */
    saveProgress: Float? = null,
    pendingStickerUri: Uri? = null,
    onStickerConsumed: () -> Unit = {},
    currentTab: PhotoEditorTab = PhotoEditorTab.ASPECT,
    onTabChanged: (PhotoEditorTab) -> Unit = {},
    allowOverwrite: Boolean = true,
    onDirtyChange: (Boolean) -> Unit = {},
    onSave: (ImageEditState, Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    var editState by remember { mutableStateOf(ImageEditState()) }
    var undoStack by remember { mutableStateOf(listOf<EditorHistoryEntry>()) }
    var redoStack by remember { mutableStateOf(listOf<EditorHistoryEntry>()) }
    var stateBeforeContinuousChange by remember { mutableStateOf<ImageEditState?>(null) }
    var showStickerPickerDialog by remember { mutableStateOf(false) }
    var committedCrop by remember { mutableStateOf(CropRect()) }
    var selectedAspectOption by remember { mutableStateOf(AspectOption.CUSTOM) }
    // Bumped by Reset: re-decodes the original so a committed crop is undone too.
    var decodeGeneration by remember { mutableIntStateOf(0) }
    // Set while Reset reloads the uncropped image: a crop applied meanwhile would cut the old
    // (cropped) bitmap but be recorded as a crop of the full image.
    var resetDecodePending by remember { mutableStateOf(false) }

    // Fast initial bounds estimate without synchronous Main-thread I/O
    val initialSize = remember(media.id, media.width, media.height) {
        if (media.width > 0 && media.height > 0) {
            Size(media.width.toFloat(), media.height.toFloat())
        } else {
            null
        }
    }

    var decodedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val editorScope = rememberCoroutineScope()
    var cropInProgress by remember { mutableStateOf(false) }
    var decodedImageSize by remember(media.uri) { mutableStateOf(initialSize) }
    var liveProcessedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun pushUndoState(
        state: ImageEditState = editState,
        bmp: Bitmap? = null,
        crop: CropRect = committedCrop,
    ) {
        val keepAlive = setOf(decodedBitmap, bmp, liveProcessedBitmap)
        undoStack = pruneEditorHistory(undoStack + EditorHistoryEntry(state, bmp, crop), keepAlive)
        recycleEvictedBitmaps(redoStack.map { it.workingBitmap }, keepAlive + undoStack.map { it.workingBitmap }.toSet())
        redoStack = emptyList()
    }

    fun onContinuousChangeStart() {
        if (stateBeforeContinuousChange == null) {
            stateBeforeContinuousChange = editState
        }
    }

    fun onContinuousChangeEnd() {
        val prev = stateBeforeContinuousChange
        if (prev != null && prev != editState) {
            pushUndoState(prev, null, committedCrop)
        }
        stateBeforeContinuousChange = null
    }

    fun recordDiscreteChange(newState: ImageEditState, bmp: Bitmap? = null) {
        if (newState != editState || bmp != null) {
            pushUndoState(editState, bmp, committedCrop)
            editState = newState
        }
    }

    val undoStackForDispose = rememberUpdatedState(undoStack)
    val redoStackForDispose = rememberUpdatedState(redoStack)
    val decodedForDispose = rememberUpdatedState(decodedBitmap)
    val liveForDispose = rememberUpdatedState(liveProcessedBitmap)
    DisposableEffect(Unit) {
        onDispose {
            ImageEditEngine.clearCaches()
            recycleEvictedBitmaps(
                (undoStackForDispose.value + redoStackForDispose.value).map { it.workingBitmap },
                emptySet(),
            )
            // A preview still computing on another thread keeps its base until it ends.
            recycleEvictedBitmaps(listOf(decodedForDispose.value, liveForDispose.value), emptySet())
        }
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val prev = undoStack.last()
            undoStack = undoStack.dropLast(1)
            val keepAlive = setOf(decodedBitmap, prev.workingBitmap, liveProcessedBitmap)
            // Only a bitmap-changing entry (crop/reset) needs the current bitmap to redo it.
            redoStack = pruneEditorHistory(
                redoStack + EditorHistoryEntry(editState, if (prev.workingBitmap != null) decodedBitmap else null, committedCrop),
                keepAlive + undoStack.map { it.workingBitmap }.toSet(),
            )
            editState = prev.editState
            committedCrop = prev.committedCrop
            selectedAspectOption = aspectOptionFor(prev.editState.aspectRatio)
            if (prev.workingBitmap != null) {
                decodedBitmap = prev.workingBitmap
                decodedImageSize = Size(prev.workingBitmap.width.toFloat(), prev.workingBitmap.height.toFloat())
                liveProcessedBitmap = null
            }
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val nextEntry = redoStack.last()
            redoStack = redoStack.dropLast(1)
            val keepAlive = setOf(decodedBitmap, nextEntry.workingBitmap, liveProcessedBitmap)
            undoStack = pruneEditorHistory(
                undoStack + EditorHistoryEntry(editState, if (nextEntry.workingBitmap != null) decodedBitmap else null, committedCrop),
                keepAlive + redoStack.map { it.workingBitmap }.toSet(),
            )
            editState = nextEntry.editState
            committedCrop = nextEntry.committedCrop
            selectedAspectOption = aspectOptionFor(nextEntry.editState.aspectRatio)
            if (nextEntry.workingBitmap != null) {
                decodedBitmap = nextEntry.workingBitmap
                decodedImageSize = Size(nextEntry.workingBitmap.width.toFloat(), nextEntry.workingBitmap.height.toFloat())
                liveProcessedBitmap = null
            }
        }
    }

    var currentAdjustParam by remember { mutableStateOf(AdjustParam.BRIGHTNESS) }
    var selectedOverlayId by remember { mutableStateOf<String?>(null) }
    var showSaveMenu by remember { mutableStateOf(false) }
    var isEditingTextPanelOpen by remember { mutableStateOf(false) }
    var textBeingEdited by remember { mutableStateOf<TextOverlay?>(null) }

    val stickerPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val newSticker = StickerOverlay(uri = uri)
            recordDiscreteChange(editState.copy(stickers = editState.stickers + newSticker))
            selectedOverlayId = newSticker.id
        }
    }

    LaunchedEffect(pendingStickerUri) {
        val uri = pendingStickerUri ?: return@LaunchedEffect
        val newSticker = StickerOverlay(uri = uri)
        recordDiscreteChange(editState.copy(stickers = editState.stickers + newSticker))
        selectedOverlayId = newSticker.id
        onStickerConsumed()
    }

    // Brush Tool state
    var brushMode by remember { mutableStateOf(BrushToolMode.NONE) }
    var brushColor by remember { mutableIntStateOf(android.graphics.Color.RED) }
    var brushSize by remember { mutableFloatStateOf(20f) }
    var brushHardness by remember { mutableFloatStateOf(1f) }
    var isColorPickerOpen by remember { mutableStateOf(false) }
    var isEyedropperActive by remember { mutableStateOf(false) }


    // Preview matches the display: a fixed 1024 cap with power-of-two sampling quartered
    // screenshots (2208 px -> 552 px). The saved file is decoded separately
    // (ImageEditEngine.decodeForExport: full size up to EXPORT_MAX_PIXELS).
    val previewMaxDim = remember {
        context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }.coerceIn(1024, 2560)
    }
    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(media.uri, media.absolutePath, decodeGeneration) {
        // Decoded but no longer wanted (editor closed or photo changed meanwhile): freed here,
        // it was never shown.
        var decodedNotShown: Bitmap? = null
        val bmp = try {
            withContext(Dispatchers.IO) {
                try {
                    ImageEditEngine.decodeSourceBitmap(
                        context,
                        media.absolutePath?.let { File(it) },
                        media.uri,
                        maxDim = previewMaxDim,
                    )
                } catch (e: Exception) {
                    // Deleted file or revoked URI grant (external "Edit"): not a reason to crash.
                    dev.ander.alexet.data.AppLog.w("PhotoEditorPane", "Cannot decode ${media.uri}", e)
                    null
                } catch (e: OutOfMemoryError) {
                    // A very large photo on a phone short of memory: "cannot open" instead of a crash.
                    dev.ander.alexet.data.AppLog.w("PhotoEditorPane", "Out of memory decoding ${media.uri}", e)
                    null
                }.also { decodedNotShown = it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            decodedNotShown?.let { if (!it.isRecycled) it.recycle() }
            throw e
        }
        resetDecodePending = false
        if (bmp == null) {
            android.widget.Toast.makeText(context, R.string.edit_open_failed, android.widget.Toast.LENGTH_SHORT).show()
            currentOnClose()
            return@LaunchedEffect
        }
        if (committedCrop != CropRect()) {
            // Undo restored a cropped bitmap while this full decode (after Reset) was running:
            // it is no longer wanted and would show the uncropped image over a cropped state.
            bmp.recycle()
            return@LaunchedEffect
        }
        decodedBitmap = bmp
        decodedImageSize = Size(bmp.width.toFloat(), bmp.height.toFloat())
        liveProcessedBitmap = null
        editState.aspectRatio?.let { ratio ->
            val w = if (editState.rotation % 180 == 0) bmp.width else bmp.height
            val h = if (editState.rotation % 180 == 0) bmp.height else bmp.width
            editState = editState.copy(crop = computeAspectCrop(w.toFloat() / h.toFloat(), ratio))
        }
    }

    // A small copy of the photo for the effects while a slider is dragged: on the full preview
    // (up to 2560 px) every value took longer than the next move, so nothing showed until release.
    // It is never drawn itself (the effects make new bitmaps), so GC takes the old one.
    var draggingBase by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(decodedBitmap) {
        draggingBase = null
        val full = decodedBitmap ?: return@LaunchedEffect
        draggingBase = withContext(Dispatchers.Default) {
            runCatching {
                if (full.isRecycled) return@runCatching null
                val scale = DRAG_PREVIEW_MAX_DIM.toFloat() / maxOf(full.width, full.height)
                if (scale >= 1f) return@runCatching null
                Bitmap.createScaledBitmap(
                    full,
                    (full.width * scale).roundToInt().coerceAtLeast(1),
                    (full.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            }.getOrNull()
        }
    }
    // ---- GIF: the editor plays it; the saved file is an animated GIF with the edits on each frame ----
    val isGif = remember(media.uri) {
        media.mimeType.equals("image/gif", ignoreCase = true) || media.displayName.endsWith(".gif", ignoreCase = true)
    }
    var gifFrames by remember(media.uri) { mutableStateOf<List<GifPreviewFrame>>(emptyList()) }
    LaunchedEffect(media.uri, media.absolutePath) {
        if (!isGif) return@LaunchedEffect
        gifFrames = withContext(Dispatchers.IO) {
            loadGifPreviewFrames(context, media.absolutePath?.let { File(it) }, media.uri, previewMaxDim)
        }
    }
    // Shadows, highlights, sharpness and blur are worked out on the CPU, too slow for every frame:
    // on their tabs the GIF stops on the current frame to show them.
    val hasCpuEffects = editState.shadows != 0f || editState.highlights != 0f ||
        abs(editState.sharpness) > 0.01f || (editState.blur.type != BlurType.NONE && editState.blur.radius > 0.5f)
    val gifPaused = isGif && hasCpuEffects &&
        (currentTab == PhotoEditorTab.PARAMETERS || currentTab == PhotoEditorTab.BLUR)
    val gifAnimating = isGif && gifFrames.size > 1 && !gifPaused
    var gifFrameIndex by remember(media.uri) { mutableIntStateOf(0) }
    LaunchedEffect(gifAnimating, gifFrames) {
        if (!gifAnimating) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(gifFrames.getOrNull(gifFrameIndex)?.delayMs?.toLong() ?: 100L)
            gifFrameIndex = (gifFrameIndex + 1) % gifFrames.size
        }
    }
    // The current frame, cut like the photo, as the base of the CPU effects while paused.
    var gifPausedBase by remember(media.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(gifPaused, gifFrames, committedCrop) {
        gifPausedBase = null
        if (!gifPaused) return@LaunchedEffect
        val frame = gifFrames.getOrNull(gifFrameIndex)?.bitmap?.takeIf { !it.isRecycled } ?: return@LaunchedEffect
        gifPausedBase = withContext(Dispatchers.Default) {
            runCatching { ImageEditEngine.applyCrop(frame, committedCrop) }.getOrNull()
        }
    }

    // Only a dragged effect slider: dragging brightness (a GPU colour filter) keeps the full preview.
    val sliderDragged = stateBeforeContinuousChange?.let { before ->
        before.shadows != editState.shadows || before.highlights != editState.highlights ||
            before.sharpness != editState.sharpness || before.blur != editState.blur
    } == true

    // A preview ran out of memory once: the message is not repeated on every slider move.
    var previewOomShown by remember { mutableStateOf(false) }

    // Live preview processing for Blur, Shadows, Highlights, and Sharpness
    LaunchedEffect(
        decodedBitmap,
        editState.blur,
        editState.shadows,
        editState.highlights,
        editState.sharpness,
        editState.rotation,
        editState.flipH,
        editState.flipV,
        sliderDragged,
        gifPausedBase,
    ) {
        val full = decodedBitmap ?: return@LaunchedEffect
        if (full.isRecycled) return@LaunchedEffect
        // A paused GIF: its current frame. Otherwise the small copy while dragging, the full
        // preview once the finger is lifted.
        val base = gifPausedBase?.takeIf { !it.isRecycled }
            ?: draggingBase?.takeIf { sliderDragged && !it.isRecycled }
            ?: full
        kotlinx.coroutines.delay(16)
        // Blur focus is placed on the displayed (rotated/flipped) image; the preview effects run on
        // the unrotated bitmap, so the focus is mapped back before processing.
        val previewState = editState.copy(
            blur = ImageEditEngine.blurDisplayToSource(editState.blur, editState.rotation, editState.flipH, editState.flipV),
        )
        // Computed off the main thread; assigned only after returning, so a newer slider value
        // cancels this effect and the stale result is dropped (withContext resumes cancellably).
        // Previous previews are not recycled by hand: the UI thread may still be drawing them.
        previewBusyBitmaps[base] = (previewBusyBitmaps[base] ?: 0) + 1
        val processed = try {
            // One preview computed at a time: a cancelled one stopped only between stages, so a
            // dragged slider ran several full-size passes at once. A newer value waiting here is
            // cancelled in turn, so only the latest one runs next.
            previewMutex.withLock {
                withContext(Dispatchers.Default) {
                    if (base.isRecycled) null else ImageEditEngine.generateLivePreviewBitmap(base, previewState, context) { !isActive }
                }
            }
        } catch (oom: OutOfMemoryError) {
            // Too big for the memory left (it closed the app): the last preview stays, the cached
            // copies are let go.
            dev.ander.alexet.data.AppLog.w("PhotoEditor", "Out of memory in the live preview", oom)
            ImageEditEngine.clearCaches()
            if (!previewOomShown) {
                previewOomShown = true
                android.widget.Toast.makeText(context, R.string.edit_preview_oom, android.widget.Toast.LENGTH_SHORT).show()
            }
            return@LaunchedEffect
        } finally {
            // Back on the main thread, after the computation really ended (cancelled or not).
            val left = (previewBusyBitmaps[base] ?: 1) - 1
            if (left > 0) {
                previewBusyBitmaps[base] = left
            } else {
                previewBusyBitmaps.remove(base)
                if (recycleAfterPreview.remove(base) && !base.isRecycled) base.recycle()
            }
        }
        liveProcessedBitmap = processed?.takeIf { !it.isRecycled }
    }

    val colorMatrixArray = remember(
        editState.brightness,
        editState.contrast,
        editState.saturation,
        editState.exposure,
        editState.gamma,
        editState.temperature,
    ) {
        ImageEditEngine.createColorMatrixArray(
            brightness = editState.brightness,
            contrast = editState.contrast,
            saturation = editState.saturation,
            exposure = editState.exposure,
            gamma = editState.gamma,
            temperature = editState.temperature,
        )
    }
    val composeColorFilter = remember(colorMatrixArray) {
        ColorFilter.colorMatrix(ColorMatrix(colorMatrixArray))
    }

    val intrinsicWidth = decodedImageSize?.width ?: media.width.toFloat().takeIf { it > 0f } ?: 1000f
    val intrinsicHeight = decodedImageSize?.height ?: media.height.toFloat().takeIf { it > 0f } ?: 1000f
    val isRotated90or270 = editState.rotation == 90 || editState.rotation == 270
    val effectiveImageAspect = if (isRotated90or270) intrinsicHeight / intrinsicWidth else intrinsicWidth / intrinsicHeight

    val hasChanges = editState.isModified || committedCrop != CropRect()
    LaunchedEffect(hasChanges) { onDirtyChange(hasChanges) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val requestClose = {
        if (hasChanges) showDiscardDialog = true else onClose()
    }
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.edit_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onClose()
                }) { Text(stringResource(R.string.edit_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    val doubleBack = dev.ander.alexet.ui.components.rememberDoubleBack()
    BackHandler {
        if (isColorPickerOpen) {
            isColorPickerOpen = false
        } else if (isEyedropperActive) {
            isEyedropperActive = false
        } else if (isEditingTextPanelOpen) {
            isEditingTextPanelOpen = false
        } else if (selectedOverlayId != null) {
            selectedOverlayId = null
        } else {
            // Leaving the editor takes two presses, like leaving the app.
            doubleBack { requestClose() }
        }
    }

    fun resetAll() {
        if (committedCrop != CropRect()) {
            // Bitmap entry: undo brings the cropped image back.
            pushUndoState(editState, decodedBitmap, committedCrop)
            committedCrop = CropRect()
            resetDecodePending = true
            decodeGeneration++
        } else {
            pushUndoState(editState, null, committedCrop)
        }
        editState = ImageEditState()
        selectedAspectOption = AspectOption.CUSTOM
    }

    fun finishCrop(bmp: Bitmap, cropped: Bitmap, sub: CropRect, subSource: CropRect) {
        if (cropped != bmp) {
            pushUndoState(editState, decodedBitmap)
            // committedCrop is kept in source space, so later rotations/flips cannot shift it.
            committedCrop = ImageEditEngine.composeCrop(committedCrop, subSource)
            val (remappedStickers, remappedStrokes, remappedTexts) = remapOverlaysToCrop(
                editState.stickers,
                editState.brushStrokes,
                editState.texts,
                sub,
            )
            decodedBitmap = cropped
            decodedImageSize = Size(cropped.width.toFloat(), cropped.height.toFloat())
            liveProcessedBitmap = null
            selectedAspectOption = AspectOption.CUSTOM
            editState = editState.copy(
                crop = CropRect(0f, 0f, 1f, 1f),
                aspectRatio = null,
                stickers = remappedStickers,
                brushStrokes = remappedStrokes,
                texts = remappedTexts,
                blur = ImageEditEngine.remapBlurToCrop(editState.blur, sub, effectiveImageAspect),
            )
        }
    }

    fun applyCurrentCrop() {
        if (resetDecodePending || cropInProgress) return
        val bmp = decodedBitmap?.takeIf { !it.isRecycled } ?: return
        // The frame is drawn on the displayed (rotated/flipped) image; the bitmap is unrotated.
        val stateAtStart = editState
        val sub = editState.crop
        val subSource = ImageEditEngine.cropDisplayToSource(sub, editState.rotation, editState.flipH, editState.flipV)
        cropInProgress = true
        // The bitmap is held like a preview's base: not recycled while it is copied.
        previewBusyBitmaps[bmp] = (previewBusyBitmaps[bmp] ?: 0) + 1
        editorScope.launch {
            // Copying the cut (up to ~6 MP) took the main thread for a frame or more.
            val cropped = try {
                withContext(Dispatchers.Default) { ImageEditEngine.applyCrop(bmp, subSource) }
            } finally {
                cropInProgress = false
                val left = (previewBusyBitmaps[bmp] ?: 1) - 1
                if (left > 0) {
                    previewBusyBitmaps[bmp] = left
                } else {
                    previewBusyBitmaps.remove(bmp)
                    if (recycleAfterPreview.remove(bmp) && !bmp.isRecycled) bmp.recycle()
                }
            }
            if (decodedBitmap !== bmp || editState != stateAtStart || bmp.isRecycled) {
                // Something else changed the image meanwhile: this cut is out of date.
                if (cropped !== bmp && !cropped.isRecycled) cropped.recycle()
                return@launch
            }
            finishCrop(bmp, cropped, sub, subSource)
        }
    }


    AlexetTheme(darkTheme = true) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            PhotoEditorMainLayout(
                media = media,
                editState = editState,
                onEditStateChange = { editState = it },
                onContinuousChangeStart = ::onContinuousChangeStart,
                onContinuousChangeEnd = ::onContinuousChangeEnd,
                onDiscreteChange = ::recordDiscreteChange,
                onApplyCrop = ::applyCurrentCrop,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                onUndo = ::undo,
                onRedo = ::redo,
                isSaving = isSaving,
                showSaveMenu = showSaveMenu,
                onShowSaveMenuChange = { showSaveMenu = it },
                allowOverwrite = allowOverwrite,
                onSave = { state, overwrite ->
                    // Export crops the SOURCE before rotating: committed crop (source space) composed
                    // with the pending frame mapped back from the displayed image.
                    val finalCrop = ImageEditEngine.composeCrop(
                        committedCrop,
                        ImageEditEngine.cropDisplayToSource(state.crop, state.rotation, state.flipH, state.flipV),
                    )
                    val pendingSub = state.crop
                    val (finalStickers, finalStrokes, finalTexts) = if (pendingSub.isValid &&
                        (pendingSub.left > 0.001f || pendingSub.top > 0.001f || pendingSub.right < 0.999f || pendingSub.bottom < 0.999f)
                    ) {
                        remapOverlaysToCrop(state.stickers, state.brushStrokes, state.texts, pendingSub)
                    } else {
                        Triple(state.stickers, state.brushStrokes, state.texts)
                    }
                    val pendingCropActive = pendingSub.isValid &&
                        (pendingSub.left > 0.001f || pendingSub.top > 0.001f || pendingSub.right < 0.999f || pendingSub.bottom < 0.999f)
                    onSave(
                        state.copy(
                            crop = finalCrop,
                            stickers = finalStickers,
                            brushStrokes = finalStrokes,
                            texts = finalTexts,
                            blur = if (pendingCropActive) ImageEditEngine.remapBlurToCrop(state.blur, pendingSub, effectiveImageAspect) else state.blur,
                        ),
                        overwrite,
                    )
                },
                onClose = requestClose,
                hasChanges = hasChanges,
                onResetAll = ::resetAll,
                currentTab = currentTab,
                onTabSelected = { tab ->
                    onTabChanged(tab)
                    selectedOverlayId = null
                    brushMode = BrushToolMode.NONE
                },
                currentAdjustParam = currentAdjustParam,
                onAdjustParamSelected = { currentAdjustParam = it },
                selectedAspectOption = selectedAspectOption,
                onAspectOptionSelected = { selectedAspectOption = it },
                effectiveImageAspect = effectiveImageAspect,
                isRotated90or270 = isRotated90or270,
                composeColorFilter = composeColorFilter,
                colorMatrixArray = colorMatrixArray,
                decodedBitmap = decodedBitmap,
                // A paused GIF shows its current frame (with the effects once worked out).
                liveProcessedBitmap = liveProcessedBitmap ?: gifPausedBase,
                gifFrame = if (gifAnimating) ({ gifFrames.getOrNull(gifFrameIndex)?.bitmap }) else null,
                gifCrop = committedCrop,
                onDecodedImageSize = { decodedImageSize = it },
                selectedOverlayId = selectedOverlayId,
                onSelectOverlay = { selectedOverlayId = it },
                brushMode = brushMode,
                onBrushModeChange = { brushMode = it },
                brushColor = brushColor,
                onBrushColorChange = { brushColor = it },
                brushSize = brushSize,
                onBrushSizeChange = { brushSize = it },
                brushHardness = brushHardness,
                onBrushHardnessChange = { brushHardness = it },
                isColorPickerOpen = isColorPickerOpen,
                onOpenColorPicker = { isColorPickerOpen = true },
                isEyedropperActive = isEyedropperActive,
                onEyedropperActiveChange = { isEyedropperActive = it },
                onOpenTextPanel = { item ->
                    textBeingEdited = item
                    isEditingTextPanelOpen = true
                },
                onAddStickerClick = {
                    showStickerPickerDialog = true
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Sticker Picker Dialog (1.1)
            if (showStickerPickerDialog) {
                StickerPickerDialog(
                    albums = albums,
                    allAlbums = allAlbums,
                    hiddenAlbumIds = hiddenAlbumIds,
                    favoriteMediaIds = favoriteMediaIds,
                    availableMedia = availableMedia,
                    onRevealHidden = onRevealHidden,
                    onMediaPicked = { picked ->
                        val newSticker = StickerOverlay(uri = picked.uri)
                        recordDiscreteChange(editState.copy(stickers = editState.stickers + newSticker))
                        selectedOverlayId = newSticker.id
                        showStickerPickerDialog = false
                        onRevealHidden(false)
                    },
                    onOtherFolder = {
                        showStickerPickerDialog = false
                        onRevealHidden(false)
                        stickerPickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onDismiss = {
                        showStickerPickerDialog = false
                        onRevealHidden(false)
                    },
                )
            }

            // --- Modals & Sheets ---

            // Text Editing Panel (~60% screen)
            AnimatedVisibility(
                visible = isEditingTextPanelOpen,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(50f),
            ) {
                val item = textBeingEdited ?: TextOverlay(text = "")
                TextEditExpandedPanel(
                    initialText = item.text,
                    initialAlignment = item.alignment,
                    initialBold = item.isBold,
                    initialItalic = item.isItalic,
                    initialUnderline = item.isUnderline,
                    initialStrikethrough = item.isStrikethrough,
                    initialFontSize = item.fontSizeSp,
                    initialColor = item.color,
                    onConfirm = { text, align, bold, italic, underline, strike, size, col ->
                        val updated = item.copy(
                            text = text,
                            alignment = align,
                            isBold = bold,
                            isItalic = italic,
                            isUnderline = underline,
                            isStrikethrough = strike,
                            fontSizeSp = size,
                            color = col,
                        )
                        if (editState.texts.any { it.id == updated.id }) {
                            recordDiscreteChange(editState.copy(texts = editState.texts.map { if (it.id == updated.id) updated else it }))
                        } else {
                            recordDiscreteChange(editState.copy(texts = editState.texts + updated))
                            selectedOverlayId = updated.id
                        }
                        isEditingTextPanelOpen = false
                        textBeingEdited = null
                    },
                    onCancel = {
                        isEditingTextPanelOpen = false
                        textBeingEdited = null
                    },
                )
            }

            // Full Color-Picker View
            AnimatedVisibility(
                visible = isColorPickerOpen,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(50f),
            ) {
                AdvancedColorPickerView(
                    initialColor = brushColor,
                    onColorSelected = { col -> brushColor = col },
                    onOpenEyedropper = {
                        isColorPickerOpen = false
                        isEyedropperActive = true
                    },
                    onClose = { isColorPickerOpen = false },
                )
            }

            // Loading Indicator during Save
            if (isSaving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .zIndex(100f)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // A GIF is saved frame by frame: how far it got.
                            val progress = saveProgress
                            if (progress != null) {
                                CircularProgressIndicator(
                                    progress = { progress.coerceIn(0f, 1f) },
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                text = if (progress != null) {
                                    "${stringResource(R.string.saving)} ${(progress * 100).roundToInt()}%"
                                } else {
                                    stringResource(R.string.saving)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Complete Photo Editor Layout: Top Bar, Zoomable Canvas, and Bottom Controls */
@Composable
private fun PhotoEditorMainLayout(
    media: GalleryMedia,
    editState: ImageEditState,
    onEditStateChange: (ImageEditState) -> Unit,
    onContinuousChangeStart: () -> Unit,
    onContinuousChangeEnd: () -> Unit,
    onDiscreteChange: (ImageEditState) -> Unit,
    onApplyCrop: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    isSaving: Boolean,
    showSaveMenu: Boolean,
    onShowSaveMenuChange: (Boolean) -> Unit,
    allowOverwrite: Boolean,
    onSave: (ImageEditState, Boolean) -> Unit,
    onClose: () -> Unit,
    hasChanges: Boolean,
    onResetAll: () -> Unit,
    currentTab: PhotoEditorTab,
    onTabSelected: (PhotoEditorTab) -> Unit,
    currentAdjustParam: AdjustParam,
    onAdjustParamSelected: (AdjustParam) -> Unit,
    selectedAspectOption: AspectOption,
    onAspectOptionSelected: (AspectOption) -> Unit,
    effectiveImageAspect: Float,
    isRotated90or270: Boolean,
    composeColorFilter: ColorFilter,
    colorMatrixArray: FloatArray,
    decodedBitmap: Bitmap?,
    liveProcessedBitmap: Bitmap?,
    /** The GIF frame on screen while a GIF plays; null for photos and a paused GIF. */
    /** The playing GIF's current frame, read when drawing; null when no GIF plays. */
    gifFrame: (() -> Bitmap?)?,
    /** Committed crop in source space, applied to [gifFrame] when it is drawn. */
    gifCrop: CropRect,
    onDecodedImageSize: (Size) -> Unit,
    selectedOverlayId: String?,
    onSelectOverlay: (String?) -> Unit,
    brushMode: BrushToolMode,
    onBrushModeChange: (BrushToolMode) -> Unit,
    brushColor: Int,
    onBrushColorChange: (Int) -> Unit,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    brushHardness: Float,
    onBrushHardnessChange: (Float) -> Unit,
    isColorPickerOpen: Boolean,
    onOpenColorPicker: () -> Unit,
    isEyedropperActive: Boolean,
    onEyedropperActiveChange: (Boolean) -> Unit,
    onOpenTextPanel: (TextOverlay?) -> Unit,
    onAddStickerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // View of the workspace (zoom, pan, turn): only for looking and editing, never saved. Two
    // fingers change it in every tab but blur; in "Proporcje" the turn snaps to the picture's own
    // quarter turns on release.
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var viewRotation by remember { mutableFloatStateOf(0f) }
    var workspaceSize by remember { mutableStateOf(IntSize.Zero) }
    var displayedImageSize by remember { mutableStateOf(Size(1000f, 1000f)) }
    fun resetView() {
        zoomScale = 1f
        panOffset = Offset.Zero
        viewRotation = 0f
    }
    LaunchedEffect(currentTab) {
        when (currentTab) {
            // Blur keeps its own two-finger gestures, so a view carried into it could not be undone there.
            PhotoEditorTab.BLUR -> resetView()
            // Turning in "Proporcje" turns the picture itself: start from an upright view.
            PhotoEditorTab.ASPECT -> {
                viewRotation = 0f
                panOffset = clampViewPan(panOffset, zoomScale, 0f, workspaceSize, displayedImageSize)
            }
            else -> Unit
        }
    }
    // A quarter turn swaps the image's sides: keep the pan inside the new bounds.
    LaunchedEffect(displayedImageSize) {
        panOffset = clampViewPan(panOffset, zoomScale, viewRotation, workspaceSize, displayedImageSize)
    }
    val viewChanged = zoomScale > 1.01f || panOffset != Offset.Zero || abs(viewRotation) >= 0.5f

    // Quarter turn of the picture itself, with its stickers, texts and strokes (one undo step).
    val rotateImageBy: (Int) -> Unit = { delta ->
        val newRot = (editState.rotation + delta + 360) % 360
        val (newStickers, newStrokes, newTexts) = rotateOverlaysByDelta(editState.stickers, editState.brushStrokes, editState.texts, delta, effectiveImageAspect)
        onDiscreteChange(
            withGeometry(editState, newRot, editState.flipH, editState.flipV, effectiveImageAspect)
                .copy(stickers = newStickers, brushStrokes = newStrokes, texts = newTexts),
        )
    }
    val currentRotateImageBy by rememberUpdatedState(rotateImageBy)
    val currentTabState by rememberUpdatedState(currentTab)
    val currentSelectedOverlayId by rememberUpdatedState(selectedOverlayId)
    val currentOnSelectOverlay by rememberUpdatedState(onSelectOverlay)
    // Shared by the overlay (records measured item sizes) and the workspace gesture handler.
    val overlayItemSizes = remember { mutableStateMapOf<String, IntSize>() }
    val overlayItemGestures = rememberOverlayItemGestures(
        active = (currentTab == PhotoEditorTab.STICKERS || currentTab == PhotoEditorTab.TEXT) && !isEyedropperActive,
        currentTab = currentTab,
        stickers = editState.stickers,
        texts = editState.texts,
        selectedItemId = selectedOverlayId,
        onSelectItem = onSelectOverlay,
        onUpdateSticker = { updated ->
            onEditStateChange(editState.copy(stickers = editState.stickers.map { if (it.id == updated.id) updated else it }))
        },
        onUpdateText = { updated ->
            onEditStateChange(editState.copy(texts = editState.texts.map { if (it.id == updated.id) updated else it }))
        },
        onContinuousChangeStart = onContinuousChangeStart,
        onContinuousChangeEnd = onContinuousChangeEnd,
        imageRect = Rect(0f, 0f, displayedImageSize.width, displayedImageSize.height),
        itemSizes = overlayItemSizes,
        // Workspace -> image: the image box is centred, scaled and turned about the centre, then panned.
        toImage = { p, ws ->
            val center = Offset(ws.width / 2f, ws.height / 2f)
            val unscaled = center + rotatePoint(p - center - panOffset, -viewRotation) / zoomScale
            unscaled - Offset((ws.width - displayedImageSize.width) / 2f, (ws.height - displayedImageSize.height) / 2f)
        },
        zoom = { zoomScale },
        viewRotation = { viewRotation },
    )

    val foldable = rememberFoldableState()
    val isTabletop = foldable.isTabletop

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Upper Section: Top Bar + Workspace (above horizontal hinge in Tabletop mode)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // 1. Top Bar
            Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onClose,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_no))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Undo,
                        contentDescription = stringResource(R.string.undo),
                        tint = if (canUndo) Color.White else Color.Gray,
                    )
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Redo,
                        contentDescription = stringResource(R.string.redo),
                        tint = if (canRedo) Color.White else Color.Gray,
                    )
                }

                if (hasChanges) {
                    TextButton(onClick = onResetAll) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.reset_changes), color = Color.White)
                    }
                }

                Box {
                    FilledTonalButton(
                        onClick = {
                            // Externally opened image: copy is the only option, skip the menu.
                            if (allowOverwrite) onShowSaveMenuChange(true) else onSave(editState, false)
                        },
                        // Saving an unchanged image would only re-encode it (quality loss).
                        enabled = !isSaving && hasChanges,
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.save))
                    }

                    DropdownMenu(
                        expanded = showSaveMenu,
                        onDismissRequest = { onShowSaveMenuChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.save_as_copy)) },
                            onClick = {
                                onShowSaveMenuChange(false)
                                onSave(editState, false)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.save_overwrite)) },
                            onClick = {
                                onShowSaveMenuChange(false)
                                onSave(editState, true)
                            },
                        )
                    }
                }
            }
        }

        // 2. Center Workspace with pinch-to-zoom and pan
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .onSizeChanged { workspaceSize = it }
                .editorViewGestures(
                    // A selected sticker/text takes two fingers itself; blur keeps them for its focus.
                    enabled = { currentTabState != PhotoEditorTab.BLUR && currentSelectedOverlayId == null },
                    onStart = {
                        // The first finger may have picked an item under it: two fingers are the view's.
                        if (currentSelectedOverlayId != null) currentOnSelectOverlay(null)
                    },
                    onTransform = { centroid, pan, zoom, rotation ->
                        val center = Offset(workspaceSize.width / 2f, workspaceSize.height / 2f)
                        val newZoom = (zoomScale * zoom).coerceIn(1f, 5f)
                        // The point under the fingers stays under them (turn and zoom about the centroid).
                        val fromCenter = rotatePoint(centroid - pan - center - panOffset, rotation) * (newZoom / zoomScale)
                        val newRotation = normalizeDegrees(viewRotation + rotation)
                        panOffset = clampViewPan(centroid - center - fromCenter, newZoom, newRotation, workspaceSize, displayedImageSize)
                        zoomScale = newZoom
                        viewRotation = newRotation
                    },
                    onEnd = {
                        if (currentTabState == PhotoEditorTab.ASPECT) {
                            // The picture's own turn: nearest quarter turn, the view upright again.
                            val delta = (((viewRotation / 90f).roundToInt() * 90) % 360 + 360) % 360
                            viewRotation = 0f
                            panOffset = clampViewPan(panOffset, zoomScale, 0f, workspaceSize, displayedImageSize)
                            if (delta != 0) currentRotateImageBy(delta)
                        }
                    },
                )
                .pointerInput(currentTab, isEyedropperActive, selectedOverlayId) {
                    // One finger pans a zoomed view where it has no tool of its own (two fingers: above).
                    if (selectedOverlayId == null && currentTab != PhotoEditorTab.BRUSH && currentTab != PhotoEditorTab.BLUR && currentTab != PhotoEditorTab.ASPECT && !isEyedropperActive) {
                        detectTransformGestures { _, pan, _, _ ->
                            if (zoomScale > 1.01f) {
                                panOffset = clampViewPan(panOffset + pan, zoomScale, viewRotation, workspaceSize, displayedImageSize)
                            }
                        }
                    }
                }
                .pointerInput(displayedImageSize, currentTab, isEyedropperActive, selectedOverlayId) {
                    val zoomLocked = currentTab == PhotoEditorTab.ASPECT ||
                        currentTab == PhotoEditorTab.BRUSH ||
                        currentTab == PhotoEditorTab.BLUR ||
                        isEyedropperActive ||
                        selectedOverlayId != null
                    detectTapGestures(
                        onDoubleTap = if (zoomLocked) null else { tapOffset ->
                            if (zoomScale > 1.05f) {
                                zoomScale = 1f
                                panOffset = Offset.Zero
                            } else {
                                // Zooms in about the tapped point: it stays under the finger.
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val tapRel = tapOffset - center - panOffset
                                val newZoom = 2.5f
                                panOffset = clampViewPan(panOffset - tapRel * (newZoom / zoomScale - 1f), newZoom, viewRotation, workspaceSize, displayedImageSize)
                                zoomScale = newZoom
                            }
                        },
                        onTap = {
                            onSelectOverlay(null)
                        },
                    )
                }
                .then(overlayItemGestures),
            contentAlignment = Alignment.Center,
        ) {
            val imageRequest = remember(media.uri, media.absolutePath) {
                ImageRequest.Builder(context)
                    .data(media.absolutePath?.let { File(it) } ?: media.uri)
                    .build()
            }

            val containerW = maxWidth.value * density.density
            val containerH = maxHeight.value * density.density

            if (containerW > 0f && containerH > 0f) {
                val horizontalPadding = with(density) { 32.dp.toPx() }
                val verticalPadding = with(density) { 24.dp.toPx() }
                val maxAvailW = (containerW - horizontalPadding * 2).coerceAtLeast(100f)
                val maxAvailH = (containerH - verticalPadding * 2).coerceAtLeast(100f)

                val availRatio = maxAvailW / maxAvailH
                val (dispW, dispH) = if (availRatio > effectiveImageAspect) {
                    (maxAvailH * effectiveImageAspect) to maxAvailH
                } else {
                    maxAvailW to (maxAvailW / effectiveImageAspect)
                }

                val (boxW, boxH) = if (isRotated90or270) dispH to dispW else dispW to dispH
                val imageBoundsRect = Rect(0f, 0f, dispW, dispH)

                LaunchedEffect(dispW, dispH) {
                    displayedImageSize = Size(dispW, dispH)
                }

                // Single unified Box containing Image + Overlays scaled and turned together
                Box(
                    modifier = Modifier
                        .size(with(density) { dispW.toDp() }, with(density) { dispH.toDp() })
                        .graphicsLayer {
                            scaleX = zoomScale
                            scaleY = zoomScale
                            rotationZ = viewRotation
                            translationX = panOffset.x
                            translationY = panOffset.y
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Background Image Layer. requiredSize: the unrotated box is wider/taller than
                    // its parent for quarter turns; size() would squeeze it into the parent and
                    // distort the rotated picture.
                    Box(
                        modifier = Modifier
                            .requiredSize(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                            .graphicsLayer {
                                rotationZ = editState.rotation.toFloat()
                                scaleX = if (editState.flipH) -1f else 1f
                                scaleY = if (editState.flipV) -1f else 1f
                            },
                    ) {
                        val currentBmp = liveProcessedBitmap?.takeIf { !it.isRecycled } ?: decodedBitmap?.takeIf { !it.isRecycled }
                        if (gifFrame != null) {
                            // A playing GIF: this frame, cut like the photo (crop in source space),
                            // with the same colour filter; turn and flip come from the layer above.
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                // The frame is read here, when drawing: read in composition, every
                                // GIF frame rebuilt the whole editor.
                                val animatedFrame = gifFrame()?.takeIf { !it.isRecycled } ?: return@Canvas
                                val frameImage = animatedFrame.asImageBitmap()
                                val fw = animatedFrame.width
                                val fh = animatedFrame.height
                                val left = (gifCrop.left * fw).roundToInt().coerceIn(0, fw - 1)
                                val top = (gifCrop.top * fh).roundToInt().coerceIn(0, fh - 1)
                                val right = (gifCrop.right * fw).roundToInt().coerceIn(left + 1, fw)
                                val bottom = (gifCrop.bottom * fh).roundToInt().coerceIn(top + 1, fh)
                                drawImage(
                                    image = frameImage,
                                    srcOffset = androidx.compose.ui.unit.IntOffset(left, top),
                                    srcSize = androidx.compose.ui.unit.IntSize(right - left, bottom - top),
                                    dstSize = androidx.compose.ui.unit.IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                    colorFilter = composeColorFilter,
                                )
                            }
                        } else if (currentBmp != null) {
                            Image(
                                bitmap = currentBmp.asImageBitmap(),
                                contentDescription = null,
                                colorFilter = composeColorFilter,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = null,
                                colorFilter = composeColorFilter,
                                contentScale = ContentScale.FillBounds,
                                onSuccess = { state ->
                                    val w = state.painter.intrinsicSize.width
                                    val h = state.painter.intrinsicSize.height
                                    if (w > 0f && h > 0f && !w.isNaN() && !h.isNaN()) {
                                        onDecodedImageSize(Size(w, h))
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    // Interactive Overlays Canvas (Persistent Brush, Blur, Stickers, Text, Eyedropper)
                    PhotoEditorOverlayCanvas(
                        currentTab = currentTab,
                        imageRect = imageBoundsRect,
                        decodedBitmap = decodedBitmap,
                        blurConfig = editState.blur,
                        onBlurConfigChange = { newConfig ->
                            onEditStateChange(editState.copy(blur = newConfig))
                        },
                        stickers = editState.stickers,
                        texts = editState.texts,
                        selectedItemId = selectedOverlayId,
                        onSelectItem = onSelectOverlay,
                        onUpdateSticker = { updated ->
                            onEditStateChange(
                                editState.copy(
                                    stickers = editState.stickers.map { if (it.id == updated.id) updated else it },
                                ),
                            )
                        },
                        onUpdateText = { updated ->
                            onEditStateChange(
                                editState.copy(
                                    texts = editState.texts.map { if (it.id == updated.id) updated else it },
                                ),
                            )
                        },
                        brushStrokes = editState.brushStrokes,
                        liveStrokeTemplate = BrushStroke(
                            color = brushColor,
                            strokeWidthPx = brushSize,
                            alpha = brushHardness,
                            hardness = brushHardness,
                            points = emptyList(),
                        ),
                        onBrushStrokeCommit = { points ->
                            if (points.isNotEmpty()) {
                                onDiscreteChange(
                                    editState.copy(
                                        brushStrokes = editState.brushStrokes + BrushStroke(
                                            color = brushColor,
                                            strokeWidthPx = brushSize,
                                            alpha = brushHardness,
                                            hardness = brushHardness,
                                            points = points,
                                        ),
                                    ),
                                )
                            }
                        },
                        isEyedropperActive = isEyedropperActive,
                        onColorSampled = { color ->
                            onBrushColorChange(color)
                            onEyedropperActiveChange(false)
                            onOpenColorPicker()
                        },
                        onEyedropperClose = { onEyedropperActiveChange(false) },
                        onContinuousChangeStart = onContinuousChangeStart,
                        onContinuousChangeEnd = onContinuousChangeEnd,
                        colorFilter = composeColorFilter,
                        liveProcessedBitmap = liveProcessedBitmap,
                        imageRotation = editState.rotation,
                        imageFlipH = editState.flipH,
                        imageFlipV = editState.flipV,
                        colorMatrix = colorMatrixArray,
                        itemSizes = overlayItemSizes,
                        viewScale = zoomScale,
                        viewRotation = viewRotation,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Crop frame lives on the whole workspace, not inside the image box: handles on
                // the image border are grabbable from outside too, and a near miss no longer
                // falls through to the workspace tap handler. Same view transform as the image
                // (both are centred, so they turn and scale about the same point).
                if (currentTab == PhotoEditorTab.ASPECT) {
                    val imageLeft = (containerW - dispW) / 2f
                    val imageTop = (containerH - dispH) / 2f
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
                                rotationZ = viewRotation
                                translationX = panOffset.x
                                translationY = panOffset.y
                            },
                    ) {
                        InteractiveCropOverlay(
                            imageRect = Rect(imageLeft, imageTop, imageLeft + dispW, imageTop + dispH),
                            imageAspect = effectiveImageAspect,
                            crop = editState.crop,
                            aspectRatio = editState.aspectRatio,
                            onCropChange = { newCrop ->
                                onEditStateChange(editState.copy(crop = newCrop))
                            },
                            onDragStart = onContinuousChangeStart,
                            onDragEnd = onContinuousChangeEnd,
                            viewScale = zoomScale,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            if (viewChanged) {
                FilledTonalButton(
                    onClick = { resetView() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                ) {
                    Text(stringResource(R.string.reset_view))
                }
            }
        }
        }

        // 3. Bottom Control Panel (in tabletop mode takes weight(1f) to act as a mixing console)
        Column(
            modifier = if (isTabletop) {
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
                    .navigationBarsPadding()
            } else {
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
                    .navigationBarsPadding()
            },
            verticalArrangement = if (isTabletop) Arrangement.Center else Arrangement.Top,
        ) {
            when (currentTab) {
                PhotoEditorTab.ASPECT -> {
                    AspectControlsBar(
                        editState = editState,
                        selectedOption = selectedAspectOption,
                        onOptionSelected = { opt ->
                            onAspectOptionSelected(opt)
                            val targetRatio = when (opt) {
                                AspectOption.CUSTOM -> null
                                else -> opt.ratio
                            }
                            val newCrop = computeAspectCrop(effectiveImageAspect, targetRatio)
                            onDiscreteChange(editState.copy(aspectRatio = targetRatio, crop = newCrop))
                        },
                        onFlipH = {
                            val (newStickers, newStrokes, newTexts) = flipOverlaysH(editState.stickers, editState.brushStrokes, editState.texts)
                            val newFlipH = if (isRotated90or270) editState.flipH else !editState.flipH
                            val newFlipV = if (isRotated90or270) !editState.flipV else editState.flipV
                            onDiscreteChange(
                                withGeometry(editState, editState.rotation, newFlipH, newFlipV, effectiveImageAspect)
                                    .copy(stickers = newStickers, brushStrokes = newStrokes, texts = newTexts),
                            )
                        },
                        onFlipV = {
                            val (newStickers, newStrokes, newTexts) = flipOverlaysV(editState.stickers, editState.brushStrokes, editState.texts)
                            val newFlipH = if (isRotated90or270) !editState.flipH else editState.flipH
                            val newFlipV = if (isRotated90or270) editState.flipV else !editState.flipV
                            onDiscreteChange(
                                withGeometry(editState, editState.rotation, newFlipH, newFlipV, effectiveImageAspect)
                                    .copy(stickers = newStickers, brushStrokes = newStrokes, texts = newTexts),
                            )
                        },
                        onRotateDelta = rotateImageBy,
                        onSetRotationAngle = { angle ->
                            // Quarter turns only: free angles cannot be matched by the export.
                            val snapped = ((angle / 90f).roundToInt() * 90 % 360 + 360) % 360
                            val delta = (snapped - editState.rotation + 360) % 360
                            if (delta != 0) {
                                val (newStickers, newStrokes, newTexts) = rotateOverlaysByDelta(editState.stickers, editState.brushStrokes, editState.texts, delta, effectiveImageAspect)
                                onDiscreteChange(
                                    withGeometry(editState, snapped, editState.flipH, editState.flipV, effectiveImageAspect)
                                        .copy(stickers = newStickers, brushStrokes = newStrokes, texts = newTexts),
                                )
                            }
                        },
                        onApplyExactCropRatio = { ratio ->
                            onAspectOptionSelected(AspectOption.EXACT)
                            val newCrop = computeAspectCrop(effectiveImageAspect, ratio)
                            onDiscreteChange(editState.copy(aspectRatio = ratio, crop = newCrop))
                        },
                        onApplyCrop = if (decodedBitmap != null) onApplyCrop else null,
                    )
                }

                PhotoEditorTab.PARAMETERS -> {
                    AdjustControlsBar(
                        editState = editState,
                        currentParam = currentAdjustParam,
                        onParamSelected = onAdjustParamSelected,
                        onParamValueChange = { param, value ->
                            onContinuousChangeStart()
                            val updated = when (param) {
                                AdjustParam.BRIGHTNESS -> editState.copy(brightness = value)
                                AdjustParam.CONTRAST -> editState.copy(contrast = value)
                                AdjustParam.SATURATION -> editState.copy(saturation = value)
                                AdjustParam.SHADOWS -> editState.copy(shadows = value)
                                AdjustParam.HIGHLIGHTS -> editState.copy(highlights = value)
                                AdjustParam.EXPOSURE -> editState.copy(exposure = value)
                                AdjustParam.GAMMA -> editState.copy(gamma = value)
                                AdjustParam.TEMPERATURE -> editState.copy(temperature = value)
                                AdjustParam.SHARPNESS -> editState.copy(sharpness = value)
                            }
                            onEditStateChange(updated)
                        },
                        onParamValueChangeFinished = onContinuousChangeEnd,
                        canUndo = canUndo,
                        canRedo = canRedo,
                        onUndo = onUndo,
                        onRedo = onRedo,
                    )
                }

                PhotoEditorTab.BLUR -> {
                    BlurControlsBar(
                        config = editState.blur,
                        onConfigChange = { newConfig ->
                            onContinuousChangeStart()
                            onEditStateChange(editState.copy(blur = newConfig))
                        },
                        onConfigChangeFinished = onContinuousChangeEnd,
                    )
                }

                PhotoEditorTab.STICKERS -> {
                    StickerControlsBar(
                        hasSelection = selectedOverlayId != null && editState.stickers.any { it.id == selectedOverlayId },
                        onAddStickerClick = onAddStickerClick,
                        onRotateStep = { step ->
                            val sel = editState.stickers.find { it.id == selectedOverlayId } ?: return@StickerControlsBar
                            val updated = sel.copy(rotationDeg = (sel.rotationDeg + step) % 360f)
                            onDiscreteChange(editState.copy(stickers = editState.stickers.map { if (it.id == updated.id) updated else it }))
                        },
                        onMoveStep = { dNx, dNy ->
                            val sel = editState.stickers.find { it.id == selectedOverlayId } ?: return@StickerControlsBar
                            val updated = sel.copy(
                                normalizedX = (sel.normalizedX + dNx).coerceIn(0f, 1f),
                                normalizedY = (sel.normalizedY + dNy).coerceIn(0f, 1f),
                            )
                            onDiscreteChange(editState.copy(stickers = editState.stickers.map { if (it.id == updated.id) updated else it }))
                        },
                        onDeleteSelected = {
                            onDiscreteChange(editState.copy(stickers = editState.stickers.filterNot { it.id == selectedOverlayId }))
                            onSelectOverlay(null)
                        },
                        onDeselect = { onSelectOverlay(null) },
                    )
                }

                PhotoEditorTab.TEXT -> {
                    TextControlsBar(
                        hasSelection = selectedOverlayId != null && editState.texts.any { it.id == selectedOverlayId },
                        onAddTextClick = { onOpenTextPanel(TextOverlay(text = "")) },
                        onEditTextClick = {
                            val sel = editState.texts.find { it.id == selectedOverlayId }
                            if (sel != null) onOpenTextPanel(sel)
                        },
                        onRotateStep = { step ->
                            val sel = editState.texts.find { it.id == selectedOverlayId } ?: return@TextControlsBar
                            val updated = sel.copy(rotationDeg = (sel.rotationDeg + step) % 360f)
                            onDiscreteChange(editState.copy(texts = editState.texts.map { if (it.id == updated.id) updated else it }))
                        },
                        onMoveStep = { dNx, dNy ->
                            val sel = editState.texts.find { it.id == selectedOverlayId } ?: return@TextControlsBar
                            val updated = sel.copy(
                                normalizedX = (sel.normalizedX + dNx).coerceIn(0f, 1f),
                                normalizedY = (sel.normalizedY + dNy).coerceIn(0f, 1f),
                            )
                            onDiscreteChange(editState.copy(texts = editState.texts.map { if (it.id == updated.id) updated else it }))
                        },
                        onDeleteSelected = {
                            onDiscreteChange(editState.copy(texts = editState.texts.filterNot { it.id == selectedOverlayId }))
                            onSelectOverlay(null)
                        },
                        onDeselect = { onSelectOverlay(null) },
                    )
                }

                PhotoEditorTab.BRUSH -> {
                    BrushControlsBar(
                        activeMode = brushMode,
                        onModeSelected = onBrushModeChange,
                        brushColor = brushColor,
                        brushSize = brushSize,
                        onBrushSizeChange = onBrushSizeChange,
                        brushHardness = brushHardness,
                        onBrushHardnessChange = onBrushHardnessChange,
                        onOpenColorPicker = onOpenColorPicker,
                        onConfirm = { onBrushModeChange(BrushToolMode.NONE) },
                        onCancel = {
                            onBrushModeChange(BrushToolMode.NONE)
                            if (editState.brushStrokes.isNotEmpty()) {
                                onEditStateChange(editState.copy(brushStrokes = editState.brushStrokes.dropLast(1)))
                            }
                        },
                    )
                }
            }

            // Bottom Navigation Main Tabs (6 tabs)
            PhotoEditorMainTabs(
                currentTab = currentTab,
                onTabSelected = onTabSelected,
            )
        }
    }
}

/** Interactive Crop Overlay with Scrim and Corner/Edge Handles */
@Composable
private fun InteractiveCropOverlay(
    imageRect: Rect,
    imageAspect: Float,
    crop: CropRect,
    aspectRatio: Float?,
    onCropChange: (CropRect) -> Unit,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    /** Zoom of the view this frame is drawn in: handles and lines keep their size on screen. */
    viewScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val k = 1f / viewScale.coerceAtLeast(0.01f)
    val currentK by rememberUpdatedState(k)
    val minNorm = 0.08f

    var activeHandle by remember { mutableStateOf(DragHandle.NONE) }
    val currentCrop by rememberUpdatedState(crop)
    // The drag detector outlives recompositions: a captured onCropChange held an old editState
    // and silently reverted flips/180-degree turns/resets made after the gesture handler started.
    val currentOnCropChange by rememberUpdatedState(onCropChange)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    val cropPixelLeft = imageRect.left + crop.left * imageRect.width
    val cropPixelTop = imageRect.top + crop.top * imageRect.height
    val cropPixelRight = imageRect.left + crop.right * imageRect.width
    val cropPixelBottom = imageRect.top + crop.bottom * imageRect.height

    fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        kotlin.math.hypot(x1 - x2, y1 - y2)

    Box(
        modifier = modifier
            .pointerInput(imageRect, aspectRatio) {
                // The handle is chosen where the finger lands, and only near a corner/edge (or
                // inside a smaller frame): a drag elsewhere no longer grabs the nearest edge, which
                // then jumped along with the finger once it crossed the line.
                fun handleAt(offset: Offset): DragHandle {
                        val handleRadius = 48.dp.toPx() * currentK
                        val l = imageRect.left + currentCrop.left * imageRect.width
                        val t = imageRect.top + currentCrop.top * imageRect.height
                        val r = imageRect.left + currentCrop.right * imageRect.width
                        val b = imageRect.top + currentCrop.bottom * imageRect.height

                        val nearImage = offset.x in (imageRect.left - handleRadius)..(imageRect.right + handleRadius) &&
                            offset.y in (imageRect.top - handleRadius)..(imageRect.bottom + handleRadius)
                        return when {
                            // The overlay spans the whole workspace; ignore touches far from the image.
                            !nearImage -> DragHandle.NONE

                            // 1. Corners have highest priority
                            dist(offset.x, offset.y, l, t) <= handleRadius -> DragHandle.TOP_LEFT
                            dist(offset.x, offset.y, r, t) <= handleRadius -> DragHandle.TOP_RIGHT
                            dist(offset.x, offset.y, l, b) <= handleRadius -> DragHandle.BOTTOM_LEFT
                            dist(offset.x, offset.y, r, b) <= handleRadius -> DragHandle.BOTTOM_RIGHT

                            // 2. Edges in Custom mode (aspectRatio == null)
                            aspectRatio == null && abs(offset.x - l) <= handleRadius && offset.y in (t - handleRadius)..(b + handleRadius) -> DragHandle.LEFT
                            aspectRatio == null && abs(offset.x - r) <= handleRadius && offset.y in (t - handleRadius)..(b + handleRadius) -> DragHandle.RIGHT
                            aspectRatio == null && abs(offset.y - t) <= handleRadius && offset.x in (l - handleRadius)..(r + handleRadius) -> DragHandle.TOP
                            aspectRatio == null && abs(offset.y - b) <= handleRadius && offset.x in (l - handleRadius)..(r + handleRadius) -> DragHandle.BOTTOM

                            // 3. Move whole frame (BODY) when frame is smaller than image
                            offset.x in l..r && offset.y in t..b && (currentCrop.right - currentCrop.left < 0.98f || currentCrop.bottom - currentCrop.top < 0.98f) -> DragHandle.BODY

                            else -> DragHandle.NONE
                        }
                }

                fun applyDrag(dragAmount: Offset) {
                        val dNormX = dragAmount.x / imageRect.width
                        val dNormY = dragAmount.y / imageRect.height
                        var c = currentCrop

                        if (activeHandle == DragHandle.BODY) {
                            val w = c.right - c.left
                            val h = c.bottom - c.top
                            val newL = (c.left + dNormX).coerceIn(0f, 1f - w)
                            val newT = (c.top + dNormY).coerceIn(0f, 1f - h)
                            currentOnCropChange(CropRect(newL, newT, newL + w, newT + h))
                            return
                        }

                        if (aspectRatio == null) {
                            when (activeHandle) {
                                DragHandle.TOP_LEFT -> {
                                    val newL = (c.left + dNormX).coerceIn(0f, c.right - minNorm)
                                    val newT = (c.top + dNormY).coerceIn(0f, c.bottom - minNorm)
                                    c = c.copy(left = newL, top = newT)
                                }
                                DragHandle.TOP_RIGHT -> {
                                    val newR = (c.right + dNormX).coerceIn(c.left + minNorm, 1f)
                                    val newT = (c.top + dNormY).coerceIn(0f, c.bottom - minNorm)
                                    c = c.copy(right = newR, top = newT)
                                }
                                DragHandle.BOTTOM_LEFT -> {
                                    val newL = (c.left + dNormX).coerceIn(0f, c.right - minNorm)
                                    val newB = (c.bottom + dNormY).coerceIn(c.top + minNorm, 1f)
                                    c = c.copy(left = newL, bottom = newB)
                                }
                                DragHandle.BOTTOM_RIGHT -> {
                                    val newR = (c.right + dNormX).coerceIn(c.left + minNorm, 1f)
                                    val newB = (c.bottom + dNormY).coerceIn(c.top + minNorm, 1f)
                                    c = c.copy(right = newR, bottom = newB)
                                }
                                DragHandle.LEFT -> {
                                    val newL = (c.left + dNormX).coerceIn(0f, c.right - minNorm)
                                    c = c.copy(left = newL)
                                }
                                DragHandle.RIGHT -> {
                                    val newR = (c.right + dNormX).coerceIn(c.left + minNorm, 1f)
                                    c = c.copy(right = newR)
                                }
                                DragHandle.TOP -> {
                                    val newT = (c.top + dNormY).coerceIn(0f, c.bottom - minNorm)
                                    c = c.copy(top = newT)
                                }
                                DragHandle.BOTTOM -> {
                                    val newB = (c.bottom + dNormY).coerceIn(c.top + minNorm, 1f)
                                    c = c.copy(bottom = newB)
                                }
                                else -> Unit
                            }
                            currentOnCropChange(c)
                        } else {
                            val targetR = aspectRatio
                            when (activeHandle) {
                                DragHandle.BOTTOM_RIGHT -> {
                                    val newR = (c.right + dNormX).coerceIn(c.left + minNorm, 1f)
                                    val newW = newR - c.left
                                    val newH = (newW * imageAspect) / targetR
                                    if (c.top + newH <= 1f) {
                                        c = c.copy(right = newR, bottom = c.top + newH)
                                    }
                                }
                                DragHandle.BOTTOM_LEFT -> {
                                    val newL = (c.left + dNormX).coerceIn(0f, c.right - minNorm)
                                    val newW = c.right - newL
                                    val newH = (newW * imageAspect) / targetR
                                    if (c.top + newH <= 1f) {
                                        c = c.copy(left = newL, bottom = c.top + newH)
                                    }
                                }
                                DragHandle.TOP_RIGHT -> {
                                    val newR = (c.right + dNormX).coerceIn(c.left + minNorm, 1f)
                                    val newW = newR - c.left
                                    val newH = (newW * imageAspect) / targetR
                                    if (c.bottom - newH >= 0f) {
                                        c = c.copy(right = newR, top = c.bottom - newH)
                                    }
                                }
                                DragHandle.TOP_LEFT -> {
                                    val newL = (c.left + dNormX).coerceIn(0f, c.right - minNorm)
                                    val newW = c.right - newL
                                    val newH = (newW * imageAspect) / targetR
                                    if (c.bottom - newH >= 0f) {
                                        c = c.copy(left = newL, top = c.bottom - newH)
                                    }
                                }
                                else -> Unit
                            }
                            currentOnCropChange(c)
                        }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val handle = handleAt(down.position)
                    if (handle == DragHandle.NONE) return@awaitEachGesture
                    // Touch slop first; a second finger (the view's zoom/turn) cancels it.
                    val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    activeHandle = handle
                    currentOnDragStart()
                    try {
                        // The whole way from the landing point, so the frame does not lag the finger.
                        applyDrag(start.position - down.position)
                        drag(start.id) { change ->
                            applyDrag(change.positionChange())
                            change.consume()
                        }
                    } finally {
                        activeHandle = DragHandle.NONE
                        currentOnDragEnd()
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val l = cropPixelLeft
            val t = cropPixelTop
            val r = cropPixelRight
            val b = cropPixelBottom
            val w = r - l
            val h = b - t

            // 1. Scrim outside crop box
            val scrimColor = Color.Black.copy(alpha = 0.58f)
            if (t > imageRect.top) {
                drawRect(scrimColor, topLeft = Offset(imageRect.left, imageRect.top), size = Size(imageRect.width, t - imageRect.top))
            }
            if (b < imageRect.bottom) {
                drawRect(scrimColor, topLeft = Offset(imageRect.left, b), size = Size(imageRect.width, imageRect.bottom - b))
            }
            if (l > imageRect.left) {
                drawRect(scrimColor, topLeft = Offset(imageRect.left, t), size = Size(l - imageRect.left, h))
            }
            if (r < imageRect.right) {
                drawRect(scrimColor, topLeft = Offset(r, t), size = Size(imageRect.right - r, h))
            }

            // 2. Crop border
            drawRect(
                color = Color.White,
                topLeft = Offset(l, t),
                size = Size(w, h),
                style = Stroke(width = 2.dp.toPx() * k),
            )

            // 3. Rule of thirds grid lines
            val thirdW = w / 3f
            val thirdH = h / 3f
            val gridColor = Color.White.copy(alpha = 0.35f)
            val gridStroke = 1.dp.toPx() * k
            drawLine(gridColor, Offset(l + thirdW, t), Offset(l + thirdW, b), strokeWidth = gridStroke)
            drawLine(gridColor, Offset(l + 2 * thirdW, t), Offset(l + 2 * thirdW, b), strokeWidth = gridStroke)
            drawLine(gridColor, Offset(l, t + thirdH), Offset(r, t + thirdH), strokeWidth = gridStroke)
            drawLine(gridColor, Offset(l, t + 2 * thirdH), Offset(r, t + 2 * thirdH), strokeWidth = gridStroke)

            // 4. Corner Handles (L-shaped thick brackets)
            val cornerLen = 22.dp.toPx() * k
            val cornerStroke = 3.5.dp.toPx() * k
            val cornerColor = Color.White

            // Top-Left
            drawLine(cornerColor, Offset(l - 1f, t), Offset(l + cornerLen, t), strokeWidth = cornerStroke)
            drawLine(cornerColor, Offset(l, t - 1f), Offset(l, t + cornerLen), strokeWidth = cornerStroke)

            // Top-Right
            drawLine(cornerColor, Offset(r + 1f, t), Offset(r - cornerLen, t), strokeWidth = cornerStroke)
            drawLine(cornerColor, Offset(r, t - 1f), Offset(r, t + cornerLen), strokeWidth = cornerStroke)

            // Bottom-Left
            drawLine(cornerColor, Offset(l - 1f, b), Offset(l + cornerLen, b), strokeWidth = cornerStroke)
            drawLine(cornerColor, Offset(l, b + 1f), Offset(l, b - cornerLen), strokeWidth = cornerStroke)

            // Bottom-Right
            drawLine(cornerColor, Offset(r + 1f, b), Offset(r - cornerLen, b), strokeWidth = cornerStroke)
            drawLine(cornerColor, Offset(r, b + 1f), Offset(r, b - cornerLen), strokeWidth = cornerStroke)

            // 5. Edge Grip Indicators in Custom mode
            if (aspectRatio == null) {
                val edgeLen = 16.dp.toPx() * k
                val edgeStroke = 3.dp.toPx() * k
                drawLine(cornerColor, Offset(l + w / 2 - edgeLen / 2, t), Offset(l + w / 2 + edgeLen / 2, t), strokeWidth = edgeStroke)
                drawLine(cornerColor, Offset(l + w / 2 - edgeLen / 2, b), Offset(l + w / 2 + edgeLen / 2, b), strokeWidth = edgeStroke)
                drawLine(cornerColor, Offset(l, t + h / 2 - edgeLen / 2), Offset(l, t + h / 2 + edgeLen / 2), strokeWidth = edgeStroke)
                drawLine(cornerColor, Offset(r, t + h / 2 - edgeLen / 2), Offset(r, t + h / 2 + edgeLen / 2), strokeWidth = edgeStroke)
            }
        }
    }
}

@Composable
fun StickerPickerDialog(
    albums: List<Album>,
    allAlbums: List<Album> = albums,
    hiddenAlbumIds: Set<Long> = emptySet(),
    favoriteMediaIds: Set<Long> = emptySet(),
    availableMedia: List<GalleryMedia>,
    onRevealHidden: (Boolean) -> Unit = {},
    onMediaPicked: (GalleryMedia) -> Unit,
    onOtherFolder: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var showHidden by remember { mutableStateOf(false) }
    var selectedAlbumId by remember { mutableStateOf<Long?>(null) }
    val displayedAlbums = remember(showHidden, allAlbums, albums) {
        (if (showHidden) allAlbums else albums).distinctBy { it.id }
    }
    val currentAlbum = remember(selectedAlbumId, allAlbums) {
        allAlbums.find { it.id == selectedAlbumId }
    }
    val hasHidden = remember(allAlbums, albums, hiddenAlbumIds) {
        allAlbums.size > albums.size || hiddenAlbumIds.isNotEmpty() || allAlbums.any { it.isHidden }
    }

    BackHandler(enabled = selectedAlbumId != null) {
        selectedAlbumId = null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectedAlbumId != null) {
                            IconButton(onClick = { selectedAlbumId = null }) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.navigate_up),
                                )
                            }
                        }
                        Text(
                            text = if (selectedAlbumId != null) (currentAlbum?.name ?: "") else stringResource(R.string.tab_stickers),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selectedAlbumId == null && hasHidden) {
                            FilledTonalIconButton(
                                onClick = {
                                    showHidden = !showHidden
                                    onRevealHidden(showHidden)
                                },
                                colors = if (showHidden) {
                                    IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    IconButtonDefaults.filledTonalIconButtonColors()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Visibility,
                                    contentDescription = stringResource(R.string.picker_show_hidden),
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_no))
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Content: Albums grid OR Media grid
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (selectedAlbumId == null) {
                            if (displayedAlbums.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        stringResource(R.string.empty_gallery),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    contentPadding = PaddingValues(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(displayedAlbums, key = { it.id }) { album ->
                                        Box(
                                            modifier = Modifier
                                                .aspectRatio(1f)
                                                .clickable { selectedAlbumId = album.id },
                                        ) {
                                            FolderCover(
                                                album = album,
                                                stamped = false,
                                                hidden = album.isHidden || album.id in hiddenAlbumIds,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            val mediaItems = remember(selectedAlbumId, availableMedia, favoriteMediaIds) {
                                (if (selectedAlbumId == AlbumIds.FAVORITES) {
                                    availableMedia.filter { it.id in favoriteMediaIds && !it.isVideo }
                                } else {
                                    availableMedia.filter { it.bucketId == selectedAlbumId && !it.isVideo }
                                }).distinctBy { it.id }
                            }
                            if (mediaItems.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        stringResource(R.string.no_media),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    contentPadding = PaddingValues(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(mediaItems, key = { it.id }) { item ->
                                        Box(
                                            modifier = Modifier
                                                .aspectRatio(1f)
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { onMediaPicked(item) },
                                        ) {
                                            AsyncImage(
                                                model = item.uri,
                                                contentDescription = item.displayName,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Bottom bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onOtherFolder) {
                            Text(stringResource(R.string.other_folder))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }
}

internal fun remapOverlaysToCrop(
    stickers: List<StickerOverlay>,
    brushStrokes: List<BrushStroke>,
    texts: List<TextOverlay>,
    sub: CropRect,
): Triple<List<StickerOverlay>, List<BrushStroke>, List<TextOverlay>> {
    val subW = (sub.right - sub.left).coerceAtLeast(0.001f)
    val subH = (sub.bottom - sub.top).coerceAtLeast(0.001f)
    val newStickers = stickers.map { s ->
        s.copy(
            normalizedX = (s.normalizedX - sub.left) / subW,
            normalizedY = (s.normalizedY - sub.top) / subH,
            scale = s.scale / subW,
        )
    }
    val newStrokes = brushStrokes.map { stroke ->
        stroke.copy(
            points = stroke.points.map { pt ->
                NormPoint(
                    x = (pt.x - sub.left) / subW,
                    y = (pt.y - sub.top) / subH,
                )
            },
            strokeWidthPx = stroke.strokeWidthPx / subW,
        )
    }
    val newTexts = texts.map { t ->
        t.copy(
            normalizedX = (t.normalizedX - sub.left) / subW,
            normalizedY = (t.normalizedY - sub.top) / subH,
            scale = t.scale / subW,
        )
    }
    return Triple(newStickers, newStrokes, newTexts)
}

internal fun rotateOverlaysByDelta(
    stickers: List<StickerOverlay>,
    brushStrokes: List<BrushStroke>,
    texts: List<TextOverlay>,
    delta: Int,
    /** Width / height of the displayed image before this turn. */
    imageAspect: Float = 1f,
): Triple<List<StickerOverlay>, List<BrushStroke>, List<TextOverlay>> {
    val normDelta = ((delta % 360) + 360) % 360
    if (normDelta == 0) return Triple(stickers, brushStrokes, texts)
    // Sizes are fractions of the image width, which a quarter turn swaps with the height: rescale
    // so overlays keep their size relative to the picture.
    val sizeFactor = if (normDelta == 90 || normDelta == 270) imageAspect.coerceIn(0.05f, 20f) else 1f

    val transformPoint: (Float, Float) -> Pair<Float, Float> = when (normDelta) {
        90 -> { x, y -> (1f - y) to x }
        180 -> { x, y -> (1f - x) to (1f - y) }
        270 -> { x, y -> y to (1f - x) }
        else -> { x, y -> x to y }
    }

    val newStickers = stickers.map { s ->
        val (nx, ny) = transformPoint(s.normalizedX, s.normalizedY)
        s.copy(
            normalizedX = nx,
            normalizedY = ny,
            rotationDeg = (s.rotationDeg + normDelta) % 360f,
            scale = s.scale * sizeFactor,
        )
    }

    val newStrokes = brushStrokes.map { stroke ->
        stroke.copy(
            points = stroke.points.map { pt ->
                val (nx, ny) = transformPoint(pt.x, pt.y)
                NormPoint(nx, ny)
            },
            strokeWidthPx = stroke.strokeWidthPx * sizeFactor,
        )
    }

    val newTexts = texts.map { t ->
        val (nx, ny) = transformPoint(t.normalizedX, t.normalizedY)
        t.copy(
            normalizedX = nx,
            normalizedY = ny,
            rotationDeg = (t.rotationDeg + normDelta) % 360f,
            scale = t.scale * sizeFactor,
        )
    }

    return Triple(newStickers, newStrokes, newTexts)
}

internal fun flipOverlaysH(
    stickers: List<StickerOverlay>,
    brushStrokes: List<BrushStroke>,
    texts: List<TextOverlay>,
): Triple<List<StickerOverlay>, List<BrushStroke>, List<TextOverlay>> {
    val newStickers = stickers.map { s ->
        s.copy(
            normalizedX = 1f - s.normalizedX,
            flipH = !s.flipH,
            rotationDeg = (-s.rotationDeg + 360f) % 360f,
        )
    }
    val newStrokes = brushStrokes.map { stroke ->
        stroke.copy(
            points = stroke.points.map { pt ->
                NormPoint(1f - pt.x, pt.y)
            },
        )
    }
    val newTexts = texts.map { t ->
        t.copy(
            normalizedX = 1f - t.normalizedX,
            flipH = !t.flipH,
            rotationDeg = (-t.rotationDeg + 360f) % 360f,
        )
    }
    return Triple(newStickers, newStrokes, newTexts)
}

internal fun flipOverlaysV(
    stickers: List<StickerOverlay>,
    brushStrokes: List<BrushStroke>,
    texts: List<TextOverlay>,
): Triple<List<StickerOverlay>, List<BrushStroke>, List<TextOverlay>> {
    val newStickers = stickers.map { s ->
        s.copy(
            normalizedY = 1f - s.normalizedY,
            flipV = !s.flipV,
            rotationDeg = (-s.rotationDeg + 360f) % 360f,
        )
    }
    val newStrokes = brushStrokes.map { stroke ->
        stroke.copy(
            points = stroke.points.map { pt ->
                NormPoint(pt.x, 1f - pt.y)
            },
        )
    }
    val newTexts = texts.map { t ->
        t.copy(
            normalizedY = 1f - t.normalizedY,
            flipV = !t.flipV,
            rotationDeg = (-t.rotationDeg + 360f) % 360f,
        )
    }
    return Triple(newStickers, newStrokes, newTexts)
}

/**
 * New rotation/flip for [state]: the pending crop frame and the blur focus (both on the displayed
 * image) move with the picture. A preset ratio is recomputed for the new shape instead.
 */
internal fun withGeometry(
    state: ImageEditState,
    rotation: Int,
    flipH: Boolean,
    flipV: Boolean,
    currentDisplayAspect: Float,
): ImageEditState {
    val sourceCrop = ImageEditEngine.cropDisplayToSource(state.crop, state.rotation, state.flipH, state.flipV)
    val sourceBlur = ImageEditEngine.blurDisplayToSource(state.blur, state.rotation, state.flipH, state.flipV)
    val quarterTurnChange = ((rotation - state.rotation) % 180 + 180) % 180 == 90
    val newAspect = if (quarterTurnChange) 1f / currentDisplayAspect else currentDisplayAspect
    val newCrop = state.aspectRatio?.let { computeAspectCrop(newAspect, it) }
        ?: ImageEditEngine.cropSourceToDisplay(sourceCrop, rotation, flipH, flipV)
    return state.copy(
        rotation = rotation,
        flipH = flipH,
        flipV = flipV,
        crop = newCrop,
        blur = ImageEditEngine.blurSourceToDisplay(sourceBlur, rotation, flipH, flipV),
    )
}

/** Chip matching a stored ratio (presets first, anything else is an exact ratio). */
internal fun aspectOptionFor(ratio: Float?): AspectOption {
    if (ratio == null) return AspectOption.CUSTOM
    return AspectOption.entries.firstOrNull { it.ratio != null && abs(it.ratio - ratio) < 0.0001f } ?: AspectOption.EXACT
}

/** One frame of a GIF in the editor, at preview size. */
private class GifPreviewFrame(val bitmap: Bitmap, val delayMs: Int)

/** Memory for a GIF's preview frames; a longer GIF keeps every n-th frame (delays added up). */
private const val GIF_PREVIEW_BUDGET_BYTES = 96L * 1024L * 1024L

/** A GIF's frames for the editor's preview, at most [maxDim] on the longer side; empty when unreadable. */
private fun loadGifPreviewFrames(context: android.content.Context, file: File?, uri: Uri, maxDim: Int): List<GifPreviewFrame> {
    val data = ImageEditEngine.readSourceBytes(context, file, uri) ?: return emptyList()
    val info = dev.ander.alexet.data.GifFrameReader.info(data) ?: return emptyList()
    if (info.frameCount <= 0 || info.width <= 0 || info.height <= 0) return emptyList()
    val scale = minOf(1f, maxDim.toFloat() / maxOf(info.width, info.height))
    val w = (info.width * scale).roundToInt().coerceAtLeast(1)
    val h = (info.height * scale).roundToInt().coerceAtLeast(1)
    val perFrame = w.toLong() * h * 4L
    val keep = (GIF_PREVIEW_BUDGET_BYTES / perFrame).toInt().coerceAtLeast(1)
    val step = ((info.frameCount + keep - 1) / keep).coerceAtLeast(1)
    val frames = ArrayList<GifPreviewFrame>(minOf(info.frameCount, keep))
    var index = 0
    var pendingDelay = 0
    return runCatching {
        dev.ander.alexet.data.GifFrameReader.forEachFrame(data) { pixels, fw, fh, delayMs ->
            pendingDelay += delayMs
            if (index++ % step == 0) {
                val full = Bitmap.createBitmap(pixels, fw, fh, Bitmap.Config.ARGB_8888)
                val sized = if (fw != w || fh != h) {
                    Bitmap.createScaledBitmap(full, w, h, true).also { if (it !== full) full.recycle() }
                } else {
                    full
                }
                frames += GifPreviewFrame(sized, pendingDelay)
                pendingDelay = 0
            } else if (frames.isNotEmpty()) {
                // A skipped frame's time goes to the frame shown before it.
                val last = frames.removeAt(frames.lastIndex)
                frames += GifPreviewFrame(last.bitmap, last.delayMs + pendingDelay)
                pendingDelay = 0
            }
            true
        }
        frames
    }.getOrElse {
        frames.forEach { it.bitmap.recycle() }
        emptyList()
    }
}

/**
 * Two fingers anywhere on the workspace move the view: pinch zooms, a twist turns, the fingers'
 * middle pans, all at once. Runs before the tools ([PointerEventPass.Initial]) and takes the whole
 * touch once a second finger lands, so the one-finger tool under it (brush, crop frame, eyedropper,
 * an item's drag) lets go. [enabled] is read when the first finger lands.
 */
private fun Modifier.editorViewGestures(
    enabled: () -> Boolean,
    onStart: () -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float, rotation: Float) -> Unit,
    onEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        var claimed = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                if (!claimed) {
                    if (pressed < 2) continue
                    claimed = true
                    onStart()
                }
                if (pressed >= 2) {
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid != Offset.Unspecified) {
                        onTransform(centroid, event.calculatePan(), event.calculateZoom(), event.calculateRotation())
                    }
                }
                // Once taken, the rest of the touch (the last finger too) stays the view's.
                event.changes.forEach { it.consume() }
            }
        } finally {
            if (claimed) onEnd()
        }
    }
}

/** [degrees] in -180..180. */
private fun normalizeDegrees(degrees: Float): Float {
    val d = degrees % 360f
    return when {
        d > 180f -> d - 360f
        d < -180f -> d + 360f
        else -> d
    }
}

/**
 * Pan of the workspace view kept so the turned, zoomed image cannot leave the workspace: within
 * half of how far its bounding box overhangs it. None at 1:1.
 */
private fun clampViewPan(pan: Offset, zoom: Float, rotationDeg: Float, workspace: IntSize, image: Size): Offset {
    if (zoom <= 1.01f || workspace.width <= 0 || workspace.height <= 0) return Offset.Zero
    val rad = rotationDeg * PI.toFloat() / 180f
    val c = abs(cos(rad))
    val s = abs(sin(rad))
    val boundsW = (image.width * c + image.height * s) * zoom
    val boundsH = (image.width * s + image.height * c) * zoom
    val maxX = ((boundsW - workspace.width) / 2f).coerceAtLeast(0f)
    val maxY = ((boundsH - workspace.height) / 2f).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}
