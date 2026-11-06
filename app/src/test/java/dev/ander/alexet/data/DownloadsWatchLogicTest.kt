package dev.ander.alexet.data

import android.net.DummyUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsWatchLogicTest {

    private fun media(
        id: Long,
        name: String,
        relativePath: String,
        bucketName: String,
        size: Long = 10L,
        abs: String? = null,
        bucketId: Long = 1L,
    ) = GalleryMedia(
        id = id,
        uri = DummyUri("content://media/external/images/media/$id"),
        displayName = name,
        mimeType = if (name.endsWith(".mp4")) "video/mp4" else "image/jpeg",
        dateTakenMs = 0L,
        dateModifiedMs = 1_000L,
        dateAddedMs = 1_000L,
        sizeBytes = size,
        width = 100,
        height = 100,
        durationMs = 0L,
        bucketId = bucketId,
        bucketName = bucketName,
        relativePath = relativePath,
        absolutePath = abs,
    )

    @Test
    fun filterMediaPathsKeepsImagesAndVideos() {
        val paths = listOf(
            " /storage/emulated/0/Download/clip.mp4 ",
            "/storage/emulated/0/Download/clip.mp4",
            "/storage/emulated/0/Download/photo.JPG",
            "/storage/emulated/0/Download/notes.txt",
            "/storage/emulated/0/Download/clip.mp4.part",
            "/storage/emulated/0/Download/.hidden.jpg",
            "",
        )
        assertEquals(
            listOf(
                "/storage/emulated/0/Download/clip.mp4",
                "/storage/emulated/0/Download/photo.JPG",
            ),
            DownloadsWatchLogic.filterMediaPaths(paths),
        )
    }

    @Test
    fun mediaMimeOfUsesExtension() {
        assertEquals("video/mp4", DownloadsWatchLogic.mediaMimeOf("a/b/c.mp4"))
        assertEquals("image/jpeg", DownloadsWatchLogic.mediaMimeOf("shot.JPEG"))
        assertNull(DownloadsWatchLogic.mediaMimeOf("file.part"))
        assertNull(DownloadsWatchLogic.mediaMimeOf(".nomedia"))
        assertNull(DownloadsWatchLogic.mediaMimeOf("   "))
    }

    @Test
    fun pendingPathsAreCoalescedAndDeduped() {
        val pending = LinkedHashSet<String>()
        DownloadsWatchLogic.addPendingPaths(
            pending,
            listOf(" /Download/a.mp4 ", "/Download/a.mp4", "/Download/b.jpg"),
        )
        DownloadsWatchLogic.addPendingPaths(
            pending,
            listOf("/Download/b.jpg", "/Download/c.part"),
        )
        assertEquals(
            listOf("/Download/a.mp4", "/Download/b.jpg", "/Download/c.part"),
            pending.toList(),
        )
        assertEquals(
            listOf("/Download/a.mp4", "/Download/b.jpg"),
            DownloadsWatchLogic.filterMediaPaths(pending),
        )
    }

    @Test
    fun isDownloadsUriDetectsMediaStoreDownloads() {
        assertTrue(
            DownloadsWatchLogic.isDownloadsUri(
                DummyUri("content://media/external/downloads/12"),
            ),
        )
        assertTrue(
            DownloadsWatchLogic.isDownloadsUri(
                DummyUri("content://media/external_primary/downloads"),
            ),
        )
        assertFalse(
            DownloadsWatchLogic.isDownloadsUri(
                DummyUri("content://media/external/images/media/12"),
            ),
        )
        assertFalse(DownloadsWatchLogic.isDownloadsUri(null))
    }

    @Test
    fun mergeDownloadsAddsNewFilesWithoutReplacingImagesUri() {
        val dcim = media(1, "camera.jpg", "DCIM/Camera/", "Camera", bucketId = 10L)
        val oldDl = media(2, "old.jpg", "Download/", "Download", bucketId = 20L)
        val other = media(3, "pic.png", "Pictures/", "Pictures", bucketId = 30L)
        val freshDl = media(4, "new.mp4", "Download/", "Download", bucketId = 20L)
        val replacement = media(
            5,
            "old.jpg",
            "Download/",
            "Download",
            size = 99L,
            bucketId = 20L,
        ).copy(uri = DummyUri("content://media/external/downloads/5"))

        val merged = DownloadsWatchLogic.mergeDownloads(
            current = listOf(dcim, oldDl, other),
            downloads = listOf(freshDl, replacement),
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.id })
        assertTrue(merged.any { it.displayName == "new.mp4" })
        assertEquals(
            "content://media/external/images/media/2",
            merged.first { it.displayName == "old.jpg" }.uri.toString(),
        )
    }

    @Test
    fun mergeDownloadsSkipsSamePathUnderDownloadsCollection() {
        val imagesRow = media(
            id = 8,
            name = "shot.jpg",
            relativePath = "Download/",
            bucketName = "Download",
            bucketId = 20L,
            abs = "/storage/emulated/0/Download/shot.jpg",
        )
        val downloadsRow = imagesRow.copy(
            id = 88,
            uri = DummyUri("content://media/external/downloads/88"),
        )
        val merged = DownloadsWatchLogic.mergeDownloads(
            current = listOf(imagesRow),
            downloads = listOf(downloadsRow),
        )
        assertEquals(listOf(8L), merged.map { it.id })
    }

    @Test
    fun mergeDownloadsDoesNotWipeOnEmptyQuery() {
        val dcim = media(1, "camera.jpg", "DCIM/Camera/", "Camera", bucketId = 10L)
        val oldDl = media(2, "kept.jpg", "Download/", "Download", bucketId = 20L)
        val merged = DownloadsWatchLogic.mergeDownloads(
            current = listOf(dcim, oldDl),
            downloads = emptyList(),
        )
        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }

    @Test
    fun mergeDownloadsKeepsImagesVideoOnlyDownloadItems() {
        val imagesDownload = media(
            id = 8,
            name = "from-images.jpg",
            relativePath = "Download/",
            bucketName = "Download",
            bucketId = 20L,
        )
        val freshDl = media(9, "new.mp4", "Download/", "Download", bucketId = 20L)
        val merged = DownloadsWatchLogic.mergeDownloads(
            current = listOf(imagesDownload),
            downloads = listOf(freshDl),
        )
        assertEquals(listOf(8L, 9L), merged.map { it.id })
    }

    @Test
    fun isDownloadsFolderItemIgnoresPicturesDownload() {
        assertFalse(
            DownloadsWatchLogic.isDownloadsFolderItem(
                relativePath = "Pictures/Download/",
                bucketName = "Download",
                absolutePath = "/storage/emulated/0/Pictures/Download/shot.jpg",
            ),
        )
        assertTrue(
            DownloadsWatchLogic.isDownloadsFolderItem(
                relativePath = "Download/",
                bucketName = "Download",
                absolutePath = "/storage/emulated/0/Download/shot.jpg",
            ),
        )
        assertTrue(
            DownloadsWatchLogic.isDownloadsFolderItem(
                relativePath = "Downloads/Telegram/",
                bucketName = "Telegram",
            ),
        )
    }
}
