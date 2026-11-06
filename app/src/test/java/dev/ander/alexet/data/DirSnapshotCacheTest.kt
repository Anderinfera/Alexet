package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DirSnapshotCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dates = HashMap<String, Long>()
    private var clock = 0L
    private var reads = 0

    private fun cache(maxAgeMs: Long = Long.MAX_VALUE) = DirSnapshotCache(
        read = { dir -> reads++; "listing ${dir.name} #$reads" },
        maxAgeMs = maxAgeMs,
        dateOf = { dir -> dates[dir.path] },
        now = { clock },
    )

    private val dir = File("/storage/emulated/0/Gallery/Anime")

    @Test
    fun aFolderWithTheSameDateIsNotListedAgain() {
        dates[dir.path] = 100L
        val cache = cache()
        assertEquals("listing Anime #1", cache.get(dir))
        assertEquals("listing Anime #1", cache.get(dir))
        assertEquals(1, reads)
        // A file came or went: the date moved.
        dates[dir.path] = 101L
        assertEquals("listing Anime #2", cache.get(dir))
        assertEquals(2, reads)
    }

    @Test
    fun forceAgeAndInvalidateListAgain() {
        dates[dir.path] = 100L
        val cache = cache(maxAgeMs = 600_000L)
        cache.get(dir)
        cache.get(dir, force = true)
        assertEquals(2, reads)
        clock += 600_000L
        cache.get(dir)
        assertEquals(3, reads)
        cache.invalidate(dir)
        cache.get(dir)
        assertEquals(4, reads)
        cache.get(dir)
        assertEquals(4, reads)
    }

    @Test
    fun aFolderThatIsGoneOrMayNotBeReadGivesNothing() {
        val cache = cache()
        assertNull(cache.get(dir))
        dates[dir.path] = 5L
        assertNull(cache.get(dir, mayRead = { false }))
        assertEquals(0, reads)
        assertNotNull(cache.get(dir))
        // Gone: dropped, and listed anew when it comes back with the same date.
        dates.remove(dir.path)
        assertNull(cache.get(dir))
        assertEquals(0, cache.size)
        dates[dir.path] = 5L
        cache.get(dir)
        assertEquals(2, reads)
    }

    @Test
    fun aDateFreshAtTheListingIsListedAgain() {
        val wall = 1_800_000_000_000L
        val cache = DirSnapshotCache(
            read = { dir -> reads++; dir.name },
            dateOf = { d -> dates[d.path] },
            now = { clock },
            wallNow = { wall },
        )
        // Changed a second before the listing: listed again, then trusted.
        dates[dir.path] = (wall - 1_000L) * 1_000_000L
        cache.get(dir)
        cache.get(dir)
        assertEquals(2, reads)
        dates[dir.path] = (wall - 10_000L) * 1_000_000L
        cache.get(dir)
        cache.get(dir)
        assertEquals(3, reads)
    }

    @Test
    fun entriesAndDatesOfRealFiles() {
        val root = temp.newFolder("Hidden")
        File(root, "a.jpg").writeBytes(ByteArray(10))
        File(root, "sub").mkdir()
        val before = DirSnapshotCache.folderDate(root)
        assertNotNull(before)
        assertNull(DirSnapshotCache.folderDate(File(root, "a.jpg")))
        val file = DirSnapshotCache.entryOf(root, "a.jpg")!!
        assertTrue(file.isFile)
        assertFalse(file.isDirectory)
        assertEquals(10L, file.size)
        assertTrue(DirSnapshotCache.entryOf(root, "sub")!!.isDirectory)
        assertNull(DirSnapshotCache.entryOf(root, "missing.jpg"))
    }
}
