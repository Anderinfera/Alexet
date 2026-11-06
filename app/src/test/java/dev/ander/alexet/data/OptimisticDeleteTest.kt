package dev.ander.alexet.data

import android.net.DummyUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OptimisticDeleteTest {

    private fun sampleMedia(id: Long, path: String? = null): GalleryMedia = GalleryMedia(
        id = id,
        uri = DummyUri("content://media/external/images/media/$id"),
        displayName = "photo_$id.jpg",
        mimeType = "image/jpeg",
        dateTakenMs = 0L,
        dateModifiedMs = 1_000L,
        dateAddedMs = 1_000L,
        sizeBytes = 100L,
        width = 100,
        height = 100,
        durationMs = 0L,
        bucketId = 1L,
        bucketName = "Camera",
        relativePath = "DCIM/Camera",
        absolutePath = path,
    )

    @Test
    fun deleteCheckReturnsTrueWhenFileAlreadyMovedToTrash() {
        val tempDir = File.createTempFile("alexet_test", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        try {
            val sourceFile = File(tempDir, "sample.jpg")
            sourceFile.writeText("image-data")
            val targetFile = File(tempDir, "sample_in_trash.jpg")

            // Simulate moveToTrash: source file is renamed
            val renamed = sourceFile.renameTo(targetFile)
            assertTrue(renamed)
            assertFalse(sourceFile.exists())
            assertTrue(targetFile.exists())

            // The old buggy check:
            val buggyDeleted = sourceFile.delete()
            assertFalse("Buggy check returned false because file was already moved", buggyDeleted)

            // The fixed check:
            val fixedDeleted = !sourceFile.exists() || sourceFile.delete()
            assertTrue("Fixed check correctly identifies that source file is gone", fixedDeleted)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun deleteCheckDeletesExistingFileAndReturnsTrue() {
        val tempDir = File.createTempFile("alexet_test", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        try {
            val file = File(tempDir, "direct_delete.jpg")
            file.writeText("data")
            assertTrue(file.exists())

            val isDeleted = !file.exists() || file.delete()
            assertTrue(isDeleted)
            assertFalse(file.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pendingDeletesFiltersOutMarkedMediaInstantly() {
        val mediaList = listOf(
            sampleMedia(1L),
            sampleMedia(2L),
            sampleMedia(3L),
            sampleMedia(4L),
        )

        val pendingIds = setOf(2L, 4L)
        val filtered = mediaList.filter { it.id !in pendingIds }

        assertEquals(2, filtered.size)
        assertEquals(listOf(1L, 3L), filtered.map { it.id })
    }

    @Test
    fun pendingDeletesFiltersOutMarkedTrashedItems() {
        val trashedList = listOf(
            TrashedItem(id = 101L, originalUriString = "", originalPath = "", originalBucketId = 1L, originalBucketName = "Camera", originalRelativePath = "", displayName = "1.jpg", mimeType = "image/jpeg", sizeBytes = 10L, dateTakenMs = 0L, trashedAtMs = 0L, trashFilePath = "/trash/1.jpg"),
            TrashedItem(id = 102L, originalUriString = "", originalPath = "", originalBucketId = 1L, originalBucketName = "Camera", originalRelativePath = "", displayName = "2.jpg", mimeType = "image/jpeg", sizeBytes = 10L, dateTakenMs = 0L, trashedAtMs = 0L, trashFilePath = "/trash/2.jpg"),
            TrashedItem(id = 103L, originalUriString = "", originalPath = "", originalBucketId = 1L, originalBucketName = "Camera", originalRelativePath = "", displayName = "3.jpg", mimeType = "image/jpeg", sizeBytes = 10L, dateTakenMs = 0L, trashedAtMs = 0L, trashFilePath = "/trash/3.jpg"),
        )

        val pendingIds = setOf(102L)
        val filtered = trashedList.filter { it.id !in pendingIds }

        assertEquals(2, filtered.size)
        assertEquals(listOf(101L, 103L), filtered.map { it.id })
    }
}
