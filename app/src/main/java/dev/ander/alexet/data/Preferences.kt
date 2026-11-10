package dev.ander.alexet.data

import androidx.compose.runtime.Immutable

/** [SMART]: photos found by a description (the smart search model), best match first. */
enum class SearchMode { FOLDERS, FILES, SMART }

/** Which folders the AI search indexes. */
enum class SmartIndexScope { ALL, ONLY_SELECTED, ALL_EXCEPT }

/** The search lists files (by name, or by description) instead of folders. */
val SearchMode.searchesFiles: Boolean get() = this != SearchMode.FOLDERS

enum class ContentViewMode {
    LAST_FOLDER,
    CURRENT_SESSION,
    MULTI_FOLDER,
    EXPAND_FOLDERS,
    CLICK_DRAG,
}

fun ContentViewMode.usesFolderDrag(): Boolean =
    this == ContentViewMode.MULTI_FOLDER || this == ContentViewMode.CLICK_DRAG

enum class MultiFolderMemory { FRESH, REMEMBER }

enum class SortField {
    NAME,
    PATH,
    SIZE,
    DATE_MODIFIED,
    DATE_TAKEN,
    RANDOM,
}

enum class SortOrder { ASCENDING, DESCENDING }

enum class FileSelectMode { RECTANGLE, LINEAR }

enum class VideoDurationFormat {
    MINUTES,
    HOURS_MINUTES_SECONDS,
}

@Immutable
data class SortSpec(
    val field: SortField = SortField.DATE_TAKEN,
    val order: SortOrder = SortOrder.DESCENDING,
)

@Immutable
data class AppSettings(
    val contentViewMode: ContentViewMode = ContentViewMode.LAST_FOLDER,
    val panelSplitPercent: Int = 40,
    val multiFolderMemory: MultiFolderMemory = MultiFolderMemory.REMEMBER,
    val expandColumns: Int = 6,
    val expandRows: Int = 5,
    val gridColumns: Int = 3,
    /** Columns of the files in an opened folder (and file search) on a phone; [gridColumns] is the folders'. */
    val fileColumns: Int = 3,
    val foldableFileColumns: Int = 3,
    val lastOpenedFolderId: Long = AlbumIds.NONE,
    val rememberedOpenFolders: List<Long> = emptyList(),
    val showFileNames: Boolean = false,
    val videoDurationFormat: VideoDurationFormat = VideoDurationFormat.MINUTES,
    val fileSelectMode: FileSelectMode = FileSelectMode.RECTANGLE,
    val searchFilesFullScreen: Boolean = true,
    val sort: SortSpec = SortSpec(),
    val folderSorts: Map<Long, SortSpec> = emptyMap(),
    val hiddenMediaIds: Set<Long> = emptySet(),
    val hiddenAlbumIds: Set<Long> = emptySet(),
    /** Every folder also shows its other files (documents, archives, apps…). */
    val showAllFiles: Boolean = false,
    /** Folders holding only other files get a tile in the folder list too. */
    val showOtherFileFolders: Boolean = false,
    /** Folders switched to "see all files" one by one (their folder menu). */
    val allFilesFolderIds: Set<Long> = emptySet(),
    val showHidden: Boolean = false,
    val albumOrder: List<Long> = emptyList(),
    val videoDoubleClickToGallery: Boolean = false,
    val nomediaFolderPaths: List<String> = emptyList(),
    val favoriteMediaIds: List<Long> = emptyList(),
    val hideFavoritesFolder: Boolean = false,
    val barActionSlots: List<SelectionAction?> = DEFAULT_BAR_SLOTS,
    val menuActionOrder: List<SelectionAction> = DEFAULT_MENU_ORDER,
    val hiddenSelectionActions: Set<SelectionAction> = emptySet(),
    val rememberFolderScrollAcrossSessions: Boolean = false,
    val newContentScrollsToTop: Boolean = false,
    val savedFolderScrolls: Map<Long, FolderScrollPos> = emptyMap(),
    val autoplayGifs: Boolean = true,
    val playerConfig: dev.ander.alexet.integration.AnderLineConfig = dev.ander.alexet.integration.AnderLineConfig(),
    val showNavArrows: Boolean = false,
    val anonymousFolderIds: Set<Long> = emptySet(),
    val randomizeFileNamesInPrivateFolders: Boolean = true,
    val stripExifInPrivateFolders: Boolean = true,
    val stripExifOnShare: Boolean = false,
    /** When true, rename/move/copy follow selection (tap/marquee) order instead of grid order. */
    val operationsFollowSelectionOrder: Boolean = false,
    /** Per-folder ordered media ids for content-order mode. */
    val folderContentOrders: Map<Long, List<Long>> = emptyMap(),
    /** Custom override for folded (cover) screen resolution in pixels. 0 means auto-detect. */
    val customWallpaperFoldedWidth: Int = 0,
    val customWallpaperFoldedHeight: Int = 0,
    /** Custom override for unfolded (inner) screen resolution in pixels. 0 means auto-detect. */
    val customWallpaperUnfoldedWidth: Int = 0,
    val customWallpaperUnfoldedHeight: Int = 0,
    /** Last real physical resolution detected when the device was folded. */
    val lastDetectedFoldedWidth: Int = 0,
    val lastDetectedFoldedHeight: Int = 0,
    /** Last real physical resolution detected when the device was unfolded. */
    val lastDetectedUnfoldedWidth: Int = 0,
    val lastDetectedUnfoldedHeight: Int = 0,
    val scrollerOnRight: Boolean = false,
    val scrollerEnabled: Boolean = true,
    val scrollerSmooth: Boolean = true,
    /** Grid thumbnails sized to the tile in pixels instead of the fixed light size; device-local, not exported. */
    val highResThumbnails: Boolean = false,
    /** Light mode: no UI animations (a preset: turning it on also turns sharper thumbnails off, which can be re-enabled). Device-local. */
    val lightMode: Boolean = false,
    /** 0 until the folders were shown for the first time; the one-off speed check that may turn on [lightMode] runs only while 0. Device-local. */
    val firstTimeOpened: Int = 0,
    val autoFoldSplitEnabled: Boolean = true,
    val foldSplitAngleThreshold: Float = 20f,
    /** "Dynamic edge": 50/50 while the content pane is empty, [panelSplitPercent] once a folder is shown. Off in [lightMode]. */
    val dynamicSplit: Boolean = true,
    /** "Extra view" settings (two panels) shown without a foldable (switch in the "View" card); fold-only ones stay hidden. */
    val showFoldableSettings: Boolean = false,
    /** A hinge or a fold was seen on this device: its settings are shown. Device-local, not exported. */
    val foldableDetected: Boolean = false,
    /** Relative path of the folder where NewPipe saves videos, such as Gallery/Downloaded-NP. */
    val newpipeFolderRelativePath: String = "",
    /** Imported file references waiting for their files to appear on this phone. */
    val pendingMedia: PendingMediaRefs = PendingMediaRefs(),
    /** Once a day at start, Alexet asks GitHub for a newer release (the only use of the internet). */
    val checkUpdates: Boolean = true,
    /** In-app debug log (see AppLog); device-local, not exported. */
    val debugLogging: Boolean = false,
    /** Text search of photos with the imported AI model (smart search). */
    val smartSearchEnabled: Boolean = false,
    /** Glow and animations of the AI search. */
    val aiEffects: AiEffectsConfig = AiEffectsConfig(),
    val smartIndexScope: SmartIndexScope = SmartIndexScope.ALL,
    /** Folders indexed in [SmartIndexScope.ONLY_SELECTED]. */
    val smartIndexIncludedFolderIds: Set<Long> = emptySet(),
    /** Folders left out in [SmartIndexScope.ALL_EXCEPT]. */
    val smartIndexExcludedFolderIds: Set<Long> = emptySet(),
    /** AI indexing stopped by hand (search keeps working on what is indexed); device-local, not exported. */
    val smartIndexPaused: Boolean = false,
    /** What hidden (.nomedia) folders show instead of their cover picture. */
    val hiddenCover: HiddenCoverConfig = HiddenCoverConfig(),
) {
    companion object {
        val DEFAULT_BAR_SLOTS: List<SelectionAction?> = listOf(
            SelectionAction.DELETE,
            SelectionAction.SHARE,
            SelectionAction.INFO,
            null, // 4th slot for folded
            null, // 5th slot for unfolded (+1)
            null, // 6th slot for unfolded (+2)
        )

        val DEFAULT_MENU_ORDER: List<SelectionAction> = listOf(
            SelectionAction.SET_AS,
            SelectionAction.MOVE,
            SelectionAction.RENAME,
            SelectionAction.EDIT,
            SelectionAction.QUICK_EDIT,
            SelectionAction.COPY,
            SelectionAction.SELECT_ALL,
            SelectionAction.HIDE,
            SelectionAction.FAVORITE,
            SelectionAction.CONTENT_ORDER,
            SelectionAction.SET_PRIVATE,
        )
    }

    fun activeBarActions(wide: Boolean): List<SelectionAction> {
        val padded = (barActionSlots + List(6) { null }).take(6)
        val slots = if (wide) {
            listOf(padded[4], padded[5], padded[0], padded[1], padded[2], padded[3])
        } else {
            padded.take(4)
        }
        return slots.filterNotNull()
            .filter { it !in hiddenSelectionActions }
    }

    fun activeMenuActions(wide: Boolean): List<SelectionAction> {
        val barSet = activeBarActions(wide).toSet()
        val inMenu = menuActionOrder.filter { it !in hiddenSelectionActions && it !in barSet }
        val missing = SelectionAction.DEFAULT_ORDER.filter { it !in hiddenSelectionActions && it !in barSet && it !in inMenu }
        return inMenu + missing
    }

    fun sortFor(albumId: Long, legacyIds: Set<Long> = emptySet()): SortSpec =
        folderSorts[albumId]
            ?: legacyIds.firstNotNullOfOrNull { folderSorts[it] }
            ?: sort

    fun hasFolderSort(albumId: Long, legacyIds: Set<Long> = emptySet()): Boolean =
        (albumId != AlbumIds.NONE && albumId in folderSorts) ||
        legacyIds.any { it in folderSorts }

    fun isAlbumHidden(albumId: Long, legacyIds: Set<Long> = emptySet()): Boolean =
        albumId in hiddenAlbumIds || legacyIds.any { it in hiddenAlbumIds }

    /** [albumId] shows its other files too (everywhere, or switched on for it). */
    fun showsAllFiles(albumId: Long): Boolean = showAllFiles || albumId in allFilesFolderIds

    fun scrollFor(albumId: Long, legacyIds: Set<Long> = emptySet()): FolderScrollPos? =
        savedFolderScrolls[albumId]
            ?: legacyIds.firstNotNullOfOrNull { savedFolderScrolls[it] }
}

object AlphanumericComparator : Comparator<String> {
    private data class NumberedParts(
        val prefix: String,
        val baseNum: Long,
        val tildeNum: Long?
    )

    private fun parseNumbered(name: String): NumberedParts? {
        val len = name.length
        if (len == 0) return null

        // Case 1: Check for ~digits suffix
        var p = len - 1
        while (p >= 0 && name[p].isDigit()) p--
        if (p in 0 until len - 1 && name[p] == '~') {
            val tildeStr = name.substring(p + 1, len)
            val tilde = tildeStr.toLongOrNull() ?: return null
            var q = p - 1
            while (q >= 0 && name[q].isDigit()) q--
            if (q < p - 1) {
                val numStr = name.substring(q + 1, p)
                val baseNum = numStr.toLongOrNull() ?: return null
                val prefix = if (q >= 0) name.substring(0, q + 1) else ""
                return NumberedParts(prefix, baseNum, tilde)
            }
        }

        // Case 2: Check for trailing digits without ~
        var q = len - 1
        while (q >= 0 && name[q].isDigit()) q--
        if (q < len - 1) {
            val numStr = name.substring(q + 1, len)
            val baseNum = numStr.toLongOrNull() ?: return null
            val prefix = if (q >= 0) name.substring(0, q + 1) else ""
            return NumberedParts(prefix, baseNum, null)
        }

        return null
    }

    override fun compare(s1: String?, s2: String?): Int {
        if (s1 === s2) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1
        return compareNatural(sortKey(s1), sortKey(s2))
    }

    /**
     * The name as natural order should see it. "name12~3" (a priority layer) becomes
     * "name" + NUL + (inverted layer) + NUL + "12": before every other "name…" file, higher layers
     * first, then by number. One key per name keeps the order consistent (a comparator with
     * special cases mixed into natural order broke transitivity and made sort() throw).
     */
    private fun sortKey(full: String): String {
        val dot = full.lastIndexOf('.')
        val name = if (dot >= 0) full.substring(0, dot) else full
        val parts = parseNumbered(name) ?: return full
        val tilde = parts.tildeNum ?: return full
        val digitsStart = name.lastIndexOf('~').let { t ->
            var q = t - 1
            while (q >= 0 && name[q].isDigit()) q--
            q + 1
        }
        val baseDigits = name.substring(digitsStart, name.lastIndexOf('~'))
        val ext = if (dot >= 0) full.substring(dot) else ""
        return parts.prefix + '\u0000' + (MAX_LAYER - tilde.coerceIn(0L, MAX_LAYER)) + '\u0000' + baseDigits + ext
    }

    private const val MAX_LAYER = 999_999_999L

    private fun compareNatural(s1: String, s2: String): Int {
        var i1 = 0
        var i2 = 0
        val len1 = s1.length
        val len2 = s2.length
        var leadingZeroDiff = 0

        while (i1 < len1 && i2 < len2) {
            val c1 = s1[i1]
            val c2 = s2[i2]

            if (c1.isDigit() && c2.isDigit()) {
                val start1 = i1
                val start2 = i2
                while (i1 < len1 && s1[i1].isDigit()) i1++
                while (i2 < len2 && s2[i2].isDigit()) i2++

                var numStart1 = start1
                var numStart2 = start2
                while (numStart1 < i1 - 1 && s1[numStart1] == '0') numStart1++
                while (numStart2 < i2 - 1 && s2[numStart2] == '0') numStart2++

                val numLen1 = i1 - numStart1
                val numLen2 = i2 - numStart2

                if (numLen1 != numLen2) {
                    return numLen1 - numLen2
                }

                for (k in 0 until numLen1) {
                    val digitDiff = s1[numStart1 + k] - s2[numStart2 + k]
                    if (digitDiff != 0) return digitDiff
                }

                val totalLen1 = i1 - start1
                val totalLen2 = i2 - start2
                if (leadingZeroDiff == 0 && totalLen1 != totalLen2) {
                    leadingZeroDiff = totalLen1 - totalLen2
                }
            } else {
                val fold1 = c1.lowercaseChar()
                val fold2 = c2.lowercaseChar()
                if (fold1 != fold2) {
                    return fold1.compareTo(fold2)
                }
                i1++
                i2++
            }
        }
        if (i1 < len1) return 1
        if (i2 < len2) return -1
        if (leadingZeroDiff != 0) return leadingZeroDiff
        return len1 - len2
    }
}

fun sortMedia(
    items: List<GalleryMedia>,
    spec: SortSpec,
    randomSeed: Long = 0L,
): List<GalleryMedia> {
    if (items.size <= 1) return items
    if (spec.field == SortField.RANDOM) {
        return items.shuffled(kotlin.random.Random(randomSeed))
    }
    val primaryComparator: Comparator<GalleryMedia> = when (spec.field) {
        SortField.NAME -> compareBy(AlphanumericComparator) { media: GalleryMedia -> media.displayName }
        SortField.PATH -> compareBy(AlphanumericComparator) { media: GalleryMedia -> media.storagePath }
        SortField.SIZE -> compareBy { media: GalleryMedia -> media.sizeBytes }
        SortField.DATE_MODIFIED -> compareBy { media: GalleryMedia -> media.dateModifiedMs }
        SortField.DATE_TAKEN -> compareBy { media: GalleryMedia -> media.capturedAtMs }
        SortField.RANDOM -> return items
    }

    val directedPrimary = if (spec.order == SortOrder.DESCENDING) {
        primaryComparator.reversed()
    } else {
        primaryComparator
    }

    val finalComparator = directedPrimary
        .thenBy(AlphanumericComparator) { it.displayName }
        .thenBy { it.id }

    return items.sortedWith(finalComparator)
}

fun findCoverMedia(
    items: List<GalleryMedia>,
    spec: SortSpec,
): GalleryMedia? {
    if (items.isEmpty()) return null
    if (items.size == 1) return items.first()
    if (spec.field == SortField.RANDOM) {
        return items.firstOrNull()
    }

    val primaryComparator: Comparator<GalleryMedia> = when (spec.field) {
        SortField.NAME -> compareBy(AlphanumericComparator) { media: GalleryMedia -> media.displayName }
        SortField.PATH -> compareBy(AlphanumericComparator) { media: GalleryMedia -> media.storagePath }
        SortField.SIZE -> compareBy { media: GalleryMedia -> media.sizeBytes }
        SortField.DATE_MODIFIED -> compareBy { media: GalleryMedia -> media.dateModifiedMs }
        SortField.DATE_TAKEN -> compareBy { media: GalleryMedia -> media.capturedAtMs }
        SortField.RANDOM -> return items.firstOrNull()
    }

    val directedPrimary = if (spec.order == SortOrder.DESCENDING) {
        primaryComparator.reversed()
    } else {
        primaryComparator
    }

    val finalComparator = directedPrimary
        .thenBy(AlphanumericComparator) { it.displayName }
        .thenBy { it.id }

    return items.minWithOrNull(finalComparator)
}

fun applyAlbumOrder(albums: List<Album>, order: List<Long>): List<Album> {
    if (order.isEmpty() || albums.isEmpty()) return albums
    val rank = order.withIndex().associate { it.value to it.index }
    return albums.sortedBy { album ->
        rank[album.id]
            ?: album.legacyBucketIds.firstNotNullOfOrNull { rank[it] }
            ?: Int.MAX_VALUE
    }
}

fun remapAlbumId(rawId: Long, albums: List<Album>): Long {
    if (rawId == AlbumIds.NONE || rawId == AlbumIds.TRASH || rawId == AlbumIds.FAVORITES) return rawId
    if (albums.any { it.id == rawId }) return rawId
    return albums.firstOrNull { rawId in it.legacyBucketIds }?.id ?: rawId
}

fun remapAlbumIds(ids: List<Long>, albums: List<Album>): List<Long> {
    val seen = HashSet<Long>()
    return ids.map { remapAlbumId(it, albums) }.filter { seen.add(it) }
}

fun remapHiddenAlbumIds(ids: Set<Long>, albums: List<Album>): Set<Long> {
    val seen = LinkedHashSet<Long>()
    ids.forEach { seen += remapAlbumId(it, albums) }
    return seen
}

fun remapFolderSorts(sorts: Map<Long, SortSpec>, albums: List<Album>): Map<Long, SortSpec> {
    if (sorts.isEmpty()) return sorts
    val currentIds = albums.map { it.id }.toHashSet()
    val remapped = LinkedHashMap<Long, SortSpec>()
    for ((id, spec) in sorts) {
        if (id !in currentIds) {
            remapped[remapAlbumId(id, albums)] = spec
        }
    }
    for ((id, spec) in sorts) {
        if (id in currentIds) remapped[id] = spec
    }
    return remapped
}

fun remapFolderScrolls(
    scrolls: Map<Long, FolderScrollPos>,
    albums: List<Album>,
): Map<Long, FolderScrollPos> {
    if (scrolls.isEmpty()) return scrolls
    val currentIds = albums.map { it.id }.toHashSet()
    val remapped = LinkedHashMap<Long, FolderScrollPos>()
    for ((id, pos) in scrolls) {
        if (id !in currentIds) {
            remapped[remapAlbumId(id, albums)] = pos
        }
    }
    for ((id, pos) in scrolls) {
        if (id in currentIds) {
            remapped[id] = pos
        }
    }
    return remapped
}

fun albumIdsIncludingLegacy(albums: Iterable<Album>): Set<Long> {
    val ids = LinkedHashSet<Long>()
    albums.forEach { album ->
        ids += album.id
        ids.addAll(album.legacyBucketIds)
    }
    return ids
}
