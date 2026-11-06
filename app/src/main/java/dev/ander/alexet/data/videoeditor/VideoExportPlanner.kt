package dev.ander.alexet.data.videoeditor

/**
 * Pure-logic export plan: clip/audio placement with timeline gaps.
 * Transformer sequences are linear, so overlapping clips are concatenated
 * (no second video layer).
 */
data class ExportSequenceOp(
    val isGap: Boolean,
    val durationMs: Long,
    val index: Int = -1,
)

object VideoExportPlanner {

    fun planClipSequence(clips: List<VideoClipItem>): List<ExportSequenceOp> {
        if (clips.isEmpty()) return emptyList()
        val sorted = clips.withIndex().sortedBy { it.value.timelineStartMs }
        val ops = ArrayList<ExportSequenceOp>(sorted.size * 2)
        var cursor = 0L
        for ((index, clip) in sorted) {
            val start = clip.timelineStartMs.coerceAtLeast(0L)
            if (start > cursor) {
                ops += ExportSequenceOp(isGap = true, durationMs = start - cursor)
                cursor = start
            }
            val dur = clip.trimmedDurationMs.coerceAtLeast(1L)
            ops += ExportSequenceOp(isGap = false, durationMs = dur, index = index)
            cursor = maxOf(cursor, start + dur)
        }
        return ops
    }

    fun planAudioSequence(track: AudioTrackItem): List<ExportSequenceOp>? {
        if (track.isMuted || track.volume <= 0.001f) return null
        val ops = ArrayList<ExportSequenceOp>(2)
        val start = track.timelineStartMs.coerceAtLeast(0L)
        if (start > 0L) {
            ops += ExportSequenceOp(isGap = true, durationMs = start)
        }
        ops += ExportSequenceOp(isGap = false, durationMs = track.trimmedDurationMs.coerceAtLeast(1L), index = 0)
        return ops
    }

    fun estimatedVideoBitrate(
        targetHeight: Int?,
        fps: Int,
        qualityPercent: Int,
        quality: ExportQuality,
    ): Int {
        val height = (targetHeight ?: 1080).coerceIn(144, 2160)
        val width = (height * 16 / 9).coerceAtLeast(1)
        val frameRate = fps.coerceIn(8, 60)
        val base = (width * height * frameRate * 0.07).toInt()
        val percent = qualityPercent.coerceIn(10, 100) / 100f
        val scaled = (base * percent * quality.bitrateMultiplier).toInt()
        return scaled.coerceIn(250_000, 50_000_000)
    }

    fun panToNdc(panPx: Float, referenceHalfSize: Float = 540f): Float {
        val half = referenceHalfSize.coerceAtLeast(1f)
        return (panPx / half).coerceIn(-4f, 4f)
    }

    fun overlayVisible(startMs: Long, endMs: Long, timeMs: Long): Boolean =
        timeMs in startMs until endMs || (startMs == endMs && timeMs == startMs)

    fun clipAt(clips: List<VideoClipItem>, timeMs: Long): VideoClipItem? {
        return clips
            .filter { timeMs >= it.timelineStartMs && timeMs < it.timelineStartMs + it.trimmedDurationMs }
            .maxByOrNull { it.timelineStartMs }
    }
    fun clampTrimRange(
        startTrimMs: Long,
        endTrimMs: Long,
        sourceDurationMs: Long,
        minSpanMs: Long = 100L,
    ): Pair<Long, Long> {
        val duration = sourceDurationMs.coerceAtLeast(0L)
        if (duration <= 0L) return 0L to 0L
        if (duration < minSpanMs) return 0L to duration
        val start = startTrimMs.coerceIn(0L, duration - minSpanMs)
        val end = endTrimMs.coerceIn(start + minSpanMs, duration)
        return start to end
    }

    fun overlayFrameNormalized(
        containerX: Float,
        containerY: Float,
        leftScrim: Float,
        topScrim: Float,
        frameWidth: Float,
        frameHeight: Float,
    ): Pair<Float, Float> {
        val fw = frameWidth.coerceAtLeast(1f)
        val fh = frameHeight.coerceAtLeast(1f)
        val nx = (containerX - leftScrim) / fw
        val ny = (containerY - topScrim) / fh
        return nx to ny
    }

    fun overlayFrameToContainer(
        normalizedX: Float,
        normalizedY: Float,
        leftScrim: Float,
        topScrim: Float,
        frameWidth: Float,
        frameHeight: Float,
    ): Pair<Float, Float> {
        return (leftScrim + normalizedX * frameWidth) to (topScrim + normalizedY * frameHeight)
    }

    fun removesVideoTrack(format: ExportFormat): Boolean = format == ExportFormat.AUDIO_ONLY

    fun nextClipAfter(
        clips: List<VideoClipItem>,
        afterTimelineMs: Long,
        excludeId: String? = null,
    ): VideoClipItem? {
        return clips
            .filter { it.id != excludeId && it.timelineStartMs >= afterTimelineMs }
            .minByOrNull { it.timelineStartMs }
    }
}
