package dev.ander.alexet.data

import android.net.DummyUri
import dev.ander.alexet.ui.gallery.GalleryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryFolderBufferTest {

    private fun sampleMedia(
        id: Long,
        name: String,
        bucketId: Long,
        rawBucketId: Long = 0L,
    ) = GalleryMedia(
        id = id,
        uri = DummyUri("content://media/external/images/media/$id"),
        displayName = name,
        mimeType = "image/jpeg",
        dateTakenMs = 0L,
        dateModifiedMs = 1000L,
        dateAddedMs = 1000L,
        sizeBytes = 1024L,
        width = 1920,
        height = 1080,
        durationMs = 0L,
        bucketId = bucketId,
        bucketName = "TestAlbum",
        relativePath = "Pictures/TestAlbum",
        absolutePath = "/storage/emulated/0/Pictures/TestAlbum/$name",
        rawBucketId = rawBucketId,
    )

    @Test
    fun filesInPrefersFolderMediaWhenGlobalMediaIsEmpty() {
        val albumId = 100L
        val item1 = sampleMedia(1L, "photo1.jpg", bucketId = albumId)
        val item2 = sampleMedia(2L, "photo2.jpg", bucketId = albumId)

        val state = GalleryUiState(
            media = emptyList(), // Global media scan not finished yet
            folderMedia = mapOf(albumId to listOf(item1, item2)),
            loadingFolderIds = setOf(albumId),
        )

        val files = state.filesIn(albumId)
        assertEquals(2, files.size)
        assertEquals(listOf(1L, 2L), files.map { it.id })
        assertFalse("Folder should not be marked loading once folderMedia is present", state.isFolderLoading(albumId))
    }

    @Test
    fun filesInMatchesRawBucketIdInAlbumLegacyBucketIds() {
        val primaryAlbumId = 100L
        val legacyId1 = 200L
        val legacyId2 = 300L
        val album = Album(
            id = primaryAlbumId,
            name = "Vacation",
            cover = null,
            itemCount = 2,
            relativePath = "Pictures/Vacation/",
            legacyBucketIds = setOf(legacyId1, legacyId2),
        )

        val itemPrimary = sampleMedia(1L, "a.jpg", bucketId = primaryAlbumId, rawBucketId = legacyId1)
        val itemLegacy = sampleMedia(2L, "b.jpg", bucketId = 99999L /* synthetic mismatch */, rawBucketId = legacyId2)
        val itemOther = sampleMedia(3L, "c.jpg", bucketId = 88888L, rawBucketId = 400L)

        val state = GalleryUiState(
            allAlbums = listOf(album),
            albums = listOf(album),
            media = listOf(itemPrimary, itemLegacy, itemOther),
        )

        val result = state.filesIn(primaryAlbumId)
        assertEquals("Both primary and legacy items matching rawBucketId must be returned", 2, result.size)
        assertTrue(result.any { it.id == 1L })
        assertTrue(result.any { it.id == 2L })
        assertFalse(result.any { it.id == 3L })
    }


    @Test
    fun filesInDoesNotDuplicateWhenCacheAndGlobalSharePath() {
        val albumId = 100L
        val cached = sampleMedia(1L, "photo.jpg", bucketId = albumId)
        val rescanned = sampleMedia(101L, "photo.jpg", bucketId = albumId)
        val other = sampleMedia(2L, "other.jpg", bucketId = albumId)

        val state = GalleryUiState(
            media = listOf(rescanned, other),
            folderMedia = mapOf(albumId to listOf(cached, other)),
        )

        val files = state.filesIn(albumId)
        assertEquals("Same path must not appear twice after MediaStore reissue", 2, files.size)
        assertEquals(setOf("photo.jpg", "other.jpg"), files.map { it.displayName }.toSet())
        assertEquals(
            "Cache URI/id must win so Download tiles do not restart",
            1L,
            files.first { it.displayName == "photo.jpg" }.id,
        )
    }

    @Test
    fun filesInMergesGlobalMediaWithoutLosingLiveFolderMedia() {
        val albumId = 100L
        // Live query found item1 and item2
        val item1 = sampleMedia(1L, "photo1.jpg", bucketId = albumId)
        val item2 = sampleMedia(2L, "photo2.jpg", bucketId = albumId)
        // Background queryAll later discovered item3
        val item3 = sampleMedia(3L, "photo3.jpg", bucketId = albumId)

        val state = GalleryUiState(
            media = listOf(item1, item3), // item2 missing from partial snapshot, item3 freshly found
            folderMedia = mapOf(albumId to listOf(item1, item2)),
        )

        val files = state.filesIn(albumId)
        assertEquals("Must contain item1, item2 from folderMedia and item3 from global media", 3, files.size)
        assertTrue(files.any { it.id == 1L })
        assertTrue(files.any { it.id == 2L })
        assertTrue(files.any { it.id == 3L })
    }

    @Test
    fun computeMediaStoreBucketIdsGeneratesCandidatesForRelativePath() {
        val candidates = dev.ander.alexet.data.GalleryRepository.computeMediaStoreBucketIds("Download")
        assertTrue("Candidates must not be empty for 'Download'", candidates.isNotEmpty())
        // Should contain standard /storage/emulated/0/Download hash
        val standardCandidate = "/storage/emulated/0/Download".lowercase().hashCode().toLong()
        assertTrue("Must contain standard /storage/emulated/0/Download bucket id candidate", candidates.contains(standardCandidate))
    }

    @Test
    fun folderMediaReconciliationClearsEmptiedFolder() {
        val albumId = 100L
        val oldItem = sampleMedia(1L, "deleted.jpg", bucketId = albumId)
        val currentFolderMedia = mapOf(albumId to listOf(oldItem))

        // Simulate applyQueryResult with fresh = emptyList() (all items deleted)
        val fresh = emptyList<GalleryMedia>()
        val albums = emptyList<Album>()
        val grouped = fresh.groupBy { it.bucketId }

        val updatedMap = currentFolderMedia.mapValues { (id, _) ->
            val album = albums.firstOrNull { it.id == id }
            if (album != null) {
                fresh.filter { it.bucketId == id }
            } else {
                grouped[id] ?: emptyList()
            }
        }

        assertEquals("Folder media must be empty after all items deleted", emptyList<GalleryMedia>(), updatedMap[albumId])
    }

    @Test
    fun filesInNeverProducesDuplicateIdsEvenWithDifferentUris() {
        val albumId = 100L
        val duplicateId = 33792L
        // Same id, but different URI (e.g. external_primary vs external, or pre-move vs post-move)
        val itemInFolder = sampleMedia(duplicateId, "photo.jpg", bucketId = albumId)
        val itemInGlobal = itemInFolder.copy(
            uri = DummyUri("content://media/external_primary/images/media/$duplicateId"),
            relativePath = "Pictures/Other/",
        )

        val state = GalleryUiState(
            media = listOf(itemInGlobal),
            folderMedia = mapOf(albumId to listOf(itemInFolder)),
        )

        val files = state.filesIn(albumId)
        assertEquals("Duplicate item IDs must never be present in filesIn", 1, files.size)
        assertEquals(duplicateId, files.first().id)
    }
}
