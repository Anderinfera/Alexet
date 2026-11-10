package dev.ander.alexet.data

import dev.ander.alexet.data.videoeditor.ExportFormat
import dev.ander.alexet.data.videoeditor.ExportResolution
import dev.ander.alexet.data.videoeditor.PreviewGeometry
import dev.ander.alexet.data.videoeditor.VideoClipItem
import dev.ander.alexet.data.videoeditor.VideoExportConfig
import dev.ander.alexet.data.videoeditor.VideoExportPlanner
import dev.ander.alexet.data.videoeditor.VideoProjectAspectRatio
import dev.ander.alexet.data.videoeditor.VideoProjectState

/** What "Quick edit" does to one photo or video; everything is saved at once. */
data class QuickEditSettings(
    /** 10..100: the quality a photo is encoded with, or a video exported with. */
    val quality: Int = QuickEdit.DEFAULT_QUALITY,
    /** Photos: share of each side kept (1: full size), set when fitting to a size. */
    val scale: Float = 1f,
    /** Videos: the output resolution, set when fitting to a size. */
    val resolution: ExportResolution = ExportResolution.ORIGINAL,
    /** Videos: the file size fitted to; the export then gets the bitrate for it exactly. */
    val targetBytes: Long? = null,
    /** Clockwise: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    val ratio: VideoProjectAspectRatio = VideoProjectAspectRatio.ORIGINAL,
    /** How far the picture is moved in the frame: -1..1 of the room there is (0: centred). */
    val panX: Float = 0f,
    val panY: Float = 0f,
)

/**
 * Where a video's quick edit is being made: its new copy at [outputPath], written where it will
 * stay; null when the original is replaced (or the copy is made elsewhere first).
 */
data class QuickEditTarget(val source: GalleryMedia, val outputPath: String?)

/** A photo or video as quick edit needs it. */
data class QuickEditSource(
    /** With [width] and [height] as read from the file. */
    val media: GalleryMedia,
    /** Size as shown (the camera's rotation applied). */
    val width: Int,
    val height: Int,
    val durationMs: Long,
    /** Videos: the original's average bitrate (bits per second, picture and sound); 0 for a photo. */
    val bitrate: Long = 0L,
) {
    /** Width / height as shown. */
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
}

object QuickEdit {
    const val DEFAULT_QUALITY = 75
    const val MIN_QUALITY = 10
    const val MAX_QUALITY = 100

    /** The lowest video bitrate the export writes. */
    private const val MIN_VIDEO_BITRATE = 250_000L

    /** Photo sizes tried (share of each side) when the quality alone can't reach a file size. */
    val PHOTO_SCALES = listOf(1f, 0.75f, 0.5f, 0.35f, 0.25f)

    /** Not for GIFs (animated), private files or files that are no photo or video. */
    fun supports(media: GalleryMedia): Boolean =
        !media.isOtherFile && !media.isEncrypted && !isGif(media)

    fun isGif(media: GalleryMedia): Boolean =
        media.mimeType.equals("image/gif", ignoreCase = true) || media.displayName.endsWith(".gif", ignoreCase = true)

    /**
     * The format a photo is saved in: its own, except a PNG below full quality, which has no
     * quality setting and is saved as JPG.
     */
    fun photoExtension(media: GalleryMedia, quality: Int): String {
        val own = when (media.displayName.substringAfterLast('.', "").lowercase()) {
            "png" -> "png"
            "webp" -> "webp"
            else -> if (media.mimeType.equals("image/png", ignoreCase = true)) "png" else "jpg"
        }
        return if (own == "png" && quality < MAX_QUALITY) "jpg" else own
    }

    fun swapsSides(rotation: Int): Boolean = Math.floorMod(rotation, 180) == 90

    /** Width / height after the rotation. */
    fun shownAspect(aspect: Float, rotation: Int): Float = if (swapsSides(rotation)) 1f / aspect else aspect

    /** Width / height of what is saved. */
    fun frameAspect(aspect: Float, settings: QuickEditSettings): Float =
        settings.ratio.ratio ?: shownAspect(aspect, settings.rotation)

    /** Size of the (turned) picture covering a [frameW] x [frameH] frame. */
    fun coverSize(frameW: Float, frameH: Float, shownAspect: Float): Pair<Float, Float> =
        if (shownAspect > frameW / frameH) (frameH * shownAspect) to frameH else frameW to (frameW / shownAspect)

    /**
     * The crop of a [width] x [height] photo (in its own space, before the rotation, as the
     * editor keeps it) that gives the chosen proportions, with the picture moved by the pan.
     */
    fun photoCrop(width: Int, height: Int, settings: QuickEditSettings): CropRect {
        val ratio = settings.ratio.ratio
        if (ratio == null || width <= 0 || height <= 0) return CropRect()
        val w = width.toFloat()
        val h = height.toFloat()
        val sourceRatio = if (swapsSides(settings.rotation)) 1f / ratio else ratio
        val (cropW, cropH) = if (w / h > sourceRatio) (h * sourceRatio) to h else w to (w / sourceRatio)
        // The picture moved one way in the frame: the crop goes the other way.
        val outX = -settings.panX.coerceIn(-1f, 1f)
        val outY = -settings.panY.coerceIn(-1f, 1f)
        // Back from the turned picture to the photo's own directions (it turns clockwise).
        val (x, y) = when (Math.floorMod(settings.rotation, 360)) {
            90 -> outY to -outX
            180 -> -outX to -outY
            270 -> -outY to outX
            else -> outX to outY
        }
        val left = (w - cropW) / 2f * (1f + x)
        val top = (h - cropH) / 2f * (1f + y)
        return CropRect(left / w, top / h, (left + cropW) / w, (top + cropH) / h)
    }

    fun photoEditState(source: QuickEditSource, settings: QuickEditSettings): ImageEditState =
        ImageEditState(
            rotation = Math.floorMod(settings.rotation, 360),
            crop = photoCrop(source.width, source.height, settings),
        )

    /** The whole video as the one clip of an editor project, with the same turn and frame. */
    fun videoProject(source: QuickEditSource, settings: QuickEditSettings): VideoProjectState {
        val duration = source.durationMs.coerceAtLeast(0L)
        // The editor keeps the pan in pixels of its preview: here a preview 1000 px wide.
        val frameW = 1000f
        val frameH = frameW / frameAspect(source.aspect, settings)
        val (coverW, coverH) = coverSize(frameW, frameH, shownAspect(source.aspect, settings.rotation))
        val clip = VideoClipItem(
            media = source.media,
            sourceDurationMs = source.durationMs,
            startTrimMs = 0L,
            endTrimMs = duration,
            rotation = Math.floorMod(settings.rotation, 360),
            panX = settings.panX.coerceIn(-1f, 1f) * (coverW - frameW) / 2f,
            panY = settings.panY.coerceIn(-1f, 1f) * (coverH - frameH) / 2f,
        )
        return VideoProjectState(
            clips = listOf(clip),
            aspectRatio = settings.ratio,
            preview = PreviewGeometry(
                containerWidthPx = frameW,
                containerHeightPx = frameH,
                frameWidthPx = frameW,
                frameHeightPx = frameH,
                density = 1f,
                fontScale = 1f,
                naturalAspects = mapOf(source.media.id to source.aspect),
            ),
        )
    }

    /**
     * The export settings. The quality is a share of the original's bitrate (100%: the same), and
     * the original's frame rate is kept. A size fitted to gives the exact bitrate for it, kept
     * constant, so the file comes out at that size (with a quality the encoder may stay under it).
     */
    fun videoConfig(source: QuickEditSource, settings: QuickEditSettings): VideoExportConfig {
        val exactBitrate = settings.targetBytes?.let { VideoExportPlanner.videoBitrateForSize(it, source.durationMs.coerceAtLeast(0L)) }
        return VideoExportConfig(
            format = ExportFormat.MP4,
            resolution = settings.resolution,
            qualityPercent = settings.quality.coerceIn(MIN_QUALITY, MAX_QUALITY),
            // 0: no frames dropped (the export's 30 per second otherwise).
            fps = 0,
            aspectRatio = settings.ratio,
            videoBitrate = exactBitrate ?: qualityBitrate(source, settings),
            constantBitrate = exactBitrate != null,
        )
    }

    /** The original's video bitrate: its average less the sound the export writes. */
    fun originalVideoBitrate(source: QuickEditSource): Long =
        (source.bitrate - VideoExportPlanner.AUDIO_BITRATE).coerceAtLeast(MIN_VIDEO_BITRATE)

    /**
     * The video bitrate of the chosen quality: that share of the original's, and at a lower
     * resolution as much per pixel as the original has.
     */
    fun qualityBitrate(source: QuickEditSource, settings: QuickEditSettings): Int {
        val own = ownHeight(source, settings)
        val target = settings.resolution.targetHeight
        val pixelShare = if (target == null || own <= 0 || target >= own) 1.0 else (target.toDouble() / own).let { it * it }
        val quality = settings.quality.coerceIn(MIN_QUALITY, MAX_QUALITY) / 100.0
        return (originalVideoBitrate(source) * pixelShare * quality).toLong()
            .coerceIn(MIN_VIDEO_BITRATE, 100_000_000L).toInt()
    }

    /** The height the export gives "Original" (see VideoExportEngine.outputSize). */
    private fun ownHeight(source: QuickEditSource, settings: QuickEditSettings): Int {
        val shown = shownAspect(source.aspect, settings.rotation)
        return if (shown >= 1f) minOf(source.width, source.height) else maxOf(source.width, source.height)
    }

    /** The resolutions a video can be brought down to, its own first (never a bigger one). */
    fun videoResolutions(source: QuickEditSource, settings: QuickEditSettings): List<ExportResolution> {
        val ownHeight = ownHeight(source, settings)
        val smaller = listOf(ExportResolution.RES_1080P, ExportResolution.RES_720P, ExportResolution.RES_480P)
            .filter { (it.targetHeight ?: 0) < ownHeight }
        return listOf(ExportResolution.ORIGINAL) + smaller
    }

    /** [option]: the photo scale or video resolution used; [reached]: false when even the smallest is bigger. */
    data class Fit<T>(val option: T, val quality: Int, val reached: Boolean)

    /**
     * The first of [options] (best first) and the highest quality whose [sizeAt] is at most
     * [targetBytes]. When none gets there: the last option at the lowest quality (the smallest file).
     */
    suspend fun <T> fitToSize(targetBytes: Long, options: List<T>, sizeAt: suspend (T, Int) -> Long): Fit<T>? {
        if (options.isEmpty()) return null
        for (option in options) {
            if (sizeAt(option, MIN_QUALITY) > targetBytes) continue
            var low = MIN_QUALITY
            var high = MAX_QUALITY
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (sizeAt(option, mid) <= targetBytes) low = mid else high = mid - 1
            }
            return Fit(option, low, reached = true)
        }
        return Fit(options.last(), MIN_QUALITY, reached = false)
    }
}
