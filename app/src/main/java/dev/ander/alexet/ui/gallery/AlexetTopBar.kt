package dev.ander.alexet.ui.gallery

import dev.ander.alexet.ui.components.AnimatedContent
import dev.ander.alexet.ui.components.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.drawWithContent
import dev.ander.alexet.ui.components.aiglow.aiColors
import dev.ander.alexet.ui.components.aiglow.aiGlowHaloAround
import dev.ander.alexet.ui.components.aiglow.rememberEffectStrength
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import dev.ander.alexet.ui.components.aiglow.rememberAiPhase
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import dev.ander.alexet.ui.components.LocalLightMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R
import dev.ander.alexet.data.SearchMode
import dev.ander.alexet.data.SelectionAction
import dev.ander.alexet.data.SortField
import dev.ander.alexet.data.SortOrder
import dev.ander.alexet.data.SortSpec
import kotlinx.coroutines.delay

private const val SEARCH_DEBOUNCE_MS = 200L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlexetTopBar(
    title: String?,
    showUp: Boolean,
    onUp: () -> Unit,
    searching: Boolean,
    query: String,
    searchMode: SearchMode,
    onQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onToggleSearchMode: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Smart search: photos indexed / photos to index, shown in the hint while some wait. */
    smartIndexProgress: Pair<Int, Int>? = null,
    /** The AI search's glow around the field in [SearchMode.SMART]. */
    aiEffects: dev.ander.alexet.data.AiEffectsConfig = dev.ander.alexet.data.AiEffectsConfig(),
    /** The field's ✨ button (AI search on / off); null hides it (AI search off or no model). */
    onToggleSmartSearch: (() -> Unit)? = null,
    /** In AI search, the folders / files mode its switch shows. */
    smartBaseMode: SearchMode = SearchMode.FOLDERS,
    /** The open folder is covered by the AI search; false greys ✨ out. */
    smartFolderAllowed: Boolean = true,
    /** ✨ tapped where the AI search does not cover the folder. */
    onSmartUnavailable: () -> Unit = {},
    /** Changes with every finished AI search: lights timed effects again. */
    aiTrigger: Int = 0,
    /** Shown while "Debugowanie logów" is on: opens the "Stwórz log" choice. */
    onCreateDebugLog: (() -> Unit)? = null,
    onOpenTrash: () -> Unit = {},
    inTrash: Boolean = false,
    onRestore: () -> Unit = {},
    /** Restores the selected trashed files into a folder picked now. */
    onRestoreTo: () -> Unit = {},
    selectionCount: Int = 0,
    isMarqueeActive: Boolean = false,
    totalCount: Int = 0,
    wide: Boolean = false,
    activeBarActions: List<SelectionAction> = emptyList(),
    /** The selection menu's cards (swiped through sideways); the first one opens. */
    menuCards: List<List<SelectionAction>> = emptyList(),
    inFolder: Boolean = false,
    sortSpec: SortSpec = SortSpec(),
    folderSortOnly: Boolean = false,
    hasContentOrder: Boolean = false,
    onApplySort: (SortSpec, Boolean, Boolean) -> Unit = { _, _, _ -> },
    onDelete: () -> Unit = {},
    onShare: () -> Unit = {},
    onInfo: () -> Unit = {},
    onRename: () -> Unit = {},
    onHide: () -> Unit = {},
    onUnhide: () -> Unit = {},
    canUnhide: Boolean = false,
    isAllFavorites: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onCopy: () -> Unit = {},
    onMove: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onSetAs: () -> Unit = {},
    onStripExif: () -> Unit = {},
    onEdit: (() -> Unit)? = null,
    editLabelRes: Int? = null,
    onQuickEdit: (() -> Unit)? = null,
    reorderingFolders: Boolean = false,
    contentOrderMode: Boolean = false,
    hasSelectedFolders: Boolean = false,
    onFolderOrder: () -> Unit = {},
    onCancelReorder: () -> Unit = {},
    onContentOrder: () -> Unit = {},
    onCancelContentOrder: () -> Unit = {},
    onRefresh: () -> Unit = {},
    isAnonymousFolder: Boolean = false,
    onToggleAnonymousFolder: (() -> Unit)? = null,
    onMixNames: (() -> Unit)? = null,
    onSetPrivate: (() -> Unit)? = null,
    onSearchSubmit: ((String) -> Unit)? = null,
    onStripPriority: (() -> Unit)? = null,
    allFilesInFolder: Boolean? = null,
    allFilesGlobal: Boolean = false,
    onToggleAllFilesInFolder: (() -> Unit)? = null,
    /** Folder settings → Numbering of the open folder's file names. */
    numberedFolder: Boolean = false,
    onToggleNumbering: (() -> Unit)? = null,
    /** In an open folder: its info (path, files, size, dates). */
    onFolderInfo: (() -> Unit)? = null,
) {
    val currentMode = when {
        reorderingFolders -> TopBarMode.REORDER_FOLDERS
        contentOrderMode -> TopBarMode.CONTENT_ORDER
        selectionCount > 0 -> TopBarMode.SELECTION
        else -> TopBarMode.NORMAL
    }

    AnimatedContent(
        targetState = currentMode,
        transitionSpec = {
            (fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                slideInVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) { -it / 3 }) togetherWith
                (fadeOut(animationSpec = tween(150, easing = FastOutSlowInEasing)) +
                    slideOutVertically(animationSpec = tween(150, easing = FastOutSlowInEasing)) { -it / 3 })
        },
        label = "topBarModeAnim",
    ) { mode ->
        when (mode) {
            TopBarMode.REORDER_FOLDERS -> ReorderTopBar(onCancel = onCancelReorder)
            TopBarMode.CONTENT_ORDER -> ReorderTopBar(
                onCancel = onCancelContentOrder,
                titleRes = R.string.content_order_action,
                hintRes = R.string.content_order_hint,
            )
            TopBarMode.SELECTION -> SelectionTopBar(
                count = selectionCount,
                isMarqueeActive = isMarqueeActive,
                totalCount = totalCount,
                wide = wide,
                activeBarActions = activeBarActions,
                menuCards = menuCards,
                hasSelectedFolders = hasSelectedFolders,
                inTrash = inTrash,
                onClear = onUp,
                onDelete = onDelete,
                onRestore = onRestore,
                onRestoreTo = onRestoreTo,
                onShare = onShare,
                onInfo = onInfo,
                onStripExif = onStripExif,
                onEdit = onEdit,
                editLabelRes = editLabelRes,
                onQuickEdit = onQuickEdit,
                onRename = onRename,
                onHide = onHide,
                onUnhide = onUnhide,
                canUnhide = canUnhide,
                isAllFavorites = isAllFavorites,
                onToggleFavorite = onToggleFavorite,
                onCopy = onCopy,
                onMove = onMove,
                onSelectAll = onSelectAll,
                onSetAs = onSetAs,
                onFolderOrder = onFolderOrder,
                onContentOrder = onContentOrder,
                onRefresh = onRefresh,
                onSetPrivate = onSetPrivate,
            )
            TopBarMode.NORMAL -> NormalTopBar(
                title = title,
                showUp = showUp,
                onUp = onUp,
                searching = searching,
                query = query,
                searchMode = searchMode,
                onQueryChange = onQueryChange,
                onSearchToggle = onSearchToggle,
                onToggleSearchMode = onToggleSearchMode,
                onOpenSettings = onOpenSettings,
                smartIndexProgress = smartIndexProgress,
                aiEffects = aiEffects,
                onToggleSmartSearch = onToggleSmartSearch,
                smartBaseMode = smartBaseMode,
                smartFolderAllowed = smartFolderAllowed,
                onSmartUnavailable = onSmartUnavailable,
                aiTrigger = aiTrigger,
                onCreateDebugLog = onCreateDebugLog,
                onOpenTrash = onOpenTrash,
                inTrash = inTrash,
                wide = wide,
                inFolder = inFolder,
                sortSpec = sortSpec,
                folderSortOnly = folderSortOnly,
                hasContentOrder = hasContentOrder,
                onApplySort = onApplySort,
                isAnonymousFolder = isAnonymousFolder,
                onToggleAnonymousFolder = onToggleAnonymousFolder,
                onMixNames = onMixNames,
                onRefresh = onRefresh,
                onContentOrder = onContentOrder,
                onSearchSubmit = onSearchSubmit,
                onStripPriority = onStripPriority,
                allFilesInFolder = allFilesInFolder,
                allFilesGlobal = allFilesGlobal,
                onToggleAllFilesInFolder = onToggleAllFilesInFolder,
                numberedFolder = numberedFolder,
                onToggleNumbering = onToggleNumbering,
                onFolderInfo = onFolderInfo,
            )
        }
    }
}

private enum class TopBarMode {
    NORMAL,
    SELECTION,
    REORDER_FOLDERS,
    CONTENT_ORDER,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NormalTopBar(
    title: String?,
    showUp: Boolean,
    onUp: () -> Unit,
    searching: Boolean,
    query: String,
    searchMode: SearchMode,
    onQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onToggleSearchMode: () -> Unit,
    onOpenSettings: () -> Unit,
    smartIndexProgress: Pair<Int, Int>?,
    aiEffects: dev.ander.alexet.data.AiEffectsConfig,
    onToggleSmartSearch: (() -> Unit)?,
    smartBaseMode: SearchMode,
    smartFolderAllowed: Boolean,
    onSmartUnavailable: () -> Unit,
    aiTrigger: Int,
    onCreateDebugLog: (() -> Unit)?,
    onOpenTrash: () -> Unit,
    inTrash: Boolean,
    wide: Boolean,
    inFolder: Boolean,
    onFolderInfo: (() -> Unit)?,
    sortSpec: SortSpec,
    folderSortOnly: Boolean,
    hasContentOrder: Boolean,
    onApplySort: (SortSpec, Boolean, Boolean) -> Unit,
    isAnonymousFolder: Boolean,
    onToggleAnonymousFolder: (() -> Unit)?,
    onMixNames: (() -> Unit)? = null,
    onRefresh: () -> Unit,
    onContentOrder: () -> Unit,
    onSearchSubmit: ((String) -> Unit)? = null,
    onStripPriority: (() -> Unit)? = null,
    allFilesInFolder: Boolean? = null,
    allFilesGlobal: Boolean = false,
    onToggleAllFilesInFolder: (() -> Unit)? = null,
    numberedFolder: Boolean = false,
    onToggleNumbering: (() -> Unit)? = null,
) {
    val smart = searchMode == SearchMode.SMART
    val fieldStyle = aiEffects.style(dev.ander.alexet.data.AiEffect.SEARCH_FIELD)
    val glowStrength = rememberEffectStrength(aiEffects, dev.ander.alexet.data.AiEffect.SEARCH_FIELD, searching && smart, aiTrigger)
    // Composition follows only on/off; the fade itself is read when drawing.
    val glowOn by remember(glowStrength) { androidx.compose.runtime.derivedStateOf { glowStrength.value > 0f } }
    // The field's place in the bar: its halo is drawn by the bar's box, over the bar, so neither
    // the title's animation nor the bar's own bounds cut it.
    var barOrigin by remember { mutableStateOf(Offset.Zero) }
    var fieldBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val haloModifier = if (glowOn) {
        val haloColors = aiColors(aiEffects, dev.ander.alexet.data.AiEffect.SEARCH_FIELD)
        val haloPhase = rememberAiPhase(fieldStyle.speed)
        // Kept across keystrokes: a new modifier rebuilt the halo's draw cache.
        remember(haloColors, fieldStyle, haloPhase, glowStrength) {
            Modifier.aiGlowHaloAround(
                target = { fieldBounds?.translate(-barOrigin) },
                colors = haloColors,
                style = fieldStyle,
                cornerRadius = AI_FIELD_RADIUS,
                phase = haloPhase,
                strength = { glowStrength.value },
                glow = 12.dp,
            )
        }
    } else {
        Modifier
    }
    Box(
        Modifier
            .onGloballyPositioned { barOrigin = it.positionInRoot() }
            .then(haloModifier),
    ) {
    TopAppBar(
        title = {
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            // The title and the search field turn like the icons beside them (a cube's faces):
            // the title up and away, the field from below. Sliding and fading in a slot whose
            // width follows those icons made the title jump to the side and back.
            TitleCube(second = searching) { isSearching ->
                if (isSearching) {
                    var textFieldValue by remember(isSearching) {
                        mutableStateOf(TextFieldValue(text = query, selection = TextRange(query.length)))
                    }

                    LaunchedEffect(query) {
                        if (query.isEmpty() && textFieldValue.text.isNotEmpty()) {
                            textFieldValue = TextFieldValue("", selection = TextRange.Zero)
                        }
                    }

                    // The typed text reaches the gallery after a short pause: each query filters
                    // the whole library and rebuilds the grid. Clearing goes through at once.
                    val currentQuery by rememberUpdatedState(query)
                    val currentOnQueryChange by rememberUpdatedState(onQueryChange)
                    LaunchedEffect(textFieldValue.text) {
                        val text = textFieldValue.text
                        if (text == currentQuery) return@LaunchedEffect
                        if (text.isNotEmpty()) delay(SEARCH_DEBOUNCE_MS)
                        currentOnQueryChange(text)
                    }

                    TextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            textFieldValue = newValue
                        },
                        placeholder = {
                            Text(
                                text = when {
                                    searchMode == SearchMode.SMART && smartIndexProgress != null ->
                                        stringResource(R.string.search_hint_smart_progress, smartIndexProgress.first, smartIndexProgress.second)
                                    searchMode == SearchMode.SMART -> stringResource(R.string.search_hint_smart)
                                    searchMode == SearchMode.FILES -> stringResource(R.string.search_hint_files)
                                    else -> stringResource(R.string.search_hint)
                                },
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboardController?.hide()
                                focusManager.clearFocus(force = true)
                                onSearchSubmit?.invoke(query)
                            },
                        ),
                        shape = if (smart) RoundedCornerShape(AI_FIELD_RADIUS) else TextFieldDefaults.shape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { fieldBounds = it.boundsInRoot() },
                    )
                } else {
                    val appName = stringResource(R.string.app_name)
                    // As tall as the field: the slot keeps its height when one replaces the other.
                    Box(Modifier.heightIn(min = TextFieldDefaults.MinHeight), contentAlignment = Alignment.CenterStart) {
                        CubeTitle(text = title ?: appName, appName = appName)
                    }
                }
            }
        },
        navigationIcon = {
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            AnimatedContent(
                targetState = when {
                    searching -> 1
                    showUp -> 2
                    else -> 3
                },
                transitionSpec = {
                    (scaleIn(initialScale = 0.7f, animationSpec = tween(180)) + fadeIn(animationSpec = tween(180))) togetherWith
                        (scaleOut(targetScale = 0.7f, animationSpec = tween(150)) + fadeOut(animationSpec = tween(150)))
                },
                label = "navigationIconAnim",
            ) { iconTarget ->
                when (iconTarget) {
                    1 -> {
                        IconButton(onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus(force = true)
                            onSearchToggle()
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                    }
                    2 -> {
                        IconButton(onClick = onUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_up),
                            )
                        }
                    }
                    else -> {
                        IconButton(onClick = onSearchToggle) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                    }
                }
            }
        },
        actions = {
            // Searching turns the icons away like the title's cube: only the search's own ones stay.
            CubeFaces(
                second = searching,
                first = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showUp && !searching) {
                    IconButton(onClick = onSearchToggle) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search))
                    }
                }
                if (!inTrash && ((!inFolder && !showUp) || wide)) {
                    IconButton(onClick = onOpenTrash) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = stringResource(R.string.trash),
                        )
                    }
                }
                if (onCreateDebugLog != null) {
                    IconButton(onClick = onCreateDebugLog) {
                        Icon(
                            imageVector = Icons.Rounded.BugReport,
                            contentDescription = stringResource(R.string.debug_log_create),
                        )
                    }
                }
                if (inFolder && !inTrash && onFolderInfo != null) {
                    IconButton(onClick = onFolderInfo) {
                        Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.folder_info))
                    }
                }
                if (inFolder && !inTrash) {
                    FolderOverflow(
                        sortSpec = sortSpec,
                        folderSortOnly = folderSortOnly,
                        hasContentOrder = hasContentOrder,
                        onApplySort = onApplySort,
                        onRefresh = onRefresh,
                        onOpenSettings = if (!wide && showUp) onOpenSettings else null,
                        onOpenTrash = if (!wide && showUp) onOpenTrash else null,
                        isAnonymousFolder = isAnonymousFolder,
                        onToggleAnonymousFolder = onToggleAnonymousFolder,
                        onMixNames = onMixNames,
                        onContentOrder = onContentOrder,
                        onStripPriority = onStripPriority,
                        allFilesInFolder = allFilesInFolder,
                        allFilesGlobal = allFilesGlobal,
                        onToggleAllFilesInFolder = onToggleAllFilesInFolder,
                        numbered = numberedFolder,
                        onToggleNumbering = onToggleNumbering,
                    )
                }
                if (!inTrash && ((!inFolder && !showUp) || wide)) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                }
            }
                },
                secondContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Name search: folders or files. AI search picks that by itself.
                        if (!smart && !inTrash && ((!inFolder && !showUp) || wide)) {
                            IconButton(onClick = onToggleSearchMode) {
                                AnimatedContent(
                                    targetState = searchMode,
                                    transitionSpec = {
                                        (fadeIn() + scaleIn(initialScale = 0.55f)) togetherWith
                                            (fadeOut() + scaleOut(targetScale = 1.25f))
                                    },
                                    label = "searchMode",
                                ) { mode ->
                                    Icon(
                                        imageVector = if (mode == SearchMode.FILES) Icons.AutoMirrored.Rounded.InsertDriveFile else Icons.Rounded.Folder,
                                        contentDescription = stringResource(R.string.search_mode_toggle),
                                    )
                                }
                            }
                        }
                        if (onToggleSmartSearch != null) {
                            IconButton(onClick = if (smartFolderAllowed) onToggleSmartSearch else onSmartUnavailable) {
                                SmartSearchIcon(active = smart, enabled = smartFolderAllowed, aiEffects = aiEffects, animate = searching)
                            }
                        }
                    }
                },
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.padding(end = 4.dp),
    )
    }
}

/**
 * The title's slot as two faces of a turning cube, as [CubeFaces] turns the icons beside it:
 * [second] turns the first face (the title) up and away and brings the second (the search
 * field) from below; back again the other way. The slot keeps the bar's width and the faces
 * start at its left. Light mode: no turn.
 */
@Composable
private fun TitleCube(second: Boolean, face: @Composable (second: Boolean) -> Unit) {
    val light = LocalLightMode.current
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (second) 1f else 0f,
        animationSpec = if (light) androidx.compose.animation.core.snap() else tween(320, easing = FastOutSlowInEasing),
        label = "titleCube",
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        if (progress < 1f) {
            Box(
                Modifier.graphicsLayer {
                    val p = progress
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    translationY = -size.height * p
                    rotationX = 90f * p
                    alpha = 1f - 0.6f * p
                    cameraDistance = 12f * density
                },
            ) { face(false) }
        }
        if (progress > 0f) {
            Box(
                Modifier.graphicsLayer {
                    val p = progress
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    translationY = size.height * (1f - p)
                    rotationX = -90f * (1f - p)
                    alpha = 0.4f + 0.6f * p
                    cameraDistance = 12f * density
                },
            ) { face(true) }
        }
    }
}

/**
 * Two faces of a turning cube, as the title's: [second] turns the first face up and away and
 * brings the second from below; back again the other way. Light mode: no turn.
 */
@Composable
private fun CubeFaces(second: Boolean, first: @Composable () -> Unit, secondContent: @Composable () -> Unit) {
    val light = LocalLightMode.current
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (second) 1f else 0f,
        animationSpec = if (light) androidx.compose.animation.core.snap() else tween(320, easing = FastOutSlowInEasing),
        label = "cubeFaces",
    )
    // As wide as the turn says, between the two faces' widths: holding the wider face's width to
    // the end of the turn and then dropping it pushed the title (the search field) back and forth.
    androidx.compose.ui.layout.Layout(
        content = {
        if (progress < 1f) {
            Box(
                Modifier.graphicsLayer {
                    val p = progress
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    translationY = -size.height * p
                    rotationX = 90f * p
                    alpha = 1f - 0.6f * p
                    cameraDistance = 12f * density
                },
            ) { first() }
        }
        if (progress > 0f) {
            Box(
                Modifier.graphicsLayer {
                    val p = progress
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    translationY = size.height * (1f - p)
                    rotationX = -90f * (1f - p)
                    alpha = 0.4f + 0.6f * p
                    cameraDistance = 12f * density
                },
            ) { secondContent() }
        }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val firstShown = progress < 1f
        val secondShown = progress > 0f
        val firstWidth = if (firstShown) placeables.first().width else 0
        val secondWidth = if (secondShown) placeables.last().width else 0
        val width = when {
            firstShown && secondShown -> (firstWidth + (secondWidth - firstWidth) * progress).toInt()
            firstShown -> firstWidth
            else -> secondWidth
        }.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            // Both faces at the end, centered vertically (as the Box placed them).
            placeables.forEach { it.place(width - it.width, (height - it.height) / 2) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReorderTopBar(
    onCancel: () -> Unit,
    titleRes: Int = R.string.folder_order,
    hintRes: Int = R.string.folder_order_hint,
) {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(titleRes))
                Text(
                    text = stringResource(hintRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            FilledTonalIconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel_reorder))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    totalCount: Int,
    wide: Boolean,
    isMarqueeActive: Boolean = false,
    activeBarActions: List<SelectionAction>,
    menuCards: List<List<SelectionAction>>,
    hasSelectedFolders: Boolean,
    inTrash: Boolean = false,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit = {},
    onRestoreTo: () -> Unit = {},
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onStripExif: () -> Unit = {},
    onEdit: (() -> Unit)? = null,
    editLabelRes: Int? = null,
    onQuickEdit: (() -> Unit)? = null,
    onRename: () -> Unit,
    onHide: () -> Unit,
    onUnhide: () -> Unit,
    canUnhide: Boolean,
    isAllFavorites: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onSelectAll: () -> Unit,
    onSetAs: () -> Unit = {},
    onFolderOrder: () -> Unit,
    onContentOrder: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onSetPrivate: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var menuCard by remember { mutableIntStateOf(0) }
    val safeCount = count.coerceAtLeast(1)
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isMarqueeActive) {
                    Text(text = "$safeCount")
                } else {
                    AnimatedContent(
                        targetState = safeCount,
                        transitionSpec = {
                            if ((initialState <= 1 && targetState <= 1) || targetState == initialState) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else if (targetState > initialState) {
                                (slideInVertically(animationSpec = tween(150, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(animationSpec = tween(150))) togetherWith
                                    (slideOutVertically(animationSpec = tween(130, easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(animationSpec = tween(130)))
                            } else {
                                (slideInVertically(animationSpec = tween(150, easing = FastOutSlowInEasing)) { -it / 2 } + fadeIn(animationSpec = tween(150))) togetherWith
                                    (slideOutVertically(animationSpec = tween(130, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(animationSpec = tween(130)))
                            }
                        },
                        label = "selectionCountAnim",
                    ) { currentCount ->
                        Text(text = "$currentCount")
                    }
                }
                if (totalCount > 0) {
                    Text(text = " / $totalCount")
                }
            }
        },
        navigationIcon = {
            FilledTonalIconButton(onClick = onClear) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear_selection))
            }
        },
        actions = {
            if (inTrash) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                }
                IconButton(onClick = onRestore) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.restore))
                }
                // Into a folder picked now, not where the files were.
                IconButton(onClick = onRestoreTo) {
                    Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = stringResource(R.string.restore_to))
                }
            } else {
                val isAvailable = { action: SelectionAction ->
                    when (action) {
                        SelectionAction.EDIT -> onEdit != null
                        SelectionAction.QUICK_EDIT -> onQuickEdit != null
                        SelectionAction.SET_AS -> count == 1 && !hasSelectedFolders
                        SelectionAction.CONTENT_ORDER -> !hasSelectedFolders
                        SelectionAction.RENAME -> !hasSelectedFolders || count == 1
                        SelectionAction.SET_PRIVATE -> hasSelectedFolders && onSetPrivate != null
                        else -> true
                    }
                }
                val barActions = activeBarActions.filter(isAvailable)
                val cards = menuCards.map { card -> card.filter(isAvailable) }
                val needsOverflow = cards.any { it.isNotEmpty() } || hasSelectedFolders

                @Composable
                fun ActionIcon(action: SelectionAction) {
                    when (action) {
                        SelectionAction.DELETE -> IconButton(onClick = onDelete) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                        }
                        SelectionAction.SHARE -> IconButton(onClick = onShare) {
                            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.share))
                        }
                        SelectionAction.INFO -> IconButton(onClick = onInfo) {
                            Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.details))
                        }
                        SelectionAction.MOVE -> IconButton(onClick = onMove) {
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = stringResource(R.string.move_to))
                        }
                        SelectionAction.RENAME -> IconButton(onClick = onRename) {
                            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.rename))
                        }
                        SelectionAction.EDIT -> if (onEdit != null) {
                            val isVideo = editLabelRes == R.string.edit_video
                            IconButton(onClick = onEdit) {
                                Icon(
                                    if (isVideo) Icons.Rounded.MovieFilter else Icons.Rounded.AutoFixHigh,
                                    contentDescription = stringResource(editLabelRes ?: R.string.edit_photo),
                                )
                            }
                        }
                        SelectionAction.QUICK_EDIT -> if (onQuickEdit != null) {
                            IconButton(onClick = onQuickEdit) {
                                Icon(Icons.Rounded.Bolt, contentDescription = stringResource(R.string.quick_edit))
                            }
                        }
                        SelectionAction.COPY -> IconButton(onClick = onCopy) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy))
                        }
                        SelectionAction.SELECT_ALL -> IconButton(onClick = onSelectAll) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.select_all))
                        }
                        SelectionAction.HIDE -> IconButton(onClick = if (canUnhide) onUnhide else onHide) {
                            Icon(
                                if (canUnhide) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = stringResource(if (canUnhide) R.string.unhide else R.string.hide),
                            )
                        }
                        SelectionAction.FAVORITE -> IconButton(onClick = onToggleFavorite) {
                            Icon(
                                if (isAllFavorites) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                tint = if (isAllFavorites) androidx.compose.ui.graphics.Color(0xFFFFD700) else androidx.compose.material3.LocalContentColor.current,
                                contentDescription = stringResource(if (isAllFavorites) R.string.remove_from_favorites else R.string.add_to_favorites),
                            )
                        }
                        SelectionAction.SET_AS -> if (count == 1 && !hasSelectedFolders) {
                            IconButton(onClick = onSetAs) {
                                Icon(Icons.Rounded.Wallpaper, contentDescription = stringResource(R.string.set_as))
                            }
                        }
                        SelectionAction.STRIP_EXIF -> if (!hasSelectedFolders) {
                            IconButton(onClick = onStripExif) {
                                Icon(Icons.Rounded.Security, contentDescription = stringResource(R.string.strip_exif))
                            }
                        }
                        SelectionAction.CONTENT_ORDER -> if (!hasSelectedFolders) {
                            IconButton(onClick = onContentOrder) {
                                Icon(Icons.Rounded.FormatListNumbered, contentDescription = stringResource(R.string.content_order_action))
                            }
                        }
                        SelectionAction.SET_PRIVATE -> if (hasSelectedFolders && onSetPrivate != null) {
                            IconButton(onClick = onSetPrivate) {
                                Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.set_private))
                            }
                        }
                    }
                }

                @Composable
                fun ActionMenuItem(action: SelectionAction) {
                    when (action) {
                        SelectionAction.DELETE -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = { menu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        )
                        SelectionAction.SHARE -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.share)) },
                            onClick = { menu = false; onShare() },
                            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                        )
                        SelectionAction.INFO -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.details)) },
                            onClick = { menu = false; onInfo() },
                            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                        )
                        SelectionAction.MOVE -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_to)) },
                            onClick = { menu = false; onMove() },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                        )
                        SelectionAction.RENAME -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = { menu = false; onRename() },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        )
                        SelectionAction.EDIT -> if (onEdit != null) {
                            val isVideo = editLabelRes == R.string.edit_video
                            DropdownMenuItem(
                                text = { Text(stringResource(editLabelRes ?: R.string.edit_photo)) },
                                onClick = { menu = false; onEdit() },
                                leadingIcon = {
                                    Icon(
                                        if (isVideo) Icons.Rounded.MovieFilter else Icons.Rounded.AutoFixHigh,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                        SelectionAction.QUICK_EDIT -> if (onQuickEdit != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.quick_edit)) },
                                onClick = { menu = false; onQuickEdit() },
                                leadingIcon = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                            )
                        }
                        SelectionAction.COPY -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy)) },
                            onClick = { menu = false; onCopy() },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        )
                        SelectionAction.SELECT_ALL -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.select_all)) },
                            onClick = { menu = false; onSelectAll() },
                            leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                        )
                        SelectionAction.HIDE -> DropdownMenuItem(
                            text = { Text(stringResource(if (canUnhide) R.string.unhide else R.string.hide)) },
                            onClick = { menu = false; if (canUnhide) onUnhide() else onHide() },
                            leadingIcon = {
                                Icon(
                                    if (canUnhide) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    contentDescription = null,
                                )
                            },
                        )
                        SelectionAction.FAVORITE -> DropdownMenuItem(
                            text = { Text(stringResource(if (isAllFavorites) R.string.remove_from_favorites else R.string.add_to_favorites)) },
                            onClick = { menu = false; onToggleFavorite() },
                            leadingIcon = {
                                Icon(
                                    if (isAllFavorites) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                    tint = if (isAllFavorites) androidx.compose.ui.graphics.Color(0xFFFFD700) else androidx.compose.material3.LocalContentColor.current,
                                    contentDescription = null,
                                )
                            },
                        )
                        SelectionAction.SET_AS -> if (count == 1 && !hasSelectedFolders) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.set_as)) },
                                onClick = { menu = false; onSetAs() },
                                leadingIcon = { Icon(Icons.Rounded.Wallpaper, contentDescription = null) },
                            )
                        }
                        SelectionAction.STRIP_EXIF -> if (!hasSelectedFolders) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.strip_exif)) },
                                onClick = { menu = false; onStripExif() },
                                leadingIcon = { Icon(Icons.Rounded.Security, contentDescription = null) },
                            )
                        }
                        SelectionAction.CONTENT_ORDER -> if (!hasSelectedFolders) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.content_order_action)) },
                                onClick = { menu = false; onContentOrder() },
                                leadingIcon = { Icon(Icons.Rounded.FormatListNumbered, contentDescription = null) },
                            )
                        }
                        SelectionAction.SET_PRIVATE -> if (hasSelectedFolders && onSetPrivate != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.set_private)) },
                                onClick = { menu = false; onSetPrivate() },
                                leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                            )
                        }
                    }
                }

                barActions.forEach { action ->
                    ActionIcon(action)
                }

                if (needsOverflow) {
                    // Every opening starts on the first card.
                    BoxMenu(expanded = menu, onDismiss = { menu = false }, onOpen = { menuCard = 0; menu = true }) {
                        SwipeMenuCards(
                            cardCount = cards.size,
                            cardIndex = menuCard.coerceIn(0, (cards.size - 1).coerceAtLeast(0)),
                            onCardChange = { menuCard = it },
                            card = { index ->
                                cards.getOrNull(index)?.forEach { action ->
                                    ActionMenuItem(action)
                                }
                            },
                            footer = {
                                if (hasSelectedFolders) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.folder_order)) },
                                        onClick = {
                                            menu = false
                                            onFolderOrder()
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.SwapVert, contentDescription = null) },
                                    )
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.refresh_view)) },
                                    onClick = {
                                        menu = false
                                        onRefresh()
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                )
                            },
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

@Composable
private fun FolderOverflow(
    sortSpec: SortSpec,
    folderSortOnly: Boolean,
    hasContentOrder: Boolean,
    onApplySort: (SortSpec, Boolean, Boolean) -> Unit,
    onRefresh: () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
    onOpenTrash: (() -> Unit)? = null,
    isAnonymousFolder: Boolean = false,
    onToggleAnonymousFolder: (() -> Unit)? = null,
    onMixNames: (() -> Unit)? = null,
    onContentOrder: () -> Unit = {},
    onStripPriority: (() -> Unit)? = null,
    allFilesInFolder: Boolean? = null,
    allFilesGlobal: Boolean = false,
    onToggleAllFilesInFolder: (() -> Unit)? = null,
    numbered: Boolean = false,
    onToggleNumbering: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showFolderSettings by remember { mutableStateOf(false) }

    if (showFolderSettings) {
        FolderSettingsDialog(
            numbered = numbered,
            numberingBlocked = isAnonymousFolder,
            onSort = {
                showFolderSettings = false
                showSortDialog = true
            },
            onContentOrder = {
                showFolderSettings = false
                onContentOrder()
            },
            onToggleNumbering = onToggleNumbering,
            onDismiss = { showFolderSettings = false },
        )
    }

    if (showSortDialog) {
        SortDialog(
            sortSpec = sortSpec,
            folderSortOnly = folderSortOnly,
            hasContentOrder = hasContentOrder,
            onApply = onApplySort,
            onStripPriority = onStripPriority,
            onDismiss = { showSortDialog = false },
        )
    }

    BoxMenu(
        expanded = menu,
        onDismiss = { menu = false },
        onOpen = { menu = true },
    ) {
        // Sorting, the hand-made order and the numbering, together.
        DropdownMenuItem(
            text = { Text(stringResource(R.string.folder_settings)) },
            onClick = {
                menu = false
                showFolderSettings = true
            },
            leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
        )
        // Kept for this folder until switched back. With all files shown everywhere (the settings
        // switch) there is nothing to switch here, so the item is not shown.
        if (allFilesInFolder != null && onToggleAllFilesInFolder != null && !allFilesGlobal) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (allFilesInFolder) R.string.all_files_folder_off
                            else R.string.all_files_folder_on,
                        ),
                    )
                },
                onClick = {
                    menu = false
                    onToggleAllFilesInFolder()
                },
                leadingIcon = {
                    Icon(
                        if (allFilesInFolder) Icons.Rounded.FilterAlt else Icons.Rounded.InsertDriveFile,
                        contentDescription = null,
                    )
                },
            )
        }
        if (isAnonymousFolder) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mix_names_action)) },
                onClick = {
                    menu = false
                    onMixNames?.invoke()
                },
                leadingIcon = { Icon(Icons.Rounded.Shuffle, contentDescription = null) },
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
            text = { Text(stringResource(R.string.refresh_view)) },
            onClick = {
                menu = false
                onRefresh()
            },
            leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
        )
        if (onOpenTrash != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trash)) },
                onClick = {
                    menu = false
                    onOpenTrash()
                },
                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            )
        }
        if (onOpenSettings != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings)) },
                onClick = {
                    menu = false
                    onOpenSettings()
                },
                leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            )
        }
    }
}

/**
 * The selection menu's cards. A swipe to the left shuffles to the next card, to the right to the
 * previous one, round and round: the top card flies off to the side with a tilt and the next one
 * comes up from under it. [footer] stays under every card. Light mode: the card just changes.
 */
@Composable
private fun SwipeMenuCards(
    cardCount: Int,
    cardIndex: Int,
    onCardChange: (Int) -> Unit,
    card: @Composable (Int) -> Unit,
    footer: @Composable () -> Unit,
) {
    val light = LocalLightMode.current
    val density = LocalDensity.current
    val switchAfterPx = with(density) { 48.dp.toPx() }
    val maxPullPx = with(density) { 36.dp.toPx() }
    val scope = rememberCoroutineScope()
    // +1: to the next card (swiped left), -1: to the previous one.
    var direction by remember { mutableIntStateOf(1) }
    // Each new card goes under the one leaving.
    var shuffles by remember { mutableIntStateOf(0) }
    val pull = remember { Animatable(0f) }
    val latestIndex by rememberUpdatedState(cardIndex)
    val latestOnChange by rememberUpdatedState(onCardChange)

    Column(
        modifier = Modifier.pointerInput(cardCount, light) {
            if (cardCount < 2) return@pointerInput
            var dragged = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragged = 0f },
                onDragEnd = {
                    if (abs(dragged) >= switchAfterPx) {
                        val step = if (dragged < 0f) 1 else -1
                        direction = step
                        shuffles++
                        latestOnChange(Math.floorMod(latestIndex + step, cardCount))
                    }
                    scope.launch { pull.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
                },
                onDragCancel = { scope.launch { pull.animateTo(0f, tween(180)) } },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dragged += amount
                    if (!light) scope.launch { pull.snapTo((dragged * 0.35f).coerceIn(-maxPullPx, maxPullPx)) }
                },
            )
        },
    ) {
        // Every card has the width of the widest one, so the menu does not jump sideways while the
        // cards are shuffled; the height follows the card shown (animated by the shuffle). The other
        // cards are measured only, never shown nor touchable.
        Layout(
            content = {
                ShuffledMenuCard(
                    cardIndex = cardIndex,
                    direction = direction,
                    shuffles = shuffles,
                    light = light,
                    pullPx = { pull.value },
                    card = card,
                )
                for (i in 0 until cardCount) {
                    Column { card(i) }
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val sizes = measurables.drop(1).map { it.measure(loose) }
            if (sizes.isEmpty()) {
                val shown = measurables.first().measure(constraints)
                return@Layout layout(shown.width, shown.height) { shown.place(0, 0) }
            }
            val width = sizes.maxOf { it.width }.coerceIn(constraints.minWidth, constraints.maxWidth)
            val shown = measurables.first().measure(
                Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = constraints.maxHeight),
            )
            layout(width, shown.height) { shown.place(0, 0) }
        }
        footer()
    }
}

/** The card on top of the menu's deck, shuffled away for the next one (see [SwipeMenuCards]). */
@Composable
private fun ShuffledMenuCard(
    cardIndex: Int,
    direction: Int,
    shuffles: Int,
    light: Boolean,
    pullPx: () -> Float,
    card: @Composable (Int) -> Unit,
) {
    AnimatedContent(
        targetState = cardIndex,
        transitionSpec = {
            val d = direction
            val enter = fadeIn(tween(200, delayMillis = 60)) +
                scaleIn(tween(280, easing = FastOutSlowInEasing), initialScale = 0.9f) +
                slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { d * it / 6 }
            val exit = fadeOut(tween(220)) +
                scaleOut(tween(240, easing = FastOutSlowInEasing), targetScale = 0.94f) +
                slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -d * it * 2 / 3 }
            // Clipped: a taller card leaving does not cover the items under the cards.
            val resize = SizeTransform(clip = true) { _, _ ->
                if (light) snap() else tween(260, easing = FastOutSlowInEasing)
            }
            (enter togetherWith exit using resize).apply {
                targetContentZIndex = -shuffles.toFloat()
            }
        },
        label = "menuCards",
    ) { index ->
        val tilt by transition.animateFloat(
            transitionSpec = { tween(if (light) 0 else 260, easing = FastOutSlowInEasing) },
            label = "menuCardTilt",
        ) { state ->
            when (state) {
                EnterExitState.PreEnter -> direction * 5f
                EnterExitState.Visible -> 0f
                EnterExitState.PostExit -> -direction * 9f
            }
        }
        Column(
            modifier = Modifier.graphicsLayer {
                rotationZ = tilt
                translationX = pullPx()
            },
        ) {
            card(index)
        }
    }
}

@Composable
private fun BoxMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = onOpen) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = content)
    }
}

/**
 * The folder name on the faces of a turning cube: going into a folder the cube turns up (the new
 * name comes from below), going back to the folders it turns down. Light mode: no turn.
 */
@Composable
private fun CubeTitle(text: String, appName: String) {
    val light = LocalLightMode.current
    var shown by remember { mutableStateOf(text) }
    var previous by remember { mutableStateOf<String?>(null) }
    var up by remember { mutableStateOf(true) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text == shown) return@LaunchedEffect
        if (light) {
            shown = text
            previous = null
            return@LaunchedEffect
        }
        previous = shown
        up = text != appName
        shown = text
        progress.snapTo(0f)
        progress.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        previous = null
    }
    Box(modifier = Modifier.clipToBounds()) {
        val old = previous
        if (old != null) {
            Text(
                text = old,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    // Turns away around the edge it shares with the new face.
                    val p = progress.value
                    val direction = if (up) 1f else -1f
                    transformOrigin = TransformOrigin(0.5f, if (up) 1f else 0f)
                    translationY = -direction * size.height * p
                    rotationX = direction * 90f * p
                    alpha = 1f - 0.6f * p
                    cameraDistance = 12f * density
                },
            )
        }
        Text(
            text = shown,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer {
                if (old == null) return@graphicsLayer
                val p = progress.value
                val direction = if (up) 1f else -1f
                transformOrigin = TransformOrigin(0.5f, if (up) 0f else 1f)
                translationY = direction * size.height * (1f - p)
                rotationX = -direction * 90f * (1f - p)
                alpha = 0.4f + 0.6f * p
                cameraDistance = 12f * density
            },
        )
    }
}

/** The search field's corners in AI mode (round, like the glowing AI buttons). */
private val AI_FIELD_RADIUS = 28.dp

/** ✨ of the search field: the AI palette's colors circling in it while AI search is on. */
@Composable
private fun SmartSearchIcon(
    active: Boolean,
    enabled: Boolean,
    aiEffects: dev.ander.alexet.data.AiEffectsConfig,
    /** The colors circle only during a search; otherwise they stand still (no redraw every frame). */
    animate: Boolean,
) {
    val description = stringResource(R.string.search_mode_smart)
    if (!enabled) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        return
    }
    if (!active) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val colors = aiColors(aiEffects, dev.ander.alexet.data.AiEffect.SEARCH_FIELD)
    val phase = if (animate) {
        rememberAiPhase(aiEffects.style(dev.ander.alexet.data.AiEffect.SEARCH_FIELD).speed)
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0.1f) }
    }
    Icon(
        Icons.Rounded.AutoAwesome,
        contentDescription = description,
        tint = androidx.compose.ui.graphics.Color.White,
        modifier = Modifier
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                    List(7) { dev.ander.alexet.ui.components.aiglow.paletteAt(colors, phase.value + it / 6f) },
                    // Between the two small stars (x 19 of the glyph's 24), so the colors circle
                    // around the whole icon. A quarter pixel off the middle row: on that row the
                    // gradient took the opposite side's color (a line), now through empty space.
                    center = androidx.compose.ui.geometry.Offset(size.width * 19f / 24f, size.height / 2f + 0.25f),
                )
                drawRect(brush, blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn)
            },
    )
}

/**
 * Folder settings (folder menu): its sorting, its hand-made order and the numbering of its file
 * names. With the numbering on, the order also renames the files.
 */
@Composable
private fun FolderSettingsDialog(
    numbered: Boolean,
    /** The name mixer gives this folder's files random names: no numbering there. */
    numberingBlocked: Boolean,
    onSort: () -> Unit,
    onContentOrder: () -> Unit,
    onToggleNumbering: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
        title = { Text(stringResource(R.string.folder_settings)) },
        text = {
            Column {
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(stringResource(R.string.sort_action)) },
                    leadingContent = { Icon(Icons.Rounded.SwapVert, contentDescription = null) },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onSort),
                )
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(stringResource(R.string.content_order_action)) },
                    supportingContent = if (numbered) {
                        { Text(stringResource(R.string.folder_order_renames)) }
                    } else null,
                    leadingContent = { Icon(Icons.Rounded.FormatListNumbered, contentDescription = null) },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onContentOrder),
                )
                val canToggle = onToggleNumbering != null && !numberingBlocked
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(stringResource(R.string.folder_numbering)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (numberingBlocked) R.string.content_order_anonymous_hint else R.string.folder_numbering_body,
                            ),
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.Tag, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = numbered,
                            onCheckedChange = { onToggleNumbering?.invoke() },
                            enabled = canToggle,
                        )
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = canToggle) { onToggleNumbering?.invoke() },
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
    )
}
