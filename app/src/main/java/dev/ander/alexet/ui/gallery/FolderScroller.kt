package dev.ander.alexet.ui.gallery

import dev.ander.alexet.ui.components.animateDpAsState
import dev.ander.alexet.ui.components.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun FolderScroller(
    gridState: LazyGridState,
    itemCount: Int,
    columns: Int,
    isOnRight: Boolean,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    smooth: Boolean = true,
    onDraggingChange: (Boolean) -> Unit = {},
    /** Bubble text for the file at a grid index (the sort key); null = no bubble. */
    labelFor: ((index: Int) -> String?)? = null,
) {
    if (itemCount <= 0) return

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val onDraggingChangeState = rememberUpdatedState(onDraggingChange)

    val safeColumns = max(1, columns)
    val totalRows = (itemCount + safeColumns - 1) / safeColumns
    if (totalRows <= 1) return

    var isDragging by remember { mutableStateOf(false) }
    var isScrollingRecently by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    // Detect grid scrolling to illuminate handle and reset 5s timer
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                isScrollingRecently = true
            }
        }
    }

    // 5-second timer to return to semi-transparent state (0.35f) after inactivity
    LaunchedEffect(gridState.isScrollInProgress, isDragging) {
        if (!gridState.isScrollInProgress && !isDragging && isScrollingRecently) {
            delay(5000L)
            isScrollingRecently = false
        }
    }

    val isActive = isDragging || isScrollingRecently || gridState.isScrollInProgress
    val targetAlpha = if (isActive) 1.0f else 0.35f
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 300),
        label = "scrollerAlpha",
    )

    // Smooth transition between idle and active dragging state
    val activeFraction by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "scrollerActiveFraction",
    )

    val baseHandleHeightPx = with(density) { 44.dp.toPx() }

    // Bubble: only while the scroller itself is dragged (not on a finger scroll of the grid).
    val labelVisible = labelFor != null && isDragging
    val labelAlpha by animateFloatAsState(
        targetValue = if (labelVisible) 1f else 0f,
        animationSpec = if (labelVisible) tween(durationMillis = 120) else tween(durationMillis = 250, delayMillis = 800),
        label = "scrollerLabelAlpha",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(if (isDragging) 48.dp else 36.dp)
            .padding(bottom = bottomPadding),
    ) {
        val trackHeightPx = constraints.maxHeight.toFloat()
        val maxOffsetPx = max(1f, trackHeightPx - baseHandleHeightPx)

        // The grid's scroll position is read where it is used (the thumb's offset, the bubble's
        // layout), not here: read in composition it rebuilt the scroller on every scroll frame.
        val fallbackRowPx = with(density) { 120.dp.toPx() }

        // Calculate dynamic row height in pixels for continuous smooth progress
        fun rowHeightPx(): Float {
            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            val firstVisible = visibleItems.firstOrNull()
            val estimatedRowHeight = if (visibleItems.size > safeColumns) {
                val row0Top = visibleItems.first().offset.y
                val nextRowTop = visibleItems.getOrNull(safeColumns)?.offset?.y
                if (nextRowTop != null && nextRowTop > row0Top) {
                    (nextRowTop - row0Top).toFloat()
                } else {
                    (firstVisible?.size?.height?.toFloat() ?: fallbackRowPx)
                }
            } else {
                (firstVisible?.size?.height?.toFloat() ?: fallbackRowPx)
            }
            return if (estimatedRowHeight > 0f) estimatedRowHeight else fallbackRowPx
        }

        // The last screen of rows can never be the first visible one: progress runs up to the
        // first row of the last screen, so the thumb reaches the bottom at the end of the folder.
        fun maxScrollRow(rowHeight: Float = rowHeightPx()): Float =
            max(1f, totalRows - gridState.layoutInfo.viewportSize.height / rowHeight)

        fun naturalProgress(): Float {
            val safeRowHeightPx = rowHeightPx()
            // Natural progress is ALWAYS continuous (pixel-perfect thumb tracking)
            // The `smooth` setting only affects drag behavior (scrollToItem calls)
            val continuousRow = (gridState.firstVisibleItemIndex / safeColumns).toFloat() +
                (gridState.firstVisibleItemScrollOffset.toFloat() / safeRowHeightPx)
            return (continuousRow / maxScrollRow(safeRowHeightPx)).coerceIn(0f, 1f)
        }

        fun pillOffsetPx(): Int {
            val currentProgress = if (isDragging) dragProgress else naturalProgress()
            val centerOffsetPx = currentProgress * maxOffsetPx + baseHandleHeightPx / 2f
            return (centerOffsetPx - baseHandleHeightPx / 2f).roundToInt()
                .coerceIn(0, max(0, (trackHeightPx - baseHandleHeightPx).roundToInt()))
        }

        var lastHapticIndex by remember { mutableIntStateOf(-1) }

        if (labelFor != null && labelAlpha > 0.01f) {
            // File at the thumb: the drag target row (first visible file while fading out).
            val labelIndex = if (isDragging) {
                ((dragProgress * maxScrollRow()).roundToInt() * safeColumns).coerceIn(0, itemCount - 1)
            } else {
                gridState.firstVisibleItemIndex.coerceIn(0, itemCount - 1)
            }
            val text = labelFor(labelIndex)
            if (text != null) {
                val gapPx = with(density) { 12.dp.roundToPx() }
                val pillWidthPx = with(density) { 12.dp.roundToPx() }
                val maxLabelWidthPx = with(density) { 260.dp.roundToPx() }
                val trackHeight = trackHeightPx.roundToInt()
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(if (isOnRight) Alignment.TopEnd else Alignment.TopStart)
                        // Zero-size anchor at the pill side; the bubble is drawn beside it, over the grid.
                        .layout { measurable, _ ->
                            val placeable = measurable.measure(Constraints(maxWidth = maxLabelWidthPx))
                            layout(0, 0) {
                                val pillCenterY = pillOffsetPx() + (baseHandleHeightPx / 2f).roundToInt()
                                val x = if (isOnRight) -(pillWidthPx + gapPx + placeable.width) else pillWidthPx + gapPx
                                val y = (pillCenterY - placeable.height / 2)
                                    .coerceIn(0, max(0, trackHeight - placeable.height))
                                placeable.place(x, y)
                            }
                        }
                        .alpha(labelAlpha)
                        .shadow(elevation = 4.dp, shape = RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(percent = 50))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }

        val pillAlignment = if (isOnRight) Alignment.TopEnd else Alignment.TopStart

        // Thumb: a single accent-coloured pill, no track. Thin and dim at rest, clearer while the grid
        // scrolls, wider and solid while dragged.
        val thumbWidth by animateDpAsState(
            targetValue = when {
                isDragging -> 15.dp
                isActive -> 10.5.dp
                else -> 7.5.dp
            },
            animationSpec = tween(durationMillis = 180),
            label = "scrollerThumbWidth",
        )
        val thumbAlpha = max(activeFraction, animatedAlpha * 0.8f)
        Box(
            modifier = Modifier
                .align(pillAlignment)
                .offset { IntOffset(x = 0, y = pillOffsetPx()) }
                .padding(horizontal = 2.dp)
                .width(thumbWidth)
                .height(44.dp)
                .alpha(thumbAlpha)
                .shadow(elevation = 3.dp * activeFraction, shape = RoundedCornerShape(percent = 50))
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primary),
        )

        // Touch handling: stable states to avoid restarting pointerInput on every scroll frame
        val maxOffsetPxState = rememberUpdatedState(maxOffsetPx)
        // Functions, not values: the scroll position is read when the gesture needs it.
        val safeRowHeightPxState = rememberUpdatedState { rowHeightPx() }
        val itemCountState = rememberUpdatedState(itemCount)
        val totalRowsState = rememberUpdatedState(totalRows)
        val smoothState = rememberUpdatedState(smooth)
        val columnsState = rememberUpdatedState(safeColumns)
        val maxScrollRowState = rememberUpdatedState { maxScrollRow() }
        val naturalProgressState = rememberUpdatedState { naturalProgress() }
        // The grid's state object can be replaced while this scroller stays (the folder's state is
        // dropped and recreated, e.g. after leaving the app). A gesture handler holding the old one
        // scrolled a grid that is no longer shown: the thumb moved, the files didn't, and on release
        // it jumped back.
        val gridStateRef = rememberUpdatedState(gridState)

        val thumbTouchHeight = 56.dp
        val touchSlackPx = with(density) { 6.dp.toPx() }.roundToInt()
        val touchHeightPx = with(density) { thumbTouchHeight.toPx() }
        fun touchBoxOffsetPx(): Int = (pillOffsetPx() - touchSlackPx)
            .coerceIn(0, max(0, (trackHeightPx - touchHeightPx).roundToInt()))

        // Touch target: when idle, sits right over the thumb (36.dp x 56.dp) so touches elsewhere pass through;
        // when dragging, expands to full height (48.dp x fillMaxHeight) for effortless sliding.
        Box(
            modifier = if (isDragging) {
                Modifier
                    .align(pillAlignment)
                    .fillMaxHeight()
                    .width(48.dp)
            } else {
                Modifier
                    .align(pillAlignment)
                    .offset { IntOffset(0, touchBoxOffsetPx()) }
                    .width(36.dp)
                    .height(thumbTouchHeight)
            }.pointerInput(Unit) {
                awaitEachGesture {
                    val gridState = gridStateRef.value
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isDragging = true
                    onDraggingChangeState.value(true)
                    isScrollingRecently = true

                    // Relative drag from where the thumb is: a touch without movement scrolls
                    // nothing. (The touch box moves and grows when dragging starts, so absolute
                    // positions inside it don't map to the track; deltas do.)
                    val startProgress = naturalProgressState.value()
                    dragProgress = startProgress
                    var travelledPx = 0f

                    fun scrollTo(progress: Float) {
                        val floatRow = progress * maxScrollRowState.value()
                        val columns = columnsState.value
                        val lastItem = max(0, itemCountState.value - 1)
                        if (smoothState.value) {
                            val intRow = floatRow.toInt()
                            val offsetPx = ((floatRow - intRow) * safeRowHeightPxState.value()).roundToInt()
                            val targetItem = (intRow * columns).coerceIn(0, lastItem)
                            coroutineScope.launch { gridState.scrollToItem(targetItem, offsetPx) }
                        } else {
                            val targetItem = (floatRow.roundToInt() * columns).coerceIn(0, lastItem)
                            coroutineScope.launch { gridState.scrollToItem(targetItem, 0) }
                        }
                    }

                    try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        travelledPx += change.position.y - change.previousPosition.y
                        val newProgress = (startProgress + travelledPx / maxOffsetPxState.value).coerceIn(0f, 1f)
                        if (newProgress != dragProgress) {
                            dragProgress = newProgress
                            val targetRow = (newProgress * maxScrollRowState.value()).roundToInt()
                            if (targetRow != lastHapticIndex) {
                                lastHapticIndex = targetRow
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            scrollTo(newProgress)
                        }
                        change.consume()
                    }
                    } finally {
                        // Also when the gesture is cancelled: a stuck drag kept the thumb frozen.
                        isDragging = false
                        onDraggingChangeState.value(false)
                    }
                }
            },
        )
    }
}
