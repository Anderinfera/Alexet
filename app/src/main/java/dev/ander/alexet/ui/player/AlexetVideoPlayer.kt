package dev.ander.alexet.ui.player

import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.aspectRatio
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import dev.ander.alexet.R
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import dev.ander.alexet.ui.components.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import kotlinx.coroutines.sync.withLock
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.integration.rememberAnderLineConfig
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.isActive

@OptIn(UnstableApi::class)
@Composable
fun AlexetVideoPlayer(
    media: GalleryMedia,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onBack: () -> Unit,
    externalPlayerAvailable: Boolean,
    onPlayElsewhere: () -> Unit,
    onShare: () -> Unit,
    onDetails: () -> Unit,
    onEdit: (() -> Unit)? = {},
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    playerConfig: dev.ander.alexet.integration.AnderLineConfig? = null,
    /** Shown with the controls, just under the title / under the seek bar. */
    topArrow: (@Composable () -> Unit)? = null,
    bottomArrow: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val playerState = rememberVideoPlayerState(media.id)
    val fallbackConfig = rememberAnderLineConfig()
    // Audio: no picture, so no brightness swipe (that side changes the volume too).
    val isAudio = media.isAudio
    val config = (playerConfig ?: fallbackConfig).let { if (isAudio) it.copy(enableBrightnessSwipeGesture = false) else it }

    // Where playback was when the app went to the background: a killed process starts there again.
    var savedPositionMs by androidx.compose.runtime.saveable.rememberSaveable(media.id) { androidx.compose.runtime.mutableLongStateOf(0L) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showScaleSheet by remember { mutableStateOf(false) }
    var isLooping by androidx.compose.runtime.saveable.rememberSaveable(media.id) { mutableStateOf(false) }
    var playPauseFlash by remember { mutableStateOf<PlayPauseFlash?>(null) }
    var playPauseFlashKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    val isEncryptedVideo = remember(media.absolutePath) {
        media.absolutePath?.endsWith(dev.ander.alexet.data.SecureFolderManager.ENC_EXTENSION) == true
    }

    // The small window over other apps: the video alone, opened with the player's button.
    val activity = remember(context) { context.findActivity() }
    val inPip by PictureInPicture.active.collectAsState()
    val pipSupported = remember(context) { PictureInPicture.isSupported(context) }
    val pipButtonShown = config.pipButton && pipSupported && !isAudio && activity != null
    // Set while the window opens: the pause that comes with it must not stop the video.
    val pipOpening = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // Sound after the app is left. Never for private videos: their folder locks with the app.
    val backgroundAllowed by androidx.compose.runtime.rememberUpdatedState(config.autoBackgroundPlay && !isEncryptedVideo)

    // The private folder of this video is open. A lock (app left, folder left) turns it off: the
    // player then drops the video, and its decrypted bytes are wiped, instead of resuming
    // without the password when the app comes back.
    val unlockedPaths by dev.ander.alexet.data.SecureFolderManager.unlockedFolderPaths.collectAsState()
    val folderUnlocked = remember(unlockedPaths, media.absolutePath) {
        val path = media.absolutePath.orEmpty()
        isEncryptedVideo && unlockedPaths.any { dir -> path.startsWith(dir.trimEnd('/') + "/") }
    }

    val exoPlayer = remember(media.id, media.uri) {
        object : androidx.compose.runtime.RememberObserver {
            // Application context: the player may live on in its own threads after the screen.
            val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
                // Audio focus: other sound pauses while it plays, a call pauses it; unplugged
                // headphones pause it (it may play on in the background).
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(
                            if (media.isAudio) androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC
                            else androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE,
                        )
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build().apply {
                val filePath = media.absolutePath
                val file = if (filePath != null) java.io.File(filePath) else null
                val isEnc = file != null && file.name.endsWith(dev.ander.alexet.data.SecureFolderManager.ENC_EXTENSION)
                if (!isEnc) {
                    // The title shows in the media notification.
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(media.uri)
                            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(media.displayName).build())
                            .build(),
                    )
                    prepare()
                }
                // User Requirement: Video does not autoplay immediately
                playWhenReady = false
            }
            private var released = false
            override fun onRemembered() {}
            override fun onForgotten() { doRelease() }
            override fun onAbandoned() { doRelease() }
            private fun doRelease() {
                if (!released) {
                    released = true
                    dev.ander.alexet.integration.BackgroundPlayback.stop(context, player)
                    player.clearMediaItems()
                    player.release()
                }
            }
        }
    }.player

    // A speed or loop restored after a process restart is applied to the new player.
    LaunchedEffect(exoPlayer) {
        if (playerState.playbackSpeed != 1f) exoPlayer.playbackParameters = PlaybackParameters(playerState.playbackSpeed)
        if (isLooping) exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
    }

    LaunchedEffect(media.id, media.uri, exoPlayer, folderUnlocked) {
        if (!isEncryptedVideo && savedPositionMs > 0L) exoPlayer.seekTo(savedPositionMs)
        if (isEncryptedVideo) {
            val file = media.absolutePath?.let { java.io.File(it) } ?: return@LaunchedEffect
            // Looks the folder up on disk (canonical paths): not on the main thread.
            val key = if (folderUnlocked) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    dev.ander.alexet.data.SecureFolderManager.getActiveKeyForFile(file)
                }
            } else {
                null
            }
            if (key == null) {
                // Locked: nothing of the video stays loaded (a stream would go on decrypting).
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                return@LaunchedEffect
            }
            val origName = file.name.removeSuffix(dev.ander.alexet.data.SecureFolderManager.ENC_EXTENSION)
            val mime = dev.ander.alexet.data.NomediaScanner.mimeOf(java.io.File(origName)) ?: "video/mp4"
            val mediaItem = MediaItem.Builder()
                .setUri(media.uri)
                .setMimeType(mime)
                .build()
            val streamable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                EncryptedVideoDataSource.canStream(file, key)
            }
            if (streamable) {
                // Decrypted chunk by chunk while playing: nothing large is held in memory.
                val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory {
                    EncryptedVideoDataSource(file, key)
                }.createMediaSource(mediaItem)
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                if (savedPositionMs > 0L) exoPlayer.seekTo(savedPositionMs)
                return@LaunchedEffect
            }
            // Older (version 1) files can only be decrypted whole: one at a time, and only if they
            // fit, or swiping through several large videos ran out of memory.
            val plainSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.ander.alexet.data.SecureFolderManager.plaintextSize(file)
            }
            if (plainSize > LEGACY_DECRYPT_LIMIT_BYTES) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.secure_video_ram_limit_error),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return@LaunchedEffect
            }
            // Private copy owned by this player; kept out of the RAM cache so it cannot be wiped mid-playback
            val bytes = legacyDecryptLock.withLock {
                var decrypted: ByteArray? = null
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        dev.ander.alexet.data.SecureRamCache.getOrDecrypt(file, key, cache = false).also { decrypted = it }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Swiped away while decrypting: the finished copy is nobody's, wipe it now.
                    decrypted?.let { java.util.Arrays.fill(it, 0.toByte()) }
                    throw e
                }
            }
            if (bytes != null) {
                val dataSourceFactory = androidx.media3.datasource.DataSource.Factory {
                    androidx.media3.datasource.ByteArrayDataSource(bytes)
                }
                val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                if (savedPositionMs > 0L) exoPlayer.seekTo(savedPositionMs)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    // Locked (the effect restarts) or disposed: the player lets go of the bytes
                    // first, then the decrypted video is wiped from RAM.
                    runCatching {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                    }
                    java.util.Arrays.fill(bytes, 0.toByte())
                }
            } else {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.secure_video_ram_limit_error),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // Attach Lifecycle observer
    DisposableEffect(lifecycleOwner, exoPlayer) {
        // Once the app is left the player gives its (hardware) decoder back, and on return it
        // loads again where it was, paused. Two exceptions: the small window plays on, and with
        // background playback a playing video goes on as sound only, with the media notification.
        var stoppedInBackground = false
        var playingInBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    savedPositionMs = exoPlayer.currentPosition
                    val toPip = pipOpening.getAndSet(false) || activity?.isInPictureInPictureMode == true
                    when {
                        toPip -> Unit
                        backgroundAllowed && exoPlayer.isPlaying &&
                            dev.ander.alexet.integration.BackgroundPlayback.start(context, exoPlayer) -> playingInBackground = true
                        else -> exoPlayer.pause()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    savedPositionMs = exoPlayer.currentPosition
                    if (playingInBackground) {
                        // Nobody sees the picture: it is not decoded (battery), only the sound.
                        exoPlayer.setVideoTrackDisabled(true)
                    } else {
                        exoPlayer.stop()
                        stoppedInBackground = true
                    }
                }
                Lifecycle.Event.ON_START -> if (playingInBackground) {
                    exoPlayer.setVideoTrackDisabled(false)
                } else if (stoppedInBackground) {
                    stoppedInBackground = false
                    // Nothing to load when the video was dropped meanwhile (its folder locked).
                    if (exoPlayer.mediaItemCount > 0) {
                        exoPlayer.prepare()
                        exoPlayer.seekTo(savedPositionMs)
                    }
                }
                Lifecycle.Event.ON_RESUME -> if (playingInBackground) {
                    // Back in the app: the notification goes, the video plays on here.
                    playingInBackground = false
                    dev.ander.alexet.integration.BackgroundPlayback.stop(context, exoPlayer)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (playingInBackground) {
                exoPlayer.setVideoTrackDisabled(false)
                dev.ander.alexet.integration.BackgroundPlayback.stop(context, exoPlayer)
            }
        }
    }

    // The small window's play / pause button.
    if (inPip) {
        DisposableEffect(exoPlayer) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, intent: android.content.Intent) {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    } else {
                        if (playerState.isEnded) exoPlayer.seekTo(0L)
                        exoPlayer.play()
                    }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                receiver,
                android.content.IntentFilter(PictureInPicture.ACTION_PLAY_PAUSE),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose { context.unregisterReceiver(receiver) }
        }
    }

    // Attach ExoPlayer listener and handle release
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerState.isPlaying = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                playerState.isBuffering = playbackState == Player.STATE_BUFFERING
                playerState.isEnded = playbackState == Player.STATE_ENDED
                val dur = exoPlayer.duration
                if (dur > 0L) {
                    playerState.durationMs = dur
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                playerState.positionMs = newPosition.positionMs
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            // Released once, by the remembered holder (it also clears the media items first).
            if (playerState.windowBrightnessOverridden) {
                restoreWindowBrightness(context)
                playerState.windowBrightnessOverridden = false
                playerState.gestureBrightness = -1f
            }
        }
    }

    // SponsorBlock segments of this video, read from its chapters.
    var sponsorSegments by remember(exoPlayer) { mutableStateOf(emptyList<SponsorSegment>()) }
    val autoSkipCategories by androidx.compose.runtime.rememberUpdatedState(
        if (config.sponsorBlockEnabled) config.sponsorBlockAutoSkip else emptySet(),
    )
    val sponsorBlockSkipper = remember(exoPlayer) {
        SponsorBlockSkipper(
            player = exoPlayer,
            autoSkip = { autoSkipCategories },
            onSegmentsChanged = { sponsorSegments = it },
            onSkipped = { category ->
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.sponsorblock_skipped, context.getString(category.nameRes)),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }
    DisposableEffect(exoPlayer) {
        exoPlayer.addListener(sponsorBlockSkipper)
        onDispose { exoPlayer.removeListener(sponsorBlockSkipper) }
    }
    val shownSegments = if (config.sponsorBlockEnabled) sponsorSegments else emptyList()
    val currentSegment by remember(shownSegments, playerState) {
        androidx.compose.runtime.derivedStateOf {
            shownSegments.skippableSegmentAt(playerState.positionMs, playerState.durationMs)
        }
    }

    // Periodic progress ticker
    LaunchedEffect(exoPlayer, playerState.isPlaying) {
        while (isActive && playerState.isPlaying) {
            playerState.positionMs = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            if (dur > 0L) playerState.durationMs = dur
            sponsorBlockSkipper.skipSegmentReached()
            delay(200L)
        }
    }

    // Auto-hide controls when playing
    val autoHideDelayMs = (config.controllerAutoHideTimeout * 1000L).coerceAtLeast(2000L)
    LaunchedEffect(controlsVisible, playerState.isPlaying, playerState.controlsLocked) {
        if (controlsVisible && playerState.isPlaying && !playerState.controlsLocked) {
            delay(autoHideDelayMs)
            onToggleControls()
        }
    }

    // Video size for the "original" (pixel for pixel) scale mode.
    var videoPixels by remember(exoPlayer) { mutableStateOf<androidx.media3.common.VideoSize?>(null) }
    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                videoPixels = videoSize.takeIf { it.width > 0 && it.height > 0 }
            }
        }
        exoPlayer.addListener(listener)
        videoPixels = exoPlayer.videoSize.takeIf { it.width > 0 && it.height > 0 }
        onDispose { exoPlayer.removeListener(listener) }
    }
    // (audio tracks, subtitle tracks) of the current video, for the track shortcuts.
    var trackCounts by remember(exoPlayer) { mutableStateOf(0 to 0) }
    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // Counted like cycleTrack picks them (playable only), or the button could do nothing.
                trackCounts = tracks.groups.count { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSupported } to
                    tracks.groups.count { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSupported }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }
    // The video's shape as shown (the player turns rotated videos itself).
    val pipWidth = videoPixels?.let { v -> (v.width * v.pixelWidthHeightRatio).toInt() } ?: 0
    val pipHeight = videoPixels?.height ?: 0
    if (pipButtonShown || inPip) {
        LaunchedEffect(inPip, playerState.isPlaying, pipWidth, pipHeight) {
            // Ready before the window opens, and kept up to date while it is shown.
            activity?.setPictureInPictureParams(PictureInPicture.params(context, pipWidth, pipHeight, playerState.isPlaying))
        }
    }
    val enterPip: (() -> Unit)? = if (pipButtonShown) {
        {
            pipOpening.set(true)
            val opened = PictureInPicture.enter(activity, PictureInPicture.params(context, pipWidth, pipHeight, playerState.isPlaying))
            if (!opened) pipOpening.set(false)
        }
    } else {
        null
    }
    val scale = playerState.contentScale
    val density = androidx.compose.ui.platform.LocalDensity.current
    val surfaceModifier = when {
        scale.forcedAspect != null -> Modifier.aspectRatio(scale.forcedAspect)
        scale.pixelExact && videoPixels != null -> with(density) {
            val v = videoPixels!!
            val (w, h) = if (v.unappliedRotationDegrees == 90 || v.unappliedRotationDegrees == 270) v.height to v.width else v.width to v.height
            Modifier.requiredSize((w * v.pixelWidthHeightRatio).toDp(), h.toDp())
        }
        else -> Modifier.fillMaxSize()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (isAudio) {
            AudioVisual(
                media = media,
                isPlaying = playerState.isPlaying,
                isBuffering = playerState.isBuffering,
                isEnded = playerState.isEnded,
                speed = playerState.playbackSpeed,
                onReplay = {
                    exoPlayer.seekTo(0L)
                    exoPlayer.play()
                },
                modifier = Modifier.fillMaxSize(),
            )
            // The screen stays on while it plays (screen off would pause it, as for videos).
            val view = androidx.compose.ui.platform.LocalView.current
            DisposableEffect(view, playerState.isPlaying) {
                view.keepScreenOn = playerState.isPlaying
                onDispose { view.keepScreenOn = false }
            }
        } else
        // Player Surface View
        AndroidView(
            factory = { viewContext ->
                PlayerView(
                    android.view.ContextThemeWrapper(
                        viewContext,
                        androidx.appcompat.R.style.Theme_AppCompat_NoActionBar,
                    ),
                ).apply {
                    useController = false
                    resizeMode = playerState.contentScale.resizeMode
                    player = exoPlayer
                }
            },
            onRelease = { view ->
                view.player = null
            },
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = playerState.contentScale.resizeMode
                view.keepScreenOn = playerState.isPlaying
            },
            modifier = surfaceModifier,
        )

        if (!inPip) {
            // Gesture detector layer
            VideoPlayerGestureDetector(
                playerState = playerState,
                controlsVisible = controlsVisible,
                onToggleControls = onToggleControls,
                config = config,
                onSeekRelative = { deltaMs ->
                    val dur = exoPlayer.duration
                    val maxPos = if (dur > 0L) dur else Long.MAX_VALUE
                    val newPos = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, maxPos)
                    exoPlayer.seekTo(newPos)
                    playerState.positionMs = newPos
                },
                onSetPlaybackSpeedTemporary = { tempSpeed ->
                    val targetSpeed = tempSpeed ?: playerState.playbackSpeed
                    exoPlayer.playbackParameters = PlaybackParameters(targetSpeed)
                },
                onTogglePlayPause = {
                    val nowPlaying = !playerState.isPlaying
                    if (playerState.isPlaying) {
                        exoPlayer.pause()
                    } else {
                        if (playerState.isEnded) exoPlayer.seekTo(0L)
                        exoPlayer.play()
                    }
                    playPauseFlash = if (nowPlaying) PlayPauseFlash.PLAY else PlayPauseFlash.PAUSE
                    playPauseFlashKey++
                },
                modifier = Modifier.fillMaxSize(),
            )

            // A double tap in the middle: the new state flashes in the centre.
            PlayPauseFlashIcon(
                flash = playPauseFlash,
                key = playPauseFlashKey,
                modifier = Modifier.align(Alignment.Center),
            )

            // Visual Gesture Feedback Indicators
            playerState.activeDoubleTap?.let { feedback ->
                DoubleTapSeekBubble(
                    feedback = feedback,
                    modifier = Modifier
                        .align(if (feedback.side == SeekSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 48.dp),
                )
            }

            playerState.activeVerticalGesture?.let { feedback ->
                VerticalProgressIndicatorView(
                    feedback = feedback,
                    modifier = Modifier
                        .align(if (feedback.type == VerticalGestureType.BRIGHTNESS) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 32.dp),
                )
            }

            if (playerState.isLongPressSpeedActive) {
                LongPressSpeedPill(
                    speed = config.longPressControlsSpeed,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 16.dp),
                )
            }

            // Full UI Controls Layer
            AnimatedVisibility(
                visible = controlsVisible && !playerState.controlsLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                // No dimming layer over the picture while the controls show.
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    VideoPlayerTopControls(
                        title = media.displayName,
                        config = config,
                        contentScale = playerState.contentScale,
                        playbackSpeed = playerState.playbackSpeed,
                        isLooping = isLooping,
                        onBack = onBack,
                        onOpenSpeedSheet = { showSpeedSheet = true },
                        onOpenScaleSheet = if (isAudio) null else ({ showScaleSheet = true }),
                        onToggleLoop = {
                            isLooping = !isLooping
                            exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                        },
                        onPlayElsewhere = if (isAudio || !externalPlayerAvailable) null else onPlayElsewhere,
                        onShare = onShare,
                        onDetails = onDetails,
                        onEdit = onEdit,
                        onCycleAudioTrack = if (trackCounts.first > 1) ({ cycleTrack(exoPlayer, androidx.media3.common.C.TRACK_TYPE_AUDIO, allowOff = false) }) else null,
                        onCycleSubtitles = if (trackCounts.second > 0) ({ cycleTrack(exoPlayer, androidx.media3.common.C.TRACK_TYPE_TEXT, allowOff = true) }) else null,
                        onEnterPip = enterPip,
                        belowTitle = topArrow,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    VideoPlayerCenterControls(
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        onPlayPause = {
                            if (playerState.isPlaying) {
                                exoPlayer.pause()
                            } else {
                                if (playerState.isEnded) {
                                    exoPlayer.seekTo(0L)
                                }
                                exoPlayer.play()
                            }
                        },
                        onPrevious = onPrevious,
                        onNext = onNext,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    VideoPlayerBottomControls(
                        playerState = playerState,
                        config = config,
                        onSeek = { targetMs ->
                            exoPlayer.seekTo(targetMs)
                            playerState.positionMs = targetMs
                        },
                        onToggleLock = {
                            playerState.controlsLocked = !playerState.controlsLocked
                        },
                        onOpenScaleSheet = if (isAudio) null else ({ showScaleSheet = true }),
                        sponsorSegments = shownSegments,
                        actionCenter = bottomArrow,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            // Playback that reaches the end stops the ticker, so the position alone doesn't show it.
            currentSegment?.takeIf { !playerState.controlsLocked && !playerState.isEnded }?.let { segment ->
                SponsorSkipButton(
                    segment = segment,
                    controlsVisible = controlsVisible,
                    onSkip = { exoPlayer.seekTo(segment.endMs.coerceAtMost(exoPlayer.duration)) },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }

            // Locked controls single button
            if (controlsVisible && playerState.controlsLocked) {
                FilledTonalIconButton(
                    onClick = { playerState.controlsLocked = false },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                    )
                }
            }
        }
    }

    // Modal Overlays
    if (showSpeedSheet && !inPip) {
        PlaybackSpeedSheet(
            currentSpeed = playerState.playbackSpeed,
            onSelectSpeed = { newSpeed ->
                playerState.playbackSpeed = newSpeed
                exoPlayer.playbackParameters = PlaybackParameters(newSpeed)
            },
            onDismiss = { showSpeedSheet = false },
        )
    }

    if (showScaleSheet && !inPip) {
        VideoScaleSheet(
            currentScale = playerState.contentScale,
            onSelectScale = { newScale ->
                playerState.contentScale = newScale
            },
            onDismiss = { showScaleSheet = false },
        )
    }
}

private enum class PlayPauseFlash { PLAY, PAUSE }

/** The play or pause sign, shown big for a moment and faded out (each flash restarts it). */
@Composable
private fun PlayPauseFlashIcon(flash: PlayPauseFlash?, key: Int, modifier: Modifier = Modifier) {
    if (flash == null) return
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val scale = remember { androidx.compose.animation.core.Animatable(0.8f) }
    LaunchedEffect(key) {
        alpha.snapTo(0.9f)
        scale.snapTo(0.8f)
        kotlinx.coroutines.coroutineScope {
            launch { scale.animateTo(1.15f, androidx.compose.animation.core.tween(550)) }
            launch {
                delay(220L)
                alpha.animateTo(0f, androidx.compose.animation.core.tween(330))
            }
        }
    }
    Box(
        modifier = modifier
            .size(84.dp)
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
            .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (flash == PlayPauseFlash.PLAY) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(48.dp),
        )
    }
}

private fun restoreWindowBrightness(context: Context) {
    val activity = context.findActivity() ?: return
    val window = activity.window
    val lp = window.attributes
    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    window.attributes = lp
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Selects the next track group of [type] (after the last one: off, when [allowOff]).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun cycleTrack(player: androidx.media3.common.Player, type: Int, allowOff: Boolean) {
    val groups = player.currentTracks.groups.filter { it.type == type && it.isSupported }
    if (groups.isEmpty()) return
    val current = groups.indexOfFirst { it.isSelected }
    val next = current + 1
    val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
    if (next >= groups.size && allowOff) {
        builder.setTrackTypeDisabled(type, true)
    } else {
        val group = groups[next % groups.size]
        builder.setTrackTypeDisabled(type, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0))
    }
    player.trackSelectionParameters = builder.build()
}

/** The picture is not decoded (sound only) while [disabled]. */
private fun Player.setVideoTrackDisabled(disabled: Boolean) {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, disabled)
        .build()
}

/** Largest version 1 private video decrypted into memory for playback. */
private const val LEGACY_DECRYPT_LIMIT_BYTES = 256L * 1024L * 1024L

private val legacyDecryptLock = kotlinx.coroutines.sync.Mutex()
