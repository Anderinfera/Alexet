package dev.ander.alexet.ui.gallery

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R
import dev.ander.alexet.data.SelectionAction
import dev.ander.alexet.ui.components.LocalLightMode

/** What each selection action does, shown by the (i) of its card. */
internal fun actionInfoRes(action: SelectionAction): Int = when (action) {
    SelectionAction.DELETE -> R.string.action_info_delete
    SelectionAction.SHARE -> R.string.action_info_share
    SelectionAction.INFO -> R.string.action_info_info
    SelectionAction.MOVE -> R.string.action_info_move
    SelectionAction.RENAME -> R.string.action_info_rename
    SelectionAction.EDIT -> R.string.action_info_edit
    SelectionAction.COPY -> R.string.action_info_copy
    SelectionAction.SELECT_ALL -> R.string.action_info_select_all
    SelectionAction.HIDE -> R.string.action_info_hide
    SelectionAction.FAVORITE -> R.string.action_info_favorite
    SelectionAction.SET_AS -> R.string.action_info_set_as
    SelectionAction.STRIP_EXIF -> R.string.action_info_strip_exif
    SelectionAction.CONTENT_ORDER -> R.string.action_info_content_order
    SelectionAction.SET_PRIVATE -> R.string.action_info_set_private
    SelectionAction.QUICK_EDIT -> R.string.action_info_quick_edit
}

/**
 * The (i) in front of an action card: a card with the action acted out on a few tiles and a
 * short explanation.
 */
@Composable
internal fun ActionInfoButton(action: SelectionAction, title: String, icon: ImageVector) {
    var open by rememberSaveable(action) { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = stringResource(R.string.settings_help_for, title),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
    if (open) {
        PreviewInfoCard(
            title = title,
            body = stringResource(actionInfoRes(action)),
            onDismiss = { open = false },
        ) { scaled ->
            ActionPreview(action, icon, scaled)
        }
    }
}

/** [action] acted out in a loop on a small grid, in the style of the settings' option pictures. */
@Composable
internal fun ActionPreview(action: SelectionAction, icon: ImageVector, modifier: Modifier = Modifier) {
    val time: State<Float>? = if (!LocalLightMode.current) {
        rememberInfiniteTransition(label = "actionPreview").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
            label = "actionPreviewTime",
        )
    } else {
        null
    }
    val accent = MaterialTheme.colorScheme.primary
    val iconPainter = rememberVectorPainter(icon)
    Canvas(modifier.size(width = 56.dp, height = 40.dp).clip(RoundedCornerShape(8.dp))) {
        val t = time?.value ?: 0.55f
        drawRect(PreviewScreen)
        drawAction(action, t, accent, iconPainter)
    }
}

private val DrawScope.unit: Float get() = 1.dp.toPx()

/** The six tiles (3 x 2) on the left; the action's spot is on the right. */
private fun DrawScope.gridTiles(): List<Rect> {
    val u = unit
    val cell = 9.5f * u
    val gap = 1.5f * u
    val left = 3f * u
    val top = (size.height - (2 * cell + gap)) / 2f
    return List(6) { i ->
        val c = i % 3
        val r = i / 3
        Rect(Offset(left + c * (cell + gap), top + r * (cell + gap)), Size(cell, cell))
    }
}

private fun DrawScope.target(): Offset = Offset(size.width - 9f * unit, size.height / 2f)

private fun DrawScope.photoTile(r: Rect, accent: Color, alpha: Float = 1f) {
    if (alpha <= 0f) return
    val u = unit
    drawRoundRect(PreviewWhite.copy(alpha = 0.22f * alpha), r.topLeft, r.size, CornerRadius(1.5f * u))
    // A little landscape: a hill and a sun.
    drawCircle(accent.copy(alpha = 0.75f * alpha), r.width * 0.13f, Offset(r.left + r.width * 0.7f, r.top + r.height * 0.32f))
    drawRoundRect(
        PreviewWhite.copy(alpha = 0.35f * alpha),
        Offset(r.left + r.width * 0.12f, r.top + r.height * 0.6f),
        Size(r.width * 0.76f, r.height * 0.26f),
        CornerRadius(1f * u),
    )
}

private fun DrawScope.check(r: Rect, accent: Color, amount: Float) {
    if (amount <= 0f) return
    val u = unit
    val c = Offset(r.right - 2.2f * u, r.top + 2.2f * u)
    drawCircle(accent.copy(alpha = amount), 2.2f * u * amount, c)
    drawLine(
        PreviewWhite.copy(alpha = amount),
        Offset(c.x - 1f * u, c.y),
        Offset(c.x - 0.2f * u, c.y + 0.8f * u),
        strokeWidth = 0.7f * u,
        cap = StrokeCap.Round,
    )
    drawLine(
        PreviewWhite.copy(alpha = amount),
        Offset(c.x - 0.2f * u, c.y + 0.8f * u),
        Offset(c.x + 1.1f * u, c.y - 0.8f * u),
        strokeWidth = 0.7f * u,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.icon(painter: VectorPainter, center: Offset, side: Float, color: Color) {
    with(painter) {
        translate(center.x - side / 2f, center.y - side / 2f) {
            draw(Size(side, side), colorFilter = ColorFilter.tint(color))
        }
    }
}

private fun Rect.scaledAround(f: Float): Rect {
    val w = width * f
    val h = height * f
    return Rect(Offset(center.x - w / 2f, center.y - h / 2f), Size(w, h))
}

private fun Rect.movedTo(c: Offset): Rect = Rect(Offset(c.x - width / 2f, c.y - height / 2f), size)

private fun lerp(a: Offset, b: Offset, f: Float) = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)

private fun DrawScope.drawAction(action: SelectionAction, t: Float, accent: Color, iconPainter: VectorPainter) {
    val u = unit
    val tiles = gridTiles()
    val selected = setOf(0, 4)
    // Selected (0.05-0.25), the action (0.3-0.7), held, then everything fades back (0.88-1).
    val pick = ease(seg(t, 0.05f, 0.25f))
    val act = ease(seg(t, 0.3f, 0.7f))
    val reset = ease(seg(t, 0.88f, 1f))
    val back = 1f - reset
    val spot = target()
    val iconSide = 10f * u
    val pulse = 1f + 0.25f * kotlin.math.sin(act * kotlin.math.PI.toFloat())
    fun drawIcon(alpha: Float = 1f, scale: Float = 1f) =
        icon(iconPainter, spot, iconSide * scale, accent.copy(alpha = alpha))

    when (action) {
        SelectionAction.DELETE, SelectionAction.MOVE -> {
            // The selected tiles fly into the action and are gone from the grid.
            tiles.forEachIndexed { i, r ->
                if (i in selected) {
                    val p = act * back
                    val rr = r.movedTo(lerp(r.center, spot, p)).scaledAround(1f - 0.85f * p)
                    photoTile(rr, accent, 1f - p * 0.9f)
                    check(rr, accent, pick * (1f - p) * back)
                } else {
                    photoTile(r, accent)
                }
            }
            drawIcon(scale = pulse)
        }
        SelectionAction.SHARE, SelectionAction.COPY -> {
            // A copy of each selected tile flies out; the originals stay.
            tiles.forEachIndexed { i, r ->
                photoTile(r, accent)
                if (i in selected) check(r, accent, pick * back)
            }
            if (act > 0f && back > 0f) {
                selected.forEach { i ->
                    val r = tiles[i]
                    val rr = r.movedTo(lerp(r.center, spot, act)).scaledAround(1f - 0.8f * act)
                    photoTile(rr, accent, (1f - act) * back)
                }
            }
            drawIcon(scale = pulse)
        }
        SelectionAction.INFO -> {
            tiles.forEach { photoTile(it, accent) }
            check(tiles[0], accent, pick * back)
            // A details sheet slides up with a few lines.
            val h = size.height * 0.62f
            val top = size.height - h * act * back
            drawRoundRect(Color(0xFF2B2D31), Offset(2f * u, top), Size(size.width - 4f * u, h), CornerRadius(3f * u))
            for (k in 0 until 3) {
                val y = top + 5f * u + k * 6f * u
                drawLine(PreviewWhite.copy(alpha = 0.35f), Offset(6f * u, y), Offset(6f * u + (30f - k * 7f) * u, y), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
            }
            drawIcon(alpha = 1f - act * back)
        }
        SelectionAction.RENAME -> {
            tiles.forEach { photoTile(it, accent) }
            check(tiles[0], accent, pick * back)
            // The name under the first tile is erased and typed anew, longer.
            val r = tiles[0]
            val y = r.bottom + 2.5f * u
            val erase = ease(seg(t, 0.3f, 0.45f))
            val type = ease(seg(t, 0.5f, 0.7f))
            val width = if (type > 0f) 22f * u * type * back + 6f * u * reset else 14f * u * (1f - erase)
            drawLine(accent, Offset(r.left, y.coerceAtMost(size.height - 1.5f * u)), Offset(r.left + width.coerceAtLeast(0.01f), y.coerceAtMost(size.height - 1.5f * u)), strokeWidth = 1.6f * u, cap = StrokeCap.Round)
            drawIcon()
        }
        SelectionAction.EDIT -> {
            tiles.forEachIndexed { i, r -> if (i != 0) photoTile(r, accent, 1f - 0.6f * act * back) }
            // The first tile grows, and a slider under it brightens it.
            val big = Rect(Offset(3f * u, 3f * u), Size(30f * u, 26f * u))
            val r0 = tiles[0]
            val grow = act * back
            val rr = Rect(lerp(r0.topLeft, big.topLeft, grow), Size(lerp(r0.width, big.width, grow), lerp(r0.height, big.height, grow)))
            photoTile(rr, accent)
            val light = ease(seg(t, 0.55f, 0.8f)) * back
            drawRoundRect(PreviewWhite.copy(alpha = 0.25f * light), rr.topLeft, rr.size, CornerRadius(1.5f * u))
            if (grow > 0.5f) {
                val y = size.height - 4f * u
                drawLine(PreviewWhite.copy(alpha = 0.3f), Offset(5f * u, y), Offset(31f * u, y), strokeWidth = 1.2f * u, cap = StrokeCap.Round)
                drawCircle(accent, 1.8f * u, Offset(lerp(10f * u, 26f * u, light), y))
            }
            drawIcon()
        }
        SelectionAction.QUICK_EDIT -> {
            tiles.forEachIndexed { i, r -> if (i != 0) photoTile(r, accent) }
            val r0 = tiles[0]
            rotate(90f * act * back, r0.center) { photoTile(r0, accent) }
            check(r0, accent, pick * back * (1f - act))
            drawIcon(scale = pulse)
        }
        SelectionAction.SELECT_ALL -> {
            tiles.forEachIndexed { i, r ->
                photoTile(r, accent)
                check(r, accent, ease(seg(t, 0.15f + i * 0.08f, 0.25f + i * 0.08f)) * back)
            }
            drawIcon()
        }
        SelectionAction.HIDE -> {
            tiles.forEachIndexed { i, r ->
                if (i in selected) {
                    photoTile(r, accent, 1f - 0.85f * act * back)
                    check(r, accent, pick * back * (1f - act))
                    if (act > 0f) icon(iconPainter, r.center, 6f * u, PreviewWhite.copy(alpha = act * back))
                } else {
                    photoTile(r, accent)
                }
            }
            drawIcon()
        }
        SelectionAction.FAVORITE -> {
            tiles.forEachIndexed { i, r ->
                photoTile(r, accent)
                if (i in selected) {
                    check(r, accent, pick * back * (1f - act))
                    val pop = act * back
                    if (pop > 0f) icon(iconPainter, Offset(r.left + 3f * u, r.top + 3f * u), 5f * u * (0.6f + 0.4f * pop), Color(0xFFFFC107).copy(alpha = pop))
                }
            }
            drawIcon(scale = pulse)
        }
        SelectionAction.SET_AS -> {
            tiles.forEachIndexed { i, r -> if (i != 0) photoTile(r, accent) }
            // A phone on the right; the photo becomes its screen.
            val phone = Rect(Offset(size.width - 16f * u, 4f * u), Size(12f * u, 32f * u))
            drawRoundRect(PreviewWhite.copy(alpha = 0.55f), phone.topLeft, phone.size, CornerRadius(2.5f * u), style = Stroke(1f * u))
            val r0 = tiles[0]
            val screen = Rect(Offset(phone.left + 1.2f * u, phone.top + 1.2f * u), Size(phone.width - 2.4f * u, phone.height - 2.4f * u))
            val p = act * back
            val rr = Rect(lerp(r0.topLeft, screen.topLeft, p), Size(lerp(r0.width, screen.width, p), lerp(r0.height, screen.height, p)))
            photoTile(rr, accent)
            check(rr, accent, pick * back * (1f - act))
        }
        SelectionAction.STRIP_EXIF -> {
            tiles.forEach { photoTile(it, accent) }
            check(tiles[0], accent, pick * back)
            // The photo's details (place, phone, settings) are wiped one by one.
            for (k in 0 until 3) {
                val wipe = ease(seg(t, 0.3f + k * 0.12f, 0.42f + k * 0.12f)) * back
                val y = 12f * u + k * 7f * u
                val x0 = size.width - 19f * u
                drawLine(PreviewWhite.copy(alpha = 0.45f * (1f - wipe)), Offset(x0, y), Offset(x0 + 15f * u * (1f - wipe), y), strokeWidth = 1.6f * u, cap = StrokeCap.Round)
            }
            if (act > 0.8f) drawIcon(alpha = (act - 0.8f) * 5f * back)
        }
        SelectionAction.CONTENT_ORDER -> {
            // Two tiles change places.
            val a = tiles[0]
            val b = tiles[2]
            val p = act * back
            tiles.forEachIndexed { i, r -> if (i != 0 && i != 2) photoTile(r, accent) }
            val lift = 2.5f * u * kotlin.math.sin(p * kotlin.math.PI.toFloat())
            photoTile(a.movedTo(lerp(a.center, b.center, p) - Offset(0f, lift)), accent)
            photoTile(b.movedTo(lerp(b.center, a.center, p) + Offset(0f, lift)), accent, 0.7f)
            drawIcon()
        }
        SelectionAction.SET_PRIVATE -> {
            // Folders; a lock comes down on the selected one.
            tiles.forEachIndexed { i, r ->
                drawRoundRect(accent.copy(alpha = if (i == 4) 0.75f else 0.45f), r.topLeft + Offset(0f, 1.5f * u), Size(r.width, r.height - 1.5f * u), CornerRadius(1.5f * u))
                drawRoundRect(accent.copy(alpha = if (i == 4) 0.75f else 0.45f), r.topLeft, Size(r.width * 0.45f, 3f * u), CornerRadius(1f * u))
            }
            check(tiles[4], accent, pick * back * (1f - act))
            val lockAt = lerp(spot, tiles[4].center, act * back)
            icon(iconPainter, lockAt, iconSide * (1f - 0.35f * act * back), if (act * back > 0.95f) PreviewWhite else accent)
        }
    }
}

