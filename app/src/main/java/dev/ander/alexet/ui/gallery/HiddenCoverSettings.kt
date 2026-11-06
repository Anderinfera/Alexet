package dev.ander.alexet.ui.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ander.alexet.R
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.HiddenCoverColorLimit
import dev.ander.alexet.data.HiddenCoverConfig
import dev.ander.alexet.data.HiddenCoverRandom
import dev.ander.alexet.data.HiddenCoverStyle
import dev.ander.alexet.ui.editor.AdvancedColorPickerView
import kotlin.math.roundToInt

internal fun hiddenCoverStyleName(style: HiddenCoverStyle): Int = when (style) {
    HiddenCoverStyle.OFF -> R.string.hidden_cover_off
    HiddenCoverStyle.PIXELATE -> R.string.hidden_cover_pixelate
    HiddenCoverStyle.BLUR -> R.string.hidden_cover_blur
    HiddenCoverStyle.IMAGE -> R.string.hidden_cover_image
    HiddenCoverStyle.COLOR -> R.string.hidden_cover_color
    HiddenCoverStyle.STRIPES -> R.string.hidden_cover_stripes
    HiddenCoverStyle.CHECKER -> R.string.hidden_cover_checker
}

/** Which color a picker dialog is changing. */
private enum class HiddenCoverColorSlot { PLAIN, A, B, PALETTE_NEW }

/**
 * Settings of hidden folders' covers: the style (each tile shows it on a real cover) and its
 * options, with a large preview on top.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HiddenCoverSettingsPage(
    config: HiddenCoverConfig,
    albums: List<Album>,
    onUpdate: ((HiddenCoverConfig) -> HiddenCoverConfig) -> Unit,
    onPickImage: (android.net.Uri) -> Unit,
    onBack: () -> Unit,
) {
    // A hidden folder's own cover when there is one, else any.
    val previewAlbum = remember(albums) { albums.firstOrNull { it.isHidden && it.cover != null } ?: albums.firstOrNull { it.cover != null } }
    val previewCover = previewAlbum?.cover
    var picking by remember { mutableStateOf<HiddenCoverColorSlot?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onPickImage)
    }

    Column(Modifier.fillMaxSize()) {
        PageBar(stringResource(R.string.hidden_cover_title), onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.hidden_cover_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Large preview, as a folder tile.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(180.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (config.style == HiddenCoverStyle.OFF) {
                    if (previewCover != null) {
                        dev.ander.alexet.ui.components.MediaThumbnail(
                            media = previewCover,
                            modifier = Modifier.fillMaxSize(),
                            showPlayIcon = false,
                            showDuration = false,
                            shape = androidx.compose.ui.graphics.RectangleShape,
                        )
                    } else {
                        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).size(48.dp))
                    }
                } else {
                    HiddenFolderCover(previewCover, config, Modifier.fillMaxSize())
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))),
                )
                Text(
                    previewAlbum?.name ?: stringResource(R.string.settings_category_folders),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                )
            }

            Text(stringResource(R.string.hidden_cover_style), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            // Rows of four of the same width (a flow row measured the square tiles too wide and
            // left two in a row).
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HiddenCoverStyle.entries.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { style ->
                            StyleTile(
                                style = style,
                                selected = config.style == style,
                                previewConfig = config.copy(style = style),
                                cover = previewCover,
                                onClick = { onUpdate { it.copy(style = style) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Empty places keep the last row's tiles as wide as the others.
                        repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }

            when (config.style) {
                HiddenCoverStyle.OFF -> Unit
                HiddenCoverStyle.PIXELATE -> IntSlider(
                    label = stringResource(R.string.hidden_cover_pixels),
                    value = config.pixels,
                    range = HiddenCoverConfig.MIN_PIXELS..HiddenCoverConfig.MAX_PIXELS,
                    shown = "${config.pixels}×${config.pixels}",
                ) { v -> onUpdate { it.copy(pixels = v) } }
                HiddenCoverStyle.BLUR -> IntSlider(
                    label = stringResource(R.string.hidden_cover_blur_strength),
                    value = config.blur,
                    range = HiddenCoverConfig.MIN_BLUR..HiddenCoverConfig.MAX_BLUR,
                    shown = config.blur.toString(),
                ) { v -> onUpdate { it.copy(blur = v) } }
                HiddenCoverStyle.IMAGE -> FilledTonalButton(
                    onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Rounded.Image, null, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(if (config.imagePath == null) R.string.hidden_cover_pick_image else R.string.hidden_cover_change_image),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                HiddenCoverStyle.COLOR -> ColorRow(stringResource(R.string.hidden_cover_color), config.color) { picking = HiddenCoverColorSlot.PLAIN }
                HiddenCoverStyle.STRIPES, HiddenCoverStyle.CHECKER -> {
                    val stripes = config.style == HiddenCoverStyle.STRIPES
                    if (stripes) {
                        IntSlider(
                            label = stringResource(R.string.hidden_cover_stripe_count),
                            value = config.stripeCount,
                            range = HiddenCoverConfig.MIN_STRIPES..HiddenCoverConfig.MAX_STRIPES,
                            shown = config.stripeCount.toString(),
                        ) { v -> onUpdate { it.copy(stripeCount = v) } }
                        IntSlider(
                            label = stringResource(R.string.hidden_cover_stripe_width),
                            value = config.stripeWidth,
                            range = HiddenCoverConfig.MIN_STRIPE_WIDTH..HiddenCoverConfig.MAX_STRIPE_WIDTH,
                            shown = "${config.stripeWidth}%",
                        ) { v -> onUpdate { it.copy(stripeWidth = v) } }
                    } else {
                        // Further right: bigger squares, so fewer along a side.
                        val min = HiddenCoverConfig.MIN_CELLS
                        val max = HiddenCoverConfig.MAX_CELLS
                        IntSlider(
                            label = stringResource(R.string.hidden_cover_cells),
                            value = min + max - config.checkerCells,
                            range = min..max,
                            shown = "${config.checkerCells}×${config.checkerCells}",
                        ) { v -> onUpdate { it.copy(checkerCells = min + max - v) } }
                    }
                    IntSlider(
                        label = stringResource(R.string.hidden_cover_angle),
                        value = config.angle,
                        range = 0..179,
                        shown = if (config.randomOrientation) stringResource(R.string.hidden_cover_random_value) else "${config.angle}°",
                        enabled = !config.randomOrientation,
                    ) { v -> onUpdate { it.copy(angle = v) } }
                    ColorRow(
                        stringResource(if (stripes) R.string.hidden_cover_color_stripes else R.string.hidden_cover_color_squares),
                        config.colorA,
                        enabled = !config.randomColors,
                    ) { picking = HiddenCoverColorSlot.A }

                    SettingsItemDivider()
                    Text(stringResource(R.string.hidden_cover_random), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    SettingsSegmentedRow(
                        options = listOf(
                            HiddenCoverRandom.NONE to stringResource(R.string.hidden_cover_random_none),
                            HiddenCoverRandom.ORIENTATION to stringResource(R.string.hidden_cover_random_orientation),
                            HiddenCoverRandom.COLOR to stringResource(R.string.hidden_cover_random_color),
                            HiddenCoverRandom.ALL to stringResource(R.string.hidden_cover_random_all),
                        ),
                        selected = config.random,
                        onSelect = { r -> onUpdate { it.copy(random = r) } },
                    )
                    if (config.randomColors) {
                        Text(stringResource(R.string.hidden_cover_limit), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        SettingsSegmentedRow(
                            options = listOf(
                                HiddenCoverColorLimit.NONE to stringResource(R.string.hidden_cover_limit_none),
                                HiddenCoverColorLimit.RANGE to stringResource(R.string.hidden_cover_limit_range),
                                HiddenCoverColorLimit.PALETTE to stringResource(R.string.hidden_cover_limit_palette),
                            ),
                            selected = config.colorLimit,
                            onSelect = { l -> onUpdate { it.copy(colorLimit = l) } },
                        )
                        when (config.colorLimit) {
                            HiddenCoverColorLimit.NONE -> Unit
                            HiddenCoverColorLimit.RANGE -> HueRange(config.hueFrom, config.hueTo) { from, to ->
                                onUpdate { it.copy(hueFrom = from, hueTo = to) }
                            }
                            HiddenCoverColorLimit.PALETTE -> PaletteEditor(
                                palette = config.palette,
                                onRemove = { i -> onUpdate { it.copy(palette = it.palette.filterIndexed { j, _ -> j != i }) } },
                                onAdd = { picking = HiddenCoverColorSlot.PALETTE_NEW },
                            )
                        }
                    }
                }
            }
        }
    }

    picking?.let { slot ->
        Dialog(onDismissRequest = { picking = null }) {
            AdvancedColorPickerView(
                initialColor = when (slot) {
                    HiddenCoverColorSlot.PLAIN -> config.color
                    HiddenCoverColorSlot.A -> config.colorA
                    HiddenCoverColorSlot.B -> config.colorB
                    HiddenCoverColorSlot.PALETTE_NEW -> config.palette.lastOrNull() ?: config.colorA
                },
                onColorSelected = { argb ->
                    onUpdate {
                        when (slot) {
                            HiddenCoverColorSlot.PLAIN -> it.copy(color = argb)
                            HiddenCoverColorSlot.A -> it.copy(colorA = argb)
                            HiddenCoverColorSlot.B -> it.copy(colorB = argb)
                            HiddenCoverColorSlot.PALETTE_NEW -> it
                        }
                    }
                    if (slot == HiddenCoverColorSlot.PALETTE_NEW) {
                        // One color added per opening of the picker.
                        onUpdate {
                            if (argb in it.palette || it.palette.size >= HiddenCoverConfig.MAX_PALETTE) it
                            else it.copy(palette = it.palette + argb)
                        }
                        picking = null
                    }
                },
                onOpenEyedropper = {},
                onClose = { picking = null },
            )
        }
    }
}

@Composable
private fun StyleTile(
    style: HiddenCoverStyle,
    selected: Boolean,
    previewConfig: HiddenCoverConfig,
    cover: dev.ander.alexet.data.GalleryMedia?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                ),
        ) {
            if (style == HiddenCoverStyle.OFF) {
                if (cover != null) {
                    dev.ander.alexet.ui.components.MediaThumbnail(
                        media = cover,
                        modifier = Modifier.fillMaxSize(),
                        showPlayIcon = false,
                        showDuration = false,
                        shape = androidx.compose.ui.graphics.RectangleShape,
                    )
                }
            } else {
                HiddenFolderCover(cover, previewConfig, Modifier.fillMaxSize())
            }
        }
        Text(
            stringResource(hiddenCoverStyleName(style)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun IntSlider(
    label: String,
    value: Int,
    range: IntRange,
    shown: String,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range).toFloat(),
            onValueChange = { v -> v.roundToInt().takeIf { it != value }?.let(onChange) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
        )
    }
}

@Composable
private fun ColorRow(label: String, color: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.weight(1f),
        )
        if (enabled) {
            ColorSwatch(color, Modifier.size(32.dp))
        } else {
            Text(stringResource(R.string.hidden_cover_random_value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ColorSwatch(color: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(color))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    )
}

/** Two hues (from, to) over a hue strip; the strip shows the range random colors come from. */
@Composable
private fun HueRange(from: Int, to: Int, onChange: (Int, Int) -> Unit) {
    val spectrum = remember { List(13) { i -> Color.hsv(i * 30f % 360f, 0.8f, 0.9f) } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.horizontalGradient(spectrum)),
        )
        // The chosen range, from its first hue to its last (round through red when from > to).
        val span = if (to >= from) to - from else to + 360 - from
        val rangeColors = List(7) { i -> Color.hsv((from + span * i / 6f) % 360f, 0.8f, 0.9f) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.horizontalGradient(rangeColors)),
        )
        IntSlider(stringResource(R.string.hidden_cover_hue_from), from, 0..359, "$from°") { v -> onChange(v, to) }
        IntSlider(stringResource(R.string.hidden_cover_hue_to), to, 0..359, "$to°") { v -> onChange(from, v) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteEditor(palette: List<Int>, onRemove: (Int) -> Unit, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            palette.forEachIndexed { i, color ->
                Box(Modifier.size(40.dp).clickable { onRemove(i) }) {
                    ColorSwatch(color, Modifier.fillMaxSize())
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(14.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                    )
                }
            }
            if (palette.size < HiddenCoverConfig.MAX_PALETTE) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onAdd),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.hidden_cover_palette_add), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Text(
            stringResource(if (palette.isEmpty()) R.string.hidden_cover_palette_empty else R.string.hidden_cover_palette_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
