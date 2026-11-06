package dev.ander.alexet.ui.gallery

import android.content.Context
import android.net.Uri
import dev.ander.alexet.ui.components.LocalLightMode
import dev.ander.alexet.ui.components.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import dev.ander.alexet.ui.components.animateFloatAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import dev.ander.alexet.ui.components.AnimatedContent
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.aspectRatio
import dev.ander.alexet.ui.components.SquareThumbnail
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import kotlin.math.roundToInt
import dev.ander.alexet.ui.editor.PhotoEditorTab
import dev.ander.alexet.ui.wallpaper.WallpaperCropRequest
import dev.ander.alexet.ui.wallpaper.WallpaperCropScreen
import dev.ander.alexet.ui.wallpaper.currentWindowSize
import dev.ander.alexet.ui.wallpaper.findActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.zIndex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import dev.ander.alexet.data.SecureFolderManager
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.ander.alexet.data.FolderScrollPos
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import android.app.Activity
import android.provider.DocumentsContract
import dev.ander.alexet.R
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import com.google.accompanist.permissions.rememberPermissionState
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.AlbumIds
import dev.ander.alexet.data.ContentViewMode
import dev.ander.alexet.data.FileSelectMode
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.SearchMode
import dev.ander.alexet.data.searchesFiles
import dev.ander.alexet.data.usesFolderDrag
import dev.ander.alexet.foldable.hasHingeSensor
import dev.ander.alexet.foldable.rememberFoldableState
import dev.ander.alexet.ui.components.LocalFolderCoverBumps
import dev.ander.alexet.ui.components.LocalFolderThumbEpoch
import dev.ander.alexet.ui.components.LocalThumbnailPx
import dev.ander.alexet.ui.components.thumbnailPx
import dev.ander.alexet.ui.components.LocalVideoDurationFormat
import dev.ander.alexet.ui.editor.PhotoEditorPane
import dev.ander.alexet.ui.components.prefetchThumbnails
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val STARTUP_LOADER_MIN_MS = 600L
private const val STARTUP_SETTLE_TIMEOUT_MS = 1_500L
private const val STARTUP_COVER_TIMEOUT_MS = 1_500L

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalPermissionsApi::class,
)
@Composable
fun GalleryApp(
    viewModel: GalleryViewModel,
    onSplashDismiss: () -> Unit = {},
    /** Folders are on screen for the first time (loader gone, list not empty). */
    onFoldersFirstShown: () -> Unit = {},
) {
    val permissionState = rememberMultiplePermissionsState(mediaPermissions())
    val hasAccess = permissionState.permissions.any { it.status.isGranted }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hasAccess) {
        val notifPermState = rememberPermissionState(android.Manifest.permission.POST_NOTIFICATIONS)
        val appContext = LocalContext.current.applicationContext
        LaunchedEffect(hasAccess) {
            // Asked once: after a refusal the system dialog would otherwise come back on every start.
            val flags = appContext.getSharedPreferences("alexet_ui_flags", android.content.Context.MODE_PRIVATE)
            if (!notifPermState.status.isGranted && !flags.getBoolean("notifications_asked", false)) {
                flags.edit().putBoolean("notifications_asked", true).apply()
                notifPermState.launchPermissionRequest()
            }
        }
    }

    // Read access granted by an older version that didn't ask for write access (Android 8-10):
    // ask for the missing one (same group, so usually granted without a dialog).
    LaunchedEffect(hasAccess) {
        if (hasAccess && permissionState.permissions.any { !it.status.isGranted } &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
        ) {
            permissionState.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(hasAccess) {
        if (!hasAccess) permissionState.launchMultiplePermissionRequest()
    }

    if (!hasAccess) {
        val permanentlyDenied = permissionState.permissions.any {
            !it.status.isGranted && !it.status.shouldShowRationale
        }
        LaunchedEffect(Unit) { onSplashDismiss() }
        PermissionScreen(
            onGrant = { permissionState.launchMultiplePermissionRequest() },
            onOpenSettings = viewModel::openAppSettings,
            permanentlyDenied = permanentlyDenied && permissionState.revokedPermissions.isNotEmpty(),
        )
        return
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Apart from uiState and read only where shown (see GalleryViewModel.activeDownloads).
    val activeDownloadsState = viewModel.activeDownloads.collectAsStateWithLifecycle()
    val activeDownloads = remember(activeDownloadsState) { { activeDownloadsState.value } }
    val transferProgressState = viewModel.transferProgress.collectAsStateWithLifecycle()
    val smartStatusState = viewModel.smartStatus.collectAsStateWithLifecycle()
    val editCopyOnly by viewModel.editCopyOnly.collectAsStateWithLifecycle()
    val fullScan by viewModel.fullScan.collectAsStateWithLifecycle()
    val pendingExternalEdit by viewModel.pendingExternalEdit.collectAsStateWithLifecycle()
    if (pendingExternalEdit != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingExternalEdit,
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.edit_discard_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPendingExternalEdit) {
                    Text(stringResource(R.string.edit_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingExternalEdit) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.leaveAfterExternalEdit.collect {
            // Back to where "Edit" was tapped (home / other app); the gallery task stays alive.
            context.findActivity()?.moveTaskToBack(true)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.startForegroundWatch()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.stopForegroundWatch()
        // Before the activity's onStop flushes the settings to disk.
        viewModel.persistFolderScrolls()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshMedia()
    }
    LaunchedEffect(uiState.showingHidden) {
        viewModel.setScanHidden(uiState.showingHidden)
        // Hidden folders need all-files access, whatever turned them on (the switch, an import).
        if (uiState.showingHidden) viewModel.requestAllFilesForHidden()
    }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onDeleteConsented()
        } else {
            viewModel.onDeleteDenied()
        }
    }
    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onWriteConsented()
        } else {
            viewModel.onWriteDenied()
        }
    }
    // The system consent dialogs go through this screen's launchers; a rebuilt screen takes over.
    DisposableEffect(viewModel, writeLauncher, deleteLauncher) {
        val launch: (GalleryViewModel.ConsentKind, android.content.IntentSender) -> Unit = { kind, sender ->
            val request = IntentSenderRequest.Builder(sender).build()
            when (kind) {
                GalleryViewModel.ConsentKind.WRITE -> writeLauncher.launch(request)
                GalleryViewModel.ConsentKind.DELETE -> deleteLauncher.launch(request)
            }
        }
        viewModel.attachConsentLauncher(launch)
        onDispose { viewModel.detachConsentLauncher(launch) }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportConfig(context, it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importConfig(context, it) } }
    // With the AI model installed the configuration is a zip: the model and the photo index go with it.
    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { viewModel.exportConfig(context, it, withSmartSearch = true) } }
    val smartModelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importSmartModel(context, it) } }
    val debugLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { viewModel.saveDebugLog(context, it) } }
    // An archive's contents ("See all files"); out of the way while the viewer shows a file from it.
    val archiveBrowser by viewModel.archiveBrowser.collectAsStateWithLifecycle()
    archiveBrowser?.let { archive ->
        if (uiState.viewerMediaId == null) {
            ArchiveBrowserDialog(
                state = archive,
                onNavigate = viewModel::archiveNavigate,
                onOpenEntry = viewModel::openArchiveEntry,
                onOpenOutside = viewModel::openArchiveOutside,
                onCancelExtract = viewModel::cancelArchiveExtract,
                onBack = viewModel::archiveBack,
                onClose = viewModel::closeArchive,
            )
        }
    }
    var showDebugLogDialog by remember { mutableStateOf(false) }
    if (showDebugLogDialog) {
        AlertDialog(
            onDismissRequest = { showDebugLogDialog = false },
            icon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
            title = { Text(stringResource(R.string.debug_log_create)) },
            text = { Text(stringResource(R.string.debug_log_create_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDebugLogDialog = false
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                    launchPicker(context) { debugLogLauncher.launch("alexet-log-$stamp.txt") }
                }) { Text(stringResource(R.string.debug_log_to_file)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDebugLogDialog = false
                    viewModel.copyDebugLogToClipboard(context)
                }) { Text(stringResource(R.string.debug_log_to_clipboard)) }
            },
        )
    }

    val thumbPx = thumbnailPx(
        highRes = uiState.settings.highResThumbnails,
        screenWidthPx = context.resources.displayMetrics.widthPixels,
        // The larger tiles of the two grids decide the thumbnail size.
        columns = minOf(uiState.settings.gridColumns, uiState.settings.fileColumns),
    )
    CompositionLocalProvider(
        LocalThumbnailPx provides thumbPx,
        LocalLightMode provides uiState.settings.lightMode,
        LocalFolderThumbEpoch provides uiState.folderThumbEpoch,
        LocalFolderCoverBumps provides uiState.folderCoverBumps,
        LocalVideoDurationFormat provides uiState.settings.videoDurationFormat,
        LocalHiddenCoverConfig provides uiState.settings.hiddenCover,
    ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val foldable = rememberFoldableState()
    val isBook = foldable.isBook || foldable.isVerticalSeparating
    val panelSplitPercent = uiState.settings.panelSplitPercent
    val autoFoldSplitEnabled = uiState.settings.autoFoldSplitEnabled
    val foldSplitAngleThreshold = uiState.settings.foldSplitAngleThreshold
    // Only recompose dual-pane when the *target ratio* flips (custom ↔ 50/50),
    // not on every hinge-sensor tick.
    val foldSplitTarget by remember(
        panelSplitPercent,
        isBook,
        autoFoldSplitEnabled,
        foldSplitAngleThreshold,
        foldable.hingeAngleState,
    ) {
        derivedStateOf {
            targetPanelSplitRatio(
                panelSplitPercent = panelSplitPercent,
                isBookMode = isBook,
                autoFoldSplitEnabled = autoFoldSplitEnabled,
                hingeAngle = foldable.hingeAngleState.angle,
                foldSplitAngleThreshold = foldSplitAngleThreshold,
            )
        }
    }
    val wide = if (foldable.isTabletop) false else (foldable.showListDetail || maxWidth >= 600.dp)
    // A hinge sensor or a reported fold: a foldable, so its settings are shown from now on.
    val foldableContext = LocalContext.current
    val hasFold = foldable.foldingFeature != null
    LaunchedEffect(hasFold, uiState.settings.foldableDetected) {
        if (!uiState.settings.foldableDetected && (hasFold || foldableContext.hasHingeSensor())) {
            viewModel.markFoldableDetected()
        }
    }
    val shownFileColumns = if (wide) uiState.settings.foldableFileColumns else uiState.settings.fileColumns
    LaunchedEffect(shownFileColumns) { viewModel.setShownFileColumns(shownFileColumns) }
    var previousWide by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(wide) {
        val prev = previousWide
        if (prev != null && prev != wide) {
            if (wide) viewModel.onUnfolded() else viewModel.onFolded()
        }
        previousWide = wide
    }

    val density = LocalDensity.current
    LaunchedEffect(wide, maxWidth, maxHeight) {
        // Split screen or a pop-up window: the narrow window is not the outer screen, and the
        // sizes measured here (the whole display) would be stored as the folded one.
        if (context.findActivity()?.isInMultiWindowMode == true) return@LaunchedEffect
        val windowPx = currentWindowSize(context.findActivity(), context)
        val w = windowPx?.width ?: with(density) { maxWidth.roundToPx() }
        val h = windowPx?.height ?: with(density) { maxHeight.roundToPx() }
        viewModel.recordScreenResolution(isWide = wide, width = w, height = h)
    }

    LaunchedEffect(uiState.phoneFolderId, uiState.phoneFolder, uiState.isLoading, uiState.albums.isNotEmpty()) {
        if (uiState.phoneFolderId != AlbumIds.NONE &&
            uiState.phoneFolderId != AlbumIds.TRASH &&
            uiState.phoneFolder == null &&
            !uiState.isLoading &&
            uiState.albums.isNotEmpty()
        ) {
            viewModel.closePhoneFolder()
        }
    }

    var searching by rememberSaveable { mutableStateOf(false) }
    // AI search shows only its folders while the field is open.
    LaunchedEffect(searching) { viewModel.setSearchOpen(searching) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val folderGridState = rememberLazyGridState()
    // Its own: one state shared by two grids on screen at once (stickers over the folder grid)
    // made them fight over the scroll position.
    val stickerFolderGridState = rememberLazyGridState()
    val folderGridStates = remember { mutableMapOf<Long, LazyGridState>() }
    val folderGridLruKeys = remember { mutableListOf<Long>() }
    var lastActiveFolderId by remember { mutableStateOf(uiState.phoneFolderId) }

    LaunchedEffect(uiState.phoneFolderId, uiState.openFolderIds) {
        val current = uiState.phoneFolderId
        val currentOpen = uiState.openFolderIds.toSet()
        if (!uiState.settings.rememberFolderScrollAcrossSessions) {
            if (lastActiveFolderId != current) {
                folderGridStates.remove(lastActiveFolderId)
                folderGridLruKeys.remove(lastActiveFolderId)
                folderGridStates.remove(current)
                folderGridLruKeys.remove(current)
            }
            val toRemove = folderGridStates.keys.filter { it != current && it !in currentOpen }
            toRemove.forEach { id ->
                folderGridStates.remove(id)
                folderGridLruKeys.remove(id)
            }
        }
        lastActiveFolderId = current
    }

    fun getOrCreateFolderGridState(folderId: Long, initial: dev.ander.alexet.data.FolderScrollPos? = null): LazyGridState {
        folderGridLruKeys.remove(folderId)
        folderGridLruKeys.add(folderId)
        while (folderGridLruKeys.size > 10) {
            val oldest = folderGridLruKeys.removeAt(0)
            folderGridStates.remove(oldest)
        }
        return folderGridStates.getOrPut(folderId) {
            val init = if (uiState.settings.rememberFolderScrollAcrossSessions) initial else null
            LazyGridState(
                firstVisibleItemIndex = init?.index ?: 0,
                firstVisibleItemScrollOffset = init?.offset ?: 0,
            )
        }
    }

    var pointerWindow by remember { mutableStateOf(Offset.Unspecified) }
    var rootInWindow by remember { mutableStateOf(Offset.Zero) }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val dragKindRef = rememberUpdatedState(uiState.dragKind)
    // Folder-drag modes (wide): long-press only arms the folder. Release without
    // moving selects it (folder actions, e.g. set private); moving starts the drag.
    var armedFolderId by remember { mutableLongStateOf(AlbumIds.NONE) }
    var armOrigin by remember { mutableStateOf(Offset.Unspecified) }
    var folderDragLive by remember { mutableStateOf(false) }
    var rightPaneBounds by remember { mutableStateOf(Rect.Zero) }
    val albumBounds = remember { mutableMapOf<Long, Rect>() }
    var transientSelectionCount by remember { mutableStateOf<Int?>(null) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showRenameFolder by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showFolderInfo by rememberSaveable { mutableStateOf(false) }
    var setAsMediaId by rememberSaveable { mutableStateOf<Long?>(null) }
    val setAsMedia = setAsMediaId?.let { id ->
        uiState.viewerMedia?.takeIf { it.id == id }
            ?: uiState.selectedFiles.find { it.id == id }
            ?: uiState.media.find { it.id == id }
    }
    var wallpaperCropPending by remember { mutableStateOf<WallpaperCropRequest?>(null) }
    // "photo id:screens" of an open crop screen: after a process restart it opens again on the
    // same photo once that is in the lists (the photo object itself can't be saved).
    var wallpaperCropKey by rememberSaveable { mutableStateOf<String?>(null) }
    fun showWallpaperCrop(request: WallpaperCropRequest?) {
        wallpaperCropPending = request
        wallpaperCropKey = request?.let { "${it.media.id}:${it.whichFlags}" }
    }
    LaunchedEffect(wallpaperCropKey, uiState.media) {
        val key = wallpaperCropKey ?: return@LaunchedEffect
        if (wallpaperCropPending != null) return@LaunchedEffect
        val id = key.substringBefore(':').toLongOrNull() ?: return@LaunchedEffect
        val flags = key.substringAfter(':').toIntOrNull() ?: return@LaunchedEffect
        val media = uiState.viewerMedia?.takeIf { it.id == id } ?: uiState.media.firstOrNull { it.id == id }
        if (media != null) wallpaperCropPending = WallpaperCropRequest(media = media, whichFlags = flags)
    }
    var applyingWallpaper by remember { mutableStateOf(false) }
    var albumPick by rememberSaveable(
        stateSaver = Saver<AlbumPickMode?, String>(
            save = { it?.name ?: "" },
            restore = { name ->
                name.takeIf { it.isNotEmpty() }?.let { runCatching { AlbumPickMode.valueOf(it) }.getOrNull() }
            },
        ),
    ) { mutableStateOf(null) }
    var editorTab by remember { mutableStateOf(PhotoEditorTab.ASPECT) }
    // Any way out of the editor (save included) resets the tab; the next image starts fresh.
    LaunchedEffect(uiState.editingMedia == null) {
        if (uiState.editingMedia == null) editorTab = PhotoEditorTab.ASPECT
    }
    var pendingStickerUri by remember { mutableStateOf<Uri?>(null) }
    var showSetPrivateDialog by rememberSaveable { mutableStateOf(false) }
    var albumToDecrypt by remember { mutableStateOf<Album?>(null) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Private folders lock a minute after the app leaves the screen, not at once: turning the
        // screen off and on again keeps the opened folder readable.
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> SecureFolderManager.onAppHidden()
                androidx.lifecycle.Lifecycle.Event.ON_START -> SecureFolderManager.onAppShown()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val albumPickState = rememberUpdatedState(albumPick)
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val mode = albumPickState.value
        if (uri == null || mode == null) return@rememberLauncherForActivityResult
        when (mode) {
            AlbumPickMode.COPY -> viewModel.copySelectionToTree(uri)
            AlbumPickMode.MOVE -> viewModel.moveSelectionToTree(
                treeUri = uri,
                launchWrite = { sender ->
                    viewModel.requestWriteConsent(sender)
                },
                launchDelete = { sender ->
                    viewModel.requestDeleteConsent(sender)
                },
            )
        }
        viewModel.releaseHiddenPicker()
        albumPick = null
    }

    fun onPointerMove(window: Offset) {
        pointerWindow = window
        val state = viewModel.uiState.value
        when (state.dragKind) {
            DragKind.FOLDER -> viewModel.hoverAt(
                    slot = dropSlotFor(window, rightPaneBounds, state.openFolderIds.size),
            )
            DragKind.FILES -> {
                val inRight = rightPaneBounds.contains(window)
                when {
                    inRight && state.settings.contentViewMode.usesFolderDrag() ->
                        viewModel.hoverAt(albumId = fileDropAlbumId(window, rightPaneBounds, state))
                    inRight && state.contentFolderId != AlbumIds.NONE ->
                        viewModel.hoverAt(albumId = state.contentFolderId)
                    else -> {
                        val albumId = albumBounds.entries.firstOrNull { it.value.contains(window) }?.key
                            ?: AlbumIds.NONE
                        viewModel.hoverAt(albumId = albumId)
                    }
                }
            }
            DragKind.NONE -> Unit
        }
    }

    fun onPointerEnd() {
        viewModel.endDrag { sender ->
            viewModel.requestWriteConsent(sender)
        }
        pointerWindow = Offset.Unspecified
    }

    var viewerDeleteTarget by remember { mutableStateOf<GalleryMedia?>(null) }
    // Survives recreation (unlike the target): a dialog opened for one viewed photo must not come
    // back as "delete the selection".
    var deleteFromViewer by rememberSaveable { mutableStateOf(false) }
    val viewer = uiState.viewerMedia
    val selecting = uiState.selectionCount > 0
    val reordering = uiState.reorderingFolders
    val expand = wide && uiState.settings.contentViewMode == ContentViewMode.EXPAND_FOLDERS
    val leftBrowse = wide &&
        !expand &&
        uiState.settings.contentViewMode == ContentViewMode.MULTI_FOLDER &&
        uiState.phoneFolderId != AlbumIds.NONE

    LaunchedEffect(viewer != null, uiState.editingMedia != null, uiState.editingVideoList != null) {
        if (viewer != null || uiState.editingMedia != null || uiState.editingVideoList != null) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    BackHandler {
        when {
            uiState.showSettings -> viewModel.closeSettings()
            uiState.editingVideoList != null -> viewModel.closeVideoEditor()
            uiState.editingMedia != null -> viewModel.closeEditor()
            viewer != null -> viewModel.closeViewer()
            reordering -> viewModel.cancelFolderReorder()
            uiState.contentOrderMode -> viewModel.cancelContentOrderMode()
            selecting -> viewModel.clearSelection()
            uiState.pendingHitboxAlbumId != AlbumIds.NONE -> viewModel.cancelHitboxes()
            // Searching inside a folder: back ends the search first and stays in the folder.
            searching -> {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                searching = false
                viewModel.setQuery("")
            }
            uiState.phoneFolderId != AlbumIds.NONE -> viewModel.closePhoneFolder()
            else -> {
                val act = context.findActivity()
                viewModel.handleBackPress(onExit = { act?.finish() })
            }
        }
    }

    val coldStarting = uiState.isLoading && uiState.albums.isEmpty() && uiState.media.isEmpty()
    var startupRevealed by remember { mutableStateOf(viewModel.startupRevealed) }
    LaunchedEffect(Unit) {
        // First Compose frame exists: hand over from the static system splash to the animated loader.
        onSplashDismiss()
        if (startupRevealed) return@LaunchedEffect
        val shownAt = SystemClock.elapsedRealtime()
        val ready = withTimeoutOrNull(STARTUP_SETTLE_TIMEOUT_MS) {
            snapshotFlow { uiState }.first { !it.isLoading }
        } ?: snapshotFlow { uiState }.first { it.albums.isNotEmpty() || !it.isLoading }
        val columns = ready.settings.gridColumns.coerceAtLeast(1)
        val covers = ready.albums
            .asSequence()
            .filter { !it.isSecure }
            .mapNotNull { album ->
                val cover = album.cover ?: return@mapNotNull null
                val bump = ready.folderCoverBumps[album.id] ?: 0
                cover to if (bump > 0) bump else ready.folderThumbEpoch
            }
            .take((columns * 6).coerceIn(6, 36))
            .toList()
        withTimeoutOrNull(STARTUP_COVER_TIMEOUT_MS) {
            // Same size as the tiles use (thumbPx above), so the warmed covers are found.
            val tileColumns = minOf(ready.settings.gridColumns, ready.settings.fileColumns)
            prefetchThumbnails(context, covers, thumbnailPx(ready.settings.highResThumbnails, context.resources.displayMetrics.widthPixels, tileColumns))
        }
        val remaining = STARTUP_LOADER_MIN_MS - (SystemClock.elapsedRealtime() - shownAt)
        if (remaining > 0) delay(remaining)
        viewModel.markStartupRevealed()
        startupRevealed = true
    }
    val offerLightMode by viewModel.offerLightMode.collectAsStateWithLifecycle()
    if (offerLightMode) {
        AlertDialog(
            onDismissRequest = { viewModel.answerLightModeOffer(false) },
            icon = { Icon(Icons.Rounded.Speed, contentDescription = null) },
            title = { Text(stringResource(R.string.light_mode_offer_title)) },
            text = { Text(stringResource(R.string.light_mode_offer_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.answerLightModeOffer(true) }) {
                    Text(stringResource(R.string.light_mode_offer_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.answerLightModeOffer(false) }) {
                    Text(stringResource(R.string.light_mode_offer_later))
                }
            },
        )
    }
    LaunchedEffect(Unit) {
        if (!viewModel.needsFirstOpenCheck()) return@LaunchedEffect
        snapshotFlow { startupRevealed && uiState.albums.isNotEmpty() }.first { it }
        onFoldersFirstShown()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            AlexetTopBar(
                title = if (uiState.inTrash) stringResource(R.string.trash) else uiState.phoneFolder?.name,
                showUp = uiState.pendingHitboxAlbumId != AlbumIds.NONE ||
                    (uiState.phoneFolderId != AlbumIds.NONE && (!wide || leftBrowse)) ||
                    (uiState.inTrash && wide),
                onUp = {
                    when {
                        uiState.pendingHitboxAlbumId != AlbumIds.NONE -> viewModel.cancelHitboxes()
                        reordering -> viewModel.cancelFolderReorder()
                        uiState.contentOrderMode -> viewModel.cancelContentOrderMode()
                        selecting -> viewModel.clearSelection()
                        else -> viewModel.closePhoneFolder()
                    }
                },
                searching = searching,
                query = uiState.query,
                // In an opened folder the search is for its files (see visibleFilesIn); the wide
                // layout keeps its mode switch, so it shows the mode itself.
                searchMode = if (uiState.phoneFolderId != AlbumIds.NONE && !wide && uiState.searchMode != SearchMode.SMART) SearchMode.FILES else uiState.searchMode,
                smartIndexProgress = smartStatusState.value.takeIf { it.pending > 0 }?.let { it.indexed to it.total },
                aiEffects = uiState.settings.aiEffects,
                onToggleSmartSearch = if (uiState.smartSearchAvailable) viewModel::toggleSmartSearchMode else null,
                smartBaseMode = uiState.modeBeforeSmart,
                smartFolderAllowed = uiState.smartSearchCoversOpenFolder,
                onSmartUnavailable = {
                    android.widget.Toast.makeText(context, R.string.smart_folder_not_covered, android.widget.Toast.LENGTH_LONG).show()
                },
                aiTrigger = uiState.smartResultsVersion,
                onQueryChange = viewModel::setQuery,
                onSearchSubmit = { query -> viewModel.tryUnlockSecureFolderWithPassword(query, context as? FragmentActivity) },
                onSearchToggle = {
                    searching = !searching
                    if (!searching) {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        viewModel.setQuery("")
                    }
                },
                onToggleSearchMode = viewModel::toggleSearchMode,
                onOpenSettings = viewModel::openSettings,
                onCreateDebugLog = if (uiState.settings.debugLogging) ({ showDebugLogDialog = true }) else null,
                onOpenTrash = { viewModel.openTrash(wide) },
                inTrash = uiState.inTrash,
                onRestore = viewModel::restoreSelectedTrash,
                selectionCount = transientSelectionCount ?: uiState.selectionCount,
                isMarqueeActive = transientSelectionCount != null,
                totalCount = when {
                    uiState.selectedAlbumIds.isNotEmpty() && uiState.selectedFileIds.isEmpty() -> uiState.visibleAlbums.size
                    uiState.inTrash -> uiState.trashedItems.size
                    uiState.phoneFolderId != AlbumIds.NONE -> uiState.visibleFilesIn(uiState.phoneFolderId).size
                    !wide -> uiState.visibleAlbums.size
                    // Split panes: the folder the selected files are in, not the whole library.
                    else -> uiState.selectedFiles.map { it.bucketId }.distinct().singleOrNull()
                        ?.let { uiState.visibleFilesIn(it).size }
                        ?: uiState.media.size
                },
                wide = wide,
                activeBarActions = uiState.settings.activeBarActions(wide),
                activeMenuActions = uiState.settings.activeMenuActions(wide),
                inFolder = uiState.phoneFolderId != AlbumIds.NONE || (wide && !expand),
                sortSpec = uiState.settings.sortFor(uiState.sortTargetAlbumId),
                folderSortOnly = uiState.settings.hasFolderSort(uiState.sortTargetAlbumId),
                hasContentOrder = uiState.settings.folderContentOrders.let { orders ->
                    val id = uiState.sortTargetAlbumId
                    !orders[id].isNullOrEmpty() ||
                        uiState.albums.firstOrNull { it.id == id }?.legacyBucketIds
                            ?.any { !orders[it].isNullOrEmpty() } == true
                },
                onApplySort = viewModel::applySortDraft,
                allFilesInFolder = uiState.sortTargetAlbumId.takeIf {
                    it != AlbumIds.NONE && it != AlbumIds.TRASH && it != AlbumIds.FAVORITES &&
                        uiState.allAlbums.firstOrNull { album -> album.id == it }?.isSecure != true
                }?.let { it in uiState.settings.allFilesFolderIds },
                allFilesGlobal = uiState.settings.showAllFiles,
                onToggleAllFilesInFolder = { viewModel.toggleAllFilesFolder(uiState.sortTargetAlbumId) },
                onStripPriority = {
                    viewModel.stripTildePriority { sender ->
                        viewModel.requestWriteConsent(sender)
                    }
                },
                onDelete = {
                    viewerDeleteTarget = null
                    deleteFromViewer = false
                    if (uiState.inTrash) {
                        showDelete = true
                    } else if (viewModel.sessionSkipDeleteConfirm) {
                        viewModel.deleteSelectionWithOption(skipTrash = viewModel.sessionSkipTrash) { sender ->
                            viewModel.requestDeleteConsent(sender)
                        }
                    } else {
                        showDelete = true
                    }
                },
                onShare = { viewModel.shareSelection(context) },
                onInfo = { showInfo = true },
                onFolderInfo = { showFolderInfo = true },
                onStripExif = viewModel::stripExifSelection,
                onRename = {
                    if (uiState.selectedAlbumIds.isNotEmpty()) {
                        showRenameFolder = true
                    } else {
                        showRename = true
                    }
                },
                onHide = viewModel::hideSelection,
                onUnhide = viewModel::unhideSelection,
                isAnonymousFolder = uiState.phoneFolderId in uiState.settings.anonymousFolderIds,
                onToggleAnonymousFolder = if (uiState.phoneFolderId != AlbumIds.NONE) {
                    { viewModel.toggleAnonymousFolder(uiState.phoneFolderId) }
                } else null,
                onMixNames = {
                    viewModel.mixFileNamesInFolder(uiState.phoneFolderId) { sender ->
                        viewModel.requestWriteConsent(sender)
                    }
                },
                canUnhide = uiState.selectedFiles.any {
                    it.isHidden || it.id in uiState.settings.hiddenMediaIds
                } || uiState.selectedAlbums.any {
                    it.isHidden || it.id in uiState.settings.hiddenAlbumIds
                },
                isAllFavorites = uiState.selectedFiles.isNotEmpty() && uiState.selectedFiles.all {
                    it.id in uiState.settings.favoriteMediaIds
                },
                onToggleFavorite = viewModel::toggleFavoriteSelection,
                onCopy = { albumPick = AlbumPickMode.COPY },
                onMove = { albumPick = AlbumPickMode.MOVE },
                // Not for a folder that already is private: a new key would lock its files out.
                onSetPrivate = if (uiState.selectedAlbums.none { it.isSecure }) {
                    { showSetPrivateDialog = true }
                } else null,
                onSelectAll = viewModel::selectAllVisible,
                onSetAs = {
                    val selectedPhoto = uiState.selectedFiles.singleOrNull { !it.isVideo }
                    if (selectedPhoto != null && uiState.selectedAlbumIds.isEmpty()) {
                        setAsMediaId = selectedPhoto.id
                    }
                },
                onEdit = run {
                    val selectedVideos = uiState.selectedFiles.filter { it.isVideo }
                    val selectedPhotos = uiState.selectedFiles.filter { !it.isVideo }
                    val canEdit = (selectedVideos.isNotEmpty() && selectedPhotos.isEmpty() && uiState.selectedAlbumIds.isEmpty()) ||
                        (selectedPhotos.size == 1 && selectedVideos.isEmpty() && uiState.selectedAlbumIds.isEmpty())
                    if (canEdit) {
                        {
                            if (selectedVideos.isNotEmpty()) {
                                viewModel.openVideoEditor(selectedVideos)
                                viewModel.clearSelection()
                            } else if (selectedPhotos.size == 1) {
                                viewModel.openEditor(selectedPhotos.first())
                                viewModel.clearSelection()
                            }
                        }
                    } else null
                },
                editLabelRes = run {
                    val selectedVideos = uiState.selectedFiles.filter { it.isVideo }
                    val selectedPhotos = uiState.selectedFiles.filter { !it.isVideo }
                    when {
                        selectedVideos.size > 1 -> R.string.merge_and_edit_videos
                        selectedVideos.size == 1 -> R.string.edit_video
                        selectedPhotos.size == 1 -> R.string.edit_photo
                        else -> null
                    }
                },
                reorderingFolders = reordering,
                contentOrderMode = uiState.contentOrderMode,
                hasSelectedFolders = uiState.selectedAlbumIds.isNotEmpty(),
                onFolderOrder = viewModel::startFolderReorder,
                onCancelReorder = viewModel::cancelFolderReorder,
                onContentOrder = { viewModel.startContentOrderMode(uiState.sortTargetAlbumId) },
                onCancelContentOrder = viewModel::cancelContentOrderMode,
                onRefresh = viewModel::refreshCurrentContext,
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .threeFingerSwipeDown { viewModel.refreshCurrentContext() }
                .onGloballyPositioned { layout ->
                    rootInWindow = layout.positionInWindow()
                    rootCoords = layout
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val armed = armedFolderId
                            if (armed != AlbumIds.NONE) {
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    armedFolderId = AlbumIds.NONE
                                    viewModel.toggleAlbumSelected(armed)
                                    continue
                                }
                                val position = pressed.last().position
                                val origin = armOrigin
                                if (origin == Offset.Unspecified) {
                                    armOrigin = position
                                    continue
                                }
                                if ((position - origin).getDistance() <= viewConfiguration.touchSlop) continue
                                armedFolderId = AlbumIds.NONE
                                viewModel.startFolderDrag(armed)
                                // uiState (and dragKindRef) lag behind; keep tracking so a quick
                                // release still reaches endDrag.
                                folderDragLive = true
                            }
                            if (dragKindRef.value == DragKind.NONE && !folderDragLive) continue
                            val pressedPointers = event.changes.filter { it.pressed }
                            val change = if (dragKindRef.value == DragKind.FILES && pressedPointers.size >= 2) {
                                pressedPointers.maxByOrNull { it.position.y }
                            } else {
                                pressedPointers.lastOrNull() ?: event.changes.lastOrNull()
                            } ?: continue
                            val coords = rootCoords
                            if (coords != null && coords.isAttached) {
                                onPointerMove(coords.localToWindow(change.position))
                            }
                            if (event.changes.none { pointer -> pointer.pressed }) {
                                folderDragLive = false
                                viewModel.endDrag { sender ->
                                    viewModel.requestWriteConsent(sender)
                                }
                                pointerWindow = Offset.Unspecified
                            }
                        }
                    }
                },
        ) {
            // The curtain set behind the photos: drawn first, the grid covers it (seen in the gaps).
            if (uiState.settings.aiEffects.style(dev.ander.alexet.data.AiEffect.SEARCH_BACKGROUND).behind) {
                dev.ander.alexet.ui.components.aiglow.AiCurtainGlow(
                    config = uiState.settings.aiEffects,
                    active = searching && uiState.searchMode == SearchMode.SMART,
                    trigger = uiState.smartResultsVersion,
                    modifier = Modifier.matchParentSize(),
                )
            }
            AnimatedVisibility(
                visible = uiState.isRefreshing || fullScan != null,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .zIndex(100f),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        val scan = fullScan
                        Text(
                            text = when {
                                scan == null -> stringResource(R.string.refreshing)
                                scan.phase == dev.ander.alexet.data.FullScanProgress.Phase.LIBRARY ->
                                    stringResource(R.string.rescan_progress_library)
                                scan.phase == dev.ander.alexet.data.FullScanProgress.Phase.FOLDERS ->
                                    stringResource(R.string.rescan_progress_folders, scan.done)
                                else -> stringResource(R.string.rescan_progress_files, scan.done, scan.total)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            when {
                uiState.albums.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.empty_gallery),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                reordering -> {
                    FolderReorderList(
                        albums = uiState.reorderAlbums,
                        columns = if (expand) uiState.settings.expandColumns else uiState.settings.gridColumns,
                        visibleRows = when {
                            expand -> uiState.settings.expandRows
                            wide -> 4
                            else -> 5
                        },
                        onMove = viewModel::moveFolderReorder,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    // AI search open with nothing typed: its folders shine.
                    CompositionLocalProvider(
                        LocalActiveDownloads provides activeDownloads,
                        dev.ander.alexet.ui.components.aiglow.LocalAiGlint provides dev.ander.alexet.ui.components.aiglow.rememberAiGlint(
                            uiState.settings.aiEffects,
                            active = uiState.showingSmartFolders,
                        ),
                    ) {
                        GalleryBody(
                            uiState = uiState,
                            wide = wide,
                            isBook = isBook,
                            foldSplitTarget = foldSplitTarget,
                            folderGridState = folderGridState,
                            onAlbumBounds = { id, rect -> albumBounds[id] = rect },
                            onRightPaneBounds = { rightPaneBounds = it },
                            onFolderClick = {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                viewModel.onFolderClick(it, wide)
                            },
                            onStamp = viewModel::toggleStamp,
                            onFolderDragStart = { id ->
                                armOrigin = Offset.Unspecified
                                armedFolderId = id
                            },
                            onFolderDrag = ::onPointerMove,
                            onFolderDragEnd = ::onPointerEnd,
                            onOpenMedia = {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                viewModel.openMedia(it)
                            },
                            onPlayVideo = {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                viewModel.playInNextPlayer(context, it)
                            },
                            onToggleSelect = viewModel::toggleFileSelected,
                            onMarqueeFiles = viewModel::setFileSelection,
                            onToggleAlbum = viewModel::toggleAlbumSelected,
                            onMarqueeAlbums = viewModel::setAlbumSelection,
                            onClosePane = viewModel::closeOpenFolder,
                            onHitboxSelect = viewModel::selectHitbox,
                            onTwoFingerStart = { albumId, offset ->
                                viewModel.startFilesDrag(albumId)
                                onPointerMove(offset)
                            },
                            onTwoFingerDrag = ::onPointerMove,
                            onTwoFingerEnd = ::onPointerEnd,
                            onSplitDragEnd = viewModel::onSplitDragEnd,
                            onSwapStackedPanes = viewModel::swapStackedPanes,
                            onGetFolderScroll = viewModel::getFolderScroll,
                            onSaveFolderScroll = viewModel::saveFolderScroll,
                            onGetFolderGridState = { id -> getOrCreateFolderGridState(id, viewModel.getFolderScroll(id)) },
                            onContentOrderMove = viewModel::moveContentOrder,
                            onDecryptFolder = { albumToDecrypt = it },
                            onTransientCountChange = { transientSelectionCount = it },
                        )
                    }
                }
            }

            DragFloatingPreview(
                dragKind = uiState.dragKind,
                draggingFolderId = uiState.draggingFolderId,
                albums = uiState.albums,
                selectedCount = uiState.selectedFileIds.size,
                pointerWindowProvider = { pointerWindow },
                rootInWindowProvider = { rootInWindow },
            )

            AnimatedVisibility(
                visible = reordering,
                enter = slideInVertically(initialOffsetY = { it * 2 }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(200)),
                exit = slideOutVertically(targetOffsetY = { it * 2 }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(200)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .zIndex(90f),
            ) {
                Button(
                    onClick = viewModel::confirmFolderReorder,
                    modifier = Modifier.widthIn(min = 220.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                ) {
                    Text(stringResource(R.string.reorder_done))
                }
            }

            AnimatedVisibility(
                visible = uiState.contentOrderMode,
                enter = slideInVertically(initialOffsetY = { it * 2 }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(200)),
                exit = slideOutVertically(targetOffsetY = { it * 2 }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(200)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .zIndex(90f),
            ) {
                ContentOrderBottomBar(
                    renameNumeric = uiState.contentOrderRenameNumeric,
                    isAnonymous = uiState.contentOrderFolderId in uiState.settings.anonymousFolderIds,
                    onRenameNumericChange = viewModel::setContentOrderRenameNumeric,
                    onSave = {
                        viewModel.confirmContentOrder { sender ->
                            viewModel.requestWriteConsent(sender)
                        }
                    },
                )
            }

            AnimatedVisibility(
                visible = uiState.stampedAlbumIds.isNotEmpty() && !reordering && !uiState.contentOrderMode,
                enter = slideInVertically(initialOffsetY = { it * 2 }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(200)),
                exit = slideOutVertically(targetOffsetY = { it * 2 }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(200)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .zIndex(90f),
            ) {
                Button(
                    onClick = {
                        viewModel.confirmMove { sender ->
                            viewModel.requestWriteConsent(sender)
                        }
                    },
                    modifier = Modifier.widthIn(min = 220.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                ) {
                    Text(stringResource(R.string.move))
                }
            }

            AnimatedVisibility(
                visible = transferProgressState.value != null,
                enter = slideInVertically(
                    initialOffsetY = { fullHeight -> fullHeight * 2 },
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                ) + fadeIn(animationSpec = tween(250)),
                exit = slideOutVertically(
                    targetOffsetY = { fullHeight -> fullHeight * 2 },
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(250)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .zIndex(100f),
            ) {
                transferProgressState.value?.let { progress ->
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .widthIn(min = 280.dp, max = 380.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = when {
                                        progress.isDone -> stringResource(
                                            when {
                                                progress.isRename || progress.isSavingOrder -> R.string.transfer_completed
                                                progress.isMove -> R.string.transfer_move_completed
                                                else -> R.string.transfer_copy_completed
                                            },
                                        )
                                        progress.isSavingOrder -> stringResource(R.string.content_order_saving)
                                        progress.isRename -> stringResource(
                                            R.string.content_order_renaming,
                                            progress.current,
                                            progress.total,
                                        )
                                        progress.isMove -> stringResource(
                                            R.string.moving_progress,
                                            progress.current,
                                            progress.total,
                                        )
                                        else -> stringResource(
                                            R.string.copying_progress,
                                            progress.current,
                                            progress.total,
                                        )
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = if (progress.isDone) stringResource(R.string.transfer_completed) else "${progress.percent}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { if (progress.isDone) 1f else progress.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                strokeCap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }
            // AI search: the curtain, over the photos (unless set behind them). Draws only, no touches.
            if (!uiState.settings.aiEffects.style(dev.ander.alexet.data.AiEffect.SEARCH_BACKGROUND).behind) {
                dev.ander.alexet.ui.components.aiglow.AiCurtainGlow(
                    config = uiState.settings.aiEffects,
                    active = searching && uiState.searchMode == SearchMode.SMART,
                    trigger = uiState.smartResultsVersion,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
    var lastViewer by remember { mutableStateOf<GalleryMedia?>(null) }
    var lastViewerList by remember { mutableStateOf<List<GalleryMedia>>(emptyList()) }
    if (viewer != null) {
        lastViewer = viewer
        val currentList = uiState.currentViewerList()
        if (currentList.isNotEmpty()) {
            lastViewerList = currentList
        }
    }
    AnimatedVisibility(
        visible = viewer != null && uiState.editingMedia == null && uiState.editingVideoList == null,
        enter = fadeIn(animationSpec = tween(190, easing = FastOutSlowInEasing)) +
            scaleIn(initialScale = 0.90f, animationSpec = tween(210, easing = FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(170, easing = FastOutSlowInEasing)) +
            scaleOut(targetScale = 0.90f, animationSpec = tween(170, easing = FastOutSlowInEasing)),
        modifier = Modifier.fillMaxSize().zIndex(150f),
    ) {
        val currentViewer = viewer ?: lastViewer
        if (currentViewer != null) {
            val pagerMedia = if (viewer != null) uiState.currentViewerList() else lastViewerList
            val effectivePagerMedia = if (pagerMedia.isNotEmpty()) pagerMedia else listOf(currentViewer)
            ViewerPane(
                media = effectivePagerMedia,
                selected = currentViewer,
                nextPlayerInstalled = uiState.nextPlayerInstalled,
                onSelect = { viewModel.selectMedia(it.id) },
                onClose = viewModel::closeViewer,
                onEdit = { media ->
                    if (media.isVideo) {
                        viewModel.openVideoEditor(listOf(media))
                    } else {
                        viewModel.openEditor(media)
                    }
                },
                onDelete = { media ->
                    viewerDeleteTarget = media
                    deleteFromViewer = true
                    if (uiState.inTrash) {
                        showDelete = true
                    } else if (viewModel.sessionSkipDeleteConfirm) {
                        viewModel.deleteMediaItem(media, skipTrash = viewModel.sessionSkipTrash) { sender ->
                            viewModel.requestDeleteConsent(sender)
                        }
                    } else {
                        showDelete = true
                    }
                },
                onPlayVideo = { viewModel.playInNextPlayer(context, it) },
                onShare = viewModel::share,
                onSetAs = { media -> setAsMediaId = media.id },
                autoplayGifs = uiState.settings.autoplayGifs,
                showNavArrows = uiState.settings.showNavArrows,
                allAlbums = uiState.allAlbums,
                allMedia = uiState.allCandidateMedia,
                onStripExif = { media -> viewModel.stripExif(listOf(media)) },
                playerConfig = uiState.settings.playerConfig,
                modifier = Modifier.fillMaxSize().blockTouchesBelow(),
            )
        }
    }
    var lastMediaToEdit by remember { mutableStateOf<GalleryMedia?>(null) }
    if (uiState.editingMedia != null) {
        lastMediaToEdit = uiState.editingMedia
    }
    AnimatedVisibility(
        visible = uiState.editingMedia != null,
        enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
            scaleIn(initialScale = 0.92f, animationSpec = tween(220, easing = FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
            scaleOut(targetScale = 0.92f, animationSpec = tween(180, easing = FastOutSlowInEasing)),
        modifier = Modifier.fillMaxSize().zIndex(200f),
    ) {
        val mediaToEdit = uiState.editingMedia ?: lastMediaToEdit
        if (mediaToEdit != null) {
            val isStickersSplit = wide && editorTab == PhotoEditorTab.STICKERS
            var draggingMedia by remember { mutableStateOf<GalleryMedia?>(null) }
            var stickerPickerFolderId by remember { mutableStateOf(AlbumIds.NONE) }
            var dragPosition by remember { mutableStateOf(Offset.Zero) }
            var rootContainerWidth by remember { mutableFloatStateOf(0f) }

            val editorWidthFraction by animateFloatAsState(
                targetValue = if (isStickersSplit) 0.5f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "editorWidthFraction",
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { rootContainerWidth = it.size.width.toFloat() }
                    .blockTouchesBelow(),
            ) {
            // Editor pane (right half when split, full width when not split)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(editorWidthFraction)
                    .align(Alignment.CenterEnd)
                    .clipToBounds()
                    .zIndex(1f),
            ) {
                // Fresh editor state per image (an incoming "Edit" must not inherit the previous one).
                key(mediaToEdit.id) {
                    PhotoEditorPane(
                        media = mediaToEdit,
                        albums = uiState.albums,
                        allAlbums = uiState.allAlbums,
                        hiddenAlbumIds = uiState.settings.hiddenAlbumIds,
                        favoriteMediaIds = remember(uiState.settings.favoriteMediaIds) { uiState.settings.favoriteMediaIds.toSet() },
                        availableMedia = uiState.media,
                        onRevealHidden = { reveal ->
                            if (reveal) viewModel.prepareHiddenPicker() else viewModel.releaseHiddenPicker()
                        },
                        wide = wide,
                        isSaving = uiState.isSavingEdit,
                        saveProgress = viewModel.editSaveProgress.collectAsStateWithLifecycle().value,
                        pendingStickerUri = pendingStickerUri,
                        onStickerConsumed = { pendingStickerUri = null },
                        currentTab = editorTab,
                        onTabChanged = { editorTab = it },
                        allowOverwrite = !editCopyOnly,
                        onDirtyChange = viewModel::setEditorDirty,
                        onSave = { editState, overwrite ->
                            viewModel.saveEditedImage(editState, overwrite) { sender ->
                                viewModel.requestDeleteConsent(sender)
                            }
                        },
                        onClose = {
                            editorTab = PhotoEditorTab.ASPECT
                            viewModel.closeEditor()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Left pane: Gallery for stickers on wide screen (animates cleanly from left)
            if (wide) {
                dev.ander.alexet.ui.components.AnimatedVisibility(
                    visible = isStickersSplit,
                    enter = slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    ),
                    exit = slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    ),
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .align(Alignment.CenterStart)
                        .zIndex(10f),
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            GalleryLeftPaneForEditor(
                                uiState = uiState,
                                // Its own folder: browsing stickers must not move the gallery's
                                // navigation (the viewer would later list this folder's files).
                                folderId = stickerPickerFolderId,
                                onAlbumClick = { id ->
                                    stickerPickerFolderId = id
                                    viewModel.loadFolderMediaFast(id)
                                },
                                onStartDragSticker = { media, startOffset ->
                                    draggingMedia = media
                                    dragPosition = startOffset
                                },
                                onDragSticker = { delta ->
                                    dragPosition += delta
                                },
                                onEndDragSticker = {
                                    if (dragPosition.x > rootContainerWidth * 0.5f) {
                                        pendingStickerUri = draggingMedia?.uri
                                    }
                                    draggingMedia = null
                                },
                                onCancelDragSticker = {
                                    draggingMedia = null
                                },
                                onBack = { stickerPickerFolderId = AlbumIds.NONE },
                                folderGridState = stickerFolderGridState,
                            )
                        }
                        VerticalDivider(
                            thickness = 2.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }

            // Floating drag preview
            val currentDragMedia = draggingMedia
            if (currentDragMedia != null) {
                Box(
                    modifier = Modifier
                        .offset {
                            val halfSize = 40.dp.roundToPx()
                            IntOffset(
                                (dragPosition.x - halfSize).roundToInt(),
                                (dragPosition.y - halfSize).roundToInt(),
                            )
                        }
                        .size(80.dp)
                        .shadow(12.dp, RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .zIndex(1000f),
                ) {
                    SquareThumbnail(
                        media = currentDragMedia,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
    }
    var lastVideoListToEdit by remember { mutableStateOf<List<GalleryMedia>?>(null) }
    if (uiState.editingVideoList != null) {
        lastVideoListToEdit = uiState.editingVideoList
    }
    AnimatedVisibility(
        visible = uiState.editingVideoList != null,
        enter = fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
            slideInVertically(initialOffsetY = { it / 4 }, animationSpec = tween(220, easing = FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
            slideOutVertically(targetOffsetY = { it / 4 }, animationSpec = tween(180, easing = FastOutSlowInEasing)),
        modifier = Modifier.fillMaxSize().zIndex(200f),
    ) {
        val videoListToEdit = uiState.editingVideoList ?: lastVideoListToEdit
        if (videoListToEdit != null) {
            dev.ander.alexet.ui.videoeditor.VideoEditorPane(
                initialMedia = videoListToEdit,
                availableMedia = uiState.media,
                isExporting = uiState.isExportingVideo,
                exportProgress = uiState.exportVideoProgress,
                onExport = { project, config ->
                    viewModel.exportVideo(project, config)
                },
                onClose = viewModel::closeVideoEditor,
                modifier = Modifier.fillMaxSize().blockTouchesBelow(),
                onCancelExport = viewModel::cancelVideoExport,
                albums = uiState.albums,
                allAlbums = uiState.allAlbums,
                hiddenAlbumIds = uiState.settings.hiddenAlbumIds,
                favoriteMediaIds = remember(uiState.settings.favoriteMediaIds) { uiState.settings.favoriteMediaIds.toSet() },
                onRevealHidden = { reveal ->
                    if (reveal) viewModel.prepareHiddenPicker() else viewModel.releaseHiddenPicker()
                },
            )
        }
    }
    // After a process restart the dialogs come back but the selection does not: a dialog left
    // with nothing to act on closes instead of offering to delete or rename zero files.
    val nothingSelected = uiState.selectionCount == 0 && uiState.selectedAlbums.isEmpty()
    LaunchedEffect(nothingSelected, showDelete, showRename, showInfo, viewerDeleteTarget) {
        if (!nothingSelected) return@LaunchedEffect
        if (showDelete && viewerDeleteTarget == null && !deleteFromViewer) showDelete = false
        if (showRename) showRename = false
        if (showInfo) showInfo = false
    }
    if (showDelete && deleteFromViewer && viewerDeleteTarget == null) {
        LaunchedEffect(Unit) {
            showDelete = false
            deleteFromViewer = false
        }
    } else if (showDelete) {
        val target = viewerDeleteTarget
        val isTrash = uiState.inTrash ||
            target?.bucketId == AlbumIds.TRASH ||
            target?.absolutePath?.contains(".alexet_trash", ignoreCase = true) == true ||
            target?.bucketName?.equals(".alexet_trash", ignoreCase = true) == true ||
            (target != null && uiState.trashedItems.any { it.id == target.id }) ||
            (target == null && uiState.selectedFiles.isNotEmpty() && uiState.selectedFiles.all {
                it.bucketId == AlbumIds.TRASH ||
                it.absolutePath?.contains(".alexet_trash", ignoreCase = true) == true ||
                it.bucketName.equals(".alexet_trash", ignoreCase = true) ||
                uiState.trashedItems.any { t -> t.id == it.id }
            })

        DeleteConfirmDialog(
            itemName = target?.displayName,
            itemSize = target?.sizeBytes,
            count = if (target != null) 1 else uiState.selectionCount,
            inTrash = isTrash,
            onConfirm = { dontAskAgain, skipTrash ->
                showDelete = false
                if (dontAskAgain && !isTrash) {
                    viewModel.sessionSkipDeleteConfirm = true
                    viewModel.sessionSkipTrash = skipTrash
                }
                if (target != null) {
                    viewModel.deleteMediaItem(target, skipTrash = skipTrash || isTrash) { sender ->
                        viewModel.requestDeleteConsent(sender)
                    }
                } else {
                    viewModel.deleteSelectionWithOption(skipTrash = skipTrash || isTrash) { sender ->
                        viewModel.requestDeleteConsent(sender)
                    }
                }
                viewerDeleteTarget = null
                deleteFromViewer = false
            },
            onDismiss = {
                showDelete = false
                viewerDeleteTarget = null
                deleteFromViewer = false
            },
        )
    }
    if (showRename) {
        RenameDialog(
            selectionCount = uiState.selectedFileIds.size,
            initialName = uiState.selectedFiles.singleOrNull()?.displayName,
            onConfirm = { request ->
                showRename = false
                viewModel.renameSelection(request) { sender ->
                    viewModel.requestWriteConsent(sender)
                }
            },
            onDismiss = { showRename = false },
        )
    }
    if (showRenameFolder) {
        val albumToRename = uiState.selectedAlbums.firstOrNull()
            ?: uiState.phoneFolder
        if (albumToRename != null) {
            RenameFolderDialog(
                currentName = albumToRename.name,
                onConfirm = { newName ->
                    showRenameFolder = false
                    viewModel.renameAlbum(albumToRename, newName)
                },
                onDismiss = { showRenameFolder = false },
            )
        } else {
            showRenameFolder = false
        }
    }
    if (showInfo) {
        MediaInfoSheet(
            media = uiState.selectedFiles,
            albums = uiState.selectedAlbums,
            albumsSizeBytes = viewModel::albumsSizeBytes,
            folderDetails = viewModel::folderDetails,
            onDismiss = { showInfo = false },
        )
    }
    if (showFolderInfo) {
        val folder = uiState.allAlbums.firstOrNull { it.id == uiState.sortTargetAlbumId }
        if (folder != null) {
            MediaInfoSheet(
                media = emptyList(),
                albums = listOf(folder),
                albumsSizeBytes = viewModel::albumsSizeBytes,
                folderDetails = viewModel::folderDetails,
                onDismiss = { showFolderInfo = false },
            )
        } else {
            showFolderInfo = false
        }
    }
    setAsMedia?.let { media ->
        SetAsDialog(
            media = media,
            onSetWallpaperHome = {
                showWallpaperCrop(WallpaperCropRequest(
                    media = media,
                    whichFlags = android.app.WallpaperManager.FLAG_SYSTEM,
                ))
            },
            onSetWallpaperLock = {
                showWallpaperCrop(WallpaperCropRequest(
                    media = media,
                    whichFlags = android.app.WallpaperManager.FLAG_LOCK,
                ))
            },
            onSetWallpaperBoth = {
                showWallpaperCrop(WallpaperCropRequest(
                    media = media,
                    whichFlags = android.app.WallpaperManager.FLAG_SYSTEM or
                        android.app.WallpaperManager.FLAG_LOCK,
                ))
            },
            onSetContact = {
                viewModel.openSetAsChooser(context, media)
            },
            onSetSystem = {
                viewModel.openSetAsChooser(context, media)
            },
            onDismiss = { setAsMediaId = null },
        )
    }
    wallpaperCropPending?.let { pending ->
        WallpaperCropScreen(
            modifier = Modifier.fillMaxSize().zIndex(400f),
            media = pending.media,
            whichFlags = pending.whichFlags,
            applying = applyingWallpaper,
            settings = uiState.settings,
            onSaveResolutions = viewModel::setCustomWallpaperResolutions,
            onConfirm = { cropResult ->
                applyingWallpaper = true
                viewModel.setAsWallpaperCropped(
                    context = context,
                    media = pending.media,
                    which = pending.whichFlags,
                    result = cropResult,
                    onDone = {
                        applyingWallpaper = false
                        showWallpaperCrop(null)
                    },
                )
            },
            onCancel = {
                if (!applyingWallpaper) {
                    showWallpaperCrop(null)
                }
            },
        )
    }
    albumPick?.let { mode ->
        AlbumPickerDialog(
            albums = uiState.albums,
            allAlbums = uiState.allAlbums,
            hiddenAlbumIds = uiState.settings.hiddenAlbumIds,
            showingHidden = uiState.showingHidden,
            showHiddenToggle = !uiState.settings.showHidden,
            onPick = { id ->
                val targetAlbum = uiState.allAlbums.firstOrNull { it.id == id }
                if (targetAlbum?.isSecure == true) {
                    android.widget.Toast.makeText(context, R.string.cannot_move_to_secure_folder, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    when (mode) {
                        AlbumPickMode.COPY -> viewModel.copySelectionToAlbum(id)
                        AlbumPickMode.MOVE -> viewModel.moveSelectionToAlbum(id) { sender ->
                            viewModel.requestWriteConsent(sender)
                        }
                    }
                }
                viewModel.releaseHiddenPicker()
                albumPick = null
            },
            onOtherFolder = {
                launchPicker(context) {
                    treeLauncher.launch(
                        DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents",
                            "primary:Pictures",
                        ),
                    )
                }
            },
            onRevealHidden = { reveal ->
                if (reveal) viewModel.prepareHiddenPicker() else viewModel.releaseHiddenPicker()
            },
            onDismiss = {
                viewModel.releaseHiddenPicker()
                albumPick = null
            },
        )
    }
    if (showSetPrivateDialog) {
        val selectedAlbum = uiState.selectedAlbums.firstOrNull()
        if (selectedAlbum != null) {
            SetPrivateFolderDialog(
                folderName = selectedAlbum.name,
                onConfirm = { password, hint, backupPassword ->
                    showSetPrivateDialog = false
                    viewModel.setFolderAsPrivate(selectedAlbum, password, hint, backupPassword)
                },
                onDismiss = { showSetPrivateDialog = false },
            )
        }
    }
    albumToDecrypt?.let { album ->
        DecryptFolderDialog(
            folderName = album.name,
            onConfirm = {
                albumToDecrypt = null
                viewModel.decryptFolderPermanently(album, context as? FragmentActivity)
            },
            onDismiss = { albumToDecrypt = null },
        )
    }
    AnimatedVisibility(
        visible = uiState.showSettings,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        ) + fadeIn(tween(220, easing = FastOutSlowInEasing)),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        ) + fadeOut(tween(180, easing = FastOutSlowInEasing)),
        modifier = Modifier.fillMaxSize().zIndex(300f),
    ) {
        val exportConfigAction = {
            launchPicker(context) {
                if (uiState.smartSearch.ready) exportZipLauncher.launch("alexet-config.zip")
                else exportLauncher.launch("alexet-config.json")
            }
        }
        val importConfigAction = { launchPicker(context) { importLauncher.launch(arrayOf("application/json", "application/zip", "text/*", "*/*")) } }
        val smartSearchUi = SmartSearchSettingsUi(
            status = smartStatusState.value,
            onEnable = viewModel::setSmartSearchEnabled,
            onImportModel = { launchPicker(context) { smartModelLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) } },
            onRemoveModel = viewModel::removeSmartModel,
            onClearIndex = viewModel::clearSmartIndex,
            loadLicenses = viewModel::smartLicenseText,
            onAiEffects = viewModel::setAiEffects,
            albums = uiState.albums,
            allAlbums = uiState.allAlbums,
            onIndexScope = viewModel::setSmartIndexScope,
            onIndexFolders = viewModel::changeSmartIndexFolders,
            onIndexPaused = viewModel::setSmartIndexPaused,
        )
        SettingsScreen(
            settings = uiState.settings,
            albums = uiState.albums,
            allAlbums = uiState.allAlbums,
            wide = wide,
            onChangeAnonymousFolders = viewModel::changeAnonymousFolders,
            onToggleAnonymousFolder = viewModel::toggleAnonymousFolder,
            onRandomizeFileNamesInPrivateFolders = viewModel::setRandomizeFileNamesInPrivateFolders,
            onStripExifInPrivateFolders = viewModel::setStripExifInPrivateFolders,
            onBack = viewModel::closeSettings,
            onContentViewMode = viewModel::setContentViewMode,
            onMultiFolderMemory = viewModel::setMultiFolderMemory,
            onPanelSplitPercent = viewModel::setPanelSplitPercent,
            onAutoFoldSplitEnabled = viewModel::setAutoFoldSplitEnabled,
            onFoldSplitAngleThreshold = viewModel::setFoldSplitAngleThreshold,
            onShowFoldableSettings = viewModel::setShowFoldableSettings,
            smartSearch = smartSearchUi,
            onExpandGrid = viewModel::setExpandGrid,
            onShowFileNames = viewModel::setShowFileNames,
            onOperationsFollowSelectionOrder = viewModel::setOperationsFollowSelectionOrder,
            onVideoDurationFormat = viewModel::setVideoDurationFormat,
            onGridColumns = viewModel::setGridColumns,
            onFileColumns = viewModel::setFileColumns,
            onShowAllFiles = viewModel::setShowAllFiles,
            onShowOtherFileFolders = viewModel::setShowOtherFileFolders,
            onFoldableFileColumns = viewModel::setFoldableFileColumns,
            onFileSelectMode = viewModel::setFileSelectMode,
            onShowHidden = viewModel::setShowHidden,
            onHideFavoritesFolder = viewModel::setHideFavoritesFolder,
            onSaveSelectionActionsConfig = viewModel::setSelectionActionsConfig,
            onToggleSelectionActionVisibility = viewModel::toggleSelectionActionVisibility,
            onSearchFilesFullScreen = viewModel::setSearchFilesFullScreen,
            onVideoDoubleClickToGallery = viewModel::setVideoDoubleClickToGallery,
            onRememberFolderScrollAcrossSessions = viewModel::setRememberFolderScrollAcrossSessions,
            onNewContentScrollsToTop = viewModel::setNewContentScrollsToTop,
            onAutoplayGifs = viewModel::setAutoplayGifs,
            onNewpipeFolder = viewModel::setNewpipeFolder,
            onShowNavArrows = viewModel::setShowNavArrows,
            onStripExifOnShare = viewModel::setStripExifOnShare,
            onSaveWallpaperResolutions = viewModel::setCustomWallpaperResolutions,
            onExportConfig = exportConfigAction,
            onDebugLoggingChange = viewModel::setDebugLogging,
            onClearDebugLog = viewModel::clearDebugLog,
            onImportConfig = importConfigAction,
            onResetThumbnails = viewModel::rescanLibrary,
            onScrollerOnRight = viewModel::setScrollerOnRight,
            onScrollerEnabled = viewModel::setScrollerEnabled,
            onScrollerSmooth = viewModel::setScrollerSmooth,
            onHighResThumbnails = viewModel::setHighResThumbnails,
            onLightMode = viewModel::setLightMode,
            onUpdatePlayerConfig = viewModel::updatePlayerConfig,
            onUpdateHiddenCover = viewModel::updateHiddenCover,
            onPickHiddenCoverImage = { viewModel.setHiddenCoverImage(context, it) },
        )
    }
    AnimatedVisibility(
        visible = !startupRevealed || coldStarting,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(280, easing = FastOutSlowInEasing)),
        modifier = Modifier.fillMaxSize().zIndex(1000f),
    ) {
        AlexetColdStartLoader()
    }
    }
    }
}

private sealed interface FolderLayerState {
    data object Search : FolderLayerState
    data class FolderContent(val folderId: Long) : FolderLayerState
    data object Grid : FolderLayerState
}

@Composable
private fun GalleryBody(
    uiState: GalleryUiState,
    wide: Boolean,
    isBook: Boolean = false,
    foldSplitTarget: Float = 0.5f,
    folderGridState: LazyGridState,
    onAlbumBounds: (Long, Rect) -> Unit,
    onRightPaneBounds: (Rect) -> Unit,
    onFolderClick: (Long) -> Unit,
    onStamp: (Long) -> Unit,
    onFolderDragStart: (Long) -> Unit,
    onFolderDrag: (Offset) -> Unit,
    onFolderDragEnd: () -> Unit,
    onOpenMedia: (GalleryMedia) -> Unit,
    onPlayVideo: (GalleryMedia) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onMarqueeFiles: (List<Long>) -> Unit,
    onToggleAlbum: (Long) -> Unit,
    onMarqueeAlbums: (List<Long>) -> Unit,
    onClosePane: (Long) -> Unit,
    onHitboxSelect: (Int) -> Unit,
    onTwoFingerStart: (Long, Offset) -> Unit,
    onTwoFingerDrag: (Offset) -> Unit,
    onTwoFingerEnd: () -> Unit,
    onSplitDragEnd: (Float, Float, Float) -> Unit,
    onSwapStackedPanes: () -> Unit,
    onGetFolderScroll: (Long) -> FolderScrollPos? = { null },
    onSaveFolderScroll: (Long, Int, Int) -> Unit = { _, _, _ -> },
    onGetFolderGridState: (Long) -> LazyGridState? = { null },
    onContentOrderMove: (Int, Int) -> Unit = { _, _ -> },
    onDecryptFolder: (Album) -> Unit = {},
    onTransientCountChange: (Int?) -> Unit = {},
) {
    val searchingFiles = uiState.searchMode.searchesFiles && uiState.query.isNotBlank() && uiState.phoneFolderId == AlbumIds.NONE
    val expand = wide && uiState.settings.contentViewMode == ContentViewMode.EXPAND_FOLDERS
    val phoneOverlay = uiState.phoneFolderId != AlbumIds.NONE && (!wide || expand)
    val leftBrowse = wide &&
        !expand &&
        uiState.settings.contentViewMode == ContentViewMode.MULTI_FOLDER &&
        uiState.phoneFolderId != AlbumIds.NONE
    val searchingFull = searchingFiles && uiState.settings.searchFilesFullScreen
    val twoFingerMove = wide && uiState.settings.contentViewMode.usesFolderDrag()
    val columns = when {
        expand -> uiState.settings.expandColumns
        else -> uiState.settings.gridColumns
    }
    val visibleRows = when {
        expand -> uiState.settings.expandRows
        wide -> 4
        else -> 5
    }
    val folderDrag = twoFingerMove && !uiState.reorderingFolders
    val albumsById = remember(uiState.allAlbums, uiState.trashAlbum, uiState.favoritesAlbum) {
        (uiState.allAlbums + listOf(uiState.trashAlbum, uiState.favoritesAlbum))
            .distinctBy { it.id }
            .associateBy { it.id }
    }

    val currentLayerState: FolderLayerState = when {
        searchingFiles -> FolderLayerState.Search
        phoneOverlay || leftBrowse -> FolderLayerState.FolderContent(uiState.phoneFolderId)
        else -> FolderLayerState.Grid
    }

    val folderLayer: @Composable (Modifier) -> Unit = { modifier ->
        AnimatedContent(
            targetState = currentLayerState,
            transitionSpec = {
                if (initialState is FolderLayerState.Grid && targetState is FolderLayerState.FolderContent) {
                    (slideInHorizontally(initialOffsetX = { it / 4 }, animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                        fadeIn(animationSpec = tween(200)))
                        .togetherWith(
                            slideOutHorizontally(targetOffsetX = { -it / 6 }, animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                            fadeOut(animationSpec = tween(160)) +
                            scaleOut(targetScale = 0.95f, animationSpec = tween(220, easing = FastOutSlowInEasing))
                        )
                } else if (initialState is FolderLayerState.FolderContent && targetState is FolderLayerState.Grid) {
                    (slideInHorizontally(initialOffsetX = { -it / 6 }, animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                        fadeIn(animationSpec = tween(200)) +
                        scaleIn(initialScale = 0.95f, animationSpec = tween(240, easing = FastOutSlowInEasing)))
                        .togetherWith(
                            slideOutHorizontally(targetOffsetX = { it / 4 }, animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                            fadeOut(animationSpec = tween(160))
                        )
                } else {
                    (fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.98f, animationSpec = tween(200)))
                        .togetherWith(fadeOut(animationSpec = tween(160)))
                }
            },
            label = "folderLayerTransition",
            modifier = modifier,
        ) { layerState ->
            when (layerState) {
                is FolderLayerState.Search -> Box(Modifier.fillMaxSize()) {
                val smartResults = uiState.searchMode == SearchMode.SMART
                MediaTiles(
                    media = uiState.searchFiles,
                    columns = if (wide) (uiState.settings.gridColumns + 1).coerceAtMost(30) else uiState.settings.fileColumns,
                    selectedIds = uiState.selectedFileIdsOrdered,
                    dimmed = uiState.dragKind == DragKind.FILES &&
                        uiState.filesDragSourceAlbumId == AlbumIds.NONE,
                    showFileNames = uiState.settings.showFileNames,
                    hiddenIds = uiState.settings.hiddenMediaIds,
                    onOpen = onOpenMedia,
                    onPlayVideo = onPlayVideo,
                    onToggleSelect = onToggleSelect,
                    onMarqueeSelect = onMarqueeFiles,
                    onTransientCountChange = onTransientCountChange,
                    twoFingerDragEnabled = false,
                    onTwoFingerStart = {},
                    onTwoFingerDrag = {},
                    onTwoFingerEnd = {},
                    linearMarquee = uiState.settings.fileSelectMode == FileSelectMode.LINEAR,
                    videoDoubleClickToGallery = uiState.settings.videoDoubleClickToGallery,
                    isLoading = uiState.isLoading,
                    scrollerOnRight = uiState.settings.scrollerOnRight,
                    scrollerEnabled = uiState.settings.scrollerEnabled,
                    scrollerSmooth = uiState.settings.scrollerSmooth,
                    // While AI is still searching there is nothing to say yet: its own animation shows.
                    emptyMessage = if (smartResults && uiState.smartSearching) "" else stringResource(R.string.no_media),
                    gridUnderlay = if (smartResults) ({ state -> AiResultsLayer(uiState, state, behind = true) }) else null,
                    gridOverlay = if (smartResults) ({ state -> AiResultsLayer(uiState, state, behind = false) }) else null,
                    modifier = Modifier.fillMaxSize(),
                )
                if (smartResults) {
                    AiSearchResultsEffects(uiState)
                }
                }
                is FolderLayerState.FolderContent -> {
                    val folderId = layerState.folderId
                    val currentAlbum = albumsById[folderId]
                    val isSecure = currentAlbum?.isSecure == true
                    key(folderId) {
                        // AI search inside this folder: the same effects as on the whole gallery's results.
                        val smartHere = uiState.searchMode == SearchMode.SMART && uiState.query.isNotBlank() && folderId == uiState.phoneFolderId
                        Box(Modifier.fillMaxSize()) {
                        MediaTiles(
                            media = if (uiState.contentOrderMode && uiState.contentOrderFolderId == folderId) {
                                uiState.contentOrderFiles
                            } else {
                                uiState.visibleFilesIn(folderId)
                            },
                            columns = if (wide) uiState.settings.foldableFileColumns else uiState.settings.fileColumns,
                            scrollLabelField = uiState.scrollLabelField(folderId),
                            selectedIds = uiState.selectedFileIdsOrdered,
                            dimmed = uiState.dragKind == DragKind.FILES &&
                                uiState.filesDragSourceAlbumId == folderId,
                            showFileNames = uiState.settings.showFileNames,
                            hiddenIds = uiState.settings.hiddenMediaIds,
                            onOpen = onOpenMedia,
                            onPlayVideo = onPlayVideo,
                            onToggleSelect = onToggleSelect,
                            onMarqueeSelect = onMarqueeFiles,
                            onTransientCountChange = onTransientCountChange,
                            twoFingerDragEnabled = false,
                            onTwoFingerStart = {},
                            onTwoFingerDrag = {},
                            onTwoFingerEnd = {},
                            linearMarquee = uiState.settings.fileSelectMode == FileSelectMode.LINEAR,
                            videoDoubleClickToGallery = uiState.settings.videoDoubleClickToGallery,
                            albumId = folderId,
                            gridState = onGetFolderGridState(folderId),
                            initialScrollPos = onGetFolderScroll(folderId),
                            onScrollPosChanged = { idx, off -> onSaveFolderScroll(folderId, idx, off) },
                            emptyMessage = when {
                                // AI is still searching: its own animation shows, not "no files".
                                smartHere && uiState.smartSearching -> ""
                                folderId == AlbumIds.FAVORITES -> stringResource(R.string.favorites_empty)
                                folderId == AlbumIds.TRASH -> stringResource(R.string.trash_empty)
                                else -> stringResource(R.string.no_media)
                            },
                            gridUnderlay = if (smartHere) ({ state -> AiResultsLayer(uiState, state, behind = true) }) else null,
                            gridOverlay = if (smartHere) ({ state -> AiResultsLayer(uiState, state, behind = false) }) else null,
                            isLoading = uiState.isFolderLoading(folderId),
                            contentOrderMode = uiState.contentOrderMode && uiState.contentOrderFolderId == folderId,
                            onContentOrderMove = onContentOrderMove,
                            bottomContentPadding = if (uiState.contentOrderMode && uiState.contentOrderFolderId == folderId) 88.dp else 0.dp,
                            scrollerOnRight = uiState.settings.scrollerOnRight,
                            scrollerEnabled = uiState.settings.scrollerEnabled,
                            scrollerSmooth = uiState.settings.scrollerSmooth,
                            isSecureFolder = isSecure,
                            onDecryptFolderPermanently = if (currentAlbum?.isSecure == true) { { onDecryptFolder(currentAlbum) } } else null,
                            forceScrollToTopOnNewContent = uiState.settings.newContentScrollsToTop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (smartHere) AiSearchResultsEffects(uiState)
                        }
                    }
                }
                is FolderLayerState.Grid -> FolderGrid(
                    albums = uiState.visibleAlbums,
                    columns = columns,
                    visibleRows = visibleRows,
                    hoveredAlbumId = uiState.hoveredAlbumId,
                    stampedAlbumIds = uiState.stampedAlbumIds,
                    selectedCount = uiState.selectedFileIds.size,
                    selectedAlbumIds = uiState.selectedAlbumIds,
                    folderDragEnabled = folderDrag,
                    dimmed = uiState.dragKind == DragKind.FOLDER,
                    hiddenAlbumIds = remember(uiState.settings.hiddenAlbumIds, uiState.albums) {
                        uiState.settings.hiddenAlbumIds + uiState.albums.filter { it.isHidden }.map { it.id }
                    },
                    anonymousAlbumIds = uiState.settings.anonymousFolderIds,
                    highlightedAlbumId = uiState.highlightedFolderId,
                    onAlbumClick = onFolderClick,
                    onStamp = onStamp,
                    onToggleAlbum = onToggleAlbum,
                    onMarqueeSelect = onMarqueeAlbums,
                    onTransientCountChange = onTransientCountChange,
                    onFolderDragStart = onFolderDragStart,
                    onFolderDrag = onFolderDrag,
                    onFolderDragEnd = onFolderDragEnd,
                    onAlbumBounds = onAlbumBounds,
                    gridState = folderGridState,
                    isLoading = uiState.isLoading,
                    downloadingAlbumIds = uiState.downloadingAlbumIds,
                    deletingFolders = uiState.deletingFolders,
                    onCancelFolderDelete = dev.ander.alexet.data.FolderDeleteQueue::cancel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (!wide || expand || phoneOverlay || searchingFull) {
        folderLayer(Modifier.fillMaxSize())
        return
    }

    // Smooth custom% → 50/50 (was snap() — skipped mid frames while the window resized).
    val leftWeight by animateFloatAsState(
        targetValue = foldSplitTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "leftWeight",
    )

    Row(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.weight(leftWeight).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            folderLayer(Modifier.fillMaxSize())
        }
        VerticalDivider(
            thickness = 3.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        Surface(
            modifier = Modifier.weight(1f - leftWeight).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            ContentHost(
                uiState = uiState,
                albumsById = albumsById,
                onOpenMedia = onOpenMedia,
                onPlayVideo = onPlayVideo,
                onToggleSelect = onToggleSelect,
                onDecryptFolder = onDecryptFolder,
                onMarqueeFiles = onMarqueeFiles,
                onClosePane = onClosePane,
                onHitboxSelect = onHitboxSelect,
                onTwoFingerStart = onTwoFingerStart,
                onTwoFingerDrag = onTwoFingerDrag,
                onTwoFingerEnd = onTwoFingerEnd,
                onPaneBounds = onRightPaneBounds,
                onSplitDragEnd = onSplitDragEnd,
                onSwapStackedPanes = onSwapStackedPanes,
                onGetFolderScroll = onGetFolderScroll,
                onSaveFolderScroll = onSaveFolderScroll,
                onGetFolderGridState = onGetFolderGridState,
                onContentOrderMove = onContentOrderMove,
                onTransientCountChange = onTransientCountChange,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun Modifier.threeFingerSwipeDown(onRefresh: () -> Unit): Modifier = pointerInput(onRefresh) {
    awaitEachGesture {
        var tracking = false
        var startY = 0f
        var triggered = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val activePointers = event.changes.filter { it.pressed }
            if (activePointers.size >= 3) {
                val currentY = activePointers.map { it.position.y }.average().toFloat()
                if (!tracking) {
                    tracking = true
                    startY = currentY
                    triggered = false
                } else if (!triggered && (currentY - startY) > 70.dp.toPx()) {
                    triggered = true
                    onRefresh()
                    activePointers.forEach { it.consume() }
                }
            } else if (activePointers.isEmpty()) {
                tracking = false
                triggered = false
                break
            }
        }
    }
}

@Composable
private fun DragFloatingPreview(
    dragKind: DragKind,
    draggingFolderId: Long,
    albums: List<Album>,
    selectedCount: Int,
    pointerWindowProvider: () -> Offset,
    rootInWindowProvider: () -> Offset,
) {
    if (dragKind == DragKind.NONE) return
    val folderDrag = dragKind == DragKind.FOLDER
    val draggingAlbum = albums.firstOrNull { it.id == draggingFolderId }
    val previewSize = if (folderDrag) 112.dp else 96.dp
    val previewShape = RoundedCornerShape(if (folderDrag) 4.dp else 28.dp)
    val density = LocalDensity.current
    val halfPx = with(density) { previewSize.toPx() / 2f }

    Surface(
        modifier = Modifier
            .offset {
                val pointer = pointerWindowProvider()
                if (!pointer.isSpecified) return@offset IntOffset.Zero
                val rootInWindow = rootInWindowProvider()
                val local = pointer - rootInWindow
                IntOffset(
                    (local.x - halfPx).roundToInt(),
                    (local.y - halfPx).roundToInt(),
                )
            }
            .size(previewSize),
        shape = previewShape,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = if (folderDrag) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
    ) {
        if (folderDrag && draggingAlbum != null) {
            FolderCover(
                album = draggingAlbum,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.selected_count,
                        selectedCount,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
private fun GalleryLeftPaneForEditor(
    uiState: GalleryUiState,
    folderId: Long,
    onAlbumClick: (Long) -> Unit,
    onStartDragSticker: (GalleryMedia, Offset) -> Unit,
    onDragSticker: (Offset) -> Unit,
    onEndDragSticker: () -> Unit,
    onCancelDragSticker: () -> Unit,
    onBack: () -> Unit,
    folderGridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val currentAlbum = remember(folderId, uiState.allAlbums) {
        uiState.allAlbums.find { it.id == folderId }
    }
    val inFolder = folderId != AlbumIds.NONE

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inFolder) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.navigate_up),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            // Explicit colours: inside the editor the inherited content colour is a dark one.
            Text(
                text = if (inFolder) (currentAlbum?.name ?: "") else stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        // Body
        Box(Modifier.fillMaxSize()) {
            if (inFolder) {
                val files = uiState.visibleFilesIn(folderId).distinctBy { it.id }
                if (files.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.no_media),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(files, key = { it.id }) { media ->
                            var itemBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .onGloballyPositioned { coords ->
                                        itemBoundsInRoot = coords.boundsInRoot()
                                    }
                                    .pointerInput(media.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { localOffset ->
                                                onStartDragSticker(media, itemBoundsInRoot.topLeft + localOffset)
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                onDragSticker(dragAmount)
                                            },
                                            onDragEnd = {
                                                onEndDragSticker()
                                            },
                                            onDragCancel = {
                                                onCancelDragSticker()
                                            },
                                        )
                                    },
                            ) {
                                SquareThumbnail(
                                    media = media,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            } else {
                FolderGrid(
                    albums = uiState.visibleAlbums,
                    columns = 3,
                    visibleRows = 4,
                    hoveredAlbumId = AlbumIds.NONE,
                    stampedAlbumIds = emptySet(),
                    selectedCount = 0,
                    selectedAlbumIds = emptySet(),
                    selectionEnabled = false,
                    folderDragEnabled = false,
                    dimmed = false,
                    hiddenAlbumIds = uiState.settings.hiddenAlbumIds,
                    anonymousAlbumIds = uiState.settings.anonymousFolderIds,
                    highlightedAlbumId = AlbumIds.NONE,
                    onAlbumClick = onAlbumClick,
                    onStamp = {},
                    onToggleAlbum = {},
                    onMarqueeSelect = {},
                    onFolderDragStart = {},
                    onFolderDrag = {},
                    onFolderDragEnd = {},
                    onAlbumBounds = { _, _ -> },
                    gridState = folderGridState,
                    isLoading = uiState.isLoading,
                    downloadingAlbumIds = uiState.downloadingAlbumIds,
                    deletingFolders = uiState.deletingFolders,
                    onCancelFolderDelete = dev.ander.alexet.data.FolderDeleteQueue::cancel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}


@Composable
private fun ContentOrderBottomBar(
    renameNumeric: Boolean,
    isAnonymous: Boolean,
    onRenameNumericChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Button(
            onClick = onSave,
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.extraLarge,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.save),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box {
            androidx.compose.material3.FilledTonalIconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.more_options),
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.content_order_rename_numeric)) },
                    onClick = {
                        menuOpen = false
                        if (!isAnonymous) onRenameNumericChange(true)
                    },
                    enabled = !isAnonymous,
                    leadingIcon = {
                        if (renameNumeric && !isAnonymous) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                        }
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.content_order_keep_names)) },
                    onClick = {
                        menuOpen = false
                        onRenameNumericChange(false)
                    },
                    leadingIcon = {
                        if (!renameNumeric || isAnonymous) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                        }
                    },
                )
                if (isAnonymous) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.content_order_anonymous_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        onClick = { menuOpen = false },
                        enabled = false,
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AlexetColdStartLoader() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Same black as the system splash and FolderGrid, so the hand-over has no grey flash.
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        // ContainedLoadingIndicator was invisible on black/dynamic dark — morphing LoadingIndicator instead.
        LoadingIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(80.dp),
        )
    }
}

/**
 * Opens a system file/folder picker. Some devices (TVs, stripped builds) have none, and the
 * launch then throws instead of doing nothing.
 */
private fun launchPicker(context: android.content.Context, launch: () -> Unit) {
    try {
        launch()
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, R.string.no_file_picker, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * Takes every touch that no control inside took: a full-screen layer (editor, viewer) passed the
 * touches on its empty spots to the folder below, and a tap beside a button opened a file there.
 */
private fun Modifier.blockTouchesBelow(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/**
 * AI effects on the results grid, under it ([behind]) or over it: the edge glow when results
 * arrive (on its chosen layer) and the Nexus lights in the gaps between the tiles.
 */
@Composable
private fun BoxScope.AiResultsLayer(uiState: GalleryUiState, state: androidx.compose.foundation.lazy.grid.LazyGridState, behind: Boolean) {
    val config = uiState.settings.aiEffects
    if (config.style(dev.ander.alexet.data.AiEffect.RESULTS_EDGE).behind == behind) {
        dev.ander.alexet.ui.components.aiglow.AiEdgeGlow(config, uiState.smartResultsVersion, Modifier.matchParentSize())
    }
    if (!behind) {
        // MediaTiles' grid: 8 dp between tiles and 8 dp padding around them.
        val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
        dev.ander.alexet.ui.components.aiglow.AiNexus(
            config = config,
            active = !uiState.smartSearching && uiState.smartResultIds.isNotEmpty(),
            grid = { nexusGridOf(state, gap, startPadding = gap) },
            trigger = uiState.smartResultsVersion,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * The visible tiles of [state] for the Nexus lights, in the grid container's coordinates: item
 * offsets leave out the content padding ([startPadding] across, viewportStartOffset down).
 */
private fun nexusGridOf(
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    gap: Float,
    startPadding: Float,
): dev.ander.alexet.ui.components.aiglow.NexusGrid? {
    val info = state.layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return null
    val dx = startPadding
    val dy = -info.viewportStartOffset.toFloat()
    val first = items.first()
    val pitch = first.size.height + gap
    return dev.ander.alexet.ui.components.aiglow.NexusGrid.of(
        cells = items.map {
            dev.ander.alexet.ui.components.aiglow.NexusCell(
                rect = androidx.compose.ui.geometry.Rect(
                    it.offset.x + dx,
                    it.offset.y + dy,
                    it.offset.x + it.size.width + dx,
                    it.offset.y + it.size.height + dy,
                ),
                row = it.row,
                column = it.column,
            )
        },
        gap = gap,
        scrollY = first.row * pitch - (first.offset.y + dy),
        endsHere = items.last().index == info.totalItemsCount - 1,
    )
}

/** The loading animation in the middle of the AI search results when a search takes a moment. */
@Composable
private fun BoxScope.AiSearchResultsEffects(uiState: GalleryUiState) {
    val config = uiState.settings.aiEffects
    var showLoader by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.smartSearching) {
        if (uiState.smartSearching) {
            kotlinx.coroutines.delay(AI_LOADER_DELAY_MS)
            showLoader = true
        } else {
            showLoader = false
        }
    }
    AnimatedVisibility(
        visible = showLoader,
        enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.85f),
        exit = fadeOut(tween(200)),
        modifier = Modifier.align(Alignment.Center),
    ) {
        dev.ander.alexet.ui.components.aiglow.AiLoader(config)
    }
}

/** A search answered quicker than this shows no loading animation. */
private const val AI_LOADER_DELAY_MS = 250L
