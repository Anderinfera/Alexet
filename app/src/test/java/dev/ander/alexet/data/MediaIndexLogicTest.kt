package dev.ander.alexet.data

import android.net.DummyUri
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaIndexLogicTest {

    private fun uri(value: String): Uri = DummyUri(value)

    private fun media(
        id: Long,
        uriStr: String = "content://media/external/images/media/$id",
        name: String = "f$id.jpg",
        path: String? = "/storage/emulated/0/Download/$name",
        modified: Long = 1_000L,
    ) = GalleryMedia(
        id = id,
        uri = uri(uriStr),
        displayName = name,
        mimeType = "image/jpeg",
        dateTakenMs = 0L,
        dateModifiedMs = modified,
        dateAddedMs = modified,
        sizeBytes = 10L,
        width = 1,
        height = 1,
        durationMs = 0L,
        bucketId = 7L,
        bucketName = "Download",
        relativePath = "Download/",
        absolutePath = path,
    )

    @Test
    fun mediaStoreRowsAreRecognised() {
        assertTrue(MediaIndexLogic.isMediaStoreRow(media(1)))
        assertFalse(MediaIndexLogic.isMediaStoreRow(media(2, uriStr = "file:///storage/emulated/0/x/a.jpg")))
        assertFalse(MediaIndexLogic.isMediaStoreRow(media(3, uriStr = "content://dev.ander.alexet.fileprovider/t/a.jpg")))
    }

    @Test
    fun dedupePrefersImagesOverDownloadsUri() {
        val downloads = media(5, uriStr = "content://media/external/downloads/5")
        val images = media(5)
        val out = MediaIndexLogic.dedupeById(listOf(downloads, images))
        assertEquals(1, out.size)
        assertSame(images, out[0])
        val keepFirst = MediaIndexLogic.dedupeById(listOf(images, downloads))
        assertSame(images, keepFirst[0])
    }

    @Test
    fun mergeDeltaReplacesInPlaceAppendsAndDeletes() {
        val a = media(1)
        val b = media(2)
        val c = media(3)
        val bNew = media(2, modified = 5_000L)
        val d = media(4)
        val out = MediaIndexLogic.mergeDelta(listOf(a, b, c), listOf(bNew, d), setOf(3L))
        assertEquals(listOf(1L, 2L, 4L), out.map { it.id })
        assertSame(a, out[0])
        assertSame(bNew, out[1])
    }

    @Test
    fun mergeDeltaDropsHiddenFileRowCoveredByNewMediaStoreRow() {
        val hidden = media(-9, uriStr = "file:///storage/emulated/0/Download/f7.jpg", name = "f7.jpg")
        val fresh = media(7)
        val out = MediaIndexLogic.mergeDelta(listOf(hidden), listOf(fresh), emptySet())
        assertEquals(listOf(7L), out.map { it.id })
    }

    @Test
    fun mergeDeltaWithoutChangesReturnsSameList() {
        val base = listOf(media(1))
        assertSame(base, MediaIndexLogic.mergeDelta(base, emptyList(), emptySet()))
    }

    @Test
    fun missingIdsOnlyConsidersMediaStoreRows() {
        val base = listOf(media(1), media(2), media(-3, uriStr = "file:///x/a.jpg"))
        assertEquals(listOf(2L), MediaIndexLogic.missingIds(base, setOf(1L)))
    }

    @Test
    fun diffDetectsChangedAndRemovedRows() {
        val a = media(1)
        val b = media(2)
        val persisted = mapOf(1L to a, 2L to b, 3L to media(3))
        val bRenamed = b.copy(displayName = "new.jpg")
        val diff = MediaIndexLogic.diff(persisted, listOf(a, bRenamed))
        assertEquals(listOf(2L), diff.upserts.map { it.id })
        assertEquals(listOf(3L), diff.deletes)
    }

    @Test
    fun diffIsEmptyForEqualCopies() {
        val a = media(1)
        val persisted = mapOf(1L to a)
        val diff = MediaIndexLogic.diff(persisted, listOf(a.copy()))
        assertTrue(diff.isEmpty)
    }

    @Test
    fun suspiciousShrinkGuard() {
        assertTrue(MediaIndexLogic.isSuspiciousShrink(persistedSize = 30_000, deletes = 29_000))
        assertFalse(MediaIndexLogic.isSuspiciousShrink(persistedSize = 30_000, deletes = 500))
        assertFalse(MediaIndexLogic.isSuspiciousShrink(persistedSize = 30_000, deletes = 12_000))
        assertFalse(MediaIndexLogic.isSuspiciousShrink(persistedSize = 400, deletes = 400))
    }
}
