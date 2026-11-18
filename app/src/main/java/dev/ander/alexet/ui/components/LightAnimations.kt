package dev.ander.alexet.ui.components

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.AnimatedContent as ComposeAnimatedContent
import androidx.compose.animation.AnimatedVisibility as ComposeAnimatedVisibility
import androidx.compose.animation.Crossfade as ComposeCrossfade
import androidx.compose.animation.animateContentSize as composeAnimateContentSize
import androidx.compose.animation.core.animateDpAsState as composeAnimateDpAsState
import androidx.compose.animation.core.animateFloatAsState as composeAnimateFloatAsState

/**
 * Light mode: UI animations are cut. Screens import these functions instead of the
 * `androidx.compose.animation` ones (same names and parameters); with [LocalLightMode] on they
 * switch instantly, otherwise they behave exactly like the originals.
 */
val LocalLightMode = compositionLocalOf { false }

@Composable
fun <T> AnimatedContent(
    targetState: T,
    modifier: Modifier = Modifier,
    transitionSpec: AnimatedContentTransitionScope<T>.() -> ContentTransform = {
        (fadeIn(animationSpec = androidx.compose.animation.core.tween(220, delayMillis = 90)))
            .togetherWith(fadeOut(animationSpec = androidx.compose.animation.core.tween(90)))
    },
    contentAlignment: Alignment = Alignment.TopStart,
    label: String = "AnimatedContent",
    contentKey: (targetState: T) -> Any? = { it },
    content: @Composable AnimatedContentScope.(targetState: T) -> Unit,
) {
    val light = LocalLightMode.current
    ComposeAnimatedContent(
        targetState = targetState,
        modifier = modifier,
        // Light mode: no animation, but the caller's size handling (e.g. no clipping) stays.
        transitionSpec = if (light) ({ (EnterTransition.None togetherWith ExitTransition.None) using transitionSpec().sizeTransform }) else transitionSpec,
        contentAlignment = contentAlignment,
        label = label,
        contentKey = contentKey,
        content = content,
    )
}

@Composable
fun AnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn() + expandVertically(),
    exit: ExitTransition = fadeOut() + shrinkVertically(),
    label: String = "AnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val light = LocalLightMode.current
    ComposeAnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (light) EnterTransition.None else enter,
        exit = if (light) ExitTransition.None else exit,
        label = label,
        content = content,
    )
}

/**
 * [AnimatedVisibility] for content most instances never show, such as overlays on thousands of
 * grid tiles: nothing (not even a Transition) is created until [visible] first turns true. It
 * then animates in as usual; visible from the start, it is shown at once like the original.
 */
@Composable
fun DeferredAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn() + expandVertically(),
    exit: ExitTransition = fadeOut() + shrinkVertically(),
    label: String = "AnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val initiallyVisible = remember { visible }
    val holder = remember { arrayOfNulls<MutableTransitionState<Boolean>>(1) }
    val state = holder[0] ?: run {
        if (!visible) return
        MutableTransitionState(initiallyVisible).also { holder[0] = it }
    }
    SideEffect { state.targetState = visible }
    val light = LocalLightMode.current
    ComposeAnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = if (light) EnterTransition.None else enter,
        exit = if (light) ExitTransition.None else exit,
        label = label,
        content = content,
    )
}

@Composable
fun ColumnScope.AnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn() + expandVertically(),
    exit: ExitTransition = fadeOut() + shrinkVertically(),
    label: String = "AnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val light = LocalLightMode.current
    with(this) {
        ComposeAnimatedVisibility(
            visible = visible,
            modifier = modifier,
            enter = if (light) EnterTransition.None else enter,
            exit = if (light) ExitTransition.None else exit,
            label = label,
            content = content,
        )
    }
}

@Composable
fun RowScope.AnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn() + androidx.compose.animation.expandHorizontally(),
    exit: ExitTransition = fadeOut() + androidx.compose.animation.shrinkHorizontally(),
    label: String = "AnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val light = LocalLightMode.current
    with(this) {
        ComposeAnimatedVisibility(
            visible = visible,
            modifier = modifier,
            enter = if (light) EnterTransition.None else enter,
            exit = if (light) ExitTransition.None else exit,
            label = label,
            content = content,
        )
    }
}

@Composable
fun <T> Crossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    animationSpec: FiniteAnimationSpec<Float> = androidx.compose.animation.core.tween(),
    label: String = "Crossfade",
    content: @Composable (T) -> Unit,
) {
    if (LocalLightMode.current) {
        androidx.compose.foundation.layout.Box(modifier) { content(targetState) }
    } else {
        ComposeCrossfade(targetState, modifier, animationSpec, label, content)
    }
}

fun Modifier.animateContentSize(
    animationSpec: FiniteAnimationSpec<IntSize> = spring(
        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        visibilityThreshold = IntSize.VisibilityThreshold,
    ),
    finishedListener: ((initialValue: IntSize, targetValue: IntSize) -> Unit)? = null,
): Modifier = composed {
    if (LocalLightMode.current) Modifier else Modifier.composeAnimateContentSize(animationSpec, finishedListener)
}

@Composable
fun animateFloatAsState(
    targetValue: Float,
    animationSpec: AnimationSpec<Float> = spring(),
    visibilityThreshold: Float = 0.01f,
    label: String = "FloatAnimation",
    finishedListener: ((Float) -> Unit)? = null,
): State<Float> = composeAnimateFloatAsState(
    targetValue = targetValue,
    animationSpec = if (LocalLightMode.current) snap() else animationSpec,
    visibilityThreshold = visibilityThreshold,
    label = label,
    finishedListener = finishedListener,
)

/** What [deferredAnimateFloatAsState] returns until its value first moves. */
private class RestingFloat(override val value: Float) : State<Float>

/**
 * [animateFloatAsState] for values most instances never move from [restingValue], such as a scale
 * on thousands of grid tiles: nothing is set up until [targetValue] first differs from it. It then
 * animates exactly like the original (same spec, from the same start); different from the start,
 * it begins there like the original.
 */
@Composable
fun deferredAnimateFloatAsState(
    targetValue: Float,
    restingValue: Float,
    animationSpec: AnimationSpec<Float> = spring(),
    visibilityThreshold: Float = 0.01f,
    label: String = "FloatAnimation",
): State<Float> {
    // The original's Animatable starts at the first target it was given.
    val initialValue = remember { targetValue }
    val holder = remember { arrayOfNulls<Animatable<Float, AnimationVector1D>>(1) }
    val animatable = holder[0] ?: run {
        if (targetValue == restingValue) return remember(restingValue) { RestingFloat(restingValue) }
        Animatable(initialValue, visibilityThreshold).also { holder[0] = it }
    }
    return animateStartedFloat(animatable, targetValue, animationSpec, visibilityThreshold)
}

/** The body of animateFloatAsState (light mode: snap), on an Animatable made elsewhere. */
@Composable
private fun animateStartedFloat(
    animatable: Animatable<Float, AnimationVector1D>,
    targetValue: Float,
    animationSpec: AnimationSpec<Float>,
    visibilityThreshold: Float,
): State<Float> {
    val spec = if (LocalLightMode.current) snap() else animationSpec
    val resolvedSpec by rememberUpdatedState(
        if (spec is SpringSpec<*> && spec.visibilityThreshold != visibilityThreshold) {
            spring(spec.dampingRatio, spec.stiffness, visibilityThreshold)
        } else {
            spec
        },
    )
    val channel = remember { Channel<Float>(Channel.CONFLATED) }
    SideEffect { channel.trySend(targetValue) }
    LaunchedEffect(channel) {
        for (target in channel) {
            // Only the newest target counts; each animation goes on from where the last one is.
            val newTarget = channel.tryReceive().getOrNull() ?: target
            launch {
                if (newTarget != animatable.targetValue) {
                    animatable.animateTo(newTarget, resolvedSpec)
                }
            }
        }
    }
    return animatable.asState()
}

/**
 * [Crossfade] between false and true for content most instances never switch, such as a folder
 * tile that is rarely deleted: until [targetState] first turns true, `content(false)` is called
 * directly (no Transition). It then fades like [Crossfade], the shown content moving into the
 * fade with its state; true from the start, it is shown at once like the original.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun DeferredCrossfade(
    targetState: Boolean,
    modifier: Modifier = Modifier,
    animationSpec: FiniteAnimationSpec<Float> = androidx.compose.animation.core.tween(),
    label: String = "Crossfade",
    content: @Composable (Boolean) -> Unit,
) {
    val initialState = remember { targetState }
    val latestContent = rememberUpdatedState(content)
    val resting = remember { movableContentOf { latestContent.value(false) } }
    val holder = remember { arrayOfNulls<MutableTransitionState<Boolean>>(1) }
    val state = holder[0] ?: run {
        if (!targetState) {
            if (modifier === Modifier) resting() else androidx.compose.foundation.layout.Box(modifier) { resting() }
            return
        }
        MutableTransitionState(initialState).also { holder[0] = it }
    }
    val shown: @Composable (Boolean) -> Unit = { s -> if (s) latestContent.value(true) else resting() }
    if (LocalLightMode.current) {
        // As the Crossfade wrapper: no fade. Back in normal mode, it starts from what is shown.
        if (state.currentState != targetState || state.targetState != targetState) {
            holder[0] = MutableTransitionState(targetState)
        }
        androidx.compose.foundation.layout.Box(modifier) { shown(targetState) }
        return
    }
    state.targetState = targetState
    rememberTransition(state, label).ComposeCrossfade(modifier, animationSpec, content = shown)
}

@Composable
fun animateDpAsState(
    targetValue: Dp,
    animationSpec: AnimationSpec<Dp> = spring(visibilityThreshold = Dp.VisibilityThreshold),
    label: String = "DpAnimation",
    finishedListener: ((Dp) -> Unit)? = null,
): State<Dp> = composeAnimateDpAsState(
    targetValue = targetValue,
    animationSpec = if (LocalLightMode.current) snap() else animationSpec,
    label = label,
    finishedListener = finishedListener,
)
