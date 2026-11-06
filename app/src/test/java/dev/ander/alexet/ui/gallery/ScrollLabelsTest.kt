package dev.ander.alexet.ui.gallery

import android.net.DummyUri
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.SortField
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollLabelsTest {

    private val format = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun media(name: String = "a.jpg", taken: Long = 0L, modified: Long = 0L, size: Long = 0L) = GalleryMedia(
        id = 1L,
        uri = DummyUri("content://media/external/images/media/1"),
        displayName = name,
        mimeType = "image/jpeg",
        dateTakenMs = taken,
        dateModifiedMs = modified,
        dateAddedMs = 0L,
        sizeBytes = size,
        width = 0,
        height = 0,
        durationMs = 0L,
        bucketId = 0L,
        bucketName = "",
        relativePath = "",
    )

    @Test
    fun datesUseTheSortedTimestamp() {
        // 2025-11-18 22:43 UTC
        val t = 1_763_505_780_000L
        assertEquals("18.11.2025, 22:43", ScrollLabels.label(media(taken = t), SortField.DATE_TAKEN, format))
        assertEquals("18.11.2025, 22:43", ScrollLabels.label(media(modified = t), SortField.DATE_MODIFIED, format))
        assertNull(ScrollLabels.label(media(), SortField.DATE_MODIFIED, format))
    }

    @Test
    fun namesShowTheInitial() {
        assertEquals("K", ScrollLabels.label(media("kot.png"), SortField.NAME, format))
        assertEquals("Ż", ScrollLabels.label(media("żaba.png"), SortField.PATH, format))
        assertEquals("3", ScrollLabels.label(media("314011536_n.mp4"), SortField.NAME, format))
        assertEquals("#", ScrollLabels.initialOf("_tmp.jpg"))
        assertEquals("#", ScrollLabels.initialOf(""))
    }

    @Test
    fun randomHasNoLabel() {
        assertNull(ScrollLabels.label(media(), SortField.RANDOM, format))
    }
}
