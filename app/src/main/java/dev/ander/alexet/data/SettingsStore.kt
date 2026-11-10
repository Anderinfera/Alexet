package dev.ander.alexet.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("alexet_settings", Context.MODE_PRIVATE)

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        dev.ander.alexet.data.AppLog.e("SettingsStore", "Uncaught coroutine exception", throwable)
    }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)
    private val writeMutex = Mutex()
    private var pendingSaveJob: Job? = null
    private val saveJobLock = Any()

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        AppLog.enabled = _settings.value.debugLogging
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val before = _settings.value
        val after = _settings.updateAndGet(transform)
        AppLog.enabled = after.debugLogging
        if (AppLog.enabled && before !== after) {
            val changes = AppLog.describeChanges(before, after)
            if (changes.isNotEmpty()) AppLog.event("Settings") { changes.joinToString("; ") }
        }
        synchronized(saveJobLock) {
            pendingSaveJob?.cancel()
            pendingSaveJob = ioScope.launch {
                delay(120)
                writeMutex.withLock {
                    try {
                        write(_settings.value)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        dev.ander.alexet.data.AppLog.e("SettingsStore", "Failed to persist settings", e)
                    }
                }
            }
        }
    }

    /**
     * Writes the current settings now, to disk before returning, instead of after the short save
     * delay. Called when the app goes to the background: a process killed there keeps the last change.
     */
    suspend fun flush() {
        synchronized(saveJobLock) { pendingSaveJob?.cancel() }
        writeMutex.withLock {
            withContext(Dispatchers.IO) { write(_settings.value, sync = true) }
        }
    }

    /** [flush] without waiting for it, for callers that are not coroutines (onStop). */
    fun flushInBackground() {
        ioScope.launch {
            try {
                flush()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e("SettingsStore", "Failed to persist settings", e)
            }
        }
    }

    private fun cleanNomediaPaths(paths: List<String>): List<String> = paths.map { path ->
        path.trim().trim('/').let { if (it.isEmpty()) it else "$it/" }
    }.filter { it.isNotEmpty() }.distinct()

    fun setNomediaFolderPaths(paths: List<String>) {
        val cleaned = cleanNomediaPaths(paths)
        if (cleaned == _settings.value.nomediaFolderPaths) return
        update { it.copy(nomediaFolderPaths = cleaned) }
    }

    /**
     * Adds and removes hidden-folder paths on the list as it is when written: hiding one folder
     * while another is being unhidden kept only one of the two changes.
     */
    fun changeNomediaFolderPaths(add: Collection<String> = emptyList(), remove: Collection<String> = emptyList()) = update { s ->
        val drop = cleanNomediaPaths(remove.toList()).toSet()
        val next = cleanNomediaPaths(s.nomediaFolderPaths.filterNot { it in drop } + add)
        if (next == s.nomediaFolderPaths) s else s.copy(nomediaFolderPaths = next)
    }

    fun setLastOpenedFolder(id: Long) {
        update { it.copy(lastOpenedFolderId = id) }
    }

    fun setRememberedOpenFolders(ids: List<Long>) {
        update { it.copy(rememberedOpenFolders = ids.take(2)) }
    }

    fun setGridColumns(cols: Int) {
        val clamped = cols.coerceIn(1, 30)
        if (clamped == _settings.value.gridColumns) return
        update { it.copy(gridColumns = clamped) }
    }

    fun setFileColumns(cols: Int) {
        val clamped = cols.coerceIn(1, 30)
        if (clamped == _settings.value.fileColumns) return
        update { it.copy(fileColumns = clamped) }
    }

    fun setFoldableFileColumns(cols: Int) {
        val clamped = cols.coerceIn(1, 10)
        if (clamped == _settings.value.foldableFileColumns) return
        update { it.copy(foldableFileColumns = clamped) }
    }

    fun setVideoDurationFormat(format: VideoDurationFormat) {
        if (format == _settings.value.videoDurationFormat) return
        update { it.copy(videoDurationFormat = format) }
    }

    /**
     * [pathOf] gives the portable path of a file id (see [MediaRefs]); [securePaths] are the private
     * folders, below the storage root. Both let an import on another phone find the same files.
     */
    fun exportJson(
        pathOf: (Long) -> String? = { null },
        securePaths: Collection<String> = emptyList(),
    ): String {
        val value = _settings.value
        return JSONObject().apply {
            put("schemaVersion", 1)
            put(
                "mediaPaths",
                JSONObject().apply {
                    val ids = LinkedHashSet<Long>().apply {
                        addAll(value.hiddenMediaIds)
                        addAll(value.favoriteMediaIds)
                        value.folderContentOrders.values.forEach { addAll(it) }
                    }
                    ids.forEach { id -> pathOf(id)?.let { put(id.toString(), it) } }
                },
            )
            if (!value.pendingMedia.isEmpty) put("pendingMedia", MediaRefs.toJson(value.pendingMedia))
            put("securePaths", JSONArray(securePaths.toList()))
            put("contentViewMode", value.contentViewMode.name)
            put("panelSplitPercent", value.panelSplitPercent)
            put("multiFolderMemory", value.multiFolderMemory.name)
            put("expandColumns", value.expandColumns)
            put("expandRows", value.expandRows)
            put("gridColumns", value.gridColumns)
            put("fileColumns", value.fileColumns)
            put("foldableFileColumns", value.foldableFileColumns)
            put("showFileNames", value.showFileNames)
            put("videoDurationFormat", value.videoDurationFormat.name)
            put("fileSelectMode", value.fileSelectMode.name)
            put("searchFilesFullScreen", value.searchFilesFullScreen)
            put("sortField", value.sort.field.name)
            put("sortOrder", value.sort.order.name)
            put(
                "folderSorts",
                JSONObject().apply {
                    value.folderSorts.forEach { (id, spec) ->
                        put(id.toString(), "${spec.field.name}:${spec.order.name}")
                    }
                },
            )
            put("hiddenMediaIds", JSONArray(value.hiddenMediaIds.toList()))
            put("hiddenAlbumIds", JSONArray(value.hiddenAlbumIds.toList()))
            put("showAllFiles", value.showAllFiles)
            put("showOtherFileFolders", value.showOtherFileFolders)
            put("allFilesFolderIds", JSONArray(value.allFilesFolderIds.toList()))
            put("showHidden", value.showHidden)
            put("albumOrder", JSONArray(value.albumOrder))
            put("videoDoubleClickToGallery", value.videoDoubleClickToGallery)
            put("newpipeFolderRelativePath", value.newpipeFolderRelativePath)
            put("nomediaFolderPaths", JSONArray(value.nomediaFolderPaths))
            put("favoriteMediaIds", JSONArray(value.favoriteMediaIds))
            put("hideFavoritesFolder", value.hideFavoritesFolder)
            put("barActionSlots", JSONArray().apply {
                value.barActionSlots.forEach { action ->
                    if (action == null) put(JSONObject.NULL) else put(action.name)
                }
            })
            put("menuActionOrder", JSONArray(value.menuActionOrder.map { it.name }))
            put("hiddenSelectionActions", JSONArray(value.hiddenSelectionActions.map { it.name }))
            put("rememberFolderScrollAcrossSessions", value.rememberFolderScrollAcrossSessions)
            put("newContentScrollsToTop", value.newContentScrollsToTop)
            put(
                "savedFolderScrolls",
                JSONObject().apply {
                    value.savedFolderScrolls.forEach { (id, pos) ->
                        put(id.toString(), "${pos.index}:${pos.offset}")
                    }
                },
            )
            put("autoplayGifs", value.autoplayGifs)
            put("showNavArrows", value.showNavArrows)
            put("anonymousFolderIds", JSONArray(value.anonymousFolderIds.toList()))
            put("randomizeFileNamesInPrivateFolders", value.randomizeFileNamesInPrivateFolders)
            put("stripExifInPrivateFolders", value.stripExifInPrivateFolders)
            put("stripExifOnShare", value.stripExifOnShare)
            put("checkUpdates", value.checkUpdates)
            put("operationsFollowSelectionOrder", value.operationsFollowSelectionOrder)
            put("scrollerOnRight", value.scrollerOnRight)
            put("scrollerEnabled", value.scrollerEnabled)
            put("scrollerSmooth", value.scrollerSmooth)
            put(
                "folderContentOrders",
                JSONObject().apply {
                    value.folderContentOrders.forEach { (id, ids) ->
                        put(id.toString(), JSONArray(ids))
                    }
                },
            )
            put("customWallpaperFoldedWidth", value.customWallpaperFoldedWidth)
            put("customWallpaperFoldedHeight", value.customWallpaperFoldedHeight)
            put("customWallpaperUnfoldedWidth", value.customWallpaperUnfoldedWidth)
            put("customWallpaperUnfoldedHeight", value.customWallpaperUnfoldedHeight)
            put("autoFoldSplitEnabled", value.autoFoldSplitEnabled)
            put("foldSplitAngleThreshold", value.foldSplitAngleThreshold.toDouble())
            put("dynamicSplit", value.dynamicSplit)
            put("showFoldableSettings", value.showFoldableSettings)
            put("smartSearchEnabled", value.smartSearchEnabled)
            put("aiEffects", value.aiEffects.toJson())
            put("hiddenCover", value.hiddenCover.toJson())
            put("smartIndexScope", value.smartIndexScope.name)
            put("smartIndexIncludedFolderIds", JSONArray(value.smartIndexIncludedFolderIds.toList()))
            put("smartIndexExcludedFolderIds", JSONArray(value.smartIndexExcludedFolderIds.toList()))
            val playerJson = dev.ander.alexet.integration.AnderLineClient.configToJson(value.playerConfig)
            put("application", playerJson.getJSONObject("application"))
            put("player", playerJson.getJSONObject("player"))
            put("lightMode", value.lightMode)
            put("highResThumbnails", value.highResThumbnails)
        }.toString(2)
    }

    /**
     * [idForPath] finds this phone's file for a portable path; [onSecurePaths] receives the private
     * folders listed in the file.
     */
    fun importJson(
        text: String,
        idForPath: (String) -> Long? = { null },
        onSecurePaths: (List<String>) -> Unit = {},
    ): Boolean = runCatching {
        if (text.isBlank()) return@runCatching false
        // Windows editors (Notepad) save UTF-8 with a byte order mark; JSONObject rejects it.
        val o = JSONObject(text.removePrefix("\uFEFF"))
        if (o.length() == 0) return@runCatching false
        // Missing schemaVersion treated as 1 for backward compatibility with older exports.
        val schemaVersion = if (o.has("schemaVersion")) o.optInt("schemaVersion", -1) else 1
        if (schemaVersion != 1) {
            dev.ander.alexet.data.AppLog.w("SettingsStore", "Unsupported config schemaVersion=$schemaVersion")
            return@runCatching false
        }
        val paths = o.optJSONObject("mediaPaths")
        val filePending = MediaRefs.fromJson(o.optJSONObject("pendingMedia"))
        // Built on the settings at the moment of the write: a change made while the file was being
        // read (last folder, scroll positions, sorts...) is kept instead of being overwritten.
        fun build(current: AppSettings): AppSettings {
            val playerConfig = if (o.has("player") || o.has("application") || o.has("enableVolumeSwipeGesture") || o.has("useMaterialYouControls")) {
                dev.ander.alexet.integration.AnderLineClient.parseConfig(text, fallback = current.playerConfig)
            } else {
                current.playerConfig
            }
            val imported = AppSettings(
                contentViewMode = if (o.has("contentViewMode")) enumValue(o.optString("contentViewMode"), current.contentViewMode) else current.contentViewMode,
                panelSplitPercent = if (o.has("panelSplitPercent")) {
                    o.optInt("panelSplitPercent", current.panelSplitPercent).coerceIn(10, 90)
                } else if (o.has("fiftyFiftySplit")) {
                    if (o.optBoolean("fiftyFiftySplit")) 50 else 40
                } else {
                    current.panelSplitPercent
                },
                multiFolderMemory = if (o.has("multiFolderMemory")) enumValue(o.optString("multiFolderMemory"), current.multiFolderMemory) else current.multiFolderMemory,
                expandColumns = if (o.has("expandColumns")) o.optInt("expandColumns", current.expandColumns).coerceIn(2, 6) else current.expandColumns,
                expandRows = if (o.has("expandRows")) o.optInt("expandRows", current.expandRows).coerceIn(2, 6) else current.expandRows,
                gridColumns = if (o.has("gridColumns")) o.optInt("gridColumns", current.gridColumns).coerceIn(1, 30) else current.gridColumns,
                // Files from before the split had the folders' count.
                fileColumns = if (o.has("fileColumns")) {
                    o.optInt("fileColumns", current.fileColumns).coerceIn(1, 30)
                } else if (o.has("gridColumns")) {
                    o.optInt("gridColumns", current.gridColumns).coerceIn(1, 30)
                } else {
                    current.fileColumns
                },
                foldableFileColumns = if (o.has("foldableFileColumns")) o.optInt("foldableFileColumns", current.foldableFileColumns).coerceIn(1, 10) else current.foldableFileColumns,
                lastOpenedFolderId = current.lastOpenedFolderId,
                rememberedOpenFolders = current.rememberedOpenFolders,
                showFileNames = if (o.has("showFileNames")) o.optBoolean("showFileNames", current.showFileNames) else current.showFileNames,
                videoDurationFormat = if (o.has("videoDurationFormat")) enumValue(o.optString("videoDurationFormat"), current.videoDurationFormat) else current.videoDurationFormat,
                fileSelectMode = if (o.has("fileSelectMode")) enumValue(o.optString("fileSelectMode"), current.fileSelectMode) else current.fileSelectMode,
                searchFilesFullScreen = if (o.has("searchFilesFullScreen")) o.optBoolean("searchFilesFullScreen", current.searchFilesFullScreen) else current.searchFilesFullScreen,
                sort = if (o.has("sortField") || o.has("sortOrder")) {
                    SortSpec(
                        field = enumValue(o.optString("sortField"), current.sort.field),
                        order = enumValue(o.optString("sortOrder"), current.sort.order),
                    )
                } else current.sort,
                folderSorts = if (o.has("folderSorts")) parseFolderSorts(o.optJSONObject("folderSorts")) else current.folderSorts,
                hiddenMediaIds = if (o.has("hiddenMediaIds")) parseIds(o.optJSONArray("hiddenMediaIds")) else current.hiddenMediaIds,
                hiddenAlbumIds = if (o.has("hiddenAlbumIds")) parseIds(o.optJSONArray("hiddenAlbumIds")) else current.hiddenAlbumIds,
                showAllFiles = if (o.has("showAllFiles")) o.optBoolean("showAllFiles", current.showAllFiles) else current.showAllFiles,
                showOtherFileFolders = if (o.has("showOtherFileFolders")) o.optBoolean("showOtherFileFolders", current.showOtherFileFolders) else current.showOtherFileFolders,
                allFilesFolderIds = if (o.has("allFilesFolderIds")) parseIds(o.optJSONArray("allFilesFolderIds")) else current.allFilesFolderIds,
                showHidden = if (o.has("showHidden")) o.optBoolean("showHidden", current.showHidden) else current.showHidden,
                albumOrder = if (o.has("albumOrder")) parseIdList(o.optJSONArray("albumOrder")) else current.albumOrder,
                videoDoubleClickToGallery = if (o.has("videoDoubleClickToGallery")) o.optBoolean("videoDoubleClickToGallery", current.videoDoubleClickToGallery) else current.videoDoubleClickToGallery,
                newpipeFolderRelativePath = if (o.has("newpipeFolderRelativePath")) o.optString("newpipeFolderRelativePath") else current.newpipeFolderRelativePath,
                nomediaFolderPaths = if (o.has("nomediaFolderPaths")) parseStringList(o.optJSONArray("nomediaFolderPaths")) else current.nomediaFolderPaths,
                favoriteMediaIds = if (o.has("favoriteMediaIds")) parseIdList(o.optJSONArray("favoriteMediaIds")) else current.favoriteMediaIds,
                hideFavoritesFolder = if (o.has("hideFavoritesFolder")) o.optBoolean("hideFavoritesFolder", current.hideFavoritesFolder) else current.hideFavoritesFolder,
                barActionSlots = if (o.has("barActionSlots")) parseNullableActionList(o.optJSONArray("barActionSlots")) else current.barActionSlots,
                menuActionOrder = if (o.has("menuActionOrder")) parseActionList(o.optJSONArray("menuActionOrder"), current.menuActionOrder) else current.menuActionOrder,
                hiddenSelectionActions = if (o.has("hiddenSelectionActions")) parseActionSet(o.optJSONArray("hiddenSelectionActions")) else current.hiddenSelectionActions,
                rememberFolderScrollAcrossSessions = if (o.has("rememberFolderScrollAcrossSessions")) o.optBoolean("rememberFolderScrollAcrossSessions", current.rememberFolderScrollAcrossSessions) else current.rememberFolderScrollAcrossSessions,
                newContentScrollsToTop = if (o.has("newContentScrollsToTop")) o.optBoolean("newContentScrollsToTop", current.newContentScrollsToTop) else current.newContentScrollsToTop,
                savedFolderScrolls = if (o.has("savedFolderScrolls")) parseFolderScrolls(o.optJSONObject("savedFolderScrolls")) else current.savedFolderScrolls,
                autoplayGifs = if (o.has("autoplayGifs")) o.optBoolean("autoplayGifs", current.autoplayGifs) else current.autoplayGifs,
                playerConfig = playerConfig,
                showNavArrows = if (o.has("showNavArrows")) o.optBoolean("showNavArrows", current.showNavArrows) else current.showNavArrows,
                anonymousFolderIds = if (o.has("anonymousFolderIds")) parseIds(o.optJSONArray("anonymousFolderIds")) else current.anonymousFolderIds,
                randomizeFileNamesInPrivateFolders = if (o.has("randomizeFileNamesInPrivateFolders")) o.optBoolean("randomizeFileNamesInPrivateFolders", current.randomizeFileNamesInPrivateFolders) else current.randomizeFileNamesInPrivateFolders,
                stripExifInPrivateFolders = if (o.has("stripExifInPrivateFolders")) o.optBoolean("stripExifInPrivateFolders", current.stripExifInPrivateFolders) else current.stripExifInPrivateFolders,
                stripExifOnShare = if (o.has("stripExifOnShare")) o.optBoolean("stripExifOnShare", current.stripExifOnShare) else current.stripExifOnShare,
                checkUpdates = if (o.has("checkUpdates")) o.optBoolean("checkUpdates", current.checkUpdates) else current.checkUpdates,
                operationsFollowSelectionOrder = if (o.has("operationsFollowSelectionOrder")) o.optBoolean("operationsFollowSelectionOrder", current.operationsFollowSelectionOrder) else current.operationsFollowSelectionOrder,
                scrollerOnRight = if (o.has("scrollerOnRight")) o.optBoolean("scrollerOnRight", current.scrollerOnRight) else current.scrollerOnRight,
                scrollerEnabled = if (o.has("scrollerEnabled")) o.optBoolean("scrollerEnabled", current.scrollerEnabled) else current.scrollerEnabled,
                scrollerSmooth = if (o.has("scrollerSmooth")) o.optBoolean("scrollerSmooth", current.scrollerSmooth) else current.scrollerSmooth,
                highResThumbnails = if (o.has("highResThumbnails")) o.optBoolean("highResThumbnails", current.highResThumbnails) else current.highResThumbnails,
                lightMode = if (o.has("lightMode")) o.optBoolean("lightMode", current.lightMode) else current.lightMode,
                firstTimeOpened = current.firstTimeOpened,
                folderContentOrders = if (o.has("folderContentOrders")) parseFolderContentOrders(o.optJSONObject("folderContentOrders")) else current.folderContentOrders,
                customWallpaperFoldedWidth = if (o.has("customWallpaperFoldedWidth")) o.optInt("customWallpaperFoldedWidth", current.customWallpaperFoldedWidth) else current.customWallpaperFoldedWidth,
                customWallpaperFoldedHeight = if (o.has("customWallpaperFoldedHeight")) o.optInt("customWallpaperFoldedHeight", current.customWallpaperFoldedHeight) else current.customWallpaperFoldedHeight,
                customWallpaperUnfoldedWidth = if (o.has("customWallpaperUnfoldedWidth")) o.optInt("customWallpaperUnfoldedWidth", current.customWallpaperUnfoldedWidth) else current.customWallpaperUnfoldedWidth,
                customWallpaperUnfoldedHeight = if (o.has("customWallpaperUnfoldedHeight")) o.optInt("customWallpaperUnfoldedHeight", current.customWallpaperUnfoldedHeight) else current.customWallpaperUnfoldedHeight,
                autoFoldSplitEnabled = if (o.has("autoFoldSplitEnabled")) o.optBoolean("autoFoldSplitEnabled", current.autoFoldSplitEnabled) else current.autoFoldSplitEnabled,
                foldSplitAngleThreshold = if (o.has("foldSplitAngleThreshold")) o.optDouble("foldSplitAngleThreshold", current.foldSplitAngleThreshold.toDouble()).toFloat().coerceIn(5f, 90f) else current.foldSplitAngleThreshold,
                dynamicSplit = if (o.has("dynamicSplit")) o.optBoolean("dynamicSplit", current.dynamicSplit) else current.dynamicSplit,
                showFoldableSettings = if (o.has("showFoldableSettings")) o.optBoolean("showFoldableSettings", current.showFoldableSettings) else current.showFoldableSettings,
                smartSearchEnabled = if (o.has("smartSearchEnabled")) o.optBoolean("smartSearchEnabled", current.smartSearchEnabled) else current.smartSearchEnabled,
                aiEffects = o.optJSONObject("aiEffects")?.let { AiEffectsConfig.parse(it, fallback = current.aiEffects) } ?: current.aiEffects,
                hiddenCover = o.optJSONObject("hiddenCover")?.let { HiddenCoverConfig.parse(it, fallback = current.hiddenCover) } ?: current.hiddenCover,
                smartIndexScope = if (o.has("smartIndexScope")) enumValue(o.optString("smartIndexScope"), current.smartIndexScope) else current.smartIndexScope,
                smartIndexIncludedFolderIds = if (o.has("smartIndexIncludedFolderIds")) parseIds(o.optJSONArray("smartIndexIncludedFolderIds")) else current.smartIndexIncludedFolderIds,
                smartIndexExcludedFolderIds = if (o.has("smartIndexExcludedFolderIds")) parseIds(o.optJSONArray("smartIndexExcludedFolderIds")) else current.smartIndexExcludedFolderIds,
                // Device-local: a paused indexer stays as it is on this phone.
                smartIndexPaused = current.smartIndexPaused,
                foldableDetected = current.foldableDetected,
                // Detected on this device, not part of the file.
                debugLogging = current.debugLogging,
                lastDetectedFoldedWidth = current.lastDetectedFoldedWidth,
                lastDetectedFoldedHeight = current.lastDetectedFoldedHeight,
                lastDetectedUnfoldedWidth = current.lastDetectedUnfoldedWidth,
                lastDetectedUnfoldedHeight = current.lastDetectedUnfoldedHeight,
            )
            val remapped = MediaRefs.remap(
                settings = imported,
                withPaths = paths != null,
                pathOfImportedId = { id -> paths?.optString(id.toString())?.takeIf { it.isNotBlank() } },
                idForPath = idForPath,
                importedPending = filePending,
            )
            // Pending refs (files not on this phone yet) of a kind the file does not carry stay.
            val kept = current.pendingMedia
            return remapped.copy(
                pendingMedia = PendingMediaRefs(
                    hidden = remapped.pendingMedia.hidden + (if (o.has("hiddenMediaIds")) emptySet() else kept.hidden),
                    favorites = (remapped.pendingMedia.favorites + (if (o.has("favoriteMediaIds")) emptyList() else kept.favorites)).distinct(),
                    contentOrders = if (o.has("folderContentOrders")) remapped.pendingMedia.contentOrders else kept.contentOrders + remapped.pendingMedia.contentOrders,
                ),
            )
        }
        update { build(it) }
        o.optJSONArray("securePaths")?.let { arr ->
            onSecurePaths((0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } })
        }
        true
    }.getOrDefault(false)

    private fun read(): AppSettings {
        return AppSettings(
            contentViewMode = ContentViewMode.entries
                .getOrElse(prefs.getInt(KEY_MODE, 0)) { ContentViewMode.LAST_FOLDER },
            panelSplitPercent = if (prefs.contains(KEY_PANEL_SPLIT)) {
                prefs.getInt(KEY_PANEL_SPLIT, 40).coerceIn(10, 90)
            } else if (prefs.contains(KEY_FIFTY_FIFTY)) {
                if (prefs.getBoolean(KEY_FIFTY_FIFTY, false)) 50 else 40
            } else {
                40
            },
            multiFolderMemory = if (prefs.getBoolean(KEY_MULTI_REMEMBER, true)) {
                MultiFolderMemory.REMEMBER
            } else {
                MultiFolderMemory.FRESH
            },
            expandColumns = prefs.getInt(KEY_COLS, 6).coerceIn(2, 6),
            expandRows = prefs.getInt(KEY_ROWS, 5).coerceIn(2, 6),
            gridColumns = prefs.getInt(KEY_GRID_COLUMNS, 3).coerceIn(1, 30),
            // Not set yet (before the split): the files keep the folders' count.
            fileColumns = prefs.getInt(KEY_FILE_COLUMNS, prefs.getInt(KEY_GRID_COLUMNS, 3)).coerceIn(1, 30),
            foldableFileColumns = prefs.getInt(KEY_FOLDABLE_FILE_COLUMNS, 3).coerceIn(1, 10),
            lastOpenedFolderId = prefs.getLong(KEY_LAST_FOLDER, AlbumIds.NONE),
            rememberedOpenFolders = prefs.getString(KEY_OPEN_FOLDERS, "")
                .orEmpty()
                .split(',')
                .mapNotNull { it.toLongOrNull() },
            showFileNames = prefs.getBoolean(KEY_SHOW_NAMES, false),
            videoDurationFormat = enumValue(prefs.getString(KEY_VIDEO_DURATION_FORMAT, null), VideoDurationFormat.MINUTES),
            fileSelectMode = enumValue(prefs.getString(KEY_FILE_SELECT, null), FileSelectMode.RECTANGLE),
            searchFilesFullScreen = prefs.getBoolean(KEY_SEARCH_FULL, true),
            sort = SortSpec(
                field = enumValue(prefs.getString(KEY_SORT_FIELD, null), SortField.DATE_TAKEN),
                order = enumValue(prefs.getString(KEY_SORT_ORDER, null), SortOrder.DESCENDING),
            ),
            folderSorts = parseFolderSorts(prefs.getString(KEY_FOLDER_SORTS, null)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }),
            hiddenMediaIds = parseCsvIds(prefs.getString(KEY_HIDDEN_MEDIA, "")),
            hiddenAlbumIds = parseCsvIds(prefs.getString(KEY_HIDDEN_ALBUMS, "")),
            showAllFiles = prefs.getBoolean(KEY_SHOW_ALL_FILES, false),
            showOtherFileFolders = prefs.getBoolean(KEY_SHOW_OTHER_FILE_FOLDERS, false),
            allFilesFolderIds = parseCsvIds(prefs.getString(KEY_ALL_FILES_FOLDERS, "")),
            showHidden = prefs.getBoolean(KEY_SHOW_HIDDEN, false),
            albumOrder = parseCsvIdList(prefs.getString(KEY_ALBUM_ORDER, "")),
            videoDoubleClickToGallery = prefs.getBoolean(KEY_VIDEO_DOUBLE_CLICK, false),
            newpipeFolderRelativePath = prefs.getString(KEY_NEWPIPE_FOLDER, "").orEmpty(),
            nomediaFolderPaths = parseStringList(prefs.getString(KEY_NOMEDIA_PATHS, null)),
            favoriteMediaIds = parseCsvIdList(prefs.getString(KEY_FAVORITE_MEDIA, "")),
            hideFavoritesFolder = prefs.getBoolean(KEY_HIDE_FAVORITES, false),
            barActionSlots = parseCsvNullableActionList(prefs.getString(KEY_BAR_SLOTS, null)),
            menuActionOrder = parseCsvActionList(prefs.getString(KEY_MENU_ORDER, null), AppSettings.DEFAULT_MENU_ORDER),
            hiddenSelectionActions = parseCsvActionSet(prefs.getString(KEY_HIDDEN_SELECTION_ACTIONS, "")),
            rememberFolderScrollAcrossSessions = prefs.getBoolean(KEY_REMEMBER_FOLDER_SCROLL, false),
            newContentScrollsToTop = prefs.getBoolean(KEY_NEW_CONTENT_SCROLLS_TO_TOP, false),
            savedFolderScrolls = parseFolderScrolls(prefs.getString(KEY_SAVED_FOLDER_SCROLLS, null)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }),
            autoplayGifs = prefs.getBoolean(KEY_AUTOPLAY_GIFS, true),
            playerConfig = prefs.getString(KEY_PLAYER_CONFIG, null)?.let {
                dev.ander.alexet.integration.AnderLineClient.parseConfig(it)
            } ?: dev.ander.alexet.integration.AnderLineConfig(),
            showNavArrows = prefs.getBoolean(KEY_SHOW_NAV_ARROWS, false),
            anonymousFolderIds = parseCsvIds(prefs.getString(KEY_ANONYMOUS_FOLDERS, "")),
            randomizeFileNamesInPrivateFolders = prefs.getBoolean(KEY_RANDOMIZE_FILE_NAMES_IN_PRIVATE_FOLDERS, true),
            stripExifInPrivateFolders = prefs.getBoolean(KEY_STRIP_EXIF_IN_PRIVATE_FOLDERS, true),
            stripExifOnShare = prefs.getBoolean(KEY_STRIP_EXIF_ON_SHARE, false),
            checkUpdates = prefs.getBoolean(KEY_CHECK_UPDATES, true),
            operationsFollowSelectionOrder = prefs.getBoolean(KEY_OPERATIONS_FOLLOW_SELECTION_ORDER, false),
            scrollerOnRight = prefs.getBoolean(KEY_SCROLLER_ON_RIGHT, false),
            scrollerEnabled = prefs.getBoolean(KEY_SCROLLER_ENABLED, true),
            scrollerSmooth = prefs.getBoolean(KEY_SCROLLER_SMOOTH, true),
            highResThumbnails = prefs.getBoolean(KEY_HIGH_RES_THUMBNAILS, false),
            lightMode = prefs.getBoolean(KEY_LIGHT_MODE, false),
            firstTimeOpened = prefs.getInt(KEY_FIRST_TIME_OPENED, 0),
            folderContentOrders = parseFolderContentOrders(prefs.getString(KEY_FOLDER_CONTENT_ORDERS, null)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }),
            customWallpaperFoldedWidth = prefs.getInt(KEY_CUSTOM_WALLPAPER_FOLDED_W, 0),
            customWallpaperFoldedHeight = prefs.getInt(KEY_CUSTOM_WALLPAPER_FOLDED_H, 0),
            customWallpaperUnfoldedWidth = prefs.getInt(KEY_CUSTOM_WALLPAPER_UNFOLDED_W, 0),
            customWallpaperUnfoldedHeight = prefs.getInt(KEY_CUSTOM_WALLPAPER_UNFOLDED_H, 0),
            autoFoldSplitEnabled = prefs.getBoolean(KEY_AUTO_FOLD_SPLIT_ENABLED, true),
            foldSplitAngleThreshold = prefs.getFloat(KEY_FOLD_SPLIT_ANGLE_THRESHOLD, 20f),
            dynamicSplit = prefs.getBoolean(KEY_DYNAMIC_SPLIT, true),
            showFoldableSettings = prefs.getBoolean(KEY_SHOW_FOLDABLE_SETTINGS, false),
            foldableDetected = prefs.getBoolean(KEY_FOLDABLE_DETECTED, false),
            pendingMedia = MediaRefs.fromJson(prefs.getString(KEY_PENDING_MEDIA, null)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }),
            debugLogging = prefs.getBoolean(KEY_DEBUG_LOGGING, false),
            smartSearchEnabled = prefs.getBoolean(KEY_SMART_SEARCH, false),
            hiddenCover = prefs.getString(KEY_HIDDEN_COVER, null)
                ?.let { raw -> runCatching { HiddenCoverConfig.parse(JSONObject(raw)) }.getOrNull() }
                ?: HiddenCoverConfig(),
            aiEffects = prefs.getString(KEY_AI_EFFECTS, null)
                ?.let { raw -> runCatching { AiEffectsConfig.parse(JSONObject(raw)) }.getOrNull() }
                ?: AiEffectsConfig(),
            smartIndexScope = enumValue(prefs.getString(KEY_SMART_INDEX_SCOPE, null), SmartIndexScope.ALL),
            smartIndexIncludedFolderIds = parseCsvIds(prefs.getString(KEY_SMART_INDEX_INCLUDED, "")),
            smartIndexExcludedFolderIds = parseCsvIds(prefs.getString(KEY_SMART_INDEX_EXCLUDED, "")),
            smartIndexPaused = prefs.getBoolean(KEY_SMART_INDEX_PAUSED, false),
        )
    }

    private fun write(value: AppSettings, sync: Boolean = false) {
        prefs.edit(commit = sync) {
            putInt(KEY_MODE, value.contentViewMode.ordinal)
            putInt(KEY_PANEL_SPLIT, value.panelSplitPercent.coerceIn(10, 90))
            putBoolean(KEY_MULTI_REMEMBER, value.multiFolderMemory == MultiFolderMemory.REMEMBER)
            putInt(KEY_COLS, value.expandColumns)
            putInt(KEY_ROWS, value.expandRows)
            putInt(KEY_GRID_COLUMNS, value.gridColumns)
            putInt(KEY_FILE_COLUMNS, value.fileColumns)
            putInt(KEY_FOLDABLE_FILE_COLUMNS, value.foldableFileColumns)
            putLong(KEY_LAST_FOLDER, value.lastOpenedFolderId)
            putString(KEY_OPEN_FOLDERS, value.rememberedOpenFolders.joinToString(","))
            putBoolean(KEY_SHOW_NAMES, value.showFileNames)
            putString(KEY_VIDEO_DURATION_FORMAT, value.videoDurationFormat.name)
            putString(KEY_FILE_SELECT, value.fileSelectMode.name)
            putBoolean(KEY_SEARCH_FULL, value.searchFilesFullScreen)
            putString(KEY_SORT_FIELD, value.sort.field.name)
            putString(KEY_SORT_ORDER, value.sort.order.name)
            putString(
                KEY_FOLDER_SORTS,
                JSONObject().apply {
                    value.folderSorts.forEach { (id, spec) ->
                        put(id.toString(), "${spec.field.name}:${spec.order.name}")
                    }
                }.toString(),
            )
            putString(KEY_HIDDEN_MEDIA, value.hiddenMediaIds.joinToString(","))
            putString(KEY_HIDDEN_ALBUMS, value.hiddenAlbumIds.joinToString(","))
            putBoolean(KEY_SHOW_ALL_FILES, value.showAllFiles)
            putBoolean(KEY_SHOW_OTHER_FILE_FOLDERS, value.showOtherFileFolders)
            putString(KEY_ALL_FILES_FOLDERS, value.allFilesFolderIds.joinToString(","))
            putBoolean(KEY_SHOW_HIDDEN, value.showHidden)
            putString(KEY_ALBUM_ORDER, value.albumOrder.joinToString(","))
            putBoolean(KEY_VIDEO_DOUBLE_CLICK, value.videoDoubleClickToGallery)
            putString(KEY_NEWPIPE_FOLDER, value.newpipeFolderRelativePath)
            putString(KEY_NOMEDIA_PATHS, JSONArray(value.nomediaFolderPaths).toString())
            putString(KEY_FAVORITE_MEDIA, value.favoriteMediaIds.joinToString(","))
            putBoolean(KEY_HIDE_FAVORITES, value.hideFavoritesFolder)
            putString(KEY_BAR_SLOTS, value.barActionSlots.joinToString(",") { it?.name.orEmpty() })
            putString(KEY_MENU_ORDER, value.menuActionOrder.joinToString(",") { it.name })
            putString(KEY_HIDDEN_SELECTION_ACTIONS, value.hiddenSelectionActions.joinToString(",") { it.name })
            putBoolean(KEY_REMEMBER_FOLDER_SCROLL, value.rememberFolderScrollAcrossSessions)
            putBoolean(KEY_NEW_CONTENT_SCROLLS_TO_TOP, value.newContentScrollsToTop)
            putString(
                KEY_SAVED_FOLDER_SCROLLS,
                JSONObject().apply {
                    value.savedFolderScrolls.forEach { (id, pos) ->
                        put(id.toString(), "${pos.index}:${pos.offset}")
                    }
                }.toString(),
            )
            putBoolean(KEY_AUTOPLAY_GIFS, value.autoplayGifs)
            putBoolean(KEY_SHOW_NAV_ARROWS, value.showNavArrows)
            putString(KEY_ANONYMOUS_FOLDERS, value.anonymousFolderIds.joinToString(","))
            putBoolean(KEY_RANDOMIZE_FILE_NAMES_IN_PRIVATE_FOLDERS, value.randomizeFileNamesInPrivateFolders)
            putBoolean(KEY_STRIP_EXIF_IN_PRIVATE_FOLDERS, value.stripExifInPrivateFolders)
            putBoolean(KEY_STRIP_EXIF_ON_SHARE, value.stripExifOnShare)
            putBoolean(KEY_CHECK_UPDATES, value.checkUpdates)
            putBoolean(KEY_OPERATIONS_FOLLOW_SELECTION_ORDER, value.operationsFollowSelectionOrder)
            putBoolean(KEY_SCROLLER_ON_RIGHT, value.scrollerOnRight)
            putBoolean(KEY_SCROLLER_ENABLED, value.scrollerEnabled)
            putBoolean(KEY_SCROLLER_SMOOTH, value.scrollerSmooth)
            putBoolean(KEY_HIGH_RES_THUMBNAILS, value.highResThumbnails)
            putBoolean(KEY_LIGHT_MODE, value.lightMode)
            putInt(KEY_FIRST_TIME_OPENED, value.firstTimeOpened)
            putString(
                KEY_FOLDER_CONTENT_ORDERS,
                JSONObject().apply {
                    value.folderContentOrders.forEach { (id, ids) ->
                        put(id.toString(), JSONArray(ids))
                    }
                }.toString(),
            )
            putString(KEY_PLAYER_CONFIG, dev.ander.alexet.integration.AnderLineClient.configToJson(value.playerConfig).toString())
            putInt(KEY_CUSTOM_WALLPAPER_FOLDED_W, value.customWallpaperFoldedWidth)
            putInt(KEY_CUSTOM_WALLPAPER_FOLDED_H, value.customWallpaperFoldedHeight)
            putInt(KEY_CUSTOM_WALLPAPER_UNFOLDED_W, value.customWallpaperUnfoldedWidth)
            putInt(KEY_CUSTOM_WALLPAPER_UNFOLDED_H, value.customWallpaperUnfoldedHeight)
            putBoolean(KEY_AUTO_FOLD_SPLIT_ENABLED, value.autoFoldSplitEnabled)
            putFloat(KEY_FOLD_SPLIT_ANGLE_THRESHOLD, value.foldSplitAngleThreshold)
            putBoolean(KEY_DYNAMIC_SPLIT, value.dynamicSplit)
            putBoolean(KEY_SHOW_FOLDABLE_SETTINGS, value.showFoldableSettings)
            putBoolean(KEY_FOLDABLE_DETECTED, value.foldableDetected)
            putBoolean(KEY_DEBUG_LOGGING, value.debugLogging)
            putBoolean(KEY_SMART_SEARCH, value.smartSearchEnabled)
            putString(KEY_AI_EFFECTS, value.aiEffects.toJson().toString())
            putString(KEY_HIDDEN_COVER, value.hiddenCover.toJson().toString())
            putString(KEY_SMART_INDEX_SCOPE, value.smartIndexScope.name)
            putString(KEY_SMART_INDEX_INCLUDED, value.smartIndexIncludedFolderIds.joinToString(","))
            putString(KEY_SMART_INDEX_EXCLUDED, value.smartIndexExcludedFolderIds.joinToString(","))
            putBoolean(KEY_SMART_INDEX_PAUSED, value.smartIndexPaused)
            if (value.pendingMedia.isEmpty) {
                remove(KEY_PENDING_MEDIA)
            } else {
                putString(KEY_PENDING_MEDIA, MediaRefs.toJson(value.pendingMedia).toString())
            }
            remove(KEY_DETECTED_WALLPAPER_FOLDED_W)
            remove(KEY_DETECTED_WALLPAPER_FOLDED_H)
            remove(KEY_DETECTED_WALLPAPER_UNFOLDED_W)
            remove(KEY_DETECTED_WALLPAPER_UNFOLDED_H)
        }
    }

    fun setShowNavArrows(show: Boolean) = update { it.copy(showNavArrows = show) }
    fun toggleAnonymousFolder(folderId: Long) = update {
        it.copy(anonymousFolderIds = if (folderId in it.anonymousFolderIds) it.anonymousFolderIds - folderId else it.anonymousFolderIds + folderId)
    }
    /**
     * Only the folders changed in the name-mixer dialog: a folder renamed meanwhile already has its
     * new id here, and writing the dialog's whole (older) set back would drop it.
     */
    fun changeAnonymousFolders(added: Set<Long>, removed: Set<Long>) = update {
        it.copy(anonymousFolderIds = it.anonymousFolderIds - removed + added)
    }
    fun setRandomizeFileNamesInPrivateFolders(randomize: Boolean) = update { it.copy(randomizeFileNamesInPrivateFolders = randomize) }
    fun setStripExifInPrivateFolders(strip: Boolean) = update { it.copy(stripExifInPrivateFolders = strip) }
    fun setStripExifOnShare(strip: Boolean) = update { it.copy(stripExifOnShare = strip) }
    fun setCheckUpdates(enabled: Boolean) = update { it.copy(checkUpdates = enabled) }
    fun setOperationsFollowSelectionOrder(enabled: Boolean) = update { it.copy(operationsFollowSelectionOrder = enabled) }
    fun setFolderContentOrder(folderId: Long, orderedIds: List<Long>) = update { settings ->
        val next = settings.folderContentOrders.toMutableMap()
        if (orderedIds.isEmpty()) next.remove(folderId) else next[folderId] = orderedIds
        settings.copy(folderContentOrders = next)
    }


    private companion object {
        const val KEY_MODE = "content_view_mode"
        const val KEY_PANEL_SPLIT = "panel_split_percent"
        const val KEY_FIFTY_FIFTY = "pane_split_fifty"
        const val KEY_MULTI_REMEMBER = "multi_remember"
        const val KEY_COLS = "expand_columns"
        const val KEY_ROWS = "expand_rows"
        const val KEY_GRID_COLUMNS = "grid_columns"
        const val KEY_FILE_COLUMNS = "file_columns"
        const val KEY_FOLDABLE_FILE_COLUMNS = "foldable_file_columns"
        const val KEY_LAST_FOLDER = "last_opened_folder"
        const val KEY_OPEN_FOLDERS = "remembered_open_folders"
        const val KEY_SHOW_NAMES = "show_file_names"
        const val KEY_VIDEO_DURATION_FORMAT = "video_duration_format"
        const val KEY_FILE_SELECT = "file_select_mode"
        const val KEY_SEARCH_FULL = "search_files_fullscreen_v2"
        const val KEY_SORT_FIELD = "sort_field"
        const val KEY_SORT_ORDER = "sort_order"
        const val KEY_FOLDER_SORTS = "folder_sorts"
        const val KEY_HIDDEN_MEDIA = "hidden_media_ids"
        const val KEY_HIDDEN_ALBUMS = "hidden_album_ids"
        const val KEY_SHOW_ALL_FILES = "show_all_files"
        const val KEY_SHOW_OTHER_FILE_FOLDERS = "show_other_file_folders"
        const val KEY_ALL_FILES_FOLDERS = "all_files_folder_ids"
        const val KEY_SHOW_HIDDEN = "show_hidden"
        const val KEY_ALBUM_ORDER = "album_order"
        const val KEY_VIDEO_DOUBLE_CLICK = "video_double_click_gallery"
        const val KEY_NOMEDIA_PATHS = "nomedia_folder_paths"
        const val KEY_FAVORITE_MEDIA = "favorite_media_ids"
        const val KEY_HIDE_FAVORITES = "hide_favorites_folder"
        const val KEY_BAR_SLOTS = "bar_action_slots_v3"
        const val KEY_MENU_ORDER = "menu_action_order_v3"
        const val KEY_HIDDEN_SELECTION_ACTIONS = "hidden_selection_actions"
        const val KEY_REMEMBER_FOLDER_SCROLL = "remember_folder_scroll_across_sessions"
        const val KEY_NEW_CONTENT_SCROLLS_TO_TOP = "new_content_scrolls_to_top"
        const val KEY_SAVED_FOLDER_SCROLLS = "saved_folder_scrolls"
        const val KEY_AUTOPLAY_GIFS = "autoplay_gifs"
        const val KEY_PLAYER_CONFIG = "player_config_json"
        const val KEY_PENDING_MEDIA = "pending_media_refs"
        const val KEY_DEBUG_LOGGING = "debug_logging"
        const val KEY_SMART_SEARCH = "smart_search_enabled"
        const val KEY_AI_EFFECTS = "ai_effects_json"
        const val KEY_HIDDEN_COVER = "hidden_cover_json"
        const val KEY_SMART_INDEX_SCOPE = "smart_index_scope"
        const val KEY_SMART_INDEX_INCLUDED = "smart_index_included_folders"
        const val KEY_SMART_INDEX_EXCLUDED = "smart_index_excluded_folders"
        const val KEY_SMART_INDEX_PAUSED = "smart_index_paused"
        const val KEY_SHOW_NAV_ARROWS = "show_nav_arrows"
        const val KEY_ANONYMOUS_FOLDERS = "anonymous_folder_ids"
        const val KEY_RANDOMIZE_FILE_NAMES_IN_PRIVATE_FOLDERS = "randomize_file_names_in_private_folders"
        const val KEY_STRIP_EXIF_IN_PRIVATE_FOLDERS = "strip_exif_in_private_folders"
        const val KEY_STRIP_EXIF_ON_SHARE = "strip_exif_on_share"
        const val KEY_CHECK_UPDATES = "check_updates"
        const val KEY_OPERATIONS_FOLLOW_SELECTION_ORDER = "operations_follow_selection_order"
        const val KEY_FOLDER_CONTENT_ORDERS = "folder_content_orders"
        const val KEY_CUSTOM_WALLPAPER_FOLDED_W = "custom_wallpaper_folded_w"
        const val KEY_CUSTOM_WALLPAPER_FOLDED_H = "custom_wallpaper_folded_h"
        const val KEY_CUSTOM_WALLPAPER_UNFOLDED_W = "custom_wallpaper_unfolded_w"
        const val KEY_CUSTOM_WALLPAPER_UNFOLDED_H = "custom_wallpaper_unfolded_h"
        const val KEY_DETECTED_WALLPAPER_FOLDED_W = "detected_wallpaper_folded_w"
        const val KEY_DETECTED_WALLPAPER_FOLDED_H = "detected_wallpaper_folded_h"
        const val KEY_DETECTED_WALLPAPER_UNFOLDED_W = "detected_wallpaper_unfolded_w"
        const val KEY_DETECTED_WALLPAPER_UNFOLDED_H = "detected_wallpaper_unfolded_h"
        const val KEY_SCROLLER_ON_RIGHT = "scroller_on_right"
        const val KEY_SCROLLER_ENABLED = "scroller_enabled"
        const val KEY_SCROLLER_SMOOTH = "scroller_smooth"
        const val KEY_HIGH_RES_THUMBNAILS = "high_res_thumbnails"
        const val KEY_LIGHT_MODE = "light_mode"
        const val KEY_FIRST_TIME_OPENED = "first_time_opened"
        const val KEY_AUTO_FOLD_SPLIT_ENABLED = "auto_fold_split_enabled"
        const val KEY_FOLD_SPLIT_ANGLE_THRESHOLD = "fold_split_angle_threshold"
        const val KEY_DYNAMIC_SPLIT = "dynamic_split"
        const val KEY_SHOW_FOLDABLE_SETTINGS = "show_foldable_settings"
        const val KEY_FOLDABLE_DETECTED = "foldable_detected"
        const val KEY_NEWPIPE_FOLDER = "newpipe_folder_relative_path"
    }

    fun setScrollerOnRight(onRight: Boolean) = update { it.copy(scrollerOnRight = onRight) }
    fun setScrollerEnabled(enabled: Boolean) = update { it.copy(scrollerEnabled = enabled) }
    fun setScrollerSmooth(smooth: Boolean) = update { it.copy(scrollerSmooth = smooth) }
    fun setHighResThumbnails(enabled: Boolean) = update { it.copy(highResThumbnails = enabled) }

    /** Light mode is a preset: turning it on also turns sharper thumbnails off (they can be turned on again). */
    fun setLightMode(enabled: Boolean) = update {
        it.copy(
            lightMode = enabled,
            highResThumbnails = if (enabled) false else it.highResThumbnails,
            // An animation: light mode turns it off (it can be turned on again).
            dynamicSplit = if (enabled) false else it.dynamicSplit,
        )
    }

    /** Folders were shown for the first time: the one-off speed check is done (whatever the user answers). */
    fun markFirstTimeOpened() = update { if (it.firstTimeOpened != 0) it else it.copy(firstTimeOpened = 1) }

    fun setAutoFoldSplitEnabled(enabled: Boolean) = update { it.copy(autoFoldSplitEnabled = enabled) }
    fun setFoldSplitAngleThreshold(threshold: Float) = update { it.copy(foldSplitAngleThreshold = threshold.coerceIn(5f, 90f)) }
    fun setDynamicSplit(enabled: Boolean) = update { it.copy(dynamicSplit = enabled) }
    fun setShowFoldableSettings(show: Boolean) = update { it.copy(showFoldableSettings = show) }
    fun setShowAllFiles(show: Boolean) = update { it.copy(showAllFiles = show) }
    fun setShowOtherFileFolders(show: Boolean) = update { it.copy(showOtherFileFolders = show) }

    /** Switches "see all files" for one folder (kept until switched back). */
    fun toggleAllFilesFolder(albumId: Long) = update {
        val ids = it.allFilesFolderIds
        it.copy(allFilesFolderIds = if (albumId in ids) ids - albumId else ids + albumId)
    }

    /** A renamed folder keeps its "see all files" choice under its new id. */
    fun moveAllFilesFolder(oldId: Long, newId: Long) = update {
        if (oldId !in it.allFilesFolderIds || oldId == newId) it
        else it.copy(allFilesFolderIds = it.allFilesFolderIds - oldId + newId)
    }
    /** Once a hinge or fold is seen it stays known (folded, the phone reports no fold). */
    fun markFoldableDetected() = update { if (it.foldableDetected) it else it.copy(foldableDetected = true) }

    fun setNewpipeFolder(relativePath: String) = update {
        it.copy(newpipeFolderRelativePath = relativePath.trim().trim('/'))
    }

    fun setCustomWallpaperResolutions(foldedW: Int, foldedH: Int, unfoldedW: Int, unfoldedH: Int) = update {
        it.copy(
            customWallpaperFoldedWidth = foldedW.coerceAtLeast(0),
            customWallpaperFoldedHeight = foldedH.coerceAtLeast(0),
            customWallpaperUnfoldedWidth = unfoldedW.coerceAtLeast(0),
            customWallpaperUnfoldedHeight = unfoldedH.coerceAtLeast(0),
        )
    }

    fun setDetectedWallpaperResolutions(
        foldedW: Int? = null,
        foldedH: Int? = null,
        unfoldedW: Int? = null,
        unfoldedH: Int? = null,
    ) {
        if (foldedW != null && foldedH != null && foldedW > 0 && foldedH > 0) {
            dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedFoldedWidth = foldedW
            dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedFoldedHeight = foldedH
        }
        if (unfoldedW != null && unfoldedH != null && unfoldedW > 0 && unfoldedH > 0) {
            dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedUnfoldedWidth = unfoldedW
            dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedUnfoldedHeight = unfoldedH
        }
        update { current ->
            var next = current
            if (foldedW != null && foldedH != null && foldedW > 0 && foldedH > 0) {
                next = next.copy(lastDetectedFoldedWidth = foldedW, lastDetectedFoldedHeight = foldedH)
            }
            if (unfoldedW != null && unfoldedH != null && unfoldedW > 0 && unfoldedH > 0) {
                next = next.copy(lastDetectedUnfoldedWidth = unfoldedW, lastDetectedUnfoldedHeight = unfoldedH)
            }
            next
        }
    }
}

private fun parseCsvIds(raw: String?): Set<Long> =
    parseCsvIdList(raw).toSet()

private fun parseCsvIdList(raw: String?): List<Long> {
    if (raw.isNullOrBlank()) return emptyList()
    val seen = HashSet<Long>()
    return raw.split(',').mapNotNull { it.toLongOrNull() }.filter { seen.add(it) }
}

private fun parseIds(array: JSONArray?): Set<Long> = parseIdList(array).toSet()

private fun parseIdList(array: JSONArray?): List<Long> {
    if (array == null) return emptyList()
    val seen = HashSet<Long>()
    return buildList {
        for (i in 0 until array.length()) {
            val id = array.optLong(i).takeIf { it != 0L }
                ?: array.optString(i).toLongOrNull()
                ?: continue
            if (seen.add(id)) add(id)
        }
    }
}

private fun parseFolderContentOrders(obj: JSONObject?): Map<Long, List<Long>> {
    if (obj == null) return emptyMap()
    val result = mutableMapOf<Long, List<Long>>()
    obj.keys().forEach { key ->
        val id = key.toLongOrNull() ?: return@forEach
        val arr = obj.optJSONArray(key) ?: return@forEach
        result[id] = parseIdList(arr)
    }
    return result
}

private fun parseFolderSorts(obj: JSONObject?): Map<Long, SortSpec> {
    if (obj == null) return emptyMap()
    val result = mutableMapOf<Long, SortSpec>()
    obj.keys().forEach { key ->
        val id = key.toLongOrNull() ?: return@forEach
        val parts = obj.optString(key).split(':')
        val field = enumValue(parts.getOrNull(0), SortField.DATE_TAKEN)
        val order = enumValue(parts.getOrNull(1), SortOrder.DESCENDING)
        result[id] = SortSpec(field, order)
    }
    return result
}

private fun parseFolderScrolls(obj: JSONObject?): Map<Long, FolderScrollPos> {
    if (obj == null) return emptyMap()
    val result = mutableMapOf<Long, FolderScrollPos>()
    obj.keys().forEach { key ->
        val id = key.toLongOrNull() ?: return@forEach
        val parts = obj.optString(key).split(':')
        val index = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val offset = parts.getOrNull(1)?.toIntOrNull() ?: 0
        result[id] = FolderScrollPos(index, offset)
    }
    return result
}

private fun parseStringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val seen = HashSet<String>()
    return buildList {
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim().trim('/')
            if (value.isEmpty()) continue
            val path = "$value/"
            if (seen.add(path.lowercase())) add(path)
        }
    }
}

private fun parseStringList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    runCatching { JSONArray(raw) }.getOrNull()?.let { return parseStringList(it) }
    return raw.split('\n').map { it.trim().trim('/') }.filter { it.isNotEmpty() }.map { "$it/" }.distinct()
}

private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T {
    if (raw.isNullOrBlank()) return fallback
    return runCatching { java.lang.Enum.valueOf(T::class.java, raw) }.getOrDefault(fallback)
}

private fun parseNullableActionList(array: JSONArray?): List<SelectionAction?> {
    if (array == null) return AppSettings.DEFAULT_BAR_SLOTS
    val result = mutableListOf<SelectionAction?>()
    for (i in 0 until 6) {
        if (i < array.length() && !array.isNull(i)) {
            val name = array.optString(i)
            val action = SelectionAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.key.equals(name, ignoreCase = true) }
            result.add(action)
        } else {
            result.add(null)
        }
    }
    return result
}

private fun parseCsvNullableActionList(raw: String?): List<SelectionAction?> {
    if (raw == null) return AppSettings.DEFAULT_BAR_SLOTS
    val parts = raw.split(',')
    val result = mutableListOf<SelectionAction?>()
    for (i in 0 until 6) {
        val item = parts.getOrNull(i)?.trim()
        if (item.isNullOrEmpty()) {
            result.add(null)
        } else {
            val action = SelectionAction.entries.firstOrNull { it.name.equals(item, ignoreCase = true) || it.key.equals(item, ignoreCase = true) }
            result.add(action)
        }
    }
    return result
}

private fun parseActionList(array: JSONArray?, fallback: List<SelectionAction> = SelectionAction.DEFAULT_ORDER): List<SelectionAction> {
    if (array == null) return fallback
    val result = mutableListOf<SelectionAction>()
    for (i in 0 until array.length()) {
        val name = array.optString(i)
        val action = SelectionAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.key.equals(name, ignoreCase = true) }
        if (action != null && action !in result) {
            result.add(action)
        }
    }
    return if (result.isEmpty()) fallback else result
}

private fun parseActionSet(array: JSONArray?): Set<SelectionAction> {
    if (array == null) return emptySet()
    val result = mutableSetOf<SelectionAction>()
    for (i in 0 until array.length()) {
        val name = array.optString(i)
        val action = SelectionAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.key.equals(name, ignoreCase = true) }
        if (action != null) {
            result.add(action)
        }
    }
    return result
}

private fun parseCsvActionList(raw: String?, fallback: List<SelectionAction> = SelectionAction.DEFAULT_ORDER): List<SelectionAction> {
    if (raw.isNullOrBlank()) return fallback
    val result = mutableListOf<SelectionAction>()
    raw.split(',').forEach { item ->
        val trimmed = item.trim()
        val action = SelectionAction.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) || it.key.equals(trimmed, ignoreCase = true) }
        if (action != null && action !in result) {
            result.add(action)
        }
    }
    return if (result.isEmpty()) fallback else result
}

private fun parseCsvActionSet(raw: String?): Set<SelectionAction> {
    if (raw.isNullOrBlank()) return emptySet()
    val result = mutableSetOf<SelectionAction>()
    raw.split(',').forEach { item ->
        val trimmed = item.trim()
        val action = SelectionAction.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) || it.key.equals(trimmed, ignoreCase = true) }
        if (action != null) {
            result.add(action)
        }
    }
    return result
}
