package dev.ander.alexet.data.videoeditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * The frames of one video, decoded once from start to end, for the GIF export. Asked for at
 * increasing times, it decodes forward and turns only the frames asked for into bitmaps, straight
 * at the wanted size and turned upright. MediaMetadataRetriever decoded from the previous keyframe
 * for every single frame, so a minute of video took minutes.
 *
 * Throws [UnsupportedOperationException] for what it can't read (no video track, not 8-bit YUV);
 * the export then reads that clip the old way.
 */
internal class SequentialFrameReader(context: Context, uri: Uri, path: String?) : Closeable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    /** Clockwise turn the video is shown with. */
    private val rotation: Int
    private val bt709: Boolean
    private val fullRange: Boolean
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false

    /** The last frame turned into a bitmap (owned here, valid until the next call) and its time. */
    private var lastBitmap: Bitmap? = null
    private var lastPts = UNSET
    private var lastDecodedPts = UNSET
    private var frameUs = DEFAULT_FRAME_US
    private var argb = IntArray(0)

    init {
        try {
            if (path != null && File(path).canRead()) extractor.setDataSource(path) else extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw UnsupportedOperationException("No video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) Math.floorMod(format.getInteger(MediaFormat.KEY_ROTATION), 360) else 0
            val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
            bt709 = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709
            } else {
                height >= 720
            }
            fullRange = format.containsKey(MediaFormat.KEY_COLOR_RANGE) &&
                format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                val fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                    .getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
                if (fps > 1f) frameUs = (1_000_000f / fps).toLong()
            }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw UnsupportedOperationException("No MIME type")
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw e
            }
        } catch (e: Throwable) {
            extractor.release()
            throw e
        }
    }

    /**
     * The frame shown at [timeUs] (the nearest one), at most [maxDim] px on its longer side; null
     * when the video has none there. The bitmap belongs to this reader until the next call.
     */
    fun frameAt(timeUs: Long, maxDim: Int): Bitmap? {
        val half = frameUs / 2
        val last = lastBitmap
        if (last != null && timeUs >= lastPts - half && timeUs < lastPts + half) return last
        // Backwards, or far ahead: from the keyframe before it (decoding all the way would cost more).
        if (lastDecodedPts == UNSET || timeUs < lastDecodedPts - half || timeUs - lastDecodedPts > SEEK_AHEAD_US) seek(timeUs)
        var idle = 0
        while (true) {
            if (!inputDone) feed()
            val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (index >= 0) {
                idle = 0
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pts = info.presentationTimeUs
                if (info.size > 0) {
                    if (lastDecodedPts != UNSET && pts > lastDecodedPts) frameUs = (pts - lastDecodedPts).coerceIn(MIN_FRAME_US, MAX_FRAME_US)
                    lastDecodedPts = pts
                    if (pts >= timeUs - frameUs / 2) {
                        val bitmap = try {
                            convert(index, maxDim)
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                        lastBitmap?.recycle()
                        lastBitmap = bitmap
                        lastPts = pts
                        return bitmap
                    }
                }
                codec.releaseOutputBuffer(index, false)
                // Past its end: the last frame stays on, as with the retriever.
                if (end) return lastBitmap
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (inputDone && ++idle > MAX_IDLE_POLLS) return lastBitmap
            }
        }
    }

    private fun seek(timeUs: Long) {
        extractor.seekTo(timeUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        lastDecodedPts = UNSET
    }

    private fun feed() {
        val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun convert(index: Int, maxDim: Int): Bitmap {
        val image = codec.getOutputImage(index) ?: throw UnsupportedOperationException("No output image")
        image.use {
            if (image.format != ImageFormat.YUV_420_888) throw UnsupportedOperationException("Frame format ${image.format}")
            val crop = image.cropRect
            val srcW = crop.width()
            val srcH = crop.height()
            val swapped = rotation == 90 || rotation == 270
            val shownW = if (swapped) srcH else srcW
            val shownH = if (swapped) srcW else srcH
            val scale = minOf(1f, maxDim.toFloat() / maxOf(shownW, shownH))
            val outW = (shownW * scale).roundToInt().coerceAtLeast(1)
            val outH = (shownH * scale).roundToInt().coerceAtLeast(1)
            if (argb.size != outW * outH) argb = IntArray(outW * outH)
            val (y, u, v) = image.planes
            yuvToArgb(
                YuvFrame(
                    y = y.buffer, yRowStride = y.rowStride, yPixelStride = y.pixelStride,
                    u = u.buffer, v = v.buffer, uvRowStride = u.rowStride, uvPixelStride = u.pixelStride,
                    left = crop.left, top = crop.top, width = srcW, height = srcH,
                    bt709 = bt709, fullRange = fullRange,
                ),
                rotation, outW, outH, argb,
            )
            return Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888)
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        lastBitmap?.recycle()
        lastBitmap = null
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
        const val DEFAULT_FRAME_US = 33_333L
        const val MIN_FRAME_US = 2_000L
        const val MAX_FRAME_US = 1_000_000L
        const val SEEK_AHEAD_US = 3_000_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L
        /** About a second without output after the end of the input: nothing more comes. */
        const val MAX_IDLE_POLLS = 100
    }
}

/** One decoded YUV 4:2:0 frame's planes and its visible part. */
internal class YuvFrame(
    val y: ByteBuffer,
    val yRowStride: Int,
    val yPixelStride: Int,
    val u: ByteBuffer,
    val v: ByteBuffer,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val bt709: Boolean,
    val fullRange: Boolean,
)

/**
 * [frame] as [outW] x [outH] ARGB pixels into [out], turned clockwise by [rotation] (0/90/180/270,
 * so [outW] x [outH] is the turned size) and scaled by taking the nearest pixel.
 */
internal fun yuvToArgb(frame: YuvFrame, rotation: Int, outW: Int, outH: Int, out: IntArray) {
    val swapped = rotation == 90 || rotation == 270
    // The scaled frame before turning.
    val scaledW = if (swapped) outH else outW
    val scaledH = if (swapped) outW else outH
    val yScale = if (frame.fullRange) 1f else 255f / 219f
    val cScale = if (frame.fullRange) 1f else 255f / 224f
    val yOffset = if (frame.fullRange) 0 else 16
    // Kr/Kb of the colour standard.
    val rv = if (frame.bt709) 1.5748f else 1.402f
    val gu = if (frame.bt709) 0.1873f else 0.3441f
    val gv = if (frame.bt709) 0.4681f else 0.7141f
    val bu = if (frame.bt709) 1.8556f else 1.772f
    for (oy in 0 until outH) {
        for (ox in 0 until outW) {
            val sxScaled: Int
            val syScaled: Int
            when (rotation) {
                90 -> {
                    sxScaled = oy
                    syScaled = scaledH - 1 - ox
                }
                180 -> {
                    sxScaled = scaledW - 1 - ox
                    syScaled = scaledH - 1 - oy
                }
                270 -> {
                    sxScaled = scaledW - 1 - oy
                    syScaled = ox
                }
                else -> {
                    sxScaled = ox
                    syScaled = oy
                }
            }
            val sx = frame.left + (sxScaled * frame.width / scaledW).coerceIn(0, frame.width - 1)
            val sy = frame.top + (syScaled * frame.height / scaledH).coerceIn(0, frame.height - 1)
            val yv = ((frame.y.get(sy * frame.yRowStride + sx * frame.yPixelStride).toInt() and 0xFF) - yOffset) * yScale
            val c = (sy / 2) * frame.uvRowStride + (sx / 2) * frame.uvPixelStride
            val uv = ((frame.u.get(c).toInt() and 0xFF) - 128) * cScale
            val vv = ((frame.v.get(c).toInt() and 0xFF) - 128) * cScale
            val r = (yv + rv * vv).roundToInt().coerceIn(0, 255)
            val g = (yv - gu * uv - gv * vv).roundToInt().coerceIn(0, 255)
            val b = (yv + bu * uv).roundToInt().coerceIn(0, 255)
            out[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
