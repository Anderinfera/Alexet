package dev.ander.alexet.data

import android.net.DummyUri
import dev.ander.alexet.ui.gallery.infoLine
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderDetailsTest {

    private fun file(id: Long, size: Long, added: Long, modified: Long) = GalleryMedia(
        id = id,
        uri = DummyUri("content://media/external/images/media/$id"),
        displayName = "f$id.jpg",
        mimeType = "image/jpeg",
        dateTakenMs = 0L,
        dateModifiedMs = modified,
        dateAddedMs = added,
        sizeBytes = size,
        width = 0,
        height = 0,
        durationMs = 0L,
        bucketId = 1L,
        bucketName = "Camera",
        relativePath = "DCIM/Camera/",
    )

    @Test
    fun countsSizeAndTheOldestFile() {
        val details = FolderDetails.of(
            files = listOf(
                file(1, 1_000, added = 5_000, modified = 9_000),
                // No date added: its modification date counts.
                file(2, 2_500, added = 0, modified = 3_000),
                file(3, -1, added = 7_000, modified = 7_000),
            ),
            path = "/storage/emulated/0/DCIM/Camera",
            lastUsedMs = 42L,
        )
        assertEquals(3, details.fileCount)
        assertEquals(3_500L, details.sizeBytes)
        assertEquals(3_000L, details.createdMs)
        assertEquals(42L, details.lastUsedMs)
        assertEquals("/storage/emulated/0/DCIM/Camera", details.path)
    }

    @Test
    fun anEmptyFolderHasNoDate() {
        val details = FolderDetails.of(emptyList(), path = null, lastUsedMs = 0L)
        assertEquals(0, details.fileCount)
        assertEquals(0L, details.sizeBytes)
        assertEquals(0L, details.createdMs)
    }

    @Test
    fun aLabelCopiesAsALine() {
        assertEquals("Rozmiar: 177,6 KB", infoLine("Rozmiar", "177,6 KB"))
        assertEquals("Ścieżka: /a/b", infoLine("Ścieżka:", "/a/b"))
    }
}
