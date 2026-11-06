package dev.ander.alexet.ui.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineZoomTest {

    private fun trackPx(scale: Float, durationMs: Long) = TimelineZoom.PX_PER_SECOND * scale * durationMs / 1000f

    @Test
    fun shortVideosKeepTheFullZoom() {
        val minute = 60_000L
        assertEquals(TimelineZoom.MAX, TimelineZoom.maxFor(minute), 0f)
        assertEquals(TimelineZoom.MIN..TimelineZoom.MAX, TimelineZoom.range(minute))
        assertEquals(2.5f, TimelineZoom.effective(2.5f, minute), 0f)
    }

    @Test
    fun theTrackNeverOutgrowsWhatComposeCanLayOut() {
        // Compose can't lay out anything wider than about 262 000 px.
        for (minutes in listOf(10L, 22L, 30L, 66L, 90L, 240L, 600L)) {
            val duration = minutes * 60_000L
            val widest = trackPx(TimelineZoom.effective(TimelineZoom.MAX, duration), duration)
            assertTrue("$minutes min: $widest px", widest <= TimelineZoom.MAX_TRACK_PX + 1f)
            val narrowest = trackPx(TimelineZoom.effective(0f, duration), duration)
            assertTrue("$minutes min: $narrowest px", narrowest <= TimelineZoom.MAX_TRACK_PX + 1f)
        }
    }

    @Test
    fun aVeryLongTimelineZoomsOutBelowTheUsualMinimum() {
        val tenHours = 10 * 3_600_000L
        val range = TimelineZoom.range(tenHours)
        assertTrue(range.endInclusive < TimelineZoom.MIN)
        assertEquals(range.endInclusive, range.start, 0f)
    }
}
