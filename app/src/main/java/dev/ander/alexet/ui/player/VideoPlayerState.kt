package dev.ander.alexet.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.roundToInt

enum class SeekSide { LEFT, RIGHT }
enum class VerticalGestureType { BRIGHTNESS, VOLUME }

@Stable
class VideoPlayerState {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var isEnded by mutableStateOf(false)
    var playbackSpeed by mutableFloatStateOf(1f)
    var contentScale by mutableStateOf(VideoContentScale.FIT)
    var controlsLocked by mutableStateOf(false)
    var showRemainingTime by mutableStateOf(false)

    // Gestures feedback
    var activeDoubleTap by mutableStateOf<DoubleTapFeedback?>(null)
    var activeVerticalGesture by mutableStateOf<VerticalGestureFeedback?>(null)
    var isLongPressSpeedActive by mutableStateOf(false)
    var windowBrightnessOverridden by mutableStateOf(false)
    /** Brightness being dragged, between the (fewer) writes to the window. Not observed. */
    var gestureBrightness = -1f

    val positionFormatted: String
        get() = formatVideoTime(positionMs)

    val durationFormatted: String
        get() = formatVideoTime(durationMs)

    val remainingFormatted: String
        get() = "-${formatVideoTime((durationMs - positionMs).coerceAtLeast(0L))}"
}

data class DoubleTapFeedback(
    val side: SeekSide,
    val seconds: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

data class VerticalGestureFeedback(
    val type: VerticalGestureType,
    val percentage: Int,
)

fun formatVideoTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

/** The user's choices (speed, picture size, lock, time format) survive a process restart. */
private val VideoPlayerStateSaver = androidx.compose.runtime.saveable.listSaver<VideoPlayerState, Any>(
    save = { listOf(it.playbackSpeed, it.contentScale.name, it.controlsLocked, it.showRemainingTime) },
    restore = { saved ->
        VideoPlayerState().apply {
            playbackSpeed = saved[0] as Float
            contentScale = runCatching { VideoContentScale.valueOf(saved[1] as String) }.getOrDefault(VideoContentScale.FIT)
            controlsLocked = saved[2] as Boolean
            showRemainingTime = saved[3] as Boolean
        }
    },
)

@Composable
fun rememberVideoPlayerState(key: Any? = null): VideoPlayerState {
    return androidx.compose.runtime.saveable.rememberSaveable(key, saver = VideoPlayerStateSaver) { VideoPlayerState() }
}
