package dev.ander.alexet.ui.components.aiglow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R
import dev.ander.alexet.data.AiEffect
import dev.ander.alexet.data.AiEffectsConfig
import dev.ander.alexet.data.AiLoaderStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The "thinking" animation of the AI search, in the style the config picks. */
@Composable
fun AiLoader(config: AiEffectsConfig, modifier: Modifier = Modifier, style: AiLoaderStyle = config.loaderStyle) {
    val effect = config.style(AiEffect.LOADER)
    if (!effect.enabled) return
    val colors = aiColors(config, AiEffect.LOADER)
    val phase = rememberAiPhase(effect.speed, periodMs = 1_600)
    // The colors go round on their own, slower cycle: calm blending instead of flashing.
    val tint = rememberAiPhase(effect.speed, periodMs = 6_000)
    val strength = effect.brightness.coerceIn(0.2f, 1f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val size = (64 * effect.width.coerceIn(0.6f, 1.8f)).dp
        when (style) {
            AiLoaderStyle.ORB -> Canvas(Modifier.size(size)) { drawOrb(colors, phase, tint, strength) }
            AiLoaderStyle.RING -> Canvas(Modifier.size(size)) { drawRing(colors, phase, tint, strength) }
            AiLoaderStyle.DOTS -> Canvas(Modifier.size(size * 1.4f, size * 0.6f)) { drawDots(colors, phase, tint, strength) }
        }
        if (style == AiLoaderStyle.ORB) {
            Text(
                text = stringResource(R.string.ai_searching),
                style = MaterialTheme.typography.titleSmall.merge(
                    TextStyle(brush = Brush.horizontalGradient(paletteStops(colors, tint.value, turns = 0.5f))),
                ),
            )
        }
    }
}

/** Turns of each orb blob per cycle: whole numbers, so the loop's end meets its start. */
private val BLOB_TURNS = intArrayOf(1, -2, 2, -1)

/**
 * A glowing orb: soft blobs in the palette's colors drift around inside it, its core breathes,
 * and a faint halo shines around it. [phase] moves it, [tint] turns the colors.
 */
private fun DrawScope.drawOrb(colors: List<Color>, phase: State<Float>, tint: State<Float>, strength: Float) {
    val p = phase.value
    val q = tint.value
    val c = center
    val r = size.minDimension * 0.34f
    val breathe = 0.94f + 0.06f * sin(p * 2 * PI).toFloat()
    drawCircle(
        Brush.radialGradient(listOf(paletteAt(colors, q).copy(alpha = 0.45f), Color.Transparent), center = c, radius = r * 1.45f),
        radius = r * 1.45f,
        center = c,
        alpha = strength,
    )
    val orb = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r * breathe)) }
    clipPath(orb) {
        drawCircle(paletteAt(colors, q + 0.5f).copy(alpha = 0.55f), radius = r, center = c, alpha = strength)
        for (i in BLOB_TURNS.indices) {
            // Each blob circles at its own pace, direction and distance.
            val a = (p * BLOB_TURNS[i] + i / 4f) * 2 * PI
            val d = r * (0.3f + 0.12f * (i % 3))
            val pos = Offset(c.x + cos(a).toFloat() * d, c.y + sin(a).toFloat() * d)
            drawCircle(
                Brush.radialGradient(listOf(paletteAt(colors, q + i / 4f), Color.Transparent), center = pos, radius = r * 0.95f),
                radius = r * 0.95f,
                center = pos,
                alpha = 0.9f * strength,
            )
        }
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f * breathe), Color.Transparent), center = c, radius = r * 0.55f),
            radius = r * 0.55f,
            center = c,
            alpha = strength,
        )
    }
    drawCircle(
        Brush.sweepGradient(paletteStops(colors, q), center = c),
        radius = r * breathe,
        center = c,
        alpha = 0.6f * strength,
        style = Stroke(size.minDimension * 0.012f),
    )
}

/** A gradient arc turning around a faint circle. */
private fun DrawScope.drawRing(colors: List<Color>, phase: State<Float>, tint: State<Float>, strength: Float) {
    val p = phase.value
    val q = tint.value
    val w = size.minDimension * 0.09f
    val inset = w / 2
    val arcSize = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
    val topLeft = Offset(inset, inset)
    drawArc(paletteAt(colors, q), 0f, 360f, false, topLeft, arcSize, alpha = 0.15f * strength, style = Stroke(w))
    val sweep = Brush.sweepGradient(paletteStops(colors, q), center = center)
    rotate(p * 360f) {
        drawArc(sweep, 0f, 270f, false, topLeft, arcSize, alpha = strength, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Four dots rising and falling one after another. */
private fun DrawScope.drawDots(colors: List<Color>, phase: State<Float>, tint: State<Float>, strength: Float) {
    val p = phase.value
    val q = tint.value
    val n = 4
    val r = size.height * 0.16f
    val gap = size.width / n
    for (i in 0 until n) {
        val wave = sin((p - i * 0.15f) * 2 * PI).toFloat()
        val y = center.y - wave * size.height * 0.25f
        val scale = 0.8f + 0.25f * ((wave + 1) / 2)
        drawCircle(paletteAt(colors, q + i / (n * 2f)), radius = r * scale, center = Offset(gap * (i + 0.5f), y), alpha = strength)
    }
}
