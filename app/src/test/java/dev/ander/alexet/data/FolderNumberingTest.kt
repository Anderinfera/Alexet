package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderNumberingTest {
    private fun e(id: Long, name: String, added: Long = 0L) = FolderNumbering.Entry(id, name, added)

    @Test
    fun numbersOfNames() {
        assertEquals(12, FolderNumbering.numberOf("12.jpg"))
        assertEquals(3, FolderNumbering.numberOf("3"))
        assertNull(FolderNumbering.numberOf("01.jpg"))
        assertNull(FolderNumbering.numberOf("0.jpg"))
        assertNull(FolderNumbering.numberOf("IMG_1.jpg"))
        assertNull(FolderNumbering.numberOf(".jpg"))
    }

    @Test
    fun allInPlaceNeedsNothing() {
        val ordered = FolderNumbering.order(listOf(e(2, "2.mp4"), e(1, "1.jpg"), e(3, "3.png")))
        assertEquals(listOf("1.jpg", "2.mp4", "3.png"), ordered.map { it.name })
        assertEquals(-1, FolderNumbering.firstMisplaced(ordered))
    }

    @Test
    fun deletedFileGapCloses() {
        val ordered = FolderNumbering.order(listOf(e(1, "1.jpg"), e(2, "2.jpg"), e(4, "4.jpg"), e(5, "5.jpg")))
        assertEquals(listOf("1.jpg", "2.jpg", "4.jpg", "5.jpg"), ordered.map { it.name })
        // From the third file on the names change: 4 → 3, 5 → 4.
        assertEquals(2, FolderNumbering.firstMisplaced(ordered))
    }

    @Test
    fun newFilesGoLastInSelectionOrderThenByDate() {
        val ordered = FolderNumbering.order(
            listOf(e(1, "1.jpg"), e(7, "late.jpg", added = 50), e(8, "b.jpg", added = 10), e(9, "a.jpg", added = 10), e(10, "c.jpg", added = 99)),
            newNames = listOf("c.jpg", "b.jpg"),
        )
        assertEquals(listOf("1.jpg", "c.jpg", "b.jpg", "a.jpg", "late.jpg"), ordered.map { it.name })
        assertEquals(1, FolderNumbering.firstMisplaced(ordered))
    }

    @Test
    fun sameNumberTwiceRenumbersFromTheSecond() {
        val ordered = FolderNumbering.order(listOf(e(1, "1.jpg"), e(2, "1.mp4"), e(3, "2.jpg")))
        assertEquals(listOf("1.jpg", "1.mp4", "2.jpg"), ordered.map { it.name })
        assertEquals(1, FolderNumbering.firstMisplaced(ordered))
    }
}
