package dev.ander.alexet.ui.gallery

import android.app.Application
import android.graphics.Bitmap
import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.ander.alexet.R
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.AppLog
import dev.ander.alexet.data.AppGraph
import dev.ander.alexet.data.FolderDeleteProgress
import dev.ander.alexet.data.FolderDeleteQueue
import dev.ander.alexet.data.SecureFolderManager
import dev.ander.alexet.data.AlbumIds
import dev.ander.alexet.data.AlphanumericComparator
import dev.ander.alexet.data.AppSettings
import dev.ander.alexet.data.ContentViewMode
import dev.ander.alexet.data.ExifUtils
import dev.ander.alexet.data.FileSelectMode
import dev.ander.alexet.data.FolderRowIndex
import dev.ander.alexet.data.FolderScrollPos
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.ActiveDownload
import dev.ander.alexet.data.DownloadTracker
import dev.ander.alexet.data.holdsDownload
import dev.ander.alexet.data.DownloadsWatchLogic
import dev.ander.alexet.ui.wallpaper.WallpaperCropResult
import dev.ander.alexet.data.GalleryRepository
import dev.ander.alexet.data.ImageEditEngine
import dev.ander.alexet.data.ImageEditState
import dev.ander.alexet.data.MultiFolderMemory
import dev.ander.alexet.data.NomediaScanner
import dev.ander.alexet.data.PriorityRolloffHelper
import dev.ander.alexet.data.RenameRequest
import dev.ander.alexet.data.SearchMode
import dev.ander.alexet.data.searchesFiles
import dev.ander.alexet.data.smartsearch.SmartSearch
import dev.ander.alexet.data.SelectionAction
import dev.ander.alexet.data.SettingsStore
import dev.ander.alexet.data.SortField
import dev.ander.alexet.data.SortOrder
import dev.ander.alexet.data.SortSpec
import dev.ander.alexet.data.StorageAccess
import dev.ander.alexet.data.VideoDurationFormat
import dev.ander.alexet.data.TransferProgress
import dev.ander.alexet.data.TrashManager
import dev.ander.alexet.data.TrashedItem
import dev.ander.alexet.data.applyAlbumOrder
import dev.ander.alexet.data.albumIdsIncludingLegacy
import dev.ander.alexet.data.remapAlbumId
import dev.ander.alexet.data.remapAlbumIds
import dev.ander.alexet.data.remapFolderScrolls
import dev.ander.alexet.data.remapFolderSorts
import dev.ander.alexet.data.remapHiddenAlbumIds
import dev.ander.alexet.data.findCoverMedia
import dev.ander.alexet.data.sortMedia
import dev.ander.alexet.data.usesFolderDrag
import dev.ander.alexet.integration.NextPlayerClient
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import coil3.SingletonImageLoader

@Immutable
enum class DragKind { NONE, FOLDER, FILES }

@Immutable
enum class PaneStack { NONE, TOP, BOTTOM }

internal const val CANVAS_RATIO = 0.90f
internal const val CANVAS_SNAP = 0.86f

@Immutable
data class GalleryUiState(
    val media: List<GalleryMedia> = emptyList(),
    val albums: List<Album> = emptyList(),
    val allAlbums: List<Album> = emptyList(),
    val trashedItems: List<TrashedItem> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val searchMode: SearchMode = SearchMode.FOLDERS,
    val query: String = "",
    val showSettings: Boolean = false,
    val phoneFolderId: Long = AlbumIds.NONE,
    val sessionFolderId: Long = AlbumIds.NONE,
    val openFolderIds: List<Long> = emptyList(),
    val clickedFolderId: Long = AlbumIds.NONE,
    val pendingHitboxAlbumId: Long = AlbumIds.NONE,
    val highlightedFolderId: Long = AlbumIds.NONE,
    val selectedFileIds: Set<Long> = emptySet(),
    val selectedFileIdsOrdered: List<Long> = emptyList(),
    val selectedAlbumIds: Set<Long> = emptySet(),
    val stampedAlbumIds: Set<Long> = emptySet(),
    val reorderingFolders: Boolean = false,
    val reorderDraftIds: List<Long> = emptyList(),
    val contentOrderMode: Boolean = false,
    val contentOrderDraftIds: List<Long> = emptyList(),
    val contentOrderFolderId: Long = AlbumIds.NONE,
    val contentOrderRenameNumeric: Boolean = false,
    val dragKind: DragKind = DragKind.NONE,
    val draggingFolderId: Long = AlbumIds.NONE,
    val filesDragSourceAlbumId: Long = AlbumIds.NONE,
    val hoveredAlbumId: Long = AlbumIds.NONE,
    val hoveredSlot: Int = -1,
    val viewerMediaId: Long? = null,
    val externalMedia: GalleryMedia? = null,
    val isLoading: Boolean = true,
    val nextPlayerInstalled: Boolean = false,
    val randomSeed: Long = 1L,
    val revealHidden: Boolean = false,
    val hasHidden: Boolean = false,
    val folderThumbEpoch: Int = 0,
    val folderCoverBumps: Map<Long, Int> = emptyMap(),
    val splitRatio: Float = 0.5f,
    val paneStack: PaneStack = PaneStack.NONE,
    val editingMedia: GalleryMedia? = null,
    val isSavingEdit: Boolean = false,
    val editingVideoList: List<GalleryMedia>? = null,
    val isExportingVideo: Boolean = false,
    val exportVideoProgress: Int = 0,
    val isRefreshing: Boolean = false,
    val cachedViewerMedia: GalleryMedia? = null,
    val loadingFolderIds: Set<Long> = emptySet(),
    val folderMedia: Map<Long, List<GalleryMedia>> = emptyMap(),
    /** Folders with a download in progress (their tiles show a badge). */
    val downloadingAlbumIds: Set<Long> = emptySet(),
    /** Whole folders being deleted in the background: their tiles show the progress. */
    val deletingFolders: Map<Long, FolderDeleteProgress> = emptyMap(),
    /** Smart search: ids of the photos matching [query], best first. */
    val smartResultIds: List<Long> = emptyList(),
    val smartSearch: SmartSearch.Status = SmartSearch.Status(),
    /** A smart search is running (the loading animation). */
    val smartSearching: Boolean = false,
    /** Bumped when a smart search brings results (the results glow). */
    val smartResultsVersion: Int = 0,
    /** Folders or files: the mode before AI search was switched on, and after it is switched off. */
    val modeBeforeSmart: SearchMode = SearchMode.FOLDERS,
    /** The search field is open. */
    val searchOpen: Boolean = false,
    /** Localized names of the virtual folders. */
    val trashName: String = "Trash",
    val favoritesName: String = "Favorites",
) {
    fun isFolderLoading(albumId: Long): Boolean =
        albumId in loadingFolderIds && folderMedia[albumId].isNullOrEmpty()

    val inTrash: Boolean by lazy { computeInTrash() }
    private fun computeInTrash(): Boolean = phoneFolderId == AlbumIds.TRASH ||
            AlbumIds.TRASH in openFolderIds ||
            contentFolderId == AlbumIds.TRASH ||
            (selectedFileIds.isNotEmpty() && selectedFiles.any {
                it.bucketId == AlbumIds.TRASH ||
                it.absolutePath?.contains(".alexet_trash", ignoreCase = true) == true ||
                it.bucketName.equals(".alexet_trash", ignoreCase = true) ||
                trashedItems.any { t -> t.id == it.id }
            }) ||
            (viewerMediaId != null && (
                trashedItems.any { it.id == viewerMediaId } ||
                viewerMedia?.bucketId == AlbumIds.TRASH ||
                viewerMedia?.absolutePath?.contains(".alexet_trash", ignoreCase = true) == true ||
                viewerMedia?.bucketName?.equals(".alexet_trash", ignoreCase = true) == true
            ))

    val trashAlbum: Album by lazy { computeTrashAlbum() }
    private fun computeTrashAlbum(): Album = Album(
            id = AlbumIds.TRASH,
            name = trashName,
            cover = trashedItems.firstOrNull()?.toGalleryMedia(),
            itemCount = trashedItems.size,
            relativePath = "",
            isHidden = true,
        )

    val favoriteFiles: List<GalleryMedia> by lazy { computeFavoriteFiles() }
    private fun computeFavoriteFiles(): List<GalleryMedia> {
            val byId = media.associateBy { it.id }
            return settings.favoriteMediaIds.mapNotNull { byId[it] }
        }

    val favoritesAlbum: Album by lazy { computeFavoritesAlbum() }
    private fun computeFavoritesAlbum(): Album {
            val favs = favoriteFiles
            return Album(
                id = AlbumIds.FAVORITES,
                name = favoritesName,
                cover = favs.firstOrNull(),
                itemCount = favs.size,
                relativePath = "",
                isHidden = settings.hideFavoritesFolder,
            )
        }

    val inFavorites: Boolean get() = phoneFolderId == AlbumIds.FAVORITES

    val phoneFolder: Album? by lazy { computePhoneFolder() }
    private fun computePhoneFolder(): Album? = when (phoneFolderId) {
            AlbumIds.TRASH -> trashAlbum
            AlbumIds.FAVORITES -> favoritesAlbum
            else -> albums.firstOrNull { it.id == phoneFolderId }
                ?: allAlbums.firstOrNull { it.id == phoneFolderId }
                ?: if (phoneFolderId != AlbumIds.NONE) {
                    Album(
                        id = phoneFolderId,
                        name = allAlbums.firstOrNull { it.id == phoneFolderId }?.name ?: "Folder",
                        cover = null,
                        itemCount = 0,
                        relativePath = "",
                    )
                } else null
        }

    val viewerMedia: GalleryMedia? by lazy { computeViewerMedia() }
    private fun computeViewerMedia(): GalleryMedia? = externalMedia?.takeIf { it.id == viewerMediaId }
            ?: trashedItems.firstOrNull { it.id == viewerMediaId }?.toGalleryMedia()
            ?: media.firstOrNull { it.id == viewerMediaId }
            ?: folderMedia.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == viewerMediaId } }
            ?: cachedViewerMedia?.takeIf { it.id == viewerMediaId }

    val allCandidateMedia: List<GalleryMedia> by lazy { computeAllCandidateMedia() }
    private fun computeAllCandidateMedia(): List<GalleryMedia> = if (folderMedia.isEmpty()) media else
        UiStateMemo.get("allCandidateMedia", media, folderMedia) { (media + folderMedia.values.flatten()).distinctBy { it.id } }

    val showPaneHitboxes: Boolean
        get() = pendingHitboxAlbumId != AlbumIds.NONE ||
            (dragKind == DragKind.FOLDER && openFolderIds.isNotEmpty())

    val visibleAlbums: List<Album> by lazy { computeVisibleAlbums() }
    /** AI search open with nothing typed: the folder grid shows only the folders it covers. */
    val showingSmartFolders: Boolean
        get() = searchOpen && searchMode == SearchMode.SMART && query.isBlank()

    private fun computeVisibleAlbums(): List<Album> = if (showingSmartFolders) {
            UiStateMemo.get(
                "smartFolders", albums, settings.smartIndexScope, settings.smartIndexIncludedFolderIds, settings.smartIndexExcludedFolderIds,
            ) { albums.filter { coveredBySmartSearch(it) } }
        } else if (query.isBlank() || searchMode != SearchMode.FOLDERS) {
            albums
        } else {
            albums.filter { it.name.contains(query, ignoreCase = true) }
        }

    /** The smart search can be used: switched on, and its model is installed. */
    val smartSearchAvailable: Boolean
        get() = settings.smartSearchEnabled && smartSearch.ready

    /** The AI search covers the opened folder (or no folder is open): its folder choice, never a private one. */
    val smartSearchCoversOpenFolder: Boolean
        get() {
            if (phoneFolderId == AlbumIds.NONE) return true
            if (phoneFolder?.isSecure == true) return false
            return dev.ander.alexet.data.smartsearch.SmartFolderFilter.of(settings).accepts(phoneFolderId)
        }

    /** Folders shown while AI search is on: the ones it covers, private folders never. */
    fun coveredBySmartSearch(album: Album): Boolean =
        !album.isSecure && dev.ander.alexet.data.smartsearch.SmartFolderFilter.of(settings).accepts(album.id)

    val searchFiles: List<GalleryMedia> by lazy { computeSearchFiles() }
    private fun computeSearchFiles(): List<GalleryMedia> = if (query.isBlank() || !searchMode.searchesFiles) {
            emptyList()
        } else if (phoneFolderId != AlbumIds.NONE) {
            visibleFilesIn(phoneFolderId)
        } else if (searchMode == SearchMode.SMART) {
            UiStateMemo.get("smartSearchFiles", media, smartResultIds) {
                val byId = media.associateBy { it.id }
                smartResultIds.mapNotNull { byId[it] }
            }
        } else {
            UiStateMemo.get("searchFiles", media, query) {
                media.filter { it.displayName.contains(query, ignoreCase = true) }
            }
        }

    val selectedFiles: List<GalleryMedia> by lazy { computeSelectedFiles() }
    private fun computeSelectedFiles(): List<GalleryMedia> {
            // Read on every recomposition of the gallery (bar flags): nothing selected must not
            // copy the whole library.
            if (selectedFileIds.isEmpty() && selectedFileIdsOrdered.isEmpty()) return emptyList()
            val allAvailable = allCandidateMedia
            if (settings.operationsFollowSelectionOrder && selectedFileIdsOrdered.isNotEmpty()) {
                val byId = HashMap<Long, GalleryMedia>(allAvailable.size + trashedItems.size)
                allAvailable.forEach { byId[it.id] = it }
                trashedItems.forEach { byId.putIfAbsent(it.id, it.toGalleryMedia()) }
                return selectedFileIdsOrdered.mapNotNull { byId[it] }
            }
            val activeList = if (phoneFolderId != AlbumIds.NONE) (folderMedia[phoneFolderId] ?: media) else media
            val selectedInActive = activeList.filter { it.id in selectedFileIds }
            val selectedInActiveIds = selectedInActive.map { it.id }.toSet()
            val remaining = (allAvailable.filter { it.id in selectedFileIds && it.id !in selectedInActiveIds } +
                trashedItems.filter { it.id in selectedFileIds && it.id !in selectedInActiveIds }.map { it.toGalleryMedia() })
            val activeSort = settings.folderSorts[phoneFolderId] ?: settings.sort
            val sortedRemaining = sortMedia(remaining, activeSort)
            return (selectedInActive + sortedRemaining).distinctBy { it.id }
        }

    val selectedAlbums: List<Album> by lazy { computeSelectedAlbums() }
    private fun computeSelectedAlbums(): List<Album> = albums.filter { it.id in selectedAlbumIds }

    val selectionCount: Int get() = selectedFileIds.size + selectedAlbumIds.size

    val showingHidden: Boolean
        get() = settings.showHidden || revealHidden

    val reorderAlbums: List<Album> by lazy { computeReorderAlbums() }
    private fun computeReorderAlbums(): List<Album> {
            if (!reorderingFolders) return emptyList()
            val byId = albums.associateBy { it.id }
            val seen = HashSet<Long>()
            return buildList {
                for (id in reorderDraftIds) {
                    val album = byId[id] ?: continue
                    if (seen.add(id)) add(album)
                }
                for (album in albums) {
                    if (seen.add(album.id)) add(album)
                }
            }
        }

    val contentOrderFiles: List<GalleryMedia> by lazy { computeContentOrderFiles() }
    private fun computeContentOrderFiles(): List<GalleryMedia> {
            if (!contentOrderMode) return emptyList()
            val byId = media.associateBy { it.id }
            return contentOrderDraftIds.mapNotNull { byId[it] }
        }

    val sortTargetAlbumId: Long
        get() = when {
            phoneFolderId != AlbumIds.NONE -> phoneFolderId
            contentFolderId != AlbumIds.NONE -> contentFolderId
            openFolderIds.isNotEmpty() -> openFolderIds.first()
            else -> AlbumIds.NONE
        }

    val contentFolderId: Long by lazy { computeContentFolderId() }
    private fun computeContentFolderId(): Long = when (settings.contentViewMode) {
            ContentViewMode.LAST_FOLDER -> {
                if (sessionFolderId == AlbumIds.TRASH) AlbumIds.TRASH
                else remapAlbumId(settings.lastOpenedFolderId, allAlbums)
            }
            ContentViewMode.CURRENT_SESSION -> sessionFolderId
            else -> AlbumIds.NONE
        }

    /**
     * Field that orders [albumId]'s files, for the scroller bubble. Null when the order has no
     * readable key: trash, unsorted favorites, a custom (hand-arranged) order or random.
     */
    fun scrollLabelField(albumId: Long): SortField? {
        val field = when (albumId) {
            AlbumIds.TRASH -> return null
            AlbumIds.FAVORITES -> {
                if (!settings.hasFolderSort(AlbumIds.FAVORITES)) return null
                settings.sortFor(AlbumIds.FAVORITES).field
            }
            else -> {
                val legacy = allAlbums.firstOrNull { it.id == albumId }?.legacyBucketIds ?: emptySet()
                val contentOrder = settings.folderContentOrders[albumId]
                    ?: legacy.firstNotNullOfOrNull { settings.folderContentOrders[it] }
                if (!contentOrder.isNullOrEmpty()) return null
                settings.sortFor(albumId, legacy).field
            }
        }
        return field.takeIf { it != SortField.RANDOM }
    }

    /**
     * Results kept for this state object (it never changes): the screens ask for the same lists
     * many times per frame, and each one filtered and sorted the whole library again.
     * On a miss [UiStateMemo] still hands back the previous state's list when its inputs are the
     * same objects, so a copy for an unrelated field (download tick, hover, progress) neither
     * re-sorts the library nor gives the grid a new list.
     */
    private val filesInCache = java.util.concurrent.ConcurrentHashMap<Long, List<GalleryMedia>>()
    private val visibleFilesInCache = java.util.concurrent.ConcurrentHashMap<Long, List<GalleryMedia>>()

    fun filesIn(albumId: Long): List<GalleryMedia> = filesInCache.getOrPut(albumId) {
        when (albumId) {
            AlbumIds.TRASH -> UiStateMemo.get("filesIn" to albumId, trashedItems) { computeFilesIn(albumId) }
            // Only the settings it reads: any other write (a scroll position) made a new settings
            // object and every folder was filtered and sorted again.
            AlbumIds.FAVORITES -> UiStateMemo.get(
                "filesIn" to albumId, media, settings.favoriteMediaIds, settings.sort, settings.folderSorts, randomSeed,
            ) { computeFilesIn(albumId) }
            else -> UiStateMemo.get(
                "filesIn" to albumId, media, allAlbums, folderMedia[albumId], settings.sort, settings.folderSorts, settings.folderContentOrders, randomSeed,
            ) {
                val startedAt = SystemClock.elapsedRealtime()
                computeFilesIn(albumId).also { files ->
                    AppLog.event("Library") {
                        "files of folder $albumId: ${files.size} in ${SystemClock.elapsedRealtime() - startedAt} ms " +
                            "(library ${media.size}, kept list ${folderMedia[albumId]?.size ?: 0})"
                    }
                }
            }
        }
    }

    private fun computeFilesIn(albumId: Long): List<GalleryMedia> =
        when (albumId) {
            AlbumIds.TRASH -> trashedItems.map { it.toGalleryMedia() }
            AlbumIds.FAVORITES -> {
                if (settings.hasFolderSort(AlbumIds.FAVORITES)) {
                    sortMedia(favoriteFiles, settings.sortFor(AlbumIds.FAVORITES), randomSeed)
                } else {
                    favoriteFiles
                }
            }
            else -> {
                val album = allAlbums.firstOrNull { it.id == albumId }
                val cached = folderMedia[albumId]
                val belongs: (GalleryMedia) -> Boolean = { item ->
                    item.bucketId == albumId ||
                        (album != null && item.rawBucketId != 0L && item.rawBucketId in album.legacyBucketIds) ||
                        (album != null && album.relativePath.isNotBlank() && item.relativePath.trim().trim('/').equals(album.relativePath.trim().trim('/'), ignoreCase = true))
                }
                fun fileKey(item: GalleryMedia): String {
                    val path = item.absolutePath?.trim()?.lowercase().orEmpty()
                    if (path.isNotEmpty()) return "p:$path"
                    val rel = item.relativePath.trim().trim('/').lowercase()
                    if (rel.isNotEmpty()) return "r:$rel/${item.displayName.lowercase()}"
                    return "n:${item.displayName.lowercase()}:${item.sizeBytes}"
                }
                // One index of the library for every folder: each folder filtered (and trimmed the
                // path of) every row of the library, after every change.
                val index = UiStateMemo.get("folderRowIndex", media) { FolderRowIndex(media) }
                val baseItems = if (!cached.isNullOrEmpty()) {
                    if (media.isEmpty()) {
                        // Cold start: trust folder cache until global media arrives.
                        cached.distinctBy { it.id }
                    } else {
                        val globalFiltered = index.rowsOf(albumId, album)
                        val globalById = index.byId
                        // Drop cache rows that (a) global already placed in another album
                        // or (b) are the same file as a global row under a new MediaStore id
                        // (copy/scan often reissues ids — that doubled every tile for seconds).
                        val pruned = cached.filter { item ->
                            val g = globalById[item.id]
                            // Drop only when global relocated this id to another album.
                            // Same-path rows with a new MediaStore id (Images vs Downloads)
                            // stay in cache so tiles keep their working URI.
                            if (g != null && !belongs(g)) return@filter false
                            true
                        }
                        if (globalFiltered.isEmpty()) {
                            pruned.distinctBy { it.id }
                        } else {
                            val globalByKey = globalFiltered.associateBy { fileKey(it) }
                            // One-way: a Downloads-collection URI has no MediaStore thumb and
                            // stays brown. Replace it when the same file exists under Images/Video.
                            val upgraded = pruned.map { item ->
                                val better = globalByKey[fileKey(item)] ?: return@map item
                                if (DownloadsWatchLogic.isDownloadsCollectionUri(item.uri) &&
                                    !DownloadsWatchLogic.isDownloadsCollectionUri(better.uri)
                                ) {
                                    better
                                } else {
                                    item
                                }
                            }
                            val prunedIds = upgraded.mapTo(HashSet()) { it.id }
                            val prunedUris = upgraded.mapTo(HashSet()) { it.uri }
                            val prunedKeys = upgraded.mapTo(HashSet()) { fileKey(it) }
                            val additional = globalFiltered.filter {
                                it.id !in prunedIds && it.uri !in prunedUris && fileKey(it) !in prunedKeys
                            }
                            (upgraded + additional).distinctBy { it.id }
                        }
                    }
                } else {
                    index.rowsOf(albumId, album)
                }
                val contentOrder = settings.folderContentOrders[albumId]
                    ?: album?.legacyBucketIds?.firstNotNullOfOrNull { settings.folderContentOrders[it] }
                if (!contentOrder.isNullOrEmpty()) {
                    val rank = contentOrder.withIndex().associate { it.value to it.index }
                    baseItems.sortedWith(
                        compareBy<GalleryMedia> { rank[it.id] ?: Int.MAX_VALUE }
                            .thenBy(AlphanumericComparator) { it.displayName }
                    )
                } else {
                    sortMedia(
                        baseItems,
                        settings.sortFor(albumId, album?.legacyBucketIds ?: emptySet()),
                        randomSeed,
                    )
                }
            }
        }

    fun visibleFilesIn(albumId: Long): List<GalleryMedia> =
        visibleFilesInCache.getOrPut(albumId) { computeVisibleFilesIn(albumId) }

    private fun computeVisibleFilesIn(albumId: Long): List<GalleryMedia> {
        val all = filesIn(albumId)
        // Inside the opened folder the search is always for its files: the files / folders mode
        // switch is not shown there, and in folder mode the typed text filtered nothing.
        if (query.isNotBlank() && searchMode == SearchMode.SMART && (albumId == phoneFolderId || phoneFolderId == AlbumIds.NONE)) {
            return UiStateMemo.get("smartFilesIn" to albumId, all, smartResultIds) {
                val rank = HashMap<Long, Int>(smartResultIds.size * 2)
                smartResultIds.forEachIndexed { i, id -> rank[id] = i }
                all.filter { it.id in rank }.sortedBy { rank.getValue(it.id) }
            }
        }
        return if (query.isNotBlank() && (searchMode.searchesFiles || albumId == phoneFolderId)) {
            UiStateMemo.get("visibleFilesIn" to albumId, all, query) {
                all.filter { it.displayName.contains(query, ignoreCase = true) }
            }
        } else {
            all
        }
    }

    private val currentViewerListCached: List<GalleryMedia> by lazy { computeCurrentViewerList() }

    fun currentViewerList(): List<GalleryMedia> = currentViewerListCached

    private fun computeCurrentViewerList(): List<GalleryMedia> {
        val viewer = viewerMedia ?: return emptyList()
        val list = viewerCandidates(viewer)
        // Other files (documents…) open outside: the viewer pages over photos, videos and audio.
        return if (list.any { it.isOtherFile && !it.isAudio }) {
            list.filter { !it.isOtherFile || it.isAudio }.ifEmpty { listOf(viewer) }
        } else {
            list
        }
    }

    private fun viewerCandidates(viewer: GalleryMedia): List<GalleryMedia> {
        return when {
            inTrash -> {
                val trash = trashedItems.map { it.toGalleryMedia() }
                if (query.isNotBlank()) trash.filter { it.displayName.contains(query, ignoreCase = true) }.ifEmpty { listOf(viewer) }
                else trash.ifEmpty { listOf(viewer) }
            }
            phoneFolderId != AlbumIds.NONE -> {
                // The opened file may come from another pane than the phone folder: its own
                // folder then, or the pager would show (and jump to) unrelated files.
                visibleFilesIn(phoneFolderId).takeIf { list -> list.any { it.id == viewer.id } }
                    ?: visibleFilesIn(viewer.bucketId).ifEmpty { listOf(viewer) }
            }
            searchMode.searchesFiles && query.isNotBlank() -> {
                searchFiles.ifEmpty { listOf(viewer) }
            }
            else -> {
                visibleFilesIn(viewer.bucketId).ifEmpty { listOf(viewer) }
            }
        }
    }
}

private data class Interaction(
    val searchMode: SearchMode = SearchMode.FOLDERS,
    val query: String = "",
    val smartResultIds: List<Long> = emptyList(),
    val smartStatus: SmartSearch.Status = SmartSearch.Status(),
    val smartSearching: Boolean = false,
    val smartResultsVersion: Int = 0,
    val modeBeforeSmart: SearchMode = SearchMode.FOLDERS,
    val searchOpen: Boolean = false,
    val showSettings: Boolean = false,
    val phoneFolderId: Long = AlbumIds.NONE,
    val sessionFolderId: Long = AlbumIds.NONE,
    val openFolderIds: List<Long> = emptyList(),
    val clickedFolderId: Long = AlbumIds.NONE,
    val pendingHitboxAlbumId: Long = AlbumIds.NONE,
    val highlightedFolderId: Long = AlbumIds.NONE,
    val selectedFileIds: Set<Long> = emptySet(),
    val selectedFileIdsOrdered: List<Long> = emptyList(),
    val selectedAlbumIds: Set<Long> = emptySet(),
    val stampedAlbumIds: Set<Long> = emptySet(),
    val reorderingFolders: Boolean = false,
    val reorderDraftIds: List<Long> = emptyList(),
    val contentOrderMode: Boolean = false,
    val contentOrderDraftIds: List<Long> = emptyList(),
    val contentOrderFolderId: Long = AlbumIds.NONE,
    val contentOrderRenameNumeric: Boolean = false,
    val dragKind: DragKind = DragKind.NONE,
    val draggingFolderId: Long = AlbumIds.NONE,
    val filesDragSourceAlbumId: Long = AlbumIds.NONE,
    val hoveredAlbumId: Long = AlbumIds.NONE,
    val hoveredSlot: Int = -1,
    val viewerMediaId: Long? = null,
    val externalMedia: GalleryMedia? = null,
    val loading: Boolean = true,
    val pendingUri: Uri? = null,
    val multiHydrated: Boolean = false,
    val randomSeed: Long = 1L,
    val revealHidden: Boolean = false,
    val splitRatio: Float = 0.5f,
    val paneStack: PaneStack = PaneStack.NONE,
)

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/** Folders of [albums] that a download in [downloads] writes into. */
private fun downloadingAlbumIdsOf(downloads: Map<String, ActiveDownload>, albums: List<Album>): Set<Long> {
    if (downloads.isEmpty()) return emptySet()
    return albums.filter { album -> downloads.keys.any(album::holdsDownload) }.map { it.id }.toSet()
}

private data class SyncState(
    val thumbEpoch: Int,
    val coverBumps: Map<Long, Int>,
    val editingMedia: GalleryMedia?,
    val isSavingEdit: Boolean,
    val editingVideoList: List<GalleryMedia>?,
    val isExportingVideo: Boolean,
    val exportVideoProgress: Int,
    val isScanning: Boolean,
    val isRefreshing: Boolean,
    val loadingFolderIds: Set<Long> = emptySet(),
    /** Folders with a download in progress (their tiles show a badge). */
    val downloadingAlbumIds: Set<Long> = emptySet(),
    /** Whole folders being deleted in the background: their tiles show the progress. */
    val deletingFolders: Map<Long, FolderDeleteProgress> = emptyMap(),
)

private fun Interaction.resetSplitIfNeeded(next: List<Long>): Interaction =
    if (next.size != 2) copy(splitRatio = 0.5f, paneStack = PaneStack.NONE) else this

class GalleryViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    // Shared with FolderDeleteService, which can outlive this screen.
    private val store: SettingsStore = AppGraph.settings(application)
    private val repository: GalleryRepository = AppGraph.repository(application)
    // Made with the screen: the app is visible now (MainActivity reports later changes).
    private val smartSearch: SmartSearch = AppGraph.smartSearch(application).also { it.setAppVisible(true) }
    private var smartSearchJob: Job? = null
    private val trashManager: TrashManager = AppGraph.trash(application)
    private val notifier = dev.ander.alexet.integration.TransferNotifier(application)
    private val interaction = MutableStateFlow(
        Interaction(
            phoneFolderId = savedStateHandle[KEY_PHONE_FOLDER_ID] ?: AlbumIds.NONE,
            viewerMediaId = savedStateHandle[KEY_VIEWER_MEDIA_ID],
        ),
    )
    /**
     * One system consent dialog at a time. A request takes the follow-up fields set for it
     * (pendingAfter..., rollbacks, toasts) with it, so a second operation asking for consent while
     * the first dialog is open no longer overwrites the first one's follow-up; it waits for its turn.
     * Main thread only.
     */
    /** Which of the screen's launchers shows a consent request. */
    enum class ConsentKind { WRITE, DELETE }

    private class ConsentRequest(
        val sender: android.content.IntentSender,
        val kind: ConsentKind,
        val afterWrite: (() -> Unit)?,
        val writeDenied: (() -> Unit)?,
        val moveDeniedToast: Boolean,
        val afterDelete: (() -> Unit)?,
        val trashRollback: List<TrashedItem>?,
        val deleteRollbackIds: Set<Long>?,
        val viewerRollbackId: Long?,
        val safMoveDeleteToast: Boolean,
    )

    /**
     * For the launch of a file operation (move, copy, delete, restore, rename, order): an
     * unexpected error (storage, MediaStore) is logged and shown as [failedRes] instead of closing
     * the app; [cleanup] then tidies what the operation left (progress, masks) and the list is
     * read again. Cancellation is not an error and passes by.
     */
    private fun fileOperationErrors(@androidx.annotation.StringRes failedRes: Int, cleanup: () -> Unit = {}) =
        kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            AppLog.e("GalleryViewModel", "File operation failed", e)
            toast(failedRes)
            runCatching(cleanup).onFailure { AppLog.e("GalleryViewModel", "Cleanup after a failed operation failed", it) }
            repository.requestRefresh()
        }

    private var activeConsent: ConsentRequest? = null
    /** The open request's dialog was shown (its result comes to the screen, even a rebuilt one). */
    private var activeConsentShown = false
    private val waitingConsents = ArrayDeque<ConsentRequest>()

    /**
     * The screen's consent launchers ([GalleryApp] attaches them). Kept here and not in the
     * requests: a request waiting in line held the launcher of a screen rebuilt meanwhile
     * (turned, folded), which no longer worked, and the user's operation counted as refused.
     */
    private var consentLauncher: ((ConsentKind, android.content.IntentSender) -> Unit)? = null

    fun attachConsentLauncher(launch: (ConsentKind, android.content.IntentSender) -> Unit) {
        consentLauncher = launch
        // A request that came while there was no screen is shown now.
        val pending = activeConsent
        if (pending != null && !activeConsentShown) showConsent(pending)
    }

    fun detachConsentLauncher(launch: (ConsentKind, android.content.IntentSender) -> Unit) {
        if (consentLauncher === launch) consentLauncher = null
    }

    fun requestWriteConsent(sender: android.content.IntentSender) = requestConsent(sender, ConsentKind.WRITE)

    fun requestDeleteConsent(sender: android.content.IntentSender) = requestConsent(sender, ConsentKind.DELETE)

    /** Shows [sender] now, or after the consent dialog that is open. */
    private fun requestConsent(sender: android.content.IntentSender, kind: ConsentKind) {
        val request = ConsentRequest(
            sender = sender,
            kind = kind,
            afterWrite = pendingAfterWrite,
            writeDenied = pendingWriteDenied,
            moveDeniedToast = pendingMoveDeniedToast,
            afterDelete = pendingAfterDelete,
            trashRollback = pendingTrashRollback,
            deleteRollbackIds = pendingDeleteRollbackIds,
            viewerRollbackId = pendingViewerRollbackId,
            safMoveDeleteToast = pendingSafMoveDeleteToast,
        )
        // The fields belong to this request now.
        restoreConsentFields(null)
        if (activeConsent == null) showConsent(request) else waitingConsents.addLast(request)
    }

    private fun showConsent(request: ConsentRequest) {
        activeConsent = request
        activeConsentShown = false
        // No screen at the moment (being rebuilt): shown when the new one attaches.
        val launch = consentLauncher ?: return
        try {
            launch(request.kind, request.sender)
            activeConsentShown = true
        } catch (e: Exception) {
            // No dialog could be shown: the same as a refusal, and the next one goes on.
            AppLog.w("GalleryViewModel", "Consent dialog could not be shown", e)
            takeActiveConsent()
            onConsentRefused()
        }
    }

    /** The open request's fields back in place for its result handler. */
    private fun takeActiveConsent() {
        val request = activeConsent ?: return
        activeConsent = null
        activeConsentShown = false
        restoreConsentFields(request)
    }

    private fun restoreConsentFields(request: ConsentRequest?) {
        pendingAfterWrite = request?.afterWrite
        pendingWriteDenied = request?.writeDenied
        pendingMoveDeniedToast = request?.moveDeniedToast ?: false
        pendingAfterDelete = request?.afterDelete
        pendingTrashRollback = request?.trashRollback
        pendingDeleteRollbackIds = request?.deleteRollbackIds
        pendingViewerRollbackId = request?.viewerRollbackId
        pendingSafMoveDeleteToast = request?.safMoveDeleteToast ?: false
    }

    private fun showNextConsent() {
        if (activeConsent != null) return
        waitingConsents.removeFirstOrNull()?.let { showConsent(it) }
    }

    /** A refusal of whatever kind the request was: both cleanups (only its own fields are set). */
    private fun onConsentRefused() {
        onWriteDeniedInternal()
        onDeleteDeniedInternal()
        showNextConsent()
    }

    private var pendingAfterWrite: (() -> Unit)? = null
    /** Cleanup when write consent is refused (e.g. files left under temporary names). */
    private var pendingWriteDenied: (() -> Unit)? = null
    private var pendingAfterDelete: (() -> Unit)? = null
    private var pendingTrashRollback: List<TrashedItem>? = null
    private var pendingDeleteRollbackIds: Set<Long>? = null
    private var pendingViewerRollbackId: Long? = null
    private var folderIdsRemapped = false
    private var pendingMoveDeniedToast = false
    private var pendingSafMoveDeleteToast = false
    private var pickerHiddenHold = false
    private var highlightJob: Job? = null
    /** Quick scan per shown folder; cancelled when the folder is left. Main thread only. */
    private val quickScanJobs = HashMap<Long, Job>()
    /** First tile on screen per folder (last scroll stop), for the half scan's close look. */
    private val firstVisibleIndex = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private var lastExternalPlayId = 0L
    private var lastExternalPlayAt = 0L
    private var lastBackPressAt = 0L
    var sessionSkipDeleteConfirm = false
    var sessionSkipTrash = false
    /** Startup loader already handed over to the gallery in this ViewModel's lifetime. */
    var startupRevealed = false
        private set

    fun markStartupRevealed() {
        startupRevealed = true
    }
    @Volatile private var lastActiveViewerMedia: GalleryMedia? = null
    private val viewerCacheLock = Any()

    /**
     * From the state computation (another thread): kept only while the viewer still shows [media],
     * so a computation from before closeViewer can't put the closed photo back.
     */
    private fun cacheViewerMedia(media: GalleryMedia) = synchronized(viewerCacheLock) {
        val ix = interaction.value
        if (ix.viewerMediaId == media.id || (ix.pendingUri != null && ix.pendingUri == media.uri)) {
            lastActiveViewerMedia = media
        }
    }
    private val folderScrollLru = LinkedHashMap<Long, FolderScrollPos>()
    private val folderThumbEpoch = MutableStateFlow(0)
    private val folderCoverBumps = MutableStateFlow<Map<Long, Int>>(emptyMap())
    private val _transferProgress = MutableStateFlow<TransferProgress?>(null)

    /**
     * Read apart from [uiState] where they are shown: their ticks (a download every 250 ms, each
     * file of a move, each indexed batch) rebuilt the whole state and redrew the whole gallery.
     */
    val activeDownloads: StateFlow<Map<String, ActiveDownload>> = repository.activeDownloads
    val transferProgress: StateFlow<TransferProgress?> = _transferProgress.asStateFlow()
    val smartStatus: StateFlow<SmartSearch.Status> = smartSearch.status
    private val _editingMedia = MutableStateFlow<GalleryMedia?>(null)
    private val _isSavingEdit = MutableStateFlow(false)
    private val _editSaveProgress = MutableStateFlow<Float?>(null)
    /** 0..1 while an edited GIF is written frame by frame; null otherwise. */
    val editSaveProgress: StateFlow<Float?> = _editSaveProgress.asStateFlow()

    /** Editor opened by an external ACTION_EDIT: the original is never overwritten. */
    private val _editCopyOnly = MutableStateFlow(false)
    val editCopyOnly: StateFlow<Boolean> = _editCopyOnly

    /**
     * The external edit came from another app (not from a screenshot taken over Alexet itself):
     * when it ends the task goes to the back so the user lands where "Edit" was tapped.
     */
    private var leaveAfterEdit = false
    private var pendingReturnToCaller = true

    /** Fires when an external edit ends (saved or closed): the UI returns to the calling app. */
    // A channel keeps the event until the screen reads it: sent while the activity was being
    // recreated, a shared flow dropped it and the app stayed open after an outside edit.
    private val _leaveAfterExternalEdit = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val leaveAfterExternalEdit: kotlinx.coroutines.flow.Flow<Unit> = _leaveAfterExternalEdit.receiveAsFlow()

    /** Editor reports unsaved changes; an incoming ACTION_EDIT then asks before replacing them. */
    private var editorDirty = false
    private val _pendingExternalEdit = MutableStateFlow<GalleryMedia?>(null)
    val pendingExternalEdit: StateFlow<GalleryMedia?> = _pendingExternalEdit

    fun setEditorDirty(dirty: Boolean) {
        editorDirty = dirty
    }

    fun confirmPendingExternalEdit() {
        val media = _pendingExternalEdit.value ?: return
        _pendingExternalEdit.value = null
        startExternalEdit(media, pendingReturnToCaller)
    }

    fun dismissPendingExternalEdit() {
        _pendingExternalEdit.value = null
    }

    private fun startExternalEdit(media: GalleryMedia, returnToCaller: Boolean) {
        editorDirty = false
        openEditor(media)
        if (_editingMedia.value == media) {
            _editCopyOnly.value = true
            leaveAfterEdit = returnToCaller
        }
    }
    private val _editingVideoList = MutableStateFlow<List<GalleryMedia>?>(null)
    private val _isExportingVideo = MutableStateFlow(false)
    private val _exportVideoProgress = MutableStateFlow(0)
    private val _isRefreshing = MutableStateFlow(false)
    private val _loadingFolderIds = MutableStateFlow<Set<Long>>(emptySet())
    /** Loads of each folder that show its spinner (see [loadFolderMediaFast]). */
    private val folderSpinnerLoads = HashMap<Long, Int>()
    /** Refreshes running; the indicator goes off with the last one (main thread). */
    private var refreshesRunning = 0

    private val mediaItems = repository.media.shareIn(
        viewModelScope,
        SharingStarted.Eagerly,
        replay = 1,
    )

    private data class MediaData(
        val media: List<GalleryMedia>,
        val settings: AppSettings,
        val trashed: List<TrashedItem>,
        val repoAlbums: List<Album>,
        val favAlbum: Album?,
        val favFiles: List<GalleryMedia>,
    )

    /**
     * The settings the folder list is built from. Every settings write (a saved scroll position on
     * each scroll stop, the last opened folder) used to rebuild all folders and check each one on
     * disk; now only these fields do.
     */
    private class AlbumSettings(val settings: AppSettings) {
        private val key = listOf(settings.sort, settings.folderSorts, settings.favoriteMediaIds, settings.hideFavoritesFolder)
        override fun equals(other: Any?) = other is AlbumSettings && other.key == key
        override fun hashCode() = key.hashCode()
    }

    /** The last folder data built, its unlocked folders and how many folders its library made. */
    @Volatile private var lastMediaData: MediaData? = null
    @Volatile private var lastUnlockedPaths: Any? = null
    @Volatile private var lastComputedFolders = 0

    private val albumDataFlow = combine(
        combine(mediaItems, store.settings.map { AlbumSettings(it) }.distinctUntilChanged(), trashManager.items) { media, albumSettings, trashed ->
            Triple(media, albumSettings.settings, trashed)
        },
        combine(repository.seedAlbums, SecureFolderManager.unlockedFolderPaths) { seedAlbums, unlockedPaths ->
            seedAlbums to unlockedPaths
        },
    ) { (media, settings, trashed), (seedAlbums, unlockedPaths) ->
        // A sync publishes the library and then the seed folders made of it: built again only
        // when the seed holds more folders than the library (a cold start on an older index).
        // Each sync built every folder twice (12-68 ms each).
        lastMediaData?.let { last ->
            if (last.media === media && last.settings === settings && last.trashed === trashed &&
                lastUnlockedPaths === unlockedPaths && seedAlbums.size <= lastComputedFolders
            ) {
                return@combine last
            }
        }
        val buildStartedAt = SystemClock.elapsedRealtime()
        val grouped = media.groupBy { it.bucketId }
        val computed = repository.albumsFrom(media).map { album ->
            val albumFiles = grouped[album.id].orEmpty()
            val cover = findCoverMedia(albumFiles, settings.sortFor(album.id, album.legacyBucketIds)) ?: album.cover
            album.copy(cover = cover)
        }
        val rawRepoAlbums = if (computed.isNotEmpty()) {
            if (seedAlbums.isNotEmpty() && seedAlbums.size > computed.size) {
                val computedMap = computed.associateBy { it.id }
                seedAlbums.map { seed -> computedMap[seed.id] ?: seed }
            } else {
                computed
            }
        } else seedAlbums

        val unlockedAlbums = unlockedPaths.mapNotNull { path ->
            val dir = File(path)
            // Listed again only when the folder changed (its date moves when a file comes or goes):
            // every library change (each finished download) listed it.
            UiStateMemo.get("unlockedAlbum" to path, dir.lastModified()) {
                if (!dir.exists() || !dir.isDirectory || !SecureFolderManager.isFolderSecure(dir)) return@get null
                val encFiles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(SecureFolderManager.ENC_EXTENSION) }.orEmpty()
                val bucketId = NomediaScanner.syntheticBucketId(dir.canonicalPath)
                Album(
                    id = bucketId,
                    name = dir.name,
                    cover = null,
                    itemCount = encFiles.size,
                    relativePath = dir.absolutePath,
                    isHidden = false,
                    isSecure = true,
                    latestAddedMs = encFiles.maxOfOrNull { it.lastModified() } ?: dir.lastModified(),
                )
            }
        }

        // Always hide MediaStore shells of AES folders — unlocked ones come from unlockedAlbums (synthetic id).
        // Checked on disk only when the folders or the private folder list change: each library
        // change (every finished download) checked every folder.
        val albumPathsKey = rawRepoAlbums.joinToString("\n") { it.relativePath }
        val securePathsKey = SecureFolderManager.getKnownSecurePaths().sorted().joinToString("\n")
        val secureAlbumPaths: Set<String> = UiStateMemo.get("secureAlbumPaths", albumPathsKey, securePathsKey) {
            rawRepoAlbums.filter { album ->
                val dir = File(album.relativePath).takeIf { it.isDirectory }
                    ?: NomediaScanner.directoryForRelativePath(album.relativePath)
                dir != null && SecureFolderManager.isFolderSecure(dir)
            }.mapTo(HashSet()) { it.relativePath }
        }
        val filteredRepo = if (secureAlbumPaths.isEmpty()) rawRepoAlbums else rawRepoAlbums.filterNot { it.relativePath in secureAlbumPaths }

        val repoAlbums = (filteredRepo + unlockedAlbums).distinctBy { it.id }

        val byIdMedia = media.associateBy { it.id }
        val favFiles = settings.favoriteMediaIds.mapNotNull { byIdMedia[it] }
        val favAlbum = if (favFiles.isNotEmpty()) {
            Album(
                id = AlbumIds.FAVORITES,
                name = getApplication<Application>().getString(R.string.favorites),
                cover = favFiles.firstOrNull(),
                itemCount = favFiles.size,
                relativePath = "",
                isHidden = settings.hideFavoritesFolder,
            )
        } else null

        MediaData(
            media = media,
            settings = settings,
            trashed = trashed,
            repoAlbums = repoAlbums,
            favAlbum = favAlbum,
            favFiles = favFiles,
        ).also {
            lastMediaData = it
            lastUnlockedPaths = unlockedPaths
            lastComputedFolders = computed.size
            AppLog.event("Library") {
                "folders built: ${media.size} file(s), ${repoAlbums.size} folder(s) in ${SystemClock.elapsedRealtime() - buildStartedAt} ms"
            }
        }
    }.flowOn(Dispatchers.Default)

    // The current settings on top, without rebuilding the folders.
    private val mediaDataFlow = combine(albumDataFlow, store.settings) { data, settings ->
        if (data.settings === settings) data else data.copy(settings = settings)
    }

    private data class ProcessedMediaData(
        val visibleMedia: List<GalleryMedia>,
        val albums: List<Album>,
        val allAlbums: List<Album>,
        val trashedItems: List<TrashedItem>,
        val settings: AppSettings,
        val hasHidden: Boolean,
        val validAlbumIds: Set<Long>,
    )

    private val processedMediaFlow = combine(
        mediaDataFlow,
        interaction.map { it.revealHidden }.distinctUntilChanged(),
        FolderDeleteQueue.jobs.map { it.keys }.distinctUntilChanged(),
        repository.otherFolders,
    ) { data, revealHidden, deletingIds, otherFolders ->
        val buildStartedAt = SystemClock.elapsedRealtime()
        val media = data.media
        val settings = data.settings
        val trashed = data.trashed
        val reveal = settings.showHidden || revealHidden
        // The very same lists while what they are made of stays: a settings write that does not
        // touch them (a scroll position, the last opened folder) made new ones, and every list
        // built from them (a folder's files) was then filtered and sorted again.
        // Files of a folder being deleted leave every list at once; its tile stays (progress).
        val visible = UiStateMemo.get("processedVisible", media, reveal, settings.hiddenMediaIds, deletingIds) {
            media.filter { item ->
                (reveal || (!item.isHidden && item.id !in settings.hiddenMediaIds)) && item.bucketId !in deletingIds
            }
        }
        val shouldIncludeFav = data.favFiles.isNotEmpty() && (!settings.hideFavoritesFolder || reveal)
        val (allAlbums, albums) = UiStateMemo.get(
            "processedAlbums",
            data.repoAlbums, data.favAlbum, otherFolders, settings.showOtherFileFolders, shouldIncludeFav,
            settings.albumOrder, reveal, settings.hiddenAlbumIds,
        ) {
            // "Show folders with other files": folders without photos get a tile too.
            val repoAlbums = if (settings.showOtherFileFolders && otherFolders.isNotEmpty()) {
                val known = data.repoAlbums.mapTo(HashSet()) { it.id }
                data.repoAlbums + otherFolders.filter { it.id !in known }
            } else {
                data.repoAlbums
            }
            val baseAlbums = (if (shouldIncludeFav && data.favAlbum != null) {
                listOf(data.favAlbum) + repoAlbums
            } else {
                repoAlbums
            }).distinctBy { it.id }
            val ordered = applyAlbumOrder(
                baseAlbums,
                settings.albumOrder,
            )
            ordered to ordered.filter { album ->
                reveal || (!album.isHidden && !settings.isAlbumHidden(album.id, album.legacyBucketIds))
            }
        }
        val validAlbumIds = UiStateMemo.get("processedValidAlbumIds", albums) {
            albums.map { it.id }.toSet() + setOf(AlbumIds.TRASH, AlbumIds.FAVORITES)
        }
        val hasHidden = UiStateMemo.get("processedAnyHidden", media) { media.any { it.isHidden } } ||
            settings.hiddenMediaIds.isNotEmpty() ||
            settings.hiddenAlbumIds.isNotEmpty() ||
            !StorageAccess.canReadAllFiles()

        ProcessedMediaData(
            visibleMedia = visible,
            albums = albums,
            allAlbums = allAlbums,
            trashedItems = trashed,
            settings = settings,
            hasHidden = hasHidden,
            validAlbumIds = validAlbumIds,
        ).also {
            AppLog.event("Library") {
                "library processed: ${visible.size} visible, ${albums.size} folder(s) in ${SystemClock.elapsedRealtime() - buildStartedAt} ms"
            }
        }
    }.flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Folders with a download in progress: a change when one starts or ends, not every download tick. */
    private val downloadingAlbumIds = combine(repository.activeDownloads, processedMediaFlow.map { it.albums }.distinctUntilChanged { a, b -> a === b }) { downloads, albums ->
        downloadingAlbumIdsOf(downloads, albums)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private val syncState = combine(
        combine(folderThumbEpoch, folderCoverBumps) { thumbEpoch, coverBumps -> thumbEpoch to coverBumps },
        combine(_editingMedia, _isSavingEdit, _editingVideoList) { editing, isSaving, videoList ->
            Triple(editing, isSaving, videoList)
        },
        combine(
            combine(_isExportingVideo, _exportVideoProgress, repository.isScanning) { exp, prog, scan -> Triple(exp, prog, scan) },
            combine(_isRefreshing, FolderDeleteQueue.jobs) { refreshing, deleting -> refreshing to deleting },
            combine(_loadingFolderIds, downloadingAlbumIds) { loading, downloading -> loading to downloading },
        ) { (exp, prog, scan), refreshingAndDeleting, loadingAndDownloads ->
            Triple(Triple(exp, prog, scan), refreshingAndDeleting, loadingAndDownloads)
        },
    ) { (thumbEpoch, coverBumps), (editing, isSaving, videoList), (expProgScan, refreshingAndDeleting, loadingAndDownloads) ->
        SyncState(
            thumbEpoch = thumbEpoch,
            coverBumps = coverBumps,
            editingMedia = editing,
            isSavingEdit = isSaving,
            editingVideoList = videoList,
            isExportingVideo = expProgScan.first,
            exportVideoProgress = expProgScan.second,
            isScanning = expProgScan.third,
            isRefreshing = refreshingAndDeleting.first,
            loadingFolderIds = loadingAndDownloads.first,
            downloadingAlbumIds = loadingAndDownloads.second,
            deletingFolders = refreshingAndDeleting.second,
        )
    }

    /** The library state and folder lists of the last logged screen state build (debug log only). */
    @Volatile private var loggedLibrary: Any? = null
    @Volatile private var loggedFolderMedia: Any? = null
    /** Screen state builds since the last logged one (taps, progress, the scanning indicator). */
    private val otherStateBuilds = java.util.concurrent.atomic.AtomicInteger(0)

    /** Debug log: how long the screen state took after a library or folder list change, and how many other builds came between. */
    private fun logScreenStateBuild(library: Any, folderMedia: Any, startedAt: Long) {
        if (!AppLog.enabled) return
        if (library === loggedLibrary && folderMedia === loggedFolderMedia) {
            otherStateBuilds.incrementAndGet()
            return
        }
        loggedLibrary = library
        loggedFolderMedia = folderMedia
        val others = otherStateBuilds.getAndSet(0)
        AppLog.event("Library") {
            "screen state built in ${SystemClock.elapsedRealtime() - startedAt} ms ($others other build(s) since the last change)"
        }
    }

    val uiState: StateFlow<GalleryUiState> = combine(
        processedMediaFlow,
        interaction,
        syncState,
        repository.folderMedia,
        repository.otherFiles,
    ) { processed, ix, sync, storeFolderMedia, otherFiles ->
        val buildStartedAt = SystemClock.elapsedRealtime()
        // "See all files": the folder's other files join its list (only folders that show them
        // have any, see the collector in init); its sort order places them among the photos.
        val folderMedia = if (otherFiles.isEmpty()) {
            storeFolderMedia
        } else {
            UiStateMemo.get("folderMediaWithOtherFiles", storeFolderMedia, otherFiles) {
                val merged = storeFolderMedia.toMutableMap()
                for ((albumId, files) in otherFiles) {
                    if (files.isEmpty()) continue
                    merged[albumId] = merged[albumId].orEmpty() + files
                }
                merged
            }
        }
        val visible = processed.visibleMedia
        val albums = processed.albums
        val sanitizedOpenFolders = remapAlbumIds(ix.openFolderIds, processed.allAlbums)
            .filter { it in processed.validAlbumIds }
        GalleryUiState(
            media = visible,
            albums = albums,
            allAlbums = processed.allAlbums,
            trashedItems = processed.trashedItems,
            settings = processed.settings,
            // Files hidden one by one are filtered from the folder caches too, not only from the
            // library list (an open folder kept showing them). isHidden rows stay: private-folder
            // and opened hidden-folder listings consist of them.
            folderMedia = if (processed.settings.showHidden || ix.revealHidden || processed.settings.hiddenMediaIds.isEmpty()) {
                folderMedia
            } else {
                val hiddenIds = processed.settings.hiddenMediaIds
                // Same map while its inputs stay: the per-folder lists keep their identity.
                UiStateMemo.get("visibleFolderMedia", folderMedia, hiddenIds) {
                    folderMedia.mapValues { (_, list) -> list.filter { it.id !in hiddenIds } }
                }
            },
            searchMode = ix.searchMode,
            query = ix.query,
            smartResultIds = ix.smartResultIds,
            smartSearch = ix.smartStatus,
            smartSearching = ix.smartSearching,
            smartResultsVersion = ix.smartResultsVersion,
            modeBeforeSmart = ix.modeBeforeSmart,
            searchOpen = ix.searchOpen,
            showSettings = ix.showSettings,
            phoneFolderId = ix.phoneFolderId,
            sessionFolderId = ix.sessionFolderId,
            openFolderIds = sanitizedOpenFolders,
            clickedFolderId = ix.clickedFolderId,
            pendingHitboxAlbumId = ix.pendingHitboxAlbumId,
            highlightedFolderId = ix.highlightedFolderId,
            selectedFileIds = ix.selectedFileIds,
            selectedFileIdsOrdered = ix.selectedFileIdsOrdered.filter { it in ix.selectedFileIds }.ifEmpty {
                // Backward-compatible: LinkedHashSet iteration order from taps.
                ix.selectedFileIds.toList()
            },
            selectedAlbumIds = ix.selectedAlbumIds,
            stampedAlbumIds = ix.stampedAlbumIds,
            reorderingFolders = ix.reorderingFolders,
            reorderDraftIds = ix.reorderDraftIds,
            contentOrderMode = ix.contentOrderMode,
            contentOrderDraftIds = ix.contentOrderDraftIds,
            contentOrderFolderId = ix.contentOrderFolderId,
            contentOrderRenameNumeric = ix.contentOrderRenameNumeric,
            dragKind = ix.dragKind,
            draggingFolderId = ix.draggingFolderId,
            filesDragSourceAlbumId = ix.filesDragSourceAlbumId,
            hoveredAlbumId = ix.hoveredAlbumId,
            hoveredSlot = ix.hoveredSlot,
            viewerMediaId = (ix.viewerMediaId?.let { id ->
                // Looked for once per change of the lists (not on every hover or download tick),
                // in the cheap places first: every folder list was searched before the library.
                val found = UiStateMemo.get("viewerLookup", id, ix.externalMedia, visible, processed.trashedItems, folderMedia) {
                    ix.externalMedia?.takeIf { it.id == id }
                        ?: visible.firstOrNull { it.id == id }
                        ?: processed.trashedItems.firstOrNull { it.id == id }?.toGalleryMedia()
                        ?: folderMedia.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } }
                }
                if (found != null) {
                    cacheViewerMedia(found)
                    id
                } else {
                    val prev = lastActiveViewerMedia
                    val allCandidates = if (folderMedia.isEmpty()) visible else (visible + folderMedia.values.flatten())
                    val remapped = if (prev != null) {
                        allCandidates.firstOrNull {
                            it.uri == prev.uri ||
                            (!it.storagePath.isNullOrBlank() && it.storagePath.equals(prev.storagePath, ignoreCase = true)) ||
                            (!it.absolutePath.isNullOrBlank() && it.absolutePath.equals(prev.absolutePath, ignoreCase = true)) ||
                            (it.displayName.equals(prev.displayName, ignoreCase = true) && it.sizeBytes == prev.sizeBytes && it.sizeBytes > 0L)
                        }
                    } else null
                    if (remapped != null) {
                        synchronized(viewerCacheLock) {
                            // Still the same photo open (not closed meanwhile): follow it to its new id.
                            if (interaction.value.viewerMediaId == id) {
                                lastActiveViewerMedia = remapped
                                interaction.update { it.copy(viewerMediaId = remapped.id) }
                            }
                        }
                        remapped.id
                    } else if (sync.isScanning || sync.isRefreshing) {
                        id
                    } else {
                        null
                    }
                }
            } ?: (visible.firstOrNull { it.uri == ix.pendingUri }
                ?: folderMedia.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.uri == ix.pendingUri } })
                ?.id?.also { pendingId ->
                    (visible.firstOrNull { it.id == pendingId }
                        ?: folderMedia.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == pendingId } })
                        ?.let { cacheViewerMedia(it) }
                }),
            cachedViewerMedia = lastActiveViewerMedia,
            externalMedia = ix.externalMedia,
            isLoading = (sync.isScanning || ix.loading) && (visible.isEmpty() || albums.isEmpty()),
            nextPlayerInstalled = NextPlayerClient.isInstalled(getApplication()),
            randomSeed = ix.randomSeed,
            revealHidden = ix.revealHidden,
            hasHidden = processed.hasHidden,
            folderThumbEpoch = sync.thumbEpoch,
            folderCoverBumps = sync.coverBumps,
            splitRatio = ix.splitRatio,
            paneStack = ix.paneStack,
            editingMedia = sync.editingMedia,
            isSavingEdit = sync.isSavingEdit,
            editingVideoList = sync.editingVideoList,
            isExportingVideo = sync.isExportingVideo,
            exportVideoProgress = sync.exportVideoProgress,
            isRefreshing = sync.isRefreshing,
            loadingFolderIds = sync.loadingFolderIds,
            downloadingAlbumIds = sync.downloadingAlbumIds,
            deletingFolders = sync.deletingFolders,
            trashName = getApplication<Application>().getString(R.string.trash),
            favoritesName = getApplication<Application>().getString(R.string.favorites),
        ).also { logScreenStateBuild(processed, storeFolderMedia, buildStartedAt) }
    }.flowOn(Dispatchers.Default)
    .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // Real settings from frame 0 — a default showHidden=false made the UI toggle hidden scanning off and on.
        GalleryUiState(
            settings = store.settings.value,
            revealHidden = interaction.value.revealHidden,
        ),
    )

    init {
        // Files unpacked from archives by an earlier run.
        viewModelScope.launch(Dispatchers.IO) { archiveCacheDir().deleteRecursively() }
        // "See all files": the shown folders that show other files have them listed from disk.
        viewModelScope.launch {
            var listed = emptySet<Long>()
            combine(
                interaction.map { ix -> setOf(ix.phoneFolderId, ix.sessionFolderId) + ix.openFolderIds }.distinctUntilChanged(),
                store.settings.map { Triple(it.showAllFiles, it.allFilesFolderIds, it.lastOpenedFolderId) }.distinctUntilChanged(),
                repository.otherFolders,
            ) { shown, (all, ids, last), otherFolders ->
                val otherOnly = otherFolders.mapTo(HashSet()) { it.id }
                val eligible = { id: Long ->
                    id != AlbumIds.NONE && id != AlbumIds.TRASH && id != AlbumIds.FAVORITES &&
                        (all || id in ids || id in otherOnly)
                }
                (shown + last).filterTo(HashSet(), eligible) to eligible
            }.collect { (wanted, eligible) ->
                // A folder left behind keeps its list (it would pop in and shift the tiles when
                // opened again); only one that no longer shows other files loses it.
                repository.retainOtherFiles(eligible)
                (listed - wanted).forEach { repository.pauseOtherFiles(it) }
                (wanted - listed).forEach { id -> launch { repository.loadOtherFiles(id, resolveAlbum(id)) } }
                listed = wanted
            }
        }
        // "Show folders with other files": the folder list is read when switched on and after
        // library changes (a few seconds later; it reads MediaStore's whole file table).
        viewModelScope.launch {
            store.settings.map { it.showOtherFileFolders }.distinctUntilChanged().collectLatest { on ->
                if (!on) return@collectLatest
                // Read now, then after changes of other files only: a new photo cannot change which
                // folders hold other files, and each read went through MediaStore's whole file table.
                merge(flowOf(Unit), repository.fileTableChanges).collectLatest {
                    delay(OTHER_FOLDERS_SCAN_DELAY_MS)
                    repository.scanOtherFolders()
                }
            }
        }
        // A private folder locked while on screen (or remembered as open across a restart) closes.
        viewModelScope.launch {
            SecureFolderManager.unlockedFolderPaths.collect { closeLockedSecureFolders() }
        }
        viewModelScope.launch {
            interaction
                .map { listOf(it.phoneFolderId, it.sessionFolderId, it.viewerMediaId ?: 0L) + it.openFolderIds }
                .distinctUntilChanged()
                .collect { closeLockedSecureFolders() }
        }
        // The video editor the same way; its project comes back from the screen's saved state.
        savedStateHandle.get<LongArray>(KEY_EDITING_VIDEO_IDS)?.let { ids ->
            viewModelScope.launch {
                val wanted = ids.toList()
                val found = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    repository.media.first { list -> list.any { it.id == wanted.first() } }
                }?.let { list -> list.associateBy { it.id } }
                val media = found?.let { byId -> wanted.mapNotNull { byId[it] } }.orEmpty()
                if (media.size == wanted.size && _editingVideoList.value == null) {
                    _editingVideoList.value = media
                } else {
                    savedStateHandle.remove<LongArray>(KEY_EDITING_VIDEO_IDS)
                }
            }
        }
        viewModelScope.launch {
            _editingVideoList.collect { list ->
                if (list != null && list.isNotEmpty() && list.all { it.id > 0L }) {
                    savedStateHandle[KEY_EDITING_VIDEO_IDS] = list.map { it.id }.toLongArray()
                } else {
                    savedStateHandle.remove<LongArray>(KEY_EDITING_VIDEO_IDS)
                }
            }
        }
        // The process was killed while a library photo was open in the editor: open it again
        // once the library is loaded (the unsaved edits themselves are gone).
        savedStateHandle.get<Long>(KEY_EDITING_MEDIA_ID)?.let { id ->
            viewModelScope.launch {
                val media = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    repository.media.first { list -> list.any { it.id == id } }.first { it.id == id }
                }
                if (media != null && _editingMedia.value == null) openEditor(media)
                else savedStateHandle.remove<Long>(KEY_EDITING_MEDIA_ID)
            }
        }
        viewModelScope.launch {
            combine(_editingMedia, _editCopyOnly) { media, copyOnly -> media to copyOnly }.collect { (media, copyOnly) ->
                // External files (synthetic ids) and copy-only edits can't be opened the same way
                // again after a restart.
                if (media != null && media.id > 0L && !copyOnly) {
                    savedStateHandle[KEY_EDITING_MEDIA_ID] = media.id
                } else {
                    savedStateHandle.remove<Long>(KEY_EDITING_MEDIA_ID)
                }
            }
        }
        viewModelScope.launch {
            var previous = interaction.value
            interaction.collect { ix ->
                if (AppLog.enabled && ix !== previous) {
                    // The search text can be a private-folder password: never in the log.
                    val changes = AppLog.describeChanges(previous, ix, redacted = setOf("query"))
                    if (changes.isNotEmpty()) AppLog.event("UiState") { changes.joinToString("; ") }
                }
                previous = ix
                savedStateHandle[KEY_PHONE_FOLDER_ID] = ix.phoneFolderId
                if (ix.viewerMediaId != null) {
                    savedStateHandle[KEY_VIEWER_MEDIA_ID] = ix.viewerMediaId
                } else {
                    savedStateHandle.remove<Long>(KEY_VIEWER_MEDIA_ID)
                }
            }
        }
        val initialFolderId = interaction.value.phoneFolderId.takeIf { it != AlbumIds.NONE }
            ?: interaction.value.sessionFolderId.takeIf { it != AlbumIds.NONE }
            ?: when (store.settings.value.contentViewMode) {
                ContentViewMode.LAST_FOLDER -> store.settings.value.lastOpenedFolderId.takeIf { it != AlbumIds.NONE }
                else -> null
            }
        if (initialFolderId != null && initialFolderId != AlbumIds.NONE) {
            loadFolderMediaFast(initialFolderId)
        }
        repository.setScanHidden(store.settings.value.showHidden)
        FolderDeleteQueue.restore(application)
        // Two calls to the system's job service: kept off the main thread while the screen starts.
        viewModelScope.launch(Dispatchers.IO) {
            dev.ander.alexet.integration.FullScanJobService.schedule(application)
        }
        viewModelScope.launch {
            var lastIndexed = -1
            combine(smartSearch.status, store.settings.map { it.smartSearchEnabled }.distinctUntilChanged()) { status, enabled -> status to enabled }
                .collect { (status, enabled) ->
                    interaction.update { ix ->
                        // Switched off, or the model removed: back to the search it replaced.
                        val mode = if (ix.searchMode == SearchMode.SMART && !(enabled && status.ready)) ix.modeBeforeSmart else ix.searchMode
                        // Only when what the screen uses from it changed (ready, the model): the
                        // indexing progress is read from smartStatus where it is shown.
                        val same = ix.smartStatus.ready == status.ready && ix.smartStatus.model == status.model
                        if (same && mode == ix.searchMode) ix else ix.copy(smartStatus = status, searchMode = mode)
                    }
                    // Photos indexed meanwhile join the results of the typed description.
                    if (status.indexed - lastIndexed >= SMART_RESEARCH_STEP || (!status.indexing && status.indexed != lastIndexed)) {
                        lastIndexed = status.indexed
                        runSmartSearch()
                    }
                }
        }
        viewModelScope.launch {
            FolderDeleteQueue.results.collect { result ->
                val app = getApplication<Application>()
                var text = app.getString(
                    when {
                        result.cancelled -> R.string.folder_delete_cancelled
                        result.toTrash -> R.string.folder_trash_done
                        else -> R.string.folder_delete_done
                    },
                    result.name,
                )
                if (result.failed > 0) text += " · " + app.getString(R.string.folder_delete_result_failed, result.failed)
                AppLog.event("Toast") { text }
                Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
            }
        }
        // Config imported from another phone: attach its hidden / favorite / ordered files once they appear.
        viewModelScope.launch(Dispatchers.Default) {
            repository.media.collect { list ->
                if (list.isEmpty() || store.settings.value.pendingMedia.isEmpty) return@collect
                val index = dev.ander.alexet.data.MediaRefs.index(list)
                if (dev.ander.alexet.data.MediaRefs.resolvePending(store.settings.value) { index[it] } == null) return@collect
                store.update { current -> dev.ander.alexet.data.MediaRefs.resolvePending(current) { index[it] } ?: current }
            }
        }
        if (store.settings.value.rememberFolderScrollAcrossSessions) {
            val list = store.settings.value.savedFolderScrolls.entries.toList()
            list.takeLast(3).forEach { entry ->
                folderScrollLru[entry.key] = entry.value
            }
        }
        viewModelScope.launch {
            // A private folder no pane shows any more is locked, whatever navigation removed it
            // (phone layout, split panes, session switch).
            var shownBefore = emptySet<Long>()
            interaction.map { ix -> setOf(ix.phoneFolderId, ix.sessionFolderId) + ix.openFolderIds }.distinctUntilChanged().collect { shown ->
                (shownBefore - shown).forEach {
                    lockSecureFolderIfLeaving(it)
                    quickScanJobs.remove(it)?.cancel()
                }
                (shown - shownBefore).forEach { scheduleQuickScan(it) }
                shownBefore = shown
            }
        }
        viewModelScope.launch {
            // Also on the scan ending: an unchanged list (empty library) does not emit again.
            combine(mediaItems, repository.isScanning) { _, scanning -> scanning }.collect { scanning ->
                if (!scanning) {
                    interaction.update { it.copy(loading = false) }
                }
            }
        }
        viewModelScope.launch {
            // Once, when the folders are first known; the collection then ends (it kept the whole
            // list pipeline running for the screen's lifetime for nothing).
            processedMediaFlow.first { it.allAlbums.isNotEmpty() }.let { processed ->
                if (!folderIdsRemapped) {
                    folderIdsRemapped = true
                    val albums = processed.allAlbums
                    remapStoredFolderIds(albums)
                }
            }
        }
        viewModelScope.launch {
            store.settings.collect { settings ->
                interaction.update { ix ->
                    if (ix.multiHydrated) {
                        ix
                    } else if (settings.contentViewMode == ContentViewMode.MULTI_FOLDER &&
                        settings.multiFolderMemory == MultiFolderMemory.REMEMBER
                    ) {
                        ix.copy(openFolderIds = settings.rememberedOpenFolders, multiHydrated = true)
                    } else {
                        ix.copy(multiHydrated = true)
                    }
                }
            }
        }
    }

    fun setSearchOpen(open: Boolean) = interaction.update { it.copy(searchOpen = open) }

    fun setQuery(value: String) {
        interaction.update { it.copy(query = value) }
        runSmartSearch()
    }

    /** Folders -> files -> description (when the smart search can be used) -> folders. */
    /** Folders <-> files (AI search has its own button, [toggleSmartSearchMode]). */
    fun toggleSearchMode() {
        interaction.update {
            // From AI search the switch goes on from the folders / files mode it replaced.
            val base = if (it.searchMode == SearchMode.SMART) it.modeBeforeSmart else it.searchMode
            it.copy(searchMode = if (base == SearchMode.FOLDERS) SearchMode.FILES else SearchMode.FOLDERS)
        }
        runSmartSearch()
    }

    /** The ✨ button of the search field: AI search on, or back to the mode it replaced. */
    fun toggleSmartSearchMode() {
        val available = store.settings.value.smartSearchEnabled && interaction.value.smartStatus.ready
        interaction.update {
            when {
                it.searchMode == SearchMode.SMART -> it.copy(searchMode = it.modeBeforeSmart)
                available -> it.copy(searchMode = SearchMode.SMART, modeBeforeSmart = it.searchMode)
                else -> it
            }
        }
        runSmartSearch()
    }

    private fun runSmartSearch() {
        val ix = interaction.value
        if (ix.searchMode != SearchMode.SMART || ix.query.isBlank()) {
            smartSearchJob?.cancel()
            interaction.update {
                it.copy(
                    smartSearching = false,
                    smartResultIds = if (it.query.isBlank()) emptyList() else it.smartResultIds,
                )
            }
            return
        }
        val query = ix.query
        smartSearchJob?.cancel()
        interaction.update { it.copy(smartSearching = true) }
        smartSearchJob = viewModelScope.launch {
            val hits = runCatching { smartSearch.search(query) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) AppLog.e("GalleryViewModel", "Smart search failed", it) }
                .getOrDefault(emptyList())
            interaction.update {
                if (it.query != query) return@update it
                val ids = hits.map { h -> h.id }
                // The glow greets new results, not the same list found again while indexing.
                val fresh = ids.isNotEmpty() && ids != it.smartResultIds
                it.copy(
                    smartResultIds = ids,
                    smartSearching = false,
                    smartResultsVersion = if (fresh) it.smartResultsVersion + 1 else it.smartResultsVersion,
                )
            }
        }
    }

    fun setAiEffects(transform: (dev.ander.alexet.data.AiEffectsConfig) -> dev.ander.alexet.data.AiEffectsConfig) {
        store.update { it.copy(aiEffects = transform(it.aiEffects)) }
    }

    fun setSmartIndexScope(scope: dev.ander.alexet.data.SmartIndexScope) {
        AppLog.event("Action") { "setSmartIndexScope $scope" }
        store.update { it.copy(smartIndexScope = scope) }
    }

    /** The folder list of the current scope: the included folders, or the excluded ones. */
    fun changeSmartIndexFolders(added: Set<Long>, removed: Set<Long>) {
        store.update {
            when (it.smartIndexScope) {
                dev.ander.alexet.data.SmartIndexScope.ALL_EXCEPT ->
                    it.copy(smartIndexExcludedFolderIds = it.smartIndexExcludedFolderIds - removed + added)
                else -> it.copy(smartIndexIncludedFolderIds = it.smartIndexIncludedFolderIds - removed + added)
            }
        }
    }

    fun setSmartIndexPaused(paused: Boolean) {
        AppLog.event("Action") { "setSmartIndexPaused $paused" }
        store.update { it.copy(smartIndexPaused = paused) }
    }

    fun setSmartSearchEnabled(enabled: Boolean) {
        AppLog.event("Action") { "setSmartSearchEnabled $enabled" }
        store.update { it.copy(smartSearchEnabled = enabled) }
    }

    fun importSmartModel(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        AppLog.event("Action") { "importSmartModel" }
        viewModelScope.launch {
            val result = smartSearch.installPack {
                appContext.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the file")
            }
            result.onSuccess {
                store.update { s -> s.copy(smartSearchEnabled = true) }
                toast(R.string.smart_model_imported)
            }.onFailure { e ->
                val app = getApplication<Application>()
                Toast.makeText(app, app.getString(R.string.smart_model_import_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun removeSmartModel() {
        AppLog.event("Action") { "removeSmartModel" }
        viewModelScope.launch { smartSearch.removeModel() }
    }

    fun clearSmartIndex() {
        AppLog.event("Action") { "clearSmartIndex" }
        viewModelScope.launch { smartSearch.clearIndex() }
    }

    suspend fun smartLicenseText(): String? = withContext(Dispatchers.IO) { smartSearch.licenseText() }

    fun openSettings() = interaction.update { it.copy(showSettings = true) }

    fun closeSettings() = interaction.update { it.copy(showSettings = false) }

    /** Saved folder ids (last folder, open panes, hidden, sorts, scrolls) mapped to [albums]' ids. */
    private fun remapStoredFolderIds(albums: List<Album>) {
        // Remapped inside update {}: a change saved meanwhile (a scroll position, a
        // sort) is remapped too instead of being overwritten by values read before it.
        store.update { current ->
            val newLast = remapAlbumId(current.lastOpenedFolderId, albums)
            val newRemembered = remapAlbumIds(current.rememberedOpenFolders, albums)
            val newHidden = remapHiddenAlbumIds(current.hiddenAlbumIds, albums)
            val newSorts = remapFolderSorts(current.folderSorts, albums)
            val newScrolls = remapFolderScrolls(current.savedFolderScrolls, albums)
            if (newLast != current.lastOpenedFolderId ||
                newRemembered != current.rememberedOpenFolders ||
                newHidden != current.hiddenAlbumIds ||
                newSorts != current.folderSorts ||
                newScrolls != current.savedFolderScrolls
            ) {
                current.copy(
                    lastOpenedFolderId = newLast,
                    rememberedOpenFolders = newRemembered,
                    hiddenAlbumIds = newHidden,
                    folderSorts = newSorts,
                    savedFolderScrolls = newScrolls,
                )
            } else {
                current
            }
        }
        synchronized(folderScrollLru) {
            val snapshot = LinkedHashMap(folderScrollLru)
            folderScrollLru.clear()
            snapshot.forEach { (id, pos) ->
                folderScrollLru[remapAlbumId(id, albums)] = pos
            }
        }
        interaction.update { ix ->
            ix.copy(openFolderIds = remapAlbumIds(ix.openFolderIds, albums))
        }
    }

    fun setContentViewMode(mode: ContentViewMode) {
        store.update { it.copy(contentViewMode = mode) }
        applyContentViewModeToPanes(mode)
    }

    /** The panes as [mode] starts them (also after an import that changed it). */
    private fun applyContentViewModeToPanes(mode: ContentViewMode) {
        interaction.update { ix ->
            when (mode) {
                ContentViewMode.MULTI_FOLDER -> {
                    val settings = store.settings.value
                    ix.copy(
                        openFolderIds = if (settings.multiFolderMemory == MultiFolderMemory.REMEMBER) {
                            settings.rememberedOpenFolders
                        } else {
                            emptyList()
                        },
                        clickedFolderId = AlbumIds.NONE,
                        pendingHitboxAlbumId = AlbumIds.NONE,
                        highlightedFolderId = AlbumIds.NONE,
                    )
                }
                ContentViewMode.CLICK_DRAG -> ix.copy(
                    openFolderIds = emptyList(),
                    clickedFolderId = AlbumIds.NONE,
                    pendingHitboxAlbumId = AlbumIds.NONE,
                    highlightedFolderId = AlbumIds.NONE,
                    splitRatio = 0.5f,
                    paneStack = PaneStack.NONE,
                )
                else -> ix.copy(
                    pendingHitboxAlbumId = AlbumIds.NONE,
                    highlightedFolderId = AlbumIds.NONE,
                )
            }
        }
    }

    fun setPanelSplitPercent(percent: Int) {
        store.update { it.copy(panelSplitPercent = percent.coerceIn(10, 90)) }
    }

    fun setMultiFolderMemory(memory: MultiFolderMemory) {
        store.update { it.copy(multiFolderMemory = memory) }
        if (memory == MultiFolderMemory.FRESH) {
            interaction.update { it.copy(openFolderIds = emptyList()) }
            store.setRememberedOpenFolders(emptyList())
        }
    }

    /** Null keeps that value as it is when the change is written (two quick taps don't undo each other). */
    fun setExpandGrid(columns: Int?, rows: Int?) {
        store.update {
            it.copy(
                expandColumns = columns?.coerceIn(2, 6) ?: it.expandColumns,
                expandRows = rows?.coerceIn(2, 6) ?: it.expandRows,
            )
        }
    }

    fun setGridColumns(columns: Int) {
        store.setGridColumns(columns)
    }

    fun setFileColumns(columns: Int) {
        store.setFileColumns(columns)
    }

    fun setFoldableFileColumns(columns: Int) {
        store.setFoldableFileColumns(columns)
    }

    fun setShowFileNames(value: Boolean) {
        store.update { it.copy(showFileNames = value) }
    }

    fun setOperationsFollowSelectionOrder(value: Boolean) {
        store.setOperationsFollowSelectionOrder(value)
    }

    fun setVideoDurationFormat(format: VideoDurationFormat) {
        store.setVideoDurationFormat(format)
    }

    fun setFileSelectMode(mode: FileSelectMode) {
        store.update { it.copy(fileSelectMode = mode) }
    }

    fun setSearchFilesFullScreen(value: Boolean) {
        store.update { it.copy(searchFilesFullScreen = value) }
    }

    fun setVideoDoubleClickToGallery(value: Boolean) {
        store.update { it.copy(videoDoubleClickToGallery = value) }
    }

    fun setRememberFolderScrollAcrossSessions(value: Boolean) {
        store.update {
            it.copy(
                rememberFolderScrollAcrossSessions = value,
                savedFolderScrolls = if (value) synchronized(folderScrollLru) { HashMap(folderScrollLru) } else emptyMap(),
            )
        }
    }

    fun setNewContentScrollsToTop(value: Boolean) {
        store.update { it.copy(newContentScrollsToTop = value) }
    }

    fun setAutoplayGifs(value: Boolean) {
        store.update { it.copy(autoplayGifs = value) }
    }

    fun updatePlayerConfig(transform: (dev.ander.alexet.integration.AnderLineConfig) -> dev.ander.alexet.integration.AnderLineConfig) {
        store.update { it.copy(playerConfig = transform(it.playerConfig)) }
    }

    fun updateHiddenCover(transform: (dev.ander.alexet.data.HiddenCoverConfig) -> dev.ander.alexet.data.HiddenCoverConfig) {
        store.update { it.copy(hiddenCover = transform(it.hiddenCover)) }
    }

    /**
     * The picture for hidden folders' covers, copied into the app (at most 1024 px): it stays when
     * the original is moved or deleted, and no permission to the original has to be kept.
     */
    fun setHiddenCoverImage(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val file = java.io.File(appContext.filesDir, "hidden_cover.jpg")
            val ok = runCatching {
                fun sampleFor(longSide: Int): Int {
                    var sample = 1
                    while (longSide / (sample * 2) >= 1024) sample *= 2
                    return sample
                }
                val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    // Turned as the photo's EXIF says.
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(appContext.contentResolver, uri)) { decoder, info, _ ->
                        decoder.setTargetSampleSize(sampleFor(maxOf(info.size.width, info.size.height)))
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    appContext.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                    appContext.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleFor(maxOf(bounds.outWidth, bounds.outHeight)) })
                    } ?: return@runCatching false
                }
                java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
                true
            }.getOrDefault(false)
            if (ok) {
                store.update {
                    it.copy(
                        hiddenCover = it.hiddenCover.copy(
                            style = dev.ander.alexet.data.HiddenCoverStyle.IMAGE,
                            imagePath = file.absolutePath,
                            imageVersion = System.currentTimeMillis(),
                        ),
                    )
                }
            } else {
                withContext(Dispatchers.Main) { toast(R.string.hidden_cover_image_failed) }
            }
        }
    }

    fun setNewpipeFolder(relativePath: String) {
        store.setNewpipeFolder(relativePath)
    }

    fun setShowNavArrows(value: Boolean) {
        store.setShowNavArrows(value)
    }

    fun toggleAnonymousFolder(folderId: Long) {
        store.toggleAnonymousFolder(folderId)
    }

    fun changeAnonymousFolders(added: Set<Long>, removed: Set<Long>) {
        store.changeAnonymousFolders(added, removed)
    }

    fun setStripExifOnShare(value: Boolean) {
        store.setStripExifOnShare(value)
    }

    fun setRandomizeFileNamesInPrivateFolders(value: Boolean) {
        store.setRandomizeFileNamesInPrivateFolders(value)
    }

    fun setStripExifInPrivateFolders(value: Boolean) {
        store.setStripExifInPrivateFolders(value)
    }

    fun setScrollerOnRight(value: Boolean) {
        store.setScrollerOnRight(value)
    }

    fun setScrollerEnabled(value: Boolean) {
        store.setScrollerEnabled(value)
    }

    fun setScrollerSmooth(value: Boolean) {
        store.setScrollerSmooth(value)
    }

    fun setHighResThumbnails(value: Boolean) {
        store.setHighResThumbnails(value)
    }

    fun setLightMode(value: Boolean) {
        store.setLightMode(value)
    }

    /** True until the folders were shown once; only then the first-run speed check runs. */
    fun needsFirstOpenCheck(): Boolean = store.settings.value.firstTimeOpened == 0

    private val _offerLightMode = MutableStateFlow(false)
    /** The first-run speed check found the UI slow: ask once whether to turn light mode on. */
    val offerLightMode: StateFlow<Boolean> = _offerLightMode

    /** Folders are on screen for the first time: the check is marked done, so the question comes only once. */
    fun onFoldersFirstShown(slow: Boolean) {
        if (!needsFirstOpenCheck()) return
        store.markFirstTimeOpened()
        AppLog.event("FrameMonitor") { "first open done, ${if (slow) "slow UI: light mode offered" else "speed fine"}" }
        if (slow && !store.settings.value.lightMode) _offerLightMode.value = true
    }

    fun answerLightModeOffer(enable: Boolean) {
        _offerLightMode.value = false
        if (enable) store.setLightMode(true)
    }

    fun setFolderAsPrivate(album: Album, password: String, hint: String?, backupPassword: String?) {
        viewModelScope.launch {
            val dir = File(album.relativePath).takeIf { it.isDirectory }
                ?: NomediaScanner.directoryForRelativePath(album.relativePath)
            if (dir == null || !dir.isDirectory) {
                toast(R.string.cannot_move_to_secure_folder)
                return@launch
            }
            if (album.isSecure || SecureFolderManager.isFolderSecure(dir)) {
                // Encrypting again would replace the key its files need.
                toast(R.string.folder_already_private)
                return@launch
            }
            val oldId = album.id
            val success = SecureFolderManager.encryptFolder(dir, password, hint, backupPassword)
            if (success) {
                // Refresh MediaStore first (removes the plaintext rows). The new private folder is
                // locked and closed right away: it opens only with its password, like any other.
                repository.refresh()
                SecureFolderManager.lockFolder(dir)
                // Its plain cover (decoded while the folder was public) must not stay in memory.
                dev.ander.alexet.data.FolderCoverCache.clear()
                interaction.update { ix ->
                    ix.copy(
                        phoneFolderId = if (ix.phoneFolderId == oldId) AlbumIds.NONE else ix.phoneFolderId,
                        sessionFolderId = if (ix.sessionFolderId == oldId) AlbumIds.NONE else ix.sessionFolderId,
                        openFolderIds = ix.openFolderIds.filterNot { it == oldId },
                        clickedFolderId = if (ix.clickedFolderId == oldId) AlbumIds.NONE else ix.clickedFolderId,
                    )
                }
                if (store.settings.value.lastOpenedFolderId == oldId) store.setLastOpenedFolder(AlbumIds.NONE)
                clearSelection()
                toast(R.string.secure_folder_encrypted_success)
            } else {
                toast(R.string.secure_folder_encrypt_error)
            }
        }
    }

    /** A password check is running (main thread only). */
    private var unlockCheckRunning = false
    /** The newest password typed while a check ran: checked next, the ones in between never. */
    private var pendingUnlockQuery: Pair<String, FragmentActivity>? = null

    fun tryUnlockSecureFolderWithPassword(query: String, activity: FragmentActivity?) {
        if (activity == null || query.isBlank()) return
        // One check at a time: each ran PBKDF2 for every private folder, and several submits
        // ran them side by side.
        if (unlockCheckRunning) {
            pendingUnlockQuery = query to activity
            return
        }
        unlockCheckRunning = true
        viewModelScope.launch {
            try {
                var next: Pair<String, FragmentActivity>? = query to activity
                while (next != null) {
                    val (text, act) = next
                    pendingUnlockQuery = null
                    // PBKDF2 per private folder: far too slow for the main thread.
                    val result = withContext(Dispatchers.Default) { SecureFolderManager.verifyQueryAgainstKnownFolders(text) }
                    // Something newer was typed meanwhile: that one counts.
                    next = pendingUnlockQuery
                    if (next != null) continue
                    val match = result.unlocked.firstOrNull()
                    if (match == null) {
                        if (result.legacyBackupOnly.isNotEmpty()) toast(R.string.secure_folder_backup_legacy)
                    } else {
                        authenticateAndOpenSecureFolder(match.first, match.second, act)
                    }
                }
            } finally {
                unlockCheckRunning = false
                pendingUnlockQuery = null
            }
        }
    }

    private fun authenticateAndOpenSecureFolder(dir: File, key: javax.crypto.SecretKey, activity: FragmentActivity) {
        SecureFolderManager.authenticateWithBiometrics(
            activity = activity,
            title = activity.getString(R.string.secure_folder_auth_title),
            subtitle = activity.getString(R.string.secure_folder_auth_subtitle),
            onSuccess = {
                SecureFolderManager.unlockFolder(dir, key)
                setQuery("")
                toast(R.string.secure_folder_unlocked)
                viewModelScope.launch {
                    // Listing the folder (and a date per file) took seconds on the main thread
                    // for a folder with thousands of files.
                    val (bucketId, album) = withContext(Dispatchers.IO) {
                        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
                        val id = NomediaScanner.syntheticBucketId(canonical)
                        val encFiles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(SecureFolderManager.ENC_EXTENSION) }.orEmpty()
                        id to Album(
                            id = id,
                            name = dir.name,
                            cover = null,
                            itemCount = encFiles.size,
                            relativePath = dir.absolutePath,
                            isHidden = false,
                            isSecure = true,
                            latestAddedMs = encFiles.maxOfOrNull { it.lastModified() } ?: dir.lastModified(),
                        )
                    }
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.loadFolderMedia(bucketId, album)
                        dev.ander.alexet.data.SecureRamCache.preloadFolder(dir, key)
                        // Old-format files open slowly: rewritten once in the chunked format.
                        SecureFolderManager.upgradeLegacyFiles(dir, key)
                    }
                    openFolder(bucketId, fromPhone = true)
                    refreshCurrentContext()
                }
            },
            onError = { err ->
                Toast.makeText(getApplication(), err, Toast.LENGTH_SHORT).show()
            },
        )
    }

    fun mixFileNamesInFolder(folderId: Long, launchWrite: ((android.content.IntentSender) -> Unit)? = null) {
        val items = uiState.value.visibleFilesIn(folderId).ifEmpty { uiState.value.filesIn(folderId) }
        if (items.isEmpty()) return
        if (blockIfPrivate(items)) return

        val itemsWithNewNames = items.map { item ->
            item to ExifUtils.generateAnonymousFileName(item.displayName)
        }

        viewModelScope.launch(fileOperationErrors(R.string.rename_failed)) {
            val (successCount, failed) = repository.renameAll(itemsWithNewNames)
            if (failed.isNotEmpty()) {
                val sender = repository.createWriteRequest(failed)
                if (sender != null && launchWrite != null) {
                    pendingAfterWrite = {
                        viewModelScope.launch(fileOperationErrors(R.string.rename_failed)) {
                            // The files as they are now (the consent dialog can take a while, and
                            // something may have moved or renamed one meanwhile); gone ones are skipped.
                            val current = repository.media.value.associateBy { it.id }
                            val remaining = failed.mapNotNull { old -> current[old.id] ?: old.takeIf { it.absolutePath?.let { p -> File(p).exists() } == true } }
                                .map { item -> item to ExifUtils.generateAnonymousFileName(item.displayName) }
                            repository.renameAll(remaining)
                            loadFolderMediaFast(folderId)
                            repository.refresh()
                        }
                    }
                    launchWrite(sender)
                    return@launch
                } else if (successCount == 0) {
                    toast(R.string.rename_failed)
                }
            }
            if (successCount > 0) {
                toast(R.string.mix_names_success)
            }
            loadFolderMediaFast(folderId)
            repository.refresh()
        }
    }

    fun decryptFolderPermanently(album: Album, activity: FragmentActivity?) {
        AppLog.event("Action") { "decryptFolderPermanently album=${album.id} ${album.name}" }
        val dir = File(album.relativePath).takeIf { it.isDirectory }
            ?: NomediaScanner.directoryForRelativePath(album.relativePath)
        if (dir == null) return
        val key = SecureFolderManager.getActiveKey(dir)
        if (key == null) {
            toast(R.string.secure_folder_no_key)
            return
        }
        if (activity == null) return
        SecureFolderManager.authenticateWithBiometrics(
            activity = activity,
            title = activity.getString(R.string.secure_folder_auth_title),
            subtitle = activity.getString(R.string.secure_folder_auth_subtitle),
            onSuccess = {
                viewModelScope.launch {
                    val success = SecureFolderManager.decryptFolderPermanently(dir, key)
                    if (success) {
                        toast(R.string.secure_folder_decrypted_success)
                        withContext(Dispatchers.IO) { NomediaScanner(getApplication()).rescanTree(dir) }
                        // Close only this folder (it gets a new id as a plain folder), not the
                        // other pane's folder.
                        val oldId = album.id
                        interaction.update { ix ->
                            ix.copy(
                                phoneFolderId = if (ix.phoneFolderId == oldId) AlbumIds.NONE else ix.phoneFolderId,
                                sessionFolderId = if (ix.sessionFolderId == oldId) AlbumIds.NONE else ix.sessionFolderId,
                                openFolderIds = ix.openFolderIds.filterNot { it == oldId },
                                clickedFolderId = if (ix.clickedFolderId == oldId) AlbumIds.NONE else ix.clickedFolderId,
                            )
                        }
                        persistOpenFolders(interaction.value.openFolderIds)
                        if (store.settings.value.lastOpenedFolderId == oldId) store.setLastOpenedFolder(AlbumIds.NONE)
                        refreshCurrentContext()
                    } else {
                        toast(R.string.secure_folder_decrypt_error)
                    }
                }
            },
            onError = { err ->
                Toast.makeText(getApplication(), err, Toast.LENGTH_SHORT).show()
            },
        )
    }

    fun setCustomWallpaperResolutions(foldedW: Int, foldedH: Int, unfoldedW: Int, unfoldedH: Int) {
        store.setCustomWallpaperResolutions(foldedW, foldedH, unfoldedW, unfoldedH)
    }

    fun setAutoFoldSplitEnabled(enabled: Boolean) {
        store.setAutoFoldSplitEnabled(enabled)
    }

    fun setFoldSplitAngleThreshold(threshold: Float) {
        store.setFoldSplitAngleThreshold(threshold)
    }

    fun setShowFoldableSettings(show: Boolean) {
        store.setShowFoldableSettings(show)
    }

    fun markFoldableDetected() {
        store.markFoldableDetected()
    }

    fun setFoldSplitAngleThreshold(threshold: Int) {
        store.setFoldSplitAngleThreshold(threshold.toFloat())
    }

    fun recordScreenResolution(isWide: Boolean, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.update(isWide, width, height)
        val current = store.settings.value
        if (isWide) {
            if (current.lastDetectedUnfoldedWidth != width || current.lastDetectedUnfoldedHeight != height) {
                store.setDetectedWallpaperResolutions(unfoldedW = width, unfoldedH = height)
            }
        } else {
            if (current.lastDetectedFoldedWidth != width || current.lastDetectedFoldedHeight != height) {
                store.setDetectedWallpaperResolutions(foldedW = width, foldedH = height)
            }
        }
    }


    private fun albumLegacyIds(albumId: Long): Set<Long> =
        uiState.value.allAlbums.firstOrNull { it.id == albumId }?.legacyBucketIds ?: emptySet()

    fun getFolderScroll(albumId: Long): FolderScrollPos? {
        if (albumId == AlbumIds.NONE) return null
        if (!store.settings.value.rememberFolderScrollAcrossSessions) return null
        val legacy = albumLegacyIds(albumId)
        val fromLru = synchronized(folderScrollLru) {
            folderScrollLru[albumId] ?: legacy.firstNotNullOfOrNull { folderScrollLru[it] }
        }
        if (fromLru != null) return fromLru
        return store.settings.value.scrollFor(albumId, legacy)
    }

    fun saveFolderScroll(albumId: Long, index: Int, offset: Int) {
        if (albumId == AlbumIds.NONE) return
        firstVisibleIndex[albumId] = index
        if (!store.settings.value.rememberFolderScrollAcrossSessions) return
        synchronized(folderScrollLru) {
            if (index == 0 && offset == 0 && !folderScrollLru.containsKey(albumId)) return
            folderScrollLru.remove(albumId)
            folderScrollLru[albumId] = FolderScrollPos(index, offset)
            while (folderScrollLru.size > 3) {
                val firstKey = folderScrollLru.keys.firstOrNull() ?: break
                folderScrollLru.remove(firstKey)
            }
        }
        // Not written to the settings here: every pause in scrolling gave the settings a new
        // value, which re-filtered the whole library and rebuilt the grid. The positions live in
        // [folderScrollLru] and reach the settings in [persistFolderScrolls].
    }

    /** Writes the remembered scroll positions to the settings; called when the app goes to the background. */
    fun persistFolderScrolls() {
        val latest = synchronized(folderScrollLru) { HashMap(folderScrollLru) }
        // Turned off in the meantime: the cleared list must stay cleared.
        store.update {
            if (it.rememberFolderScrollAcrossSessions && it.savedFolderScrolls != latest) it.copy(savedFolderScrolls = latest) else it
        }
    }

    fun loadFolderMediaFast(id: Long) {
        if (id == AlbumIds.NONE || id == AlbumIds.TRASH || id == AlbumIds.FAVORITES) return
        val album = uiState.value.allAlbums.firstOrNull { it.id == id }
            ?: repository.seedAlbums.value.firstOrNull { it.id == id }
        val currentHasItems = uiState.value.folderMedia[id]?.isNotEmpty() == true ||
            uiState.value.media.any {
                it.bucketId == id || (album != null && it.rawBucketId != 0L && it.rawBucketId in album.legacyBucketIds)
            }
        if (!currentHasItems) {
            synchronized(folderSpinnerLoads) { folderSpinnerLoads[id] = (folderSpinnerLoads[id] ?: 0) + 1 }
            _loadingFolderIds.update { it + id }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.loadFolderMedia(id, album)
            } finally {
                if (!currentHasItems) {
                    val left = synchronized(folderSpinnerLoads) {
                        val n = (folderSpinnerLoads[id] ?: 1) - 1
                        if (n <= 0) folderSpinnerLoads.remove(id) else folderSpinnerLoads[id] = n
                        n
                    }
                    // Two loads of one folder: the first to end left the second without its spinner.
                    if (left <= 0) _loadingFolderIds.update { it - id }
                }
            }
        }
    }

    /**
     * A private folder on screen whose lock came (a minute off screen, a lock elsewhere, a restart
     * that remembered it as open): its viewer, panes and file list close, back to the folders.
     * Left open it showed thumbnails still in memory, a black viewer and a nameless folder.
     */
    private fun closeLockedSecureFolders() {
        val ix = interaction.value
        val viewerBucket = uiState.value.viewerMedia?.bucketId ?: AlbumIds.NONE
        val candidates = setOf(ix.phoneFolderId, ix.sessionFolderId, viewerBucket) + ix.openFolderIds
        val locked = candidates.filterTo(HashSet()) { id ->
            id != AlbumIds.NONE && id != AlbumIds.TRASH && id != AlbumIds.FAVORITES &&
                SecureFolderManager.isSecureBucketLocked(id)
        }
        if (locked.isEmpty()) return
        AppLog.event("Secure") { "locked private folder(s) closed on screen: ${locked.size}" }
        if (viewerBucket in locked) closeViewer()
        val lockedFiles = locked.flatMapTo(HashSet()) { id -> uiState.value.folderMedia[id].orEmpty().map { it.id } }
        if (ix.selectedFileIds.any { it in lockedFiles }) clearSelection()
        if (store.settings.value.lastOpenedFolderId in locked) store.setLastOpenedFolder(AlbumIds.NONE)
        val updated = interaction.updateAndGet { current ->
            val open = current.openFolderIds.filterNot { it in locked }
            current.copy(
                phoneFolderId = if (current.phoneFolderId in locked) AlbumIds.NONE else current.phoneFolderId,
                sessionFolderId = if (current.sessionFolderId in locked) open.lastOrNull() ?: AlbumIds.NONE else current.sessionFolderId,
                openFolderIds = open,
                clickedFolderId = if (current.clickedFolderId in locked) AlbumIds.NONE else current.clickedFolderId,
                highlightedFolderId = if (current.highlightedFolderId in locked) AlbumIds.NONE else current.highlightedFolderId,
            ).let { if (open.size != current.openFolderIds.size) it.resetSplitIfNeeded(open) else it }
        }
        if (ix.openFolderIds.any { it in locked }) persistOpenFolders(updated.openFolderIds)
        repository.forgetFolderMedia(locked)
    }

    private fun lockSecureFolderIfLeaving(folderId: Long) {
        if (folderId == AlbumIds.NONE || folderId == AlbumIds.TRASH || folderId == AlbumIds.FAVORITES) return
        val album = uiState.value.allAlbums.firstOrNull { it.id == folderId }
        // An ordinary folder with nothing unlocked: no disk lookups on the main thread for it.
        if (album?.isSecure != true && SecureFolderManager.unlockedFolderPaths.value.isEmpty()) return
        val dir = album?.let {
            File(it.relativePath).takeIf { f -> f.isDirectory }
                ?: NomediaScanner.directoryForRelativePath(it.relativePath)
        }
        if (dir != null) {
            if (SecureFolderManager.isFolderSecure(dir) || SecureFolderManager.isFolderUnlocked(dir)) {
                SecureFolderManager.lockFolder(dir)
            }
        }
    }

    private fun shownFolderIds(): Set<Long> = interaction.value.let { ix ->
        setOf(ix.phoneFolderId, ix.sessionFolderId) + ix.openFolderIds
    }

    fun openFolder(id: Long, fromPhone: Boolean) {
        if (blockIfFolderDeleting(id)) return
        val currentPhoneFolderId = uiState.value.phoneFolderId
        if (fromPhone && currentPhoneFolderId != id && currentPhoneFolderId != AlbumIds.NONE) {
            lockSecureFolderIfLeaving(currentPhoneFolderId)
        }
        store.setLastOpenedFolder(id)
        // For "last used" in the folder's info.
        dev.ander.alexet.data.FolderUsage.markOpened(getApplication(), id)
        interaction.update {
            it.copy(
                phoneFolderId = if (fromPhone) id else if (it.phoneFolderId == AlbumIds.TRASH) AlbumIds.NONE else it.phoneFolderId,
                sessionFolderId = id,
                viewerMediaId = null,
            )
        }
        loadFolderMediaFast(id)
    }

    fun closePhoneFolder() {
        val currentPhoneFolderId = uiState.value.phoneFolderId
        lockSecureFolderIfLeaving(currentPhoneFolderId)
        if (store.settings.value.contentViewMode == ContentViewMode.LAST_FOLDER) {
            store.setLastOpenedFolder(AlbumIds.NONE)
        }
        interaction.update { ix ->
            ix.copy(
                phoneFolderId = AlbumIds.NONE,
                sessionFolderId = AlbumIds.NONE,
                openFolderIds = emptyList(),
                clickedFolderId = AlbumIds.NONE,
                pendingHitboxAlbumId = AlbumIds.NONE,
                highlightedFolderId = AlbumIds.NONE,
            ).resetSplitIfNeeded(emptyList())
        }
        persistOpenFolders(emptyList())
    }

    private fun openOnRight(id: Long) {
        if (id == AlbumIds.NONE) return
        val currentPhoneFolderId = uiState.value.phoneFolderId
        if (currentPhoneFolderId != AlbumIds.NONE && currentPhoneFolderId != id) {
            lockSecureFolderIfLeaving(currentPhoneFolderId)
        }
        store.setLastOpenedFolder(id)
        // Settings are written after the update: its lambda can run more than once.
        persistOpenFolders(listOf(id))
        interaction.update { ix ->
            ix.copy(
                openFolderIds = listOf(id),
                sessionFolderId = id,
                phoneFolderId = AlbumIds.NONE,
                viewerMediaId = null,
            )
        }
        loadFolderMediaFast(id)
    }

    fun onFolded() {
        val ix = interaction.value
        val activeFolderId = when {
            ix.phoneFolderId != AlbumIds.NONE -> ix.phoneFolderId
            ix.openFolderIds.isNotEmpty() -> ix.openFolderIds.first()
            ix.sessionFolderId != AlbumIds.NONE -> ix.sessionFolderId
            else -> AlbumIds.NONE
        }
        if (activeFolderId != AlbumIds.NONE && ix.phoneFolderId != activeFolderId) {
            interaction.update { it.copy(phoneFolderId = activeFolderId) }
        } else if (activeFolderId == AlbumIds.NONE && ix.phoneFolderId != AlbumIds.NONE) {
            interaction.update { it.copy(phoneFolderId = AlbumIds.NONE) }
        }
    }

    fun onUnfolded() {
        val id = interaction.value.phoneFolderId
        if (id == AlbumIds.NONE) {
            interaction.update { ix ->
                ix.copy(
                    sessionFolderId = AlbumIds.NONE,
                    phoneFolderId = AlbumIds.NONE,
                    openFolderIds = emptyList(),
                    clickedFolderId = AlbumIds.NONE,
                    pendingHitboxAlbumId = AlbumIds.NONE,
                ).resetSplitIfNeeded(emptyList())
            }
            if (store.settings.value.contentViewMode == ContentViewMode.LAST_FOLDER) {
                store.setLastOpenedFolder(AlbumIds.NONE)
            }
            persistOpenFolders(emptyList())
            return
        }
        if (id != AlbumIds.TRASH) {
            store.setLastOpenedFolder(id)
        }
        val mode = store.settings.value.contentViewMode
        if (mode == ContentViewMode.MULTI_FOLDER) {
            persistOpenFolders(listOf(id))
            interaction.update { ix ->
                val opens = listOf(id)
                ix.copy(
                    openFolderIds = opens,
                    sessionFolderId = id,
                    phoneFolderId = AlbumIds.NONE,
                )
            }
        } else if (mode == ContentViewMode.CLICK_DRAG) {
            interaction.update { it.copy(phoneFolderId = AlbumIds.NONE) }
            applyClickDragSlot(id, SLOT_MIDDLE, clicked = true)
        } else {
            interaction.update {
                it.copy(
                    sessionFolderId = id,
                    phoneFolderId = AlbumIds.NONE,
                )
            }
        }
    }

    fun onFolderClick(id: Long, wide: Boolean) {
        if (interaction.value.reorderingFolders) return
        if (blockIfFolderDeleting(id)) return
        if (interaction.value.selectedAlbumIds.isNotEmpty()) {
            toggleAlbumSelected(id)
            return
        }
        val mode = store.settings.value.contentViewMode
        if (wide && mode == ContentViewMode.MULTI_FOLDER) {
            if (interaction.value.selectedFileIds.isNotEmpty()) {
                toggleStamp(id)
                return
            }
            openOnRight(id)
            return
        }
        if (wide && mode == ContentViewMode.CLICK_DRAG) {
            if (interaction.value.selectedFileIds.isNotEmpty()) {
                toggleStamp(id)
                return
            }
            onClickDragFolderClick(id)
            return
        }
        if (interaction.value.selectedFileIds.isNotEmpty()) {
            toggleStamp(id)
            return
        }
        when {
            !wide || mode == ContentViewMode.EXPAND_FOLDERS -> openFolder(id, fromPhone = true)
            else -> openFolder(id, fromPhone = false)
        }
    }

    fun startFolderDrag(id: Long) {
        if (interaction.value.reorderingFolders) return
        if (FolderDeleteQueue.isDeleting(id)) return
        if (!store.settings.value.contentViewMode.usesFolderDrag()) return
        if (interaction.value.selectedFileIds.isNotEmpty()) {
            toggleStamp(id)
            return
        }
        interaction.update {
            it.copy(
                dragKind = DragKind.FOLDER,
                draggingFolderId = id,
                hoveredSlot = -1,
                pendingHitboxAlbumId = AlbumIds.NONE,
                highlightedFolderId = AlbumIds.NONE,
            )
        }
    }

    fun startFilesDrag(sourceAlbumId: Long) {
        if (!store.settings.value.contentViewMode.usesFolderDrag()) return
        if (interaction.value.selectedFileIds.isEmpty()) return
        interaction.update {
            it.copy(dragKind = DragKind.FILES, filesDragSourceAlbumId = sourceAlbumId)
        }
    }

    fun hoverAt(albumId: Long = AlbumIds.NONE, slot: Int = -1) {
        interaction.update { it.copy(hoveredAlbumId = albumId, hoveredSlot = slot) }
    }

    fun endDrag(launchWrite: (android.content.IntentSender) -> Unit = {}) {
        val ix = interaction.value
        val destId = when (ix.dragKind) {
            DragKind.FOLDER -> {
                if (ix.hoveredSlot >= 0) {
                    if (store.settings.value.contentViewMode == ContentViewMode.CLICK_DRAG) {
                        applyClickDragSlot(
                            id = ix.draggingFolderId,
                            slot = if (ix.openFolderIds.isEmpty()) SLOT_MIDDLE else ix.hoveredSlot,
                            clicked = false,
                        )
                    } else {
                        dropFolder(ix.draggingFolderId, ix.hoveredSlot)
                    }
                }
                null
            }
            DragKind.FILES -> when {
                ix.hoveredAlbumId != AlbumIds.NONE -> ix.hoveredAlbumId
                ix.hoveredSlot >= 0 -> when (ix.hoveredSlot) {
                    0 -> ix.openFolderIds.getOrNull(0)
                    2 -> ix.openFolderIds.getOrNull(1) ?: ix.openFolderIds.getOrNull(0)
                    else -> ix.openFolderIds.singleOrNull() ?: ix.openFolderIds.getOrNull(0)
                }
                else -> null
            }
            DragKind.NONE -> null
        }
        val dest = destId?.let { id -> uiState.value.albums.firstOrNull { it.id == id } }
        val items = if (ix.dragKind == DragKind.FILES && dest != null) {
            selectedMedia().filter { it.bucketId != dest.id }
        } else {
            emptyList()
        }
        interaction.update {
            it.copy(
                dragKind = DragKind.NONE,
                draggingFolderId = AlbumIds.NONE,
                filesDragSourceAlbumId = AlbumIds.NONE,
                hoveredAlbumId = AlbumIds.NONE,
                hoveredSlot = -1,
            )
        }
        if (dest != null && items.isNotEmpty()) startMove(items, dest, launchWrite)
    }

    fun cancelHitboxes() {
        interaction.update {
            it.copy(
                pendingHitboxAlbumId = AlbumIds.NONE,
                highlightedFolderId = AlbumIds.NONE,
            )
        }
    }

    fun selectHitbox(slot: Int) {
        val id = interaction.value.pendingHitboxAlbumId
        if (id == AlbumIds.NONE) return
        applyClickDragSlot(id, slot, clicked = true)
    }

    private fun onClickDragFolderClick(id: Long) {
        val ix = interaction.value
        if (id in ix.openFolderIds) {
            pulseOpenFolderHighlight(id)
            return
        }
        val clicked = if (ix.clickedFolderId != AlbumIds.NONE && ix.clickedFolderId in ix.openFolderIds) {
            ix.clickedFolderId
        } else {
            AlbumIds.NONE
        }
        when {
            ix.openFolderIds.isEmpty() -> {
                interaction.update { it.copy(viewerMediaId = null) }
                applyClickDragSlot(id, SLOT_MIDDLE, clicked = true)
            }
            clicked != AlbumIds.NONE -> {
                val clickedIndex = ix.openFolderIds.indexOf(clicked)
                if (clickedIndex < 0) return
                store.setLastOpenedFolder(id)
                interaction.update { state ->
                    val next = state.openFolderIds.toMutableList()
                    if (clickedIndex in next.indices) next[clickedIndex] = id
                    state.copy(
                        openFolderIds = next,
                        clickedFolderId = id,
                        pendingHitboxAlbumId = AlbumIds.NONE,
                        highlightedFolderId = AlbumIds.NONE,
                        sessionFolderId = id,
                        viewerMediaId = null,
                    ).resetSplitIfNeeded(next)
                }
            }
            else -> interaction.update {
                it.copy(
                    pendingHitboxAlbumId = id,
                    highlightedFolderId = AlbumIds.NONE,
                )
            }
        }
    }

    private fun pulseOpenFolderHighlight(id: Long) {
        highlightJob?.cancel()
        interaction.update {
            it.copy(
                highlightedFolderId = id,
                pendingHitboxAlbumId = AlbumIds.NONE,
            )
        }
        highlightJob = viewModelScope.launch {
            delay(400)
            interaction.update { ix ->
                if (ix.highlightedFolderId == id) {
                    ix.copy(highlightedFolderId = AlbumIds.NONE)
                } else {
                    ix
                }
            }
        }
    }

    private fun applyClickDragSlot(id: Long, slot: Int, clicked: Boolean) {
        if (id == AlbumIds.NONE) return
        store.setLastOpenedFolder(id)
        interaction.update { ix ->
            val existing = ix.openFolderIds.filter { it != id }
            val next = when (slot) {
                SLOT_MIDDLE -> listOf(id)
                SLOT_TOP -> {
                    val bottom = existing.getOrNull(1) ?: existing.getOrNull(0)
                    if (bottom == null) listOf(id) else listOf(id, bottom)
                }
                else -> {
                    val top = existing.getOrNull(0)
                    if (top == null) listOf(id) else listOf(top, id)
                }
            }.take(2)
            val newClicked = when {
                slot == SLOT_MIDDLE -> if (clicked) id else AlbumIds.NONE
                clicked -> id
                else -> if (ix.clickedFolderId in next) ix.clickedFolderId else AlbumIds.NONE
            }
            ix.copy(
                openFolderIds = next,
                clickedFolderId = newClicked,
                pendingHitboxAlbumId = AlbumIds.NONE,
                highlightedFolderId = AlbumIds.NONE,
                sessionFolderId = id,
                phoneFolderId = AlbumIds.NONE,
            ).resetSplitIfNeeded(next)
        }
        loadFolderMediaFast(id)
    }

    private fun dropFolder(id: Long, slot: Int) {
        if (id == AlbumIds.NONE) return
        // Settings are written after the update: its lambda can run more than once.
        interaction.updateAndGet { ix ->
            val existing = ix.openFolderIds.filter { it != id }
            val next = when (slot) {
                1 -> listOf(id)
                0 -> {
                    val bottom = existing.getOrNull(1) ?: existing.getOrNull(0)
                    if (bottom == null) listOf(id) else listOf(id, bottom)
                }
                else -> {
                    val top = existing.getOrNull(0)
                    if (top == null) listOf(id) else listOf(top, id)
                }
            }.take(2)
            ix.copy(openFolderIds = next, sessionFolderId = id, phoneFolderId = AlbumIds.NONE)
        }.also { persistOpenFolders(it.openFolderIds) }
        store.setLastOpenedFolder(id)
        loadFolderMediaFast(id)
    }

    private fun persistOpenFolders(ids: List<Long>) {
        if (store.settings.value.contentViewMode != ContentViewMode.MULTI_FOLDER) return
        if (store.settings.value.multiFolderMemory == MultiFolderMemory.REMEMBER) {
            store.setRememberedOpenFolders(ids)
        }
    }

    fun closeOpenFolder(id: Long) {
        lockSecureFolderIfLeaving(id)
        // Settings are written after the update: its lambda can run more than once.
        val updated = interaction.updateAndGet { ix ->
            val next = ix.openFolderIds.filterNot { it == id }
            val nextSessionId = if (next.isNotEmpty()) next.last() else AlbumIds.NONE
            ix.copy(
                openFolderIds = next,
                clickedFolderId = if (ix.clickedFolderId == id) AlbumIds.NONE else ix.clickedFolderId,
                pendingHitboxAlbumId = AlbumIds.NONE,
                highlightedFolderId = if (ix.highlightedFolderId == id) {
                    AlbumIds.NONE
                } else {
                    ix.highlightedFolderId
                },
                sessionFolderId = nextSessionId,
            ).resetSplitIfNeeded(next)
        }
        persistOpenFolders(updated.openFolderIds)
        if (updated.openFolderIds.isEmpty() && store.settings.value.contentViewMode == ContentViewMode.LAST_FOLDER) {
            store.setLastOpenedFolder(AlbumIds.NONE)
        }
    }

    fun onSplitDragEnd(ratio: Float, velocityPxPerSec: Float, paneHeightPx: Float) {
        val ix = interaction.value
        if (ix.openFolderIds.size != 2) return
        val result = splitReleaseResult(ratio, velocityPxPerSec, paneHeightPx)
        when {
            result.closeBottom -> closeOpenFolder(ix.openFolderIds[1])
            result.closeTop -> closeOpenFolder(ix.openFolderIds[0])
            else -> interaction.update {
                it.copy(
                    paneStack = result.stack,
                    splitRatio = if (result.stack == PaneStack.NONE) result.ratio else it.splitRatio,
                )
            }
        }
    }

    fun swapStackedPanes() {
        interaction.update { ix ->
            ix.copy(
                paneStack = when (ix.paneStack) {
                    PaneStack.TOP -> PaneStack.BOTTOM
                    PaneStack.BOTTOM -> PaneStack.TOP
                    PaneStack.NONE -> ix.paneStack
                },
            )
        }
    }

    fun toggleFileSelected(id: Long) {
        interaction.update { ix ->
            val currentSet = ix.selectedFileIds
            val currentOrdered = (ix.selectedFileIdsOrdered.ifEmpty { ix.selectedFileIds.toList() })
                .filter { it in currentSet }
            val newOrdered = if (id in currentSet) {
                currentOrdered.filter { it != id }
            } else {
                currentOrdered.filter { it != id } + id
            }
            ix.withFileSelection(newOrdered)
        }
    }

    fun clearSelection() = interaction.update {
        it.copy(
            selectedFileIds = emptySet(),
            selectedFileIdsOrdered = emptyList(),
            selectedAlbumIds = emptySet(),
            stampedAlbumIds = emptySet(),
        )
    }

    private fun Interaction.withFileSelection(ordered: List<Long>): Interaction {
        val distinct = ordered.distinct()
        return copy(
            selectedFileIds = distinct.toSet(),
            selectedFileIdsOrdered = distinct,
            selectedAlbumIds = emptySet(),
            stampedAlbumIds = if (distinct.isEmpty()) emptySet() else stampedAlbumIds,
        )
    }

    fun startFolderReorder() {
        val ids = uiState.value.albums.map { it.id }
        if (ids.isEmpty()) return
        interaction.update {
            it.copy(
                reorderingFolders = true,
                reorderDraftIds = ids,
                selectedFileIds = emptySet(),
                selectedFileIdsOrdered = emptyList(),
                selectedAlbumIds = emptySet(),
                stampedAlbumIds = emptySet(),
                dragKind = DragKind.NONE,
                draggingFolderId = AlbumIds.NONE,
                hoveredAlbumId = AlbumIds.NONE,
                hoveredSlot = -1,
            )
        }
    }

    fun moveFolderReorder(orderedIds: List<Long>) {
        interaction.update { ix ->
            if (!ix.reorderingFolders) return@update ix
            // The order as shown; draft ids of folders not on screen right now keep their place
            // after it, so a folder that briefly vanished during a sync isn't dropped.
            val shown = orderedIds.distinct()
            val shownSet = shown.toHashSet()
            val rest = ix.reorderDraftIds.filter { it !in shownSet }
            ix.copy(reorderDraftIds = shown + rest)
        }
    }

    fun confirmFolderReorder() {
        val ix = interaction.value
        if (!ix.reorderingFolders) return
        store.update { it.copy(albumOrder = ix.reorderDraftIds) }
        interaction.update {
            it.copy(reorderingFolders = false, reorderDraftIds = emptyList())
        }
    }

    fun cancelFolderReorder() {
        interaction.update {
            it.copy(reorderingFolders = false, reorderDraftIds = emptyList())
        }
    }

    fun startContentOrderMode(folderId: Long = AlbumIds.NONE) {
        val state = uiState.value
        val selected = state.selectedFiles
        val selectedBucketIds = selected.map { it.bucketId }.distinct()
        val target = when {
            folderId != AlbumIds.NONE -> folderId
            state.sortTargetAlbumId != AlbumIds.NONE -> state.sortTargetAlbumId
            selectedBucketIds.size == 1 -> selectedBucketIds.first()
            selected.isNotEmpty() -> selected.first().bucketId
            else -> AlbumIds.NONE
        }
        if (target != AlbumIds.NONE && target != AlbumIds.TRASH && blockIfPrivateFolder(target)) return
        if (target == AlbumIds.NONE || target == AlbumIds.TRASH) {
            toast(R.string.content_order_no_folder)
            return
        }
        var current = state.visibleFilesIn(target)
        if (current.isEmpty()) {
            current = state.filesIn(target)
        }
        if (current.isEmpty() && target != AlbumIds.FAVORITES && target != AlbumIds.TRASH) {
            val album = state.allAlbums.firstOrNull { it.id == target }
            current = state.media.filter { it.bucketId == target || (album != null && it.rawBucketId != 0L && it.rawBucketId in album.legacyBucketIds) }
        }
        if (current.isEmpty()) {
            toast(R.string.content_order_no_folder)
            return
        }
        val saved = state.settings.folderContentOrders[target].orEmpty()
        val byId = current.associateBy { it.id }
        val draft = buildList {
            val seen = HashSet<Long>()
            for (id in saved) {
                if (byId.containsKey(id) && seen.add(id)) add(id)
            }
            for (item in current) {
                if (seen.add(item.id)) add(item.id)
            }
        }
        val isAnon = target in state.settings.anonymousFolderIds
        interaction.update {
            it.copy(
                contentOrderMode = true,
                contentOrderDraftIds = draft,
                contentOrderFolderId = target,
                contentOrderRenameNumeric = false,
                selectedFileIds = emptySet(),
                selectedFileIdsOrdered = emptyList(),
                selectedAlbumIds = emptySet(),
                stampedAlbumIds = emptySet(),
                reorderingFolders = false,
                reorderDraftIds = emptyList(),
                dragKind = DragKind.NONE,
                draggingFolderId = AlbumIds.NONE,
                hoveredAlbumId = AlbumIds.NONE,
                hoveredSlot = -1,
            )
        }
        // silence unused in anon path for clarity
        if (isAnon) {
            interaction.update { ix -> ix.copy(contentOrderRenameNumeric = false) }
        }
    }

    fun moveContentOrder(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        interaction.update { ix ->
            if (!ix.contentOrderMode) return@update ix
            val list = ix.contentOrderDraftIds.toMutableList()
            if (fromIndex !in list.indices || toIndex !in list.indices) return@update ix
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            ix.copy(contentOrderDraftIds = list)
        }
    }

    fun setContentOrderRenameNumeric(enabled: Boolean) {
        interaction.update { ix ->
            if (!ix.contentOrderMode) return@update ix
            val anon = ix.contentOrderFolderId in store.settings.value.anonymousFolderIds
            ix.copy(contentOrderRenameNumeric = enabled && !anon)
        }
    }

    fun cancelContentOrderMode() {
        interaction.update {
            it.copy(
                contentOrderMode = false,
                contentOrderDraftIds = emptyList(),
                contentOrderFolderId = AlbumIds.NONE,
                contentOrderRenameNumeric = false,
            )
        }
    }

    fun confirmContentOrder(launchWrite: (android.content.IntentSender) -> Unit) {
        val ix = interaction.value
        if (!ix.contentOrderMode) return
        val folderId = ix.contentOrderFolderId
        val draftIds = ix.contentOrderDraftIds
        val renameNumeric = ix.contentOrderRenameNumeric &&
            folderId !in store.settings.value.anonymousFolderIds
        if (folderId == AlbumIds.NONE || draftIds.isEmpty()) {
            cancelContentOrderMode()
            return
        }
        viewModelScope.launch(fileOperationErrors(R.string.content_order_failed) { _transferProgress.value = null }) {
            val state = uiState.value
            val byId = state.media.associateBy { it.id }
            val ordered = draftIds.mapNotNull { byId[it] }

            val files = withContext(Dispatchers.IO) { ordered.mapNotNull { repository.fileForMedia(it) } }
            if (files.isNotEmpty()) {
                _transferProgress.value = TransferProgress(
                    current = 0,
                    total = files.size,
                    isMove = false,
                    isSavingOrder = true,
                )
                val needWrite = try {
                    repository.applySelectionOrderModifiedDates(files)
                } finally {
                    if (!renameNumeric) {
                        _transferProgress.value = null
                    }
                }
                if (needWrite.isNotEmpty()) {
                    val sender = repository.createWriteRequest(needWrite)
                    if (sender != null) {
                        _transferProgress.value = null
                        pendingAfterWrite = {
                            viewModelScope.launch(fileOperationErrors(R.string.content_order_failed) { _transferProgress.value = null }) {
                                // The saved order is dropped only when the names now carry it.
                                var renamedOk = false
                                try {
                                    _transferProgress.value = TransferProgress(
                                        current = 0,
                                        total = files.size,
                                        isMove = false,
                                        isSavingOrder = true,
                                    )
                                    repository.applySelectionOrderModifiedDates(files)
                                    if (renameNumeric) {
                                        renamedOk = runContentOrderRename(ordered).isEmpty()
                                    }
                                } finally {
                                    completeContentOrder(
                                        folderId,
                                        draftIds,
                                        ordered.mapNotNull { repository.fileForMedia(it) }.ifEmpty { files },
                                        isRenamedNumeric = renamedOk,
                                    )
                                }
                            }
                        }
                        launchWrite(sender)
                        return@launch
                    }
                }
            }

            var renamedOk = false
            if (renameNumeric && ordered.isNotEmpty()) {
                val failed = runContentOrderRename(ordered)
                renamedOk = failed.isEmpty()
                if (failed.isNotEmpty()) {
                    val sender = repository.createWriteRequest(failed)
                    if (sender != null) {
                        _transferProgress.value = null
                        pendingAfterWrite = {
                            viewModelScope.launch {
                                var retryOk = false
                                try {
                                    retryOk = runContentOrderRename(ordered).isEmpty()
                                } finally {
                                    completeContentOrder(
                                        folderId,
                                        draftIds,
                                        ordered.mapNotNull { repository.fileForMedia(it) }.ifEmpty { files },
                                        isRenamedNumeric = retryOk,
                                    )
                                }
                            }
                        }
                        launchWrite(sender)
                        return@launch
                    }
                }
            }

            completeContentOrder(
                folderId,
                draftIds,
                ordered.mapNotNull { repository.fileForMedia(it) }.ifEmpty { files },
                // A failed rename keeps the hand-made order as the saved list.
                isRenamedNumeric = renamedOk,
            )
        }
    }

    private fun completeContentOrder(folderId: Long, draftIds: List<Long>, files: List<File>, isRenamedNumeric: Boolean = false) {
        val album = uiState.value.allAlbums.firstOrNull { it.id == folderId }
        val idsToSave = if (isRenamedNumeric) emptyList() else draftIds
        store.setFolderContentOrder(folderId, idsToSave)
        album?.legacyBucketIds?.forEach { legId ->
            store.setFolderContentOrder(legId, idsToSave)
        }
        _transferProgress.value = null
        cancelContentOrderMode()
        if (files.isNotEmpty()) {
            val paths = files.map { it.absolutePath }.toTypedArray()
            val remaining = java.util.concurrent.atomic.AtomicInteger(paths.size)
            MediaScannerConnection.scanFile(
                getApplication(),
                paths,
                null,
            ) { _, _ ->
                if (remaining.decrementAndGet() == 0) {
                    repository.requestRefresh()
                }
            }
        }
    }

    /** Returns failed items. Shows transfer progress while renaming. */
    private suspend fun runContentOrderRename(ordered: List<GalleryMedia>): List<GalleryMedia> {
        if (ordered.isEmpty()) return emptyList()
        _transferProgress.value = TransferProgress(
            current = 0,
            total = ordered.size * 2,
            isMove = false,
            isRename = true,
        )
        val (success, failed) = repository.renameToNumericOrder(ordered) { cur, tot ->
            _transferProgress.value = TransferProgress(
                current = cur,
                total = tot,
                isMove = false,
                isRename = true,
            )
        }
        if (failed.isNotEmpty() && success == 0) {
            toast(R.string.rename_failed)
        } else {
            _transferProgress.value = TransferProgress(
                current = ordered.size * 2,
                total = ordered.size * 2,
                isMove = false,
                isRename = true,
                isDone = true,
            )
            delay(350L)
        }
        return failed
    }

    fun toggleStamp(albumId: Long) {
        if (interaction.value.selectedFileIds.isEmpty()) return
        if (blockIfFolderDeleting(albumId)) return
        interaction.update { ix ->
            val next = ix.stampedAlbumIds.toMutableSet()
            if (!next.add(albumId)) next.remove(albumId)
            ix.copy(stampedAlbumIds = next)
        }
    }

    fun confirmMove(launchWrite: (android.content.IntentSender) -> Unit) {
        val dest = interaction.value.stampedAlbumIds.toList()
        if (dest.isEmpty()) return
        if (dest.size == 1) {
            moveSelectionToAlbum(dest.first(), launchWrite)
        } else {
            copySelectionTo(dest)
        }
    }

    private fun copySelectionTo(albumIds: List<Long>) {
        copyToDestinations(albumIds.mapNotNull(::resolveAlbum))
    }

    private fun copyToDestinations(destinations: List<Album>) {
        val items = excludeDownloading(selectedMedia())
        if (blockIfPrivate(items)) return
        if (destinations.any { it.isSecure }) {
            toast(R.string.cannot_move_to_secure_folder)
        }
        val dest = destinations.filter { album ->
            !album.isSecure && items.any { it.bucketId != album.id }
        }
        if (items.isEmpty() || dest.isEmpty()) return
        val sourceIds = items.map { it.bucketId }
        // Counted over the folders actually copied to (private ones were filtered out above).
        val total = dest.sumOf { d -> items.count { it.bucketId != d.id } }
        clearSelection()
        viewModelScope.launch {
            try {
                _transferProgress.value = TransferProgress(current = 0, total = total, isMove = false)
                notifier.showProgress(0, total, isMove = false)
                // Finished even when the app is left meanwhile (Back cancels this scope): a cut
                // left a half-written copy.
                val ok = withContext(kotlinx.coroutines.NonCancellable) {
                    repository.copyToAlbums(
                        items,
                        dest,
                        anonymousFolderIds = store.settings.value.anonymousFolderIds,
                        randomizeFileNames = store.settings.value.randomizeFileNamesInPrivateFolders,
                        stripExif = store.settings.value.stripExifInPrivateFolders,
                    ) { cur, tot ->
                        _transferProgress.value = TransferProgress(current = cur, total = tot, isMove = false)
                        notifier.showProgress(cur, tot, isMove = false)
                    }
                }
                val copied = ok.size
                if (copied == 0) {
                    toast(R.string.copy_failed)
                    notifier.showFailed(isMove = false)
                } else {
                    if (copied < total) {
                        toast(R.string.copied_partial, copied, total)
                    } else {
                        toast(R.string.copied_count, copied)
                    }
                    _transferProgress.value = TransferProgress(current = copied, total = total, isMove = false, isDone = true)
                    notifier.showCompleted(copied, isMove = false)
                }
                bumpFolderCovers(sourceIds + dest.map { it.id })
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e("GalleryViewModel", "Copy failed", e)
                toast(R.string.copy_failed)
                notifier.showFailed(isMove = false)
            } finally {
                _transferProgress.value = null
            }
        }
    }

    fun openMedia(item: GalleryMedia) {
        AppLog.event("Action") { "openMedia id=${item.id} ${item.displayName} video=${item.isVideo}" }
        if (interaction.value.selectedFileIds.isNotEmpty()) {
            toggleFileSelected(item.id)
            return
        }
        if (blockIfDownloading(item)) return
        if (item.isOtherFile && !item.isAudio) {
            // ZIP, 7Z, TAR, RAR: their contents in a window; other files in a matching app.
            if (dev.ander.alexet.data.ArchiveReader.formatOf(item.displayName) != null) openArchive(item) else openOtherFile(item)
            return
        }
        lastActiveViewerMedia = item
        interaction.update { it.copy(viewerMediaId = item.id) }
    }

    /** A document, an archive, an app…: opened in the app the phone has for it. */
    private fun openOtherFile(item: GalleryMedia) {
        openFileOutside(item.absolutePath?.let { File(it) }, item.mimeType, item.displayName)
    }

    private fun openFileOutside(file: File?, mimeType: String, displayName: String) {
        val app = getApplication<Application>()
        val uri = file?.let {
            runCatching { androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", it) }.getOrNull()
        }
        if (uri == null) {
            toast(R.string.open_file_no_app)
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(view)
        } catch (e: android.content.ActivityNotFoundException) {
            toast(R.string.open_file_no_app)
        } catch (e: Exception) {
            AppLog.w("GalleryViewModel", "Opening $displayName failed", e)
            toast(R.string.open_file_no_app)
        }
    }

    // ---------------------------------------------------------------- archive window

    /** An archive shown in its window: the listing (null while read) and the folder inside it. */
    data class ArchiveBrowserState(
        val path: String,
        val name: String,
        val mimeType: String,
        val listing: dev.ander.alexet.data.ArchiveReader.Listing? = null,
        val dir: String = "",
        /** Entry being unpacked to open it; [progress] 0..100, or -1 when the size is unknown. */
        val extracting: String? = null,
        val progress: Int = -1,
    )

    private val _archiveBrowser = MutableStateFlow<ArchiveBrowserState?>(null)
    val archiveBrowser: StateFlow<ArchiveBrowserState?> = _archiveBrowser.asStateFlow()
    private var archiveListJob: Job? = null
    private var archiveExtractJob: Job? = null
    /** Unpacked for another app: kept when the window closes (that app may still be reading it). */
    @Volatile private var archiveFileOutside: File? = null

    private fun archiveCacheDir(): File = File(getApplication<Application>().cacheDir, ARCHIVE_CACHE_DIR)

    private fun openArchive(item: GalleryMedia) {
        val path = item.absolutePath ?: return openOtherFile(item)
        archiveListJob?.cancel()
        archiveExtractJob?.cancel()
        _archiveBrowser.value = ArchiveBrowserState(path = path, name = item.displayName, mimeType = item.mimeType)
        archiveListJob = viewModelScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            val listing = withContext(Dispatchers.IO) { dev.ander.alexet.data.ArchiveReader.list(File(path)) }
            AppLog.event("Archive") {
                val what = (listing as? dev.ander.alexet.data.ArchiveReader.Listing.Ok)?.entries?.size?.let { "$it entries" }
                    ?: listing.javaClass.simpleName
                "${item.displayName}: $what in ${android.os.SystemClock.elapsedRealtime() - started} ms"
            }
            _archiveBrowser.update { s -> if (s?.path == path) s.copy(listing = listing) else s }
        }
    }

    fun archiveNavigate(dir: String) {
        _archiveBrowser.update { it?.copy(dir = dir) }
    }

    /** Back in the window: stops an unpacking, then goes one folder up, then closes. */
    fun archiveBack() {
        val s = _archiveBrowser.value ?: return
        when {
            s.extracting != null -> cancelArchiveExtract()
            s.dir.isEmpty() -> closeArchive()
            else -> archiveNavigate(s.dir.substringBeforeLast('/', ""))
        }
    }

    fun closeArchive() {
        archiveListJob?.cancel()
        archiveExtractJob?.cancel()
        _archiveBrowser.value = null
        val keep = archiveFileOutside
        viewModelScope.launch(Dispatchers.IO) {
            archiveCacheDir().listFiles()?.forEach { dir ->
                if (keep == null || keep.parentFile != dir) dir.deleteRecursively()
            }
        }
    }

    fun cancelArchiveExtract() {
        archiveExtractJob?.cancel()
        _archiveBrowser.update { it?.copy(extracting = null, progress = -1) }
    }

    /** The whole archive in the app the phone has for it. */
    fun openArchiveOutside() {
        val s = _archiveBrowser.value ?: return
        openFileOutside(File(s.path), s.mimeType, s.name)
    }

    /** A file inside: unpacked to the cache, then a photo or video in the viewer, else outside. */
    fun openArchiveEntry(entry: dev.ander.alexet.data.ArchiveReader.Entry) {
        val s = _archiveBrowser.value ?: return
        val listing = s.listing as? dev.ander.alexet.data.ArchiveReader.Listing.Ok ?: return
        if (!listing.canExtract) {
            toast(R.string.archive_list_only)
            return
        }
        if (entry.encrypted) {
            toast(R.string.archive_entry_encrypted)
            return
        }
        archiveExtractJob?.cancel()
        _archiveBrowser.update { it?.copy(extracting = entry.path, progress = -1) }
        archiveExtractJob = viewModelScope.launch {
            val target = withContext(Dispatchers.IO) {
                val dir = File(archiveCacheDir(), java.util.UUID.randomUUID().toString().take(8)).apply { mkdirs() }
                val file = File(dir, dev.ander.alexet.data.sanitizeFileName(entry.name.ifBlank { "file" }))
                val ok = dev.ander.alexet.data.ArchiveReader.extract(
                    archive = File(s.path),
                    entryPath = entry.path,
                    target = file,
                    onProgress = { p ->
                        _archiveBrowser.update { st -> if (st?.extracting == entry.path) st.copy(progress = p) else st }
                    },
                    isCancelled = { !isActive },
                )
                if (ok) file else null.also { dir.deleteRecursively() }
            }
            _archiveBrowser.update { it?.copy(extracting = null, progress = -1) }
            if (target == null) {
                if (isActive) toast(R.string.archive_extract_failed)
                return@launch
            }
            val mime = NomediaScanner.mimeOf(target.name)
            if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                // The window steps aside while the viewer is open and comes back when it closes.
                val media = withContext(Dispatchers.IO) { externalMediaFor(Uri.fromFile(target)) }
                interaction.update { it.copy(viewerMediaId = media.id, externalMedia = media, pendingUri = null) }
            } else {
                archiveFileOutside = target
                openFileOutside(target, dev.ander.alexet.data.OtherFiles.mimeOf(target.name), target.name)
            }
        }
    }

    /** Actions only photos and videos have (edit, wallpaper, EXIF…): other files are left out. */
    private fun withoutOtherFiles(items: List<GalleryMedia>): List<GalleryMedia> {
        if (items.none { it.isOtherFile }) return items
        val media = items.filter { !it.isOtherFile }
        if (media.isEmpty()) toast(R.string.other_file_action_unavailable)
        return media
    }

    fun setShowAllFiles(show: Boolean) {
        store.setShowAllFiles(show)
    }

    fun setShowOtherFileFolders(show: Boolean) {
        store.setShowOtherFileFolders(show)
    }

    /** "See all files" for one folder, kept until switched back (also across restarts). */
    fun toggleAllFilesFolder(albumId: Long) {
        if (albumId == AlbumIds.NONE || albumId == AlbumIds.TRASH || albumId == AlbumIds.FAVORITES) return
        store.toggleAllFilesFolder(albumId)
    }

    fun closeViewer() {
        AppLog.event("Action") { "closeViewer" }
        synchronized(viewerCacheLock) {
            lastActiveViewerMedia = null
            interaction.update { it.copy(viewerMediaId = null, pendingUri = null, externalMedia = null) }
        }
    }

    fun selectMedia(id: Long?) {
        if (id == null) {
            lastActiveViewerMedia = null
        } else {
            val state = uiState.value
            val found = state.media.firstOrNull { it.id == id }
                ?: state.folderMedia.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } }
            found?.let {
                if (blockIfDownloading(it)) return
                lastActiveViewerMedia = it
            }
        }
        interaction.update {
            it.copy(
                viewerMediaId = id,
                pendingUri = if (id == null) null else it.pendingUri,
            )
        }
    }

    fun openUri(uri: Uri) {
        AppLog.event("Action") { "openUri $uri" }
        interaction.update { it.copy(pendingUri = uri, viewerMediaId = null) }
        viewModelScope.launch {
            val match = matchIncomingUri(uri, repository.refresh())
            if (match != null) {
                store.setLastOpenedFolder(match.bucketId)
                interaction.update {
                    it.copy(
                        viewerMediaId = match.id,
                        sessionFolderId = match.bucketId,
                        pendingUri = uri,
                        externalMedia = null,
                    )
                }
            } else {
                // Provider query + image bounds: slow providers would freeze the UI on Main.
                val extItem = withContext(Dispatchers.IO) { externalMediaFor(uri) }
                interaction.update {
                    it.copy(
                        viewerMediaId = extItem.id,
                        externalMedia = extItem,
                        pendingUri = uri,
                    )
                }
            }
        }
    }

    /**
     * ACTION_EDIT from another app (e.g. the screenshot "Edit" chooser): open the photo editor directly.
     * [wasOnScreenAt] tells whether Alexet was visible at a given wall-clock time: a screenshot
     * taken then was taken inside the app, so after the edit the user stays here; otherwise the
     * task goes back to where "Edit" was tapped.
     */
    fun editUri(uri: Uri, wasOnScreenAt: (Long) -> Boolean = { false }) {
        AppLog.event("Action") { "editUri $uri" }
        viewModelScope.launch {
            // No repository.refresh() here: a MediaStore sync on cold start held the editor
            // back for seconds. The cached list or a direct lookup of the URI is enough;
            // saving resolves the source folder from the URI itself (fileForMedia).
            // Cold start: the library list may still be empty for a moment; without it a library
            // photo was taken for an outside file and edited as a copy.
            val library = repository.media.value.ifEmpty {
                kotlinx.coroutines.withTimeoutOrNull(1_500L) { repository.media.first { it.isNotEmpty() } }.orEmpty()
            }
            val cached = matchIncomingUri(uri, library)
            val media = cached ?: withContext(Dispatchers.IO) { externalMediaFor(uri) }
            val capturedAt = withContext(Dispatchers.IO) { captureTimeOf(uri, cached) }
            val returnToCaller = capturedAt <= 0L || !wasOnScreenAt(capturedAt)
            val current = _editingMedia.value
            if (current != null && current.id != media.id && editorDirty) {
                pendingReturnToCaller = returnToCaller
                _pendingExternalEdit.value = media
            } else {
                startExternalEdit(media, returnToCaller)
            }
        }
    }

    /** When the incoming file was created (wall clock, ms); 0 when unknown. */
    private fun captureTimeOf(uri: Uri, cached: GalleryMedia?): Long {
        val resolver = getApplication<Application>().contentResolver
        if (uri.scheme == "content") {
            val fromStore = runCatching {
                resolver.query(
                    uri,
                    arrayOf(
                        MediaStore.MediaColumns.DATE_TAKEN,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                    ),
                    null,
                    null,
                    null,
                )?.use { c ->
                    if (!c.moveToFirst()) return@use 0L
                    val taken = if (c.isNull(0)) 0L else c.getLong(0)
                    val added = if (c.isNull(1)) 0L else c.getLong(1) * 1000L
                    val modified = if (c.isNull(2)) 0L else c.getLong(2) * 1000L
                    listOf(taken, added, modified).firstOrNull { it > 0L } ?: 0L
                } ?: 0L
            }.getOrDefault(0L)
            if (fromStore > 0L) return fromStore
        }
        val path = cached?.absolutePath ?: uri.takeIf { it.scheme == "file" }?.path
        return path?.let { File(it).lastModified() } ?: (cached?.capturedAtMs ?: 0L)
    }

    private fun matchIncomingUri(uri: Uri, allMedia: List<GalleryMedia>): GalleryMedia? {
        allMedia.firstOrNull { it.uri == uri }?.let { return it }
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return allMedia.firstOrNull {
                it.absolutePath?.equals(path, ignoreCase = true) == true ||
                    it.storagePath.equals(path, ignoreCase = true)
            }
        }
        if (uri.scheme == "content" && uri.authority == android.provider.MediaStore.AUTHORITY) {
            val idLong = uri.lastPathSegment?.toLongOrNull() ?: return null
            return allMedia.firstOrNull { it.id == idLong }
        }
        return null
    }

    private fun externalMediaFor(uri: Uri): GalleryMedia {
        val context = getApplication<android.app.Application>()
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty().ifBlank {
            val ext = uri.lastPathSegment?.substringAfterLast('.')?.lowercase().orEmpty()
            if (ext in listOf("mp4", "mkv", "mov", "webm", "3gp", "avi")) "video/$ext" else "image/*"
        }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment ?: "file" }
        // A query of its own: a provider rejecting the column must not cost the name above.
        val providerSize = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
            }
        }.getOrNull() ?: 0L
        // The file itself when it can be found: the info shows its path instead of a bare name.
        val path = if (uri.scheme == "file") {
            uri.path
        } else {
            dev.ander.alexet.data.ExternalFilePath.resolve(context, uri, name, providerSize, repository.media.value)
        }
        val size = path?.let { runCatching { File(it).length() }.getOrDefault(0L) }?.takeIf { it > 0L }
            ?: providerSize.coerceAtLeast(0L)
        // Header-only read (fast): the editor needs the real aspect before the preview decodes,
        // otherwise aspect presets are computed for a 1000x1000 placeholder.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (mime.startsWith("image")) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                }
            }
        }
        return GalleryMedia(
            id = NomediaScanner.syntheticId("external:$uri"),
            uri = uri,
            displayName = name,
            mimeType = mime,
            dateTakenMs = System.currentTimeMillis(),
            dateModifiedMs = System.currentTimeMillis(),
            dateAddedMs = System.currentTimeMillis(),
            sizeBytes = size,
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
            durationMs = 0L,
            bucketId = AlbumIds.NONE,
            bucketName = "External",
            relativePath = "",
            isHidden = false,
            absolutePath = path,
        )
    }

    fun playInNextPlayer(context: Context, media: GalleryMedia): Boolean {
        if (blockIfDownloading(media)) return false
        if (media.absolutePath?.endsWith(SecureFolderManager.ENC_EXTENSION) == true) {
            openMedia(media)
            return true
        }
        val now = SystemClock.elapsedRealtime()
        if (media.id == lastExternalPlayId && now - lastExternalPlayAt < 1_200L) return true
        lastExternalPlayId = media.id
        lastExternalPlayAt = now
        val playlist = when {
            uiState.value.searchFiles.isNotEmpty() -> uiState.value.searchFiles.filter { it.isVideo }
            uiState.value.phoneFolderId != AlbumIds.NONE -> uiState.value.visibleFilesIn(uiState.value.phoneFolderId).filter { it.isVideo }
            else -> uiState.value.visibleFilesIn(media.bucketId).filter { it.isVideo }
        }
        val ok = NextPlayerClient.play(context, media, playlist)
        if (!ok) {
            lastExternalPlayId = 0L
            if (!NextPlayerClient.isInstalled(context)) {
                Toast.makeText(context, R.string.next_player_missing, Toast.LENGTH_LONG).show()
            } else {
                openMedia(media)
            }
        }
        return ok
    }

    /**
     * Each share gets its own folder in the share cache: two shares of the same file (or a share
     * and a wallpaper) wrote and deleted one common copy under each other. "p_" folders hold
     * decrypted private files and are kept for a shorter time.
     */
    private fun newShareDir(context: Context, private: Boolean): File =
        File(File(context.cacheDir, "share_clean"), (if (private) "p_" else "s_") + java.util.UUID.randomUUID().toString().take(12))
            .apply { mkdirs() }

    private fun cleanOldShareCache(context: Context) {
        runCatching {
            val shareDir = File(context.cacheDir, "share_clean")
            if (shareDir.exists()) {
                val now = System.currentTimeMillis()
                shareDir.listFiles()?.forEach { entry ->
                    // The receiving app may still be reading a share; a plain copy of a private
                    // file is not left for long.
                    val keepMs = if (entry.name.startsWith("p_")) 15 * 60 * 1000L else 2 * 3600 * 1000L
                    if (now - entry.lastModified() > keepMs) {
                        entry.deleteRecursively()
                    }
                }
            }
        }
    }

    /**
     * What to hand to the receiving app, or null when the file can't be shared as promised (a
     * private file that can't be decrypted, metadata that can't be removed).
     */
    private fun resolveShareUri(context: Context, media: GalleryMedia): Uri? {
        // Documents and other files have no photo metadata (a re-encode would turn them into JPEGs).
        val stripExif = store.settings.value.stripExifOnShare && !media.isVideo && !media.isOtherFile
        val opDir = newShareDir(context, media.isEncrypted)
        val srcFile = if (media.isEncrypted) {
            decryptedCopy(context, media, opDir) ?: return null
        } else {
            media.absolutePath?.let { File(it) }
                ?: (if (media.uri.scheme == "file") media.uri.path?.let { File(it) } else null)
                ?: repository.fileForMedia(media)
        }

        if (stripExif) {
            try {
                val shareDir = opDir
                val safeName = dev.ander.alexet.data.sanitizeFileName(media.displayName.ifBlank { "share_item" })
                val tempFile = File(shareDir, safeName)
                if (!tempFile.canonicalPath.startsWith(shareDir.canonicalPath)) {
                    throw SecurityException("Path traversal attempt in share temp file: ${tempFile.path}")
                }
                val copied = if (srcFile != null && srcFile.canonicalPath == tempFile.canonicalPath) {
                    true // already a private copy (decrypted private file)
                } else if (srcFile != null && srcFile.exists()) {
                    srcFile.copyTo(tempFile, overwrite = true)
                    true
                } else {
                    context.contentResolver.openInputStream(media.uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                        true
                    } ?: false
                }
                if (copied && tempFile.exists()) {
                    val shared = if (ExifUtils.stripExifExceptDate(tempFile)) {
                        tempFile
                    } else if (tempFile.extension.lowercase() in setOf("gif", "bmp")) {
                        // No EXIF in these formats; re-encoding would turn an animation into a still JPEG.
                        tempFile
                    } else {
                        // Tags can't be rewritten in this format (HEIC, RAW...): share a clean JPEG
                        // rather than the file with its location still in it.
                        val jpeg = File(shareDir, "${safeName.substringBeforeLast('.')}.jpg")
                        ExifUtils.reencodeWithoutMetadata(tempFile, jpeg)?.also { tempFile.delete() }
                    }
                    if (shared != null) {
                        return androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            shared,
                        )
                    }
                    tempFile.delete()
                }
                // Sharing the original would send the location the user asked to remove.
                dev.ander.alexet.data.AppLog.w("GalleryViewModel", "Could not remove metadata from ${media.displayName}; not shared")
                return null
            } catch (e: Exception) {
                dev.ander.alexet.data.AppLog.w("GalleryViewModel", "Failed to create stripped share copy", e)
                return null
            }
        }

        return if (srcFile != null && srcFile.exists()) {
            runCatching {
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", srcFile)
            }.getOrDefault(media.uri)
        } else {
            media.uri
        }
    }

    fun share(media: GalleryMedia) {
        AppLog.event("Action") { "share id=${media.id} ${media.displayName}" }
        if (blockIfDownloading(media)) return
        val context = getApplication<Application>()
        viewModelScope.launch {
            val shareUri = withContext(Dispatchers.IO) {
                cleanOldShareCache(context)
                resolveShareUri(context, media)
            }
            if (shareUri == null) {
                toast(R.string.share_prepare_failed)
                return@launch
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = media.mimeType.ifBlank { if (media.isVideo) "video/*" else "image/*" }
                putExtra(Intent.EXTRA_STREAM, shareUri)
                clipData = android.content.ClipData.newRawUri(media.displayName, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.share))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { e ->
                dev.ander.alexet.data.AppLog.w("GalleryViewModel", "Failed to start share chooser", e)
            }
        }
    }

    fun openAppSettings() {
        val context = getApplication<Application>()
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { e ->
            dev.ander.alexet.data.AppLog.w("GalleryViewModel", "Failed to open app settings", e)
        }
    }

    fun setAsWallpaper(context: Context, media: GalleryMedia, which: Int, onDone: () -> Unit = {}) {
        if (withoutOtherFiles(listOf(media)).isEmpty()) {
            onDone()
            return
        }
        if (blockIfDownloading(media)) {
            onDone()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var plainCopy: File? = null
            val ok = runCatching {
                val wallpaperManager = WallpaperManager.getInstance(context)
                val inputStream = if (media.isEncrypted) {
                    decryptedCopy(context, media)?.also { plainCopy = it }?.let { plain -> plain.inputStream() }
                } else if (media.absolutePath != null) {
                    File(media.absolutePath).inputStream()
                } else {
                    context.contentResolver.openInputStream(media.uri)
                }
                inputStream?.use { stream ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wallpaperManager.setStream(stream, null, true, which)
                    } else {
                        wallpaperManager.setStream(stream)
                    }
                    true
                } ?: false
            }.getOrDefault(false)
            // The plain copy of a private photo is not needed once the system has it.
            plainCopy?.parentFile?.deleteRecursively()

            withContext(Dispatchers.Main) {
                onDone()
                Toast.makeText(
                    context,
                    if (ok) R.string.wallpaper_set_success else R.string.wallpaper_set_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /**
     * Decode [media] (EXIF-aware), crop to [result], scale to output size, then set wallpaper.
     * Shows [R.string.setting_wallpaper] while applying.
     */
    fun setAsWallpaperCropped(
        context: Context,
        media: GalleryMedia,
        which: Int,
        result: WallpaperCropResult,
        onDone: () -> Unit = {},
    ) {
        if (blockIfDownloading(media)) {
            onDone()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.setting_wallpaper, Toast.LENGTH_SHORT).show()
            }
            val ok = runCatching {
                val file = if (media.isEncrypted) {
                    decryptedCopy(context, media) ?: return@runCatching false
                } else {
                    media.absolutePath?.let { File(it) }?.takeIf { it.exists() }
                }
                val source = try {
                    ImageEditEngine.decodeSourceBitmap(
                        context = context,
                        sourceFile = file,
                        sourceUri = media.uri,
                        maxDim = 4096,
                    )
                } finally {
                    // Decoded: the plain copy of a private photo goes right away.
                    if (media.isEncrypted) file?.parentFile?.deleteRecursively()
                } ?: return@runCatching false

                try {
                    val crop = result.crop
                    val safeW = crop.width.coerceAtLeast(1).coerceAtMost(source.width - crop.left.coerceAtLeast(0))
                    val safeH = crop.height.coerceAtLeast(1).coerceAtMost(source.height - crop.top.coerceAtLeast(0))
                    val left = crop.left.coerceIn(0, source.width - 1)
                    val top = crop.top.coerceIn(0, source.height - 1)
                    val width = safeW.coerceAtMost(source.width - left)
                    val height = safeH.coerceAtMost(source.height - top)
                    if (width <= 0 || height <= 0) return@runCatching false

                    val cropped = Bitmap.createBitmap(source, left, top, width, height)
                    val outW = result.outputWidth.coerceAtLeast(1)
                    val outH = result.outputHeight.coerceAtLeast(1)
                    val scaled = if (cropped.width == outW && cropped.height == outH) {
                        cropped
                    } else {
                        Bitmap.createScaledBitmap(cropped, outW, outH, true).also {
                            if (it != cropped && !cropped.isRecycled) cropped.recycle()
                        }
                    }

                    try {
                        val wallpaperManager = WallpaperManager.getInstance(context)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            wallpaperManager.setBitmap(scaled, null, true, which)
                        } else {
                            @Suppress("DEPRECATION")
                            wallpaperManager.setBitmap(scaled)
                        }
                        true
                    } finally {
                        if (!scaled.isRecycled) scaled.recycle()
                    }
                } finally {
                    if (!source.isRecycled) source.recycle()
                }
            }.getOrDefault(false)

            withContext(Dispatchers.Main) {
                onDone()
                Toast.makeText(
                    context,
                    if (ok) R.string.wallpaper_set_success else R.string.wallpaper_set_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun openSetAsChooser(context: Context, media: GalleryMedia) {
        viewModelScope.launch {
            val shareUri = withContext(Dispatchers.IO) { resolveShareUri(context, media) }
            if (shareUri == null) {
                toast(R.string.share_prepare_failed)
                return@launch
            }
            val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                setDataAndType(shareUri, media.mimeType.ifBlank { "image/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("mimeType", media.mimeType.ifBlank { "image/*" })
            }
            val chooser = Intent.createChooser(intent, context.getString(R.string.set_as)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(chooser) }
        }
    }

    fun setFileSelection(ids: List<Long>) {
        interaction.update { it.withFileSelection(ids) }
    }

    fun setFileSelection(ids: Set<Long>) {
        // Preserve encounter order when the set is a LinkedHashSet from prior selection.
        setFileSelection(ids.toList())
    }

    fun toggleAlbumSelected(id: Long) {
        if (FolderDeleteQueue.isDeleting(id)) return
        interaction.update { ix ->
            val next = ix.selectedAlbumIds.toMutableSet()
            if (!next.add(id)) next.remove(id)
            ix.copy(
                selectedAlbumIds = next,
                selectedFileIds = emptySet(),
                selectedFileIdsOrdered = emptyList(),
                stampedAlbumIds = emptySet(),
            )
        }
    }

    fun setAlbumSelection(ids: Set<Long>) {
        val deleting = FolderDeleteQueue.jobs.value.keys
        interaction.update {
            it.copy(
                selectedAlbumIds = if (deleting.isEmpty()) ids else ids - deleting,
                selectedFileIds = emptySet(),
                selectedFileIdsOrdered = emptyList(),
                stampedAlbumIds = emptySet(),
            )
        }
    }

    fun setAlbumSelection(ids: List<Long>) {
        setAlbumSelection(ids.toSet())
    }

    fun selectAllVisible() {
        val state = uiState.value
        if (state.selectedAlbumIds.isNotEmpty() ||
            (state.selectedFileIds.isEmpty() && state.phoneFolderId == AlbumIds.NONE && state.searchFiles.isEmpty())
        ) {
            setAlbumSelection(state.visibleAlbums.map { it.id }.toSet())
        } else {
            val ids = when {
                state.searchFiles.isNotEmpty() -> state.searchFiles
                state.phoneFolderId != AlbumIds.NONE -> state.visibleFilesIn(state.phoneFolderId)
                state.sortTargetAlbumId != AlbumIds.NONE -> state.visibleFilesIn(state.sortTargetAlbumId)
                else -> state.media
            }.map { it.id }
            setFileSelection(ids)
        }
    }

    fun setSortField(field: SortField) {
        val albumId = uiState.value.sortTargetAlbumId
        if (field == SortField.RANDOM) {
            interaction.update { it.copy(randomSeed = System.nanoTime()) }
        }
        applySort(albumId) { it.copy(field = field) }
    }

    fun setSortOrder(order: SortOrder) {
        val albumId = uiState.value.sortTargetAlbumId
        applySort(albumId) { it.copy(order = order) }
    }

    fun setFolderSortOnly(only: Boolean) {
        val albumId = uiState.value.sortTargetAlbumId
        if (albumId == AlbumIds.NONE) return
        val legacy = albumLegacyIds(albumId)
        store.update { settings ->
            val spec = settings.sortFor(albumId, legacy)
            if (only) settings.copy(folderSorts = settings.folderSorts - albumId - legacy + (albumId to spec))
            else settings.copy(folderSorts = settings.folderSorts - albumId - legacy)
        }
    }

    /** [change] is applied to the sort as it is when written (two quick taps don't undo each other). */
    private fun applySort(albumId: Long, change: (SortSpec) -> SortSpec) {
        val legacy = albumLegacyIds(albumId)
        store.update { settings ->
            val spec = change(settings.sortFor(albumId, legacy))
            if (settings.hasFolderSort(albumId, legacy)) {
                settings.copy(folderSorts = settings.folderSorts - albumId - legacy + (albumId to spec))
            } else {
                settings.copy(sort = spec)
            }
        }
    }

    fun applySortDraft(spec: SortSpec, folderOnly: Boolean, forceSort: Boolean = false) {
        val albumId = uiState.value.sortTargetAlbumId
        if (spec.field == SortField.RANDOM) {
            interaction.update { it.copy(randomSeed = System.nanoTime()) }
        }
        val legacy = albumLegacyIds(albumId)
        store.update { settings ->
            var next = when {
                folderOnly && albumId != AlbumIds.NONE ->
                    settings.copy(folderSorts = settings.folderSorts - albumId - legacy + (albumId to spec))
                albumId != AlbumIds.NONE && settings.hasFolderSort(albumId, legacy) && !folderOnly ->
                    settings.copy(sort = spec, folderSorts = settings.folderSorts - albumId - legacy)
                else -> settings.copy(sort = spec)
            }
            if (forceSort) {
                val orders = next.folderContentOrders.toMutableMap()
                if (albumId == AlbumIds.NONE) {
                    orders.clear()
                } else {
                    orders.remove(albumId)
                    legacy.forEach { orders.remove(it) }
                }
                next = next.copy(folderContentOrders = orders)
            }
            next
        }
    }

    fun setScanHidden(enabled: Boolean) {
        repository.setScanHidden(enabled || pickerHiddenHold)
    }

    fun setShowHidden(value: Boolean) {
        store.update { it.copy(showHidden = value) }
        if (value) {
            interaction.update { it.copy(revealHidden = false) }
            // Turned on by hand: asked again even when it was refused earlier in this run.
            askedAllFilesThisRun = false
        }
        repository.setScanHidden(value || interaction.value.revealHidden)
    }

    /** Asked for all-files access once in this run (a refusal does not bring the screen back on each change). */
    private var askedAllFilesThisRun = false

    /**
     * Hidden folders are shown but Alexet can't read them (no all-files access): the system screen
     * is opened, once per run. Called whenever hidden folders get shown, however they did: the
     * switch, a moment's reveal, a settings import, a start with them already on.
     */
    fun requestAllFilesForHidden() {
        if (StorageAccess.canReadAllFiles() || askedAllFilesThisRun) return
        askedAllFilesThisRun = true
        StorageAccess.requestAllFiles(getApplication())
        toast(R.string.all_files_needed)
    }

    fun toggleRevealHidden() {
        interaction.update { it.copy(revealHidden = !it.revealHidden) }
        repository.setScanHidden(
            store.settings.value.showHidden || interaction.value.revealHidden,
        )
    }

    fun refreshMedia() {
        repository.onAllFilesAccessMaybeChanged()
        repository.requestRefresh(scanDisk = true, force = false)
        // Coming back to the app counts as entering the folders on screen again.
        shownFolderIds().forEach { scheduleQuickScan(it) }
    }

    private fun scheduleQuickScan(folderId: Long) {
        if (folderId == AlbumIds.NONE || folderId == AlbumIds.TRASH || folderId == AlbumIds.FAVORITES) return
        quickScanJobs.remove(folderId)?.cancel()
        quickScanJobs[folderId] = viewModelScope.launch {
            // At once: an unchanged folder costs one stat now, not a listing (it waited 300 ms so
            // that a mis-tap started nothing).
            repository.quickScanFolder(folderId)
        }
    }

    fun startForegroundWatch() {
        repository.startForegroundWatch()
    }

    fun stopForegroundWatch() {
        repository.stopForegroundWatch()
    }

    fun ensureNomediaAccess() {
        repository.setScanHidden(true)
        if (!StorageAccess.canReadAllFiles()) {
            StorageAccess.requestAllFiles(getApplication())
            toast(R.string.all_files_needed)
        }
    }

    fun hideSelection() {
        AppLog.event("Action") { "hideSelection " + "selected=${uiState.value.selectionCount}" }
        val state = uiState.value
        val albums = state.selectedAlbums
        if (albums.isNotEmpty()) {
            if (!StorageAccess.canReadAllFiles()) ensureNomediaAccess()
            viewModelScope.launch { repository.hideAlbumsWithNomedia(albums) }
        }
        store.update {
            it.copy(
                hiddenMediaIds = it.hiddenMediaIds + state.selectedFileIds,
                hiddenAlbumIds = it.hiddenAlbumIds + state.selectedAlbumIds,
            )
        }
        clearSelection()
        toast(R.string.hidden_done)
    }

    fun unhideSelection() {
        AppLog.event("Action") { "unhideSelection " + "selected=${uiState.value.selectionCount}" }
        val state = uiState.value
        val albums = state.selectedAlbums
        if (albums.isNotEmpty()) {
            viewModelScope.launch { repository.unhideAlbumsNomedia(albums) }
        }
        val albumsForLegacy = albums.ifEmpty { state.allAlbums.filter { it.id in state.selectedAlbumIds } }
        val idsToUnhide = state.selectedAlbumIds + albumIdsIncludingLegacy(albumsForLegacy)
        store.update {
            it.copy(
                hiddenMediaIds = it.hiddenMediaIds - state.selectedFileIds,
                hiddenAlbumIds = it.hiddenAlbumIds - idsToUnhide,
            )
        }
        clearSelection()
    }

    fun toggleFavoriteSelection() {
        AppLog.event("Action") { "toggleFavoriteSelection " + "selected=${uiState.value.selectionCount}" }
        val state = uiState.value
        // Favorites are photos and videos (the Favorites folder lists library files).
        val otherIds = state.selectedFiles.filter { it.isOtherFile }.mapTo(HashSet()) { it.id }
        val selectedIds = state.selectedFileIds - otherIds
        if (selectedIds.isEmpty()) {
            if (otherIds.isNotEmpty()) toast(R.string.other_file_action_unavailable)
            return
        }
        // From the stored settings inside the update: the UI state copy can lag behind a star
        // toggled just before.
        store.update { current ->
            val currentFavs = current.favoriteMediaIds
            val allSelectedAreFav = selectedIds.all { it in currentFavs }
            val newFavs = if (allSelectedAreFav) {
                currentFavs.filter { it !in selectedIds }
            } else {
                currentFavs + selectedIds.filter { it !in currentFavs }
            }
            current.copy(favoriteMediaIds = newFavs)
        }
        clearSelection()
    }

    fun toggleFavorite(mediaId: Long) {
        AppLog.event("Action") { "toggleFavorite id=$mediaId" }
        store.update { current ->
            val currentFavs = current.favoriteMediaIds
            current.copy(favoriteMediaIds = if (mediaId in currentFavs) currentFavs - mediaId else currentFavs + mediaId)
        }
    }

    fun isFavorite(id: Long): Boolean = id in store.settings.value.favoriteMediaIds

    fun setHideFavoritesFolder(hide: Boolean) {
        store.update { it.copy(hideFavoritesFolder = hide) }
    }

    fun setBarActionSlots(slots: List<SelectionAction?>) {
        val padded = (slots + List(6) { null }).take(6)
        store.update { it.copy(barActionSlots = padded) }
    }

    fun setMenuActionOrder(order: List<SelectionAction>) {
        store.update { it.copy(menuActionOrder = order) }
    }

    fun setSelectionActionsConfig(
        barSlots: List<SelectionAction?>,
        menuOrder: List<SelectionAction>,
        hidden: Set<SelectionAction>,
    ) {
        val padded = (barSlots + List(6) { null }).take(6)
        store.update {
            it.copy(
                barActionSlots = padded,
                menuActionOrder = menuOrder,
                hiddenSelectionActions = hidden,
            )
        }
    }

    fun toggleSelectionActionVisibility(action: SelectionAction) {
        store.update {
            val current = it.hiddenSelectionActions
            val updated = if (action in current) current - action else current + action
            it.copy(hiddenSelectionActions = updated)
        }
    }

    fun refreshCurrentContext() {
        viewModelScope.launch {
            refreshesRunning++
            _isRefreshing.value = true
            try {
                val state = uiState.value
                val phoneFolderId = state.phoneFolderId
                val isInsidePhoneFolder = phoneFolderId != AlbumIds.NONE && phoneFolderId != AlbumIds.TRASH && phoneFolderId != AlbumIds.FAVORITES
                val openFolders = state.openFolderIds
                val activeFolderId = if (isInsidePhoneFolder) {
                    phoneFolderId
                } else if (openFolders.isNotEmpty()) {
                    state.sessionFolderId.takeIf { it in openFolders } ?: openFolders.firstOrNull() ?: AlbumIds.NONE
                } else {
                    AlbumIds.NONE
                }

                if (activeFolderId != AlbumIds.NONE && activeFolderId != AlbumIds.TRASH && activeFolderId != AlbumIds.FAVORITES) {
                    // Half scan: a close look at the part on screen, or the whole folder when small.
                    // The list off the main thread: not yet worked out, it filters and sorts.
                    val files = withContext(Dispatchers.Default) { state.visibleFilesIn(activeFolderId) }
                    val focus = if (files.size < HALF_SCAN_WHOLE_BELOW) {
                        files
                    } else {
                        val first = (firstVisibleIndex[activeFolderId] ?: 0).coerceIn(0, files.size)
                        files.subList(
                            (first - HALF_SCAN_BEFORE).coerceAtLeast(0),
                            (first + HALF_SCAN_AFTER).coerceAtMost(files.size),
                        )
                    }
                    repository.halfScanFolder(activeFolderId, focus)
                    delay(650L)
                } else {
                    repository.refresh(rereadHidden = true)
                    delay(650L)
                }
            } finally {
                refreshesRunning--
                if (refreshesRunning <= 0) {
                    refreshesRunning = 0
                    _isRefreshing.value = false
                }
            }
        }
    }

    fun shareSelection(context: Context) {
        AppLog.event("Action") { "shareSelection " + "selected=${uiState.value.selectionCount}" }
        val items = excludeDownloading(selectedMedia())
        if (items.isEmpty()) return
        if (items.size == 1) {
            share(items.first())
            return
        }
        viewModelScope.launch {
            _transferProgress.value = TransferProgress(0, items.size)
            val uris = withContext(Dispatchers.IO) {
                cleanOldShareCache(context)
                val list = ArrayList<Uri>(items.size)
                items.forEachIndexed { index, item ->
                    resolveShareUri(context, item)?.let(list::add)
                    _transferProgress.value = TransferProgress(index + 1, items.size)
                }
                list
            }
            _transferProgress.value = null
            if (uris.size < items.size) toast(R.string.share_prepare_failed)
            if (uris.isEmpty()) return@launch
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                if (uris.isNotEmpty()) {
                    val clip = android.content.ClipData.newRawUri("Media", uris.first())
                    for (i in 1 until uris.size) {
                        clip.addItem(android.content.ClipData.Item(uris[i]))
                    }
                    clipData = clip
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.share))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { e ->
                dev.ander.alexet.data.AppLog.w("GalleryViewModel", "Failed to start shareSelection chooser", e)
            }
        }
    }

    fun renameSelection(request: RenameRequest, launchWrite: (android.content.IntentSender) -> Unit) {
        AppLog.event("Action") { "renameSelection " + "selected=${uiState.value.selectionCount}" + " request=$request" }
        val state = uiState.value
        val rawItems = state.selectedFiles
        if (rawItems.isEmpty()) return
        if (blockIfPrivate(rawItems)) return

        // %i / rename numbering must follow selection order (tap/marquee), not grid sort.
        // selectedFileIdsOrdered is the encounter order; only fall back to visible order
        // when that list is empty.
        val byId = rawItems.associateBy { it.id }
        val orderedIds = state.selectedFileIdsOrdered.filter { it in state.selectedFileIds }
        val items = excludeDownloading(
            if (orderedIds.isNotEmpty()) {
                orderedIds.mapNotNull { byId[it] }
            } else {
                val visibleList = when {
                    state.phoneFolderId != AlbumIds.NONE -> state.visibleFilesIn(state.phoneFolderId)
                    state.searchMode.searchesFiles && state.query.isNotBlank() -> state.searchFiles
                    else -> sortMedia(state.media, state.settings.sort, state.randomSeed)
                }
                val visibleIdOrder = visibleList.withIndex().associate { it.value.id to it.index }
                rawItems.sortedWith(compareBy { visibleIdOrder[it.id] ?: Int.MAX_VALUE })
            },
        )
        if (items.isEmpty()) return

        if (request is RenameRequest.Swap) {
            if (items.size != 2) return
            val (a, b) = items[0] to items[1]
            viewModelScope.launch {
                val ok = repository.swapDisplayNames(a, b)
                if (!ok) {
                    val sender = repository.createWriteRequest(listOf(a, b))
                    if (sender != null) {
                        pendingAfterWrite = {
                            viewModelScope.launch {
                                repository.swapDisplayNames(a, b)
                                clearSelection()
                                repository.refresh()
                            }
                        }
                        launchWrite(sender)
                        return@launch
                    }
                    toast(R.string.rename_failed)
                }
                clearSelection()
                repository.refresh()
            }
            return
        }

        if (request is RenameRequest.ReverseOrder) {
            if (items.size < 2) return
            val pairs = buildList {
                var i = 0
                val n = items.size
                while (i < n / 2) {
                    add(items[i] to items[n - 1 - i])
                    i++
                }
            }
            viewModelScope.launch {
                var firstFailed = -1
                for ((index, pair) in pairs.withIndex()) {
                    if (!repository.swapDisplayNames(pair.first, pair.second)) {
                        firstFailed = index
                        break
                    }
                }
                if (firstFailed >= 0) {
                    // Pairs before firstFailed are swapped already: swapping them again would undo
                    // them. Consent covers every file still to be renamed.
                    val remaining = pairs.drop(firstFailed)
                    val sender = repository.createWriteRequest(remaining.flatMap { listOf(it.first, it.second) }.distinctBy { it.id })
                    if (sender != null) {
                        pendingAfterWrite = {
                            viewModelScope.launch {
                                for ((a, b) in remaining) {
                                    repository.swapDisplayNames(a, b)
                                }
                                clearSelection()
                                repository.refresh()
                            }
                        }
                        launchWrite(sender)
                        return@launch
                    }
                    toast(R.string.rename_failed)
                }
                clearSelection()
                repository.refresh()
            }
            return
        }

        if (request is RenameRequest.Pattern && request.pattern.contains("%i")) {
            viewModelScope.launch {
                val (successCount, failed) = repository.renamePatternTwoPhase(items, request)
                if (failed.isNotEmpty()) {
                    val sender = repository.createWriteRequest(failed)
                    if (sender != null) {
                        pendingAfterWrite = {
                            viewModelScope.launch {
                                if (repository.hasPendingPatternRenames()) {
                                    // Phase 2 failed: same final names, numbering unchanged.
                                    repository.retryPendingPatternRenames()
                                } else {
                                    // Phase 1 failed and was rolled back: start over.
                                    repository.renamePatternTwoPhase(failed, request)
                                }
                                clearSelection()
                                repository.refresh()
                            }
                        }
                        pendingWriteDenied = {
                            viewModelScope.launch { repository.abandonPendingPatternRenames() }
                        }
                        launchWrite(sender)
                        return@launch
                    } else {
                        repository.abandonPendingPatternRenames()
                        if (successCount == 0) toast(R.string.rename_failed)
                    }
                }
                clearSelection()
                repository.refresh()
            }
            return
        }

        val padDigits = maxOf(3, items.size.toString().length)
        val itemsWithNewNames = items.mapIndexed { idx, item ->
            item to request.applyTo(item, idx + 1, padDigits)
        }.filter { (item, newName) ->
            newName.isNotBlank() && newName != item.displayName
        }

        if (itemsWithNewNames.isEmpty()) {
            clearSelection()
            return
        }

        viewModelScope.launch(fileOperationErrors(R.string.rename_failed)) {
            val (successCount, failed) = repository.renameAll(itemsWithNewNames)
            if (failed.isNotEmpty()) {
                val sender = repository.createWriteRequest(failed)
                if (sender != null) {
                    pendingAfterWrite = {
                        viewModelScope.launch {
                            val remaining = failed.mapIndexed { idx, item ->
                                val originalIndex = items.indexOf(item).takeIf { it >= 0 } ?: idx
                                item to request.applyTo(item, originalIndex + 1, padDigits)
                            }
                            repository.renameAll(remaining)
                            clearSelection()
                            repository.refresh()
                        }
                    }
                    launchWrite(sender)
                    return@launch
                } else if (successCount == 0) {
                    toast(R.string.rename_failed)
                }
            }
            clearSelection()
            repository.refresh()
        }
    }

    fun stripTildePriority(launchWrite: ((android.content.IntentSender) -> Unit)? = null) {
        val state = uiState.value
        if (state.phoneFolderId == AlbumIds.NONE) {
            toast(R.string.strip_priority_no_files)
            return
        }
        val folderItems = state.visibleFilesIn(state.phoneFolderId)
        if (folderItems.isEmpty()) {
            toast(R.string.strip_priority_no_files)
            return
        }
        if (blockIfPrivate(folderItems)) return

        val allFileNames = folderItems.map { it.displayName }
        val plan = PriorityRolloffHelper.computeRolloffPlan(allFileNames)
        if (plan.isEmpty()) {
            toast(R.string.strip_priority_no_files)
            return
        }

        val fileMap = folderItems.associateBy { it.displayName }
        val renamePairs: List<Pair<GalleryMedia, String>> = plan.mapNotNull { pair ->
            val media = fileMap[pair.first] ?: return@mapNotNull null
            media to pair.second
        }

        if (renamePairs.isEmpty()) {
            toast(R.string.strip_priority_no_files)
            return
        }

        viewModelScope.launch {
            val itemsToRename = renamePairs.map { pair -> pair.first }
            val sender = repository.createWriteRequest(itemsToRename)
            if (sender != null && launchWrite != null) {
                pendingAfterWrite = {
                    viewModelScope.launch {
                        val (success, failed) = repository.executeRolloffRename(renamePairs)
                        repository.refresh()
                        if (success > 0) {
                            toast(R.string.strip_priority_success, success)
                        } else if (failed.isNotEmpty()) {
                            toast(R.string.rename_failed)
                        }
                    }
                }
                launchWrite(sender)
                return@launch
            }

            val (success, failed) = repository.executeRolloffRename(renamePairs)
            repository.refresh()
            if (success > 0) {
                toast(R.string.strip_priority_success, success)
            } else if (failed.isNotEmpty()) {
                toast(R.string.rename_failed)
            }
        }
    }

    fun renameAlbum(album: Album, newName: String) {
        AppLog.event("Action") { "renameAlbum ${album.id} ${album.name} -> $newName" }
        viewModelScope.launch {
            val ok = repository.renameAlbum(album, newName)
            if (ok) {
                val parent = album.relativePath.trim().trim('/').substringBeforeLast('/', "")
                val newRel = if (parent.isEmpty()) newName.trim() else "$parent/${newName.trim()}"
                store.moveAllFilesFolder(album.id, NomediaScanner.syntheticBucketId(newRel))
                toast(R.string.folder_renamed)
                clearSelection()
            } else {
                toast(R.string.rename_folder_failed)
            }
        }
    }

    fun copySelectionToAlbum(albumId: Long) {
        AppLog.event("Action") { "copySelectionToAlbum album=$albumId " + "selected=${uiState.value.selectionCount}" }
        val dest = resolveAlbum(albumId)
        if (dest == null) {
            clearSelection()
            return
        }
        copyToDestinations(listOf(dest))
    }

    fun moveSelectionToAlbum(albumId: Long, launchWrite: (android.content.IntentSender) -> Unit) {
        AppLog.event("Action") { "moveSelectionToAlbum album=$albumId " + "selected=${uiState.value.selectionCount}" }
        val dest = resolveAlbum(albumId)
        if (dest == null) {
            clearSelection()
            return
        }
        val items = selectedMedia().filter { it.bucketId != dest.id }
        if (items.isEmpty()) {
            clearSelection()
            return
        }
        startMove(items, dest, launchWrite)
    }

    fun copySelectionToTree(treeUri: Uri) {
        AppLog.event("Action") { "copySelectionToTree $treeUri " + "selected=${uiState.value.selectionCount}" }
        repository.persistTreePermission(treeUri)
        val dest = repository.destinationFromTree(treeUri, uiState.value.allAlbums)
        if (dest.useMediaStore) {
            copyToDestinations(listOf(dest.album))
            return
        }
        val items = excludeDownloading(selectedMedia())
        if (items.isEmpty()) return
        if (blockIfPrivate(items)) return
        clearSelection()
        viewModelScope.launch(fileOperationErrors(R.string.copy_failed) { notifier.showFailed(isMove = false) }) {
            try {
                _transferProgress.value = TransferProgress(current = 0, total = items.size, isMove = false)
                notifier.showProgress(0, items.size, isMove = false)
                val copied = repository.copyToDocumentTree(items, treeUri) { cur, tot ->
                    _transferProgress.value = TransferProgress(current = cur, total = tot, isMove = false)
                    notifier.showProgress(cur, tot, isMove = false)
                }
                if (copied.isEmpty()) {
                    toast(R.string.copy_failed)
                    notifier.showFailed(isMove = false)
                } else {
                    if (copied.size < items.size) {
                        toast(R.string.copied_partial, copied.size, items.size)
                    } else {
                        toast(R.string.copied_count, copied.size)
                    }
                    _transferProgress.value = TransferProgress(current = copied.size, total = items.size, isMove = false, isDone = true)
                    notifier.showCompleted(copied.size, isMove = false)
                }
                repository.requestRefresh()
            } finally {
                _transferProgress.value = null
            }
        }
    }

    fun moveSelectionToTree(
        treeUri: Uri,
        launchWrite: (android.content.IntentSender) -> Unit,
        launchDelete: (android.content.IntentSender) -> Unit,
    ) {
        AppLog.event("Action") { "moveSelectionToTree " + "selected=${uiState.value.selectionCount}" }
        repository.persistTreePermission(treeUri)
        val dest = repository.destinationFromTree(treeUri, uiState.value.allAlbums)
        val items = selectedMedia()
        if (items.isEmpty()) return
        if (blockIfPrivate(items)) return
        if (dest.useMediaStore) {
            val moving = items.filter { it.bucketId != dest.album.id }
            if (moving.isEmpty()) return
            startMove(moving, dest.album, launchWrite)
            return
        }
        val ready = excludeDownloading(items)
        if (ready.isEmpty()) return
        clearSelection()
        viewModelScope.launch(fileOperationErrors(R.string.move_failed) { notifier.showFailed(isMove = true) }) {
            try {
                _transferProgress.value = TransferProgress(current = 0, total = ready.size, isMove = true)
                notifier.showProgress(0, ready.size, isMove = true)
                // Copy and removal of the originals finish together even when the app is left
                // meanwhile (Back cancels this scope): a cut here turned the move into a copy.
                val (copied, toDelete, files) = withContext(kotlinx.coroutines.NonCancellable) {
                    val copiedIds = repository.copyToDocumentTree(ready, treeUri) { cur, tot ->
                        _transferProgress.value = TransferProgress(current = cur, total = tot, isMove = true)
                        notifier.showProgress(cur, tot, isMove = true)
                    }
                    val done = ready.filter { it.id in copiedIds }
                    val withPath = done.filter { it.absolutePath != null }
                    if (withPath.isNotEmpty()) repository.deleteDirect(withPath)
                    Triple(copiedIds, done, withPath)
                }
                if (copied.isEmpty()) {
                    toast(R.string.move_failed)
                    notifier.showFailed(isMove = true)
                    return@launch
                }
                // Without all-files access a direct delete fails: those originals still exist and
                // go through the system delete consent like rows without a path, or the "move"
                // would silently be a copy.
                val notDeleted = withContext(Dispatchers.IO) {
                    files.filter { item -> item.absolutePath?.let { File(it).exists() } == true }
                }
                val storeItems = toDelete.filter { it.absolutePath == null } + notDeleted
                if (storeItems.isEmpty()) {
                    toast(R.string.moved_count, copied.size)
                    _transferProgress.value = TransferProgress(current = copied.size, total = ready.size, isMove = true, isDone = true)
                    notifier.showCompleted(copied.size, isMove = true)
                    repository.requestRefresh()
                    return@launch
                }
                val sender = repository.createDeleteRequest(storeItems)
                if (sender != null) {
                    pendingSafMoveDeleteToast = true
                    pendingAfterDelete = {
                        pendingSafMoveDeleteToast = false
                        toast(R.string.moved_count, copied.size)
                        viewModelScope.launch {
                            _transferProgress.value = TransferProgress(current = copied.size, total = ready.size, isMove = true, isDone = true)
                            notifier.showCompleted(copied.size, isMove = true)
                            _transferProgress.value = null
                            repository.requestRefresh()
                        }
                    }
                    launchDelete(sender)
                } else {
                    val deleted = repository.deleteDirect(storeItems)
                    val recoverable = repository.takeRecoverableSender()
                    if (recoverable != null) {
                        pendingSafMoveDeleteToast = true
                        pendingAfterDelete = {
                            pendingSafMoveDeleteToast = false
                            viewModelScope.launch {
                                repository.deleteDirect(storeItems)
                                toast(R.string.moved_count, copied.size)
                                _transferProgress.value = TransferProgress(current = copied.size, total = ready.size, isMove = true, isDone = true)
                                notifier.showCompleted(copied.size, isMove = true)
                                _transferProgress.value = null
                                repository.requestRefresh()
                            }
                        }
                        launchDelete(recoverable)
                        return@launch
                    }
                    if (deleted == 0) {
                        toast(R.string.move_failed)
                        notifier.showFailed(isMove = true)
                    } else {
                        toast(R.string.moved_count, copied.size)
                        _transferProgress.value = TransferProgress(current = copied.size, total = ready.size, isMove = true, isDone = true)
                        notifier.showCompleted(copied.size, isMove = true)
                    }
                    repository.requestRefresh()
                }
            } finally {
                if (pendingAfterDelete == null) {
                    _transferProgress.value = null
                }
            }
        }
    }

    fun prepareHiddenPicker() {
        pickerHiddenHold = true
        if (!StorageAccess.canReadAllFiles()) {
            StorageAccess.requestAllFiles(getApplication())
            toast(R.string.all_files_needed)
        }
        repository.setScanHidden(true)
    }

    fun releaseHiddenPicker() {
        pickerHiddenHold = false
        repository.setScanHidden(
            store.settings.value.showHidden || interaction.value.revealHidden,
        )
    }

    private fun resolveAlbum(id: Long): Album? {
        // Nothing goes into a folder that is being deleted.
        if (blockIfFolderDeleting(id)) return null
        val state = uiState.value
        return state.allAlbums.firstOrNull { it.id == id }
            ?: state.albums.firstOrNull { it.id == id }
            ?: repository.seedAlbums.value.firstOrNull { it.id == id }
    }

    private fun startMove(
        items: List<GalleryMedia>,
        dest: Album,
        launchWrite: (android.content.IntentSender) -> Unit,
    ) {
        if (dest.isSecure) {
            toast(R.string.cannot_move_to_secure_folder)
            clearSelection()
            return
        }
        if (blockIfPrivate(items)) return
        val ready = excludeDownloading(items)
        if (ready.isEmpty()) return
        clearSelection()
        viewModelScope.launch(fileOperationErrors(R.string.move_failed) { notifier.showFailed(isMove = true) }) {
            try {
                _transferProgress.value = TransferProgress(current = 0, total = ready.size, isMove = true)
                notifier.showProgress(0, ready.size, isMove = true)
                finishMove(ready, dest, launchWrite)
            } finally {
                if (pendingAfterWrite == null) {
                    _transferProgress.value = null
                }
            }
        }
    }

    /** Columns of the file grid on screen: moved files leave it a row at a time. Set by the UI. */
    @Volatile private var shownFileColumns = 1

    fun setShownFileColumns(columns: Int) {
        shownFileColumns = columns.coerceAtLeast(1)
    }

    private suspend fun finishMove(
        items: List<GalleryMedia>,
        dest: Album,
        launchWrite: (android.content.IntentSender) -> Unit,
    ) {
        val isAnon = dest.id in store.settings.value.anonymousFolderIds
        val randomize = store.settings.value.randomizeFileNamesInPrivateFolders
        val strip = store.settings.value.stripExifInPrivateFolders
        val followSelectionOrder = store.settings.value.operationsFollowSelectionOrder
        // Finished even when the app is left meanwhile (Back cancels this scope), like the move to
        // a folder on disk: a cut left part of the files moved and part not.
        val moved = withContext(kotlinx.coroutines.NonCancellable) {
            repository.moveToAlbum(
                items,
                dest,
                isAnonymous = isAnon,
                randomizeFileNames = randomize,
                stripExif = strip,
                stampSelectionOrderMtimes = followSelectionOrder,
                uiBatchSize = shownFileColumns,
            ) { cur, tot ->
                _transferProgress.value = TransferProgress(current = cur, total = tot, isMove = true)
                notifier.showProgress(cur, tot, isMove = true)
            }
        }
        // Files that started downloading during the move were skipped by moveToAlbum: they are
        // not asked about (consent would move a half-written file).
        val remaining = items.filter { it.id !in moved && !repository.isDownloading(it) }
        if (remaining.isNotEmpty()) {
            val sender = repository.createWriteRequest(remaining)
            if (sender != null) {
                if (moved.isNotEmpty()) {
                    bumpFolderCovers(items.filter { it.id in moved }.map { it.bucketId } + dest.id)
                }
                pendingMoveDeniedToast = true
                pendingAfterWrite = {
                    viewModelScope.launch(fileOperationErrors(R.string.move_failed) { notifier.showFailed(isMove = true) }) {
                        try {
                            _transferProgress.value = TransferProgress(current = moved.size, total = items.size, isMove = true)
                            notifier.showProgress(moved.size, items.size, isMove = true)
                            val retried = withContext(kotlinx.coroutines.NonCancellable) {
                                repository.moveToAlbum(
                                    remaining,
                                    dest,
                                    isAnonymous = isAnon,
                                    randomizeFileNames = randomize,
                                    stripExif = strip,
                                    stampSelectionOrderMtimes = store.settings.value.operationsFollowSelectionOrder,
                                    uiBatchSize = shownFileColumns,
                                ) { cur, _ ->
                                    val currentTotal = moved.size + cur
                                    _transferProgress.value = TransferProgress(current = currentTotal, total = items.size, isMove = true)
                                    notifier.showProgress(currentTotal, items.size, isMove = true)
                                }
                            }
                            if (retried.size < remaining.size) {
                                toast(R.string.move_failed)
                                notifier.showFailed(isMove = true)
                            } else {
                                _transferProgress.value = TransferProgress(current = items.size, total = items.size, isMove = true, isDone = true)
                                notifier.showCompleted(items.size, isMove = true)
                            }
                            bumpFolderCovers(remaining.map { it.bucketId } + dest.id)
                        } finally {
                            _transferProgress.value = null
                        }
                    }
                }
                launchWrite(sender)
                return
            }
            if (moved.isEmpty()) {
                toast(R.string.move_failed)
                notifier.showFailed(isMove = true)
            } else {
                toast(R.string.moved_count, moved.size)
                _transferProgress.value = TransferProgress(current = moved.size, total = items.size, isMove = true, isDone = true)
                notifier.showCompleted(moved.size, isMove = true)
            }
        } else {
            _transferProgress.value = TransferProgress(current = items.size, total = items.size, isMove = true, isDone = true)
            notifier.showCompleted(items.size, isMove = true)
        }
        bumpFolderCovers(items.map { it.bucketId } + dest.id)
    }

    private fun bumpFolderCovers(albumIds: Collection<Long>) {
        val ids = albumIds.filter { it != AlbumIds.NONE }.toSet()
        if (ids.isEmpty()) return
        folderCoverBumps.update { current ->
            val next = current.toMutableMap()
            ids.forEach { id -> next[id] = (next[id] ?: 0) + 1 }
            next
        }
    }

    fun onWriteConsented() {
        takeActiveConsent()
        onWriteConsentedInternal()
        showNextConsent()
    }

    fun onWriteDenied() {
        takeActiveConsent()
        onWriteDeniedInternal()
        showNextConsent()
    }

    private fun onWriteConsentedInternal() {
        pendingMoveDeniedToast = false
        pendingWriteDenied = null
        pendingAfterWrite?.invoke()
        pendingAfterWrite = null
    }

    private fun onWriteDeniedInternal() {
        pendingAfterWrite = null
        pendingWriteDenied?.invoke()
        pendingWriteDenied = null
        _transferProgress.value = null
        cancelContentOrderMode()
        notifier.cancel()
        if (pendingMoveDeniedToast) {
            pendingMoveDeniedToast = false
            toast(R.string.move_failed)
        }
    }

    fun openTrash(wide: Boolean = false) {
        val mode = store.settings.value.contentViewMode
        if (wide) {
            when (mode) {
                ContentViewMode.MULTI_FOLDER -> {
                    openOnRight(AlbumIds.TRASH)
                }
                ContentViewMode.CLICK_DRAG -> {
                    interaction.update { it.copy(phoneFolderId = AlbumIds.NONE) }
                    applyClickDragSlot(AlbumIds.TRASH, SLOT_MIDDLE, clicked = true)
                }
                else -> {
                    interaction.update {
                        it.copy(
                            phoneFolderId = AlbumIds.NONE,
                            sessionFolderId = AlbumIds.TRASH,
                            openFolderIds = emptyList(),
                            clickedFolderId = AlbumIds.NONE,
                            pendingHitboxAlbumId = AlbumIds.NONE,
                            highlightedFolderId = AlbumIds.NONE,
                            selectedFileIds = emptySet(),
                            selectedFileIdsOrdered = emptyList(),
                            selectedAlbumIds = emptySet(),
                            stampedAlbumIds = emptySet(),
                        )
                    }
                }
            }
        } else {
            interaction.update {
                it.copy(
                    phoneFolderId = AlbumIds.TRASH,
                    sessionFolderId = AlbumIds.TRASH,
                    openFolderIds = emptyList(),
                    clickedFolderId = AlbumIds.NONE,
                    pendingHitboxAlbumId = AlbumIds.NONE,
                    highlightedFolderId = AlbumIds.NONE,
                    selectedFileIds = emptySet(),
                    selectedFileIdsOrdered = emptyList(),
                    selectedAlbumIds = emptySet(),
                    stampedAlbumIds = emptySet(),
                )
            }
        }
    }

    fun restoreSelectedTrash() {
        AppLog.event("Action") { "restoreSelectedTrash " + "selected=${uiState.value.selectionCount}" }
        val state = uiState.value
        val selectedIds = state.selectedFileIds
        val toRestore = state.trashedItems.filter { it.id in selectedIds }
        if (toRestore.isEmpty()) return
        trashManager.markPendingDelete(toRestore.map { it.id })
        clearSelection()
        viewModelScope.launch(fileOperationErrors(R.string.restore_failed) { trashManager.unmarkPendingDelete(toRestore.map { it.id }) }) {
            val restored = trashManager.restore(toRestore)
            // The delete mask set when they were trashed would hide the restored rows (and the
            // sync would then record them as seen): lift it for everything restored.
            repository.unmarkPendingDeletes(toRestore.map { it.id })
            toast(R.string.restored_count, restored.size)
            repository.requestRefresh()
        }
    }

    fun deleteSelectedTrashPermanently() {
        AppLog.event("Action") { "deleteSelectedTrashPermanently " + "selected=${uiState.value.selectionCount}" }
        val state = uiState.value
        val selectedIds = state.selectedFileIds
        val toDelete = state.trashedItems.filter { it.id in selectedIds }
        if (toDelete.isEmpty()) return
        trashManager.markPendingDelete(toDelete.map { it.id })
        clearSelection()
        viewModelScope.launch(fileOperationErrors(R.string.delete_failed) { trashManager.unmarkPendingDelete(toDelete.map { it.id }) }) {
            trashManager.deletePermanently(toDelete)
        }
    }

    fun deleteMediaItem(
        item: GalleryMedia,
        skipTrash: Boolean,
        launchDelete: (android.content.IntentSender) -> Unit,
    ) {
        AppLog.event("Action") { "deleteMediaItem" }
        if (item.bucketId == AlbumIds.NONE && item.absolutePath == null &&
            item.uri.scheme == "content" && item.uri.authority != android.provider.MediaStore.AUTHORITY
        ) {
            // Opened from another app (file manager, chat): Alexet only got read access. The trash
            // copy would be made, the original kept, and restoring it would write a duplicate.
            toast(R.string.cannot_delete_external_file)
            return
        }
        val isTrashedItem = uiState.value.inTrash ||
            item.bucketId == AlbumIds.TRASH ||
            item.absolutePath?.contains(".alexet_trash", ignoreCase = true) == true ||
            item.bucketName.equals(".alexet_trash", ignoreCase = true) ||
            uiState.value.trashedItems.any { it.id == item.id }

        // The viewer moves on at once; a failed delete puts it back on this file. Kept for this
        // delete only: in a shared field the next delete started meanwhile took it over.
        val viewerRollback: Long? = if (uiState.value.viewerMediaId == item.id) {
            val currentList = uiState.value.currentViewerList()
            val currentIndex = currentList.indexOfFirst { it.id == item.id }
            val nextItem = when {
                currentIndex > 0 -> currentList[currentIndex - 1]
                currentIndex == 0 -> currentList.getOrNull(1)
                currentList.isNotEmpty() -> currentList.last()
                else -> null
            }
            if (nextItem != null) {
                interaction.update { it.copy(viewerMediaId = nextItem.id) }
                item.id
            } else {
                closeViewer()
                null
            }
        } else {
            null
        }
        // An error on the way: the file shows again and the viewer goes back to it.
        val restoreItem = {
            trashManager.unmarkPendingDelete(listOf(item.id))
            repository.unmarkPendingDeletes(listOf(item.id))
            if (viewerRollback != null) interaction.update { it.copy(viewerMediaId = viewerRollback) }
        }

        if (isTrashedItem) {
            val trashed = uiState.value.trashedItems.filter { it.id == item.id }
            trashManager.markPendingDelete(listOf(item.id))
            repository.markPendingDeletes(listOf(item))
            if (trashed.isNotEmpty()) {
                viewModelScope.launch(fileOperationErrors(R.string.delete_failed, restoreItem)) {
                    trashManager.deletePermanently(trashed)
                    pendingViewerRollbackId = null
                }
            } else {
                viewModelScope.launch(fileOperationErrors(R.string.delete_failed, restoreItem)) {
                    withContext(Dispatchers.IO) { item.absolutePath?.let { java.io.File(it).delete() } }
                    pendingViewerRollbackId = null
                    repository.requestRefresh()
                }
            }
            return
        }

        repository.markPendingDeletes(listOf(item))

        if (skipTrash) {
            viewModelScope.launch(fileOperationErrors(R.string.delete_failed, restoreItem)) {
                val files = if (item.absolutePath != null) listOf(item) else emptyList()
                val deletedDirectCount = if (files.isNotEmpty()) repository.deleteDirect(files) else 0
                if (deletedDirectCount == 0 && item.uri.scheme == "content") {
                    val sender = repository.createDeleteRequest(listOf(item))
                    if (sender != null) {
                        pendingDeleteRollbackIds = setOf(item.id)
                        pendingAfterDelete = {
                            pendingDeleteRollbackIds = null
                            pendingViewerRollbackId = null
                            viewModelScope.launch { repository.requestRefresh() }
                        }
                        pendingViewerRollbackId = viewerRollback
                        launchDelete(sender)
                        return@launch
                    }
                    val recoverable = repository.takeRecoverableSender()
                    if (recoverable != null) {
                        pendingDeleteRollbackIds = setOf(item.id)
                        pendingAfterDelete = {
                            viewModelScope.launch {
                                repository.deleteDirect(listOf(item))
                                pendingDeleteRollbackIds = null
                                pendingViewerRollbackId = null
                                repository.requestRefresh()
                            }
                        }
                        pendingViewerRollbackId = viewerRollback
                        launchDelete(recoverable)
                        return@launch
                    }
                }
                if (deletedDirectCount == 0) {
                    // Not deleted and nothing to ask: the file comes back at once instead of
                    // staying hidden by its delete mask for the next 45 s.
                    repository.unmarkPendingDeletes(listOf(item.id))
                    if (viewerRollback != null) interaction.update { it.copy(viewerMediaId = viewerRollback) }
                    toast(R.string.delete_failed)
                    return@launch
                }
                pendingViewerRollbackId = null
                repository.requestRefresh()
            }
        } else {
            viewModelScope.launch(fileOperationErrors(R.string.move_failed, restoreItem)) {
                val trashed = trashManager.moveToTrash(listOf(item)) { repository.fileForMedia(it) }
                val trashedIds = trashed.map { it.id }.toSet()
                if (trashedIds.isEmpty()) {
                    repository.unmarkPendingDeletes(listOf(item.id))
                    if (viewerRollback != null) {
                        interaction.update { it.copy(viewerMediaId = viewerRollback) }
                    }
                    toast(R.string.move_failed)
                    return@launch
                }
                if (trashedIds.isNotEmpty()) {
                    val wasSourceRemoved = trashed.firstOrNull()?.sourceRemoved == true
                    // Prefer direct MediaStore delete; SAF only when still required.
                    val directCount = repository.deleteDirect(listOf(item))
                    if (directCount > 0) {
                        pendingViewerRollbackId = null
                        toast(R.string.moved_to_trash_count, trashed.size)
                    } else {
                        val sender = repository.createDeleteRequest(listOf(item))
                        if (sender != null) {
                            // Rollback list = only this SAF-waiting item.
                            pendingTrashRollback = trashed
                            pendingDeleteRollbackIds = setOf(item.id)
                            pendingAfterDelete = {
                                pendingTrashRollback = null
                                pendingDeleteRollbackIds = null
                                pendingViewerRollbackId = null
                                viewModelScope.launch { repository.requestRefresh() }
                            }
                            pendingViewerRollbackId = null
                            toast(R.string.moved_to_trash_count, trashed.size)
                            launchDelete(sender)
                            return@launch
                        }
                        val recoverable = repository.takeRecoverableSender()
                        if (recoverable != null) {
                            pendingTrashRollback = trashed
                            pendingDeleteRollbackIds = setOf(item.id)
                            pendingAfterDelete = {
                                viewModelScope.launch {
                                    repository.deleteDirect(listOf(item))
                                    pendingTrashRollback = null
                                    pendingDeleteRollbackIds = null
                                    pendingViewerRollbackId = null
                                    repository.requestRefresh()
                                }
                            }
                            pendingViewerRollbackId = null
                            toast(R.string.moved_to_trash_count, trashed.size)
                            launchDelete(recoverable)
                            return@launch
                        }
                        // Keep in trash; do NOT silent-restore when MediaStore delete returned 0.
                        pendingViewerRollbackId = null
                        val originalStillThere = !wasSourceRemoved && withContext(Dispatchers.IO) {
                            item.absolutePath?.let { File(it).exists() } == true
                        }
                        if (wasSourceRemoved) {
                            toast(R.string.moved_to_trash_count, trashed.size)
                        } else if (originalStillThere) {
                            // Nothing could delete the original: drop the trash copy (same id as the
                            // live file, so it would shadow it) and show the file again.
                            trashManager.rollbackDeniedDelete(trashed)
                            repository.unmarkPendingDeletes(setOf(item.id))
                            toast(R.string.move_failed)
                        } else {
                            toast(R.string.trash_pending_mediastore_delete)
                        }
                        // File gone, row still listed: the pendingDeletes mask hides it meanwhile.
                    }
                }
                repository.requestRefresh()
            }
        }
    }

    fun deleteSelectionWithOption(
        skipTrash: Boolean,
        launchDelete: (android.content.IntentSender) -> Unit,
    ) {
        AppLog.event("Action") { "deleteSelectionWithOption skipTrash=$skipTrash " + "selected=${uiState.value.selectionCount}" }
        if (!uiState.value.inTrash && deleteFoldersInBackground(skipTrash)) return
        val trashedInSelection = uiState.value.trashedItems.filter { it.id in uiState.value.selectedFileIds }
        val directTrashFiles = uiState.value.selectedFiles.filter {
            it.bucketId == AlbumIds.TRASH ||
            it.absolutePath?.contains(".alexet_trash", ignoreCase = true) == true ||
            it.bucketName.equals(".alexet_trash", ignoreCase = true)
        }

        if (uiState.value.inTrash || trashedInSelection.isNotEmpty() || directTrashFiles.isNotEmpty()) {
            if (trashedInSelection.isNotEmpty()) {
                deleteSelectedTrashPermanently()
            }
            if (directTrashFiles.isNotEmpty()) {
                val directTrashIds = directTrashFiles.map { it.id }
                repository.markPendingDeletes(directTrashFiles)
                clearSelection()
                viewModelScope.launch(fileOperationErrors(R.string.delete_failed) { repository.unmarkPendingDeletes(directTrashIds) }) {
                    repository.deleteDirect(directTrashFiles)
                    repository.requestRefresh()
                }
            }
            val handledIds = trashedInSelection.mapTo(HashSet()) { it.id }.apply { directTrashFiles.mapTo(this) { it.id } }
            if (uiState.value.selectedFileIds.all { it in handledIds }) {
                return
            }
        }
        val items = excludeDownloading(selectedMedia())
        if (items.isEmpty()) {
            clearSelection()
            return
        }
        val itemIds = items.map { it.id }.toSet()
        repository.markPendingDeletes(items)
        clearSelection()

        if (skipTrash) {
            viewModelScope.launch(fileOperationErrors(R.string.delete_failed) { repository.unmarkPendingDeletes(itemIds) }) {
                val files = items.filter { it.absolutePath != null }
                val deletedCount = if (files.isNotEmpty()) repository.deleteDirect(files) else 0
                // Only files still there: the ones deleted just above must not be put into the
                // consent dialog again (their rows go with the scan deleteDirect started).
                val storeItems = withContext(Dispatchers.IO) {
                    items.filter { it.uri.scheme == "content" && (it.absolutePath == null || File(it.absolutePath).exists()) }
                }
                if (deletedCount < items.size && storeItems.isNotEmpty()) {
                    val sender = repository.createDeleteRequest(storeItems)
                    if (sender != null) {
                        pendingDeleteRollbackIds = itemIds
                        pendingAfterDelete = {
                            pendingDeleteRollbackIds = null
                            viewModelScope.launch { repository.requestRefresh() }
                        }
                        launchDelete(sender)
                        return@launch
                    }
                    repository.deleteDirect(storeItems)
                    val recoverable = repository.takeRecoverableSender()
                    if (recoverable != null) {
                        pendingDeleteRollbackIds = itemIds
                        pendingAfterDelete = {
                            viewModelScope.launch {
                                repository.deleteDirect(storeItems)
                                pendingDeleteRollbackIds = null
                                repository.requestRefresh()
                            }
                        }
                        launchDelete(recoverable)
                        return@launch
                    }
                }
                repository.requestRefresh()
            }
        } else {
            viewModelScope.launch(fileOperationErrors(R.string.move_failed) { repository.unmarkPendingDeletes(itemIds) }) {
                val trashed = trashManager.moveToTrash(items) { repository.fileForMedia(it) }
                val trashedIds = trashed.map { it.id }.toSet()
                val failedIds = itemIds - trashedIds
                if (failedIds.isNotEmpty()) {
                    repository.unmarkPendingDeletes(failedIds)
                }
                if (trashedIds.isNotEmpty()) {
                    // Always try MediaStore delete for trashed items (incl. sourceRemoved) to drop orphans.
                    val storeAll = items.filter { it.id in trashedIds && it.uri.scheme == "content" }
                    if (storeAll.isNotEmpty()) {
                        repository.deleteDirect(storeAll)
                    } else {
                        val direct = items.filter { it.id in trashedIds && it.absolutePath != null }
                        if (direct.isNotEmpty()) repository.deleteDirect(direct)
                    }
                    val stillNeedConfirm = trashed.filter { itemT ->
                        val media = items.firstOrNull { it.id == itemT.id }
                        media != null && media.uri.scheme == "content" && !itemT.sourceRemoved
                    }
                    val storeItems = items.filter { media ->
                        stillNeedConfirm.any { it.id == media.id } && media.uri.scheme == "content"
                    }
                    if (storeItems.isNotEmpty()) {
                        val sender = repository.createDeleteRequest(storeItems)
                        if (sender != null) {
                            // Only SAF-waiting items go into rollback — not the whole successful batch.
                            pendingTrashRollback = stillNeedConfirm
                            pendingDeleteRollbackIds = storeItems.map { it.id }.toSet()
                            pendingAfterDelete = {
                                pendingTrashRollback = null
                                pendingDeleteRollbackIds = null
                                viewModelScope.launch { repository.requestRefresh() }
                            }
                            toast(R.string.moved_to_trash_count, trashed.size)
                            launchDelete(sender)
                            return@launch
                        }
                        repository.deleteDirect(storeItems)
                        val recoverable = repository.takeRecoverableSender()
                        if (recoverable != null) {
                            pendingTrashRollback = stillNeedConfirm
                            pendingDeleteRollbackIds = storeItems.map { it.id }.toSet()
                            pendingAfterDelete = {
                                viewModelScope.launch {
                                    repository.deleteDirect(storeItems)
                                    pendingTrashRollback = null
                                    pendingDeleteRollbackIds = null
                                    repository.requestRefresh()
                                }
                            }
                            toast(R.string.moved_to_trash_count, trashed.size)
                            launchDelete(recoverable)
                            return@launch
                        }
                    }
                    toast(R.string.moved_to_trash_count, trashed.size)
                }
                repository.requestRefresh()
            }
        }
    }

    /**
     * Whole selected folders are deleted (or moved to the trash) in the background, a batch at a
     * time ([FolderDeleteQueue]); the tiles show the progress and the app stays usable. Only with
     * direct file access (a system confirmation can't be shown from the background) and only for
     * ordinary folders; otherwise false and the usual path runs.
     */
    private fun deleteFoldersInBackground(skipTrash: Boolean): Boolean {
        val state = uiState.value
        if (state.selectedAlbumIds.isEmpty() || state.selectedFileIds.isNotEmpty()) return false
        if (!StorageAccess.canReadAllFiles()) return false
        val albums = state.selectedAlbumIds.map { id -> state.allAlbums.firstOrNull { it.id == id } ?: return false }
        if (albums.any { it.id == AlbumIds.FAVORITES || it.id == AlbumIds.TRASH || it.isSecure }) return false
        // Every file the folder counts, hidden ones too (the visible list leaves them out).
        val byBucket = repository.media.value.groupBy { it.bucketId }
        val loaded = repository.folderMedia.value
        val perAlbum = albums.associateWith { album ->
            (byBucket[album.id].orEmpty() + loaded[album.id].orEmpty()).distinctBy { it.id }
        }
        if (perAlbum.values.any { files -> files.any { it.isEncrypted } }) return false
        clearSelection()
        val app = getApplication<Application>()
        var skipped = 0
        perAlbum.forEach { (album, files) ->
            val ready = files.filter { !repository.isDownloading(it) }
            skipped += files.size - ready.size
            if (ready.isEmpty()) return@forEach
            closeFolderEverywhere(album.id)
            FolderDeleteQueue.enqueue(app, album.id, album.name, ready, toTrash = !skipTrash)
        }
        if (skipped == 1) toast(R.string.download_in_progress_one)
        else if (skipped > 1) toast(R.string.download_in_progress_skipped, skipped)
        return true
    }

    private fun blockIfFolderDeleting(albumId: Long): Boolean {
        if (!FolderDeleteQueue.isDeleting(albumId)) return false
        toast(R.string.folder_delete_busy)
        return true
    }

    private fun closeFolderEverywhere(id: Long) {
        if (interaction.value.phoneFolderId == id) closePhoneFolder()
        if (id in interaction.value.openFolderIds) closeOpenFolder(id)
        interaction.update { ix ->
            if (ix.sessionFolderId == id) ix.copy(sessionFolderId = AlbumIds.NONE) else ix
        }
        if (store.settings.value.lastOpenedFolderId == id) store.setLastOpenedFolder(AlbumIds.NONE)
    }

    fun handleBackPress(onExit: () -> Unit): Boolean {
        val state = uiState.value
        val isRoot = state.viewerMediaId == null &&
            !state.reorderingFolders &&
            state.selectionCount == 0 &&
            state.pendingHitboxAlbumId == AlbumIds.NONE &&
            state.phoneFolderId == AlbumIds.NONE &&
            state.query.isEmpty()

        if (isRoot) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackPressAt < 2000L) {
                onExit()
                return true
            }
            lastBackPressAt = now
            toast(R.string.press_back_again_to_exit)
            return true
        }
        return false
    }

    fun deleteSelection(launchDelete: (android.content.IntentSender) -> Unit) {
        deleteSelectionWithOption(skipTrash = sessionSkipTrash, launchDelete = launchDelete)
    }


    private fun launchDeleteOrDirect(
        storeItems: List<GalleryMedia>,
        launchDelete: (android.content.IntentSender) -> Unit,
        after: () -> Unit,
    ) {
        if (storeItems.isEmpty()) {
            after()
            return
        }
        val storeIds = storeItems.map { it.id }.toSet()
        repository.markPendingDeletes(storeItems)
        viewModelScope.launch {
            val sender = repository.createDeleteRequest(storeItems)
            if (sender != null) {
                pendingDeleteRollbackIds = storeIds
                pendingAfterDelete = {
                    pendingDeleteRollbackIds = null
                    after()
                }
                launchDelete(sender)
                return@launch
            }
            repository.deleteDirect(storeItems)
            val recoverable = repository.takeRecoverableSender()
            if (recoverable != null) {
                pendingDeleteRollbackIds = storeIds
                pendingAfterDelete = {
                    viewModelScope.launch {
                        repository.deleteDirect(storeItems)
                        pendingDeleteRollbackIds = null
                        after()
                    }
                }
                launchDelete(recoverable)
            } else {
                after()
            }
        }
    }

    fun onDeleteConsented() {
        takeActiveConsent()
        onDeleteConsentedInternal()
        showNextConsent()
    }

    fun onDeleteDenied() {
        takeActiveConsent()
        onDeleteDeniedInternal()
        showNextConsent()
    }

    private fun onDeleteConsentedInternal() {
        pendingViewerRollbackId = null
        pendingTrashRollback = null
        pendingDeleteRollbackIds = null
        pendingSafMoveDeleteToast = false
        val extra = pendingAfterDelete
        pendingAfterDelete = null
        if (extra != null) {
            extra()
            return
        }
        clearSelection()
        viewModelScope.launch { repository.refresh() }
    }

    private fun onDeleteDeniedInternal() {
        pendingAfterDelete = null
        val rollbackViewerId = pendingViewerRollbackId
        pendingViewerRollbackId = null
        if (rollbackViewerId != null) {
            interaction.update { it.copy(viewerMediaId = rollbackViewerId) }
        }
        val rollback = pendingTrashRollback
        pendingTrashRollback = null
        val rollbackIds = pendingDeleteRollbackIds
        pendingDeleteRollbackIds = null
        _transferProgress.value = null
        notifier.cancel()
        if (pendingSafMoveDeleteToast) {
            pendingSafMoveDeleteToast = false
            toast(R.string.move_failed)
        }
        if (rollbackIds != null) {
            repository.unmarkPendingDeletes(rollbackIds)
        }
        if (rollback != null) {
            viewModelScope.launch { trashManager.rollbackDeniedDelete(rollback) }
        }
    }

    /** Progress of the running full scan (null when none). */
    val fullScan: StateFlow<dev.ander.alexet.data.FullScanProgress?> = repository.fullScan

    /**
     * "Re-scan": a complete rewrite of the caches. Thumbnails are dropped and made again, MediaStore
     * is read again in full, and every folder on the storage is checked file by file.
     */
    fun rescanLibrary() {
        // The scan slot first: another scan starting meanwhile made this one quietly not run
        // after the thumbnails had been cleared and "started" shown.
        if (repository.fullScan.value != null || !repository.tryClaimFullScan()) {
            toast(R.string.rescan_running)
            return
        }
        // Not cancellable: the claimed scan must run (its end frees the slot) even if the screen goes.
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val loader = SingletonImageLoader.get(getApplication())
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                }
                // Folder tiles also fall back to these pinned covers (keyed without the epoch).
                dev.ander.alexet.data.FolderCoverCache.clear()
            }
            folderThumbEpoch.update { it + 1 }
            toast(R.string.rescan_started)
            val result = repository.runFullScan("manual", background = false, claimed = true) ?: return@launch
            toast(R.string.rescan_done, result.sent)
        }
    }

    /** Every file the app knows about, for matching config references by path. */
    private fun allKnownMedia(): List<GalleryMedia> {
        val state = uiState.value
        return (repository.media.value + state.media + state.folderMedia.values.flatten()).distinctBy { it.id }
    }

    fun setDebugLogging(enabled: Boolean) {
        store.update { it.copy(debugLogging = enabled) }
        if (!enabled) AppLog.clearCrashLog(getApplication<Application>().filesDir)
    }

    fun clearDebugLog() {
        AppLog.clear()
        AppLog.clearCrashLog(getApplication<Application>().filesDir)
        toast(R.string.debug_log_cleared)
    }

    /**
     * Full log text: header, the log kept from a crash, then app entries and this process' logcat
     * from the last [AppLog.WINDOW_MS], merged by time. [maxChars] keeps only the newest part.
     * Returns the text and the minutes it covers.
     */
    suspend fun buildDebugLog(maxChars: Int = Int.MAX_VALUE): Pair<String, Int> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        val version = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)})" }
        }.getOrDefault("?")
        val header = buildString {
            append("Alexet ").append(version).append('\n')
            append("Device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(", Android ").append(android.os.Build.VERSION.RELEASE).append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append("Created: ").append(AppLog.formatTime(now)).append('\n')
            AppLog.crashLog(app.filesDir)?.let { crash ->
                // Bounded output (clipboard): the crash log gets at most a quarter of it, newest
                // part, so the current entries still fit.
                val limit = if (maxChars == Int.MAX_VALUE) Int.MAX_VALUE else maxChars / 4
                val text = crash.trimEnd()
                if (text.length > limit) append("[… log awarii skrócony …]\n").append(text.takeLast(limit)) else append(text)
                append('\n')
            }
            append("=== Log (ostatnie ").append(AppLog.WINDOW_MS / 60_000).append(" min) ===\n")
        }
        val entries = AppLog.snapshot()
        val logcat = readOwnLogcat(now - AppLog.WINDOW_MS)
        val blocks = AppLog.merge(AppLog.appBlocks(entries), AppLog.logcatBlocks(logcat, entries))
        val (text, firstKey) = AppLog.newestWithin(header, blocks, maxChars)
        val minutes = firstKey?.let { key ->
            val first = runCatching {
                java.text.SimpleDateFormat("yyyy MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .parse("${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} $key")?.time
            }.getOrNull()
            first?.let { ((now - it + 59_999) / 60_000).toInt().coerceAtLeast(1) }
        } ?: 0
        text to minutes
    }

    /** This process' own logcat since [sinceMs] (readable without permissions). */
    private fun readOwnLogcat(sinceMs: Long): List<String> = runCatching {
        val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(sinceMs))
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "--pid=${android.os.Process.myPid()}", "-t", since,
        ).redirectErrorStream(true).start()
        val lines = ArrayList<String>()
        var chars = 0L
        process.inputStream.bufferedReader().useLines { seq ->
            for (line in seq) {
                lines += line
                chars += line.length
                if (chars > 4_000_000L) break
            }
        }
        runCatching { process.destroy() }
        lines
    }.getOrDefault(emptyList())

    fun saveDebugLog(context: Context, uri: Uri) {
        viewModelScope.launch {
            val ok = runCatching {
                val (text, _) = buildDebugLog()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw java.io.IOException("Cannot open output stream")
                }
                true
            }.getOrDefault(false)
            toast(if (ok) R.string.debug_log_saved else R.string.debug_log_save_failed)
        }
    }

    fun copyDebugLogToClipboard(context: Context) {
        // The application, not the activity: this job can outlive the screen.
        val appContext = context.applicationContext
        viewModelScope.launch {
            // The clipboard goes through a binder transaction (~1 MB); keep the newest part.
            val (text, minutes) = buildDebugLog(maxChars = DEBUG_LOG_CLIPBOARD_CHARS)
            val clipboard = appContext.getSystemService(android.content.ClipboardManager::class.java)
            val ok = runCatching {
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Alexet log", text))
                clipboard != null
            }.getOrDefault(false)
            if (ok) toast(R.string.debug_log_copied_minutes, minutes) else toast(R.string.debug_log_save_failed)
        }
    }

    /** [withSmartSearch]: a zip with the AI model and the photo index (see SmartConfigBundle). */
    fun exportConfig(context: Context, uri: Uri, withSmartSearch: Boolean = false) {
        // The application, not the activity: this job can outlive the screen.
        val appContext = context.applicationContext
        AppLog.event("Action") { "exportConfig $uri smart=$withSmartSearch" }
        if (withSmartSearch) toast(R.string.config_exporting)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val byId = allKnownMedia().associateBy { it.id }
                val json = store.exportJson(
                    pathOf = { id -> byId[id]?.let(dev.ander.alexet.data.MediaRefs::portablePath) },
                    securePaths = SecureFolderManager.getKnownSecurePaths()
                        .filter { File(it).isDirectory }
                        .map(dev.ander.alexet.data.MediaRefs::portableDirPath),
                )
                // "wt": truncate, or a longer existing file would keep its old tail after the JSON.
                val out = appContext.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Cannot open output stream")
                out.use { stream ->
                    if (withSmartSearch) {
                        dev.ander.alexet.data.smartsearch.SmartConfigBundle.write(stream, json, smartSearch)
                    } else {
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
                true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) toast(R.string.config_exported) else toast(R.string.config_export_failed)
            }
        }
    }

    /** Private folders listed in an imported config, registered when they exist on this phone. */
    private fun registerImportedSecureFolders(paths: List<String>) {
        @Suppress("DEPRECATION")
        val root = android.os.Environment.getExternalStorageDirectory() ?: return
        for (path in paths) {
            val dir = File(root, path)
            if (!dir.isDirectory || !SecureFolderManager.isFolderSecure(dir)) continue
            val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            SecureFolderManager.registerKnownSecurePath(canonical)
        }
    }

    /** Installs the bundle's AI model and photo index; returns its config.json. */
    private suspend fun importConfigBundle(appContext: Context, input: java.io.InputStream): String {
        withContext(Dispatchers.Main) { toast(R.string.config_importing) }
        val bundle = dev.ander.alexet.data.smartsearch.SmartConfigBundle.read(input, smartSearch::newStaging, appContext.cacheDir)
        try {
            bundle.manifest?.let { smartSearch.installStaged(it) }
            bundle.indexFile?.let { file ->
                val taken = file.inputStream().use { smartSearch.readIndex(it, allKnownMedia()) }
                AppLog.d("GalleryViewModel", "Imported smart search index: $taken photos")
            }
        } finally {
            bundle.indexFile?.delete()
        }
        return bundle.configJson ?: throw java.io.IOException("No config.json in the bundle")
    }

    fun importConfig(context: Context, uri: Uri) {
        // The application, not the activity: this job can outlive the screen.
        val appContext = context.applicationContext
        AppLog.event("Action") { "importConfig $uri" }
        viewModelScope.launch(Dispatchers.IO) {
            var before: AppSettings? = null
            val ok = runCatching {
                val maxBytes = 2 * 1024 * 1024
                val text = appContext.contentResolver.openInputStream(uri)?.use { raw ->
                    val input = raw.buffered(64 * 1024)
                    input.mark(2)
                    val zip = dev.ander.alexet.data.smartsearch.SmartConfigBundle.isZip(input.read(), input.read())
                    input.reset()
                    // A zip export: the AI model and the photo index come with the configuration.
                    if (zip) return@use importConfigBundle(appContext, input)
                    val buffer = ByteArray(maxBytes + 1)
                    var offset = 0
                    while (offset < buffer.size) {
                        val n = input.read(buffer, offset, buffer.size - offset)
                        if (n <= 0) break
                        offset += n
                    }
                    if (offset > maxBytes) {
                        dev.ander.alexet.data.AppLog.w("GalleryViewModel", "Config import rejected: exceeds 2MB limit")
                        ""
                    } else {
                        String(buffer, 0, offset, Charsets.UTF_8)
                    }
                }.orEmpty()
                val index = dev.ander.alexet.data.MediaRefs.index(allKnownMedia())
                before = store.settings.value
                text.isNotBlank() && store.importJson(
                    text,
                    idForPath = { index[it] },
                    onSecurePaths = { paths -> registerImportedSecureFolders(paths) },
                )
            }.getOrDefault(false)
            if (ok) {
                before?.let { applyImportedSettings(it, store.settings.value) }
                // The whole imported config on disk now, not ~120 ms later.
                runCatching { store.flush() }
                // "Pokaż ukryte" and the hidden folder list may come from the file: apply them now,
                // not on the next launch.
                repository.setScanHidden(store.settings.value.showHidden || interaction.value.revealHidden || pickerHiddenHold)
                repository.rescanHiddenNow()
            }
            withContext(Dispatchers.Main) {
                if (ok) toast(R.string.config_imported) else toast(R.string.config_import_failed)
            }
        }
    }

    /** What changing each setting by hand does besides saving it, for the values an import changed. */
    private fun applyImportedSettings(before: AppSettings, after: AppSettings) {
        if (after.contentViewMode != before.contentViewMode) applyContentViewModeToPanes(after.contentViewMode)
        if (after.multiFolderMemory != before.multiFolderMemory && after.multiFolderMemory == MultiFolderMemory.FRESH) {
            interaction.update { it.copy(openFolderIds = emptyList()) }
            store.setRememberedOpenFolders(emptyList())
        }
        if (after.showHidden && !before.showHidden) {
            interaction.update { it.copy(revealHidden = false) }
            // Asked for all-files access again, as when it is turned on by hand.
            askedAllFilesThisRun = false
        }
        val newRandomSort = (after.sort != before.sort && after.sort.field == SortField.RANDOM) ||
            (after.folderSorts != before.folderSorts && after.folderSorts.values.any { it.field == SortField.RANDOM })
        if (newRandomSort) interaction.update { it.copy(randomSeed = System.nanoTime()) }
        // The file's scroll positions replace the ones in memory, which would overwrite them when
        // the app goes to the background.
        if (after.rememberFolderScrollAcrossSessions) {
            synchronized(folderScrollLru) {
                folderScrollLru.clear()
                after.savedFolderScrolls.entries.toList().takeLast(3).forEach { folderScrollLru[it.key] = it.value }
            }
        }
        // Folder ids of the file (an older version's kind) mapped now, not at the next start.
        uiState.value.allAlbums.takeIf { it.isNotEmpty() }?.let { remapStoredFolderIds(it) }
    }

    fun openEditor(media: GalleryMedia) {
        AppLog.event("Action") { "openEditor id=${media.id} ${media.displayName}" }
        // A save is writing the photo being edited: the editor can't change photos under it.
        if (_isSavingEdit.value) return
        if (withoutOtherFiles(listOf(media)).isEmpty()) return
        if (blockIfDownloading(media)) return
        // The editor would save a plain copy next to the encrypted files.
        if (blockIfPrivate(listOf(media))) return
        _editCopyOnly.value = false
        leaveAfterEdit = false
        _editingMedia.value = media
    }

    fun closeEditor() {
        AppLog.event("Action") { "closeEditor copyOnly=${_editCopyOnly.value} leave=$leaveAfterEdit" }
        if (_isSavingEdit.value) return
        _editingMedia.value = null
        editorDirty = false
        if (_editCopyOnly.value && leaveAfterEdit) _leaveAfterExternalEdit.trySend(Unit)
        _editCopyOnly.value = false
        leaveAfterEdit = false
        ImageEditEngine.clearCaches()
    }

    fun saveEditedImage(
        editState: ImageEditState,
        overwriteRequested: Boolean,
        launchDelete: ((android.content.IntentSender) -> Unit)? = null,
    ) {
        AppLog.event("Action") { "saveEditedImage overwrite=$overwriteRequested media=${_editingMedia.value?.id}" }
        val media = _editingMedia.value ?: return
        if (_isSavingEdit.value) return
        _isSavingEdit.value = true

        viewModelScope.launch {
            val app = getApplication<Application>()
            // A GIF stays an animated GIF: every frame gets the edits.
            val isGif = media.mimeType.equals("image/gif", ignoreCase = true) ||
                media.displayName.endsWith(".gif", ignoreCase = true)
            val ext = if (isGif) "gif" else ImageEditEngine.encoderExtensionFor(media.displayName, media.mimeType)
            // In place only when the encoder (chosen by content type) writes what the name says:
            // a PNG named .jpg must not get PNG bytes under its .jpg name.
            val canOverwriteInPlace = if (isGif) {
                media.displayName.endsWith(".gif", ignoreCase = true)
            } else {
                ImageEditEngine.encoderMatchesOriginalExtension(media.displayName) &&
                    ImageEditEngine.encoderExtensionFor(media.displayName) == ext
            }
            val tempImageFile = File(app.cacheDir, "alexet_edit_${System.currentTimeMillis()}.$ext")
            try {
                val sourceFile = repository.fileForMedia(media)
                val baseName = media.displayName.substringBeforeLast('.')

                // Overwriting must never lose content: a downscaled export or an animated WebP
                // (only the first frame survives) is written as a copy instead.
                val webpKind = withContext(Dispatchers.IO) {
                    if (ext == "webp") ImageEditEngine.readWebpKind(app, sourceFile, media.uri)
                    else ImageEditEngine.WebpKind.NOT_WEBP
                }
                // GIF frames are written at their own size.
                val downscaled = !isGif && withContext(Dispatchers.IO) {
                    ImageEditEngine.readImageBounds(app, sourceFile, media.uri)?.let { (w, h) ->
                        ImageEditEngine.exportTargetSize(w, h) != (w to h)
                    } ?: false
                }
                var overwrite = overwriteRequested && !_editCopyOnly.value
                val copyReason = when {
                    !overwrite -> null
                    webpKind == ImageEditEngine.WebpKind.ANIMATED -> R.string.edit_saved_copy_animated
                    downscaled -> R.string.edit_saved_copy_downscaled
                    else -> null
                }
                if (copyReason != null) overwrite = false

                val exportResult = if (isGif) {
                    _editSaveProgress.value = 0f
                    ImageEditEngine.processAndSaveGif(
                        context = app,
                        sourceFile = sourceFile,
                        sourceUri = media.uri,
                        destinationFile = tempImageFile,
                        editState = editState,
                        onProgress = { _editSaveProgress.value = it },
                    )
                } else {
                    ImageEditEngine.processAndSave(
                        context = app,
                        sourceFile = sourceFile,
                        sourceUri = media.uri,
                        destinationFile = tempImageFile,
                        editState = editState,
                        webpLossless = webpKind == ImageEditEngine.WebpKind.LOSSLESS,
                        keepLocation = overwrite && canOverwriteInPlace,
                    )
                }

                if (exportResult == dev.ander.alexet.data.ExportResult.SAVED && tempImageFile.exists() && tempImageFile.length() > 0L) {
                    val saved = if (overwrite && canOverwriteInPlace) {
                        // Not cancellable: once the file is replaced, the list, the thumbnails and
                        // the toast below must follow (a cancel left the old date and thumbnail).
                        val directSaved = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                            var direct = false
                            if (sourceFile != null && sourceFile.canWrite()) {
                                direct = replaceFileAtomically(tempImageFile, sourceFile)
                            }
                            if (!direct) {
                                // The provider writes the same file on disk: the folder watcher
                                // must not take that write for a download (it locks the photo).
                                sourceFile?.let { repository.noteOwnWrite(it) }
                                try {
                                    direct = overwriteThroughResolver(app, media.uri, tempImageFile)
                                } finally {
                                    sourceFile?.let { repository.endOwnWrite(it) }
                                }
                            }
                            direct
                        }
                        if (directSaved) {
                            val now = System.currentTimeMillis()
                            val newSize = tempImageFile.length()
                            if (sourceFile != null && sourceFile.exists()) {
                                runCatching { sourceFile.setLastModified(now) }
                                MediaScannerConnection.scanFile(app, arrayOf(sourceFile.absolutePath), null) { _, _ ->
                                    repository.requestRefresh()
                                }
                            } else {
                                repository.requestRefresh()
                            }
                            repository.updateMediaItem(media.id) { current ->
                                current.copy(
                                    dateModifiedMs = now,
                                    sizeBytes = newSize,
                                )
                            }
                            if (lastActiveViewerMedia?.id == media.id) {
                                lastActiveViewerMedia = lastActiveViewerMedia?.copy(
                                    dateModifiedMs = now,
                                    sizeBytes = newSize,
                                )
                            }
                            val oldKey = "thumb-${media.id}-${media.dateModifiedMs}"
                            val newKey = "thumb-${media.id}-$now"
                            val oldFullKey = "full-${media.id}-${media.dateModifiedMs}"
                            val newFullKey = "full-${media.id}-$now"
                            val imageLoader = SingletonImageLoader.get(app)
                            val keysToEvict = buildList {
                                add(oldKey)
                                add(newKey)
                                add(oldFullKey)
                                add(newFullKey)
                                add(media.uri.toString())
                                for (g in 0..5) {
                                    add("$oldKey-g$g")
                                    add("$newKey-g$g")
                                    add("$oldFullKey-g$g")
                                    add("$newFullKey-g$g")
                                    add("${media.uri}-g$g")
                                }
                                sourceFile?.let { sf ->
                                    add(sf.absolutePath)
                                    for (g in 0..5) {
                                        add("${sf.absolutePath}-g$g")
                                    }
                                }
                            }
                            // Disk cache work off the main thread (about 40 removals).
                            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                                keysToEvict.forEach { key ->
                                    imageLoader.diskCache?.remove(key)
                                }
                            }
                            // Only this file's entries: clearing everything reloaded every thumbnail.
                            // By prefix too: sized keys ("-s<px>") and later generations are not in the list.
                            val evictSet = keysToEvict.toSet()
                            val evictPrefixes = listOf("thumb-${media.id}-", "full-${media.id}-", media.uri.toString()) +
                                listOfNotNull(sourceFile?.absolutePath)
                            imageLoader.memoryCache?.let { cache ->
                                cache.keys.filter { k -> k.key in evictSet || evictPrefixes.any { k.key.startsWith(it) } }
                                    .forEach { cache.remove(it) }
                            }
                            // Folder covers keep their own copies (keyed with the old date).
                            dev.ander.alexet.data.FolderCoverCache.removeByPrefix("thumb-${media.id}-")
                            // The tile reloads by itself (updateMediaItem gave it a new date, so a new
                            // key); only its folder cover needs a nudge. A global epoch bump reloaded
                            // every thumbnail in the library.
                            bumpFolderCovers(listOf(media.bucketId))
                            true
                        } else {
                            false
                        }
                    } else {
                        val wroteCopy = saveEditedImageToStorage(
                            context = app,
                            tempFile = tempImageFile,
                            baseName = baseName,
                            ext = ext,
                            preferredFolder = sourceFile?.parentFile,
                        )
                        if (wroteCopy && overwrite && !canOverwriteInPlace) {
                            deleteReplacedOriginal(media, sourceFile, launchDelete)
                        }
                        wroteCopy
                    }

                    if (saved) {
                        toast(copyReason ?: R.string.image_saved)
                        // Another edit may have been opened while this one was saving: only the
                        // editor of the image just saved is closed, and its own exit rules apply.
                        if (_editingMedia.value?.id == media.id) {
                            _editingMedia.value = null
                            editorDirty = false
                            if (_editCopyOnly.value && leaveAfterEdit) _leaveAfterExternalEdit.trySend(Unit)
                            _editCopyOnly.value = false
                            leaveAfterEdit = false
                        }
                        if (!overwrite || !canOverwriteInPlace) {
                            repository.requestRefresh()
                        }
                    } else {
                        toast(R.string.image_save_failed)
                    }
                } else {
                    toast(
                        if (exportResult == dev.ander.alexet.data.ExportResult.OUT_OF_MEMORY) R.string.edit_save_oom
                        else R.string.image_save_failed,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                toast(R.string.image_save_failed)
            } finally {
                tempImageFile.delete()
                _editSaveProgress.value = null
                _isSavingEdit.value = false
            }
        }
    }

    /**
     * Writes [temp] next to [target] and renames it over the original, so a failed write
     * (e.g. no space left) leaves the original untouched instead of truncated.
     */
    /**
     * Overwrite through the content provider (no direct file access). "rwt" empties the original
     * before anything is written, so a copy of it is kept first and written back if the new
     * content does not make it in full.
     */
    /**
     * "rwt" empties the original before the new content is in, so the original is first copied to
     * app storage with a note of what is being written. A process killed in between leaves both;
     * the next start ([dev.ander.alexet.data.StartupRecovery]) checks the file and writes the
     * original back if the new content did not make it.
     */
    private fun overwriteThroughResolver(app: Application, uri: Uri, newContent: File): Boolean {
        val resolver = app.contentResolver
        val dir = dev.ander.alexet.data.StartupRecovery.overwriteBackupDir(app)
        val stamp = System.nanoTime()
        val backup = File(dir, "alexet_overwrite_backup_$stamp")
        val note = File(dir, "alexet_overwrite_backup_$stamp.json")
        var keepBackup = false
        try {
            val backedUp = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(backup).use { out ->
                        input.copyTo(out)
                        out.fd.sync()
                    }
                } != null
            }.getOrDefault(false)
            if (!backedUp) return false
            val noted = runCatching {
                dev.ander.alexet.data.StartupRecovery.writeOverwriteNote(note, uri, backup, newContent.length())
            }.isSuccess
            if (!noted) return false
            val written = runCatching {
                resolver.openOutputStream(uri, "rwt")?.use { output ->
                    newContent.inputStream().use { it.copyTo(output) }
                    output.flush()
                } != null
            }.getOrDefault(false)
            if (written) return true
            val restored = runCatching {
                resolver.openOutputStream(uri, "rwt")?.use { output -> backup.inputStream().use { it.copyTo(output) } } != null
            }.getOrDefault(false)
            if (!restored) {
                // Last copy of the original: kept with its note, the next start tries again.
                keepBackup = true
                AppLog.e("GalleryViewModel", "Overwrite of $uri failed and the original could not be written back; kept at ${backup.path}")
            } else {
                AppLog.w("GalleryViewModel", "Overwrite of $uri failed; original restored")
            }
            return false
        } finally {
            if (!keepBackup) {
                backup.delete()
                note.delete()
            }
        }
    }

    private fun replaceFileAtomically(temp: File, target: File): Boolean {
        val parent = target.parentFile ?: return false
        val staging = File(parent, ".${target.name}.alexet_tmp")
        val app = getApplication<Application>()
        dev.ander.alexet.data.StartupRecovery.notePartial(app, staging)
        return try {
            temp.inputStream().use { input ->
                java.io.FileOutputStream(staging).use { output ->
                    input.copyTo(output)
                    output.fd.sync() // the new content is on disk before it replaces the original
                }
            }
            if (staging.renameTo(target)) {
                true
            } else {
                staging.delete()
                false
            }
        } catch (e: Exception) {
            staging.delete()
            false
        } finally {
            dev.ander.alexet.data.StartupRecovery.donePartial(app, staging)
        }
    }

    /**
     * Original replaced by an "_edit" file in another format (HEIC, GIF...): it goes to the
     * app trash like a normal delete, so it can be restored.
     */
    private suspend fun deleteReplacedOriginal(
        media: GalleryMedia,
        sourceFile: File?,
        launchDelete: ((android.content.IntentSender) -> Unit)?,
    ) {
        val item = if (sourceFile != null) media.copy(absolutePath = sourceFile.absolutePath) else media
        val trashed = trashManager.moveToTrash(listOf(item)) { repository.fileForMedia(it) }
        // Could not secure a trash copy: keep the original next to the edited copy.
        val entry = trashed.firstOrNull() ?: return
        val storeItem = media.copy(absolutePath = null)
        if (media.uri.scheme == "content") repository.deleteDirect(listOf(storeItem))
        if (entry.sourceRemoved) return
        // Its own request first: a consent left over from another operation would run that
        // operation's follow-up instead of this one.
        val sender = repository.createDeleteRequest(listOf(storeItem)) ?: repository.takeRecoverableSender()
        if (sender != null && launchDelete != null) {
            pendingAfterDelete = {
                pendingTrashRollback = null
                viewModelScope.launch {
                    repository.deleteDirect(listOf(storeItem))
                    repository.requestRefresh()
                }
            }
            // Denied: the original stays, so its trash copy must go (restoring it would duplicate).
            pendingTrashRollback = trashed
            launchDelete(sender)
        } else {
            dev.ander.alexet.data.AppLog.w(
                "GalleryViewModel",
                "Original kept after *_edit save (no delete consent possible); dropping its trash copy",
            )
            trashManager.rollbackDeniedDelete(trashed)
        }
    }

    private suspend fun saveEditedImageToStorage(
        context: Context,
        tempFile: File,
        baseName: String,
        ext: String,
        preferredFolder: File?,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (preferredFolder != null && preferredFolder.exists() && preferredFolder.canWrite()) {
                var candidate = File(preferredFolder, "${baseName}_edit.$ext")
                var index = 1
                while (candidate.exists()) {
                    candidate = File(preferredFolder, "${baseName}_edit_$index.$ext")
                    index++
                }
                // Written under a hidden name and renamed when complete: a failed copy (full
                // disk, card removed) must not leave a broken "_edit" file next to the original.
                val partial = File(preferredFolder, ".${candidate.name}.part")
                repository.noteOwnWrite(candidate)
                dev.ander.alexet.data.StartupRecovery.notePartial(context, partial)
                try {
                    tempFile.inputStream().use { input ->
                        partial.outputStream().use { output ->
                            input.copyTo(output)
                            output.flush()
                            runCatching { output.fd.sync() }
                        }
                    }
                    if (partial.renameTo(candidate)) {
                        MediaScannerConnection.scanFile(context, arrayOf(candidate.absolutePath), null, null)
                        return@withContext true
                    }
                } catch (_: Exception) {
                } finally {
                    partial.delete()
                    dev.ander.alexet.data.StartupRecovery.donePartial(context, partial)
                    repository.endOwnWrite(candidate)
                }
            }

            val resolver = context.contentResolver
            val mimeType = when (ext.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            val displayName = "${baseName}_edit.$ext"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Alexet")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            var outUri: Uri? = null
            var success = false
            try {
                val inserted = resolver.insert(collection, values) ?: return@withContext false
                outUri = inserted
                // A process killed during the copy leaves a pending row with part of the file.
                dev.ander.alexet.data.StartupRecovery.notePartial(context, inserted)
                val copied = resolver.openOutputStream(inserted)?.use { out ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                    true
                } ?: false

                if (!copied) {
                    return@withContext false
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(inserted, done, null, null)
                }
                success = true
                true
            } finally {
                if (!success && outUri != null) {
                    runCatching { resolver.delete(outUri, null, null) }
                }
                outUri?.let { dev.ander.alexet.data.StartupRecovery.donePartial(context, it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun openVideoEditor(media: List<GalleryMedia>) {
        AppLog.event("Action") { "openVideoEditor count=${media.size}" }
        if (blockIfPrivate(media)) return
        val ready = excludeDownloading(withoutOtherFiles(media))
        if (ready.isEmpty()) return
        _editingVideoList.value = ready
    }

    fun closeVideoEditor() {
        AppLog.event("Action") { "closeVideoEditor exporting=${_isExportingVideo.value}" }
        // Closing during an export would drop the project while the export still runs.
        if (_isExportingVideo.value) return
        _editingVideoList.value = null
    }

    private var videoExportJob: kotlinx.coroutines.Job? = null
    /**
     * The finished export is being copied into the gallery: that copy can't be stopped half-way
     * (it is plain file I/O), so Cancel no longer applies; it said "cancelled" over a saved file.
     */
    @Volatile private var videoExportSaving = false

    fun cancelVideoExport() {
        AppLog.event("Action") { "cancelVideoExport saving=$videoExportSaving" }
        val job = videoExportJob ?: return
        if (!job.isActive || videoExportSaving) return
        job.cancel()
        toast(R.string.export_cancelled)
    }

    fun exportVideo(
        project: dev.ander.alexet.data.videoeditor.VideoProjectState,
        config: dev.ander.alexet.data.videoeditor.VideoExportConfig,
    ) {
        if (_isExportingVideo.value) return
        _isExportingVideo.value = true
        _exportVideoProgress.value = 0
        val session = _editingVideoList.value

        videoExportJob = viewModelScope.launch {
            val app = getApplication<Application>()
            // Keeps the process alive when the user leaves the app during a long export.
            dev.ander.alexet.integration.ExportService.start(app)
            val ext = config.format.extension
            val tempExportFile = File(app.cacheDir, "alexet_export_${System.currentTimeMillis()}.$ext")
            try {
                val firstClipMedia = project.clips.firstOrNull()?.media
                val sourceFile = firstClipMedia?.let { repository.fileForMedia(it) }
                val baseName = firstClipMedia?.displayName?.substringBeforeLast('.') ?: "video"

                val success = dev.ander.alexet.data.videoeditor.VideoExportEngine.exportVideo(
                    context = app,
                    project = project,
                    config = config,
                    outputFile = tempExportFile,
                    onProgress = { pct ->
                        _exportVideoProgress.value = pct
                        dev.ander.alexet.integration.ExportService.update(app, pct)
                    },
                )

                if (success && tempExportFile.exists() && tempExportFile.length() > 0L) {
                    videoExportSaving = true
                    val saved = withContext(kotlinx.coroutines.NonCancellable) {
                        saveExportedMediaToStorage(
                            context = app,
                            tempFile = tempExportFile,
                            baseName = baseName,
                            ext = ext,
                            format = config.format,
                            preferredFolder = sourceFile?.parentFile,
                        )
                    }
                    if (saved) {
                        toast(R.string.export_completed)
                        // Only the editor this export came from.
                        if (_editingVideoList.value === session) _editingVideoList.value = null
                        repository.requestRefresh()
                    } else {
                        toast(R.string.export_failed)
                    }
                } else {
                    toast(R.string.export_failed)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                toast(R.string.export_failed)
            } finally {
                videoExportSaving = false
                tempExportFile.delete()
                _isExportingVideo.value = false
                dev.ander.alexet.integration.ExportService.stop(app)
            }
        }
    }

    private suspend fun saveExportedMediaToStorage(
        context: Context,
        tempFile: File,
        baseName: String,
        ext: String,
        format: dev.ander.alexet.data.videoeditor.ExportFormat,
        preferredFolder: File?,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (preferredFolder != null && preferredFolder.exists() && preferredFolder.canWrite()) {
                var candidate = File(preferredFolder, "${baseName}_edit.$ext")
                var index = 1
                while (candidate.exists()) {
                    candidate = File(preferredFolder, "${baseName}_edit_$index.$ext")
                    index++
                }
                // Written under a hidden name and renamed when complete: a failed copy (full
                // disk, card removed) must not leave a broken "_edit" file next to the original.
                val partial = File(preferredFolder, ".${candidate.name}.part")
                repository.noteOwnWrite(candidate)
                dev.ander.alexet.data.StartupRecovery.notePartial(context, partial)
                try {
                    tempFile.inputStream().use { input ->
                        partial.outputStream().use { output ->
                            input.copyTo(output)
                            output.flush()
                            runCatching { output.fd.sync() }
                        }
                    }
                    if (partial.renameTo(candidate)) {
                        MediaScannerConnection.scanFile(context, arrayOf(candidate.absolutePath), null, null)
                        return@withContext true
                    }
                } catch (_: Exception) {
                } finally {
                    partial.delete()
                    dev.ander.alexet.data.StartupRecovery.donePartial(context, partial)
                    repository.endOwnWrite(candidate)
                }
            }

            val resolver = context.contentResolver
            val mimeType = format.mimeType
            val displayName = "${baseName}_edit.$ext"
            val relativeDir = when (format) {
                dev.ander.alexet.data.videoeditor.ExportFormat.AUDIO_ONLY -> "Music/Alexet"
                dev.ander.alexet.data.videoeditor.ExportFormat.GIF -> "Pictures/Alexet"
                else -> "Movies/Alexet"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = when (format) {
                dev.ander.alexet.data.videoeditor.ExportFormat.AUDIO_ONLY ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                dev.ander.alexet.data.videoeditor.ExportFormat.GIF ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            var outUri: Uri? = null
            var success = false
            try {
                val inserted = resolver.insert(collection, values) ?: return@withContext false
                outUri = inserted
                // A process killed during the copy leaves a pending row with part of the file.
                dev.ander.alexet.data.StartupRecovery.notePartial(context, inserted)
                val copied = resolver.openOutputStream(inserted)?.use { out ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                    true
                } ?: false

                if (!copied) {
                    return@withContext false
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(inserted, done, null, null)
                }
                success = true
                true
            } finally {
                if (!success && outUri != null) {
                    runCatching { resolver.delete(outUri, null, null) }
                }
                outUri?.let { dev.ander.alexet.data.StartupRecovery.donePartial(context, it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Total size of the files in [albums], counted the same way as their item counts (library rows
     * by folder id). Private folders hold encrypted files outside the library: their open listing.
     */
    fun albumsSizeBytes(albums: List<Album>): Long {
        if (albums.isEmpty()) return 0L
        val libraryIds = albums.filterNot { it.isSecure }.mapTo(HashSet()) { it.id }
        var total = 0L
        if (libraryIds.isNotEmpty()) {
            for (item in repository.media.value) {
                if (item.bucketId in libraryIds) total += item.sizeBytes.coerceAtLeast(0L)
            }
        }
        val cached = repository.folderMedia.value
        for (album in albums) {
            if (album.isSecure) total += cached[album.id].orEmpty().sumOf { it.sizeBytes.coerceAtLeast(0L) }
        }
        return total
    }

    /**
     * A folder's info: the files it shows (count, size, the oldest one's date), its whole path and
     * when it was last opened here. Reads the disk: not on the main thread.
     */
    fun folderDetails(album: Album): dev.ander.alexet.data.FolderDetails {
        val files = uiState.value.filesIn(album.id)
        val path = when {
            album.id == AlbumIds.FAVORITES || album.id == AlbumIds.TRASH || album.relativePath.isBlank() -> null
            else -> File(album.relativePath).takeIf { it.isAbsolute && it.isDirectory }?.absolutePath
                ?: NomediaScanner.directoryForRelativePath(album.relativePath)?.absolutePath
                ?: android.os.Environment.getExternalStorageDirectory()?.let { File(it, album.relativePath.trim('/')).absolutePath }
        }
        return dev.ander.alexet.data.FolderDetails.of(
            files = files,
            path = path,
            lastUsedMs = dev.ander.alexet.data.FolderUsage.lastOpenedMs(getApplication(), album.id),
        )
    }

    private fun selectedMedia(): List<GalleryMedia> {
        val state = uiState.value
        val fromFiles = state.selectedFiles
        val allMedia = if (state.folderMedia.isEmpty()) state.media else (state.media + state.folderMedia.values.flatten()).distinctBy { it.id }
        val fromAlbums = allMedia.filter { it.bucketId in state.selectedAlbumIds }
        return (fromFiles + fromAlbums).distinctBy { it.id }
    }

    private fun excludeDownloading(items: List<GalleryMedia>): List<GalleryMedia> {
        if (items.isEmpty()) return items
        val ready = items.filter { !repository.isDownloading(it) }
        val skipped = items.size - ready.size
        if (skipped == 1) toast(R.string.download_in_progress_one)
        else if (skipped > 1) toast(R.string.download_in_progress_skipped, skipped)
        return ready
    }

    /**
     * Files of a private folder are encrypted on disk: renaming, moving, copying or editing them
     * like plain files breaks them (the name loses its .enc, the bytes stay unreadable).
     */
    private fun blockIfPrivate(items: Collection<GalleryMedia>): Boolean {
        if (items.none { it.isEncrypted }) return false
        toast(R.string.private_file_action_unavailable)
        return true
    }

    private fun blockIfPrivateFolder(folderId: Long): Boolean {
        val album = uiState.value.allAlbums.firstOrNull { it.id == folderId }
        val private = album?.isSecure == true || uiState.value.filesIn(folderId).any { it.isEncrypted }
        if (private) toast(R.string.private_file_action_unavailable)
        return private
    }

    /** A private file decrypted into the share cache (cleaned with it): others can't read the .enc. */
    private fun decryptedCopy(context: Context, media: GalleryMedia, dir: File = newShareDir(context, private = true)): File? {
        val enc = media.absolutePath?.let { File(it) } ?: return null
        val key = SecureFolderManager.getActiveKeyForFile(enc) ?: return null
        val safeName = dev.ander.alexet.data.sanitizeFileName(media.displayName.ifBlank { "share_item" })
        val out = File(dir, safeName)
        return try {
            val input = SecureFolderManager.openDecryptedStream(enc, key) ?: return null
            input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            out
        } catch (e: Exception) {
            AppLog.w("GalleryViewModel", "Could not decrypt ${media.displayName} for sharing", e)
            out.delete()
            null
        }
    }

    private fun blockIfDownloading(item: GalleryMedia): Boolean {
        if (!repository.isDownloading(item)) return false
        AppLog.event("Downloads") { "blocked open, still tracked as downloading: ${item.absolutePath}" }
        toast(R.string.download_in_progress_one)
        return true
    }

    private fun toast(res: Int, arg: Int? = null) {
        val app = getApplication<Application>()
        val text = if (arg == null) app.getString(res) else app.getString(res, arg)
        AppLog.event("Toast") { text }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toast(res: Int, copied: Int, total: Int) {
        val app = getApplication<Application>()
        AppLog.event("Toast") { app.getString(res, copied, total) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(app, app.getString(res, copied, total), Toast.LENGTH_SHORT).show()
        }
    }

    fun stripExif(items: List<GalleryMedia>) {
        if (blockIfPrivate(items)) return
        val ready = excludeDownloading(withoutOtherFiles(items))
        if (ready.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            for (item in ready) {
                if (item.isVideo) continue
                val file = repository.fileForMedia(item)
                // Written in place: not a download for the folder watcher.
                file?.let { repository.noteOwnWrite(it) }
                val ok = try {
                    if (file != null && file.exists()) {
                        ExifUtils.stripExifExceptDate(file)
                    } else {
                        ExifUtils.stripExifExceptDate(getApplication<Application>(), item.uri)
                    }
                } finally {
                    file?.let { repository.endOwnWrite(it) }
                }
                if (ok) successCount++
            }
            withContext(Dispatchers.Main) {
                if (successCount > 0) {
                    toast(R.string.exif_stripped_count, successCount)
                    repository.refresh()
                } else {
                    toast(R.string.exif_strip_failed)
                }
            }
        }
    }

    fun stripExifSelection() {
        AppLog.event("Action") { "stripExifSelection " + "selected=${uiState.value.selectionCount}" }
        val items = selectedMedia()
        clearSelection()
        stripExif(items)
    }

    override fun onCleared() {
        persistFolderScrolls()
        repository.stopForegroundWatch()
        notifier.close()
        super.onCleared()
    }

    companion object {
        private const val KEY_PHONE_FOLDER_ID = "phoneFolderId"
        /** Folders smaller than this get the half scan's close look in full. */
        private const val HALF_SCAN_WHOLE_BELOW = 50
        private const val HALF_SCAN_BEFORE = 30
        private const val HALF_SCAN_AFTER = 150
        /** ~400k chars (~800 KB in UTF-16) stays below the clipboard's binder limit. */
        private const val DEBUG_LOG_CLIPBOARD_CHARS = 400_000
        private const val KEY_VIEWER_MEDIA_ID = "viewerMediaId"
        private const val KEY_EDITING_MEDIA_ID = "editingMediaId"
        /** Files unpacked from archives to be viewed (cache dir, removed with the window). */
        private const val ARCHIVE_CACHE_DIR = "archive_view"
        /** Folders with other files are read this long after the last library change. */
        private const val OTHER_FOLDERS_SCAN_DELAY_MS = 3_000L
        private const val KEY_EDITING_VIDEO_IDS = "editingVideoIds"
        /** The typed description is searched again after this many newly indexed photos. */
        private const val SMART_RESEARCH_STEP = 200
    }
}
