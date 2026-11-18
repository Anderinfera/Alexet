package dev.ander.alexet.data

import android.net.DummyUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MosaicSnapTest {

    private fun source(w: Int, h: Int) = MosaicSource(
        GalleryMedia(
            id = 1L,
            uri = DummyUri(),
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            dateTakenMs = 0L,
            dateModifiedMs = 0L,
            dateAddedMs = 0L,
            sizeBytes = 1L,
            width = w,
            height = h,
            durationMs = 0L,
            bucketId = 1L,
            bucketName = "x",
            relativePath = "DCIM/",
        ),
        w,
        h,
    )

    @Test
    fun movedPieceSnapsSideBySideWithTheNearestEdge() {
        // Another photo from 0 to 1000: a 500-wide piece dropped at 1010 goes to 1000 (side by side).
        assertEquals(1000f, MosaicSnap.snapPosition(1010f, 500f, listOf(0f to 1000f), 30f), 0.001f)
        // Its end near the other's start: placed right before it.
        assertEquals(-500f, MosaicSnap.snapPosition(-520f, 500f, listOf(0f to 1000f), 30f), 0.001f)
        // Too far: stays where it was dropped.
        assertEquals(1100f, MosaicSnap.snapPosition(1100f, 500f, listOf(0f to 1000f), 30f), 0.001f)
    }

    @Test
    fun edgeSnapsOnlyWhenNear() {
        assertEquals(3000f, MosaicSnap.snapEdge(2990f, listOf(0f to 3000f), 20f)!!, 0.001f)
        assertNull(MosaicSnap.snapEdge(2900f, listOf(0f to 3000f), 20f))
    }

    @Test
    fun startsAsOneRowAndGrowsWithTheSizes() {
        val pieces = MosaicSnap.initialRow(listOf(source(4000, 3000), source(3000, 4000)))
        assertEquals(0f, pieces[0].x, 0f)
        assertEquals(4000f, pieces[1].x, 0f)
        val (w, h, factor) = MosaicRenderer.outputSize(pieces)
        assertEquals(7000, w)
        assertEquals(4000, h)
        assertEquals(1f, factor, 0f)
    }

    @Test
    fun tooBigIsMadeSmallerToTheLimit() {
        val pieces = MosaicSnap.initialRow(List(8) { source(4000, 3000) })
        val (w, h, factor) = MosaicRenderer.outputSize(pieces)
        assert(factor < 1f)
        assert(w.toLong() * h <= MosaicRenderer.MAX_PIXELS + w + h)
    }
}
