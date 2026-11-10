package dev.ander.alexet.data

import android.net.DummyUri
import dev.ander.alexet.data.videoeditor.VideoProjectAspectRatio
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickEditTest {

    private fun media(name: String, mime: String, w: Int = 1920, h: Int = 1080) = GalleryMedia(
        id = 7L,
        uri = DummyUri(),
        displayName = name,
        mimeType = mime,
        dateTakenMs = 0L,
        dateModifiedMs = 0L,
        dateAddedMs = 0L,
        sizeBytes = 1000L,
        width = w,
        height = h,
        durationMs = 100_000L,
        bucketId = 1L,
        bucketName = "x",
        relativePath = "Movies/",
    )

    @Test
    fun squareCropOfLandscapePhotoIsCentredAndFollowsThePan() {
        val square = QuickEditSettings(ratio = VideoProjectAspectRatio.RATIO_1_1)
        val centred = QuickEdit.photoCrop(4000, 3000, square)
        assertEquals(0.125f, centred.left, 1e-4f)
        assertEquals(0.875f, centred.right, 1e-4f)
        assertEquals(0f, centred.top, 1e-4f)
        assertEquals(1f, centred.bottom, 1e-4f)
        // The picture moved right as far as it goes: the left part of the photo is kept.
        val moved = QuickEdit.photoCrop(4000, 3000, square.copy(panX = 1f))
        assertEquals(0f, moved.left, 1e-4f)
        assertEquals(0.75f, moved.right, 1e-4f)
    }

    @Test
    fun cropOfTurnedPhotoIsInThePhotosOwnDirections() {
        // A portrait photo turned a quarter clockwise is landscape; 16:9 of it.
        val settings = QuickEditSettings(rotation = 90, ratio = VideoProjectAspectRatio.RATIO_16_9)
        val centred = QuickEdit.photoCrop(3000, 4000, settings)
        assertEquals(0.125f, centred.left, 1e-4f)
        assertEquals(0.875f, centred.right, 1e-4f)
        // Moved down in the turned picture: the photo's left side (its top once turned) is kept.
        val moved = QuickEdit.photoCrop(3000, 4000, settings.copy(panY = 1f))
        assertEquals(0f, moved.left, 1e-4f)
        assertEquals(0.75f, moved.right, 1e-4f)
    }

    @Test
    fun originalProportionsKeepTheWholePhoto() {
        assertEquals(CropRect(), QuickEdit.photoCrop(4000, 3000, QuickEditSettings(rotation = 90)))
    }

    @Test
    fun videoProjectKeepsTheWholeVideoWithTurnAndFrame() {
        val video = media("clip.mp4", "video/mp4")
        val source = QuickEditSource(video, 1920, 1080, 100_000L)
        val project = QuickEdit.videoProject(
            source,
            QuickEditSettings(ratio = VideoProjectAspectRatio.RATIO_1_1, panX = 1f),
        )
        val clip = project.clips.single()
        assertEquals(0L, clip.startTrimMs)
        assertEquals(100_000L, clip.endTrimMs)
        assertEquals(VideoProjectAspectRatio.RATIO_1_1, project.aspectRatio)
        // A 1000 px square frame; the 16:9 picture covering it is 1777.8 px wide.
        assertEquals((1000f * 1920f / 1080f - 1000f) / 2f, clip.panX, 0.01f)
        assertEquals(0f, clip.panY, 0.001f)
    }

    @Test
    fun fitFindsHighestQualityUnderTheTarget() = runBlocking {
        val sizeAt: suspend (Float, Int) -> Long = { option, quality -> (option * quality * 1000).toLong() }
        val fit = QuickEdit.fitToSize(60_000L, listOf(1f, 0.5f), sizeAt)!!
        assertEquals(1f, fit.option)
        assertEquals(60, fit.quality)
        assertTrue(fit.reached)
        // Even the smallest is bigger: the smallest file is chosen.
        val smallest = QuickEdit.fitToSize(4_000L, listOf(1f, 0.5f), sizeAt)!!
        assertEquals(0.5f, smallest.option)
        assertEquals(QuickEdit.MIN_QUALITY, smallest.quality)
        assertFalse(smallest.reached)
        // Too small at full size, fits when smaller.
        val smaller = QuickEdit.fitToSize(8_000L, listOf(1f, 0.5f), sizeAt)!!
        assertEquals(0.5f, smaller.option)
        assertEquals(16, smaller.quality)
    }

    @Test
    fun pngBelowFullQualityIsSavedAsJpg() {
        assertEquals("jpg", QuickEdit.photoExtension(media("a.png", "image/png"), 75))
        assertEquals("png", QuickEdit.photoExtension(media("a.png", "image/png"), 100))
        assertEquals("webp", QuickEdit.photoExtension(media("a.webp", "image/webp"), 75))
        assertEquals("jpg", QuickEdit.photoExtension(media("a.heic", "image/heic"), 75))
    }

    @Test
    fun gifsAreNotOffered() {
        assertFalse(QuickEdit.supports(media("a.gif", "image/gif")))
        assertTrue(QuickEdit.supports(media("a.jpg", "image/jpeg")))
    }

    @Test
    fun fittedVideoSizeGivesExactConstantBitrate() {
        val bitrate = dev.ander.alexet.data.videoeditor.VideoExportPlanner.videoBitrateForSize(8_567_870L, 60_000L)
        assertEquals(1_000_000.0, bitrate.toDouble(), 2.0)

        val source = QuickEditSource(media("clip.mp4", "video/mp4", 3840, 2160), 3840, 2160, 362_219L, bitrate = 25_280_000L)
        val target = 999L * 1024L * 1024L
        val settings = QuickEditSettings(quality = 100, targetBytes = target)
        val config = QuickEdit.videoConfig(source, settings)
        assertTrue(config.constantBitrate)
        // The estimate shown is the size fitted to.
        val estimate = dev.ander.alexet.data.videoeditor.VideoExportEngine.estimatedSizeBytes(
            QuickEdit.videoProject(source, settings),
            config,
        )!!
        assertEquals(target.toDouble(), estimate.toDouble(), target * 0.001)
        // Without a fitted size: the quality's bitrate, variable.
        assertFalse(QuickEdit.videoConfig(source, settings.copy(targetBytes = null)).constantBitrate)
    }

    @Test
    fun fullQualityKeepsTheOriginalsBitrateAndFrames() {
        // 1 144 782 116 bytes over 362.2 s: about 25.3 Mb/s, as the 4K test film.
        val bitrate = 1_144_782_116L * 8_000L / 362_219L
        val source = QuickEditSource(media("film.mp4", "video/mp4", 3840, 2160), 3840, 2160, 362_219L, bitrate)
        val config = QuickEdit.videoConfig(source, QuickEditSettings(quality = 100))
        assertEquals(0, config.fps)
        assertFalse(config.constantBitrate)
        val full = dev.ander.alexet.data.videoeditor.VideoExportEngine.estimatedSizeBytes(
            QuickEdit.videoProject(source, QuickEditSettings(quality = 100)),
            config,
        )!!
        // About the original's size (within the container's share).
        assertEquals(1_144_782_116.0, full.toDouble(), 1_144_782_116.0 * 0.02)
        // Half the quality: half the picture's bitrate.
        val half = QuickEdit.qualityBitrate(source, QuickEditSettings(quality = 50))
        assertEquals(QuickEdit.originalVideoBitrate(source) / 2.0, half.toDouble(), 2.0)
        // 1080p of a 4K film: a quarter of the pixels, a quarter of the bitrate at the same quality.
        val smaller = QuickEdit.qualityBitrate(
            source,
            QuickEditSettings(quality = 100, resolution = dev.ander.alexet.data.videoeditor.ExportResolution.RES_1080P),
        )
        assertEquals(QuickEdit.originalVideoBitrate(source) / 4.0, smaller.toDouble(), 2.0)
    }
}
