package dev.ander.alexet.data

import android.net.DummyUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FolderRowIndexTest {

    private fun row(id: Long, bucket: Long, rel: String, raw: Long = 0L) = GalleryMedia(
        id = id,
        uri = DummyUri("content://media/external/images/media/$id"),
        displayName = "f$id.jpg",
        mimeType = "image/jpeg",
        dateTakenMs = 0L,
        dateModifiedMs = id,
        dateAddedMs = id,
        sizeBytes = 1L,
        width = 0,
        height = 0,
        durationMs = 0L,
        bucketId = bucket,
        bucketName = "b",
        relativePath = rel,
        rawBucketId = raw,
    )

    private val library = listOf(
        row(1, bucket = 10, rel = "DCIM/Camera/"),
        row(2, bucket = 20, rel = "Pictures/Other/"),
        // Old MediaStore bucket id of the folder.
        row(3, bucket = 30, rel = "Moved/", raw = 777),
        // Same path written otherwise.
        row(4, bucket = 40, rel = " dcim/camera "),
        row(5, bucket = 10, rel = "DCIM/Camera/"),
        // The same id twice: once in the result.
        row(5, bucket = 10, rel = "DCIM/Camera/"),
        row(6, bucket = 50, rel = ""),
    )

    private val album = Album(id = 10, name = "Camera", cover = null, itemCount = 3, relativePath = "DCIM/Camera/", legacyBucketIds = setOf(777L, 0L))

    /** The filter of GalleryUiState.computeFilesIn before the index. */
    private fun filtered(albumId: Long, album: Album?): List<GalleryMedia> = library.filter { item ->
        item.bucketId == albumId ||
            (album != null && item.rawBucketId != 0L && item.rawBucketId in album.legacyBucketIds) ||
            (album != null && album.relativePath.isNotBlank() && item.relativePath.trim().trim('/').equals(album.relativePath.trim().trim('/'), ignoreCase = true))
    }.distinctBy { it.id }

    @Test
    fun rowsOfAFolderAreTheFilteredRows() {
        val index = FolderRowIndex(library)
        assertEquals(filtered(10, album), index.rowsOf(10, album))
        assertEquals(filtered(10, null), index.rowsOf(10, null))
        assertEquals(filtered(50, null), index.rowsOf(50, null))
        assertEquals(emptyList<GalleryMedia>(), index.rowsOf(99, null))
    }

    @Test
    fun byIdKeepsTheLastRowOfAnId() {
        val index = FolderRowIndex(library)
        assertSame(library[5], index.byId[5L])
        assertEquals(library.associateBy { it.id }, index.byId)
    }
}
