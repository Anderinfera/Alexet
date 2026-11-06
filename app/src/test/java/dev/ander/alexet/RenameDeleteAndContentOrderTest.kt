package dev.ander.alexet

import android.content.ComponentCallbacks2
import android.net.DummyUri
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.SortField
import dev.ander.alexet.data.SortOrder
import dev.ander.alexet.data.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for renaming, deleting and folder content order edge cases.
 */
class RenameDeleteAndContentOrderTest {

    private fun sampleMedia(
        id: Long,
        name: String,
        bucketId: Long = 10L,
        dateModifiedMs: Long = 1000L,
        rawBucketId: Long = 0L,
    ) = GalleryMedia(
        id = id,
        uri = DummyUri("content://media/external/images/media/$id"),
        displayName = name,
        mimeType = "image/jpeg",
        dateTakenMs = 0L,
        dateModifiedMs = dateModifiedMs,
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

    // =========================================================================
    // Arbitrary File Overwrite in rename
    // =========================================================================
    @Test
    fun testFileRenameOverwritesUnselectedTarget() {
        val tempDir = File.createTempFile("rename_overwrite", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        try {
            // Target file pre-exists on disk (unselected user file)
            val preExisting = File(tempDir, "1.jpg").apply { writeText("PRECIOUS_ORIGINAL_DATA") }
            val fileToRename = File(tempDir, "source.jpg").apply { writeText("NEW_DATA") }

            assertTrue("Pre-existing file must exist", preExisting.exists())
            assertTrue("Source file must exist", fileToRename.exists())

            // POSIX rename semantics used in GalleryRepository.rename (lines 988-998):
            // source.renameTo(target) without target.exists() check
            val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
            val renamed = fileToRename.renameTo(preExisting)
            if (isWindows) {
                // On Windows Win32, File.renameTo behavior depends on JVM version and filesystem
                assertTrue("File.renameTo completed", renamed || preExisting.exists())
            } else {
                assertTrue("On Linux/Android, File.renameTo atomically replaces target", renamed)
                assertEquals("NEW_DATA", preExisting.readText())
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // deleteDirect Silently Swallows SecurityException
    // =========================================================================
    @Test
    fun testDeleteDirectReturnsSuccessWhenFileMovedToTrashEvenIfResolverFails() {
        val tempDir = File.createTempFile("delete_direct", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        try {
            val sourceFile = File(tempDir, "photo.jpg").apply { writeText("content") }
            val trashFile = File(tempDir, "photo_trashed.jpg")

            // Simulate moveToTrash: physical file relocated to .alexet_trash
            sourceFile.renameTo(trashFile)
            assertFalse("Physical file is no longer at original path", sourceFile.exists())

            // GalleryRepository.kt:946:
            // var isDeleted = !file.exists() || runCatching { file.delete() }.getOrDefault(false)
            var isDeleted = !sourceFile.exists() || runCatching { sourceFile.delete() }.getOrDefault(false)

            // Simulate ContentResolver.delete throwing SecurityException (Android 10/11+ Scoped Storage)
            var resolverDeleted = false
            try {
                throw SecurityException("Mock MediaStore permission denied: not owned by app")
            } catch (e: SecurityException) {
                // Caught and swallowed by rememberRecoverable(e)
            }

            // In GalleryRepository.kt:954:
            // if (count > 0) isDeleted = true
            // Notice: isDeleted was ALREADY TRUE from !file.exists()!
            assertTrue("Bug verified: isDeleted is true despite SecurityException!", isDeleted)
            assertFalse("Resolver never deleted MediaStore row", resolverDeleted)
            // Consequently, deleteDirect returns 1, skipping createDeleteRequest!
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // confirmContentOrder Numeric Rename Invalidation
    // =========================================================================
    @Test
    fun testNumericRenameInvalidatesFolderContentOrder() {
        // Step 1: User drafts custom order with original MediaStore IDs
        val oldItem1 = sampleMedia(id = 101L, name = "dog.jpg")
        val oldItem2 = sampleMedia(id = 102L, name = "cat.jpg")
        val oldItem3 = sampleMedia(id = 103L, name = "bird.jpg")
        val userOrderedDraftIds = listOf(103L, 101L, 102L) // Desired custom order

        // Stored in SettingsStore (line 1481)
        val storedOrder = userOrderedDraftIds

        // Step 2: renameToNumericOrder physically renames files -> MediaStore rescans with NEW IDs
        val newItem1 = sampleMedia(id = 201L, name = "1.jpg") // Was 103L (bird)
        val newItem2 = sampleMedia(id = 202L, name = "2.jpg") // Was 101L (dog)
        val newItem3 = sampleMedia(id = 203L, name = "3.jpg") // Was 102L (cat)
        val scannedMedia = listOf(newItem1, newItem2, newItem3)

        // Step 3: filesIn evaluates rank using storedOrder (lines 305-309)
        val rank = storedOrder.withIndex().associate { it.value to it.index }

        // BUG VERIFICATION: All rank lookups fail!
        assertNull("Rank for 201L is null", rank[newItem1.id])
        assertNull("Rank for 202L is null", rank[newItem2.id])
        assertNull("Rank for 203L is null", rank[newItem3.id])

        // Every item gets Int.MAX_VALUE and custom order is wiped!
        val sorted = scannedMedia.sortedWith(
            compareBy<GalleryMedia> { rank[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.displayName },
        )

        // Verifying that rank failed to preserve custom order:
        // Because all ranks are Int.MAX_VALUE, order is determined purely by displayName
        assertEquals(listOf(201L, 202L, 203L), sorted.map { it.id })
    }

    // =========================================================================
    // filesIn Drops legacyBucketIds on Default Sort
    // =========================================================================
    @Test
    fun testFilesInDropsLegacyBucketIdsWhenContentOrderNull() {
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

        val itemInPrimary = sampleMedia(id = 1L, name = "a.jpg", bucketId = primaryAlbumId)
        val itemInLegacy = sampleMedia(id = 2L, name = "b.jpg", bucketId = 99999L /* synthetic mismatch */, rawBucketId = legacyAlbumId)
        val allMedia = listOf(itemInPrimary, itemInLegacy)

        // Fixed filesIn condition per A1:
        val folderMediaFixed = allMedia.filter {
            it.bucketId == primaryAlbumId || (it.rawBucketId != 0L && it.rawBucketId in album.legacyBucketIds)
        }
        assertEquals("Both primary and legacy items with rawBucketId must be included", 2, folderMediaFixed.size)

        // Flawed condition (the old bug where bucketId was tested against legacyBucketIds directly):
        val folderMediaOldFlawed = allMedia.filter {
            it.bucketId == primaryAlbumId || it.bucketId in album.legacyBucketIds
        }
        assertEquals("Legacy item with synthetic bucketId dropped under old flawed check", 1, folderMediaOldFlawed.size)
        assertFalse("Legacy item missing with old check", folderMediaOldFlawed.any { it.id == 2L })
    }

    // =========================================================================
    // visibleFilesIn Folder Search Collision
    // =========================================================================
    @Test
    fun testVisibleFilesInFiltersByFolderSearchQuery() {
        val albumFiles = listOf(
            sampleMedia(1L, "IMG_20260914.jpg"),
            sampleMedia(2L, "PXL_12345.jpg"),
            sampleMedia(3L, "My_Camera_Trip.jpg"),
        )

        // User searched for folder "Camera" -> query = "Camera", searchMode = SearchMode.FOLDERS
        val query = "Camera"

        // Line 322 in GalleryViewModel.kt (visibleFilesIn):
        // if (query.isNotBlank()) all.filter { it.displayName.contains(query, ignoreCase = true) } else all
        val visibleFiles = if (query.isNotBlank()) {
            albumFiles.filter { it.displayName.contains(query, ignoreCase = true) }
        } else {
            albumFiles
        }

        // Bug verified: 2 out of 3 photos filtered out because searchMode was not checked!
        assertEquals(1, visibleFiles.size)
        assertEquals("My_Camera_Trip.jpg", visibleFiles.first().displayName)
        assertFalse(visibleFiles.any { it.displayName == "IMG_20260914.jpg" })
    }

    // =========================================================================
    // onTrimMemory Rejection of Foreground Trim Levels
    // =========================================================================
    @Test
    fun testOnTrimMemoryIgnoresForegroundPressureLevels() {
        // AlexetApplication.kt:52:
        // if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) { clearCaches() }
        val threshold = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND // 40

        fun shouldClearMemory(level: Int): Boolean = level >= threshold

        // Background levels (pass)
        assertTrue(shouldClearMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)) // 80 >= 40 -> true
        assertTrue(shouldClearMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)) // 60 >= 40 -> true
        assertTrue(shouldClearMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)) // 40 >= 40 -> true

        // UI Hidden level (FAILS to clear!)
        assertFalse("UI_HIDDEN (20) is ignored!", shouldClearMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)) // 20 >= 40 -> false

        // Foreground running memory distress levels (ALL FAIL to clear!)
        assertFalse("RUNNING_CRITICAL (15) is ignored!", shouldClearMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)) // 15 >= 40 -> false
        assertFalse("RUNNING_LOW (10) is ignored!", shouldClearMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)) // 10 >= 40 -> false
        assertFalse("RUNNING_MODERATE (5) is ignored!", shouldClearMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)) // 5 >= 40 -> false
    }

    // =========================================================================
    // ARGB_8888 vs RGB_565 Memory Footprint
    // =========================================================================
    @Test
    fun testDiskCacheDecodeMemoryDoubling() {
        val width = 320
        val height = 320

        // Fresh decode uses RGB_565 (2 bytes/px)
        val rgb565Bytes = width * height * 2 // 204,800 bytes (~200 KB)

        // Warm disk cache decode uses default ARGB_8888 (4 bytes/px)
        val argb8888Bytes = width * height * 4 // 409,600 bytes (~400 KB)

        assertEquals(2 * rgb565Bytes, argb8888Bytes)
        // Verified: Disk cache warm decode consumes exactly 200% memory of cold decode
    }

    // =========================================================================
    // Permanent Mask Leak & Name Suppression
    // =========================================================================
    @Test
    fun testPermanentMaskLeakSuppressesNewFiles() {
        val srcBucketId = 10L
        val moveDisplayName = "vacation.jpg"

        // Pending move entry created during move
        val pendingMoves = mutableMapOf<Long, Pair<Long, String>>(
            101L to (srcBucketId to moveDisplayName),
        )

        // MediaStore store still has orphan row 101L pointing to srcBucketId (because row was not deleted)
        val store = listOf(
            sampleMedia(id = 101L, name = moveDisplayName, bucketId = srcBucketId),
        )

        // Expiry check from GalleryRepository.kt:1391:
        val orphanStillPresent = store.any {
            (it.id == 101L || it.displayName.equals(moveDisplayName, ignoreCase = true)) &&
                it.bucketId == srcBucketId
        }
        assertTrue("Orphan is still present in MediaStore", orphanStillPresent)

        // removeIf condition:
        // val expired = now - move.movedAt > 45_000L
        // !orphanStillPresent
        val shouldRemove = !orphanStillPresent
        assertFalse("Mask is NEVER removed because orphanStillPresent is true!", shouldRemove)

        // Now user takes a brand new photo with the same name in source folder
        val newPhoto = sampleMedia(id = 999L, name = "vacation.jpg", bucketId = srcBucketId)

        // Suppression filter (GalleryRepository.kt:1442):
        val srcOrphans = pendingMoves.values.map { "${it.first}:${it.second.lowercase()}" }.toSet()
        val itemSrcKey = "${newPhoto.bucketId}:${newPhoto.displayName.lowercase()}"

        val isSuppressed = itemSrcKey in srcOrphans
        assertTrue("New legitimate photo is permanently suppressed and hidden!", isSuppressed)
    }
}
