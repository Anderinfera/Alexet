package dev.ander.alexet.ui.videoeditor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.sin

/** Drag of one edge of a timeline item: finger movement in px. */
internal class EdgeDrag(
    val onStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onEnd: () -> Unit,
)

/**
 * Whether an edge can still grow ([canGrow]: more of the source left, not at the start of the
 * timeline) and the free space before the next item on its track ([roomPx]; null = none there).
 */
internal class EdgeRoom(val canGrow: Boolean, val roomPx: Float?)

/** Handle outside a selected item, for items too short to grab by their edges. */
private val OUTER_HANDLE_WIDTH = 14.dp
private val WAVE_MIN_WIDTH = 8.dp

/**
 * The trim handles of one timeline item, laid over its track (siblings of the item, so they get
 * the touch before the item's own move gesture):
 * - always a thin handle on each edge, flush with it;
 * - when selected, a wider one outside each edge that can still grow; where a neighbour leaves no
 *   room for it, a moving wave between the two instead.
 */
@Composable
internal fun EdgeHandles(
    itemKey: Any,
    leftDp: Dp,
    widthDp: Dp,
    selected: Boolean,
    color: Color,
    innerWidth: Dp,
    startRoom: EdgeRoom,
    endRoom: EdgeRoom,
    startDrag: EdgeDrag,
    endDrag: EdgeDrag,
) {
    val density = LocalDensity.current
    val inner = minOf(innerWidth, widthDp / 3)
    val innerColor = if (selected) color else Color.White.copy(alpha = 0.55f)
    EdgeHandle(
        key = itemKey to "inner-start",
        modifier = Modifier.offset(x = leftDp).width(inner),
        color = innerColor,
        shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp),
        drag = startDrag,
        grip = false,
    )
    EdgeHandle(
        key = itemKey to "inner-end",
        modifier = Modifier.offset(x = leftDp + widthDp - inner).width(inner),
        color = innerColor,
        shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp),
        drag = endDrag,
        grip = false,
    )
    if (!selected) return

    val outerPx = with(density) { OUTER_HANDLE_WIDTH.toPx() }
    if (startRoom.canGrow) {
        val room = startRoom.roomPx
        if (room == null || room >= outerPx) {
            EdgeHandle(
                key = itemKey to "outer-start",
                modifier = Modifier.offset(x = leftDp - OUTER_HANDLE_WIDTH).width(OUTER_HANDLE_WIDTH),
                color = color,
                shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp),
                drag = startDrag,
                grip = true,
            )
        } else {
            val roomDp = with(density) { room.toDp() }
            EdgeWave(centerDp = leftDp - roomDp / 2, gapDp = roomDp, color = color)
        }
    }
    if (endRoom.canGrow) {
        val room = endRoom.roomPx
        if (room == null || room >= outerPx) {
            EdgeHandle(
                key = itemKey to "outer-end",
                modifier = Modifier.offset(x = leftDp + widthDp).width(OUTER_HANDLE_WIDTH),
                color = color,
                shape = RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp),
                drag = endDrag,
                grip = true,
            )
        } else {
            val roomDp = with(density) { room.toDp() }
            EdgeWave(centerDp = leftDp + widthDp + roomDp / 2, gapDp = roomDp, color = color)
        }
    }
}

@Composable
private fun EdgeHandle(
    key: Any,
    modifier: Modifier,
    color: Color,
    shape: Shape,
    drag: EdgeDrag,
    grip: Boolean,
) {
    // The gesture outlives this composition: it calls the newest lambdas (the item moved meanwhile).
    val current by rememberUpdatedState(drag)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .zIndex(10f)
            .background(color, shape)
            .pointerInput(key) {
                detectHorizontalDragGestures(
                    onDragStart = { current.onStart() },
                    onDragEnd = { current.onEnd() },
                    onDragCancel = { current.onEnd() },
                ) { change, dragAmount ->
                    change.consume()
                    current.onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (grip) {
            Box(
                modifier = Modifier
                    .size(2.dp, 12.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** No room for a handle between two items: a vertical wave (like a loading indicator) in the gap. */
@Composable
private fun EdgeWave(centerDp: Dp, gapDp: Dp, color: Color) {
    val width = maxOf(gapDp, WAVE_MIN_WIDTH)
    val transition = rememberInfiniteTransition(label = "edgeWave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "edgeWavePhase",
    )
    Canvas(
        modifier = Modifier
            .offset(x = centerDp - width / 2)
            .width(width)
            .fillMaxHeight()
            .zIndex(11f),
    ) {
        val amplitude = size.width * 0.3f
        val cx = size.width / 2f
        val steps = 24
        val path = Path()
        for (i in 0..steps) {
            val y = size.height * i / steps
            val x = cx + amplitude * sin(phase + (y / size.height) * 4f * PI.toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

/** Free space (px) left of [edgeMs] before another item ([others]: start to end) ends; null: none there. */
internal fun roomBefore(edgeMs: Long, others: List<Pair<Long, Long>>, pxPerMs: Float): Float? =
    others.filter { (start, _) -> start < edgeMs }
        .maxOfOrNull { (_, end) -> end }
        ?.let { ((edgeMs - it) * pxPerMs).coerceAtLeast(0f) }

/** Free space (px) right of [edgeMs] before another item ([others]: start to end) starts; null: none there. */
internal fun roomAfter(edgeMs: Long, others: List<Pair<Long, Long>>, pxPerMs: Float): Float? =
    others.filter { (_, end) -> end > edgeMs }
        .minOfOrNull { (start, _) -> start }
        ?.let { ((it - edgeMs) * pxPerMs).coerceAtLeast(0f) }
