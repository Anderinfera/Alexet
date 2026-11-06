package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTrackerTest {

    private class Clock {
        var now = 0L
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun tracker(
        clock: Clock,
        sizes: MutableMap<String, Long>,
        completed: MutableList<String> = ArrayList(),
    ): DownloadTracker {
        val t = DownloadTracker(
            nowMs = { clock.now },
            snapshot = { path -> sizes[path]?.let { it to 0L } },
        )
        t.onCompleted = { _, display -> completed += display }
        return t
    }

    @Test
    fun createModifyCloseSettleCompletes() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/a.mp4" to 10L)
        val done = ArrayList<String>()
        val t = tracker(clock, sizes, done)

        assertTrue(t.onCreated("/sdcard/Download/a.mp4"))
        assertTrue(t.isActive("/sdcard/Download/a.mp4"))
        clock.advance(200)
        sizes["/sdcard/Download/a.mp4"] = 50L
        t.onModified("/sdcard/Download/a.mp4")
        t.onCloseOrMovedTo("/sdcard/Download/a.mp4")
        assertEquals(DownloadPhase.SETTLING, t.get("/sdcard/Download/a.mp4")?.phase)

        clock.advance(DownloadTracker.SETTLE_MS)
        val completed = t.tick()
        assertEquals(listOf("/sdcard/Download/a.mp4"), completed)
        assertFalse(t.isActive("/sdcard/Download/a.mp4"))
        assertEquals(listOf("/sdcard/Download/a.mp4"), done)
    }

    @Test
    fun settleThenGrowingReturnsToActive() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/b.mp4" to 10L)
        val t = tracker(clock, sizes)
        t.onCreated("/sdcard/Download/b.mp4")
        t.onCloseOrMovedTo("/sdcard/Download/b.mp4")
        clock.advance(500)
        sizes["/sdcard/Download/b.mp4"] = 80L
        t.tick()
        assertEquals(DownloadPhase.ACTIVE, t.get("/sdcard/Download/b.mp4")?.phase)
        assertTrue(t.isActive("/sdcard/Download/b.mp4"))
    }

    @Test
    fun idleStableActiveBecomesReady() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/c.jpg" to 100L)
        val t = tracker(clock, sizes)
        t.onCreated("/sdcard/Download/c.jpg")
        clock.advance(DownloadTracker.IDLE_COMPLETE_MS)
        val completed = t.tick()
        assertEquals(listOf("/sdcard/Download/c.jpg"), completed)
        assertFalse(t.isActive("/sdcard/Download/c.jpg"))
    }

    @Test
    fun movedInFileIsNotADownload() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Pictures/Trip/d.jpg" to 100L)
        val t = tracker(clock, sizes)
        // A complete file renamed into the folder (in-app move, file manager) stays accessible
        assertFalse(t.onMovedTo("/sdcard/Pictures/Trip/d.jpg"))
        assertFalse(t.isActive("/sdcard/Pictures/Trip/d.jpg"))
    }

    @Test
    fun browserTempRenameIsReadyImmediately() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/e.mp4.crdownload" to 10L)
        val t = tracker(clock, sizes)
        t.onCreated("/sdcard/Download/e.mp4.crdownload")
        assertTrue(t.isActive("/sdcard/Download/e.mp4"))
        // Rename temp -> final: MOVED_FROM(temp) then MOVED_TO(final)
        sizes.remove("/sdcard/Download/e.mp4.crdownload")
        sizes["/sdcard/Download/e.mp4"] = 10L
        t.onDeleted("/sdcard/Download/e.mp4.crdownload")
        t.onMovedTo("/sdcard/Download/e.mp4")
        assertFalse(t.isActive("/sdcard/Download/e.mp4"))
    }

    @Test
    fun movedToOverTrackedPathSettles() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/f.mp4" to 10L)
        val t = tracker(clock, sizes)
        t.onCreated("/sdcard/Download/f.mp4")
        assertTrue(t.onMovedTo("/sdcard/Download/f.mp4"))
        assertEquals(DownloadPhase.SETTLING, t.get("/sdcard/Download/f.mp4")?.phase)
    }

    @Test
    fun deleteRemovesImmediately() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/d.mp4" to 1L)
        val t = tracker(clock, sizes)
        t.onCreated("/sdcard/Download/d.mp4")
        t.onDeleted("/sdcard/Download/d.mp4")
        assertFalse(t.isActive("/sdcard/Download/d.mp4"))
        clock.advance(DownloadTracker.SETTLE_MS)
        assertTrue(t.tick().isEmpty())
    }

    @Test
    fun nonMediaNeverTracked() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/notes.txt" to 4L)
        val t = tracker(clock, sizes)
        assertFalse(t.onCreated("/sdcard/Download/notes.txt"))
        assertFalse(t.onModified("/sdcard/Download/notes.txt"))
        assertFalse(t.onCloseOrMovedTo("/sdcard/Download/apk.apk"))
        assertTrue(t.active.value.isEmpty())
    }

    @Test
    fun managerProgressSetsTotalBytes() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/e.mp4" to 20L)
        val t = tracker(clock, sizes)
        t.onManagerProgress("/sdcard/Download/e.mp4", bytes = 20L, totalBytes = 100L)
        val entry = t.get("/sdcard/Download/e.mp4")
        assertEquals(20L, entry?.bytes)
        assertEquals(100L, entry?.totalBytes)
        assertEquals(DownloadSource.DOWNLOAD_MANAGER, entry?.source)
    }

    @Test
    fun lookupMatchesTempSuffixAlias() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/clip.mp4.crdownload" to 40L)
        val t = tracker(clock, sizes)
        t.onCreated("/sdcard/Download/clip.mp4.crdownload")
        assertTrue(t.isActive("/sdcard/Download/clip.mp4"))
        assertTrue(t.isActive("/sdcard/Download/clip.mp4.crdownload"))
        assertEquals(40L, t.get("/sdcard/Download/clip.mp4")?.bytes)
    }

    @Test
    fun screenshotAndCameraPathsNeverTracked() {
        val clock = Clock()
        val sizes = mutableMapOf(
            "/storage/emulated/0/Pictures/Screenshots/Screenshot_20260928-102351.png" to 1024L,
            "/storage/emulated/0/DCIM/Camera/IMG_20260928_102351.jpg" to 2048L,
        )
        val t = tracker(clock, sizes)
        assertFalse(t.onCreated("/storage/emulated/0/Pictures/Screenshots/Screenshot_20260928-102351.png"))
        assertFalse(t.isActive("/storage/emulated/0/Pictures/Screenshots/Screenshot_20260928-102351.png"))
        assertFalse(t.onCreated("/storage/emulated/0/DCIM/Camera/IMG_20260928_102351.jpg"))
        assertFalse(t.isActive("/storage/emulated/0/DCIM/Camera/IMG_20260928_102351.jpg"))
        assertTrue(t.active.value.isEmpty())
    }

    @Test
    fun seededFinishedFileFreesAfterShortSettle() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/s.mp4" to 100L)
        val t = tracker(clock, sizes)
        assertTrue(t.onSeeded("/sdcard/Download/s.mp4"))
        assertEquals(DownloadPhase.SETTLING, t.get("/sdcard/Download/s.mp4")?.phase)
        clock.advance(DownloadTracker.SETTLE_MS)
        t.tick()
        assertFalse(t.isActive("/sdcard/Download/s.mp4"))
    }

    @Test
    fun seededGrowingFileBecomesActive() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/g.mp4" to 100L)
        val t = tracker(clock, sizes)
        t.onSeeded("/sdcard/Download/g.mp4")
        clock.advance(1_000)
        sizes["/sdcard/Download/g.mp4"] = 400L
        t.tick()
        assertEquals(DownloadPhase.ACTIVE, t.get("/sdcard/Download/g.mp4")?.phase)
    }

    @Test
    fun seedDoesNotOverrideTrackedDownload() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/m.mp4" to 10L)
        val t = tracker(clock, sizes)
        t.onManagerProgress("/sdcard/Download/m.mp4", 10L, 1000L)
        assertFalse(t.onSeeded("/sdcard/Download/m.mp4"))
        assertEquals(DownloadPhase.ACTIVE, t.get("/sdcard/Download/m.mp4")?.phase)
    }

    private fun publishedTracker(clock: Clock, sizes: MutableMap<String, Long>, published: () -> Boolean) =
        DownloadTracker(
            nowMs = { clock.now },
            snapshot = { path -> sizes[path]?.let { it to 0L } },
            isPublished = { published() },
        )

    @Test
    fun publishedStableFileFreesWithoutCloseEvent() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/p.mp4" to 100L)
        val t = publishedTracker(clock, sizes) { true }
        t.onCreated("/sdcard/Download/p.mp4")
        clock.advance(DownloadTracker.PUBLISHED_STABLE_MS - 500)
        t.tick()
        assertTrue(t.isActive("/sdcard/Download/p.mp4"))
        clock.advance(500)
        t.tick()
        assertFalse(t.isActive("/sdcard/Download/p.mp4"))
    }

    @Test
    fun growingOrPendingFileStaysActive() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/g.mp4" to 100L)
        var published = true
        val t = publishedTracker(clock, sizes) { published }
        t.onCreated("/sdcard/Download/g.mp4")
        // Grows without events: refreshed, not freed.
        clock.advance(DownloadTracker.PUBLISHED_STABLE_MS)
        sizes["/sdcard/Download/g.mp4"] = 500L
        t.tick()
        assertTrue(t.isActive("/sdcard/Download/g.mp4"))
        // Stable but still IS_PENDING: waits.
        published = false
        clock.advance(DownloadTracker.PUBLISHED_STABLE_MS)
        t.tick()
        assertTrue(t.isActive("/sdcard/Download/g.mp4"))
        // Published: freed.
        published = true
        t.tick()
        assertFalse(t.isActive("/sdcard/Download/g.mp4"))
    }

    @Test
    fun downloadManagerEntryIgnoresPublishedShortcut() {
        val clock = Clock()
        val sizes = mutableMapOf("/sdcard/Download/d.mp4" to 100L)
        val t = publishedTracker(clock, sizes) { true }
        t.onManagerProgress("/sdcard/Download/d.mp4", 100L, 1000L)
        clock.advance(DownloadTracker.PUBLISHED_STABLE_MS * 2)
        t.tick()
        assertTrue(t.isActive("/sdcard/Download/d.mp4"))
    }

    @Test
    fun completeContentIsReadyAfterOneQuietSecond() {
        val clock = Clock()
        val path = "/sdcard/Download/d.mp4"
        val sizes = mutableMapOf(path to 100L)
        var complete: Boolean? = false
        val t = DownloadTracker(
            nowMs = { clock.now },
            snapshot = { p -> sizes[p]?.let { it to 0L } },
            isComplete = { complete },
        )
        t.onCreated(path)
        clock.advance(DownloadTracker.CONTENT_STABLE_MS)
        t.tick()
        assertTrue(t.isActive(path)) // stable but structurally incomplete
        complete = true
        clock.advance(DownloadTracker.CONTENT_STABLE_MS)
        assertEquals(listOf(path), t.tick())
    }

    @Test
    fun incompleteContentIsNotReleasedByMediaStoreAlone() {
        val clock = Clock()
        val path = "/sdcard/Download/e.mp4"
        val sizes = mutableMapOf(path to 100L)
        val t = DownloadTracker(
            nowMs = { clock.now },
            snapshot = { p -> sizes[p]?.let { it to 0L } },
            isPublished = { true },
            isComplete = { false },
        )
        t.onCreated(path)
        clock.advance(DownloadTracker.PUBLISHED_STABLE_MS)
        t.tick()
        assertTrue(t.isActive(path))
    }

    @Test
    fun openReleasesAFinishedFileAtOnce() {
        val clock = Clock()
        val path = "/sdcard/Download/f.mp4"
        val sizes = mutableMapOf(path to 100L)
        val done = ArrayList<String>()
        val t = DownloadTracker(
            nowMs = { clock.now },
            snapshot = { p -> sizes[p]?.let { it to 0L } },
            isComplete = { true },
        )
        t.onCompleted = { _, display -> done += display }
        t.onCreated(path)
        assertTrue(t.isStillDownloading(path)) // just written: not quiet yet
        clock.advance(DownloadTracker.CONTENT_STABLE_MS)
        sizes[path] = 120L
        assertTrue(t.isStillDownloading(path)) // grew since the last event
        t.onModified(path)
        clock.advance(DownloadTracker.CONTENT_STABLE_MS)
        assertFalse(t.isStillDownloading(path))
        assertFalse(t.isActive(path))
        assertEquals(listOf(path), done)
    }

    @Test
    fun downloadManagerRowWaitsForAllBytes() {
        val clock = Clock()
        val path = "/sdcard/Download/g.mp4"
        val sizes = mutableMapOf(path to 50L)
        val t = DownloadTracker(
            nowMs = { clock.now },
            snapshot = { p -> sizes[p]?.let { it to 0L } },
            isComplete = { true },
        )
        t.onManagerProgress(path, 50L, 100L)
        clock.advance(DownloadTracker.CONTENT_STABLE_MS)
        assertTrue(t.isStillDownloading(path))
    }

    @Test
    fun closedButIncompleteFileIsNotReleased() {
        val clock = Clock()
        val path = "/sdcard/Download/h.mp4"
        val sizes = mutableMapOf(path to 100L)
        val t = DownloadTracker(
            nowMs = { clock.now },
            snapshot = { p -> sizes[p]?.let { it to 0L } },
            isComplete = { false },
        )
        t.onCreated(path)
        t.onCloseOrMovedTo(path)
        clock.advance(DownloadTracker.SETTLE_MS)
        assertTrue(t.tick().isEmpty())
        assertEquals(DownloadPhase.ACTIVE, t.get(path)?.phase)
    }

    private val film = "/sdcard/Gallery/Downloaded-NP/film.mp4"

    private fun mission(state: DownloaderState, bytes: Long = -1, total: Long = -1) =
        DownloaderMission(film, bytes, total, state)

    /** The file looks finished by every file rule: only the downloader may disagree. */
    private fun completeFileTracker(clock: Clock, done: MutableList<String>): DownloadTracker {
        val t = DownloadTracker(
            nowMs = { clock.now },
            snapshot = { 1_000L to 0L },
            isPublished = { true },
            isComplete = { true },
        )
        t.onCompleted = { _, display -> done += display }
        return t
    }

    @Test
    fun downloaderReportsBytesAndSize() {
        val t = completeFileTracker(Clock(), ArrayList())

        t.onDownloaderMissions(listOf(mission(DownloaderState.RUNNING, 50, 200)))

        assertEquals(50L, t.get(film)?.bytes)
        assertEquals(200L, t.get(film)?.totalBytes)
        assertEquals(DownloadSource.DOWNLOADER, t.get(film)?.source)
        assertTrue(t.followsDownloader)
    }

    @Test
    fun fileRulesDoNotReleaseWhatTheDownloaderStillLists() {
        val clock = Clock()
        val done = ArrayList<String>()
        val t = completeFileTracker(clock, done)
        t.onDownloaderMissions(listOf(mission(DownloaderState.RUNNING, 50, 200)))

        t.onModified(film)
        t.onCloseOrMovedTo(film)
        t.onCreated(film)
        clock.advance(DownloadTracker.STALE_REMOVE_MS + 1_000L)
        assertTrue(t.tick().isEmpty())

        assertTrue(t.isStillDownloading(film))
        assertEquals(50L, t.get(film)?.bytes)
        assertEquals(DownloadSource.DOWNLOADER, t.get(film)?.source)
        assertEquals(emptyList<String>(), done)
    }

    @Test
    fun aFileThatDoesNotExistYetStaysFollowed() {
        val clock = Clock()
        val t = DownloadTracker(nowMs = { clock.now }, snapshot = { null })
        t.onDownloaderMissions(listOf(mission(DownloaderState.RUNNING, 50, 200)))

        clock.advance(DownloadTracker.STALE_REMOVE_MS + 1_000L)
        t.tick()

        assertTrue(t.isStillDownloading(film))
    }

    @Test
    fun muxingHasNoPercentAndAPauseKeepsTheLastOne() {
        val t = completeFileTracker(Clock(), ArrayList())
        t.onDownloaderMissions(listOf(mission(DownloaderState.RUNNING, 50, 200)))

        t.onDownloaderMissions(listOf(mission(DownloaderState.PAUSED)))
        assertEquals(50L, t.get(film)?.bytes)
        assertEquals(200L, t.get(film)?.totalBytes)

        t.onDownloaderMissions(listOf(mission(DownloaderState.POSTPROCESSING)))
        assertEquals(null, t.get(film)?.totalBytes)
    }

    @Test
    fun anErrorKeepsTheFileLocked() {
        val clock = Clock()
        val done = ArrayList<String>()
        val t = completeFileTracker(clock, done)
        t.onDownloaderMissions(listOf(mission(DownloaderState.RUNNING, 50, 200)))

        t.onDownloaderMissions(listOf(mission(DownloaderState.ERROR)))
        clock.advance(DownloadTracker.IDLE_COMPLETE_MS)
        t.tick()

        assertTrue(t.isStillDownloading(film))
        assertEquals(emptyList<String>(), done)
    }

    @Test
    fun finishedReleasesTheFileOnce() {
        val done = ArrayList<String>()
        val t = completeFileTracker(Clock(), done)
        t.onDownloaderMissions(listOf(mission(DownloaderState.POSTPROCESSING)))

        t.onDownloaderMissions(listOf(mission(DownloaderState.FINISHED)))
        t.onDownloaderMissions(listOf(mission(DownloaderState.FINISHED)))

        assertFalse(t.isActive(film))
        assertEquals(listOf(film), done)
    }

    @Test
    fun aFinishedRowOfAnUntrackedFileIsIgnored() {
        val done = ArrayList<String>()
        val t = completeFileTracker(Clock(), done)

        t.onDownloaderMissions(listOf(mission(DownloaderState.FINISHED)))

        assertFalse(t.isActive(film))
        assertEquals(emptyList<String>(), done)
    }

    @Test
    fun aFileNoLongerListedGoesBackToTheFileRules() {
        val clock = Clock()
        val done = ArrayList<String>()
        val t = completeFileTracker(clock, done)
        t.onDownloaderMissions(listOf(mission(DownloaderState.RUNNING, 50, 200)))

        t.onDownloaderMissions(emptyList())

        assertEquals(DownloadSource.FILESYSTEM, t.get(film)?.source)
        assertEquals(null, t.get(film)?.totalBytes)
        assertFalse(t.followsDownloader)
        clock.advance(DownloadTracker.CONTENT_STABLE_MS)
        t.tick()
        clock.advance(DownloadTracker.CONTENT_STABLE_MS)
        t.tick()
        assertEquals(listOf(film), done)
    }

    @Test
    fun aDownloaderMissionTakesOverAFileAlreadyTracked() {
        val t = completeFileTracker(Clock(), ArrayList())
        t.onCreated(film)

        t.onDownloaderMissions(listOf(mission(DownloaderState.RUNNING, 50, 200)))

        assertEquals(DownloadSource.DOWNLOADER, t.get(film)?.source)
        assertEquals(200L, t.get(film)?.totalBytes)
    }

    @Test
    fun otherFilesOfTheDownloaderAreLeftOut() {
        val t = completeFileTracker(Clock(), ArrayList())

        t.onDownloaderMissions(
            listOf(DownloaderMission("/sdcard/Gallery/Downloaded-NP/notes.txt", 5, 10, DownloaderState.RUNNING)),
        )

        assertTrue(t.active.value.isEmpty())
    }
}
