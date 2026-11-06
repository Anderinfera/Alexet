package dev.ander.alexet.ui.videoeditor

import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import dev.ander.alexet.data.videoeditor.ImageOverlayItem
import dev.ander.alexet.data.videoeditor.TextOverlayItem
import dev.ander.alexet.data.videoeditor.VideoExportPlanner
import dev.ander.alexet.data.videoeditor.VideoProjectAspectRatio
import kotlin.math.roundToInt

@Composable
fun VideoOverlayCanvas(
    state: VideoEditorStateHolder,
    onCanvasClick: () -> Unit = {},
    onTextDoubleTap: (String) -> Unit = {},
    onImageDoubleTap: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val project = state.project
    val selectedClip = project.selectedClip
    val targetAspect = project.aspectRatio.ratio
    // System (Material You) accent instead of a fixed blue.
    val accent = MaterialTheme.colorScheme.primary

    // Only a change of which overlays are shown recomposes, not every playback frame.
    val textOverlays by remember(state) {
        derivedStateOf { state.project.textOverlays.filter { state.playheadMs in it.startTimelineMs..it.endTimelineMs } }
    }
    val imageOverlays by remember(state) {
        derivedStateOf { state.project.imageOverlays.filter { state.playheadMs in it.startTimelineMs..it.endTimelineMs } }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()

        if (containerWidth <= 0f || containerHeight <= 0f) return@BoxWithConstraints

        val isFramingActive = targetAspect != null && (project.aspectRatio != VideoProjectAspectRatio.ORIGINAL || state.currentTab == VideoEditorTab.ASPECT || project.isFramingMode)

        val frameWidth: Float
        val frameHeight: Float
        val leftScrim: Float
        val topScrim: Float

        if (isFramingActive && targetAspect != null) {
            if (containerWidth / containerHeight > targetAspect) {
                frameHeight = containerHeight
                frameWidth = containerHeight * targetAspect
            } else {
                frameWidth = containerWidth
                frameHeight = containerWidth / targetAspect
            }
            leftScrim = (containerWidth - frameWidth) / 2f
            topScrim = (containerHeight - frameHeight) / 2f
        } else {
            frameWidth = containerWidth
            frameHeight = containerHeight
            leftScrim = 0f
            topScrim = 0f
        }
        SideEffect { state.previewFramePx = frameWidth to frameHeight }

        val coroutineScope = rememberCoroutineScope()
        var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }
        var lastTapTime by remember { mutableStateOf(0L) }

        // --- 1. Unified Canvas Gesture Layer (Framing, Scrim, 2-Finger Text Gestures & Canvas Taps) ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(targetAspect, isFramingActive, project.selectedTextId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        val downPos = down.position
                        var isDraggingOrTransforming = false

                        val clipOnDown = state.project.selectedClip
                        var currentPanX = clipOnDown?.panX ?: 0f
                        var currentPanY = clipOnDown?.panY ?: 0f
                        var currentZoom = clipOnDown?.zoomScale ?: 1.0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val activePointers = event.changes.filter { it.pressed }
                            if (activePointers.isEmpty()) {
                                break
                            }

                            val pointerCount = activePointers.size

                            if (pointerCount >= 2) {
                                val currentSelectedText = project.selectedTextId
                                val p1 = activePointers[0]
                                val p2 = activePointers[1]
                                val prevP1 = p1.previousPosition
                                val prevP2 = p2.previousPosition

                                val prevDist = (prevP1 - prevP2).getDistance()
                                val currentDist = (p1.position - p2.position).getDistance()
                                val panDelta = ((p1.position - prevP1) + (p2.position - prevP2)) / 2f

                                if (currentSelectedText != null) {
                                    // Two fingers: move selected text + pinch scale selected text
                                    isDraggingOrTransforming = true
                                    if (frameWidth > 0f && frameHeight > 0f) {
                                        state.moveTextOverlay(currentSelectedText, panDelta.x / frameWidth, panDelta.y / frameHeight)
                                    }
                                    if (prevDist > 20f) {
                                        val scaleDelta = (currentDist / prevDist) - 1f
                                        state.scaleTextOverlay(currentSelectedText, scaleDelta)
                                    }
                                    p1.consume()
                                    p2.consume()
                                } else if (isFramingActive) {
                                    // Multi-touch Pinch to Zoom & Pan video framing — CANCELS pan lock
                                    isDraggingOrTransforming = true
                                    state.framingLocked = false
                                    state.fitLocked = false
                                    val zoomDelta = if (prevDist > 20f) currentDist / prevDist else 1f
                                    p1.consume()
                                    p2.consume()

                                    currentZoom = (currentZoom * zoomDelta).coerceIn(MIN_FRAMING_ZOOM, 5.0f)
                                    currentPanX += panDelta.x
                                    currentPanY += panDelta.y

                                    // Free pan — no clamping since panLocked is false
                                    state.updateFraming(currentPanX, currentPanY, currentZoom)
                                }
                            } else if (pointerCount == 1 && isFramingActive && project.selectedTextId == null) {
                                val change = activePointers[0]
                                val dragDelta = change.positionChange()
                                val distFromDown = (change.position - downPos).getDistance()

                                if (distFromDown > viewConfiguration.touchSlop) {
                                    isDraggingOrTransforming = true
                                }

                                if (isDraggingOrTransforming) {
                                    change.consume()
                                    currentPanX += dragDelta.x
                                    currentPanY += dragDelta.y

                                    if (state.fitLocked) {
                                        // "Fit": only along the free side, never out of the frame.
                                        val (x, y) = state.clampFit(currentPanX, currentPanY, currentZoom)
                                        currentPanX = x
                                        currentPanY = y
                                        state.updateFraming(currentPanX, currentPanY, currentZoom)
                                    } else if (state.framingLocked) {
                                        // Clamp pan so black background never shows inside the frame: the
                                        // limits come from the picture's own size (it covers the frame).
                                        val (maxPanX, maxPanY) = state.lockedPanLimits(currentZoom)
                                        currentPanX = currentPanX.coerceIn(-maxPanX, maxPanX)
                                        currentPanY = currentPanY.coerceIn(-maxPanY, maxPanY)
                                        state.updateFraming(currentPanX, currentPanY, currentZoom, maxPanX, maxPanY)
                                    } else {
                                        state.updateFraming(currentPanX, currentPanY, currentZoom)
                                    }
                                }
                            }
                        }

                        // Tap or Double-Tap handler when released without significant dragging
                        val upTime = System.currentTimeMillis()
                        val duration = upTime - downTime
                        if (!isDraggingOrTransforming && duration < 350L) {
                            val timeSinceLastTap = downTime - lastTapTime
                            if (timeSinceLastTap in 40L..320L && isFramingActive && targetAspect != null) {
                                // Double Tap on canvas: CANCEL single tap immediately & toggle fill/reset
                                pendingSingleTapJob?.cancel()
                                pendingSingleTapJob = null
                                lastTapTime = 0L

                                // Fill the frame and lock it, or (already locked) show the whole picture.
                                // The preview covers the frame at zoom 1: the ratio applied again
                                // zoomed in far too much.
                                if (state.project.selectedClip != null) state.toggleFraming()
                            } else {
                                // First Tap on canvas: schedule single tap with debounce
                                lastTapTime = downTime
                                pendingSingleTapJob?.cancel()
                                if (isFramingActive && targetAspect != null) {
                                    pendingSingleTapJob = coroutineScope.launch {
                                        delay(260L)
                                        state.selectText(null)
                                        state.selectImage(null)
                                        onCanvasClick()
                                    }
                                } else {
                                    state.selectText(null)
                                    state.selectImage(null)
                                    onCanvasClick()
                                }
                            }
                        }
                    }
                }
                .zIndex(1f),
        ) {
            if (isFramingActive && targetAspect != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Draw dark ghost scrim outside the aspect box
                    val scrimColor = Color.Black.copy(alpha = 0.58f)
                    if (leftScrim > 0) {
                        drawRect(scrimColor, Offset(0f, 0f), Size(leftScrim, containerHeight))
                        drawRect(scrimColor, Offset(leftScrim + frameWidth, 0f), Size(leftScrim, containerHeight))
                    }
                    if (topScrim > 0) {
                        drawRect(scrimColor, Offset(0f, 0f), Size(containerWidth, topScrim))
                        drawRect(scrimColor, Offset(0f, topScrim + frameHeight), Size(containerWidth, topScrim))
                    }

                    // Dashed bounding rectangle around aspect frame
                    drawRect(
                        color = accent,
                        topLeft = Offset(leftScrim, topScrim),
                        size = Size(frameWidth, frameHeight),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                        ),
                    )
                }
            }
        }

        // --- 2. Text Overlays ---
        textOverlays.forEach { item ->
            val isSelected = project.selectedTextId == item.id
            var itemSize by remember(item.id) { mutableStateOf(IntSize.Zero) }

            Box(
                modifier = Modifier
                    .offset {
                        val (centerPxX, centerPxY) = VideoExportPlanner.overlayFrameToContainer(
                            item.normalizedX,
                            item.normalizedY,
                            leftScrim,
                            topScrim,
                            frameWidth,
                            frameHeight,
                        )
                        val leftPx = centerPxX - (itemSize.width / 2f)
                        val topPx = centerPxY - (itemSize.height / 2f)
                        IntOffset(leftPx.roundToInt(), topPx.roundToInt())
                    }
                    .onGloballyPositioned { itemSize = it.size }
                    .graphicsLayer {
                        scaleX = item.scale
                        scaleY = item.scale
                        rotationZ = item.rotationDeg
                    }
                    .zIndex(if (isSelected) 10f else 2f)
                    .pointerInput(item.id) {
                        detectTapGestures(
                            onTap = { state.selectText(item.id) },
                            onDoubleTap = {
                                state.selectText(item.id)
                                onTextDoubleTap(item.id)
                            },
                        )
                    }
                    // One finger moves the text (before only two fingers did).
                    .pointerInput(item.id, frameWidth, frameHeight) {
                        detectDragGestures(
                            onDragStart = { state.selectText(item.id) },
                            // The next drag is a new undo step.
                            onDragEnd = { state.endGesture() },
                            onDragCancel = { state.endGesture() },
                        ) { change, dragAmount ->
                            change.consume()
                            if (frameWidth <= 0f || frameHeight <= 0f) return@detectDragGestures
                            // The finger is measured inside the text's scaled and rotated layer.
                            val current = currentTextItem(state, item.id) ?: return@detectDragGestures
                            val (dx, dy) = layerDeltaToParent(dragAmount, current.rotationDeg, current.scale)
                            state.moveTextOverlay(item.id, dx / frameWidth, dy / frameHeight)
                        }
                    },
            ) {
                // Text Content
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(item.bgColor))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = item.text,
                        color = Color(item.textColor),
                        fontSize = item.fontSize.sp,
                        fontWeight = if (item.isBold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (item.isItalic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.combine(
                            listOfNotNull(
                                androidx.compose.ui.text.style.TextDecoration.Underline.takeIf { item.isUnderline },
                                androidx.compose.ui.text.style.TextDecoration.LineThrough.takeIf { item.isStrikethrough },
                            ),
                        ),
                        textAlign = when (item.alignment) {
                            0 -> androidx.compose.ui.text.style.TextAlign.Left
                            2 -> androidx.compose.ui.text.style.TextAlign.Right
                            else -> androidx.compose.ui.text.style.TextAlign.Center
                        },
                    )
                }

                // Controls when selected: frame outline, delete, free turn, size and 90° step
                if (isSelected) {
                    // Top turn handle: follows the finger around the text's centre.
                    RotationHandle(
                        accent = accent,
                        rotationDeg = item.rotationDeg,
                        itemHeightPx = itemSize.height.toFloat(),
                        key = item.id,
                        onRotate = { deg -> state.rotateTextOverlay(item.id, deg) },
                        onEnd = { state.endGesture() },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    // Bottom-right size handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 10.dp, y = 10.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .border(1.5.dp, Color.White, CircleShape)
                            .pointerInput(item.id) {
                                detectDragGestures(
                                    onDragEnd = { state.endGesture() },
                                    onDragCancel = { state.endGesture() },
                                ) { change, dragAmount ->
                                    change.consume()
                                    state.scaleTextOverlay(item.id, (dragAmount.x + dragAmount.y) / 100f)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.OpenInFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp),
                        )
                    }

                    // Frame Outline
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .border(
                                width = 1.5.dp,
                                color = accent,
                                shape = RoundedCornerShape(8.dp),
                            ),
                    )

                    // Top-Left Delete Handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = (-10).dp, y = (-10).dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0F172A))
                            .border(1.5.dp, Color(0xFFEF4444), CircleShape)
                            .clickable { state.removeTextOverlay(item.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(12.dp),
                        )
                    }

                    // Bottom-Left 90° Step Rotate Handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = (-10).dp, y = 10.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0F172A))
                            .border(1.5.dp, accent, CircleShape)
                            .clickable {
                                val rawRot = ((item.rotationDeg + 90f) / 90f).roundToInt() * 90f
                                val nextRot = ((rawRot % 360f) + 360f) % 360f
                                state.setTextRotation(item.id, nextRot)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.RotateRight,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        // --- 3. Image / Sticker Overlays ---
        imageOverlays.forEach { item ->
            val isSelected = project.selectedImageId == item.id
            var itemSize by remember(item.id) { mutableStateOf(IntSize.Zero) }

            Box(
                modifier = Modifier
                    .offset {
                        val (centerPxX, centerPxY) = VideoExportPlanner.overlayFrameToContainer(
                            item.normalizedX,
                            item.normalizedY,
                            leftScrim,
                            topScrim,
                            frameWidth,
                            frameHeight,
                        )
                        val leftPx = centerPxX - (itemSize.width / 2f)
                        val topPx = centerPxY - (itemSize.height / 2f)
                        IntOffset(leftPx.roundToInt(), topPx.roundToInt())
                    }
                    .size(90.dp)
                    .onGloballyPositioned { itemSize = it.size }
                    .graphicsLayer {
                        scaleX = item.scale
                        scaleY = item.scale
                        rotationZ = item.rotationDeg
                    }
                    .zIndex(if (isSelected) 10f else 2f)
                    .pointerInput(item.id, frameWidth, frameHeight) {
                        // The finger is measured inside the sticker's scaled and rotated layer.
                        fun move(amount: Offset) {
                            if (frameWidth <= 0f || frameHeight <= 0f) return
                            val current = state.project.imageOverlays.firstOrNull { it.id == item.id } ?: return
                            val (dx, dy) = layerDeltaToParent(amount, current.rotationDeg, current.scale)
                            state.moveImageOverlay(item.id, dx / frameWidth, dy / frameHeight)
                        }
                        var lastTapAt = 0L
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            state.selectImage(item.id)
                            var drag = awaitTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                move(over)
                            }
                            if (drag == null) {
                                // A tap: two in a row open the sticker's menu.
                                if (down.uptimeMillis - lastTapAt < DOUBLE_TAP_MS) {
                                    lastTapAt = 0L
                                    onImageDoubleTap(item.id)
                                } else {
                                    lastTapAt = down.uptimeMillis
                                }
                                return@awaitEachGesture
                            }
                            lastTapAt = 0L
                            while (drag != null) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    break
                                }
                                val dragAmount = change.positionChange()
                                change.consume()
                                move(dragAmount)
                                drag = if (change.isConsumed) change else null
                            }
                            // The next drag is a new undo step.
                            state.endGesture()
                        }
                    },
            ) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                // Adobe Transform Box when Selected
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .border(
                                width = 1.5.dp,
                                color = accent,
                                shape = RoundedCornerShape(4.dp),
                            ),
                    )

                    // 1. Top turn handle: follows the finger around the sticker's centre.
                    RotationHandle(
                        accent = accent,
                        rotationDeg = item.rotationDeg,
                        itemHeightPx = itemSize.height.toFloat(),
                        key = item.id,
                        onRotate = { deg -> state.rotateImageOverlay(item.id, deg) },
                        onEnd = { state.endGesture() },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    // 2. Top-Left Delete Handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = (-10).dp, y = (-10).dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0F172A))
                            .border(1.5.dp, Color(0xFFEF4444), CircleShape)
                            .clickable { state.removeImageOverlay(item.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(12.dp),
                        )
                    }

                    // 3. Bottom-Right Scale Handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 10.dp, y = 10.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .border(1.5.dp, Color.White, CircleShape)
                            .pointerInput(item.id) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val scaleDelta = (dragAmount.x + dragAmount.y) / 100f
                                    state.scaleImageOverlay(item.id, scaleDelta)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.OpenInFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp),
                        )
                    }

                    // 4. Bottom-Left Reset / Step 90° Handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = (-10).dp, y = 10.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0F172A))
                            .border(1.5.dp, accent, CircleShape)
                            .clickable {
                                val rawRot = ((item.rotationDeg + 90f) / 90f).roundToInt() * 90f
                                val nextRot = ((rawRot % 360f) + 360f) % 360f
                                state.setImageRotation(item.id, nextRot)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.RestartAlt,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The text as it is now (a drag handler outlives the item it was set up with). */
private fun currentTextItem(state: VideoEditorStateHolder, id: String): TextOverlayItem? =
    state.project.textOverlays.firstOrNull { it.id == id }

/** Two taps on a sticker closer than this open its menu. */
private const val DOUBLE_TAP_MS = 300L

/**
 * A finger movement measured inside an item's layer (scaled by [scale], turned by [rotationDeg])
 * as a movement on the frame.
 */
private fun layerDeltaToParent(delta: Offset, rotationDeg: Float, scale: Float): Pair<Float, Float> {
    val rad = Math.toRadians(rotationDeg.toDouble())
    val cos = kotlin.math.cos(rad).toFloat()
    val sin = kotlin.math.sin(rad).toFloat()
    return (delta.x * cos - delta.y * sin) * scale to (delta.x * sin + delta.y * cos) * scale
}

/**
 * Turn handle above a text or sticker. It sits inside the item's turned layer, so a drag along its
 * own x is a move around the item's centre: the angle follows the finger (before, 0.8° per pixel
 * whatever the item's size, and it ran away from the finger on small items).
 */
@Composable
private fun RotationHandle(
    accent: Color,
    rotationDeg: Float,
    itemHeightPx: Float,
    key: Any,
    onRotate: (Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // The knob's centre is 20 dp above the item's top edge.
    val radiusPx = (itemHeightPx / 2f + with(density) { 20.dp.toPx() }).coerceAtLeast(1f)
    val currentRadius by rememberUpdatedState(radiusPx)
    Box(
        modifier = modifier.offset(y = (-32).dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(1.5.dp)
                .height(16.dp)
                .offset(y = 12.dp)
                .background(accent),
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0xFF0F172A))
                .border(1.5.dp, accent, CircleShape)
                .pointerInput(key) {
                    detectDragGestures(
                        onDragEnd = onEnd,
                        onDragCancel = onEnd,
                    ) { change, dragAmount ->
                        change.consume()
                        onRotate(Math.toDegrees((dragAmount.x / currentRadius).toDouble()).toFloat())
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.RotateRight,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
        }
        if (rotationDeg != 0f) {
            Box(
                modifier = Modifier
                    .offset(y = (-18).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xEE0F172A))
                    .border(0.5.dp, accent, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    "${rotationDeg.roundToInt()}°",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
