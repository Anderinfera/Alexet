package dev.ander.alexet.ui.player

import android.content.pm.ActivityInfo
import dev.ander.alexet.integration.PlayerShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerControlsLogicTest {

    @Test
    fun swipeSeekAmount_is50msPerPixel() {
        assertEquals(15_000L, swipeSeekAmount(300f, startMs = 60_000L, durationMs = 600_000L))
        assertEquals(-5_000L, swipeSeekAmount(-100f, startMs = 60_000L, durationMs = 600_000L))
    }

    @Test
    fun swipeSeekAmount_staysInsideTheVideo() {
        assertEquals(-10_000L, swipeSeekAmount(-1_000f, startMs = 10_000L, durationMs = 600_000L))
        assertEquals(5_000L, swipeSeekAmount(1_000f, startMs = 595_000L, durationMs = 600_000L))
    }

    @Test
    fun swipeSeekLabel_showsSignAmountAndTarget() {
        assertEquals("+00:15\n[12:34]", swipeSeekLabel(15_000L, 754_000L))
        assertEquals("-01:05\n[00:10]", swipeSeekLabel(-65_000L, 10_000L))
        assertEquals("+00:00\n[00:00]", swipeSeekLabel(0L, 0L))
    }

    @Test
    fun toggleShortcut_appendsAtTheEndAndRemoves() {
        val start = linkedSetOf(PlayerShortcut.LOOP, PlayerShortcut.PICTURE_IN_PICTURE, PlayerShortcut.PLAYBACK_SPEED)

        val added = toggleShortcut(start, PlayerShortcut.SCREEN_ROTATION, on = true)
        assertEquals(
            listOf(PlayerShortcut.LOOP, PlayerShortcut.PICTURE_IN_PICTURE, PlayerShortcut.PLAYBACK_SPEED, PlayerShortcut.SCREEN_ROTATION),
            added.toList(),
        )
        val removed = toggleShortcut(added, PlayerShortcut.LOOP, on = false)
        assertEquals(
            listOf(PlayerShortcut.PICTURE_IN_PICTURE, PlayerShortcut.PLAYBACK_SPEED, PlayerShortcut.SCREEN_ROTATION),
            removed.toList(),
        )
        // Already on: stays where it was.
        assertEquals(removed.toList(), toggleShortcut(removed, PlayerShortcut.PLAYBACK_SPEED, on = true).toList())
    }

    @Test
    fun shortcutNumber_countsOnlyListedShortcutsInSetOrder() {
        val set = linkedSetOf(PlayerShortcut.PICTURE_IN_PICTURE, PlayerShortcut.LOOP, PlayerShortcut.PLAYBACK_SPEED)

        assertEquals(1, shortcutNumber(set, PlayerShortcut.LOOP))
        assertEquals(2, shortcutNumber(set, PlayerShortcut.PLAYBACK_SPEED))
        assertNull(shortcutNumber(set, PlayerShortcut.SCREEN_ROTATION))
    }

    @Test
    fun positionToBias_mapsCentreAndEdges() {
        assertEquals(0f, positionToBias(0.5f), 0.0001f)
        assertEquals(-0.9f, positionToBias(0.05f), 0.0001f)
        assertEquals(0.9f, positionToBias(0.95f), 0.0001f)
    }

    @Test
    fun nextRequestedOrientation_flipsBetweenPortraitAndLandscape() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, nextRequestedOrientation(isLandscape = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, nextRequestedOrientation(isLandscape = false))
    }
}
