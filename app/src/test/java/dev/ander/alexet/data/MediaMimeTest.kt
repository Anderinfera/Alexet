package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMimeTest {

    @Test
    fun generalSystemTypeOfVideoTakesTypeFromName() {
        assertEquals("video/mp4", NomediaScanner.mediaMimeOf("application/binary", "15_FRIEREN PATREON VER.mp4"))
        assertEquals("video/x-matroska", NomediaScanner.mediaMimeOf("application/octet-stream", "clip.mkv"))
        assertEquals("video/x-matroska", NomediaScanner.mediaMimeOf("application/x-matroska", "clip.MKV"))
    }

    @Test
    fun missingSystemTypeTakesTypeFromName() {
        assertEquals("image/jpeg", NomediaScanner.mediaMimeOf(null, "photo.jpg"))
        assertEquals("image/png", NomediaScanner.mediaMimeOf("", "photo.png"))
    }

    @Test
    fun specificSystemTypeIsKept() {
        assertEquals("video/mp4", NomediaScanner.mediaMimeOf("video/mp4", "clip.mkv"))
        assertEquals("audio/mp4", NomediaScanner.mediaMimeOf("audio/mp4", "voice.mp4"))
    }

    @Test
    fun videoNameCheckMatchesMimeOf() {
        val names = listOf(
            "clip.mp4", "clip.MP4", "clip.Mkv", "a.b.webm", ".mp4", "mp4", "clip.", "clip", "",
            "clip.mp4.jpg", "photo.jpg.mov", "clip.ts", "clip.TS", "x.3gp", "x.3GP", "x.m4v", "x.mpeg", "x.mpg",
            "x.avi", "x.avİ", "x.AVİ", "x.tſ", "x.mKv", "x.mp4 ", "x .mp4", "x.mp4",
            "x.heic", "x.tiff", "x.dng", "x.𐐀mp4", "x.mp", "x.p4", "x..mp4", "x.mp4.",
        )
        for (name in names) {
            val expected = NomediaScanner.mimeOf(name)?.startsWith("video/") == true
            assertEquals(name, expected, NomediaScanner.isVideoName(name))
        }
    }

    @Test
    fun fileNotNamedAsMediaKeepsSystemType() {
        assertEquals("application/pdf", NomediaScanner.mediaMimeOf("application/pdf", "doc.pdf"))
        assertEquals("application/binary", NomediaScanner.mediaMimeOf("application/binary", "data.bin"))
        assertNull(NomediaScanner.mediaMimeOf(null, "notes"))
    }
}
