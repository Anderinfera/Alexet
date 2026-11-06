package dev.ander.alexet.ui.gallery

import dev.ander.alexet.data.Album
import dev.ander.alexet.data.AlbumIds
import dev.ander.alexet.data.FolderScrollPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderRenameAndScrollTest {

    @Test
    fun testTotalCountSelectionLogic() {
        val visibleAlbums = listOf(
            Album(1L, "Folder 1", null, 5, "Pictures/F1"),
            Album(2L, "Folder 2", null, 10, "Pictures/F2"),
            Album(3L, "Folder 3", null, 15, "Pictures/F3"),
        )
        val selectedAlbumIds = setOf(1L)
        val selectedFileIds = emptySet<Long>()
        val totalFilesCount = 39485
        val phoneFolderId = AlbumIds.NONE
        val wide = false

        val totalCount = when {
            selectedAlbumIds.isNotEmpty() && selectedFileIds.isEmpty() -> visibleAlbums.size
            phoneFolderId != AlbumIds.NONE -> 0
            !wide -> visibleAlbums.size
            else -> totalFilesCount
        }

        assertEquals(3, totalCount)
    }

    @Test
    fun testFolderScrollLruCapacityThree() {
        val folderScrollLru = LinkedHashMap<Long, FolderScrollPos>()
        fun saveScroll(folderId: Long, pos: FolderScrollPos) {
            folderScrollLru.remove(folderId)
            folderScrollLru[folderId] = pos
            while (folderScrollLru.size > 3) {
                val oldestKey = folderScrollLru.keys.firstOrNull() ?: break
                folderScrollLru.remove(oldestKey)
            }
        }

        saveScroll(1L, FolderScrollPos(index = 10, offset = 0))
        saveScroll(2L, FolderScrollPos(index = 20, offset = 0))
        saveScroll(3L, FolderScrollPos(index = 30, offset = 0))

        assertEquals(3, folderScrollLru.size)
        assertEquals(10, folderScrollLru[1L]?.index)

        // Adding 4th folder should evict folder 1L (oldest)
        saveScroll(4L, FolderScrollPos(index = 40, offset = 0))
        assertEquals(3, folderScrollLru.size)
        assertNull(folderScrollLru[1L])
        assertEquals(20, folderScrollLru[2L]?.index)
        assertEquals(30, folderScrollLru[3L]?.index)
        assertEquals(40, folderScrollLru[4L]?.index)
    }

    @Test
    fun testFolderGridLruKeysCapacityThree() {
        val folderGridLruKeys = mutableListOf<Long>()
        val folderGridStates = mutableMapOf<Long, String>()
        fun getOrCreate(folderId: Long): String {
            folderGridLruKeys.remove(folderId)
            folderGridLruKeys.add(folderId)
            while (folderGridLruKeys.size > 3) {
                val oldest = folderGridLruKeys.removeAt(0)
                folderGridStates.remove(oldest)
            }
            return folderGridStates.getOrPut(folderId) { "State_$folderId" }
        }

        getOrCreate(100L)
        getOrCreate(200L)
        getOrCreate(300L)
        assertEquals(listOf(100L, 200L, 300L), folderGridLruKeys)

        // Access 100L again -> becomes most recent
        getOrCreate(100L)
        assertEquals(listOf(200L, 300L, 100L), folderGridLruKeys)

        // Access 400L -> evicts 200L
        getOrCreate(400L)
        assertEquals(listOf(300L, 100L, 400L), folderGridLruKeys)
        assertNull(folderGridStates[200L])
        assertEquals("State_100", folderGridStates[100L])
    }
}
