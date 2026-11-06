package dev.ander.alexet.ui.components.aiglow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ander.alexet.data.AiEffect
import dev.ander.alexet.data.AiEffectStyle
import dev.ander.alexet.data.AiEffectsConfig
import dev.ander.alexet.data.AiCurtainEdge
import dev.ander.alexet.data.AiPalette
import dev.ander.alexet.ui.components.LocalLightMode
import kotlinx.coroutines.delay

/** The colors [effect] uses: its own ones in Custom, else the palette (fixed, or Material You). */
@Composable
fun aiColors(config: AiEffectsConfig, effect: AiEffect): List<Color> {
    val own = config.style(effect).customColors
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val palette = config.palette
    return remember(own, palette, scheme) {
        own?.map { Color(it) } ?: when (palette) {
            AiPalette.MULTI_COLOR -> AiEffectsConfig.MULTI_COLORS.map { Color(it) }
            AiPalette.SYSTEM -> materialYouColors(context, scheme)
        }
    }
}

/**
 * The whole Material You palette: light, middle and dark tones of the system's three dynamic
 * accent palettes (Android 12+), alternating so neighbours blend; before that, the theme's
 * accents with their darker containers.
 */
private fun materialYouColors(context: android.content.Context, scheme: ColorScheme): List<Color> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return listOf(
            android.R.color.system_accent1_200,
            android.R.color.system_accent3_500,
            android.R.color.system_accent2_700,
            android.R.color.system_accent1_500,
            android.R.color.system_accent3_200,
            android.R.color.system_accent2_500,
            android.R.color.system_accent1_700,
            android.R.color.system_accent3_700,
            android.R.color.system_accent2_200,
        ).map { Color(context.getColor(it)) }
    }
    return listOf(
        scheme.primary, scheme.tertiaryContainer, scheme.secondary,
        scheme.primaryContainer, scheme.tertiary, scheme.secondaryContainer,
        scheme.inversePrimary, scheme.onTertiaryContainer, scheme.onSecondaryContainer,
    )
}

/**
 * 0..1, one turn of the colors per [periodMs] / speed; still in light mode (no endless
 * animations) and with the system's animations off. Counted from the frame clock, so a new
 * [speed] applies at once and without a jump (an infinite transition keeps its first tempo).
 */
@Composable
fun rememberAiPhase(speed: Float, periodMs: Int = 4_000): State<Float> {
    if (LocalLightMode.current) return remember { mutableFloatStateOf(0.1f) }
    val phase = remember { mutableFloatStateOf(0f) }
    val perMs by rememberUpdatedState(speed.coerceAtLeast(0.05f) / periodMs)
    LaunchedEffect(Unit) {
        val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        if (scale <= 0f) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) phase.floatValue = (phase.floatValue + (now - last) / 1e6f * perMs / scale) % 1f
                last = now
            }
        }
    }
    return phase
}

/**
 * Strength 0..1 of a one-off effect: up when [trigger] changes to a value above 0, held, and
 * faded out so the whole lasts [durationMs].
 */
@Composable
fun rememberTimedGlow(trigger: Int, durationMs: Int): State<Float> {
    val strength = remember { Animatable(0f) }
    LaunchedEffect(trigger, durationMs) {
        if (trigger <= 0) {
            strength.snapTo(0f)
            return@LaunchedEffect
        }
        strength.animateTo(1f, tween(GLOW_FADE_IN_MS))
        delay((durationMs - GLOW_FADE_IN_MS - GLOW_FADE_OUT_MS).coerceAtLeast(0).toLong())
        strength.animateTo(0f, tween(GLOW_FADE_OUT_MS))
    }
    return strength.asState()
}

/** A one-off effect over and over (previews): [durationMs] of it, then [gapMs] without, again. */
@Composable
fun rememberLoopingGlow(durationMs: Int, gapMs: Long = 2_000L): State<Float> {
    val strength = remember { Animatable(0f) }
    LaunchedEffect(durationMs) {
        while (true) {
            strength.animateTo(1f, tween(GLOW_FADE_IN_MS))
            delay((durationMs - GLOW_FADE_IN_MS - GLOW_FADE_OUT_MS).coerceAtLeast(0).toLong())
            strength.animateTo(0f, tween(GLOW_FADE_OUT_MS))
            delay(gapMs)
        }
    }
    return strength.asState()
}

private const val GLOW_FADE_IN_MS = 350
private const val GLOW_FADE_OUT_MS = 700

/**
 * Strength 0..1 of an effect shown while [active]: lasting, or (set to timed) out after its
 * duration and lit again when [trigger] changes (a new search).
 */
@Composable
fun rememberEffectStrength(config: AiEffectsConfig, effect: AiEffect, active: Boolean, trigger: Any? = null): State<Float> {
    val style = config.style(effect)
    val timed = config.isTimed(effect)
    val strength = remember { Animatable(0f) }
    LaunchedEffect(active && style.enabled, timed, style.durationMs, trigger) {
        if (!active || !style.enabled) {
            strength.animateTo(0f, tween(GLOW_FADE_IN_MS))
            return@LaunchedEffect
        }
        strength.animateTo(1f, tween(GLOW_FADE_IN_MS))
        if (timed) {
            delay((style.durationMs - GLOW_FADE_IN_MS - GLOW_FADE_OUT_MS).coerceAtLeast(0).toLong())
            strength.animateTo(0f, tween(GLOW_FADE_OUT_MS))
        }
    }
    return strength.asState()
}

/**
 * A ring with a soft halo just outside this element's rounded shape, its colors circling.
 * Drawn behind the content and outside the bounds (a clipping parent cuts the halo).
 */
fun Modifier.aiGlowHalo(
    colors: List<Color>,
    style: AiEffectStyle,
    cornerRadius: Dp,
    phase: State<Float>,
    strength: () -> Float,
    glow: Dp = 14.dp,
    stroke: Dp = 2.dp,
): Modifier = drawWithCache {
    val renderer = AiGlowRenderer()
    val radiusPx = cornerRadius.toPx()
    val glowPx = glow.toPx() * style.width
    val strokePx = stroke.toPx() * style.width.coerceIn(0.75f, 1.5f)
    onDrawBehind {
        with(renderer) {
            draw(AiGlowMode.HALO, Rect(Offset.Zero, size), radiusPx, glowPx, strokePx, colors, phase.value, strength() * style.brightness)
        }
    }
}

/**
 * The same ring and halo around [target] (in this element's coordinates; null: nothing), drawn
 * over this element. For a target whose own parents would clip its halo.
 */
fun Modifier.aiGlowHaloAround(
    target: () -> Rect?,
    colors: List<Color>,
    style: AiEffectStyle,
    cornerRadius: Dp,
    phase: State<Float>,
    strength: () -> Float,
    glow: Dp = 14.dp,
    stroke: Dp = 2.dp,
): Modifier = drawWithCache {
    val renderer = AiGlowRenderer()
    val radiusPx = cornerRadius.toPx()
    val glowPx = glow.toPx() * style.width
    val strokePx = stroke.toPx() * style.width.coerceIn(0.75f, 1.5f)
    onDrawWithContent {
        drawContent()
        val rect = target() ?: return@onDrawWithContent
        with(renderer) {
            draw(AiGlowMode.HALO, rect, radiusPx, glowPx, strokePx, colors, phase.value, strength() * style.brightness)
        }
    }
}

/**
 * Glow on the edges of the area it fills, for [AiEffect.RESULTS_EDGE]'s duration after [trigger]
 * changes; [loop]: again and again (previews). Its corners follow [cornerRadius], or (null) the
 * screen's own rounded corners where the area reaches them.
 */
@Composable
fun AiEdgeGlow(
    config: AiEffectsConfig,
    trigger: Int,
    modifier: Modifier = Modifier,
    loop: Boolean = false,
    cornerRadius: Dp? = null,
) {
    val style = config.style(AiEffect.RESULTS_EDGE)
    if (!style.enabled) return
    val colors = aiColors(config, AiEffect.RESULTS_EDGE)
    val phase = rememberAiPhase(style.speed)
    val strength by if (loop) rememberLoopingGlow(style.durationMs) else rememberTimedGlow(trigger, style.durationMs)
    if (strength <= 0f) return
    val renderer = remember { AiGlowRenderer() }
    val view = LocalView.current
    var screenCorners by remember { mutableStateOf<AiCorners?>(null) }
    val fixed = cornerRadius?.let { with(LocalDensity.current) { AiCorners.all(it.toPx()) } }
    Canvas(
        modifier
            .fillMaxSize()
            // Its own layer: each frame redraws only the glow, not the photos around it.
            .graphicsLayer()
            .then(if (fixed == null) Modifier.onGloballyPositioned { screenCorners = screenCornersAt(view, it.boundsInWindow()) } else Modifier),
    ) {
        // A share of the shorter side: the same look on the screen and in a small preview.
        val reach = size.minDimension * EDGE_REACH * style.width
        with(renderer) {
            draw(AiGlowMode.EDGE, Rect(Offset.Zero, size), 0f, reach, 0f, colors, phase.value, strength * style.brightness, fixed ?: screenCorners)
        }
    }
}

/** Edge glow reach per width 1: about 34 dp on a phone. */
private const val EDGE_REACH = 0.085f

/** Curtain reach per width 1, of the area's depth: about 120 dp from the top of a phone. */
private const val CURTAIN_REACH = 0.15f

/**
 * The screen's rounded corners (Android 12+) at those corners of [bounds] (in the window) that
 * lie in the window's corners; square elsewhere. Null when unknown.
 */
private fun screenCornersAt(view: android.view.View, bounds: Rect): AiCorners? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val insets = view.rootWindowInsets ?: return null
    val root = view.rootView
    val left = bounds.left <= 1f
    val top = bounds.top <= 1f
    val right = bounds.right >= root.width - 1f
    val bottom = bounds.bottom >= root.height - 1f
    fun radius(position: Int, inCorner: Boolean): Float =
        if (inCorner) insets.getRoundedCorner(position)?.radius?.toFloat() ?: 0f else 0f
    return AiCorners(
        topLeft = radius(android.view.RoundedCorner.POSITION_TOP_LEFT, left && top),
        topRight = radius(android.view.RoundedCorner.POSITION_TOP_RIGHT, right && top),
        bottomRight = radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT, right && bottom),
        bottomLeft = radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT, left && bottom),
    )
}

/**
 * The curtain: a soft wavy glow from the configured edge(s) ([AiEffectsConfig.curtainEdge]) while
 * [active]; [trigger] lights a timed curtain again. [loop]: previews.
 */
@Composable
fun AiCurtainGlow(
    config: AiEffectsConfig,
    active: Boolean,
    modifier: Modifier = Modifier,
    trigger: Any? = null,
    loop: Boolean = false,
) {
    val style = config.style(AiEffect.SEARCH_BACKGROUND)
    val strength by if (loop && config.isTimed(AiEffect.SEARCH_BACKGROUND)) {
        rememberLoopingGlow(style.durationMs)
    } else {
        rememberEffectStrength(config, AiEffect.SEARCH_BACKGROUND, active, trigger)
    }
    if (strength <= 0f || !style.enabled) return
    val colors = aiColors(config, AiEffect.SEARCH_BACKGROUND)
    val phase = rememberAiPhase(style.speed, periodMs = 6_000)
    val renderer = remember { AiGlowRenderer() }
    val edge = config.curtainEdge
    // Its own layer: each frame redraws only the curtain, not what is under or over it.
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val edges = when (edge) {
            AiCurtainEdge.SIDES -> listOf(AiCurtainEdge.LEFT, AiCurtainEdge.RIGHT)
            else -> listOf(edge)
        }
        for (e in edges) {
            // Drawn as a top curtain, turned onto its edge: [length] along it, [depth] into the screen.
            val vertical = e == AiCurtainEdge.LEFT || e == AiCurtainEdge.RIGHT
            val length = if (vertical) size.height else size.width
            val depth = if (vertical) size.width else size.height
            // A share of the depth (at most 37.5%): it fades out completely before the far side,
            // and a small preview shows the same proportions as the screen.
            val reach = depth * CURTAIN_REACH * style.width
            withTransform({
                when (e) {
                    AiCurtainEdge.TOP, AiCurtainEdge.SIDES -> Unit
                    AiCurtainEdge.BOTTOM -> rotate(180f, pivot = center)
                    AiCurtainEdge.LEFT -> {
                        translate(top = size.height)
                        rotate(-90f, pivot = Offset.Zero)
                    }
                    AiCurtainEdge.RIGHT -> {
                        translate(left = size.width)
                        rotate(90f, pivot = Offset.Zero)
                    }
                }
            }) {
                with(renderer) {
                    draw(AiGlowMode.TOP_WASH, Rect(0f, 0f, length, depth), 0f, reach, 0f, colors, phase.value, strength * 0.55f * style.brightness)
                }
            }
        }
    }
}
