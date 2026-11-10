package dev.ander.alexet.ui.gallery

import dev.ander.alexet.ui.components.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.atan2
import kotlin.math.hypot
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import dev.ander.alexet.ui.components.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ander.alexet.R
import kotlin.math.abs

/** Shared strip between the panes: holds both folder names and works as the drag handle. */
private val BandHeight = 36.dp
private val BandLineThick = 6.dp
private val BandHalfStep = 14.dp

/**
 * Centre line of the step inside a strip of height [h]: (left y, right y). At rest the left
 * segment runs along the bottom and the right one along the top; [p] = 1 straightens it.
 */
private fun bandStepYs(h: Float, stroke: Float, p: Float): Pair<Float, Float> {
    val yLeft = (h - stroke / 2f) + (h / 2f - (h - stroke / 2f)) * p
    val yRight = (stroke / 2f) + (h / 2f - stroke / 2f) * p
    return yLeft to yRight
}
private const val GROOVE_RADIUS = 0.07f
private const val GROOVE_DEPTH = 0.34f

internal data class SplitRelease(
    val closeTop: Boolean = false,
    val closeBottom: Boolean = false,
    val stack: PaneStack = PaneStack.NONE,
    val ratio: Float = 0.5f,
)

internal fun targetPanelSplitRatio(
    panelSplitPercent: Int,
    isBookMode: Boolean,
    autoFoldSplitEnabled: Boolean = true,
    hingeAngle: Float? = null,
    foldSplitAngleThreshold: Float = 20f,
): Float {
    val customRatio = panelSplitPercent.coerceIn(10, 90) / 100f
    if (!autoFoldSplitEnabled) {
        return customRatio
    }
    val isFolded = isBookMode || (hingeAngle != null && hingeAngle <= (180f - foldSplitAngleThreshold) && hingeAngle > 5f)
    return if (isFolded) 0.5f else customRatio
}

/**
 * True while the edge between the folders and the content moves. The tiles of both grids then
 * drop their own placement animation: with it they trailed the moving edge on a curve of their own.
 */
internal val LocalPaneResizing = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>> {
    mutableStateOf(false)
}

/**
 * Folders | divider | content side by side. [leftFraction] (of the width without the divider) is
 * read while measuring only: a moving edge re-measures the three children, it never recomposes
 * them. Children: left pane, divider, right pane.
 */
@Composable
internal fun TwoPaneLayout(
    leftFraction: () -> Float,
    dividerWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxSize()) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val divider = dividerWidth.roundToPx().coerceIn(0, width)
        val available = width - divider
        val leftW = (available * leftFraction().coerceIn(0f, 1f)).roundToInt()
        val rightW = available - leftW
        val left = measurables[0].measure(Constraints.fixed(leftW, height))
        val line = measurables[1].measure(Constraints.fixed(divider, height))
        val right = measurables[2].measure(Constraints.fixed(rightW, height))
        layout(width, height) {
            left.placeRelative(0, 0)
            line.placeRelative(leftW, 0)
            right.placeRelative(leftW + divider, 0)
        }
    }
}

/** Finger ratio from which a release closes the pane (the zone is darkened while dragging). */
private const val CLOSE_ZONE = 0.93f

internal fun splitReleaseResult(
    ratio: Float,
    velocityPxPerSec: Float,
    paneHeightPx: Float,
): SplitRelease {
    val speed = if (paneHeightPx > 1f) abs(velocityPxPerSec) / paneHeightPx else 0f
    val fast = speed >= 1.15f
    val goingDown = velocityPxPerSec > 0f
    val goingUp = velocityPxPerSec < 0f
    return when {
        // Released inside the darkened close zone: it closes, as the feedback promised.
        ratio >= CLOSE_ZONE -> SplitRelease(closeBottom = true)
        ratio <= 1f - CLOSE_ZONE -> SplitRelease(closeTop = true)
        fast && goingDown && ratio >= 0.78f ->
            SplitRelease(closeBottom = true)
        fast && goingUp && ratio <= 0.22f ->
            SplitRelease(closeTop = true)
        ratio >= CANVAS_SNAP ->
            SplitRelease(stack = PaneStack.TOP, ratio = CANVAS_RATIO)
        ratio <= 1f - CANVAS_SNAP ->
            SplitRelease(stack = PaneStack.BOTTOM, ratio = 1f - CANVAS_RATIO)
        else ->
            SplitRelease(stack = PaneStack.NONE, ratio = ratio)
    }
}

/** Soft well: 1:1 outside, slightly pulled toward [center] inside the radius. */
private fun lightGroove(finger: Float): Float {
    val x = finger.coerceIn(0.05f, 0.95f)
    return well(well(x, CANVAS_RATIO), 1f - CANVAS_RATIO)
}

private fun well(x: Float, center: Float): Float {
    val d = x - center
    if (abs(d) >= GROOVE_RADIUS) return x
    val t = d / GROOVE_RADIUS
    val f = t * (1f - GROOVE_DEPTH * (1f - t * t))
    return center + f * GROOVE_RADIUS
}

private fun inGroove(ratio: Float): Boolean =
    abs(ratio - CANVAS_RATIO) < GROOVE_RADIUS ||
        abs(ratio - (1f - CANVAS_RATIO)) < GROOVE_RADIUS

@Composable
internal fun ResizableFolderSplit(
    topId: Long,
    bottomId: Long,
    splitRatio: Float,
    paneStack: PaneStack,
    handleEnabled: Boolean,
    onDragEnd: (ratio: Float, velocityPxPerSec: Float, paneHeightPx: Float) -> Unit,
    onSwapStacked: () -> Unit,
    topLabel: String,
    bottomLabel: String,
    onCloseTop: () -> Unit,
    onCloseBottom: () -> Unit,
    topHighlight: Float = 0f,
    bottomHighlight: Float = 0f,
    modifier: Modifier = Modifier,
    content: @Composable (albumId: Long) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val haptic = LocalHapticFeedback.current
        val parentHeightPx = with(density) { maxHeight.toPx() }
        var dragging by remember { mutableStateOf(false) }
        // Finger on the strip (before the drag slop too): drives the "pressed" look.
        var touching by remember { mutableStateOf(false) }
        var fingerRatio by remember { mutableFloatStateOf(splitRatio) }
        var inWell by remember { mutableStateOf(false) }
        var committedStack by remember { mutableStateOf(paneStack) }
        var committedRatio by remember { mutableFloatStateOf(splitRatio) }
        LaunchedEffect(paneStack, splitRatio) {
            if (!dragging) {
                committedStack = paneStack
                committedRatio = splitRatio
            }
        }
        val settledRatio = when (committedStack) {
            PaneStack.TOP -> CANVAS_RATIO
            PaneStack.BOTTOM -> 1f - CANVAS_RATIO
            PaneStack.NONE -> committedRatio
        }
        val liveRatio = if (dragging) lightGroove(fingerRatio) else settledRatio
        val ratio = liveRatio
        val closeZone = dragging && (fingerRatio >= CLOSE_ZONE || fingerRatio <= 1f - CLOSE_ZONE)
        val grooved = dragging && inGroove(fingerRatio)
        val canvas = !dragging && committedStack != PaneStack.NONE
        val windowH = maxHeight * CANVAS_RATIO
        val peekH = maxHeight - windowH
        val parentH = maxHeight

        val topY: Dp
        val topH: Dp
        val bottomY: Dp
        val bottomH: Dp
        val handleY: Dp
        if (canvas && committedStack == PaneStack.TOP) {
            topY = 0.dp
            topH = windowH
            bottomY = windowH
            bottomH = windowH
            handleY = windowH
        } else if (canvas && committedStack == PaneStack.BOTTOM) {
            topY = peekH - windowH
            topH = windowH
            bottomY = peekH
            bottomH = windowH
            handleY = peekH
        } else {
            topY = 0.dp
            topH = maxHeight * ratio
            bottomY = maxHeight * ratio
            bottomH = maxHeight * (1f - ratio)
            handleY = maxHeight * ratio
        }

        val spec: AnimationSpec<Dp> = if (dragging) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        }
        val aTopY by animateDpAsState(topY, spec, label = "splitTopY")
        val aTopH by animateDpAsState(topH.coerceAtLeast(0.dp), spec, label = "splitTopH")
        val aBottomY by animateDpAsState(bottomY, spec, label = "splitBottomY")
        val aBottomH by animateDpAsState(bottomH.coerceAtLeast(0.dp), spec, label = "splitBottomH")
        val aHandleY by animateDpAsState(handleY, spec, label = "splitHandleY")

        val pressProgress by animateFloatAsState(
            targetValue = if (touching || dragging) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
            label = "splitBandPress",
        )
        val highlightColor = MaterialTheme.colorScheme.primary

        val dragState = rememberDraggableState { delta ->
            if (parentHeightPx <= 1f) return@rememberDraggableState
            fingerRatio = (fingerRatio + delta / parentHeightPx).coerceIn(0.05f, 0.95f)
            val nowInWell = inGroove(fingerRatio)
            if (nowInWell && !inWell) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            inWell = nowInWell
        }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, aTopY.roundToPx()) }
                    .fillMaxWidth()
                    .height((aTopH - BandHeight / 2).coerceAtLeast(0.dp)),
            ) {
                key(topId) { content(topId) }
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, (aBottomY + BandHeight / 2).roundToPx()) }
                    .fillMaxWidth()
                    .height((aBottomH - BandHeight / 2).coerceAtLeast(0.dp)),
            ) {
                key(bottomId) { content(bottomId) }
            }
            if (closeZone) {
                val closingBottom = fingerRatio >= CLOSE_ZONE
                Box(
                    Modifier
                        .align(if (closingBottom) Alignment.BottomCenter else Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(parentH * 0.07f)
                        .zIndex(1f)
                        .background(Color.Black.copy(alpha = 0.28f)),
                )
            }
            if (canvas) {
                val peekAlign = if (committedStack == PaneStack.TOP) {
                    Alignment.BottomCenter
                } else {
                    Alignment.TopCenter
                }
                Box(
                    Modifier
                        .align(peekAlign)
                        .fillMaxWidth()
                        .height(peekH)
                        .zIndex(1f)
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(enabled = !dragging) {
                            committedStack = when (committedStack) {
                                PaneStack.TOP -> PaneStack.BOTTOM
                                PaneStack.BOTTOM -> PaneStack.TOP
                                PaneStack.NONE -> committedStack
                            }
                            onSwapStacked()
                        },
                )
            }
            SplitLabelBand(
                enabled = handleEnabled,
                snapped = canvas || grooved,
                closing = closeZone,
                progress = pressProgress,
                topLabel = topLabel,
                bottomLabel = bottomLabel,
                onCloseTop = onCloseTop,
                onCloseBottom = onCloseBottom,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, (aHandleY - BandHeight / 2).roundToPx()) }
                    .fillMaxWidth()
                    .height(BandHeight)
                    .zIndex(2f)
                    .pointerInput(handleEnabled) {
                        if (!handleEnabled) return@pointerInput
                        // Observe only (Initial pass, nothing consumed): close buttons keep working.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            touching = true
                            try {
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                } while (event.changes.any { it.pressed })
                            } finally {
                                touching = false
                            }
                        }
                    }
                    // Drag starts after the touch slop, so a tap on a close button stays a tap.
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        enabled = handleEnabled,
                        onDragStarted = {
                            dragging = true
                            fingerRatio = settledRatio
                            inWell = inGroove(fingerRatio)
                        },
                        onDragStopped = { velocity ->
                            val result = splitReleaseResult(fingerRatio, velocity, parentHeightPx)
                            if (!result.closeTop && !result.closeBottom) {
                                committedStack = result.stack
                                committedRatio = result.ratio
                            }
                            dragging = false
                            inWell = false
                            onDragEnd(fingerRatio, velocity, parentHeightPx)
                        },
                    ),
            )
            // Folder reminder frame: each pane plus its side of the stepped line (its name included).
            if (topHighlight > 0.01f || bottomHighlight > 0.01f) {
                Canvas(Modifier.fillMaxSize().zIndex(3f)) {
                    val band = BandHeight.toPx()
                    val stroke = BandLineThick.toPx()
                    val stripTop = aHandleY.toPx() - band / 2f
                    val (yLeft, yRight) = bandStepYs(band, stroke, pressProgress)
                    val halfStep = BandHalfStep.toPx()
                    val mid = size.width / 2f
                    val w = size.width
                    fun frame(path: Path, alpha: Float) {
                        drawPath(path, highlightColor.copy(alpha = 0.21f * alpha))
                        // Stroke centred on the outline, clipped to the inside: 3 dp inner border.
                        clipPath(path) {
                            drawPath(path, highlightColor.copy(alpha = 0.5f * alpha), style = Stroke(width = 6.dp.toPx()))
                        }
                    }
                    if (topHighlight > 0.01f) {
                        val l = stripTop + yLeft - stroke / 2f
                        val r = stripTop + yRight - stroke / 2f
                        frame(
                            Path().apply {
                                moveTo(0f, aTopY.toPx())
                                lineTo(w, aTopY.toPx())
                                lineTo(w, r)
                                lineTo(mid + halfStep, r)
                                lineTo(mid - halfStep, l)
                                lineTo(0f, l)
                                close()
                            },
                            topHighlight,
                        )
                    }
                    if (bottomHighlight > 0.01f) {
                        val l = stripTop + yLeft + stroke / 2f
                        val r = stripTop + yRight + stroke / 2f
                        val end = (aBottomY + aBottomH).toPx()
                        frame(
                            Path().apply {
                                moveTo(0f, l)
                                lineTo(mid - halfStep, l)
                                lineTo(mid + halfStep, r)
                                lineTo(w, r)
                                lineTo(w, end)
                                lineTo(0f, end)
                                close()
                            },
                            bottomHighlight,
                        )
                    }
                }
            }
        }
    }
}


/**
 * Shared title strip between the two panes. At rest: top folder name on the left above a thin
 * line, bottom folder name on the right below it, the line stepping across with a tilted grip.
 * While touched ([progress] -> 1): the line straightens, the grip turns white and level,
 * and the names slide apart (left up, right down). One spring drives every part of the change.
 */
@Composable
private fun SplitLabelBand(
    enabled: Boolean,
    snapped: Boolean,
    closing: Boolean,
    progress: Float,
    topLabel: String,
    bottomLabel: String,
    onCloseTop: () -> Unit,
    onCloseBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleLabel = stringResource(R.string.split_handle)
    val closeLabel = stringResource(R.string.close_pane)
    val p = progress
    val baseLine = when {
        closing -> Color(0xFF8A2A2A)
        snapped -> Color(0xFF8A8A8A)
        else -> lerp(Color(0xFF6A6A6A), Color(0xFF3C3C3C), p)
    }
    val gripColor = when {
        !enabled -> Color.White.copy(alpha = 0.45f)
        closing -> Color(0xFFFF8A80)
        else -> lerp(Color(0xFFB4B4B4), Color.White, p)
    }
    val labelBg = MaterialTheme.colorScheme.surfaceContainerHigh

    BoxWithConstraints(
        modifier
            .background(labelBg)
            .semantics { contentDescription = handleLabel },
    ) {
        val labelMaxW = maxWidth * 0.45f
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            // Same thickness at rest and while touched: only the shape and colours animate.
            val stroke = BandLineThick.toPx()
            val halfStep = BandHalfStep.toPx()
            val mid = w / 2f
            val (yLeft, yRight) = bandStepYs(h, stroke, p)
            val path = Path().apply {
                moveTo(0f, yLeft)
                lineTo(mid - halfStep, yLeft)
                lineTo(mid + halfStep, yRight)
                lineTo(w, yRight)
            }
            drawPath(path, baseLine, style = Stroke(width = stroke, join = StrokeJoin.Round))

            // Half the length of the diagonal at rest; same size while touched.
            val gripW = hypot(2f * halfStep, h - stroke) * 0.5f
            val gripH = 4.dp.toPx()
            val cy = (yLeft + yRight) / 2f
            val angle = Math.toDegrees(atan2((yRight - yLeft).toDouble(), (2f * halfStep).toDouble())).toFloat()
            rotate(angle, pivot = Offset(mid, cy)) {
                drawRoundRect(
                    color = gripColor,
                    topLeft = Offset(mid - gripW / 2f, cy - gripH / 2f),
                    size = Size(gripW, gripH),
                    cornerRadius = CornerRadius(gripH / 2f, gripH / 2f),
                )
            }
        }

        // While touched the names move off the strip onto the panes: give them a backdrop.
        val lift = 22.dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .widthIn(max = labelMaxW)
                .offset { IntOffset(0, (-lift * p).roundToPx()) }
                .background(labelBg.copy(alpha = 0.9f * p), RoundedCornerShape(8.dp)),
        ) {
            BandCloseButton(onCloseTop, closeLabel)
            Text(
                text = topLabel,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .widthIn(max = labelMaxW)
                .offset { IntOffset(0, (lift * p).roundToPx()) }
                .background(labelBg.copy(alpha = 0.9f * p), RoundedCornerShape(8.dp)),
        ) {
            Text(
                text = bottomLabel,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(start = 8.dp),
            )
            BandCloseButton(onCloseBottom, closeLabel)
        }
    }
}

@Composable
private fun BandCloseButton(onClick: () -> Unit, description: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(BandHeight)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}
