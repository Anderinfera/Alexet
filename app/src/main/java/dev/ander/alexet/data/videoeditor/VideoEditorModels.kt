package dev.ander.alexet.data.videoeditor

import android.net.Uri
import dev.ander.alexet.R
import dev.ander.alexet.data.GalleryMedia
import java.util.UUID

enum class VideoProjectAspectRatio(val labelRes: Int, val ratio: Float?) {
    ORIGINAL(R.string.aspect_original, null),
    RATIO_16_9(R.string.aspect_16_9, 16f / 9f),
    RATIO_9_16(R.string.aspect_9_16, 9f / 16f),
    RATIO_1_1(R.string.aspect_square, 1f),
    RATIO_4_3(R.string.aspect_4_3, 4f / 3f),
    RATIO_21_9(R.string.aspect_21_9, 21f / 9f),
}

enum class ExportFormat(val extension: String, val mimeType: String) {
    MP4("mp4", "video/mp4"),
    GIF("gif", "image/gif"),
    AUDIO_ONLY("m4a", "audio/mp4"),
}

enum class ExportResolution(val label: String, val targetHeight: Int?) {
    ORIGINAL("Original", null),
    RES_4K("4K (2160p)", 2160),
    RES_1080P("1080p Full HD", 1080),
    RES_720P("720p HD", 720),
    RES_480P("480p SD", 480),
}

enum class ExportQuality(val bitrateMultiplier: Float) {
    HIGH(1.2f),
    MEDIUM(0.8f),
    LOW(0.4f),
}

data class VideoClipItem(
    val id: String = UUID.randomUUID().toString(),
    val media: GalleryMedia,
    val sourceDurationMs: Long = 0L,
    val timelineStartMs: Long = 0L,
    val startTrimMs: Long = 0L,
    val endTrimMs: Long = 0L,
    val speed: Float = 1.0f,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val gamma: Float = 1.0f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoomScale: Float = 1.0f,
) {
    val trimmedDurationMs: Long
        get() {
            val raw = (endTrimMs - startTrimMs).coerceAtLeast(100L)
            return (raw / speed.coerceIn(0.2f, 5.0f)).toLong()
        }
}

data class AudioTrackItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val displayName: String,
    val sourceDurationMs: Long = 0L,
    val timelineStartMs: Long = 0L,
    val startTrimMs: Long = 0L,
    val endTrimMs: Long = 0L,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val isVideoSource: Boolean = false,
) {
    val trimmedDurationMs: Long
        get() = (endTrimMs - startTrimMs).coerceAtLeast(100L)
}

data class TextOverlayItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val textColor: Long = 0xFFFFFFFF,
    val bgColor: Long = 0x99000000,
    val fontSize: Float = 22f,
    val startTimelineMs: Long = 0L,
    val endTimelineMs: Long = 5000L,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val scale: Float = 1.0f,
    val rotationDeg: Float = 0f,
    // Style, as in the photo editor's text panel. Bold by default: texts were always bold before.
    val alignment: Int = 1, // 0: left, 1: centre, 2: right
    val isBold: Boolean = true,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrikethrough: Boolean = false,
)

data class ImageOverlayItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val startTimelineMs: Long = 0L,
    val endTimelineMs: Long = 5000L,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val scale: Float = 1.0f,
    val rotationDeg: Float = 0f,
)

data class VideoExportConfig(
    val format: ExportFormat = ExportFormat.MP4,
    val resolution: ExportResolution = ExportResolution.ORIGINAL,
    val qualityPercent: Int = 85, // 10% .. 100%
    val quality: ExportQuality = ExportQuality.HIGH,
    val fps: Int = 30,
    val aspectRatio: VideoProjectAspectRatio = VideoProjectAspectRatio.ORIGINAL,
)

data class VideoProjectState(
    val clips: List<VideoClipItem> = emptyList(),
    val audioTracks: List<AudioTrackItem> = emptyList(),
    val textOverlays: List<TextOverlayItem> = emptyList(),
    val imageOverlays: List<ImageOverlayItem> = emptyList(),
    val selectedClipIndex: Int = 0,
    val selectedTextId: String? = null,
    val selectedImageId: String? = null,
    val selectedAudioId: String? = null,
    val aspectRatio: VideoProjectAspectRatio = VideoProjectAspectRatio.ORIGINAL,
    val isFramingMode: Boolean = false,
    /** How the editor showed the project, attached at export (see [PreviewGeometry]). */
    val preview: PreviewGeometry? = null,
) {
    val totalTimelineDurationMs: Long
        get() {
            val maxClip = clips.maxOfOrNull { it.timelineStartMs + it.trimmedDurationMs } ?: 0L
            val maxAudio = audioTracks.maxOfOrNull { it.timelineStartMs + it.trimmedDurationMs } ?: 0L
            val maxText = textOverlays.maxOfOrNull { it.endTimelineMs } ?: 0L
            val maxImage = imageOverlays.maxOfOrNull { it.endTimelineMs } ?: 0L
            return maxOf(maxClip, maxAudio, maxText, maxImage, 1000L)
        }

    val selectedClip: VideoClipItem?
        get() = clips.getOrNull(selectedClipIndex)
}

/**
 * The editor preview the user arranged things in. Pan is stored in preview pixels and overlay
 * sizes in dp, so the export needs this to reproduce what was on screen.
 */
data class PreviewGeometry(
    /** Area the video is shown in (it fills it, cropped). */
    val containerWidthPx: Float,
    val containerHeightPx: Float,
    /** The output frame as drawn over the preview (overlay coordinates). */
    val frameWidthPx: Float,
    val frameHeightPx: Float,
    val density: Float,
    val fontScale: Float,
    /** Width / height of each clip's video as displayed (rotation tag applied), by media id. */
    val naturalAspects: Map<Long, Float>,
)

/**
 * A clip's clockwise rotation as the export applies it: whole quarter turns (90 and 270 swap the
 * frame's sides), the rest turned inside that frame. Preview and export both use this, so a free
 * angle such as 60 or 91 degrees gives the same frame in both.
 */
object ClipRotation {
    fun quarterTurns(rotation: Int): Int = Math.floorMod(rotation, 360) / 90 * 90

    fun swapsSides(rotation: Int): Boolean = quarterTurns(rotation).let { it == 90 || it == 270 }
}
