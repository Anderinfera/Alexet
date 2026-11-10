package dev.ander.alexet.ui.gallery

import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import dev.ander.alexet.data.SmartIndexScope
import androidx.compose.runtime.mutableIntStateOf
import dev.ander.alexet.data.AiEffect
import dev.ander.alexet.data.AiEffectsPreset
import dev.ander.alexet.ui.components.aiglow.aiColors
import dev.ander.alexet.ui.components.aiglow.aiGlowHalo
import dev.ander.alexet.ui.components.aiglow.rememberAiPhase
import dev.ander.alexet.ui.components.aiglow.rememberTimedGlow
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import dev.ander.alexet.data.smartsearch.SmartSearch
import androidx.activity.compose.BackHandler
import dev.ander.alexet.ui.components.LocalLightMode
import dev.ander.alexet.ui.components.AnimatedContent
import dev.ander.alexet.ui.components.AnimatedVisibility
import dev.ander.alexet.ui.components.Crossfade
import dev.ander.alexet.ui.components.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import dev.ander.alexet.ui.components.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import dev.ander.alexet.foldable.rememberFoldableState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.ander.alexet.BuildConfig
import dev.ander.alexet.R
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.AppSettings
import dev.ander.alexet.data.SponsorBlockCategory
import dev.ander.alexet.data.ContentViewMode
import dev.ander.alexet.data.FileSelectMode
import dev.ander.alexet.data.MultiFolderMemory
import dev.ander.alexet.data.SelectionAction
import dev.ander.alexet.data.VideoDurationFormat
import dev.ander.alexet.ui.wallpaper.WallpaperScreenResolutionsDialog
import kotlinx.coroutines.launch

enum class SettingsCategory(
    val titleRes: Int,
    val subtitleRes: Int,
    val icon: ImageVector,
) {
    VIEW(
        titleRes = R.string.settings_category_view,
        subtitleRes = R.string.settings_category_view_sub,
        icon = Icons.Rounded.GridView,
    ),
    FOLDERS(
        titleRes = R.string.settings_category_folders,
        subtitleRes = R.string.settings_category_folders_sub,
        icon = Icons.Rounded.Folder,
    ),
    SELECTION(
        titleRes = R.string.settings_category_selection,
        subtitleRes = R.string.settings_category_selection_sub,
        icon = Icons.Rounded.SelectAll,
    ),
    VIEWING(
        titleRes = R.string.settings_category_player,
        subtitleRes = R.string.settings_category_player_sub,
        icon = Icons.Rounded.PlayCircle,
    ),
    SEARCH(
        titleRes = R.string.settings_category_extra,
        subtitleRes = R.string.settings_category_extra_sub,
        icon = Icons.Rounded.ImageSearch,
    ),
    PRIVACY(
        titleRes = R.string.settings_category_privacy,
        subtitleRes = R.string.settings_category_privacy_sub,
        icon = Icons.Rounded.Security,
    ),
    APP(
        titleRes = R.string.settings_category_system,
        subtitleRes = R.string.settings_category_system_sub,
        icon = Icons.Rounded.Tune,
    ),
}

/** The card's subtitle; View names no panels or wallpapers while those options are hidden. */
private fun SettingsCategory.subtitleFor(settings: AppSettings): Int = when {
    this != SettingsCategory.VIEW -> subtitleRes
    settings.foldOnlySettingsShown -> subtitleRes
    settings.extraViewSettingsShown -> R.string.settings_category_view_sub_panels
    else -> R.string.settings_category_view_sub_phone
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    albums: List<Album> = emptyList(),
    allAlbums: List<Album> = emptyList(),
    wide: Boolean = false,
    onChangeAnonymousFolders: (added: Set<Long>, removed: Set<Long>) -> Unit = { _, _ -> },
    onToggleAnonymousFolder: (Long) -> Unit = {},
    onRandomizeFileNamesInPrivateFolders: (Boolean) -> Unit = {},
    onStripExifInPrivateFolders: (Boolean) -> Unit = {},
    onBack: () -> Unit,
    onContentViewMode: (ContentViewMode) -> Unit,
    onMultiFolderMemory: (MultiFolderMemory) -> Unit,
    onPanelSplitPercent: (Int) -> Unit,
    onExpandGrid: (columns: Int?, rows: Int?) -> Unit,
    onShowFileNames: (Boolean) -> Unit,
    onOperationsFollowSelectionOrder: (Boolean) -> Unit = {},
    onVideoDurationFormat: (VideoDurationFormat) -> Unit = {},
    onGridColumns: (Int) -> Unit = {},
    onFileColumns: (Int) -> Unit = {},
    onShowAllFiles: (Boolean) -> Unit = {},
    onShowOtherFileFolders: (Boolean) -> Unit = {},
    onFoldableFileColumns: (Int) -> Unit = {},
    onFileSelectMode: (FileSelectMode) -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onHideFavoritesFolder: (Boolean) -> Unit,
    onSaveSelectionActionsConfig: (List<SelectionAction?>, List<SelectionAction>, Set<SelectionAction>) -> Unit,
    onToggleSelectionActionVisibility: (SelectionAction) -> Unit,
    onSearchFilesFullScreen: (Boolean) -> Unit,
    onVideoDoubleClickToGallery: (Boolean) -> Unit,
    onRememberFolderScrollAcrossSessions: (Boolean) -> Unit = {},
    onNewContentScrollsToTop: (Boolean) -> Unit = {},
    onAutoplayGifs: (Boolean) -> Unit = {},
    onNewpipeFolder: (String) -> Unit = {},
    onShowNavArrows: (Boolean) -> Unit = {},
    onStripExifOnShare: (Boolean) -> Unit = {},
    onSaveWallpaperResolutions: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    onExportConfig: () -> Unit,
    onDebugLoggingChange: (Boolean) -> Unit = {},
    onClearDebugLog: () -> Unit = {},
    onImportConfig: () -> Unit,
    onResetThumbnails: () -> Unit,
    onScrollerOnRight: (Boolean) -> Unit = {},
    onScrollerEnabled: (Boolean) -> Unit = {},
    onScrollerSmooth: (Boolean) -> Unit = {},
    onHighResThumbnails: (Boolean) -> Unit = {},
    onLightMode: (Boolean) -> Unit = {},
    onAutoFoldSplitEnabled: (Boolean) -> Unit = {},
    onDynamicSplit: (Boolean) -> Unit = {},
    onFoldSplitAngleThreshold: (Float) -> Unit = {},
    onShowFoldableSettings: (Boolean) -> Unit = {},
    smartSearch: SmartSearchSettingsUi? = null,
    onUpdatePlayerConfig: ((dev.ander.alexet.integration.AnderLineConfig) -> dev.ander.alexet.integration.AnderLineConfig) -> Unit = {},
    onUpdateHiddenCover: ((dev.ander.alexet.data.HiddenCoverConfig) -> dev.ander.alexet.data.HiddenCoverConfig) -> Unit = {},
    onPickHiddenCoverImage: (android.net.Uri) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    var folderHelpTopic by rememberSaveable { mutableStateOf<FolderHelpTopic?>(null) }
    var showPaneSplitHelp by rememberSaveable { mutableStateOf(false) }
    var showViewHelp by rememberSaveable { mutableStateOf(false) }
    var showSelectHelp by rememberSaveable { mutableStateOf(false) }
    var showBehaviorHelp by rememberSaveable { mutableStateOf(false) }
    var showSelectionHelp by rememberSaveable { mutableStateOf(false) }
    var showSearchHelp by rememberSaveable { mutableStateOf(false) }
    var showActionCardsHelp by rememberSaveable { mutableStateOf(false) }
    var showPrivacyHelp by rememberSaveable { mutableStateOf(false) }
    var showPrivatizedFoldersDialog by rememberSaveable { mutableStateOf(false) }
    var showNewpipeFolderDialog by rememberSaveable { mutableStateOf(false) }
    var showWallpaperResolutionsDialog by rememberSaveable { mutableStateOf(false) }
    var showConfigHelp by rememberSaveable { mutableStateOf(false) }
    var showMaintenanceHelp by rememberSaveable { mutableStateOf(false) }
    var isDraggingActionCard by remember { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.VIEW) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Kept here, not in the layouts: folding or unfolding swaps the layout, and the open category
    // and the scroll positions must survive that.
    val categoryListScrollState = rememberScrollState()
    val categoryScrollStates = remember { SettingsCategory.values().associateWith { ScrollState(0) } }
    val phoneOpenCategory = rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    val foldable = rememberFoldableState()
    // A tapped search result: the option it names scrolls into view and pulses.
    val searchJump = remember { SettingsJumpController() }

    CompositionLocalProvider(LocalSettingsJump provides searchJump) {
        if (wide) {
            MasterDetailSettingsLayout(
                settings = settings,
                albums = albums,
                allAlbums = allAlbums,
                foldable = foldable,
                selectedCategory = selectedCategory,
                onSelectCategory = {
                    selectedCategory = it
                    // Folded again later: the phone shows the category picked here, not an older one.
                    if (phoneOpenCategory.value != null) phoneOpenCategory.value = it
                },
                listScrollState = categoryListScrollState,
                detailScrollStates = categoryScrollStates,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                isDraggingActionCard = isDraggingActionCard,
                onBack = onBack,
                onContentViewMode = onContentViewMode,
                onMultiFolderMemory = onMultiFolderMemory,
                onPanelSplitPercent = onPanelSplitPercent,
                onExpandGrid = onExpandGrid,
                onShowFileNames = onShowFileNames,
                onOperationsFollowSelectionOrder = onOperationsFollowSelectionOrder,
                onVideoDurationFormat = onVideoDurationFormat,
                onGridColumns = onGridColumns,
                onFileColumns = onFileColumns,
                onShowAllFiles = onShowAllFiles,
                onShowOtherFileFolders = onShowOtherFileFolders,
                onFoldableFileColumns = onFoldableFileColumns,
                onFileSelectMode = onFileSelectMode,
                onShowHidden = onShowHidden,
                onHideFavoritesFolder = onHideFavoritesFolder,
                onSaveSelectionActionsConfig = onSaveSelectionActionsConfig,
                onToggleSelectionActionVisibility = onToggleSelectionActionVisibility,
                onSearchFilesFullScreen = onSearchFilesFullScreen,
                onVideoDoubleClickToGallery = onVideoDoubleClickToGallery,
                onRememberFolderScrollAcrossSessions = onRememberFolderScrollAcrossSessions,
                onNewContentScrollsToTop = onNewContentScrollsToTop,
                onAutoplayGifs = onAutoplayGifs,
                onNewpipeFolder = onNewpipeFolder,
                onShowNavArrows = onShowNavArrows,
                onStripExifOnShare = onStripExifOnShare,
                onSaveWallpaperResolutions = onSaveWallpaperResolutions,
                onExportConfig = onExportConfig,
                onDebugLoggingChange = onDebugLoggingChange,
                onClearDebugLog = onClearDebugLog,
                onImportConfig = onImportConfig,
                onResetThumbnails = onResetThumbnails,
                onScrollerOnRight = onScrollerOnRight,
                onScrollerEnabled = onScrollerEnabled,
                onScrollerSmooth = onScrollerSmooth,
                onHighResThumbnails = onHighResThumbnails,
                onLightMode = onLightMode,
                onAutoFoldSplitEnabled = onAutoFoldSplitEnabled,
                onDynamicSplit = onDynamicSplit,
                onFoldSplitAngleThreshold = onFoldSplitAngleThreshold,
                onShowFoldableSettings = onShowFoldableSettings,
                smartSearch = smartSearch,
                onUpdatePlayerConfig = onUpdatePlayerConfig,
                onUpdateHiddenCover = onUpdateHiddenCover,
                onPickHiddenCoverImage = onPickHiddenCoverImage,
                onRandomizeFileNamesInPrivateFolders = onRandomizeFileNamesInPrivateFolders,
                onStripExifInPrivateFolders = onStripExifInPrivateFolders,
                onShowViewHelp = { showViewHelp = true },
                onShowPaneSplitHelp = { showPaneSplitHelp = true },
                onShowWallpaperResolutionsDialog = { showWallpaperResolutionsDialog = true },
                onShowFolderHelp = { folderHelpTopic = it },
                onShowPrivacyHelp = { showPrivacyHelp = true },
                onShowPrivatizedFoldersDialog = { showPrivatizedFoldersDialog = true },
                onShowBehaviorHelp = { showBehaviorHelp = true },
                onShowNewpipeFolderDialog = { showNewpipeFolderDialog = true },
                onShowSelectHelp = { showSelectHelp = true },
                onShowActionCardsHelp = { showActionCardsHelp = true },
                onShowMaintenanceHelp = { showMaintenanceHelp = true },
                onShowSelectionHelp = { showSelectionHelp = true },
                onShowSearchHelp = { showSearchHelp = true },
                onDraggingChanged = { isDraggingActionCard = it },
            )
        } else {
            PhoneSettingsLayout(
                settings = settings,
                albums = albums,
                allAlbums = allAlbums,
                selectedCategory = selectedCategory,
                onSelectCategory = { selectedCategory = it },
                openCategoryState = phoneOpenCategory,
                listScrollState = categoryListScrollState,
                scrollStates = categoryScrollStates,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                foldable = foldable,
                isDraggingActionCard = isDraggingActionCard,
                onBack = onBack,
                onContentViewMode = onContentViewMode,
                onMultiFolderMemory = onMultiFolderMemory,
                onPanelSplitPercent = onPanelSplitPercent,
                onExpandGrid = onExpandGrid,
                onShowFileNames = onShowFileNames,
                onOperationsFollowSelectionOrder = onOperationsFollowSelectionOrder,
                onVideoDurationFormat = onVideoDurationFormat,
                onGridColumns = onGridColumns,
                onFileColumns = onFileColumns,
                onShowAllFiles = onShowAllFiles,
                onShowOtherFileFolders = onShowOtherFileFolders,
                onFoldableFileColumns = onFoldableFileColumns,
                onFileSelectMode = onFileSelectMode,
                onShowHidden = onShowHidden,
                onHideFavoritesFolder = onHideFavoritesFolder,
                onSaveSelectionActionsConfig = onSaveSelectionActionsConfig,
                onToggleSelectionActionVisibility = onToggleSelectionActionVisibility,
                onSearchFilesFullScreen = onSearchFilesFullScreen,
                onVideoDoubleClickToGallery = onVideoDoubleClickToGallery,
                onRememberFolderScrollAcrossSessions = onRememberFolderScrollAcrossSessions,
                onNewContentScrollsToTop = onNewContentScrollsToTop,
                onAutoplayGifs = onAutoplayGifs,
                onNewpipeFolder = onNewpipeFolder,
                onShowNavArrows = onShowNavArrows,
                onStripExifOnShare = onStripExifOnShare,
                onSaveWallpaperResolutions = onSaveWallpaperResolutions,
                onExportConfig = onExportConfig,
                onDebugLoggingChange = onDebugLoggingChange,
                onClearDebugLog = onClearDebugLog,
                onImportConfig = onImportConfig,
                onResetThumbnails = onResetThumbnails,
                onScrollerOnRight = onScrollerOnRight,
                onScrollerEnabled = onScrollerEnabled,
                onScrollerSmooth = onScrollerSmooth,
                onHighResThumbnails = onHighResThumbnails,
                onLightMode = onLightMode,
                onAutoFoldSplitEnabled = onAutoFoldSplitEnabled,
                onDynamicSplit = onDynamicSplit,
                onFoldSplitAngleThreshold = onFoldSplitAngleThreshold,
                onShowFoldableSettings = onShowFoldableSettings,
                smartSearch = smartSearch,
                onUpdatePlayerConfig = onUpdatePlayerConfig,
                onUpdateHiddenCover = onUpdateHiddenCover,
                onPickHiddenCoverImage = onPickHiddenCoverImage,
                onRandomizeFileNamesInPrivateFolders = onRandomizeFileNamesInPrivateFolders,
                onStripExifInPrivateFolders = onStripExifInPrivateFolders,
                onShowViewHelp = { showViewHelp = true },
                onShowPaneSplitHelp = { showPaneSplitHelp = true },
                onShowWallpaperResolutionsDialog = { showWallpaperResolutionsDialog = true },
                onShowFolderHelp = { folderHelpTopic = it },
                onShowPrivacyHelp = { showPrivacyHelp = true },
                onShowPrivatizedFoldersDialog = { showPrivatizedFoldersDialog = true },
                onShowBehaviorHelp = { showBehaviorHelp = true },
                onShowNewpipeFolderDialog = { showNewpipeFolderDialog = true },
                onShowSelectHelp = { showSelectHelp = true },
                onShowActionCardsHelp = { showActionCardsHelp = true },
                onShowMaintenanceHelp = { showMaintenanceHelp = true },
                onShowSelectionHelp = { showSelectionHelp = true },
                onShowSearchHelp = { showSearchHelp = true },
                onDraggingChanged = { isDraggingActionCard = it },
            )
        }
    }

    folderHelpTopic?.let { topic ->
        HiddenHelpDialog(topic = topic, onDismiss = { folderHelpTopic = null })
    }


    if (showPaneSplitHelp) {
        PaneSplitHelpDialog(onDismiss = { showPaneSplitHelp = false })
    }

    if (showViewHelp) {
        ViewHelpDialog(onDismiss = { showViewHelp = false })
    }

    if (showSelectHelp) {
        SelectHelpDialog(onDismiss = { showSelectHelp = false })
    }

    if (showBehaviorHelp) {
        BehaviorHelpDialog(onDismiss = { showBehaviorHelp = false })
    }

    if (showSelectionHelp) {
        SelectionHelpDialog(onDismiss = { showSelectionHelp = false })
    }

    if (showSearchHelp) {
        SearchHelpDialog(onDismiss = { showSearchHelp = false })
    }

    if (showActionCardsHelp) {
        ActionCardsHelpDialog(onDismiss = { showActionCardsHelp = false })
    }

    if (showPrivacyHelp) {
        PrivacyHelpDialog(onDismiss = { showPrivacyHelp = false })
    }

    if (showPrivatizedFoldersDialog) {
        PrivatizedFoldersDialog(
            albums = albums,
            allAlbums = allAlbums,
            anonymousFolderIds = settings.anonymousFolderIds,
            hiddenAlbumIds = settings.hiddenAlbumIds,
            onChangeAnonymousFolders = onChangeAnonymousFolders,
            onDismiss = { showPrivatizedFoldersDialog = false },
        )
    }

    if (showNewpipeFolderDialog) {
        NewpipeFolderDialog(
            albums = albums,
            allAlbums = allAlbums,
            selectedPath = settings.newpipeFolderRelativePath,
            hiddenAlbumIds = settings.hiddenAlbumIds,
            onSelect = onNewpipeFolder,
            onDismiss = { showNewpipeFolderDialog = false },
        )
    }

    if (showWallpaperResolutionsDialog) {
        WallpaperScreenResolutionsDialog(
            settings = settings,
            onSave = onSaveWallpaperResolutions,
            onDismiss = { showWallpaperResolutionsDialog = false },
        )
    }

    if (showConfigHelp) {
        ConfigHelpDialog(onDismiss = { showConfigHelp = false })
    }

    if (showMaintenanceHelp) {
        MaintenanceHelpDialog(onDismiss = { showMaintenanceHelp = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MasterDetailSettingsLayout(
    settings: AppSettings,
    albums: List<Album>,
    allAlbums: List<Album>,
    foldable: dev.ander.alexet.foldable.FoldableState,
    selectedCategory: SettingsCategory,
    onSelectCategory: (SettingsCategory) -> Unit,
    listScrollState: ScrollState,
    detailScrollStates: Map<SettingsCategory, ScrollState>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isDraggingActionCard: Boolean,
    onBack: () -> Unit,
    onContentViewMode: (ContentViewMode) -> Unit,
    onMultiFolderMemory: (MultiFolderMemory) -> Unit,
    onPanelSplitPercent: (Int) -> Unit,
    onExpandGrid: (columns: Int?, rows: Int?) -> Unit,
    onShowFileNames: (Boolean) -> Unit,
    onOperationsFollowSelectionOrder: (Boolean) -> Unit,
    onVideoDurationFormat: (VideoDurationFormat) -> Unit,
    onGridColumns: (Int) -> Unit,
    onFileColumns: (Int) -> Unit,
    onShowAllFiles: (Boolean) -> Unit,
    onShowOtherFileFolders: (Boolean) -> Unit,
    onFoldableFileColumns: (Int) -> Unit,
    onFileSelectMode: (FileSelectMode) -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onHideFavoritesFolder: (Boolean) -> Unit,
    onSaveSelectionActionsConfig: (List<SelectionAction?>, List<SelectionAction>, Set<SelectionAction>) -> Unit,
    onToggleSelectionActionVisibility: (SelectionAction) -> Unit,
    onSearchFilesFullScreen: (Boolean) -> Unit,
    onVideoDoubleClickToGallery: (Boolean) -> Unit,
    onRememberFolderScrollAcrossSessions: (Boolean) -> Unit,
    onNewContentScrollsToTop: (Boolean) -> Unit,
    onAutoplayGifs: (Boolean) -> Unit,
    onNewpipeFolder: (String) -> Unit,
    onShowNavArrows: (Boolean) -> Unit,
    onStripExifOnShare: (Boolean) -> Unit,
    onSaveWallpaperResolutions: (Int, Int, Int, Int) -> Unit,
    onExportConfig: () -> Unit,
    onDebugLoggingChange: (Boolean) -> Unit = {},
    onClearDebugLog: () -> Unit = {},
    onImportConfig: () -> Unit,
    onResetThumbnails: () -> Unit,
    onScrollerOnRight: (Boolean) -> Unit,
    onScrollerEnabled: (Boolean) -> Unit,
    onScrollerSmooth: (Boolean) -> Unit,
    onHighResThumbnails: (Boolean) -> Unit,
    onLightMode: (Boolean) -> Unit,
    onAutoFoldSplitEnabled: (Boolean) -> Unit,
    onDynamicSplit: (Boolean) -> Unit,
    onFoldSplitAngleThreshold: (Float) -> Unit,
    onShowFoldableSettings: (Boolean) -> Unit,
    smartSearch: SmartSearchSettingsUi?,
    onUpdatePlayerConfig: ((dev.ander.alexet.integration.AnderLineConfig) -> dev.ander.alexet.integration.AnderLineConfig) -> Unit,
    onUpdateHiddenCover: ((dev.ander.alexet.data.HiddenCoverConfig) -> dev.ander.alexet.data.HiddenCoverConfig) -> Unit,
    onPickHiddenCoverImage: (android.net.Uri) -> Unit,
    onRandomizeFileNamesInPrivateFolders: (Boolean) -> Unit,
    onStripExifInPrivateFolders: (Boolean) -> Unit,
    onShowViewHelp: () -> Unit,
    onShowPaneSplitHelp: () -> Unit,
    onShowWallpaperResolutionsDialog: () -> Unit,
    onShowFolderHelp: (FolderHelpTopic) -> Unit,
    onShowPrivacyHelp: () -> Unit,
    onShowPrivatizedFoldersDialog: () -> Unit,
    onShowBehaviorHelp: () -> Unit,
    onShowNewpipeFolderDialog: () -> Unit,
    onShowSelectHelp: () -> Unit,
    onShowActionCardsHelp: () -> Unit,
    onShowMaintenanceHelp: () -> Unit,
    onShowSelectionHelp: () -> Unit,
    onShowSearchHelp: () -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
) {
    val masterScrollState = listScrollState

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // LEWY PANEL: MASTER
            val jumpController = LocalSettingsJump.current
            SettingsCategoryList(
                selectedCategory = selectedCategory,
                onSelectCategory = onSelectCategory,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                onBack = onBack,
                scrollState = masterScrollState,
                settings = settings,
                onOpenOption = { entry ->
                    onSelectCategory(entry.category)
                    jumpController?.jumpTo(entry.key)
                },
                modifier = Modifier
                    .width(340.dp)
                    .fillMaxHeight()
                    .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
            )

        // Subtelny pionowy separator
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        )

        // PRAWY PANEL: DETAIL
        // A page opened from a card (e.g. "Customize") covers only this pane.
        val subPageSlot = remember { SettingsSubPageSlot() }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
        CompositionLocalProvider(LocalSettingsSubPageSlot provides subPageSlot) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, top = 8.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Nagłówek kategorii po prawej stronie
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = selectedCategory,
                    transitionSpec = {
                        if (targetState.ordinal > initialState.ordinal) {
                            (slideInVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(animationSpec = tween(200))) togetherWith
                                (slideOutVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(animationSpec = tween(150)))
                        } else {
                            (slideInVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) { -it / 2 } + fadeIn(animationSpec = tween(200))) togetherWith
                                (slideOutVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(animationSpec = tween(150)))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = "SettingsDetailTitleAnim",
                ) { cat ->
                    // Title and help button change together: the button opens the help of the
                    // category whose title is shown.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(cat.titleRes),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (cat != SettingsCategory.FOLDERS) IconButton(
                            onClick = {
                                when (cat) {
                                    SettingsCategory.VIEW -> onShowViewHelp()
                                    SettingsCategory.FOLDERS -> Unit
                                    SettingsCategory.SELECTION -> onShowSelectionHelp()
                                    SettingsCategory.VIEWING -> onShowBehaviorHelp()
                                    SettingsCategory.SEARCH -> onShowSearchHelp()
                                    SettingsCategory.PRIVACY -> onShowPrivacyHelp()
                                    SettingsCategory.APP -> onShowMaintenanceHelp()
                                }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = stringResource(R.string.settings_help_for, stringResource(cat.titleRes)),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }

            // Zawartość wybranej kategorii z płynną animacją przesunięcia góra/dół i niezależnym scrollem
            AnimatedContent(
                targetState = selectedCategory,
                transitionSpec = {
                    if (targetState.ordinal > initialState.ordinal) {
                        (slideInVertically(animationSpec = tween(240, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(animationSpec = tween(220))) togetherWith
                            (slideOutVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(animationSpec = tween(160)))
                    } else {
                        (slideInVertically(animationSpec = tween(240, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(animationSpec = tween(220))) togetherWith
                            (slideOutVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(animationSpec = tween(160)))
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "SettingsDetailContentAnim",
            ) { cat ->
                val activeScroll = detailScrollStates[cat] ?: rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(activeScroll, enabled = !isDraggingActionCard)
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (cat) {
                        SettingsCategory.VIEW -> ViewSettingsSectionContent(
                            settings = settings,
                            foldable = foldable,
                            showHeader = false,
                            onContentViewMode = onContentViewMode,
                            onExpandGrid = onExpandGrid,
                            onFoldableFileColumns = onFoldableFileColumns,
                            onPanelSplitPercent = onPanelSplitPercent,
                            onAutoFoldSplitEnabled = onAutoFoldSplitEnabled,
                            onDynamicSplit = onDynamicSplit,
                            onFoldSplitAngleThreshold = onFoldSplitAngleThreshold,
                            onShowFoldableSettings = onShowFoldableSettings,
                            onShowFileNames = onShowFileNames,
                            onGridColumns = onGridColumns,
                            onFileColumns = onFileColumns,
                            onVideoDurationFormat = onVideoDurationFormat,
                            onScrollerEnabled = onScrollerEnabled,
                            onScrollerOnRight = onScrollerOnRight,
                            onScrollerSmooth = onScrollerSmooth,
                            onShowViewHelp = onShowViewHelp,
                            onShowPaneSplitHelp = onShowPaneSplitHelp,
                        )
                        SettingsCategory.FOLDERS -> FoldersSettingsSectionContent(
                            settings = settings,
                            albums = albums,
                            allAlbums = allAlbums,
                            showHeader = false,
                            onShowAllFiles = onShowAllFiles,
                            onShowOtherFileFolders = onShowOtherFileFolders,
                            onHideFavoritesFolder = onHideFavoritesFolder,
                            onMultiFolderMemory = onMultiFolderMemory,
                            onRememberFolderScrollAcrossSessions = onRememberFolderScrollAcrossSessions,
                            onNewContentScrollsToTop = onNewContentScrollsToTop,
                            onShowNewpipeFolderDialog = onShowNewpipeFolderDialog,
                            onShowFolderHelp = onShowFolderHelp,
                        )
                        SettingsCategory.SELECTION -> SelectionSettingsSectionContent(
                            settings = settings,
                            onFileSelectMode = onFileSelectMode,
                            onOperationsFollowSelectionOrder = onOperationsFollowSelectionOrder,
                            onSaveSelectionActionsConfig = onSaveSelectionActionsConfig,
                            onToggleSelectionActionVisibility = onToggleSelectionActionVisibility,
                            onShowSelectHelp = onShowSelectHelp,
                            onShowActionCardsHelp = onShowActionCardsHelp,
                            onDraggingChanged = onDraggingChanged,
                        )
                        SettingsCategory.VIEWING -> PlayerSettingsSectionContent(
                            settings = settings,
                            showHeader = false,
                            onVideoDoubleClickToGallery = onVideoDoubleClickToGallery,
                            onUpdatePlayerConfig = onUpdatePlayerConfig,
                            onAutoplayGifs = onAutoplayGifs,
                            onShowNavArrows = onShowNavArrows,
                            onShowBehaviorHelp = onShowBehaviorHelp,
                        )
                        SettingsCategory.SEARCH -> SearchSettingsSectionContent(
                            settings = settings,
                            onSearchFilesFullScreen = onSearchFilesFullScreen,
                            smartSearch = smartSearch,
                        )
                        SettingsCategory.PRIVACY -> PrivacySettingsSectionContent(
                            settings = settings,
                            albums = allAlbums,
                            showHeader = false,
                            onShowHidden = onShowHidden,
                            onShowFolderHelp = onShowFolderHelp,
                            onUpdateHiddenCover = onUpdateHiddenCover,
                            onPickHiddenCoverImage = onPickHiddenCoverImage,
                            onRandomizeFileNamesInPrivateFolders = onRandomizeFileNamesInPrivateFolders,
                            onStripExifInPrivateFolders = onStripExifInPrivateFolders,
                            onStripExifOnShare = onStripExifOnShare,
                            onShowPrivatizedFoldersDialog = onShowPrivatizedFoldersDialog,
                            onShowPrivacyHelp = onShowPrivacyHelp,
                        )
                        SettingsCategory.APP -> SystemSettingsSectionContent(
                            settings = settings,
                            debugLogging = settings.debugLogging,
                            showHeader = false,
                            onLightMode = onLightMode,
                            onHighResThumbnails = onHighResThumbnails,
                            onShowWallpaperResolutionsDialog = onShowWallpaperResolutionsDialog,
                            onExportConfig = onExportConfig,
                            onDebugLoggingChange = onDebugLoggingChange,
                            onClearDebugLog = onClearDebugLog,
                            onImportConfig = onImportConfig,
                            onResetThumbnails = onResetThumbnails,
                            onShowMaintenanceHelp = onShowMaintenanceHelp,
                        )
                    }
                }
            }
        }
        }
        subPageSlot.page?.invoke()
        }
    }
}
}

/**
 * Settings categories with search, as cards: the left pane of the wide layout, and the first
 * screen on a phone (a category opens on its own screen there).
 */
@Composable
private fun SettingsCategoryList(
    selectedCategory: SettingsCategory?,
    onSelectCategory: (SettingsCategory) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    scrollState: ScrollState,
    settings: AppSettings,
    onOpenOption: (SettingsSearchEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val masterScrollState = scrollState
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Nagłówek panelu lewego
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

            // Pigułka wyszukiwania
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_search_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.settings_clear_search),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Lista kategorii (zaokrąglone pigułki z aktywnym stanem)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(masterScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val query = normalizeForSearch(searchQuery.trim())
                // Every option's words, read and normalized once per language, not on each keystroke.
                val searchContext = LocalContext.current
                val searchConfiguration = LocalConfiguration.current
                val searchTexts = remember(searchConfiguration) {
                    settingsSearchEntries.associateWith { entry -> settingsSearchText(searchContext, entry) }
                }
                var shownCategories = 0
                for (cat in SettingsCategory.values()) {
                    // While searching: categories whose name fits or that have options that fit,
                    // with those options under the category card.
                    val options = if (query.isEmpty()) {
                        emptyList()
                    } else {
                        settingsSearchEntries.filter { entry ->
                            entry.category == cat && entry.visible(settings) && searchTexts.getValue(entry).contains(query)
                        }
                    }
                    val categoryFits = query.isEmpty() ||
                        normalizeForSearch(stringResource(cat.titleRes)).contains(query) ||
                        normalizeForSearch(stringResource(cat.subtitleFor(settings))).contains(query)
                    if (categoryFits || options.isNotEmpty()) {
                        shownCategories++
                        val isSelected = cat == selectedCategory
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            tonalElevation = if (isSelected) 3.dp else 1.dp,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { onSelectCategory(cat) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = cat.icon,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(cat.titleRes),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(cat.subtitleFor(settings)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (options.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (entry in options) {
                                    SettingsSearchResult(entry = entry, onClick = { onOpenOption(entry) })
                                }
                            }
                        }
                    }
                }
                if (shownCategories == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SearchOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Stopka panelu Master
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Alexet Gallery",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.settings_version_full, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
}

/** Words the search compares with: the option's name, description and extra words. */
private fun settingsSearchText(context: android.content.Context, entry: SettingsSearchEntry): String {
    val parts = mutableListOf(context.getString(entry.titleRes), context.getString(entry.key))
    entry.summaryRes?.let { parts += context.getString(it) }
    for (res in entry.extraRes) parts += context.getString(res)
    return normalizeForSearch(parts.joinToString(" "))
}

/** A found option under its category card: name and a short description; tap opens it. */
@Composable
private fun SettingsSearchResult(entry: SettingsSearchEntry, onClick: () -> Unit) {
    val summary = entry.summaryRes?.let { stringResource(it) }
        ?: entry.extraRes.map { stringResource(it) }.joinToString(" · ").ifEmpty { null }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(entry.titleRes).trimEnd(':', ' '),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneSettingsLayout(
    settings: AppSettings,
    albums: List<Album>,
    allAlbums: List<Album>,
    selectedCategory: SettingsCategory,
    onSelectCategory: (SettingsCategory) -> Unit,
    openCategoryState: MutableState<SettingsCategory?>,
    listScrollState: ScrollState,
    scrollStates: Map<SettingsCategory, ScrollState>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    foldable: dev.ander.alexet.foldable.FoldableState,
    isDraggingActionCard: Boolean,
    onBack: () -> Unit,
    onContentViewMode: (ContentViewMode) -> Unit,
    onMultiFolderMemory: (MultiFolderMemory) -> Unit,
    onPanelSplitPercent: (Int) -> Unit,
    onExpandGrid: (columns: Int?, rows: Int?) -> Unit,
    onShowFileNames: (Boolean) -> Unit,
    onOperationsFollowSelectionOrder: (Boolean) -> Unit,
    onVideoDurationFormat: (VideoDurationFormat) -> Unit,
    onGridColumns: (Int) -> Unit,
    onFileColumns: (Int) -> Unit,
    onShowAllFiles: (Boolean) -> Unit,
    onShowOtherFileFolders: (Boolean) -> Unit,
    onFoldableFileColumns: (Int) -> Unit,
    onFileSelectMode: (FileSelectMode) -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onHideFavoritesFolder: (Boolean) -> Unit,
    onSaveSelectionActionsConfig: (List<SelectionAction?>, List<SelectionAction>, Set<SelectionAction>) -> Unit,
    onToggleSelectionActionVisibility: (SelectionAction) -> Unit,
    onSearchFilesFullScreen: (Boolean) -> Unit,
    onVideoDoubleClickToGallery: (Boolean) -> Unit,
    onRememberFolderScrollAcrossSessions: (Boolean) -> Unit,
    onNewContentScrollsToTop: (Boolean) -> Unit,
    onAutoplayGifs: (Boolean) -> Unit,
    onNewpipeFolder: (String) -> Unit,
    onShowNavArrows: (Boolean) -> Unit,
    onStripExifOnShare: (Boolean) -> Unit,
    onSaveWallpaperResolutions: (Int, Int, Int, Int) -> Unit,
    onExportConfig: () -> Unit,
    onDebugLoggingChange: (Boolean) -> Unit = {},
    onClearDebugLog: () -> Unit = {},
    onImportConfig: () -> Unit,
    onResetThumbnails: () -> Unit,
    onScrollerOnRight: (Boolean) -> Unit,
    onScrollerEnabled: (Boolean) -> Unit,
    onScrollerSmooth: (Boolean) -> Unit,
    onHighResThumbnails: (Boolean) -> Unit,
    onLightMode: (Boolean) -> Unit,
    onAutoFoldSplitEnabled: (Boolean) -> Unit,
    onDynamicSplit: (Boolean) -> Unit,
    onFoldSplitAngleThreshold: (Float) -> Unit,
    onShowFoldableSettings: (Boolean) -> Unit,
    smartSearch: SmartSearchSettingsUi?,
    onUpdatePlayerConfig: ((dev.ander.alexet.integration.AnderLineConfig) -> dev.ander.alexet.integration.AnderLineConfig) -> Unit,
    onUpdateHiddenCover: ((dev.ander.alexet.data.HiddenCoverConfig) -> dev.ander.alexet.data.HiddenCoverConfig) -> Unit,
    onPickHiddenCoverImage: (android.net.Uri) -> Unit,
    onRandomizeFileNamesInPrivateFolders: (Boolean) -> Unit,
    onStripExifInPrivateFolders: (Boolean) -> Unit,
    onShowViewHelp: () -> Unit,
    onShowPaneSplitHelp: () -> Unit,
    onShowWallpaperResolutionsDialog: () -> Unit,
    onShowFolderHelp: (FolderHelpTopic) -> Unit,
    onShowPrivacyHelp: () -> Unit,
    onShowPrivatizedFoldersDialog: () -> Unit,
    onShowBehaviorHelp: () -> Unit,
    onShowNewpipeFolderDialog: () -> Unit,
    onShowSelectHelp: () -> Unit,
    onShowActionCardsHelp: () -> Unit,
    onShowMaintenanceHelp: () -> Unit,
    onShowSelectionHelp: () -> Unit,
    onShowSearchHelp: () -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
) {
    // scrollStates: one scroll position per category, kept while going back to the list.
    val jumpController = LocalSettingsJump.current
    // Null: the category list; otherwise that category on its own screen.
    var openCategory by openCategoryState

    fun openCategoryScreen(cat: SettingsCategory) {
        openCategory = cat
    }

    // Back (button or gesture) slides the category out to the right, the same as closing a folder.
    BackHandler(enabled = openCategory != null) {
        openCategory = null
    }

    val categoryList: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            SettingsCategoryList(
                // Nothing is "selected" on a phone list: a category opens on its own screen. The
                // choice is still kept, so the unfolded layout shows that category.
                selectedCategory = null,
                onSelectCategory = { cat ->
                    onSelectCategory(cat)
                    openCategoryScreen(cat)
                },
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                onBack = onBack,
                scrollState = listScrollState,
                settings = settings,
                onOpenOption = { entry ->
                    onSelectCategory(entry.category)
                    openCategoryScreen(entry.category)
                    jumpController?.jumpTo(entry.key)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }

    // List <-> category moves like opening a folder: the category comes in from the right while
    // the list slides left and shrinks a little; going back reverses it. Light mode switches
    // instantly (the AnimatedContent wrapper).
    AnimatedContent(
        targetState = openCategory,
        transitionSpec = {
            when {
                targetState != null ->
                    (slideInHorizontally(initialOffsetX = { it / 4 }, animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                        fadeIn(animationSpec = tween(200)))
                        .togetherWith(
                            slideOutHorizontally(targetOffsetX = { -it / 6 }, animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                                fadeOut(animationSpec = tween(160)) +
                                scaleOut(targetScale = 0.95f, animationSpec = tween(220, easing = FastOutSlowInEasing)),
                        )
                else ->
                    (slideInHorizontally(initialOffsetX = { -it / 6 }, animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                        fadeIn(animationSpec = tween(200)) +
                        scaleIn(initialScale = 0.95f, animationSpec = tween(240, easing = FastOutSlowInEasing)))
                        .togetherWith(
                            slideOutHorizontally(targetOffsetX = { it / 4 }, animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                                fadeOut(animationSpec = tween(160)),
                        )
            }
        },
        // Settings lie over the gallery: an opaque background, so the gallery does not show
        // through while both screens are faded during the switch.
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        label = "PhoneSettingsScreenAnim",
    ) { open ->
        if (open == null) {
            categoryList()
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = stringResource(open.titleRes),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { openCategory = null }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                            contentDescription = stringResource(R.string.navigate_up),
                                        )
                                    }
                                },
                                actions = {
                                    if (open != SettingsCategory.FOLDERS) IconButton(
                                        onClick = {
                                            when (open) {
                                                SettingsCategory.VIEW -> onShowViewHelp()
                                                SettingsCategory.FOLDERS -> Unit
                                                SettingsCategory.SELECTION -> onShowSelectionHelp()
                                                SettingsCategory.VIEWING -> onShowBehaviorHelp()
                                                SettingsCategory.SEARCH -> onShowSearchHelp()
                                                SettingsCategory.PRIVACY -> onShowPrivacyHelp()
                                                SettingsCategory.APP -> onShowMaintenanceHelp()
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Info,
                                            contentDescription = stringResource(R.string.settings_help_for, stringResource(open.titleRes)),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) { padding ->
                        val cat = open
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .verticalScroll(scrollStates.getValue(cat), enabled = !isDraggingActionCard)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            when (cat) {
                                SettingsCategory.VIEW -> ViewSettingsSectionContent(
                                    settings = settings,
                                    foldable = foldable,
                                    showHeader = false,
                                    onContentViewMode = onContentViewMode,
                                    onExpandGrid = onExpandGrid,
                                    onFoldableFileColumns = onFoldableFileColumns,
                                    onPanelSplitPercent = onPanelSplitPercent,
                                    onAutoFoldSplitEnabled = onAutoFoldSplitEnabled,
                                    onDynamicSplit = onDynamicSplit,
                                    onFoldSplitAngleThreshold = onFoldSplitAngleThreshold,
                                    onShowFoldableSettings = onShowFoldableSettings,
                                    onShowFileNames = onShowFileNames,
                                    onGridColumns = onGridColumns,
                                    onFileColumns = onFileColumns,
                                    onVideoDurationFormat = onVideoDurationFormat,
                                    onScrollerEnabled = onScrollerEnabled,
                                    onScrollerOnRight = onScrollerOnRight,
                                    onScrollerSmooth = onScrollerSmooth,
                                    onShowViewHelp = onShowViewHelp,
                                    onShowPaneSplitHelp = onShowPaneSplitHelp,
                                )
                                SettingsCategory.FOLDERS -> FoldersSettingsSectionContent(
                                    settings = settings,
                                    albums = albums,
                                    allAlbums = allAlbums,
                                    showHeader = false,
                                    onShowAllFiles = onShowAllFiles,
                                    onShowOtherFileFolders = onShowOtherFileFolders,
                                    onHideFavoritesFolder = onHideFavoritesFolder,
                                    onMultiFolderMemory = onMultiFolderMemory,
                                    onRememberFolderScrollAcrossSessions = onRememberFolderScrollAcrossSessions,
                                    onNewContentScrollsToTop = onNewContentScrollsToTop,
                                    onShowNewpipeFolderDialog = onShowNewpipeFolderDialog,
                                    onShowFolderHelp = onShowFolderHelp,
                                )
                                SettingsCategory.SELECTION -> SelectionSettingsSectionContent(
                                    settings = settings,
                                    onFileSelectMode = onFileSelectMode,
                                    onOperationsFollowSelectionOrder = onOperationsFollowSelectionOrder,
                                    onSaveSelectionActionsConfig = onSaveSelectionActionsConfig,
                                    onToggleSelectionActionVisibility = onToggleSelectionActionVisibility,
                                    onShowSelectHelp = onShowSelectHelp,
                                    onShowActionCardsHelp = onShowActionCardsHelp,
                                    onDraggingChanged = onDraggingChanged,
                                )
                                SettingsCategory.VIEWING -> PlayerSettingsSectionContent(
                                    settings = settings,
                                    showHeader = false,
                                    onVideoDoubleClickToGallery = onVideoDoubleClickToGallery,
                                    onUpdatePlayerConfig = onUpdatePlayerConfig,
                                    onAutoplayGifs = onAutoplayGifs,
                                    onShowNavArrows = onShowNavArrows,
                                    onShowBehaviorHelp = onShowBehaviorHelp,
                                )
                                SettingsCategory.SEARCH -> SearchSettingsSectionContent(
                                    settings = settings,
                                    onSearchFilesFullScreen = onSearchFilesFullScreen,
                                    smartSearch = smartSearch,
                                )
                                SettingsCategory.PRIVACY -> PrivacySettingsSectionContent(
                                    settings = settings,
                                    albums = allAlbums,
                                    showHeader = false,
                                    onShowHidden = onShowHidden,
                                    onShowFolderHelp = onShowFolderHelp,
                                    onUpdateHiddenCover = onUpdateHiddenCover,
                                    onPickHiddenCoverImage = onPickHiddenCoverImage,
                                    onRandomizeFileNamesInPrivateFolders = onRandomizeFileNamesInPrivateFolders,
                                    onStripExifInPrivateFolders = onStripExifInPrivateFolders,
                                    onStripExifOnShare = onStripExifOnShare,
                                    onShowPrivatizedFoldersDialog = onShowPrivatizedFoldersDialog,
                                    onShowPrivacyHelp = onShowPrivacyHelp,
                                )
                                SettingsCategory.APP -> SystemSettingsSectionContent(
                                    settings = settings,
                                    debugLogging = settings.debugLogging,
                                    showHeader = false,
                                    onLightMode = onLightMode,
                                    onHighResThumbnails = onHighResThumbnails,
                                    onShowWallpaperResolutionsDialog = onShowWallpaperResolutionsDialog,
                                    onExportConfig = onExportConfig,
                                    onDebugLoggingChange = onDebugLoggingChange,
                                    onClearDebugLog = onClearDebugLog,
                                    onImportConfig = onImportConfig,
                                    onResetThumbnails = onResetThumbnails,
                                    onShowMaintenanceHelp = onShowMaintenanceHelp,
                                )
                            }
                        }
                    }
            }
        }
    }
}

// =============================================================================
// === MODULARNE SEKCJE USTAWIEŃ (Modular Settings Sections)
// =============================================================================

/** Options of the View card's tablet tab (the two panes) and of its phone tab. */
private val TabletViewOptionKeys = setOf(
    R.string.content_view_title,
    R.string.expand_grid_size,
    R.string.settings_file_columns_label,
    R.string.pane_split_title,
    R.string.dynamic_split_title,
    R.string.auto_fold_split_title,
    R.string.fold_split_angle_title,
)
private val PhoneViewOptionKeys = setOf(R.string.file_columns_title)

@Composable
private fun ViewSettingsSectionContent(
    settings: AppSettings,
    foldable: dev.ander.alexet.foldable.FoldableState,
    showHeader: Boolean,
    onContentViewMode: (ContentViewMode) -> Unit,
    onExpandGrid: (columns: Int?, rows: Int?) -> Unit,
    onFoldableFileColumns: (Int) -> Unit,
    onPanelSplitPercent: (Int) -> Unit,
    onAutoFoldSplitEnabled: (Boolean) -> Unit,
    onDynamicSplit: (Boolean) -> Unit,
    onFoldSplitAngleThreshold: (Float) -> Unit,
    onShowFoldableSettings: (Boolean) -> Unit,
    onShowFileNames: (Boolean) -> Unit,
    onGridColumns: (Int) -> Unit,
    onFileColumns: (Int) -> Unit,
    onVideoDurationFormat: (VideoDurationFormat) -> Unit,
    onScrollerEnabled: (Boolean) -> Unit,
    onScrollerOnRight: (Boolean) -> Unit,
    onScrollerSmooth: (Boolean) -> Unit,
    onShowViewHelp: () -> Unit,
    onShowPaneSplitHelp: () -> Unit,
) {
    if (showHeader) {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_category_view),
            icon = Icons.Rounded.GridView,
            onInfoClick = onShowViewHelp,
            infoDescription = stringResource(R.string.settings_help_for, stringResource(SettingsCategory.VIEW.titleRes)),
        )
    }
    SettingsCardContainer {
        // Phone and tablet tabs (this device first), each with its own options and the shared ones.
        // Without the two-panel options (no foldable, switch below off) there is only the phone.
        val wideNow = !foldable.isTabletop && (foldable.showListDetail || LocalConfiguration.current.screenWidthDp >= 600)
        var chosenDevice by rememberSaveable { mutableStateOf(if (wideNow) PreviewDevice.TABLET else PreviewDevice.PHONE) }
        val previewDevice = if (settings.extraViewSettingsShown) chosenDevice else PreviewDevice.PHONE
        // A search result in the other tab opens that tab.
        val jumpKey = LocalSettingsJump.current?.target?.key
        LaunchedEffect(jumpKey) {
            if (jumpKey == null) return@LaunchedEffect
            if (jumpKey in TabletViewOptionKeys) {
                chosenDevice = PreviewDevice.TABLET
            } else if (jumpKey in PhoneViewOptionKeys) {
                chosenDevice = PreviewDevice.PHONE
            }
        }
        // Files' columns as that device shows them.
        val previewFileColumns = if (previewDevice == PreviewDevice.TABLET) settings.foldableFileColumns else settings.fileColumns
        val modePhonePreview: @Composable (ContentViewMode) -> Unit = { mode ->
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PhoneFlowPreview(
                    folderColumns = settings.gridColumns,
                    fileColumns = settings.fileColumns,
                    mode = mode,
                    expandColumns = settings.expandColumns,
                    expandRows = settings.expandRows,
                    width = maxHeight * 0.56f,
                    height = maxHeight,
                )
            }
        }
        SettingsSwitchRow(
            title = stringResource(R.string.show_foldable_settings_title),
            modifier = Modifier.settingsSearchTarget(R.string.show_foldable_settings_title),
            body = stringResource(
                if (settings.foldableDetected) R.string.show_foldable_settings_detected
                else R.string.show_foldable_settings_body,
            ),
            // On a foldable they are always shown: the switch stays on.
            checked = settings.extraViewSettingsShown,
            enabled = !settings.foldableDetected,
            onCheckedChange = onShowFoldableSettings,
            icon = Icons.Rounded.Devices,
            preview = { OptionPreview(OptionPreviewKind.EXTRA_VIEW, active = settings.extraViewSettingsShown) },
        )
        SettingsItemDivider(startIndent = 0.dp)
        if (settings.extraViewSettingsShown) {
            Text(
                text = stringResource(R.string.view_preview_device),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
            )
            SettingsSegmentedRow(
                options = listOf(
                    PreviewDevice.PHONE to stringResource(R.string.view_preview_phone),
                    PreviewDevice.TABLET to stringResource(R.string.view_preview_tablet),
                ),
                selected = chosenDevice,
                onSelect = { chosenDevice = it },
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsItemDivider(startIndent = 0.dp)
        }
        // Two-panel options: the tablet tab.
        if (previewDevice == PreviewDevice.TABLET) {
            Column(modifier = Modifier.settingsSearchTarget(R.string.content_view_title)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.content_view_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 3 Tryby widoku składaka jako Kafelki (Tiles)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 1. Ostatnio otwarte
                    FoldableModeTile(
                        selected = settings.contentViewMode == ContentViewMode.LAST_FOLDER,
                        title = stringResource(R.string.content_view_last),
                        badge = stringResource(R.string.settings_badge_default),
                        onClick = { onContentViewMode(ContentViewMode.LAST_FOLDER) },
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        phoneGraphic = if (previewDevice == PreviewDevice.PHONE) {
                            { modePhonePreview(ContentViewMode.LAST_FOLDER) }
                        } else {
                            null
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(0.4f)
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(3.dp)),
                            ) {
                                MiniKafelkiGrid()
                            }
                            Box(
                                modifier = Modifier
                                    .weight(0.6f)
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f), RoundedCornerShape(3.dp)),
                            )
                        }
                    }

                    // 2. Szeroki widok
                    FoldableModeTile(
                        selected = settings.contentViewMode == ContentViewMode.EXPAND_FOLDERS,
                        title = stringResource(R.string.content_view_expand),
                        badge = stringResource(R.string.settings_badge_grid),
                        onClick = { onContentViewMode(ContentViewMode.EXPAND_FOLDERS) },
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        phoneGraphic = if (previewDevice == PreviewDevice.PHONE) {
                            { modePhonePreview(ContentViewMode.EXPAND_FOLDERS) }
                        } else {
                            null
                        },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(1.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            repeat(3) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    repeat(4) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Multi-folder
                    FoldableModeTile(
                        selected = settings.contentViewMode == ContentViewMode.CLICK_DRAG,
                        title = stringResource(R.string.content_view_click_drag),
                        badge = "Multi",
                        onClick = { onContentViewMode(ContentViewMode.CLICK_DRAG) },
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        phoneGraphic = if (previewDevice == PreviewDevice.PHONE) {
                            { modePhonePreview(ContentViewMode.CLICK_DRAG) }
                        } else {
                            null
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(0.4f)
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(3.dp)),
                            ) {
                                MiniKafelkiGrid()
                            }
                            Column(
                                modifier = Modifier
                                    .weight(0.6f)
                                    .fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f), RoundedCornerShape(2.dp)),
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f), RoundedCornerShape(2.dp)),
                                )
                            }
                        }
                    }
                }
            }

            // Subustawienie A: Rozmiar siatki (widoczne tylko dla "Szeroki widok")
            AnimatedVisibility(
                visible = settings.contentViewMode == ContentViewMode.EXPAND_FOLDERS,
                enter = fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150)),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    modifier = Modifier.settingsSearchTarget(R.string.expand_grid_size)
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.expand_grid_size),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = stringResource(R.string.settings_expand_grid_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.padding(start = 8.dp),
                            ) {
                                Text(
                                    text = "${settings.expandColumns} ${stringResource(R.string.grid_size_times)} ${settings.expandRows}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            thickness = 0.5.dp,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_columns_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.settings_columns_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SettingsCompactStepper(
                                value = settings.expandColumns,
                                range = 2..6,
                                // Only this value: the other one is read when the change is written.
                                onValueChange = { onExpandGrid(it, null) },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_rows_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.settings_rows_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SettingsCompactStepper(
                                value = settings.expandRows,
                                range = 2..6,
                                onValueChange = { onExpandGrid(null, it) },
                            )
                        }
                    }
                }
            }

            // Subustawienie B: Ilość kolumn w panelu plików składaka
            AnimatedVisibility(
                visible = settings.contentViewMode != ContentViewMode.EXPAND_FOLDERS,
                enter = fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150)),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    modifier = Modifier.settingsSearchTarget(R.string.settings_file_columns_label)
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_file_columns_label),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.settings_file_columns_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SettingsCompactStepper(
                            value = settings.foldableFileColumns,
                            range = 1..10,
                            onValueChange = onFoldableFileColumns,
                        )
                    }
                }
            }

            // Subustawienie C: Podział paneli (widoczne dla "Ostatnio otwarte" oraz "Multi-folder")
            AnimatedVisibility(
                visible = settings.contentViewMode != ContentViewMode.EXPAND_FOLDERS,
                enter = fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SettingsItemDivider(startIndent = 0.dp)

                    // Changes only when the yes/no answer changes, not on every degree of the hinge.
                    val threshold = settings.foldSplitAngleThreshold
                    val isBook by remember(foldable, threshold) {
                        androidx.compose.runtime.derivedStateOf { foldable.isBentBook(threshold) }
                    }
                    Column(modifier = Modifier.settingsSearchTarget(R.string.pane_split_title)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.pane_split_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = onShowPaneSplitHelp,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = stringResource(R.string.pane_split_title),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        // R1: Rich PanelSplitPreviewTile styled identically to FoldableModeTile
                        PanelSplitPreviewTile(
                            panelSplitPercent = settings.panelSplitPercent,
                            isBookMode = isBook && settings.autoFoldSplitEnabled,
                            folderColumns = settings.gridColumns,
                            // As the app: "Kolumny plików" while folded, the foldable count while wide.
                            fileColumns = previewFileColumns,
                            device = previewDevice,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                ) {
                                    IconButton(
                                        onClick = {
                                            val current = (settings.panelSplitPercent / 10) * 10
                                            val next = (current - 10).coerceAtLeast(10)
                                            onPanelSplitPercent(next)
                                        },
                                        enabled = settings.panelSplitPercent > 10,
                                        modifier = Modifier.size(38.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Remove,
                                            contentDescription = stringResource(R.string.settings_shrink_left_pane),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    Text(
                                        text = if (isBook && settings.autoFoldSplitEnabled) {
                                            stringResource(R.string.pane_split_fifty)
                                        } else {
                                            "${settings.panelSplitPercent}% / ${100 - settings.panelSplitPercent}%"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .widthIn(min = 96.dp)
                                            .padding(horizontal = 8.dp),
                                    )
                                    IconButton(
                                        onClick = {
                                            val current = (settings.panelSplitPercent / 10) * 10
                                            val next = (current + 10).coerceAtMost(90)
                                            onPanelSplitPercent(next)
                                        },
                                        enabled = settings.panelSplitPercent < 90,
                                        modifier = Modifier.size(38.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Add,
                                            contentDescription = stringResource(R.string.settings_grow_left_pane),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 50/50 until a folder opens on the right; an animation, so light mode turns it off.
                    SettingsSwitchRow(
                        title = stringResource(R.string.dynamic_split_title),
                        modifier = Modifier.settingsSearchTarget(R.string.dynamic_split_title),
                        body = stringResource(R.string.dynamic_split_body),
                        checked = settings.dynamicSplit && !settings.lightMode,
                        onCheckedChange = onDynamicSplit,
                        icon = Icons.Rounded.VerticalSplit,
                        preview = { OptionPreview(OptionPreviewKind.DYNAMIC_SPLIT, active = settings.dynamicSplit && !settings.lightMode) },
                    )

                    // Fold options: only on a foldable (they never apply elsewhere).
                    if (settings.foldOnlySettingsShown) {
                        // R13 controls:
                        SettingsSwitchRow(
                            title = stringResource(R.string.auto_fold_split_title),
                            modifier = Modifier.settingsSearchTarget(R.string.auto_fold_split_title),
                            body = stringResource(R.string.auto_fold_split_body),
                            checked = settings.autoFoldSplitEnabled,
                            onCheckedChange = onAutoFoldSplitEnabled,
                            icon = Icons.Rounded.PhoneAndroid,
                            preview = { OptionPreview(OptionPreviewKind.FOLD_SPLIT, active = settings.autoFoldSplitEnabled) },
                        )
                        AnimatedVisibility(visible = settings.autoFoldSplitEnabled) {
                            Row(
                                modifier = Modifier.settingsSearchTarget(R.string.fold_split_angle_title)
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.fold_split_angle_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = stringResource(R.string.fold_split_angle_body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                SettingsCompactStepper(
                                    value = settings.foldSplitAngleThreshold.toInt(),
                                    range = 5..90,
                                    onValueChange = { onFoldSplitAngleThreshold(it.toFloat()) },
                                    suffix = "°",
                                )
                            }
                        }
                    }
                }
            }

            SettingsItemDivider(startIndent = 0.dp)
        }

        // Siatka miniatur i etykiety
        SettingsSwitchRow(
            title = stringResource(R.string.show_file_names),
            modifier = Modifier.settingsSearchTarget(R.string.show_file_names),
            body = stringResource(R.string.show_file_names_body),
            checked = settings.showFileNames,
            onCheckedChange = onShowFileNames,
            icon = Icons.Rounded.Visibility,
            preview = {
                MiniLayoutPreview(
                    device = previewDevice,
                    folderColumns = settings.gridColumns,
                    fileColumns = previewFileColumns,
                    focus = PreviewFocus.FILES,
                    showNames = settings.showFileNames,
                    infoTitle = R.string.show_file_names,
                    infoBody = R.string.preview_info_show_file_names,
                )
            },
        )
        SettingsItemDivider()
        Row(
            modifier = Modifier.settingsSearchTarget(R.string.grid_columns)
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MiniLayoutPreview(
                    device = previewDevice,
                    folderColumns = settings.gridColumns,
                    fileColumns = previewFileColumns,
                    focus = PreviewFocus.FOLDERS,
                    showNames = settings.showFileNames,
                    infoTitle = R.string.grid_columns,
                    infoBody = R.string.preview_info_grid_columns,
                )
                Column {
                    Text(
                        text = stringResource(R.string.grid_columns),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.grid_columns_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SettingsCompactStepper(
                value = settings.gridColumns,
                range = 1..30,
                onValueChange = onGridColumns,
            )
        }
        SettingsItemDivider()
        // The phone tab only: a tablet uses the file pane's columns.
        if (previewDevice == PreviewDevice.PHONE) {
            Row(
                modifier = Modifier.settingsSearchTarget(R.string.file_columns_title)
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // On a tablet the foldable count is used: this one stands dimmed there.
                    MiniLayoutPreview(
                        device = previewDevice,
                        folderColumns = settings.gridColumns,
                        fileColumns = if (previewDevice == PreviewDevice.TABLET) settings.foldableFileColumns else settings.fileColumns,
                        focus = PreviewFocus.FILES,
                        showNames = settings.showFileNames,
                        infoTitle = R.string.file_columns_title,
                        infoBody = R.string.preview_info_file_columns,
                        dimFocus = previewDevice == PreviewDevice.TABLET,
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.file_columns_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.file_columns_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SettingsCompactStepper(
                    value = settings.fileColumns,
                    range = 1..30,
                    onValueChange = onFileColumns,
                )
            }
            SettingsItemDivider()
        }
        Column(
            modifier = Modifier.settingsSearchTarget(R.string.video_duration_format_title)
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OptionPreview(
                    OptionPreviewKind.DURATION_FORMAT,
                    active = true,
                    fullDuration = settings.videoDurationFormat == VideoDurationFormat.HOURS_MINUTES_SECONDS,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.video_duration_format_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.video_duration_format_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SettingsSegmentedRow(
                options = listOf(
                    VideoDurationFormat.MINUTES to stringResource(R.string.video_duration_format_minutes),
                    VideoDurationFormat.HOURS_MINUTES_SECONDS to stringResource(R.string.video_duration_format_hms),
                ),
                selected = settings.videoDurationFormat,
                onSelect = onVideoDurationFormat,
            )
        }
        SettingsItemDivider()

        // Przewijanie (Scroller):
        SettingsSwitchRow(
            title = stringResource(R.string.scroller_enabled_title),
            modifier = Modifier.settingsSearchTarget(R.string.scroller_enabled_title),
            body = stringResource(R.string.scroller_enabled_body),
            checked = settings.scrollerEnabled,
            onCheckedChange = onScrollerEnabled,
            icon = Icons.Rounded.SwapVert,
            preview = { OptionPreview(OptionPreviewKind.SCROLLER, active = settings.scrollerEnabled) },
        )
        AnimatedVisibility(
            visible = settings.scrollerEnabled,
            enter = fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.settingsSearchTarget(R.string.scroller_position_title),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.scroller_position_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 2.dp, start = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ScrollerPreviewTile(
                            selected = !settings.scrollerOnRight,
                            title = stringResource(R.string.scroller_left),
                            isOnRight = false,
                            device = previewDevice,
                            onClick = { onScrollerOnRight(false) },
                            modifier = Modifier.weight(1f),
                        )
                        ScrollerPreviewTile(
                            selected = settings.scrollerOnRight,
                            title = stringResource(R.string.scroller_right),
                            isOnRight = true,
                            device = previewDevice,
                            onClick = { onScrollerOnRight(true) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // How the grid follows the handle: smoothly or a whole row at a time.
                Column(
                    modifier = Modifier.settingsSearchTarget(R.string.scroller_smooth_title)
                        .fillMaxWidth()
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        OptionPreview(
                            if (settings.scrollerSmooth) OptionPreviewKind.SMOOTH_SCROLL else OptionPreviewKind.ROW_SCROLL,
                            active = true,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.scroller_smooth_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.scroller_smooth_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    SettingsSegmentedRow(
                        options = listOf(
                            true to stringResource(R.string.scroller_mode_smooth),
                            false to stringResource(R.string.scroller_mode_rows),
                        ),
                        selected = settings.scrollerSmooth,
                        onSelect = onScrollerSmooth,
                    )
                }
            }
        }

    }
}

/** "AI search": the switch, the model file, the photo index and the model's licenses. */
@Composable
private fun SmartSearchSettingsCard(settings: AppSettings, ui: SmartSearchSettingsUi) {
    val context = LocalContext.current
    val status = ui.status
    val model = status.model
    val scope = rememberCoroutineScope()
    var confirmRes by remember { mutableStateOf<Int?>(null) }
    var licenses by remember { mutableStateOf<String?>(null) }
    var showAiEffects by rememberSaveable { mutableStateOf(false) }
    var showIndexFolders by rememberSaveable { mutableStateOf(false) }
    val modelSize = (model as? SmartSearch.ModelState.Ready)?.let {
        formatFileSize(it.manifest.totalBytes)
    }
    val aiEffects = settings.aiEffects
    // Switching the AI search on lights a ring around it for a moment.
    var ringTrigger by remember { mutableIntStateOf(0) }
    val ringStyle = aiEffects.style(AiEffect.SETTINGS_RING)
    val ringStrength by rememberTimedGlow(ringTrigger, ringStyle.durationMs)
    SettingsCardContainer {
        SettingsSwitchRow(
            title = stringResource(R.string.smart_search_title),
            modifier = Modifier
                .settingsSearchTarget(R.string.smart_search_title)
                .then(
                    if (ringStyle.enabled && ringStrength > 0f) {
                        Modifier.aiGlowHalo(
                            colors = aiColors(aiEffects, AiEffect.SETTINGS_RING),
                            style = ringStyle,
                            cornerRadius = 12.dp,
                            phase = rememberAiPhase(ringStyle.speed, periodMs = 2_000),
                            strength = { ringStrength },
                            glow = 10.dp,
                        )
                    } else {
                        Modifier
                    },
                ),
            body = stringResource(if (status.ready) R.string.smart_search_body else R.string.smart_search_needs_model),
            checked = settings.smartSearchEnabled,
            onCheckedChange = { on ->
                if (on) ringTrigger++
                ui.onEnable(on)
            },
            icon = Icons.Rounded.AutoAwesome,
            preview = { OptionPreview(OptionPreviewKind.AI_SEARCH, active = settings.smartSearchEnabled) },
        )
        SettingsItemDivider()
        SettingsActionRow(
            title = stringResource(R.string.ai_effects_title),
            modifier = Modifier.settingsSearchTarget(R.string.ai_effects_title),
            body = stringResource(R.string.ai_effects_body),
            icon = Icons.Rounded.Palette,
        )
        SettingsSegmentedRow(
            options = listOf(
                AiEffectsPreset.MULTI_COLOR to stringResource(R.string.ai_preset_multi),
                AiEffectsPreset.SYSTEM to stringResource(R.string.ai_preset_system),
                AiEffectsPreset.CUSTOM to stringResource(R.string.ai_preset_custom),
            ),
            selected = aiEffects.preset,
            onSelect = { preset ->
                ui.onAiEffects { it.copy(preset = preset) }
                if (preset == AiEffectsPreset.CUSTOM) showAiEffects = true
            },
            modifier = Modifier.padding(start = 52.dp, top = 4.dp, bottom = 4.dp),
        )
        if (aiEffects.preset == AiEffectsPreset.CUSTOM) {
            SettingsActionRow(
                title = stringResource(R.string.ai_effects_customize),
                body = stringResource(R.string.ai_effects_customize_body),
                icon = Icons.Rounded.Tune,
                onClick = { showAiEffects = true },
            )
        }
        SettingsItemDivider()
        SettingsActionRow(
            title = stringResource(R.string.smart_model_import),
            modifier = Modifier.settingsSearchTarget(R.string.smart_model_import),
            body = when (model) {
                SmartSearch.ModelState.Missing -> stringResource(R.string.smart_model_missing)
                SmartSearch.ModelState.Installing -> stringResource(R.string.smart_model_installing)
                is SmartSearch.ModelState.Ready ->
                    stringResource(R.string.smart_model_ready, model.manifest.modelId, modelSize.orEmpty(), model.accelerator.name)
                is SmartSearch.ModelState.Failed -> stringResource(R.string.smart_model_failed, model.reason)
            },
            icon = Icons.Rounded.UploadFile,
            onClick = if (model == SmartSearch.ModelState.Installing) null else ui.onImportModel,
            trailingContent = if (model == SmartSearch.ModelState.Installing) {
                { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else {
                null
            },
        )
        if (model is SmartSearch.ModelState.Ready) {
            SettingsItemDivider()
            SettingsActionRow(
                title = stringResource(R.string.smart_index_title),
                modifier = Modifier.settingsSearchTarget(R.string.smart_index_title),
                body = when {
                    settings.smartIndexPaused -> stringResource(R.string.smart_index_status_paused, status.indexed, status.total)
                    status.indexing -> stringResource(R.string.smart_index_status_running, status.indexed, status.total)
                    status.pending > 0 -> stringResource(R.string.smart_index_status_waiting, status.indexed, status.total)
                    else -> stringResource(R.string.smart_index_status, status.indexed, status.total)
                },
                icon = Icons.Rounded.ImageSearch,
                trailingContent = if (settings.smartIndexPaused || status.indexing || status.pending > 0) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (status.indexing && !settings.smartIndexPaused) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            // Paused: resume; otherwise pause, also while it waits for its turn.
                            IconButton(onClick = { ui.onIndexPaused(!settings.smartIndexPaused) }) {
                                Icon(
                                    imageVector = if (settings.smartIndexPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    contentDescription = stringResource(
                                        if (settings.smartIndexPaused) R.string.smart_index_resume else R.string.smart_index_pause,
                                    ),
                                )
                            }
                        }
                    }
                } else {
                    null
                },
            )
            SettingsItemDivider()
            SettingsActionRow(
                title = stringResource(R.string.smart_index_folders_title),
                modifier = Modifier.settingsSearchTarget(R.string.smart_index_folders_title),
                body = stringResource(R.string.smart_index_folders_body),
                icon = Icons.Rounded.FolderOpen,
            )
            SettingsSegmentedRow(
                options = listOf(
                    SmartIndexScope.ALL to stringResource(R.string.smart_index_scope_all),
                    SmartIndexScope.ONLY_SELECTED to stringResource(R.string.smart_index_scope_selected),
                    SmartIndexScope.ALL_EXCEPT to stringResource(R.string.smart_index_scope_except),
                ),
                selected = settings.smartIndexScope,
                onSelect = ui.onIndexScope,
                modifier = Modifier.padding(start = 52.dp, top = 4.dp, bottom = 4.dp),
            )
            if (settings.smartIndexScope != SmartIndexScope.ALL) {
                val exclude = settings.smartIndexScope == SmartIndexScope.ALL_EXCEPT
                val count = if (exclude) settings.smartIndexExcludedFolderIds.size else settings.smartIndexIncludedFolderIds.size
                SettingsActionRow(
                    title = stringResource(if (exclude) R.string.smart_index_choose_excluded else R.string.smart_index_choose_included, count),
                    icon = Icons.Rounded.Checklist,
                    onClick = { showIndexFolders = true },
                )
            }
            SettingsItemDivider()
            SettingsActionRow(
                title = stringResource(R.string.smart_index_clear),
                icon = Icons.Rounded.DeleteSweep,
                onClick = { confirmRes = R.string.smart_index_clear_confirm },
                isDestructive = true,
            )
            SettingsItemDivider()
            SettingsActionRow(
                title = stringResource(R.string.smart_model_remove),
                body = stringResource(R.string.smart_model_remove_body, modelSize.orEmpty()),
                icon = Icons.Rounded.DeleteForever,
                onClick = { confirmRes = R.string.smart_model_remove_confirm },
                isDestructive = true,
            )
            SettingsItemDivider()
            SettingsActionRow(
                title = stringResource(R.string.smart_licenses),
                modifier = Modifier.settingsSearchTarget(R.string.smart_licenses),
                body = stringResource(R.string.smart_licenses_body),
                icon = Icons.Rounded.Gavel,
                onClick = { scope.launch { licenses = ui.loadLicenses().orEmpty() } },
            )
        }
    }
    confirmRes?.let { res ->
        AlertDialog(
            onDismissRequest = { confirmRes = null },
            text = { Text(stringResource(res)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRes = null
                    if (res == R.string.smart_model_remove_confirm) ui.onRemoveModel() else ui.onClearIndex()
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRes = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    licenses?.let { text ->
        SettingsInfoDialog(title = stringResource(R.string.smart_licenses), onDismiss = { licenses = null }) {
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showIndexFolders && settings.smartIndexScope != SmartIndexScope.ALL) {
        SmartIndexFoldersDialog(
            exclude = settings.smartIndexScope == SmartIndexScope.ALL_EXCEPT,
            ui = ui,
            settings = settings,
            onDismiss = { showIndexFolders = false },
        )
    }
    if (showAiEffects) {
        AiEffectsDialog(
            config = aiEffects,
            onChange = ui.onAiEffects,
            onDismiss = { showAiEffects = false },
        )
    }
}

@Composable
private fun FoldersSettingsSectionContent(
    settings: AppSettings,
    albums: List<Album>,
    allAlbums: List<Album>,
    showHeader: Boolean,
    onShowAllFiles: (Boolean) -> Unit,
    onShowOtherFileFolders: (Boolean) -> Unit,
    onHideFavoritesFolder: (Boolean) -> Unit,
    onMultiFolderMemory: (MultiFolderMemory) -> Unit,
    onRememberFolderScrollAcrossSessions: (Boolean) -> Unit,
    onNewContentScrollsToTop: (Boolean) -> Unit,
    onShowNewpipeFolderDialog: () -> Unit,
    onShowFolderHelp: (FolderHelpTopic) -> Unit,
) {
    if (showHeader) {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_category_folders),
            icon = Icons.Rounded.Folder,
        )
    }
    SettingsCardContainer {
        // Pliki inne niż zdjęcia i filmy
        SettingsSwitchRow(
            title = stringResource(R.string.show_all_files_title),
            modifier = Modifier.settingsSearchTarget(R.string.show_all_files_title),
            body = stringResource(R.string.show_all_files_body),
            checked = settings.showAllFiles,
            onCheckedChange = onShowAllFiles,
            icon = Icons.Rounded.InsertDriveFile,
            preview = { OptionPreview(OptionPreviewKind.ALL_FILES, active = settings.showAllFiles) },
        )
        SettingsSwitchRow(
            title = stringResource(R.string.show_other_file_folders_title),
            modifier = Modifier.settingsSearchTarget(R.string.show_other_file_folders_title),
            body = stringResource(R.string.show_other_file_folders_body),
            checked = settings.showOtherFileFolders,
            onCheckedChange = onShowOtherFileFolders,
            icon = Icons.Rounded.FolderOpen,
            preview = { OptionPreview(OptionPreviewKind.OTHER_FILE_FOLDERS, active = settings.showOtherFileFolders) },
        )

        SettingsItemDivider()

        // Ukrywanie folderu Ulubione
        SettingsSwitchRow(
            title = stringResource(R.string.hide_favorites_folder),
            modifier = Modifier.settingsSearchTarget(R.string.hide_favorites_folder),
            body = stringResource(R.string.settings_hide_favorites_short),
            checked = settings.hideFavoritesFolder,
            onCheckedChange = onHideFavoritesFolder,
            onInfoClick = { onShowFolderHelp(FolderHelpTopic.FAVORITES) },
            icon = Icons.Rounded.Star,
            preview = { OptionPreview(OptionPreviewKind.HIDE_FAVORITES, active = settings.hideFavoritesFolder) },
        )

        SettingsItemDivider()

        if (settings.extraViewSettingsShown) {
            // Pamięć otwartych paneli (multiFolderMemory)
            Column(
                modifier = Modifier.settingsSearchTarget(R.string.multi_folder_memory_title)
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Dashboard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.multi_folder_memory_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.multi_folder_memory_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onShowFolderHelp(FolderHelpTopic.MEMORY) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = stringResource(R.string.settings_help_for, stringResource(R.string.multi_folder_memory_title)),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                SettingsSegmentedRow(
                    options = listOf(
                        MultiFolderMemory.FRESH to stringResource(R.string.multi_folder_memory_fresh),
                        MultiFolderMemory.REMEMBER to stringResource(R.string.multi_folder_memory_remember),
                    ),
                    selected = settings.multiFolderMemory,
                    onSelect = onMultiFolderMemory,
                )
            }

            SettingsItemDivider()
        }

        // Zapamiętywanie pozycji przewijania w folderach
        SettingsSwitchRow(
            title = stringResource(R.string.remember_folder_scroll),
            modifier = Modifier.settingsSearchTarget(R.string.remember_folder_scroll),
            body = stringResource(R.string.remember_folder_scroll_body),
            checked = settings.rememberFolderScrollAcrossSessions,
            onCheckedChange = onRememberFolderScrollAcrossSessions,
            onInfoClick = { onShowFolderHelp(FolderHelpTopic.SCROLL) },
            icon = Icons.Rounded.History,
            preview = { OptionPreview(OptionPreviewKind.REMEMBER_SCROLL, active = settings.rememberFolderScrollAcrossSessions) },
        )

        SettingsItemDivider()

        // Nowa zawartość przenosi na górę widoku
        SettingsSwitchRow(
            title = stringResource(R.string.new_content_scrolls_to_top),
            modifier = Modifier.settingsSearchTarget(R.string.new_content_scrolls_to_top),
            body = stringResource(R.string.new_content_scrolls_to_top_body),
            checked = settings.newContentScrollsToTop,
            onCheckedChange = onNewContentScrollsToTop,
            onInfoClick = { onShowFolderHelp(FolderHelpTopic.NEW_CONTENT) },
            icon = Icons.Rounded.ArrowUpward,
            preview = { OptionPreview(OptionPreviewKind.NEW_CONTENT_TOP, active = settings.newContentScrollsToTop) },
        )

        SettingsItemDivider()

        // Folder pobrań NewPipe: nowe pliki widać od razu
        val newpipePath = settings.newpipeFolderRelativePath.trim().trim('/')
        val newpipeAlbum = (albums + allAlbums).firstOrNull { album ->
            album.relativePath.trim().trim('/').equals(newpipePath, ignoreCase = true)
        }
        SettingsActionRow(
            title = stringResource(R.string.newpipe_folder_title),
            modifier = Modifier.settingsSearchTarget(R.string.newpipe_folder_title),
            body = when {
                newpipeAlbum != null -> newpipeAlbum.name
                newpipePath.isNotEmpty() -> newpipePath
                else -> stringResource(R.string.newpipe_folder_empty)
            },
            icon = Icons.Rounded.FileDownload,
            preview = { OptionPreview(OptionPreviewKind.NEWPIPE, active = newpipePath.isNotEmpty()) },
            onClick = onShowNewpipeFolderDialog,
        )
    }
}

@Composable
private fun PrivacySettingsSectionContent(
    settings: AppSettings,
    albums: List<Album>,
    showHeader: Boolean,
    onShowHidden: (Boolean) -> Unit,
    onShowFolderHelp: (FolderHelpTopic) -> Unit,
    onUpdateHiddenCover: ((dev.ander.alexet.data.HiddenCoverConfig) -> dev.ander.alexet.data.HiddenCoverConfig) -> Unit,
    onPickHiddenCoverImage: (android.net.Uri) -> Unit,
    onRandomizeFileNamesInPrivateFolders: (Boolean) -> Unit,
    onStripExifInPrivateFolders: (Boolean) -> Unit,
    onStripExifOnShare: (Boolean) -> Unit,
    onShowPrivatizedFoldersDialog: () -> Unit,
    onShowPrivacyHelp: () -> Unit,
) {
    if (showHeader) {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_category_privacy),
            icon = Icons.Rounded.Security,
            onInfoClick = onShowPrivacyHelp,
            infoDescription = stringResource(R.string.settings_help_for, stringResource(SettingsCategory.PRIVACY.titleRes)),
        )
    }
    SettingsCardContainer {
        // Skarbiec AES-GCM (.enc) info row
        SettingsActionRow(
            title = stringResource(R.string.secure_folder_settings_title),
            modifier = Modifier.settingsSearchTarget(R.string.secure_folder_settings_title),
            body = stringResource(R.string.secure_folder_settings_desc),
            icon = Icons.Rounded.Lock,
        )

        SettingsItemDivider()

        // Ukryte pliki i foldery (.nomedia)
        SettingsSwitchRow(
            title = stringResource(R.string.show_hidden),
            modifier = Modifier.settingsSearchTarget(R.string.show_hidden),
            body = stringResource(if (settings.showHidden && !rememberAllFilesAccess()) R.string.show_hidden_no_access else R.string.show_hidden_body),
            checked = settings.showHidden,
            onCheckedChange = onShowHidden,
            onInfoClick = { onShowFolderHelp(FolderHelpTopic.HIDDEN) },
            icon = Icons.Rounded.VisibilityOff,
            preview = { OptionPreview(OptionPreviewKind.SHOW_HIDDEN, active = settings.showHidden) },
        )

        SettingsItemDivider()

        // Okładki ukrytych folderów (.nomedia) z filtrem
        var showHiddenCover by rememberSaveable { mutableStateOf(false) }
        SettingsActionRow(
            title = stringResource(R.string.hidden_cover_title),
            modifier = Modifier.settingsSearchTarget(R.string.hidden_cover_title),
            body = stringResource(hiddenCoverStyleName(settings.hiddenCover.style)),
            icon = Icons.Rounded.BlurOn,
            onClick = { showHiddenCover = true },
        )
        SettingsSubPage(open = showHiddenCover, onClose = { showHiddenCover = false }) { onBack ->
            HiddenCoverSettingsPage(
                config = settings.hiddenCover,
                albums = albums,
                onUpdate = onUpdateHiddenCover,
                onPickImage = onPickHiddenCoverImage,
                onBack = onBack,
            )
        }

        SettingsItemDivider()

        // Mikser nazw (lista wybranych folderów)
        SettingsActionRow(
            title = stringResource(R.string.privatized_folders_title),
            modifier = Modifier.settingsSearchTarget(R.string.privatized_folders_title),
            body = if (settings.anonymousFolderIds.isEmpty()) {
                stringResource(R.string.privatized_folders_empty)
            } else {
                stringResource(R.string.privatized_folders_count, settings.anonymousFolderIds.size)
            },
            icon = Icons.Rounded.Folder,
            onClick = onShowPrivatizedFoldersDialog,
            onInfoClick = onShowPrivacyHelp,
        )

        SettingsItemDivider()

        // Losowe nazwy plików w mikserze
        SettingsSwitchRow(
            title = stringResource(R.string.randomize_file_names_title),
            modifier = Modifier.settingsSearchTarget(R.string.randomize_file_names_title),
            body = stringResource(R.string.randomize_file_names_body),
            checked = settings.randomizeFileNamesInPrivateFolders,
            onCheckedChange = onRandomizeFileNamesInPrivateFolders,
            icon = Icons.Rounded.AutoFixHigh,
            preview = { OptionPreview(OptionPreviewKind.RANDOM_NAMES, active = settings.randomizeFileNamesInPrivateFolders) },
            onInfoClick = onShowPrivacyHelp,
        )

        SettingsItemDivider()

        // Usuwanie EXIF w mikserze
        SettingsSwitchRow(
            title = stringResource(R.string.strip_exif_title),
            modifier = Modifier.settingsSearchTarget(R.string.strip_exif_title),
            body = stringResource(R.string.strip_exif_in_private_folders_body),
            checked = settings.stripExifInPrivateFolders,
            onCheckedChange = onStripExifInPrivateFolders,
            icon = Icons.Rounded.Security,
            preview = { OptionPreview(OptionPreviewKind.STRIP_EXIF, active = settings.stripExifInPrivateFolders) },
            onInfoClick = onShowPrivacyHelp,
        )

        SettingsItemDivider()

        // Usuwanie EXIF przy udostępnianiu
        SettingsSwitchRow(
            title = stringResource(R.string.strip_exif_on_share_title),
            modifier = Modifier.settingsSearchTarget(R.string.strip_exif_on_share_title),
            body = stringResource(R.string.strip_exif_on_share_body),
            checked = settings.stripExifOnShare,
            onCheckedChange = onStripExifOnShare,
            icon = Icons.Rounded.Share,
            preview = { OptionPreview(OptionPreviewKind.STRIP_EXIF_SHARE, active = settings.stripExifOnShare) },
            onInfoClick = onShowPrivacyHelp,
        )
    }
}

@Composable
private fun PlayerSettingsSectionContent(
    settings: AppSettings,
    showHeader: Boolean,
    onVideoDoubleClickToGallery: (Boolean) -> Unit,
    onUpdatePlayerConfig: ((dev.ander.alexet.integration.AnderLineConfig) -> dev.ander.alexet.integration.AnderLineConfig) -> Unit,
    onAutoplayGifs: (Boolean) -> Unit,
    onShowNavArrows: (Boolean) -> Unit,
    onShowBehaviorHelp: () -> Unit,
) {
    if (showHeader) {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_category_player),
            icon = Icons.Rounded.PlayCircle,
            onInfoClick = onShowBehaviorHelp,
            infoDescription = stringResource(R.string.settings_help_for, stringResource(SettingsCategory.VIEWING.titleRes)),
        )
    }
    SettingsCardContainer {
        // Autoodtwarzanie GIF
        SettingsSwitchRow(
            title = stringResource(R.string.autoplay_gifs),
            modifier = Modifier.settingsSearchTarget(R.string.autoplay_gifs),
            body = stringResource(R.string.autoplay_gifs_body),
            checked = settings.autoplayGifs,
            onCheckedChange = onAutoplayGifs,
            icon = Icons.Rounded.Animation,
            preview = { PlayerOptionPreview(PlayerPreviewKind.GIF, active = settings.autoplayGifs) },
        )

        SettingsItemDivider()

        // Strzałki nawigacyjne
        SettingsSwitchRow(
            title = stringResource(R.string.nav_arrows_title),
            modifier = Modifier.settingsSearchTarget(R.string.nav_arrows_title),
            body = stringResource(R.string.nav_arrows_body),
            checked = settings.showNavArrows,
            onCheckedChange = onShowNavArrows,
            icon = Icons.Rounded.SwapHoriz,
            preview = { PlayerOptionPreview(PlayerPreviewKind.NAV_ARROWS, active = settings.showNavArrows) },
        )

        SettingsItemDivider()

        // Podwójne kliknięcie -> odtwarzacz Alexet
        SettingsSwitchRow(
            title = stringResource(R.string.video_double_click_gallery),
            modifier = Modifier.settingsSearchTarget(R.string.video_double_click_gallery),
            body = stringResource(R.string.video_double_click_gallery_body),
            checked = settings.videoDoubleClickToGallery,
            onCheckedChange = onVideoDoubleClickToGallery,
            icon = Icons.Rounded.PlayCircle,
            preview = { PlayerOptionPreview(PlayerPreviewKind.DOUBLE_CLICK_PLAYER, active = settings.videoDoubleClickToGallery) },
        )

        SettingsItemDivider()

        // Obraz w obrazie (przycisk w odtwarzaczu)
        SettingsSwitchRow(
            title = stringResource(R.string.pip_title),
            modifier = Modifier.settingsSearchTarget(R.string.pip_title),
            body = stringResource(R.string.pip_body),
            checked = settings.playerConfig.pipButton,
            onCheckedChange = { checked ->
                onUpdatePlayerConfig { it.copy(pipButton = checked) }
            },
            icon = Icons.Rounded.PictureInPictureAlt,
            preview = { PlayerOptionPreview(PlayerPreviewKind.PIP, active = settings.playerConfig.pipButton) },
        )

        SettingsItemDivider()

        // Odtwarzanie w tle
        SettingsSwitchRow(
            title = stringResource(R.string.background_play_title),
            modifier = Modifier.settingsSearchTarget(R.string.background_play_title),
            body = stringResource(R.string.background_play_body),
            checked = settings.playerConfig.autoBackgroundPlay,
            onCheckedChange = { checked ->
                onUpdatePlayerConfig { it.copy(autoBackgroundPlay = checked) }
            },
            icon = Icons.Rounded.Headphones,
            preview = { PlayerOptionPreview(PlayerPreviewKind.BACKGROUND_PLAY, active = settings.playerConfig.autoBackgroundPlay) },
        )

        SettingsItemDivider()

        Text(
            text = stringResource(R.string.player_gestures_section_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )

        // Gesty głośności
        SettingsSwitchRow(
            title = stringResource(R.string.player_volume_gesture_title),
            modifier = Modifier.settingsSearchTarget(R.string.player_volume_gesture_title),
            body = stringResource(R.string.player_volume_gesture_body),
            checked = settings.playerConfig.enableVolumeSwipeGesture,
            onCheckedChange = { checked ->
                onUpdatePlayerConfig { it.copy(enableVolumeSwipeGesture = checked) }
            },
            icon = Icons.AutoMirrored.Rounded.VolumeUp,
            preview = { PlayerOptionPreview(PlayerPreviewKind.VOLUME, active = settings.playerConfig.enableVolumeSwipeGesture) },
        )

        SettingsItemDivider()

        // Gesty jasności
        SettingsSwitchRow(
            title = stringResource(R.string.player_brightness_gesture_title),
            modifier = Modifier.settingsSearchTarget(R.string.player_brightness_gesture_title),
            body = stringResource(R.string.player_brightness_gesture_body),
            checked = settings.playerConfig.enableBrightnessSwipeGesture,
            onCheckedChange = { checked ->
                onUpdatePlayerConfig { it.copy(enableBrightnessSwipeGesture = checked) }
            },
            icon = Icons.Rounded.BrightnessMedium,
            preview = { PlayerOptionPreview(PlayerPreviewKind.BRIGHTNESS, active = settings.playerConfig.enableBrightnessSwipeGesture) },
        )

        SettingsItemDivider()

        // Przyspieszenie przytrzymaniem
        SettingsSwitchRow(
            title = stringResource(R.string.player_long_press_speed_title),
            modifier = Modifier.settingsSearchTarget(R.string.player_long_press_speed_title),
            body = stringResource(R.string.player_long_press_speed_body),
            checked = settings.playerConfig.useLongPressControls,
            onCheckedChange = { checked ->
                onUpdatePlayerConfig { it.copy(useLongPressControls = checked) }
            },
            icon = Icons.Rounded.Speed,
            preview = {
                PlayerOptionPreview(
                    PlayerPreviewKind.LONG_PRESS,
                    active = settings.playerConfig.useLongPressControls,
                    speed = settings.playerConfig.longPressControlsSpeed,
                )
            },
        )

        AnimatedVisibility(
            visible = settings.playerConfig.useLongPressControls,
            enter = fadeIn(animationSpec = tween(200)) + expandVertically(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_long_press_speed_selector),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SettingsSegmentedRow(
                    options = listOf(
                        1.5f to "1.5x",
                        2.0f to "2.0x",
                        2.5f to "2.5x",
                        3.0f to "3.0x",
                    ),
                    selected = if (settings.playerConfig.longPressControlsSpeed in listOf(1.5f, 2.0f, 2.5f, 3.0f)) {
                        settings.playerConfig.longPressControlsSpeed
                    } else 2.0f,
                    onSelect = { speed ->
                        onUpdatePlayerConfig { it.copy(longPressControlsSpeed = speed) }
                    },
                )
            }
        }

        SettingsItemDivider()

        // Przewijanie dwukrotnym stuknięciem
        SettingsSwitchRow(
            title = stringResource(R.string.player_double_tap_seek_title),
            modifier = Modifier.settingsSearchTarget(R.string.player_double_tap_seek_title),
            body = stringResource(R.string.player_double_tap_seek_body),
            checked = settings.playerConfig.enableDoubleTapSeek,
            onCheckedChange = { checked ->
                onUpdatePlayerConfig { it.copy(enableDoubleTapSeek = checked) }
            },
            icon = Icons.Rounded.FastForward,
            preview = {
                PlayerOptionPreview(
                    PlayerPreviewKind.DOUBLE_TAP,
                    active = settings.playerConfig.enableDoubleTapSeek,
                    seekSeconds = settings.playerConfig.seekIncrement,
                )
            },
        )

        AnimatedVisibility(
            visible = settings.playerConfig.enableDoubleTapSeek,
            enter = fadeIn(animationSpec = tween(200)) + expandVertically(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_double_tap_seek_selector),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SettingsSegmentedRow(
                    options = listOf(
                        5 to "5s",
                        10 to "10s",
                        15 to "15s",
                        30 to "30s",
                    ),
                    selected = if (settings.playerConfig.seekIncrement in listOf(5, 10, 15, 30)) {
                        settings.playerConfig.seekIncrement
                    } else 10,
                    onSelect = { sec ->
                        onUpdatePlayerConfig { it.copy(seekIncrement = sec) }
                    },
                )
            }
        }

        SettingsItemDivider()

        // Czas ukrywania kontrolek
        Column(
            modifier = Modifier.settingsSearchTarget(R.string.player_auto_hide_selector)
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlayerOptionPreview(
                    PlayerPreviewKind.AUTO_HIDE,
                    active = true,
                    hideSeconds = settings.playerConfig.controllerAutoHideTimeout,
                )
                Text(
                    text = stringResource(R.string.player_auto_hide_selector),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            SettingsSegmentedRow(
                options = listOf(
                    2 to "2s",
                    3 to "3s",
                    4 to "4s",
                    5 to "5s",
                ),
                selected = if (settings.playerConfig.controllerAutoHideTimeout in listOf(2, 3, 4, 5)) {
                    settings.playerConfig.controllerAutoHideTimeout
                } else 4,
                onSelect = { sec ->
                    onUpdatePlayerConfig { it.copy(controllerAutoHideTimeout = sec) }
                },
            )
        }

        SettingsItemDivider()

        // SponsorBlock: its options open on a page of their own
        var showSponsorBlock by rememberSaveable { mutableStateOf(false) }
        val sponsorBlockAutoSkipCount = SponsorBlockCategory.entries.count { it in settings.playerConfig.sponsorBlockAutoSkip }
        SettingsActionRow(
            title = stringResource(R.string.sponsorblock),
            modifier = Modifier.settingsSearchTarget(R.string.sponsorblock),
            body = if (settings.playerConfig.sponsorBlockEnabled) {
                stringResource(R.string.sponsorblock_settings_on, sponsorBlockAutoSkipCount, SponsorBlockCategory.entries.size)
            } else {
                stringResource(R.string.sponsorblock_settings_off)
            },
            icon = Icons.Rounded.FastForward,
            preview = { OptionPreview(OptionPreviewKind.SPONSORBLOCK, active = settings.playerConfig.sponsorBlockEnabled) },
            onClick = { showSponsorBlock = true },
        )
        SettingsSubPage(open = showSponsorBlock, onClose = { showSponsorBlock = false }) { onBack ->
            SponsorBlockSettingsPage(
                config = settings.playerConfig,
                onUpdatePlayerConfig = onUpdatePlayerConfig,
                onBack = onBack,
            )
        }
    }
}

/** Selecting files on the grid and what the action bar offers for them. */
@Composable
private fun SelectionSettingsSectionContent(
    settings: AppSettings,
    onFileSelectMode: (FileSelectMode) -> Unit,
    onOperationsFollowSelectionOrder: (Boolean) -> Unit,
    onSaveSelectionActionsConfig: (List<SelectionAction?>, List<SelectionAction>, Set<SelectionAction>) -> Unit,
    onToggleSelectionActionVisibility: (SelectionAction) -> Unit,
    onShowSelectHelp: () -> Unit,
    onShowActionCardsHelp: () -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
) {
    SettingsCardContainer {
        // Styl zaznaczania na siatce (Prostokąt / Liniowy)
        Column(
            modifier = Modifier.settingsSearchTarget(R.string.settings_select_style_label)
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OptionPreview(
                    OptionPreviewKind.SELECT_STYLE,
                    active = true,
                    linearSelect = settings.fileSelectMode == FileSelectMode.LINEAR,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = stringResource(R.string.settings_select_style_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onShowSelectHelp,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.file_select_help),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            SettingsSegmentedRow(
                options = listOf(
                    FileSelectMode.RECTANGLE to stringResource(R.string.file_select_rectangle),
                    FileSelectMode.LINEAR to stringResource(R.string.file_select_linear),
                ),
                selected = settings.fileSelectMode,
                onSelect = onFileSelectMode,
            )
        }

        SettingsItemDivider()

        // Kolejność operacji wg zaznaczenia
        SettingsSwitchRow(
            title = stringResource(R.string.operations_follow_selection_order),
            modifier = Modifier.settingsSearchTarget(R.string.operations_follow_selection_order),
            body = stringResource(R.string.operations_follow_selection_order_body) +
                "\n" + stringResource(R.string.operations_follow_selection_order_info),
            checked = settings.operationsFollowSelectionOrder,
            onCheckedChange = onOperationsFollowSelectionOrder,
            icon = Icons.AutoMirrored.Rounded.Sort,
            preview = { OptionPreview(OptionPreviewKind.SELECTION_ORDER, active = settings.operationsFollowSelectionOrder) },
        )

        SettingsItemDivider()

        // Karty akcji paska (Drag & Drop)
        Row(
            modifier = Modifier.settingsSearchTarget(R.string.action_cards_order).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.action_cards_order),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.action_cards_order_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onShowActionCardsHelp,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = stringResource(R.string.action_cards_order),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        ActionCardsConfigSection(
            barSlots = settings.barActionSlots,
            menuOrder = settings.menuActionOrder,
            hiddenActions = settings.hiddenSelectionActions,
            onSaveConfig = onSaveSelectionActionsConfig,
            onToggleVisibility = onToggleSelectionActionVisibility,
            onDraggingChanged = onDraggingChanged,
        )
    }
}

/** Search results, and the AI search with its model and photo index. */
@Composable
private fun SearchSettingsSectionContent(
    settings: AppSettings,
    onSearchFilesFullScreen: (Boolean) -> Unit,
    smartSearch: SmartSearchSettingsUi?,
) {
    SettingsCardContainer {
        // Pełnoekranowe wyszukiwanie plików
        SettingsSwitchRow(
            title = stringResource(R.string.search_files_fullscreen),
            modifier = Modifier.settingsSearchTarget(R.string.search_files_fullscreen),
            body = stringResource(R.string.search_files_fullscreen_body),
            checked = settings.searchFilesFullScreen,
            onCheckedChange = onSearchFilesFullScreen,
            icon = Icons.Rounded.Fullscreen,
            preview = { OptionPreview(OptionPreviewKind.SEARCH_FULLSCREEN, active = settings.searchFilesFullScreen) },
        )
    }
    if (smartSearch != null) {
        Spacer(Modifier.height(12.dp))
        SmartSearchSettingsCard(settings, smartSearch)
    }
}

@Composable
private fun SystemSettingsSectionContent(
    settings: AppSettings,
    debugLogging: Boolean = false,
    showHeader: Boolean,
    onLightMode: (Boolean) -> Unit,
    onHighResThumbnails: (Boolean) -> Unit,
    onShowWallpaperResolutionsDialog: () -> Unit,
    onExportConfig: () -> Unit,
    onDebugLoggingChange: (Boolean) -> Unit = {},
    onClearDebugLog: () -> Unit = {},
    onImportConfig: () -> Unit,
    onResetThumbnails: () -> Unit,
    onShowMaintenanceHelp: () -> Unit = {},
) {
    if (showHeader) {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_category_system),
            icon = Icons.Rounded.SaveAlt,
            onInfoClick = onShowMaintenanceHelp,
            infoDescription = stringResource(R.string.settings_help_for, stringResource(SettingsCategory.APP.titleRes)),
        )
    }
    SettingsCardContainer {
        // Tryb lekki
        SettingsSwitchRow(
            title = stringResource(R.string.light_mode_title),
            modifier = Modifier.settingsSearchTarget(R.string.light_mode_title),
            body = stringResource(R.string.light_mode_body),
            checked = settings.lightMode,
            onCheckedChange = onLightMode,
            icon = Icons.Rounded.Speed,
        )
        SettingsItemDivider()
        // Miniatury
        SettingsSwitchRow(
            title = stringResource(R.string.thumbnails_high_res_title),
            modifier = Modifier.settingsSearchTarget(R.string.thumbnails_high_res_title),
            body = stringResource(R.string.thumbnails_high_res_body),
            checked = settings.highResThumbnails,
            onCheckedChange = onHighResThumbnails,
            icon = Icons.Rounded.PhotoSizeSelectLarge,
            preview = { OptionPreview(OptionPreviewKind.SHARP_THUMBNAILS, active = settings.highResThumbnails) },
        )
        if (settings.foldOnlySettingsShown) {
            SettingsItemDivider()

            // Rozdzielczości tapet
            val wallpaperResSubtitle = if (settings.customWallpaperFoldedWidth > 0 || settings.customWallpaperUnfoldedWidth > 0) {
                stringResource(R.string.wallpaper_res_custom_label) + ": " +
                    "${settings.customWallpaperFoldedWidth}×${settings.customWallpaperFoldedHeight} / " +
                    "${settings.customWallpaperUnfoldedWidth}×${settings.customWallpaperUnfoldedHeight}"
            } else {
                stringResource(R.string.wallpaper_res_mode_auto) +
                    if (settings.lastDetectedFoldedWidth > 0 || settings.lastDetectedUnfoldedWidth > 0) {
                        " (${settings.lastDetectedFoldedWidth}×${settings.lastDetectedFoldedHeight} / ${settings.lastDetectedUnfoldedWidth}×${settings.lastDetectedUnfoldedHeight})"
                    } else ""
            }
            SettingsActionRow(
                title = stringResource(R.string.wallpaper_resolutions_title),
                modifier = Modifier.settingsSearchTarget(R.string.wallpaper_resolutions_title),
                body = wallpaperResSubtitle,
                icon = Icons.Rounded.Wallpaper,
                onClick = onShowWallpaperResolutionsDialog,
            )
        }
        SettingsItemDivider()
        DuplicatesSettingsRow()
        SettingsItemDivider()
        SettingsActionRow(
            title = stringResource(R.string.config_export),
            modifier = Modifier.settingsSearchTarget(R.string.config_export),
            body = stringResource(R.string.config_export_body),
            icon = Icons.Rounded.FileUpload,
            onClick = onExportConfig,
        )
        SettingsItemDivider()
        SettingsActionRow(
            title = stringResource(R.string.config_import),
            modifier = Modifier.settingsSearchTarget(R.string.config_import),
            body = stringResource(R.string.config_import_body),
            icon = Icons.Rounded.FileDownload,
            onClick = onImportConfig,
        )
        SettingsItemDivider()
        SettingsActionRow(
            title = stringResource(R.string.rescan),
            modifier = Modifier.settingsSearchTarget(R.string.rescan),
            body = stringResource(R.string.rescan_body),
            icon = Icons.Rounded.RestartAlt,
            onClick = onResetThumbnails,
            isDestructive = true,
        )
        SettingsItemDivider()
        UpdatesSettingsRows(settings)
        SettingsItemDivider()
        var confirmDebugLogging by remember { mutableStateOf(false) }
        if (confirmDebugLogging) {
            AlertDialog(
                onDismissRequest = { confirmDebugLogging = false },
                icon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
                title = { Text(stringResource(R.string.debug_logging_confirm_title)) },
                text = { Text(stringResource(R.string.debug_logging_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDebugLogging = false
                        onDebugLoggingChange(true)
                    }) { Text(stringResource(R.string.debug_logging_enable)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDebugLogging = false }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        SettingsSwitchRow(
            title = stringResource(R.string.debug_logging),
            modifier = Modifier.settingsSearchTarget(R.string.debug_logging),
            body = stringResource(R.string.debug_logging_body),
            checked = debugLogging,
            onCheckedChange = { enable -> if (enable) confirmDebugLogging = true else onDebugLoggingChange(false) },
            icon = Icons.Rounded.BugReport,
        )
        if (debugLogging) {
            SettingsItemDivider()
            SettingsActionRow(
                title = stringResource(R.string.debug_log_clear),
                modifier = Modifier.settingsSearchTarget(R.string.debug_log_clear),
                body = stringResource(R.string.debug_log_clear_body),
                icon = Icons.Rounded.DeleteSweep,
                onClick = onClearDebugLog,
            )
        }
    }
}

@Composable
private fun SettingsCardContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    icon: ImageVector,
    onInfoClick: (() -> Unit)? = null,
    infoDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (onInfoClick != null) {
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = infoDescription ?: stringResource(R.string.details),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun SettingsItemDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 52.dp,
) {
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent, top = 8.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )
}

/** All-files access, looked at again whenever the screen comes back (from the system screen that grants it). */
@Composable
private fun rememberAllFilesAccess(): Boolean {
    var granted by remember { mutableStateOf(dev.ander.alexet.data.StorageAccess.canReadAllFiles()) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        granted = dev.ander.alexet.data.StorageAccess.canReadAllFiles()
    }
    return granted
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    body: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    onInfoClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Shown instead of [icon]: a live picture of the option. */
    preview: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (preview != null) {
            preview()
        } else if (icon != null) {
            Surface(
                shape = CircleShape,
                color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!body.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onInfoClick != null) {
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    body: String? = null,
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
    onInfoClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    isDestructive: Boolean = false,
    /** Shown instead of [icon]: a live picture of the option. */
    preview: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (preview != null) {
            preview()
        } else Surface(
            shape = CircleShape,
            color = if (isDestructive) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(38.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (!body.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onInfoClick != null) {
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (trailingContent != null) {
            trailingContent()
        } else if (onClick != null) {
            // Only a row that opens or does something gets the arrow, not a heading or a status.
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SettingsRadioOption(
    selected: Boolean,
    title: String,
    body: String? = null,
    icon: ImageVector? = null,
    badge: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (badge != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (!body.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCompactStepper(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    label: String? = null,
    suffix: String? = null,
    step: Int = 1,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.height(42.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 4.dp),
        ) {
            IconButton(
                onClick = { if (value > range.first) onValueChange((value - step).coerceAtLeast(range.first)) },
                enabled = value > range.first,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = stringResource(R.string.settings_decrease),
                    modifier = Modifier.size(18.dp),
                )
            }
            val displayText = when {
                suffix != null && label != null -> "$label: $value$suffix"
                suffix != null -> "$value$suffix"
                label == "%" -> "$value%"
                label != null -> "$label: $value"
                else -> "$value"
            }
            Text(
                text = displayText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 36.dp)
                    .padding(horizontal = 4.dp),
            )
            IconButton(
                onClick = { if (value < range.last) onValueChange((value + step).coerceAtMost(range.last)) },
                enabled = value < range.last,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.settings_increase),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
internal fun <T> SettingsSegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { (option, label) ->
                val isSelected = option == selected
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    tonalElevation = if (isSelected) 2.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(11.dp))
                        .clickable { onSelect(option) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsInfoDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok), fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        },
    )
}

@Composable
private fun HelpFeatureCard(
    title: String,
    body: String,
    badge: String? = null,
    graphic: @Composable () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (badge != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                graphic()
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MiniKafelkiGrid(
    columns: Int = 3,
    staircase: Boolean = false,
    rows: Int? = null,
    tileHeight: Dp = 22.dp,
    /** Rows left out of the ones that fit (auto rows only). */
    fewerRows: Int = 0,
    modifier: Modifier = Modifier,
) {
    val spacing = 2.dp
    val safeCols = columns.coerceIn(1, 10)

    if (rows != null) {
        val rowCount = rows.coerceAtLeast(1)
        Column(
            modifier = modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            repeat(rowCount) { rowIdx ->
                val itemsInRow = if (staircase && safeCols > 1) {
                    if (rowIdx == rowCount - 1) {
                        (safeCols - 2).coerceAtLeast(1)
                    } else {
                        safeCols
                    }
                } else {
                    safeCols
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    repeat(safeCols) { colIdx ->
                        if (colIdx < itemsInRow) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(tileHeight)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(2.dp),
                                    ),
                            )
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(tileHeight),
                            )
                        }
                    }
                }
            }
        }
    } else {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .padding(2.dp)
                .clipToBounds(),
        ) {
            val totalSpacing = spacing * (safeCols - 1)
            val itemWidth = (maxWidth - totalSpacing) / safeCols
            val autoRows = if (itemWidth > 0.dp) {
                ((maxHeight + spacing) / (itemWidth + spacing)).toInt().coerceAtLeast(1)
            } else {
                3
            }
            val shownRows = (autoRows - fewerRows).coerceAtLeast(1)
            val rowCount = if (staircase && safeCols > 1) shownRows.coerceAtLeast(3) else shownRows

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                repeat(rowCount) { rowIdx ->
                    val itemsInRow = if (staircase && safeCols > 1) {
                        if (rowIdx == rowCount - 1) {
                            (safeCols - 1).coerceAtLeast(1)
                        } else {
                            safeCols
                        }
                    } else {
                        safeCols
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                    ) {
                        repeat(safeCols) { colIdx ->
                            if (colIdx < itemsInRow) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .background(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                            shape = RoundedCornerShape(2.dp),
                                        ),
                                    )
                            } else {
                                Spacer(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniMediaGrid(
    columns: Int = 3,
    rows: Int? = null,
    tileHeight: Dp = 22.dp,
    /** Rows left out of the ones that fit (auto rows only). */
    fewerRows: Int = 0,
    modifier: Modifier = Modifier,
) {
    val spacing = 2.dp
    val safeCols = columns.coerceIn(1, 12)

    if (rows != null) {
        val rowCount = rows.coerceAtLeast(1)
        Column(
            modifier = modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            repeat(rowCount) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    repeat(safeCols) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(tileHeight)
                                .background(
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
            }
        }
    } else {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .padding(2.dp)
                .clipToBounds(),
        ) {
            val totalSpacing = spacing * (safeCols - 1)
            val itemWidth = (maxWidth - totalSpacing) / safeCols
            val rowCount = if (itemWidth > 0.dp) {
                (((maxHeight + spacing) / (itemWidth + spacing)).toInt() - fewerRows).coerceAtLeast(1)
            } else {
                3
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                repeat(rowCount) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                    ) {
                        repeat(safeCols) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(2.dp),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelSplitPreviewTile(
    panelSplitPercent: Int,
    isBookMode: Boolean,
    folderColumns: Int = 3,
    fileColumns: Int = 3,
    device: PreviewDevice = PreviewDevice.TABLET,
    modifier: Modifier = Modifier,
) {
    val targetRatio = targetPanelSplitRatio(panelSplitPercent, isBookMode)
    val animatedRatio by animateFloatAsState(
        targetValue = targetRatio,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy,
        ),
        label = "panelSplitPreviewRatio",
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A phone shows one pane at a time: folders, then the folder's content.
            if (device == PreviewDevice.PHONE) {
                PhoneFlowPreview(folderColumns = folderColumns, fileColumns = fileColumns, width = 84.dp, height = 150.dp)
            } else Box(
                modifier = Modifier
                    .height(150.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(animatedRatio.coerceIn(0.1f, 0.9f))
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                            .padding(2.dp),
                    ) {
                        MiniKafelkiGrid(columns = folderColumns, staircase = true, fewerRows = 1)
                    }

                    // Minimal hinge divider line matching device appearance
                    Box(
                        modifier = Modifier
                            .fillMaxHeight(0.9f)
                            .width(1.dp)
                            .background(
                                if (isBookMode) {
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                },
                            ),
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight((1f - animatedRatio).coerceIn(0.1f, 0.9f))
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                            .padding(2.dp),
                    ) {
                        MiniMediaGrid(columns = fileColumns, fewerRows = 1)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.pane_split_folders),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Icon(
                        imageVector = Icons.Rounded.GridView,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = stringResource(R.string.pane_split_content),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isBookMode) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.pane_split_fifty),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldableModeTile(
    selected: Boolean,
    title: String,
    badge: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shown instead of [graphic] when the phone is previewed. */
    phoneGraphic: (@Composable () -> Unit)? = null,
    graphic: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (phoneGraphic != null) phoneGraphic() else graphic()
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                if (badge != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DualPaneMiniGraphic(
    leftRatio: Float,
    rightRatio: Float,
    rightHasContent: Boolean,
    rightLabel: String,
) {
    Row(
        modifier = Modifier
            .size(width = 140.dp, height = 65.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(leftRatio)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
        ) {
            MiniKafelkiGrid()
        }
        Box(
            modifier = Modifier
                .weight(rightRatio)
                .fillMaxSize()
                .background(
                    if (rightHasContent) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(4.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = rightLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (rightHasContent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
    }
}

/** Option of the Folders section whose help is shown. */
private enum class FolderHelpTopic { MEMORY, SCROLL, NEW_CONTENT, HIDDEN, FAVORITES }

@Composable
private fun HiddenHelpDialog(topic: FolderHelpTopic, onDismiss: () -> Unit) {
    SettingsInfoDialog(
        title = stringResource(R.string.settings_category_folders),
        onDismiss = onDismiss,
    ) {
        if (topic == FolderHelpTopic.MEMORY) HelpFeatureCard(
            title = stringResource(R.string.multi_folder_memory_title),
            badge = stringResource(R.string.settings_badge_memory_mode),
            body = stringResource(R.string.settings_memory_mode_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Dashboard, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_memory_mode_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

        if (topic == FolderHelpTopic.SCROLL) HelpFeatureCard(
            title = stringResource(R.string.remember_folder_scroll),
            badge = stringResource(R.string.settings_badge_scrolling),
            body = stringResource(R.string.remember_folder_scroll_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_scroll_memory_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

        if (topic == FolderHelpTopic.NEW_CONTENT) HelpFeatureCard(
            title = stringResource(R.string.new_content_scrolls_to_top),
            badge = stringResource(R.string.settings_badge_scrolling),
            body = stringResource(R.string.new_content_scrolls_to_top_body),
        ) {
            Icon(Icons.Rounded.ArrowUpward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }

        if (topic == FolderHelpTopic.HIDDEN) HelpFeatureCard(
            title = stringResource(R.string.show_hidden),
            badge = ".nomedia",
            body = stringResource(R.string.show_hidden_body),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                    Text(stringResource(R.string.settings_plain_album), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.settings_visible_in_system), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text("vs", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    Text(stringResource(R.string.settings_hidden_nomedia), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.settings_only_in_alexet), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (topic == FolderHelpTopic.FAVORITES) HelpFeatureCard(
            title = stringResource(R.string.hide_favorites_folder),
            badge = stringResource(R.string.favorites),
            body = stringResource(R.string.settings_hide_favorites_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Icon(
                    imageVector = Icons.Rounded.GridView,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun PaneSplitHelpDialog(onDismiss: () -> Unit) {
    SettingsInfoDialog(
        title = stringResource(R.string.pane_split_title),
        onDismiss = onDismiss,
    ) {
        HelpFeatureCard(
            title = stringResource(R.string.settings_split_title),
            badge = stringResource(R.string.settings_badge_step10),
            body = stringResource(R.string.settings_split_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DualPaneMiniGraphic(leftRatio = 0.3f, rightRatio = 0.7f, rightHasContent = true, rightLabel = "30 / 70")
                DualPaneMiniGraphic(leftRatio = 0.5f, rightRatio = 0.5f, rightHasContent = true, rightLabel = "50 / 50")
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.settings_hinge_split_title),
            badge = stringResource(R.string.settings_badge_angle_sensor),
            body = stringResource(R.string.settings_hinge_split_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Icon(Icons.Rounded.SwapHoriz, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.settings_hinge_split_hint), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ViewHelpDialog(onDismiss: () -> Unit) {
    SettingsInfoDialog(
        title = stringResource(R.string.settings_category_view),
        onDismiss = onDismiss,
    ) {
        HelpFeatureCard(
            title = stringResource(R.string.show_file_names),
            badge = stringResource(R.string.settings_badge_labels),
            body = stringResource(R.string.show_file_names_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(54.dp, 44.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Wallpaper, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(2.dp),
                    ) {
                        Text("IMG_01.jpg", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                }

                Text("vs", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(54.dp, 54.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Wallpaper, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.grid_columns),
            badge = stringResource(R.string.settings_badge_columns_1_7),
            body = stringResource(R.string.grid_columns_body),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(32.dp, 32.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp)),
                    )
                    Text(stringResource(R.string.settings_columns_large), style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(3) {
                            Box(modifier = Modifier.size(9.dp, 32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                        }
                    }
                    Text(stringResource(R.string.settings_columns_default), style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        repeat(6) {
                            Box(modifier = Modifier.size(4.dp, 32.dp).background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(1.dp)))
                        }
                    }
                    Text(stringResource(R.string.settings_columns_compact), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.video_duration_format_title),
            badge = stringResource(R.string.settings_badge_duration),
            body = stringResource(R.string.video_duration_format_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_duration_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.scroller_enabled_title),
            badge = "Full Scroller",
            body = stringResource(R.string.settings_scroller_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.SwapVert, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_scroller_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

    }
}

@Composable
private fun SelectHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok), fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.file_select_help), style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SelectHelpExample(
                    title = stringResource(R.string.file_select_help_rect),
                    body = stringResource(R.string.file_select_help_rect_body),
                    filled = setOf(3, 4, 5, 6, 7, 8),
                    start = 3,
                    modifier = Modifier.weight(1f),
                )
                SelectHelpExample(
                    title = stringResource(R.string.file_select_help_linear),
                    body = stringResource(R.string.file_select_help_linear_body),
                    filled = setOf(2, 3, 4, 5, 6, 7, 8),
                    start = 2,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
}

@Composable
private fun BehaviorHelpDialog(onDismiss: () -> Unit) {
    SettingsInfoDialog(
        title = stringResource(R.string.settings_category_player),
        onDismiss = onDismiss,
    ) {
        HelpFeatureCard(
            title = stringResource(R.string.video_double_click_gallery),
            badge = stringResource(R.string.settings_badge_player),
            body = stringResource(R.string.video_double_click_gallery_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Icon(Icons.Rounded.TouchApp, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.settings_double_click_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.autoplay_gifs),
            badge = "GIF",
            body = stringResource(R.string.autoplay_gifs_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Animation, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_gif_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.nav_arrows_title),
            badge = stringResource(R.string.settings_badge_navigation),
            body = stringResource(R.string.nav_arrows_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.SwapHoriz, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_nav_buttons_hint), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ActionCardsHelpDialog(onDismiss: () -> Unit) {
    SettingsInfoDialog(
        title = stringResource(R.string.action_cards_order),
        onDismiss = onDismiss,
    ) {
        HelpFeatureCard(
            title = stringResource(R.string.settings_action_bar_title),
            badge = stringResource(R.string.settings_badge_bottom_bar),
            body = stringResource(R.string.settings_action_bar_body),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Share, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text("+ '...'", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.settings_card_drag_title),
            badge = stringResource(R.string.settings_badge_configuration),
            body = stringResource(R.string.settings_card_drag_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.DragIndicator, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_card_drag_hint), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ConfigHelpDialog(onDismiss: () -> Unit) {
    MaintenanceHelpDialog(onDismiss = onDismiss)
}

@Composable
private fun MaintenanceHelpDialog(onDismiss: () -> Unit) {
    SettingsInfoDialog(
        title = stringResource(R.string.settings_category_system),
        onDismiss = onDismiss,
    ) {
        HelpFeatureCard(
            title = stringResource(R.string.config_export) + " & " + stringResource(R.string.config_import),
            badge = stringResource(R.string.settings_badge_json_backup),
            body = stringResource(R.string.settings_json_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.FileUpload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.settings_export_short), style = MaterialTheme.typography.labelSmall)
                }
                Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.FileDownload, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                    Text("Import", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.rescan),
            badge = stringResource(R.string.settings_badge_cache),
            body = stringResource(R.string.rescan_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.RestartAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_cache_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.wallpaper_resolutions_title),
            badge = stringResource(R.string.settings_badge_wallpapers),
            body = stringResource(R.string.settings_wallpaper_res_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Wallpaper, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_wallpaper_res_hint), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SelectionHelpDialog(onDismiss: () -> Unit) {
    SettingsInfoDialog(
        title = stringResource(R.string.settings_category_selection),
        onDismiss = onDismiss,
    ) {
        HelpFeatureCard(
            title = stringResource(R.string.settings_select_style_title),
            badge = stringResource(R.string.settings_badge_selection),
            body = stringResource(R.string.settings_select_style_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_select_style_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.operations_follow_selection_order),
            badge = stringResource(R.string.settings_badge_order),
            body = stringResource(R.string.operations_follow_selection_order_body) + " " + stringResource(R.string.operations_follow_selection_order_info),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Sort, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_click_order_hint), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SearchHelpDialog(onDismiss: () -> Unit) {
    SettingsInfoDialog(
        title = stringResource(R.string.settings_category_extra),
        onDismiss = onDismiss,
    ) {
        HelpFeatureCard(
            title = stringResource(R.string.search_files_fullscreen),
            badge = stringResource(R.string.settings_badge_search),
            body = stringResource(R.string.search_files_fullscreen_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.GridView, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_search_full_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.smart_search_title),
            badge = "AI",
            body = stringResource(R.string.smart_search_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ImageSearch, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.smart_search_needs_model), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun PrivacyHelpDialog(onDismiss: () -> Unit) {
    SettingsInfoDialog(
        title = stringResource(R.string.settings_category_privacy),
        onDismiss = onDismiss,
    ) {
        HelpFeatureCard(
            title = stringResource(R.string.secure_folder_settings_title),
            badge = stringResource(R.string.settings_badge_encryption),
            body = stringResource(R.string.settings_vault_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_vault_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.privatized_folders_title),
            badge = stringResource(R.string.settings_badge_name_mixer),
            body = stringResource(R.string.privatized_folders_desc),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_anonymize_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.randomize_file_names_title),
            badge = stringResource(R.string.settings_badge_anonymization),
            body = stringResource(R.string.randomize_file_names_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text("IMG_2024.jpg -> anon_a1b2c3d4.jpg", style = MaterialTheme.typography.labelMedium)
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.strip_exif_title),
            badge = stringResource(R.string.settings_badge_in_folders),
            body = stringResource(R.string.strip_exif_in_private_folders_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_strip_hint), style = MaterialTheme.typography.labelMedium)
            }
        }

        HelpFeatureCard(
            title = stringResource(R.string.strip_exif_on_share_title),
            badge = stringResource(R.string.settings_badge_sharing),
            body = stringResource(R.string.strip_exif_on_share_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Share, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.settings_share_strip_hint), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** NewPipe folder: one folder picked from the same tile grid as the name mixer; none = not tracked. */
@Composable
private fun NewpipeFolderDialog(
    albums: List<Album>,
    allAlbums: List<Album>,
    selectedPath: String,
    hiddenAlbumIds: Set<Long>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    fun pathOf(album: Album) = album.relativePath.trim().trim('/')
    var selected by rememberSaveable { mutableStateOf(selectedPath.trim().trim('/')) }
    // Private folders and folders without a path on disk can't be watched.
    val watchable: (List<Album>) -> List<Album> = { list ->
        list.filter { !it.isSecure && pathOf(it).isNotEmpty() }.distinctBy { pathOf(it).lowercase() }
    }
    val selectedAlbum = (allAlbums + albums).firstOrNull { pathOf(it).equals(selected, ignoreCase = true) }
    FolderChoiceDialog(
        title = stringResource(R.string.newpipe_folder_title),
        description = stringResource(R.string.newpipe_folder_desc),
        albums = watchable(albums),
        allAlbums = watchable(allAlbums),
        hiddenAlbumIds = hiddenAlbumIds,
        isSelected = { pathOf(it).equals(selected, ignoreCase = true) },
        // Tapping the chosen folder again un-picks it: no folder is tracked.
        onTileClick = { album -> selected = if (pathOf(album).equals(selected, ignoreCase = true)) "" else pathOf(album) },
        status = when {
            selected.isEmpty() -> stringResource(R.string.newpipe_folder_clear)
            selectedAlbum != null -> selectedAlbum.name.ifBlank { selected }
            else -> selected
        },
        emptyText = stringResource(R.string.newpipe_folder_empty),
        onConfirm = { onSelect(selected) },
        onDismiss = onDismiss,
    )
}

/** A set of ids in saved instance state (a Bundle holds a LongArray, not a Set). */
private val LongSetSaver = Saver<Set<Long>, LongArray>(
    save = { it.toLongArray() },
    restore = { it.toSet() },
)

@Composable
private fun PrivatizedFoldersDialog(
    albums: List<Album>,
    allAlbums: List<Album>,
    anonymousFolderIds: Set<Long>,
    hiddenAlbumIds: Set<Long>,
    onChangeAnonymousFolders: (added: Set<Long>, removed: Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    // The set when the dialog opened and the user's choice; both survive a process restart.
    val initialIds by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(anonymousFolderIds) }
    var selectedIds by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(anonymousFolderIds) }
    FolderChoiceDialog(
        title = stringResource(R.string.privatized_folders_title),
        description = stringResource(R.string.privatized_folders_desc),
        albums = albums,
        allAlbums = allAlbums,
        hiddenAlbumIds = hiddenAlbumIds,
        isSelected = { it.id in selectedIds },
        onTileClick = { album -> selectedIds = if (album.id in selectedIds) selectedIds - album.id else selectedIds + album.id },
        markAnonymous = true,
        status = if (selectedIds.isEmpty()) {
            stringResource(R.string.privatized_folders_empty)
        } else {
            stringResource(R.string.privatized_folders_count, selectedIds.size)
        },
        emptyText = stringResource(R.string.privatized_folders_empty),
        // Only what the user changed: a folder renamed meanwhile keeps its new id.
        onConfirm = { onChangeAnonymousFolders(selectedIds - initialIds, initialIds - selectedIds) },
        onDismiss = onDismiss,
    )
}

/** The folders AI search indexes ([exclude] false), or leaves out ([exclude] true), as in the move picker. */
@Composable
private fun SmartIndexFoldersDialog(
    exclude: Boolean,
    ui: SmartSearchSettingsUi,
    settings: AppSettings,
    onDismiss: () -> Unit,
) {
    val current = if (exclude) settings.smartIndexExcludedFolderIds else settings.smartIndexIncludedFolderIds
    val initialIds by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(current) }
    var selectedIds by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(current) }
    // Private folders are never indexed: not offered.
    val albums = remember(ui.albums) { ui.albums.filter { !it.isSecure } }
    val allAlbums = remember(ui.allAlbums) { ui.allAlbums.filter { !it.isSecure } }
    FolderChoiceDialog(
        title = stringResource(if (exclude) R.string.smart_index_pick_excluded_title else R.string.smart_index_pick_included_title),
        description = stringResource(if (exclude) R.string.smart_index_pick_excluded_desc else R.string.smart_index_pick_included_desc),
        albums = albums,
        allAlbums = allAlbums,
        hiddenAlbumIds = settings.hiddenAlbumIds,
        isSelected = { it.id in selectedIds },
        onTileClick = { album -> selectedIds = if (album.id in selectedIds) selectedIds - album.id else selectedIds + album.id },
        status = if (selectedIds.isEmpty()) {
            stringResource(R.string.smart_index_pick_empty)
        } else {
            stringResource(R.string.smart_index_pick_count, selectedIds.size)
        },
        emptyText = stringResource(R.string.smart_index_pick_empty),
        // Only what the user changed: a folder renamed meanwhile keeps its new id.
        onConfirm = { ui.onIndexFolders(selectedIds - initialIds, initialIds - selectedIds) },
        onDismiss = onDismiss,
    )
}

/**
 * Folders picked from a grid of folder tiles (name mixer, NewPipe folder): title, description,
 * a toggle for hidden folders, the tiles, and the current choice with Cancel / OK.
 * @param markAnonymous Picked tiles also show the name-mixer badge.
 */
@Composable
private fun FolderChoiceDialog(
    title: String,
    description: String,
    albums: List<Album>,
    allAlbums: List<Album>,
    hiddenAlbumIds: Set<Long>,
    isSelected: (Album) -> Boolean,
    onTileClick: (Album) -> Unit,
    status: String,
    emptyText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    markAnonymous: Boolean = false,
) {
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val baseList = if (albums.isNotEmpty()) albums else allAlbums
    val hasHidden = remember(allAlbums, hiddenAlbumIds) {
        allAlbums.any { it.isHidden || it.id in hiddenAlbumIds }
    }
    val displayed = remember(showHidden, allAlbums, baseList) {
        (if (showHidden) allAlbums else baseList).distinctBy { it.id }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (hasHidden) {
                            FilledTonalIconButton(
                                onClick = { showHidden = !showHidden },
                                colors = if (showHidden) {
                                    IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    IconButtonDefaults.filledTonalIconButtonColors()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Visibility,
                                    contentDescription = stringResource(R.string.picker_show_hidden),
                                )
                            }
                        }
                    }
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        if (displayed.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = emptyText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                items(displayed, key = { it.id }) { album ->
                                    val picked = isSelected(album)
                                    val itemScale by animateFloatAsState(
                                        targetValue = if (picked) 0.93f else 1f,
                                        animationSpec = spring(
                                            stiffness = Spring.StiffnessMediumLow,
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                        ),
                                        label = "folderChoiceTileScale",
                                    )
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .then(if (LocalLightMode.current) Modifier else Modifier.animateItem())
                                            .graphicsLayer {
                                                scaleX = itemScale
                                                scaleY = itemScale
                                            }
                                            .clickable { onTileClick(album) },
                                    ) {
                                        FolderCover(
                                            album = album,
                                            stamped = picked,
                                            hidden = album.isHidden || album.id in hiddenAlbumIds,
                                            isAnonymous = markAnonymous && picked,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                onConfirm()
                                onDismiss()
                            },
                        ) {
                            Text(stringResource(R.string.action_ok))
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun SelectHelpExample(
    title: String,
    body: String,
    filled: Set<Int>,
    start: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) { col ->
                            val index = row * 3 + col
                            val color = when {
                                index == start -> colors.primary
                                index in filled -> colors.primary.copy(alpha = 0.45f)
                                else -> colors.surfaceVariant
                            }
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .background(color, RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

private const val HIDDEN_DROP_ZONE = -100
private const val MENU_DROP_ZONE = -200

private enum class ActionCardsPreviewMode {
    PHONE,
    TABLET,
}

private sealed interface DragSource {
    data class FromBar(val slotIndex: Int) : DragSource
    data class FromMenu(val menuIndex: Int) : DragSource
    data object FromHidden : DragSource
}

private val ActionCardRowHeight = 50.dp
private val ActionCardInnerHeight = 46.dp

@Composable
private fun DropInsertionSlot(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(ActionCardRowHeight)
            .padding(vertical = 2.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.settings_drop_here),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ActionCardsConfigSection(
    barSlots: List<SelectionAction?>,
    menuOrder: List<SelectionAction>,
    hiddenActions: Set<SelectionAction>,
    onSaveConfig: (barSlots: List<SelectionAction?>, menuOrder: List<SelectionAction>, hidden: Set<SelectionAction>) -> Unit,
    onToggleVisibility: (SelectionAction) -> Unit,
    onDraggingChanged: (Boolean) -> Unit = {},
) {
    val paddedSlots = remember(barSlots) {
        (barSlots + List(6) { null }).take(6)
    }

    val activeBarActionsSet = remember(paddedSlots) {
        paddedSlots.filterNotNull().toSet()
    }

    val visibleMenuActions = remember(menuOrder, hiddenActions, activeBarActionsSet) {
        val inMenu = menuOrder.filter { it !in hiddenActions && it !in activeBarActionsSet }
        val missing = SelectionAction.DEFAULT_ORDER.filter { it !in hiddenActions && it !in activeBarActionsSet && it !in inMenu }
        inMenu + missing
    }

    val hiddenList = remember(hiddenActions) {
        SelectionAction.DEFAULT_ORDER.filter { it in hiddenActions }
    }

    // The drag handlers run inside pointerInput blocks that may have started on an older
    // composition (their keys did not change): they read the lists through these, so a card hidden
    // meanwhile is not written back by the next drag.
    val latestSlots = rememberUpdatedState(paddedSlots)
    val latestMenu = rememberUpdatedState(visibleMenuActions)
    val latestHidden = rememberUpdatedState(hiddenActions)
    val latestHiddenList = rememberUpdatedState(hiddenList)

    // A drag cut off by a layout change (fold, rotation) never reaches its end: without this the
    // settings would stay unscrollable.
    DisposableEffect(Unit) {
        onDispose { onDraggingChanged(false) }
    }

    val configuration = LocalConfiguration.current
    val foldable = rememberFoldableState()
    val isDeviceWide = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        foldable.showListDetail ||
        configuration.screenWidthDp >= 600

    var previewMode by remember {
        mutableStateOf(
            if (isDeviceWide) ActionCardsPreviewMode.TABLET else ActionCardsPreviewMode.PHONE,
        )
    }

    val isTablet = previewMode == ActionCardsPreviewMode.TABLET
    val unfoldProgress by animateFloatAsState(
        targetValue = if (isTablet) 1f else 0f,
        animationSpec = tween(
            durationMillis = 380,
            easing = FastOutSlowInEasing,
        ),
        label = "slideOutProgress",
    )

    var draggingAction by remember { mutableStateOf<SelectionAction?>(null) }
    var isDraggingEmptySlot by remember { mutableStateOf(false) }
    var dragSource by remember { mutableStateOf<DragSource?>(null) }
    var dragGlobalPos by remember { mutableStateOf(Offset.Zero) }
    var rootLayoutCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var hoveredTargetSlot by remember { mutableStateOf<Int?>(null) }
    val targetBounds = remember { mutableStateMapOf<Int, Rect>() }
    var cardRowWidthPx by remember { mutableFloatStateOf(0f) }

    var hoveredMenuInsertionIndex by remember { mutableStateOf<Int?>(null) }
    var isReorderingMode by remember { mutableStateOf(false) }

    LaunchedEffect(isDeviceWide) {
        val targetMode = if (isDeviceWide) ActionCardsPreviewMode.TABLET else ActionCardsPreviewMode.PHONE
        if (previewMode != targetMode) {
            previewMode = targetMode
            if (targetMode == ActionCardsPreviewMode.PHONE) {
                targetBounds.remove(4)
                targetBounds.remove(5)
            }
        }
    }

    fun findHoveredTarget(pos: Offset): Int? {
        val visibleMenuActions = latestMenu.value
        val hiddenList = latestHiddenList.value
        val isTablet = previewMode == ActionCardsPreviewMode.TABLET
        // 1. Check specific TopBar slots (only visible slots based on phone vs tablet)
        val visibleSlotRange = if (isTablet) 0..5 else 0..3
        for (slotIdx in visibleSlotRange) {
            val rect = targetBounds[slotIdx]
            if (rect != null && rect.contains(pos)) {
                hoveredMenuInsertionIndex = null
                return slotIdx
            }
        }

        // 2. Check specific hidden rows (-1000 - hiddenIdx)
        for (hiddenIdx in hiddenList.indices) {
            val key = -1000 - hiddenIdx
            val rect = targetBounds[key]
            if (rect != null && rect.contains(pos)) {
                hoveredMenuInsertionIndex = null
                return HIDDEN_DROP_ZONE
            }
        }

        val hiddenDropRect = targetBounds[HIDDEN_DROP_ZONE]
        if (hiddenDropRect != null && hiddenDropRect.contains(pos)) {
            hoveredMenuInsertionIndex = null
            return HIDDEN_DROP_ZONE
        }

        // 3. Check Menu list insertion
        val menuDropRect = targetBounds[MENU_DROP_ZONE]
        if (menuDropRect != null && (menuDropRect.contains(pos) || (pos.x in (menuDropRect.left - 20f)..(menuDropRect.right + 20f) && pos.y >= menuDropRect.top - 20f && pos.y <= menuDropRect.bottom + 20f))) {
            if (visibleMenuActions.isEmpty()) {
                hoveredMenuInsertionIndex = 0
                return 100
            }
            var insertIdx = visibleMenuActions.size
            for (i in visibleMenuActions.indices) {
                val rect = targetBounds[100 + i]
                if (rect != null && pos.y < rect.center.y) {
                    insertIdx = i
                    break
                }
            }
            val fromMenu = dragSource as? DragSource.FromMenu
            if (fromMenu != null) {
                if (insertIdx == fromMenu.menuIndex || insertIdx == fromMenu.menuIndex + 1) {
                    hoveredMenuInsertionIndex = null
                    return 100 + fromMenu.menuIndex
                }
            }
            hoveredMenuInsertionIndex = insertIdx
            return 100 + insertIdx
        }

        hoveredMenuInsertionIndex = null
        return null
    }

    fun handleDragEnd() {
        val paddedSlots = latestSlots.value
        val visibleMenuActions = latestMenu.value
        val hiddenActions = latestHidden.value
        val target = hoveredTargetSlot
        val action = draggingAction
        val source = dragSource

        if (target != null && source != null) {
            if (isDraggingEmptySlot && source is DragSource.FromBar) {
                if (target in 0..5 && target != source.slotIndex) {
                    val newSlots = paddedSlots.toMutableList()
                    val currentAtTarget = newSlots[target]
                    newSlots[target] = null
                    newSlots[source.slotIndex] = currentAtTarget
                    onSaveConfig(newSlots, visibleMenuActions, hiddenActions)
                }
            } else if (action != null) {
                when {
                    target == HIDDEN_DROP_ZONE -> {
                        when (source) {
                            is DragSource.FromBar -> {
                                val newSlots = paddedSlots.toMutableList()
                                newSlots[source.slotIndex] = null
                                val newHidden = hiddenActions + action
                                val newMenu = visibleMenuActions - action
                                onSaveConfig(newSlots, newMenu, newHidden)
                            }
                            is DragSource.FromMenu -> {
                                val newHidden = hiddenActions + action
                                val newMenu = visibleMenuActions - action
                                onSaveConfig(paddedSlots, newMenu, newHidden)
                            }
                            is DragSource.FromHidden -> { /* no-op */ }
                        }
                    }
                    target in 0..5 -> {
                        val targetSlotIdx = target
                        val currentAtTarget = paddedSlots[targetSlotIdx]
                        val newSlots = paddedSlots.toMutableList()
                        newSlots[targetSlotIdx] = action

                        when (source) {
                            is DragSource.FromBar -> {
                                newSlots[source.slotIndex] = if (source.slotIndex == targetSlotIdx) action else currentAtTarget
                                val newHidden = hiddenActions - action
                                onSaveConfig(newSlots, visibleMenuActions, newHidden)
                            }
                            is DragSource.FromMenu -> {
                                var newMenu = visibleMenuActions - action
                                if (currentAtTarget != null && currentAtTarget != action && currentAtTarget !in newMenu) {
                                   newMenu = newMenu + currentAtTarget
                                }
                                val newHidden = hiddenActions - action
                                onSaveConfig(newSlots, newMenu, newHidden)
                            }
                            is DragSource.FromHidden -> {
                                var newMenu = visibleMenuActions - action
                                if (currentAtTarget != null && currentAtTarget != action && currentAtTarget !in newMenu) {
                                    newMenu = newMenu + currentAtTarget
                                }
                                val newHidden = hiddenActions - action
                                onSaveConfig(newSlots, newMenu, newHidden)
                            }
                        }
                    }
                    target == MENU_DROP_ZONE || target >= 100 -> {
                        val targetMenuIdx = if (target >= 100) (target - 100).coerceIn(0, visibleMenuActions.size) else visibleMenuActions.size
                        when (source) {
                            is DragSource.FromBar -> {
                                val newSlots = paddedSlots.toMutableList()
                                newSlots[source.slotIndex] = null
                                val newMenu = visibleMenuActions.toMutableList()
                                newMenu.add(targetMenuIdx.coerceIn(0, newMenu.size), action)
                                val newHidden = hiddenActions - action
                                onSaveConfig(newSlots, newMenu, newHidden)
                            }
                            is DragSource.FromMenu -> {
                                val fromIdx = source.menuIndex
                                val targetIdx = targetMenuIdx
                                if (targetIdx != fromIdx && targetIdx != fromIdx + 1) {
                                    val newMenu = visibleMenuActions.toMutableList()
                                    newMenu.removeAt(fromIdx)
                                    val adjustedTarget = if (targetIdx > fromIdx) targetIdx - 1 else targetIdx
                                    newMenu.add(adjustedTarget.coerceIn(0, newMenu.size), action)
                                    onSaveConfig(paddedSlots, newMenu, hiddenActions)
                                }
                            }
                            is DragSource.FromHidden -> {
                                val newHidden = hiddenActions - action
                                val newMenu = visibleMenuActions.toMutableList()
                                newMenu.add(targetMenuIdx.coerceIn(0, newMenu.size), action)
                                onSaveConfig(paddedSlots, newMenu, newHidden)
                            }
                        }
                    }
                }
            }
        }
        onDraggingChanged(false)
        draggingAction = null
        isDraggingEmptySlot = false
        dragSource = null
        hoveredTargetSlot = null
        hoveredMenuInsertionIndex = null
    }

    fun clearSlot(slotIndex: Int) {
        val paddedSlots = latestSlots.value
        val visibleMenuActions = latestMenu.value
        val hiddenActions = latestHidden.value
        val action = paddedSlots.getOrNull(slotIndex) ?: return
        val newSlots = paddedSlots.toMutableList()
        newSlots[slotIndex] = null
        val newMenu = if (action !in visibleMenuActions) visibleMenuActions + action else visibleMenuActions
        onSaveConfig(newSlots, newMenu, hiddenActions)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rootLayoutCoords = it },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Przełącznik Telefon / Tablet
            SettingsSegmentedRow(
                options = listOf(
                    ActionCardsPreviewMode.PHONE to stringResource(R.string.preview_phone),
                    ActionCardsPreviewMode.TABLET to stringResource(R.string.preview_tablet),
                ),
                selected = previewMode,
                onSelect = { newMode ->
                    previewMode = newMode
                    if (newMode == ActionCardsPreviewMode.PHONE) {
                        targetBounds.remove(4)
                        targetBounds.remove(5)
                    }
                },
                modifier = Modifier.padding(bottom = 2.dp),
            )

            Crossfade(
                targetState = isTablet,
                animationSpec = tween(durationMillis = 280),
                label = "modeDesc",
            ) { tablet ->
                Text(
                    text = if (tablet) {
                        stringResource(R.string.action_cards_mode_tablet_desc)
                    } else {
                        stringResource(R.string.action_cards_mode_phone_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // --- 2. Interactive Mockup Container ---
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val slotSize = 46.dp
                    val slotSpacing = 6.dp
                    val dividerWidth = 2.dp
                    val dividerPadding = 6.dp
                    val extraSlotsWidth = slotSize * 2 + slotSpacing
                    val hingeSectionWidth = dividerWidth + dividerPadding * 2
                    val totalLeftWidth = extraSlotsWidth + hingeSectionWidth
                    val animatedLeftWidth = totalLeftWidth * unfoldProgress
                    val mainSlotsWidth = slotSize * 4 + slotSpacing * 3

                    @Composable
                    fun RenderBarSlot(slotIndex: Int) {
                        val action = paddedSlots.getOrNull(slotIndex)
                        val isHovered = hoveredTargetSlot == slotIndex
                        val isDraggingThis = if (action != null) draggingAction == action else (isDraggingEmptySlot && dragSource == DragSource.FromBar(slotIndex))
                        val isAnotherBarSlotDragged = dragSource is DragSource.FromBar && !isDraggingThis

                        val animatedSlotScale by animateFloatAsState(
                            targetValue = if (isHovered) 1.22f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                            label = "slotScale_$slotIndex",
                        )

                        Box(
                            modifier = Modifier
                                .onGloballyPositioned { targetBounds[slotIndex] = it.boundsInRoot() }
                                .graphicsLayer {
                                    scaleX = animatedSlotScale
                                    scaleY = animatedSlotScale
                                    shadowElevation = if (isHovered) 10.dp.toPx() else 0f
                                    if (isDraggingThis) {
                                        alpha = 0.35f
                                    }
                                }
                                .zIndex(if (isHovered) 20f else 1f)
                                .size(slotSize)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    when {
                                        isHovered -> MaterialTheme.colorScheme.primaryContainer
                                        isDraggingThis -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f)
                                        action != null -> MaterialTheme.colorScheme.surfaceContainerHigh
                                        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
                                    }
                                )
                                .border(
                                    width = if (isHovered) 2.dp else 1.dp,
                                    color = when {
                                        isHovered -> MaterialTheme.colorScheme.primary
                                        isDraggingThis -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .pointerInput(action, slotIndex, paddedSlots, visibleMenuActions, hiddenActions) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { localOffset ->
                                            onDraggingChanged(true)
                                            if (action != null) {
                                                draggingAction = action
                                                isDraggingEmptySlot = false
                                            } else {
                                                draggingAction = null
                                                isDraggingEmptySlot = true
                                            }
                                            dragSource = DragSource.FromBar(slotIndex)
                                            val bounds = targetBounds[slotIndex]
                                            dragGlobalPos = if (bounds != null) bounds.topLeft + localOffset else Offset.Zero
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragGlobalPos += dragAmount
                                            hoveredTargetSlot = findHoveredTarget(dragGlobalPos)
                                        },
                                        onDragEnd = { handleDragEnd() },
                                        onDragCancel = {
                                            onDraggingChanged(false)
                                            draggingAction = null
                                            isDraggingEmptySlot = false
                                            dragSource = null
                                            hoveredTargetSlot = null
                                            hoveredMenuInsertionIndex = null
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (action != null) {
                                Icon(
                                    imageVector = actionIcon(action),
                                    contentDescription = null,
                                    tint = if (isDraggingThis) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 2.dp, end = 2.dp)
                                        .size(16.dp)
                                        .clickable { clearSlot(slotIndex) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.settings_remove_from_slot),
                                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                        modifier = Modifier.size(11.dp),
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = if (isDraggingThis) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                    modifier = Modifier.size(19.dp),
                                )
                            }

                            if (isHovered && isAnotherBarSlotDragged) {
                                Icon(
                                    imageVector = Icons.Rounded.SwapHoriz,
                                    contentDescription = stringResource(R.string.settings_swap_slot),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 2.dp),
                                )
                            }
                        }
                    }

                    // TopBar Slots Row (2 dodatkowe sloty po LEWEJ stronie dla tabletu + 4 główne sloty po PRAWEJ)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 1. Rząd slotów - wysunięcie 2 dodatkowych slotów „spod” lewej strony
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Lewe skrzydło (Tabletu) - wysunięcie spod lewej strony
                            Box(
                                modifier = Modifier
                                    .width(animatedLeftWidth)
                                    .clipToBounds()
                                    .zIndex(1f)
                                    .graphicsLayer {
                                        alpha = unfoldProgress.coerceIn(0f, 1f)
                                    },
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .width(totalLeftWidth)
                                        .graphicsLayer {
                                            translationX = (1f - unfoldProgress) * totalLeftWidth.toPx()
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 2 Dodatkowe sloty
                                    Row(
                                        modifier = Modifier.width(extraSlotsWidth),
                                        horizontalArrangement = Arrangement.spacedBy(slotSpacing),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RenderBarSlot(4)
                                        RenderBarSlot(5)
                                    }

                                    Spacer(Modifier.width(dividerPadding))

                                    // Linia podziału / separator
                                    Box(
                                        modifier = Modifier
                                            .height(36.dp)
                                            .width(dividerWidth)
                                            .background(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                                shape = RoundedCornerShape(1.dp),
                                            ),
                                    )

                                    Spacer(Modifier.width(dividerPadding))
                                }
                            }

                            // Prawe skrzydło - 4 Główne sloty (Telefon)
                            Row(
                                modifier = Modifier
                                    .width(mainSlotsWidth)
                                    .zIndex(2f),
                                horizontalArrangement = Arrangement.spacedBy(slotSpacing),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                for (slotIndex in 0..3) {
                                    RenderBarSlot(slotIndex)
                                }
                            }
                        }

                        // 2. Podpisy pod slotami - powiązane przestrzennie ze skrzydłami (brak nagłych przesunięć)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Etykieta lewego skrzydła (tabletu)
                            Box(
                                modifier = Modifier
                                    .width(animatedLeftWidth)
                                    .graphicsLayer {
                                        alpha = (unfoldProgress * 2f - 0.4f).coerceIn(0f, 1f)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    modifier = Modifier.width(totalLeftWidth),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.extra_slots_left_tablet),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            // Etykieta prawego skrzydła (telefonu)
                            Row(
                                modifier = Modifier.width(mainSlotsWidth),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.main_slots_phone),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    // Open Hamburger Dropdown Menu
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { targetBounds[MENU_DROP_ZONE] = it.boundsInRoot() },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_dropdown_menu),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.sp,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (isReorderingMode) {
                                        Button(
                                            onClick = {
                                                isReorderingMode = false
                                                draggingAction = null
                                                isDraggingEmptySlot = false
                                                dragSource = null
                                                hoveredTargetSlot = null
                                                hoveredMenuInsertionIndex = null
                                                onDraggingChanged(false)
                                            },
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                                            modifier = Modifier.height(32.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                            ),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "OK",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { isReorderingMode = true },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(32.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.SwapVert,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = stringResource(R.string.settings_reorder),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }

                            if (visibleMenuActions.isEmpty() && (draggingAction == null || hoveredMenuInsertionIndex == null)) {
                                Text(
                                    text = stringResource(R.string.settings_all_on_bar),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                )
                            }

                            visibleMenuActions.forEachIndexed { menuIdx, action ->
                                val isDraggingThis = draggingAction == action
                                val fromMenu = dragSource as? DragSource.FromMenu
                                val isOwnSlot = fromMenu != null && (menuIdx == fromMenu.menuIndex || menuIdx == fromMenu.menuIndex + 1)

                                AnimatedVisibility(
                                    visible = draggingAction != null && hoveredMenuInsertionIndex == menuIdx && !isOwnSlot,
                                    enter = expandVertically(
                                        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
                                    ) + fadeIn(animationSpec = tween(60)),
                                    exit = shrinkVertically(
                                        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
                                    ) + fadeOut(animationSpec = tween(60)),
                                ) {
                                    DropInsertionSlot()
                                }

                                val targetCode = 100 + menuIdx

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = when {
                                        isDraggingThis -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.25f)
                                        isReorderingMode -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
                                        else -> Color.Transparent
                                    },
                                    border = when {
                                        isDraggingThis -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                        isReorderingMode -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                        else -> null
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(ActionCardRowHeight)
                                        .padding(vertical = 2.dp)
                                        .onGloballyPositioned {
                                            targetBounds[targetCode] = it.boundsInRoot()
                                            if (it.size.width > 0) {
                                                cardRowWidthPx = it.size.width.toFloat()
                                            }
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 8.dp)
                                            .alpha(if (isDraggingThis) 0.25f else 1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        IconButton(
                                            onClick = {
                                                val newHidden = hiddenActions + action
                                                val newMenu = visibleMenuActions - action
                                                onSaveConfig(paddedSlots, newMenu, newHidden)
                                            },
                                            modifier = Modifier.size(36.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.VisibilityOff,
                                                contentDescription = stringResource(R.string.settings_hide),
                                                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        Icon(
                                            imageVector = actionIcon(action),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp),
                                        )
                                        Text(
                                            text = stringResource(actionTitleRes(action)),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isReorderingMode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                    else Color.Transparent,
                                                )
                                                // menuIdx: hiding a card above shifts this one; the
                                                // gesture must start from its current place.
                                                .pointerInput(action, menuIdx, isReorderingMode) {
                                                    if (isReorderingMode) {
                                                        detectDragGestures(
                                                            onDragStart = { localOffset ->
                                                                onDraggingChanged(true)
                                                                draggingAction = action
                                                                isDraggingEmptySlot = false
                                                                dragSource = DragSource.FromMenu(menuIdx)
                                                                val bounds = targetBounds[targetCode]
                                                                dragGlobalPos = if (bounds != null) bounds.topLeft + localOffset else Offset.Zero
                                                                hoveredTargetSlot = 100 + menuIdx
                                                                hoveredMenuInsertionIndex = null
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                dragGlobalPos += dragAmount
                                                                hoveredTargetSlot = findHoveredTarget(dragGlobalPos)
                                                            },
                                                            onDragEnd = { handleDragEnd() },
                                                            onDragCancel = {
                                                                onDraggingChanged(false)
                                                                draggingAction = null
                                                                isDraggingEmptySlot = false
                                                                dragSource = null
                                                                hoveredTargetSlot = null
                                                                hoveredMenuInsertionIndex = null
                                                            },
                                                        )
                                                    } else {
                                                        detectDragGesturesAfterLongPress(
                                                            onDragStart = { localOffset ->
                                                                onDraggingChanged(true)
                                                                draggingAction = action
                                                                isDraggingEmptySlot = false
                                                                dragSource = DragSource.FromMenu(menuIdx)
                                                                val bounds = targetBounds[targetCode]
                                                                dragGlobalPos = if (bounds != null) bounds.topLeft + localOffset else Offset.Zero
                                                                hoveredTargetSlot = 100 + menuIdx
                                                                hoveredMenuInsertionIndex = null
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                dragGlobalPos += dragAmount
                                                                hoveredTargetSlot = findHoveredTarget(dragGlobalPos)
                                                            },
                                                            onDragEnd = { handleDragEnd() },
                                                            onDragCancel = {
                                                                onDraggingChanged(false)
                                                                draggingAction = null
                                                                isDraggingEmptySlot = false
                                                                dragSource = null
                                                                hoveredTargetSlot = null
                                                                hoveredMenuInsertionIndex = null
                                                            },
                                                        )
                                                    }
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DragIndicator,
                                                contentDescription = stringResource(R.string.settings_move_card),
                                                tint = if (isReorderingMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            val fromMenuLast = dragSource as? DragSource.FromMenu
                            val isOwnBottomSlot = fromMenuLast != null && fromMenuLast.menuIndex == visibleMenuActions.size - 1
                            AnimatedVisibility(
                                visible = draggingAction != null && hoveredMenuInsertionIndex == visibleMenuActions.size && !isOwnBottomSlot,
                                enter = expandVertically(
                                    animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
                                ) + fadeIn(animationSpec = tween(60)),
                                exit = shrinkVertically(
                                    animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
                                ) + fadeOut(animationSpec = tween(60)),
                            ) {
                                DropInsertionSlot()
                            }
                        }
                    }
                }
            }

            // --- 3. Dedicated Always-Visible Hidden Cards Drop Zone ---
            val isHiddenZoneHovered = hoveredTargetSlot == HIDDEN_DROP_ZONE
            val hiddenZoneScale by animateFloatAsState(
                targetValue = if (isHiddenZoneHovered) 1.03f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "hiddenZoneScale",
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isHiddenZoneHovered) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    width = if (isHiddenZoneHovered) 2.5.dp else 1.dp,
                    color = if (isHiddenZoneHovered) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { targetBounds[HIDDEN_DROP_ZONE] = it.boundsInRoot() }
                    .graphicsLayer {
                        scaleX = hiddenZoneScale
                        scaleY = hiddenZoneScale
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_hidden_cards_zone, hiddenList.size),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isHiddenZoneHovered) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.settings_drag_to_hide),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    if (hiddenList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_no_hidden_cards),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            hiddenList.forEachIndexed { hiddenIdx, action ->
                                val isDraggingThis = draggingAction == action
                                val hiddenItemKey = -1000 - hiddenIdx
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isDraggingThis) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.25f)
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isDraggingThis) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(ActionCardRowHeight)
                                        .padding(vertical = 2.dp)
                                        .onGloballyPositioned {
                                            targetBounds[hiddenItemKey] = it.boundsInRoot()
                                            if (it.size.width > 0) {
                                                cardRowWidthPx = it.size.width.toFloat()
                                            }
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 8.dp)
                                            .alpha(if (isDraggingThis) 0.25f else 1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        IconButton(
                                            onClick = {
                                                val newHidden = hiddenActions - action
                                                val newMenu = if (action !in visibleMenuActions) visibleMenuActions + action else visibleMenuActions
                                                onSaveConfig(paddedSlots, newMenu, newHidden)
                                            },
                                            modifier = Modifier.size(36.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Visibility,
                                                contentDescription = stringResource(R.string.settings_restore_to_menu),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        Icon(
                                            imageVector = actionIcon(action),
                                            contentDescription = null,
                                            tint = if (isDraggingThis) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(22.dp),
                                        )
                                        Text(
                                            text = stringResource(actionTitleRes(action)),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isDraggingThis) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .pointerInput(action, hiddenIdx, paddedSlots, visibleMenuActions, hiddenActions, isReorderingMode) {
                                                    if (isReorderingMode) {
                                                        detectDragGestures(
                                                            onDragStart = { localOffset ->
                                                                onDraggingChanged(true)
                                                                draggingAction = action
                                                                isDraggingEmptySlot = false
                                                                dragSource = DragSource.FromHidden
                                                                val bounds = targetBounds[hiddenItemKey] ?: targetBounds[HIDDEN_DROP_ZONE]
                                                                dragGlobalPos = if (bounds != null) bounds.topLeft + localOffset else Offset.Zero
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                dragGlobalPos += dragAmount
                                                                hoveredTargetSlot = findHoveredTarget(dragGlobalPos)
                                                            },
                                                            onDragEnd = { handleDragEnd() },
                                                            onDragCancel = {
                                                                onDraggingChanged(false)
                                                                draggingAction = null
                                                                isDraggingEmptySlot = false
                                                                dragSource = null
                                                                hoveredTargetSlot = null
                                                                hoveredMenuInsertionIndex = null
                                                            },
                                                        )
                                                    } else {
                                                        detectDragGesturesAfterLongPress(
                                                            onDragStart = { localOffset ->
                                                                onDraggingChanged(true)
                                                                draggingAction = action
                                                                isDraggingEmptySlot = false
                                                                dragSource = DragSource.FromHidden
                                                                val bounds = targetBounds[hiddenItemKey] ?: targetBounds[HIDDEN_DROP_ZONE]
                                                                dragGlobalPos = if (bounds != null) bounds.topLeft + localOffset else Offset.Zero
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                dragGlobalPos += dragAmount
                                                                hoveredTargetSlot = findHoveredTarget(dragGlobalPos)
                                                            },
                                                            onDragEnd = { handleDragEnd() },
                                                            onDragCancel = {
                                                                onDraggingChanged(false)
                                                                draggingAction = null
                                                                isDraggingEmptySlot = false
                                                                dragSource = null
                                                                hoveredTargetSlot = null
                                                                hoveredMenuInsertionIndex = null
                                                            },
                                                        )
                                                    }
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DragIndicator,
                                                contentDescription = stringResource(R.string.settings_move_hidden_card),
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Reset Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        onSaveConfig(
                            AppSettings.DEFAULT_BAR_SLOTS,
                            AppSettings.DEFAULT_MENU_ORDER,
                            emptySet(),
                        )
                    },
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_restore_default_layout))
                }
            }
        }

        // --- 5. Floating Card Above & Held By Finger ---
        val rootCoords = rootLayoutCoords
        val dragged = draggingAction
        if ((dragged != null || isDraggingEmptySlot) && rootCoords != null) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        val rootPos = rootCoords.positionInRoot()
                        val relativeX = dragGlobalPos.x - rootPos.x
                        val relativeY = dragGlobalPos.y - rootPos.y
                        val isFromMenu = dragSource is DragSource.FromMenu || dragSource is DragSource.FromHidden
                        val offsetX = if (isFromMenu) {
                            if (cardRowWidthPx > 0f) (cardRowWidthPx - 26.dp.toPx()) else 260.dp.toPx()
                        } else 110.dp.toPx()
                        val offsetY = if (isFromMenu) 23.dp.toPx() else 48.dp.toPx()
                        translationX = (relativeX - offsetX).coerceAtLeast(8.dp.toPx())
                        translationY = (relativeY - offsetY).coerceAtLeast(0f)
                        scaleX = 1f
                        scaleY = 1f
                    }
                    .zIndex(999f),
            ) {
                val fromBar = dragSource as? DragSource.FromBar
                if (fromBar != null) {
                    val isExtra = fromBar.slotIndex in 4..5
                    val slotTitle = if (isExtra) {
                        stringResource(R.string.extra_slot_prefix, fromBar.slotIndex - 3)
                    } else {
                        stringResource(R.string.main_slot_prefix, fromBar.slotIndex + 1)
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                        shadowElevation = 12.dp,
                        modifier = Modifier.height(ActionCardInnerHeight),
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (dragged != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (dragged != null) {
                                    Icon(
                                        imageVector = actionIcon(dragged),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Text(
                                text = if (dragged != null) stringResource(actionTitleRes(dragged)) else slotTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                } else if (dragged != null) {
                    val cardWidth = with(LocalDensity.current) {
                        if (cardRowWidthPx > 0f) cardRowWidthPx.toDp() else Dp.Unspecified
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                        shadowElevation = 16.dp,
                        modifier = Modifier
                            .then(if (cardWidth != Dp.Unspecified) Modifier.width(cardWidth) else Modifier)
                            .height(ActionCardInnerHeight),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VisibilityOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Icon(
                                imageVector = actionIcon(dragged),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = stringResource(actionTitleRes(dragged)),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DragIndicator,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                        shadowElevation = 12.dp,
                        modifier = Modifier.height(ActionCardInnerHeight),
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 14.dp)
                                .fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.empty_slot_label),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun actionTitleRes(action: SelectionAction): Int = when (action) {
    SelectionAction.DELETE -> R.string.delete
    SelectionAction.SHARE -> R.string.share
    SelectionAction.INFO -> R.string.details
    SelectionAction.MOVE -> R.string.move_to
    SelectionAction.RENAME -> R.string.rename
    SelectionAction.EDIT -> R.string.edit
    SelectionAction.COPY -> R.string.copy
    SelectionAction.SELECT_ALL -> R.string.select_all
    SelectionAction.HIDE -> R.string.hide
    SelectionAction.FAVORITE -> R.string.favorites
    SelectionAction.SET_AS -> R.string.set_as
    SelectionAction.STRIP_EXIF -> R.string.strip_exif
    SelectionAction.CONTENT_ORDER -> R.string.content_order_action
    SelectionAction.SET_PRIVATE -> R.string.set_private
    SelectionAction.QUICK_EDIT -> R.string.quick_edit
}

private fun actionIcon(action: SelectionAction) = when (action) {
    SelectionAction.DELETE -> Icons.Rounded.Delete
    SelectionAction.SHARE -> Icons.Rounded.Share
    SelectionAction.INFO -> Icons.Rounded.Info
    SelectionAction.MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove
    SelectionAction.RENAME -> Icons.Rounded.Edit
    SelectionAction.EDIT -> Icons.Rounded.AutoFixHigh
    SelectionAction.COPY -> Icons.Outlined.ContentCopy
    SelectionAction.SELECT_ALL -> Icons.Outlined.SelectAll
    SelectionAction.HIDE -> Icons.Rounded.VisibilityOff
    SelectionAction.FAVORITE -> Icons.Rounded.Star
    SelectionAction.SET_AS -> Icons.Rounded.Wallpaper
    SelectionAction.STRIP_EXIF -> Icons.Rounded.Security
    SelectionAction.CONTENT_ORDER -> Icons.Rounded.FormatListNumbered
    SelectionAction.SET_PRIVATE -> Icons.Rounded.Lock
    SelectionAction.QUICK_EDIT -> Icons.Rounded.Bolt
}

@Composable
private fun ScrollerPreviewTile(
    selected: Boolean,
    title: String,
    isOnRight: Boolean,
    device: PreviewDevice = PreviewDevice.PHONE,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read only where the pill is placed (layout), not here: reading it in composition
    // recomposed both tiles on every frame of the animation.
    val progressState: androidx.compose.runtime.State<Float>? = if (LocalLightMode.current) {
        null
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "scrollerPreview")
        infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "scrollerPreviewPill",
        )
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = modifier
            .aspectRatio(1.2f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(3.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = if (isOnRight) 2.dp else 8.dp, end = if (isOnRight) 8.dp else 2.dp, top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val tablet = device == PreviewDevice.TABLET
                    if (tablet) {
                        // Folders' pane: the scroller runs along the files.
                        Box(
                            Modifier
                                .weight(1.4f)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), RoundedCornerShape(2.dp)),
                        )
                    }
                    repeat(3) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            repeat(if (tablet) 2 else 3) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(
                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                            RoundedCornerShape(2.dp),
                                        ),
                                )
                            }
                        }
                    }
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val maxOffset = maxHeight - 14.dp
                    // On a tablet a left scroller sits at the files' edge, past the folders.
                    val startX = if (device == PreviewDevice.TABLET && !isOnRight) maxWidth * (1.4f / 4.4f) else 0.dp
                    Box(
                        modifier = Modifier
                            .align(if (isOnRight) Alignment.TopEnd else Alignment.TopStart)
                            .offset {
                                val y = maxOffset.toPx() * (progressState?.value ?: 0.5f)
                                androidx.compose.ui.unit.IntOffset(startX.roundToPx(), kotlin.math.round(y).toInt())
                            }
                            .width(4.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** The SponsorBlock page: chapters on or off and the categories skipped automatically. */
@Composable
private fun SponsorBlockSettingsPage(
    config: dev.ander.alexet.integration.AnderLineConfig,
    onUpdatePlayerConfig: ((dev.ander.alexet.integration.AnderLineConfig) -> dev.ander.alexet.integration.AnderLineConfig) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PageBar(stringResource(R.string.sponsorblock), onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.sponsorblock_enable),
                body = stringResource(R.string.sponsorblock_enable_description),
                checked = config.sponsorBlockEnabled,
                onCheckedChange = { checked ->
                    onUpdatePlayerConfig { it.copy(sponsorBlockEnabled = checked) }
                },
                icon = Icons.Rounded.FastForward,
            )
            AnimatedVisibility(
                visible = config.sponsorBlockEnabled,
                enter = fadeIn(animationSpec = tween(200)) + expandVertically(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 4.dp),
                ) {
                    SettingsItemDivider()
                    Text(
                        text = stringResource(R.string.sponsorblock_auto_skip),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.sponsorblock_auto_skip_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SponsorBlockCategory.entries.forEach { category ->
                        SettingsSwitchRow(
                            title = stringResource(category.nameRes),
                            checked = category in config.sponsorBlockAutoSkip,
                            onCheckedChange = { checked ->
                                onUpdatePlayerConfig {
                                    it.copy(
                                        sponsorBlockAutoSkip = if (checked) {
                                            it.sponsorBlockAutoSkip + category
                                        } else {
                                            it.sponsorBlockAutoSkip - category
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
