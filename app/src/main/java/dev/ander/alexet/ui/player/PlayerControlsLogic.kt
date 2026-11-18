package dev.ander.alexet.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import dev.ander.alexet.integration.PlayerShortcut
import kotlin.math.abs

/** How far a sideways swipe seeks: 50 ms per pixel (Next Player's default sensitivity). */
const val SWIPE_SEEK_MS_PER_PX = 50f

/**
 * The seek of a sideways swipe that moved [dxPx] since it was recognised at [startMs]; kept
 * inside the video ([durationMs]).
 */
fun swipeSeekAmount(dxPx: Float, startMs: Long, durationMs: Long, msPerPx: Float = SWIPE_SEEK_MS_PER_PX): Long {
    val start = startMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
    return (dxPx * msPerPx).toLong().coerceIn(-start, durationMs.coerceAtLeast(0L) - start)
}

/** "+00:15\n[12:34]": the jump and where it lands. */
fun swipeSeekLabel(amountMs: Long, targetMs: Long): String =
    "${if (amountMs < 0L) "-" else "+"}${formatVideoTime(abs(amountMs))}\n[${formatVideoTime(targetMs)}]"

data class SwipeSeekFeedback(val amountMs: Long, val targetMs: Long)

/** The shortcuts the "Shortcuts" dialog lists, in its order. */
val DIALOG_SHORTCUTS = listOf(
    PlayerShortcut.PLAYBACK_SPEED,
    PlayerShortcut.SCREEN_ROTATION,
    PlayerShortcut.VIDEO_CONTENT_SCALE,
    PlayerShortcut.AUDIO_TRACK,
    PlayerShortcut.SUBTITLE_TRACK,
    PlayerShortcut.LOOP,
    PlayerShortcut.PLAY_IN_BACKGROUND,
    PlayerShortcut.LOCK_CONTROLS,
)

/** Turned on: added at the end (shown last); turned off: removed. The others keep their order. */
fun toggleShortcut(shortcuts: Set<PlayerShortcut>, shortcut: PlayerShortcut, on: Boolean): Set<PlayerShortcut> {
    val result = LinkedHashSet(shortcuts)
    if (on) result.add(shortcut) else result.remove(shortcut)
    return result
}

/** 1-based place of [shortcut] among the turned-on ones of [listed], or null when it is off. */
fun shortcutNumber(shortcuts: Set<PlayerShortcut>, shortcut: PlayerShortcut, listed: List<PlayerShortcut> = DIALOG_SHORTCUTS): Int? {
    val index = shortcuts.filter { it in listed }.indexOf(shortcut)
    return if (index >= 0) index + 1 else null
}

/** A 0..1 screen position as an alignment bias (-1..1). */
fun positionToBias(position: Float): Float = (position.coerceIn(0f, 1f) * 2f) - 1f

/** Next Player's rotate button: landscape turns to portrait, anything else to landscape. */
fun nextRequestedOrientation(isLandscape: Boolean): Int =
    if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

/**
 * The window's orientation before the player first turned it; given back when the viewer closes,
 * so the next video keeps the turn but the gallery is as it was.
 */
class ScreenOrientationKeeper {
    private var original: Int? = null

    fun rotate(activity: Activity) {
        if (original == null) original = activity.requestedOrientation
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        activity.requestedOrientation = nextRequestedOrientation(landscape)
    }

    fun restore(activity: Activity?) {
        val saved = original ?: return
        original = null
        activity?.requestedOrientation = saved
    }
}
