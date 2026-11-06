package dev.ander.alexet.data

import android.net.DummyUri
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class PreferencesTest {

    private val dummyUri: Uri = DummyUri()

    private fun createMedia(
        id: Long,
        name: String,
        path: String,
        size: Long,
        modified: Long,
        taken: Long,
    ) = GalleryMedia(
        id = id,
        uri = dummyUri,
        displayName = name,
        mimeType = "image/jpeg",
        dateTakenMs = taken,
        dateModifiedMs = modified,
        dateAddedMs = modified,
        sizeBytes = size,
        width = 100,
        height = 100,
        durationMs = 0L,
        bucketId = 1L,
        bucketName = "TestBucket",
        relativePath = path,
    )

    @Test
    fun testSortMediaByNameAscendingAndDescending() {
        val a = createMedia(1, "Alpha.jpg", "Pictures/", 100, 1000, 1000)
        val b = createMedia(2, "beta.jpg", "Pictures/", 200, 2000, 2000)
        val c = createMedia(3, "Gamma.jpg", "Pictures/", 300, 3000, 3000)
        val list = listOf(c, a, b)

        val asc = sortMedia(list, SortSpec(SortField.NAME, SortOrder.ASCENDING), randomSeed = 1L)
        assertEquals(listOf(1L, 2L, 3L), asc.map { it.id })

        val desc = sortMedia(list, SortSpec(SortField.NAME, SortOrder.DESCENDING), randomSeed = 1L)
        assertEquals(listOf(3L, 2L, 1L), desc.map { it.id })
    }

    @Test
    fun testSortMediaBySize() {
        val a = createMedia(1, "A.jpg", "Pictures/", 500, 1000, 1000)
        val b = createMedia(2, "B.jpg", "Pictures/", 100, 2000, 2000)
        val c = createMedia(3, "C.jpg", "Pictures/", 900, 3000, 3000)
        val list = listOf(a, b, c)

        val asc = sortMedia(list, SortSpec(SortField.SIZE, SortOrder.ASCENDING), randomSeed = 1L)
        assertEquals(listOf(2L, 1L, 3L), asc.map { it.id })

        val desc = sortMedia(list, SortSpec(SortField.SIZE, SortOrder.DESCENDING), randomSeed = 1L)
        assertEquals(listOf(3L, 1L, 2L), desc.map { it.id })
    }

    @Test
    fun testSortMediaByDateTaken() {
        val a = createMedia(1, "A.jpg", "Pictures/", 100, 1000, 3000)
        val b = createMedia(2, "B.jpg", "Pictures/", 100, 2000, 1000)
        val c = createMedia(3, "C.jpg", "Pictures/", 100, 3000, 2000)
        val list = listOf(a, b, c)

        val asc = sortMedia(list, SortSpec(SortField.DATE_TAKEN, SortOrder.ASCENDING), randomSeed = 1L)
        assertEquals(listOf(2L, 3L, 1L), asc.map { it.id })

        val desc = sortMedia(list, SortSpec(SortField.DATE_TAKEN, SortOrder.DESCENDING), randomSeed = 1L)
        assertEquals(listOf(1L, 3L, 2L), desc.map { it.id })
    }

    @Test
    fun testSortMediaRandomDeterministicWithSeed() {
        val items = (1L..20L).map { createMedia(it, "item$it.jpg", "Pictures/", it * 10, it * 100, it * 100) }
        val s1 = sortMedia(items, SortSpec(SortField.RANDOM, SortOrder.ASCENDING), randomSeed = 42L)
        val s2 = sortMedia(items, SortSpec(SortField.RANDOM, SortOrder.ASCENDING), randomSeed = 42L)
        assertEquals(s1.map { it.id }, s2.map { it.id })
    }

    @Test
    fun testApplyAlbumOrder() {
        val a1 = Album(id = 10, name = "A", cover = null, itemCount = 1, relativePath = "")
        val a2 = Album(id = 20, name = "B", cover = null, itemCount = 2, relativePath = "")
        val a3 = Album(id = 30, name = "C", cover = null, itemCount = 3, relativePath = "")
        val albums = listOf(a1, a2, a3)

        val ordered = applyAlbumOrder(albums, listOf(30L, 10L, 20L))
        assertEquals(listOf(30L, 10L, 20L), ordered.map { it.id })

        val partialOrder = applyAlbumOrder(albums, listOf(20L))
        assertEquals(listOf(20L, 10L, 30L), partialOrder.map { it.id })
    }

    @Test
    fun testRemapAlbumIdUsesLegacyBucketIds() {
        val albums = listOf(
            Album(id = 999L, name = "Camera", cover = null, itemCount = 1, relativePath = "DCIM/Camera/", legacyBucketIds = setOf(42L)),
            Album(id = 1000L, name = "Download", cover = null, itemCount = 1, relativePath = "Download/"),
        )
        assertEquals(999L, remapAlbumId(42L, albums))
        assertEquals(999L, remapAlbumId(999L, albums))
        assertEquals(7L, remapAlbumId(7L, albums))
        assertEquals(listOf(999L, 1000L), remapAlbumIds(listOf(42L, 1000L, 999L), albums))
    }

    @Test
    fun remapHiddenSortAndScrollUseLegacyBucketIds() {
        val albums = listOf(
            Album(id = 999L, name = "Camera", cover = null, itemCount = 1, relativePath = "DCIM/Camera/", legacyBucketIds = setOf(42L)),
        )
        assertEquals(setOf(999L), remapHiddenAlbumIds(setOf(42L), albums))
        val sorts = remapFolderSorts(
            mapOf(42L to SortSpec(SortField.NAME, SortOrder.ASCENDING)),
            albums,
        )
        assertEquals(SortField.NAME, sorts[999L]?.field)
        val scrolls = remapFolderScrolls(
            mapOf(42L to FolderScrollPos(3, 10)),
            albums,
        )
        assertEquals(3, scrolls[999L]?.index)
        val settings = AppSettings(
            hiddenAlbumIds = setOf(42L),
            folderSorts = mapOf(42L to SortSpec(SortField.SIZE, SortOrder.ASCENDING)),
            savedFolderScrolls = mapOf(42L to FolderScrollPos(4, 0)),
        )
        assertTrue(settings.isAlbumHidden(999L, setOf(42L)))
        assertTrue(settings.hasFolderSort(999L, setOf(42L)))
        assertEquals(SortField.SIZE, settings.sortFor(999L, setOf(42L)).field)
        assertEquals(4, settings.scrollFor(999L, setOf(42L))?.index)
        assertEquals(setOf(999L, 42L), albumIdsIncludingLegacy(albums))
    }

    @Test
    fun testActiveBarActionsOrder() {
        val settings = AppSettings(
            barActionSlots = listOf(
                SelectionAction.DELETE,
                SelectionAction.SHARE,
                SelectionAction.INFO,
                SelectionAction.RENAME,
                SelectionAction.EDIT,
                SelectionAction.MOVE,
            ),
        )

        // Folded / Phone (takes 4 main slots)
        val phoneActions = settings.activeBarActions(wide = false)
        assertEquals(
            listOf(SelectionAction.DELETE, SelectionAction.SHARE, SelectionAction.INFO, SelectionAction.RENAME),
            phoneActions,
        )

        // Tablet / Unfolded (2 extra slots placed on the LEFT of the 4 main slots)
        val tabletActions = settings.activeBarActions(wide = true)
        assertEquals(
            listOf(
                SelectionAction.EDIT,
                SelectionAction.MOVE,
                SelectionAction.DELETE,
                SelectionAction.SHARE,
                SelectionAction.INFO,
                SelectionAction.RENAME,
            ),
            tabletActions,
        )
    }

    @Test
    fun testAlphanumericComparatorNaturalOrder() {
        val names = listOf("file10.jpg", "file1.jpg", "file2.jpg", "file20.jpg")
        val sorted = names.sortedWith(AlphanumericComparator)
        assertEquals(listOf("file1.jpg", "file2.jpg", "file10.jpg", "file20.jpg"), sorted)
    }

    @Test
    fun testAlphanumericComparatorLeadingZerosWithSubsequentText() {
        // file1_a should come before file01_b because 'a' < 'b', even though file01 has a leading zero
        val names = listOf("file01_b.jpg", "file1_a.jpg", "file001_c.jpg")
        val sorted = names.sortedWith(AlphanumericComparator)
        assertEquals(listOf("file1_a.jpg", "file01_b.jpg", "file001_c.jpg"), sorted)

        // When identical except for leading zeros, fewer zeros comes first
        val identicalWithZeros = listOf("img001.png", "img1.png", "img01.png")
        val sortedZeros = identicalWithZeros.sortedWith(AlphanumericComparator)
        assertEquals(listOf("img1.png", "img01.png", "img001.png"), sortedZeros)
    }

    @Test
    fun testAlphanumericComparatorTildeSuffixOrdering() {
        val names = listOf(
            "002.png",
            "001.png",
            "001~2.png",
            "002~2.png",
            "002~1.png",
            "001~1.png",
            "003.png",
        )
        val sorted = names.sortedWith(AlphanumericComparator)
        // Primary key: tilde layer descending (~2, then ~1, then no tilde)
        // Secondary key: base number ascending
        // 001~2 -> 002~2 -> 001~1 -> 002~1 -> 001 -> 002 -> 003
        assertEquals(
            listOf("001~2.png", "002~2.png", "001~1.png", "002~1.png", "001.png", "002.png", "003.png"),
            sorted,
        )
    }

    @Test
    fun testAppSettingsNewDefaults() {
        val settings = AppSettings()
        assertEquals(false, settings.showNavArrows)
        assertEquals(emptySet<Long>(), settings.anonymousFolderIds)
        assertEquals(false, settings.stripExifOnShare)
    }

    @Test
    fun testGenerateAnonymousFileName() {
        val anonJpg = ExifUtils.generateAnonymousFileName("my_vacation_photo.jpg")
        assertTrue(anonJpg.startsWith("anon_"))
        assertTrue(anonJpg.endsWith(".jpg"))
        assertEquals(17, anonJpg.length) // "anon_" (5) + 8 hex + ".jpg" (4) = 17

        val anonNoExt = ExifUtils.generateAnonymousFileName("photo_without_extension")
        assertTrue(anonNoExt.startsWith("anon_"))
        assertEquals(13, anonNoExt.length) // "anon_" (5) + 8 hex = 13
    }
}
