package dev.ander.alexet.ui.gallery

import androidx.compose.runtime.DisposableEffect
import android.os.SystemClock
import dev.ander.alexet.ui.components.LocalLightMode
import dev.ander.alexet.ui.components.AnimatedVisibility
import androidx.compose.animation.core.Spring
import dev.ander.alexet.ui.components.animateFloatAsState
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.ander.alexet.R
import dev.ander.alexet.data.ActiveDownload
import dev.ander.alexet.data.AlbumIds
import dev.ander.alexet.data.DownloadTracker
import dev.ander.alexet.data.FolderScrollPos
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.SortField
import dev.ander.alexet.ui.components.LocalFolderThumbEpoch
import dev.ander.alexet.ui.components.SquareThumbnail

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaTiles(
    media: List<GalleryMedia>,
    columns: Int,
    selectedIds: List<Long>,
    dimmed: Boolean,
    twoFingerDragEnabled: Boolean,
    onOpen: (GalleryMedia) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onTwoFingerStart: (Offset) -> Unit,
    onTwoFingerDrag: (Offset) -> Unit,
    onTwoFingerEnd: () -> Unit,
    modifier: Modifier = Modifier,
    albumId: Long = AlbumIds.NONE,
    initialScrollPos: FolderScrollPos? = null,
    onScrollPosChanged: ((Int, Int) -> Unit)? = null,
    onPlayVideo: (GalleryMedia) -> Unit = {},
    showFileNames: Boolean = false,
    hiddenIds: Set<Long> = emptySet(),
    onMarqueeSelect: (List<Long>) -> Unit = {},
    onTransientCountChange: (Int?) -> Unit = {},
    marqueeEnabled: Boolean = true,
    linearMarquee: Boolean = false,
    videoDoubleClickToGallery: Boolean = false,
    emptyMessage: String = stringResource(R.string.no_media),
    isLoading: Boolean = false,
    contentOrderMode: Boolean = false,
    onContentOrderMove: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState? = null,
    /** Drawn under the grid / over it, in the grid's coordinates (AI effects). */
    gridUnderlay: (@Composable androidx.compose.foundation.layout.BoxScope.(androidx.compose.foundation.lazy.grid.LazyGridState) -> Unit)? = null,
    gridOverlay: (@Composable androidx.compose.foundation.layout.BoxScope.(androidx.compose.foundation.lazy.grid.LazyGridState) -> Unit)? = null,
    scrollerOnRight: Boolean = false,
    scrollerEnabled: Boolean = true,
    scrollerSmooth: Boolean = true,
    isSecureFolder: Boolean = false,
    onDecryptFolderPermanently: (() -> Unit)? = null,
    forceScrollToTopOnNewContent: Boolean = false,
    /** Sort key shown in the scroller bubble; null = no bubble (random or custom order, search). */
    scrollLabelField: SortField? = null,
) {
    val activeDownloads = LocalActiveDownloads.current
    val safeMedia = remember(media) { media.distinctBy { it.id } }
    val scrollDateFormat = remember {
        val locale = java.util.Locale.getDefault()
        java.text.SimpleDateFormat(
            android.text.format.DateFormat.getBestDateTimePattern(locale, "ddMMyyyyHHmm"),
            locale,
        )
    }
    // Live selection during marquee stays local so we do not thrash ViewModel/uiState every move.
    var marqueeActive by remember { mutableStateOf(false) }
    var liveSelectedIds by remember { mutableStateOf(selectedIds) }
    LaunchedEffect(selectedIds) {
        if (!marqueeActive) liveSelectedIds = selectedIds
    }
    val selectedIdSet = remember(liveSelectedIds) { liveSelectedIds.toSet() }
    if (safeMedia.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            if (isSecureFolder && onDecryptFolderPermanently != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LockOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.secure_folder_unlocked_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = onDecryptFolderPermanently,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.action_decrypt_permanently),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                if (isLoading) {
                    LoadingIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val itemBounds = remember { mutableMapOf<Long, Rect>() }
    val mediaIds = remember(safeMedia) { safeMedia.map { it.id }.toSet() }
    val orderedIds = remember(safeMedia) { safeMedia.map { it.id } }
    val orderIndexById = remember(orderedIds) {
        orderedIds.withIndex().associate { it.value to (it.index + 1) }
    }
    val haptic = LocalHapticFeedback.current
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragFromIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragGrabLocal by remember { mutableStateOf(Offset.Zero) }
    var dragFingerWindow by remember { mutableStateOf(Offset.Zero) }
    var lastOrderSwapUptime by remember { mutableLongStateOf(0L) }
    var gridHostCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    SideEffect { itemBounds.keys.retainAll(mediaIds) }
    val start = rememberUpdatedState(onTwoFingerStart)
    val drag = rememberUpdatedState(onTwoFingerDrag)
    val end = rememberUpdatedState(onTwoFingerEnd)
    val selectedState = rememberUpdatedState(selectedIds)
    val mediaIdsState = rememberUpdatedState(mediaIds)
    val playVideo = rememberUpdatedState(onPlayVideo)
    val openItem = rememberUpdatedState(onOpen)
    val toggle = rememberUpdatedState(onToggleSelect)
    val orderedIdsState = rememberUpdatedState(orderedIds)
    val onContentOrderMoveState = rememberUpdatedState(onContentOrderMove)
    val dragFromIndexRef = remember { mutableIntStateOf(-1) }
    // Single tap on a video waits this long for a second one when double tap opens the in-app player.
    val tapScope = rememberCoroutineScope()
    var pendingVideoTap by remember { mutableStateOf<Job?>(null) }
    var pendingVideoTapId by remember { mutableLongStateOf(0L) }
    LaunchedEffect(videoDoubleClickToGallery, selectedIdSet.isEmpty()) {
        if (!videoDoubleClickToGallery || selectedIdSet.isNotEmpty()) pendingVideoTap?.cancel()
    }
    // Two fingers down on a pane holding the selection: the grid does not scroll under them.
    var twoFingerDragActive by remember { mutableStateOf(false) }
    val defaultGridState = androidx.compose.runtime.saveable.rememberSaveable(
        albumId.toString(),
        saver = androidx.compose.foundation.lazy.grid.LazyGridState.Saver,
    ) {
        androidx.compose.foundation.lazy.grid.LazyGridState(
            firstVisibleItemIndex = initialScrollPos?.index ?: 0,
            firstVisibleItemScrollOffset = initialScrollPos?.offset ?: 0,
        )
    }
    val gridState = gridState ?: defaultGridState

    var initialScrollRestored by remember(albumId) {
        mutableStateOf(gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0)
    }
    LaunchedEffect(albumId, initialScrollPos, safeMedia.isNotEmpty()) {
        if (!initialScrollRestored && initialScrollPos != null && safeMedia.isNotEmpty()) {
            if (initialScrollPos.index > 0 || initialScrollPos.offset > 0) {
                val targetIndex = initialScrollPos.index.coerceAtMost((safeMedia.size - 1).coerceAtLeast(0))
                gridState.scrollToItem(targetIndex, initialScrollPos.offset)
            }
            // Also for a folder saved at the top: otherwise every later saved position (written
            // while the user scrolls) restarted this and jerked the list back.
            initialScrollRestored = true
        }
    }

    var userScrolledAwayFromTop by remember(albumId) {
        mutableStateOf(
            FolderTopScrollReducer.initialAwayState(
                gridIndex = gridState.firstVisibleItemIndex,
                gridOffset = gridState.firstVisibleItemScrollOffset,
                initialIndex = initialScrollPos?.index ?: 0,
                initialOffset = initialScrollPos?.offset ?: 0,
            )
        )
    }

    var isScrollerDragging by remember(albumId) { mutableStateOf(false) }

    LaunchedEffect(gridState, albumId, forceScrollToTopOnNewContent, marqueeActive, draggingId, isScrollerDragging) {
        snapshotFlow {
            val inProgress = gridState.isScrollInProgress || marqueeActive || (draggingId != null) || isScrollerDragging
            Triple(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
                inProgress,
            )
        }.collect { (index, offset, inProgress) ->
            val event = if (inProgress) {
                FolderTopScrollEvent.ScrollInProgress(index, offset)
            } else {
                FolderTopScrollEvent.ScrollIdle(index, offset)
            }
            val (nextState, action) = FolderTopScrollReducer.reduce(
                currentState = FolderTopScrollState(userScrolledAwayFromTop),
                event = event,
                forceScrollToTopOnNewContent = forceScrollToTopOnNewContent,
            )
            userScrolledAwayFromTop = nextState.userScrolledAwayFromTop
            if (action is FolderTopScrollAction.ScrollToTop && !marqueeActive && draggingId == null && !isScrollerDragging) {
                gridState.scrollToItem(0, 0)
            }
        }
    }

    var previousFirstMediaId by remember(albumId) { mutableStateOf<Long?>(null) }
    var previousMediaIdsSet by remember(albumId) { mutableStateOf<Set<Long>?>(null) }

    LaunchedEffect(albumId, safeMedia, forceScrollToTopOnNewContent) {
        val currentFirstId = safeMedia.firstOrNull()?.id
        val currentIds = safeMedia.map { it.id }.toSet()
        val prevSet = previousMediaIdsSet
        val prevFirstId = previousFirstMediaId

        val hasNewFiles = prevSet != null && (currentIds.any { it !in prevSet } || (prevFirstId != null && currentFirstId != prevFirstId))
        previousFirstMediaId = currentFirstId
        previousMediaIdsSet = currentIds

        if (hasNewFiles) {
            val (nextState, action) = FolderTopScrollReducer.reduce(
                currentState = FolderTopScrollState(userScrolledAwayFromTop),
                event = FolderTopScrollEvent.NewContentArrived(hasNewFiles = true),
                forceScrollToTopOnNewContent = forceScrollToTopOnNewContent,
            )
            userScrolledAwayFromTop = nextState.userScrolledAwayFromTop
            if (action is FolderTopScrollAction.ScrollToTop) {
                gridState.scrollToItem(0, 0)
            }
        }
    }

    if (onScrollPosChanged != null && albumId != AlbumIds.NONE) {
        val scrollCallback by rememberUpdatedState(onScrollPosChanged)
        LaunchedEffect(gridState, albumId) {
            snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    scrollCallback(index, offset)
                }
        }
    }

    // While reordering: edge auto-scroll + keep gap under finger even when held still.
    LaunchedEffect(draggingId) {
        if (draggingId == null) return@LaunchedEffect
        val edgePx = with(density) { 72.dp.toPx() }
        val maxSpeed = with(density) { 34.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val id = draggingId ?: break
            val host = gridHostCoords
            if (host != null && host.isAttached) {
                val local = host.windowToLocal(dragFingerWindow)
                val h = host.size.height.toFloat()
                val scrollDelta = when {
                    local.y < edgePx -> {
                        val t = ((edgePx - local.y) / edgePx).coerceIn(0f, 1f)
                        -maxSpeed * t * t
                    }
                    local.y > h - edgePx -> {
                        val t = ((local.y - (h - edgePx)) / edgePx).coerceIn(0f, 1f)
                        maxSpeed * t * t
                    }
                    else -> 0f
                }
                if (scrollDelta != 0f) {
                    if (scrollDelta > 0f) {
                        userScrolledAwayFromTop = true
                    }
                    gridState.dispatchRawDelta(scrollDelta)
                }
            }
            // Move gap toward finger — at most one row (±columns) per frame so a
            // bad hit-test cannot throw the item across the whole folder.
            val from = dragFromIndexRef.intValue
            if (from < 0) continue
            val target = itemBounds.entries
                .filter { (bid, _) -> bid != id }
                .minByOrNull { (_, rect) ->
                    val cx = (rect.left + rect.right) / 2f
                    val cy = (rect.top + rect.bottom) / 2f
                    val ddx = dragFingerWindow.x - cx
                    val ddy = dragFingerWindow.y - cy
                    ddx * ddx + ddy * ddy
                } ?: continue
            val toRect = target.value
            val toCx = (toRect.left + toRect.right) / 2f
            val toCy = (toRect.top + toRect.bottom) / 2f
            val ddx = dragFingerWindow.x - toCx
            val ddy = dragFingerWindow.y - toCy
            val distSq = ddx * ddx + ddy * ddy
            val reach = maxOf(toRect.width, toRect.height) * 0.72f
            if (distSq > reach * reach) continue
            val toIndex = orderedIdsState.value.indexOf(target.key)
            if (toIndex < 0 || toIndex == from) continue
            val cols = columns.coerceAtLeast(1)
            val indexDelta = kotlin.math.abs(toIndex - from)
            val adjacent = indexDelta == 1 || indexDelta == cols
            val slot = itemBounds[id]
            if (adjacent) {
                val fromRect = slot ?: continue
                val fromCx = (fromRect.left + fromRect.right) / 2f
                val fromCy = (fromRect.top + fromRect.bottom) / 2f
                val gate = 0.22f
                val gateX = fromCx + (toCx - fromCx) * gate
                val gateY = fromCy + (toCy - fromCy) * gate
                val horizontal = kotlin.math.abs(toCx - fromCx) >= kotlin.math.abs(toCy - fromCy)
                val crossed = if (horizontal) {
                    if (toCx >= fromCx) dragFingerWindow.x >= gateX else dragFingerWindow.x <= gateX
                } else {
                    if (toCy >= fromCy) dragFingerWindow.y >= gateY else dragFingerWindow.y <= gateY
                }
                if (!crossed) continue
            } else {
                val tight = minOf(toRect.width, toRect.height) * 0.45f
                if (!toRect.contains(dragFingerWindow) && distSq > tight * tight) continue
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastOrderSwapUptime < 32L) continue
            val step = indexDelta.coerceAtMost(cols)
            val dir = if (toIndex > from) 1 else -1
            val stepTo = from + dir * step
            if (stepTo == from) continue
            onContentOrderMoveState.value(from, stepTo)
            dragFromIndexRef.intValue = stepTo
            dragFromIndex = stepTo
            lastOrderSwapUptime = now
        }
    }
    MarqueeBox(
        enabled = marqueeEnabled && !contentOrderMode,
        // Paused while the fast-scroll thumb is held: a long hold on it is not a long press on the grid.
        suspended = isScrollerDragging,
        itemBoundsInWindow = itemBounds,
        selectedIds = liveSelectedIds,
        orderedIds = orderedIds,
        linear = linearMarquee,
        onSelectIds = { ids ->
            val list = ids.toList()
            liveSelectedIds = list
            onTransientCountChange(list.size)
        },
        onSelectingChange = { active ->
            if (active) {
                liveSelectedIds = selectedIds
                marqueeActive = true
                onTransientCountChange(selectedIds.size)
            } else {
                marqueeActive = false
                onTransientCountChange(null)
                onMarqueeSelect(liveSelectedIds)
            }
        },
        onAutoScroll = { delta ->
            if (delta > 0f) {
                userScrolledAwayFromTop = true
            }
            gridState.dispatchRawDelta(delta)
        },
        modifier = modifier
            .onGloballyPositioned { coords = it }
            .then(
                if (twoFingerDragEnabled) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            // Initial pass: the grid below sees these touches only after they are consumed here.
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // Drag only from the pane that holds part of the selection;
                            // two fingers elsewhere must stay a plain scroll.
                            val paneIds = mediaIdsState.value
                            if (selectedState.value.none { it in paneIds }) return@awaitEachGesture
                            var started = false
                            var anchor: Offset? = null
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        if (!twoFingerDragActive) {
                                            twoFingerDragActive = true
                                            tapScope.launch { gridState.stopScroll() }
                                        }
                                        pressed.forEach { it.consume() }
                                        if (!started) {
                                            // A still two-finger touch must not move files: wait for real movement.
                                            val centroid = Offset(
                                                pressed.map { it.position.x }.average().toFloat(),
                                                pressed.map { it.position.y }.average().toFloat(),
                                            )
                                            val origin = anchor
                                            if (origin == null) {
                                                anchor = centroid
                                                continue
                                            }
                                            if ((centroid - origin).getDistance() <= viewConfiguration.touchSlop) continue
                                        }
                                        val bottomFinger = pressed.maxByOrNull { it.position.y } ?: pressed.first()
                                        val window = coords?.localToWindow(bottomFinger.position) ?: bottomFinger.position
                                        if (!started) {
                                            started = true
                                            start.value(window)
                                        } else {
                                            drag.value(window)
                                        }
                                    } else if (!started && pressed.size == 1) {
                                        anchor = null
                                    } else if (started) {
                                        end.value()
                                        break
                                    } else if (pressed.isEmpty()) {
                                        break
                                    }
                                }
                            } finally {
                                twoFingerDragActive = false
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (isSecureFolder && onDecryptFolderPermanently != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LockOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.secure_folder_unlocked_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = onDecryptFolderPermanently,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.action_decrypt_permanently),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { gridHostCoords = it }
                    .then(
                        if (!contentOrderMode) {
                            Modifier
                        } else {
                            // Gesture on the grid host — NOT on Lazy items. Item-level
                            // pointerInput is cancelled when a fast reorder/scroll disposes
                            // the item ("throws" / auto-drops the drag).
                            Modifier.pointerInput(columns) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val pointerId = down.id
                                    var origin = down.position
                                    val slop = viewConfiguration.touchSlop
                                    val timeout = viewConfiguration.longPressTimeoutMillis
                                    val aborted = withTimeoutOrNull(timeout) {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == pointerId }
                                                ?: return@withTimeoutOrNull true
                                            if (!change.pressed) return@withTimeoutOrNull true
                                            if ((change.position - origin).getDistance() > slop) {
                                                return@withTimeoutOrNull true
                                            }
                                        }
                                    }
                                    if (aborted == true) return@awaitEachGesture
                                    val host = gridHostCoords ?: return@awaitEachGesture
                                    val originWindow = host.localToWindow(origin)
                                    val hit = itemBounds.entries.firstOrNull { (_, rect) ->
                                        rect.contains(originWindow)
                                    } ?: itemBounds.entries.minByOrNull { (_, rect) ->
                                        val cx = (rect.left + rect.right) / 2f
                                        val cy = (rect.top + rect.bottom) / 2f
                                        val dx = originWindow.x - cx
                                        val dy = originWindow.y - cy
                                        dx * dx + dy * dy
                                    }
                                    if (hit == null) return@awaitEachGesture
                                    val reach = maxOf(hit.value.width, hit.value.height) * 0.75f
                                    val hcx = (hit.value.left + hit.value.right) / 2f
                                    val hcy = (hit.value.top + hit.value.bottom) / 2f
                                    val hdx = originWindow.x - hcx
                                    val hdy = originWindow.y - hcy
                                    if (hdx * hdx + hdy * hdy > reach * reach) return@awaitEachGesture
                                    val idx = orderedIdsState.value.indexOf(hit.key)
                                    if (idx < 0) return@awaitEachGesture
                                    currentEvent.changes.forEach { it.consume() }
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragFromIndexRef.intValue = idx
                                    dragFromIndex = idx
                                    draggingId = hit.key
                                    dragGrabLocal = Offset(
                                        originWindow.x - hit.value.left,
                                        originWindow.y - hit.value.top,
                                    )
                                    dragFingerWindow = originWindow
                                    dragOffset = Offset.Zero
                                    lastOrderSwapUptime = 0L
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == pointerId }
                                                ?: break
                                            change.consume()
                                            if (!change.pressed) break
                                            val hostNow = gridHostCoords
                                            dragFingerWindow = if (hostNow != null && hostNow.isAttached) {
                                                hostNow.localToWindow(change.position)
                                            } else {
                                                change.position
                                            }
                                        }
                                    } finally {
                                        draggingId = null
                                        dragFromIndexRef.intValue = -1
                                        dragFromIndex = -1
                                        dragOffset = Offset.Zero
                                    }
                                }
                            }
                        },
                    ),
            ) {
                gridUnderlay?.invoke(this, gridState)
                LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceAtLeast(1)),
            state = gridState,
            userScrollEnabled = !marqueeActive && draggingId == null && !twoFingerDragActive,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (dimmed) {
                        drawRect(Color.Black.copy(alpha = 0.45f))
                    }
                },
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp + bottomContentPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(safeMedia, key = { it.id }) { item ->
                // A tile scrolled away keeps its last on-screen rect otherwise, and drag selection
                // or reordering could hit a file the user can't see.
                DisposableEffect(item.id) {
                    onDispose { itemBounds.remove(item.id) }
                }
                val selected = item.id in selectedIdSet
                val orderNumber = if (contentOrderMode) orderIndexById[item.id] else null
                val isDraggingItem = draggingId == item.id
                val dimOthers = draggingId != null && !isDraggingItem
                // Only this tile follows its own download: the whole map changes every 250 ms.
                val download by remember(item.absolutePath, activeDownloads) {
                    androidx.compose.runtime.derivedStateOf { DownloadTracker.lookup(activeDownloads(), item.absolutePath) }
                }
                val downloading = download != null
                // The edge between the panes moves: the tile follows it as it is, no own glide.
                val paneResizing = LocalPaneResizing.current.value
                val selectScale by animateFloatAsState(
                    targetValue = if (selected && !contentOrderMode) 0.92f else 1f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                    ),
                    label = "mediaTileSelectScale",
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .then(
                            when {
                                marqueeActive || isDraggingItem || paneResizing -> Modifier
                                LocalLightMode.current -> Modifier
                                else -> Modifier.animateItem(
                                    placementSpec = spring(
                                        stiffness = 180f,
                                        dampingRatio = 0.92f,
                                    ),
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                )
                            }
                        )
                        .onGloballyPositioned { itemBounds[item.id] = it.boundsInWindow() }
                        .graphicsLayer {
                            scaleX = selectScale
                            scaleY = selectScale
                            translationX = 0f
                            translationY = 0f
                            shadowElevation = 0f
                            alpha = when {
                                isDraggingItem -> 0f
                                dimOthers -> 0.82f
                                else -> 1f
                            }
                        }
                        .then(
                            if (!contentOrderMode) {
                                Modifier.combinedClickable(
                                    onClick = {
                                        if (selectedIdSet.isNotEmpty()) {
                                            toggle.value(item.id)
                                        } else if (item.isVideo && videoDoubleClickToGallery) {
                                            val pending = pendingVideoTap
                                            if (pending?.isActive == true && pendingVideoTapId == item.id) {
                                                pending.cancel()
                                                openItem.value(item)
                                            } else {
                                                pending?.cancel()
                                                pendingVideoTapId = item.id
                                                pendingVideoTap = tapScope.launch {
                                                    delay(android.view.ViewConfiguration.getDoubleTapTimeout().toLong())
                                                    playVideo.value(item)
                                                }
                                            }
                                        } else if (item.isVideo) {
                                            playVideo.value(item)
                                        } else {
                                            openItem.value(item)
                                        }
                                    },
                                    // Long-press belongs to MarqueeBox when enabled.
                                    onLongClick = if (marqueeEnabled) {
                                        null
                                    } else {
                                        {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            toggle.value(item.id)
                                        }
                                    },
                                )
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Box {
                        // Where a quick edit is making a video: the forge over the tile, no play icon or
                        // time under it; a copy not made yet shows its original's picture.
                        val forge = LocalQuickEditTile.current?.takeIf { it.matches(item) }
                        SquareThumbnail(
                            media = if (forge?.target?.outputPath != null) forge.target.source else item,
                            showPlayIcon = !contentOrderMode && !downloading && forge == null,
                            thumbGeneration = LocalFolderThumbEpoch.current,
                            skipLoad = downloading,
                            showDuration = forge == null,
                        )
                        DownloadTileOverlay(
                            download = download,
                            modifier = Modifier.matchParentSize(),
                        )
                        forge?.let { tile -> QuickEditForgeOverlay(tile, Modifier.matchParentSize()) }
                        if (contentOrderMode && orderNumber != null) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Black.copy(alpha = if (isDraggingItem) 0.40f else 0.28f)),
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = orderNumber.toString(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        if (item.bucketId == dev.ander.alexet.data.AlbumIds.TRASH) {
                            val daysLeft = kotlin.math.max(0, 7 - ((System.currentTimeMillis() - item.dateAddedMs) / (24 * 60 * 60 * 1000L)).toInt())
                            val badgeText = when (daysLeft) {
                                0 -> stringResource(R.string.days_remaining_today)
                                1 -> stringResource(R.string.days_remaining_one)
                                else -> stringResource(R.string.days_remaining, daysLeft)
                            }
                            androidx.compose.material3.Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.88f),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp),
                            ) {
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        } else if (item.isHidden || item.id in hiddenIds) {
                            dev.ander.alexet.ui.components.HiddenBadge(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp),
                            )
                        }
                        dev.ander.alexet.ui.components.DeferredAnimatedVisibility(
                            visible = selected && !contentOrderMode,
                            enter = fadeIn(tween(150)),
                            exit = fadeOut(tween(120)),
                            modifier = Modifier.matchParentSize(),
                        ) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(Color.Black.copy(alpha = 0.38f)),
                            )
                        }
                        dev.ander.alexet.ui.components.DeferredAnimatedVisibility(
                            visible = selected && !contentOrderMode,
                            enter = scaleIn(
                                animationSpec = spring(
                                    stiffness = Spring.StiffnessMediumLow,
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                ),
                                initialScale = 0.4f,
                            ) + fadeIn(tween(150)),
                            exit = scaleOut(
                                animationSpec = tween(100),
                                targetScale = 0.4f,
                            ) + fadeOut(tween(100)),
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(6.dp)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    if (showFileNames) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, start = 2.dp, end = 2.dp),
                        )
                    }
                }
            }
        }
        gridOverlay?.invoke(this, gridState)
        val floatingId = draggingId
        if (contentOrderMode && floatingId != null) {
            val draggedMedia = safeMedia.find { it.id == floatingId }
            val host = gridHostCoords
            val slotBound = itemBounds[floatingId]
            if (draggedMedia != null && host != null && slotBound != null && host.isAttached) {
                val orderNum = orderIndexById[floatingId]
                Box(
                    modifier = Modifier
                        .zIndex(40f)
                        // The finger is read here, when placing: read in composition, every move
                        // of it rebuilt the whole grid.
                        .offset {
                            val topLeft = if (host.isAttached) {
                                host.windowToLocal(Offset(dragFingerWindow.x - dragGrabLocal.x, dragFingerWindow.y - dragGrabLocal.y))
                            } else {
                                Offset.Zero
                            }
                            IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt())
                        }
                        .width(with(density) { slotBound.width.toDp() })
                        .graphicsLayer {
                            scaleX = 1.03f
                            scaleY = 1.03f
                            shadowElevation = 10.dp.toPx()
                            alpha = 0.98f
                        },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            SquareThumbnail(
                                media = draggedMedia,
                                showPlayIcon = false,
                                thumbGeneration = LocalFolderThumbEpoch.current,
                            )
                            if (orderNum != null) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(Color.Black.copy(alpha = 0.40f)),
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = orderNum.toString(),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                        if (showFileNames) {
                            Text(
                                text = draggedMedia.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, start = 2.dp, end = 2.dp),
                            )
                        }
                    }
                }
            }
        }
                if (scrollerEnabled && safeMedia.isNotEmpty() && !contentOrderMode) {
            FolderScroller(
                gridState = gridState,
                itemCount = safeMedia.size,
                columns = columns,
                isOnRight = scrollerOnRight,
                smooth = scrollerSmooth,
                bottomPadding = bottomContentPadding,
                onDraggingChange = { isScrollerDragging = it },
                labelFor = scrollLabelField?.let { field ->
                    { index -> safeMedia.getOrNull(index)?.let { ScrollLabels.label(it, field, scrollDateFormat) } }
                },
                modifier = Modifier
                    .align(if (scrollerOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                    .zIndex(2f),
            )
        }
    }
}
}
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadTileOverlay(
    download: ActiveDownload?,
    modifier: Modifier = Modifier,
) {
    val expressiveSpring = spring<Float>(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = Spring.DampingRatioMediumBouncy,
    )
    // Most tiles never download: no Transition for them (a fling created one per tile).
    dev.ander.alexet.ui.components.DeferredAnimatedVisibility(
        visible = download != null,
        enter = fadeIn(tween(180)) + scaleIn(
            animationSpec = expressiveSpring,
            initialScale = 0.92f,
        ),
        exit = fadeOut(tween(140)) + scaleOut(
            animationSpec = tween(140),
            targetScale = 0.92f,
        ),
        modifier = modifier,
    ) {
        val progress = download?.totalBytes?.takeIf { it > 0L }?.let { total ->
            (download.bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f)),
            )
            dev.ander.alexet.ui.components.DownloadBadge(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
            if (progress != null) {
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                )
            } else {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                )
            }
        }
    }
}

/**
 * Downloads in progress, read by each tile for itself (GalleryApp provides them): passed down as a
 * map, each change (every 250 ms) redrew every tile.
 */
val LocalActiveDownloads = androidx.compose.runtime.staticCompositionLocalOf<() -> Map<String, ActiveDownload>> { { emptyMap() } }
