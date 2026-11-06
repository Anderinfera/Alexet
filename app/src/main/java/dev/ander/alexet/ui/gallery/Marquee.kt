package dev.ander.alexet.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

fun Rect.normalized(): Rect {
    val l = min(left, right)
    val t = min(top, bottom)
    val r = max(left, right)
    val b = max(top, bottom)
    return Rect(l, t, r, b)
}

fun idsIntersecting(bounds: Map<Long, Rect>, rect: Rect): Set<Long> {
    val area = rect.normalized()
    if (area.width < 8f && area.height < 8f) {
        val point = Offset(area.left, area.top)
        val direct = bounds.entries.firstOrNull { it.value.contains(point) }
        if (direct != null) return setOf(direct.key)
        val expanded = Rect(point.x - 24f, point.y - 24f, point.x + 24f, point.y + 24f)
        val near = bounds.filter { it.value.touches(expanded) }.keys
        if (near.isNotEmpty()) return near
        return emptySet()
    }
    return bounds.filter { it.value.touches(area) }.keys
}

/** Rectangle hit-test preserving grid reading order when [ordered] is provided. */
fun idsIntersectingOrdered(
    bounds: Map<Long, Rect>,
    rect: Rect,
    ordered: List<Long> = emptyList(),
): List<Long> {
    val hit = idsIntersecting(bounds, rect)
    if (hit.isEmpty()) return emptyList()
    return if (ordered.isNotEmpty()) {
        ordered.filter { it in hit }
    } else {
        hit.toList()
    }
}

fun indexOfPoint(bounds: Map<Long, Rect>, ordered: List<Long>, point: Offset): Int {
    for ((id, rect) in bounds) {
        if (rect.contains(point)) {
            val idx = ordered.indexOf(id)
            if (idx >= 0) return idx
        }
    }
    var bestId = -1L
    var bestDist = Float.POSITIVE_INFINITY
    for ((id, rect) in bounds) {
        val dist = (rect.center - point).getDistanceSquared()
        if (dist < bestDist) {
            bestDist = dist
            bestId = id
        }
    }
    return if (bestId != -1L) ordered.indexOf(bestId) else -1
}

fun idsInLinearRange(
    ordered: List<Long>,
    bounds: Map<Long, Rect>,
    origin: Offset,
    finger: Offset,
    startIndex: Int,
): List<Long> {
    if (ordered.isEmpty()) return emptyList()
    val start = if (startIndex in ordered.indices) {
        startIndex
    } else {
        indexOfPoint(bounds, ordered, origin)
    }
    val end = indexOfPoint(bounds, ordered, finger).let { if (it < 0) start else it }
    if (start < 0) return emptyList()
    val lo = minOf(start, end)
    val hi = maxOf(start, end)
    val safeLo = lo.coerceIn(0, ordered.size)
    val safeHi = (hi + 1).coerceIn(safeLo, ordered.size)
    return ordered.subList(safeLo, safeHi).toList()
}

private fun Rect.touches(other: Rect): Boolean {
    return left <= other.right &&
        right >= other.left &&
        top <= other.bottom &&
        bottom >= other.top
}

private fun edgeScrollDelta(y: Float, height: Float, edge: Float, maxSpeed: Float): Float {
    if (height <= edge * 2f) return 0f
    return when {
        y < edge -> {
            val t = ((edge - y) / edge).coerceIn(0f, 1f)
            -maxSpeed * t * t
        }
        y > height - edge -> {
            val t = ((y - (height - edge)) / edge).coerceIn(0f, 1f)
            maxSpeed * t * t
        }
        else -> 0f
    }
}

@Composable
fun MarqueeBox(
    enabled: Boolean,
    itemBoundsInWindow: Map<Long, Rect>,
    onSelectIds: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
    selectedIds: List<Long> = emptyList(),
    orderedIds: List<Long> = emptyList(),
    linear: Boolean = false,
    onSelectingChange: (Boolean) -> Unit = {},
    onAutoScroll: (Float) -> Float = { 0f },
    /**
     * Ignores gestures for now (the fast-scroll thumb is held) while staying attached: removing
     * the handler mid-gesture cancelled the thumb's drag inside this box.
     */
    suspended: Boolean = false,
    content: @Composable () -> Unit,
) {
    val suspendedState = rememberUpdatedState(suspended)
    val boundsState = rememberUpdatedState(itemBoundsInWindow)
    val selectState = rememberUpdatedState(onSelectIds)
    val selectedState = rememberUpdatedState(selectedIds)
    val orderedState = rememberUpdatedState(orderedIds)
    val linearState = rememberUpdatedState(linear)
    val activeState = rememberUpdatedState(onSelectingChange)
    val scrollState = rememberUpdatedState(onAutoScroll)
    var host by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val hostState = rememberUpdatedState(host)
    val haptic = LocalHapticFeedback.current
    val frameScope = rememberCoroutineScope()
    val frameScopeState = rememberUpdatedState(frameScope)
    // No drawn selection rectangle: the highlighted tiles show what is selected.
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { host = it }
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        val edge = 64.dp.toPx()
                        val maxSpeed = 22.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val pointerId = down.id
                            var origin = down.position
                            val slop = viewConfiguration.touchSlop
                            val timeout = viewConfiguration.longPressTimeoutMillis
                            val aborted = withTimeoutOrNull(timeout) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                        ?: return@withTimeoutOrNull true
                                    if (!change.pressed) return@withTimeoutOrNull true
                                    if (suspendedState.value) return@withTimeoutOrNull true
                                    if ((change.position - origin).getDistance() > slop) {
                                        return@withTimeoutOrNull true
                                    }
                                }
                            }
                            if (aborted == true || suspendedState.value) return@awaitEachGesture
                            val currentChange = currentEvent.changes.firstOrNull { it.id == pointerId }
                            if (currentChange == null || !currentChange.pressed) return@awaitEachGesture
                            currentEvent.changes.forEach { it.consume() }

                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Snapshot selection BEFORE notifying parent (parent may flip
                            // marqueeActive / rewrite live list on the same call stack).
                            val base = selectedState.value.toList()
                            activeState.value(true)
                            var finger = currentEvent.changes
                                .firstOrNull { it.id == pointerId }
                                ?.position
                                ?: origin
                            val hostNow = hostState.value
                            val originW = toWindowPoint(hostNow, origin)
                            val visible = visibleItemBounds(hostNow, boundsState.value)
                            val ordered = orderedState.value
                            val useLinear = linearState.value && ordered.isNotEmpty()
                            val startIndex = if (useLinear) {
                                indexOfPoint(visible, ordered, originW)
                            } else {
                                -1
                            }
                            val startId = if (useLinear) {
                                ordered.getOrNull(startIndex)
                            } else {
                                idsIntersectingOrdered(
                                    boundsState.value,
                                    toWindow(hostNow, Rect(origin, origin)),
                                    ordered,
                                ).firstOrNull()
                            }
                            val baseSet = base.toSet()
                            val subtract = startId != null && startId in baseSet
                            var lastPublished: List<Long> = base
                            var lastHit: List<Long> = emptyList()
                            fun publish() {
                                val host = hostState.value
                                val hit = if (useLinear) {
                                    idsInLinearRange(
                                        ordered = orderedState.value,
                                        bounds = visibleItemBounds(host, boundsState.value),
                                        origin = toWindowPoint(host, origin),
                                        finger = toWindowPoint(host, finger),
                                        startIndex = startIndex,
                                    )
                                } else {
                                    val bounds = boundsState.value
                                    val now = idsIntersectingOrdered(
                                        bounds,
                                        toWindow(host, Rect(origin, finger)),
                                        orderedState.value,
                                    )
                                    // Tiles scrolled out under the rectangle lose their bounds; they
                                    // were inside it when they left, so they stay hit.
                                    now + lastHit.filter { it !in bounds && it !in now }
                                }
                                lastHit = hit
                                val hitSet = hit.toSet()
                                val next = if (subtract) {
                                    // Deselect hit ids; keep prior order of survivors.
                                    base.filter { it !in hitSet }
                                } else {
                                    // Append newly hit ids in traversal order; keep prior positions.
                                    base + hit.filter { it !in baseSet }
                                }
                                if (next != lastPublished) {
                                    lastPublished = next
                                    selectState.value(next)
                                }
                            }
                            publish()
                            val scrollJob = frameScopeState.value.launch {
                                var pendingDelta = 0f
                                while (true) {
                                    withFrameNanos { }
                                    val height = hostState.value?.size?.height?.toFloat() ?: continue
                                    val delta = edgeScrollDelta(finger.y, height, edge, maxSpeed)
                                    pendingDelta = (pendingDelta + delta).coerceIn(-maxSpeed * 2f, maxSpeed * 2f)
                                    if (pendingDelta == 0f) continue
                                    val actual = scrollState.value(pendingDelta)
                                    pendingDelta -= actual
                                    if (actual == 0f) continue
                                    origin = origin.copy(y = origin.y - actual)
                                    publish()
                                }
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                        ?: break
                                    change.consume()
                                    if (!change.pressed) break
                                    finger = change.position
                                    publish()
                                }
                            } finally {
                                scrollJob.cancel()
                                activeState.value(false)
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

private fun toWindow(host: LayoutCoordinates?, local: Rect): Rect {
    if (host == null) return local
    val topLeft = host.localToWindow(Offset(local.left, local.top))
    val bottomRight = host.localToWindow(Offset(local.right, local.bottom))
    return Rect(topLeft, bottomRight)
}

private fun toWindowPoint(host: LayoutCoordinates?, local: Offset): Offset {
    return host?.localToWindow(local) ?: local
}

private fun visibleItemBounds(host: LayoutCoordinates?, bounds: Map<Long, Rect>): Map<Long, Rect> {
    val window = host?.boundsInWindow() ?: return bounds
    return bounds.filterValues { it.overlaps(window) }
}
