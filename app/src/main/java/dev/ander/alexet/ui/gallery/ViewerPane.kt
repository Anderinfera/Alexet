package dev.ander.alexet.ui.gallery

import android.app.Activity
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import dev.ander.alexet.ui.components.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import dev.ander.alexet.ui.components.animateDpAsState
import dev.ander.alexet.ui.components.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.ander.alexet.ui.components.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.AlbumIds
import dev.ander.alexet.R
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.ui.components.MediaThumbnail
import dev.ander.alexet.ui.player.AlexetVideoPlayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.runtime.snapshotFlow

@Composable
fun ViewerPane(
    media: List<GalleryMedia>,
    selected: GalleryMedia,
    nextPlayerInstalled: Boolean,
    onSelect: (GalleryMedia) -> Unit,
    onClose: () -> Unit,
    onDelete: (GalleryMedia) -> Unit = {},
    onEdit: (GalleryMedia) -> Unit = {},
    onPlayVideo: (GalleryMedia) -> Unit,
    onShare: (GalleryMedia) -> Unit,
    onSetAs: (GalleryMedia) -> Unit = {},
    autoplayGifs: Boolean = true,
    showNavArrows: Boolean = false,
    allAlbums: List<Album> = emptyList(),
    allMedia: List<GalleryMedia> = emptyList(),
    onStripExif: (GalleryMedia) -> Unit = {},
    playerConfig: dev.ander.alexet.integration.AnderLineConfig = dev.ander.alexet.integration.AnderLineConfig(),
    modifier: Modifier = Modifier,
) {
    val effectiveMedia = remember(media) {
        (if (media.isEmpty()) listOf(selected) else media).distinctBy { it.id }
    }

    val index = effectiveMedia.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = index.coerceIn(0, (effectiveMedia.size - 1).coerceAtLeast(0)),
        pageCount = { effectiveMedia.size },
    )
    var showDetails by remember { mutableStateOf(false) }
    var showFilmstrip by remember { mutableStateOf(false) }
    var isCompareMode by remember { mutableStateOf(false) }
    var comparedMedia by remember { mutableStateOf<GalleryMedia?>(null) }
    var isCompareSwapped by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // A video opened straight away plays without its controls; one tap shows them.
    var showChrome by remember { mutableStateOf(!selected.isVideo) }
    val scope = rememberCoroutineScope()
    val mediaState = rememberUpdatedState(effectiveMedia)
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    BackHandler(enabled = isCompareMode || showDetails || showFilmstrip) {
        when {
            showDetails -> showDetails = false
            showFilmstrip -> showFilmstrip = false
            isCompareMode -> {
                isCompareMode = false
                comparedMedia = null
                isCompareSwapped = false
            }
        }
    }

    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(selected.id) {
        val i = effectiveMedia.indexOfFirst { it.id == selected.id }
        if (i >= 0 && pagerState.currentPage != i && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(i)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow {
            if (!pagerState.isScrollInProgress) pagerState.settledPage else null
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { settledPage ->
                mediaState.value.getOrNull(settledPage)?.let(onSelect)
            }
    }

    val current = remember(pagerState.settledPage, effectiveMedia) {
        effectiveMedia.getOrNull(pagerState.settledPage) ?: selected
    }
    val chromeVisible = showChrome
    val gifPlayingStates = remember { mutableStateMapOf<Long, Boolean>() }

    LaunchedEffect(pagerState.settledPage, effectiveMedia) {
        if (effectiveMedia.isEmpty()) return@LaunchedEffect
        val fromIndex = (pagerState.settledPage - 2).coerceIn(0, effectiveMedia.size)
        val toIndex = (pagerState.settledPage + 3).coerceIn(fromIndex, effectiveMedia.size)
        val currentIds = effectiveMedia.subList(fromIndex, toIndex).map { it.id }.toSet()
        gifPlayingStates.keys.retainAll(currentIds)
    }

    DisposableEffect(chromeVisible) {
        val window = view.activityWindow() ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        if (chromeVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var photoZoomed by remember { mutableStateOf(false) }
    // The bands the one-finger edge swipes start in: right past the system's own edge gestures
    // (the status bar's pull-down, the gesture bar's home swipe), in window coordinates.
    val (topSystemPx, bottomSystemPx) = systemEdgeGesturesPx()
    val edgeBandPx = with(LocalDensity.current) { EDGE_SWIPE_BAND.toPx() }
    var viewerLeftInWindow by remember { mutableFloatStateOf(0f) }
    var viewerTopInWindow by remember { mutableFloatStateOf(0f) }
    var viewerHeight by remember { mutableFloatStateOf(0f) }
    // Window bounds of the shown edge arrows: a swipe starting on one counts as an edge swipe even
    // where the arrow sits outside the band (under a GIF's pause button, the video's title).
    var topArrowBounds by remember { mutableStateOf<Rect?>(null) }
    var bottomArrowBounds by remember { mutableStateOf<Rect?>(null) }
    val edgeZone = rememberUpdatedState { local: Offset ->
        val inWindow = Offset(viewerLeftInWindow + local.x, viewerTopInWindow + local.y)
        when {
            topArrowBounds?.contains(inWindow) == true -> EdgeSwipe.FROM_TOP
            bottomArrowBounds?.contains(inWindow) == true -> EdgeSwipe.FROM_BOTTOM
            else -> edgeSwipeZone(
                yInWindow = inWindow.y,
                areaTop = viewerTopInWindow,
                areaBottom = viewerTopInWindow + viewerHeight,
                windowHeight = view.rootView.height.toFloat(),
                topSystem = topSystemPx,
                bottomSystem = bottomSystemPx,
                band = edgeBandPx,
            )
        }?.takeIf { (it == EdgeSwipe.FROM_TOP && !showDetails) || (it == EdgeSwipe.FROM_BOTTOM && !showFilmstrip) }
    }
    val topArrow: @Composable () -> Unit = {
        EdgeSwipeArrow(
            pointsDown = true,
            contentDescription = stringResource(R.string.details),
            onClick = { showDetails = true },
            onBounds = { topArrowBounds = it },
        )
    }
    val bottomArrow: @Composable () -> Unit = {
        EdgeSwipeArrow(
            pointsDown = false,
            contentDescription = stringResource(R.string.filmstrip),
            onClick = { showFilmstrip = true },
            onBounds = { bottomArrowBounds = it },
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned {
                val position = it.positionInWindow()
                viewerLeftInWindow = position.x
                viewerTopInWindow = position.y
                viewerHeight = it.size.height.toFloat()
            }
            .edgeSwipeViewerGestures(
                isPhotoZoomed = { photoZoomed },
                zoneAt = { edgeZone.value(it) },
                onSwipeDownFromTop = {
                    showDetails = true
                },
                onSwipeUpFromBottom = {
                    showFilmstrip = true
                },
            ),
    ) {
        val isUiActive = showChrome || showFilmstrip || showDetails || menu
        if (!isCompareMode) {
            MediaPager(
                media = effectiveMedia,
                pagerState = pagerState,
                onZoomedChange = { photoZoomed = it },
                controlsVisible = showChrome,
                isUiActive = isUiActive,
                onToggleChrome = {
                    menu = false
                    showChrome = !showChrome
                },
                onCoverZoom = { covering ->
                    if (covering) {
                        menu = false
                        showChrome = false
                    }
                },
                onClose = onClose,
                nextPlayerInstalled = nextPlayerInstalled,
                onPlayVideo = onPlayVideo,
                onShare = onShare,
                onEdit = onEdit,
                onShowDetails = { showDetails = true },
                autoplayGifs = autoplayGifs,
                gifPlayingStates = gifPlayingStates,
                playerConfig = playerConfig,
                videoTopArrow = topArrow.takeIf { !showDetails },
                videoBottomArrow = bottomArrow.takeIf { !showFilmstrip },
            )

            // Photo overlay controls (Back, Play/Pause for GIF, Edit, Delete, and Menu buttons for non-video items)
            if (!current.isVideo && !current.isAudio) {
                val isCurrentGif = current.mimeType.equals("image/gif", ignoreCase = true) ||
                    current.displayName.endsWith(".gif", ignoreCase = true) ||
                    current.mimeType.equals("image/webp", ignoreCase = true)
                val isCurrentGifPlaying = gifPlayingStates[current.id] ?: autoplayGifs

                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(16.dp),
                ) {
                    FilledTonalIconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                    }
                }

                if (isCurrentGif) {
                    AnimatedVisibility(
                        visible = chromeVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(16.dp),
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                gifPlayingStates[current.id] = !isCurrentGifPlaying
                            },
                        ) {
                            Icon(
                                imageVector = if (isCurrentGifPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isCurrentGifPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(onClick = { showDetails = true }) {
                            Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.details))
                        }
                        Box {
                            FilledTonalIconButton(onClick = { menu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (current.bucketId != dev.ander.alexet.data.AlbumIds.TRASH) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (current.isVideo) R.string.edit_video else R.string.edit_photo)) },
                                        onClick = {
                                            menu = false
                                            onEdit(current)
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                    )
                                    if (!current.isVideo) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.set_as)) },
                                            onClick = {
                                                menu = false
                                                onSetAs(current)
                                            },
                                            leadingIcon = { Icon(Icons.Rounded.Wallpaper, contentDescription = null) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.compare)) },
                                            onClick = {
                                                menu = false
                                                isCompareMode = true
                                                comparedMedia = null
                                                isCompareSwapped = false
                                            },
                                            leadingIcon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.strip_exif)) },
                                            onClick = {
                                                menu = false
                                                onStripExif(current)
                                            },
                                            leadingIcon = { Icon(Icons.Rounded.Security, contentDescription = null) },
                                        )
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share)) },
                                    onClick = {
                                        menu = false
                                        onShare(current)
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = {
                                        menu = false
                                        onDelete(current)
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                )
                            }
                        }
                    }
                }

                // Edge arrows: in the edge swipe bands (past the status bar, above the gesture bar);
                // a GIF's top one sits under its pause button.
                val density = LocalDensity.current
                val windowHeight = view.rootView.height.toFloat()
                val topBandOffset = with(density) { (maxOf(viewerTopInWindow, topSystemPx) - viewerTopInWindow).coerceAtLeast(0f).toDp() }
                val bottomBandOffset = with(density) {
                    val viewerBottom = viewerTopInWindow + viewerHeight
                    (viewerBottom - minOf(viewerBottom, windowHeight - bottomSystemPx)).coerceAtLeast(0f).toDp()
                }
                AnimatedVisibility(
                    visible = chromeVisible && !photoZoomed && !showDetails,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topBandOffset + if (isCurrentGif) 60.dp else 0.dp),
                ) {
                    topArrow()
                }
                AnimatedVisibility(
                    visible = chromeVisible && !photoZoomed && !showFilmstrip,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomBandOffset),
                ) {
                    bottomArrow()
                }
            }

            // Side Navigation Arrows (instant scrollToPage without animation)
            if (showNavArrows && chromeVisible && effectiveMedia.size > 1) {
                AnimatedVisibility(
                    visible = pagerState.settledPage > 0,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp)
                        .zIndex(40f),
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            if (pagerState.settledPage > 0) {
                                scope.launch {
                                    pagerState.scrollToPage(pagerState.settledPage - 1)
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.previous),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = pagerState.settledPage < effectiveMedia.size - 1,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .zIndex(40f),
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            if (pagerState.settledPage < effectiveMedia.size - 1) {
                                scope.launch {
                                    pagerState.scrollToPage(pagerState.settledPage + 1)
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = stringResource(R.string.next),
                        )
                    }
                }
            }
        } else {
            // Split-Screen Compare Mode
            if (comparedMedia == null) {
                Row(modifier = Modifier.fillMaxSize()) {
                    CompareMediaPicker(
                        allAlbums = allAlbums,
                        allMedia = allMedia,
                        onMediaSelected = { comparedMedia = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF333333)),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color.Black),
                    ) {
                        val rightZoom = remember(current.id) { PhotoZoomState() }
                        ZoomablePhoto(
                            media = current,
                            zoomState = rightZoom,
                            onToggleChrome = {},
                            onCoverZoom = {},
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = { isCompareMode = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .zIndex(50f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close_compare),
                    )
                }
            } else {
                val nonNullCompared = comparedMedia ?: current
                val leftMedia = if (!isCompareSwapped) nonNullCompared else current
                val rightMedia = if (!isCompareSwapped) current else nonNullCompared
                val leftZoom = remember(leftMedia.id) { PhotoZoomState() }
                val rightZoom = remember(rightMedia.id) { PhotoZoomState() }

                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color.Black),
                    ) {
                        ZoomablePhoto(
                            media = leftMedia,
                            zoomState = leftZoom,
                            onToggleChrome = {},
                            onCoverZoom = {},
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF555555)),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color.Black),
                    ) {
                        ZoomablePhoto(
                            media = rightMedia,
                            zoomState = rightZoom,
                            onToggleChrome = {},
                            onCoverZoom = {},
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 12.dp)
                        .zIndex(50f),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.7f),
                    contentColor = Color.White,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { isCompareSwapped = !isCompareSwapped },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SwapHoriz,
                                contentDescription = stringResource(R.string.swap_photos),
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            onClick = { comparedMedia = null },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.select_photo_to_compare),
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            onClick = {
                                isCompareMode = false
                                comparedMedia = null
                                isCompareSwapped = false
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close_compare),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }

        // Filmstrip card (one finger up from the bottom edge band)
        AnimatedVisibility(
            visible = showFilmstrip && !isCompareMode,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(60f),
        ) {
            FilmstripBar(
                media = effectiveMedia,
                selectedIndex = pagerState.settledPage,
                onSelectIndex = { idx ->
                    scope.launch {
                        pagerState.scrollToPage(idx)
                    }
                },
                onScrubIndex = { idx ->
                    scope.launch {
                        pagerState.scrollToPage(idx)
                    }
                },
                onClose = { showFilmstrip = false },
            )
        }

        // Top Details Panel (one finger down from the top edge band, or info button)
        AnimatedVisibility(
            visible = showDetails,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(100f),
        ) {
            TopDetailsPanel(
                media = current,
                onDismiss = { showDetails = false },
            )
        }
    }
}

class PhotoZoomState(
    initialScale: Float = 1f,
    initialOffset: Offset = Offset.Zero,
) {
    var scale by mutableFloatStateOf(initialScale)
    var offset by mutableStateOf(initialOffset)
    var isInteracting by mutableStateOf(false)
    val isZoomed: Boolean get() = scale > 1.005f || isInteracting
}

@Composable
private fun MediaPager(
    media: List<GalleryMedia>,
    pagerState: PagerState,
    controlsVisible: Boolean,
    isUiActive: Boolean = false,
    onToggleChrome: () -> Unit,
    onCoverZoom: (Boolean) -> Unit,
    onClose: () -> Unit,
    nextPlayerInstalled: Boolean,
    onPlayVideo: (GalleryMedia) -> Unit,
    onShare: (GalleryMedia) -> Unit,
    onEdit: (GalleryMedia) -> Unit,
    onShowDetails: () -> Unit,
    autoplayGifs: Boolean = true,
    gifPlayingStates: Map<Long, Boolean> = emptyMap(),
    playerConfig: dev.ander.alexet.integration.AnderLineConfig = dev.ander.alexet.integration.AnderLineConfig(),
    onZoomedChange: (Boolean) -> Unit = {},
    videoTopArrow: (@Composable () -> Unit)? = null,
    videoBottomArrow: (@Composable () -> Unit)? = null,
) {
    if (media.isEmpty()) return
    val scope = rememberCoroutineScope()
    val zoomStates = remember { mutableStateMapOf<Long, PhotoZoomState>() }
    val isCurrentZoomed by remember(pagerState, media) {
        derivedStateOf {
            val currentMedia = media.getOrNull(pagerState.currentPage)
            val currentZoomState = currentMedia?.let { zoomStates[it.id] }
            currentZoomState?.isZoomed == true
        }
    }
    LaunchedEffect(isCurrentZoomed) { onZoomedChange(isCurrentZoomed) }
    // Only the pages around the current one keep their zoom (like the GIF states): the map grew
    // by one entry for every photo seen.
    LaunchedEffect(pagerState.settledPage, media) {
        val fromIndex = (pagerState.settledPage - 2).coerceIn(0, media.size)
        val toIndex = (pagerState.settledPage + 3).coerceIn(fromIndex, media.size)
        val nearIds = media.subList(fromIndex, toIndex).mapTo(HashSet()) { it.id }
        zoomStates.keys.retainAll(nearIds)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().background(Color.Black),
        key = { media.getOrNull(it)?.id ?: it },
        beyondViewportPageCount = 1,
        userScrollEnabled = !isCurrentZoomed,
    ) { page ->
        val item = media.getOrNull(page) ?: return@HorizontalPager
        if (item.isVideo || item.isAudio) {
            val isCurrentVideo = page == pagerState.settledPage
            if (isCurrentVideo) {
                AlexetVideoPlayer(
                    media = item,
                    controlsVisible = controlsVisible,
                    onToggleControls = onToggleChrome,
                    onBack = onClose,
                    nextPlayerInstalled = nextPlayerInstalled,
                    onPlayInNextPlayer = { onPlayVideo(item) },
                    onShare = { onShare(item) },
                    onDetails = onShowDetails,
                    onEdit = if (item.isAudio) null else ({ onEdit(item) }),
                    hasPrevious = page > 0,
                    hasNext = page < media.size - 1,
                    onPrevious = {
                        if (page > 0) {
                            scope.launch { pagerState.scrollToPage(page - 1) }
                        }
                    },
                    onNext = {
                        if (page < media.size - 1) {
                            scope.launch { pagerState.scrollToPage(page + 1) }
                        }
                    },
                    playerConfig = playerConfig,
                    topArrow = videoTopArrow,
                    bottomArrow = videoBottomArrow,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (item.isAudio) {
                // The next track's disc, still, while swiping to it.
                dev.ander.alexet.ui.player.AudioVisual(
                    media = item,
                    isPlaying = false,
                    isBuffering = false,
                    isEnded = false,
                    speed = 1f,
                    onReplay = {},
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MediaThumbnail(
                    media = item,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    thumbnail = false,
                )
            }
        } else {
            val zoomState = zoomStates.getOrPut(item.id) { PhotoZoomState() }
            val isItemGifPlaying = gifPlayingStates[item.id] ?: autoplayGifs
            ZoomablePhoto(
                media = item,
                zoomState = zoomState,
                onToggleChrome = onToggleChrome,
                onCoverZoom = onCoverZoom,
                isGifPlaying = isItemGifPlaying,
                isUiActive = isUiActive,
            )
        }
    }
}

@Composable
private fun ZoomablePhoto(
    media: GalleryMedia,
    zoomState: PhotoZoomState,
    onToggleChrome: () -> Unit,
    onCoverZoom: (Boolean) -> Unit,
    isGifPlaying: Boolean = true,
    isUiActive: Boolean = false,
) {
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(media.id) {
        mutableStateOf(IntSize(media.width, media.height))
    }
    val toggleChrome = rememberUpdatedState(onToggleChrome)
    val coverZoom = rememberUpdatedState(onCoverZoom)
    // Read inside the gesture: as a pointerInput key it restarted the handler mid-gesture
    // (a pinch right after a tap froze and left the pager locked).
    val uiActive = rememberUpdatedState(isUiActive)
    val layoutState = rememberUpdatedState(layoutSize)
    val imageState = rememberUpdatedState(imageSize)
    val scope = rememberCoroutineScope()
    val chromeJob = remember { mutableStateOf<Job?>(null) }
    val zoomJob = remember { mutableStateOf<Job?>(null) }
    val lastTap = remember { mutableStateOf(TapRecord()) }

    LaunchedEffect(layoutSize, imageSize) {
        if (zoomState.scale > 1.005f) {
            zoomState.offset = clampOffset(zoomState.offset, zoomState.scale, layoutSize, imageSize)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { layoutSize = it }
            .pointerInput(media.id) {
                val doubleTimeout = 320L
                val doubleMin = 40L
                val slop = viewConfiguration.touchSlop
                val tapSlop = slop * 1.5f
                val topDeadZone = 100.dp.toPx()
                val bottomDeadZone = 130.dp.toPx()

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val downTime = down.uptimeMillis
                    val downPos = down.position
                    var currentScale = zoomState.scale
                    var currentOffset = zoomState.offset
                    var maxPointerDistance = 0f
                    var isMultiTouch = false
                    val maxScale = max(5f, coverScale(layoutState.value, imageState.value) * 1.5f)

                    if (currentScale > 1.005f) {
                        down.consume()
                    }

                    try {
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            if (pressed.any { it.isConsumed }) {
                                continue
                            }
                            val view = layoutState.value
                            val inDeadZone = pressed.any {
                                it.position.y < topDeadZone || it.position.y > (view.height - bottomDeadZone)
                            }
                            if (uiActive.value && inDeadZone) {
                                continue
                            }
                            val bothInTopEdge = pressed.all { it.position.y < topDeadZone }
                            val bothInBottomEdge = pressed.all { it.position.y > (view.height - bottomDeadZone) }
                            if (bothInTopEdge || bothInBottomEdge) {
                                continue
                            }

                            isMultiTouch = true
                            zoomState.isInteracting = true
                            zoomJob.value?.cancel()
                            val zoom = event.calculateZoom()
                            val centroid = event.calculateCentroid()
                            val pan = event.calculatePan()
                            val newScale = (currentScale * zoom).coerceIn(1f, maxScale)
                            val center = Offset(view.width / 2f, view.height / 2f)
                            // The point under the fingers stays there: (c - center)(1 - k) + offset * k,
                            // k = new / old scale (the old form was right only at scale 1).
                            val k = newScale / currentScale
                            val newOffset = (centroid - center) * (1f - k) + currentOffset * k + pan
                            currentScale = newScale
                            currentOffset = if (currentScale <= 1.005f) {
                                currentScale = 1f
                                Offset.Zero
                            } else {
                                clampOffset(newOffset, currentScale, layoutState.value, imageState.value)
                            }
                            zoomState.scale = currentScale
                            zoomState.offset = currentOffset
                            event.changes.forEach { it.consume() }
                        } else {
                            val pointer = pressed[0]
                            val dist = (pointer.position - downPos).getDistance()
                            if (dist > maxPointerDistance) maxPointerDistance = dist

                            if (currentScale > 1.005f) {
                                val pan = pointer.positionChange()
                                if (pan != Offset.Zero) {
                                    zoomJob.value?.cancel()
                                    currentOffset = clampOffset(
                                        currentOffset + pan,
                                        currentScale,
                                        layoutState.value,
                                        imageState.value,
                                    )
                                    zoomState.offset = currentOffset
                                }
                                pointer.consume()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                    } finally {
                        // Also when the gesture is cancelled: a stuck flag keeps the pager locked.
                        zoomState.isInteracting = false
                    }

                    val upTime = SystemClock.uptimeMillis()
                    val gestureDuration = upTime - downTime
                    val isTap = !isMultiTouch && maxPointerDistance <= tapSlop && gestureDuration < 300L

                    if (isTap) {
                        val previous = lastTap.value
                        val isDouble = (downTime - previous.uptime) in doubleMin..doubleTimeout &&
                            (downPos - previous.position).getDistance() <= tapSlop * 1.5f

                        if (isDouble) {
                            chromeJob.value?.cancel()
                            lastTap.value = TapRecord()
                            zoomJob.value?.cancel()

                            val current = zoomState.scale
                            val zoomed = current > 1.005f
                            val fill = coverScale(layoutState.value, imageState.value)
                            val targetScale = if (zoomed) {
                                1f
                            } else {
                                if (fill > 1.005f) fill else 2.5f
                            }
                            val targetOffset = if (targetScale <= 1.005f) {
                                Offset.Zero
                            } else {
                                val view = layoutState.value
                                val center = Offset(view.width / 2f, view.height / 2f)
                                val focalOffset = (center - downPos) * (targetScale - 1f)
                                clampOffset(focalOffset, targetScale, view, imageState.value)
                            }

                            if (!zoomed) {
                                coverZoom.value(true)
                            }

                            val startScale = current
                            val startOffset = zoomState.offset
                            zoomJob.value = scope.launch {
                                val scaleAnim = Animatable(startScale)
                                val offsetAnim = Animatable(startOffset, Offset.VectorConverter)
                                coroutineScope {
                                    launch {
                                        scaleAnim.animateTo(
                                            targetScale,
                                            spring(stiffness = Spring.StiffnessMediumLow),
                                        ) { zoomState.scale = value }
                                    }
                                    launch {
                                        offsetAnim.animateTo(
                                            targetOffset,
                                            spring(stiffness = Spring.StiffnessMediumLow),
                                        ) {
                                            zoomState.offset = clampOffset(value, zoomState.scale, layoutState.value, imageState.value)
                                        }
                                    }
                                }
                                zoomState.scale = targetScale
                                zoomState.offset = if (targetScale <= 1.005f) Offset.Zero else clampOffset(targetOffset, targetScale, layoutState.value, imageState.value)
                            }
                        } else {
                            lastTap.value = TapRecord(downTime, downPos)
                            chromeJob.value?.cancel()
                            chromeJob.value = scope.launch {
                                delay(doubleTimeout)
                                toggleChrome.value()
                            }
                        }
                    }
                }
            }
        .graphicsLayer {
            scaleX = zoomState.scale
            scaleY = zoomState.scale
            translationX = zoomState.offset.x
            translationY = zoomState.offset.y
        },
    contentAlignment = Alignment.Center,
) {
    MediaThumbnail(
        media = media,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
        showPlayIcon = false,
        showDuration = false,
        thumbnail = false,
        shape = RectangleShape,
        isGifPlaying = isGifPlaying,
        onDecodedSize = { imageSize = it },
    )
}
}

private data class TapRecord(
    val uptime: Long = 0L,
    val position: Offset = Offset.Zero,
)

private fun clampOffset(
    offset: Offset,
    scale: Float,
    view: IntSize,
    image: IntSize,
): Offset {
    val viewW = view.width.toFloat()
    val viewH = view.height.toFloat()
    val imageW = image.width.toFloat()
    val imageH = image.height.toFloat()
    if (viewW <= 0f || viewH <= 0f || imageW <= 0f || imageH <= 0f || scale <= 1.001f) {
        return Offset.Zero
    }
    val fitScale = min(viewW / imageW, viewH / imageH)
    if (fitScale <= 0f) return Offset.Zero

    val displayedW = imageW * fitScale * scale
    val displayedH = imageH * fitScale * scale

    val maxOffsetX = max(0f, (displayedW - viewW) / 2f)
    val maxOffsetY = max(0f, (displayedH - viewH) / 2f)

    val clampedX = if (maxOffsetX > 0.5f) offset.x.coerceIn(-maxOffsetX, maxOffsetX) else 0f
    val clampedY = if (maxOffsetY > 0.5f) offset.y.coerceIn(-maxOffsetY, maxOffsetY) else 0f

    return Offset(clampedX, clampedY)
}

private fun coverScale(view: IntSize, image: IntSize): Float {
    val viewW = view.width.toFloat()
    val viewH = view.height.toFloat()
    val imageW = image.width.toFloat()
    val imageH = image.height.toFloat()
    if (viewW <= 0f || viewH <= 0f || imageW <= 0f || imageH <= 0f) return 1f
    val fit = min(viewW / imageW, viewH / imageH)
    val cover = max(viewW / imageW, viewH / imageH)
    if (fit <= 0f) return 1f
    return max(cover / fit, 1f)
}

private fun android.view.View.activityWindow(): android.view.Window? {
    var ctx = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return null
}

/** How far from the system's edge area a one-finger edge swipe may start. */
private val EDGE_SWIPE_BAND = 36.dp

internal enum class EdgeSwipe { FROM_TOP, FROM_BOTTOM }

/**
 * Hint for an edge swipe that also opens its panel on a tap. Reports its window bounds (null once
 * gone), so a swipe starting on it is an edge swipe wherever it sits.
 */
@Composable
private fun EdgeSwipeArrow(
    pointsDown: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    onBounds: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnBounds by rememberUpdatedState(onBounds)
    DisposableEffect(Unit) {
        onDispose { currentOnBounds(null) }
    }
    Box(
        modifier = modifier
            .size(EDGE_SWIPE_BAND)
            .onGloballyPositioned { currentOnBounds(it.boundsInWindow()) }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (pointsDown) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                contentDescription = contentDescription,
                tint = Color.White,
            )
        }
    }
}

/**
 * Which edge band a touch at [yInWindow] starts in: [band] px right past the system's own gesture
 * area at each edge ([topSystem] / [bottomSystem] px of a [windowHeight] px window), within the
 * viewer ([areaTop]..[areaBottom], window coordinates). Null elsewhere: closer to the edge is the
 * system's (notifications, home), further in is the photo's (swipe, zoom).
 */
internal fun edgeSwipeZone(
    yInWindow: Float,
    areaTop: Float,
    areaBottom: Float,
    windowHeight: Float,
    topSystem: Float,
    bottomSystem: Float,
    band: Float,
): EdgeSwipe? {
    val top = maxOf(areaTop, topSystem)
    val bottom = minOf(areaBottom, windowHeight - bottomSystem)
    if (bottom - top < band * 2) return null
    return when {
        yInWindow >= top && yInWindow < top + band -> EdgeSwipe.FROM_TOP
        yInWindow > bottom - band && yInWindow <= bottom -> EdgeSwipe.FROM_BOTTOM
        else -> null
    }
}

/** Heights in px of the system's edge gesture areas (top, bottom), also while the bars are hidden. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun systemEdgeGesturesPx(): Pair<Float, Float> {
    val density = LocalDensity.current
    val top = maxOf(
        WindowInsets.statusBarsIgnoringVisibility.getTop(density),
        WindowInsets.mandatorySystemGestures.getTop(density),
    )
    val bottom = maxOf(
        WindowInsets.navigationBarsIgnoringVisibility.getBottom(density),
        WindowInsets.mandatorySystemGestures.getBottom(density),
    )
    return top.toFloat() to bottom.toFloat()
}

/**
 * One finger from an edge band ([zoneAt]) moved clearly towards the middle: from the top it
 * opens the info, from the bottom the carousel. Not on a zoomed photo; a second finger (a pinch),
 * a sideways move (the pager) or a move towards the edge leave the touch to the viewer, which
 * gets every touch untouched until the swipe is recognised.
 */
private fun Modifier.edgeSwipeViewerGestures(
    isPhotoZoomed: () -> Boolean,
    zoneAt: (Offset) -> EdgeSwipe?,
    onSwipeDownFromTop: () -> Unit,
    onSwipeUpFromBottom: () -> Unit,
): Modifier = pointerInput(Unit) {
    val threshold = 48.dp.toPx()
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (isPhotoZoomed()) return@awaitEachGesture
        val zone = zoneAt(down.position) ?: return@awaitEachGesture
        var claimed = false
        var triggered = false
        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (triggered) {
                // The rest of this touch belongs to the swipe: nothing scrolls under it.
                event.changes.forEach { it.consume() }
                continue
            }
            if (!claimed && event.changes.count { it.pressed } > 1) return@awaitEachGesture
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            val towardMiddle = if (zone == EdgeSwipe.FROM_TOP) dy else -dy
            if (!claimed) {
                if (abs(dx) > slop && abs(dx) >= abs(dy)) return@awaitEachGesture
                if (towardMiddle < -slop) return@awaitEachGesture
                if (towardMiddle > slop && towardMiddle > abs(dx)) claimed = true
            }
            if (claimed) {
                event.changes.forEach { it.consume() }
                if (towardMiddle > threshold) {
                    triggered = true
                    if (zone == EdgeSwipe.FROM_TOP) onSwipeDownFromTop() else onSwipeUpFromBottom()
                }
            }
        }
    }
}

@Composable
private fun TopDetailsPanel(
    media: GalleryMedia,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.details),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                SingleFileInfo(item = media)
            }
        }
    }
}

@Composable
private fun FilmstripBar(
    media: List<GalleryMedia>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    onScrubIndex: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val itemWidth = 60.dp
    val itemHeight = 60.dp
    val itemSpacing = 8.dp
    // The card sits this far from the screen's sides.
    val cardMargin = 12.dp
    val onCloseState = rememberUpdatedState(onClose)
    // The scrub effect below lives as long as the list: read current values, not the first ones.
    val selectedIndexState = rememberUpdatedState(selectedIndex)
    val mediaState = rememberUpdatedState(media)
    val onScrubState = rememberUpdatedState(onScrubIndex)
    // Centring animations run by this code are not the user scrubbing.
    var programmaticScroll by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        // The row is as wide as the card: the selected thumbnail is centred in it.
        val totalWidth = maxWidth - cardMargin * 2
        val horizontalPadding = if (totalWidth > itemWidth) (totalWidth - itemWidth) / 2 else 0.dp

        // When scrolling (scrubbing) the filmstrip, update pagerState simultaneously
        LaunchedEffect(listState) {
            snapshotFlow {
                if (!listState.isScrollInProgress || programmaticScroll) null
                else {
                    val viewportCenter = filmstripCenter(listState)
                    if (listState.layoutInfo.viewportSize.width <= 0) null
                    else {
                        val closest = listState.layoutInfo.visibleItemsInfo
                            .minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }
                        closest?.index
                    }
                }
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { centerIndex ->
                    if (centerIndex in mediaState.value.indices && centerIndex != selectedIndexState.value) {
                        onScrubState.value(centerIndex)
                    }
                }
        }

        // When user finishes scrubbing, snap/center cleanly
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { inProgress ->
                    if (!inProgress) {
                        val viewportCenter = filmstripCenter(listState)
                        if (listState.layoutInfo.viewportSize.width > 0) {
                            val closest = listState.layoutInfo.visibleItemsInfo
                                .minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }
                            if (closest != null) {
                                val diff = (closest.offset + closest.size / 2) - viewportCenter
                                if (abs(diff) > 1) {
                                    // The snap lands on the item already chosen while scrubbing.
                                    programmaticScroll = true
                                    try {
                                        listState.animateScrollBy(diff.toFloat())
                                    } finally {
                                        programmaticScroll = false
                                    }
                                }
                            }
                        }
                    }
                }
        }

        // When selectedIndex changes externally (or initial open), center selected item
        LaunchedEffect(selectedIndex) {
            snapshotFlow { listState.layoutInfo.viewportSize.width }
                .distinctUntilChanged()
                .collect { width ->
                    if (listState.isScrollInProgress) return@collect
                    if (selectedIndex !in media.indices) return@collect
                    if (width <= 0) return@collect
                    val viewportCenter = filmstripCenter(listState)

                    programmaticScroll = true
                    try {
                        val currentItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
                        if (currentItem != null) {
                            val diff = (currentItem.offset + currentItem.size / 2) - viewportCenter
                            if (abs(diff) > 1) {
                                listState.animateScrollBy(diff.toFloat())
                            }
                        } else {
                            listState.scrollToItem(selectedIndex)
                            val itemAfter = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
                            if (itemAfter != null) {
                                val diff = (itemAfter.offset + itemAfter.size / 2) - viewportCenter
                                if (abs(diff) > 1) {
                                    listState.animateScrollBy(diff.toFloat())
                                }
                            }
                        }
                    } finally {
                        programmaticScroll = false
                    }
                }
        }

        // A card in the app's own colours, off the screen's edges.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = cardMargin)
                .padding(bottom = cardMargin),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                // Handle and counter: dragging them down (or a tap on the handle) closes the card.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            var dragged = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dragged = 0f },
                                onVerticalDrag = { change, amount ->
                                    dragged += amount
                                    change.consume()
                                },
                                onDragEnd = { if (dragged > 32.dp.toPx()) onCloseState.value() },
                            )
                        }
                        .padding(top = 4.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = stringResource(R.string.close)) { onClose() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(width = 32.dp, height = 4.dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Text(
                            text = "${selectedIndex + 1} / ${media.size}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }

                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                ) {
                    itemsIndexed(
                        items = media,
                        key = { _, item -> item.id },
                    ) { idx, item ->
                        val isSelected = idx == selectedIndex
                        val itemScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.1f else 0.92f,
                            animationSpec = spring(
                                stiffness = Spring.StiffnessMediumLow,
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                            ),
                            label = "filmstripScale",
                        )
                        val animatedBorderWidth by animateDpAsState(
                            targetValue = if (isSelected) 3.dp else 0.dp,
                            animationSpec = spring(
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                            label = "filmstripBorder",
                        )
                        Box(
                            modifier = Modifier
                                .size(width = itemWidth, height = itemHeight)
                                .graphicsLayer {
                                    scaleX = itemScale
                                    scaleY = itemScale
                                    // The others a little dimmer: the chosen one stands out.
                                    alpha = if (isSelected) 1f else 0.8f
                                }
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = animatedBorderWidth,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable {
                                    onSelectIndex(idx)
                                },
                        ) {
                            MediaThumbnail(
                                media = item,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                thumbnail = true,
                                showPlayIcon = item.isVideo,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareMediaPicker(
    allAlbums: List<Album>,
    allMedia: List<GalleryMedia>,
    onMediaSelected: (GalleryMedia) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedAlbumId by remember { mutableStateOf<Long?>(null) }

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            if (selectedAlbumId == null) {
                Text(
                    text = stringResource(R.string.select_photo_to_compare),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp),
                )
                val safeAlbums = remember(allAlbums) { allAlbums.distinctBy { it.id } }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedAlbumId = -999L },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.all_media),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                    }
                    items(safeAlbums, key = { it.id }) { album ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedAlbumId = album.id },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                album.cover?.let { coverMedia ->
                                    MediaThumbnail(
                                        media = coverMedia,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        thumbnail = true,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter)
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(4.dp),
                                ) {
                                    Text(
                                        text = album.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val photos = remember(selectedAlbumId, allMedia) {
                    (if (selectedAlbumId == -999L) {
                        allMedia.filter { !it.isVideo }
                    } else {
                        allMedia.filter { it.bucketId == selectedAlbumId && !it.isVideo }
                    }).distinctBy { it.id }
                }
                val albumName = if (selectedAlbumId == -999L) {
                    stringResource(R.string.all_media)
                } else {
                    allAlbums.firstOrNull { it.id == selectedAlbumId }?.name ?: ""
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { selectedAlbumId = null }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                        )
                    }
                    Text(
                        text = albumName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(photos, key = { it.id }) { photo ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onMediaSelected(photo) },
                        ) {
                            MediaThumbnail(
                                media = photo,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                thumbnail = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Centre of the filmstrip's visible area in item offset coordinates. Item offsets start after the
 * (large) start padding, so it is not half the viewport width.
 */
private fun filmstripCenter(state: androidx.compose.foundation.lazy.LazyListState): Int =
    (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
