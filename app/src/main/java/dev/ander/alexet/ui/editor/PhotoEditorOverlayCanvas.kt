package dev.ander.alexet.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.hypot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import dev.ander.alexet.data.BlurConfig
import dev.ander.alexet.data.BlurType
import dev.ander.alexet.data.BrushStroke
import dev.ander.alexet.data.ImageEditEngine
import dev.ander.alexet.data.NormPoint
import dev.ander.alexet.data.StickerOverlay
import dev.ander.alexet.data.TextOverlay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Overlay canvas handling interactive Blur controls (Zdjęcia 1, 2, 3),
 * Stickers & Text with multi-touch gestures and selection indicator (Zdjęcie 4),
 * Drawing brush strokes (Zdjęcie 1), and Eyedropper Magnifier (Zdjęcie 3).
 */
@Composable
fun PhotoEditorOverlayCanvas(
    currentTab: PhotoEditorTab,
    imageRect: Rect,
    decodedBitmap: Bitmap?,
    blurConfig: BlurConfig,
    onBlurConfigChange: (BlurConfig) -> Unit,
    stickers: List<StickerOverlay>,
    texts: List<TextOverlay>,
    selectedItemId: String?,
    onSelectItem: (String?) -> Unit,
    onUpdateSticker: (StickerOverlay) -> Unit,
    onUpdateText: (TextOverlay) -> Unit,
    brushStrokes: List<BrushStroke>,
    liveStrokeTemplate: BrushStroke,
    onBrushStrokeCommit: (List<NormPoint>) -> Unit,
    isEyedropperActive: Boolean,
    onColorSampled: (Int) -> Unit,
    onEyedropperClose: () -> Unit,
    onContinuousChangeStart: () -> Unit = {},
    onContinuousChangeEnd: () -> Unit = {},
    colorFilter: ColorFilter? = null,
    liveProcessedBitmap: Bitmap? = null,
    imageRotation: Int = 0,
    imageFlipH: Boolean = false,
    imageFlipV: Boolean = false,
    colorMatrix: FloatArray? = null,
    itemSizes: MutableMap<String, IntSize> = remember { mutableStateMapOf() },
    /** Workspace view zoom and turn: the eyedropper loupe keeps its size and stays above the finger. */
    viewScale: Float = 1f,
    viewRotation: Float = 0f,
    modifier: Modifier = Modifier,
) {
    var eyedropperPosition by remember { mutableStateOf<Offset?>(null) }
    var sampledColor by remember { mutableStateOf<Color?>(null) }
    val density = LocalDensity.current

    val currentImageRect by rememberUpdatedState(imageRect)
    val currentOnContinuousChangeStart by rememberUpdatedState(onContinuousChangeStart)
    val currentOnContinuousChangeEnd by rememberUpdatedState(onContinuousChangeEnd)
    val currentOnBrushStrokeCommit by rememberUpdatedState(onBrushStrokeCommit)
    // In-progress stroke lives here: appending a point redraws this canvas only, instead of
    // copying the point list and recomposing the whole editor for every touch sample.
    val livePoints = remember { mutableStateListOf<NormPoint>() }
    // Paths of the finished strokes, built when the strokes or the image area change, not in
    // every frame of the stroke being drawn.
    val preparedStrokes = remember(brushStrokes, imageRect) { prepareBrushStrokes(brushStrokes, imageRect) }

    // Not clipped: stickers/texts may extend past the image; that part is dimmed below.
    Box(modifier = modifier.fillMaxSize()) {

        // --- 2. Stickers (Image Overlays) ---
        val renderStickersInOverlay = true
        if (renderStickersInOverlay) {
            stickers.forEach { sticker ->
                val isSelected = selectedItemId == sticker.id
                var itemSize by remember(sticker.id) { mutableStateOf(IntSize.Zero) }

                val posX = imageRect.left + sticker.normalizedX * imageRect.width
                val posY = imageRect.top + sticker.normalizedY * imageRect.height
                // Same size rule as the export (quarter of the image width), no dp clamp.
                val stickerBaseDp = if (imageRect.width > 0f) {
                    with(density) { ImageEditEngine.stickerBasePx(imageRect.width).toDp() }
                } else {
                    110.dp
                }

                Box(
                    modifier = Modifier
                        .offset {
                            val lx = posX - (itemSize.width / 2f)
                            val ly = posY - (itemSize.height / 2f)
                            IntOffset(lx.roundToInt(), ly.roundToInt())
                        }
                        .size(stickerBaseDp)
                        .onGloballyPositioned {
                            itemSize = it.size
                            itemSizes[sticker.id] = it.size
                        }
                        .graphicsLayer {
                            scaleX = sticker.scale * (if (sticker.flipH) -1f else 1f)
                            scaleY = sticker.scale * (if (sticker.flipV) -1f else 1f)
                            rotationZ = sticker.rotationDeg
                        }
                        .zIndex(if (isSelected) 12f else 5f),
                ) {
                    // No colour filter: the export draws stickers after the colour adjustments.
                    AsyncImage(
                        model = sticker.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        texts.forEach { textItem ->
            val isSelected = selectedItemId == textItem.id
            var itemSize by remember(textItem.id) { mutableStateOf(IntSize.Zero) }
            val posX = imageRect.left + textItem.normalizedX * imageRect.width
            val posY = imageRect.top + textItem.normalizedY * imageRect.height

            Box(
                modifier = Modifier
                    .offset {
                        val lx = posX - (itemSize.width / 2f)
                        val ly = posY - (itemSize.height / 2f)
                        IntOffset(lx.roundToInt(), ly.roundToInt())
                    }
                    .onGloballyPositioned {
                        itemSize = it.size
                        itemSizes[textItem.id] = it.size
                    }
                    .graphicsLayer {
                        scaleX = textItem.scale * (if (textItem.flipH) -1f else 1f)
                        scaleY = textItem.scale * (if (textItem.flipV) -1f else 1f)
                        rotationZ = textItem.rotationDeg
                    }
                    .zIndex(if (isSelected) 14f else 8f),
            ) {
                Text(
                    text = textItem.text,
                    color = Color(textItem.color),
                    // Plain font metrics like the export's StaticLayout: the theme's body style adds a
                    // fixed line height and letter spacing the saved file doesn't have.
                    style = androidx.compose.ui.text.TextStyle.Default,
                    // Relative to the image width, same formula as the export.
                    fontSize = with(density) { ImageEditEngine.textFontPx(textItem.fontSizeSp, imageRect.width).toSp() },
                    fontWeight = if (textItem.isBold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (textItem.isItalic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = when {
                        textItem.isUnderline && textItem.isStrikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                        textItem.isUnderline -> TextDecoration.Underline
                        textItem.isStrikethrough -> TextDecoration.LineThrough
                        else -> TextDecoration.None
                    },
                    textAlign = when (textItem.alignment) {
                        0 -> TextAlign.Left
                        2 -> TextAlign.Right
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.padding(8.dp),
                )
            }
        }

        // Parts of stickers/texts outside the image are not exported: show them greyed out,
        // like the area outside the crop frame.
        if (stickers.isNotEmpty() || texts.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize().zIndex(15f)) {
                val far = 20000f
                val r = imageRect
                val outside = listOf(
                    Rect(r.left - far, r.top - far, r.right + far, r.top),
                    Rect(r.left - far, r.bottom, r.right + far, r.bottom + far),
                    Rect(r.left - far, r.top, r.left, r.bottom),
                    Rect(r.right, r.top, r.right + far, r.bottom),
                )
                for (rect in outside) {
                    drawRect(Color.Gray, rect.topLeft, rect.size, blendMode = BlendMode.Saturation)
                    drawRect(Color.Black.copy(alpha = 0.55f), rect.topLeft, rect.size)
                }
            }
        }

        // Selection frame and angle badge on top of the dimming, never clipped by the image.
        val selectedSticker = stickers.firstOrNull { it.id == selectedItemId }
        val selectedText = texts.firstOrNull { it.id == selectedItemId }
        val selectedSize = selectedItemId?.let { itemSizes[it] }
        if (selectedSize != null && (selectedSticker != null || selectedText != null)) {
            val nx = selectedSticker?.normalizedX ?: selectedText!!.normalizedX
            val ny = selectedSticker?.normalizedY ?: selectedText!!.normalizedY
            val scale = selectedSticker?.scale ?: selectedText!!.scale
            val rotation = selectedSticker?.rotationDeg ?: selectedText!!.rotationDeg
            val flipH = selectedSticker?.flipH ?: selectedText!!.flipH
            val flipV = selectedSticker?.flipV ?: selectedText!!.flipV
            val posX = imageRect.left + nx * imageRect.width
            val posY = imageRect.top + ny * imageRect.height
            Box(
                modifier = Modifier
                    .offset { IntOffset((posX - selectedSize.width / 2f).roundToInt(), (posY - selectedSize.height / 2f).roundToInt()) }
                    .size(with(density) { selectedSize.width.toDp() }, with(density) { selectedSize.height.toDp() })
                    .graphicsLayer {
                        scaleX = scale * (if (flipH) -1f else 1f)
                        scaleY = scale * (if (flipV) -1f else 1f)
                        rotationZ = rotation
                    }
                    .zIndex(16f),
            ) {
                SelectionBracketsIndicator(itemScale = scale, modifier = Modifier.matchParentSize())
                val normRot = ((rotation % 360f) + 360f) % 360f
                if (normRot > 0.5f && normRot < 359.5f) {
                    val displayDeg = if (normRot > 180f) (normRot - 360f).roundToInt() else normRot.roundToInt()
                    // Counter-scaled/rotated: the badge stays upright, readable and at a fixed gap.
                    val inverseScale = 1f / scale.coerceAtLeast(0.01f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-28).dp * inverseScale)
                            .graphicsLayer {
                                rotationZ = -rotation
                                scaleX = inverseScale * (if (flipH) -1f else 1f)
                                scaleY = inverseScale * (if (flipV) -1f else 1f)
                            }
                            .background(Color(0xDD0F172A), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "$displayDeg°",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // --- 4. Brush Drawing Layer (Renders ON TOP of stickers and texts) ---
        if (currentTab == PhotoEditorTab.BRUSH && !isEyedropperActive) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(25f)
                    .pointerInput(Unit) {
                        // Stroke starts exactly at the touch point (no slop gap), a tap leaves a
                        // dot, and leaving the image follows its edge instead of cutting a chord.
                        fun norm(p: Offset, rect: Rect) = NormPoint(
                            ((p.x - rect.left) / rect.width).coerceIn(0f, 1f),
                            ((p.y - rect.top) / rect.height).coerceIn(0f, 1f),
                        )
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val rect = currentImageRect
                            if (rect.width <= 0f || rect.height <= 0f || !rect.contains(down.position)) return@awaitEachGesture
                            down.consume()
                            livePoints.clear()
                            livePoints.add(norm(down.position, rect))
                            // A second finger hands the touch to the view (zoom/turn): the stroke
                            // begun so far is dropped, not kept as a stray line.
                            var cancelled = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.isConsumed || event.changes.count { it.pressed } > 1) {
                                        cancelled = true
                                        break
                                    }
                                    if (!change.pressed) break
                                    if (change.positionChanged()) {
                                        change.consume()
                                        livePoints.add(norm(change.position, currentImageRect))
                                    }
                                }
                            } finally {
                                val points = livePoints.toList()
                                livePoints.clear()
                                if (!cancelled) currentOnBrushStrokeCommit(points)
                            }
                        }
                    },
            ) {
                drawPreparedStrokes(preparedStrokes)
                if (livePoints.isNotEmpty()) {
                    // The live list itself (read only while drawing), not a copy per frame.
                    drawBrushStrokes(listOf(liveStrokeTemplate.copy(points = livePoints)), imageRect)
                }
            }
        } else if (brushStrokes.isNotEmpty()) {
            // Render brush strokes read-only above stickers
            Canvas(modifier = Modifier.fillMaxSize().zIndex(20f)) {
                drawPreparedStrokes(preparedStrokes)
            }
        }

        // GAUSSIAN has no focus area: an overlay there would only swallow touches.
        if (currentTab == PhotoEditorTab.BLUR && blurConfig.type != BlurType.NONE && blurConfig.type != BlurType.GAUSSIAN) {
            InteractiveBlurOverlay(
                imageRect = imageRect,
                config = blurConfig,
                onConfigChange = onBlurConfigChange,
                onGestureStart = onContinuousChangeStart,
                onGestureEnd = onContinuousChangeEnd,
                modifier = Modifier.fillMaxSize().zIndex(30f),
            )
        }

        if (isEyedropperActive) {
            // Fresh state on every activation: no stale colour or loupe position from last time.
            LaunchedEffect(Unit) {
                eyedropperPosition = null
                sampledColor = null
            }
            val sampleSource = liveProcessedBitmap?.takeIf { !it.isRecycled } ?: decodedBitmap?.takeIf { !it.isRecycled }
            val currentSampleSource by rememberUpdatedState(sampleSource)
            val currentColorMatrix by rememberUpdatedState(colorMatrix)
            val currentOnColorSampled by rememberUpdatedState(onColorSampled)
            val currentOnEyedropperClose by rememberUpdatedState(onEyedropperClose)
            val currentRotation by rememberUpdatedState(imageRotation)
            val currentFlipH by rememberUpdatedState(imageFlipH)
            val currentFlipV by rememberUpdatedState(imageFlipV)

            // Samples what is actually on screen: undoes rotation/flip, uses the processed preview
            // and applies the colour matrix of the parameter sliders.
            fun sampleAt(pos: Offset): Color? {
                val bmp = currentSampleSource ?: return null
                val norm = displayToSourceNorm(pos, currentImageRect, currentRotation, currentFlipH, currentFlipV) ?: return null
                var colorInt = ImageEditEngine.sampleBitmapPixelColor(bmp, norm.x, norm.y)
                currentColorMatrix?.let { colorInt = applyColorMatrix(colorInt, it) }
                return Color(colorInt)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Above stickers (5-14), brush strokes (20-25) and blur controls (30).
                    .zIndex(40f)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var picked: Color? = null
                            eyedropperPosition = down.position
                            sampleAt(down.position)?.let { picked = it; sampledColor = it }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed || event.changes.count { it.pressed } > 1) {
                                    // Two fingers move the view: nothing is picked.
                                    picked = null
                                    eyedropperPosition = null
                                    break
                                }
                                if (!change.pressed) break
                                change.consume()
                                eyedropperPosition = change.position
                                sampleAt(change.position)?.let { picked = it; sampledColor = it }
                            }
                            // Single tap picks too; releasing outside the image keeps the last valid sample.
                            picked?.let { c ->
                                currentOnColorSampled(c.toArgb())
                                currentOnEyedropperClose()
                            }
                        }
                    },
            ) {
                eyedropperPosition?.let { pos ->
                    val loupeSizeDp = 110.dp
                    val loupeRadiusPx = with(density) { (loupeSizeDp / 2).toPx() }
                    val currentSampleColor = sampledColor ?: Color.White
                    // Drawn inside the zoomed/turned view: sizes divided by the zoom, "above the
                    // finger" turned back to screen up.
                    val k = 1f / viewScale.coerceAtLeast(0.01f)
                    val radius = loupeRadiusPx * k
                    var loupeCenter = pos + rotatePoint(Offset(0f, -with(density) { 70.dp.toPx() } * k), -viewRotation)
                    if (abs(viewRotation) < 0.5f) {
                        loupeCenter = Offset(
                            loupeCenter.x.coerceAtMost(imageRect.right - radius).coerceAtLeast(imageRect.left + radius),
                            loupeCenter.y.coerceAtLeast(radius),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .offset { IntOffset((loupeCenter.x - loupeRadiusPx).roundToInt(), (loupeCenter.y - loupeRadiusPx).roundToInt()) }
                            .graphicsLayer {
                                scaleX = k
                                scaleY = k
                            }
                            .size(loupeSizeDp)
                            .clip(CircleShape)
                            .border(5.dp, currentSampleColor, CircleShape)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val bmp = sampleSource
                            val norm = displayToSourceNorm(pos, imageRect, imageRotation, imageFlipH, imageFlipV)
                            if (bmp != null && norm != null) {
                                val centerPx = (norm.x * bmp.width).roundToInt()
                                val centerPy = (norm.y * bmp.height).roundToInt()
                                val sampleRadius = 8
                                val srcRect = android.graphics.Rect(
                                    (centerPx - sampleRadius).coerceAtLeast(0),
                                    (centerPy - sampleRadius).coerceAtLeast(0),
                                    (centerPx + sampleRadius).coerceAtMost(bmp.width),
                                    (centerPy + sampleRadius).coerceAtMost(bmp.height),
                                )
                                val dstRect = android.graphics.Rect(0, 0, size.width.toInt(), size.height.toInt())
                                val paint = android.graphics.Paint().apply {
                                    isFilterBitmap = false
                                    colorMatrix?.let { setColorFilter(android.graphics.ColorMatrixColorFilter(it)) }
                                }
                                // Same orientation as the image on screen.
                                withTransform({
                                    rotate(imageRotation.toFloat(), pivot = center)
                                    scale(if (imageFlipH) -1f else 1f, if (imageFlipV) -1f else 1f, pivot = center)
                                }) {
                                    drawContext.canvas.nativeCanvas.drawBitmap(bmp, srcRect, dstRect, paint)
                                }
                            }

                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
                            drawCircle(Color.Black, radius = 7.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.dp.toPx()))

                            val crossLen = 14.dp.toPx()
                            drawLine(Color.White, Offset(cx - crossLen, cy), Offset(cx - 7.dp.toPx(), cy), strokeWidth = 2.dp.toPx())
                            drawLine(Color.White, Offset(cx + 7.dp.toPx(), cy), Offset(cx + crossLen, cy), strokeWidth = 2.dp.toPx())
                            drawLine(Color.White, Offset(cx, cy - crossLen), Offset(cx, cy - 7.dp.toPx()), strokeWidth = 2.dp.toPx())
                            drawLine(Color.White, Offset(cx, cy + 7.dp.toPx()), Offset(cx, cy + crossLen), strokeWidth = 2.dp.toPx())
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SelectionBracketsIndicator(itemScale: Float = 1f, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        // Drawn inside the item's scaled layer: divide by the scale to keep dp sizes on screen.
        val k = 1f / itemScale.coerceAtLeast(0.01f)
        val w = size.width
        val h = size.height
        val cornerLen = 14.dp.toPx() * k
        val strokeW = 3.dp.toPx() * k
        val cornerColor = Color.White
        val offsetDist = 6.dp.toPx() * k
        val l = -offsetDist
        val t = -offsetDist
        val r = w + offsetDist
        val b = h + offsetDist
        drawRect(
            color = Color.White.copy(alpha = 0.8f),
            topLeft = Offset(0f, 0f),
            size = Size(w, h),
            style = Stroke(1.5.dp.toPx() * k),
        )
        drawLine(cornerColor, Offset(l, t), Offset(l + cornerLen, t), strokeWidth = strokeW)
        drawLine(cornerColor, Offset(l, t), Offset(l, t + cornerLen), strokeWidth = strokeW)
        drawLine(cornerColor, Offset(r, t), Offset(r - cornerLen, t), strokeWidth = strokeW)
        drawLine(cornerColor, Offset(r, t), Offset(r, t + cornerLen), strokeWidth = strokeW)
        drawLine(cornerColor, Offset(l, b), Offset(l + cornerLen, b), strokeWidth = strokeW)
        drawLine(cornerColor, Offset(l, b), Offset(l, b - cornerLen), strokeWidth = strokeW)
        drawLine(cornerColor, Offset(r, b), Offset(r - cornerLen, b), strokeWidth = strokeW)
        drawLine(cornerColor, Offset(r, b), Offset(r, b - cornerLen), strokeWidth = strokeW)
    }
}

private enum class BlurDragHandle {
    NONE,
    CENTER,
    SIZE,
    DIRECTION,
}

@Composable
fun InteractiveBlurOverlay(
    imageRect: Rect,
    config: BlurConfig,
    onConfigChange: (BlurConfig) -> Unit,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val currentOnGestureStart by rememberUpdatedState(onGestureStart)
    val currentOnGestureEnd by rememberUpdatedState(onGestureEnd)
    val currentConfig by rememberUpdatedState(config)
    val currentOnConfigChange by rememberUpdatedState(onConfigChange)
    val currentImageRect by rememberUpdatedState(imageRect)
    val cx = imageRect.left + config.focusCenter.x * imageRect.width
    val cy = imageRect.top + config.focusCenter.y * imageRect.height
    val w = imageRect.width.roundToInt().coerceAtLeast(1)
    val h = imageRect.height.roundToInt().coerceAtLeast(1)
    val minDim = minOf(imageRect.width, imageRect.height)
    // Same geometry as ImageEditEngine.applyBlur: the guides mark where blur starts / is full.
    val focusPx = ImageEditEngine.blurFocusPx(config, w, h)
    val transitionPx = ImageEditEngine.blurTransitionPx(config, w, h)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val knobTouch = 24.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cfg0 = currentConfig
                    val rect0 = currentImageRect
                    val min0 = minOf(rect0.width, rect0.height)
                    val center0 = Offset(rect0.left + cfg0.focusCenter.x * rect0.width, rect0.top + cfg0.focusCenter.y * rect0.height)
                    val knob = center0 + Offset(ImageEditEngine.blurFocusPx(cfg0, rect0.width.roundToInt().coerceAtLeast(1), rect0.height.roundToInt().coerceAtLeast(1)), 0f)
                    if (cfg0.type == BlurType.RADIAL && min0 > 0f && (down.position - knob).getDistance() <= knobTouch) {
                        // Radius handle: the circle follows the finger.
                        down.consume()
                        currentOnGestureStart()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val size = ((change.position - center0).getDistance() * 2f / min0)
                                    .coerceIn(ImageEditEngine.BLUR_FOCUS_MIN, ImageEditEngine.BLUR_FOCUS_MAX)
                                currentOnConfigChange(currentConfig.copy(focusSize = size))
                            }
                        } finally {
                            currentOnGestureEnd()
                        }
                        return@awaitEachGesture
                    }
                    // One gesture = one undo entry.
                    trackTransform(onStart = currentOnGestureStart, onEnd = currentOnGestureEnd) { pan, zoom, rotation ->
                        val cfg = currentConfig
                        val rect = currentImageRect
                        var updated = cfg
                        var changed = false

                        if (abs(rotation) > 0.1f && (cfg.type == BlurType.MIRROR || cfg.type == BlurType.LINEAR)) {
                            val newAngle = (cfg.focusAngle + rotation) % 360f
                            updated = updated.copy(focusAngle = if (newAngle < 0f) newAngle + 360f else newAngle)
                            changed = true
                        }
                        // Pinch = where the blur starts, for every focus type (linear included).
                        if (abs(zoom - 1f) > 0.0005f) {
                            val newSize = (cfg.focusSize * zoom).coerceIn(ImageEditEngine.BLUR_FOCUS_MIN, ImageEditEngine.BLUR_FOCUS_MAX)
                            updated = updated.copy(focusSize = newSize)
                            changed = true
                        }
                        if (pan != Offset.Zero && rect.width > 0f && rect.height > 0f) {
                            val newX = (updated.focusCenter.x + pan.x / rect.width).coerceIn(0.02f, 0.98f)
                            val newY = (updated.focusCenter.y + pan.y / rect.height).coerceIn(0.02f, 0.98f)
                            updated = updated.copy(focusCenter = NormPoint(newX, newY))
                            changed = true
                        }
                        if (changed) {
                            currentOnConfigChange(updated)
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeW = 2.dp.toPx()
            val crossLen = 12.dp.toPx()
            val color = Color.White
            val shadow = Color.Black.copy(alpha = 0.45f)
            val arrowW = 10.dp.toPx()
            val arrowH = 8.dp.toPx()
            val dashed = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))

            // Chevron pointing away from the sharp area, tip at distance [at] (negative = up).
            fun chevron(at: Float, up: Boolean) {
                val base = if (up) at + arrowH else at - arrowH
                drawLine(color, Offset(cx - arrowW, base), Offset(cx, at), strokeWidth = 2.5.dp.toPx())
                drawLine(color, Offset(cx + arrowW, base), Offset(cx, at), strokeWidth = 2.5.dp.toPx())
            }

            clipRect(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom) {
                when (config.type) {
                    BlurType.RADIAL -> {
                        drawCircle(color = color, radius = focusPx, center = Offset(cx, cy), style = Stroke(strokeW))
                        drawLine(color, Offset(cx - crossLen, cy), Offset(cx + crossLen, cy), strokeWidth = strokeW)
                        drawLine(color, Offset(cx, cy - crossLen), Offset(cx, cy + crossLen), strokeWidth = strokeW)
                        // Corners on the full-blur ring (top-left and bottom-right), pointing outward.
                        val hLen = 16.dp.toPx()
                        val d = (focusPx + transitionPx) * 0.7071f
                        val tl = Offset(cx - d, cy - d)
                        drawLine(color, tl, tl + Offset(hLen, 0f), strokeWidth = 3.dp.toPx())
                        drawLine(color, tl, tl + Offset(0f, hLen), strokeWidth = 3.dp.toPx())
                        val br = Offset(cx + d, cy + d)
                        drawLine(color, br, br - Offset(hLen, 0f), strokeWidth = 3.dp.toPx())
                        drawLine(color, br, br - Offset(0f, hLen), strokeWidth = 3.dp.toPx())
                        // Radius handle on the circle.
                        val knob = Offset(cx + focusPx, cy)
                        drawCircle(shadow, radius = 9.dp.toPx(), center = knob)
                        drawCircle(color, radius = 7.dp.toPx(), center = knob)
                    }
                    BlurType.MIRROR -> {
                        val extW = maxOf(imageRect.width, imageRect.height) * 2f
                        rotate(config.focusAngle, pivot = Offset(cx, cy)) {
                            drawLine(color, Offset(cx - crossLen, cy), Offset(cx + crossLen, cy), strokeWidth = strokeW)
                            drawLine(color, Offset(cx, cy - crossLen), Offset(cx, cy + crossLen), strokeWidth = strokeW)
                            val topY = cy - focusPx
                            val botY = cy + focusPx
                            drawLine(color, Offset(cx - extW, topY), Offset(cx + extW, topY), strokeWidth = strokeW)
                            drawLine(color, Offset(cx - extW, botY), Offset(cx + extW, botY), strokeWidth = strokeW)
                            // Full blur beyond these arrows.
                            chevron(topY - transitionPx, up = true)
                            chevron(botY + transitionPx, up = false)
                        }
                    }
                    BlurType.LINEAR -> {
                        val extW = maxOf(imageRect.width, imageRect.height) * 2f
                        rotate(config.focusAngle, pivot = Offset(cx, cy)) {
                            drawLine(color, Offset(cx - extW, cy), Offset(cx + extW, cy), strokeWidth = strokeW)
                            drawLine(color, Offset(cx, cy - 8.dp.toPx()), Offset(cx, cy + 8.dp.toPx()), strokeWidth = 3.dp.toPx())
                            // Blur starts at the dashed line and is full beyond the arrow.
                            val startY = cy - focusPx
                            drawLine(color, Offset(cx - extW, startY), Offset(cx + extW, startY), strokeWidth = strokeW, pathEffect = dashed)
                            chevron(startY - transitionPx, up = true)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

private class OverlayHit(
    val id: String,
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotationDeg: Float,
)

/** Rotates [p] by [degrees] clockwise in screen (y-down) coordinates, like graphicsLayer.rotationZ. */
internal fun rotatePoint(p: Offset, degrees: Float): Offset {
    val rad = degrees.toDouble() * PI / 180.0
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    return Offset(p.x * c - p.y * s, p.x * s + p.y * c)
}

/**
 * Maps a point on the displayed (rotated/flipped) image back to normalized coordinates of the
 * unrotated source bitmap. The image layer applies flip (scale) first, then rotation, around the
 * centre, so the inverse is: un-rotate, then un-flip. Returns null outside the image.
 */
internal fun displayToSourceNorm(
    point: Offset,
    displayRect: Rect,
    rotationDeg: Int,
    flipH: Boolean,
    flipV: Boolean,
): NormPoint? {
    if (displayRect.width <= 0f || displayRect.height <= 0f || !displayRect.contains(point)) return null
    var local = rotatePoint(point - displayRect.center, -rotationDeg.toFloat())
    if (flipH) local = local.copy(x = -local.x)
    if (flipV) local = local.copy(y = -local.y)
    val quarterTurn = ((rotationDeg % 180) + 180) % 180 == 90
    val boxW = if (quarterTurn) displayRect.height else displayRect.width
    val boxH = if (quarterTurn) displayRect.width else displayRect.height
    val nx = local.x / boxW + 0.5f
    val ny = local.y / boxH + 0.5f
    if (nx !in 0f..1f || ny !in 0f..1f) return null
    return NormPoint(nx, ny)
}

/** Applies an android.graphics.ColorMatrix-style 4x5 array (offsets in 0..255) to one ARGB pixel. */
internal fun applyColorMatrix(argb: Int, m: FloatArray): Int {
    val r = (argb shr 16 and 0xFF).toFloat()
    val g = (argb shr 8 and 0xFF).toFloat()
    val b = (argb and 0xFF).toFloat()
    val a = (argb ushr 24).toFloat()
    fun ch(row: Int) = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 3] * a + m[row * 5 + 4])
        .roundToInt().coerceIn(0, 255)
    return (ch(3) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
}

/**
 * Transform gesture after the first down (touch slop, then pan/zoom/rotation deltas).
 * [onStart]/[onEnd] bracket the gesture exactly once, so it becomes a single undo entry
 * (detectTransformGestures never returns, which left undo entries open).
 */
private suspend fun AwaitPointerEventScope.trackTransform(
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onTransform: (pan: Offset, zoom: Float, rotation: Float) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    var pastSlop = false
    var started = false
    var zoomAcc = 1f
    var rotationAcc = 0f
    var panAcc = Offset.Zero
    try {
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            // Taken before this handler saw it (the workspace view gesture): stop here.
            if (event.changes.any { it.isConsumed }) break
            val zoom = event.calculateZoom()
            val rotation = event.calculateRotation()
            val pan = event.calculatePan()
            if (!pastSlop) {
                zoomAcc *= zoom
                rotationAcc += rotation
                panAcc += pan
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                pastSlop = abs(1f - zoomAcc) * centroidSize > slop ||
                    abs(rotationAcc * PI.toFloat() * centroidSize / 180f) > slop ||
                    panAcc.getDistance() > slop
            }
            if (pastSlop) {
                if (!started) {
                    started = true
                    onStart()
                }
                if (zoom != 1f || rotation != 0f || pan != Offset.Zero) onTransform(pan, zoom, rotation)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    } finally {
        if (started) onEnd()
    }
}

/** One stroke ready to draw: a path (or a dot for a single point), its colour and width. */
private class PreparedStroke(
    val path: androidx.compose.ui.graphics.Path?,
    val dot: Offset?,
    val color: Color,
    val width: Float,
)

private fun prepareBrushStrokes(strokes: List<BrushStroke>, imageRect: Rect): List<PreparedStroke> =
    strokes.mapNotNull { stroke ->
        if (stroke.points.isEmpty()) return@mapNotNull null
        val color = Color(stroke.color).let { it.copy(alpha = it.alpha * stroke.alpha) }
        val strokeW = ImageEditEngine.brushWidthPx(stroke.strokeWidthPx, imageRect.width)
        fun at(p: NormPoint) = Offset(imageRect.left + p.x * imageRect.width, imageRect.top + p.y * imageRect.height)
        if (stroke.points.size == 1) {
            PreparedStroke(null, at(stroke.points[0]), color, strokeW)
        } else {
            val path = androidx.compose.ui.graphics.Path().apply {
                val p0 = at(stroke.points[0])
                moveTo(p0.x, p0.y)
                for (k in 1 until stroke.points.size) {
                    val p = at(stroke.points[k])
                    lineTo(p.x, p.y)
                }
            }
            PreparedStroke(path, null, color, strokeW)
        }
    }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPreparedStrokes(strokes: List<PreparedStroke>) {
    for (stroke in strokes) {
        if (stroke.dot != null) {
            drawCircle(color = stroke.color, radius = stroke.width / 2f, center = stroke.dot)
            continue
        }
        val path = stroke.path ?: continue
        drawPath(
            path = path,
            color = stroke.color,
            style = Stroke(
                width = stroke.width,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

/** Brush strokes as the export draws them: one path per stroke, width relative to the image. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBrushStrokes(strokes: List<BrushStroke>, imageRect: Rect) {
    for (stroke in strokes) {
        if (stroke.points.isEmpty()) continue
        val color = Color(stroke.color).let { it.copy(alpha = it.alpha * stroke.alpha) }
        val strokeW = ImageEditEngine.brushWidthPx(stroke.strokeWidthPx, imageRect.width)
        fun at(p: NormPoint) = Offset(imageRect.left + p.x * imageRect.width, imageRect.top + p.y * imageRect.height)
        if (stroke.points.size == 1) {
            drawCircle(color = color, radius = strokeW / 2f, center = at(stroke.points[0]))
            continue
        }
        val path = androidx.compose.ui.graphics.Path().apply {
            val p0 = at(stroke.points[0])
            moveTo(p0.x, p0.y)
            for (k in 1 until stroke.points.size) {
                val p = at(stroke.points[k])
                lineTo(p.x, p.y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeW,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

/**
 * Sticker/text gestures for the whole editor workspace (not just the image rectangle), so an item
 * can be grabbed by its part hanging outside the image. [toImage] maps a workspace pointer position
 * (given the workspace size) into overlay/image coordinates; [zoom] is the current workspace zoom.
 * One handler decides for all items: topmost item under the finger, else the selected one; pan is
 * converted to image space so the item follows the finger at any zoom, rotation or scale.
 */
@Composable
fun rememberOverlayItemGestures(
    active: Boolean,
    currentTab: PhotoEditorTab,
    stickers: List<StickerOverlay>,
    texts: List<TextOverlay>,
    selectedItemId: String?,
    onSelectItem: (String?) -> Unit,
    onUpdateSticker: (StickerOverlay) -> Unit,
    onUpdateText: (TextOverlay) -> Unit,
    onContinuousChangeStart: () -> Unit,
    onContinuousChangeEnd: () -> Unit,
    imageRect: Rect,
    itemSizes: Map<String, IntSize>,
    toImage: (Offset, IntSize) -> Offset,
    zoom: () -> Float,
    /** Turn of the workspace view, degrees clockwise. */
    viewRotation: () -> Float = { 0f },
): Modifier {
    val currentStickers by rememberUpdatedState(stickers)
    val currentTexts by rememberUpdatedState(texts)
    val currentSelectedId by rememberUpdatedState(selectedItemId)
    val currentOnSelectItem by rememberUpdatedState(onSelectItem)
    val currentOnUpdateSticker by rememberUpdatedState(onUpdateSticker)
    val currentOnUpdateText by rememberUpdatedState(onUpdateText)
    val currentOnStart by rememberUpdatedState(onContinuousChangeStart)
    val currentOnEnd by rememberUpdatedState(onContinuousChangeEnd)
    val currentImageRect by rememberUpdatedState(imageRect)
    val currentToImage by rememberUpdatedState(toImage)
    val currentZoom by rememberUpdatedState(zoom)
    val currentViewRotation by rememberUpdatedState(viewRotation)
    if (!active) return Modifier

    return Modifier.pointerInput(currentTab) {
        val touchPadding = 12.dp.toPx()
        fun hitTest(point: Offset): String? {
            val rect = currentImageRect
            val candidates = if (currentTab == PhotoEditorTab.STICKERS) {
                currentStickers.map { OverlayHit(it.id, it.normalizedX, it.normalizedY, it.scale, it.rotationDeg) }
            } else {
                currentTexts.map { OverlayHit(it.id, it.normalizedX, it.normalizedY, it.scale, it.rotationDeg) }
            }
            // Selected item is drawn on top, then later items above earlier ones.
            val ordered = candidates.filter { it.id == currentSelectedId } +
                candidates.filter { it.id != currentSelectedId }.reversed()
            val z = currentZoom().coerceAtLeast(0.01f)
            return ordered.firstOrNull { item ->
                val size = itemSizes[item.id] ?: return@firstOrNull false
                val center = Offset(rect.left + item.x * rect.width, rect.top + item.y * rect.height)
                val local = rotatePoint(point - center, -item.rotationDeg)
                val scale = item.scale.coerceAtLeast(0.01f)
                val pad = touchPadding / z
                abs(local.x) <= size.width * scale / 2f + pad &&
                    abs(local.y) <= size.height * scale / 2f + pad
            }?.id
        }

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val hitId = hitTest(currentToImage(down.position, size))
            val targetId = hitId ?: currentSelectedId ?: return@awaitEachGesture
            if (hitId != null) {
                // Keeps the workspace tap handler from deselecting what was just picked.
                down.consume()
                if (hitId != currentSelectedId) currentOnSelectItem(hitId)
            }
            trackTransform(onStart = currentOnStart, onEnd = currentOnEnd) { screenPan, zoomDelta, rotation ->
                val rect = currentImageRect
                val pan = rotatePoint(screenPan, -currentViewRotation()) / currentZoom().coerceAtLeast(0.01f)
                val s = currentStickers.find { it.id == targetId }
                if (s != null) {
                    currentOnUpdateSticker(
                        s.copy(
                            scale = (s.scale * zoomDelta).coerceIn(0.2f, 6f),
                            rotationDeg = (s.rotationDeg + rotation) % 360f,
                            normalizedX = if (rect.width > 0f) (s.normalizedX + pan.x / rect.width).coerceIn(0f, 1f) else s.normalizedX,
                            normalizedY = if (rect.height > 0f) (s.normalizedY + pan.y / rect.height).coerceIn(0f, 1f) else s.normalizedY,
                        ),
                    )
                } else {
                    val t = currentTexts.find { it.id == targetId } ?: return@trackTransform
                    currentOnUpdateText(
                        t.copy(
                            scale = (t.scale * zoomDelta).coerceIn(0.2f, 6f),
                            rotationDeg = (t.rotationDeg + rotation) % 360f,
                            normalizedX = if (rect.width > 0f) (t.normalizedX + pan.x / rect.width).coerceIn(0f, 1f) else t.normalizedX,
                            normalizedY = if (rect.height > 0f) (t.normalizedY + pan.y / rect.height).coerceIn(0f, 1f) else t.normalizedY,
                        ),
                    )
                }
            }
        }
    }
}

