package dev.ander.alexet.ui.components.aiglow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ander.alexet.data.AiEffect
import dev.ander.alexet.data.AiEffectStyle
import dev.ander.alexet.data.AiEffectsConfig

/**
 * The glowing border of the folders the AI search covers (provided around their grid).
 * [renderer]: one for every tile, so its shader is compiled once, not per folder.
 */
class AiGlint(
    val colors: List<Color>,
    val style: AiEffectStyle,
    val phase: State<Float>,
    val strength: State<Float>,
    val renderer: AiGlowRenderer,
)

val LocalAiGlint = compositionLocalOf<AiGlint?> { null }

/** The border while [active] (lasting, or timed and lit again by [trigger]); null when off. */
@Composable
fun rememberAiGlint(config: AiEffectsConfig, active: Boolean, trigger: Any? = null): AiGlint? {
    val style = config.style(AiEffect.FOLDER_GLINT)
    val strength = rememberEffectStrength(config, AiEffect.FOLDER_GLINT, active, trigger)
    val renderer = remember { AiGlowRenderer() }
    if (!style.enabled || strength.value <= 0f) return null
    val colors = aiColors(config, AiEffect.FOLDER_GLINT)
    val phase = rememberAiPhase(style.speed, periodMs = GLINT_PERIOD_MS)
    return AiGlint(colors, style, phase, strength, renderer)
}

const val GLINT_PERIOD_MS = 4_000

private val RING_STROKE = 1.5.dp
private val RING_GLOW = 14.dp

/**
 * A thin glowing line along this element's border with a soft glow inwards, its colors circling
 * around it. Inside the bounds only (folder tiles sit 1 dp apart).
 */
fun Modifier.aiGlint(glint: AiGlint?): Modifier {
    if (glint == null) return this
    return drawWithContent {
        drawContent()
        val strength = glint.strength.value * glint.style.brightness
        if (strength <= 0f) return@drawWithContent
        val width = glint.style.width
        with(glint.renderer) {
            draw(
                mode = AiGlowMode.EDGE,
                rect = Rect(Offset.Zero, size),
                cornerRadiusPx = 0f,
                glowPx = RING_GLOW.toPx() * width,
                strokePx = RING_STROKE.toPx() * width.coerceIn(0.6f, 2f),
                colors = glint.colors,
                phase = glint.phase.value,
                alpha = strength,
            )
        }
    }
}
