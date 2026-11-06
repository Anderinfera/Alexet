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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ander.alexet.ui.components.LocalLightMode
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Player option shown by [PlayerOptionPreview]. */
internal enum class PlayerPreviewKind {
    VOLUME,
    BRIGHTNESS,
    LONG_PRESS,
    DOUBLE_TAP,
    AUTO_HIDE,
    DOUBLE_CLICK_PLAYER,
    NAV_ARROWS,
    GIF,
}

private val PreviewScreen = Color(0xFF1C1D20)
private val PreviewWhite = Color.White

/**
 * A small player screen acting out its option in a loop: the gesture and what it does, with the
 * current values (speed, seek step, hide time). An option that is off stands dimmed and still;
 * light mode shows one still frame.
 */
@Composable
internal fun PlayerOptionPreview(
    kind: PlayerPreviewKind,
    active: Boolean,
    modifier: Modifier = Modifier,
    speed: Float = 2f,
    seekSeconds: Int = 10,
    hideSeconds: Int = 4,
) {
    val cycleMs = when (kind) {
        PlayerPreviewKind.VOLUME, PlayerPreviewKind.BRIGHTNESS -> 2200
        PlayerPreviewKind.LONG_PRESS -> 2600
        PlayerPreviewKind.DOUBLE_TAP -> 1800
        // Real time: the controls stay up as long as they will in the player.
        PlayerPreviewKind.AUTO_HIDE -> hideSeconds * 1000 + 1600
        PlayerPreviewKind.DOUBLE_CLICK_PLAYER -> 2800
        PlayerPreviewKind.NAV_ARROWS -> 3200
        PlayerPreviewKind.GIF -> 1600
    }
    val stillFrame = when (kind) {
        PlayerPreviewKind.AUTO_HIDE -> (300f + hideSeconds * 300f) / cycleMs
        PlayerPreviewKind.DOUBLE_CLICK_PLAYER -> 0.75f
        PlayerPreviewKind.NAV_ARROWS -> 0.2f
        PlayerPreviewKind.GIF -> 0.3f
        else -> 0.5f
    }
    // Read only while drawing: the frames redraw the canvas without recomposing the row.
    val time: State<Float>? = if (active && !LocalLightMode.current) {
        key(cycleMs) {
            rememberInfiniteTransition(label = "playerPreview").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(cycleMs, easing = LinearEasing), RepeatMode.Restart),
                label = "playerPreviewTime",
            )
        }
    } else {
        null
    }
    val accent = MaterialTheme.colorScheme.primary
    val textMeasurer = rememberTextMeasurer()
    val labelText = when (kind) {
        PlayerPreviewKind.LONG_PRESS -> String.format(Locale.US, "%.1fx", speed)
        PlayerPreviewKind.DOUBLE_TAP -> "+${seekSeconds}s"
        PlayerPreviewKind.GIF -> "GIF"
        PlayerPreviewKind.AUTO_HIDE -> "${hideSeconds}s"
        else -> null
    }
    val label = remember(labelText, textMeasurer) {
        labelText?.let {
            textMeasurer.measure(it, TextStyle(color = PreviewWhite, fontSize = 8.sp, fontWeight = FontWeight.Bold))
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
            PlayerPreviewKind.VOLUME -> drawSideSwipe(t, rightSide = true, accent = accent)
            PlayerPreviewKind.BRIGHTNESS -> drawSideSwipe(t, rightSide = false, accent = accent)
            PlayerPreviewKind.LONG_PRESS -> drawLongPress(t, speed, accent, label)
            PlayerPreviewKind.DOUBLE_TAP -> drawDoubleTap(t, accent, label)
            PlayerPreviewKind.AUTO_HIDE -> drawAutoHide(t * cycleMs, hideSeconds, accent, label)
            PlayerPreviewKind.DOUBLE_CLICK_PLAYER -> drawDoubleClickToPlayer(t, accent)
            PlayerPreviewKind.NAV_ARROWS -> drawNavArrows(t, accent)
            PlayerPreviewKind.GIF -> drawGif(t, active, accent, label)
        }
    }
}

/** 0 before [a], 1 after [b], linear between. */
private fun seg(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

private fun ease(x: Float): Float = x * x * (3f - 2f * x)

private fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f

private fun DrawScope.drawFinger(center: Offset, alpha: Float) {
    if (alpha <= 0f) return
    drawCircle(PreviewWhite.copy(alpha = 0.85f * alpha), radius = 3.5.dp.toPx(), center = center)
    drawCircle(PreviewWhite.copy(alpha = 0.35f * alpha), radius = 5.5.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
}

/** A ring spreading out of a tap, [p] from 0 (tap) to 1 (gone). */
private fun DrawScope.drawRipple(center: Offset, p: Float, color: Color) {
    if (p <= 0f || p >= 1f) return
    drawCircle(
        color.copy(alpha = (1f - p) * 0.9f),
        radius = lerp(3.dp.toPx(), 11.dp.toPx(), p),
        center = center,
        style = Stroke(1.5.dp.toPx()),
    )
}

private fun DrawScope.drawLabel(label: TextLayoutResult?, center: Offset, alpha: Float, background: Color? = null) {
    if (label == null || alpha <= 0f) return
    val w = label.size.width.toFloat()
    val h = label.size.height.toFloat()
    val topLeft = Offset(center.x - w / 2f, center.y - h / 2f)
    if (background != null) {
        val pad = 3.dp.toPx()
        drawRoundRect(
            background.copy(alpha = background.alpha * alpha),
            topLeft = Offset(topLeft.x - pad, topLeft.y - 1.dp.toPx()),
            size = Size(w + pad * 2f, h + 2.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
    }
    drawText(label, topLeft = topLeft, alpha = alpha)
}

private fun DrawScope.drawPlayTriangle(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x - radius * 0.6f, center.y - radius)
        lineTo(center.x + radius, center.y)
        lineTo(center.x - radius * 0.6f, center.y + radius)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawChevron(center: Offset, half: Float, pointsRight: Boolean, color: Color, stroke: Float) {
    val dir = if (pointsRight) 1f else -1f
    val path = Path().apply {
        moveTo(center.x - dir * half * 0.5f, center.y - half)
        lineTo(center.x + dir * half * 0.5f, center.y)
        lineTo(center.x - dir * half * 0.5f, center.y + half)
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
}

/** Volume (right edge) or brightness (left edge): a finger slides up and the level fills. */
private fun DrawScope.drawSideSwipe(t: Float, rightSide: Boolean, accent: Color) {
    val w = size.width
    val h = size.height
    val move = ease(seg(t, 0.15f, 0.65f))
    val back = seg(t, 0.85f, 1f)
    val level = 0.25f + 0.55f * move * (1f - back)
    val fingerAlpha = seg(t, 0.08f, 0.15f) * (1f - seg(t, 0.68f, 0.76f))
    val shown = seg(t, 0.12f, 0.2f) * (1f - seg(t, 0.8f, 0.9f))

    // Level bar at the swiped edge.
    val barX = if (rightSide) w * 0.88f else w * 0.12f
    val top = h * 0.2f
    val bottom = h * 0.8f
    val barW = 3.dp.toPx()
    val radius = CornerRadius(barW / 2f)
    drawRoundRect(PreviewWhite.copy(alpha = 0.22f), Offset(barX - barW / 2f, top), Size(barW, bottom - top), radius)
    val fillTop = bottom - (bottom - top) * level
    drawRoundRect(accent, Offset(barX - barW / 2f, fillTop), Size(barW, bottom - fillTop), radius)

    // What the swipe changes, in the middle while it lasts.
    val c = Offset(w * 0.5f, h * 0.5f)
    val glyph = PreviewWhite.copy(alpha = 0.35f + 0.65f * shown)
    if (rightSide) {
        val u = 1.dp.toPx()
        val body = Path().apply {
            moveTo(c.x - 5f * u, c.y - 2.5f * u)
            lineTo(c.x - 2f * u, c.y - 2.5f * u)
            lineTo(c.x + 2f * u, c.y - 6f * u)
            lineTo(c.x + 2f * u, c.y + 6f * u)
            lineTo(c.x - 2f * u, c.y + 2.5f * u)
            lineTo(c.x - 5f * u, c.y + 2.5f * u)
            close()
        }
        drawPath(body, glyph)
        val waves = 1 + (level * 2.99f).toInt()
        repeat(waves) { i ->
            val r = (4f + 3f * i) * u
            drawArc(
                glyph,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(c.x + 1f * u - r, c.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(1.2f * u, cap = StrokeCap.Round),
            )
        }
    } else {
        val u = 1.dp.toPx()
        drawCircle(glyph, radius = (2f + 1.5f * level) * u, center = c)
        repeat(8) { i ->
            val a = i * PI.toFloat() / 4f
            val dir = Offset(cos(a), sin(a))
            drawLine(glyph, c + dir * (5f * u), c + dir * ((6.5f + 1.5f * level) * u), strokeWidth = 1.2f * u, cap = StrokeCap.Round)
        }
    }

    val fingerX = if (rightSide) w * 0.68f else w * 0.32f
    drawFinger(Offset(fingerX, lerp(h * 0.75f, h * 0.3f, move)), fingerAlpha)
}

/** Hold: a finger presses, the speed shows on top and the progress runs faster. */
private fun DrawScope.drawLongPress(t: Float, speed: Float, accent: Color, label: TextLayoutResult?) {
    val w = size.width
    val h = size.height
    val pressed = seg(t, 0.15f, 0.2f) * (1f - seg(t, 0.8f, 0.85f))
    val finger = Offset(w * 0.5f, h * 0.55f)
    drawFinger(finger, seg(t, 0.08f, 0.15f) * (1f - seg(t, 0.85f, 0.92f)))
    if (pressed > 0f) {
        val pulse = (t * 5f) % 1f
        drawCircle(accent.copy(alpha = (1f - pulse) * 0.7f * pressed), radius = lerp(5.dp.toPx(), 10.dp.toPx(), pulse), center = finger, style = Stroke(1.2.dp.toPx()))
    }
    drawLabel(label, Offset(w * 0.5f, 8.dp.toPx()), pressed, background = accent.copy(alpha = 0.85f))

    // Progress: faster while held, by the chosen speed.
    val y = h - 4.dp.toPx()
    val x0 = 5.dp.toPx()
    val x1 = w - 5.dp.toPx()
    val played = (0.1f + 0.3f * t + 0.2f * (speed - 1f) * seg(t, 0.15f, 0.8f)).coerceAtMost(0.98f)
    drawLine(PreviewWhite.copy(alpha = 0.25f), Offset(x0, y), Offset(x1, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(accent, Offset(x0, y), Offset(lerp(x0, x1, played), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
}

/** Two taps on the right: the step jumps forward and shows as "+Ns". */
private fun DrawScope.drawDoubleTap(t: Float, accent: Color, label: TextLayoutResult?) {
    val w = size.width
    val h = size.height
    val tap = Offset(w * 0.72f, h * 0.55f)
    val shown = seg(t, 0.28f, 0.34f) * (1f - seg(t, 0.75f, 0.85f))
    if (shown > 0f) {
        drawCircle(PreviewWhite.copy(alpha = 0.12f * shown), radius = w * 0.45f, center = Offset(w * 1.05f, h * 0.5f))
    }
    drawFinger(tap, seg(t, 0.04f, 0.08f) * (1f - seg(t, 0.4f, 0.46f)))
    drawRipple(tap, seg(t, 0.08f, 0.3f), PreviewWhite)
    drawRipple(tap, seg(t, 0.2f, 0.42f), PreviewWhite)
    if (shown > 0f) {
        val cy = h * 0.3f
        val stroke = 1.4.dp.toPx()
        drawChevron(Offset(w * 0.7f, cy), 3.dp.toPx(), true, PreviewWhite.copy(alpha = shown), stroke)
        drawChevron(Offset(w * 0.7f + 4.dp.toPx(), cy), 3.dp.toPx(), true, PreviewWhite.copy(alpha = shown), stroke)
    }
    drawLabel(label, Offset(w * 0.72f, h * 0.72f), shown)

    // Progress jumps by the step.
    val y = h - 4.dp.toPx()
    val x0 = 5.dp.toPx()
    val x1 = w - 5.dp.toPx()
    val played = 0.3f + 0.25f * ease(seg(t, 0.3f, 0.4f)) * (1f - seg(t, 0.9f, 1f))
    drawLine(PreviewWhite.copy(alpha = 0.25f), Offset(x0, y), Offset(x1, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(accent, Offset(x0, y), Offset(lerp(x0, x1, played), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
}

/** A tap brings the controls; they go after the chosen time, counted down around the play button. */
private fun DrawScope.drawAutoHide(ms: Float, hideSeconds: Int, accent: Color, label: TextLayoutResult?) {
    val w = size.width
    val h = size.height
    val hideMs = hideSeconds * 1000f
    val visible = seg(ms, 100f, 300f) * (1f - seg(ms, 300f + hideMs, 600f + hideMs))
    val c = Offset(w * 0.5f, h * 0.48f)
    drawFinger(c, seg(ms, 0f, 80f) * (1f - seg(ms, 250f, 400f)))
    drawRipple(c, seg(ms, 60f, 400f), PreviewWhite)
    if (visible <= 0f) return

    drawRect(Color.Black.copy(alpha = 0.3f * visible))
    drawPlayTriangle(c, 4.dp.toPx(), PreviewWhite.copy(alpha = visible))
    // Time left before they hide.
    val left = 1f - seg(ms, 300f, 300f + hideMs)
    val r = 8.dp.toPx()
    drawArc(
        accent.copy(alpha = visible),
        startAngle = -90f,
        sweepAngle = 360f * left,
        useCenter = false,
        topLeft = Offset(c.x - r, c.y - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
    )
    drawLabel(label, Offset(w - 9.dp.toPx(), 7.dp.toPx()), visible)
    val y = h - 4.dp.toPx()
    val x0 = 5.dp.toPx()
    val x1 = w - 5.dp.toPx()
    drawLine(PreviewWhite.copy(alpha = 0.25f * visible), Offset(x0, y), Offset(x1, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(accent.copy(alpha = visible), Offset(x0, y), Offset(lerp(x0, x1, 0.4f), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
}

/** Double tap on a video tile in the grid: it opens straight in the player. */
private fun DrawScope.drawDoubleClickToPlayer(t: Float, accent: Color) {
    val w = size.width
    val h = size.height
    val gap = 3.dp.toPx()
    val tileW = (w - gap * 4f) / 3f
    val tileH = tileW * 0.85f
    val top = (h - tileH) / 2f
    val tiles = List(3) { i -> Rect(Offset(gap + i * (tileW + gap), top), Size(tileW, tileH)) }
    val radius = CornerRadius(2.dp.toPx())
    tiles.forEachIndexed { i, r ->
        if (i != 1) drawRoundRect(PreviewWhite.copy(alpha = 0.22f), r.topLeft, r.size, radius)
    }
    val open = ease(seg(t, 0.42f, 0.58f)) * (1f - seg(t, 0.92f, 1f))
    val from = tiles[1]
    val rect = Rect(
        lerp(from.left, 0f, open),
        lerp(from.top, 0f, open),
        lerp(from.right, w, open),
        lerp(from.bottom, h, open),
    )
    drawRoundRect(accent.copy(alpha = 0.55f + 0.2f * open), rect.topLeft, rect.size, CornerRadius(lerp(2.dp.toPx(), 0f, open)))
    drawPlayTriangle(rect.center, lerp(2.5.dp.toPx(), 5.dp.toPx(), open), PreviewWhite)
    val tap = from.center + Offset(2.dp.toPx(), 3.dp.toPx())
    drawFinger(tap, seg(t, 0.04f, 0.1f) * (1f - seg(t, 0.38f, 0.44f)))
    drawRipple(tap, seg(t, 0.1f, 0.3f), PreviewWhite)
    drawRipple(tap, seg(t, 0.2f, 0.4f), PreviewWhite)
}

/** Side arrows: a tap on the right one slides to the next picture, the left one back. */
private fun DrawScope.drawNavArrows(t: Float, accent: Color) {
    val w = size.width
    val h = size.height
    val forward = ease(seg(t, 0.35f, 0.5f)) - ease(seg(t, 0.8f, 0.95f))
    val inset = Rect(10.dp.toPx(), 5.dp.toPx(), w - 10.dp.toPx(), h - 5.dp.toPx())
    val radius = CornerRadius(2.dp.toPx())
    drawRoundRect(accent.copy(alpha = 0.6f), inset.topLeft + Offset(-w * forward, 0f), inset.size, radius)
    drawRoundRect(PreviewWhite.copy(alpha = 0.35f), inset.topLeft + Offset(w * (1f - forward), 0f), inset.size, radius)

    val cy = h * 0.5f
    val half = 3.5.dp.toPx()
    val stroke = 1.6.dp.toPx()
    val left = Offset(5.dp.toPx(), cy)
    val right = Offset(w - 5.dp.toPx(), cy)
    drawChevron(left, half, false, PreviewWhite, stroke)
    drawChevron(right, half, true, PreviewWhite, stroke)
    drawRipple(right, seg(t, 0.25f, 0.45f), PreviewWhite)
    drawRipple(left, seg(t, 0.7f, 0.9f), PreviewWhite)
}

/** A GIF tile playing by itself; off, it waits for a tap. */
private fun DrawScope.drawGif(t: Float, active: Boolean, accent: Color, label: TextLayoutResult?) {
    val w = size.width
    val h = size.height
    val tri = if (t < 0.5f) t * 2f else 2f - t * 2f
    val ball = Offset(lerp(w * 0.25f, w * 0.75f, tri), h * 0.75f - abs(sin(t * 2f * PI.toFloat() * 2f)) * h * 0.4f)
    drawLine(PreviewWhite.copy(alpha = 0.25f), Offset(w * 0.12f, h * 0.82f), Offset(w * 0.88f, h * 0.82f), strokeWidth = 1.dp.toPx())
    drawCircle(accent, radius = 4.dp.toPx(), center = ball)
    drawLabel(label, Offset(11.dp.toPx(), 8.dp.toPx()), 1f, background = Color.Black.copy(alpha = 0.6f))
    if (!active) drawPlayTriangle(Offset(w * 0.5f, h * 0.45f), 5.dp.toPx(), PreviewWhite)
}
