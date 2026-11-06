package dev.ander.alexet.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadMediaFilterTest {

    @Test
    fun acceptsImagesAndVideos() {
        assertTrue(DownloadMediaFilter.isMediaDownload("wideo.mp4"))
        assertTrue(DownloadMediaFilter.isMediaDownload("foto.JPG"))
        assertTrue(DownloadMediaFilter.isMediaDownload("film.mkv.crdownload"))
        assertTrue(DownloadMediaFilter.isMediaDownload("klip.webm.part"))
        assertTrue(DownloadMediaFilter.isMediaDownload("/storage/emulated/0/Download/shot.webp.tmp"))
        assertTrue(DownloadMediaFilter.isMediaDownload("clip.mp4.part.crdownload"))
    }

    @Test
    fun rejectsUnsupportedAndHidden() {
        assertFalse(DownloadMediaFilter.isMediaDownload("plik.apk"))
        assertFalse(DownloadMediaFilter.isMediaDownload("archiwum.zip"))
        assertFalse(DownloadMediaFilter.isMediaDownload("dokument.pdf"))
        assertFalse(DownloadMediaFilter.isMediaDownload("utwor.mp3"))
        assertFalse(DownloadMediaFilter.isMediaDownload("notatka.md"))
        assertFalse(DownloadMediaFilter.isMediaDownload("notatka.txt"))
        assertFalse(DownloadMediaFilter.isMediaDownload(".hidden.jpg"))
        assertFalse(DownloadMediaFilter.isMediaDownload("Unconfirmed 12345.crdownload"))
        assertFalse(DownloadMediaFilter.isMediaDownload("   "))
    }

    @Test
    fun mimeOverridesFilename() {
        assertTrue(DownloadMediaFilter.isMediaDownload("noext", mime = "video/mp4"))
        assertTrue(DownloadMediaFilter.isMediaDownload("clip.mp4", mime = "application/octet-stream"))
        assertFalse(DownloadMediaFilter.isMediaDownload("notes.txt", mime = "text/plain"))
    }

    @Test
    fun rejectsDeviceGeneratedPaths() {
        assertFalse(DownloadMediaFilter.isMediaDownload("/storage/emulated/0/Pictures/Screenshots/Screenshot_20260928-102351.png"))
        assertFalse(DownloadMediaFilter.isMediaDownload("/storage/emulated/0/Pictures/Screenshots/Screenshot_20260928-102351.png", mime = "image/png"))
        assertFalse(DownloadMediaFilter.isMediaDownload("/storage/emulated/0/DCIM/Camera/IMG_20260928_102351.jpg"))
        assertFalse(DownloadMediaFilter.isMediaDownload("/storage/emulated/0/DCIM/Camera/VID_20260928_102351.mp4", mime = "video/mp4"))
        assertFalse(DownloadMediaFilter.isMediaDownload("/storage/emulated/0/Movies/Screenrecords/recording.mp4"))
        assertFalse(DownloadMediaFilter.isMediaDownload("/storage/emulated/0/Movies/Screen_Recordings/record.mp4"))
        assertFalse(DownloadMediaFilter.isMediaDownload("/storage/emulated/0/Pictures/Restored/restored.jpg"))
    }
}
