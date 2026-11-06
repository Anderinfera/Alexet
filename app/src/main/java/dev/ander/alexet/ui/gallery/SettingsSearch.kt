package dev.ander.alexet.ui.gallery

import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.withFrameNanos
import android.os.SystemClock
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R
import dev.ander.alexet.data.AppSettings
import dev.ander.alexet.data.ContentViewMode
import dev.ander.alexet.data.SponsorBlockCategory
import dev.ander.alexet.ui.components.LocalLightMode
import kotlinx.coroutines.delay
import java.text.Normalizer

/**
 * One option the settings search can find. [key] is the string id the option is marked with on
 * its screen (see [settingsSearchTarget]); [titleRes] and [summaryRes] are what the result shows,
 * [extraRes] only adds words to match. [visible] hides options that are not on screen right now
 * (e.g. the parts under a switch that is off).
 */
class SettingsSearchEntry(
    @StringRes val key: Int,
    val category: SettingsCategory,
    @StringRes val summaryRes: Int? = null,
    @StringRes val titleRes: Int = key,
    val extraRes: List<Int> = emptyList(),
    val visible: (AppSettings) -> Boolean = { true },
)

/**
 * "Extra view" options (folders and contents side by side, e.g. a phone turned sideways): on a
 * foldable, or with the switch in the "Extra" card.
 */
val AppSettings.extraViewSettingsShown: Boolean
    get() = foldableDetected || showFoldableSettings

/** Options that only a foldable can use (the fold, the second screen): only on one. */
val AppSettings.foldOnlySettingsShown: Boolean
    get() = foldableDetected

private val extraViewOnly: (AppSettings) -> Boolean = { it.extraViewSettingsShown }
private val foldOnly: (AppSettings) -> Boolean = { it.foldOnlySettingsShown }
private val notWide: (AppSettings) -> Boolean = { it.extraViewSettingsShown && it.contentViewMode != ContentViewMode.EXPAND_FOLDERS }

val settingsSearchEntries: List<SettingsSearchEntry> = listOf(
    // View
    SettingsSearchEntry(
        R.string.content_view_title, SettingsCategory.VIEW,
        extraRes = listOf(R.string.content_view_last, R.string.content_view_expand, R.string.content_view_click_drag),
        visible = extraViewOnly,
    ),
    SettingsSearchEntry(
        R.string.expand_grid_size, SettingsCategory.VIEW, R.string.settings_expand_grid_body,
        extraRes = listOf(R.string.settings_columns_label, R.string.settings_rows_label),
        visible = { it.extraViewSettingsShown && it.contentViewMode == ContentViewMode.EXPAND_FOLDERS },
    ),
    SettingsSearchEntry(R.string.settings_file_columns_label, SettingsCategory.VIEW, R.string.settings_file_columns_desc, visible = notWide),
    SettingsSearchEntry(R.string.pane_split_title, SettingsCategory.VIEW, visible = notWide),
    SettingsSearchEntry(R.string.auto_fold_split_title, SettingsCategory.VIEW, R.string.auto_fold_split_body, visible = { notWide(it) && foldOnly(it) }),
    SettingsSearchEntry(
        R.string.fold_split_angle_title, SettingsCategory.VIEW, R.string.fold_split_angle_body,
        visible = { notWide(it) && foldOnly(it) && it.autoFoldSplitEnabled },
    ),
    SettingsSearchEntry(R.string.show_file_names, SettingsCategory.VIEW, R.string.show_file_names_body),
    SettingsSearchEntry(R.string.grid_columns, SettingsCategory.VIEW, R.string.grid_columns_body),
    SettingsSearchEntry(R.string.file_columns_title, SettingsCategory.VIEW, R.string.file_columns_body),
    SettingsSearchEntry(R.string.show_all_files_title, SettingsCategory.VIEW, R.string.show_all_files_body),
    SettingsSearchEntry(R.string.show_other_file_folders_title, SettingsCategory.VIEW, R.string.show_other_file_folders_body),
    SettingsSearchEntry(R.string.video_duration_format_title, SettingsCategory.VIEW, R.string.video_duration_format_body),
    SettingsSearchEntry(R.string.light_mode_title, SettingsCategory.VIEW, R.string.light_mode_body),
    SettingsSearchEntry(R.string.thumbnails_high_res_title, SettingsCategory.VIEW, R.string.thumbnails_high_res_body),
    SettingsSearchEntry(R.string.scroller_enabled_title, SettingsCategory.VIEW, R.string.scroller_enabled_body),
    SettingsSearchEntry(
        R.string.scroller_position_title, SettingsCategory.VIEW,
        extraRes = listOf(R.string.scroller_left, R.string.scroller_right),
        visible = { it.scrollerEnabled },
    ),
    SettingsSearchEntry(R.string.scroller_smooth_title, SettingsCategory.VIEW, R.string.scroller_smooth_body, visible = { it.scrollerEnabled }),
    SettingsSearchEntry(R.string.wallpaper_resolutions_title, SettingsCategory.VIEW, R.string.wallpaper_resolutions_desc, visible = foldOnly),
    // Folders
    SettingsSearchEntry(R.string.multi_folder_memory_title, SettingsCategory.FOLDERS, R.string.multi_folder_memory_body, visible = extraViewOnly),
    SettingsSearchEntry(R.string.remember_folder_scroll, SettingsCategory.FOLDERS, R.string.remember_folder_scroll_body),
    SettingsSearchEntry(R.string.new_content_scrolls_to_top, SettingsCategory.FOLDERS, R.string.new_content_scrolls_to_top_body),
    SettingsSearchEntry(R.string.show_hidden, SettingsCategory.FOLDERS, R.string.show_hidden_body),
    SettingsSearchEntry(R.string.hide_favorites_folder, SettingsCategory.FOLDERS, R.string.settings_hide_favorites_short),
    SettingsSearchEntry(R.string.hidden_cover_title, SettingsCategory.FOLDERS, R.string.hidden_cover_hint),
    // Privacy
    SettingsSearchEntry(R.string.secure_folder_settings_title, SettingsCategory.PRIVACY, R.string.secure_folder_settings_desc),
    SettingsSearchEntry(R.string.privatized_folders_title, SettingsCategory.PRIVACY, R.string.privatized_folders_desc),
    SettingsSearchEntry(R.string.randomize_file_names_title, SettingsCategory.PRIVACY, R.string.randomize_file_names_body),
    SettingsSearchEntry(R.string.strip_exif_title, SettingsCategory.PRIVACY, R.string.strip_exif_in_private_folders_body),
    SettingsSearchEntry(R.string.strip_exif_on_share_title, SettingsCategory.PRIVACY, R.string.strip_exif_on_share_body),
    // Player
    SettingsSearchEntry(R.string.newpipe_folder_title, SettingsCategory.PLAYER, R.string.newpipe_folder_desc),
    SettingsSearchEntry(R.string.video_double_click_gallery, SettingsCategory.PLAYER, R.string.video_double_click_gallery_body),
    SettingsSearchEntry(R.string.player_volume_gesture_title, SettingsCategory.PLAYER, R.string.player_volume_gesture_body),
    SettingsSearchEntry(R.string.player_brightness_gesture_title, SettingsCategory.PLAYER, R.string.player_brightness_gesture_body),
    SettingsSearchEntry(
        R.string.player_long_press_speed_title, SettingsCategory.PLAYER, R.string.player_long_press_speed_body,
        extraRes = listOf(R.string.player_long_press_speed_selector),
    ),
    SettingsSearchEntry(
        R.string.player_double_tap_seek_title, SettingsCategory.PLAYER, R.string.player_double_tap_seek_body,
        extraRes = listOf(R.string.player_double_tap_seek_selector),
    ),
    SettingsSearchEntry(R.string.player_auto_hide_selector, SettingsCategory.PLAYER),
    // One row opens the SponsorBlock page: its options are found through it.
    SettingsSearchEntry(
        R.string.sponsorblock, SettingsCategory.PLAYER, R.string.sponsorblock_enable_description,
        extraRes = listOf(R.string.sponsorblock_enable, R.string.sponsorblock_auto_skip) +
            SponsorBlockCategory.entries.map { it.nameRes },
    ),
    SettingsSearchEntry(R.string.autoplay_gifs, SettingsCategory.PLAYER, R.string.autoplay_gifs_body),
    SettingsSearchEntry(R.string.nav_arrows_title, SettingsCategory.PLAYER, R.string.nav_arrows_body),
    SettingsSearchEntry(R.string.search_files_fullscreen, SettingsCategory.PLAYER, R.string.search_files_fullscreen_body),
    SettingsSearchEntry(
        R.string.settings_select_style_label, SettingsCategory.PLAYER, R.string.settings_select_style_hint,
        titleRes = R.string.settings_select_style_title,
    ),
    SettingsSearchEntry(R.string.operations_follow_selection_order, SettingsCategory.PLAYER, R.string.operations_follow_selection_order_body),
    SettingsSearchEntry(R.string.action_cards_order, SettingsCategory.PLAYER, R.string.action_cards_order_desc),
    // Extra
    SettingsSearchEntry(R.string.show_foldable_settings_title, SettingsCategory.EXTRA, R.string.show_foldable_settings_body),
    SettingsSearchEntry(R.string.smart_search_title, SettingsCategory.EXTRA, R.string.smart_search_body),
    SettingsSearchEntry(R.string.smart_model_import, SettingsCategory.EXTRA, R.string.smart_model_missing),
    SettingsSearchEntry(R.string.ai_effects_title, SettingsCategory.EXTRA, R.string.ai_effects_body),
    SettingsSearchEntry(
        R.string.smart_index_folders_title,
        SettingsCategory.EXTRA,
        R.string.smart_index_folders_body,
        visible = { it.smartSearchEnabled },
    ),
    // System
    SettingsSearchEntry(R.string.config_export, SettingsCategory.SYSTEM, R.string.config_export_body),
    SettingsSearchEntry(R.string.config_import, SettingsCategory.SYSTEM, R.string.config_import_body),
    SettingsSearchEntry(R.string.rescan, SettingsCategory.SYSTEM, R.string.rescan_body),
    SettingsSearchEntry(R.string.debug_logging, SettingsCategory.SYSTEM, R.string.debug_logging_body),
    SettingsSearchEntry(R.string.debug_log_clear, SettingsCategory.SYSTEM, R.string.debug_log_clear_body, visible = { it.debugLogging }),
)

/** Lower case without accents, so "wyczysc" finds "Wyczyść". */
fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .replace('ł', 'l')

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** A jump to one option; a new object per tap, so tapping the same result again pulses again. */
class SettingsJump(@StringRes val key: Int) {
    val createdAt: Long = SystemClock.uptimeMillis()
}

/**
 * A jump nobody took up within this time is dropped: an option that is not on screen (e.g. under a
 * switch that is off) must not flash out of the blue when it shows up much later.
 */
private const val JUMP_TAKE_UP_MS = 1_500L

/** Which option a search result asked for; the marked option scrolls itself into view and pulses. */
class SettingsJumpController {
    var target by mutableStateOf<SettingsJump?>(null)
        private set

    fun jumpTo(@StringRes key: Int) {
        target = SettingsJump(key)
    }

    fun finish(jump: SettingsJump) {
        if (target === jump) target = null
    }
}

val LocalSettingsJump = staticCompositionLocalOf<SettingsJumpController?> { null }

private val PULSES_MS = listOf(300, 300, 600)

/**
 * Marks a settings option for the search: when a result for [key] is tapped, the option scrolls
 * into view and flashes: twice quickly, once longer (light mode: a steady highlight as long).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.settingsSearchTarget(@StringRes key: Int): Modifier = composed {
    val controller = LocalSettingsJump.current
    val jump = controller?.target?.takeIf { it.key == key }
    val light = LocalLightMode.current
    val color = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var glow by remember { mutableFloatStateOf(0f) }
    // Where the option is on screen; moves while the category screen slides in.
    val windowY = remember { floatArrayOf(Float.NaN) }

    LaunchedEffect(jump) {
        if (jump == null || controller == null) {
            glow = 0f
            return@LaunchedEffect
        }
        if (SystemClock.uptimeMillis() - jump.createdAt > JUMP_TAKE_UP_MS) {
            controller.finish(jump)
            return@LaunchedEffect
        }
        try {
            // Scroll once the category screen stopped moving (its slide-in ended), not after a
            // fixed time that a slow first frame can outlast. At most ~1 s of waiting.
            var lastY = Float.NaN
            var still = 0
            var frames = 0
            while (still < 3 && frames < 60) {
                withFrameNanos { }
                frames++
                val y = windowY[0]
                still = if (!y.isNaN() && y == lastY) still + 1 else 0
                lastY = y
            }
            val margin = with(density) { 96.dp.toPx() }
            requester.bringIntoView(Rect(0f, -margin, size.width.toFloat(), size.height + margin))
            if (light) {
                glow = 1f
                delay(PULSES_MS.sum().toLong())
            } else {
                // Two quick flashes (0.3 s) and one longer (0.6 s), each up and back down.
                for (ms in PULSES_MS) {
                    animate(glow, 1f, animationSpec = tween(ms * 2 / 5, easing = FastOutSlowInEasing)) { v, _ -> glow = v }
                    animate(glow, 0f, animationSpec = tween(ms * 3 / 5, easing = FastOutSlowInEasing)) { v, _ -> glow = v }
                }
            }
        } finally {
            glow = 0f
            controller.finish(jump)
        }
    }

    Modifier
        .onSizeChanged { size = it }
        .onGloballyPositioned { windowY[0] = it.positionInWindow().y }
        .bringIntoViewRequester(requester)
        .drawWithContent {
            drawContent()
            if (glow > 0f) {
                drawRoundRect(
                    color = color.copy(alpha = 0.24f * glow),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                )
            }
        }
}
