package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrashMarkerTest {

    @Test
    fun sameAsCaseInsensitiveContains() {
        val samples = listOf(
            "",
            ".",
            ".alexet_trash",
            ".ALEXET_TRASH",
            ".Alexet_Trash/x.jpg",
            "/storage/emulated/0/.alexet_trash/1.jpg",
            "/storage/emulated/0/DCIM/Camera/IMG_1.jpg",
            "DCIM/.alexet_tras",
            "DCIM/..alexet_trash",
            "a.b.c.alexet_trash.d",
            "alexet_trash",
            "x/.alexet_trashy/",
            "Pictures/.thumbnails/",
            ".alexet_trash".repeat(3),
        )
        for (s in samples) {
            assertEquals(s, s.contains(".alexet_trash", ignoreCase = true), hasTrashMarker(s))
        }
    }
}
