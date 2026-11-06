package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderProgressTest {
    private val root = "/storage/emulated/0"
    private val folder = "/storage/emulated/0/Gallery/Downloaded-NP"
    private val album = Album(1, "Downloaded-NP", null, 0, "Gallery/Downloaded-NP/")

    private fun download(name: String, bytes: Long, total: Long?) = ActiveDownload(
        path = "/storage/emulated/0/gallery/downloaded-np/$name",
        phase = DownloadPhase.ACTIVE,
        bytes = bytes,
        totalBytes = total,
        source = DownloadSource.DOWNLOADER,
        lastEventAt = 0L,
    ).let { it.path to it }

    @Test
    fun aMissionOfTheNewPipeFolderGetsItsFullPath() {
        assertEquals(
            "$folder/film.mp4",
            downloaderMissionPath(root, folder, "Gallery/Downloaded-NP/film.mp4"),
        )
        assertEquals(
            "$folder/film.mp4",
            downloaderMissionPath(root, "$folder/", "gallery/downloaded-np/film.mp4")?.replace("gallery/downloaded-np", "Gallery/Downloaded-NP"),
        )
    }

    @Test
    fun aMissionSavedElsewhereIsNotFollowed() {
        assertNull(downloaderMissionPath(root, folder, "PipePipe/film.mp4"))
        assertNull(downloaderMissionPath(root, folder, "Gallery/Downloaded-NP/old/film.mp4"))
        assertNull(downloaderMissionPath(root, null, "Gallery/Downloaded-NP/film.mp4"))
    }

    @Test
    fun readsTheStatesOfTheDownloader() {
        DownloaderState.entries.forEach { assertEquals(it, DownloaderState.fromName(it.name)) }
        assertNull(DownloaderState.fromName("SOMETHING_NEW"))
        assertNull(DownloaderState.fromName(null))
    }

    @Test
    fun aFolderHoldsWhatIsWrittenIntoIt() {
        assertTrue(album.holdsDownload("/storage/emulated/0/gallery/downloaded-np/film.mp4"))
        assertFalse(album.holdsDownload("/storage/emulated/0/pipepipe/film.mp4"))
    }

    @Test
    fun aFolderShowsItsDownloadsTogether() {
        val downloads = mapOf(download("a.mp4", 50, 100), download("b.mp4", 250, 300))

        assertEquals(75, folderDownloadPercent(album, downloads))
    }

    @Test
    fun downloadsWithoutASizeDoNotCount() {
        val downloads = mapOf(download("a.mp4", 50, 100), download("b.mp4", 0, null))

        assertEquals(50, folderDownloadPercent(album, downloads))
        assertNull(folderDownloadPercent(album, mapOf(download("b.mp4", 0, null))))
    }

    @Test
    fun theFolderNeverPassesAHundredPercent() {
        assertEquals(100, folderDownloadPercent(album, mapOf(download("hls.mp4", 150, 100))))
    }

    @Test
    fun downloadsOfOtherFoldersDoNotCount() {
        val other = Album(2, "Other", null, 0, "Gallery/Other/")

        assertNull(folderDownloadPercent(other, mapOf(download("a.mp4", 50, 100))))
    }
}
