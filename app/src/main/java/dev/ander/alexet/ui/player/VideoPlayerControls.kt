package dev.ander.alexet.ui.player

import dev.ander.alexet.ui.components.AnimatedVisibility
import dev.ander.alexet.ui.components.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R
import dev.ander.alexet.integration.AnderLineConfig
import dev.ander.alexet.integration.PlayerShortcut

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoPlayerTopControls(
    title: String,
    config: AnderLineConfig,
    contentScale: VideoContentScale,
    playbackSpeed: Float,
    isLooping: Boolean,
    onBack: () -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onOpenScaleSheet: (() -> Unit)?,
    onToggleLoop: () -> Unit,
    onPlayElsewhere: (() -> Unit)?,
    onShare: () -> Unit,
    onDetails: () -> Unit,
    onEdit: (() -> Unit)? = null,
    /** Switches to the next audio track; null when the video has one or none. */
    onCycleAudioTrack: (() -> Unit)? = null,
    /** Switches to the next subtitle track, then off; null without subtitles. */
    onCycleSubtitles: (() -> Unit)? = null,
    /** Centred right under the title row. */
    belowTitle: (@Composable () -> Unit)? = null,
    /** Shrinks the video into the small window over other apps; null when that is off. */
    onEnterPip: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Title Row (Back Button, Title, Menu Button)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilledTonalIconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.navigate_up),
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (onEnterPip != null) {
                FilledTonalIconButton(
                    onClick = onEnterPip,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PictureInPictureAlt,
                        contentDescription = stringResource(R.string.pip_title),
                    )
                }
            }

            // 3-dots Menu
            Box {
                FilledTonalIconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.more_options),
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.widthIn(min = 180.dp),
                ) {
                    if (onEdit != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit_video)) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        },
                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.details)) },
                        onClick = {
                            menuExpanded = false
                            onDetails()
                        },
                        leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    )
                    if (onPlayElsewhere != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.play_in_other_player)) },
                            onClick = {
                                menuExpanded = false
                                onPlayElsewhere()
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            },
                        )
                    }
                }
            }
        }

        if (belowTitle != null) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                belowTitle()
            }
        }

        // Shortcuts Row (FlowRow below title row matching Next Player)
        val topShortcuts = config.visibleShortcuts.filter {
            it != PlayerShortcut.LOCK_CONTROLS && it != PlayerShortcut.PICTURE_IN_PICTURE
        }

        if (topShortcuts.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                topShortcuts.forEach { shortcut ->
                    when (shortcut) {
                        PlayerShortcut.PLAYBACK_SPEED -> {
                            FilledTonalIconButton(
                                onClick = onOpenSpeedSheet,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Speed,
                                    contentDescription = stringResource(R.string.playback_speed),
                                )
                            }
                        }
                        PlayerShortcut.SCREEN_ROTATION, PlayerShortcut.VIDEO_CONTENT_SCALE -> if (onOpenScaleSheet != null) {
                            FilledTonalIconButton(
                                onClick = onOpenScaleSheet,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ScreenRotation,
                                    contentDescription = stringResource(R.string.video_scale),
                                )
                            }
                        }
                        PlayerShortcut.AUDIO_TRACK -> if (onCycleAudioTrack != null) {
                            FilledTonalIconButton(
                                onClick = onCycleAudioTrack,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Audiotrack,
                                    contentDescription = null,
                                )
                            }
                        }
                        PlayerShortcut.LOOP -> {
                            FilledTonalIconButton(
                                onClick = onToggleLoop,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = if (isLooping) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                    contentDescription = null,
                                )
                            }
                        }
                        // Playlist and shuffle have no counterpart in the gallery viewer (the pager is
                        // the list): not shown rather than opening something unrelated.
                        PlayerShortcut.SUBTITLE_TRACK -> if (onCycleSubtitles != null) {
                            FilledTonalIconButton(
                                onClick = onCycleSubtitles,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Subtitles,
                                    contentDescription = null,
                                )
                            }
                        }
                        PlayerShortcut.PLAY_IN_BACKGROUND -> if (onPlayElsewhere != null) {
                            FilledTonalIconButton(
                                onClick = onPlayElsewhere,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Headset,
                                    contentDescription = null,
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
fun VideoPlayerCenterControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp, alignment = Alignment.CenterHorizontally),
    ) {
        // Previous Button
        FilledTonalIconButton(
            onClick = onPrevious,
            enabled = hasPrevious,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.previous_video),
                modifier = Modifier.size(28.dp),
            )
        }

        // Play / Pause Button
        Box(contentAlignment = Alignment.Center) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(64.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                )
            } else {
                FilledTonalIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.play_pause),
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }

        // Next Button
        FilledTonalIconButton(
            onClick = onNext,
            enabled = hasNext,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.next_video),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
fun VideoPlayerBottomControls(
    playerState: VideoPlayerState,
    config: AnderLineConfig,
    onSeek: (Long) -> Unit,
    onToggleLock: () -> Unit,
    onOpenScaleSheet: (() -> Unit)?,
    sponsorSegments: List<SponsorSegment> = emptyList(),
    /** Centred in the action row under the seek bar. */
    actionCenter: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp)
            .padding(top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Time Row on left
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { playerState.showRemainingTime = !playerState.showRemainingTime },
            ) {
                Text(
                    text = if (playerState.showRemainingTime) playerState.remainingFormatted else playerState.positionFormatted,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text(
                    text = " / ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text(
                    text = playerState.durationFormatted,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        // Custom Material You Seekbar matching Next Player
        MaterialYouPlayerSeekbar(
            positionMs = playerState.positionMs,
            durationMs = playerState.durationMs,
            onSeek = onSeek,
            sponsorSegments = sponsorSegments,
            modifier = Modifier.fillMaxWidth(),
        )

        // Bottom Action Row (Lock on start, Aspect ratio / PiP on end)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (actionCenter != null) {
                Box(modifier = Modifier.align(Alignment.Center)) {
                    actionCenter()
                }
            }

            if (PlayerShortcut.LOCK_CONTROLS in config.visibleShortcuts) {
                FilledTonalIconButton(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(40.dp),
                    onClick = onToggleLock,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LockOpen,
                        contentDescription = stringResource(R.string.lock_controls),
                    )
                }
            }

            if (onOpenScaleSheet != null && (PlayerShortcut.VIDEO_CONTENT_SCALE in config.visibleShortcuts ||
                PlayerShortcut.PICTURE_IN_PICTURE in config.visibleShortcuts)
            ) {
                FilledTonalIconButton(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(40.dp),
                    onClick = onOpenScaleSheet,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Fullscreen,
                        contentDescription = stringResource(R.string.video_scale),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialYouPlayerSeekbar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    sponsorSegments: List<SponsorSegment> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val currentFraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val sliderValue = if (isDragging) dragProgress else currentFraction

    val primaryColor = MaterialTheme.colorScheme.primary
    val trackHeight = 8.dp
    val trackThumbGapWidth = 12.dp

    Slider(
        value = sliderValue,
        valueRange = 0f..1f,
        onValueChange = {
            isDragging = true
            dragProgress = it
        },
        onValueChangeFinished = {
            isDragging = false
            onSeek((dragProgress * durationMs).toLong())
        },
        modifier = modifier.height(24.dp),
        thumb = {
            val thumbWidth by animateDpAsState(if (isDragging) 8.dp else 4.dp, label = "thumbW")
            val thumbHeight by animateDpAsState(if (isDragging) 24.dp else 20.dp, label = "thumbH")
            Box(
                modifier = Modifier
                    .width(thumbWidth)
                    .height(thumbHeight)
                    .clip(CircleShape)
                    .background(primaryColor),
            )
        },
        track = { sliderState ->
            val disabledAlpha = 0.38f
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .sponsorSegments(sponsorSegments, durationMs),
            ) {
                val playedFraction = sliderState.value.coerceIn(0f, 1f)
                val playedPixels = size.width * playedFraction
                val endCornerRadius = size.height / 2f
                val insideCornerRadius = 2.dp.toPx()
                val gapHalf = trackThumbGapWidth.toPx() / 2f
                val leftEnd = (playedPixels - gapHalf).coerceIn(0f, size.width)
                val rightStart = (playedPixels + gapHalf).coerceIn(0f, size.width)

                // Inactive track (right side)
                if (rightStart < size.width) {
                    drawRoundedRect(
                        offset = Offset(rightStart, 0f),
                        size = Size(size.width - rightStart, size.height),
                        color = primaryColor.copy(alpha = disabledAlpha),
                        startCornerRadius = insideCornerRadius,
                        endCornerRadius = endCornerRadius,
                    )
                }

                // Active track (left side)
                if (leftEnd > 0f) {
                    drawRoundedRect(
                        offset = Offset(0f, 0f),
                        size = Size(leftEnd, size.height),
                        color = primaryColor,
                        startCornerRadius = endCornerRadius,
                        endCornerRadius = insideCornerRadius,
                    )
                }
            }
        },
    )
}

/** Paints each segment in its category color over the track, played part included. */
private fun Modifier.sponsorSegments(segments: List<SponsorSegment>, durationMs: Long): Modifier = drawWithContent {
    drawContent()
    if (durationMs <= 0L) return@drawWithContent
    val duration = durationMs.toFloat()
    segments.forEach { segment ->
        val start = size.width * (segment.startMs / duration).coerceIn(0f, 1f)
        val end = size.width * (segment.endMs / duration).coerceIn(0f, 1f)
        drawRect(segment.color, topLeft = Offset(start, 0f), size = Size(end - start, size.height))
    }
}

private fun DrawScope.drawRoundedRect(
    offset: Offset,
    size: Size,
    color: Color,
    startCornerRadius: Float,
    endCornerRadius: Float,
) {
    val startCorner = CornerRadius(startCornerRadius, startCornerRadius)
    val endCorner = CornerRadius(endCornerRadius, endCornerRadius)
    val track = RoundRect(
        rect = Rect(Offset(offset.x, 0f), size = Size(size.width, size.height)),
        topLeft = startCorner,
        topRight = endCorner,
        bottomRight = endCorner,
        bottomLeft = startCorner,
    )
    drawPath(
        path = Path().apply {
            addRoundRect(track)
        },
        color = color,
    )
}

@Composable
fun VerticalProgressIndicatorView(
    feedback: VerticalGestureFeedback,
    modifier: Modifier = Modifier,
) {
    val icon = when (feedback.type) {
        VerticalGestureType.BRIGHTNESS -> {
            when {
                feedback.percentage > 66 -> Icons.Rounded.BrightnessHigh
                feedback.percentage > 33 -> Icons.Rounded.BrightnessMedium
                else -> Icons.Rounded.BrightnessLow
            }
        }
        VerticalGestureType.VOLUME -> {
            when {
                feedback.percentage > 66 -> Icons.AutoMirrored.Rounded.VolumeUp
                feedback.percentage > 33 -> Icons.AutoMirrored.Rounded.VolumeDown
                feedback.percentage > 0 -> Icons.AutoMirrored.Rounded.VolumeMute
                else -> Icons.AutoMirrored.Rounded.VolumeOff
            }
        }
    }

    Surface(
        modifier = modifier
            .heightIn(max = 240.dp)
            .width(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${feedback.percentage}%",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .width(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight((feedback.percentage / 100f).coerceIn(0f, 1f))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun DoubleTapSeekBubble(
    feedback: DoubleTapFeedback,
    modifier: Modifier = Modifier,
) {
    val isLeft = feedback.side == SeekSide.LEFT
    val icon = if (isLeft) Icons.Rounded.Replay10 else Icons.Rounded.Forward10
    val text = "${if (isLeft) "-" else "+"}${feedback.seconds}s"

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
fun LongPressSpeedPill(
    speed: Float = 2.0f,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Speed,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.fast_playback_speed, speed),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}
