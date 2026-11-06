package dev.ander.alexet.data

import android.net.DummyUri
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentOrderTest {

    private fun sampleMedia(id: Long, name: String, rawBucketId: Long = 0L): GalleryMedia = GalleryMedia(
        id = id,
        uri = DummyUri("content://media/external/images/media/$id"),
        displayName = name,
        mimeType = "image/jpeg",
        dateTakenMs = 0L,
        dateModifiedMs = 1_000L,
        dateAddedMs = 1_000L,
        sizeBytes = 100L,
        width = 100,
        height = 100,
        durationMs = 0L,
        bucketId = 10L,
        bucketName = "MyAlbum",
        relativePath = "Pictures/MyAlbum",
        rawBucketId = rawBucketId,
    )

    @Test
    fun contentOrderOverridesDefaultSorting() {
        val item1 = sampleMedia(1L, "a.jpg")
        val item2 = sampleMedia(2L, "b.jpg")
        val item3 = sampleMedia(3L, "c.jpg")
        val items = listOf(item1, item2, item3)

        val customOrder = listOf(3L, 1L, 2L)
        val rank = customOrder.withIndex().associate { it.value to it.index }

        val sorted = items.sortedWith(
            compareBy<GalleryMedia> { rank[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.displayName },
        )

        assertEquals(listOf(3L, 1L, 2L), sorted.map { it.id })
    }

    @Test
    fun unrankedItemsPlacedAtEnd() {
        val item1 = sampleMedia(1L, "a.jpg")
        val item2 = sampleMedia(2L, "b.jpg")
        val item3 = sampleMedia(3L, "c.jpg")
        val items = listOf(item1, item2, item3)

        val customOrder = listOf(2L)
        val rank = customOrder.withIndex().associate { it.value to it.index }

        val sorted = items.sortedWith(
            compareBy<GalleryMedia> { rank[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.displayName },
        )

        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.id })
    }

    @Test
    fun numericRenamedItemsSortNaturallyWhenContentOrderEmpty() {
        val item1 = sampleMedia(201L, "1.jpg")
        val item2 = sampleMedia(202L, "2.jpg")
        val item3 = sampleMedia(203L, "3.jpg")
        val items = listOf(item3, item1, item2)

        val customOrder = emptyList<Long>()
        val rank = customOrder.withIndex().associate { it.value to it.index }

        val sorted = items.sortedWith(
            compareBy<GalleryMedia> { rank[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.displayName },
        )

        assertEquals(listOf(201L, 202L, 203L), sorted.map { it.id })
    }

    @Test
    fun legacyBucketItemsIncludedInAlbumFilter() {
        val primaryAlbumId = 10L
        val legacyAlbumId = 20L
        val album = Album(
            id = primaryAlbumId,
            name = "Vacation",
            cover = null,
            itemCount = 2,
            relativePath = "Pictures/Vacation",
            legacyBucketIds = setOf(legacyAlbumId),
        )

        val item1 = sampleMedia(1L, "a.jpg").copy(bucketId = primaryAlbumId)
        val item2 = sampleMedia(2L, "b.jpg", rawBucketId = legacyAlbumId).copy(bucketId = 99999L /* synthetic mismatch */)
        val item3 = sampleMedia(3L, "c.jpg").copy(bucketId = 999L)
        val allMedia = listOf(item1, item2, item3)

        val folderMedia = allMedia.filter {
            it.bucketId == primaryAlbumId || (it.rawBucketId != 0L && it.rawBucketId in album.legacyBucketIds)
        }
        assertEquals(2, folderMedia.size)
        assertEquals(setOf(1L, 2L), folderMedia.map { it.id }.toSet())
    }
}
