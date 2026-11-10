package dev.ander.alexet.ui.gallery

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ander.alexet.R
import dev.ander.alexet.data.QuickEdit
import dev.ander.alexet.data.QuickEditSettings
import dev.ander.alexet.data.QuickEditSource
import dev.ander.alexet.data.videoeditor.ExportResolution
import dev.ander.alexet.data.videoeditor.VideoExportEngine
import dev.ander.alexet.data.videoeditor.VideoProjectAspectRatio
import dev.ander.alexet.ui.components.MediaThumbnail
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ButtonShape = RoundedCornerShape(12.dp)

/** Megabytes as the file details count them (1 MB = 1024 × 1024 bytes, see [formatFileSize]). */
private const val BYTES_PER_MB = 1024L * 1024L

/**
 * "Quick edit": the quality, a cut (videos), a quarter turn and proportions of one photo or
 * video, saved at once. A shortcut, not a replacement for the editors. The preview stays on top;
 * the options scroll under it. Saving closes the popup (it goes on in the background).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickEditDialog(
    source: QuickEditSource,
    photoBytes: suspend (QuickEditSettings) -> Long?,
    fitPhoto: suspend (QuickEditSettings, Long) -> QuickEdit.Fit<Float>?,
    onSave: (QuickEditSettings, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val isVideo = source.media.isVideo
    var settings by remember(source.media.id) { mutableStateOf(QuickEditSettings()) }
    var askSaveAs by remember { mutableStateOf(false) }
    var targetText by remember(source.media.id) { mutableStateOf("") }
    var fitting by remember { mutableStateOf(false) }
    var fitMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The size after saving: a video's as the export reckons it, a photo's encoded (exact).
    var photoSize by remember(source.media.id) { mutableStateOf<Long?>(null) }
    var counting by remember { mutableStateOf(false) }
    if (!isVideo) {
        LaunchedEffect(settings) {
            counting = true
            delay(250)
            photoSize = photoBytes(settings)
            counting = false
        }
    }
    val videoSize = if (isVideo) {
        remember(settings) {
            VideoExportEngine.estimatedSizeBytes(QuickEdit.videoProject(source, settings), QuickEdit.videoConfig(source, settings))
        }
    } else null
    val estimate = if (isVideo) videoSize else photoSize.takeIf { !counting }

    fun fit() {
        val mb = targetText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 } ?: return
        var target = (mb * BYTES_PER_MB).toLong()
        // Bigger than the original makes no sense: the original's size is the most.
        val original = source.media.sizeBytes
        val capped = original in 1..target
        if (capped) target = original
        fitting = true
        fitMessage = null
        scope.launch {
            val reached = if (isVideo) {
                // The resolution: lowered only when even the lowest quality is too big. The
                // bitrate is then set exactly for the size.
                val base = settings.copy(targetBytes = null)
                QuickEdit.fitToSize(target, QuickEdit.videoResolutions(source, base)) { resolution, quality ->
                    val trial = base.copy(resolution = resolution, quality = quality)
                    VideoExportEngine.estimatedSizeBytes(QuickEdit.videoProject(source, trial), QuickEdit.videoConfig(source, trial))
                        ?: Long.MAX_VALUE
                }?.let { fit ->
                    settings = base.copy(
                        quality = fit.quality,
                        resolution = fit.option,
                        scale = 1f,
                        targetBytes = target.takeIf { fit.reached },
                    )
                    fit.reached
                }
            } else {
                fitPhoto(settings, target)?.let { fit ->
                    settings = settings.copy(quality = fit.quality, scale = fit.option)
                    fit.reached
                }
            }
            fitMessage = when {
                capped -> context.getString(R.string.quick_edit_fit_original, formatFileSize(original))
                reached == false -> context.getString(R.string.quick_edit_fit_unreachable, formatFileSize(target))
                else -> null
            }
            fitting = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(vertical = 20.dp)) {
                Text(
                    stringResource(R.string.quick_edit),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    source.media.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(12.dp))
                // Always in sight; the options below scroll under its lower edge.
                val previewHeight = (LocalConfiguration.current.screenHeightDp * 0.28f).coerceIn(120f, 220f).dp
                Box(Modifier.padding(horizontal = 24.dp)) {
                    QuickEditPreview(source, settings, height = previewHeight) { dx, dy ->
                        settings = settings.copy(
                            panX = (settings.panX + dx).coerceIn(-1f, 1f),
                            panY = (settings.panY + dy).coerceIn(-1f, 1f),
                        )
                    }
                }
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .then(rememberEdgeBlur(scroll))
                        .verticalScroll(scroll)
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    // Sections apart, a thin line between each two.
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Quality
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionTitle(stringResource(R.string.quick_edit_quality), "${settings.quality}%")
                        Slider(
                            value = settings.quality.toFloat(),
                            onValueChange = {
                                // A quality picked by hand: back to the full size, no fitted size.
                                settings = settings.copy(
                                    quality = it.roundToInt(),
                                    scale = 1f,
                                    resolution = ExportResolution.ORIGINAL,
                                    targetBytes = null,
                                )
                                fitMessage = null
                            },
                            valueRange = QuickEdit.MIN_QUALITY.toFloat()..QuickEdit.MAX_QUALITY.toFloat(),
                            steps = QuickEdit.MAX_QUALITY - QuickEdit.MIN_QUALITY - 1,
                            enabled = !fitting,
                        )
                        val now = formatFileSize(source.media.sizeBytes)
                        Hint(
                            if (estimate != null) {
                                stringResource(R.string.quick_edit_size, now, formatFileSize(estimate))
                            } else {
                                stringResource(R.string.quick_edit_size_counting, now)
                            },
                        )
                        if (settings.scale < 0.999f) {
                            Hint(stringResource(R.string.quick_edit_scaled, (settings.scale * 100).roundToInt()))
                        }
                        if (settings.resolution != ExportResolution.ORIGINAL) {
                            Hint(stringResource(R.string.quick_edit_resolution, settings.resolution.label))
                        }
                        if (!isVideo && QuickEdit.photoExtension(source.media, QuickEdit.MAX_QUALITY) == "png" &&
                            settings.quality < QuickEdit.MAX_QUALITY
                        ) {
                            Hint(stringResource(R.string.quick_edit_png_as_jpg))
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = targetText,
                                onValueChange = { text -> targetText = text.filter { it.isDigit() || it == ',' || it == '.' }.take(7) },
                                label = { Text(stringResource(R.string.quick_edit_target_mb)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { fit() }),
                                enabled = !fitting,
                                shape = ButtonShape,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = { fit() },
                                enabled = !fitting && targetText.isNotBlank(),
                                shape = ButtonShape,
                            ) {
                                if (fitting) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(stringResource(R.string.quick_edit_fit))
                                }
                            }
                        }
                        fitMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    SectionDivider()

                    // Turn
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.quick_edit_rotate), "${settings.rotation}°")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    settings = settings.copy(rotation = Math.floorMod(settings.rotation - 90, 360), panX = 0f, panY = 0f)
                                },
                                shape = ButtonShape,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.RotateLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("−90°")
                            }
                            OutlinedButton(
                                onClick = {
                                    settings = settings.copy(rotation = Math.floorMod(settings.rotation + 90, 360), panX = 0f, panY = 0f)
                                },
                                shape = ButtonShape,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("+90°")
                            }
                        }
                    }
                    SectionDivider()

                    // Proportions
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionTitle(stringResource(R.string.quick_edit_ratio))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VideoProjectAspectRatio.entries.forEach { ratio ->
                                FilterChip(
                                    selected = settings.ratio == ratio,
                                    onClick = { settings = settings.copy(ratio = ratio, panX = 0f, panY = 0f) },
                                    label = { Text(stringResource(ratio.labelRes)) },
                                )
                            }
                        }
                        if (settings.ratio != VideoProjectAspectRatio.ORIGINAL) {
                            Hint(stringResource(R.string.quick_edit_drag_hint))
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss, shape = ButtonShape) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(onClick = { askSaveAs = true }, enabled = !fitting, shape = ButtonShape) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }

    if (askSaveAs) {
        AlertDialog(
            onDismissRequest = { askSaveAs = false },
            title = { Text(stringResource(R.string.quick_edit_save_as)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askSaveAs = false
                        onSave(settings, false)
                    },
                    shape = ButtonShape,
                ) {
                    Text(stringResource(R.string.quick_edit_save_copy))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        askSaveAs = false
                        onSave(settings, true)
                    },
                    shape = ButtonShape,
                ) {
                    Text(stringResource(R.string.quick_edit_save_replace))
                }
            },
        )
    }
}

/**
 * Options scrolled up go under the preview: blurred and faded at the top edge (blur from
 * Android 13, fading only before). Nothing changes until the list is scrolled.
 */
@Composable
private fun rememberEdgeBlur(scroll: ScrollState): Modifier {
    val bandPx = with(LocalDensity.current) { 44.dp.toPx() }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(EDGE_BLUR_SHADER) }
        return Modifier.graphicsLayer {
            val amount = (scroll.value / bandPx).coerceIn(0f, 1f)
            renderEffect = if (amount <= 0f) {
                null
            } else {
                shader.setFloatUniform("band", bandPx)
                shader.setFloatUniform("amount", amount)
                android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
            }
        }
    }
    return Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val amount = (scroll.value / bandPx).coerceIn(0f, 1f)
            if (amount > 0f) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 1f - amount),
                        1f to Color.Black,
                        startY = 0f,
                        endY = bandPx,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

/** Blur growing toward the top edge over [band] px, fading out there too ([amount] 0..1). */
private const val EDGE_BLUR_SHADER = """
uniform shader content;
uniform float band;
uniform float amount;

half4 main(float2 p) {
    float t = clamp(1.0 - p.y / band, 0.0, 1.0) * amount;
    if (t <= 0.0) {
        return content.eval(p);
    }
    float stride = t * 5.0;
    half4 sum = half4(0.0);
    for (int i = -2; i <= 2; i++) {
        for (int j = -2; j <= 2; j++) {
            sum += content.eval(p + float2(float(i), float(j)) * stride);
        }
    }
    return (sum / 25.0) * (1.0 - t);
}
"""

/**
 * The saved frame with the picture in it, turned and moved as set. With proportions other than
 * the picture's, a drag moves the picture inside the frame (what is kept).
 */
@Composable
private fun QuickEditPreview(
    source: QuickEditSource,
    settings: QuickEditSettings,
    height: androidx.compose.ui.unit.Dp,
    onPanBy: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val frameAspect = QuickEdit.frameAspect(source.aspect, settings)
    val shown = QuickEdit.shownAspect(source.aspect, settings.rotation)
    val panBy by rememberUpdatedState(onPanBy)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val (frameW, frameH) = if (maxW / maxH > frameAspect) (maxH * frameAspect) to maxH else maxW to (maxW / frameAspect)
        val (coverW, coverH) = QuickEdit.coverSize(frameW, frameH, shown)
        val roomX = (coverW - frameW) / 2f
        val roomY = (coverH - frameH) / 2f
        Box(
            modifier = Modifier
                .size(with(density) { frameW.toDp() }, with(density) { frameH.toDp() })
                .clip(ButtonShape)
                .background(Color.Black)
                .pointerInput(roomX, roomY) {
                    if (roomX < 1f && roomY < 1f) return@pointerInput
                    detectDragGestures { change, drag ->
                        change.consume()
                        panBy(
                            if (roomX >= 1f) drag.x / roomX else 0f,
                            if (roomY >= 1f) drag.y / roomY else 0f,
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // The picture before its turn: the turned one covers the frame.
            val (imageW, imageH) = if (QuickEdit.swapsSides(settings.rotation)) coverH to coverW else coverW to coverH
            MediaThumbnail(
                media = source.media,
                modifier = Modifier
                    .requiredSize(with(density) { imageW.toDp() }, with(density) { imageH.toDp() })
                    .graphicsLayer {
                        rotationZ = settings.rotation.toFloat()
                        translationX = settings.panX * roomX
                        translationY = settings.panY * roomY
                    },
                contentScale = ContentScale.FillBounds,
                showPlayIcon = false,
                showDuration = false,
                thumbnail = false,
                shape = RectangleShape,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, value: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
