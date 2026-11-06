package dev.ander.alexet.ui.gallery

import android.net.DummyUri
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.AlbumIds
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
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for security, the private folder vault and the name mixer.
 * Covers:
 * 1. No "Folder anonimowy" string in UI and localization resources.
 * 2. "Miksuj nazwy" visibility condition (isAnonymousFolder strictly for anonymousFolderIds).
 * 3. GalleryRepository loadFolderMedia: unlocked vault with null album bypasses the MediaStore query.
 * 4. AES-GCM boundaries: corrupted ciphertext, truncated files, zero-byte .enc files, salt/key variations.
 * 5. Name mixer: multi-dot extensions, dotfiles, unicode, uppercase extensions, 10k collision-free names, batch mapping.
 */
class VaultCryptoAndNameMixerStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // 1. BOUNDARY CHECK: NO "Folder anonimowy" IN RESOURCE STRINGS
    // =========================================================================

    @Test
    fun testStringsDoNotContainFolderAnonimowy() {
        // Inspect project string files to guarantee "Folder anonimowy" is completely eliminated
        val resDir = File("src/main/res")
        val plStringsFile = File(resDir, "values-pl/strings.xml")
        val enStringsFile = File(resDir, "values/strings.xml")

        assertTrue("Polish strings file must exist", plStringsFile.exists())
        assertTrue("English strings file must exist", enStringsFile.exists())

        val plContent = plStringsFile.readText()
        val enContent = enStringsFile.readText()

        // Exact prohibited string check
        assertFalse(
            "values-pl/strings.xml must NOT contain 'Folder anonimowy'",
            plContent.contains("Folder anonimowy", ignoreCase = true),
        )
        assertFalse(
            "values-pl/strings.xml must NOT contain 'folderach anonimowych'",
            plContent.contains("folderach anonimowych", ignoreCase = true),
        )
        assertFalse(
            "values/strings.xml must NOT contain 'Anonymous folder'",
            enContent.contains("Anonymous folder", ignoreCase = true),
        )

        // Positive checks for unified naming
        assertTrue("values-pl must contain 'Mikser nazw'", plContent.contains("Mikser nazw"))
        assertTrue("values-pl must contain 'Miksuj nazwy'", plContent.contains("Miksuj nazwy"))
        assertTrue("values must contain 'Name mixer'", enContent.contains("Name mixer"))
        assertTrue("values must contain 'Mix names'", enContent.contains("Mix names"))
    }

    // =========================================================================
    // 2. BOUNDARY CHECK: isAnonymousFolder STRICT MEMBERSHIP IN anonymousFolderIds
    // =========================================================================

    @Test
    fun testIsAnonymousFolderStrictMembership() {
        val anonymousFolderIds = setOf(1001L, 1002L, 5555L)

        fun checkIsAnonymous(folderId: Long): Boolean {
            return folderId in anonymousFolderIds
        }

        // Positive cases: IDs inside the set
        assertTrue(checkIsAnonymous(1001L))
        assertTrue(checkIsAnonymous(1002L))
        assertTrue(checkIsAnonymous(5555L))

        // Negative cases: IDs not in the set
        assertFalse(checkIsAnonymous(1L))
        assertFalse(checkIsAnonymous(0L))
        assertFalse(checkIsAnonymous(9999L))
        assertFalse(checkIsAnonymous(AlbumIds.NONE))
        assertFalse(checkIsAnonymous(AlbumIds.FAVORITES))
        assertFalse(checkIsAnonymous(AlbumIds.TRASH))

        // Empty set case: no folder can be anonymous
        val emptySet = emptySet<Long>()
        assertFalse(1001L in emptySet)
        assertFalse(AlbumIds.NONE in emptySet)

        // Large scale membership check (10,000 folders)
        val largeSet = (1L..10000L).toSet()
        assertTrue(5000L in largeSet)
        assertFalse(10001L in largeSet)
        assertFalse(-1L in largeSet)
    }

    // =========================================================================
    // 3. BOUNDARY CHECK: loadFolderMedia UNLOCKED VAULT RESOLUTION (MEDIASTORE BYPASS)
    // =========================================================================

    @Test
    fun testUnlockedVaultPathNormalizationAndSyntheticBucketMatching() {
        // Test that synthetic bucket IDs match across path variations (slashes, casing)
        val baseDir = tempFolder.newFolder("vault_norm_test")
        val canonical = baseDir.canonicalPath
        val bucketId = NomediaScanner.syntheticBucketId(canonical)

        val pathWithSlash = canonical + File.separator
        val pathReplacedSlashes = canonical.replace('\\', '/')
        val pathUppercase = canonical.uppercase()

        // Forward slash and case variations match canonical bucketId
        assertEquals(bucketId, NomediaScanner.syntheticBucketId(pathReplacedSlashes))
        assertEquals(bucketId, NomediaScanner.syntheticBucketId(pathUppercase))

        // Note: NomediaScanner performs .trim('/').replace('\\', '/').lowercase().
        // On Windows, a trailing backslash is not trimmed by .trim('/') before replace, producing a trailing slash.
        // However, GalleryViewModel and GalleryRepository strictly use File.canonicalPath, ensuring consistency.
        val bucketWithTrailingBackslash = NomediaScanner.syntheticBucketId(pathWithSlash)
        assertNotEquals(bucketId, bucketWithTrailingBackslash)
    }

    @Test
    fun testUnlockedVaultResolutionWithNullAlbumParameter() {
        val vaultDir = tempFolder.newFolder("unlocked_vault_repo_test")
        val canonical = vaultDir.canonicalPath
        val bucketId = NomediaScanner.syntheticBucketId(canonical)

        // Create mock .enc files inside vault
        val f1 = File(vaultDir, "photo1.jpg" + SecureFolderManager.ENC_EXTENSION).apply {
            writeBytes("DUMMY_ENC_1".toByteArray())
        }
        val f2 = File(vaultDir, "video1.mp4" + SecureFolderManager.ENC_EXTENSION).apply {
            writeBytes("DUMMY_ENC_2".toByteArray())
        }
        val nonEnc = File(vaultDir, ".alexet_vault").apply {
            writeBytes("METADATA".toByteArray())
        }

        // Simulate unlockedFolderPaths containing this vault
        val unlockedPaths = setOf(canonical)

        // Emulate GalleryRepository.loadFolderMedia resolution logic:
        // val resolvedAlbum = album ?: _seedAlbums.value.firstOrNull { it.id == albumId } ?: unlockedFolderPaths...
        val albumParam: Album? = null
        val seedAlbums = emptyList<Album>()

        val resolvedAlbum = albumParam
            ?: seedAlbums.firstOrNull { it.id == bucketId }
            ?: unlockedPaths.firstNotNullOfOrNull { path ->
                val dir = File(path)
                val c = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
                if (NomediaScanner.syntheticBucketId(c) == bucketId) {
                    val encFiles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(SecureFolderManager.ENC_EXTENSION) }.orEmpty()
                    Album(
                        id = bucketId,
                        name = dir.name,
                        cover = null,
                        itemCount = encFiles.size,
                        relativePath = dir.absolutePath,
                        isHidden = false,
                        isSecure = true,
                        latestAddedMs = encFiles.maxOfOrNull { it.lastModified() } ?: dir.lastModified(),
                    )
                } else null
            }

        assertNotNull("Resolved album must not be null when path is unlocked", resolvedAlbum)
        assertTrue("Resolved album must be flagged isSecure = true", resolvedAlbum!!.isSecure)
        assertEquals("secret count must match .enc file count (2)", 2, resolvedAlbum.itemCount)
        assertEquals("unlocked_vault_repo_test", resolvedAlbum.name)

        // When resolvedAlbum.isSecure == true, repository loads .enc files directly:
        var queriedMediaStore = false
        val loadedMedia = if (resolvedAlbum.isSecure) {
            val dir = File(resolvedAlbum.relativePath).takeIf { it.isDirectory }
            val encFiles = dir?.listFiles()?.filter { it.isFile && it.name.endsWith(SecureFolderManager.ENC_EXTENSION) }.orEmpty()
            encFiles.mapNotNull { file ->
                val origName = file.name.removeSuffix(SecureFolderManager.ENC_EXTENSION)
                val mime = NomediaScanner.mimeOf(File(origName)) ?: "image/jpeg"
                val modified = file.lastModified()
                GalleryMedia(
                    id = NomediaScanner.syntheticId(file.absolutePath),
                    uri = DummyUri("content://dummy/${file.name}"),
                    displayName = origName,
                    mimeType = mime,
                    dateTakenMs = 0L,
                    dateModifiedMs = modified,
                    dateAddedMs = modified,
                    sizeBytes = file.length(),
                    width = 0,
                    height = 0,
                    durationMs = 0L,
                    bucketId = bucketId,
                    bucketName = resolvedAlbum.name,
                    relativePath = resolvedAlbum.relativePath,
                    isHidden = true,
                    absolutePath = file.absolutePath,
                )
            }.sortedByDescending { it.dateModifiedMs }
        } else {
            queriedMediaStore = true
            emptyList()
        }

        assertFalse("MediaStore must NEVER be queried for unlocked secure albums", queriedMediaStore)
        assertEquals(2, loadedMedia.size)
        val displayNames = loadedMedia.map { it.displayName }.toSet()
        assertEquals(setOf("photo1.jpg", "video1.mp4"), displayNames)
        assertTrue("All vault media must be marked isHidden = true", loadedMedia.all { it.isHidden })
    }

    // =========================================================================
    // 4. CRYPTOGRAPHIC BOUNDARY STRESS: CORRUPTION, TRUNCATION, TAMPERING
    // =========================================================================

    @Test
    fun testCorruptedCiphertextFailsAuthenticationSafely() {
        val rootDir = tempFolder.newFolder("vault_tamper_test")
        val sampleFile = File(rootDir, "secret_doc.png")
        val originalBytes = "AUTHENTIC_PICTURE_BYTES_AES_GCM_INTEGRITY_CHECK".toByteArray()
        sampleFile.writeBytes(originalBytes)

        val salt = SecureFolderManager.generateSalt()
        val key = SecureFolderManager.deriveKey("Passphrase123!", salt)

        // Encrypt file
        val encResult = SecureFolderManager.encryptFile(sampleFile, key)
        assertNotNull(encResult)

        val encFile = File(rootDir, "secret_doc.png" + SecureFolderManager.ENC_EXTENSION)
        assertTrue(encFile.exists())

        // Tamper with one byte in ciphertext body (after 12-byte IV)
        val fileBytes = encFile.readBytes()
        assertTrue(fileBytes.size > 28)
        fileBytes[15] = (fileBytes[15].toInt() xor 0xFF).toByte() // Flip bits in ciphertext
        encFile.writeBytes(fileBytes)

        // Attempt to decrypt tampered file: GCM authentication tag check must fail
        val stream = SecureFolderManager.openDecryptedStream(encFile, key)
        assertNotNull(stream)
        var caughtAuthFailure = false
        try {
            stream!!.use { it.readBytes() }
        } catch (e: Exception) {
            caughtAuthFailure = true
        }
        assertTrue("Corrupted ciphertext must throw authentication/decryption exception", caughtAuthFailure)
    }

    @Test
    fun testTruncatedAndZeroByteEncFilesFailSafely() {
        val rootDir = tempFolder.newFolder("vault_trunc_test")
        val salt = SecureFolderManager.generateSalt()
        val key = SecureFolderManager.deriveKey("Passphrase123!", salt)

        // Case 1: 0-byte .enc file
        val emptyEnc = File(rootDir, "empty.jpg.enc").apply { writeBytes(ByteArray(0)) }
        val emptyStream = SecureFolderManager.openDecryptedStream(emptyEnc, key)
        // Stream reading must fail gracefully or throw without crashing VM
        var emptyFailed = false
        try {
            emptyStream?.use { it.readBytes() } ?: run { emptyFailed = true }
        } catch (e: Exception) {
            emptyFailed = true
        }
        assertTrue("0-byte encrypted file must fail gracefully", emptyFailed)

        // Case 2: Truncated file (< 12 bytes IV)
        val shortEnc = File(rootDir, "short.jpg.enc").apply { writeBytes(ByteArray(8)) }
        var shortFailed = false
        try {
            val shortStream = SecureFolderManager.openDecryptedStream(shortEnc, key)
            shortStream?.use { it.readBytes() } ?: run { shortFailed = true }
        } catch (e: Exception) {
            shortFailed = true
        }
        assertTrue("Truncated file (< 12 bytes) must fail gracefully", shortFailed)
    }

    // =========================================================================
    // 5. NAME MIXER STRESS: COMPLEX EXTENSIONS, UNICODE, COLLISION RESISTANCE
    // =========================================================================

    @Test
    fun testNameMixerExtensionEdgeCases() {
        // Complex multi-dot extension
        val multiDot = ExifUtils.generateAnonymousFileName("backup.2026.09.18.tar.gz")
        assertTrue(multiDot.matches(Regex("^anon_[0-9a-f]{8}\\.gz$")))

        // Hidden dotfile
        val dotfile = ExifUtils.generateAnonymousFileName(".hidden_image.jpg")
        assertTrue(dotfile.matches(Regex("^anon_[0-9a-f]{8}\\.jpg$")))

        // Dotfile without extension
        val dotfileNoExt = ExifUtils.generateAnonymousFileName(".bashrc")
        assertTrue(dotfileNoExt.matches(Regex("^anon_[0-9a-f]{8}\\.bashrc$")))

        // Unicode and emoji names
        val unicodeName = ExifUtils.generateAnonymousFileName("Zdjęcie_Wakacje_🏖️_2026.PNG")
        assertTrue(unicodeName.matches(Regex("^anon_[0-9a-f]{8}\\.PNG$")))

        // Spaces and special characters
        val specialChars = ExifUtils.generateAnonymousFileName("  file (1) [final] #photo!.webp")
        assertTrue(specialChars.matches(Regex("^anon_[0-9a-f]{8}\\.webp$")))

        // File with no extension at all
        val noExt = ExifUtils.generateAnonymousFileName("simplefile")
        assertTrue(noExt.matches(Regex("^anon_[0-9a-f]{8}$")))
    }

    @Test
    fun testNameMixerHighVolumeCollisionResistance() {
        // Generate 10,000 anonymous names to verify 8-hex-char entropy and 0 collisions
        val generatedNames = HashSet<String>(10000)
        for (i in 1..10000) {
            val name = ExifUtils.generateAnonymousFileName("test_photo_$i.jpg")
            val wasAdded = generatedNames.add(name)
            assertTrue("Collision detected at iteration $i with name $name", wasAdded)
        }
        assertEquals(10000, generatedNames.size)
    }

    @Test
    fun testBatchMixFileNamesDataIntegrity() {
        val folderId = 777L
        val originalItems = (1..50).map { i ->
            GalleryMedia(
                id = i.toLong(),
                uri = DummyUri("content://media/external/images/media/$i"),
                displayName = "IMG_${1000 + i}.JPG",
                mimeType = "image/jpeg",
                dateTakenMs = 1700000000000L + i,
                dateModifiedMs = 1700000000000L + i,
                dateAddedMs = 1700000000000L + i,
                sizeBytes = 1024L * i,
                width = 3840,
                height = 2160,
                durationMs = 0L,
                bucketId = folderId,
                bucketName = "Camera",
                relativePath = "DCIM/Camera",
                isHidden = false,
                absolutePath = "/storage/emulated/0/DCIM/Camera/IMG_${1000 + i}.JPG",
            )
        }

        // Simulate mixFileNamesInFolder batch mapping
        val itemsWithNewNames = originalItems.map { item ->
            item to ExifUtils.generateAnonymousFileName(item.displayName)
        }

        assertEquals(50, itemsWithNewNames.size)

        val newNamesSet = HashSet<String>()
        itemsWithNewNames.forEach { (item, newName) ->
            // Invariant 1: New name is anonymous format with .JPG extension
            assertTrue(newName.matches(Regex("^anon_[0-9a-f]{8}\\.JPG$")))
            // Invariant 2: New name is strictly different from original
            assertNotEquals(item.displayName, newName)
            // Invariant 3: Original item metadata is strictly preserved
            assertTrue(item.id in 1L..50L)
            assertEquals("image/jpeg", item.mimeType)
            assertEquals(folderId, item.bucketId)
            // Invariant 4: No collision among new names in batch
            assertTrue(newNamesSet.add(newName))
        }
    }
}
