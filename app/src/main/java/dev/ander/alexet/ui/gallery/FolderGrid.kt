package dev.ander.alexet.ui.gallery

import dev.ander.alexet.ui.components.aiglow.aiGlint
import dev.ander.alexet.ui.components.LocalLightMode
import dev.ander.alexet.ui.components.AnimatedVisibility
import dev.ander.alexet.ui.components.DeferredCrossfade
import dev.ander.alexet.ui.components.deferredAnimateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import dev.ander.alexet.ui.components.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import dev.ander.alexet.R
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.AlbumIds
import dev.ander.alexet.data.FolderDeleteProgress
import dev.ander.alexet.data.folderDownloadPercent
import dev.ander.alexet.ui.components.DownloadBadge
import dev.ander.alexet.ui.components.HiddenBadge
import dev.ander.alexet.ui.components.LocalFolderCoverBumps
import dev.ander.alexet.ui.components.NameMixerBadge
import dev.ander.alexet.ui.components.LocalFolderThumbEpoch
import dev.ander.alexet.ui.components.MediaThumbnail

private val FolderGap = 1.dp

/** The dark band under a folder's name; one for all tiles. */
private val FolderNameBand = Brush.verticalGradient(
    0f to Color.Transparent,
    0.45f to Color.Black.copy(alpha = 0.28f),
    1f to Color.Black.copy(alpha = 0.72f),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun FolderGrid(
    albums: List<Album>,
    columns: Int,
    visibleRows: Int,
    hoveredAlbumId: Long,
    stampedAlbumIds: Set<Long>,
    hasFileSelection: Boolean,
    folderDragEnabled: Boolean,
    dimmed: Boolean,
    onAlbumClick: (Long) -> Unit,
    onStamp: (Long) -> Unit,
    onFolderDragStart: (Long) -> Unit,
    onFolderDrag: (Offset) -> Unit,
    onFolderDragEnd: () -> Unit,
    /** A tile's layout coordinates (its window bounds are worked out when read); null: no drop target. */
    onAlbumBounds: (Long, LayoutCoordinates?) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    selectedAlbumIds: Set<Long> = emptySet(),
    /** False where folders are only picked (no selection to keep), e.g. the sticker picker. */
    selectionEnabled: Boolean = true,
    hiddenAlbumIds: Set<Long> = emptySet(),
    anonymousAlbumIds: Set<Long> = emptySet(),
    highlightedAlbumId: Long = AlbumIds.NONE,
    onToggleAlbum: (Long) -> Unit = {},
    onMarqueeSelect: (List<Long>) -> Unit = {},
    onTransientCountChange: (Int?) -> Unit = {},
    isLoading: Boolean = false,
    downloadingAlbumIds: Set<Long> = emptySet(),
    /** Read by each tile (a delete's progress changes every batch): the grid itself does not recompose for it. */
    deletingFolders: () -> Map<Long, FolderDeleteProgress> = { emptyMap() },
    onCancelFolderDelete: (Long) -> Unit = {},
) {
    val safeAlbums = remember(albums) { albums.distinctBy { it.id } }
    if (safeAlbums.isEmpty() && isLoading) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (LocalLightMode.current) {
                // Light mode: a plain spinner instead of the morphing shapes.
                androidx.compose.material3.CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                LoadingIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        return
    }

    val itemBounds = remember { TileBounds() }
    val albumIds = remember(safeAlbums) { safeAlbums.map { it.id }.toSet() }
    SideEffect { itemBounds.retainAll(albumIds) }

    var marqueeActive by remember { mutableStateOf(false) }
    var liveSelectedAlbumIds by remember { mutableStateOf(selectedAlbumIds.toList()) }
    LaunchedEffect(selectedAlbumIds) {
        if (!marqueeActive) liveSelectedAlbumIds = selectedAlbumIds.toList()
    }
    val displayAlbumIdSet = remember(liveSelectedAlbumIds) { liveSelectedAlbumIds.toSet() }
    MarqueeBox(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        enabled = selectionEnabled && !folderDragEnabled,
        itemBoundsInWindow = itemBounds,
        selectedIds = liveSelectedAlbumIds,
        onSelectIds = { ids ->
            liveSelectedAlbumIds = ids
            onTransientCountChange(ids.size)
        },
        onSelectingChange = { active ->
            if (active) {
                liveSelectedAlbumIds = selectedAlbumIds.toList()
                marqueeActive = true
                onTransientCountChange(selectedAlbumIds.size)
            } else {
                marqueeActive = false
                onTransientCountChange(null)
                onMarqueeSelect(liveSelectedAlbumIds)
            }
        },
        onAutoScroll = { gridState.dispatchRawDelta(it) },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceIn(1, 30)),
            state = gridState,
            userScrollEnabled = !marqueeActive,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (dimmed) {
                        drawRect(Color.Black.copy(alpha = 0.45f))
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(FolderGap),
            verticalArrangement = Arrangement.spacedBy(FolderGap),
        ) {
            items(safeAlbums, key = { it.id }) { album ->
                // A disposed tile (scrolled away, or grid replaced by folder/search layer)
                // must stop being a drop target.
                DisposableEffect(album.id) {
                    onDispose {
                        onAlbumBounds(album.id, null)
                        itemBounds.remove(album.id)
                    }
                }
                val deleting = deletingFolders()[album.id]
                FolderTile(
                    album = album,
                    deleting = deleting,
                    onCancelDelete = { onCancelFolderDelete(album.id) },
                    hovered = album.id == hoveredAlbumId,
                    highlighted = album.id == highlightedAlbumId,
                    stamped = album.id in stampedAlbumIds || album.id in displayAlbumIdSet,
                    hidden = album.id in hiddenAlbumIds,
                    isAnonymous = album.id in anonymousAlbumIds,
                    downloading = album.id in downloadingAlbumIds,
                    folderDragEnabled = folderDragEnabled && !hasFileSelection && displayAlbumIdSet.isEmpty(),
                    onClick = {
                        when {
                            displayAlbumIdSet.isNotEmpty() -> onToggleAlbum(album.id)
                            hasFileSelection -> onStamp(album.id)
                            else -> onAlbumClick(album.id)
                        }
                    },
                    onLongClick = {
                        when {
                            hasFileSelection -> onStamp(album.id)
                            else -> onToggleAlbum(album.id)
                        }
                    },
                    onDragStart = { onFolderDragStart(album.id) },
                    onDrag = onFolderDrag,
                    onDragEnd = onFolderDragEnd,
                    onBounds = {
                        // A folder being deleted is no drop target and can't be marquee-selected.
                        if (deleting == null) {
                            onAlbumBounds(album.id, it)
                            itemBounds[album.id] = it
                        } else {
                            onAlbumBounds(album.id, null)
                            itemBounds.remove(album.id)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LazyGridItemScope.FolderTile(
    album: Album,
    deleting: FolderDeleteProgress?,
    onCancelDelete: () -> Unit,
    hovered: Boolean,
    highlighted: Boolean,
    stamped: Boolean,
    hidden: Boolean,
    isAnonymous: Boolean = false,
    downloading: Boolean = false,
    folderDragEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onBounds: (LayoutCoordinates) -> Unit,
) {
    // Most tiles never pulse, hover or get stamped: nothing animates until one first does.
    val pulseAlpha by deferredAnimateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        restingValue = 0f,
        animationSpec = if (highlighted) {
            snap()
        } else {
            tween(durationMillis = 600, easing = LinearEasing)
        },
        label = "tilePulse",
    )
    val hoverScale by deferredAnimateFloatAsState(
        targetValue = if (hovered) 1.06f else 1f,
        restingValue = 1f,
        label = "folderHover",
    )
    val stampedScale by deferredAnimateFloatAsState(
        targetValue = if (stamped) 0.93f else 1f,
        restingValue = 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "folderStampedScale",
    )
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val dragStart = rememberUpdatedState(onDragStart)
    val drag = rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            // The edge between the panes moves: the tile follows it as it is, no own glide.
            .then(if (LocalLightMode.current || LocalPaneResizing.current.value) Modifier else Modifier.animateItem())
            .zIndex(if (hovered || pulseAlpha > 0.01f) 2f else 0f)
            .graphicsLayer {
                val currentScale = maxOf(hoverScale, 1f + 0.06f * pulseAlpha) * stampedScale
                scaleX = currentScale
                scaleY = currentScale
            }
            .onGloballyPositioned { layout ->
                coords = layout
                // Only the coordinates: the bounds are worked out when a gesture needs them.
                onBounds(layout)
            }
            .then(
                if (deleting != null) {
                    // Locked until the deleting ends: no opening, selecting or dragging.
                    Modifier
                } else if (folderDragEnabled) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            dragStart.value()
                            coords?.let { layout ->
                                val center = Offset(
                                    layout.size.width / 2f,
                                    layout.size.height / 2f,
                                )
                                drag.value(layout.localToWindow(center))
                            }
                        },
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
    ) {
        // The last progress stays drawn while the tile fades back (cancelled, some files left).
        var lastDeleting by remember { mutableStateOf(deleting) }
        if (deleting != null) lastDeleting = deleting
        DeferredCrossfade(targetState = deleting != null, label = "folderDeleting") { isDeleting ->
            val progress = lastDeleting
            if (isDeleting && progress != null) {
                DeletingFolderCover(progress = progress, onCancel = onCancelDelete)
            } else {
                FolderCover(
                    album = album,
                    stamped = stamped,
                    highlighted = highlighted,
                    highlightAlpha = pulseAlpha,
                    hidden = hidden,
                    isAnonymous = isAnonymous,
                    downloading = downloading,
                )
            }
        }
    }
}

/**
 * A folder being deleted: its name and count give way to a trash can inside a ring that fills
 * as the files go, with Cancel below (left out on tiles too small for it; the notification
 * has Cancel too).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeletingFolderCover(progress: FolderDeleteProgress, onCancel: () -> Unit) {
    val fraction by animateFloatAsState(
        targetValue = progress.fraction,
        animationSpec = tween(durationMillis = 400),
        label = "folderDeleteFraction",
    )
    // Light mode: no endless animation, the trash stays upright.
    val light = LocalLightMode.current
    val swing = if (light) {
        0f
    } else {
        val tilt = rememberInfiniteTransition(label = "trashTilt")
        val animatedSwing by tilt.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "trashSwing",
        )
        animatedSwing
    }
    val description = stringResource(R.string.folder_delete_in_progress)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = "$description ${progress.name}: ${progress.done} / ${progress.total}" },
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight)
        val showCancel = side >= 104.dp
        val ring = side * if (showCancel) 0.48f else 0.62f
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                if (light) {
                    // Light mode: a plain ring, no wave moving every frame.
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                } else {
                    CircularWavyProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(ring * 0.46f)
                        .graphicsLayer {
                            // A gentle swing while files are going; still while waiting in line.
                            if (progress.running) rotationZ = 7f * swing
                        },
                )
            }
            if (showCancel) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun FolderCover(
    album: Album,
    modifier: Modifier = Modifier,
    stamped: Boolean = false,
    highlighted: Boolean = false,
    highlightAlpha: Float = 0f,
    hidden: Boolean = false,
    isAnonymous: Boolean = false,
    downloading: Boolean = false,
) {
    Box(modifier.fillMaxSize()) {
        val cover = album.cover
        val hiddenCover = LocalHiddenCoverConfig.current
        if (album.isHidden && !album.isSecure && hiddenCover.style != dev.ander.alexet.data.HiddenCoverStyle.OFF) {
            HiddenFolderCover(cover = cover, config = hiddenCover, modifier = Modifier.fillMaxSize())
        } else if (album.isSecure) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
        } else if (cover != null) {
            val epoch = LocalFolderThumbEpoch.current
            val bump = LocalFolderCoverBumps.current[album.id] ?: 0
            val generation = if (bump > 0) bump else epoch
            // The newest file being made by a quick edit has no picture yet: the cover shows its
            // original's, as the file's tile does.
            val shown = LocalQuickEditTile.current
                ?.takeIf { it.target.outputPath != null && it.matches(cover) }
                ?.target?.source ?: cover
            val cacheKey = "thumb-${shown.id}-${shown.dateModifiedMs}"
            MediaThumbnail(
                media = shown,
                modifier = Modifier.fillMaxSize(),
                showPlayIcon = false,
                showDuration = false,
                shape = RectangleShape,
                cacheKey = cacheKey,
                thumbGeneration = generation,
                isFolderCover = true,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(72.dp)
                .background(FolderNameBand),
        )
        // Light mode: the names without their blurred shadow (the dark band under them stays).
        val textShadows = !LocalLightMode.current
        val titleSmall = MaterialTheme.typography.titleSmall
        val bodySmall = MaterialTheme.typography.bodySmall
        val nameStyle = remember(titleSmall, textShadows) {
            titleSmall.copy(
                color = Color.White,
                shadow = if (!textShadows) null else Shadow(
                    color = Color.Black.copy(alpha = 0.55f),
                    offset = Offset(0f, 1f),
                    blurRadius = 4f,
                ),
            )
        }
        val countStyle = remember(bodySmall, textShadows) {
            bodySmall.copy(
                color = Color.White.copy(alpha = 0.92f),
                shadow = if (!textShadows) null else Shadow(
                    color = Color.Black.copy(alpha = 0.5f),
                    offset = Offset(0f, 1f),
                    blurRadius = 3f,
                ),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, end = 12.dp, bottom = 8.dp),
        ) {
            Text(
                text = album.name.ifBlank { "—" },
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = nameStyle,
            )
            Text(
                text = if (album.otherCount > 0) "${album.itemCount} (+${album.otherCount})" else album.itemCount.toString(),
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 13.sp,
                maxLines = 1,
                style = countStyle,
            )
        }
        if (hidden || isAnonymous) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hidden) HiddenBadge()
                if (isAnonymous) NameMixerBadge()
            }
        }
        // Most tiles never show these badges: nothing is set up until one first does.
        dev.ander.alexet.ui.components.DeferredAnimatedVisibility(
            visible = downloading,
            enter = scaleIn(
                animationSpec = spring(
                    stiffness = Spring.StiffnessMediumLow,
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                ),
                initialScale = 0.4f,
            ) + fadeIn(animationSpec = tween(durationMillis = 150)),
            exit = scaleOut(
                animationSpec = tween(durationMillis = 100),
                targetScale = 0.4f,
            ) + fadeOut(animationSpec = tween(durationMillis = 100)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            // Only this tile follows its downloads: the whole map changes every 250 ms.
            val activeDownloads = LocalActiveDownloads.current
            val percent by remember(album, activeDownloads) {
                androidx.compose.runtime.derivedStateOf { folderDownloadPercent(album, activeDownloads()) }
            }
            DownloadBadge(percent = percent)
        }
        if (!stamped && highlightAlpha > 0.01f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.21f * highlightAlpha)),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .border(
                        3.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f * highlightAlpha),
                        RectangleShape,
                    ),
            )
        }
        dev.ander.alexet.ui.components.DeferredAnimatedVisibility(
            visible = stamped,
            enter = fadeIn(animationSpec = tween(durationMillis = 150)),
            exit = fadeOut(animationSpec = tween(durationMillis = 120)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(3.dp, MaterialTheme.colorScheme.primary, RectangleShape),
            )
        }
        dev.ander.alexet.ui.components.DeferredAnimatedVisibility(
            visible = stamped,
            enter = scaleIn(
                animationSpec = spring(
                    stiffness = Spring.StiffnessMediumLow,
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                ),
                initialScale = 0.4f,
            ) + fadeIn(animationSpec = tween(durationMillis = 150)),
            exit = scaleOut(
                animationSpec = tween(durationMillis = 100),
                targetScale = 0.4f,
            ) + fadeOut(animationSpec = tween(durationMillis = 100)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // AI search: the folders it covers shine (provided around their grid). On its own layer:
        // each frame of the border redraws only the border, not the cover under it.
        val glint = dev.ander.alexet.ui.components.aiglow.LocalAiGlint.current
        if (glint != null) {
            Box(Modifier.matchParentSize().graphicsLayer().aiGlint(glint))
        }
    }
}
