package dev.ander.alexet.data.videoeditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TimestampWrapper
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dev.ander.alexet.data.ImageEditEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
object VideoExportEngine {

    /** The tallest GIF made (the export dialog offers no 4K for GIF; a saved choice is capped here). */
    private const val MAX_GIF_HEIGHT = 1080

    suspend fun exportVideo(
        context: Context,
        project: VideoProjectState,
        config: VideoExportConfig,
        outputFile: File,
        onProgress: (Int) -> Unit,
    ): Boolean {
        if (project.clips.isEmpty()) return false

        if (config.format == ExportFormat.GIF) {
            return withContext(Dispatchers.IO) {
                exportGif(context, project, config, outputFile, onProgress)
            }
        }

        // Sticker and text bitmaps of the overlays: freed when the export is over, not left to GC.
        val overlayBitmaps = java.util.Collections.synchronizedList(ArrayList<Bitmap>())
        return try {
            val composition = withContext(Dispatchers.Default) {
                buildComposition(context, project, config, overlayBitmaps)
            }

            withContext(Dispatchers.Main) {
                val deferredResult = kotlinx.coroutines.CompletableDeferred<Boolean>()

                val transformerBuilder = Transformer.Builder(context)
                applyOutputMime(transformerBuilder, config)
                val bitrate = videoBitrate(project, config)
                transformerBuilder.setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setBitrate(bitrate)
                                .apply {
                                    // An encoder without it keeps its usual (variable) mode.
                                    if (config.constantBitrate) {
                                        setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                                    }
                                }
                                .build(),
                        )
                        .build(),
                )

                val transformer = transformerBuilder.build()

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        deferredResult.complete(true)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        dev.ander.alexet.data.AppLog.e("VideoExportEngine", "Transformer error: ${exportException.errorCodeName}", exportException)
                        deferredResult.complete(false)
                    }
                }

                transformer.addListener(listener)
                try {
                    transformer.start(composition, outputFile.absolutePath)

                    val progressHolder = ProgressHolder()
                    while (!deferredResult.isCompleted) {
                        val progressState = transformer.getProgress(progressHolder)
                        if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress)
                        }
                        delay(200)
                    }

                    val success = deferredResult.await()
                    if (success) {
                        onProgress(100)
                    } else {
                        outputFile.delete()
                    }
                    success
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    transformer.cancel()
                    outputFile.delete()
                    throw ce
                } catch (e: Exception) {
                    transformer.cancel()
                    outputFile.delete()
                    false
                } finally {
                    transformer.removeListener(listener)
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            outputFile.delete()
            throw ce
        } catch (e: Exception) {
            dev.ander.alexet.data.AppLog.e("VideoExportEngine", "Export exception", e)
            outputFile.delete()
            false
        } finally {
            // The transformer is done with them (completed, failed or cancelled and released).
            synchronized(overlayBitmaps) { overlayBitmaps.forEach { if (!it.isRecycled) it.recycle() } }
        }
    }

    /**
     * About how big the export will be, in bytes (shown before it starts). Null for a GIF, whose
     * size depends mostly on what the picture shows.
     */
    fun estimatedSizeBytes(project: VideoProjectState, config: VideoExportConfig): Long? {
        if (project.clips.isEmpty() || config.format == ExportFormat.GIF) return null
        val video = if (VideoExportPlanner.removesVideoTrack(config.format)) null else videoBitrate(project, config)
        return VideoExportPlanner.estimatedSizeBytes(VideoExportPlanner.exportDurationMs(project.clips), video)
    }

    /**
     * The video's bitrate, from the real output frame: "Original" keeps the source size (a 4K
     * source got a 1080p bitrate and looked blocky).
     */
    private fun videoBitrate(project: VideoProjectState, config: VideoExportConfig): Int =
        config.videoBitrate ?: VideoExportPlanner.estimatedVideoBitrate(
            outputSize(project, config).let { (w, h) -> minOf(w, h) },
            config.fps,
            config.qualityPercent,
            config.quality,
        )

    private fun applyOutputMime(
        builder: Transformer.Builder,
        config: VideoExportConfig,
    ) {
        when (config.format) {
            ExportFormat.AUDIO_ONLY -> {
                builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
            }
            else -> {
                builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
            }
        }
    }

    private fun buildComposition(
        context: Context,
        project: VideoProjectState,
        config: VideoExportConfig,
        overlayBitmaps: MutableList<Bitmap>,
    ): Composition {
        val clipOps = VideoExportPlanner.planClipSequence(project.clips)
        val outSize = outputSize(project, config)
        val videoSeqBuilder = EditedMediaItemSequence.Builder()
        var startsWithGap = clipOps.firstOrNull()?.isGap == true
        if (startsWithGap && !VideoExportPlanner.removesVideoTrack(config.format)) {
            videoSeqBuilder.experimentalSetForceVideoTrack(true)
        }
        // Gaps and muted clips have no audio; when other clips do, the sequence needs silence
        // generated for them, or the export fails / drops the sound. A clip whose file has no
        // audio track at all (screen recording) can't be told from here, so any sequence of more
        // than one item gets the silence track too (harmless when every item has sound).
        val usedClips = clipOps.filterNot { it.isGap }.map { project.clips[it.index] }
        if (usedClips.any { !clipRemovesAudio(it) } &&
            (clipOps.size > 1 || clipOps.any { it.isGap } || usedClips.any { clipRemovesAudio(it) })
        ) {
            videoSeqBuilder.experimentalSetForceAudioTrack(true)
        }
        for (op in clipOps) {
            if (op.isGap) {
                videoSeqBuilder.addGap(op.durationMs * 1000L)
            } else {
                val clip = project.clips[op.index]
                videoSeqBuilder.addItem(buildEditedClip(clip, config, project.preview, outSize))
            }
        }
        val allSequences = mutableListOf(videoSeqBuilder.build())

        for (audio in project.audioTracks) {
            val ops = VideoExportPlanner.planAudioSequence(audio) ?: continue
            val audioSeq = EditedMediaItemSequence.Builder()
            if (ops.firstOrNull()?.isGap == true) {
                audioSeq.experimentalSetForceAudioTrack(true)
            }
            for (op in ops) {
                if (op.isGap) {
                    audioSeq.addGap(op.durationMs * 1000L)
                } else {
                    audioSeq.addItem(buildEditedAudio(audio))
                }
            }
            allSequences.add(audioSeq.build())
        }

        val compositionEffects = if (VideoExportPlanner.removesVideoTrack(config.format)) {
            emptyList()
        } else {
            val list = mutableListOf<Effect>()
            if (config.fps in 10..120) {
                list.add(FrameDropEffect.createDefaultFrameDropEffect(config.fps.toFloat()))
            }
            list.addAll(buildOverlayEffects(context, project, outSize, overlayBitmaps))
            list
        }
        val compositionBuilder = Composition.Builder(allSequences)
        if (compositionEffects.isNotEmpty()) {
            compositionBuilder.setEffects(Effects(emptyList(), compositionEffects))
        }
        return compositionBuilder.build()
    }

    private fun buildEditedClip(
        clip: VideoClipItem,
        config: VideoExportConfig,
        preview: PreviewGeometry?,
        outSize: Pair<Int, Int>,
    ): EditedMediaItem {
        val mediaItemBuilder = MediaItem.Builder().setUri(clip.media.uri)
        // A clip whose length couldn't be read keeps a placeholder length: cut there too, or the
        // export would hold the whole video while the timeline showed less.
        val cutEnd = clip.endTrimMs > 0L && (clip.endTrimMs < clip.sourceDurationMs || clip.media.durationMs <= 0L)
        if (clip.startTrimMs > 0L || cutEnd) {
            val clipConfig = MediaItem.ClippingConfiguration.Builder().apply {
                if (clip.startTrimMs > 0L) setStartPositionMs(clip.startTrimMs)
                if (cutEnd) {
                    setEndPositionMs(clip.endTrimMs)
                }
            }.build()
            mediaItemBuilder.setClippingConfiguration(clipConfig)
        }

        val audioOnly = VideoExportPlanner.removesVideoTrack(config.format)
        val videoEffects = mutableListOf<Effect>()
        val scaleX = (if (clip.flipH) -1f else 1f) * clip.zoomScale
        val scaleY = (if (clip.flipV) -1f else 1f) * clip.zoomScale
        val (panNdcX, panNdcY) = panNdc(clip, preview)
        // Same order as the preview's graphicsLayer: flip/zoom, then rotate, then pan.
        if (clip.flipH || clip.flipV || clip.zoomScale != 1.0f) {
            videoEffects.add(
                FramingMatrixTransformation(
                    scaleX = scaleX,
                    scaleY = scaleY,
                    rotationDeg = 0f,
                    panNdcX = 0f,
                    panNdcY = 0f,
                ),
            )
        }
        val rotation = Math.floorMod(clip.rotation, 360)
        val quarterTurns = ClipRotation.quarterTurns(rotation)
        val residual = rotation - quarterTurns
        if (quarterTurns != 0) {
            videoEffects.add(
                ScaleAndRotateTransformation.Builder()
                    // Media3 rotates counterclockwise; clip.rotation is clockwise like the preview.
                    .setRotationDegrees(-quarterTurns.toFloat())
                    .build(),
            )
        }
        if (residual != 0) {
            // Free angles turn inside the same frame, corners cropped, as the preview shows them.
            // (ScaleAndRotateTransformation would grow the frame to the rotated bounding box and
            // shrink the picture.)
            videoEffects.add(InPlaceRotation(residual.toFloat()))
        }
        if (panNdcX != 0f || panNdcY != 0f) {
            videoEffects.add(
                FramingMatrixTransformation(
                    scaleX = 1f,
                    scaleY = 1f,
                    rotationDeg = 0f,
                    panNdcX = panNdcX,
                    panNdcY = panNdcY,
                ),
            )
        }
        if (clip.brightness != 0f) {
            videoEffects.add(Brightness(clip.brightness.coerceIn(-1f, 1f)))
        }
        if (clip.contrast != 0f) {
            videoEffects.add(Contrast(clip.contrast.coerceIn(-1f, 1f)))
        }
        if (clip.saturation != 0f) {
            videoEffects.add(
                HslAdjustment.Builder()
                    .adjustSaturation((clip.saturation * 100f).coerceIn(-100f, 100f))
                    .build(),
            )
        }
        if (clip.gamma != 1.0f) {
            val gFactor = ImageEditEngine.gammaRgbScale(clip.gamma.coerceIn(0.2f, 3.0f))
            if (gFactor != 1.0f) {
                videoEffects.add(
                    RgbAdjustment.Builder()
                        .setRedScale(gFactor)
                        .setGreenScale(gFactor)
                        .setBlueScale(gFactor)
                        .build(),
                )
            }
        }

        val targetHeight = config.resolution.targetHeight
        val ratio = config.aspectRatio.ratio
        if (ratio != null) {
            // "Original" resolution: the source's height, not a fixed 1080.
            val baseHeight = targetHeight ?: outSize.second
            var h = baseHeight
            var w = (h * ratio).roundToInt()
            if (w % 2 != 0) w += 1
            if (h % 2 != 0) h += 1
            videoEffects.add(
                Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
            )
        } else if (targetHeight != null) {
            var h = targetHeight
            if (h % 2 != 0) h += 1
            videoEffects.add(Presentation.createForHeight(h))
        }

        val audioProcessors = volumeProcessors(if (clip.isMuted) 0f else clip.volume)
        val builder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setRemoveAudio(clipRemovesAudio(clip))
            .setRemoveVideo(audioOnly)
            .setEffects(Effects(audioProcessors, if (audioOnly) emptyList() else videoEffects))
        if (clip.speed != 1.0f && clip.speed > 0.1f) {
            // Video and audio together (the old SpeedChangeEffect only retimed the video, so the
            // sound ran at normal speed and drifted out of sync). Pitch is kept, as in the preview.
            builder.setSpeed(ConstantSpeed(clip.speed))
        }
        return builder.build()
    }

    private fun clipRemovesAudio(clip: VideoClipItem): Boolean = clip.isMuted || clip.volume <= 0.001f

    /** Width / height of the clip's video with its rotation tag applied, before the user's rotation. */
    private fun naturalAspect(clip: VideoClipItem, preview: PreviewGeometry?): Float =
        preview?.naturalAspects?.get(clip.media.id)
            ?: clip.media.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height }
            ?: (16f / 9f)

    /** As shown after the user's rotation (quarter turns swap the sides). */
    private fun shownAspect(clip: VideoClipItem, preview: PreviewGeometry?): Float {
        val a = naturalAspect(clip, preview)
        return if (ClipRotation.swapsSides(clip.rotation)) 1f / a else a
    }

    /** Size of the exported frame: what overlays and the aspect crop are laid out on. */
    private fun outputSize(project: VideoProjectState, config: VideoExportConfig): Pair<Int, Int> {
        val first = project.clips.firstOrNull()
        val shown = first?.let { shownAspect(it, project.preview) } ?: (16f / 9f)
        val longSide = first?.media?.let { maxOf(it.width, it.height) }?.takeIf { it > 0 } ?: 1920
        val shortSide = first?.media?.let { minOf(it.width, it.height) }?.takeIf { it > 0 } ?: 1080
        val naturalHeight = if (shown >= 1f) shortSide else longSide
        val h = config.resolution.targetHeight ?: naturalHeight
        val aspect = config.aspectRatio.ratio ?: shown
        var w = (h * aspect).roundToInt().coerceAtLeast(2)
        var hh = h.coerceAtLeast(2)
        if (w % 2 != 0) w += 1
        if (hh % 2 != 0) hh += 1
        return w to hh
    }

    /**
     * Pan in NDC of the (rotated) video frame. The preview pans the video box, drawn to cover its
     * area, by [VideoClipItem.panX]/[VideoClipItem.panY] screen pixels: a box half-width is 1.
     */
    private fun panNdc(clip: VideoClipItem, preview: PreviewGeometry?): Pair<Float, Float> {
        if (clip.panX == 0f && clip.panY == 0f) return 0f to 0f
        if (preview == null || preview.containerWidthPx <= 0f || preview.containerHeightPx <= 0f) {
            return VideoExportPlanner.panToNdc(clip.panX) to VideoExportPlanner.panToNdc(-clip.panY)
        }
        val (coverW, coverH) = previewCover(clip, preview)
        return (clip.panX / (coverW / 2f)).coerceIn(-4f, 4f) to (-clip.panY / (coverH / 2f)).coerceIn(-4f, 4f)
    }

    /** Size of the clip's video box in the preview (cover of the preview area). */
    private fun previewCover(clip: VideoClipItem, preview: PreviewGeometry): Pair<Float, Float> {
        val shown = shownAspect(clip, preview)
        val cw = preview.containerWidthPx
        val ch = preview.containerHeightPx
        return if (shown > cw / ch) (ch * shown) to ch else cw to (cw / shown)
    }

    /** Output pixels per preview dp: overlays were sized in dp on a frame [PreviewGeometry.frameWidthPx] wide. */
    private fun outputPxPerDp(context: Context, preview: PreviewGeometry?, outWidth: Int): Float {
        val metrics = context.resources.displayMetrics
        val density = preview?.density ?: metrics.density
        val frameW = preview?.frameWidthPx?.takeIf { it > 0f } ?: metrics.widthPixels.toFloat()
        return density * outWidth / frameW
    }

    /**
     * A text overlay drawn like the preview's (its style, 14/8 dp padding, 8 dp rounded background),
     * at output resolution. Unscaled: the overlay's own scale is applied when it is placed.
     */
    private fun renderTextOverlay(overlay: TextOverlayItem, pxPerDp: Float, fontScale: Float, maxWidth: Int): Bitmap? {
        if (overlay.text.isBlank()) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlay.textColor.toInt()
            textSize = overlay.fontSize * fontScale * pxPerDp
            typeface = Typeface.create(
                Typeface.DEFAULT,
                when {
                    overlay.isBold && overlay.isItalic -> Typeface.BOLD_ITALIC
                    overlay.isBold -> Typeface.BOLD
                    overlay.isItalic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                },
            )
            isUnderlineText = overlay.isUnderline
            isStrikeThruText = overlay.isStrikethrough
        }
        val padH = 14f * pxPerDp
        val padV = 8f * pxPerDp
        val textW = kotlin.math.ceil(Layout.getDesiredWidth(overlay.text, paint)).toInt()
            .coerceIn(1, (maxWidth - 2 * padH).roundToInt().coerceAtLeast(1))
        val layout = StaticLayout.Builder.obtain(overlay.text, 0, overlay.text.length, paint, textW)
            .setAlignment(
                when (overlay.alignment) {
                    0 -> Layout.Alignment.ALIGN_NORMAL
                    2 -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_CENTER
                },
            )
            .build()
        val w = (textW + 2 * padH).roundToInt().coerceAtLeast(1)
        val h = (layout.height + 2 * padV).roundToInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                if (overlay.bgColor.toInt() ushr 24 != 0) {
                    val radius = 8f * pxPerDp
                    canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = overlay.bgColor.toInt() })
                }
                canvas.translate(padH, padV)
                layout.draw(canvas)
            }
        }.getOrNull()
    }

    /** Scale that fits [bitmap] in the preview's 90 dp sticker box, at output resolution. */
    private fun stickerFitScale(bitmap: Bitmap, pxPerDp: Float): Float {
        val box = 90f * pxPerDp
        return minOf(box / bitmap.width.coerceAtLeast(1), box / bitmap.height.coerceAtLeast(1))
    }

    /** Rotation by [clockwiseDeg] inside the same frame (NDC corrected for the frame's aspect). */
    private class InPlaceRotation(private val clockwiseDeg: Float) : MatrixTransformation {
        private val matrix = android.graphics.Matrix()

        override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
            val aspect = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
            matrix.reset()
            matrix.postScale(aspect, 1f)
            matrix.postRotate(-clockwiseDeg) // NDC has y up: clockwise on screen is negative
            matrix.postScale(1f / aspect, 1f)
            return androidx.media3.common.util.Size(inputWidth, inputHeight)
        }

        override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix = matrix
    }

    private class ConstantSpeed(private val speed: Float) : androidx.media3.common.audio.SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = androidx.media3.common.C.TIME_UNSET
    }

    private fun buildEditedAudio(audio: AudioTrackItem): EditedMediaItem {
        val audioMediaItemBuilder = MediaItem.Builder().setUri(audio.uri)
        if (audio.startTrimMs > 0L || (audio.endTrimMs > 0L && audio.endTrimMs < audio.sourceDurationMs)) {
            val audioClipConfig = MediaItem.ClippingConfiguration.Builder().apply {
                if (audio.startTrimMs > 0L) setStartPositionMs(audio.startTrimMs)
                if (audio.endTrimMs > 0L && audio.endTrimMs < audio.sourceDurationMs) {
                    setEndPositionMs(audio.endTrimMs)
                }
            }.build()
            audioMediaItemBuilder.setClippingConfiguration(audioClipConfig)
        }
        val processors = volumeProcessors(audio.volume)
        return EditedMediaItem.Builder(audioMediaItemBuilder.build())
            .setRemoveVideo(true)
            .setEffects(Effects(processors, emptyList()))
            .build()
    }

    private fun volumeProcessors(volume: Float): List<androidx.media3.common.audio.AudioProcessor> {
        val v = volume.coerceIn(0f, 2f)
        if (kotlin.math.abs(v - 1f) < 0.001f) return emptyList()
        val processor = ChannelMixingAudioProcessor()
        processor.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 1).scaleBy(v))
        processor.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2).scaleBy(v))
        return listOf(processor)
    }

    private fun buildOverlayEffects(
        context: Context,
        project: VideoProjectState,
        outSize: Pair<Int, Int>,
        overlayBitmaps: MutableList<Bitmap>,
    ): List<Effect> {
        val effects = mutableListOf<Effect>()
        val pxPerDp = outputPxPerDp(context, project.preview, outSize.first)
        val fontScale = project.preview?.fontScale ?: context.resources.configuration.fontScale
        for (overlay in project.textOverlays) {
            // Drawn here rather than as a TextOverlay: that one is sized in screen units of the
            // export, not the preview's, and has no padding or rounded background.
            val bitmap = renderTextOverlay(overlay, pxPerDp, fontScale, outSize.first) ?: continue
            overlayBitmaps += bitmap
            val startUs = overlay.startTimelineMs.coerceAtLeast(0L) * 1000L
            val endUs = overlay.endTimelineMs.coerceAtLeast(overlay.startTimelineMs + 1L) * 1000L
            val settings = overlaySettings(overlay.normalizedX, overlay.normalizedY, overlay.scale, overlay.rotationDeg)
            effects += TimestampWrapper(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(bitmap, settings))), startUs, endUs)
        }
        for (overlay in project.imageOverlays) {
            val bitmap = decodeOverlayBitmap(context, overlay.uri) ?: continue
            overlayBitmaps += bitmap
            val startUs = overlay.startTimelineMs.coerceAtLeast(0L) * 1000L
            val endUs = overlay.endTimelineMs.coerceAtLeast(overlay.startTimelineMs + 1L) * 1000L
            // Media3 draws a bitmap at its own pixel size: scale it to the preview's sticker box.
            val scale = stickerFitScale(bitmap, pxPerDp) * overlay.scale
            val settings = overlaySettings(overlay.normalizedX, overlay.normalizedY, scale, overlay.rotationDeg)
            val image = BitmapOverlay.createStaticBitmapOverlay(bitmap, settings)
            effects += TimestampWrapper(OverlayEffect(listOf(image)), startUs, endUs)
        }
        return effects
    }

    private fun overlaySettings(
        normalizedX: Float,
        normalizedY: Float,
        scale: Float,
        rotationDeg: Float,
    ): StaticOverlaySettings {
        val x = (normalizedX * 2f - 1f).coerceIn(-1f, 1f)
        val y = (1f - normalizedY * 2f).coerceIn(-1f, 1f)
        val s = scale.coerceIn(0.005f, 8f)
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(x, y)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(s, s)
            // Media3 rotates overlays counterclockwise; the editor's rotationDeg is clockwise.
            .setRotationDegrees(-rotationDeg)
            .build()
    }

    /**
     * Sampled down (a sticker is drawn at most a few hundred pixels wide; a full 50 MP photo ran
     * out of memory and the sticker silently vanished) and upright by its EXIF orientation, as
     * the preview shows it.
     */
    private fun decodeOverlayBitmap(context: Context, uri: Uri): Bitmap? = try {
        val file = if (uri.scheme == "file") uri.path?.let { java.io.File(it) } else null
        ImageEditEngine.decodeSourceBitmap(context, file, if (file == null) uri else null, maxDim = 2048)
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        dev.ander.alexet.data.AppLog.w("VideoExportEngine", "Sticker could not be decoded: $uri", e)
        null
    }

    private class FramingMatrixTransformation(
        scaleX: Float,
        scaleY: Float,
        rotationDeg: Float,
        panNdcX: Float,
        panNdcY: Float,
    ) : MatrixTransformation {
        private val matrix = android.graphics.Matrix().apply {
            postScale(scaleX, scaleY)
            if (rotationDeg != 0f) postRotate(rotationDeg)
            if (panNdcX != 0f || panNdcY != 0f) postTranslate(panNdcX, panNdcY)
        }

        override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix = matrix
    }

    private suspend fun exportGif(
        context: Context,
        project: VideoProjectState,
        config: VideoExportConfig,
        outputFile: File,
        onProgress: (Int) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val fps = config.fps.coerceIn(10, 30)
        val frameIntervalMs = 1000L / fps

        // One frame size for the whole GIF (the encoder would stretch later frames to the first):
        // the chosen aspect, or the first clip as shown.
        val gifHeight = (config.resolution.targetHeight ?: 480).coerceIn(16, MAX_GIF_HEIGHT)
        val gifAspect = config.aspectRatio.ratio ?: project.clips.firstOrNull()?.let { shownAspect(it, project.preview) } ?: (16f / 9f)
        var gifWidth = (gifHeight * gifAspect).roundToInt().coerceAtLeast(16)
        if (gifWidth % 2 != 0) gifWidth += 1
        val pxPerDp = outputPxPerDp(context, project.preview, gifWidth)
        val fontScale = project.preview?.fontScale ?: context.resources.configuration.fontScale
        val videoClipsDuration = project.clips.maxOfOrNull { it.timelineStartMs + it.trimmedDurationMs } ?: 0L
        if (videoClipsDuration <= 0L) return@withContext false
        val durationMs = videoClipsDuration.coerceAtLeast(frameIntervalMs)
        val totalFramesEstimate = (durationMs / frameIntervalMs).toInt().coerceAtLeast(1)
        val encoder = GifEncoder()
        if (!encoder.start(outputFile)) return@withContext false
        encoder.setDelay(frameIntervalMs.toInt())
        encoder.setRepeat(0)

        val clipFrames = HashMap<String, ClipFrames>()
        val overlayBitmaps = HashMap<String, Bitmap?>()
        // One frame drawn into the same bitmap each time (a new bitmap, canvas and paints per
        // frame before); the encoder reads it before the next one is drawn.
        val canvas = GifFrameCanvas(gifWidth, gifHeight)
        var processedFrames = 0
        var framesAdded = 0
        var gifFailed = false

        try {
            var t = 0L
            while (t < durationMs && isActive) {
                val clip = VideoExportPlanner.clipAt(project.clips, t)
                // Owned by its ClipFrames: not recycled here.
                val frame = if (clip != null) {
                    val localMs = ((t - clip.timelineStartMs) * clip.speed.coerceIn(0.2f, 5f)).toLong()
                    val sourceUs = (clip.startTrimMs + localMs) * 1000L
                    // Decoded near the GIF size (a full 4K/8K frame is 33-132 MB and slow);
                    // twice the size, more when zoomed in, keeps the detail.
                    val maxDim = (maxOf(gifWidth, gifHeight) * 2 * clip.zoomScale.coerceIn(1f, 4f)).roundToInt()
                    clipFrames.getOrPut(clip.id) { ClipFrames(context, clip) }.frameAt(sourceUs, maxDim)
                } else {
                    null
                }

                canvas.bitmap.eraseColor(Color.BLACK)
                if (frame != null && clip != null) drawClipFrame(frame, clip, canvas, project.preview)
                drawGifOverlays(context, canvas, project, t, overlayBitmaps, pxPerDp, fontScale)
                val addedOk = encoder.addFrame(canvas.bitmap)
                if (addedOk) framesAdded++
                if (!addedOk) {
                    // Part of the frame may already be in the file: the GIF is broken, not a
                    // success with a frame missing.
                    gifFailed = true
                    dev.ander.alexet.data.AppLog.e("VideoExport", "GIF frame ${processedFrames + 1} could not be written")
                    break
                }

                processedFrames++
                val pct = ((processedFrames.toFloat() / totalFramesEstimate) * 100).roundToInt().coerceIn(0, 99)
                onProgress(pct)
                t += frameIntervalMs
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // A GIF cut off part-way is not a finished export (also out of memory: not caught
            // further up, it crashed the app).
            gifFailed = true
            dev.ander.alexet.data.AppLog.e("VideoExport", "GIF export failed at ${processedFrames} frame(s)", e)
        } finally {
            clipFrames.values.forEach { runCatching { it.close() } }
            canvas.bitmap.recycle()
            overlayBitmaps.values.forEach { bmp -> if (bmp != null && !bmp.isRecycled) bmp.recycle() }
            if (!isActive) {
                // Cancelled: the file is left open and unfinished otherwise.
                encoder.close()
                outputFile.delete()
            }
        }

        if (!isActive || gifFailed) {
            encoder.close()
            outputFile.delete()
            return@withContext false
        }

        val finished = encoder.finish()
        if (finished && framesAdded > 0) {
            onProgress(100)
            true
        } else {
            encoder.close()
            outputFile.delete()
            false
        }
    }

    /** The GIF's frame and what it is drawn with, made once for the whole export. */
    private class GifFrameCanvas(width: Int, height: Int) {
        val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val framePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = android.graphics.Matrix()
        /** A clip's colour filter, per clip. */
        val filters = HashMap<String, ColorMatrixColorFilter>()
    }

    /**
     * A clip's frames for the GIF: decoded once in order ([SequentialFrameReader]). When that
     * can't read the clip, or fails on the way, the rest comes from MediaMetadataRetriever as
     * before (a seek and decode for every frame). The bitmap given belongs to this object.
     */
    private class ClipFrames(private val context: Context, private val clip: VideoClipItem) : java.io.Closeable {
        private var reader: SequentialFrameReader? = runCatching {
            SequentialFrameReader(context, clip.media.uri, clip.media.absolutePath)
        }.onFailure {
            dev.ander.alexet.data.AppLog.w("VideoExport", "Clip ${clip.id} read frame by frame", it)
        }.getOrNull()
        private var retriever: MediaMetadataRetriever? = null
        private var retrieved: Bitmap? = null

        fun frameAt(sourceUs: Long, maxDim: Int): Bitmap? {
            reader?.let { r ->
                try {
                    return r.frameAt(sourceUs, maxDim)
                } catch (e: Exception) {
                    dev.ander.alexet.data.AppLog.w("VideoExport", "Decoder of clip ${clip.id} failed; frame by frame from here", e)
                    runCatching { r.close() }
                    reader = null
                }
            }
            val retr = retriever ?: MediaMetadataRetriever().also { r ->
                try {
                    dev.ander.alexet.data.Mp4MetaRepair.setDataSource(r, context, clip.media.absolutePath, clip.media.uri)
                } catch (e: Throwable) {
                    runCatching { r.release() }
                    throw e
                }
                retriever = r
            }
            val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                retr.getScaledFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST, maxDim, maxDim)
            } else {
                retr.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            retrieved?.recycle()
            retrieved = frame
            return frame
        }

        override fun close() {
            reader?.let { runCatching { it.close() } }
            retriever?.let { runCatching { it.release() } }
            retrieved?.recycle()
        }
    }

    /** Overlays drawn like the MP4 path places them: centred on their point, scaled and rotated. */
    private fun drawGifOverlays(
        context: Context,
        target: GifFrameCanvas,
        project: VideoProjectState,
        timeMs: Long,
        cache: MutableMap<String, Bitmap?>,
        pxPerDp: Float,
        fontScale: Float,
    ) {
        val frame = target.bitmap
        val canvas = target.canvas
        val paint = target.overlayPaint
        fun place(bitmap: Bitmap, nx: Float, ny: Float, scale: Float, rotationDeg: Float) {
            val cx = nx * frame.width
            val cy = ny * frame.height
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rotationDeg)
            canvas.scale(scale, scale)
            canvas.drawBitmap(bitmap, -bitmap.width / 2f, -bitmap.height / 2f, paint)
            canvas.restore()
        }
        for (overlay in project.textOverlays) {
            if (!VideoExportPlanner.overlayVisible(overlay.startTimelineMs, overlay.endTimelineMs, timeMs)) continue
            // A failed render (null) is remembered too, not retried on every frame.
            val textKey = "text:${overlay.id}"
            val bmp = (if (cache.containsKey(textKey)) cache[textKey] else renderTextOverlay(overlay, pxPerDp, fontScale, frame.width).also { cache[textKey] = it }) ?: continue
            place(bmp, overlay.normalizedX, overlay.normalizedY, overlay.scale, overlay.rotationDeg)
        }
        for (overlay in project.imageOverlays) {
            if (!VideoExportPlanner.overlayVisible(overlay.startTimelineMs, overlay.endTimelineMs, timeMs)) continue
            val bmp = (if (cache.containsKey(overlay.id)) cache[overlay.id] else decodeOverlayBitmap(context, overlay.uri).also { cache[overlay.id] = it }) ?: continue
            place(bmp, overlay.normalizedX, overlay.normalizedY, stickerFitScale(bmp, pxPerDp) * overlay.scale, overlay.rotationDeg)
        }
    }

    /**
     * One GIF frame of [outW] x [outH]: the clip's frame covers it (cropped like the preview and
     * the MP4 aspect crop), with flip/zoom, the user's rotation and pan in that order.
     */
    private fun drawClipFrame(
        source: Bitmap,
        clip: VideoClipItem,
        target: GifFrameCanvas,
        preview: PreviewGeometry?,
    ) {
        val outW = target.bitmap.width
        val outH = target.bitmap.height
        val canvas = target.canvas
        val paint = target.framePaint.apply {
            colorFilter = target.filters.getOrPut(clip.id) {
                ColorMatrixColorFilter(
                    ColorMatrix(ImageEditEngine.createColorMatrixArray(clip.brightness, clip.contrast, clip.saturation, clip.gamma)),
                )
            }
        }
        val rotation = Math.floorMod(clip.rotation, 360)
        val swapped = ClipRotation.swapsSides(rotation)
        val shownW = (if (swapped) source.height else source.width).toFloat().coerceAtLeast(1f)
        val shownH = (if (swapped) source.width else source.height).toFloat().coerceAtLeast(1f)
        val cover = maxOf(outW / shownW, outH / shownH)

        // Pan: a fraction of the preview's video box, applied to the video box here.
        val (panX, panY) = if (preview != null && preview.containerWidthPx > 0f && preview.containerHeightPx > 0f) {
            val (coverW, coverH) = previewCover(clip, preview)
            (clip.panX / coverW * shownW * cover) to (clip.panY / coverH * shownH * cover)
        } else {
            (clip.panX / 1080f * outH) to (clip.panY / 1080f * outH)
        }

        val matrix = target.matrix
        matrix.reset()
        matrix.postTranslate(-source.width / 2f, -source.height / 2f)
        matrix.postScale(
            (if (clip.flipH) -1f else 1f) * clip.zoomScale * cover,
            (if (clip.flipV) -1f else 1f) * clip.zoomScale * cover,
        )
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        matrix.postTranslate(outW / 2f + panX, outH / 2f + panY)
        canvas.drawBitmap(source, matrix, paint)
    }
}
