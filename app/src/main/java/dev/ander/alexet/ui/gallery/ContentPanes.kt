package dev.ander.alexet.ui.gallery

import dev.ander.alexet.ui.components.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import dev.ander.alexet.ui.components.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.AlbumIds
import dev.ander.alexet.data.ContentViewMode
import dev.ander.alexet.data.FileSelectMode
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.usesFolderDrag
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

import androidx.compose.runtime.key
import dev.ander.alexet.data.FolderScrollPos

private val LocalDecryptFolder = androidx.compose.runtime.staticCompositionLocalOf<(Album) -> Unit> { {} }

internal const val SLOT_TOP = 0
internal const val SLOT_MIDDLE = 1
internal const val SLOT_BOTTOM = 2

@Composable
fun ContentHost(
    uiState: GalleryUiState,
    albumsById: Map<Long, Album>,
    onOpenMedia: (GalleryMedia) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onMarqueeFiles: (List<Long>) -> Unit,
    onClosePane: (Long) -> Unit,
    onHitboxSelect: (Int) -> Unit,
    onTwoFingerStart: (Long, Offset) -> Unit,
    onTwoFingerDrag: (Offset) -> Unit,
    onTwoFingerEnd: () -> Unit,
    onPaneBounds: (Rect) -> Unit,
    onGetFolderScroll: (Long) -> FolderScrollPos? = { null },
    onSaveFolderScroll: (Long, Int, Int) -> Unit = { _, _, _ -> },
    onGetFolderGridState: (Long) -> androidx.compose.foundation.lazy.grid.LazyGridState? = { null },
    onPlayVideo: (GalleryMedia) -> Unit = {},
    onSplitDragEnd: (Float, Float, Float) -> Unit = { _, _, _ -> },
    onSwapStackedPanes: () -> Unit = {},
    onContentOrderMove: (Int, Int) -> Unit = { _, _ -> },
    onTransientCountChange: (Int?) -> Unit = {},
    /** "Decrypt permanently" for a private folder shown in a pane. */
    onDecryptFolder: (Album) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val mode = uiState.settings.contentViewMode
    // Reaches every FolderPane without threading it through each split layout.
    androidx.compose.runtime.CompositionLocalProvider(LocalDecryptFolder provides onDecryptFolder) {
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { onPaneBounds(it.boundsInWindow()) },
    ) {
        when (mode) {
            ContentViewMode.EXPAND_FOLDERS -> Unit
            ContentViewMode.LAST_FOLDER, ContentViewMode.CURRENT_SESSION -> {
                Box(Modifier.fillMaxSize().padding(12.dp)) {
                    AnimatedContent(
                        targetState = uiState.contentFolderId,
                        transitionSpec = {
                            (fadeIn(tween(180)) + scaleIn(initialScale = 0.98f, animationSpec = tween(180))) togetherWith
                                fadeOut(tween(150))
                        },
                        label = "lastFolderAnim",
                        modifier = Modifier.fillMaxSize(),
                    ) { folderId ->
                        val album = albumsById[folderId]
                        if (album == null) {
                            Hint(stringResource(R.string.choose_folder_hint))
                        } else {
                            FolderPane(
                                album = album,
                                files = uiState.visibleFilesIn(album.id),
                                uiState = uiState,
                                showClose = false,
                                hovered = false,
                                onOpenMedia = onOpenMedia,
                                onPlayVideo = onPlayVideo,
                                onToggleSelect = onToggleSelect,
                                onMarqueeFiles = onMarqueeFiles,
                                onClose = {},
                                onTwoFingerStart = onTwoFingerStart,
                                onTwoFingerDrag = onTwoFingerDrag,
                                onTwoFingerEnd = onTwoFingerEnd,
                                onGetFolderScroll = onGetFolderScroll,
                                onSaveFolderScroll = onSaveFolderScroll,
                                onGetFolderGridState = onGetFolderGridState,
                                onContentOrderMove = onContentOrderMove,
                                onTransientCountChange = onTransientCountChange,
                            )
                        }
                    }
                }
            }
            ContentViewMode.MULTI_FOLDER -> {
                MultiFolderSplit(
                    uiState = uiState,
                    albumsById = albumsById,
                    onOpenMedia = onOpenMedia,
                    onPlayVideo = onPlayVideo,
                    onToggleSelect = onToggleSelect,
                    onMarqueeFiles = onMarqueeFiles,
                    onClosePane = onClosePane,
                    onTwoFingerStart = onTwoFingerStart,
                    onTwoFingerDrag = onTwoFingerDrag,
                    onTwoFingerEnd = onTwoFingerEnd,
                    onGetFolderScroll = onGetFolderScroll,
                    onSaveFolderScroll = onSaveFolderScroll,
                    onGetFolderGridState = onGetFolderGridState,
                    onContentOrderMove = onContentOrderMove,
                    onTransientCountChange = onTransientCountChange,
                )
            }
            ContentViewMode.CLICK_DRAG -> {
                ClickDragSplit(
                    uiState = uiState,
                    albumsById = albumsById,
                    onOpenMedia = onOpenMedia,
                    onPlayVideo = onPlayVideo,
                    onToggleSelect = onToggleSelect,
                    onMarqueeFiles = onMarqueeFiles,
                    onClosePane = onClosePane,
                    onHitboxSelect = onHitboxSelect,
                    onTwoFingerStart = onTwoFingerStart,
                    onTwoFingerDrag = onTwoFingerDrag,
                    onTwoFingerEnd = onTwoFingerEnd,
                    onSplitDragEnd = onSplitDragEnd,
                    onSwapStackedPanes = onSwapStackedPanes,
                    onGetFolderScroll = onGetFolderScroll,
                    onSaveFolderScroll = onSaveFolderScroll,
                    onGetFolderGridState = onGetFolderGridState,
                    onContentOrderMove = onContentOrderMove,
                    onTransientCountChange = onTransientCountChange,
                )
            }
        }
    }
    }
}

@Composable
private fun MultiFolderSplit(
    uiState: GalleryUiState,
    albumsById: Map<Long, Album>,
    onOpenMedia: (GalleryMedia) -> Unit,
    onPlayVideo: (GalleryMedia) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onMarqueeFiles: (List<Long>) -> Unit,
    onClosePane: (Long) -> Unit,
    onTwoFingerStart: (Long, Offset) -> Unit,
    onTwoFingerDrag: (Offset) -> Unit,
    onTwoFingerEnd: () -> Unit,
    onGetFolderScroll: (Long) -> FolderScrollPos?,
    onSaveFolderScroll: (Long, Int, Int) -> Unit,
    onGetFolderGridState: (Long) -> androidx.compose.foundation.lazy.grid.LazyGridState? = { null },
    onContentOrderMove: (Int, Int) -> Unit = { _, _ -> },
    onTransientCountChange: (Int?) -> Unit = {},
) {
    val ids = visualFolderIds(
        openIds = uiState.openFolderIds,
        dragKind = uiState.dragKind,
        hoveredSlot = uiState.hoveredSlot,
        draggingFolderId = uiState.draggingFolderId,
    )
    val draggingFolder = uiState.dragKind == DragKind.FOLDER
    val replaceHover = draggingFolder && uiState.hoveredSlot == SLOT_MIDDLE &&
        uiState.openFolderIds.isNotEmpty()

    Box(Modifier.fillMaxSize()) {
        if (ids.isEmpty()) {
            Hint(stringResource(R.string.multi_folder_hint))
        } else {
            AnimatedSplitColumn(ids = ids) { albumId, index, count ->
                val album = albumsById[albumId]
                if (album != null) {
                    val hovered = draggingFolder && !replaceHover && when (count) {
                        1 -> uiState.hoveredSlot == SLOT_MIDDLE
                        else -> {
                            (index == 0 && uiState.hoveredSlot == SLOT_TOP) ||
                                (index == 1 && uiState.hoveredSlot == SLOT_BOTTOM)
                        }
                    }
                    FolderPane(
                        album = album,
                        files = uiState.visibleFilesIn(album.id),
                        uiState = uiState,
                        showClose = albumId in uiState.openFolderIds,
                        hovered = hovered,
                        onOpenMedia = onOpenMedia,
                        onPlayVideo = onPlayVideo,
                        onToggleSelect = onToggleSelect,
                        onMarqueeFiles = onMarqueeFiles,
                        onClose = { onClosePane(album.id) },
                        onTwoFingerStart = onTwoFingerStart,
                        onTwoFingerDrag = onTwoFingerDrag,
                        onTwoFingerEnd = onTwoFingerEnd,
                        onGetFolderScroll = onGetFolderScroll,
                        onSaveFolderScroll = onSaveFolderScroll,
                        onGetFolderGridState = onGetFolderGridState,
                        onContentOrderMove = onContentOrderMove,
                        onTransientCountChange = onTransientCountChange,
                    )
                }
            }
        }
        if (replaceHover) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f))
                    .border(3.dp, MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.drop_to_replace),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else if (draggingFolder && uiState.openFolderIds.isEmpty() && uiState.hoveredSlot >= 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(3.dp, MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun ClickDragSplit(
    uiState: GalleryUiState,
    albumsById: Map<Long, Album>,
    onOpenMedia: (GalleryMedia) -> Unit,
    onPlayVideo: (GalleryMedia) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onMarqueeFiles: (List<Long>) -> Unit,
    onClosePane: (Long) -> Unit,
    onHitboxSelect: (Int) -> Unit,
    onTwoFingerStart: (Long, Offset) -> Unit,
    onTwoFingerDrag: (Offset) -> Unit,
    onTwoFingerEnd: () -> Unit,
    onSplitDragEnd: (Float, Float, Float) -> Unit,
    onSwapStackedPanes: () -> Unit,
    onGetFolderScroll: (Long) -> FolderScrollPos?,
    onSaveFolderScroll: (Long, Int, Int) -> Unit,
    onGetFolderGridState: (Long) -> androidx.compose.foundation.lazy.grid.LazyGridState? = { null },
    onContentOrderMove: (Int, Int) -> Unit = { _, _ -> },
    onTransientCountChange: (Int?) -> Unit = {},
) {
    val ids = uiState.openFolderIds
    val draggingFolder = uiState.dragKind == DragKind.FOLDER
    val pane: @Composable (Long) -> Unit = { albumId ->
        val album = albumsById[albumId]
        if (album != null) {
            FolderPane(
                album = album,
                files = uiState.visibleFilesIn(album.id),
                uiState = uiState,
                showClose = true,
                hovered = false,
                inSplit = ids.size >= 2,
                pulseHighlight = album.id == uiState.highlightedFolderId,
                onOpenMedia = onOpenMedia,
                onPlayVideo = onPlayVideo,
                onToggleSelect = onToggleSelect,
                onMarqueeFiles = onMarqueeFiles,
                onClose = { onClosePane(album.id) },
                onTwoFingerStart = onTwoFingerStart,
                onTwoFingerDrag = onTwoFingerDrag,
                onTwoFingerEnd = onTwoFingerEnd,
                onGetFolderScroll = onGetFolderScroll,
                onSaveFolderScroll = onSaveFolderScroll,
                onGetFolderGridState = onGetFolderGridState,
                onContentOrderMove = onContentOrderMove,
                onTransientCountChange = onTransientCountChange,
            )
        }
    }
    // One movable pane per folder: going from two panes to one moves the remaining pane
    // instead of building it again, so its tiles keep their loaded thumbnails.
    val latestPane = rememberUpdatedState(pane)
    val movablePanes = remember { mutableMapOf<Long, @Composable (Long) -> Unit>() }
    SideEffect { movablePanes.keys.retainAll(ids.toSet()) }
    val movablePane: @Composable (Long) -> Unit = { albumId ->
        movablePanes.getOrPut(albumId) { movableContentOf { id: Long -> latestPane.value(id) } }(albumId)
    }
    val topPulse = rememberPanePulse(ids.size >= 2 && ids[0] == uiState.highlightedFolderId)
    val bottomPulse = rememberPanePulse(ids.size >= 2 && ids[1] == uiState.highlightedFolderId)
    Box(Modifier.fillMaxSize()) {
        when {
            ids.isEmpty() -> Hint(stringResource(R.string.click_drag_hint))
            ids.size == 1 -> movablePane(ids[0])
            else -> ResizableFolderSplit(
                topId = ids[0],
                bottomId = ids[1],
                splitRatio = uiState.splitRatio,
                paneStack = uiState.paneStack,
                handleEnabled = !uiState.showPaneHitboxes,
                onDragEnd = onSplitDragEnd,
                onSwapStacked = onSwapStackedPanes,
                topLabel = albumsById[ids[0]]?.name.orEmpty(),
                bottomLabel = albumsById[ids[1]]?.name.orEmpty(),
                onCloseTop = { onClosePane(ids[0]) },
                onCloseBottom = { onClosePane(ids[1]) },
                topHighlight = topPulse,
                bottomHighlight = bottomPulse,
            ) { albumId -> movablePane(albumId) }
        }
        if (uiState.showPaneHitboxes) {
            PaneHitboxes(
                hoveredSlot = uiState.hoveredSlot,
                clickable = uiState.pendingHitboxAlbumId != AlbumIds.NONE && !draggingFolder,
                onSelect = onHitboxSelect,
            )
        } else if (draggingFolder && ids.isEmpty() && uiState.hoveredSlot >= 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(3.dp, MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun PaneHitboxes(
    hoveredSlot: Int,
    clickable: Boolean,
    onSelect: (Int) -> Unit,
) {
    val zones = listOf(
        SLOT_TOP to R.string.hitbox_top,
        SLOT_MIDDLE to R.string.hitbox_whole,
        SLOT_BOTTOM to R.string.hitbox_bottom,
    )
    Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
        zones.forEachIndexed { index, (slot, label) ->
            val active = hoveredSlot == slot
            val ratio = if (index == 1) 0.2f else 0.4f
            Box(
                modifier = Modifier
                    .weight(ratio)
                    .fillMaxWidth()
                    .then(
                        if (clickable) {
                            Modifier.clickable { onSelect(slot) }
                        } else {
                            Modifier
                        },
                    )
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
                        },
                    )
                    .border(
                        width = if (active) 3.dp else 1.dp,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun StableSplitColumn(
    ids: List<Long>,
    content: @Composable (albumId: Long, index: Int, count: Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ids.forEachIndexed { index, id ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = 3.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            androidx.compose.runtime.key(id) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    content(id, index, ids.size)
                }
            }
        }
    }
}

@Composable
private fun AnimatedSplitColumn(
    ids: List<Long>,
    content: @Composable (albumId: Long, index: Int, count: Int) -> Unit,
) {
    val target = 1f / ids.size.coerceAtLeast(1)
    val weights = remember { mutableStateMapOf<Long, Animatable<Float, AnimationVector1D>>() }
    val spec = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        )
    }

    LaunchedEffect(ids, target) {
        ids.forEach { id ->
            if (weights[id] == null) {
                weights[id] = Animatable(if (weights.isNotEmpty()) 0.04f else target)
            }
        }
        coroutineScope {
            ids.forEach { id ->
                launch { weights[id]?.animateTo(target, spec) }
            }
            weights.keys.filter { it !in ids }.toList().forEach { stale ->
                launch {
                    weights[stale]?.animateTo(0.04f, spec)
                    weights.remove(stale)
                }
            }
        }
    }

    val dividerPx = with(androidx.compose.ui.platform.LocalDensity.current) { DIVIDER_THICKNESS.roundToPx() }
    // Like a Column with weights, but the weights are read while measuring, not in composition:
    // each frame of the opening/closing animation lays the panes out again instead of rebuilding
    // both file grids.
    androidx.compose.ui.layout.Layout(
        content = {
            ids.forEachIndexed { index, id ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = DIVIDER_THICKNESS,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                androidx.compose.runtime.key(id) {
                    Box(Modifier.fillMaxWidth()) {
                        content(id, index, ids.size)
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val paneWeights = ids.map { (weights[it]?.value ?: target).coerceAtLeast(0.04f) }
        val totalWeight = paneWeights.sum().coerceAtLeast(0.0001f)
        val free = (height - (ids.size - 1).coerceAtLeast(0) * dividerPx).coerceAtLeast(0)
        // Children in order: pane, then divider + pane for each further one.
        var paneIndex = 0
        var given = 0
        val placeables = measurables.mapIndexed { i, measurable ->
            val isDivider = i % 2 == 1
            if (isDivider) {
                measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, dividerPx))
            } else {
                val last = paneIndex == ids.size - 1
                val paneHeight = if (last) (free - given).coerceAtLeast(0) else (free * paneWeights[paneIndex] / totalWeight).toInt()
                given += paneHeight
                paneIndex++
                measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, paneHeight))
            }
        }
        layout(width, height) {
            var y = 0
            for (placeable in placeables) {
                placeable.place(0, y)
                y += placeable.height
            }
        }
    }
}

private val DIVIDER_THICKNESS = 3.dp

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/** Folder reminder pulse: on at once, fades out over 600 ms. */
@Composable
private fun rememberPanePulse(active: Boolean): Float {
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = if (active) {
            snap()
        } else {
            tween(durationMillis = 600, easing = LinearEasing)
        },
        label = "panePulse",
    )
    return alpha
}

@Composable
private fun FolderPane(
    album: Album,
    files: List<GalleryMedia>,
    uiState: GalleryUiState,
    showClose: Boolean,
    hovered: Boolean,
    inSplit: Boolean = false,
    pulseHighlight: Boolean = false,
    onOpenMedia: (GalleryMedia) -> Unit,
    onPlayVideo: (GalleryMedia) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onMarqueeFiles: (List<Long>) -> Unit,
    onClose: () -> Unit,
    onTwoFingerStart: (Long, Offset) -> Unit,
    onTwoFingerDrag: (Offset) -> Unit,
    onTwoFingerEnd: () -> Unit,
    onGetFolderScroll: (Long) -> FolderScrollPos?,
    onSaveFolderScroll: (Long, Int, Int) -> Unit,
    onGetFolderGridState: (Long) -> androidx.compose.foundation.lazy.grid.LazyGridState? = { null },
    onContentOrderMove: (Int, Int) -> Unit = { _, _ -> },
    onTransientCountChange: (Int?) -> Unit = {},
) {
    val dimmed = uiState.dragKind == DragKind.FILES &&
        uiState.filesDragSourceAlbumId == album.id
    // In the split the frame is drawn by ResizableFolderSplit, so it can include the name strip.
    val pulse = rememberPanePulse(pulseHighlight)
    val pulseAlpha = if (inSplit) 0f else pulse
    val pulseColor = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (hovered) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary)
                } else {
                    Modifier
                },
            )
            .drawWithContent {
                drawContent()
                if (pulseAlpha > 0.01f) {
                    drawRect(pulseColor.copy(alpha = 0.21f * pulseAlpha))
                }
            }
            .then(
                if (pulseAlpha > 0.01f) {
                    Modifier.border(3.dp, pulseColor.copy(alpha = 0.5f * pulseAlpha))
                } else {
                    Modifier
                },
            ),
    ) {
        // In the two-pane split the names live in the strip between the panes instead.
        if (!inSplit) Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
            )
            if (showClose) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close_pane),
                    )
                }
            }
        }
        key(album.id) {
            MediaTiles(
                media = if (uiState.contentOrderMode && uiState.contentOrderFolderId == album.id) {
                    uiState.contentOrderFiles
                } else {
                    files
                },
                columns = uiState.settings.foldableFileColumns,
                scrollLabelField = uiState.scrollLabelField(album.id),
                selectedIds = uiState.selectedFileIdsOrdered,
                dimmed = dimmed,
                showFileNames = uiState.settings.showFileNames,
                hiddenIds = uiState.settings.hiddenMediaIds,
                onOpen = onOpenMedia,
                onPlayVideo = onPlayVideo,
                onToggleSelect = onToggleSelect,
                onMarqueeSelect = onMarqueeFiles,
                onTransientCountChange = onTransientCountChange,
                twoFingerDragEnabled = uiState.settings.contentViewMode.usesFolderDrag() &&
                    !(uiState.contentOrderMode && uiState.contentOrderFolderId == album.id),
                onTwoFingerStart = { onTwoFingerStart(album.id, it) },
                onTwoFingerDrag = onTwoFingerDrag,
                onTwoFingerEnd = onTwoFingerEnd,
                linearMarquee = uiState.settings.fileSelectMode == FileSelectMode.LINEAR,
                videoDoubleClickToGallery = uiState.settings.videoDoubleClickToGallery,
                albumId = album.id,
                initialScrollPos = onGetFolderScroll(album.id),
                gridState = onGetFolderGridState(album.id),
                onScrollPosChanged = { idx, off -> onSaveFolderScroll(album.id, idx, off) },
                emptyMessage = when (album.id) {
                    AlbumIds.FAVORITES -> stringResource(R.string.favorites_empty)
                    AlbumIds.TRASH -> stringResource(R.string.trash_empty)
                    else -> stringResource(R.string.no_media)
                },
                isLoading = uiState.isFolderLoading(album.id),
                contentOrderMode = uiState.contentOrderMode && uiState.contentOrderFolderId == album.id,
                onContentOrderMove = onContentOrderMove,
                bottomContentPadding = if (uiState.contentOrderMode && uiState.contentOrderFolderId == album.id) 88.dp else 0.dp,
                scrollerOnRight = uiState.settings.scrollerOnRight,
                scrollerEnabled = uiState.settings.scrollerEnabled,
                scrollerSmooth = uiState.settings.scrollerSmooth,
                forceScrollToTopOnNewContent = uiState.settings.newContentScrollsToTop,
                isSecureFolder = album.isSecure,
                onDecryptFolderPermanently = if (album.isSecure) {
                    val decrypt = LocalDecryptFolder.current
                    ({ decrypt(album) })
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun visualFolderIds(
    openIds: List<Long>,
    dragKind: DragKind,
    hoveredSlot: Int,
    draggingFolderId: Long,
): List<Long> {
    val existing = openIds.filter { it != draggingFolderId }
    val preview = dragKind == DragKind.FOLDER &&
        hoveredSlot >= 0 &&
        draggingFolderId != AlbumIds.NONE
    if (!preview) return existing
    return when (hoveredSlot) {
        SLOT_MIDDLE ->
            if (existing.isEmpty()) listOf(draggingFolderId) else existing
        SLOT_TOP -> {
            val bottom = existing.getOrNull(1) ?: existing.getOrNull(0)
            if (bottom == null) listOf(draggingFolderId) else listOf(draggingFolderId, bottom)
        }
        else -> {
            val top = existing.getOrNull(0)
            if (top == null) listOf(draggingFolderId) else listOf(top, draggingFolderId)
        }
    }
}

fun dropSlotFor(
    pointer: Offset,
    pane: Rect,
    openCount: Int = 0,
    equalThirds: Boolean = false,
): Int {
    if (!pane.contains(pointer) || pane.height <= 0f) return -1
    val rel = (pointer.y - pane.top) / pane.height
    if (equalThirds) {
        return when {
            rel < 1f / 3f -> SLOT_TOP
            rel < 2f / 3f -> SLOT_MIDDLE
            else -> SLOT_BOTTOM
        }
    }
    return when {
        rel < 0.40f -> SLOT_TOP
        rel < 0.60f -> SLOT_MIDDLE
        else -> SLOT_BOTTOM
    }
}

fun fileDropAlbumId(
    pointer: Offset,
    pane: Rect,
    state: GalleryUiState,
): Long {
    val ids = state.openFolderIds
    if (ids.isEmpty() || !pane.contains(pointer) || pane.height <= 0f) return AlbumIds.NONE
    if (ids.size == 1) return ids[0]
    val rel = ((pointer.y - pane.top) / pane.height).coerceIn(0f, 1f)
    val topShare = when {
        state.settings.contentViewMode == ContentViewMode.CLICK_DRAG -> when (state.paneStack) {
            PaneStack.TOP -> CANVAS_RATIO
            PaneStack.BOTTOM -> 1f - CANVAS_RATIO
            PaneStack.NONE -> state.splitRatio
        }
        else -> 0.5f
    }
    return if (rel < topShare) ids[0] else ids[1]
}
