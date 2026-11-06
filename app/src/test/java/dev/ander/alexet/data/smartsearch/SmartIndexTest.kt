package dev.ander.alexet.data.smartsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SmartIndexTest {

    private fun randomUnit(rnd: Random, dim: Int) = FloatArray(dim) { rnd.nextFloat() * 2 - 1 }.normalizedL2()

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    @Test
    fun int8ScoresStayCloseToTheFloatCosine() {
        val rnd = Random(1)
        val dim = 768
        repeat(50) {
            val a = randomUnit(rnd, dim)
            val b = randomUnit(rnd, dim)
            val q = QuantizedVector.of(b)
            val index = SmartIndex(dim).apply { put(1L, q) }
            val score = index.search(a, 1).single().score
            assertTrue("score $score vs ${cosine(a, b)}", abs(score - cosine(a, b)) < 0.01f)
        }
    }

    @Test
    fun searchReturnsTheBestMatchesFirst() {
        val rnd = Random(2)
        val dim = 64
        val query = randomUnit(rnd, dim)
        val index = SmartIndex(dim)
        val vectors = (1L..500L).associateWith { QuantizedVector.of(randomUnit(rnd, dim)) }
        vectors.forEach { (id, v) -> index.put(id, v) }
        // Ranked by the stored int8 values: near ties may swap against the float vectors.
        val stored = vectors.mapValues { (_, q) -> FloatArray(dim) { q.vec[it] * q.scale } }
        val expected = stored.entries.sortedByDescending { cosine(query, it.value) }.take(10).map { it.key }
        val got = index.search(query, 10)
        assertEquals(expected, got.map { it.id })
        assertTrue(got.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun removeFilterAndThreshold() {
        val dim = 4
        val index = SmartIndex(dim)
        index.put(1L, QuantizedVector.of(floatArrayOf(1f, 0f, 0f, 0f)))
        index.put(2L, QuantizedVector.of(floatArrayOf(0f, 1f, 0f, 0f)))
        index.put(3L, QuantizedVector.of(floatArrayOf(0.9f, 0.1f, 0f, 0f).normalizedL2()))
        val query = floatArrayOf(1f, 0f, 0f, 0f)

        assertEquals(listOf(1L, 3L, 2L), index.search(query, 10).map { it.id })
        assertEquals(listOf(1L, 3L), index.search(query, 10, minScore = 0.5f).map { it.id })
        assertEquals(listOf(3L, 2L), index.search(query, 10, allowed = { it != 1L }).map { it.id })

        index.remove(1L)
        assertFalse(index.contains(1L))
        assertEquals(2, index.size)
        // The last slot moved into the removed one: still found, with its own vector.
        assertEquals(listOf(3L, 2L), index.search(query, 10).map { it.id })

        index.put(3L, QuantizedVector.of(floatArrayOf(0f, 0f, 1f, 0f)))
        assertEquals(2, index.size)
        assertEquals(2L, index.search(floatArrayOf(0f, 1f, 0f, 0f), 1).single().id)
    }

    @Test
    fun growsPastItsInitialCapacity() {
        val rnd = Random(3)
        val index = SmartIndex(8)
        for (id in 0L until 1000L) index.put(id, QuantizedVector.of(randomUnit(rnd, 8)))
        assertEquals(1000, index.size)
        assertEquals(1000, index.ids().size)
    }
}
