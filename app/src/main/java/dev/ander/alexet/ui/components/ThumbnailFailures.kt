package dev.ander.alexet.ui.components

/**
 * Thumbnails that could not be made (a broken file): its tile shows the broken-file icon at once
 * instead of trying again (up to a hundred frame reads) every time it comes back on screen, each
 * try holding one of the few slots for video frames. A changed file (another key: date, size) is
 * tried again, and so is any after [TTL_MS].
 */
internal object ThumbnailFailures {
    const val TTL_MS = 10 * 60_000L
    private const val MAX_ENTRIES = 512

    private val failedAt = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_ENTRIES
    }

    fun isFailed(key: String, now: Long = android.os.SystemClock.elapsedRealtime()): Boolean = synchronized(failedAt) {
        val at = failedAt[key] ?: return false
        if (now - at > TTL_MS) {
            failedAt.remove(key)
            false
        } else {
            true
        }
    }

    fun markFailed(key: String, now: Long = android.os.SystemClock.elapsedRealtime()) {
        synchronized(failedAt) { failedAt[key] = now }
    }

    internal fun clearForTests() = synchronized(failedAt) { failedAt.clear() }
}
