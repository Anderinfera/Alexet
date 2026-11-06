package dev.ander.alexet.ui.components

import dev.ander.alexet.data.VideoDurationFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormattingTest {

    @Test
    fun testFormatBytes() {
        Locale.setDefault(Locale.US)
        assertEquals("500 B", formatBytes(500L))
        assertEquals("1.0 KB", formatBytes(1024L))
        assertEquals("1.5 KB", formatBytes(1536L))
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("2.5 MB", formatBytes((2.5 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", formatBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun testFormatDuration_minutesFormat() {
        Locale.setDefault(Locale.US)
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:05", formatDuration(5000L))
        assertEquals("0:59", formatDuration(59000L))
        assertEquals("1:00", formatDuration(60000L))
        assertEquals("1:30", formatDuration(90000L))
        assertEquals("10:05", formatDuration(605000L))

        // 49h 10min 50sec in default / MINUTES format -> 2950:50
        val duration49h10m50s = ((49L * 3600L) + (10L * 60L) + 50L) * 1000L
        assertEquals("2950:50", formatDuration(duration49h10m50s))
        assertEquals("2950:50", formatDuration(duration49h10m50s, VideoDurationFormat.MINUTES))
    }

    @Test
    fun testFormatDuration_hoursMinutesSecondsFormat() {
        Locale.setDefault(Locale.US)
        // Under 1 hour
        assertEquals("0:00", formatDuration(0L, VideoDurationFormat.HOURS_MINUTES_SECONDS))
        assertEquals("0:05", formatDuration(5000L, VideoDurationFormat.HOURS_MINUTES_SECONDS))
        assertEquals("0:59", formatDuration(59000L, VideoDurationFormat.HOURS_MINUTES_SECONDS))
        assertEquals("1:00", formatDuration(60000L, VideoDurationFormat.HOURS_MINUTES_SECONDS))
        assertEquals("10:05", formatDuration(605000L, VideoDurationFormat.HOURS_MINUTES_SECONDS))

        // 1 hour and above
        val duration1h0m5s = (3600L + 5L) * 1000L
        assertEquals("1:00:05", formatDuration(duration1h0m5s, VideoDurationFormat.HOURS_MINUTES_SECONDS))

        val duration2h15m30s = ((2L * 3600L) + (15L * 60L) + 30L) * 1000L
        assertEquals("2:15:30", formatDuration(duration2h15m30s, VideoDurationFormat.HOURS_MINUTES_SECONDS))

        // User example: 49h 10min 50sec -> 49:10:50
        val duration49h10m50s = ((49L * 3600L) + (10L * 60L) + 50L) * 1000L
        assertEquals("49:10:50", formatDuration(duration49h10m50s, VideoDurationFormat.HOURS_MINUTES_SECONDS))

        // Boundary / negative ms protection
        assertEquals("0:00", formatDuration(-5000L, VideoDurationFormat.HOURS_MINUTES_SECONDS))
    }
}
