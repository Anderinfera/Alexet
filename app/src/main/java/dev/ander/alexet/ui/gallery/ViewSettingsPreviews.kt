package dev.ander.alexet.ui.gallery

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ander.alexet.data.ContentViewMode
import dev.ander.alexet.ui.components.LocalLightMode

/** Which device the View settings' pictures show. */
internal enum class PreviewDevice { PHONE, TABLET }

/** Which grid of a [MiniLayoutPreview] the setting it stands next to changes. */
internal enum class PreviewFocus { FOLDERS, FILES }

/** Colors of the previews, read once from the theme. */
private data class PreviewColors(
    val frame: Color,
    val screen: Color,
    val bar: Color,
    val folder: Color,
    val file: Color,
    val accent: Color,
)

@Composable
private fun previewColors(): PreviewColors {
    val scheme = MaterialTheme.colorScheme
    return PreviewColors(
        frame = scheme.outline,
        screen = scheme.surface,
        bar = scheme.surfaceContainerHighest,
        folder = scheme.primary.copy(alpha = 0.6f),
        file = scheme.secondary.copy(alpha = 0.55f),
        accent = scheme.primary,
    )
}

/** A device outline; returns the screen inside it. */
private fun DrawScope.drawDevice(device: PreviewDevice, colors: PreviewColors): Rect {
    val u = 1.dp.toPx()
    val outer = Rect(Offset.Zero, size)
    drawRoundRect(colors.frame.copy(alpha = 0.18f), outer.topLeft, outer.size, CornerRadius(if (device == PreviewDevice.PHONE) 6f * u else 5f * u))
    drawRoundRect(
        colors.frame.copy(alpha = 0.7f),
        outer.topLeft,
        outer.size,
        CornerRadius(if (device == PreviewDevice.PHONE) 6f * u else 5f * u),
        style = Stroke(1.2f * u),
    )
    val inset = if (device == PreviewDevice.PHONE) 2.5f * u else 3f * u
    val screen = Rect(inset, inset, size.width - inset, size.height - inset)
    drawRoundRect(colors.screen, screen.topLeft, screen.size, CornerRadius(3f * u))
    return screen
}

/** Square tiles in [columns] columns from the top of [area]; [rows] limits them (null: all that fit). */
private fun DrawScope.drawTiles(area: Rect, columns: Int, color: Color, rows: Int? = null, nameBars: Boolean = false, nameColor: Color = color) {
    val cols = columns.coerceIn(1, 30)
    val gap = (1.dp.toPx() * (6f / cols).coerceIn(0.3f, 1.2f))
    val tile = (area.width - gap * (cols - 1)) / cols
    if (tile <= 0f) return
    val nameH = if (nameBars) (tile * 0.22f).coerceAtLeast(1f) else 0f
    val rowH = tile + nameH + gap
    val count = rows ?: (((area.height + gap) / rowH).toInt() + 1)
    val radius = CornerRadius((tile * 0.12f).coerceAtMost(2.dp.toPx()))
    clipRect(area.left, area.top, area.right, area.bottom) {
        for (r in 0 until count) {
            val y = area.top + r * rowH
            if (y > area.bottom) break
            for (c in 0 until cols) {
                val x = area.left + c * (tile + gap)
                drawRoundRect(color, Offset(x, y), Size(tile, tile), radius)
                if (nameBars) {
                    drawRect(nameColor.copy(alpha = 0.5f), Offset(x + tile * 0.1f, y + tile + nameH * 0.25f), Size(tile * 0.8f, nameH * 0.5f))
                }
            }
        }
    }
}

private fun DrawScope.drawTopBar(screen: Rect, colors: PreviewColors, back: Boolean): Float {
    val h = (screen.height * 0.09f).coerceAtLeast(3.dp.toPx())
    drawRect(colors.bar, screen.topLeft, Size(screen.width, h))
    if (back) {
        val u = 1.dp.toPx()
        val c = Offset(screen.left + h * 0.6f, screen.top + h / 2f)
        val path = Path().apply {
            moveTo(c.x + h * 0.15f, c.y - h * 0.25f)
            lineTo(c.x - h * 0.12f, c.y)
            lineTo(c.x + h * 0.15f, c.y + h * 0.25f)
        }
        drawPath(path, colors.accent, style = Stroke(1f * u, cap = StrokeCap.Round))
    }
    return screen.top + h
}

private fun seg(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun ease(x: Float): Float = x * x * (3f - 2f * x)

/**
 * A phone going from its folders to a folder's content and back: a tap on a folder, the content
 * slides in, then back. [mode] adds what the view mode changes (an expanded folder grid, two
 * folders open at once). Light mode: the content, still.
 */
@Composable
internal fun PhoneFlowPreview(
    folderColumns: Int,
    fileColumns: Int,
    modifier: Modifier = Modifier,
    mode: ContentViewMode = ContentViewMode.LAST_FOLDER,
    expandColumns: Int = 4,
    expandRows: Int = 4,
    width: Dp = 64.dp,
    height: Dp = 112.dp,
) {
    val colors = previewColors()
    val time: State<Float>? = if (LocalLightMode.current) {
        null
    } else {
        rememberInfiniteTransition(label = "phoneFlow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
            label = "phoneFlowTime",
        )
    }
    Canvas(modifier.size(width, height)) {
        val t = time?.value ?: 0.6f
        val screen = drawDevice(PreviewDevice.PHONE, colors)
        val u = 1.dp.toPx()
        val pad = 2f * u
        clipRect(screen.left, screen.top, screen.right, screen.bottom) {
            // Folders.
            val barBottom = drawTopBar(screen, colors, back = false)
            val foldersArea = Rect(screen.left + pad, barBottom + pad, screen.right - pad, screen.bottom - pad)
            val expanded = mode == ContentViewMode.EXPAND_FOLDERS
            val cols = if (expanded) expandColumns else folderColumns
            drawTiles(foldersArea, cols, colors.folder, rows = if (expanded) expandRows else null)

            // The tapped folder: first row, second column (or the only one).
            val safeCols = cols.coerceIn(1, 30)
            val gap = (1.dp.toPx() * (6f / safeCols).coerceIn(0.3f, 1.2f))
            val tile = (foldersArea.width - gap * (safeCols - 1)) / safeCols
            val col = if (safeCols > 1) 1 else 0
            val tap = Offset(foldersArea.left + col * (tile + gap) + tile / 2f, foldersArea.top + tile / 2f)
            val fingerAlpha = seg(t, 0.14f, 0.2f) * (1f - seg(t, 0.32f, 0.38f))
            if (fingerAlpha > 0f) {
                drawCircle(Color.White.copy(alpha = 0.9f * fingerAlpha), radius = 3f * u, center = tap)
                drawCircle(colors.accent.copy(alpha = 0.8f * fingerAlpha), radius = 3f * u, center = tap, style = Stroke(1f * u))
            }
            val ripple = seg(t, 0.2f, 0.38f)
            if (ripple in 0.001f..0.999f) {
                drawCircle(colors.accent.copy(alpha = 1f - ripple), radius = (3f + 8f * ripple) * u, center = tap, style = Stroke(1.2f * u))
            }

            // Content, sliding in from the right and back.
            val shown = ease(seg(t, 0.36f, 0.5f)) * (1f - ease(seg(t, 0.82f, 0.96f)))
            if (shown > 0f) {
                val dx = screen.width * (1f - shown)
                val content = Rect(screen.left + dx, screen.top, screen.right + dx, screen.bottom)
                drawRect(colors.screen, content.topLeft, content.size)
                val top = drawTopBar(content, colors, back = true)
                val area = Rect(content.left + pad, top + pad, content.right - pad, content.bottom - pad)
                if (mode == ContentViewMode.CLICK_DRAG) {
                    // Two folders open, one above the other.
                    val mid = area.top + area.height / 2f
                    val header = 2.5f * u
                    drawRect(colors.folder, Offset(area.left, area.top), Size(area.width * 0.45f, header))
                    drawTiles(Rect(area.left, area.top + header + pad, area.right, mid - pad), fileColumns, colors.file)
                    drawRect(colors.folder, Offset(area.left, mid + pad), Size(area.width * 0.45f, header))
                    drawTiles(Rect(area.left, mid + header + pad * 2f, area.right, area.bottom), fileColumns, colors.file)
                } else {
                    drawTiles(area, fileColumns, colors.file)
                }
            }
        }
    }
}

/**
 * A still device with the grids the settings shape: on a phone the grid of [focus] alone, on a
 * tablet both panes, [focus]'s one marked. [dimFocus]: the setting does not apply to this device.
 */
@Composable
internal fun MiniLayoutPreview(
    device: PreviewDevice,
    folderColumns: Int,
    fileColumns: Int,
    focus: PreviewFocus,
    modifier: Modifier = Modifier,
    showNames: Boolean = false,
    dimFocus: Boolean = false,
) {
    val colors = previewColors()
    val w = if (device == PreviewDevice.PHONE) 26.dp else 54.dp
    val h = if (device == PreviewDevice.PHONE) 44.dp else 38.dp
    Canvas(modifier.size(w, h)) {
        val screen = drawDevice(device, colors)
        val u = 1.dp.toPx()
        val pad = 1.5f * u
        val top = drawTopBar(screen, colors, back = false)
        if (device == PreviewDevice.PHONE) {
            val area = Rect(screen.left + pad, top + pad, screen.right - pad, screen.bottom - pad)
            if (focus == PreviewFocus.FOLDERS) {
                drawTiles(area, folderColumns, colors.folder, nameBars = showNames, nameColor = colors.frame)
            } else {
                drawTiles(area, fileColumns, colors.file.copy(alpha = if (dimFocus) 0.25f else 0.55f), nameBars = showNames, nameColor = colors.frame)
            }
        } else {
            val split = screen.left + screen.width * 0.4f
            val left = Rect(screen.left + pad, top + pad, split - pad / 2f, screen.bottom - pad)
            val right = Rect(split + pad / 2f, top + pad, screen.right - pad, screen.bottom - pad)
            val folderFocus = focus == PreviewFocus.FOLDERS
            drawTiles(left, folderColumns, colors.folder.copy(alpha = if (folderFocus) 0.75f else 0.3f), nameBars = showNames, nameColor = colors.frame)
            val fileAlpha = when {
                folderFocus -> 0.25f
                dimFocus -> 0.25f
                else -> 0.7f
            }
            drawTiles(right, fileColumns, colors.file.copy(alpha = fileAlpha), nameBars = showNames, nameColor = colors.frame)
            drawLine(colors.frame.copy(alpha = 0.5f), Offset(split, top), Offset(split, screen.bottom), strokeWidth = 0.6f * u)
            // The pane the setting changes.
            val marked = if (folderFocus) left else right
            if (!dimFocus || folderFocus) {
                drawRoundRect(colors.accent, marked.topLeft - Offset(pad / 2f, pad / 2f), Size(marked.width + pad, marked.height + pad), CornerRadius(1.5f * u), style = Stroke(0.8f * u))
            }
        }
    }
}
