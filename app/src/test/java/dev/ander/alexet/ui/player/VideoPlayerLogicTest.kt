package dev.ander.alexet.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerLogicTest {

    @Test
    fun testFormatVideoTime_zero() {
        assertEquals("00:00", formatVideoTime(0L))
    }

    @Test
    fun testFormatVideoTime_secondsOnly() {
        assertEquals("00:09", formatVideoTime(9_000L))
        assertEquals("00:59", formatVideoTime(59_000L))
    }

    @Test
    fun testFormatVideoTime_minutesAndSeconds() {
        assertEquals("01:00", formatVideoTime(60_000L))
        assertEquals("03:45", formatVideoTime(225_000L))
        assertEquals("59:59", formatVideoTime(3_599_000L))
    }

    @Test
    fun testFormatVideoTime_hoursMinutesSeconds() {
        assertEquals("1:00:00", formatVideoTime(3_600_000L))
        assertEquals("1:02:03", formatVideoTime(3_723_000L))
        assertEquals("12:34:56", formatVideoTime((12 * 3600 + 34 * 60 + 56) * 1000L))
    }

    @Test
    fun testFormatVideoTime_negativeOrInvalid() {
        assertEquals("00:00", formatVideoTime(-1000L))
    }

    @Test
    fun testVideoContentScale_cycleOrder() {
        assertEquals(VideoContentScale.ZOOM, VideoContentScale.FIT.next())
        assertEquals(VideoContentScale.STRETCH, VideoContentScale.ZOOM.next())
        assertEquals(VideoContentScale.ASPECT_16_9, VideoContentScale.STRETCH.next())
        assertEquals(VideoContentScale.ASPECT_4_3, VideoContentScale.ASPECT_16_9.next())
        assertEquals(VideoContentScale.ORIGINAL, VideoContentScale.ASPECT_4_3.next())
        assertEquals(VideoContentScale.FIT, VideoContentScale.ORIGINAL.next())
    }

    @Test
    fun testVideoPlayerState_initialState() {
        val state = VideoPlayerState()
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertFalse(state.isPlaying)
        assertFalse(state.isBuffering)
        assertFalse(state.isEnded)
        assertEquals(1.0f, state.playbackSpeed, 0.001f)
        assertEquals(VideoContentScale.FIT, state.contentScale)
        assertFalse(state.controlsLocked)
        assertFalse(state.showRemainingTime)
        assertNull(state.activeDoubleTap)
        assertNull(state.activeVerticalGesture)
        assertFalse(state.isLongPressSpeedActive)
    }

    @Test
    fun testVideoPlayerState_formattedProperties() {
        val state = VideoPlayerState().apply {
            positionMs = 65_000L
            durationMs = 200_000L
        }
        assertEquals("01:05", state.positionFormatted)
        assertEquals("03:20", state.durationFormatted)
        assertEquals("-02:15", state.remainingFormatted)
    }

    @Test
    fun testDoubleTapFeedback_accumulation() {
        val feedback1 = DoubleTapFeedback(side = SeekSide.RIGHT, seconds = 10)
        assertEquals(SeekSide.RIGHT, feedback1.side)
        assertEquals(10, feedback1.seconds)

        val feedback2 = feedback1.copy(seconds = feedback1.seconds + 10)
        assertEquals(20, feedback2.seconds)
    }

    @Test
    fun volumeAccumulatorChangesOnSmallSwipes() {
        // 15-step stream, 2% of screen each event should eventually step
        var accum = 0f
        var volume = 7
        val max = 15
        repeat(5) {
            val (nextAccum, nextVol) = accumulateVolumeDelta(accum, 0.02f, volume, max)
            accum = nextAccum
            volume = nextVol
        }
        assertTrue(volume > 7)
        assertTrue(volume <= max)
    }

    @Test
    fun volumeAccumulatorIgnoresTinyNoiseUntilFullStep() {
        val (accum, volume) = accumulateVolumeDelta(0f, 0.01f, 5, 15)
        assertEquals(5, volume)
        assertTrue(accum > 0f)
        val (accum2, volume2) = accumulateVolumeDelta(accum, 0.06f, 5, 15)
        assertTrue(volume2 >= 5)
        assertTrue(accum2 < 1f)
    }

    @Test
    fun volumeAccumulatorDownward() {
        val (accum, volume) = accumulateVolumeDelta(0f, -0.2f, 5, 15)
        assertEquals(2, volume) // -0.2 * 15 = -3
        assertEquals(0f, accum, 0.001f)
    }
}
