package dev.ander.alexet.ui.videoeditor

import androidx.compose.runtime.mutableIntStateOf
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.SupervisorJob
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.videoeditor.AudioTrackItem
import dev.ander.alexet.data.videoeditor.ImageOverlayItem
import dev.ander.alexet.data.videoeditor.TextOverlayItem
import dev.ander.alexet.data.videoeditor.VideoClipItem
import dev.ander.alexet.data.videoeditor.VideoProjectAspectRatio
import dev.ander.alexet.data.videoeditor.VideoExportPlanner
import dev.ander.alexet.data.videoeditor.VideoProjectState
import java.io.File
import java.util.UUID

/** The text panel is open for a new text ([textId] null) or for the text with this id. */
class TextPanelRequest(val textId: String?)

/** What the text panel sets: the words and their look. */
data class TextStyleChoice(
    val text: String,
    val alignment: Int,
    val isBold: Boolean,
    val isItalic: Boolean,
    val isUnderline: Boolean,
    val isStrikethrough: Boolean,
    val fontSize: Float,
    val textColor: Long,
) {
    fun applyTo(item: TextOverlayItem): TextOverlayItem = item.copy(
        text = text,
        alignment = alignment,
        isBold = isBold,
        isItalic = isItalic,
        isUnderline = isUnderline,
        isStrikethrough = isStrikethrough,
        fontSize = fontSize,
        textColor = textColor,
    )
}

enum class VideoEditorTab {
    TIMELINE,
    TRIM_SPLIT,
    SPEED,
    AUDIO,
    ADJUST,
    ASPECT,
    TEXT,
    STICKER,
}

class VideoEditorStateHolder(
    initialMedia: List<GalleryMedia>,
    context: Context,
    /** The project as saved before the process was ended (see [VideoProjectJson]). */
    restored: VideoProjectState? = null,
) {
    var project by mutableStateOf(VideoProjectState())
        private set

    var playheadMs by mutableLongStateOf(0L)

    // Preview layout, written by the preview each layout pass (plain fields: no recomposition).
    var previewContainerPx: Pair<Float, Float> = 0f to 0f
    var previewFramePx: Pair<Float, Float> = 0f to 0f
    var previewNaturalAspects: Map<Long, Float> = emptyMap()
    /** The current clip's picture at zoom 1 (px): it covers the frame, as the export crops it. */
    var previewCoverPx: Pair<Float, Float> = 0f to 0f

    /**
     * The picture fills the frame (no black) and a one-finger drag stays inside it. A pinch frees it
     * (black may then show); a double tap or "Fit" sets it again.
     */
    var framingLocked by mutableStateOf(false)

    /**
     * "Fit" (a toggle): the picture fits the frame, two opposite edges on it, and a drag keeps it
     * inside (a third edge touches when it is pushed to a side). A pinch or a double tap ends it.
     */
    var fitLocked by mutableStateOf(false)

    fun toggleFit() {
        if (fitLocked) {
            fitLocked = false
            return
        }
        if (project.selectedClipIndex !in project.clips.indices) {
            if (project.clips.isEmpty()) return
            project = project.copy(selectedClipIndex = 0)
        }
        val clip = project.selectedClip ?: return
        val (fw, fh) = previewFramePx.takeIf { it.first > 0f && it.second > 0f } ?: previewContainerPx
        val (cw, ch) = previewCoverPx
        if (fw <= 0f || fh <= 0f || cw <= 0f || ch <= 0f) return
        framingLocked = false
        fitLocked = true
        val zoom = minOf(fw / cw, fh / ch, 1f)
        // Left where it was if inside the frame; pulled back to the edge if it stuck out.
        val (panX, panY) = clampFit(clip.panX, clip.panY, zoom)
        updateFraming(panX, panY, zoom)
    }

    /** [panX], [panY] limited so the picture stays inside the frame (or covers it on a longer side). */
    fun clampFit(panX: Float, panY: Float, zoom: Float): Pair<Float, Float> {
        val (fw, fh) = previewFramePx.takeIf { it.first > 0f && it.second > 0f } ?: previewContainerPx
        val (cw, ch) = previewCoverPx
        val limitX = kotlin.math.abs(fw - cw * zoom) / 2f
        val limitY = kotlin.math.abs(fh - ch * zoom) / 2f
        return panX.coerceIn(-limitX, limitX) to panY.coerceIn(-limitY, limitY)
    }

    /** Fills the frame with the picture, centred, and locks it there. */
    fun fillFraming() {
        fitLocked = false
        framingLocked = true
        updateFraming(0f, 0f, 1f)
    }

    /** The whole picture inside the frame, centred (black around it); not locked. */
    fun showWholeFraming() {
        fitLocked = false
        framingLocked = false
        val (fw, fh) = previewFramePx.takeIf { it.first > 0f && it.second > 0f } ?: previewContainerPx
        val (cw, ch) = previewCoverPx
        val zoom = if (fw > 0f && fh > 0f && cw > 0f && ch > 0f) minOf(fw / cw, fh / ch, 1f) else 1f
        updateFraming(0f, 0f, zoom)
    }

    /** Double tap: fill and lock, or (already filled and locked) show the whole picture. */
    fun toggleFraming() {
        if (framingLocked) showWholeFraming() else fillFraming()
    }

    /** How far the picture may move from the centre while [framingLocked] (no black inside the frame). */
    fun lockedPanLimits(zoom: Float): Pair<Float, Float> {
        val (fw, fh) = previewFramePx.takeIf { it.first > 0f && it.second > 0f } ?: previewContainerPx
        val (cw, ch) = previewCoverPx
        return ((cw * zoom - fw) / 2f).coerceAtLeast(0f) to ((ch * zoom - fh) / 2f).coerceAtLeast(0f)
    }

    /** [project] with the current preview geometry, for the export. */
    fun projectForExport(density: Float, fontScale: Float): VideoProjectState {
        val (cw, ch) = previewContainerPx
        if (cw <= 0f || ch <= 0f) return project
        val (fw, fh) = previewFramePx.takeIf { it.first > 0f && it.second > 0f } ?: previewContainerPx
        return project.copy(
            preview = dev.ander.alexet.data.videoeditor.PreviewGeometry(
                containerWidthPx = cw,
                containerHeightPx = ch,
                frameWidthPx = fw,
                frameHeightPx = fh,
                density = density,
                fontScale = fontScale,
                naturalAspects = previewNaturalAspects,
            ),
        )
    }
    /** Bumped by [seekTo]: a jump the player must follow even while playing. */
    var seekGeneration by mutableIntStateOf(0)
        private set

    /** Moves the playhead as a jump (buttons), not as playback progress. */
    fun seekTo(timelineMs: Long) {
        playheadMs = timelineMs.coerceIn(0L, project.totalTimelineDurationMs.coerceAtLeast(0L))
        seekGeneration++
    }
    var isPlaying by mutableStateOf(false)
    var isScrubbing by mutableStateOf(false)
    var currentTab by mutableStateOf(VideoEditorTab.TIMELINE)
    var timelineScale by mutableStateOf(1.0f) // zoom factor for timeline

    private val undoStack = mutableListOf<VideoProjectState>()
    private val redoStack = mutableListOf<VideoProjectState>()
    private val holderJob = SupervisorJob()
    private val holderScope = CoroutineScope(holderJob + Dispatchers.IO)

    /** Changed with the undo / redo lists (plain lists): the Undo and Redo buttons read it. */
    private var historyVersion by mutableIntStateOf(0)

    val canUndo: Boolean get() = historyVersion.let { undoStack.isNotEmpty() }
    val canRedo: Boolean get() = historyVersion.let { redoStack.isNotEmpty() }

    fun release() {
        holderJob.cancel()
    }

    init {
        val ids = initialMedia.mapTo(HashSet()) { it.id }
        if (restored != null && restored.clips.isNotEmpty() && restored.clips.all { it.media.id in ids }) {
            project = restored
        } else {
            buildInitialProject(initialMedia, context)
        }
    }

    private fun buildInitialProject(initialMedia: List<GalleryMedia>, context: Context) {
        var accumMs = 0L
        val needsAsyncDuration = mutableListOf<GalleryMedia>()
        val initialClips = initialMedia.filter { it.isVideo }.map { media ->
            val duration = if (media.durationMs > 0L) {
                media.durationMs
            } else {
                needsAsyncDuration.add(media)
                PLACEHOLDER_DURATION_MS
            }
            val clip = VideoClipItem(
                media = media,
                sourceDurationMs = duration,
                timelineStartMs = accumMs,
                startTrimMs = 0L,
                endTrimMs = duration,
            )
            accumMs += clip.trimmedDurationMs
            clip
        }
        project = VideoProjectState(clips = initialClips)

        if (needsAsyncDuration.isNotEmpty()) {
            holderScope.launch {
                val updatedDurations = needsAsyncDuration.associate { m ->
                    m.id to queryMediaDuration(context, m)
                }
                withContext(Dispatchers.Main) {
                    // Only clips still on the placeholder, keeping what the user did meanwhile
                    // (moves, trims, splits): a clip changes length, the clips after it shift.
                    var clips = project.clips
                    for (i in clips.indices) {
                        val clip = clips[i]
                        val newDur = updatedDurations[clip.media.id] ?: continue
                        if (newDur <= 0L || clip.sourceDurationMs != PLACEHOLDER_DURATION_MS) continue
                        val oldEnd = clip.timelineStartMs + clip.trimmedDurationMs
                        val updated = clip.copy(
                            sourceDurationMs = newDur,
                            startTrimMs = clip.startTrimMs.coerceAtMost(newDur - 1),
                            endTrimMs = if (clip.endTrimMs >= PLACEHOLDER_DURATION_MS) newDur else clip.endTrimMs.coerceAtMost(newDur),
                        )
                        val delta = updated.timelineStartMs + updated.trimmedDurationMs - oldEnd
                        clips = clips.mapIndexed { j, c ->
                            when {
                                j == i -> updated
                                delta != 0L && c.timelineStartMs >= oldEnd -> c.copy(timelineStartMs = (c.timelineStartMs + delta).coerceAtLeast(0L))
                                else -> c
                            }
                        }
                    }
                    project = project.copy(clips = clips)
                }
            }
        }
    }

    fun pushUndo() {
        undoStack.add(project)
        if (undoStack.size > 25) undoStack.removeAt(0)
        redoStack.clear()
        continuousKey = null
        historyVersion++
    }

    // ---- Continuous edits (drags, sliders): one undo step per gesture ----
    private var continuousKey: String? = null
    private var continuousAt = 0L
    private var gestureBase: VideoProjectState? = null
    /** Unsnapped positions of the item being dragged, so snapping never traps a slow drag. */
    private val rawDrag = HashMap<String, Long>()

    /**
     * Records an undo step at the start of a gesture: calls for the same [key] closer together
     * than [CONTINUOUS_GAP_MS] belong to one gesture. Also clears redo, so a drag after an undo
     * can't be silently thrown away by a later redo.
     */
    private fun recordContinuousEdit(key: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (key != continuousKey || now - continuousAt > CONTINUOUS_GAP_MS) {
            pushUndo()
            rawDrag.clear()
            gestureBase = project
            continuousKey = key
        }
        continuousAt = now
    }

    /**
     * A timeline drag ended: the next drag of the same item is a new undo step and starts from the
     * item's place (a drag right after one that snapped started from the unsnapped spot).
     */
    fun endGesture() {
        continuousKey = null
        rawDrag.clear()
    }

    /** [current] moved by [deltaMs], accumulated over the gesture without snapping. */
    private fun rawValue(key: String, current: Long, deltaMs: Long, bounds: LongRange? = null): Long {
        var raw = (rawDrag[key] ?: current) + deltaMs
        // Kept inside the bounds: dragged 2 s past the start, the item used to stay put until the
        // finger came 2 s back.
        if (bounds != null) raw = raw.coerceIn(bounds.first, maxOf(bounds.first, bounds.last))
        rawDrag[key] = raw
        return raw
    }

    /**
     * Clips may not overlap (preview and export would disagree on which plays): later clips are
     * pushed right, gaps are kept. [pinnedId] wins ties at the same start.
     */
    private fun withoutOverlaps(clips: List<VideoClipItem>, pinnedId: String? = null): List<VideoClipItem> {
        val order = clips.indices.sortedWith(
            compareBy<Int>({ clips[it].timelineStartMs }, { if (clips[it].id == pinnedId) 0 else 1 }),
        )
        val starts = LongArray(clips.size) { clips[it].timelineStartMs }
        var prevEnd = Long.MIN_VALUE
        for (i in order) {
            if (starts[i] < prevEnd) starts[i] = prevEnd
            prevEnd = starts[i] + clips[i].trimmedDurationMs
        }
        return clips.mapIndexed { i, c -> if (starts[i] != c.timelineStartMs) c.copy(timelineStartMs = starts[i]) else c }
    }

    fun undo() {
        continuousKey = null
        if (undoStack.isNotEmpty()) {
            val previous = undoStack.removeAt(undoStack.size - 1)
            redoStack.add(project)
            project = previous
            historyVersion++
        }
    }

    fun redo() {
        continuousKey = null
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeAt(redoStack.size - 1)
            undoStack.add(project)
            project = next
            historyVersion++
        }
    }

    fun selectClip(index: Int) {
        if (index in project.clips.indices) {
            project = project.copy(
                selectedClipIndex = index,
                selectedTextId = null,
                selectedImageId = null,
                selectedAudioId = null,
            )
        }
    }

    fun selectText(id: String?) {
        project = project.copy(
            selectedTextId = id,
            selectedImageId = null,
            selectedAudioId = null,
        )
    }

    fun selectImage(id: String?) {
        project = project.copy(
            selectedImageId = id,
            selectedTextId = null,
            selectedAudioId = null,
        )
    }

    fun selectAudio(id: String?) {
        project = project.copy(
            selectedAudioId = id,
            selectedTextId = null,
            selectedImageId = null,
        )
    }

    fun addClips(mediaList: List<GalleryMedia>, context: Context) {
        pushUndo()
        var currentEnd = project.clips.maxOfOrNull { it.timelineStartMs + it.trimmedDurationMs } ?: 0L
        val newClips = mediaList.filter { it.isVideo }.map { media ->
            val duration = queryMediaDuration(context, media)
            val clip = VideoClipItem(
                media = media,
                sourceDurationMs = duration,
                timelineStartMs = currentEnd,
                startTrimMs = 0L,
                endTrimMs = duration,
            )
            currentEnd += clip.trimmedDurationMs
            clip
        }
        project = project.copy(clips = project.clips + newClips)
    }

    fun removeSelectedClip() {
        val idx = project.selectedClipIndex
        if (idx in project.clips.indices && project.clips.size > 1) {
            pushUndo()
            val mutable = project.clips.toMutableList()
            mutable.removeAt(idx)
            val newIdx = idx.coerceAtMost(mutable.size - 1)
            project = project.copy(clips = mutable, selectedClipIndex = newIdx)
            playheadMs = mutable.getOrNull(newIdx)?.timelineStartMs ?: 0L
        }
    }

    /**
     * Gathers magnetic snap points from all tracks (video clips, audio tracks, text and stickers, playhead, 0L).
     */
    fun getTimelineSnapPoints(excludeId: String? = null): List<Long> {
        val points = mutableSetOf<Long>()
        points.add(0L)
        points.add(playheadMs)

        // Track V1: Video clips boundaries
        for (clip in project.clips) {
            if (clip.id != excludeId) {
                points.add(clip.timelineStartMs)
                points.add(clip.timelineStartMs + clip.trimmedDurationMs)
            }
        }

        // Track A1: Audio tracks boundaries
        for (audio in project.audioTracks) {
            if (audio.id != excludeId) {
                points.add(audio.timelineStartMs)
                points.add(audio.timelineStartMs + audio.trimmedDurationMs)
            }
        }

        // Track T1: Text overlays boundaries
        for (text in project.textOverlays) {
            if (text.id != excludeId) {
                points.add(text.startTimelineMs)
                points.add(text.endTimelineMs)
            }
        }

        // Track S1: Image / Sticker overlays boundaries
        for (img in project.imageOverlays) {
            if (img.id != excludeId) {
                points.add(img.startTimelineMs)
                points.add(img.endTimelineMs)
            }
        }

        return points.toList()
    }

    /**
     * Snaps timeMs to the closest snap point if within thresholdMs.
     */
    fun snapTimestamp(timeMs: Long, excludeId: String? = null, thresholdMs: Long = 200L): Long {
        var closest = timeMs
        var minDiff = thresholdMs + 1
        for (pt in getTimelineSnapPoints(excludeId)) {
            val diff = kotlin.math.abs(timeMs - pt)
            if (diff < minDiff && diff <= thresholdMs) {
                minDiff = diff
                closest = pt
            }
        }
        return closest
    }

    /**
     * Repositions clip along timeline with magnetic snap to all tracks, playhead, or adjacent clip edges.
     */
    fun moveClipTimeline(clipId: String, targetStartMs: Long, snapThresholdMs: Long = 200L) {
        val clip = project.clips.find { it.id == clipId } ?: return
        recordContinuousEdit("clip-move:$clipId")
        val duration = clip.trimmedDurationMs
        val rawStart = targetStartMs.coerceAtLeast(0L)
        val rawEnd = rawStart + duration

        val snappedStart = snapTimestamp(rawStart, excludeId = clipId, thresholdMs = snapThresholdMs)
        val finalStart = if (snappedStart != rawStart) {
            snappedStart
        } else {
            val snappedEnd = snapTimestamp(rawEnd, excludeId = clipId, thresholdMs = snapThresholdMs)
            if (snappedEnd != rawEnd) {
                (snappedEnd - duration).coerceAtLeast(0L)
            } else {
                rawStart
            }
        }

        val clamped = finalStart.coerceAtLeast(0L)
        // Laid out from the gesture's start, so clips pushed aside come back when dragged away again.
        // Only the starts come from there: anything else (a clip length found out while dragging)
        // is taken as it is now.
        val baseStarts = gestureBase?.clips
            ?.takeIf { b -> b.map { it.id } == project.clips.map { it.id } }
            ?.associate { it.id to it.timelineStartMs }
        val mutable = project.clips.map { c ->
            when {
                c.id == clipId -> c.copy(timelineStartMs = clamped)
                baseStarts != null -> c.copy(timelineStartMs = baseStarts[c.id] ?: c.timelineStartMs)
                else -> c
            }
        }
        project = project.copy(clips = withoutOverlaps(mutable, pinnedId = clipId))
    }

    /**
     * Splits selected clip at the current playhead position into two clips with millisecond accuracy.
     */
    fun splitSelectedClipAtPlayhead(): Boolean {
        val clipInfo = mapPlayheadToClip(playheadMs) ?: return false
        val clip = clipInfo.first
        val clipLocalTimeMs = clipInfo.second
        val clipIndex = project.clips.indexOfFirst { it.id == clip.id }
        if (clipIndex < 0) return false

        val sourceSplitMs = clip.startTrimMs + (clipLocalTimeMs * clip.speed).toLong()
        if (sourceSplitMs <= clip.startTrimMs + 100L || sourceSplitMs >= clip.endTrimMs - 100L) {
            return false // Cannot split too close to edges
        }

        pushUndo()
        val firstTrimmedDur = ((sourceSplitMs - clip.startTrimMs) / clip.speed).toLong()
        val firstClip = clip.copy(
            id = UUID.randomUUID().toString(),
            endTrimMs = sourceSplitMs,
        )
        val secondClip = clip.copy(
            id = UUID.randomUUID().toString(),
            timelineStartMs = clip.timelineStartMs + firstTrimmedDur,
            startTrimMs = sourceSplitMs,
        )

        val mutable = project.clips.toMutableList()
        mutable.removeAt(clipIndex)
        mutable.add(clipIndex, secondClip)
        mutable.add(clipIndex, firstClip)

        project = project.copy(clips = mutable, selectedClipIndex = clipIndex + 1)
        return true
    }

    fun updateTrim(startTrimMs: Long, endTrimMs: Long) {
        val idx = project.selectedClipIndex
        val clip = project.clips.getOrNull(idx) ?: return
        val (clampedStart, clampedEnd) = VideoExportPlanner.clampTrimRange(
            startTrimMs,
            endTrimMs,
            clip.sourceDurationMs,
        )

        pushUndo()
        val updated = clip.copy(startTrimMs = clampedStart, endTrimMs = clampedEnd)
        val mutable = project.clips.toMutableList()
        mutable[idx] = updated
        project = project.copy(clips = mutable)
    }

    fun updateClipTrim(clipId: String, startTrimMs: Long, endTrimMs: Long, newTimelineStartMs: Long? = null) {
        val clipIndex = project.clips.indexOfFirst { it.id == clipId }
        if (clipIndex < 0) return
        recordContinuousEdit("clip-trim:$clipId")
        val clip = project.clips[clipIndex]
        val (clampedStart, clampedEnd) = VideoExportPlanner.clampTrimRange(
            startTrimMs,
            endTrimMs,
            clip.sourceDurationMs,
        )
        val mutable = project.clips.toMutableList()
        mutable[clipIndex] = clip.copy(
            startTrimMs = clampedStart,
            endTrimMs = clampedEnd,
            timelineStartMs = newTimelineStartMs?.coerceAtLeast(0L) ?: clip.timelineStartMs,
        )
        project = project.copy(clips = withoutOverlaps(mutable, pinnedId = clipId))
    }

    fun updateSpeed(speed: Float, applyToAll: Boolean) {
        val clampedSpeed = speed.coerceIn(0.25f, 4.0f)
        pushUndo()
        val mutable = if (applyToAll) {
            project.clips.map { it.copy(speed = clampedSpeed) }
        } else {
            val idx = project.selectedClipIndex
            val clip = project.clips.getOrNull(idx) ?: return
            val m = project.clips.toMutableList()
            m[idx] = clip.copy(speed = clampedSpeed)
            m
        }
        // A slower clip gets longer: the ones after it move instead of being overlapped.
        project = project.copy(clips = withoutOverlaps(mutable))
    }

    fun updateVolume(volume: Float, isMuted: Boolean, recordUndo: Boolean = true) {
        val idx = project.selectedClipIndex
        val clip = project.clips.getOrNull(idx) ?: return
        if (recordUndo) pushUndo() else recordContinuousEdit("volume:$idx")
        val mutable = project.clips.toMutableList()
        mutable[idx] = clip.copy(volume = volume.coerceIn(0f, 2f), isMuted = isMuted)
        project = project.copy(clips = mutable)
    }

    fun updateAdjust(brightness: Float, contrast: Float, saturation: Float, gamma: Float) {
        val idx = project.selectedClipIndex
        val clip = project.clips.getOrNull(idx) ?: return
        recordContinuousEdit("adjust:$idx")
        val mutable = project.clips.toMutableList()
        mutable[idx] = clip.copy(
            brightness = brightness.coerceIn(-1f, 1f),
            contrast = contrast.coerceIn(-1f, 1f),
            saturation = saturation.coerceIn(-1f, 1f),
            gamma = gamma.coerceIn(0.2f, 3.0f),
        )
        project = project.copy(clips = mutable)
    }

    fun updateFraming(
        panX: Float,
        panY: Float,
        zoomScale: Float,
        maxPanX: Float? = null,
        maxPanY: Float? = null,
    ) {
        val idx = project.selectedClipIndex
        val clip = project.clips.getOrNull(idx) ?: return
        recordContinuousEdit("framing:$idx")
        // Down to 0.2: the whole of a landscape video in a 9:16 frame is about 0.32.
        val clampedZoom = zoomScale.coerceIn(MIN_FRAMING_ZOOM, 5.0f)
        val clampedPanX = if (maxPanX != null) panX.coerceIn(-maxPanX, maxPanX) else panX
        val clampedPanY = if (maxPanY != null) panY.coerceIn(-maxPanY, maxPanY) else panY
        val mutable = project.clips.toMutableList()
        mutable[idx] = clip.copy(
            panX = clampedPanX,
            panY = clampedPanY,
            zoomScale = clampedZoom,
        )
        project = project.copy(clips = mutable)
    }

    fun updateRotationFlip(rotation: Int, flipH: Boolean, flipV: Boolean, recordUndo: Boolean = true) {
        val idx = if (project.selectedClipIndex in project.clips.indices) {
            project.selectedClipIndex
        } else if (project.clips.isNotEmpty()) {
            0
        } else {
            return
        }
        val clip = project.clips[idx]
        if (recordUndo) pushUndo() else recordContinuousEdit("rotation:$idx")
        val mutable = project.clips.toMutableList()
        mutable[idx] = clip.copy(rotation = rotation, flipH = flipH, flipV = flipV)
        project = project.copy(clips = mutable, selectedClipIndex = idx)
    }

    fun setAspectRatio(aspectRatio: VideoProjectAspectRatio) {
        pushUndo()
        project = project.copy(aspectRatio = aspectRatio)
    }

    fun toggleFramingMode(enabled: Boolean? = null) {
        project = project.copy(isFramingMode = enabled ?: !project.isFramingMode)
    }

    fun addAudioTrack(uri: Uri, displayName: String, durationMs: Long, isVideoSource: Boolean = false) {
        pushUndo()
        val currentPlayhead = playheadMs
        val track = AudioTrackItem(
            uri = uri,
            displayName = displayName,
            sourceDurationMs = durationMs,
            timelineStartMs = currentPlayhead,
            startTrimMs = 0L,
            endTrimMs = durationMs,
            isVideoSource = isVideoSource,
        )
        project = project.copy(audioTracks = project.audioTracks + track, selectedAudioId = track.id)
    }

    fun moveAudioTimeline(id: String, deltaMs: Long, snapThresholdMs: Long = 200L) {
        val audio = project.audioTracks.find { it.id == id } ?: return
        recordContinuousEdit("audio-move:$id")
        val duration = audio.trimmedDurationMs
        val rawStart = rawValue("audio-move:$id", audio.timelineStartMs, deltaMs, 0L..Long.MAX_VALUE)
        val rawEnd = rawStart + duration

        val snappedStart = snapTimestamp(rawStart, excludeId = id, thresholdMs = snapThresholdMs)
        val finalStart = if (snappedStart != rawStart) {
            snappedStart
        } else {
            val snappedEnd = snapTimestamp(rawEnd, excludeId = id, thresholdMs = snapThresholdMs)
            if (snappedEnd != rawEnd) {
                (snappedEnd - duration).coerceAtLeast(0L)
            } else {
                rawStart
            }
        }

        val clamped = finalStart.coerceAtLeast(0L)
        val mutable = project.audioTracks.map {
            if (it.id == id) it.copy(timelineStartMs = clamped) else it
        }
        project = project.copy(audioTracks = mutable)
    }

    fun updateAudioStart(id: String, deltaMs: Long, snapThresholdMs: Long = 200L) {
        val audio = project.audioTracks.find { it.id == id } ?: return
        recordContinuousEdit("audio-start:$id")
        val (rawStart, _) = VideoExportPlanner.clampTrimRange(
            rawValue("audio-start:$id", audio.startTrimMs, deltaMs),
            audio.endTrimMs,
            audio.sourceDurationMs,
        )
        val absoluteTimelinePos = audio.timelineStartMs + rawStart
        val snappedAbsolute = snapTimestamp(absoluteTimelinePos, excludeId = id, thresholdMs = snapThresholdMs)
        val snappedStartCandidate = snappedAbsolute - audio.timelineStartMs
        val finalStart = if (snappedAbsolute != absoluteTimelinePos) {
            VideoExportPlanner.clampTrimRange(snappedStartCandidate, audio.endTrimMs, audio.sourceDurationMs).first
        } else {
            rawStart
        }

        val mutable = project.audioTracks.map {
            if (it.id == id) it.copy(startTrimMs = finalStart) else it
        }
        project = project.copy(audioTracks = mutable)
    }

    fun updateAudioEnd(id: String, deltaMs: Long, snapThresholdMs: Long = 200L) {
        val audio = project.audioTracks.find { it.id == id } ?: return
        recordContinuousEdit("audio-end:$id")
        val (_, rawEnd) = VideoExportPlanner.clampTrimRange(
            audio.startTrimMs,
            rawValue("audio-end:$id", audio.endTrimMs, deltaMs),
            audio.sourceDurationMs,
        )
        val absoluteTimelinePos = audio.timelineStartMs + rawEnd
        val snappedAbsolute = snapTimestamp(absoluteTimelinePos, excludeId = id, thresholdMs = snapThresholdMs)
        val snappedEndCandidate = snappedAbsolute - audio.timelineStartMs
        val finalEnd = if (snappedAbsolute != absoluteTimelinePos) {
            VideoExportPlanner.clampTrimRange(audio.startTrimMs, snappedEndCandidate, audio.sourceDurationMs).second
        } else {
            rawEnd
        }

        val mutable = project.audioTracks.map {
            if (it.id == id) it.copy(endTrimMs = finalEnd) else it
        }
        project = project.copy(audioTracks = mutable)
    }

    fun updateAudioDuration(id: String, startMs: Long, endMs: Long, timelineStartMs: Long? = null) {
        val track = project.audioTracks.find { it.id == id } ?: return
        recordContinuousEdit("audio-dur:$id")
        val (clampedStart, clampedEnd) = VideoExportPlanner.clampTrimRange(
            startMs,
            endMs,
            track.sourceDurationMs,
        )
        val mutable = project.audioTracks.map {
            if (it.id == id) {
                it.copy(
                    startTrimMs = clampedStart,
                    endTrimMs = clampedEnd,
                    timelineStartMs = timelineStartMs ?: it.timelineStartMs,
                )
            } else it
        }
        project = project.copy(audioTracks = mutable)
    }

    fun removeAudioTrack(id: String) {
        pushUndo()
        project = project.copy(audioTracks = project.audioTracks.filterNot { it.id == id })
    }

    /**
     * End of a new overlay starting at [start]: 4 s, but not past the end of the video (the export
     * stops there, and a longer overlay only stretched the timeline).
     */
    private fun newOverlayEnd(start: Long): Long {
        val videoEnd = project.clips.maxOfOrNull { it.timelineStartMs + it.trimmedDurationMs } ?: 0L
        val wanted = start + 4000L
        return if (videoEnd >= start + 500L) minOf(wanted, videoEnd) else wanted
    }

    /** The text panel when open: for a new text, or for the text it was opened on. */
    var textPanel by mutableStateOf<TextPanelRequest?>(null)

    /** A new text from the text panel, at the playhead. */
    fun addStyledText(style: TextStyleChoice) {
        pushUndo()
        val currentPlayhead = playheadMs
        val overlay = style.applyTo(TextOverlayItem(text = style.text)).copy(
            startTimelineMs = currentPlayhead,
            endTimelineMs = newOverlayEnd(currentPlayhead),
        )
        project = project.copy(
            textOverlays = project.textOverlays + overlay,
            selectedTextId = overlay.id,
        )
    }

    /** The text panel's values for an existing text; its place, size, turn and time stay. */
    fun restyleText(id: String, style: TextStyleChoice) {
        if (project.textOverlays.none { it.id == id }) return
        pushUndo()
        project = project.copy(
            textOverlays = project.textOverlays.map { if (it.id == id) style.applyTo(it) else it },
        )
    }

    fun moveTextOverlay(id: String, deltaX: Float, deltaY: Float) {
        recordContinuousEdit("text-xform:$id")
        val mutable = project.textOverlays.map {
            if (it.id == id) {
                val newX = (it.normalizedX + deltaX).coerceIn(0.02f, 0.98f)
                val newY = (it.normalizedY + deltaY).coerceIn(0.02f, 0.98f)
                it.copy(normalizedX = newX, normalizedY = newY)
            } else it
        }
        project = project.copy(textOverlays = mutable)
    }

    fun scaleTextOverlay(id: String, scaleDelta: Float) {
        recordContinuousEdit("text-xform:$id")
        val mutable = project.textOverlays.map {
            if (it.id == id) {
                val newScale = (it.scale + scaleDelta).coerceIn(0.3f, 5.0f)
                it.copy(scale = newScale)
            } else it
        }
        project = project.copy(textOverlays = mutable)
    }

    fun rotateTextOverlay(id: String, deltaDeg: Float) {
        recordContinuousEdit("text-xform:$id")
        val mutable = project.textOverlays.map {
            if (it.id == id) {
                val newRot = (it.rotationDeg + deltaDeg) % 360f
                val normalizedRot = if (newRot < 0f) newRot + 360f else newRot
                it.copy(rotationDeg = normalizedRot)
            } else it
        }
        project = project.copy(textOverlays = mutable)
    }

    fun setTextRotation(id: String, degrees: Float) {
        recordContinuousEdit("text-xform:$id")
        val mutable = project.textOverlays.map {
            if (it.id == id) {
                val norm = ((degrees % 360f) + 360f) % 360f
                it.copy(rotationDeg = norm)
            } else it
        }
        project = project.copy(textOverlays = mutable)
    }

    fun moveTextTimeline(id: String, deltaMs: Long, snapThresholdMs: Long = 200L) {
        val text = project.textOverlays.find { it.id == id } ?: return
        recordContinuousEdit("text-move:$id")
        val duration = text.endTimelineMs - text.startTimelineMs
        val rawStart = rawValue("text-move:$id", text.startTimelineMs, deltaMs, 0L..Long.MAX_VALUE)
        val rawEnd = rawStart + duration

        val snappedStart = snapTimestamp(rawStart, excludeId = id, thresholdMs = snapThresholdMs)
        val finalStart = if (snappedStart != rawStart) {
            snappedStart
        } else {
            val snappedEnd = snapTimestamp(rawEnd, excludeId = id, thresholdMs = snapThresholdMs)
            if (snappedEnd != rawEnd) {
                (snappedEnd - duration).coerceAtLeast(0L)
            } else {
                rawStart
            }
        }

        val clamped = finalStart.coerceAtLeast(0L)
        val mutable = project.textOverlays.map {
            if (it.id == id) it.copy(startTimelineMs = clamped, endTimelineMs = clamped + duration) else it
        }
        project = project.copy(textOverlays = mutable)
    }

    fun updateTextStart(id: String, deltaMs: Long, snapThresholdMs: Long = 200L) {
        val text = project.textOverlays.find { it.id == id } ?: return
        recordContinuousEdit("text-start:$id")
        val rawStart = rawValue("text-start:$id", text.startTimelineMs, deltaMs, 0L..text.endTimelineMs - 200L)
        val snappedStart = snapTimestamp(rawStart, excludeId = id, thresholdMs = snapThresholdMs).coerceIn(0L, text.endTimelineMs - 200L)

        val mutable = project.textOverlays.map {
            if (it.id == id) it.copy(startTimelineMs = snappedStart) else it
        }
        project = project.copy(textOverlays = mutable)
    }

    fun updateTextEnd(id: String, deltaMs: Long, snapThresholdMs: Long = 200L) {
        val text = project.textOverlays.find { it.id == id } ?: return
        recordContinuousEdit("text-end:$id")
        val rawEnd = rawValue("text-end:$id", text.endTimelineMs, deltaMs, text.startTimelineMs + 200L..Long.MAX_VALUE)
        val snappedEnd = snapTimestamp(rawEnd, excludeId = id, thresholdMs = snapThresholdMs).coerceAtLeast(text.startTimelineMs + 200L)

        val mutable = project.textOverlays.map {
            if (it.id == id) it.copy(endTimelineMs = snappedEnd) else it
        }
        project = project.copy(textOverlays = mutable)
    }

    fun updateTextDuration(id: String, startMs: Long, endMs: Long) {
        recordContinuousEdit("text-dur:$id")
        val clampedStart = startMs.coerceAtLeast(0L)
        val clampedEnd = endMs.coerceAtLeast(clampedStart + 200L)
        val mutable = project.textOverlays.map {
            if (it.id == id) it.copy(startTimelineMs = clampedStart, endTimelineMs = clampedEnd) else it
        }
        project = project.copy(textOverlays = mutable)
    }

    /** Another picture for a sticker; its place, turn, size and time stay. */
    fun replaceImageOverlay(id: String, uri: Uri) {
        if (project.imageOverlays.none { it.id == id }) return
        pushUndo()
        project = project.copy(
            imageOverlays = project.imageOverlays.map { if (it.id == id) it.copy(uri = uri) else it },
        )
    }

    fun addImageOverlay(uri: Uri) {
        pushUndo()
        val currentPlayhead = playheadMs
        val overlay = ImageOverlayItem(
            uri = uri,
            startTimelineMs = currentPlayhead,
            endTimelineMs = newOverlayEnd(currentPlayhead),
        )
        project = project.copy(
            imageOverlays = project.imageOverlays + overlay,
            selectedImageId = overlay.id,
        )
    }

    fun moveImageOverlay(id: String, deltaX: Float, deltaY: Float) {
        recordContinuousEdit("image-xform:$id")
        val mutable = project.imageOverlays.map {
            if (it.id == id) {
                val newX = (it.normalizedX + deltaX).coerceIn(0.02f, 0.98f)
                val newY = (it.normalizedY + deltaY).coerceIn(0.02f, 0.98f)
                it.copy(normalizedX = newX, normalizedY = newY)
            } else it
        }
        project = project.copy(imageOverlays = mutable)
    }

    fun scaleImageOverlay(id: String, scaleDelta: Float) {
        recordContinuousEdit("image-xform:$id")
        val mutable = project.imageOverlays.map {
            if (it.id == id) {
                val newScale = (it.scale + scaleDelta).coerceIn(0.3f, 5.0f)
                it.copy(scale = newScale)
            } else it
        }
        project = project.copy(imageOverlays = mutable)
    }

    fun rotateImageOverlay(id: String, deltaDeg: Float) {
        recordContinuousEdit("image-xform:$id")
        val mutable = project.imageOverlays.map {
            if (it.id == id) {
                val newRot = (it.rotationDeg + deltaDeg) % 360f
                val normalizedRot = if (newRot < 0f) newRot + 360f else newRot
                it.copy(rotationDeg = normalizedRot)
            } else it
        }
        project = project.copy(imageOverlays = mutable)
    }

    fun setImageRotation(id: String, degrees: Float) {
        recordContinuousEdit("image-xform:$id")
        val mutable = project.imageOverlays.map {
            if (it.id == id) {
                val norm = ((degrees % 360f) + 360f) % 360f
                it.copy(rotationDeg = norm)
            } else it
        }
        project = project.copy(imageOverlays = mutable)
    }

    fun moveImageTimeline(id: String, deltaMs: Long, snapThresholdMs: Long = 200L) {
        val img = project.imageOverlays.find { it.id == id } ?: return
        recordContinuousEdit("image-move:$id")
        val duration = img.endTimelineMs - img.startTimelineMs
        val rawStart = rawValue("image-move:$id", img.startTimelineMs, deltaMs, 0L..Long.MAX_VALUE)
        val rawEnd = rawStart + duration

        val snappedStart = snapTimestamp(rawStart, excludeId = id, thresholdMs = snapThresholdMs)
        val finalStart = if (snappedStart != rawStart) {
            snappedStart
        } else {
            val snappedEnd = snapTimestamp(rawEnd, excludeId = id, thresholdMs = snapThresholdMs)
            if (snappedEnd != rawEnd) {
                (snappedEnd - duration).coerceAtLeast(0L)
            } else {
                rawStart
            }
        }

        val clamped = finalStart.coerceAtLeast(0L)
        val mutable = project.imageOverlays.map {
            if (it.id == id) it.copy(startTimelineMs = clamped, endTimelineMs = clamped + duration) else it
        }
        project = project.copy(imageOverlays = mutable)
    }

    fun updateImageStart(id: String, deltaMs: Long, snapThresholdMs: Long = 200L) {
        val img = project.imageOverlays.find { it.id == id } ?: return
        recordContinuousEdit("image-start:$id")
        val rawStart = rawValue("image-start:$id", img.startTimelineMs, deltaMs, 0L..img.endTimelineMs - 200L)
        val snappedStart = snapTimestamp(rawStart, excludeId = id, thresholdMs = snapThresholdMs).coerceIn(0L, img.endTimelineMs - 200L)

        val mutable = project.imageOverlays.map {
            if (it.id == id) it.copy(startTimelineMs = snappedStart) else it
        }
        project = project.copy(imageOverlays = mutable)
    }

    fun updateImageEnd(id: String, deltaMs: Long, snapThresholdMs: Long = 200L) {
        val img = project.imageOverlays.find { it.id == id } ?: return
        recordContinuousEdit("image-end:$id")
        val rawEnd = rawValue("image-end:$id", img.endTimelineMs, deltaMs, img.startTimelineMs + 200L..Long.MAX_VALUE)
        val snappedEnd = snapTimestamp(rawEnd, excludeId = id, thresholdMs = snapThresholdMs).coerceAtLeast(img.startTimelineMs + 200L)

        val mutable = project.imageOverlays.map {
            if (it.id == id) it.copy(endTimelineMs = snappedEnd) else it
        }
        project = project.copy(imageOverlays = mutable)
    }

    fun updateImageDuration(id: String, startMs: Long, endMs: Long) {
        recordContinuousEdit("image-dur:$id")
        val clampedStart = startMs.coerceAtLeast(0L)
        val clampedEnd = endMs.coerceAtLeast(clampedStart + 200L)
        val mutable = project.imageOverlays.map {
            if (it.id == id) it.copy(startTimelineMs = clampedStart, endTimelineMs = clampedEnd) else it
        }
        project = project.copy(imageOverlays = mutable)
    }

    fun updateTextOverlay(item: TextOverlayItem) {
        val mutable = project.textOverlays.map { if (it.id == item.id) item else it }
        project = project.copy(textOverlays = mutable)
    }

    fun updateImageOverlay(item: ImageOverlayItem) {
        val mutable = project.imageOverlays.map { if (it.id == item.id) item else it }
        project = project.copy(imageOverlays = mutable)
    }

    fun removeTextOverlay(id: String) {
        pushUndo()
        project = project.copy(textOverlays = project.textOverlays.filterNot { it.id == id }, selectedTextId = null)
    }

    fun removeImageOverlay(id: String) {
        pushUndo()
        project = project.copy(imageOverlays = project.imageOverlays.filterNot { it.id == id }, selectedImageId = null)
    }

    /**
     * Maps a global timeline timestamp (ms) to the specific VideoClipItem and the local offset (ms) within that clip.
     */
    fun mapPlayheadToClip(timelineMs: Long): Pair<VideoClipItem, Long>? {
        val clip = VideoExportPlanner.clipAt(project.clips, timelineMs) ?: return null
        val localOffsetMs = (timelineMs - clip.timelineStartMs).coerceIn(0L, clip.trimmedDurationMs)
        return clip to localOffsetMs
    }

    private companion object {
        /** Length shown for a clip until its real duration is read. */
        const val PLACEHOLDER_DURATION_MS = 10_000L
    }

    private fun queryMediaDuration(context: Context, media: GalleryMedia): Long {
        if (media.durationMs > 0L) return media.durationMs
        val retriever = MediaMetadataRetriever()
        return try {
            dev.ander.alexet.data.Mp4MetaRepair.setDataSource(retriever, context, media.absolutePath, media.uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: media.durationMs
        } catch (_: Exception) {
            media.durationMs
        } finally {
            runCatching { retriever.release() }
        }
    }
}

@Composable
fun rememberVideoEditorState(
    initialMedia: List<GalleryMedia>,
    context: Context,
): VideoEditorStateHolder {
    // Saved as text with the screen's state: a process ended in the background brings the
    // project back (cuts, texts, stickers), not the original clips.
    val saver = remember(initialMedia, context) {
        androidx.compose.runtime.saveable.Saver<VideoEditorStateHolder, String>(
            save = { dev.ander.alexet.data.videoeditor.VideoProjectJson.encode(it.project) },
            restore = { VideoEditorStateHolder(initialMedia, context, dev.ander.alexet.data.videoeditor.VideoProjectJson.decode(it)) },
        )
    }
    val state = androidx.compose.runtime.saveable.rememberSaveable(initialMedia, saver = saver) {
        VideoEditorStateHolder(initialMedia, context)
    }
    DisposableEffect(state) {
        onDispose {
            state.release()
        }
    }
    return state
}

/** Calls of one continuous edit closer together than this are one undo step. */
private const val CONTINUOUS_GAP_MS = 700L
/** Smallest clip zoom in framing (the whole picture of an extreme ratio still fits). */
internal const val MIN_FRAMING_ZOOM = 0.2f
