package dev.ander.alexet.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import dev.ander.alexet.integration.AnderLineConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VideoPlayerGestureDetector(
    playerState: VideoPlayerState,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onSetPlaybackSpeedTemporary: (Float?) -> Unit,
    onTogglePlayPause: () -> Unit = {},
    config: AnderLineConfig = AnderLineConfig(),
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val viewConfiguration = LocalViewConfiguration.current

    val toggleControlsState = rememberUpdatedState(onToggleControls)
    val seekRelativeState = rememberUpdatedState(onSeekRelative)
    val setSpeedTempState = rememberUpdatedState(onSetPlaybackSpeedTemporary)
    val togglePlayPauseState = rememberUpdatedState(onTogglePlayPause)
    val configState = rememberUpdatedState(config)

    val doubleTapFeedbackJob = remember { mutableMapOf<SeekSide, Job>() }
    val lastTap = remember { longArrayOf(0L) }
    /**
     * Third of the screen of the last tap (0 left, 1 middle, 2 right): a tap in another third is
     * a new single tap, not a double tap.
     */
    val lastTapZone = remember { intArrayOf(-1) }
    /** Hides the volume / brightness indicator after a swipe; a new swipe cancels it. */
    val verticalHideJob = remember { arrayOfNulls<Job>(1) }
    val pendingTapJob = remember { arrayOfNulls<Job>(1) }
    val volumeAccum = remember { floatArrayOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(playerState.controlsLocked, config) {
                if (playerState.controlsLocked) {
                    detectTapGestures(onTap = { toggleControlsState.value() })
                    return@pointerInput
                }

                val touchSlop = viewConfiguration.touchSlop
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val downPos = down.position
                    val downTime = System.currentTimeMillis()
                    val isLongPress = AtomicBoolean(false)
                    var verticalKind: VerticalGestureType? = null
                    var consumedAsSwipe = false
                    var consumedAsSeek = false
                    var moved = false
                    val cfg = configState.value
                    val seekSeconds = cfg.seekIncrement.coerceIn(1, 60)

                    val longPressJob = if (cfg.useLongPressControls) {
                        scope.launch {
                            delay(longPressTimeout)
                            if (!consumedAsSwipe && !consumedAsSeek) {
                                isLongPress.set(true)
                                playerState.isLongPressSpeedActive = true
                                setSpeedTempState.value(cfg.longPressControlsSpeed.coerceIn(1.1f, 8f))
                            }
                        }
                    } else {
                        null
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                break
                            }
                            val delta = change.position - downPos
                            if (!isLongPress.get() && (delta.getDistance() > touchSlop || change.isConsumed)) {
                                // A drag (e.g. the pager swiping to the next item) is not a hold:
                                // it must not switch to the fast speed halfway.
                                moved = true
                                longPressJob?.cancel()
                            }
                            if (!consumedAsSwipe && !isLongPress.get()) {
                                if (abs(delta.y) > touchSlop && abs(delta.y) > abs(delta.x) * 1.2f) {
                                    val leftHalf = downPos.x < size.width * 0.5f
                                    verticalKind = when {
                                        leftHalf && cfg.enableBrightnessSwipeGesture -> VerticalGestureType.BRIGHTNESS
                                        !leftHalf && cfg.enableVolumeSwipeGesture -> VerticalGestureType.VOLUME
                                        leftHalf && cfg.enableVolumeSwipeGesture && !cfg.enableBrightnessSwipeGesture -> VerticalGestureType.VOLUME
                                        else -> null
                                    }
                                    if (verticalKind != null) {
                                        consumedAsSwipe = true
                                        longPressJob?.cancel()
                                        change.consume()
                                    }
                                }
                            }
                            if (consumedAsSwipe && verticalKind != null) {
                                val dy = change.positionChange().y
                                val fraction = (-dy / size.height.toFloat()).coerceIn(-0.15f, 0.15f)
                                verticalHideJob[0]?.cancel()
                                applyVerticalGesture(context, playerState, verticalKind, fraction, volumeAccum)
                                change.consume()
                            }
                        }
                    } finally {
                        longPressJob?.cancel()
                        // Also when the handler restarts mid-hold: the temporary speed must not stick.
                        if (isLongPress.get()) {
                            playerState.isLongPressSpeedActive = false
                            setSpeedTempState.value(null)
                        }
                    }

                    val upTime = System.currentTimeMillis()
                    val duration = upTime - downTime
                    if (isLongPress.get()) {
                        // Speed already restored above.
                    } else if (consumedAsSwipe) {
                        volumeAccum[0] = 0f
                        playerState.gestureBrightness = -1f
                        verticalHideJob[0]?.cancel()
                        verticalHideJob[0] = scope.launch {
                            delay(400L)
                            playerState.activeVerticalGesture = null
                        }
                    } else if (duration < longPressTimeout && !moved) {
                        // Three equal thirds: back, play / pause, forward. (Two halves made a
                        // double tap in the middle seek instead of pausing.)
                        val zone = when {
                            downPos.x < size.width / 3f -> 0
                            downPos.x > size.width * 2f / 3f -> 2
                            else -> 1
                        }
                        val side = if (zone == 0) SeekSide.LEFT else SeekSide.RIGHT
                        // One window for both: the single tap fires only once a second tap can no
                        // longer come, so a double tap never also toggles the controls.
                        val isDouble = upTime - lastTap[0] < DOUBLE_TAP_WINDOW_MS && lastTapZone[0] == zone
                        lastTap[0] = upTime
                        lastTapZone[0] = zone
                        if (isDouble && zone == 1) {
                            pendingTapJob[0]?.cancel()
                            pendingTapJob[0] = null
                            // A third tap right after is a new tap, not another double.
                            lastTap[0] = 0L
                            togglePlayPauseState.value()
                        } else if (isDouble && cfg.enableDoubleTapSeek) {
                            pendingTapJob[0]?.cancel()
                            pendingTapJob[0] = null
                            val deltaSec = if (side == SeekSide.LEFT) -seekSeconds else seekSeconds
                            seekRelativeState.value(deltaSec * 1000L)
                            val currentFeedback = playerState.activeDoubleTap
                            val nextSeconds = if (currentFeedback != null && currentFeedback.side == side) {
                                currentFeedback.seconds + abs(deltaSec)
                            } else {
                                abs(deltaSec)
                            }
                            playerState.activeDoubleTap = DoubleTapFeedback(side = side, seconds = nextSeconds)
                            doubleTapFeedbackJob[side]?.cancel()
                            doubleTapFeedbackJob[side] = scope.launch {
                                delay(900L)
                                if (playerState.activeDoubleTap?.side == side) {
                                    playerState.activeDoubleTap = null
                                }
                            }
                        } else {
                            pendingTapJob[0]?.cancel()
                            pendingTapJob[0] = scope.launch {
                                delay(DOUBLE_TAP_WINDOW_MS)
                                toggleControlsState.value()
                            }
                        }
                    }
                }
            },
    )
}

/**
 * Accumulates fractional volume steps so small swipes still change STREAM_MUSIC
 * on 15-step streams. Returns (newAccumulator, newVolume).
 */
fun accumulateVolumeDelta(
    accumulator: Float,
    deltaFraction: Float,
    currentVolume: Int,
    maxVolume: Int,
): Pair<Float, Int> {
    val max = maxVolume.coerceAtLeast(1)
    val nextAccum = accumulator + deltaFraction * max
    val steps = nextAccum.toInt() // truncates toward zero
    val remaining = nextAccum - steps
    val newVolume = (currentVolume + steps).coerceIn(0, max)
    return remaining to newVolume
}

private fun applyVerticalGesture(
    context: Context,
    playerState: VideoPlayerState,
    type: VerticalGestureType,
    deltaFraction: Float,
    volumeAccum: FloatArray,
) {
    when (type) {
        VerticalGestureType.VOLUME -> {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            // Fixed for the stream: asked once (every finger move made these system calls).
            val max = streamMaxVolume.takeIf { it > 0 }
                ?: am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1).also { streamMaxVolume = it }
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val (remaining, next) = accumulateVolumeDelta(volumeAccum[0], deltaFraction, current, max)
            volumeAccum[0] = remaining
            if (next != current) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
            }
            playerState.activeVerticalGesture = VerticalGestureFeedback(
                type = VerticalGestureType.VOLUME,
                percentage = ((next / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100),
            )
        }
        VerticalGestureType.BRIGHTNESS -> {
            val activity = context.findActivity() ?: return
            val window = activity.window
            val lp = window.attributes
            playerState.windowBrightnessOverridden = true
            val shown = if (lp.screenBrightness in 0f..1f) lp.screenBrightness else 0.5f
            // Small moves add up here; the window (a system call) is updated once per whole percent.
            val current = playerState.gestureBrightness.takeIf { it in 0f..1f } ?: shown
            val next = (current + deltaFraction).coerceIn(0.01f, 1f)
            playerState.gestureBrightness = next
            if ((next * 100f).roundToInt() != (shown * 100f).roundToInt()) {
                lp.screenBrightness = next
                window.attributes = lp
            }
            playerState.activeVerticalGesture = VerticalGestureFeedback(
                type = VerticalGestureType.BRIGHTNESS,
                percentage = (next * 100f).roundToInt().coerceIn(0, 100),
            )
        }
    }
}

@Volatile private var streamMaxVolume = 0

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val DOUBLE_TAP_WINDOW_MS = 300L
