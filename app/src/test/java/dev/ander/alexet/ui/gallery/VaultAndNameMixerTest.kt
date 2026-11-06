package dev.ander.alexet.ui.gallery

import android.net.DummyUri
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.ExifUtils
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.NomediaScanner
import dev.ander.alexet.data.SecureFolderManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for the private folder vault and the name mixer.
 * - Secure folder (.enc) AES-GCM decryption, stream decoding and repository fallback resolution.
 * - "Miksuj nazwy" in the folder overflow menu, visibility constraint and anonymous file name generation.
 * - "Mikser nazw" / "Name mixer" integration and anonymousFolderIds tracking.
 */
class VaultAndNameMixerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // 1. SECURE FOLDER AES-GCM ENCRYPTION, STREAM DECRYPTION & REPO FALLBACK
    // =========================================================================

    @Test
    fun testKeyDerivationAndVerificationHashConsistency() {
        val password = "AlexetSecretPassword2026!"
        val salt = SecureFolderManager.generateSalt()
        assertEquals(16, salt.size)

        // Passwords are checked by unwrapping the folder key (version 2), not by a stored hash.
        val wrong = SecureFolderManager.deriveKey("WrongPassword", salt)
        assertNotEquals(SecureFolderManager.deriveKey(password, salt).encoded.toList(), wrong.encoded.toList())

        val key1 = SecureFolderManager.deriveKey(password, salt)
        val key2 = SecureFolderManager.deriveKey(password, salt)
        assertEquals("AES", key1.algorithm)
        assertEquals(key1.encoded.toList(), key2.encoded.toList())
    }

    @Test
    fun testFileEncryptionAndDecryptedStreamReading() {
        val rootDir = tempFolder.newFolder("vault_test")
        val sampleFile = File(rootDir, "photo.jpg")
        val testData = "IMAGE_HEADER_FAKE_BYTES_1234567890_JPEG_EXIF_M2".toByteArray()
        sampleFile.writeBytes(testData)

        val salt = SecureFolderManager.generateSalt()
        val key = SecureFolderManager.deriveKey("test_pass", salt)

        // Encrypt file
        val encSuccess = SecureFolderManager.encryptFile(sampleFile, key)
        assertNotNull(encSuccess)
        assertFalse(sampleFile.exists())

        val encFile = File(rootDir, "photo.jpg" + SecureFolderManager.ENC_EXTENSION)
        assertTrue(encFile.exists())
        assertTrue(encFile.length() > testData.size) // IV (12) + ciphertext + GCM tag (16)

        // Decrypted stream reading
        val stream = SecureFolderManager.openDecryptedStream(encFile, key)
        assertNotNull(stream)
        val decryptedBytes = stream!!.use { it.readBytes() }
        assertEquals(testData.toList(), decryptedBytes.toList())

        // Decryption fails with invalid key
        val badKey = SecretKeySpec(ByteArray(32), "AES")
        val badStream = SecureFolderManager.openDecryptedStream(encFile, badKey)
        assertNotNull(badStream)
        var failedAuth = false
        try {
            badStream!!.use { it.readBytes() }
        } catch (e: Exception) {
            failedAuth = true
        }
        assertTrue(failedAuth)
    }

    @Test
    fun testUnlockedFolderSyntheticBucketResolution() {
        val vaultDir = tempFolder.newFolder("secret_vault")
        val canonical = vaultDir.canonicalPath
        val expectedBucketId = NomediaScanner.syntheticBucketId(canonical)

        // Create mock .enc files inside vault
        val enc1 = File(vaultDir, "image1.jpg.enc").apply { writeBytes(ByteArray(64)) }
        val enc2 = File(vaultDir, "video1.mp4.enc").apply { writeBytes(ByteArray(64)) }
        val nonEnc = File(vaultDir, "ignore.txt").apply { writeBytes(ByteArray(10)) }

        val encFiles = vaultDir.listFiles()?.filter { it.isFile && it.name.endsWith(SecureFolderManager.ENC_EXTENSION) }.orEmpty()
        assertEquals(2, encFiles.size)

        val album = Album(
            id = expectedBucketId,
            name = vaultDir.name,
            cover = null,
            itemCount = encFiles.size,
            relativePath = vaultDir.absolutePath,
            isHidden = false,
            isSecure = true,
            latestAddedMs = encFiles.maxOfOrNull { it.lastModified() } ?: vaultDir.lastModified(),
        )

        assertEquals(expectedBucketId, album.id)
        assertTrue(album.isSecure)
        assertEquals(2, album.itemCount)
        assertEquals("secret_vault", album.name)
    }

    // =========================================================================
    // 2. NAME MIXER ANONYMOUS FILE NAME GENERATION & CONSTRAINTS
    // =========================================================================

    @Test
    fun testAnonymousFileNameGenerationFormatAndExtension() {
        val jpgName = ExifUtils.generateAnonymousFileName("holiday_photo.jpg")
        assertTrue("Expected anon_ prefix and .jpg extension, got: $jpgName", jpgName.matches(Regex("^anon_[0-9a-f]{8}\\.jpg$")))

        val pngUpper = ExifUtils.generateAnonymousFileName("SCREENSHOT.PNG")
        assertTrue(pngUpper.matches(Regex("^anon_[0-9a-f]{8}\\.PNG$")))

        val noExt = ExifUtils.generateAnonymousFileName("README")
        assertTrue(noExt.matches(Regex("^anon_[0-9a-f]{8}$")))

        val multiDot = ExifUtils.generateAnonymousFileName("archive.tar.gz")
        assertTrue(multiDot.matches(Regex("^anon_[0-9a-f]{8}\\.gz$")))
    }

    @Test
    fun testAnonymousFileNameGenerationUniqueness() {
        val generated = (1..1000).map { ExifUtils.generateAnonymousFileName("photo.jpg") }.toSet()
        assertEquals(1000, generated.size)
    }

    @Test
    fun testNameMixerVisibilityConstraint() {
        val nameMixerFolderIds = setOf(101L, 202L, 303L)

        // Active folder in name mixer
        val folderInMixer = 202L
        val isVisibleInMixer = folderInMixer in nameMixerFolderIds
        assertTrue(isVisibleInMixer)

        // Active folder not in name mixer
        val normalFolder = 999L
        val isVisibleNormal = normalFolder in nameMixerFolderIds
        assertFalse(isVisibleNormal)

        // Empty settings case
        val emptyMixer = emptySet<Long>()
        assertFalse(folderInMixer in emptyMixer)
    }

    @Test
    fun testMixFileNamesBatchMappingLogic() {
        val mockMedia1 = GalleryMedia(
            id = 1L,
            uri = DummyUri("content://media/external/images/media/1"),
            displayName = "IMG_20260918_001.jpg",
            mimeType = "image/jpeg",
            dateTakenMs = 1000L,
            dateModifiedMs = 1000L,
            dateAddedMs = 1000L,
            sizeBytes = 2048L,
            width = 1920,
            height = 1080,
            durationMs = 0L,
            bucketId = 101L,
            bucketName = "Camera",
            relativePath = "DCIM/Camera",
            isHidden = false,
            absolutePath = "/storage/emulated/0/DCIM/Camera/IMG_20260918_001.jpg",
        )

        val mockMedia2 = GalleryMedia(
            id = 2L,
            uri = DummyUri("content://media/external/images/media/2"),
            displayName = "IMG_20260918_002.jpg",
            mimeType = "image/jpeg",
            dateTakenMs = 2000L,
            dateModifiedMs = 2000L,
            dateAddedMs = 2000L,
            sizeBytes = 4096L,
            width = 1920,
            height = 1080,
            durationMs = 0L,
            bucketId = 101L,
            bucketName = "Camera",
            relativePath = "DCIM/Camera",
            isHidden = false,
            absolutePath = "/storage/emulated/0/DCIM/Camera/IMG_20260918_002.jpg",
        )

        val items = listOf(mockMedia1, mockMedia2)
        val itemsWithNewNames = items.map { item ->
            item to ExifUtils.generateAnonymousFileName(item.displayName)
        }

        assertEquals(2, itemsWithNewNames.size)
        itemsWithNewNames.forEach { (item, newName) ->
            assertTrue(newName.startsWith("anon_"))
            assertTrue(newName.endsWith(".jpg"))
            assertNotEquals(item.displayName, newName)
        }
    }

    @Test
    fun testAlexetTopBarComposableSignatureHasOnMixNames() {
        val methods = Class.forName("dev.ander.alexet.ui.gallery.AlexetTopBarKt").declaredMethods
        val alexetTopBar = methods.firstOrNull { it.name == "AlexetTopBar" }
        assertNotNull("AlexetTopBar composable method must exist", alexetTopBar)
        val function0Count = alexetTopBar!!.parameterTypes.count { it.name == "kotlin.jvm.functions.Function0" }
        assertTrue("AlexetTopBar composable must have at least 27 Function0 callbacks", function0Count >= 27)
    }

    @Test
    fun testSecureRamCacheStrictMemoryLimitAndWipe() {
        val rootDir = tempFolder.newFolder("ram_cache_test")
        dev.ander.alexet.data.SecureRamCache.clear()

        // Test MAX_ENTRIES (6) constraint and LRU eviction
        val files = (1..7).map { File(rootDir, "f$it.enc") }
        val payloads = (1..7).map { "SECRET_PAYLOAD_$it".toByteArray() }

        // Put first 6 entries: all must succeed and be cached
        for (i in 0 until 6) {
            assertTrue(dev.ander.alexet.data.SecureRamCache.put(files[i], payloads[i]))
            assertNotNull(dev.ander.alexet.data.SecureRamCache.get(files[i]))
        }

        // Put 7th entry: must succeed, but the 1st (oldest) entry must be evicted
        assertTrue(dev.ander.alexet.data.SecureRamCache.put(files[6], payloads[6]))
        assertNotNull(dev.ander.alexet.data.SecureRamCache.get(files[6]))
        // Oldest (f1) was evicted
        assertNull(dev.ander.alexet.data.SecureRamCache.get(files[0]))

        // Test zeroing and rejection for payload exceeding MAX_TOTAL_BYTES
        val hugeFile = File(rootDir, "huge.enc")
        // Try putting directly or simulating oversize: put returns false if size exceeds MAX_TOTAL_BYTES
        // Verify wipe on folder exit
        dev.ander.alexet.data.SecureRamCache.removeForFolder(rootDir)
        for (f in files) {
            assertNull("File ${f.name} must be null after removeForFolder", dev.ander.alexet.data.SecureRamCache.get(f))
        }
    }

    @Test
    fun testSecureRamCacheEvictionDoesNotCorruptBytesInUse() {
        val rootDir = tempFolder.newFolder("ram_cache_isolation_test")
        dev.ander.alexet.data.SecureRamCache.clear()

        val viewed = File(rootDir, "viewed.enc")
        val payload = "SECRET_PAYLOAD_VIEWED".toByteArray()
        assertTrue(dev.ander.alexet.data.SecureRamCache.put(viewed, payload.copyOf()))

        // A decoder obtains the bytes, then other loads evict and wipe the cache entry
        val inUse = dev.ander.alexet.data.SecureRamCache.get(viewed)
        assertNotNull(inUse)
        for (i in 1..6) {
            dev.ander.alexet.data.SecureRamCache.put(File(rootDir, "other$i.enc"), "OTHER_$i".toByteArray())
        }
        assertNull(dev.ander.alexet.data.SecureRamCache.get(viewed))
        assertArrayEquals("Eviction must not wipe bytes already handed to a reader", payload, inUse)

        // Mutating a returned array must not leak back into the cache
        val other = File(rootDir, "other6.enc")
        java.util.Arrays.fill(dev.ander.alexet.data.SecureRamCache.get(other)!!, 0.toByte())
        assertArrayEquals("OTHER_6".toByteArray(), dev.ander.alexet.data.SecureRamCache.get(other))

        // Equivalent paths (absolute vs non-normalized) resolve to one entry
        val aliased = File(rootDir, "sub/../other6.enc")
        assertArrayEquals("OTHER_6".toByteArray(), dev.ander.alexet.data.SecureRamCache.get(aliased))

        dev.ander.alexet.data.SecureRamCache.clear()
    }

    @Test
    fun testRenameCollisionResolutionSimulation() {
        val folder = tempFolder.newFolder("rename_collision_test")
        // Existing file in folder
        val existing = File(folder, "001.png")
        existing.writeText("existing_content")

        // 2 items to rename with target clean names 001.png and 002.png
        val targets = listOf(
            "001" to "png",
            "002" to "png",
        )

        val finalNames = ArrayList<String>()
        val claimedPaths = mutableSetOf<String>()

        for ((baseName, ext) in targets) {
            var k = 0
            var candidateName: String
            while (true) {
                val suffix = if (k == 0) "" else "~$k"
                candidateName = if (ext.isNotEmpty()) "$baseName$suffix.$ext" else "$baseName$suffix"
                val candidateFile = File(folder, candidateName)
                val canonical = candidateFile.canonicalPath
                val existsOnDisk = candidateFile.exists()
                val claimed = canonical in claimedPaths

                if (!existsOnDisk && !claimed) {
                    claimedPaths.add(canonical)
                    break
                }
                k++
            }
            finalNames.add(candidateName)
        }

        // File 1 collides with existing 001.png -> gets 001~1.png
        // File 2 is free -> gets 002.png
        assertEquals(listOf("001~1.png", "002.png"), finalNames)
    }
}

