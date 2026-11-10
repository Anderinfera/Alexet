package dev.ander.alexet.ui.videoeditor

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import dev.ander.alexet.data.videoeditor.VideoClipItem
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.requiredSize
import android.app.Activity
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import dev.ander.alexet.ui.components.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.TextureView
import android.view.View
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import dev.ander.alexet.R
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.ImageEditEngine
import dev.ander.alexet.data.videoeditor.ExportFormat
import dev.ander.alexet.data.videoeditor.VideoExportConfig
import dev.ander.alexet.data.videoeditor.VideoProjectAspectRatio
import dev.ander.alexet.data.videoeditor.VideoExportPlanner
import dev.ander.alexet.data.videoeditor.VideoProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ander.alexet.foldable.rememberFoldableState

@OptIn(UnstableApi::class)
@ExperimentalMaterial3Api
@Composable
fun VideoEditorPane(
    initialMedia: List<GalleryMedia>,
    availableMedia: List<GalleryMedia>,
    isExporting: Boolean,
    exportProgress: Int,
    onExport: (VideoProjectState, VideoExportConfig) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onCancelExport: () -> Unit = {},
    // Folders for the sticker picker (the same window as in the photo editor).
    albums: List<dev.ander.alexet.data.Album> = emptyList(),
    allAlbums: List<dev.ander.alexet.data.Album> = albums,
    hiddenAlbumIds: Set<Long> = emptySet(),
    favoriteMediaIds: Set<Long> = emptySet(),
    onRevealHidden: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = rememberVideoEditorState(initialMedia = initialMedia, context = context)
    val density = LocalDensity.current
    // System (Material You) accent instead of a fixed blue.
    val accent = MaterialTheme.colorScheme.primary

    // Display aspect of each clip with its rotation tag applied. MediaStore sizes ignore that tag,
    // so a portrait recording (landscape frames + 90°) looked landscape and got stretched.
    val displayAspects = remember { mutableStateMapOf<Long, Float>() }
    val clipMediaIds = state.project.clips.map { it.media.id }.distinct()
    LaunchedEffect(clipMediaIds) {
        val missing = state.project.clips.map { it.media }.distinctBy { it.id }.filter { it.id !in displayAspects }
        if (missing.isEmpty()) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            missing.mapNotNull { media -> readDisplayAspect(context, media)?.let { media.id to it } }
        }
        found.forEach { (id, aspect) -> displayAspects[id] = aspect }
        state.previewNaturalAspects = displayAspects.toMap()
    }

    var showExportDialog by remember { mutableStateOf(false) }
    var isCompactMode by remember { mutableStateOf(false) }

    // Leaving the editor takes two presses, like leaving the app (dialogs and sheets close on their own).
    val doubleBack = dev.ander.alexet.ui.components.rememberDoubleBack()
    androidx.activity.compose.BackHandler {
        // Not while exporting: the project would be gone (the export has its own Cancel button).
        if (!isExporting) doubleBack(onClose)
    }

    // Text edit popup (triggered by double-tap on text overlay)

    val scope = rememberCoroutineScope()

    // Standard audio file picker launcher
    // "Add clip": videos from the system picker, appended at the end of the timeline.
    val clipPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { uris.mapNotNull { pickedVideo(context, it) } }
            if (picked.isNotEmpty()) {
                state.addClips(picked, context)
            } else {
                android.widget.Toast.makeText(context, R.string.add_clip_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment ?: "Audio Track"
            scope.launch {
                val dur = withContext(Dispatchers.IO) { getUriDuration(context, uri) }
                state.addAudioTrack(uri, name, dur, isVideoSource = false)
            }
        }
    }

    // Video file as audio extractor picker launcher
    val videoAsAudioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            val name = "Audio (" + (uri.lastPathSegment ?: "Video") + ")"
            scope.launch {
                val dur = withContext(Dispatchers.IO) { getUriDuration(context, uri) }
                state.addAudioTrack(uri, name, dur, isVideoSource = true)
            }
        }
    }

    // Sticker picker: the gallery's folders; a sticker being replaced keeps its place.
    var showStickerPicker by remember { mutableStateOf(false) }
    var replaceStickerId by remember { mutableStateOf<String?>(null) }
    fun onStickerPicked(uri: Uri) {
        val replacing = replaceStickerId
        if (replacing != null) state.replaceImageOverlay(replacing, uri) else state.addImageOverlay(uri)
        replaceStickerId = null
    }

    // "Other folder": the system picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) onStickerPicked(uri) else replaceStickerId = null
    }

    // Internal ExoPlayer for live preview
    val exoPlayer = remember {
        object : androidx.compose.runtime.RememberObserver {
            val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
            }
            private var released = false
            override fun onRemembered() {}
            override fun onForgotten() { doRelease() }
            override fun onAbandoned() { doRelease() }
            private fun doRelease() { if (!released) { released = true; player.release() } }
        }
    }.player
    // Built on first use: most projects have no added audio, and building two players at once
    // made opening the editor stutter.
    val extraAudioPlayer = remember { LazyExtraPlayer(context.applicationContext) }

    DisposableEffect(lifecycleOwner, exoPlayer, extraAudioPlayer) {
        // The preview plays only in the app: once it is left the players give their (hardware)
        // decoders back, and load again where they were on return.
        var stoppedInBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    state.isPlaying = false
                    exoPlayer.pause()
                    extraAudioPlayer.ifCreated?.pause()
                }
                Lifecycle.Event.ON_STOP -> {
                    state.isPlaying = false
                    exoPlayer.stop()
                    extraAudioPlayer.ifCreated?.stop()
                    stoppedInBackground = true
                }
                Lifecycle.Event.ON_START -> if (stoppedInBackground) {
                    stoppedInBackground = false
                    exoPlayer.prepare()
                    state.mapPlayheadToClip(state.playheadMs)?.let { (clip, localOffsetMs) ->
                        val minSeek = minOf(clip.startTrimMs, clip.endTrimMs)
                        val maxSeek = maxOf(clip.startTrimMs, clip.endTrimMs)
                        exoPlayer.setSeekParameters(SeekParameters.EXACT)
                        exoPlayer.seekTo((clip.startTrimMs + (localOffsetMs * clip.speed).toLong()).coerceIn(minSeek, maxSeek))
                    }
                    extraAudioPlayer.ifCreated?.prepare()
                    syncExtraAudioPreview(extraAudioPlayer, state, playing = false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Released once, by their remember holders above (released twice before).

    // A clip the device can't decode (some HDR / AV1 / damaged files) stops the player with an
    // error: say so and stop, instead of a frozen preview with the Pause icon still on.
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                dev.ander.alexet.data.AppLog.w("VideoEditor", "Preview playback failed", error)
                state.isPlaying = false
                android.widget.Toast.makeText(context, R.string.video_preview_error, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Synchronize play/pause state change
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            if (state.playheadMs >= state.project.totalTimelineDurationMs) {
                state.playheadMs = 0L
            }
            val clipInfo = state.mapPlayheadToClip(state.playheadMs)
            if (clipInfo != null) {
                val (clip, localOffsetMs) = clipInfo
                val mediaItem = MediaItem.fromUri(clip.media.uri)
                if (exoPlayer.currentMediaItem?.localConfiguration?.uri != clip.media.uri) {
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                }
                val minSeek = minOf(clip.startTrimMs, clip.endTrimMs)
                val maxSeek = maxOf(clip.startTrimMs, clip.endTrimMs)
                val targetSeek = (clip.startTrimMs + (localOffsetMs * clip.speed).toLong()).coerceIn(minSeek, maxSeek)
                exoPlayer.setSeekParameters(SeekParameters.EXACT)
                exoPlayer.seekTo(targetSeek)
                exoPlayer.playbackParameters = PlaybackParameters(clip.speed)
                exoPlayer.volume = if (clip.isMuted) 0f else clip.volume
                exoPlayer.play()
            } else {
                exoPlayer.pause()
            }
            syncExtraAudioPreview(extraAudioPlayer, state, playing = true)
        } else {
            exoPlayer.pause()
            extraAudioPlayer.ifCreated?.pause()
        }
    }

    // Button jumps while playing: load and seek the clip at the new playhead.
    LaunchedEffect(state.seekGeneration) {
        if (state.seekGeneration == 0 || !state.isPlaying) return@LaunchedEffect
        val clipInfo = state.mapPlayheadToClip(state.playheadMs)
        if (clipInfo == null) {
            exoPlayer.pause()
            return@LaunchedEffect
        }
        val (clip, localOffsetMs) = clipInfo
        if (exoPlayer.currentMediaItem?.localConfiguration?.uri != clip.media.uri) {
            exoPlayer.setMediaItem(MediaItem.fromUri(clip.media.uri))
            exoPlayer.prepare()
        }
        val minSeek = minOf(clip.startTrimMs, clip.endTrimMs)
        val maxSeek = maxOf(clip.startTrimMs, clip.endTrimMs)
        exoPlayer.setSeekParameters(SeekParameters.EXACT)
        exoPlayer.seekTo((clip.startTrimMs + (localOffsetMs * clip.speed).toLong()).coerceIn(minSeek, maxSeek))
        exoPlayer.playbackParameters = PlaybackParameters(clip.speed)
        exoPlayer.volume = if (clip.isMuted) 0f else clip.volume.coerceIn(0f, 1f)
        exoPlayer.play()
        syncExtraAudioPreview(extraAudioPlayer, state, playing = true)
    }

    // Manual Scrubbing / Seeking listener. Read in a flow, not as effect keys: keys are read by the
    // whole screen, which then recomposed on every playback frame.
    LaunchedEffect(state) {
        snapshotFlow { state.playheadMs to state.isScrubbing }.collect {
            if (state.isScrubbing || !state.isPlaying) {
                val clipInfo = state.mapPlayheadToClip(state.playheadMs)
                if (clipInfo != null) {
                    val (clip, localOffsetMs) = clipInfo
                    val mediaItem = MediaItem.fromUri(clip.media.uri)
                    if (exoPlayer.currentMediaItem?.localConfiguration?.uri != clip.media.uri) {
                        exoPlayer.setMediaItem(mediaItem)
                        exoPlayer.prepare()
                    }
                    val minSeek = minOf(clip.startTrimMs, clip.endTrimMs)
                    val maxSeek = maxOf(clip.startTrimMs, clip.endTrimMs)
                    val targetSeek = (clip.startTrimMs + (localOffsetMs * clip.speed).toLong()).coerceIn(minSeek, maxSeek)
                    // While the finger drags: the nearest keyframe, shown at once (an exact seek
                    // decodes from the keyframe on every move). Let go: the exact frame.
                    exoPlayer.setSeekParameters(if (state.isScrubbing) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
                    exoPlayer.seekTo(targetSeek)
                }
                syncExtraAudioPreview(extraAudioPlayer, state, playing = state.isPlaying)
            }
        }
    }

    // Smooth 60FPS Playback loop: advances through timeline gaps as silence/black, then the next clip
    LaunchedEffect(state.isPlaying) {
        var lastTick = android.os.SystemClock.elapsedRealtime()
        while (state.isPlaying && isActive) {
            delay(16) // ~60fps
            val now = android.os.SystemClock.elapsedRealtime()
            val dt = (now - lastTick).coerceIn(0L, 80L)
            lastTick = now
            val currentClipInfo = state.mapPlayheadToClip(state.playheadMs)
            if (currentClipInfo != null) {
                val (clip, _) = currentClipInfo
                val pos = exoPlayer.currentPosition
                val clipEndTimeline = clip.timelineStartMs + clip.trimmedDurationMs
                // ENDED at any position: the decoder's last frame can land a few ms before the
                // trim end (rounded durations) and the loop would otherwise wait there forever.
                if (pos >= clip.endTrimMs - CLIP_END_SLACK_MS || exoPlayer.playbackState == Player.STATE_ENDED) {
                    val nextClip = VideoExportPlanner.nextClipAfter(state.project.clips, clipEndTimeline, excludeId = clip.id)
                    if (nextClip != null && nextClip.timelineStartMs <= clipEndTimeline) {
                        val nextMediaItem = MediaItem.fromUri(nextClip.media.uri)
                        if (exoPlayer.currentMediaItem?.localConfiguration?.uri != nextClip.media.uri) {
                            exoPlayer.setMediaItem(nextMediaItem)
                            exoPlayer.prepare()
                        }
                        exoPlayer.seekTo(nextClip.startTrimMs)
                        exoPlayer.playbackParameters = PlaybackParameters(nextClip.speed)
                        exoPlayer.volume = if (nextClip.isMuted) 0f else nextClip.volume
                        state.playheadMs = nextClip.timelineStartMs
                    } else {
                        exoPlayer.pause()
                        state.playheadMs = clipEndTimeline
                    }
                } else if (pos < clip.startTrimMs) {
                    exoPlayer.seekTo(clip.startTrimMs)
                } else {
                    // Speed and volume can change while playing; apply them to the running clip.
                    if (exoPlayer.playbackParameters.speed != clip.speed) {
                        exoPlayer.playbackParameters = PlaybackParameters(clip.speed)
                    }
                    // The player clamps to 0..1, so compare against the clamped value.
                    val clipVolume = if (clip.isMuted) 0f else clip.volume.coerceIn(0f, 1f)
                    if (exoPlayer.volume != clipVolume) exoPlayer.volume = clipVolume
                    val clipLocalProgress = ((pos - clip.startTrimMs).coerceAtLeast(0L) / clip.speed).toLong()
                    state.playheadMs = (clip.timelineStartMs + clipLocalProgress).coerceAtMost(clipEndTimeline)
                }
            } else {
                exoPlayer.pause()
                state.playheadMs = (state.playheadMs + dt).coerceAtMost(state.project.totalTimelineDurationMs)
                val nextClip = VideoExportPlanner.nextClipAfter(state.project.clips, state.playheadMs)
                if (nextClip != null && state.playheadMs >= nextClip.timelineStartMs) {
                    val nextMediaItem = MediaItem.fromUri(nextClip.media.uri)
                    if (exoPlayer.currentMediaItem?.localConfiguration?.uri != nextClip.media.uri) {
                        exoPlayer.setMediaItem(nextMediaItem)
                        exoPlayer.prepare()
                    }
                    exoPlayer.seekTo(nextClip.startTrimMs)
                    exoPlayer.playbackParameters = PlaybackParameters(nextClip.speed)
                    exoPlayer.volume = if (nextClip.isMuted) 0f else nextClip.volume
                    exoPlayer.play()
                    state.playheadMs = nextClip.timelineStartMs
                } else if (nextClip == null && state.playheadMs >= state.project.totalTimelineDurationMs) {
                    extraAudioPlayer.ifCreated?.pause()
                    state.isPlaying = false
                    state.playheadMs = 0L
                    state.project.clips.firstOrNull()?.let { firstClip ->
                        val firstItem = MediaItem.fromUri(firstClip.media.uri)
                        if (exoPlayer.currentMediaItem?.localConfiguration?.uri != firstClip.media.uri) {
                            exoPlayer.setMediaItem(firstItem)
                            exoPlayer.prepare()
                        }
                        exoPlayer.seekTo(firstClip.startTrimMs)
                    }
                }
            }
            syncExtraAudioPreview(extraAudioPlayer, state, playing = state.isPlaying)
        }
    }

    val foldable = rememberFoldableState()
    val isTabletop = foldable.isTabletop

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF09090B)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Upper Section: Top Bar + Video Preview (above horizontal hinge in Tabletop mode)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    // --- 1. Top Bar (Adobe Studio Dark) with Mathematically Centered Playback ---
                    Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    // Close Button (Left)
                    FilledTonalIconButton(
                        onClick = onClose,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFF18181B),
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_no))
                    }

                    // Top Center: Undo, Playback Controls, Redo Cluster (Exact Screen Center)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color(0xFF18181B).copy(alpha = 0.95f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        // 1. Undo (usuń zmiany)
                        IconButton(
                            onClick = { state.undo() },
                            enabled = state.canUndo,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Undo,
                                contentDescription = stringResource(R.string.undo),
                                tint = if (state.canUndo) Color.White else Color(0xFF52525B),
                                modifier = Modifier.size(17.dp),
                            )
                        }

                        // 2. Previous Clip
                        IconButton(
                            onClick = {
                                val ordered = state.project.clips.sortedBy { it.timelineStartMs }
                                val currentIndex = ordered.indexOfFirst {
                                    state.playheadMs in it.timelineStartMs until (it.timelineStartMs + it.trimmedDurationMs)
                                }
                                val prevClip = ordered.getOrNull((currentIndex - 1).coerceAtLeast(0))
                                state.seekTo(prevClip?.timelineStartMs ?: 0L)
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                        }

                        // 3. -1s Rewind
                        IconButton(
                            onClick = { state.seekTo(state.playheadMs - 1000L) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Rounded.FastRewind, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                        }

                        // 4. Center Play/Pause Button
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(accent)
                                .clickable { state.isPlaying = !state.isPlaying },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        // 5. +1s Forward
                        IconButton(
                            onClick = { state.seekTo(state.playheadMs + 1000L) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Rounded.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                        }

                        // 6. Next Clip
                        IconButton(
                            onClick = {
                                val ordered = state.project.clips.sortedBy { it.timelineStartMs }
                                val currentIndex = ordered.indexOfFirst {
                                    state.playheadMs in it.timelineStartMs until (it.timelineStartMs + it.trimmedDurationMs)
                                }
                                val nextClip = ordered.getOrNull(currentIndex + 1)
                                state.seekTo(nextClip?.timelineStartMs ?: state.project.totalTimelineDurationMs)
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                        }

                        // 7. Redo (powtórz zmiany)
                        IconButton(
                            onClick = { state.redo() },
                            enabled = state.canRedo,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Redo,
                                contentDescription = stringResource(R.string.redo),
                                tint = if (state.canRedo) Color.White else Color(0xFF52525B),
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }

                    // Export Button (Right) - Icon only without text
                    FilledTonalIconButton(
                        onClick = { showExportDialog = true },
                        enabled = !isExporting && state.project.clips.isNotEmpty(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(
                            Icons.Rounded.FileUpload,
                            contentDescription = stringResource(R.string.export),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // --- 2. Center Video Preview Player & Overlays (Auto Fit & Weight 1f) ---
                val activeClip = state.project.selectedClip
                // The clip under the playhead changes a few times per playback, the playhead every
                // frame: only a new clip recomposes the screen.
                val playheadClip by remember(state) { derivedStateOf { state.mapPlayheadToClip(state.playheadMs)?.first } }
                val currentPlayingClip = playheadClip ?: activeClip ?: state.project.clips.firstOrNull()
                val isRotated90or270 = currentPlayingClip?.let { dev.ander.alexet.data.videoeditor.ClipRotation.swapsSides(it.rotation) } ?: false

                // Natural (rotation-tag applied) aspect of a clip's video, before the user's rotation.
                fun naturalAspect(clip: VideoClipItem): Float? =
                    displayAspects[clip.media.id]
                        ?: clip.media.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height }
                // As shown after the user's rotation.
                fun shownAspect(clip: VideoClipItem): Float? = naturalAspect(clip)?.let { a ->
                    if (dev.ander.alexet.data.videoeditor.ClipRotation.swapsSides(clip.rotation)) 1f / a else a
                }
                val clipAspect: Float = currentPlayingClip?.let(::shownAspect)
                    ?: state.project.clips.firstOrNull()?.let(::shownAspect)
                    ?: (16f / 9f)
                // Use the project aspect ratio when explicitly set; fall back to clip's native ratio
                val sourceAspect: Float = state.project.aspectRatio.ratio ?: clipAspect

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val containerWidth = maxWidth
                    val containerHeight = maxHeight
                    val containerAspect = if (containerHeight.value > 0) containerWidth.value / containerHeight.value else 1f
                    val matchHeight = containerAspect > sourceAspect

                    // Video Display Container: Fits video naturally with rounded corners
                    BoxWithConstraints(
                        modifier = Modifier
                            .aspectRatio(sourceAspect, matchHeightConstraintsFirst = matchHeight)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        val dispW = maxWidth
                        val dispH = maxHeight
                        val containerPx = with(LocalDensity.current) { dispW.toPx() to dispH.toPx() }
                        SideEffect { state.previewContainerPx = containerPx }
                        // TextureView stretches frames to its size: give it the video's own shape and
                        // fill the frame with crop, as the export does (LAYOUT_SCALE_TO_FIT_WITH_CROP).
                        val shown = currentPlayingClip?.let(::shownAspect) ?: clipAspect
                        val frameAspect = if (dispH.value > 0f) dispW.value / dispH.value else shown
                        val coverW = if (shown > frameAspect) dispH * shown else dispW
                        val coverH = if (shown > frameAspect) dispH else dispW / shown
                        val coverPx = with(LocalDensity.current) { coverW.toPx() to coverH.toPx() }
                        SideEffect { state.previewCoverPx = coverPx }
                        // The view is rotated by the clip rotation, so its unrotated size is swapped.
                        val boxW = if (isRotated90or270) coverH else coverW
                        val boxH = if (isRotated90or270) coverW else coverH

                        // ExoPlayer View with Live Hardware Color Grading (Adjust) & Pan/Zoom/Rotation
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).apply {
                                    exoPlayer.setVideoTextureView(this)
                                }
                            },
                            onRelease = { textureView ->
                                exoPlayer.clearVideoTextureView(textureView)
                            },
                            update = { textureView ->
                                val currentClip = playheadClip ?: activeClip
                                // Runs on every frame of playback: the layer is set again only when the
                                // colour settings change (a new matrix, paint and layer each frame before).
                                // Neutral values need no layer: an offscreen layer on every 4K frame
                                // only cost GPU time and battery.
                                val adjust = currentClip
                                    ?.takeUnless {
                                        it.brightness == 0f && it.contrast == 0f && it.saturation == 0f && it.gamma == 1f
                                    }
                                    ?.let { listOf(it.brightness, it.contrast, it.saturation, it.gamma) }
                                if (textureView.getTag(R.id.video_preview_adjust) != adjust) {
                                    textureView.setTag(R.id.video_preview_adjust, adjust)
                                    if (currentClip != null && adjust != null) {
                                        val cmArray = ImageEditEngine.createColorMatrixArray(
                                            brightness = currentClip.brightness,
                                            contrast = currentClip.contrast,
                                            saturation = currentClip.saturation,
                                            gamma = currentClip.gamma,
                                        )
                                        val paint = Paint().apply {
                                            colorFilter = ColorMatrixColorFilter(cmArray)
                                        }
                                        textureView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                                    } else {
                                        textureView.setLayerType(View.LAYER_TYPE_NONE, null)
                                    }
                                }
                            },
                            modifier = Modifier
                                .requiredSize(boxW, boxH)
                                .graphicsLayer {
                                    val currentClip = playheadClip ?: activeClip
                                    if (currentClip != null) {
                                        translationX = currentClip.panX
                                        translationY = currentClip.panY
                                        scaleX = if (currentClip.flipH) -currentClip.zoomScale else currentClip.zoomScale
                                        scaleY = if (currentClip.flipV) -currentClip.zoomScale else currentClip.zoomScale
                                        rotationZ = currentClip.rotation.toFloat()
                                    }
                                },
                        )

                        // Interactive Text, Sticker Overlays & Phantom Aspect Frame with Ghost Scrim
                        VideoOverlayCanvas(
                            state = state,
                            onCanvasClick = { isCompactMode = !isCompactMode },
                            // The same panel as when the text was made, with its values.
                            onTextDoubleTap = { textId ->
                                if (state.project.textOverlays.any { it.id == textId }) {
                                    state.textPanel = TextPanelRequest(textId)
                                }
                            },
                            // A sticker's menu: its tab (turn, size, change image, delete).
                            onImageDoubleTap = { imageId ->
                                state.selectImage(imageId)
                                state.currentTab = VideoEditorTab.STICKER
                                isCompactMode = false
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                }

                // --- 3. Bottom Area: Timeline & Tools Panel (in Tabletop mode takes weight(1f) as editing console) ---
                Column(
                    modifier = if (isTabletop) {
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    },
                    verticalArrangement = if (isTabletop) Arrangement.Center else Arrangement.Top,
                ) {
                    // Multi-track Timeline (Always visible!)
                    VideoTimeline(
                        state = state,
                        onAddClipClick = {
                            isCompactMode = false
                            clipPickerLauncher.launch("video/*")
                        },
                        onAddAudioClick = {
                            isCompactMode = false
                            audioPickerLauncher.launch("audio/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Controls & Tool Tabs (Collapses in compact mode)
                    AnimatedVisibility(
                        visible = !isCompactMode,
                    ) {
                        VideoEditorControls(
                            state = state,
                            onAddAudioClick = { audioPickerLauncher.launch("audio/*") },
                            onAddVideoAsAudioClick = { videoAsAudioPickerLauncher.launch("video/*") },
                            onAddStickerClick = {
                                replaceStickerId = null
                                showStickerPicker = true
                            },
                            onReplaceStickerClick = { id ->
                                replaceStickerId = id
                                showStickerPicker = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // --- Sticker picker (the gallery's folders, as in the photo editor) ---
            if (showStickerPicker) {
                dev.ander.alexet.ui.editor.StickerPickerDialog(
                    albums = albums,
                    allAlbums = allAlbums,
                    hiddenAlbumIds = hiddenAlbumIds,
                    favoriteMediaIds = favoriteMediaIds,
                    availableMedia = availableMedia,
                    onRevealHidden = onRevealHidden,
                    onMediaPicked = { picked ->
                        showStickerPicker = false
                        onRevealHidden(false)
                        onStickerPicked(picked.uri)
                    },
                    onOtherFolder = {
                        showStickerPicker = false
                        onRevealHidden(false)
                        imagePickerLauncher.launch("image/*")
                    },
                    onDismiss = {
                        showStickerPicker = false
                        replaceStickerId = null
                        onRevealHidden(false)
                    },
                )
            }

            // --- Export Dialog ---
            if (showExportDialog) {
                VideoExportDialog(
                    initialConfig = VideoExportConfig(aspectRatio = state.project.aspectRatio),
                    // For the size estimate: the frame the export will make.
                    project = remember { state.projectForExport(density.density, density.fontScale) },
                    onConfirm = { cfg ->
                        showExportDialog = false
                        // With the preview geometry: pan and overlay sizes are kept in preview units.
                        onExport(state.projectForExport(density.density, density.fontScale), cfg)
                    },
                    onDismiss = { showExportDialog = false },
                )
            }

            // --- Exporting Progress Overlay ---
            if (isExporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .zIndex(100f)
                        // Swallows touches: editing the project (or closing it) during the export.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF18181B),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier.padding(24.dp).fillMaxWidth(0.85f),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.rendering_video, exportProgress),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                Text(
                                    text = "$exportProgress%",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = accent,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            LinearProgressIndicator(
                                progress = { exportProgress / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                strokeCap = StrokeCap.Round,
                                color = accent,
                                trackColor = Color(0xFF27272A),
                            )
                            androidx.compose.material3.TextButton(
                                onClick = onCancelExport,
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(stringResource(R.string.cancel), color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // --- Text panel (the photo editor's): a new text, or one double-tapped / edited ---
        val panelRequest = state.textPanel
        if (panelRequest != null) {
            val editing = panelRequest.textId?.let { id -> state.project.textOverlays.find { it.id == id } }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(50f)
                    .background(Color.Black.copy(alpha = 0.45f))
                    // A tap beside the panel closes it, as Cancel does.
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        onClick = { state.textPanel = null },
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // Its own key: a panel for another text starts from that text's values.
                androidx.compose.runtime.key(panelRequest) {
                    dev.ander.alexet.ui.editor.TextEditExpandedPanel(
                        initialText = editing?.text.orEmpty(),
                        initialAlignment = editing?.alignment ?: 1,
                        initialBold = editing?.isBold ?: true,
                        initialItalic = editing?.isItalic ?: false,
                        initialUnderline = editing?.isUnderline ?: false,
                        initialStrikethrough = editing?.isStrikethrough ?: false,
                        initialFontSize = editing?.fontSize ?: 22f,
                        initialColor = (editing?.textColor ?: 0xFFFFFFFF).toInt(),
                        onConfirm = { text, align, bold, italic, underline, strike, size, color ->
                            val style = TextStyleChoice(text, align, bold, italic, underline, strike, size, color.toLong() and 0xFFFFFFFFL)
                            if (editing != null) state.restyleText(editing.id, style) else state.addStyledText(style)
                            state.textPanel = null
                        },
                        onCancel = { state.textPanel = null },
                        modifier = Modifier
                            .imePadding()
                            .navigationBarsPadding()
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                onClick = {},
                            ),
                    )
                }
            }
        }
    }
}

/** A picked video as a clip source; null when it can't be read (no duration). */
private fun pickedVideo(context: Context, uri: Uri): GalleryMedia? {
    val resolver = context.contentResolver
    val duration = getUriDuration(context, uri)
    if (duration <= 0L) return null
    val mime = runCatching { resolver.getType(uri) }.getOrNull()?.takeIf { it.startsWith("video/") } ?: "video/mp4"
    val name = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "video"
    return GalleryMedia(
        id = dev.ander.alexet.data.NomediaScanner.syntheticId("clip:$uri"),
        uri = uri,
        displayName = name,
        mimeType = mime,
        dateTakenMs = 0L,
        dateModifiedMs = 0L,
        dateAddedMs = 0L,
        sizeBytes = 0L,
        width = 0,
        height = 0,
        durationMs = duration,
        bucketId = dev.ander.alexet.data.AlbumIds.NONE,
        bucketName = "",
        relativePath = "",
        isHidden = false,
        absolutePath = null,
    )
}

private fun getUriDuration(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        dev.ander.alexet.data.Mp4MetaRepair.setDataSource(retriever, context, null, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    } finally {
        runCatching { retriever.release() }
    }
}

private fun syncExtraAudioPreview(
    extraPlayer: LazyExtraPlayer,
    state: VideoEditorStateHolder,
    playing: Boolean,
) {
    val t = state.playheadMs
    val active = state.project.audioTracks.firstOrNull { track ->
        !track.isMuted && track.volume > 0.001f &&
            t >= track.timelineStartMs && t < track.timelineStartMs + track.trimmedDurationMs
    }
    if (!playing || active == null) {
        extraPlayer.ifCreated?.let {
            it.pause()
            it.volume = 0f
        }
        return
    }
    val extraAudioPlayer = extraPlayer.get() ?: return
    val localMs = (active.startTrimMs + (t - active.timelineStartMs)).coerceIn(
        minOf(active.startTrimMs, active.endTrimMs),
        maxOf(active.startTrimMs, active.endTrimMs),
    )
    if (extraAudioPlayer.currentMediaItem?.localConfiguration?.uri != active.uri) {
        extraAudioPlayer.setMediaItem(MediaItem.fromUri(active.uri))
        extraAudioPlayer.prepare()
    }
    extraAudioPlayer.volume = active.volume.coerceIn(0f, 2f)
    val drift = kotlin.math.abs(extraAudioPlayer.currentPosition - localMs)
    if (drift > 250L || extraAudioPlayer.playbackState == Player.STATE_IDLE) {
        extraAudioPlayer.seekTo(localMs)
    }
    if (!extraAudioPlayer.isPlaying) {
        extraAudioPlayer.play()
    }
}

/** The added-audio preview player, made on first use and released with the editor. */
internal class LazyExtraPlayer(private val context: Context) : androidx.compose.runtime.RememberObserver {
    private var created: ExoPlayer? = null
    private var released = false

    val ifCreated: ExoPlayer? get() = created

    /** Null once the editor is gone. */
    fun get(): ExoPlayer? {
        if (released) return null
        return created ?: ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }.also { created = it }
    }

    override fun onRemembered() {}
    override fun onForgotten() = release()
    override fun onAbandoned() = release()

    private fun release() {
        if (released) return
        released = true
        created?.release()
        created = null
    }
}

/** Width / height of the video as displayed (rotation tag applied); null when unreadable. */
private fun readDisplayAspect(context: Context, media: GalleryMedia): Float? {
    val retriever = MediaMetadataRetriever()
    return try {
        dev.ander.alexet.data.Mp4MetaRepair.setDataSource(retriever, context, media.absolutePath, media.uri)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: return null
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: return null
        if (w <= 0f || h <= 0f) return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) h / w else w / h
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** A clip whose position is this close to its trim end is treated as finished. */
private const val CLIP_END_SLACK_MS = 40L
