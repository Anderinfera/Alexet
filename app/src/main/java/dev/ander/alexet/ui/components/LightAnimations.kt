package dev.ander.alexet.ui.components

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
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
