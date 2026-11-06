package dev.ander.alexet.data.smartsearch

import android.net.DummyUri
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.SmartIndexScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartIndexPlannerTest {

    private fun media(
        id: Long,
        name: String = "p$id.jpg",
        mime: String = "image/jpeg",
        dir: String = "DCIM/Camera",
        size: Long = 100L,
        modified: Long = 1_000L,
        taken: Long = id * 10,
        absolutePath: String? = null,
        isOtherFile: Boolean = false,
    ) = GalleryMedia(
        id = id,
        uri = DummyUri("content://media/external/images/media/$id"),
        displayName = name,
        mimeType = mime,
        dateTakenMs = taken,
        dateModifiedMs = modified,
        dateAddedMs = modified,
        sizeBytes = size,
        width = 100,
        height = 100,
        durationMs = 0L,
        bucketId = 10L,
        bucketName = dir.substringAfterLast('/'),
        relativePath = dir,
        absolutePath = absolutePath,
        isOtherFile = isOtherFile,
    )

    private val secure = SmartIndexPlanner.secureDirKeys(listOf("/storage/emulated/0/Private/Vault"))

    @Test
    fun onlyPhotosOutsidePrivateFoldersAreIndexed() {
        assertTrue(SmartIndexPlanner.isEligible(media(1), secure))
        assertFalse("video", SmartIndexPlanner.isEligible(media(2, "v.mp4", "video/mp4"), secure))
        assertFalse("other file", SmartIndexPlanner.isEligible(media(3, "a.pdf", "application/pdf", isOtherFile = true), secure))
        assertFalse(
            "encrypted",
            SmartIndexPlanner.isEligible(media(4, absolutePath = "/storage/emulated/0/Pictures/x.jpg.enc"), secure),
        )
        assertFalse("private folder", SmartIndexPlanner.isEligible(media(5, dir = "Private/Vault"), secure))
        assertFalse("inside a private folder", SmartIndexPlanner.isEligible(media(6, dir = "private/vault/sub"), secure))
        assertTrue("similar name", SmartIndexPlanner.isEligible(media(7, dir = "Private/Vault2"), secure))
        assertTrue("mime from the name", SmartIndexPlanner.isEligible(media(8, "x.png", mime = ""), secure))
    }

    @Test
    fun planIndexesNewAndChangedPhotosNewestFirstAndDropsGoneOnes() {
        val list = listOf(
            media(1, taken = 100),
            media(2, taken = 300),
            media(3, taken = 200, size = 555),
            media(4, "v.mp4", "video/mp4"),
        )
        val indexed = mapOf(
            1L to IndexedKey(100L, 1_000L),
            3L to IndexedKey(100L, 1_000L),
            9L to IndexedKey(100L, 1_000L),
        )
        val plan = SmartIndexPlanner.plan(list, indexed, secure)
        assertEquals(listOf(2L, 3L), plan.toIndex.map { it.id })
        assertEquals(listOf(9L), plan.toDelete)
    }

    private fun inFolder(id: Long, folder: Long) = media(id).copy(bucketId = folder)

    @Test
    fun folderScopes() {
        val photos = listOf(inFolder(1, 10L), inFolder(2, 20L), inFolder(3, 30L))
        fun planned(filter: SmartFolderFilter) =
            SmartIndexPlanner.plan(photos, emptyMap(), secure, filter).toIndex.map { it.id }.sorted()

        assertEquals(listOf(1L, 2L, 3L), planned(SmartFolderFilter.ALL))
        assertEquals(listOf(1L, 3L), planned(SmartFolderFilter(SmartIndexScope.ONLY_SELECTED, included = setOf(10L, 30L))))
        assertEquals(emptyList<Long>(), planned(SmartFolderFilter(SmartIndexScope.ONLY_SELECTED)))
        assertEquals(listOf(1L, 3L), planned(SmartFolderFilter(SmartIndexScope.ALL_EXCEPT, excluded = setOf(20L))))
        // The other scope's list does not count.
        assertEquals(listOf(1L, 2L, 3L), planned(SmartFolderFilter(SmartIndexScope.ALL_EXCEPT, included = setOf(10L))))
    }

    @Test
    fun folderLeavingTheSelectionLeavesTheIndex() {
        val photos = listOf(inFolder(1, 10L), inFolder(2, 20L))
        val indexed = mapOf(1L to IndexedKey(100L, 1_000L), 2L to IndexedKey(100L, 1_000L))
        val plan = SmartIndexPlanner.plan(photos, indexed, secure, SmartFolderFilter(SmartIndexScope.ALL_EXCEPT, excluded = setOf(20L)))
        assertTrue(plan.toIndex.isEmpty())
        assertEquals(listOf(2L), plan.toDelete)
    }

    @Test
    fun filterFollowsTheSettings() {
        val settings = dev.ander.alexet.data.AppSettings(
            smartIndexScope = SmartIndexScope.ONLY_SELECTED,
            smartIndexIncludedFolderIds = setOf(10L),
            smartIndexExcludedFolderIds = setOf(20L),
        )
        assertEquals(SmartFolderFilter(SmartIndexScope.ONLY_SELECTED, included = setOf(10L)), SmartFolderFilter.of(settings))
        assertEquals(SmartFolderFilter.ALL, SmartFolderFilter.of(settings.copy(smartIndexScope = SmartIndexScope.ALL)))
    }

    @Test
    fun photoMovedIntoAPrivateFolderLeavesTheIndex() {
        val plan = SmartIndexPlanner.plan(
            listOf(media(1, dir = "Private/Vault")),
            mapOf(1L to IndexedKey(100L, 1_000L)),
            secure,
        )
        assertTrue(plan.toIndex.isEmpty())
        assertEquals(listOf(1L), plan.toDelete)
    }
}
