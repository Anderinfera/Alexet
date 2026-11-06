package dev.ander.alexet.ui.videoeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import dev.ander.alexet.R
import java.io.File
import kotlin.math.roundToInt

@Composable
fun VideoTimeline(
    state: VideoEditorStateHolder,
    onAddClipClick: () -> Unit,
    onAddAudioClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = state.project
    val totalDurationMs = project.totalTimelineDurationMs.coerceAtLeast(1000L)
    val density = LocalDensity.current
    // System (Material You) accent instead of a fixed blue.
    val accent = MaterialTheme.colorScheme.primary

    val startMarginDp = 20.dp
    val startMarginPx = with(density) { startMarginDp.toPx() }

    // Pixels per second calculation; the zoom is capped so a long timeline stays layable.
    val scale = TimelineZoom.effective(state.timelineScale, totalDurationMs)
    val zoomRange = TimelineZoom.range(totalDurationMs)
    val basePxPerSec = TimelineZoom.PX_PER_SECOND * scale
    val pxPerMs = basePxPerSec / 1000f

    val totalWidthPx = (totalDurationMs * pxPerMs).coerceAtLeast(400f)
    val totalWidthDp = with(density) { totalWidthPx.toDp() }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF121214))
            .padding(vertical = 4.dp),
    ) {
        // --- Timeline Header: Timecode & Quick Action Buttons ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Millisecond precision timer
            PlayheadTimecode(state, totalDurationMs)

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Zoom Out / In
                IconButton(
                    onClick = { state.timelineScale = (scale / 1.3f).coerceIn(zoomRange) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Rounded.ZoomOut, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = { state.timelineScale = (scale * 1.3f).coerceIn(zoomRange) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Rounded.ZoomIn, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                }

                Spacer(Modifier.width(4.dp))

                // Split Clip Button
                FilledTonalIconButton(
                    onClick = { state.splitSelectedClipAtPlayhead() },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Rounded.ContentCut,
                        contentDescription = stringResource(R.string.split_clip),
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Add Clip Button
                FilledTonalIconButton(
                    onClick = onAddClipClick,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.add_clip),
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Delete Clip Button
                if (project.clips.size > 1) {
                    IconButton(
                        onClick = { state.removeSelectedClip() },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.delete_clip),
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // --- Interactive Multi-Track Scrollable Timeline ---
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(175.dp),
        ) {
            val parentWidth = maxWidth
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(scrollState),
            ) {
                // Track Width Container — always at least full screen width
                val trackContentWidth = startMarginDp + totalWidthDp + 300.dp
                val effectiveTrackWidth = if (trackContentWidth < parentWidth) parentWidth else trackContentWidth
                Box(
                    modifier = Modifier
                        .width(effectiveTrackWidth)
                        .fillMaxHeight(),
                ) {
                // 1. Time Ruler (Top Bar)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(Color(0xFF18181B))
                        .pointerInput(totalDurationMs, pxPerMs, startMarginPx) {
                            detectTapGestures(
                                onPress = { offset ->
                                    state.isScrubbing = true
                                    val clickedMs = ((offset.x - startMarginPx) / pxPerMs).toLong().coerceIn(0L, totalDurationMs)
                                    state.playheadMs = clickedMs
                                    tryAwaitRelease()
                                    state.isScrubbing = false
                                },
                            )
                        }
                        .pointerInput(totalDurationMs, pxPerMs, startMarginPx) {
                            detectHorizontalDragGestures(
                                onDragStart = { state.isScrubbing = true },
                                onDragEnd = { state.isScrubbing = false },
                                onDragCancel = { state.isScrubbing = false },
                            ) { change, dragAmount ->
                                change.consume()
                                val deltaMs = (dragAmount / pxPerMs).toLong()
                                state.playheadMs = (state.playheadMs + deltaMs).coerceIn(0L, totalDurationMs)
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val secStep = when {
                            scale > 2.0f -> 1L
                            scale > 0.8f -> 2L
                            scale > 0.1f -> 5L
                            // Hours of video squeezed below the old minimum zoom: a tick a minute.
                            else -> 60L
                        }
                        val majorTickHeight = size.height * 0.45f
                        val minorTickHeight = size.height * 0.25f
                        val strokeMajor = 1.dp.toPx()
                        val strokeMinor = 0.8.dp.toPx()
                        val colorMajor = Color.White.copy(alpha = 0.5f)
                        val colorMinor = Color.White.copy(alpha = 0.25f)

                        var curSec = 0L
                        while (true) {
                            val x = startMarginPx + (curSec * 1000L * pxPerMs)
                            if (x > size.width) break

                            // Major tick
                            drawLine(
                                color = colorMajor,
                                start = Offset(x, size.height - majorTickHeight),
                                end = Offset(x, size.height),
                                strokeWidth = strokeMajor,
                            )
                            // Minor tick (half step)
                            val halfX = startMarginPx + (curSec * 1000L + (secStep * 500L)) * pxPerMs
                            if (halfX <= size.width) {
                                drawLine(
                                    color = colorMinor,
                                    start = Offset(halfX, size.height - minorTickHeight),
                                    end = Offset(halfX, size.height),
                                    strokeWidth = strokeMinor,
                                )
                            }
                            curSec += secStep
                        }
                    }
                }

                // 2. Track V1: Video Clips (height 60dp, offset 26dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 26.dp)
                        .height(60.dp),
                ) {
                    project.clips.forEachIndexed { index, clip ->
                        val isSelected = index == project.selectedClipIndex
                        val clipLeftPx = clip.timelineStartMs * pxPerMs
                        val clipWidthPx = clip.trimmedDurationMs * pxPerMs
                        val clipWidthDp = with(density) { clipWidthPx.toDp() }.coerceAtLeast(24.dp)
                        val clipLeftDp = startMarginDp + with(density) { clipLeftPx.toDp() }

                        var initialDragStartMs by remember(clip.id) { mutableLongStateOf(0L) }
                        var initialTrimStartMs by remember(clip.id) { mutableLongStateOf(0L) }
                        var initialTrimEndMs by remember(clip.id) { mutableLongStateOf(0L) }

                        Box(
                            modifier = Modifier
                                .offset(x = clipLeftDp)
                                .width(clipWidthDp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1E1E24))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) accent else Color.White.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .pointerInput(clip.id) {
                                    detectTapGestures {
                                        state.selectClip(index)
                                    }
                                }
                                .pointerInput(clip.id, pxPerMs) {
                                    // Drag clip along timeline with magnetic snapping
                                    detectHorizontalDragGestures(
                                        onDragStart = {
                                            // From the project, not the captured clip: this gesture
                                            // handler outlives earlier moves of the same clip.
                                            val current = state.project.clips.find { it.id == clip.id }
                                            state.project.clips.indexOfFirst { it.id == clip.id }
                                                .takeIf { it >= 0 }?.let { state.selectClip(it) }
                                            initialDragStartMs = current?.timelineStartMs ?: clip.timelineStartMs
                                        },
                                        // The gesture is over: the next one is a new undo step and starts from where the item is.
                                        onDragEnd = { state.endGesture() },
                                        onDragCancel = { state.endGesture() },
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val deltaMs = (dragAmount / pxPerMs).toLong()
                                        // Not below 0: dragged past the start, the clip waited for
                                        // the finger to come all the way back.
                                        initialDragStartMs = (initialDragStartMs + deltaMs).coerceAtLeast(0L)
                                        state.moveClipTimeline(clip.id, initialDragStartMs)
                                    }
                                },
                        ) {
                            // Full Unobstructed Video Thumbnail Strip
                            AsyncImage(
                                // A video file is no image for the default decoders: the gallery's
                                // thumbnail fetcher reads a frame (and repairs broken metadata).
                                model = dev.ander.alexet.ui.components.MediaThumb(
                                    uri = clip.media.uri,
                                    id = clip.media.id,
                                    isVideo = clip.media.isVideo,
                                    path = clip.media.absolutePath,
                                ),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.92f),
                            )

                            // Top-left compact track badge & duration (CapCut style)
                            if (clipWidthDp >= 36.dp) {
                                val badgeLabel = if (clipWidthDp < 75.dp) "V${index + 1}" else "V${index + 1} • ${formatDurationMs(clip.trimmedDurationMs)}"
                                Text(
                                    text = badgeLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(
                                            start = 8.dp,
                                            top = 3.dp,
                                            end = 8.dp,
                                        )
                                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(2.dp))
                                        .padding(horizontal = 3.dp, vertical = 1.dp),
                                )
                            }
                        }

                        // Trim handles: thin ones on the edges, bigger ones outside when selected.
                        val clipEndMs = clip.timelineStartMs + clip.trimmedDurationMs
                        val otherClips = project.clips.filter { it.id != clip.id }
                            .map { it.timelineStartMs to it.timelineStartMs + it.trimmedDurationMs }
                        val currentClip by rememberUpdatedState(clip)
                        var fixedRightMs by remember(clip.id) { mutableLongStateOf(0L) }
                        EdgeHandles(
                            itemKey = clip.id to pxPerMs,
                            leftDp = clipLeftDp,
                            widthDp = clipWidthDp,
                            selected = isSelected,
                            color = accent,
                            innerWidth = 5.dp,
                            // More of the video before its start, and room on the timeline for it.
                            startRoom = EdgeRoom(
                                canGrow = clip.startTrimMs > 0L && clip.timelineStartMs > 0L,
                                roomPx = roomBefore(clip.timelineStartMs, otherClips, pxPerMs),
                            ),
                            endRoom = EdgeRoom(
                                canGrow = clip.endTrimMs < clip.sourceDurationMs,
                                roomPx = roomAfter(clipEndMs, otherClips, pxPerMs),
                            ),
                            startDrag = EdgeDrag(
                                onStart = {
                                    val c = currentClip
                                    state.project.clips.indexOfFirst { it.id == c.id }
                                        .takeIf { it >= 0 }?.let { state.selectClip(it) }
                                    initialTrimStartMs = c.startTrimMs
                                    fixedRightMs = c.timelineStartMs + c.trimmedDurationMs
                                },
                                onDrag = { dragAmount ->
                                    val c = currentClip
                                    val deltaMs = (dragAmount / pxPerMs * c.speed).toLong()
                                    val maxStart = (c.endTrimMs - 150L).coerceAtLeast(0L)
                                    initialTrimStartMs = (initialTrimStartMs + deltaMs).coerceIn(0L, maxStart)
                                    val newTrimmedDur = ((c.endTrimMs - initialTrimStartMs) / c.speed).toLong()
                                    val newTimelineStart = (fixedRightMs - newTrimmedDur).coerceAtLeast(0L)
                                    state.updateClipTrim(c.id, initialTrimStartMs, c.endTrimMs, newTimelineStart)
                                    state.playheadMs = newTimelineStart
                                },
                                // The gesture is over: the next one is a new undo step and starts from where the item is.
                                onEnd = { state.endGesture() },
                            ),
                            endDrag = EdgeDrag(
                                onStart = {
                                    val c = currentClip
                                    state.project.clips.indexOfFirst { it.id == c.id }
                                        .takeIf { it >= 0 }?.let { state.selectClip(it) }
                                    initialTrimEndMs = c.endTrimMs
                                },
                                onDrag = { dragAmount ->
                                    val c = currentClip
                                    val deltaMs = (dragAmount / pxPerMs * c.speed).toLong()
                                    val minEnd = (c.startTrimMs + 150L).coerceAtMost(c.sourceDurationMs)
                                    initialTrimEndMs = (initialTrimEndMs + deltaMs).coerceIn(minEnd, c.sourceDurationMs)
                                    state.updateClipTrim(c.id, c.startTrimMs, initialTrimEndMs)
                                    val currentTrimmed = ((initialTrimEndMs - c.startTrimMs) / c.speed).toLong()
                                    state.playheadMs = (c.timelineStartMs + currentTrimmed).coerceAtLeast(0L)
                                },
                                onEnd = { state.endGesture() },
                            ),
                        )
                    }
                }

                // 3. Track A1: Audio Tracks (height 26dp, offset 92dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 92.dp)
                        .height(26.dp),
                ) {
                    project.audioTracks.forEach { audio ->
                        val isSelected = project.selectedAudioId == audio.id
                        val audioLeftPx = audio.timelineStartMs * pxPerMs
                        val audioWidthPx = (audio.trimmedDurationMs * pxPerMs).coerceAtLeast(36f)
                        val audioWidthDp = with(density) { audioWidthPx.toDp() }
                        val audioLeftDp = startMarginDp + with(density) { audioLeftPx.toDp() }

                        var initialAudioStartTrim by remember(audio.id) { mutableLongStateOf(0L) }
                        var initialAudioEndTrim by remember(audio.id) { mutableLongStateOf(0L) }

                        Row(
                            modifier = Modifier
                                .offset(x = audioLeftDp)
                                .width(audioWidthDp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF15803D))
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) Color.White else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .pointerInput(audio.id) {
                                    detectTapGestures { state.selectAudio(audio.id) }
                                }
                                .pointerInput(audio.id, pxPerMs) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { state.selectAudio(audio.id) },
                                        // The gesture is over: the next one is a new undo step and starts from where the item is.
                                        onDragEnd = { state.endGesture() },
                                        onDragCancel = { state.endGesture() },
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val deltaMs = (dragAmount / pxPerMs).toLong()
                                        state.moveAudioTimeline(audio.id, deltaMs)
                                    }
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                audio.displayName,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        val audioEndMs = audio.timelineStartMs + audio.trimmedDurationMs
                        val otherAudio = project.audioTracks.filter { it.id != audio.id }
                            .map { it.timelineStartMs to it.timelineStartMs + it.trimmedDurationMs }
                        val currentAudio by rememberUpdatedState(audio)
                        var audioFixedRightMs by remember(audio.id) { mutableLongStateOf(0L) }
                        EdgeHandles(
                            itemKey = audio.id to pxPerMs,
                            leftDp = audioLeftDp,
                            widthDp = audioWidthDp,
                            selected = isSelected,
                            color = accent,
                            innerWidth = 7.dp,
                            startRoom = EdgeRoom(
                                canGrow = audio.startTrimMs > 0L && audio.timelineStartMs > 0L,
                                roomPx = roomBefore(audio.timelineStartMs, otherAudio, pxPerMs),
                            ),
                            endRoom = EdgeRoom(
                                canGrow = audio.endTrimMs < audio.sourceDurationMs,
                                roomPx = roomAfter(audioEndMs, otherAudio, pxPerMs),
                            ),
                            // The left edge trims the start and keeps the end where it is (as video clips do).
                            startDrag = EdgeDrag(
                                onStart = {
                                    val a = currentAudio
                                    state.selectAudio(a.id)
                                    initialAudioStartTrim = a.startTrimMs
                                    audioFixedRightMs = a.timelineStartMs + a.trimmedDurationMs
                                },
                                onDrag = { dragAmount ->
                                    val a = currentAudio
                                    val deltaMs = (dragAmount / pxPerMs).toLong()
                                    // Not past the start of the timeline: the end stays put.
                                    val minStart = (a.endTrimMs - audioFixedRightMs).coerceAtLeast(0L)
                                    val maxStart = (a.endTrimMs - 150L).coerceAtLeast(minStart)
                                    initialAudioStartTrim = (initialAudioStartTrim + deltaMs).coerceIn(minStart, maxStart)
                                    val newTimelineStart = (audioFixedRightMs - (a.endTrimMs - initialAudioStartTrim)).coerceAtLeast(0L)
                                    state.updateAudioDuration(a.id, initialAudioStartTrim, a.endTrimMs, newTimelineStart)
                                },
                                // The gesture is over: the next one is a new undo step and starts from where the item is.
                                onEnd = { state.endGesture() },
                            ),
                            endDrag = EdgeDrag(
                                onStart = {
                                    val a = currentAudio
                                    state.selectAudio(a.id)
                                    initialAudioEndTrim = a.endTrimMs
                                },
                                onDrag = { dragAmount ->
                                    val a = currentAudio
                                    val deltaMs = (dragAmount / pxPerMs).toLong()
                                    val minEnd = (a.startTrimMs + 150L).coerceAtMost(a.sourceDurationMs)
                                    initialAudioEndTrim = (initialAudioEndTrim + deltaMs).coerceIn(minEnd, maxOf(minEnd, a.sourceDurationMs))
                                    state.updateAudioDuration(a.id, a.startTrimMs, initialAudioEndTrim)
                                },
                                onEnd = { state.endGesture() },
                            ),
                        )
                    }
                }

                // 4. Track T1: Text & Subtitles (height 24dp, offset 120dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 120.dp)
                        .height(24.dp),
                ) {
                    project.textOverlays.forEach { textOverlay ->
                        val isSelected = project.selectedTextId == textOverlay.id
                        val durMs = (textOverlay.endTimelineMs - textOverlay.startTimelineMs).coerceAtLeast(300L)
                        val overlayWidthPx = (durMs * pxPerMs).coerceAtLeast(36f)
                        val overlayWidthDp = with(density) { overlayWidthPx.toDp() }
                        val startOffsetPx = textOverlay.startTimelineMs * pxPerMs
                        val startOffsetDp = startMarginDp + with(density) { startOffsetPx.toDp() }

                        Row(
                            modifier = Modifier
                                .offset(x = startOffsetDp)
                                .width(overlayWidthDp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF8B5CF6))
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) accent else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .pointerInput(textOverlay.id) {
                                    detectTapGestures { state.selectText(textOverlay.id) }
                                }
                                .pointerInput(textOverlay.id, pxPerMs) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { state.selectText(textOverlay.id) },
                                        // The gesture is over: the next one is a new undo step and starts from where the item is.
                                        onDragEnd = { state.endGesture() },
                                        onDragCancel = { state.endGesture() },
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val deltaMs = (dragAmount / pxPerMs).toLong()
                                        state.moveTextTimeline(textOverlay.id, deltaMs)
                                    }
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Subtitles, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(
                                textOverlay.text,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        val otherTexts = project.textOverlays.filter { it.id != textOverlay.id }
                            .map { it.startTimelineMs to it.endTimelineMs }
                        EdgeHandles(
                            itemKey = textOverlay.id to pxPerMs,
                            leftDp = startOffsetDp,
                            widthDp = overlayWidthDp,
                            selected = isSelected,
                            color = accent,
                            innerWidth = 7.dp,
                            // A text has no source length: it grows until the start of the timeline.
                            startRoom = EdgeRoom(
                                canGrow = textOverlay.startTimelineMs > 0L,
                                roomPx = roomBefore(textOverlay.startTimelineMs, otherTexts, pxPerMs),
                            ),
                            endRoom = EdgeRoom(
                                canGrow = true,
                                roomPx = roomAfter(textOverlay.endTimelineMs, otherTexts, pxPerMs),
                            ),
                            startDrag = EdgeDrag(
                                onStart = { state.selectText(textOverlay.id) },
                                onDrag = { dragAmount -> state.updateTextStart(textOverlay.id, (dragAmount / pxPerMs).toLong()) },
                                // The gesture is over: the next one is a new undo step and starts from where the item is.
                                onEnd = { state.endGesture() },
                            ),
                            endDrag = EdgeDrag(
                                onStart = { state.selectText(textOverlay.id) },
                                onDrag = { dragAmount -> state.updateTextEnd(textOverlay.id, (dragAmount / pxPerMs).toLong()) },
                                onEnd = { state.endGesture() },
                            ),
                        )
                    }
                }

                // 5. Track S1: Stickers & Images (height 24dp, offset 146dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 146.dp)
                        .height(24.dp),
                ) {
                    project.imageOverlays.forEach { imageOverlay ->
                        val isSelected = project.selectedImageId == imageOverlay.id
                        val durMs = (imageOverlay.endTimelineMs - imageOverlay.startTimelineMs).coerceAtLeast(300L)
                        val overlayWidthPx = (durMs * pxPerMs).coerceAtLeast(36f)
                        val overlayWidthDp = with(density) { overlayWidthPx.toDp() }
                        val startOffsetPx = imageOverlay.startTimelineMs * pxPerMs
                        val startOffsetDp = startMarginDp + with(density) { startOffsetPx.toDp() }

                        Row(
                            modifier = Modifier
                                .offset(x = startOffsetDp)
                                .width(overlayWidthDp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.tertiary)
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) Color.White else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .pointerInput(imageOverlay.id) {
                                    detectTapGestures { state.selectImage(imageOverlay.id) }
                                }
                                .pointerInput(imageOverlay.id, pxPerMs) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { state.selectImage(imageOverlay.id) },
                                        // The gesture is over: the next one is a new undo step and starts from where the item is.
                                        onDragEnd = { state.endGesture() },
                                        onDragCancel = { state.endGesture() },
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val deltaMs = (dragAmount / pxPerMs).toLong()
                                        state.moveImageTimeline(imageOverlay.id, deltaMs)
                                    }
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Image, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "Sticker",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        val otherImages = project.imageOverlays.filter { it.id != imageOverlay.id }
                            .map { it.startTimelineMs to it.endTimelineMs }
                        EdgeHandles(
                            itemKey = imageOverlay.id to pxPerMs,
                            leftDp = startOffsetDp,
                            widthDp = overlayWidthDp,
                            selected = isSelected,
                            color = accent,
                            innerWidth = 7.dp,
                            // A sticker has no source length: it grows until the start of the timeline.
                            startRoom = EdgeRoom(
                                canGrow = imageOverlay.startTimelineMs > 0L,
                                roomPx = roomBefore(imageOverlay.startTimelineMs, otherImages, pxPerMs),
                            ),
                            endRoom = EdgeRoom(
                                canGrow = true,
                                roomPx = roomAfter(imageOverlay.endTimelineMs, otherImages, pxPerMs),
                            ),
                            startDrag = EdgeDrag(
                                onStart = { state.selectImage(imageOverlay.id) },
                                onDrag = { dragAmount -> state.updateImageStart(imageOverlay.id, (dragAmount / pxPerMs).toLong()) },
                                // The gesture is over: the next one is a new undo step and starts from where the item is.
                                onEnd = { state.endGesture() },
                            ),
                            endDrag = EdgeDrag(
                                onStart = { state.selectImage(imageOverlay.id) },
                                onDrag = { dragAmount -> state.updateImageEnd(imageOverlay.id, (dragAmount / pxPerMs).toLong()) },
                                onEnd = { state.endGesture() },
                            ),
                        )
                    }
                }

                // 6. Adobe Playhead (Red / Orange line with triangle thumb)
                val startMarginPx = with(density) { startMarginDp.toPx() }
                val halfThumbPx = with(density) { 20.dp.toPx() }

                Box(
                    modifier = Modifier
                        .offset {
                            val playheadOffsetPx = state.playheadMs * pxPerMs
                            IntOffset((startMarginPx + playheadOffsetPx - halfThumbPx).roundToInt(), 0)
                        }
                        .width(40.dp)
                        .fillMaxHeight()
                        .zIndex(100f)
                        .pointerInput(totalDurationMs, pxPerMs) {
                            detectHorizontalDragGestures(
                                onDragStart = { state.isScrubbing = true },
                                onDragEnd = { state.isScrubbing = false },
                                onDragCancel = { state.isScrubbing = false },
                            ) { change, dragAmount ->
                                change.consume()
                                val deltaMs = (dragAmount / pxPerMs).toLong()
                                state.playheadMs = (state.playheadMs + deltaMs).coerceIn(0L, totalDurationMs)
                            }
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // Vertical red line
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Color(0xFFEF4444)),
                    )

                    // Adobe Inverted Triangle Thumb Marker
                    Canvas(modifier = Modifier.size(16.dp, 12.dp)) {
                        val path = Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, 0f)
                            lineTo(size.width / 2f, size.height)
                            close()
                        }
                        drawPath(path, Color(0xFFEF4444))
                    }
                }
            }
            }
        }
    }
}

/** Its own composable: the time changes every playback frame and only this text redraws. */
@Composable
private fun PlayheadTimecode(state: VideoEditorStateHolder, totalDurationMs: Long) {
    Text(
        text = formatDurationMs(state.playheadMs) + " / " + formatDurationMs(totalDurationMs),
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        ),
        color = MaterialTheme.colorScheme.primary,
    )
}

fun formatDurationMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val millis = (ms % 1000) / 10
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    val remMinutes = minutes % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d.%02d", hours, remMinutes, seconds, millis)
    } else {
        String.format("%02d:%02d.%02d", minutes, seconds, millis)
    }
}

/**
 * Timeline zoom limits. The track is [PX_PER_SECOND] x zoom wide, and Compose can't lay out
 * anything wider than about 262 000 px: a long video at a high zoom crashed the editor. The track
 * now stays under [MAX_TRACK_PX]; a very long timeline may zoom out below [MIN] to fit.
 */
internal object TimelineZoom {
    const val MIN = 0.4f
    const val MAX = 4.0f
    const val PX_PER_SECOND = 50f
    const val MAX_TRACK_PX = 200_000f

    /** The highest zoom [durationMs] of timeline can take. */
    fun maxFor(durationMs: Long): Float =
        minOf(MAX, MAX_TRACK_PX / (PX_PER_SECOND * durationMs.coerceAtLeast(1L) / 1000f))

    fun range(durationMs: Long): ClosedFloatingPointRange<Float> {
        val max = maxFor(durationMs)
        return minOf(MIN, max)..max
    }

    /** [scale] as chosen, within what [durationMs] allows. */
    fun effective(scale: Float, durationMs: Long): Float = scale.coerceIn(range(durationMs))
}
