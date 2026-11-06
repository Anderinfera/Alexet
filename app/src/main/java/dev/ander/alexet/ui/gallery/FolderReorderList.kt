package dev.ander.alexet.ui.gallery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.isActive
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ander.alexet.data.Album
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
@Suppress("UNUSED_PARAMETER")
fun FolderReorderList(
    albums: List<Album>,
    columns: Int,
    visibleRows: Int,
    /** The whole order as shown after a drag: indexes alone broke when the list changed meanwhile. */
    onMove: (orderedIds: List<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val albumsRef = rememberUpdatedState(albums)
    val onMoveRef = rememberUpdatedState(onMove)

    val items = remember { mutableStateListOf<Album>().also { it.addAll(albums) } }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var fromIndex by remember { mutableIntStateOf(-1) }
    var hoverIndex by remember { mutableIntStateOf(-1) }
    var pointerWindow by remember { mutableStateOf(Offset.Zero) }
    var grabInItem by remember { mutableStateOf(Offset.Zero) }
    var viewportOrigin by remember { mutableStateOf(Offset.Zero) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    val incomingIds = albums.map { it.id }

    LaunchedEffect(incomingIds) {
        if (draggingId != null) return@LaunchedEffect
        if (items.map { it.id } == incomingIds) return@LaunchedEffect
        items.clear()
        items.addAll(albumsRef.value)
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .onGloballyPositioned { coords ->
                viewportOrigin = coords.positionInWindow()
                viewportHeight = coords.size.height.toFloat()
            },
    ) {
        val cols = columns.coerceIn(2, 6)
        val gap = 1.dp
        val gapPx = with(density) { gap.toPx() }
        val itemW = (constraints.maxWidth - (cols - 1) * gapPx) / cols
        val itemH = itemW
        val itemHeight = with(density) { itemH.toDp() }
        val strideX = itemW + gapPx
        val strideY = itemH + gapPx
        val rowCount = ((items.size + cols - 1) / cols).coerceAtLeast(1)
        val contentHeight = with(density) {
            (rowCount * strideY - gapPx + with(density) { 112.dp.toPx() }).toDp()
        }

        // Only the rows on screen and a margin are composed: with hundreds of folders every cover
        // was loaded at once. Recomposed when the range changes, not on every scrolled pixel.
        val shownRows by remember(strideY) {
            derivedStateOf { reorderShownRows(scroll.value, viewportHeight, strideY, REORDER_ROW_MARGIN) }
        }

        fun slotIndex(window: Offset): Int {
            if (items.isEmpty()) return 0
            val localX = window.x - viewportOrigin.x
            val localY = window.y - viewportOrigin.y + scroll.value
            val col = (localX / strideX).toInt().coerceIn(0, cols - 1)
            val row = (localY / strideY).toInt().coerceAtLeast(0)
            return (row * cols + col).coerceIn(0, items.lastIndex)
        }

        fun commitDrag() {
            val from = fromIndex
            val to = hoverIndex
            if (from != to && from in items.indices && to in items.indices) {
                val album = items.removeAt(from)
                items.add(to, album)
                onMoveRef.value(items.map { it.id })
            }
            draggingId = null
            fromIndex = -1
            hoverIndex = -1
            grabInItem = Offset.Zero
        }

        LaunchedEffect(draggingId) {
            if (draggingId == null) return@LaunchedEffect
            val edge = 120f
            var lastFrameNanos = 0L
            while (isActive) {
                withFrameNanos { frameTimeNanos ->
                    val dtMillis = if (lastFrameNanos > 0L) {
                        ((frameTimeNanos - lastFrameNanos) / 1_000_000f).coerceIn(4f, 50f)
                    } else 16.6f
                    lastFrameNanos = frameTimeNanos

                    val localY = pointerWindow.y - viewportOrigin.y
                    val factor = when {
                        localY < edge -> -((edge - localY) / edge).coerceIn(0f, 1f)
                        localY > viewportHeight - edge -> ((localY - (viewportHeight - edge)) / edge).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (factor != 0f) {
                        val speedPerMs = 28f / 16.6f
                        val delta = factor * speedPerMs * dtMillis
                        launch {
                            scroll.scrollBy(delta)
                            hoverIndex = slotIndex(pointerWindow)
                        }
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll, enabled = draggingId == null),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(contentHeight),
            ) {
                if (draggingId != null && hoverIndex >= 0) {
                    val holeCol = hoverIndex % cols
                    val holeRow = hoverIndex / cols
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (holeCol * strideX).roundToInt(),
                                    (holeRow * strideY).roundToInt(),
                                )
                            }
                            .size(with(density) { itemW.toDp() }, itemHeight)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    )
                }
                items.forEachIndexed { index, album ->
                    key(album.id) {
                        val dragging = album.id == draggingId
                        val visual = visualIndex(
                            index = index,
                            from = fromIndex,
                            hover = hoverIndex,
                            dragging = draggingId != null,
                        )
                        val col = visual % cols
                        val row = visual / cols
                        val restX = col * strideX
                        val restY = row * strideY
                        if (dragging || row in shownRows) FolderReorderSlot(
                            album = album,
                            dragging = dragging,
                            restX = restX,
                            restY = restY,
                            widthPx = itemW,
                            height = itemHeight,
                            // A provider: passed as a value, every finger move recomposed every slot.
                            pointerWindow = { pointerWindow },
                            grabInItem = grabInItem,
                            viewportOrigin = viewportOrigin,
                            scrollYProvider = { scroll.value.toFloat() },
                            onDragStart = { window, grab ->
                                val startIndex = items.indexOfFirst { it.id == album.id }
                                draggingId = album.id
                                fromIndex = startIndex
                                hoverIndex = startIndex
                                pointerWindow = window
                                grabInItem = grab
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { delta ->
                                pointerWindow += delta
                                hoverIndex = slotIndex(pointerWindow)
                            },
                            onDragEnd = { commitDrag() },
                        )
                    }
                }
            }
        }
    }
}

/** Rows (of [strideY] px) to compose for a list scrolled by [scrollY] in a [viewportHeight] px view. */
internal fun reorderShownRows(scrollY: Int, viewportHeight: Float, strideY: Float, margin: Int): IntRange {
    if (strideY <= 0f) return 0..margin
    val first = (scrollY / strideY).toInt() - margin
    val last = ((scrollY + viewportHeight) / strideY).toInt() + margin
    return first.coerceAtLeast(0)..last.coerceAtLeast(0)
}

private const val REORDER_ROW_MARGIN = 2

private fun visualIndex(index: Int, from: Int, hover: Int, dragging: Boolean): Int {
    if (!dragging || from < 0 || hover < 0 || index == from) return if (dragging) hover else index
    return if (from < hover) {
        if (index in (from + 1)..hover) index - 1 else index
    } else {
        if (index in hover until from) index + 1 else index
    }
}

@Composable
private fun FolderReorderSlot(
    album: Album,
    dragging: Boolean,
    restX: Float,
    restY: Float,
    widthPx: Float,
    height: Dp,
    pointerWindow: () -> Offset,
    grabInItem: Offset,
    viewportOrigin: Offset,
    scrollYProvider: () -> Float,
    onDragStart: (window: Offset, grabInItem: Offset) -> Unit,
    onDrag: (delta: Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val startRef = rememberUpdatedState(onDragStart)
    val dragRef = rememberUpdatedState(onDrag)
    val endRef = rememberUpdatedState(onDragEnd)
    val x = remember { Animatable(restX) }
    val y = remember { Animatable(restY) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val spec = spring<Float>(stiffness = Spring.StiffnessMediumLow)

    LaunchedEffect(restX, restY, dragging) {
        if (dragging) return@LaunchedEffect
        launch { x.animateTo(restX, spec) }
        launch { y.animateTo(restY, spec) }
    }
    // Only the dragged slot follows the finger; it keeps x/y on it so it settles from there.
    LaunchedEffect(dragging, grabInItem, viewportOrigin) {
        if (!dragging) return@LaunchedEffect
        snapshotFlow { pointerWindow() }.collect { finger ->
            x.snapTo(finger.x - grabInItem.x - viewportOrigin.x)
            y.snapTo(finger.y - grabInItem.y - viewportOrigin.y + scrollYProvider())
        }
    }

    Box(
        modifier = Modifier
            // Read when placing, not in composition: no recomposition per finger move or frame.
            .offset {
                if (dragging) {
                    val finger = pointerWindow()
                    IntOffset(
                        (finger.x - grabInItem.x - viewportOrigin.x).roundToInt(),
                        (finger.y - grabInItem.y - viewportOrigin.y + scrollYProvider()).roundToInt(),
                    )
                } else {
                    IntOffset(x.value.roundToInt(), y.value.roundToInt())
                }
            }
            .size(with(LocalDensity.current) { widthPx.toDp() }, height)
            .zIndex(if (dragging) 8f else 0f)
            .graphicsLayer {
                if (dragging) {
                    scaleX = 1.06f
                    scaleY = 1.06f
                    shadowElevation = 16.dp.toPx()
                }
            }
            .onGloballyPositioned { coords = it }
            .pointerInput(album.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val window = coords?.localToWindow(offset) ?: offset
                        startRef.value(window, offset)
                    },
                    onDragEnd = { endRef.value() },
                    onDragCancel = { endRef.value() },
                    onDrag = { change, amount ->
                        change.consume()
                        dragRef.value(amount)
                    },
                )
            },
    ) {
        if (dragging) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(8.dp),
            ) {
                FolderCover(album)
            }
        } else {
            FolderCover(album)
        }
    }
}
