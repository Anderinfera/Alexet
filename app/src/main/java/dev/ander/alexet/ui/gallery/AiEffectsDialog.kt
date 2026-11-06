package dev.ander.alexet.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ander.alexet.R
import dev.ander.alexet.data.AiCurtainEdge
import dev.ander.alexet.data.AiEffect
import dev.ander.alexet.data.AiEffectStyle
import dev.ander.alexet.data.AiEffectsConfig
import dev.ander.alexet.data.AiEffectsPreset
import dev.ander.alexet.data.AiLoaderStyle
import dev.ander.alexet.data.AiPalette
import dev.ander.alexet.ui.components.AnimatedContent
import dev.ander.alexet.ui.components.AnimatedVisibility
import dev.ander.alexet.ui.components.aiglow.AiCurtainGlow
import dev.ander.alexet.ui.components.aiglow.AiEdgeGlow
import dev.ander.alexet.ui.components.aiglow.AiGlint
import dev.ander.alexet.ui.components.aiglow.AiGlowRenderer
import dev.ander.alexet.ui.components.aiglow.AiLoader
import dev.ander.alexet.ui.components.aiglow.AiNexus
import dev.ander.alexet.ui.components.aiglow.GLINT_PERIOD_MS
import dev.ander.alexet.ui.components.aiglow.NexusCell
import dev.ander.alexet.ui.components.aiglow.NexusGrid
import dev.ander.alexet.ui.components.aiglow.aiColors
import dev.ander.alexet.ui.components.aiglow.aiGlint
import dev.ander.alexet.ui.components.aiglow.aiGlowHalo
import dev.ander.alexet.ui.components.aiglow.rememberAiPhase
import dev.ander.alexet.ui.components.aiglow.rememberLoopingGlow
import dev.ander.alexet.ui.editor.AdvancedColorPickerView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The "Custom" menu of the AI effects: the base style and a list of the effects; each opens its
 * own page with a live preview and its options (colors, saved palettes, brightness, speed, width,
 * duration and the effect's own choices).
 */
@Composable
fun AiEffectsDialog(
    config: AiEffectsConfig,
    onChange: ((AiEffectsConfig) -> AiEffectsConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    // The menu edits Custom: its previews show Custom whatever preset is picked.
    val preview = config.copy(preset = AiEffectsPreset.CUSTOM)
    var open by rememberSaveable { mutableStateOf<AiEffect?>(null) }
    // Wide settings show the menu in their detail pane, a phone over the whole screen.
    SettingsSubPage(open = true, onClose = onDismiss) { onBack ->
        // Back from an effect's page goes to the list first.
        BackHandler(enabled = open != null) { open = null }
        AnimatedContent(
            targetState = open,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forward = targetState != null
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn())
                    .togetherWith(slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
            },
            label = "aiEffectsPage",
        ) { effect ->
            if (effect == null) {
                EffectsList(config, preview, onChange, onBack, onOpen = { open = it })
            } else {
                EffectPage(effect, config, preview, onChange, onBack = { open = null })
            }
        }
    }
}

@Composable
internal fun PageBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.close))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun EffectsList(
    config: AiEffectsConfig,
    preview: AiEffectsConfig,
    onChange: ((AiEffectsConfig) -> AiEffectsConfig) -> Unit,
    onDismiss: () -> Unit,
    onOpen: (AiEffect) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PageBar(stringResource(R.string.ai_effects_title), onDismiss) {
            TextButton(onClick = { onChange { it.resetCustom() } }) { Text(stringResource(R.string.ai_effects_reset)) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ai_base_palette), style = MaterialTheme.typography.titleSmall)
            SettingsSegmentedRow(
                options = listOf(
                    AiPalette.MULTI_COLOR to stringResource(R.string.ai_preset_multi),
                    AiPalette.SYSTEM to stringResource(R.string.ai_preset_system),
                ),
                selected = config.customBase,
                onSelect = { base -> onChange { it.copy(customBase = base) } },
            )
            Spacer(Modifier.height(6.dp))
            for (effect in AiEffect.entries) {
                val style = preview.style(effect)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { onOpen(effect) },
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(effect.titleRes), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(effect.bodyRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = style.enabled, onCheckedChange = { on -> onChange { c -> c.withCustom(effect) { it.copy(enabled = on) } } })
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val AiEffect.titleRes: Int
    get() = when (this) {
        AiEffect.SETTINGS_RING -> R.string.ai_effect_settings_ring
        AiEffect.SEARCH_FIELD -> R.string.ai_effect_search_field
        AiEffect.SEARCH_BACKGROUND -> R.string.ai_effect_search_background
        AiEffect.RESULTS_EDGE -> R.string.ai_effect_results_edge
        AiEffect.LOADER -> R.string.ai_effect_loader
        AiEffect.FOLDER_GLINT -> R.string.ai_effect_folder_glint
        AiEffect.RESULTS_NEXUS -> R.string.ai_effect_results_nexus
    }

private val AiEffect.bodyRes: Int
    get() = when (this) {
        AiEffect.SETTINGS_RING -> R.string.ai_effect_settings_ring_body
        AiEffect.SEARCH_FIELD -> R.string.ai_effect_search_field_body
        AiEffect.SEARCH_BACKGROUND -> R.string.ai_effect_search_background_body
        AiEffect.RESULTS_EDGE -> R.string.ai_effect_results_edge_body
        AiEffect.LOADER -> R.string.ai_effect_loader_body
        AiEffect.FOLDER_GLINT -> R.string.ai_effect_folder_glint_body
        AiEffect.RESULTS_NEXUS -> R.string.ai_effect_results_nexus_body
    }

/** One effect's page: its preview, the switch, then its options. */
@Composable
private fun EffectPage(
    effect: AiEffect,
    config: AiEffectsConfig,
    preview: AiEffectsConfig,
    onChange: ((AiEffectsConfig) -> AiEffectsConfig) -> Unit,
    onBack: () -> Unit,
) {
    val style = preview.style(effect)
    val update: ((AiEffectStyle) -> AiEffectStyle) -> Unit = { t -> onChange { it.withCustom(effect, t) } }
    Column(Modifier.fillMaxSize()) {
        PageBar(stringResource(effect.titleRes), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EffectPreview(effect, preview)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(effect.bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Switch(checked = style.enabled, onCheckedChange = { on -> update { it.copy(enabled = on) } })
            }
            if (effect == AiEffect.LOADER) {
                SettingsSegmentedRow(
                    options = listOf(
                        AiLoaderStyle.ORB to stringResource(R.string.ai_loader_orb),
                        AiLoaderStyle.RING to stringResource(R.string.ai_loader_ring),
                        AiLoaderStyle.DOTS to stringResource(R.string.ai_loader_dots),
                    ),
                    selected = config.customLoaderStyle,
                    onSelect = { s -> onChange { it.copy(customLoaderStyle = s) } },
                )
            }
            if (effect == AiEffect.SEARCH_BACKGROUND) {
                Text(stringResource(R.string.ai_curtain_edge), style = MaterialTheme.typography.titleSmall)
                SettingsSegmentedRow(
                    options = listOf(
                        AiCurtainEdge.TOP to stringResource(R.string.ai_curtain_top),
                        AiCurtainEdge.BOTTOM to stringResource(R.string.ai_curtain_bottom),
                        AiCurtainEdge.LEFT to stringResource(R.string.ai_curtain_left),
                        AiCurtainEdge.RIGHT to stringResource(R.string.ai_curtain_right),
                        AiCurtainEdge.SIDES to stringResource(R.string.ai_curtain_sides),
                    ),
                    selected = config.customCurtainEdge,
                    onSelect = { e -> onChange { it.copy(customCurtainEdge = e) } },
                )
            }
            if (effect.canBeBehind) {
                SettingsSegmentedRow(
                    options = listOf(false to stringResource(R.string.ai_layer_above), true to stringResource(R.string.ai_layer_behind)),
                    selected = style.behind,
                    onSelect = { b -> update { it.copy(behind = b) } },
                )
            }
            if (effect.canBeTimed) {
                Column {
                    SettingsSegmentedRow(
                        options = listOf(false to stringResource(R.string.ai_mode_lasting), true to stringResource(R.string.ai_mode_timed)),
                        selected = style.timed,
                        onSelect = { t -> update { it.copy(timed = t) } },
                    )
                    // Right under the switch it belongs to.
                    AnimatedVisibility(visible = style.timed) {
                        DurationSlider(style, update, Modifier.padding(top = 12.dp))
                    }
                }
            } else if (effect.alwaysTimed) {
                DurationSlider(style, update)
            }
            ColorsEditor(effect, config, preview, style, update, onChange)
            if (effect == AiEffect.RESULTS_NEXUS) {
                ValueSlider(
                    R.string.ai_count,
                    style.count.toFloat(),
                    AiEffectStyle.MIN_COUNT.toFloat()..AiEffectStyle.MAX_COUNT.toFloat(),
                    style.count.toString(),
                    steps = AiEffectStyle.MAX_COUNT - AiEffectStyle.MIN_COUNT - 1,
                ) { v -> update { it.copy(count = v.roundToInt()) } }
            }
            ValueSlider(R.string.ai_brightness, style.brightness, AiEffectStyle.MIN_BRIGHTNESS..AiEffectStyle.MAX_BRIGHTNESS, percent(style.brightness)) { v ->
                update { it.copy(brightness = v) }
            }
            ValueSlider(R.string.ai_speed, style.speed, AiEffectStyle.MIN_SPEED..AiEffectStyle.MAX_SPEED, "×" + String.format(Locale.ROOT, "%.2f", style.speed)) { v ->
                update { it.copy(speed = v) }
            }
            ValueSlider(R.string.ai_width, style.width, AiEffectStyle.MIN_WIDTH..AiEffectStyle.MAX_WIDTH, percent(style.width)) { v ->
                update { it.copy(width = v) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DurationSlider(style: AiEffectStyle, update: ((AiEffectStyle) -> AiEffectStyle) -> Unit, modifier: Modifier = Modifier) {
    ValueSlider(
        R.string.ai_duration,
        style.durationMs / 1000f,
        AiEffectStyle.MIN_DURATION_MS / 1000f..AiEffectStyle.MAX_DURATION_MS / 1000f,
        String.format(Locale.ROOT, "%.1f s", style.durationMs / 1000f),
        modifier = modifier,
    ) { v -> update { it.copy(durationMs = (v * 1000).toInt()) } }
}

private fun percent(v: Float) = "${(v * 100).toInt()}%"

/** Previews: lasting effects stay on, timed ones repeat (on, then 2 s off). */
@Composable
private fun previewStrength(config: AiEffectsConfig, effect: AiEffect): State<Float> =
    if (config.isTimed(effect)) rememberLoopingGlow(config.style(effect).durationMs) else remember { mutableFloatStateOf(1f) }

private const val MOCK_COLUMNS = 5
private const val MOCK_ROWS = 3
private val MOCK_TILE = 36.dp
private val MOCK_GAP = 8.dp
private val PREVIEW_RADIUS = 16.dp

/** A small grid of placeholder tiles, centered; returns them for the Nexus preview. */
@Composable
private fun BoxScope.MockTiles(): State<List<NexusCell>?> {
    val cells = remember { mutableStateOf<List<NexusCell>?>(null) }
    val density = LocalDensity.current
    Column(
        Modifier
            .align(Alignment.Center)
            .onGloballyPositioned { coords ->
                val origin = coords.positionInParent()
                val t = with(density) { MOCK_TILE.toPx() }
                val g = with(density) { MOCK_GAP.toPx() }
                cells.value = (0 until MOCK_ROWS).flatMap { row ->
                    (0 until MOCK_COLUMNS).map { col ->
                        val left = origin.x + col * (t + g)
                        val top = origin.y + row * (t + g)
                        NexusCell(Rect(left, top, left + t, top + t), row, col)
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(MOCK_GAP),
    ) {
        repeat(MOCK_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(MOCK_GAP)) {
                repeat(MOCK_COLUMNS) {
                    Box(Modifier.size(MOCK_TILE).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2E39)))
                }
            }
        }
    }
    return cells
}

/** A small live example of [effect] with the current settings; one-off effects repeat. */
@Composable
private fun EffectPreview(effect: AiEffect, config: AiEffectsConfig) {
    val style = config.style(effect)
    // Effects of the whole screen get a taller, more screen-like preview.
    val screenLike = effect == AiEffect.SEARCH_BACKGROUND || effect == AiEffect.RESULTS_EDGE || effect == AiEffect.RESULTS_NEXUS
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (screenLike) 200.dp else 150.dp)
            .clip(RoundedCornerShape(PREVIEW_RADIUS))
            .background(Color(0xFF0E1016)),
        contentAlignment = Alignment.Center,
    ) {
        when (effect) {
            AiEffect.SETTINGS_RING -> {
                val strength by rememberLoopingGlow(style.durationMs)
                val glow = if (style.enabled && strength > 0f) {
                    Modifier.aiGlowHalo(aiColors(config, effect), style, 12.dp, rememberAiPhase(style.speed, 2_000), { strength }, glow = 10.dp)
                } else {
                    Modifier
                }
                Row(
                    Modifier
                        .padding(horizontal = 28.dp)
                        .fillMaxWidth()
                        .then(glow)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1C1F27))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.smart_search_title), color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = true, onCheckedChange = null)
                }
            }
            AiEffect.SEARCH_FIELD -> {
                val strength by previewStrength(config, effect)
                val glow = if (style.enabled && strength > 0f) {
                    Modifier.aiGlowHalo(aiColors(config, effect), style, 28.dp, rememberAiPhase(style.speed), { strength }, glow = 10.dp)
                } else {
                    Modifier
                }
                Box(
                    Modifier
                        .padding(horizontal = 28.dp)
                        .fillMaxWidth()
                        .height(52.dp)
                        .then(glow)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFF1C1F27))
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(stringResource(R.string.ai_preview_search), color = Color(0xFFB8BCC8))
                }
            }
            AiEffect.SEARCH_BACKGROUND -> {
                if (style.behind) AiCurtainGlow(config, active = true, modifier = Modifier.matchParentSize(), loop = true)
                MockTiles()
                if (!style.behind) AiCurtainGlow(config, active = true, modifier = Modifier.matchParentSize(), loop = true)
            }
            AiEffect.RESULTS_EDGE -> {
                // Its corners follow the preview's own rounding (on the phone: the screen's).
                if (style.behind) AiEdgeGlow(config, trigger = 1, modifier = Modifier.matchParentSize(), loop = true, cornerRadius = PREVIEW_RADIUS)
                MockTiles()
                if (!style.behind) AiEdgeGlow(config, trigger = 1, modifier = Modifier.matchParentSize(), loop = true, cornerRadius = PREVIEW_RADIUS)
            }
            AiEffect.LOADER -> AiLoader(config)
            AiEffect.FOLDER_GLINT -> {
                val strength = previewStrength(config, effect)
                val renderer = remember { AiGlowRenderer() }
                val glint = if (style.enabled) {
                    AiGlint(aiColors(config, effect), style, rememberAiPhase(style.speed, periodMs = GLINT_PERIOD_MS), strength, renderer)
                } else {
                    null
                }
                // Square like the folder grid's tiles.
                Box(
                    Modifier
                        .size(118.dp)
                        .background(Color(0xFF2A2E39))
                        .aiGlint(glint),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Text("DCIM", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(10.dp))
                }
            }
            AiEffect.RESULTS_NEXUS -> {
                val tiles = MockTiles()
                val gap = with(LocalDensity.current) { MOCK_GAP.toPx() }
                AiNexus(
                    config = config,
                    active = true,
                    grid = { tiles.value?.let { NexusGrid.of(it, gap, scrollY = 0f, endsHere = true) } },
                    modifier = Modifier.matchParentSize(),
                    loop = true,
                )
            }
        }
    }
}

/**
 * The effect's colors: the style's or its own (up to [AiEffectStyle.MAX_COLORS]), and the saved
 * palettes shared by all effects: tap one to use it here, hold it to delete it.
 */
@Composable
private fun ColorsEditor(
    effect: AiEffect,
    config: AiEffectsConfig,
    preview: AiEffectsConfig,
    style: AiEffectStyle,
    update: ((AiEffectStyle) -> AiEffectStyle) -> Unit,
    onChange: ((AiEffectsConfig) -> AiEffectsConfig) -> Unit,
) {
    val resolved = aiColors(preview, effect)
    var editing by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    Text(stringResource(R.string.ai_colors), style = MaterialTheme.typography.titleSmall)
    SettingsSegmentedRow(
        options = listOf(false to stringResource(R.string.ai_colors_palette), true to stringResource(R.string.ai_colors_own)),
        selected = style.customColors != null,
        onSelect = { own ->
            update { s ->
                s.copy(customColors = if (own) resolved.take(AiEffectStyle.MAX_COLORS).map { it.toArgb() } else null)
            }
        },
    )
    val own = style.customColors
    if (own != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            own.forEachIndexed { i, argb ->
                Box {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { editing = i },
                    )
                    if (own.size > 1) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { update { s -> s.copy(customColors = own.filterIndexed { j, _ -> j != i }) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ai_color_remove), modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
            if (own.size < AiEffectStyle.MAX_COLORS) {
                IconButton(onClick = { editing = own.size }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.ai_color_add))
                }
            }
        }
        val saved = own in config.savedPalettes
        FilledTonalButton(
            onClick = { onChange { it.withSavedPalette(own) } },
            enabled = config.canSavePalette && !saved,
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_palette_save))
        }
        if (!config.canSavePalette && !saved) {
            Text(stringResource(R.string.ai_palettes_full), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (config.savedPalettes.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_palettes_saved), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                config.savedPalettes.forEachIndexed { i, palette ->
                    SavedPalette(
                        colors = palette,
                        selected = palette == own,
                        onClick = { update { it.copy(customColors = palette) } },
                        onLongClick = { deleting = i },
                    )
                }
            }
            Text(stringResource(R.string.ai_palettes_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    editing?.let { index ->
        val list = own ?: return@let
        Dialog(onDismissRequest = { editing = null }) {
            AdvancedColorPickerView(
                initialColor = list.getOrNull(index) ?: list.last(),
                onColorSelected = { argb ->
                    update { s ->
                        val colors = (s.customColors ?: list).toMutableList()
                        if (index < colors.size) colors[index] = argb else colors += argb
                        s.copy(customColors = colors.take(AiEffectStyle.MAX_COLORS))
                    }
                },
                onOpenEyedropper = {},
                onClose = { editing = null },
            )
        }
    }
    deleting?.let { index ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.ai_palette_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onChange { it.withoutSavedPalette(index) }
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** A saved palette: its colors as overlapping dots on a small card. */
@Composable
private fun SavedPalette(colors: List<Int>, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).size(width = (14 * colors.size + 10).dp, height = 24.dp)) {
            colors.forEachIndexed { i, argb ->
                Box(
                    Modifier
                        .offset(x = (14 * i).dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun ValueSlider(
    labelRes: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    shown: String,
    steps: Int = 0,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}
