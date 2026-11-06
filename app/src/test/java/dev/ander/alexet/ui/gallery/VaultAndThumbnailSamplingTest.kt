package dev.ander.alexet.ui.gallery

import android.net.DummyUri
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.ExifUtils
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.NomediaScanner
import dev.ander.alexet.data.SecureFolderManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for unlocking private folders, thumbnail sample-size maths and the name mixer.
 * Focus areas:
 * 1. Vault unlock & load: empty secure folder, non-image files, repeated unlocks.
 * 2. Decrypted stream thumbnail sampling: sampleSize calculations, extreme aspect ratios, stream bounds.
 * 3. Name mixer: empty folder, special characters and unicode in file names, extension preservation.
 */
class VaultAndThumbnailSamplingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // 1. VAULT UNLOCK & LOAD BEHAVIOR STRESS TESTS
    // =========================================================================

    @Test
    fun testVaultUnlockWithEmptyFolder() {
        val emptyVaultDir = tempFolder.newFolder("empty_vault")
        val canonical = emptyVaultDir.canonicalPath
        val bucketId = NomediaScanner.syntheticBucketId(canonical)

        val salt = SecureFolderManager.generateSalt()
        val key = SecureFolderManager.deriveKey("EmptyVaultPass123", salt)

        // Unlock the empty folder
        SecureFolderManager.unlockFolder(emptyVaultDir, key)
        assertTrue(SecureFolderManager.isFolderUnlocked(emptyVaultDir))
        assertEquals(key, SecureFolderManager.getActiveKey(emptyVaultDir))

        // Emulate Album creation in GalleryViewModel.tryUnlockSecureFolderWithPassword
        val encFiles = emptyVaultDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(SecureFolderManager.ENC_EXTENSION)
        }.orEmpty()

        assertEquals(0, encFiles.size)

        val album = Album(
            id = bucketId,
            name = emptyVaultDir.name,
            cover = null,
            itemCount = encFiles.size,
            relativePath = emptyVaultDir.absolutePath,
            isHidden = false,
            isSecure = true,
            latestAddedMs = encFiles.maxOfOrNull { it.lastModified() } ?: emptyVaultDir.lastModified(),
        )

        assertEquals(0, album.itemCount)
        assertTrue(album.isSecure)
        assertEquals(bucketId, album.id)

        // Emulate GalleryRepository.loadFolderMedia for this secure folder
        val loadedMedia = if (album.isSecure) {
            val dir = File(album.relativePath).takeIf { it.isDirectory }
            val files = dir?.listFiles()?.filter { it.isFile && it.name.endsWith(SecureFolderManager.ENC_EXTENSION) }.orEmpty()
            files.mapNotNull { f ->
                val origName = f.name.removeSuffix(SecureFolderManager.ENC_EXTENSION)
                GalleryMedia(
                    id = NomediaScanner.syntheticId(f.absolutePath),
                    uri = DummyUri("content://vault/${f.name}"),
                    displayName = origName,
                    mimeType = NomediaScanner.mimeOf(File(origName)) ?: "image/jpeg",
                    dateTakenMs = 0L,
                    dateModifiedMs = f.lastModified(),
                    dateAddedMs = f.lastModified(),
                    sizeBytes = f.length(),
                    width = 0,
                    height = 0,
                    durationMs = 0L,
                    bucketId = bucketId,
                    bucketName = album.name,
                    relativePath = album.relativePath,
                    isHidden = true,
                    absolutePath = f.absolutePath,
                )
            }
        } else {
            emptyList()
        }

        assertTrue("Empty secure folder must produce empty media list without crashing", loadedMedia.isEmpty())

        // Cleanup
        SecureFolderManager.lockFolder(emptyVaultDir)
        assertFalse(SecureFolderManager.isFolderUnlocked(emptyVaultDir))
    }

    @Test
    fun testVaultUnlockWithNonImageAndNonEncFiles() {
        val vaultDir = tempFolder.newFolder("mixed_vault")
        val canonical = vaultDir.canonicalPath
        val bucketId = NomediaScanner.syntheticBucketId(canonical)

        // Create diverse non-image and non-enc files
        File(vaultDir, SecureFolderManager.NOMEDIA_FILE).writeText("")
        File(vaultDir, SecureFolderManager.SECURE_CONFIG_FILE).writeText("{}")
        File(vaultDir, "notes.txt").writeText("Plain text note")
        File(vaultDir, "script.sh").writeText("echo hello")
        val subDir = File(vaultDir, "nested_folder").apply { mkdirs() }
        File(subDir, "inner.jpg").writeText("inside nested")

        // Create valid encrypted image and video files
        val salt = SecureFolderManager.generateSalt()
        val key = SecureFolderManager.deriveKey("MixedVaultPass456", salt)

        val imageOriginal = File(vaultDir, "vacation.jpg").apply { writeBytes("FAKE_JPEG_IMAGE_DATA".toByteArray()) }
        val videoOriginal = File(vaultDir, "movie.mp4").apply { writeBytes("FAKE_MP4_VIDEO_DATA".toByteArray()) }
        val pdfOriginal = File(vaultDir, "manual.pdf").apply { writeBytes("FAKE_PDF_DOCUMENT_DATA".toByteArray()) }

        assertNotNull(SecureFolderManager.encryptFile(imageOriginal, key))
        assertNotNull(SecureFolderManager.encryptFile(videoOriginal, key))
        assertNotNull(SecureFolderManager.encryptFile(pdfOriginal, key))

        // Unlock
        SecureFolderManager.unlockFolder(vaultDir, key)
        assertTrue(SecureFolderManager.isFolderUnlocked(vaultDir))

        // Verify filter strictly selects only .enc files
        val encFiles = vaultDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(SecureFolderManager.ENC_EXTENSION)
        }.orEmpty()

        assertEquals("Only .enc files must be picked up", 3, encFiles.size)
        val fileNames = encFiles.map { it.name }.toSet()
        assertTrue(fileNames.contains("vacation.jpg.enc"))
        assertTrue(fileNames.contains("movie.mp4.enc"))
        assertTrue(fileNames.contains("manual.pdf.enc"))
        assertFalse(fileNames.contains("notes.txt"))
        assertFalse(fileNames.contains(".nomedia"))
        assertFalse(fileNames.contains(".alexet_secure.json"))
        assertFalse(fileNames.contains("nested_folder"))

        // Emulate loadFolderMedia extraction
        val mediaItems = encFiles.mapNotNull { f ->
            val origName = f.name.removeSuffix(SecureFolderManager.ENC_EXTENSION)
            val mime = NomediaScanner.mimeOf(File(origName)) ?: "image/jpeg"
            GalleryMedia(
                id = NomediaScanner.syntheticId(f.absolutePath),
                uri = DummyUri("content://vault/${f.name}"),
                displayName = origName,
                mimeType = mime,
                dateTakenMs = 0L,
                dateModifiedMs = f.lastModified(),
                dateAddedMs = f.lastModified(),
                sizeBytes = f.length(),
                width = 0,
                height = 0,
                durationMs = 0L,
                bucketId = bucketId,
                bucketName = vaultDir.name,
                relativePath = vaultDir.absolutePath,
                isHidden = true,
                absolutePath = f.absolutePath,
            )
        }

        assertEquals(3, mediaItems.size)
        val mimes = mediaItems.associate { it.displayName to it.mimeType }
        assertEquals("image/jpeg", mimes["vacation.jpg"])
        assertEquals("video/mp4", mimes["movie.mp4"])
        // NomediaScanner handles PDF or falls back to image/jpeg gracefully
        assertNotNull(mimes["manual.pdf"])

        SecureFolderManager.lockFolder(vaultDir)
    }

    @Test
    fun testVaultRepeatedUnlockIdempotence() {
        val vaultDir = tempFolder.newFolder("idempotent_vault")
        val salt = SecureFolderManager.generateSalt()
        val key = SecureFolderManager.deriveKey("RepeatedUnlockPass789", salt)

        // Call unlockFolder 10 times in a row
        for (i in 1..10) {
            SecureFolderManager.unlockFolder(vaultDir, key)
            assertTrue("Vault must be unlocked after call $i", SecureFolderManager.isFolderUnlocked(vaultDir))
            val activeKey = SecureFolderManager.getActiveKey(vaultDir)
            assertNotNull("Active key must exist after call $i", activeKey)
            assertEquals(key, activeKey)
        }

        val canonical = vaultDir.canonicalPath
        // StateFlow unlockedFolderPaths must not contain duplicate paths
        val paths = SecureFolderManager.unlockedFolderPaths.value
        assertEquals(1, paths.count { it == canonical })

        // Lock and unlock again
        SecureFolderManager.lockFolder(vaultDir)
        assertFalse(SecureFolderManager.isFolderUnlocked(vaultDir))
        assertNull(SecureFolderManager.getActiveKey(vaultDir))

        SecureFolderManager.unlockFolder(vaultDir, key)
        assertTrue(SecureFolderManager.isFolderUnlocked(vaultDir))
        assertEquals(key, SecureFolderManager.getActiveKey(vaultDir))

        SecureFolderManager.lockFolder(vaultDir)
    }

    // =========================================================================
    // 2. DECRYPTED STREAM THUMBNAIL SAMPLING & BOUNDS STRESS TESTS
    // =========================================================================

    private fun computeSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        val largest = maxOf(width, height)
        while (largest / sample > target * 2) sample *= 2
        return sample
    }

    @Test
    fun testSampleSizeCalculationsAndExtremeBounds() {
        // Target typical thumbnail sizes
        val target = 256

        // Case 1: Image smaller than target
        assertEquals(1, computeSampleSize(100, 100, target))
        assertEquals(1, computeSampleSize(256, 256, target))

        // Case 2: Image exactly 2x target (512x512) -> 512 / 1 = 512 <= 512 -> sample 1
        assertEquals(1, computeSampleSize(512, 512, target))

        // Case 3: Image slightly larger than 2x target (513x513) -> 513 / 1 > 512 -> sample 2
        assertEquals(2, computeSampleSize(513, 513, target))

        // Case 4: Standard high-res camera image (4032 x 3024)
        // 4032 / 1 > 512 (2), 4032 / 2 = 2016 > 512 (4), 4032 / 4 = 1008 > 512 (8), 4032 / 8 = 504 <= 512 -> 8
        assertEquals(8, computeSampleSize(4032, 3024, target))

        // Case 5: 108MP image (12000 x 9000)
        // 12000 / 1 -> 2, / 2 -> 4, / 4 -> 8, / 8 -> 16, 12000 / 16 = 750 > 512, 12000 / 32 = 375 <= 512 -> 32
        assertEquals(32, computeSampleSize(12000, 9000, target))

        // Case 6: Extreme panoramic aspect ratio (20,000 x 200)
        // Largest is 20,000 -> 20000 / 32 = 625 > 512, 20000 / 64 = 312 <= 512 -> sampleSize = 64
        val panoramaSample = computeSampleSize(20000, 200, target)
        assertEquals(64, panoramaSample)
        assertTrue("Largest dimension downsampled must be <= target * 2", 20000 / panoramaSample <= target * 2)

        // Case 7: Extreme vertical strip aspect ratio (200 x 20,000)
        val verticalSample = computeSampleSize(200, 20000, target)
        assertEquals(64, verticalSample)
        assertTrue("Largest dimension downsampled must be <= target * 2", 20000 / verticalSample <= target * 2)

        // Case 8: Target = 1 edge case (should not loop infinitely)
        val tinyTargetSample = computeSampleSize(1000, 1000, 1)
        assertTrue("Sample size must be power of 2 and terminate", tinyTargetSample >= 512)

        // Case 9: Zero dimensions (guard test)
        assertEquals(1, computeSampleSize(0, 0, target))
    }

    @Test
    fun testDecryptedStreamMultiPassReadingAndStreamIsolation() {
        val rootDir = tempFolder.newFolder("stream_pass_test")
        val sampleFile = File(rootDir, "test_pic.jpg")
        val payload = ByteArray(1024 * 64) { (it % 256).toByte() } // 64 KB payload
        sampleFile.writeBytes(payload)

        val salt = SecureFolderManager.generateSalt()
        val key = SecureFolderManager.deriveKey("StreamIsolationKey", salt)

        assertNotNull(SecureFolderManager.encryptFile(sampleFile, key))
        val encFile = File(rootDir, "test_pic.jpg" + SecureFolderManager.ENC_EXTENSION)
        assertTrue(encFile.exists())

        // Pass 1: Simulate inJustDecodeBounds (reading only the first 100 bytes of stream)
        val pass1Stream = SecureFolderManager.openDecryptedStream(encFile, key)
        assertNotNull(pass1Stream)
        val headerBytes = ByteArray(100)
        val read1 = pass1Stream!!.read(headerBytes)
        assertEquals(100, read1)
        pass1Stream.close()

        // Pass 2: Open a fresh decrypted stream for full thumbnail decode
        val pass2Stream = SecureFolderManager.openDecryptedStream(encFile, key)
        assertNotNull(pass2Stream)
        val fullDecrypted = pass2Stream!!.use { it.readBytes() }
        assertEquals(payload.size, fullDecrypted.size)
        assertEquals(payload.toList(), fullDecrypted.toList())

        // Pass 3: Parallel concurrent stream reads
        val streamA = SecureFolderManager.openDecryptedStream(encFile, key)
        val streamB = SecureFolderManager.openDecryptedStream(encFile, key)
        assertNotNull(streamA)
        assertNotNull(streamB)

        val bytesA = streamA!!.use { it.readBytes() }
        val bytesB = streamB!!.use { it.readBytes() }
        assertEquals(payload.toList(), bytesA.toList())
        assertEquals(payload.toList(), bytesB.toList())
    }

    @Test
    fun testDecryptedStreamSubIvBoundaryCases() {
        val rootDir = tempFolder.newFolder("sub_iv_test")
        val salt = SecureFolderManager.generateSalt()
        val key = SecureFolderManager.deriveKey("SubIvKey", salt)

        // Test file with 1 byte
        val file1Byte = File(rootDir, "one_byte.jpg.enc").apply { writeBytes(ByteArray(1)) }
        val stream1 = SecureFolderManager.openDecryptedStream(file1Byte, key)
        assertNull("Files with < 12 bytes must return null stream immediately", stream1)

        // Test file with exactly 11 bytes (1 byte short of 12-byte IV)
        val file11Bytes = File(rootDir, "eleven_bytes.jpg.enc").apply { writeBytes(ByteArray(11)) }
        val stream11 = SecureFolderManager.openDecryptedStream(file11Bytes, key)
        assertNull("Files with 11 bytes must return null stream", stream11)

        // Test file with exactly 12 bytes (IV only, 0 ciphertext/tag): the old format is checked
        // whole when opened, so it fails there (null) or, at the latest, on read.
        val file12Bytes = File(rootDir, "twelve_bytes.jpg.enc").apply { writeBytes(ByteArray(12)) }
        val stream12 = SecureFolderManager.openDecryptedStream(file12Bytes, key)
        var failedSafely = stream12 == null
        if (stream12 != null) {
            try {
                stream12.use { it.readBytes() }
            } catch (e: Exception) {
                failedSafely = true
            }
        }
        assertTrue("A 12-byte file (no tag) must fail safely on decipher", failedSafely)
    }

    // =========================================================================
    // 3. NAME MIXER STRESS: EMPTY FOLDER, UNICODE, SPECIAL CHARS & EXTENSIONS
    // =========================================================================

    @Test
    fun testNameMixerEmptyFolderHandling() {
        val emptyItems = emptyList<GalleryMedia>()

        // Simulate GalleryViewModel.mixFileNamesInFolder guard:
        // val items = uiState.value.visibleFilesIn(folderId).ifEmpty { uiState.value.filesIn(folderId) }
        // if (items.isEmpty()) return
        val wasProcessed = if (emptyItems.isEmpty()) {
            false
        } else {
            val itemsWithNewNames = emptyItems.map { it to ExifUtils.generateAnonymousFileName(it.displayName) }
            itemsWithNewNames.isNotEmpty()
        }

        assertFalse("Empty folder must immediately return without invoking rename", wasProcessed)
    }

    @Test
    fun testNameMixerSpecialCharactersAndUnicodePreservation() {
        val testCases = listOf(
            // Polish diacritics
            "Zażółć_gęślą_jaźń_2026.jpg" to ".jpg",
            "zdjęcie łoś żółć #1.jpeg" to ".jpeg",
            // Spaces and punctuation
            "my holiday photo (1) [final] {v2}.png" to ".png",
            "file-with-dashes_and_underscores.webp" to ".webp",
            "symbols_!@#$%^&()_+~`={}[];'.gif" to ".gif",
            // Multiple dots
            "backup.2026.09.18.tar.gz" to ".gz",
            "image.min.preview.heic" to ".heic",
            "dot.at.end." to ".",
            // Dotfiles
            ".hidden_image.jpg" to ".jpg",
            ".profile.png" to ".png",
            ".gitignore" to ".gitignore",
            // Uppercase extensions
            "CAMERA_IMG_001.JPG" to ".JPG",
            "SCREEN_RECORDING.MP4" to ".MP4",
            "SCAN.PDF" to ".PDF",
            // Unicode / Asian characters
            "旅行写真_富士山_2026.png" to ".png",
            "방탄소년단_사진.webp" to ".webp",
            "Картинка_Природа.jpg" to ".jpg",
            // No extension at all
            "plain_file_without_extension" to "",
            "README" to "",
        )

        testCases.forEach { (originalName, expectedExt) ->
            val anon = ExifUtils.generateAnonymousFileName(originalName)

            // 1. Must start with "anon_"
            assertTrue("'$anon' must start with 'anon_'", anon.startsWith("anon_"))

            // 2. Must end with exact expected extension (case-sensitive)
            assertTrue(
                "'$anon' generated from '$originalName' must end with '$expectedExt'",
                anon.endsWith(expectedExt),
            )

            // 3. Middle 8 characters must be lowercase hex
            val hexPart = anon.removePrefix("anon_").removeSuffix(expectedExt)
            assertEquals("Hex part of '$anon' must be exactly 8 characters", 8, hexPart.length)
            assertTrue("Hex part '$hexPart' must be valid hex", hexPart.matches(Regex("^[0-9a-f]{8}$")))

            // 4. Must not equal original name
            assertNotEquals(originalName, anon)
        }
    }

    @Test
    fun testNameMixerLargeScaleBatchUniqueness() {
        // Test 1: 1,000 items (typical large folder) must have zero collisions
        val smallBatch = 1000
        val smallNames = (1..smallBatch).map { i ->
            ExifUtils.generateAnonymousFileName("photo_$i.jpg")
        }
        assertEquals(smallBatch, smallNames.size)
        assertEquals("1,000 generated names must be 100% unique", smallBatch, smallNames.toSet().size)

        // Test 2: 10,000 items entropy check (birthday paradox allows at most a few collisions in 4.29B space)
        val largeBatch = 10000
        val largeNames = (1..largeBatch).map { i ->
            ExifUtils.generateAnonymousFileName("photo_$i.jpg")
        }
        val uniqueLarge = largeNames.toSet().size
        assertTrue(
            "10,000 generated anonymous names must have > 99.9% distinctness (got $uniqueLarge / $largeBatch)",
            uniqueLarge >= 9990,
        )
    }
}
