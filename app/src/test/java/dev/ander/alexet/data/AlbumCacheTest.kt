package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumCacheTest {

    @Test
    fun encodeDecodeRoundTripPreservesLegacyBucketIds() {
        val original = listOf(
            AlbumCache.SnapshotAlbum(
                id = 10L,
                name = "Camera",
                itemCount = 42,
                relativePath = "DCIM/Camera/",
                isHidden = false,
                latestAddedMs = 123456789L,
                coverUri = "content://media/external/images/media/1",
                coverId = 1L,
                coverMimeType = "image/jpeg",
                coverDisplayName = "IMG_001.jpg",
                coverDateModifiedMs = 1720000000000L,
                legacyBucketIds = setOf(100L, 200L, 300L),
            ),
            AlbumCache.SnapshotAlbum(
                id = 20L,
                name = "Download",
                itemCount = 5,
                relativePath = "Download/",
                isHidden = false,
                latestAddedMs = 987654321L,
                coverUri = null,
                coverId = 0L,
                coverMimeType = "image/*",
                coverDisplayName = "",
                coverDateModifiedMs = 0L,
                legacyBucketIds = setOf(555L),
            ),
        )

        val json = AlbumCache.encode(original, savedAt = 1000L)
        assertTrue("JSON must specify schemaVersion 2", json.contains("\"schemaVersion\":2"))
        assertTrue("JSON must contain legacyBucketIds", json.contains("\"legacyBucketIds\":[100,200,300]"))

        val decoded = AlbumCache.decode(json)
        assertNotNull("Decoded list must not be null", decoded)
        assertEquals(2, decoded!!.size)

        val first = decoded[0]
        assertEquals(10L, first.id)
        assertEquals("Camera", first.name)
        assertEquals(42, first.itemCount)
        assertEquals("DCIM/Camera/", first.relativePath)
        assertEquals(1720000000000L, first.coverDateModifiedMs)
        assertEquals(setOf(100L, 200L, 300L), first.legacyBucketIds)

        val second = decoded[1]
        assertEquals(20L, second.id)
        assertEquals("Download", second.name)
        assertEquals(0L, second.coverDateModifiedMs)
        assertEquals(setOf(555L), second.legacyBucketIds)
    }

    @Test
    fun decodeSupportsSchemaVersion1BackwardCompatibility() {
        val schema1Json = """
            {
              "schemaVersion": 1,
              "savedAt": 12345,
              "albums": [
                {
                  "id": 10,
                  "name": "OldAlbum",
                  "itemCount": 3,
                  "relativePath": "Pictures/Old/",
                  "isHidden": false,
                  "latestAddedMs": 5000,
                  "coverUri": null,
                  "coverId": 0,
                  "coverMimeType": "image/*",
                  "coverDisplayName": ""
                }
              ]
            }
        """.trimIndent()

        val decoded = AlbumCache.decode(schema1Json)
        assertNotNull("Schema 1 should decode successfully", decoded)
        assertEquals(1, decoded!!.size)
        assertEquals(10L, decoded[0].id)
        assertEquals("OldAlbum", decoded[0].name)
        assertEquals("Pictures/Old/", decoded[0].relativePath)
        assertEquals("Legacy bucket IDs should be empty for schema 1", emptySet<Long>(), decoded[0].legacyBucketIds)
    }

    @Test
    fun decodeRejectsUnknownSchemaVersion() {
        val unknownJson = """{"schemaVersion":999,"savedAt":1,"albums":[]}"""
        assertNull("Unknown schema version must return null", AlbumCache.decode(unknownJson))
    }

    @Test
    fun emptyAlbumsEncodeDecode() {
        val json = AlbumCache.encode(emptyList(), savedAt = 123L)
        val decoded = AlbumCache.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded!!.isEmpty())
    }
}
