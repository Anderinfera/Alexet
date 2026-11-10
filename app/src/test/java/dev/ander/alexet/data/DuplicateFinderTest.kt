package dev.ander.alexet.data

import android.net.DummyUri
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {

    private fun media(
        id: Long,
        size: Long,
        path: String = "/storage/emulated/0/DCIM/f$id.jpg",
        width: Int = 100,
        height: Int = 100,
        taken: Long = 1_000L,
    ) = GalleryMedia(
        id = id,
        uri = DummyUri("content://media/external/images/media/$id"),
        displayName = path.substringAfterLast('/'),
        mimeType = "image/jpeg",
        dateTakenMs = taken,
        dateModifiedMs = taken,
        dateAddedMs = taken,
        sizeBytes = size,
        width = width,
        height = height,
        durationMs = 0L,
        bucketId = 1L,
        bucketName = "Camera",
        relativePath = "DCIM/",
        absolutePath = path,
    )

    private fun bytes(seed: Int, size: Int) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    @Test
    fun theSameFileListedTwiceIsNotItsOwnDuplicate() {
        val a = media(1, 10, path = "/storage/emulated/0/DCIM/a.jpg")
        val sameFile = media(2, 10, path = "/storage/emulated/0/DCIM/A.jpg")
        val other = media(3, 10, path = "/storage/emulated/0/DCIM/b.jpg")
        assertEquals(listOf(1L, 3L), DuplicateFinder.candidates(listOf(a, sameFile, other)).map { it.id })
    }

    @Test
    fun privateTrashedAndEmptyFilesAreLeftOut() {
        val files = listOf(
            media(1, 10, path = "/storage/emulated/0/Safe/a.jpg.enc"),
            media(2, 10, path = "/storage/emulated/0/.alexet_trash/b.jpg"),
            media(3, 0),
            media(4, 10),
        )
        assertEquals(listOf(4L), DuplicateFinder.candidates(files).map { it.id })
    }

    @Test
    fun filesWithTheSameBytesAreGroupedWhateverTheirNames() = runBlocking {
        // Larger than two pieces: the middle differs for the third file only.
        val size = 200 * 1024
        val content = bytes(7, size)
        val changedInTheMiddle = content.copyOf().also { it[size / 2] = (it[size / 2] + 1).toByte() }
        val files = listOf(media(1, size.toLong()), media(2, size.toLong()), media(3, size.toLong()), media(4, 5))
        val data = mapOf(1L to content, 2L to content.copyOf(), 3L to changedInTheMiddle, 4L to bytes(1, 5))
        val groups = DuplicateFinder.identicalGroups(files, { ByteArrayInputStream(data.getValue(it.id)) }) {}
        assertEquals(1, groups.size)
        assertEquals(setOf(1L, 2L), groups.single().map { it.id }.toSet())
    }

    @Test
    fun unreadableFilesAreNeverReportedAsTheSame() = runBlocking {
        val files = listOf(media(1, 10), media(2, 10))
        val groups = DuplicateFinder.identicalGroups(files, { null }) {}
        assertTrue(groups.isEmpty())
    }

    @Test
    fun photosThatLookAlikeArePaired() = runBlocking {
        val dim = 64
        val base = FloatArray(dim) { kotlin.math.sin(it.toFloat()) }.normalized()
        val near = FloatArray(dim) { base[it] + if (it == 3) 0.05f else 0f }.normalized()
        val far = FloatArray(dim) { kotlin.math.cos(it * 1.7f) }.normalized()
        val vectors = listOf(base, near, far).map { dev.ander.alexet.data.smartsearch.QuantizedVector.of(it) }
        val e = DuplicateFinder.Embeddings(
            ids = longArrayOf(10, 11, 12),
            vectors = vectors.flatMap { it.vec.toList() }.toByteArray(),
            scales = vectors.map { it.scale }.toFloatArray(),
            dim = dim,
        )
        val pairs = DuplicateFinder.similarPairs(e, 0.95f, allowed = { true }) {}
        assertEquals(listOf(10L to 11L), pairs)
        // Ids left out are not compared.
        assertTrue(DuplicateFinder.similarPairs(e, 0.95f, allowed = { it != 11L }) {}.isEmpty())
    }

    @Test
    fun theSignatureMarginKeepsAlikePairs() {
        // A pair just at the threshold differs in about 30 of 256 bits: the cut is well above.
        val bits = DuplicateFinder.maxDifferingBits(DuplicateFinder.SIMILAR_MIN_COSINE)
        assertTrue(bits in 45..70)
    }

    @Test
    fun linkedFilesMakeOneGroupWithTheBestCopyFirst() {
        val small = media(1, 1_000, width = 100, height = 100, taken = 5)
        val copy = media(2, 1_000, width = 100, height = 100, taken = 9)
        val large = media(3, 9_000, width = 400, height = 300)
        val alone = media(4, 1_000)
        val byId = listOf(small, copy, large, alone).associateBy { it.id }
        val groups = DuplicateFinder.merge(byId, identical = listOf(listOf(small, copy)), similar = listOf(2L to 3L))
        assertEquals(1, groups.size)
        assertEquals(listOf(3L, 1L, 2L), groups.single().items.map { it.id })
        // The resized copy only looks alike: not all of the group has the same bytes.
        assertFalse(groups.single().identical)
    }

    @Test
    fun aGroupOfCopiesIsIdenticalAndTheOldestStays() {
        val newer = media(1, 1_000, taken = 9)
        val older = media(2, 1_000, taken = 5)
        val groups = DuplicateFinder.merge(mapOf(1L to newer, 2L to older), identical = listOf(listOf(newer, older)), similar = emptyList())
        assertTrue(groups.single().identical)
        assertEquals(2L, groups.single().items.first().id)
    }

    private fun FloatArray.normalized(): FloatArray {
        val n = kotlin.math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(size) { this[it] / n }
    }
}
