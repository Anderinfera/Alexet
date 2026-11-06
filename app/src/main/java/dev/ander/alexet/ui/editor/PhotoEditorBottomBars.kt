package dev.ander.alexet.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import dev.ander.alexet.ui.components.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatStrikethrough
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ander.alexet.R
import dev.ander.alexet.data.BlurConfig
import dev.ander.alexet.data.BlurType
import dev.ander.alexet.data.ImageEditState
import kotlin.math.roundToInt

@Composable
fun PhotoEditorMainTabs(
    currentTab: PhotoEditorTab,
    onTabSelected: (PhotoEditorTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.height(64.dp),
    ) {
        PhotoEditorTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                label = {
                    Text(
                        stringResource(tab.titleRes),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        maxLines = 1,
                        softWrap = false,
                    )
                },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
fun AspectControlsBar(
    editState: ImageEditState,
    selectedOption: AspectOption,
    onOptionSelected: (AspectOption) -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onRotateDelta: (Int) -> Unit,
    onSetRotationAngle: (Int) -> Unit,
    onApplyExactCropRatio: (Float) -> Unit,
    onApplyCrop: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showRotationDialog by remember { mutableStateOf(false) }
    var showExactDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val canApplyCrop = onApplyCrop != null && editState.crop.isValid &&
            (editState.crop.left > 0.005f || editState.crop.top > 0.005f || editState.crop.right < 0.995f || editState.crop.bottom < 0.995f)

        // Top row: Flip H, Flip V, Rotate Angle button + Compact Apply Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onFlipH) {
                    Icon(Icons.Rounded.Flip, contentDescription = stringResource(R.string.flip_horizontal), modifier = Modifier.size(18.dp))
                }
                FilledTonalIconButton(onClick = onFlipV) {
                    Icon(
                        Icons.Rounded.Flip,
                        contentDescription = stringResource(R.string.flip_vertical),
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 90f },
                    )
                }
                FilledTonalIconButton(onClick = { onRotateDelta(-90) }) {
                    Icon(Icons.AutoMirrored.Rounded.RotateLeft, contentDescription = stringResource(R.string.rotate_left), modifier = Modifier.size(18.dp))
                }
                FilledTonalIconButton(onClick = { onRotateDelta(90) }) {
                    Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = stringResource(R.string.rotate_right), modifier = Modifier.size(18.dp))
                }
                FilledTonalButton(
                    onClick = { showRotationDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(Icons.Rounded.Rotate90DegreesCw, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("${editState.rotation}°", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (canApplyCrop && onApplyCrop != null) {
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onApplyCrop,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.apply_crop),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Bottom row: Scrollable Aspect Ratio Options
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AspectOption.entries.forEach { option ->
                FilterChip(
                    selected = selectedOption == option,
                    onClick = {
                        if (option == AspectOption.EXACT) {
                            showExactDialog = true
                        } else {
                            onOptionSelected(option)
                        }
                    },
                    label = { Text(stringResource(option.labelRes), style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
    }

    if (showRotationDialog) {
        RotationAngleDialog(
            currentRotation = editState.rotation,
            onApply = { angle ->
                onSetRotationAngle(angle)
                showRotationDialog = false
            },
            onDismiss = { showRotationDialog = false },
        )
    }

    if (showExactDialog) {
        ExactAspectDialog(
            onApply = { ratio ->
                // onOptionSelected(EXACT) would recompute a free crop and wipe the ratio just set.
                onApplyExactCropRatio(ratio)
                showExactDialog = false
            },
            onDismiss = { showExactDialog = false },
        )
    }
}

@Composable
fun AdjustControlsBar(
    editState: ImageEditState,
    currentParam: AdjustParam,
    onParamSelected: (AdjustParam) -> Unit,
    onParamValueChange: (AdjustParam, Float) -> Unit,
    onParamValueChangeFinished: () -> Unit = {},
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentValue = when (currentParam) {
        AdjustParam.BRIGHTNESS -> editState.brightness
        AdjustParam.CONTRAST -> editState.contrast
        AdjustParam.SATURATION -> editState.saturation
        AdjustParam.SHADOWS -> editState.shadows
        AdjustParam.HIGHLIGHTS -> editState.highlights
        AdjustParam.EXPOSURE -> editState.exposure
        AdjustParam.GAMMA -> editState.gamma
        AdjustParam.TEMPERATURE -> editState.temperature
        AdjustParam.SHARPNESS -> editState.sharpness
    }
    val percentValue = (currentValue * 100).roundToInt()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Top row: Parameter name, value, and Undo/Redo icons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The editor is dark whatever the system theme: the default content colour was black.
            val arrowColors = IconButtonDefaults.iconButtonColors(
                contentColor = Color.White,
                disabledContentColor = Color.White.copy(alpha = 0.38f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUndo, enabled = canUndo, colors = arrowColors) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.undo))
                }
                IconButton(onClick = onRedo, enabled = canRedo, colors = arrowColors) {
                    Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = stringResource(R.string.redo))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${stringResource(currentParam.labelRes)}: ${if (percentValue > 0) "+$percentValue%" else "$percentValue%"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(
                    onClick = {
                        onParamValueChange(currentParam, 0f)
                        onParamValueChangeFinished()
                    },
                    enabled = currentValue != 0f,
                ) {
                    Text(stringResource(R.string.default_label))
                }
            }
        }

        // Center slider: -------o------- (center mark at 0)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Every parameter starts at the middle (sharpness below 0 softens).
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                // Center neutral tick mark
                drawLine(
                    color = Color.White.copy(alpha = 0.5f),
                    start = Offset(cx, cy - 8.dp.toPx()),
                    end = Offset(cx, cy + 8.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            Slider(
                value = currentValue,
                onValueChange = { onParamValueChange(currentParam, it) },
                onValueChangeFinished = onParamValueChangeFinished,
                valueRange = -1f..1f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }

        // Parameter selector chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AdjustParam.entries.forEach { param ->
                FilterChip(
                    selected = currentParam == param,
                    onClick = { onParamSelected(param) },
                    label = { Text(stringResource(param.labelRes), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
fun BlurControlsBar(
    config: BlurConfig,
    onConfigChange: (BlurConfig) -> Unit,
    onConfigChangeFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Blur Type Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BlurType.entries.forEach { type ->
                val labelRes = when (type) {
                    BlurType.NONE -> R.string.blur_none
                    BlurType.RADIAL -> R.string.blur_radial
                    BlurType.MIRROR -> R.string.blur_mirror
                    BlurType.LINEAR -> R.string.blur_linear
                    BlurType.GAUSSIAN -> R.string.blur_gaussian
                }
                FilterChip(
                    selected = config.type == type,
                    onClick = {
                        val newRadius = if (type != BlurType.NONE && config.radius <= 1f) 25f else config.radius
                        onConfigChange(config.copy(type = type, radius = newRadius))
                        onConfigChangeFinished()
                    },
                    label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        if (config.type != BlurType.NONE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${stringResource(R.string.blur_strength)}: ${config.radius.roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = config.radius,
                onValueChange = { onConfigChange(config.copy(radius = it)) },
                onValueChangeFinished = onConfigChangeFinished,
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth(),
            )
            // Focus types only: how quickly sharp turns into full blur.
            if (config.type != BlurType.GAUSSIAN) {
                Text(
                    text = "${stringResource(R.string.blur_falloff)}: ${(config.falloff * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = config.falloff,
                    onValueChange = { onConfigChange(config.copy(falloff = it)) },
                    onValueChangeFinished = onConfigChangeFinished,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun StickerControlsBar(
    hasSelection: Boolean,
    onAddStickerClick: () -> Unit,
    onRotateStep: (Float) -> Unit,
    onMoveStep: (Float, Float) -> Unit,
    onDeleteSelected: () -> Unit,
    onDeselect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A selected item's buttons don't all fit on a phone: the row scrolls (the last one, x, was cut off).
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (hasSelection) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = if (hasSelection) Arrangement.spacedBy(8.dp) else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!hasSelection) {
            FilledTonalButton(onClick = onAddStickerClick) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_sticker))
            }
        } else {
            // Selected item options: -90°, +90°, move by 1% of the image, delete
            FilledTonalIconButton(onClick = { onRotateStep(-90f) }) {
                Text("-90°", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalIconButton(onClick = { onRotateStep(90f) }) {
                Text("+90°", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalIconButton(onClick = { onMoveStep(0f, -MOVE_STEP) }) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.move_up))
            }
            FilledTonalIconButton(onClick = { onMoveStep(0f, MOVE_STEP) }) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.move_down))
            }
            FilledTonalIconButton(onClick = { onMoveStep(-MOVE_STEP, 0f) }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.move_left))
            }
            FilledTonalIconButton(onClick = { onMoveStep(MOVE_STEP, 0f) }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.move_right))
            }
            FilledTonalIconButton(
                onClick = onDeleteSelected,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete_clip))
            }
            IconButton(onClick = onDeselect) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
fun TextControlsBar(
    hasSelection: Boolean,
    onAddTextClick: () -> Unit,
    onEditTextClick: () -> Unit,
    onRotateStep: (Float) -> Unit,
    onMoveStep: (Float, Float) -> Unit,
    onDeleteSelected: () -> Unit,
    onDeselect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A selected item's buttons don't all fit on a phone: the row scrolls (the last one, x, was cut off).
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (hasSelection) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = if (hasSelection) Arrangement.spacedBy(8.dp) else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!hasSelection) {
            FilledTonalButton(onClick = onAddTextClick) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_text))
            }
        } else {
            FilledTonalIconButton(
                onClick = onEditTextClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.22f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.edit))
            }
            FilledTonalIconButton(onClick = { onRotateStep(-90f) }) {
                Text("-90°", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalIconButton(onClick = { onRotateStep(90f) }) {
                Text("+90°", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalIconButton(onClick = { onMoveStep(0f, -MOVE_STEP) }) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.move_up))
            }
            FilledTonalIconButton(onClick = { onMoveStep(0f, MOVE_STEP) }) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.move_down))
            }
            FilledTonalIconButton(onClick = { onMoveStep(-MOVE_STEP, 0f) }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.move_left))
            }
            FilledTonalIconButton(onClick = { onMoveStep(MOVE_STEP, 0f) }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.move_right))
            }
            FilledTonalIconButton(
                onClick = onDeleteSelected,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
            }
            IconButton(onClick = onDeselect) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
            }
        }
    }
}

/** Zdjęcie 5: Text Editing Panel expanding to ~60% screen with full toolbar */
@Composable
fun TextEditExpandedPanel(
    initialText: String,
    initialAlignment: Int,
    initialBold: Boolean,
    initialItalic: Boolean,
    initialUnderline: Boolean,
    initialStrikethrough: Boolean,
    initialFontSize: Float,
    initialColor: Int,
    onConfirm: (text: String, align: Int, bold: Boolean, italic: Boolean, underline: Boolean, strike: Boolean, size: Float, color: Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(initialText) }
    var alignment by remember { mutableIntStateOf(initialAlignment) }
    var isBold by remember { mutableStateOf(initialBold) }
    var isItalic by remember { mutableStateOf(initialItalic) }
    var isUnderline by remember { mutableStateOf(initialUnderline) }
    var isStrikethrough by remember { mutableStateOf(initialStrikethrough) }
    var fontSize by remember { mutableFloatStateOf(initialFontSize) }
    var color by remember { mutableIntStateOf(initialColor) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.62f),
        color = Color(0xFF1E1E1E),
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Text Input / Preview area with bracket styling (Zdjęcie 5)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp)
                    .border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.text_hint), color = Color.Gray) },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = Color(color),
                        fontSize = fontSize.sp,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (isItalic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                        textDecoration = when {
                            isUnderline && isStrikethrough -> androidx.compose.ui.text.style.TextDecoration.combine(
                                listOf(androidx.compose.ui.text.style.TextDecoration.Underline, androidx.compose.ui.text.style.TextDecoration.LineThrough),
                            )
                            isUnderline -> androidx.compose.ui.text.style.TextDecoration.Underline
                            isStrikethrough -> androidx.compose.ui.text.style.TextDecoration.LineThrough
                            else -> androidx.compose.ui.text.style.TextDecoration.None
                        },
                        textAlign = when (alignment) {
                            0 -> TextAlign.Left
                            2 -> TextAlign.Right
                            else -> TextAlign.Center
                        },
                    ),
                )
            }

            // Formatting toolbar (Zdjęcie 5): [L] [C] [R] [B] [I] [U] [S] [-] [15] [+]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Alignments
                IconButton(onClick = { alignment = 0 }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.FormatAlignLeft,
                        contentDescription = stringResource(R.string.align_left),
                        tint = if (alignment == 0) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = { alignment = 1 }) {
                    Icon(
                        Icons.Rounded.FormatAlignCenter,
                        contentDescription = stringResource(R.string.align_center),
                        tint = if (alignment == 1) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = { alignment = 2 }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.FormatAlignRight,
                        contentDescription = stringResource(R.string.align_right),
                        tint = if (alignment == 2) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Styles: B, I, U, S
                IconButton(onClick = { isBold = !isBold }) {
                    Text(
                        "B",
                        fontWeight = FontWeight.Bold,
                        color = if (isBold) MaterialTheme.colorScheme.primary else Color.White,
                        fontSize = 18.sp,
                    )
                }
                IconButton(onClick = { isItalic = !isItalic }) {
                    Text(
                        "I",
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = if (isItalic) MaterialTheme.colorScheme.primary else Color.White,
                        fontSize = 18.sp,
                    )
                }
                IconButton(onClick = { isUnderline = !isUnderline }) {
                    Icon(
                        Icons.Rounded.FormatUnderlined,
                        contentDescription = stringResource(R.string.underline),
                        tint = if (isUnderline) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = { isStrikethrough = !isStrikethrough }) {
                    Icon(
                        Icons.Rounded.FormatStrikethrough,
                        contentDescription = stringResource(R.string.strikethrough),
                        tint = if (isStrikethrough) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Font size [-] [15] [+]
                IconButton(onClick = { fontSize = (fontSize - 2f).coerceAtLeast(8f) }) {
                    Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.font_smaller), tint = Color.White)
                }
                Text(
                    text = "${fontSize.roundToInt()}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                IconButton(onClick = { fontSize = (fontSize + 2f).coerceAtMost(72f) }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.font_larger), tint = Color.White)
                }
            }

            // Bottom action row: Close, Color palette & Confirm
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White)
                }

                // Quick Color Swatches
                val swatches = listOf(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.BLACK,
                    android.graphics.Color.YELLOW,
                    android.graphics.Color.RED,
                    android.graphics.Color.GREEN,
                    android.graphics.Color.CYAN,
                    android.graphics.Color.MAGENTA,
                )
                Row {
                    swatches.forEach { c ->
                        // 36 dp touch area around the 24 dp dot (7 swatches + 2 icons fit a folded screen).
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(
                                        width = if (color == c) 2.dp else 1.dp,
                                        color = if (color == c) MaterialTheme.colorScheme.primary else Color.Gray,
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onConfirm(text, alignment, isBold, isItalic, isUnderline, isStrikethrough, fontSize, color)
                        }
                    },
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.edit_confirm), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Zdjęcie 1: Brush Toolbar with X, PĘDZEL, ✓, and 3 mode icons with Slider above */
@Composable
fun BrushControlsBar(
    activeMode: BrushToolMode,
    onModeSelected: (BrushToolMode) -> Unit,
    brushColor: Int,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    brushHardness: Float,
    onBrushHardnessChange: (Float) -> Unit,
    onOpenColorPicker: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Slider above (adjusts size or hardness depending on activeMode)
        if (activeMode == BrushToolMode.SIZE || activeMode == BrushToolMode.HARDNESS) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                if (activeMode == BrushToolMode.SIZE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Preview dot (relative, capped) and the numeric value of the current size.
                        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size((brushSize * 0.4f).coerceIn(2f, 40f).dp)
                                    .clip(CircleShape)
                                    .background(Color(brushColor)),
                            )
                        }
                        Slider(
                            value = brushSize,
                            onValueChange = onBrushSizeChange,
                            valueRange = 4f..100f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF38BDF8),
                                activeTrackColor = Color(0xFF38BDF8),
                            ),
                        )
                        Text(
                            text = "${brushSize.roundToInt()}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(32.dp).padding(start = 6.dp),
                        )
                    }
                } else {
                    Slider(
                        value = brushHardness,
                        onValueChange = onBrushHardnessChange,
                        valueRange = 0.05f..1.0f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF38BDF8),
                            activeTrackColor = Color(0xFF38BDF8),
                        ),
                    )
                }
            }
        } else {
            Spacer(Modifier.height(36.dp))
        }

        // 3 Circular buttons: [Kolor], [Rozmiar], [Twardość] (Zdjęcie 1)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. Kolor
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable { onOpenColorPicker() },
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(brushColor))
                        .border(
                            width = if (activeMode == BrushToolMode.COLOR) 2.dp else 1.dp,
                            color = if (activeMode == BrushToolMode.COLOR) Color(0xFF38BDF8) else Color.Gray,
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.brush_color), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }

            // 2. Rozmiar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable {
                        onModeSelected(if (activeMode == BrushToolMode.SIZE) BrushToolMode.NONE else BrushToolMode.SIZE)
                    },
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A2A))
                        .border(
                            width = if (activeMode == BrushToolMode.SIZE) 2.dp else 1.dp,
                            color = if (activeMode == BrushToolMode.SIZE) Color(0xFF38BDF8) else Color.Gray,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.OpenInFull, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.brush_size), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }

            // 3. Twardość (Zdjęcie 1: Diamond with dashed border)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable {
                        onModeSelected(if (activeMode == BrushToolMode.HARDNESS) BrushToolMode.NONE else BrushToolMode.HARDNESS)
                    },
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A2A))
                        .border(
                            width = if (activeMode == BrushToolMode.HARDNESS) 2.dp else 1.dp,
                            color = if (activeMode == BrushToolMode.HARDNESS) Color(0xFF38BDF8) else Color.Gray,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Opacity, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.brush_hardness), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

/** Zdjęcie 2: Full Color-Picker View with Hue, 2D Sat/Val, Alpha, swatches and Eyedropper */
@Composable
fun AdvancedColorPickerView(
    initialColor: Int,
    onColorSelected: (Int) -> Unit,
    onOpenEyedropper: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialHsv = remember(initialColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor, hsv)
        Triple(hsv[0], hsv[1], hsv[2])
    }
    val initialAlpha = remember(initialColor) {
        android.graphics.Color.alpha(initialColor) / 255f
    }
    var hue by remember(initialColor) { mutableFloatStateOf(initialHsv.first) }
    var sat by remember(initialColor) { mutableFloatStateOf(initialHsv.second) }
    var valB by remember(initialColor) { mutableFloatStateOf(initialHsv.third) }
    var alpha by remember(initialColor) { mutableFloatStateOf(initialAlpha) }

    val computedColor = remember(hue, sat, valB, alpha) {
        val c = android.graphics.Color.HSVToColor((alpha * 255).roundToInt(), floatArrayOf(hue, sat, valB))
        c
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Upper section: Hue strip (left), Sat/Val 2D Canvas (center), Alpha strip (right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Hue strip. Top = 360, bottom = 0 (same direction as the old rotated slider).
            // Custom input: a Slider rotated inside a 32 dp box was clamped to ~32 dp of travel.
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Red, Color.Magenta, Color.Blue,
                                Color.Cyan, Color.Green, Color.Yellow, Color.Red,
                            ),
                        ),
                    )
                    .fractionInput(initialColor) { _, fy -> hue = (1f - fy) * 360f },
            ) {
                StripThumb(fraction = 1f - hue / 360f)
            }

            // Center: Saturation (x) & Value (y) field, tap or drag to pick
            val baseHueColor = remember(hue) {
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.horizontalGradient(listOf(Color.White, baseHueColor)),
                    )
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
                    )
                    .fractionInput(initialColor) { fx, fy ->
                        sat = fx
                        valB = 1f - fy
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = sat * size.width
                    val cy = (1f - valB) * size.height
                    drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(cx, cy), style = Stroke(2.5.dp.toPx()))
                    drawCircle(Color.Black, radius = 6.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
                }
            }

            // Right: Alpha strip. Top = opaque, bottom = transparent.
            val opaqueColor = remember(hue, sat, valB) {
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, valB)))
            }
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.DarkGray)
                    .background(Brush.verticalGradient(listOf(opaqueColor, opaqueColor.copy(alpha = 0f))))
                    .fractionInput(initialColor) { _, fy -> alpha = 1f - fy },
            ) {
                StripThumb(fraction = 1f - alpha)
            }
        }

        // Swatches row with Eyedropper button on the left (Zdjęcie 2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pipette / Eyedropper button with blue outline
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2A2A2A))
                    .border(2.dp, Color(0xFF38BDF8), RoundedCornerShape(4.dp))
                    .clickable { onOpenEyedropper() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Colorize, contentDescription = stringResource(R.string.eyedropper), tint = Color.White)
            }

            // Standard Preset Swatches
            val swatches = listOf(
                android.graphics.Color.WHITE,
                android.graphics.Color.GRAY,
                android.graphics.Color.BLACK,
                android.graphics.Color.CYAN,
                android.graphics.Color.BLUE,
                0xFF8A2BE2.toInt(),
                0xFFFF69B4.toInt(),
                android.graphics.Color.MAGENTA,
                android.graphics.Color.RED,
                android.graphics.Color.YELLOW,
                android.graphics.Color.GREEN,
            )
            swatches.forEach { c ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(c))
                        .border(
                            width = if (computedColor == c) 2.dp else 0.5.dp,
                            color = if (computedColor == c) Color(0xFF38BDF8) else Color.Black,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .clickable {
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(c, hsv)
                            hue = hsv[0]
                            sat = hsv[1]
                            valB = hsv[2]
                        },
                )
            }
        }

        // Bottom Bar: X | KOLOR PĘDZLA | ✓
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White)
            }
            Text(
                text = stringResource(R.string.brush_color).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            IconButton(
                onClick = {
                    onColorSelected(computedColor)
                    onClose()
                },
            ) {
                Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.edit_confirm), tint = Color.White)
            }
        }
    }
}

@Composable
private fun RotationAngleDialog(
    currentRotation: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var angle by remember { mutableFloatStateOf(currentRotation.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rotate_right)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "${angle.roundToInt()}°",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Slider(
                    value = angle,
                    onValueChange = { angle = it },
                    // Quarter turns only: a free angle cannot be reproduced by the export.
                    valueRange = 0f..270f,
                    steps = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf(0, 90, 180, 270).forEach { preset ->
                        FilledTonalButton(onClick = { angle = preset.toFloat() }) {
                            Text("$preset°")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(((angle / 90f).roundToInt() * 90) % 360) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_no))
            }
        },
    )
}

@Composable
private fun ExactAspectDialog(
    onApply: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var isPixelMode by remember { mutableStateOf(false) }
    var valX by remember { mutableStateOf("16") }
    var valY by remember { mutableStateOf("9") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.aspect_exact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !isPixelMode, onClick = {
                        isPixelMode = false
                        valX = "16"
                        valY = "9"
                    })
                    Text(stringResource(R.string.ratio_mode))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isPixelMode, onClick = {
                        isPixelMode = true
                        valX = "1920"
                        valY = "1080"
                    })
                    Text(stringResource(R.string.pixel_mode))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = valX,
                        onValueChange = { valX = it.filter { ch -> ch.isDigit() } },
                        label = { Text("X") },
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (isPixelMode) "×" else ":", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    OutlinedTextField(
                        value = valY,
                        onValueChange = { valY = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Y") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            val x = valX.toFloatOrNull()
            val y = valY.toFloatOrNull()
            Button(
                // Empty or zero field used to become 1 silently ("16 : empty" -> 16:1).
                enabled = x != null && y != null && x > 0f && y > 0f,
                onClick = {
                    if (x != null && y != null && x > 0f && y > 0f) onApply(x / y)
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_no))
            }
        },
    )
}

/** Tap/drag anywhere in the element reports the pointer as fractions (0..1) of width and height. */
private fun Modifier.fractionInput(key: Any?, onFraction: (Float, Float) -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown()
            fun emit(p: Offset) = onFraction(
                (p.x / size.width).coerceIn(0f, 1f),
                (p.y / size.height).coerceIn(0f, 1f),
            )
            down.consume()
            emit(down.position)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                change.consume()
                emit(change.position)
            }
        }
    }

/** Horizontal marker across a vertical strip at [fraction] of its height. */
@Composable
private fun StripThumb(fraction: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val y = fraction.coerceIn(0f, 1f) * size.height
        drawRect(
            color = Color.Black,
            topLeft = Offset(0f, y - 3.dp.toPx()),
            size = Size(size.width, 6.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
        drawRect(
            color = Color.White,
            topLeft = Offset(1.dp.toPx(), y - 2.dp.toPx()),
            size = Size(size.width - 2.dp.toPx(), 4.dp.toPx()),
            style = Stroke(2.dp.toPx()),
        )
    }
}

/** Arrow buttons move a selected sticker/text by 1% of the image (independent of the screen). */
private const val MOVE_STEP = 0.01f
