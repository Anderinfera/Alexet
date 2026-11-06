package dev.ander.alexet.data.videoeditor

import android.net.DummyUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.Transformer

@OptIn(UnstableApi::class)
class VideoExportPlannerTest {
    @Test
    fun frameDropEffectCreatesProperly() {
        val effect = FrameDropEffect.createDefaultFrameDropEffect(30f)
        org.junit.Assert.assertNotNull(effect)
        val factory = DefaultMuxer.Factory()
        val videoTypes = factory.getSupportedSampleMimeTypes(androidx.media3.common.C.TRACK_TYPE_VIDEO)
        val audioTypes = factory.getSupportedSampleMimeTypes(androidx.media3.common.C.TRACK_TYPE_AUDIO)
        assertTrue(videoTypes.contains(androidx.media3.common.MimeTypes.VIDEO_VP9))
        assertTrue(audioTypes.contains(androidx.media3.common.MimeTypes.AUDIO_OPUS))
    }

    private fun clip(
        start: Long,
        duration: Long,
        trimStart: Long = 0L,
        speed: Float = 1f,
    ) = VideoClipItem(
        media = dummyMedia(),
        sourceDurationMs = duration,
        timelineStartMs = start,
        startTrimMs = trimStart,
        endTrimMs = trimStart + duration,
        speed = speed,
    )

    private fun dummyMedia() = dev.ander.alexet.data.GalleryMedia(
        id = 0L,
        uri = DummyUri(),
        displayName = "c.mp4",
        mimeType = "video/mp4",
        dateTakenMs = 0L,
        dateModifiedMs = 0L,
        dateAddedMs = 0L,
        sizeBytes = 0L,
        width = 16,
        height = 9,
        durationMs = 1000L,
        bucketId = 1L,
        bucketName = "x",
        relativePath = "Movies/",
    )

    @Test
    fun concatenatesClipsWithLeadingAndMidGaps() {
        val clips = listOf(
            clip(start = 1000L, duration = 500L),
            clip(start = 2000L, duration = 250L),
        )
        val ops = VideoExportPlanner.planClipSequence(clips)
        assertEquals(4, ops.size)
        assertTrue(ops[0].isGap)
        assertEquals(1000L, ops[0].durationMs)
        assertFalse(ops[1].isGap)
        assertEquals(0, ops[1].index)
        assertEquals(500L, ops[1].durationMs)
        assertTrue(ops[2].isGap)
        assertEquals(500L, ops[2].durationMs)
        assertEquals(1, ops[3].index)
        assertEquals(250L, ops[3].durationMs)
    }

    @Test
    fun overlappingClipsConcatenatedWithoutNegativeGap() {
        val clips = listOf(
            clip(start = 0L, duration = 1000L),
            clip(start = 400L, duration = 200L),
        )
        val ops = VideoExportPlanner.planClipSequence(clips)
        assertEquals(2, ops.size)
        assertFalse(ops[0].isGap)
        assertFalse(ops[1].isGap)
        assertEquals(0, ops[0].index)
        assertEquals(1, ops[1].index)
     }

    @Test
    fun mutedAudioTrackOmitted() {
        val track = AudioTrackItem(
            uri = DummyUri(),
            displayName = "a.mp3",
            sourceDurationMs = 1000L,
            timelineStartMs = 250L,
            startTrimMs = 0L,
            endTrimMs = 1000L,
            volume = 1f,
            isMuted = true,
        )
        assertNull(VideoExportPlanner.planAudioSequence(track))
    }

    @Test
    fun audioTrackGetsLeadingGap() {
        val track = AudioTrackItem(
            uri = DummyUri(),
            displayName = "a.mp3",
            sourceDurationMs = 1000L,
            timelineStartMs = 250L,
            startTrimMs = 0L,
            endTrimMs = 1000L,
            volume = 0.5f,
        )
        val ops = VideoExportPlanner.planAudioSequence(track)!!
        assertEquals(2, ops.size)
        assertTrue(ops[0].isGap)
        assertEquals(250L, ops[0].durationMs)
        assertFalse(ops[1].isGap)
        assertEquals(1000L, ops[1].durationMs)
    }

    @Test
    fun bitrateUsesQualityPercentAndPreset() {
        val high = VideoExportPlanner.estimatedVideoBitrate(1080, 30, 100, ExportQuality.HIGH)
        val low = VideoExportPlanner.estimatedVideoBitrate(1080, 30, 40, ExportQuality.LOW)
        assertTrue(high > low)
        assertTrue(low >= 250_000)
        assertTrue(high <= 50_000_000)
    }

    @Test
    fun panToNdcScalesPreviewPixels() {
        assertEquals(0f, VideoExportPlanner.panToNdc(0f), 0.001f)
        assertEquals(1f, VideoExportPlanner.panToNdc(540f), 0.001f)
        assertEquals(-0.5f, VideoExportPlanner.panToNdc(-270f), 0.001f)
    }

    @Test
    fun clampTrimRangeGuardsShortAndZeroDuration() {
        assertEquals(0L to 0L, VideoExportPlanner.clampTrimRange(0L, 50L, 0L))
        assertEquals(0L to 40L, VideoExportPlanner.clampTrimRange(-10L, 999L, 40L))
        assertEquals(0L to 100L, VideoExportPlanner.clampTrimRange(0L, 50L, 100L))
        val normal = VideoExportPlanner.clampTrimRange(200L, 800L, 1000L)
        assertEquals(200L, normal.first)
        assertEquals(800L, normal.second)
        val inverted = VideoExportPlanner.clampTrimRange(900L, 100L, 1000L)
        assertEquals(900L, inverted.first)
        assertEquals(1000L, inverted.second)
    }

    @Test
    fun overlayFrameNormalizedUsesFrameNotContainer() {
        val (nx, ny) = VideoExportPlanner.overlayFrameNormalized(
            containerX = 150f,
            containerY = 50f,
            leftScrim = 100f,
            topScrim = 0f,
            frameWidth = 200f,
            frameHeight = 100f,
        )
        assertEquals(0.25f, nx, 0.001f)
        assertEquals(0.5f, ny, 0.001f)

        val (cx, cy) = VideoExportPlanner.overlayFrameToContainer(
            normalizedX = 0.25f,
            normalizedY = 0.5f,
            leftScrim = 100f,
            topScrim = 0f,
            frameWidth = 200f,
            frameHeight = 100f,
        )
        assertEquals(150f, cx, 0.001f)
        assertEquals(50f, cy, 0.001f)
    }

    @Test
    fun audioOnlyFormatRemovesVideoTrack() {
        assertTrue(VideoExportPlanner.removesVideoTrack(ExportFormat.AUDIO_ONLY))
        assertFalse(VideoExportPlanner.removesVideoTrack(ExportFormat.MP4))
        assertFalse(VideoExportPlanner.removesVideoTrack(ExportFormat.GIF))
    }

    @Test
    fun nextClipAfterSkipsEarlierClips() {
        val a = clip(start = 0L, duration = 500L)
        val b = clip(start = 1500L, duration = 250L)
        val next = VideoExportPlanner.nextClipAfter(listOf(a, b), afterTimelineMs = 500L)
        assertEquals(1500L, next?.timelineStartMs)
        assertNull(VideoExportPlanner.nextClipAfter(listOf(a, b), afterTimelineMs = 2000L))
    }

    @Test
    fun clipAtReturnsNullInGap() {
        val clips = listOf(
            clip(start = 0L, duration = 500L),
            clip(start = 1500L, duration = 250L),
        )
        assertNull(VideoExportPlanner.clipAt(clips, 800L))
        assertEquals(0L, VideoExportPlanner.clipAt(clips, 100L)?.timelineStartMs)
    }
}
