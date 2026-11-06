package dev.ander.alexet.data

import dev.ander.alexet.data.WaitingFileRule.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class WaitingFileRuleTest {

    private fun decide(length: Long = 1_000L, ageMs: Long = 60_000L, complete: Boolean? = null, live: Boolean = false) =
        WaitingFileRule.decide(length, ageMs, complete, live)

    @Test
    fun `a complete file is ready even right after it was written`() {
        assertEquals(Decision.READY, decide(ageMs = 0L, complete = true))
    }

    @Test
    fun `a cut-off file is never ready, however old`() {
        assertEquals(Decision.WRITING, decide(ageMs = 24 * 60 * 60_000L, complete = false))
    }

    @Test
    fun `an unknown format waits until no write for the quiet time`() {
        assertEquals(Decision.WRITING, decide(ageMs = WaitingFileRule.QUIET_MS - 1, complete = null))
        assertEquals(Decision.READY, decide(ageMs = WaitingFileRule.QUIET_MS, complete = null))
    }

    @Test
    fun `an old modification date (set by the downloader) counts as quiet`() {
        // yt-dlp style: the date is the upload date, years back.
        assertEquals(Decision.READY, decide(ageMs = 3L * 365 * 24 * 60 * 60_000L, complete = null))
    }

    @Test
    fun `a live download or own write waits, even when it looks complete`() {
        assertEquals(Decision.WRITING, decide(complete = true, live = true))
    }

    @Test
    fun `a missing or empty file is skipped`() {
        assertEquals(Decision.SKIP, decide(length = 0L, complete = true))
        assertEquals(Decision.SKIP, decide(length = 0L, live = true))
    }
}
