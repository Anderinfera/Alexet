package dev.ander.alexet.ui.components

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailFailuresTest {

    @After
    fun clear() = ThumbnailFailures.clearForTests()

    @Test
    fun aFailedThumbnailIsNotTriedAgainForAWhile() {
        val key = "thumb-7-1000-g0-320-4096"
        assertFalse(ThumbnailFailures.isFailed(key, now = 0L))
        ThumbnailFailures.markFailed(key, now = 1_000L)
        assertTrue(ThumbnailFailures.isFailed(key, now = 2_000L))
        assertTrue(ThumbnailFailures.isFailed(key, now = 1_000L + ThumbnailFailures.TTL_MS))
        // Long enough: tried again.
        assertFalse(ThumbnailFailures.isFailed(key, now = 1_001L + ThumbnailFailures.TTL_MS))
        assertFalse(ThumbnailFailures.isFailed(key, now = 1_002L + ThumbnailFailures.TTL_MS))
    }

    @Test
    fun aChangedFileIsTriedAgain() {
        ThumbnailFailures.markFailed("thumb-7-1000-g0-320-4096", now = 0L)
        // Another date or size: another key.
        assertFalse(ThumbnailFailures.isFailed("thumb-7-2000-g0-320-4096", now = 10L))
        assertFalse(ThumbnailFailures.isFailed("thumb-7-1000-g0-320-5000", now = 10L))
    }
}
