package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AlphanumericComparatorTest {

    @Test
    fun `tilde names mixed with other names keep a consistent order`() {
        // The case that used to form a cycle: img9~1 < img2 < img5x < img9~1.
        val a = "img9~1.jpg"
        val c = "img2.jpg"
        val d = "img5x.jpg"
        val ab = AlphanumericComparator.compare(a, c)
        val bc = AlphanumericComparator.compare(c, d)
        val ca = AlphanumericComparator.compare(d, a)
        assertTrue("cycle: $ab $bc $ca", !(ab < 0 && bc < 0 && ca < 0) && !(ab > 0 && bc > 0 && ca > 0))
    }

    @Test
    fun `comparator is transitive on random gallery-like names`() {
        val rnd = Random(7)
        val parts = listOf("img", "IMG_", "photo ", "a", "x", "")
        val names = List(300) {
            val sb = StringBuilder(parts[rnd.nextInt(parts.size)])
            sb.append(rnd.nextInt(0, 30))
            when (rnd.nextInt(4)) {
                0 -> sb.append('~').append(rnd.nextInt(1, 4))
                1 -> sb.append("x")
                2 -> sb.append(" (copy)")
                else -> {}
            }
            sb.append(if (rnd.nextBoolean()) ".jpg" else ".png").toString()
        }
        // Sorting must not throw ("Comparison method violates its general contract").
        val sorted = names.sortedWith(AlphanumericComparator)
        for (i in 0 until sorted.size - 1) {
            assertTrue(AlphanumericComparator.compare(sorted[i], sorted[i + 1]) <= 0)
        }
        for (x in names.take(60)) for (y in names.take(60)) for (z in names.take(60)) {
            if (AlphanumericComparator.compare(x, y) < 0 && AlphanumericComparator.compare(y, z) < 0) {
                assertTrue("$x < $y < $z", AlphanumericComparator.compare(x, z) < 0)
            }
        }
    }

    @Test
    fun `priority layers still come first within their group`() {
        val names = listOf("img2.jpg", "img1~1.jpg", "img1.jpg", "img3~2.jpg")
        assertEquals(listOf("img3~2.jpg", "img1~1.jpg", "img1.jpg", "img2.jpg"), names.sortedWith(AlphanumericComparator))
    }
}
