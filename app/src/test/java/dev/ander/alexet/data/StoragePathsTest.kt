package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoragePathsTest {
    private val primary = "/storage/emulated/0"

    @Test
    fun sdCardFolderIsNotInsideTheMainStorage() {
        // relativeToOrNull alone gives "../../6FFF-B5B1/Gallery/Eruu Claymie" here.
        assertNull(StoragePaths.pathInside(File("/storage/6FFF-B5B1/Gallery/Eruu Claymie"), File(primary)))
    }

    @Test
    fun folderInsideTheMainStorage() {
        assertEquals("Movies/NewPipe", StoragePaths.pathInside(File("$primary/Movies/NewPipe"), File(primary)))
        assertEquals("", StoragePaths.pathInside(File(primary), File(primary)))
    }

    @Test
    fun pathOnItsOwnVolume() {
        assertEquals("Gallery/Eruu Claymie", StoragePaths.pathOnItsVolume("/storage/6FFF-B5B1/Gallery/Eruu Claymie", primary))
        assertEquals("Download/Downloader", StoragePaths.pathOnItsVolume("$primary/Download/Downloader/", primary))
        assertEquals("", StoragePaths.pathOnItsVolume(primary, primary))
        assertNull(StoragePaths.pathOnItsVolume("/data/user/0/dev.ander.alexet/files", primary))
        assertNull(StoragePaths.pathOnItsVolume("$primary/../../6FFF-B5B1/Gallery", primary))
    }

    @Test
    fun volumeRoots() {
        assertEquals(primary, StoragePaths.volumeRootOf("$primary/DCIM/a.jpg", primary))
        assertEquals("/storage/6FFF-B5B1", StoragePaths.volumeRootOf("/storage/6FFF-B5B1/Gallery/a.mp4", primary))
        assertNull(StoragePaths.volumeRootOf("/storage/emulated/10/a.mp4", primary))
        assertNull(StoragePaths.volumeRootOf("/data/local/tmp/a.mp4", primary))
    }

    @Test
    fun storageSafeNameDropsEmojiOnly() {
        assertEquals(
            "【ASMR 14】ASMR for when you need encouragement Fluffy Triggers & Affirmations.mp4",
            StoragePaths.storageSafeName("【ASMR 14】ASMR for when you need encouragement 💗 Fluffy Triggers & Affirmations.mp4"),
        )
        // ❤ (U+2764) is within U+FFFF and stays; its variation selector and 💗 go.
        assertEquals("Heart ❤.mp4", StoragePaths.storageSafeName("Heart ❤️💗.mp4"))
        assertEquals("a_b_c.jpg", StoragePaths.storageSafeName("a:b?c.jpg"))
        assertEquals("file.mp4", StoragePaths.storageSafeName("💗.mp4"))
        assertEquals("zwykła nazwa 【1】.mp4", StoragePaths.storageSafeName("zwykła nazwa 【1】.mp4"))
    }

    @Test
    fun mainStorageToSdCardIsAnotherVolume() {
        assertFalse(StoragePaths.sameVolume("$primary/Download/Downloader/a.mp4", "/storage/6FFF-B5B1/Gallery/Eruu Claymie", primary))
        assertTrue(StoragePaths.sameVolume("/storage/6FFF-B5B1/Downloader/a.mp4", "/storage/6FFF-B5B1/Gallery/Eruu Claymie", primary))
        assertTrue(StoragePaths.sameVolume("$primary/a.mp4", "$primary/Movies", primary))
        // Unknown places keep the old behaviour (MediaStore move).
        assertTrue(StoragePaths.sameVolume("/data/a.mp4", "/storage/6FFF-B5B1/Gallery", primary))
    }
}
