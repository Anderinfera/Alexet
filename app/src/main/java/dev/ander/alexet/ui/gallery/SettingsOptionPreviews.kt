package dev.ander.alexet.ui.gallery

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import dev.ander.alexet.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ander.alexet.ui.components.LocalLightMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Settings option shown by [OptionPreview] (the player's own ones are [PlayerOptionPreview]). */
internal enum class OptionPreviewKind {
    EXTRA_VIEW,
    DYNAMIC_SPLIT,
    FOLD_SPLIT,
    DURATION_FORMAT,
    SCROLLER,
    SMOOTH_SCROLL,
    ROW_SCROLL,
    ALL_FILES,
    OTHER_FILE_FOLDERS,
    HIDE_FAVORITES,
    REMEMBER_SCROLL,
    NEW_CONTENT_TOP,
    NEWPIPE,
    SELECT_STYLE,
    SELECTION_ORDER,
    SPONSORBLOCK,
    SEARCH_FULLSCREEN,
    AI_SEARCH,
    SHOW_HIDDEN,
    RANDOM_NAMES,
    STRIP_EXIF,
    STRIP_EXIF_SHARE,
    SHARP_THUMBNAILS,
}

private val SponsorGreen = Color(0xFF00D400)

/**
 * A small screen acting out a settings option in a loop, in the style of [PlayerOptionPreview]:
 * an option that is off stands dimmed and still, light mode shows one still frame. [fullDuration]
 * and [linearSelect] follow the chosen video duration format and selection style. A tap opens a
 * card with the picture enlarged and a short explanation.
 */
@Composable
internal fun OptionPreview(
    kind: OptionPreviewKind,
    active: Boolean,
    modifier: Modifier = Modifier,
    fullDuration: Boolean = false,
    linearSelect: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    OptionPreviewCanvas(
        kind = kind,
        active = active,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true },
        fullDuration = fullDuration,
        linearSelect = linearSelect,
    )
    if (open) {
        val (title, info) = optionPreviewInfo(kind)
        PreviewInfoCard(
            title = stringResource(title),
            body = stringResource(info),
            onDismiss = { open = false },
        ) { scaled ->
            // Always moving here: the card is there to show what the option does.
            OptionPreviewCanvas(kind, active = true, modifier = scaled, fullDuration = fullDuration, linearSelect = linearSelect)
        }
    }
}

/**
 * The option's name, its picture enlarged to the card's width (the same drawing, scaled: sharp and
 * no dearer) and a short explanation under it.
 */
@Composable
internal fun PreviewInfoCard(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    previewWidth: Dp = 56.dp,
    previewHeight: Dp = 40.dp,
    preview: @Composable (Modifier) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = title.trimEnd(':', ' '),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val k = minOf(maxWidth / previewWidth, 220.dp / previewHeight, 5f).coerceAtLeast(1f)
                    Box(Modifier.size(previewWidth * k, previewHeight * k), contentAlignment = Alignment.Center) {
                        preview(Modifier.graphicsLayer { scaleX = k; scaleY = k })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.action_ok), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Name and explanation of each option picture. */
private fun optionPreviewInfo(kind: OptionPreviewKind): Pair<Int, Int> = when (kind) {
    OptionPreviewKind.EXTRA_VIEW -> R.string.show_foldable_settings_title to R.string.preview_info_extra_view
    OptionPreviewKind.DYNAMIC_SPLIT -> R.string.dynamic_split_title to R.string.preview_info_dynamic_split
    OptionPreviewKind.FOLD_SPLIT -> R.string.auto_fold_split_title to R.string.preview_info_fold_split
    OptionPreviewKind.DURATION_FORMAT -> R.string.video_duration_format_title to R.string.preview_info_duration_format
    OptionPreviewKind.SCROLLER -> R.string.scroller_enabled_title to R.string.preview_info_scroller
    OptionPreviewKind.SMOOTH_SCROLL -> R.string.scroller_smooth_title to R.string.preview_info_smooth_scroll
    OptionPreviewKind.ROW_SCROLL -> R.string.scroller_smooth_title to R.string.preview_info_row_scroll
    OptionPreviewKind.ALL_FILES -> R.string.show_all_files_title to R.string.preview_info_all_files
    OptionPreviewKind.OTHER_FILE_FOLDERS -> R.string.show_other_file_folders_title to R.string.preview_info_other_file_folders
    OptionPreviewKind.HIDE_FAVORITES -> R.string.hide_favorites_folder to R.string.preview_info_hide_favorites
    OptionPreviewKind.REMEMBER_SCROLL -> R.string.remember_folder_scroll to R.string.preview_info_remember_scroll
    OptionPreviewKind.NEW_CONTENT_TOP -> R.string.new_content_scrolls_to_top to R.string.preview_info_new_content_top
    OptionPreviewKind.NEWPIPE -> R.string.newpipe_folder_title to R.string.preview_info_newpipe
    OptionPreviewKind.SELECT_STYLE -> R.string.settings_select_style_title to R.string.preview_info_select_style
    OptionPreviewKind.SELECTION_ORDER -> R.string.operations_follow_selection_order to R.string.preview_info_selection_order
    OptionPreviewKind.SPONSORBLOCK -> R.string.sponsorblock to R.string.preview_info_sponsorblock
    OptionPreviewKind.SEARCH_FULLSCREEN -> R.string.search_files_fullscreen to R.string.preview_info_search_fullscreen
    OptionPreviewKind.AI_SEARCH -> R.string.smart_search_title to R.string.preview_info_ai_search
    OptionPreviewKind.SHOW_HIDDEN -> R.string.show_hidden to R.string.preview_info_show_hidden
    OptionPreviewKind.RANDOM_NAMES -> R.string.randomize_file_names_title to R.string.preview_info_random_names
    OptionPreviewKind.STRIP_EXIF -> R.string.strip_exif_title to R.string.preview_info_strip_exif
    OptionPreviewKind.STRIP_EXIF_SHARE -> R.string.strip_exif_on_share_title to R.string.preview_info_strip_exif_share
    OptionPreviewKind.SHARP_THUMBNAILS -> R.string.thumbnails_high_res_title to R.string.preview_info_sharp_thumbnails
}

@Composable
private fun OptionPreviewCanvas(
    kind: OptionPreviewKind,
    active: Boolean,
    modifier: Modifier = Modifier,
    fullDuration: Boolean = false,
    linearSelect: Boolean = false,
) {
    val cycleMs = when (kind) {
        OptionPreviewKind.DURATION_FORMAT -> 2400
        OptionPreviewKind.SMOOTH_SCROLL -> 2600
        OptionPreviewKind.ROW_SCROLL -> 3200
        OptionPreviewKind.SPONSORBLOCK -> 3200
        OptionPreviewKind.REMEMBER_SCROLL -> 3600
        else -> 3000
    }
    val stillFrame = when (kind) {
        OptionPreviewKind.DURATION_FORMAT -> 0.5f
        OptionPreviewKind.SMOOTH_SCROLL -> 0.15f
        OptionPreviewKind.ROW_SCROLL -> 0.15f
        OptionPreviewKind.SPONSORBLOCK -> 0.3f
        OptionPreviewKind.REMEMBER_SCROLL -> 0.2f
        OptionPreviewKind.NEWPIPE -> 0.4f
        else -> 0.65f
    }
    // Read only while drawing: the frames redraw the canvas without recomposing the row.
    val time: State<Float>? = if (active && !LocalLightMode.current) {
        key(cycleMs) {
            rememberInfiniteTransition(label = "optionPreview").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(cycleMs, easing = LinearEasing), RepeatMode.Restart),
                label = "optionPreviewTime",
            )
        }
    } else {
        null
    }
    val accent = MaterialTheme.colorScheme.primary
    val textMeasurer = rememberTextMeasurer()
    val texts: List<Pair<String, TextUnit>> = when (kind) {
        OptionPreviewKind.DURATION_FORMAT -> listOf((if (fullDuration) "0:12:34" else "12:34") to 7.sp)
        OptionPreviewKind.ALL_FILES -> listOf("APK" to 5.5.sp)
        OptionPreviewKind.SELECTION_ORDER -> listOf("1" to 6.sp, "2" to 6.sp, "3" to 6.sp)
        OptionPreviewKind.RANDOM_NAMES -> listOf("IMG_2034.jpg" to 6.5.sp, "k3f9a2x.jpg" to 6.5.sp)
        else -> emptyList()
    }
    val labels = remember(texts, textMeasurer) {
        texts.map { (text, size) ->
            textMeasurer.measure(text, TextStyle(color = PreviewWhite, fontSize = size, fontWeight = FontWeight.Bold))
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Canvas(
        modifier
            .size(width = 56.dp, height = 40.dp)
            .graphicsLayer { alpha = if (active) 1f else 0.45f }
            .clip(shape),
    ) {
        val t = time?.value ?: stillFrame
        drawRect(PreviewScreen)
        when (kind) {
            OptionPreviewKind.EXTRA_VIEW -> drawExtraView(t, accent)
            OptionPreviewKind.DYNAMIC_SPLIT -> drawDynamicSplit(t, accent)
            OptionPreviewKind.FOLD_SPLIT -> drawFoldSplit(t, accent)
            OptionPreviewKind.DURATION_FORMAT -> drawDuration(t, accent, labels.firstOrNull())
            OptionPreviewKind.SCROLLER -> drawScroller(t, accent)
            OptionPreviewKind.SMOOTH_SCROLL -> drawSmoothScroll(t, accent)
            OptionPreviewKind.ROW_SCROLL -> drawRowScroll(t, accent)
            OptionPreviewKind.ALL_FILES -> drawAllFiles(t, accent, labels.firstOrNull())
            OptionPreviewKind.OTHER_FILE_FOLDERS -> drawOtherFolders(t, accent)
            OptionPreviewKind.HIDE_FAVORITES -> drawHideFavorites(t, accent)
            OptionPreviewKind.REMEMBER_SCROLL -> drawRememberScroll(t, accent)
            OptionPreviewKind.NEW_CONTENT_TOP -> drawNewContentTop(t, accent)
            OptionPreviewKind.NEWPIPE -> drawNewPipe(t, accent)
            OptionPreviewKind.SELECT_STYLE -> drawSelectStyle(t, accent, linearSelect)
            OptionPreviewKind.SELECTION_ORDER -> drawSelectionOrder(t, accent, labels)
            OptionPreviewKind.SPONSORBLOCK -> drawSponsorBlock(t, accent)
            OptionPreviewKind.SEARCH_FULLSCREEN -> drawSearchFullscreen(t, accent)
            OptionPreviewKind.AI_SEARCH -> drawAiSearch(t, accent)
            OptionPreviewKind.SHOW_HIDDEN -> drawShowHidden(t, accent)
            OptionPreviewKind.RANDOM_NAMES -> drawRandomNames(t, accent, labels)
            OptionPreviewKind.STRIP_EXIF -> drawStripExif(t, accent)
            OptionPreviewKind.STRIP_EXIF_SHARE -> drawStripExifShare(t, accent)
            OptionPreviewKind.SHARP_THUMBNAILS -> drawSharpThumbnails(t, accent)
        }
    }
}

// ----------------------------------------------------------------------------- shared pieces

private val DrawScope.u: Float get() = 1.dp.toPx()

/** [cols] × [rows] cells filling [area] with [gap] between them. */
private fun cells(area: Rect, cols: Int, rows: Int, gap: Float): List<Rect> {
    val cw = (area.width - gap * (cols - 1)) / cols
    val ch = (area.height - gap * (rows - 1)) / rows
    return List(cols * rows) { i ->
        val c = i % cols
        val r = i / cols
        Rect(Offset(area.left + c * (cw + gap), area.top + r * (ch + gap)), Size(cw, ch))
    }
}

/** Square cells of side [cell], [cols] × [rows], centred in the canvas. */
private fun DrawScope.squareCells(cols: Int, rows: Int, cell: Float, gap: Float, dy: Float = 0f): List<Rect> {
    val gw = cols * cell + (cols - 1) * gap
    val gh = rows * cell + (rows - 1) * gap
    val area = Rect(Offset((size.width - gw) / 2f, (size.height - gh) / 2f + dy), Size(gw, gh))
    return cells(area, cols, rows, gap)
}

private fun Rect.scaled(f: Float): Rect =
    Rect(Offset(center.x - width * f / 2f, center.y - height * f / 2f), Size(width * f, height * f))

private fun Rect.moved(dx: Float, dy: Float): Rect = Rect(Offset(left + dx, top + dy), size)

private fun lerpRect(a: Rect, b: Rect, f: Float): Rect =
    Rect(lerp(a.left, b.left, f), lerp(a.top, b.top, f), lerp(a.right, b.right, f), lerp(a.bottom, b.bottom, f))

private fun DrawScope.tile(r: Rect, color: Color, radius: Float = 1.5f * u) {
    if (r.width <= 0f || r.height <= 0f) return
    drawRoundRect(color, r.topLeft, r.size, CornerRadius(radius))
}

/** A folder: a tab on top of the body. */
private fun DrawScope.folder(r: Rect, color: Color) {
    if (r.width <= 0f || r.height <= 0f) return
    val tabH = r.height * 0.28f
    drawRoundRect(color, r.topLeft, Size(r.width * 0.45f, tabH), CornerRadius(1.2f * u))
    drawRoundRect(color, Offset(r.left, r.top + tabH * 0.55f), Size(r.width, r.height - tabH * 0.55f), CornerRadius(1.5f * u))
}

/** A page with a folded corner and two text lines. */
private fun DrawScope.docGlyph(c: Offset, r: Float, color: Color) {
    val w = r * 1.4f
    val h = r * 1.8f
    val l = c.x - w / 2f
    val t = c.y - h / 2f
    val fold = w * 0.35f
    val page = Path().apply {
        moveTo(l, t)
        lineTo(l + w - fold, t)
        lineTo(l + w, t + fold)
        lineTo(l + w, t + h)
        lineTo(l, t + h)
        close()
    }
    drawPath(page, color)
    val line = PreviewScreen.copy(alpha = 0.7f)
    drawLine(line, Offset(l + w * 0.2f, t + h * 0.5f), Offset(l + w * 0.8f, t + h * 0.5f), strokeWidth = 0.7f * u)
    drawLine(line, Offset(l + w * 0.2f, t + h * 0.7f), Offset(l + w * 0.65f, t + h * 0.7f), strokeWidth = 0.7f * u)
}

/** An archive: a box with a zipper down the middle. */
private fun DrawScope.zipGlyph(c: Offset, r: Float, color: Color) {
    drawRoundRect(color, Offset(c.x - r, c.y - r * 0.9f), Size(r * 2f, r * 1.8f), CornerRadius(0.8f * u))
    val dark = PreviewScreen.copy(alpha = 0.75f)
    var y = c.y - r * 0.75f
    var left = true
    while (y < c.y + r * 0.7f) {
        val x0 = if (left) c.x - r * 0.25f else c.x
        drawLine(dark, Offset(x0, y), Offset(x0 + r * 0.25f, y), strokeWidth = 0.7f * u)
        y += r * 0.3f
        left = !left
    }
}

private fun DrawScope.starGlyph(c: Offset, r: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val a = -PI.toFloat() / 2f + i * PI.toFloat() / 5f
        val rr = if (i % 2 == 0) r else r * 0.45f
        val p = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, color)
}

/** A four-point sparkle. */
private fun DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x, c.y, c.x + r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - r)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.eyeOffGlyph(c: Offset, r: Float, color: Color) {
    val stroke = Stroke(0.9f * u)
    drawOval(color, Offset(c.x - r, c.y - r * 0.55f), Size(r * 2f, r * 1.1f), style = stroke)
    drawCircle(color, radius = r * 0.3f, center = c)
    drawLine(color, Offset(c.x - r * 0.9f, c.y + r * 0.8f), Offset(c.x + r * 0.9f, c.y - r * 0.8f), strokeWidth = 1f * u, cap = StrokeCap.Round)
}

private fun DrawScope.pinGlyph(c: Offset, r: Float, color: Color) {
    val head = Offset(c.x, c.y - r * 0.3f)
    val path = Path().apply {
        moveTo(c.x - r * 0.55f, head.y + r * 0.2f)
        lineTo(c.x, c.y + r)
        lineTo(c.x + r * 0.55f, head.y + r * 0.2f)
        close()
    }
    drawPath(path, color)
    drawCircle(color, radius = r * 0.62f, center = head)
    drawCircle(PreviewScreen, radius = r * 0.25f, center = head)
}

private fun DrawScope.cameraGlyph(c: Offset, r: Float, color: Color) {
    drawRoundRect(color, Offset(c.x - r, c.y - r * 0.6f), Size(r * 2f, r * 1.35f), CornerRadius(0.8f * u))
    drawRect(color, Offset(c.x - r * 0.35f, c.y - r * 0.85f), Size(r * 0.7f, r * 0.3f))
    drawCircle(PreviewScreen, radius = r * 0.4f, center = Offset(c.x, c.y + 0.07f * r))
}

private fun DrawScope.magnifier(c: Offset, r: Float, color: Color) {
    drawCircle(color, radius = r, center = c, style = Stroke(0.9f * u))
    drawLine(color, Offset(c.x + r * 0.7f, c.y + r * 0.7f), Offset(c.x + r * 1.5f, c.y + r * 1.5f), strokeWidth = 1f * u, cap = StrokeCap.Round)
}

/** A photo: sky, a sun and a hill. */
private fun DrawScope.photo(r: Rect, accent: Color, alpha: Float = 1f) {
    tile(r, accent.copy(alpha = 0.45f * alpha), 2f * u)
    drawCircle(PreviewWhite.copy(alpha = 0.85f * alpha), radius = r.width * 0.11f, center = Offset(r.left + r.width * 0.72f, r.top + r.height * 0.3f))
    val hill = Path().apply {
        moveTo(r.left + r.width * 0.08f, r.bottom - 1f * u)
        lineTo(r.left + r.width * 0.42f, r.top + r.height * 0.4f)
        lineTo(r.left + r.width * 0.78f, r.bottom - 1f * u)
        close()
    }
    drawPath(hill, PreviewWhite.copy(alpha = 0.6f * alpha))
}

/** A video tile's duration or a badge: text on a dark plate, its right edge at [right]. */
private fun DrawScope.labelAtRight(label: TextLayoutResult?, right: Float, centerY: Float, alpha: Float) {
    if (label == null) return
    drawLabel(label, Offset(right - label.size.width / 2f, centerY), alpha, background = Color.Black.copy(alpha = 0.6f))
}

// ----------------------------------------------------------------------------- View

/** The phone turns sideways and shows folders and contents side by side. */
private fun DrawScope.drawExtraView(t: Float, accent: Color) {
    val turn = ease(seg(t, 0.18f, 0.4f)) * (1f - ease(seg(t, 0.82f, 0.98f)))
    val land = seg(turn, 0.75f, 1f)
    val c = center
    val pw = 16f * u
    val ph = 28f * u
    rotate(90f * turn, pivot = c) {
        val body = Rect(Offset(c.x - pw / 2f, c.y - ph / 2f), Size(pw, ph))
        drawRoundRect(PreviewWhite.copy(alpha = 0.85f), body.topLeft, body.size, CornerRadius(3f * u), style = Stroke(1.2f * u))
        if (land < 1f) {
            val area = Rect(body.left + 2.5f * u, body.top + 3f * u, body.right - 2.5f * u, body.bottom - 3f * u)
            cells(area, 2, 3, 1.5f * u).forEach { tile(it, PreviewWhite.copy(alpha = 0.35f * (1f - land))) }
        }
    }
    if (land > 0f) {
        val body = Rect(Offset(c.x - ph / 2f, c.y - pw / 2f), Size(ph, pw))
        val area = Rect(body.left + 3f * u, body.top + 2.5f * u, body.right - 3f * u, body.bottom - 2.5f * u)
        val split = area.left + area.width * 0.4f
        cells(Rect(area.left, area.top, split - 1f * u, area.bottom), 2, 2, 1f * u).forEach { tile(it, accent.copy(alpha = 0.8f * land), 1f * u) }
        drawLine(PreviewWhite.copy(alpha = 0.6f * land), Offset(split, area.top), Offset(split, area.bottom), strokeWidth = 0.8f * u)
        cells(Rect(split + 1f * u, area.top, area.right, area.bottom), 3, 2, 1f * u).forEach { tile(it, PreviewWhite.copy(alpha = 0.35f * land), 1f * u) }
    }
}

/** Nothing open on the right: 50/50; a tap on a folder moves the edge to 40/60 and fills the right. */
private fun DrawScope.drawDynamicSplit(t: Float, accent: Color) {
    val area = Rect(3f * u, 3f * u, size.width - 3f * u, size.height - 3f * u)
    val open = ease(seg(t, 0.36f, 0.56f)) * (1f - ease(seg(t, 0.84f, 0.98f)))
    val split = area.left + area.width * lerp(0.5f, 0.4f, open)
    val left = Rect(area.left, area.top, split - 1.5f * u, area.bottom)
    val folders = cells(left, 2, 3, 1.2f * u)
    folders.forEach { folder(it, accent.copy(alpha = 0.75f)) }
    drawLine(PreviewWhite.copy(alpha = 0.7f), Offset(split, area.top), Offset(split, area.bottom), strokeWidth = 1.2f * u)
    val right = Rect(split + 1.5f * u, area.top, area.right, area.bottom)
    drawRoundRect(PreviewWhite.copy(alpha = 0.2f * (1f - open)), right.topLeft, right.size, CornerRadius(2f * u), style = Stroke(0.8f * u))
    cells(right, 3, 3, 1.2f * u).forEach { tile(it, PreviewWhite.copy(alpha = 0.4f * open), 1f * u) }
    val tap = folders[2].center
    drawFinger(tap, seg(t, 0.16f, 0.22f) * (1f - seg(t, 0.4f, 0.46f)))
    drawRipple(tap, seg(t, 0.24f, 0.44f), PreviewWhite)
}

/** The screen bends at the hinge and the edge between the panes goes to the middle. */
private fun DrawScope.drawFoldSplit(t: Float, accent: Color) {
    val bend = ease(seg(t, 0.2f, 0.42f)) * (1f - ease(seg(t, 0.78f, 0.96f)))
    val area = Rect(3f * u, 4f * u, size.width - 3f * u, size.height - 4f * u)
    val mid = area.center.x
    val squeeze = 4f * u * bend
    val farX = area.right - 3f * u * bend
    drawRect(PreviewWhite.copy(alpha = 0.08f), area.topLeft, Size(mid - area.left, area.height))
    val far = Path().apply {
        moveTo(mid, area.top)
        lineTo(farX, area.top + squeeze)
        lineTo(farX, area.bottom - squeeze)
        lineTo(mid, area.bottom)
        close()
    }
    drawPath(far, PreviewWhite.copy(alpha = 0.08f + 0.05f * bend))
    val split = area.left + area.width * lerp(0.4f, 0.5f, bend)
    cells(Rect(area.left + 1.5f * u, area.top + 1.5f * u, split - 1.5f * u, area.bottom - 1.5f * u), 2, 2, 1.2f * u)
        .forEach { folder(it, accent.copy(alpha = 0.75f)) }
    cells(Rect(split + 1.5f * u, area.top + 1.5f * u + squeeze * 0.5f, farX - 1.5f * u, area.bottom - 1.5f * u - squeeze * 0.5f), 3, 2, 1.2f * u)
        .forEach { tile(it, PreviewWhite.copy(alpha = 0.4f - 0.12f * bend), 1f * u) }
    // The hinge, dashed.
    var y = area.top
    while (y < area.bottom) {
        drawLine(PreviewWhite.copy(alpha = 0.25f + 0.35f * bend), Offset(mid, y), Offset(mid, min(y + 2f * u, area.bottom)), strokeWidth = 0.8f * u)
        y += 4f * u
    }
    drawLine(accent, Offset(split, area.top), Offset(split, area.bottom), strokeWidth = 1.2f * u)
}

/** A playing video tile with its length as chosen. */
private fun DrawScope.drawDuration(t: Float, accent: Color, label: TextLayoutResult?) {
    val r = Rect(5f * u, 4f * u, size.width - 5f * u, size.height - 4f * u)
    tile(r, accent.copy(alpha = 0.45f), 3f * u)
    drawPlayTriangle(Offset(r.center.x - 1f * u, r.center.y - 3f * u), 4f * u, PreviewWhite)
    labelAtRight(label, r.right - 3.5f * u, r.bottom - 8f * u, 1f)
    val y = r.bottom - 2.5f * u
    val x0 = r.left + 3f * u
    val x1 = r.right - 3f * u
    drawLine(PreviewWhite.copy(alpha = 0.3f), Offset(x0, y), Offset(x1, y), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
    drawLine(PreviewWhite, Offset(x0, y), Offset(lerp(x0, x1, t), y), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
}

private fun DrawScope.scrollingGrid(area: Rect, offset: Float, color: Color) {
    val gap = 1.5f * u
    val cell = (area.width - gap * 3f) / 4f
    clipRect(area.left, area.top, area.right, area.bottom) {
        for (r in 0 until 9) {
            val y = area.top + r * (cell + gap) - offset
            if (y > area.bottom || y + cell < area.top) continue
            for (c in 0 until 4) {
                tile(Rect(Offset(area.left + c * (cell + gap), y), Size(cell, cell)), color, 1f * u)
            }
        }
    }
}

private fun DrawScope.gridTravel(area: Rect): Float {
    val gap = 1.5f * u
    val cell = (area.width - gap * 3f) / 4f
    return (9 * cell + 8 * gap - area.height).coerceAtLeast(0f)
}

/** A finger drags the handle at the edge and the whole grid follows. */
private fun DrawScope.drawScroller(t: Float, accent: Color) {
    val area = Rect(3f * u, 3f * u, size.width - 10f * u, size.height - 3f * u)
    val p = ease(seg(t, 0.12f, 0.55f)) * (1f - ease(seg(t, 0.7f, 0.95f)))
    scrollingGrid(area, p * gridTravel(area), PreviewWhite.copy(alpha = 0.35f))
    val x = size.width - 5f * u
    val pillH = 10f * u
    val pillY = lerp(area.top, area.bottom - pillH, p)
    drawLine(PreviewWhite.copy(alpha = 0.15f), Offset(x, area.top), Offset(x, area.bottom), strokeWidth = 2f * u, cap = StrokeCap.Round)
    drawRoundRect(accent, Offset(x - 2f * u, pillY), Size(4f * u, pillH), CornerRadius(2f * u))
    drawFinger(Offset(x - 1f * u, pillY + pillH / 2f), seg(t, 0.04f, 0.11f) * (1f - seg(t, 0.93f, 0.99f)))
}

/** The grid glides back and forth without jumps (a faint trail shows the motion). */
private fun DrawScope.drawSmoothScroll(t: Float, accent: Color) {
    val area = Rect(3f * u, 3f * u, size.width - 10f * u, size.height - 3f * u)
    val travel = gridTravel(area)
    val p = 0.5f - 0.5f * cos(2f * PI.toFloat() * t)
    val v = sin(2f * PI.toFloat() * t)
    scrollingGrid(area, p * travel - v * 4f * u, PreviewWhite.copy(alpha = 0.08f))
    scrollingGrid(area, p * travel, PreviewWhite.copy(alpha = 0.35f))
    val x = size.width - 5f * u
    val pillH = 10f * u
    val pillY = lerp(area.top, area.bottom - pillH, p)
    drawLine(PreviewWhite.copy(alpha = 0.15f), Offset(x, area.top), Offset(x, area.bottom), strokeWidth = 2f * u, cap = StrokeCap.Round)
    drawRoundRect(accent, Offset(x - 2f * u, pillY), Size(4f * u, pillH), CornerRadius(2f * u))
}

/**
 * The handle glides back and forth, and the grid follows it a whole row at a time: it stands
 * still between the jumps, and a row's worth of the handle's way moves it one row.
 */
private fun DrawScope.drawRowScroll(t: Float, accent: Color) {
    val area = Rect(3f * u, 3f * u, size.width - 10f * u, size.height - 3f * u)
    val travel = gridTravel(area)
    val gap = 1.5f * u
    val row = (area.width - gap * 3f) / 4f + gap
    val p = 0.5f - 0.5f * cos(2f * PI.toFloat() * t)
    val rows = kotlin.math.round(p * travel / row)
    scrollingGrid(area, (rows * row).coerceAtMost(travel), PreviewWhite.copy(alpha = 0.35f))
    val x = size.width - 5f * u
    val pillH = 10f * u
    val pillY = lerp(area.top, area.bottom - pillH, p)
    drawLine(PreviewWhite.copy(alpha = 0.15f), Offset(x, area.top), Offset(x, area.bottom), strokeWidth = 2f * u, cap = StrokeCap.Round)
    drawRoundRect(accent, Offset(x - 2f * u, pillY), Size(4f * u, pillH), CornerRadius(2f * u))
}

// ----------------------------------------------------------------------------- Folders

/** Documents, archives and apps join the pictures of a folder. */
private fun DrawScope.drawAllFiles(t: Float, accent: Color, apk: TextLayoutResult?) {
    val grid = squareCells(4, 2, 11f * u, 2f * u)
    for (i in 0 until 5) photo(grid[i], accent)
    for (k in 0 until 3) {
        val start = 0.3f + 0.07f * k
        val a = ease(seg(t, start, start + 0.14f)) * (1f - ease(seg(t, 0.84f, 0.96f)))
        if (a <= 0f) continue
        val r = grid[5 + k].scaled(a)
        tile(r, PreviewWhite.copy(alpha = 0.25f * a), 2f * u)
        val glyph = PreviewWhite.copy(alpha = 0.9f * a)
        when (k) {
            0 -> docGlyph(r.center, 3.2f * u * a, glyph)
            1 -> zipGlyph(r.center, 3f * u * a, glyph)
            else -> drawLabel(apk, r.center, a)
        }
    }
}

/** A folder holding only other files (a document) joins the folders. */
private fun DrawScope.drawOtherFolders(t: Float, accent: Color) {
    val grid = squareCells(3, 2, 15f * u, 2.5f * u)
    for (i in 0 until 5) folder(grid[i], accent.copy(alpha = 0.75f))
    val a = ease(seg(t, 0.3f, 0.48f)) * (1f - ease(seg(t, 0.84f, 0.96f)))
    if (a > 0f) {
        val r = grid[5].scaled(a)
        folder(r, PreviewWhite.copy(alpha = 0.3f * a))
        docGlyph(Offset(r.center.x, r.center.y + 1.5f * u * a), 3.4f * u * a, PreviewWhite.copy(alpha = a))
    }
}

/** The Favorites folder goes and the others close up. */
private fun DrawScope.drawHideFavorites(t: Float, accent: Color) {
    val slots = squareCells(4, 2, 11f * u, 2.5f * u)
    val hide = ease(seg(t, 0.3f, 0.5f)) * (1f - ease(seg(t, 0.8f, 0.95f)))
    for (i in 1 until 8) {
        folder(lerpRect(slots[i], slots[i - 1], hide), accent.copy(alpha = 0.7f))
    }
    val fav = slots[0].scaled(1f - hide)
    if (hide < 1f) {
        folder(fav, PreviewWhite.copy(alpha = 0.35f * (1f - hide)))
        starGlyph(Offset(fav.center.x, fav.center.y + 1f * u), 3.5f * u * (1f - hide), Color(0xFFFFC94D).copy(alpha = 1f - hide))
    }
}

/** Out of a folder and back in: it opens where it was left. */
private fun DrawScope.drawRememberScroll(t: Float, accent: Color) {
    val fade = seg(t, 0f, 0.05f) * (1f - seg(t, 0.94f, 1f))
    val area = Rect(3f * u, 3f * u, size.width - 3f * u, size.height - 3f * u)
    val folders = cells(area, 3, 2, 2.5f * u)
    folders.forEach { folder(it, accent.copy(alpha = 0.7f * fade)) }
    val scrolled = ease(seg(t, 0.06f, 0.26f))
    val away = ease(seg(t, 0.32f, 0.44f)) * (1f - ease(seg(t, 0.56f, 0.68f)))
    val dx = away * size.width
    if (away < 1f) {
        drawRect(PreviewScreen.copy(alpha = fade), Offset(dx, 0f), size)
        val gap = 1.5f * u
        val cell = (area.width - gap * 4f) / 5f
        clipRect(dx, 0f, size.width + dx, size.height) {
            for (r in 0 until 7) {
                for (c in 0 until 5) {
                    val y = area.top + r * (cell + gap) - scrolled * (cell + gap) * 2.5f
                    val rect = Rect(Offset(area.left + c * (cell + gap) + dx, y), Size(cell, cell))
                    val mark = r == 3 && c == 2
                    tile(rect, if (mark) accent.copy(alpha = fade) else PreviewWhite.copy(alpha = 0.35f * fade), 1f * u)
                }
            }
        }
    }
    val tap = folders[1].center
    drawFinger(tap, seg(t, 0.44f, 0.48f) * (1f - seg(t, 0.56f, 0.6f)) * fade)
    drawRipple(tap, seg(t, 0.47f, 0.6f), PreviewWhite)
}

/** A new file arrives: the grid goes to the top and the file shows there. */
private fun DrawScope.drawNewContentTop(t: Float, accent: Color) {
    val fade = seg(t, 0f, 0.05f) * (1f - seg(t, 0.92f, 1f))
    val area = Rect(3f * u, 3f * u, size.width - 3f * u, size.height - 3f * u)
    val gap = 1.5f * u
    val cell = (area.width - gap * 4f) / 5f
    val step = cell + gap
    val up = ease(seg(t, 0.25f, 0.45f))
    val arrive = ease(seg(t, 0.42f, 0.58f))
    val offset = (1f - up) * step * 2.5f
    fun slot(i: Int) = Rect(Offset(area.left + (i % 5) * step, area.top + (i / 5) * step - offset), Size(cell, cell))
    clipRect(area.left, area.top, area.right, area.bottom) {
        for (i in 0 until 30) {
            // Each file moves one place on: along the row, or down to the start of the next one.
            val rect = lerpRect(slot(i), slot(i + 1), arrive)
            if (rect.top > area.bottom || rect.bottom < area.top) continue
            tile(rect, PreviewWhite.copy(alpha = 0.35f * fade), 1f * u)
        }
        if (arrive > 0f) {
            val first = Rect(Offset(area.left, area.top - offset), Size(cell, cell)).scaled(arrive)
            tile(first, accent.copy(alpha = fade), 1f * u)
        }
    }
}

/** A download fills its ring; once done the tile unlocks and plays. */
private fun DrawScope.drawNewPipe(t: Float, accent: Color) {
    val fade = seg(t, 0f, 0.05f) * (1f - seg(t, 0.93f, 1f))
    val r = Rect(Offset(center.x - 15f * u, center.y - 15f * u), Size(30f * u, 30f * u))
    tile(r, accent.copy(alpha = 0.45f * fade), 2.5f * u)
    val progress = seg(t, 0.08f, 0.62f)
    val done = seg(t, 0.62f, 0.72f)
    if (done < 1f) {
        val a = (1f - done) * fade
        tile(r, Color.Black.copy(alpha = 0.45f * a), 2.5f * u)
        val ring = 8f * u
        drawCircle(PreviewWhite.copy(alpha = 0.25f * a), radius = ring, center = r.center, style = Stroke(1.6f * u))
        drawArc(
            accent.copy(alpha = a),
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = Offset(r.center.x - ring, r.center.y - ring),
            size = Size(ring * 2f, ring * 2f),
            style = Stroke(1.6f * u, cap = StrokeCap.Round),
        )
        // Down arrow.
        val c = r.center
        drawLine(PreviewWhite.copy(alpha = a), Offset(c.x, c.y - 3f * u), Offset(c.x, c.y + 2.5f * u), strokeWidth = 1.2f * u, cap = StrokeCap.Round)
        drawLine(PreviewWhite.copy(alpha = a), Offset(c.x - 2.5f * u, c.y), Offset(c.x, c.y + 2.5f * u), strokeWidth = 1.2f * u, cap = StrokeCap.Round)
        drawLine(PreviewWhite.copy(alpha = a), Offset(c.x + 2.5f * u, c.y), Offset(c.x, c.y + 2.5f * u), strokeWidth = 1.2f * u, cap = StrokeCap.Round)
    }
    if (done > 0f) drawPlayTriangle(r.center, 4.5f * u * done, PreviewWhite.copy(alpha = done * fade))
}

// ----------------------------------------------------------------------------- Selection

/** A drag selects a rectangle of tiles, or every tile from the first to the last (linear). */
private fun DrawScope.drawSelectStyle(t: Float, accent: Color, linear: Boolean) {
    val cols = 5
    val grid = squareCells(cols, 3, 9f * u, 1.6f * u)
    val start = 3
    val end = 11
    val move = ease(seg(t, 0.15f, 0.6f))
    val finger = Offset(lerp(grid[start].center.x, grid[end].center.x, move), lerp(grid[start].center.y, grid[end].center.y, move))
    val shown = seg(t, 0.1f, 0.15f) * (1f - seg(t, 0.85f, 0.95f))
    val current = grid.indices.minByOrNull { (grid[it].center - finger).getDistanceSquared() } ?: start
    val r0 = start / cols
    val c0 = start % cols
    val rc = current / cols
    val cc = current % cols
    grid.forEachIndexed { i, r ->
        val row = i / cols
        val col = i % cols
        val selected = if (linear) {
            i in minOf(start, current)..maxOf(start, current)
        } else {
            row in minOf(r0, rc)..maxOf(r0, rc) && col in minOf(c0, cc)..maxOf(c0, cc)
        }
        tile(r, PreviewWhite.copy(alpha = 0.3f), 1f * u)
        if (selected && shown > 0f) {
            tile(r, accent.copy(alpha = 0.8f * shown), 1f * u)
        }
    }
    drawFinger(finger, seg(t, 0.06f, 0.12f) * (1f - seg(t, 0.66f, 0.72f)))
}

/** Tiles tapped one after another get their numbers: the operations go in that order. */
private fun DrawScope.drawSelectionOrder(t: Float, accent: Color, numbers: List<TextLayoutResult>) {
    val grid = squareCells(4, 2, 11f * u, 2.5f * u)
    val order = listOf(5, 2, 7)
    val clear = 1f - seg(t, 0.86f, 0.95f)
    grid.forEach { tile(it, PreviewWhite.copy(alpha = 0.3f), 1.5f * u) }
    order.forEachIndexed { k, idx ->
        val at = 0.12f + 0.22f * k
        val r = grid[idx]
        drawFinger(r.center, seg(t, at - 0.06f, at - 0.02f) * (1f - seg(t, at + 0.04f, at + 0.08f)))
        drawRipple(r.center, seg(t, at, at + 0.15f), PreviewWhite)
        val on = seg(t, at, at + 0.04f) * clear
        if (on > 0f) {
            tile(r, accent.copy(alpha = 0.7f * on), 1.5f * u)
            val badge = Offset(r.right - 2.5f * u, r.top + 2.5f * u)
            drawCircle(accent.copy(alpha = on), radius = 3.2f * u, center = badge)
            drawCircle(PreviewWhite.copy(alpha = on), radius = 3.2f * u, center = badge, style = Stroke(0.6f * u))
            numbers.getOrNull(k)?.let { drawLabel(it, badge, on) }
        }
    }
}

// ----------------------------------------------------------------------------- Viewing

/** The play head reaches a sponsor segment (green) and jumps over it. */
private fun DrawScope.drawSponsorBlock(t: Float, accent: Color) {
    val w = size.width
    val h = size.height
    val y = h - 6f * u
    val x0 = 5f * u
    val x1 = w - 5f * u
    val segA = 0.45f
    val segB = 0.7f
    val raw = 0.1f + 0.8f * t
    val p = if (raw >= segA) (raw + (segB - segA)).coerceAtMost(0.98f) else raw
    drawLine(PreviewWhite.copy(alpha = 0.25f), Offset(x0, y), Offset(x1, y), strokeWidth = 2f * u, cap = StrokeCap.Round)
    drawLine(SponsorGreen.copy(alpha = 0.9f), Offset(lerp(x0, x1, segA), y), Offset(lerp(x0, x1, segB), y), strokeWidth = 2f * u)
    drawLine(accent, Offset(x0, y), Offset(lerp(x0, x1, p), y), strokeWidth = 2f * u, cap = StrokeCap.Round)
    drawCircle(PreviewWhite, radius = 2.2f * u, center = Offset(lerp(x0, x1, p), y))
    val flash = if (raw >= segA) 1f - seg(raw, segA, segA + 0.15f) else 0f
    if (flash > 0f) {
        val c = Offset(w * 0.5f, h * 0.4f)
        val stroke = 1.6f * u
        drawChevron(Offset(c.x - 2.5f * u, c.y), 3.5f * u, true, PreviewWhite.copy(alpha = flash), stroke)
        drawChevron(Offset(c.x + 2.5f * u, c.y), 3.5f * u, true, PreviewWhite.copy(alpha = flash), stroke)
    }
    if (raw < segA) drawPlayTriangle(Offset(w * 0.5f, h * 0.4f), 4f * u, PreviewWhite.copy(alpha = 0.4f))
}

// ----------------------------------------------------------------------------- Search

/** The search results open over the whole screen instead of the left pane. */
private fun DrawScope.drawSearchFullscreen(t: Float, accent: Color) {
    val area = Rect(3f * u, 3f * u, size.width - 3f * u, size.height - 3f * u)
    val grow = ease(seg(t, 0.3f, 0.5f)) * (1f - ease(seg(t, 0.82f, 0.96f)))
    val split = area.left + area.width * lerp(0.42f, 1f, grow)
    cells(Rect(area.left + area.width * 0.42f + 1.5f * u, area.top, area.right, area.bottom), 3, 3, 1.2f * u)
        .forEach { tile(it, PreviewWhite.copy(alpha = 0.3f), 1f * u) }
    val left = Rect(area.left, area.top, split, area.bottom)
    drawRect(PreviewScreen, Offset(0f, 0f), Size(split + 1f * u, size.height))
    drawLine(PreviewWhite.copy(alpha = 0.6f * (1f - grow)), Offset(split + 0.75f * u, area.top), Offset(split + 0.75f * u, area.bottom), strokeWidth = 0.8f * u)
    val bar = Rect(left.left, left.top, left.right - 1.5f * u, left.top + 6f * u)
    drawRoundRect(PreviewWhite.copy(alpha = 0.22f), bar.topLeft, bar.size, CornerRadius(1.5f * u))
    magnifier(Offset(bar.left + 3.5f * u, bar.center.y - 0.4f * u), 1.6f * u, PreviewWhite)
    val gap = 1.2f * u
    val cell = 6.5f * u
    clipRect(left.left, bar.bottom + 1.5f * u, left.right, left.bottom) {
        var y = bar.bottom + 1.5f * u
        while (y < left.bottom) {
            var x = left.left
            while (x + cell <= left.right - 1f * u) {
                tile(Rect(Offset(x, y), Size(cell, cell)), accent.copy(alpha = 0.7f), 1f * u)
                x += cell + gap
            }
            y += cell + gap
        }
    }
}

/** A description is typed and the matching photos light up. */
private fun DrawScope.drawAiSearch(t: Float, accent: Color) {
    val fade = 1f - seg(t, 0.88f, 0.98f)
    val bar = Rect(4f * u, 3f * u, size.width - 4f * u, 10f * u)
    drawRoundRect(PreviewWhite.copy(alpha = 0.22f), bar.topLeft, bar.size, CornerRadius(1.5f * u))
    sparkle(Offset(bar.left + 3.5f * u, bar.center.y), 2.4f * u, accent)
    val typed = seg(t, 0.06f, 0.34f) * fade
    if (typed > 0f) {
        val x0 = bar.left + 7.5f * u
        drawLine(PreviewWhite.copy(alpha = 0.85f), Offset(x0, bar.center.y), Offset(x0 + (bar.width * 0.5f) * typed, bar.center.y), strokeWidth = 1.6f * u, cap = StrokeCap.Round)
    }
    val grid = cells(Rect(4f * u, 13f * u, size.width - 4f * u, size.height - 3f * u), 4, 2, 2f * u)
    val found = ease(seg(t, 0.4f, 0.55f)) * fade
    val pulse = 0.65f + 0.35f * sin(t * 6f * PI.toFloat())
    grid.forEachIndexed { i, r ->
        val match = i == 1 || i == 6
        photo(r, accent, alpha = if (match) 1f else 1f - 0.6f * found)
        if (match && found > 0f) {
            drawRoundRect(accent.copy(alpha = found * pulse), r.topLeft, r.size, CornerRadius(2f * u), style = Stroke(1.5f * u))
        }
    }
}

// ----------------------------------------------------------------------------- Privacy

/** A hidden folder (crossed-out eye) comes into the list and goes again. */
private fun DrawScope.drawShowHidden(t: Float, accent: Color) {
    val grid = squareCells(4, 2, 11f * u, 2.5f * u)
    for (i in 0 until 8) if (i != 5) folder(grid[i], accent.copy(alpha = 0.7f))
    val a = ease(seg(t, 0.28f, 0.44f)) * (1f - ease(seg(t, 0.8f, 0.94f)))
    if (a > 0f) {
        val r = grid[5].scaled(a)
        folder(r, PreviewWhite.copy(alpha = 0.3f * a))
        eyeOffGlyph(Offset(r.center.x, r.center.y + 1f * u * a), 3.2f * u * a, PreviewWhite.copy(alpha = a))
    }
}

/** A file's own name gives way to a random one. */
private fun DrawScope.drawRandomNames(t: Float, accent: Color, names: List<TextLayoutResult>) {
    val r = Rect(Offset(center.x - 10f * u, 4f * u), Size(20f * u, 20f * u))
    photo(r, accent)
    val swap = ease(seg(t, 0.32f, 0.5f)) * (1f - ease(seg(t, 0.82f, 0.95f)))
    val y = r.bottom + 6f * u
    names.getOrNull(0)?.let { drawLabel(it, Offset(center.x, y - 4f * u * swap), 1f - swap) }
    names.getOrNull(1)?.let { drawLabel(it, Offset(center.x, y + 4f * u * (1f - swap)), swap) }
    val glint = 1f - abs(2f * swap - 1f)
    if (glint > 0f) sparkle(Offset(r.right + 3f * u, r.top + 3f * u), 2.5f * u * glint, accent.copy(alpha = glint))
}

/** The location and camera tags drop off the photo. */
private fun DrawScope.drawStripExif(t: Float, accent: Color) {
    val r = Rect(Offset(center.x - 15f * u, center.y - 12f * u), Size(30f * u, 24f * u))
    photo(r, accent)
    val fall = ease(seg(t, 0.32f, 0.6f))
    val back = seg(t, 0f, 0.08f)
    val a = back * (1f - fall)
    val drop = fall * 14f * u
    if (a > 0f) {
        val pin = Offset(r.right - 2f * u, r.top + 1f * u + drop)
        drawCircle(Color.Black.copy(alpha = 0.55f * a), radius = 4.6f * u, center = pin)
        pinGlyph(pin, 3.2f * u, PreviewWhite.copy(alpha = a))
        val cam = Offset(r.right - 10f * u, r.top + 0.5f * u + drop * 1.2f)
        drawCircle(Color.Black.copy(alpha = 0.55f * a), radius = 4.6f * u, center = cam)
        cameraGlyph(cam, 2.8f * u, PreviewWhite.copy(alpha = a))
    }
}

/** Before sending, the location tag drops off; then the photo goes. */
private fun DrawScope.drawStripExifShare(t: Float, accent: Color) {
    val start = Rect(Offset(5f * u, center.y - 8f * u), Size(18f * u, 16f * u))
    val target = Offset(size.width - 10f * u, center.y)
    val appear = seg(t, 0f, 0.08f)
    val fly = ease(seg(t, 0.42f, 0.7f))
    val r = lerpRect(start, Rect(Offset(target.x - 2f * u, target.y - 2f * u), Size(4f * u, 4f * u)), fly)
    // Send target: a circle with an arrow.
    drawCircle(PreviewWhite.copy(alpha = 0.2f), radius = 6.5f * u, center = target)
    drawLine(PreviewWhite, Offset(target.x - 2.5f * u, target.y), Offset(target.x + 2.5f * u, target.y), strokeWidth = 1.2f * u, cap = StrokeCap.Round)
    drawChevron(Offset(target.x + 1.5f * u, target.y), 2.5f * u, true, PreviewWhite, 1.2f * u)
    drawRipple(target, seg(t, 0.7f, 0.86f), accent)
    if (fly < 1f) photo(r, accent, alpha = appear * (1f - seg(fly, 0.8f, 1f)))
    val fall = ease(seg(t, 0.18f, 0.36f))
    val a = appear * (1f - fall)
    if (a > 0f) {
        val pin = Offset(start.right - 1f * u, start.top + 1f * u + fall * 12f * u)
        drawCircle(Color.Black.copy(alpha = 0.55f * a), radius = 4.4f * u, center = pin)
        pinGlyph(pin, 3f * u, PreviewWhite.copy(alpha = a))
    }
}

// ----------------------------------------------------------------------------- App

/** A blocky, soft thumbnail turns sharp. */
private fun DrawScope.drawSharpThumbnails(t: Float, accent: Color) {
    val r = Rect(Offset(center.x - 17f * u, center.y - 14f * u), Size(34f * u, 28f * u))
    val sharp = ease(seg(t, 0.3f, 0.5f)) * (1f - ease(seg(t, 0.8f, 0.95f)))
    if (sharp < 1f) {
        val cols = 7
        val rows = 6
        val bw = r.width / cols
        val bh = r.height / rows
        val sun = Offset(r.left + r.width * 0.72f, r.top + r.height * 0.3f)
        val sunR = r.width * 0.11f
        val a = Offset(r.left + r.width * 0.08f, r.bottom)
        val b = Offset(r.left + r.width * 0.42f, r.top + r.height * 0.4f)
        val c = Offset(r.left + r.width * 0.78f, r.bottom)
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val p = Offset(r.left + (x + 0.5f) * bw, r.top + (y + 0.5f) * bh)
                val color = when {
                    (p - sun).getDistance() < sunR * 1.6f -> PreviewWhite.copy(alpha = 0.7f)
                    inTriangle(p, a, b, c) -> PreviewWhite.copy(alpha = 0.5f)
                    else -> accent.copy(alpha = 0.45f)
                }
                drawRect(color.copy(alpha = color.alpha * (1f - sharp)), Offset(r.left + x * bw, r.top + y * bh), Size(bw + 0.5f, bh + 0.5f))
            }
        }
    }
    if (sharp > 0f) photo(r, accent, alpha = sharp)
}

private fun inTriangle(p: Offset, a: Offset, b: Offset, c: Offset): Boolean {
    fun side(p1: Offset, p2: Offset, p3: Offset) = (p1.x - p3.x) * (p2.y - p3.y) - (p2.x - p3.x) * (p1.y - p3.y)
    val d1 = side(p, a, b)
    val d2 = side(p, b, c)
    val d3 = side(p, c, a)
    val neg = d1 < 0 || d2 < 0 || d3 < 0
    val pos = d1 > 0 || d2 > 0 || d3 > 0
    return !(neg && pos)
}
