package dev.ander.alexet.data.smartsearch

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** An image embedding stored as int8 with one scale: value = vec[i] * scale. */
class QuantizedVector(val vec: ByteArray, val scale: Float) {
    companion object {
        fun of(values: FloatArray): QuantizedVector {
            var max = 0f
            for (v in values) max = maxOf(max, abs(v))
            val scale = if (max == 0f) 1f else max / 127f
            val out = ByteArray(values.size) { i -> (values[i] / scale).roundToInt().coerceIn(-127, 127).toByte() }
            return QuantizedVector(out, scale)
        }
    }
}

fun FloatArray.normalizedL2(): FloatArray {
    var sum = 0.0
    for (v in this) sum += v * v
    val norm = sqrt(sum).toFloat()
    if (norm == 0f) return this
    return FloatArray(size) { this[it] / norm }
}

/**
 * Every indexed image's embedding in memory (int8, ~0.8 KB each), searched by brute force:
 * the cosine of L2-normalized vectors is their dot product.
 */
class SmartIndex(val dim: Int) {
    class Hit(val id: Long, val score: Float)

    private var ids = LongArray(INITIAL)
    private var vecs = ByteArray(INITIAL * dim)
    private var scales = FloatArray(INITIAL)
    private val slotOf = HashMap<Long, Int>()
    var size = 0
        private set

    @Synchronized
    fun contains(id: Long): Boolean = id in slotOf

    @Synchronized
    fun put(id: Long, v: QuantizedVector) {
        require(v.vec.size == dim) { "Vector size ${v.vec.size}, expected $dim" }
        val slot = slotOf[id] ?: run {
            ensureCapacity(size + 1)
            val s = size++
            slotOf[id] = s
            ids[s] = id
            s
        }
        System.arraycopy(v.vec, 0, vecs, slot * dim, dim)
        scales[slot] = v.scale
    }

    @Synchronized
    fun remove(id: Long) {
        val slot = slotOf.remove(id) ?: return
        val last = --size
        if (slot != last) {
            ids[slot] = ids[last]
            scales[slot] = scales[last]
            System.arraycopy(vecs, last * dim, vecs, slot * dim, dim)
            slotOf[ids[slot]] = slot
        }
    }

    @Synchronized
    fun clear() {
        slotOf.clear()
        size = 0
    }

    @Synchronized
    fun ids(): Set<Long> = HashSet(slotOf.keys)

    /** The [limit] best matches of a normalized [query] among the ids [allowed] accepts, best first. */
    @Synchronized
    fun search(query: FloatArray, limit: Int, minScore: Float = -1f, allowed: ((Long) -> Boolean)? = null): List<Hit> {
        require(query.size == dim)
        if (limit <= 0) return emptyList()
        val heap = PriorityQueue<Hit>(limit + 1, compareBy { it.score })
        for (slot in 0 until size) {
            val id = ids[slot]
            if (allowed != null && !allowed(id)) continue
            var dot = 0f
            val base = slot * dim
            for (i in 0 until dim) dot += query[i] * vecs[base + i]
            val score = dot * scales[slot]
            if (score < minScore) continue
            if (heap.size < limit) {
                heap += Hit(id, score)
            } else if (score > heap.peek()!!.score) {
                heap.poll()
                heap += Hit(id, score)
            }
        }
        return heap.sortedByDescending { it.score }
    }

    /** A copy of every entry, for work that takes long (the index keeps being updated meanwhile). */
    @Synchronized
    fun snapshot(): dev.ander.alexet.data.DuplicateFinder.Embeddings =
        dev.ander.alexet.data.DuplicateFinder.Embeddings(
            ids = ids.copyOf(size),
            vectors = vecs.copyOf(size * dim),
            scales = scales.copyOf(size),
            dim = dim,
        )

    private fun ensureCapacity(n: Int) {
        if (n <= ids.size) return
        val cap = maxOf(n, ids.size * 2)
        ids = ids.copyOf(cap)
        scales = scales.copyOf(cap)
        vecs = vecs.copyOf(cap * dim)
    }

    private companion object {
        const val INITIAL = 256
    }
}
