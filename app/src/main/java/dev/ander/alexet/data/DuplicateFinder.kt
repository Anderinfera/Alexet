package dev.ander.alexet.data

import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Finds the same files (same bytes, whatever their names) and photos that look alike (a burst,
 * another resolution of the same picture), the latter from the AI index's embeddings.
 * In each group the best copy is kept and the others are offered for the trash.
 */
object DuplicateFinder {

    class Group(
        /** Best first: the copy that stays. */
        val items: List<GalleryMedia>,
        /** Every file has the same bytes (otherwise the photos only look alike). */
        val identical: Boolean,
    )

    /** The AI index as plain arrays: [ids] with their int8 vectors of [dim] values and scales. */
    class Embeddings(val ids: LongArray, val vectors: ByteArray, val scales: FloatArray, val dim: Int) {
        val size: Int get() = ids.size
    }

    /** Photos at least this alike count as one picture (a burst, a resized copy). */
    const val SIMILAR_MIN_COSINE = 0.93f

    /** Size of the first and last pieces compared before whole files are read. */
    private const val PIECE = 64 * 1024

    /**
     * The groups found among [media] (photos and videos). [open] reads a file's bytes (null when it
     * can't be read); [embeddings], when there is an index, adds the photos that look alike.
     * [onProgress] gets 0..1.
     */
    suspend fun find(
        media: List<GalleryMedia>,
        open: (GalleryMedia) -> InputStream?,
        embeddings: Embeddings?,
        onProgress: (Float) -> Unit,
    ): List<Group> = withContext(Dispatchers.Default) {
        val files = candidates(media)
        val byId = files.associateBy { it.id }
        val similarWeight = if (embeddings != null && embeddings.size > 1) 0.5f else 0f
        val identical = identicalGroups(files, open) { onProgress(it * (1f - similarWeight)) }
        val similar = if (embeddings != null && similarWeight > 0f) {
            similarPairs(embeddings, SIMILAR_MIN_COSINE, allowed = { it in byId }) {
                onProgress(1f - similarWeight + it * similarWeight)
            }
        } else {
            emptyList()
        }
        onProgress(1f)
        merge(byId, identical, similar)
    }

    /**
     * Photos and videos only, each file once: the same file listed twice (two ids for one path)
     * must never be offered as its own duplicate.
     */
    internal fun candidates(media: List<GalleryMedia>): List<GalleryMedia> {
        val seen = HashSet<String>()
        return media.filter { m ->
            !m.isOtherFile && !m.isEncrypted && m.sizeBytes > 0L &&
                m.bucketId != AlbumIds.TRASH &&
                m.absolutePath?.let(::hasTrashMarker) != true &&
                seen.add(m.absolutePath?.lowercase() ?: m.uri.toString())
        }
    }

    /** Files with the same bytes: same size, then the same first and last pieces, then the same hash. */
    internal suspend fun identicalGroups(
        files: List<GalleryMedia>,
        open: (GalleryMedia) -> InputStream?,
        onProgress: (Float) -> Unit,
    ): List<List<GalleryMedia>> = coroutineScope {
        val sameSize = files.groupBy { it.sizeBytes }.values.filter { it.size > 1 }
        val total = sameSize.sumOf { it.size }.coerceAtLeast(1)
        var done = 0
        val result = ArrayList<List<GalleryMedia>>()
        for (group in sameSize) {
            ensureActive()
            val byPieces = group.groupBy { piecesKey(it, open) }.filterKeys { it != null }.values.filter { it.size > 1 }
            for (candidates in byPieces) {
                // Whole files only where the pieces matched (usually the real copies).
                val byHash = candidates.groupBy { fullHash(it, open) }.filterKeys { it != null }.values
                byHash.filter { it.size > 1 }.forEach(result::add)
            }
            done += group.size
            onProgress(done.toFloat() / total)
        }
        result
    }

    private fun piecesKey(media: GalleryMedia, open: (GalleryMedia) -> InputStream?): String? = runCatching {
        open(media)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(PIECE)
            digest.update(buffer, 0, input.readFully(buffer))
            val tailStart = media.sizeBytes - PIECE
            if (tailStart > PIECE) {
                var toSkip = tailStart - PIECE
                while (toSkip > 0) {
                    val skipped = input.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
                digest.update(buffer, 0, input.readFully(buffer))
            }
            digest.digest().toHex()
        }
    }.getOrNull()

    private fun fullHash(media: GalleryMedia, open: (GalleryMedia) -> InputStream?): String? = runCatching {
        open(media)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().toHex()
        }
    }.getOrNull()

    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Pairs of ids whose cosine is at least [minCosine]. Every pair is first compared by 256-bit
     * signatures (random hyperplanes: the share of differing bits follows the angle), and only
     * pairs close enough there by their vectors: a library of tens of thousands of photos takes
     * seconds instead of minutes.
     */
    internal suspend fun similarPairs(
        e: Embeddings,
        minCosine: Float,
        allowed: (Long) -> Boolean,
        onProgress: (Float) -> Unit,
    ): List<Pair<Long, Long>> = coroutineScope {
        val slots = (0 until e.size).filter { allowed(e.ids[it]) }.toIntArray()
        val n = slots.size
        if (n < 2) return@coroutineScope emptyList()
        val signatures = signatures(e, slots)
        val maxBits = maxDifferingBits(minCosine)
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val totalPairs = n.toLong() * (n - 1) / 2
        val donePairs = java.util.concurrent.atomic.AtomicLong(0)
        val parts = (0 until workers).map { w ->
            async {
                val found = ArrayList<Pair<Long, Long>>()
                // Rows dealt out in turn: the early rows have the most pairs.
                var i = w
                while (i < n) {
                    ensureActive()
                    val si = i * WORDS
                    for (j in i + 1 until n) {
                        val sj = j * WORDS
                        var bits = 0
                        for (k in 0 until WORDS) bits += java.lang.Long.bitCount(signatures[si + k] xor signatures[sj + k])
                        if (bits > maxBits) continue
                        if (cosine(e, slots[i], slots[j]) >= minCosine) found += e.ids[slots[i]] to e.ids[slots[j]]
                    }
                    val done = donePairs.addAndGet((n - 1 - i).toLong())
                    if (w == 0) onProgress(done.toFloat() / totalPairs)
                    i += workers
                }
                found
            }
        }
        parts.awaitAll().flatten()
    }

    private const val BITS = 256
    private const val WORDS = BITS / 64

    /** Largest number of differing signature bits for a pair at [minCosine], with a wide margin. */
    internal fun maxDifferingBits(minCosine: Float): Int {
        val p = acos(minCosine.toDouble().coerceIn(-1.0, 1.0)) / PI
        return ceil(BITS * p + 5 * sqrt(BITS * p * (1 - p))).toInt()
    }

    private suspend fun signatures(e: Embeddings, slots: IntArray): LongArray = coroutineScope {
        val random = java.util.Random(SIGNATURE_SEED)
        val planes = FloatArray(BITS * e.dim) { random.nextGaussian().toFloat() }
        val out = LongArray(slots.size * WORDS)
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        (0 until workers).map { w ->
            async {
                var i = w
                while (i < slots.size) {
                    ensureActive()
                    val base = slots[i] * e.dim
                    for (b in 0 until BITS) {
                        val pb = b * e.dim
                        var dot = 0f
                        for (d in 0 until e.dim) dot += planes[pb + d] * e.vectors[base + d]
                        if (dot >= 0f) {
                            val word = i * WORDS + b / 64
                            out[word] = out[word] or (1L shl (b % 64))
                        }
                    }
                    i += workers
                }
            }
        }.awaitAll()
        out
    }

    private const val SIGNATURE_SEED = 0x5EED_A1E7L

    private fun cosine(e: Embeddings, a: Int, b: Int): Float {
        val ba = a * e.dim
        val bb = b * e.dim
        var dot = 0
        for (d in 0 until e.dim) dot += e.vectors[ba + d] * e.vectors[bb + d]
        return dot * e.scales[a] * e.scales[b]
    }

    /** One group per set of linked files (same bytes or alike), the best copy first. */
    internal fun merge(
        byId: Map<Long, GalleryMedia>,
        identical: List<List<GalleryMedia>>,
        similar: List<Pair<Long, Long>>,
    ): List<Group> {
        val parent = HashMap<Long, Long>()
        fun root(x: Long): Long {
            var r = x
            while (true) {
                val p = parent[r]
                if (p == null || p == r) break
                r = p
            }
            // Shorter paths for the next look-ups.
            var c = x
            while (c != r) {
                val next = parent.getValue(c)
                parent[c] = r
                c = next
            }
            return r
        }
        fun union(a: Long, b: Long) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[ra] = rb
        }
        val sameBytes = HashMap<Long, Int>()
        identical.forEachIndexed { index, group ->
            group.forEach { sameBytes[it.id] = index }
            group.zipWithNext { a, b -> union(a.id, b.id) }
        }
        similar.forEach { (a, b) -> union(a, b) }
        val members = HashMap<Long, MutableList<GalleryMedia>>()
        (identical.flatten().map { it.id } + similar.flatMap { listOf(it.first, it.second) }).toSet().forEach { id ->
            val m = byId[id] ?: return@forEach
            members.getOrPut(root(id)) { ArrayList() } += m
        }
        return members.values
            .filter { it.size > 1 }
            .map { group ->
                val sorted = group.sortedWith(bestFirst)
                val firstSet = sameBytes[sorted.first().id]
                Group(sorted, identical = firstSet != null && sorted.all { sameBytes[it.id] == firstSet })
            }
            .sortedWith(compareByDescending<Group> { it.identical }.thenByDescending { it.items.first().capturedAtMs })
    }

    /** Most pixels, then the largest file, then the oldest (the original rather than a copy). */
    internal val bestFirst: Comparator<GalleryMedia> = compareByDescending<GalleryMedia> { it.width.toLong() * it.height }
        .thenByDescending { it.sizeBytes }
        .thenBy { it.capturedAtMs }
        .thenBy { it.id }
}
