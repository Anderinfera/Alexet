package dev.ander.alexet.data

import dev.ander.alexet.data.MediaChange.Collection
import dev.ander.alexet.data.MediaChange.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaChangeTest {

    @Test
    fun readsRowUrisOfEveryCollection() {
        assertEquals(
            MediaChange("external_primary", Collection.IMAGES, 70019L, Kind.INSERT),
            MediaChange.parse("content://media/external_primary/images/media/70019", MediaChange.NOTIFY_INSERT),
        )
        assertEquals(
            MediaChange("external", Collection.VIDEO, 5L, Kind.UPDATE),
            MediaChange.parse("content://media/external/video/media/5", MediaChange.NOTIFY_UPDATE or 1),
        )
        assertEquals(
            MediaChange("external", Collection.FILES, 70018L, Kind.DELETE),
            MediaChange.parse("content://media/external/file/70018", MediaChange.NOTIFY_DELETE or 1),
        )
        assertEquals(
            MediaChange("external_primary", Collection.DOWNLOADS, 9L, Kind.INSERT),
            MediaChange.parse("content://media/external_primary/downloads/9", MediaChange.NOTIFY_INSERT),
        )
    }

    @Test
    fun aCollectionWithoutARowHasNoId() {
        assertNull(MediaChange.parse("content://media/external/images/media", 0)!!.id)
        assertNull(MediaChange.parse("content://media/external/file", MediaChange.NOTIFY_UPDATE)!!.id)
        assertNull(MediaChange.parse("content://media/external/audio/genres", 0)!!.id)
        assertEquals(Kind.UNKNOWN, MediaChange.parse("content://media/external/file/3", 0)!!.kind)
    }

    @Test
    fun otherUrisAreNotMediaStore() {
        assertNull(MediaChange.parse("content://downloads/my_downloads/3", MediaChange.NOTIFY_INSERT))
        assertEquals(Collection.OTHER, MediaChange.parse("content://media/external/fs_id", 0)!!.collection)
    }

    @Test
    fun aBatchTellsFileOnlyRowsFromListedOnes() {
        val queue = MediaChangeQueue()
        // A photo: notified as an image and as a file (in separate callbacks).
        queue.add(listOf("content://media/external_primary/images/media/1"), MediaChange.NOTIFY_INSERT)
        queue.add(listOf("content://media/external_primary/file/1"), MediaChange.NOTIFY_INSERT)
        // A file of a hidden folder and a deleted document: file table only.
        queue.add(listOf("content://media/external_primary/file/2"), MediaChange.NOTIFY_INSERT)
        queue.add(listOf("content://media/external_primary/file/3"), MediaChange.NOTIFY_DELETE)
        // An audio file and its collection-wide notifications: not the library's.
        queue.add(
            listOf("content://media/external/audio/media/4", "content://media/external/audio/genres", "content://media/external/file/4"),
            MediaChange.NOTIFY_INSERT,
        )
        val batch = queue.drain()
        assertEquals(setOf(1L), batch.listedIds)
        assertEquals(setOf(2L), batch.fileOnlyIds)
        assertEquals(false, batch.unknown)
        assertTrue(queue.drain().isEmpty)
    }

    @Test
    fun aBatchNamesDeletedAndAddedRows() {
        val queue = MediaChangeQueue()
        queue.add(listOf("content://media/external/images/media/5"), MediaChange.NOTIFY_DELETE)
        queue.add(listOf("content://media/external/file/5"), MediaChange.NOTIFY_DELETE)
        queue.add(listOf("content://media/external/video/media/6"), MediaChange.NOTIFY_INSERT)
        // Changed, then deleted before the sync: only deleted.
        queue.add(listOf("content://media/external/images/media/7"), MediaChange.NOTIFY_UPDATE)
        queue.add(listOf("content://media/external/images/media/7"), MediaChange.NOTIFY_DELETE)
        val batch = queue.drain()
        assertEquals(setOf(5L, 7L), batch.deletedListedIds)
        assertEquals(setOf(6L), batch.addedListedIds)
        assertEquals(emptySet<Long>(), batch.fileOnlyIds)
    }

    @Test
    fun anInexactOrOverflowingQueueIsUnknown() {
        val queue = MediaChangeQueue(max = 2)
        queue.add(listOf("content://media/external/images/media"), MediaChange.NOTIFY_UPDATE)
        assertTrue(queue.drain().unknown)
        queue.add(listOf("content://media/external/images/media/1"), 0)
        assertTrue(queue.drain().unknown)
        queue.markUnknown()
        assertTrue(queue.drain().unknown)
        // A whole volume or collection named, no row: everything is checked.
        queue.add(listOf("content://media/external_primary"), MediaChange.NOTIFY_UPDATE)
        assertTrue(queue.drain().unknown)
        queue.add(listOf("content://media/external/file"), MediaChange.NOTIFY_UPDATE)
        assertTrue(queue.drain().unknown)
        queue.add((1..3).map { "content://media/external/file/$it" }, MediaChange.NOTIFY_INSERT)
        val overflow = queue.drain()
        assertTrue(overflow.unknown)
        assertEquals(2, overflow.changes.size)
    }

    @Test
    fun summaryCountsKindsCollectionsAndIds() {
        val changes = listOf(
            MediaChange.parse("content://media/external/images/media/1", MediaChange.NOTIFY_INSERT),
            MediaChange.parse("content://media/external/file/1", MediaChange.NOTIFY_INSERT),
            MediaChange.parse("content://media/external/file", MediaChange.NOTIFY_INSERT),
            null,
        )
        val line = MediaChange.summary(changes)
        assertTrue(line, line.startsWith("4 uri(s): insert=3; images=1 files=2; 2 with id; 1 not MediaStore"))
    }
}
