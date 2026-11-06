package dev.ander.alexet.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLogTest {

    private var now = 1_000_000_000L

    @Before
    fun setUp() {
        AppLog.nowMs = { now }
        AppLog.enabled = true
        AppLog.clear()
    }

    @After
    fun tearDown() {
        AppLog.enabled = false
        AppLog.nowMs = { System.currentTimeMillis() }
    }

    @Test
    fun disabledLogBuildsAndKeepsNothing() {
        AppLog.enabled = false
        var built = false
        AppLog.event("T") {
            built = true
            "x"
        }
        AppLog.w("T", "warning")
        assertFalse(built)
        assertTrue(AppLog.snapshot().isEmpty())
    }

    @Test
    fun keepsOnlyTheLastFiveMinutes() {
        AppLog.event("T") { "old" }
        now += AppLog.WINDOW_MS - 1_000
        AppLog.event("T") { "recent" }
        now += 2_000
        assertEquals(listOf("recent"), AppLog.snapshot().map { it.message })
    }

    @Test
    fun capsTheNumberOfEntries() {
        repeat(AppLog.MAX_ENTRIES + 50) { i -> AppLog.event("T") { "e$i" } }
        val kept = AppLog.snapshot()
        assertEquals(AppLog.MAX_ENTRIES, kept.size)
        assertEquals("e50", kept.first().message)
    }

    @Test
    fun turningOffClearsTheBuffer() {
        AppLog.event("T") { "x" }
        AppLog.enabled = false
        AppLog.enabled = true
        assertTrue(AppLog.snapshot().isEmpty())
    }

    @Test
    fun clipboardKeepsTheNewestBlocks() {
        val blocks = (1..10).map { AppLog.Block("10-03 12:00:%02d.000".format(it), "line$it".padEnd(20, '.')) }
        val header = "HEADER\n"
        // Room for the header and exactly three blocks (20 chars + newline each).
        val (text, firstKey) = AppLog.newestWithin(header, blocks, header.length + 3 * 21)
        assertTrue(text.startsWith(header))
        assertEquals(listOf("line8", "line9", "line10"), text.removePrefix(header).lines().filter { it.isNotBlank() }.map { it.trimEnd('.') })
        assertEquals("10-03 12:00:08.000", firstKey)

        val (all, first) = AppLog.newestWithin(header, blocks, Int.MAX_VALUE)
        assertEquals(10, all.removePrefix(header).lines().count { it.isNotBlank() })
        assertEquals("10-03 12:00:01.000", first)

        val (none, noKey) = AppLog.newestWithin(header, blocks, header.length + 5)
        assertEquals(header, none)
        assertNull(noKey)
    }

    @Test
    fun logcatIsMergedByTimeWithoutDuplicates() {
        AppLog.w("Repo", "duplicate message")
        val entries = AppLog.snapshot()
        val appTime = AppLog.formatTime(entries.single().timeMs)
        val logcat = listOf(
            "--------- beginning of main",
            "$appTime  1234  1250 W Repo    : duplicate message",
            "01-01 00:00:00.001  1234  1250 E MediaMetadataRetrieverJNI: getFrameAtTime: videoFrame is a NULL pointer",
            "12-31 23:59:59.999  1234  1251 I Other   : later line",
        )
        val merged = AppLog.merge(AppLog.appBlocks(entries), AppLog.logcatBlocks(logcat, entries))
        assertEquals(3, merged.size)
        assertTrue(merged[0].text.contains("MediaMetadataRetrieverJNI"))
        assertTrue(merged.any { it.text.contains(" APP W/Repo: duplicate message") })
        assertTrue(merged.last().text.contains("later line"))
    }

    @Test
    fun describesChangedFields() {
        data class State(val folder: Long, val viewer: Long?, val query: String)
        val changes = AppLog.describeChanges(State(1, null, ""), State(2, null, "kot"))
        assertEquals(listOf("folder: 1 -> 2", "query:  -> kot"), changes.sorted())
    }

    @Test
    fun redactedFieldsHideTheirContent() {
        data class State(val folder: Long, val query: String)
        val changes = AppLog.describeChanges(State(1, ""), State(1, "Secret123"), redacted = setOf("query"))
        assertEquals(listOf("query: changed (hidden)"), changes)
    }
}
