package dev.ander.alexet.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import android.app.DownloadManager
import android.app.RecoverableSecurityException
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.ensureActive

private data class PendingMove(
    val oldMediaId: Long,
    val srcBucketId: Long,
    val destBucketId: Long,
    val destBucketName: String,
    val destRelativePath: String,
    val destAbsolutePath: String? = null,
    /**
     * MediaStore BUCKET_ID of the destination directory. Relocated copies must carry it:
     * a copy keeping the source rawBucketId still "belongs" to F1 via legacyBucketIds.
     */
    val destRawBucketId: Long = 0L,
    val displayName: String,
    /** Original name/path in F1 — used to hide MediaStore orphans after renameTo. */
    val srcDisplayName: String = displayName,
    val srcAbsolutePath: String? = null,
    val movedAt: Long = android.os.SystemClock.elapsedRealtime(),
)

/** Keep gallery tiles hidden until MediaStore stops resurrecting trashed/deleted rows. */
private data class PendingDelete(
    val id: Long,
    val displayName: String,
    val absolutePath: String? = null,
    val bucketId: Long = 0L,
    val markedAt: Long = android.os.SystemClock.elapsedRealtime(),
)

/**
 * Hash lookups over the pending deletes. Checking every gallery row against every pending delete
 * one by one froze the app (ANR) when two folders of 8k files each were deleted at once.
 */
private class PendingDeleteIndex(val version: Int, deletes: Collection<PendingDelete>) {
    private val paths = HashSet<String>()
    private val bucketNames = HashSet<String>()
    private val pathlessNames = HashSet<String>()

    init {
        for (pd in deletes) {
            val name = pd.displayName.lowercase()
            if (pd.absolutePath != null) paths += pd.absolutePath else pathlessNames += name
            if (pd.bucketId != 0L) bucketNames += "${pd.bucketId}:$name"
        }
    }

    /** [path] and [name] lowercased. */
    fun matches(path: String?, name: String, bucketId: Long): Boolean =
        (path != null && path in paths) ||
            (bucketId != 0L && "$bucketId:$name" in bucketNames) ||
            (path == null && name in pathlessNames)
}

/**
 * Keys of a row as a possible source orphan of a move: its folder plus its id, path or name.
 * A row is the orphan of a move when the two key sets meet: it is in the move's source folder
 * and has the moved id, the source path, or the old or new file name.
 */
private fun srcRowKeys(item: GalleryMedia): List<String> {
    val b = item.bucketId
    val path = item.absolutePath?.lowercase()
    val name = item.displayName.lowercase()
    return if (path != null) listOf("$b#${item.id}", "$b|$path", "$b:$name") else listOf("$b#${item.id}", "$b:$name")
}

private fun moveOrphanKeys(oldId: Long, move: PendingMove): List<String> {
    val b = move.srcBucketId
    return listOfNotNull(
        "$b#$oldId",
        move.srcAbsolutePath?.let { "$b|$it" },
        "$b:${move.srcDisplayName.lowercase()}",
        "$b:${move.displayName.lowercase()}",
    )
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GalleryRepository(context: Context, private val settings: SettingsStore) {

    private val appContext = context.applicationContext
    /**
     * Picking a free name and taking it are one step: two copies running at once (each takes
     * seconds for a video) chose the same free name, and the second rename replaced the first copy.
     */
    private val fileNameLock = Any()
    private val resolver: ContentResolver = appContext.contentResolver
    private val nomediaScanner = NomediaScanner(appContext)
    private val scanHidden = AtomicBoolean(false)
    @Volatile private var nomediaCache: NomediaScanResult? = null
    /**
     * Held while a quick (index-only) result checks that nothing is cached and stores itself, and
     * while a full scan stores its result: a quick read finishing late no longer replaces the full
     * scan's result with its shorter one.
     */
    private val nomediaCacheLock = Any()

    /** Stores [result] only while nothing is cached; false when something already was. */
    private fun offerQuickNomedia(result: NomediaScanResult?): Boolean = synchronized(nomediaCacheLock) {
        if (nomediaCache != null) return false
        nomediaCache = result
        nomediaReadAt = android.os.SystemClock.elapsedRealtime()
        true
    }

    /**
     * Takes a fresh read of the hidden folders: cached, and put into the lists unless they already
     * hold the same (see [HiddenFolderMarker.intake]).
     * @param onlyIfNothingCached Store it only while nothing is cached (a quick read must not replace
     *   a walk that finished meanwhile).
     * @return whether the cached read changed; null when it was not taken.
     */
    private fun takeInHiddenRead(fresh: NomediaScanResult, onlyIfNothingCached: Boolean = false): Boolean? {
        val intake = synchronized(nomediaCacheLock) {
            if (onlyIfNothingCached && nomediaCache != null) return null
            intakeLocked(fresh)
        }
        intake.publish?.let { publishNomedia(it) }
        return intake.changed
    }

    /** [takeInHiddenRead] without the publishing; called holding [nomediaCacheLock]. */
    private fun intakeLocked(fresh: NomediaScanResult): HiddenFolderMarker.Intake {
        nomediaReadAt = android.os.SystemClock.elapsedRealtime()
        return HiddenFolderMarker.intake(nomediaCache, markedWith, fresh).also { nomediaCache = it.keep }
    }

    /** When every hidden folder was last read from disk (elapsedRealtime), 0 when never. */
    @Volatile private var nomediaReadAt = 0L
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        dev.ander.alexet.data.AppLog.e("GalleryRepository", "Uncaught coroutine exception", throwable)
    }
    private val scanDispatcher = Dispatchers.IO.limitedParallelism(2)
    /**
     * Chores nothing on screen waits for (starting the folder watchers, the walk for hidden
     * folders): low-priority threads, so the frames being drawn get the fast cores first.
     */
    private val choresDispatcher = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "alexet-chores").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val queryDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val queryMutex = kotlinx.coroutines.sync.Mutex()
    private val folderLoadTimestamps = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val folderDiskState = FolderDiskState.of(appContext)
    @Volatile private var lastMediaStoreChangeAt = 0L
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)
    private val scanScope = CoroutineScope(SupervisorJob() + scanDispatcher + coroutineExceptionHandler)
    private var hiddenScanJob: Job? = null
    @Volatile private var nomediaFullScanAt = 0L
    private val nomediaScanEpoch = java.util.concurrent.atomic.AtomicInteger(0)
    private val pendingMoves = java.util.concurrent.ConcurrentHashMap<Long, PendingMove>()
    private val pendingDeletes = java.util.concurrent.ConcurrentHashMap<Long, PendingDelete>()
    /** Bumped after every change of [pendingDeletes]; the cached index is rebuilt when stale. */
    private val pendingDeletesVersion = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var pendingDeleteIndexCache: PendingDeleteIndex? = null
    /** Bumped by optimistic move/delete patches; a full refresh started before a bump is stale. */
    private val mutationEpoch = java.util.concurrent.atomic.AtomicInteger(0)
    /**
     * Held while a sync result is checked against [mutationEpoch] and published, and while a patch
     * bumps the epoch and changes the list: either the result goes out first and the patch lands on
     * it, or the result sees the new epoch and is dropped. Both steps are short.
     */
    private val publishLock = Any()

    /**
     * The list last published with its hidden-folder rows marked, and the read of the hidden folders
     * it was marked with. A delta on that very list with that very read only marks its new rows;
     * any other change of the list (a patch, a merge) gives a new list, which is marked in full.
     */
    @Volatile private var markedList: List<GalleryMedia>? = null
    @Volatile private var markedWith: NomediaScanResult? = null

    /**
     * An optimistic change of the library list (and, with [alsoFolders], of the open folders' lists;
     * only for transforms that change items in place, never move them between folders).
     */
    private fun patchMedia(alsoFolders: Boolean = false, transform: (List<GalleryMedia>) -> List<GalleryMedia>) {
        synchronized(publishLock) {
            mutationEpoch.incrementAndGet()
            _currentMedia.update(transform)
            if (alsoFolders) {
                _folderMedia.update { map ->
                    var changed = false
                    val next = map.mapValues { (_, list) ->
                        val updated = transform(list)
                        if (updated == list) list else updated.also { changed = true }
                    }
                    if (changed) next else map
                }
            }
        }
    }
    private val _seedAlbums = MutableStateFlow<List<Album>>(emptyList())
    /** Stale album grid for cold start / process death (from [AlbumCache]). */
    val seedAlbums: StateFlow<List<Album>> = _seedAlbums.asStateFlow()
    @Volatile private var lastRecoverableSender: android.content.IntentSender? = null
    private val watchLock = Any()
    private val dirObservers = LinkedHashMap<String, FileObserver>()
    private val pinnedWatchPaths = HashSet<String>()
    private val activeAlbumDirs = LinkedHashSet<String>()
    @Volatile private var newpipeWatchPath: String? = null
    private val growthSnapshots = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val watchPendingPaths = LinkedHashSet<String>()
    private val watchSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    @Volatile private var watching = false
    /** ContentObserver fired while backgrounded; coalesced on startForegroundWatch. */
    @Volatile private var mediaDirtyWhileBackgrounded = false
    @Volatile private var watchHadDelete = false
    private var foregroundWatchJob: Job? = null
    private var watchSetupJob: Job? = null
    private var downloadReceiver: BroadcastReceiver? = null
    private var downloadsMergeJob: Job? = null
    private var downloadTickJob: Job? = null
    private val downloadTracker = DownloadTracker(
        isPublished = ::isPublishedInMediaStore,
        isComplete = { path -> FileCompleteness.check(path) },
    )
    val activeDownloads: StateFlow<Map<String, ActiveDownload>> = downloadTracker.active
    private val lastDownloadManagerPaths = HashSet<String>()
    private val downloaderSignals = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    private var downloaderJob: Job? = null
    private var downloaderObserved = false
    private val downloaderObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            downloaderSignals.tryEmit(Unit)
        }
    }

    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** What MediaStore's notifications named since the last sync took them (Android 11+). */
    private val mediaChanges = MediaChangeQueue()
    /** When the first change signal not yet synced came in (elapsedRealtime), 0 when none. */
    private val firstChangeAt = java.util.concurrent.atomic.AtomicLong(0L)
    /** Set by [requestRefresh] (full = true); consumed by the next sync. */
    private val fullRefreshRequested = AtomicBoolean(false)
    /** Set by [requestRefresh]: the next sync runs even when no notification named a listed row. */
    private val explicitRefresh = AtomicBoolean(false)
    /** Syncs on exact changes since the last one that checked every id, and when that one was. */
    private val exactSyncs = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastFullCheckAt = 0L

    /** A check of every id now and then: a notification can get lost (Alexet frozen in the background). */
    private fun fullCheckDue(): Boolean =
        exactSyncs.get() >= EXACT_SYNCS_PER_FULL_CHECK ||
            android.os.SystemClock.elapsedRealtime() - lastFullCheckAt >= FULL_CHECK_MS
    private val mediaIndex = MediaIndexStore(appContext)
    /** Guards [persisted] and every write to [mediaIndex]. */
    private val indexMutex = kotlinx.coroutines.sync.Mutex()
    /** Rows exactly as last written to [mediaIndex]; the journal diffs published lists against it. */
    private var persisted: HashMap<Long, GalleryMedia> = HashMap()
    @Volatile private var indexReady = false
    private val _currentMedia = MutableStateFlow<List<GalleryMedia>>(emptyList())
    val media: StateFlow<List<GalleryMedia>> = _currentMedia.asStateFlow()
    private val _folderMedia = MutableStateFlow<Map<Long, List<GalleryMedia>>>(emptyMap())
    /** Last album list written to [AlbumCache]; equal lists are not written again. */
    @Volatile private var lastSavedAlbums: List<Album>? = null
    val folderMedia: StateFlow<Map<Long, List<GalleryMedia>>> = _folderMedia.asStateFlow()
    private val _isScanning = MutableStateFlow(true)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Re-checks a tracked file on the spot, so a finished one opens at once. */
    fun isDownloading(item: GalleryMedia): Boolean =
        downloadTracker.isStillDownloading(item.absolutePath)

    /**
     * Files Alexet itself is moving, copying or rewriting: normalized path -> grace deadline (elapsedRealtime).
     * Their filesystem events are not downloads, so they must never lock the file in the grid or viewer.
     */
    private val ownWrites = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun markOwnWrite(file: File) {
        val now = android.os.SystemClock.elapsedRealtime()
        // Expired entries are otherwise only dropped when the same path is looked up again.
        if (ownWrites.size > 256) ownWrites.entries.removeIf { it.value < now }
        ownWrites[DownloadMediaFilter.normalizePath(file.absolutePath)] = now + OWN_WRITE_GRACE_MS
    }

    /** For other components that write files (trash restore): see [markOwnWrite]. */
    fun noteOwnWrite(file: File) = markOwnWrite(file)

    /** See [finishOwnWrite]. */
    fun endOwnWrite(file: File) = finishOwnWrite(file)

    /** Re-arms the grace window once the write is done and drops anything the watcher already recorded. */
    private fun finishOwnWrite(file: File) {
        markOwnWrite(file)
        downloadTracker.onDeleted(file.absolutePath)
    }

    private fun isOwnWrite(path: String): Boolean {
        if (ownWrites.isEmpty()) return false
        val key = DownloadMediaFilter.normalizePath(path)
        val until = ownWrites[key] ?: return false
        if (android.os.SystemClock.elapsedRealtime() <= until) return true
        ownWrites.remove(key, until)
        return false
    }

    fun setScanHidden(enabled: Boolean) {
        if (scanHidden.getAndSet(enabled) == enabled) return
        if (!enabled) {
            requestRefresh()
            return
        }
        // Called from ViewModel init inside the first composition: the hidden-folder walk must not block the frame.
        ioScope.launch {
            if (nomediaCache == null) {
                quickNomediaFromIndex()?.let { takeInHiddenRead(it, onlyIfNothingCached = true) }
            }
            scheduleDeferredNomediaScan()
            requestRefresh()
        }
    }

    /** All-files access as last seen; null before the first look. */
    @Volatile private var hadAllFilesAccess: Boolean? = null

    /**
     * Called when the app comes back (the user may have granted all-files access in the system
     * screen): once it is granted, the hidden folders are walked and MediaStore read in full now.
     * A walk made without access found nothing, and the next one was up to 30 minutes away.
     */
    fun onAllFilesAccessMaybeChanged() {
        val now = StorageAccess.canReadAllFiles()
        val before = hadAllFilesAccess
        hadAllFilesAccess = now
        if (before == false && now) {
            AppLog.event("Hidden") { "all-files access granted: hidden folders walked again" }
            rescanHiddenNow()
            requestRefresh(full = true)
        }
    }

    /** @param full Re-read every MediaStore row instead of only the changed ones (manual refresh). */
    fun requestRefresh(scanDisk: Boolean = false, force: Boolean = false, full: Boolean = false) {
        if (scanDisk) {
            publishWaitingFiles(if (force) "manual" else "resume")
            // Files other apps put into hidden folders meanwhile: MediaStore doesn't list them.
            rereadHiddenFoldersLater()
        }
        if (full) fullRefreshRequested.set(true)
        explicitRefresh.set(true)
        refresh.tryEmit(Unit)
        scheduleOtherFilesRefresh()
    }

    fun startForegroundWatch() {
        val needsCoalescedRefresh: Boolean
        synchronized(watchLock) {
            if (watching) return
            watching = true
            needsCoalescedRefresh = mediaDirtyWhileBackgrounded
            mediaDirtyWhileBackgrounded = false
        }
        registerWatchReceiver()
        synchronized(watchLock) {
            // Called from ON_START on the main thread; listing the watched trees stats thousands
            // of files and blocked the return transition from other apps (NextPlayer) for seconds.
            watchSetupJob?.cancel()
            watchSetupJob = ioScope.launch(choresDispatcher) {
                startDirObservers()
                applyNewpipeFolder(settings.settings.value.newpipeFolderRelativePath)
                watchDownloader()
                pollDownloader()
            }
            if (downloaderJob?.isActive != true) {
                downloaderJob = ioScope.launch {
                    downloaderSignals.collect { pollDownloader() }
                }
            }
            if (foregroundWatchJob?.isActive != true) {
                foregroundWatchJob = ioScope.launch {
                    watchSignals.debounce(DownloadsWatchLogic.DEBOUNCE_MS).collect {
                        processWatchBatch()
                    }
                }
            }
            if (downloadTickJob?.isActive != true) {
                downloadTickJob = ioScope.launch { runDownloadTrackerLoop() }
            }
        }
        if (needsCoalescedRefresh) {
            mediaChanges.markUnknown()
            requestRefresh()
        }
    }

    fun stopForegroundWatch() {
        synchronized(watchLock) {
            if (!watching && downloadReceiver == null && dirObservers.isEmpty()) return
            watching = false
            watchSetupJob?.cancel()
            watchSetupJob = null
            foregroundWatchJob?.cancel()
            foregroundWatchJob = null
            downloadTickJob?.cancel()
            downloadTickJob = null
            downloaderJob?.cancel()
            downloaderJob = null
            watchPendingPaths.clear()
            watchHadDelete = false
        }
        stopDirObservers()
        unregisterWatchReceiver()
        unwatchDownloader()
    }

    /** Observing doesn't start the downloader's process. Refused while it isn't installed: tried again on the next start. */
    private fun watchDownloader() {
        synchronized(downloaderObserver) {
            if (downloaderObserved || !watching) return
            downloaderObserved = runCatching {
                appContext.contentResolver.registerContentObserver(
                    dev.ander.alexet.integration.DownloaderProgressClient.missionsUri(),
                    false,
                    downloaderObserver,
                )
            }.isSuccess
        }
    }

    private fun unwatchDownloader() {
        synchronized(downloaderObserver) {
            if (!downloaderObserved) return
            downloaderObserved = false
            runCatching { appContext.contentResolver.unregisterContentObserver(downloaderObserver) }
        }
    }

    /** Only downloads into the NewPipe folder are followed. Runs off the main thread: see [DownloaderProgressClient.read]. */
    private fun pollDownloader() {
        val rows = dev.ander.alexet.integration.DownloaderProgressClient.read(appContext).orEmpty()
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()?.absolutePath ?: return
        val folder = newpipeWatchPath
        downloadTracker.onDownloaderMissions(
            rows.mapNotNull { row ->
                downloaderMissionPath(root, folder, row.relativePath)?.let { path ->
                    DownloaderMission(path, row.bytes, row.total, row.state)
                }
            },
        )
    }

    private fun startDirObservers() {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory() ?: return
        val tops = listOf(
            File(root, Environment.DIRECTORY_DOWNLOADS),
            File(root, Environment.DIRECTORY_MOVIES),
            File(root, Environment.DIRECTORY_DCIM),
            File(root, Environment.DIRECTORY_PICTURES),
        )
        val startedAt = android.os.SystemClock.elapsedRealtime()
        for (top in tops) {
            if (!top.isDirectory) continue
            startWatchingDir(top, pin = true)
            watchChildDirs(top, depth = 1, maxDepth = 2)
        }
        AppLog.event("Watch") {
            "folder watchers: ${synchronized(watchLock) { dirObservers.size }} in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
        }
    }

    private fun watchChildDirs(dir: File, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val subfolders = watchSubfolders.get(dir) ?: return
        for (name in subfolders) {
            val child = File(dir, name)
            startWatchingDir(child)
            watchChildDirs(child, depth + 1, maxDepth)
        }
    }

    /**
     * Sub-folders of the watched trees, kept while each folder's date stays: starting the watchers
     * on every return to the app took 1-2.7 s (a stat for each of Camera's 4.4k files).
     */
    private val watchSubfolders = DirSnapshotCache(::readSubfolders)

    /**
     * [dir]'s sub-folders (not hidden ones). Names of photos, videos and known kinds of files
     * (documents, archives, apps, audio) need no stat; any other name is checked.
     */
    private fun readSubfolders(dir: File): List<String>? {
        val names = dir.list() ?: return null
        return names.filter { name ->
            !name.startsWith(".") && NomediaScanner.mimeOf(name) == null &&
                OtherFiles.kindOf(name) == OtherFiles.Kind.OTHER &&
                DirSnapshotCache.entryOf(dir, name)?.isDirectory == true
        }
    }

    private fun stopDirObservers() {
        val observers: List<FileObserver>
        synchronized(watchLock) {
            observers = dirObservers.values.toList()
            dirObservers.clear()
        }
        observers.forEach { observer ->
            runCatching { observer.stopWatching() }
        }
    }

    private fun startWatchingDir(dir: File, pin: Boolean = false) {
        if (!watching || !dir.isDirectory) return
        val key = dir.absolutePath
        val evicted = ArrayList<FileObserver>()
        var created: FileObserver? = null
        synchronized(watchLock) {
            if (!watching) return
            if (pin) pinnedWatchPaths += key
            if (dirObservers.containsKey(key)) return
            var room = true
            while (dirObservers.size >= MAX_WATCH_DIRS) {
                val victim = dirObservers.keys.firstOrNull { it !in pinnedWatchPaths }
                if (victim == null) {
                    room = false
                    break
                }
                dirObservers.remove(victim)?.let { evicted += it }
            }
            if (room) {
                created = newDirObserver(dir)
                dirObservers[key] = created
            }
        }
        evicted.forEach { existing -> runCatching { existing.stopWatching() } }
        created?.let { observer -> runCatching { observer.startWatching() } }
    }

    /**
     * NewPipe and other apps save outside Download/DCIM/Movies/Pictures.
     * Watch the folder the user is looking at, and treat a growing media file as in progress.
     */
    private fun watchOpenAlbum(album: Album?, sample: GalleryMedia?) {
        if (!watching || album?.isSecure == true) return
        val dir = album?.let { directoryForAlbum(it) }
            ?: sample?.absolutePath?.let { File(it).parentFile }
            ?: return
        if (!dir.isDirectory) return
        val key = dir.absolutePath
        startWatchingDir(dir, pin = true)
        synchronized(watchLock) {
            activeAlbumDirs.remove(key)
            activeAlbumDirs.add(key)
            while (activeAlbumDirs.size > 4) {
                val oldest = activeAlbumDirs.first()
                activeAlbumDirs.remove(oldest)
                if (!isStandardWatchRoot(oldest) && oldest != newpipeWatchPath) {
                    pinnedWatchPaths.remove(oldest)
                }
            }
        }
        snapshotAlbumDir(dir)
    }

    private val newpipeLock = Any()

    /**
     * One at a time and always for the folder set now: two calls running at once (a settings change
     * and the app coming back) left the active NewPipe folder unwatched until the next start.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun applyNewpipeFolder(relative: String) = synchronized(newpipeLock) {
        val rel = settings.settings.value.newpipeFolderRelativePath.trim().trim('/')
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        val dir = if (rel.isEmpty() || root == null) {
            null
        } else {
            nomediaScanner.directoryForRelativePath(rel)
                ?: File(root, rel).takeIf { it.isDirectory }
        }
        val next = dir?.absolutePath
        val previous = newpipeWatchPath
        newpipeWatchPath = next
        if (previous != null && previous != next) {
            val stillNeeded = synchronized(watchLock) {
                previous in activeAlbumDirs || isStandardWatchRoot(previous)
            }
            if (!stillNeeded) {
                synchronized(watchLock) { pinnedWatchPaths.remove(previous) }
                dropWatchingDir(previous)
            }
        }
        if (dir != null && dir.isDirectory) {
            startWatchingDir(dir, pin = true)
            snapshotAlbumDir(dir)
        }
    }

    private fun isStandardWatchRoot(path: String): Boolean {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory() ?: return false
        return listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES,
        ).any { File(root, it).absolutePath == path }
    }

    /**
     * Download-candidate files of [dir] (dated [date]) with their sizes: the name is checked before
     * any stat, folders no download lands in (Camera, Screenshots...) are not listed at all, and
     * files MediaStore lists as finished are left out (a download in progress is not one of them).
     * One stat per remaining file: Memes (4.8k files) took 1.8 s with two stats for every file.
     */
    private inline fun forEachDownloadCandidate(dir: File, date: Long, action: (path: String, size: Long) -> Unit) {
        if (!DownloadMediaFilter.isMediaDownload(dir.absolutePath + "/probe.mp4")) return
        val names = listNames(dir, date) ?: return
        val finished = finishedNamesIn(dir)
        for (name in names) {
            if (!DownloadMediaFilter.isMediaDownload(name)) continue
            if (finished.isNotEmpty() && name.lowercase() in finished) continue
            val entry = DirSnapshotCache.entryOf(dir, name) ?: continue
            if (!entry.isFile) continue
            action(File(dir, name).absolutePath, entry.size)
        }
    }

    private fun snapshotAlbumDir(dir: File) {
        val date = DirSnapshotCache.folderDate(dir) ?: return
        forEachDownloadCandidate(dir, date) { path, size ->
            growthSnapshots.putIfAbsent(path, size)
        }
    }

    /**
     * The last few folders' names as listed, while their date stays: entering a folder listed it
     * three times (size snapshot, quick scan, download poll); now once.
     */
    private val recentListings = object : LinkedHashMap<String, Pair<Long, Array<String>>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Array<String>>>?) = size > RECENT_LISTINGS
    }

    /** The names in [dir] (dated [date], from before the listing); null when it cannot be read. */
    private fun listNames(dir: File, date: Long): Array<String>? {
        val key = dir.absolutePath
        synchronized(recentListings) {
            recentListings[key]?.takeIf { it.first == date }?.let { return it.second }
        }
        val names = dir.list() ?: return null
        synchronized(recentListings) { recentListings[key] = date to names }
        return names
    }

    /** The library list [finishedIndex] was built from (by identity). */
    @Volatile private var finishedIndexOf: List<GalleryMedia>? = null
    @Volatile private var finishedIndex: Map<String, Set<String>> = emptyMap()

    /**
     * Lowercased names of the files in [dir] that MediaStore lists as finished (its published rows;
     * pending ones are not among them). Built once per library list.
     */
    private fun finishedNamesIn(dir: File): Set<String> {
        val library = _currentMedia.value
        if (library !== finishedIndexOf) {
            val map = HashMap<String, HashSet<String>>()
            for (item in library) {
                if (!MediaIndexLogic.isMediaStoreRow(item)) continue
                val path = item.absolutePath ?: continue
                val slash = path.lastIndexOf('/')
                if (slash <= 0) continue
                map.getOrPut(path.substring(0, slash).lowercase()) { HashSet() } += path.substring(slash + 1).lowercase()
            }
            finishedIndex = map
            finishedIndexOf = library
        }
        return finishedIndex[dir.absolutePath.lowercase()].orEmpty()
    }

    /** Per polled folder: its date and candidate files at the last full listing. */
    private class PolledDir(val date: Long, val listedAtMs: Long, val paths: List<String>)
    private val polledDirs = java.util.concurrent.ConcurrentHashMap<String, PolledDir>()

    private fun pollGrowingFiles() {
        // Only entries that were there when this poll began may be dropped below: one added
        // meanwhile (a folder just opened) has not been looked at yet.
        val before = HashSet(growthSnapshots.keys)
        val configured = newpipeWatchPath
        val dirs = synchronized(watchLock) { activeAlbumDirs.toList() }
        val all = if (configured != null && configured !in dirs) dirs + configured else dirs
        val idle = downloadTracker.active.value.isEmpty()
        val now = android.os.SystemClock.elapsedRealtime()
        val live = HashSet<String>()
        for (dirPath in all) {
            val dir = File(dirPath)
            val date = DirSnapshotCache.folderDate(dir) ?: continue
            val last = polledDirs[dirPath]
            // Nothing downloading and no file added or removed since the last listing: a folder of
            // thousands of files is not listed and stat-ed again every few seconds. The folder
            // watchers report writes; a full look still happens now and then.
            if (idle && last != null && last.date == date && now - last.listedAtMs < IDLE_FULL_POLL_MS) {
                live += last.paths
                continue
            }
            val paths = ArrayList<String>()
            forEachDownloadCandidate(dir, date) { path, size ->
                paths += path
                live += path
                val prev = growthSnapshots.put(path, size)
                if (prev != null && size != prev && !isOwnWrite(path)) {
                    if (downloadTracker.isActive(path)) downloadTracker.onModified(path)
                    else downloadTracker.onCreated(path)
                }
            }
            polledDirs[dirPath] = PolledDir(date, now, paths)
        }
        polledDirs.keys.retainAll(all.toSet())
        if (live.isNotEmpty()) {
            for (key in before) {
                if (key !in live && !downloadTracker.isActive(key)) growthSnapshots.remove(key)
            }
        }
    }

    private fun dropWatchingDir(path: String) {
        val observer = synchronized(watchLock) { dirObservers.remove(path) }
        observer?.let { runCatching { it.stopWatching() } }
    }

    private fun newDirObserver(dir: File): FileObserver {
        val mask = FileObserver.CREATE or FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
            FileObserver.DELETE or FileObserver.DELETE_SELF or FileObserver.MODIFY
        val emit: (Int, String?) -> Unit = { event, path -> handleFsEvent(dir, event, path) }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = emit(event, path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = emit(event, path)
            }
        }
    }

    private fun handleFsEvent(dir: File, event: Int, path: String?) {
        if (!watching) return
        if (otherFileDirs.isNotEmpty()) scheduleOtherFilesRefresh()
        val ev = event and FileObserver.ALL_EVENTS
        val target = if (path.isNullOrEmpty()) dir else File(dir, path)
        when (ev) {
            FileObserver.CREATE -> {
                if (target.isDirectory) {
                    startWatchingDir(target)
                } else {
                    if (!isOwnWrite(target.absolutePath)) downloadTracker.onCreated(target.absolutePath)
                    enqueueWatchPath(target.absolutePath, isDelete = false)
                }
            }
            FileObserver.MOVED_TO -> {
                if (target.isDirectory) {
                    startWatchingDir(target)
                } else {
                    if (!isOwnWrite(target.absolutePath)) downloadTracker.onMovedTo(target.absolutePath)
                    enqueueWatchPath(target.absolutePath, isDelete = false)
                }
            }
            FileObserver.MODIFY -> {
                if (!target.isDirectory && !isOwnWrite(target.absolutePath)) {
                    downloadTracker.onModified(target.absolutePath)
                }
            }
            FileObserver.CLOSE_WRITE -> {
                if (!target.isDirectory) {
                    if (!isOwnWrite(target.absolutePath)) downloadTracker.onCloseOrMovedTo(target.absolutePath)
                    enqueueWatchPath(target.absolutePath, isDelete = false)
                }
            }
            FileObserver.MOVED_FROM, FileObserver.DELETE -> {
                dropWatchingDir(target.absolutePath)
                downloadTracker.onDeleted(target.absolutePath)
                enqueueWatchPath(target.absolutePath, isDelete = true)
            }
            FileObserver.DELETE_SELF -> {
                dropWatchingDir(dir.absolutePath)
                downloadTracker.onDeleted(dir.absolutePath)
                enqueueWatchPath(dir.absolutePath, isDelete = true)
            }
        }
    }

    private fun enqueueWatchPath(path: String, isDelete: Boolean) {
        if (!watching || path.isBlank()) return
        synchronized(watchLock) {
            if (!watching) return
            DownloadsWatchLogic.addPendingPaths(watchPendingPaths, listOf(path))
            if (isDelete) watchHadDelete = true
        }
        watchSignals.tryEmit(Unit)
    }

    private fun processWatchBatch() {
        if (!watching) return
        val paths: List<String>
        val hadDelete: Boolean
        synchronized(watchLock) {
            paths = watchPendingPaths.toList()
            watchPendingPaths.clear()
            hadDelete = watchHadDelete
            watchHadDelete = false
        }
        val mediaPaths = DownloadsWatchLogic.filterMediaPaths(paths)
        // Alexet reads hidden folders itself, MediaStore doesn't list them: told of other apps' files there.
        onHiddenFilesChanged(mediaPaths.filterNot { isOwnWrite(it) })
        val toScan = mediaPaths.filter { path ->
            // A file being downloaded is scanned once it is finished (onDownloadReady), not half
            // written; and on Android 11+ a removed file's row is dropped by Android itself.
            !downloadTracker.isActive(path) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || File(path).exists())
        }
        val sent = scanOnce(toScan, toScan.map { DownloadsWatchLogic.mediaMimeOf(it) }) {
            if (watching) requestRefresh()
        }
        if (sent == 0 && hadDelete) {
            requestRefresh()
        }
    }

    /** Paths [scanOnce] handed to the scanner (normalized) -> when (elapsedRealtime). */
    private val recentScans = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Hands [paths] to the scanner, except those handed to it in the last [SCAN_ONCE_MS]: the
     * download tracker (twice for one file at times), the folder watcher and DownloadManager's
     * broadcast each scanned the same new file, and each scan was a MediaStore change and a sync.
     * [onDone] runs when the scanner is through the ones sent; never when none was.
     * @return how many were sent.
     */
    private fun scanOnce(paths: List<String>, mimes: List<String?>, onDone: () -> Unit): Int {
        if (paths.isEmpty()) return 0
        val now = android.os.SystemClock.elapsedRealtime()
        if (recentScans.size > 256) recentScans.entries.removeIf { now - it.value >= SCAN_ONCE_MS }
        val sendPaths = ArrayList<String>(paths.size)
        val sendMimes = ArrayList<String?>(paths.size)
        paths.forEachIndexed { index, path ->
            val key = DownloadMediaFilter.normalizePath(path)
            val last = recentScans[key]
            if (last != null && now - last < SCAN_ONCE_MS) return@forEachIndexed
            recentScans[key] = now
            sendPaths += path
            sendMimes += mimes.getOrNull(index)
        }
        if (sendPaths.isEmpty()) {
            AppLog.event("Sync") { "scan skipped: ${paths.size} file(s) scanned moments ago" }
            return 0
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(sendPaths.size)
        MediaScannerConnection.scanFile(appContext, sendPaths.toTypedArray(), sendMimes.toTypedArray()) { _, _ ->
            if (remaining.decrementAndGet() == 0) onDone()
        }
        return sendPaths.size
    }

    private fun onDownloadReady(path: String) {
        onHiddenFilesChanged(listOf(path))
        if (recentlyPublishedWaiting(path)) {
            requestRefresh()
            return
        }
        scanOnce(listOf(path), listOf(DownloadsWatchLogic.mediaMimeOf(path))) { requestRefresh() }
        requestRefresh()
    }

    private suspend fun runDownloadTrackerLoop() {
        seedRecentInProgressWrites()
        pollDownloadManager()
        syncPendingDownloads()
        pollGrowingFiles()
        var idleSeconds = 0
        var busyTicks = 0
        while (watching) {
            if (downloadTracker.active.value.isNotEmpty()) {
                // Something downloads: its files are looked at every quarter second (a finished
                // one is ready that much sooner); folders and the manager still once a second.
                delay(DownloadTracker.ACTIVE_TICK_MS)
                if (!watching) return
                idleSeconds = 0
                downloadTracker.tick()
                if (++busyTicks < BUSY_TICKS_PER_POLL) continue
                busyTicks = 0
                pollGrowingFiles()
                pollDownloadManager()
                // Its process may die without a word: a download followed is asked about anyway.
                if (downloadTracker.followsDownloader) pollDownloader()
                continue
            }
            delay(1_000L)
            if (!watching) return
            // Nothing downloads: every few seconds (a new file is usually seen at once by the
            // folder watchers, this poll only catches what they miss).
            if (downloadTracker.active.value.isEmpty() && ++idleSeconds < IDLE_POLL_SECONDS) continue
            idleSeconds = 0
            busyTicks = 0
            downloadTracker.tick()
            pollGrowingFiles()
            pollDownloadManager()
        }
    }

    private fun seedRecentInProgressWrites() {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory() ?: return
        val nowWall = System.currentTimeMillis()
        val configured = newpipeWatchPath
        val dirs = mutableListOf(File(root, Environment.DIRECTORY_DOWNLOADS))
        if (configured != null) {
            val configuredDir = File(configured)
            if (configuredDir !in dirs) dirs += configuredDir
        }
        for (dir in dirs) {
            seedRecentInDir(dir, nowWall, depth = 0, maxDepth = 2)
        }
    }

    private fun seedRecentInDir(dir: File, nowWall: Long, depth: Int, maxDepth: Int) {
        if (!dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (depth < maxDepth && !child.name.startsWith(".")) {
                    seedRecentInDir(child, nowWall, depth + 1, maxDepth)
                }
            } else if (nowWall - child.lastModified() in 0..DownloadTracker.RECENT_WRITE_SEED_MS && !isOwnWrite(child.absolutePath)) {
                // Settle quickly instead of the 30 s idle wait: a finished file opens right away.
                downloadTracker.onSeeded(child.absolutePath)
            }
        }
    }

    private fun pollDownloadManager() {
        val manager = appContext.getSystemService(DownloadManager::class.java) ?: return
        val seen = HashSet<String>()
        val queried = runCatching {
            val query = DownloadManager.Query().setFilterByStatus(
                DownloadManager.STATUS_PENDING or
                    DownloadManager.STATUS_RUNNING or
                    DownloadManager.STATUS_PAUSED,
            )
            manager.query(query)?.use { cursor ->
                val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val soFarIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (cursor.moveToNext()) {
                    val localUri = if (uriIdx >= 0) cursor.getString(uriIdx) else null
                    val path = localUri?.let { Uri.parse(it).path }?.takeIf { it.isNotBlank() } ?: continue
                    if (!DownloadMediaFilter.isMediaDownload(path)) continue
                    val bytes = if (soFarIdx >= 0) cursor.getLong(soFarIdx) else 0L
                    val total = if (totalIdx >= 0) cursor.getLong(totalIdx) else -1L
                    seen += DownloadMediaFilter.normalizePath(path)
                    downloadTracker.onManagerProgress(path, bytes, total.takeIf { it > 0L })
                }
            }
        }
        // A failed query says nothing about running downloads: releasing them all would let
        // half-downloaded files open.
        if (queried.isFailure) return
        // Two tracker loops can overlap for a moment (stop / start): one set, one at a time.
        synchronized(lastDownloadManagerPaths) {
            for (old in lastDownloadManagerPaths) {
                if (old !in seen) {
                    val display = File(old).takeIf { it.exists() }?.absolutePath ?: old
                    downloadTracker.onCloseOrMovedTo(display)
                }
            }
            lastDownloadManagerPaths.clear()
            lastDownloadManagerPaths.addAll(seen)
        }
    }

    /** The file has a MediaStore row that is no longer IS_PENDING (the writer finished it). */
    private fun isPublishedInMediaStore(path: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val extras = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            @Suppress("DEPRECATION")
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATA}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(path))
        }
        return runCatching {
            resolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns.IS_PENDING),
                extras,
                null,
            )?.use { c -> c.moveToFirst() && c.getInt(0) == 0 } ?: false
        }.getOrDefault(false)
    }

    private fun syncPendingDownloads() {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val pending = queryPendingMediaPaths()
        downloadTracker.syncPending(pending)
        AppLog.event("Sync") {
            "pending downloads: ${pending.size} in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
        }
    }

    private fun queryPendingMediaPaths(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val uris = buildList {
            add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA,
        )
        val out = ArrayList<String>()
        for (uri in uris) {
            runCatching {
                val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val extras = android.os.Bundle().apply {
                        putInt(
                            MediaStore.QUERY_ARG_MATCH_PENDING,
                            MediaStore.MATCH_INCLUDE,
                        )
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.IS_PENDING}=1")
                    }
                    resolver.query(uri, projection, extras, null)
                } else {
                    resolver.query(
                        uri,
                        projection,
                        "${MediaStore.MediaColumns.IS_PENDING}=1",
                        null,
                        null,
                    )
                }
                cursor?.use { rows ->
                    val nameIdx = rows.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeIdx = rows.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val dataIdx = rows.getColumnIndex(MediaStore.MediaColumns.DATA)
                    while (rows.moveToNext()) {
                        val name = if (nameIdx >= 0) rows.getString(nameIdx).orEmpty() else ""
                        val mime = if (mimeIdx >= 0) rows.getString(mimeIdx) else null
                        val data = if (dataIdx >= 0) rows.getString(dataIdx) else null
                        val pathOrName = data?.takeIf { it.isNotBlank() } ?: name
                        if (!DownloadMediaFilter.isMediaDownload(pathOrName, mime)) continue
                        // Already handed to the scanner as finished: not a download in progress.
                        if (!data.isNullOrBlank() && !recentlyPublishedWaiting(data)) out += data
                    }
                }
            }
        }
        return out
    }

    private fun registerWatchReceiver() {
        if (downloadReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                ioScope.launch {
                    when (action) {
                        DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                            val path = pathFromDownloadId(id)
                            if (path != null) {
                                downloadTracker.onCloseOrMovedTo(path)
                                val mime = DownloadsWatchLogic.mediaMimeOf(path)
                                val sent = if (mime != null) {
                                    scanOnce(listOf(path), listOf(mime)) { if (watching) requestRefresh() }
                                } else {
                                    0
                                }
                                if (sent == 0 && watching) requestRefresh()
                            } else if (watching) {
                                requestRefresh()
                            }
                        }
                        Intent.ACTION_MEDIA_SCANNER_FINISHED -> {
                            if (watching) requestRefresh()
                        }
                    }
                }
            }
        }
        fun register(filter: IntentFilter) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
        }
        runCatching { register(IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)) }
        runCatching {
            register(
                IntentFilter(Intent.ACTION_MEDIA_SCANNER_FINISHED).apply {
                    addDataScheme("file")
                    addDataScheme("content")
                },
            )
        }
        downloadReceiver = receiver
    }

    private fun unregisterWatchReceiver() {
        val receiver = downloadReceiver ?: return
        downloadReceiver = null
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun pathFromDownloadId(id: Long): String? {
        if (id < 0L) return null
        val manager = appContext.getSystemService(DownloadManager::class.java) ?: return null
        return runCatching {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val localUri = if (uriIdx >= 0) cursor.getString(uriIdx) else null
                if (!localUri.isNullOrBlank()) {
                    val parsed = Uri.parse(localUri)
                    parsed.path?.takeIf { it.isNotBlank() }
                } else {
                    @Suppress("DEPRECATION")
                    val nameIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)
                    if (nameIdx >= 0) cursor.getString(nameIdx) else null
                }
            }
        }.getOrNull()
    }

    private fun scheduleDownloadsMerge() {
        downloadsMergeJob?.cancel()
        downloadsMergeJob = ioScope.launch {
            delay(CONTENT_DEBOUNCE_MS)
            try {
                val downloads = runCatching { queryDownloadsOnly() }.getOrElse { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    dev.ander.alexet.data.AppLog.e("GalleryRepository", "Downloads query failed", error)
                    return@launch
                }
                // Masks taken and applied in the same step as the merge: a move or delete started
                // between the two brought the file back to the list.
                synchronized(publishLock) {
                    val moveKeys = pendingMoveKeys()
                    val unmasked = downloads.filterNot { isMaskedByPendingOps(it, moveKeys) }
                    _currentMedia.update { current ->
                        DownloadsWatchLogic.mergeDownloads(current, unmasked)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                dev.ander.alexet.data.AppLog.e("GalleryRepository", "Downloads merge failed", e)
            }
        }
    }

    private suspend fun queryDownloadsOnly(): List<GalleryMedia> = withContext(queryDispatcher) {
        queryMutex.withLock {
            val mimeSel =
                "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%' OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'video/%'"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.getExternalVolumeNames(appContext).flatMap { volume ->
                    queryCollection(
                        MediaStore.Downloads.getContentUri(volume),
                        isVideo = false,
                        selection = mimeSel,
                    )
                }
            } else {
                val like = "%/${Environment.DIRECTORY_DOWNLOADS}/%"
                val sel = "${MediaStore.MediaColumns.DATA} LIKE ?"
                queryCollection(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    isVideo = false,
                    selection = sel,
                    selectionArgs = arrayOf(like),
                ) + queryCollection(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    isVideo = true,
                    selection = sel,
                    selectionArgs = arrayOf(like),
                )
            }.distinctBy { DownloadsWatchLogic.itemDedupeKey(it) }
        }
    }

    /** @param rereadHidden The refresh gesture: the hidden folders are read from disk again too. */
    suspend fun refresh(rereadHidden: Boolean = false): List<GalleryMedia> = withContext(Dispatchers.IO) {
        publishWaitingFiles("refresh")
        if (rereadHidden) rereadHiddenFolders()
        val epoch = mutationEpoch.get()
        val published = runCatching {
            syncFromMediaStore(full = false, publish = { epoch == mutationEpoch.get() })
        }.getOrDefault(false)
        if (!published) requestRefresh()
        _currentMedia.value
    }

    fun updateMediaItem(id: Long, transform: (GalleryMedia) -> GalleryMedia) {
        // A sync reading MediaStore meanwhile must not undo this.
        patchMedia(alsoFolders = true) { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    /** The list [albumsFrom] last worked on and its result. */
    @Volatile private var albumsMemo: Pair<List<GalleryMedia>, List<Album>>? = null

    /**
     * The folders of [media]. The same list asked for again (the screen asks right after a sync
     * published it) gets the same result without going through the library again.
     */
    fun albumsFrom(media: List<GalleryMedia>): List<Album> {
        albumsMemo?.let { (of, albums) -> if (of === media) return albums }
        return computeAlbums(media).also { albumsMemo = media to it }
    }

    private fun computeAlbums(media: List<GalleryMedia>): List<Album> {
        return media
            .filterNot {
                it.bucketId == AlbumIds.TRASH ||
                hasTrashMarker(it.relativePath) ||
                it.absolutePath?.let(::hasTrashMarker) == true ||
                it.bucketName.equals(".alexet_trash", ignoreCase = true)
            }
            .groupBy { it.bucketId }
            .map { (id, items) ->
                val newest = items.maxByOrNull { it.capturedAtMs }
                val cover = items.filter { !it.isVideo }.maxByOrNull { it.capturedAtMs }
                    ?: newest
                val relPath = items.firstNotNullOfOrNull { item -> item.relativePath.trim().trim('/').takeIf { it.isNotEmpty() } }
                    ?: newest?.relativePath.orEmpty().trim().trim('/')
                val albumName = newest?.bucketName?.ifBlank {
                    relPath.substringAfterLast('/').ifBlank { "Album" }
                } ?: "Album"
                val legacyIds = items.mapNotNull { it.rawBucketId.takeIf { raw -> raw != 0L } }.toSet()
                Album(
                    id = id,
                    name = albumName,
                    cover = cover,
                    itemCount = items.size,
                    relativePath = if (relPath.isNotEmpty()) "$relPath/" else "",
                    isHidden = items.any { it.isHidden },
                    latestAddedMs = items.maxOf { it.dateAddedMs },
                    legacyBucketIds = legacyIds,
                )
            }
            .distinctBy { it.id }
            .sortedByDescending { it.cover?.capturedAtMs ?: 0L }
    }

    fun itemsIn(albumId: Long, media: List<GalleryMedia>): List<GalleryMedia> {
        if (albumId == AlbumIds.NONE) return emptyList()
        return media.filter { it.bucketId == albumId }.sortedByDescending { it.capturedAtMs }
    }

    fun fileForMedia(item: GalleryMedia): File? {
        item.absolutePath?.let { return File(it) }
        if (item.uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                @Suppress("DEPRECATION")
                resolver.query(
                    item.uri,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null,
                )?.use { rows ->
                    if (rows.moveToFirst()) {
                        val data = rows.getString(0)
                        if (!data.isNullOrBlank()) {
                            val f = File(data)
                            if (f.exists()) return f
                        }
                    }
                }
            }
        }
        if (item.relativePath.isNotBlank()) {
            val rel = item.relativePath.trim().trim('/')
            val f = File(Environment.getExternalStorageDirectory(), "$rel/${item.displayName}")
            if (f.exists()) return f
        }
        return null
    }

    private fun albumRelativePath(dest: Album): String {
        val dir = directoryForAlbum(dest)
        if (dir != null) {
            val root = Environment.getExternalStorageDirectory()
            val rel = dir.relativeToOrNull(root)?.path?.replace('\\', '/')?.trim('/')
            if (!rel.isNullOrBlank()) return "$rel/"
        }
        val raw = dest.relativePath.ifBlank {
            "${Environment.DIRECTORY_PICTURES}/${dest.name}/"
        }.trim().trim('/')
        return if (raw.isEmpty()) "" else "$raw/"
    }

    suspend fun copyToAlbums(
        items: List<GalleryMedia>,
        destinations: List<Album>,
        anonymousFolderIds: Set<Long> = emptySet(),
        randomizeFileNames: Boolean = true,
        stripExif: Boolean = true,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Set<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        val total = destinations.sumOf { dest -> items.count { it.bucketId != dest.id } }
        var current = 0
        val scanPaths = ArrayList<String>()
        // Hidden / other-volume folders written by plain file copies: their trees are rescanned
        // and the hidden-folder cache re-read once at the end, not after every file.
        val treeScanDirs = LinkedHashSet<File>()

        val result = buildSet {
            for (dest in destinations) {
                val isAnon = dest.id in anonymousFolderIds
                for (item in items.filter { it.bucketId != dest.id }) {
                    if (downloadTracker.isActive(item.absolutePath)) continue
                    val copiedFile = copyOne(item, dest, isAnon, randomizeFileNames, stripExif, treeScanDirs)
                    current++
                    onProgress(current, total)
                    if (copiedFile != null) {
                        finishOwnWrite(copiedFile)
                        add(item.id to dest.id)
                        scanPaths += copiedFile.absolutePath
                    }
                }
            }
        }
        if (treeScanDirs.isNotEmpty()) {
            treeScanDirs.forEach { nomediaScanner.rescanTree(it) }
            invalidateNomediaCache()
        }
        ioScope.launch {
            if (scanPaths.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    appContext,
                    scanPaths.distinct().toTypedArray(),
                    null,
                    null,
                )
            }
            syncAfterOperation()
        }
        scheduleOtherFilesRefresh()
        result
    }

    /** Post-operation refresh: a delta sync, falling back to the debounced refresh on failure. */
    private suspend fun syncAfterOperation() {
        val published = runCatching { syncFromMediaStore(full = false) }
            .onFailure { e ->
                if (e is CancellationException) throw e
                dev.ander.alexet.data.AppLog.w("GalleryRepository", "Post-operation sync failed", e)
            }
            .getOrDefault(false)
        if (!published) requestRefresh()
    }

    private fun copyOne(
        item: GalleryMedia,
        dest: Album,
        isAnonymous: Boolean = false,
        randomizeFileNames: Boolean = true,
        stripExif: Boolean = true,
        treeScanDirs: MutableSet<File>,
    ): File? {
        val albumDir = directoryForAlbum(dest)
        val destDir = albumDir
            ?: File(Environment.getExternalStorageDirectory(), albumRelativePath(dest).trim().trim('/'))
        val baseName = if (isAnonymous && randomizeFileNames) ExifUtils.generateAnonymousFileName(item.displayName) else item.displayName
        val uniqueTarget = resolveUniqueTargetFile(destDir, baseName)
        markOwnWrite(uniqueTarget)
        // A plain file copy for folders MediaStore doesn't index (hidden) or can't address with a
        // RELATIVE_PATH (another volume such as an SD card).
        fun copyByFile(): File? {
            var tempTarget: File? = null
            return try {
                if (destDir.exists() || destDir.mkdirs()) {
                    tempTarget = File(destDir, ".tmp_${System.currentTimeMillis()}_${uniqueTarget.name}")
                    StartupRecovery.notePartial(appContext, tempTarget)
                    val input = if (item.absolutePath != null) {
                        File(item.absolutePath).inputStream()
                    } else {
                        resolver.openInputStream(item.uri)
                    }
                    val ok = input?.use { inp ->
                        tempTarget.outputStream().use { out -> inp.copyTo(out, COPY_BUFFER_BYTES) }
                        true
                    } ?: false
                    if (ok && tempTarget.exists() && tempTarget.length() > 0L) {
                        val finalTarget = synchronized(fileNameLock) {
                            // Taken meanwhile (another copy finished first): the next free name.
                            val target = if (uniqueTarget.exists()) resolveUniqueTargetFile(destDir, uniqueTarget.name) else uniqueTarget
                            if (target != uniqueTarget) markOwnWrite(target)
                            val renamed = tempTarget.renameTo(target)
                            if (!renamed) {
                                tempTarget.copyTo(target, overwrite = false)
                                tempTarget.delete()
                            }
                            target
                        }
                        if (isAnonymous && stripExif && finalTarget.exists()) {
                            ExifUtils.stripExifExceptDate(finalTarget)
                        }
                        treeScanDirs += destDir
                        finalTarget
                    } else {
                        tempTarget.delete()
                        null
                    }
                } else null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // An interrupted copy must not leave a partial hidden file behind.
                tempTarget?.delete()
                null
            } finally {
                tempTarget?.let { StartupRecovery.donePartial(appContext, it) }
            }
        }
        val onOtherVolume = albumDir != null && albumDir.relativeToOrNull(Environment.getExternalStorageDirectory()) == null
        // A document or an app cannot go into MediaStore's photo / video tables.
        if (item.isOtherFile || (albumDir != null && (dest.isHidden || onOtherVolume || File(albumDir, ".nomedia").exists()))) {
            return copyByFile()
        }
        val relative = albumRelativePath(dest)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueTarget.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (item.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        // MediaStore refuses some folders for some types (an image under Movies/ or Download/
        // without all-files access): that throws, and nothing above catches it.
        val outUri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            AppLog.w("GalleryRepository", "MediaStore insert refused for $relative", e)
            null
        } ?: return if (albumDir != null && StorageAccess.canReadAllFiles()) copyByFile() else null
        var success = false
        // A process killed during the copy leaves a pending row with part of the file.
        StartupRecovery.notePartial(appContext, outUri)
        return try {
            val input = if (item.absolutePath != null) {
                File(item.absolutePath).inputStream()
            } else {
                resolver.openInputStream(item.uri)
            } ?: throw java.io.FileNotFoundException("Cannot open source stream for ${item.uri}")

            input.use { inp ->
                resolver.openOutputStream(outUri)?.use { out ->
                    inp.copyTo(out, COPY_BUFFER_BYTES)
                    success = true
                } ?: throw java.io.IOException("Cannot open destination stream for $outUri")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(outUri, done, null, null)
            }
            if (isAnonymous && stripExif) {
                if (uniqueTarget.exists()) {
                    ExifUtils.stripExifExceptDate(uniqueTarget)
                } else {
                    ExifUtils.stripExifExceptDate(appContext, outUri)
                }
            }
            uniqueTarget
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } finally {
            if (!success) {
                runCatching { resolver.delete(outUri, null, null) }
            }
            StartupRecovery.donePartial(appContext, outUri)
        }
    }

    private fun canonicalMediaStoreUri(item: GalleryMedia): Uri {
        if (item.uri.scheme != "content") return item.uri
        val collection = if (item.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return if (item.id > 0) {
            ContentUris.withAppendedId(collection, item.id)
        } else {
            val uriStr = item.uri.toString()
            if (uriStr.contains("external_primary")) {
                Uri.parse(uriStr.replace("external_primary", "external"))
            } else {
                item.uri
            }
        }
    }


    /** When [lastRecoverableSender] was set (elapsedRealtime). */
    @Volatile private var lastRecoverableAt = 0L

    /**
     * The consent request of the operation that just failed. One slot for all operations: a request
     * nobody took is dropped after a few seconds, so a later operation can't launch an old one
     * (whose consent then ran the wrong follow-up).
     */
    fun takeRecoverableSender(): android.content.IntentSender? = synchronized(this) {
        val sender = lastRecoverableSender
        val fresh = android.os.SystemClock.elapsedRealtime() - lastRecoverableAt <= RECOVERABLE_TTL_MS
        lastRecoverableSender = null
        if (fresh) sender else null
    }

    private fun rememberRecoverable(error: Throwable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
            synchronized(this) {
                lastRecoverableSender = error.userAction.actionIntent.intentSender
                lastRecoverableAt = android.os.SystemClock.elapsedRealtime()
            }
        }
    }


    /**
     * Stamp DATE_MODIFIED so first selected = newest (top-left when sorting by date modified DESC).
     * Does not change DATE_TAKEN / captured. Uses File.setLastModified when path is available.
     */
    suspend fun applySelectionOrderModifiedDates(targetsInOrder: List<File>): List<GalleryMedia> = withContext(Dispatchers.IO) {
        if (targetsInOrder.isEmpty()) return@withContext emptyList()
        val nowMs = System.currentTimeMillis()
        val needWrite = ArrayList<GalleryMedia>()
        val stampedByPath = HashMap<String, Long>(targetsInOrder.size)
        targetsInOrder.forEachIndexed { index, file ->
            val stampMs = nowMs - index * 1_000L
            val stampSec = stampMs / 1000L
            runCatching { file.setLastModified(stampMs) }
            stampedByPath[file.absolutePath.lowercase()] = stampMs
            val uri = findMediaStoreUriForFile(file)
            if (uri != null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_MODIFIED, stampSec)
                }
                try {
                    resolver.update(uri, values, null, null)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    rememberRecoverable(e)
                    needWrite += GalleryMedia(
                        id = 0L,
                        uri = uri,
                        displayName = file.name,
                        mimeType = "application/octet-stream",
                        dateTakenMs = 0L,
                        dateModifiedMs = stampMs,
                        dateAddedMs = 0L,
                        sizeBytes = file.length(),
                        width = 0,
                        height = 0,
                        durationMs = 0L,
                        bucketId = AlbumIds.NONE,
                        bucketName = "",
                        relativePath = "",
                        absolutePath = file.absolutePath,
                    )
                }
            }
        }
        // Single in-memory pass after all disk stamps (avoids N full-list maps).
        if (stampedByPath.isNotEmpty()) {
            // A sync reading MediaStore meanwhile must not undo this.
            patchMedia(alsoFolders = true) { list ->
                list.map { item ->
                    val path = item.absolutePath?.lowercase() ?: return@map item
                    val stamp = stampedByPath[path] ?: return@map item
                    item.copy(dateModifiedMs = stamp)
                }
            }
        }
        needWrite
    }

    private fun findMediaStoreUriForFile(file: File): Uri? {
        val path = file.absolutePath
        val collections = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.getExternalVolumeNames(appContext).forEach { volume ->
                    add(MediaStore.Images.Media.getContentUri(volume))
                    add(MediaStore.Video.Media.getContentUri(volume))
                    add(MediaStore.Files.getContentUri(volume))
                }
            } else {
                add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                @Suppress("DEPRECATION")
                add(MediaStore.Files.getContentUri("external"))
            }
        }
        for (collection in collections) {
            runCatching {
                @Suppress("DEPRECATION")
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
                    "${MediaStore.MediaColumns.DATA}=?",
                    arrayOf(path),
                    null,
                )?.use { rows ->
                    if (rows.moveToFirst()) {
                        val id = rows.getLong(0)
                        return android.content.ContentUris.withAppendedId(collection, id)
                    }
                }
            }
        }
        return null
    }

    fun createDeleteRequest(items: List<GalleryMedia>): android.content.IntentSender? {
        val mediaStoreItems = items.filter { it.uri.scheme == "content" || it.id > 0 }
        if (mediaStoreItems.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching {
                val canonicalUris = mediaStoreItems.map { canonicalMediaStoreUri(it) }
                MediaStore.createDeleteRequest(resolver, canonicalUris).intentSender
            }.getOrNull()
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            return takeRecoverableSender()
        }
        return null
    }

    fun createWriteRequest(items: List<GalleryMedia>): android.content.IntentSender? {
        val mediaStoreItems = items.filter { it.uri.scheme == "content" || it.id > 0 }
        if (mediaStoreItems.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching {
                val canonicalUris = mediaStoreItems.map { canonicalMediaStoreUri(it) }
                MediaStore.createWriteRequest(resolver, canonicalUris).intentSender
            }.getOrNull()
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            return takeRecoverableSender()
        }
        return null
    }

    fun markPendingDeletes(items: Collection<GalleryMedia>) {
        if (items.isEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val idSet = items.mapTo(HashSet()) { it.id }
        items.forEach { item ->
            pendingDeletes[item.id] = PendingDelete(
                id = item.id,
                displayName = item.displayName,
                absolutePath = item.absolutePath?.lowercase(),
                bucketId = item.bucketId,
                markedAt = now,
            )
        }
        pendingDeletesVersion.incrementAndGet()
        dropOtherFiles(idSet)
        patchMedia { list -> list.filter { it.id !in idSet && !matchesPendingDelete(it) } }
        _folderMedia.update { map ->
            map.mapValues { (_, list) -> list.filter { it.id !in idSet && !matchesPendingDelete(it) } }
        }
    }

    fun unmarkPendingDeletes(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        ids.forEach { pendingDeletes.remove(it) }
        pendingDeletesVersion.incrementAndGet()
        requestRefresh()
    }

    suspend fun deleteDirect(items: List<GalleryMedia>): Int = withContext(Dispatchers.IO) {
        val now = android.os.SystemClock.elapsedRealtime()
        var deleted = 0
        var deletedNomedia = false
        // Scanned once at the end: one scanner connection per file (thousands when deleting whole
        // folders) flooded MediaProvider.
        val toScan = ArrayList<String>()
        items.forEach { item ->
            if (downloadTracker.isActive(item.absolutePath)) return@forEach
            val resolvedFile = fileForMedia(item)
            val path = item.absolutePath ?: resolvedFile?.absolutePath
            val ok = if (path != null) {
                val file = File(path)
                val isFileDeleted = !file.exists() || runCatching { file.delete() }.getOrDefault(false)
                // Only a hidden-folder file changes the hidden-folder scan.
                if (isFileDeleted && (item.isHidden || item.uri.scheme == "file")) {
                    deletedNomedia = true
                }
                var isProviderDeleted = false
                var providerFailed = false
                if (item.uri.toString().startsWith("content://media/") || item.uri.scheme == "content") {
                    try {
                        val count = resolver.delete(item.uri, null, null)
                        isProviderDeleted = count > 0
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        rememberRecoverable(e)
                        providerFailed = true
                    }
                }
                // A row the provider removed itself needs no scan.
                if (isFileDeleted && !isProviderDeleted) toScan += path
                if (providerFailed) {
                    false
                } else {
                    isProviderDeleted || (isFileDeleted && !item.uri.toString().startsWith("content://media/"))
                }
            } else {
                try {
                    resolver.delete(item.uri, null, null) > 0
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    rememberRecoverable(e)
                    false
                }
            }
            if (ok) {
                deleted++
                pendingDeletes[item.id] = PendingDelete(
                    id = item.id,
                    displayName = item.displayName,
                    absolutePath = (item.absolutePath ?: path)?.lowercase(),
                    bucketId = item.bucketId,
                    markedAt = now,
                )
                // At once, not after the whole batch: a sync during the loop must already mask
                // this file (it flickered back on the list until the end).
                pendingDeletesVersion.incrementAndGet()
            }
        }
        if (toScan.isNotEmpty()) {
            MediaScannerConnection.scanFile(appContext, toScan.toTypedArray(), null, null)
        }
        if (deletedNomedia) {
            invalidateNomediaCache()
        }
        pendingDeletesVersion.incrementAndGet()
        patchMedia { list -> list.filter { !matchesPendingDelete(it) } }
        _folderMedia.update { map -> map.mapValues { (_, list) -> list.filter { !matchesPendingDelete(it) } } }
        dropOtherFiles(items.filter { it.isOtherFile }.mapTo(HashSet()) { it.id })
        scheduleOtherFilesRefresh()
        deleted
    }

    suspend fun rename(item: GalleryMedia, newName: String, notifyScan: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@withContext false
        if (downloadTracker.isActive(item.absolutePath)) return@withContext false
        val safeName = sanitizeFileName(trimmed)
        val path = item.absolutePath ?: fileForMedia(item)?.absolutePath
        if (path != null) {
            val source = File(path)
            val parent = source.parentFile ?: return@withContext false
            val target = File(parent, safeName)
            val parentCanonical = runCatching { parent.canonicalFile }.getOrNull()
                ?: return@withContext false
            val targetCanonical = runCatching { target.canonicalFile }.getOrNull()
                ?: return@withContext false
            if (targetCanonical.parentFile?.canonicalFile != parentCanonical) {
                return@withContext false
            }
            if (target.exists() && target.canonicalPath != source.canonicalPath) {
                return@withContext false
            }
            // A rename shows up as a new file to the watcher: not a download.
            markOwnWrite(target)
            if (source.renameTo(target)) {
                finishOwnWrite(target)
                patchRenamed(item.id, safeName, target.absolutePath)
                if (notifyScan) {
                    MediaScannerConnection.scanFile(
                        appContext,
                        arrayOf(source.absolutePath, target.absolutePath),
                        null,
                    ) { _, _ ->
                        requestRefresh()
                    }
                    // The renamed file is scanned above; the whole folder only when it is hidden.
                    val dir = target.parentFile ?: target
                    if (File(dir, ".nomedia").exists()) nomediaScanner.rescanTree(dir)
                    invalidateNomediaCache()
                }
                return@withContext true
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
        }
        val ok = try {
            resolver.update(item.uri, values, null, null) > 0
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            rememberRecoverable(e)
            false
        }
        if (ok) {
            val newAbsPath = path?.let { p ->
                File(File(p).parentFile ?: File(p), safeName).absolutePath
            }
            patchRenamed(item.id, safeName, newAbsPath)
            if (notifyScan) {
                requestRefresh()
            }
        }
        ok
    }

    /** Renames inside a [renameBatch], applied to the shown lists together when it ends. */
    private val renameBatchDepth = java.util.concurrent.atomic.AtomicInteger(0)
    private val deferredRenames = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, String?>>()

    /**
     * Runs a multi-file rename. Each [rename] patched the whole library (and every open folder)
     * at once: 2,000 files meant 2,000 copies of the full list and as many UI updates.
     */
    private suspend fun <T> renameBatch(
        context: kotlin.coroutines.CoroutineContext,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> T,
    ): T = withContext(context) {
        renameBatchDepth.incrementAndGet()
        try {
            block()
        } finally {
            if (renameBatchDepth.decrementAndGet() == 0) flushDeferredRenames()
        }
    }

    /** Shows [id] under [name] (and [absolutePath] when known); a sync reading MediaStore meanwhile must not undo it. */
    private fun patchRenamed(id: Long, name: String, absolutePath: String?) {
        if (renameBatchDepth.get() > 0) {
            deferredRenames[id] = name to absolutePath
            // The batch may have ended between the check and the put.
            if (renameBatchDepth.get() == 0) flushDeferredRenames()
            return
        }
        applyRenames(mapOf(id to (name to absolutePath)))
    }

    private fun flushDeferredRenames() {
        if (deferredRenames.isEmpty()) return
        val pending = HashMap<Long, Pair<String, String?>>()
        for (key in deferredRenames.keys.toList()) {
            deferredRenames.remove(key)?.let { pending[key] = it }
        }
        if (pending.isNotEmpty()) applyRenames(pending)
    }

    private fun applyRenames(renames: Map<Long, Pair<String, String?>>) {
        if (_otherFiles.value.values.any { list -> list.any { it.id in renames } }) scheduleOtherFilesRefresh()
        patchMedia(alsoFolders = true) { list ->
            if (list.none { it.id in renames }) {
                list
            } else {
                list.map { item ->
                    val (name, path) = renames[item.id] ?: return@map item
                    item.copy(displayName = name, absolutePath = path ?: item.absolutePath)
                }
            }
        }
    }

    suspend fun renameAll(
        itemsWithNewNames: List<Pair<GalleryMedia, String>>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<Int, List<GalleryMedia>> = renameBatch(Dispatchers.IO) {
        if (itemsWithNewNames.isEmpty()) return@renameBatch 0 to emptyList()
        var successCount = 0
        val failed = mutableListOf<GalleryMedia>()
        val total = itemsWithNewNames.size
        itemsWithNewNames.forEachIndexed { index, (item, newName) ->
            onProgress(index, total)
            if (rename(item, newName, notifyScan = false)) {
                successCount++
            } else {
                failed.add(item)
            }
        }
        if (successCount > 0) {
            requestRefresh()
        }
        successCount to failed
    }

    // The multi-step renames below run to their end even when the screen goes away (the
    // ViewModel's scope is cancelled): stopped half-way, files kept their temporary names.
    suspend fun executeRolloffRename(
        plan: List<Pair<GalleryMedia, String>>,
    ): Pair<Int, List<GalleryMedia>> = renameBatch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        if (plan.isEmpty()) return@renameBatch 0 to emptyList()

        val charPool = ('a'..'z') + ('0'..'9')
        data class Phase1Step(
            val originalMedia: GalleryMedia,
            val tempMedia: GalleryMedia,
            val targetName: String,
        )

        val phase1Steps = ArrayList<Phase1Step>(plan.size)
        val failedPhase1 = mutableListOf<GalleryMedia>()
        val rolloffJournaled = ArrayList<File>()

        // Phase 1: Rename every file in plan to unique temp name
        for ((item, targetName) in plan) {
            val randomTag = (1..8).map { charPool.random() }.joinToString("")
            val tempName = "rename_${randomTag}_${item.displayName}"
            journalTemp(item, tempName)?.let { rolloffJournaled += it }

            if (rename(item, tempName, notifyScan = false)) {
                val parentDir = item.absolutePath?.let { File(it).parentFile }
                    ?: fileForMedia(item)?.parentFile
                    ?: File("")
                val tempPath = File(parentDir, tempName).absolutePath
                val updatedMedia = item.copy(displayName = tempName, absolutePath = tempPath)
                phase1Steps += Phase1Step(item, updatedMedia, targetName)
            } else {
                failedPhase1.add(item)
                break
            }
        }

        // If Phase 1 failed on any item: best-effort rollback
        if (failedPhase1.isNotEmpty()) {
            for (step in phase1Steps) {
                runCatching {
                    rename(step.tempMedia, step.originalMedia.displayName, notifyScan = false)
                }
            }
            RenameJournal.remove(rolloffJournaled)
            requestRefresh()
            return@renameBatch 0 to (failedPhase1 + phase1Steps.map { it.originalMedia })
        }

        // Phase 2: Rename each temp file to targetName
        var successCount = 0
        val newlyRenamedFiles = ArrayList<File>()
        val renamedMap = HashMap<Long, Pair<String, String?>>()
        val failedPhase2 = mutableListOf<GalleryMedia>()

        for (step in phase1Steps) {
            if (rename(step.tempMedia, step.targetName, notifyScan = false)) {
                successCount++
                val parentDir = File(step.tempMedia.absolutePath ?: "").parentFile
                    ?: fileForMedia(step.tempMedia)?.parentFile
                    ?: File("")
                val newPath = File(parentDir, step.targetName).absolutePath
                newlyRenamedFiles += File(newPath)
                renamedMap[step.originalMedia.id] = step.targetName to newPath
            } else {
                runCatching {
                    rename(step.tempMedia, step.originalMedia.displayName, notifyScan = false)
                }
                failedPhase2.add(step.originalMedia)
            }
        }
        RenameJournal.remove(rolloffJournaled)

        if (renamedMap.isNotEmpty()) {
            // A sync reading MediaStore meanwhile must not undo this.
            patchMedia(alsoFolders = true) { list ->
                list.map { existing ->
                    val ren = renamedMap[existing.id]
                    if (ren != null) {
                        existing.copy(
                            displayName = ren.first,
                            absolutePath = ren.second ?: existing.absolutePath,
                        )
                    } else existing
                }
            }
        }

        if (newlyRenamedFiles.isNotEmpty()) {
            MediaScannerConnection.scanFile(
                appContext,
                newlyRenamedFiles.map { it.absolutePath }.toTypedArray(),
                null,
            ) { _, _ ->
                requestRefresh()
            }
        }
        requestRefresh()

        successCount to failedPhase2
    }


    private fun evictCoilCacheFor(mediaList: List<GalleryMedia>) {
        runCatching {
            val loader = SingletonImageLoader.get(appContext)
            for (item in mediaList) {
                val baseThumb = "thumb-${item.id}-${item.dateModifiedMs}"
                val baseFull = "full-${item.id}-${item.dateModifiedMs}"
                loader.memoryCache?.remove(MemoryCache.Key(baseThumb))
                loader.memoryCache?.remove(MemoryCache.Key(baseFull))
                loader.diskCache?.remove(baseThumb)
                loader.diskCache?.remove(baseFull)
                for (g in 0..10) {
                    val thumbKey = "$baseThumb-g$g"
                    val fullKey = "$baseFull-g$g"
                    loader.memoryCache?.remove(MemoryCache.Key(thumbKey))
                    loader.memoryCache?.remove(MemoryCache.Key(fullKey))
                    loader.diskCache?.remove(thumbKey)
                    loader.diskCache?.remove(fullKey)
                }
                val uriStr = item.uri.toString()
                loader.memoryCache?.remove(MemoryCache.Key(uriStr))
                loader.diskCache?.remove(uriStr)
                item.absolutePath?.let { p ->
                    loader.memoryCache?.remove(MemoryCache.Key(p))
                    loader.diskCache?.remove(p)
                }
            }
        }
    }

    /**
     * Swap full display names (incl. extensions) via a temporary intermediate file
     * so MediaStore / filesystem never collide (A→tmp, B→A, tmp→B).
     */
    suspend fun swapDisplayNames(a: GalleryMedia, b: GalleryMedia): Boolean = renameBatch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        if (a.id == b.id) return@renameBatch true
        val nameA = a.displayName
        val nameB = b.displayName
        if (nameA == nameB) return@renameBatch true

        val fileA = fileForMedia(a)
        val fileB = fileForMedia(b)
        if (fileA != null && fileB != null && fileA.exists() && fileB.exists()) {
            val parentA = fileA.parentFile ?: return@renameBatch false
            val parentB = fileB.parentFile ?: return@renameBatch false
            if (parentA.canonicalPath != parentB.canonicalPath) {
                // Fall back to sequential MediaStore renames with unique temp.
            } else {
                val tmp = uniqueTempSibling(parentA, a.id, fileA.extension)
                val destA = File(parentA, nameA)
                val destB = File(parentA, nameB)
                listOf(tmp, destA, destB).forEach(::markOwnWrite)
                RenameJournal.add(tmp, fileA, destB)
                if (!fileA.renameTo(tmp)) {
                    RenameJournal.remove(listOf(tmp))
                    return@renameBatch false
                }
                if (!fileB.renameTo(destA)) {
                    tmp.renameTo(fileA)
                    RenameJournal.remove(listOf(tmp))
                    return@renameBatch false
                }
                if (!tmp.renameTo(destB)) {
                    destA.renameTo(fileB)
                    tmp.renameTo(fileA)
                    RenameJournal.remove(listOf(tmp))
                    return@renameBatch false
                }
                RenameJournal.remove(listOf(tmp))
                listOf(tmp, destA, destB).forEach(::finishOwnWrite)
                val stampMs = System.currentTimeMillis()
                destA.setLastModified(stampMs)
                destB.setLastModified(stampMs + 1000L)
                MediaScannerConnection.scanFile(
                    appContext,
                    arrayOf(fileA.absolutePath, fileB.absolutePath, destA.absolutePath, destB.absolutePath, tmp.absolutePath),
                    null,
                    null,
                )
                // Also poke MediaStore DISPLAY_NAME when possible
                runCatching {
                    resolver.update(a.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, nameB) }, null, null)
                }
                runCatching {
                    resolver.update(b.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, nameA) }, null, null)
                }
                // A sync reading MediaStore meanwhile must not undo this.
                patchMedia(alsoFolders = true) { list ->
                    list.map { existing ->
                        when (existing.id) {
                            a.id -> existing.copy(displayName = nameB, dateModifiedMs = stampMs + 1000L, absolutePath = destB.absolutePath)
                            b.id -> existing.copy(displayName = nameA, dateModifiedMs = stampMs, absolutePath = destA.absolutePath)
                            else -> existing
                        }
                    }
                }
                evictCoilCacheFor(listOf(a, b, a.copy(displayName = nameB, dateModifiedMs = stampMs + 1000L), b.copy(displayName = nameA, dateModifiedMs = stampMs)))
                return@renameBatch true
            }
        }

        val pathA = a.absolutePath ?: fileForMedia(a)?.absolutePath
        val pathB = b.absolutePath ?: fileForMedia(b)?.absolutePath
        val tmpName = uniqueTempDisplayName(a)
        val swapTemp = journalTemp(a, tmpName, pathA?.let { File(it).parentFile }?.let { File(it, nameB) })
        val swapJournaled = listOfNotNull(swapTemp)
        if (!rename(a, tmpName, notifyScan = false)) {
            RenameJournal.remove(swapJournaled)
            return@renameBatch false
        }
        // After rename(a), path changed — rebuild from known tmp
        val aAfter = a.copy(
            displayName = tmpName,
            absolutePath = pathA?.let { old ->
                File(old).parentFile?.let { File(it, tmpName).absolutePath }
            },
        )
        if (!rename(b, nameA, notifyScan = false)) {
            rename(aAfter, nameA, notifyScan = false)
            RenameJournal.remove(swapJournaled)
            return@renameBatch false
        }
        val bAfter = b.copy(
            displayName = nameA,
            absolutePath = pathB?.let { old ->
                File(old).parentFile?.let { File(it, nameA).absolutePath }
            },
        )
        if (!rename(aAfter, nameB, notifyScan = false)) {
            rename(bAfter, nameB, notifyScan = false)
            rename(aAfter, nameA, notifyScan = false)
            RenameJournal.remove(swapJournaled)
            return@renameBatch false
        }
        RenameJournal.remove(swapJournaled)
        val stampMs = System.currentTimeMillis()
        fileForMedia(bAfter)?.setLastModified(stampMs)
        fileForMedia(aAfter)?.setLastModified(stampMs + 1000L)
        // A sync reading MediaStore meanwhile must not undo this.
        patchMedia(alsoFolders = true) { list ->
            list.map { existing ->
                when (existing.id) {
                    a.id -> existing.copy(displayName = nameB, dateModifiedMs = stampMs + 1000L)
                    b.id -> existing.copy(displayName = nameA, dateModifiedMs = stampMs)
                    else -> existing
                }
            }
        }
        evictCoilCacheFor(listOf(a, b, a.copy(displayName = nameB, dateModifiedMs = stampMs + 1000L), b.copy(displayName = nameA, dateModifiedMs = stampMs)))
        requestRefresh()
        true
    }

    /**
     * Rename items in order to 1.ext, 2.ext, … using temp names to avoid collisions.
     * Keeps each item's original extension.
     */
    suspend fun renameToNumericOrder(
        itemsInOrder: List<GalleryMedia>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<Int, List<GalleryMedia>> = renameBatch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        if (itemsInOrder.isEmpty()) return@renameBatch 0 to emptyList()
        val currentCanonicalPaths = itemsInOrder.mapNotNull {
            val p = it.absolutePath ?: fileForMedia(it)?.absolutePath
            p?.let { path -> runCatching { File(path).canonicalPath }.getOrNull() }
        }.toSet()
        val hasCollision = itemsInOrder.indices.any { index ->
            val item = itemsInOrder[index]
            val path = item.absolutePath ?: fileForMedia(item)?.absolutePath ?: return@any false
            val parent = File(path).parentFile ?: return@any false
            val ext = item.displayName.substringAfterLast('.', missingDelimiterValue = "")
            val n = index + 1
            val finalName = if (ext.isNotEmpty()) "$n.$ext" else "$n"
            val target = File(parent, finalName)
            target.exists() && runCatching { target.canonicalPath }.getOrNull() !in currentCanonicalPaths
        }
        if (hasCollision) {
            return@renameBatch 0 to itemsInOrder
        }
        data class TempStep(val media: GalleryMedia, val originalName: String, val orderIndex: Int)
        val temps = ArrayList<TempStep>(itemsInOrder.size)
        val failed = mutableListOf<GalleryMedia>()
        val totalSteps = itemsInOrder.size * 2
        var stepCount = 0
        val numericJournaled = ArrayList<File>()
        itemsInOrder.forEachIndexed { index, item ->
            val resolvedPath = item.absolutePath ?: fileForMedia(item)?.absolutePath
            val tmp = uniqueTempDisplayName(item, index)
            journalTemp(item, tmp)?.let { numericJournaled += it }
            if (rename(item, tmp, notifyScan = false)) {
                val updated = item.copy(
                    displayName = tmp,
                    absolutePath = resolvedPath?.let { oldPath ->
                        File(oldPath).parentFile?.let { File(it, tmp).absolutePath }
                    },
                )
                temps += TempStep(updated, item.displayName, index)
            } else {
                failed += item
            }
            stepCount++
            onProgress(stepCount, totalSteps)
        }
        if (failed.isNotEmpty()) {
            for (step in temps) {
                rename(step.media, step.originalName, notifyScan = false)
            }
            RenameJournal.remove(numericJournaled)
            return@renameBatch 0 to itemsInOrder
        }
        var success = 0
        val renamedMap = HashMap<Long, Pair<String, String?>>()
        val newlyRenamedFiles = ArrayList<File>()
        for (step in temps) {
            val ext = step.originalName.substringAfterLast('.', missingDelimiterValue = "")
            val n = step.orderIndex + 1
            val finalName = if (ext.isNotEmpty()) "$n.$ext" else "$n"
            if (rename(step.media, finalName, notifyScan = false)) {
                success++
                val newPath = step.media.absolutePath?.let { oldPath ->
                    File(oldPath).parentFile?.let { File(it, finalName).absolutePath }
                }
                newPath?.let { newlyRenamedFiles += File(it) }
                renamedMap[step.media.id] = finalName to newPath
            } else {
                failed += step.media
            }
            stepCount++
            onProgress(stepCount.coerceAtMost(totalSteps), totalSteps)
        }
        RenameJournal.remove(numericJournaled)
        if (renamedMap.isNotEmpty()) {
            // A sync reading MediaStore meanwhile must not undo this.
            patchMedia(alsoFolders = true) { list ->
                list.map { existing ->
                    val ren = renamedMap[existing.id]
                    if (ren != null) {
                        existing.copy(
                            displayName = ren.first,
                            absolutePath = ren.second ?: existing.absolutePath,
                        )
                    } else existing
                }
            }
        }
        if (newlyRenamedFiles.isNotEmpty()) {
            MediaScannerConnection.scanFile(
                appContext,
                newlyRenamedFiles.map { it.absolutePath }.toTypedArray(),
                null,
            ) { _, _ ->
                requestRefresh()
            }
        }
        if (stepCount < totalSteps) {
            onProgress(totalSteps, totalSteps)
        }
        success to failed
    }

    /**
     * Rename items with pattern (such as %i) using a two-phase rename:
     * Phase 1: rename_<random10alnum>_<cleanDesiredName>.<ext>
     * Between phases: per-item collision check (cleanDesiredName, ~1, ~2, ... until free)
     * Phase 2: strip up to second '_' to apply final resolved name
     */
    suspend fun renamePatternTwoPhase(
        itemsInOrder: List<GalleryMedia>,
        request: RenameRequest,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<Int, List<GalleryMedia>> = renameBatch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        if (itemsInOrder.isEmpty()) return@renameBatch 0 to emptyList()

        val padDigits = maxOf(3, itemsInOrder.size.toString().length)
        val currentCanonicalPaths = itemsInOrder.mapNotNull {
            val p = it.absolutePath ?: fileForMedia(it)?.absolutePath
            p?.let { path -> runCatching { File(path).canonicalPath }.getOrNull() }
        }.toSet()

        data class ItemTarget(
            val item: GalleryMedia,
            val dir: File,
            val baseName: String,
            val ext: String,
        )

        val itemTargets = itemsInOrder.mapIndexedNotNull { idx, item ->
            val path = item.absolutePath ?: fileForMedia(item)?.absolutePath ?: return@mapIndexedNotNull null
            val dir = File(path).parentFile ?: return@mapIndexedNotNull null
            val rawNewName = request.applyTo(item, idx + 1, padDigits)
            val hasDot = rawNewName.contains('.')
            val base = if (hasDot) rawNewName.substringBeforeLast('.') else rawNewName
            val ext = if (hasDot) rawNewName.substringAfterLast('.') else ""
            ItemTarget(item, dir, base, ext)
        }

        if (itemTargets.size != itemsInOrder.size) {
            return@renameBatch 0 to itemsInOrder
        }

        val charPool = ('a'..'z') + ('0'..'9')
        data class Phase1Step(
            val originalMedia: GalleryMedia,
            val tempMedia: GalleryMedia,
            val target: ItemTarget,
        )

        val totalSteps = itemsInOrder.size * 2
        var stepCount = 0
        val phase1Steps = ArrayList<Phase1Step>(itemsInOrder.size)
        val failed = mutableListOf<GalleryMedia>()

        phase2Pending.clear()
        val patternJournaled = ArrayList<File>()
        // Phase 1: rename each item to temporary name with clean desired target name: rename_<random>_<base>.<ext>
        for (target in itemTargets) {
            val cleanDesiredName = if (target.ext.isNotEmpty()) "${target.baseName}.${target.ext}" else target.baseName
            val randomTag = (1..10).map { charPool.random() }.joinToString("")
            val tempName = "rename_${randomTag}_$cleanDesiredName"
            journalTemp(target.item, tempName)?.let { patternJournaled += it }

            if (rename(target.item, tempName, notifyScan = false)) {
                val newPath = File(target.dir, tempName).absolutePath
                val updatedMedia = target.item.copy(displayName = tempName, absolutePath = newPath)
                phase1Steps += Phase1Step(target.item, updatedMedia, target)
            } else {
                failed += target.item
                break
            }
            stepCount++
            onProgress(stepCount, totalSteps)
        }

        if (failed.isNotEmpty()) {
            for (step in phase1Steps) {
                rename(step.tempMedia, step.originalMedia.displayName, notifyScan = false)
            }
            RenameJournal.remove(patternJournaled)
            return@renameBatch 0 to itemsInOrder
        }

        // Between Phase 1 and 2: resolve collisions per file independently
        // Files in batch are currently under tempName, so original paths are free unless occupied by external files.
        val finalNames = ArrayList<String>(itemTargets.size)
        val claimedCanonicalPaths = mutableSetOf<String>()

        for (target in itemTargets) {
            var k = 0
            var candidateName: String
            while (true) {
                val suffix = if (k == 0) "" else "~$k"
                candidateName = if (target.ext.isNotEmpty()) {
                    "${target.baseName}$suffix.${target.ext}"
                } else {
                    "${target.baseName}$suffix"
                }
                val candidateFile = File(target.dir, candidateName)
                val canonical = runCatching { candidateFile.canonicalPath }.getOrNull()
                    ?: candidateFile.absolutePath

                val existsOnDisk = candidateFile.exists()
                val claimedByBatch = canonical in claimedCanonicalPaths

                if (!existsOnDisk && !claimedByBatch) {
                    claimedCanonicalPaths.add(canonical)
                    break
                }
                k++
            }
            finalNames.add(candidateName)
        }

        // Phase 2: strip temp prefix and apply resolved final name (with per-item ~k if collided)
        var successCount = 0
        val newlyRenamedFiles = ArrayList<File>()
        val renamedMap = HashMap<Long, Pair<String, String?>>()

        for (i in phase1Steps.indices) {
            val step = phase1Steps[i]
            val finalName = finalNames[i]
            if (rename(step.tempMedia, finalName, notifyScan = false)) {
                successCount++
                val parentDir = File(step.tempMedia.absolutePath ?: "").parentFile
                    ?: fileForMedia(step.tempMedia)?.parentFile
                    ?: File("")
                val newPath = File(parentDir, finalName).absolutePath
                newlyRenamedFiles += File(newPath)
                renamedMap[step.originalMedia.id] = finalName to newPath
            } else {
                failed += step.tempMedia
                phase2Pending += PendingPatternRename(step.tempMedia, finalName, step.originalMedia.displayName)
            }
            stepCount++
            onProgress(stepCount.coerceAtMost(totalSteps), totalSteps)
        }
        // Files waiting for consent stay noted until retryPendingPatternRenames / abandon.
        val waiting = synchronized(phase2Pending) { phase2Pending.mapNotNullTo(HashSet()) { it.temp.absolutePath } }
        RenameJournal.remove(patternJournaled.filter { it.absolutePath !in waiting })

        if (renamedMap.isNotEmpty()) {
            // A sync reading MediaStore meanwhile must not undo this.
            patchMedia(alsoFolders = true) { list ->
                list.map { existing ->
                    val ren = renamedMap[existing.id]
                    if (ren != null) {
                        existing.copy(
                            displayName = ren.first,
                            absolutePath = ren.second ?: existing.absolutePath,
                        )
                    } else existing
                }
            }
        }

        if (newlyRenamedFiles.isNotEmpty()) {
            MediaScannerConnection.scanFile(
                appContext,
                newlyRenamedFiles.map { it.absolutePath }.toTypedArray(),
                null,
            ) { _, _ ->
                requestRefresh()
            }
        }

        successCount to failed
    }

    /** A file left under its temporary name when phase 2 of a numbered rename failed. */
    private data class PendingPatternRename(val temp: GalleryMedia, val finalName: String, val originalName: String)

    private val phase2Pending = java.util.Collections.synchronizedList(ArrayList<PendingPatternRename>())

    fun hasPendingPatternRenames(): Boolean = phase2Pending.isNotEmpty()

    /**
     * Finishes a numbered rename after write consent: the files still under temporary names get
     * the final names computed the first time (numbering unchanged); one that still can't be
     * renamed goes back to its original name. Returns how many got their final name.
     */
    suspend fun retryPendingPatternRenames(): Int = renameBatch(Dispatchers.IO) {
        val pending = synchronized(phase2Pending) { phase2Pending.toList().also { phase2Pending.clear() } }
        var done = 0
        val scan = ArrayList<String>()
        for (p in pending) {
            val dir = p.temp.absolutePath?.let { File(it).parentFile } ?: fileForMedia(p.temp)?.parentFile
            val name = when {
                rename(p.temp, p.finalName, notifyScan = false) -> p.finalName.also { done++ }
                rename(p.temp, p.originalName, notifyScan = false) -> p.originalName
                else -> null
            }
            if (name != null && dir != null) scan += File(dir, name).absolutePath
        }
        RenameJournal.remove(pending.mapNotNull { it.temp.absolutePath?.let(::File) })
        if (scan.isNotEmpty()) {
            MediaScannerConnection.scanFile(appContext, scan.toTypedArray(), null) { _, _ -> requestRefresh() }
        }
        done
    }

    /** Consent denied: give the files left under temporary names their original names back. */
    suspend fun abandonPendingPatternRenames() = renameBatch(Dispatchers.IO) {
        val pending = synchronized(phase2Pending) { phase2Pending.toList().also { phase2Pending.clear() } }
        for (p in pending) rename(p.temp, p.originalName, notifyScan = true)
        RenameJournal.remove(pending.mapNotNull { it.temp.absolutePath?.let(::File) })
        if (pending.isNotEmpty()) requestRefresh()
    }

    /**
     * Notes in [RenameJournal] that [item]'s file is about to get the temporary [tempName], so a
     * killed process doesn't leave it under that name. Returns the temporary file.
     */
    private fun journalTemp(item: GalleryMedia, tempName: String, alternative: File? = null): File? {
        val original = (item.absolutePath ?: fileForMedia(item)?.absolutePath)?.let(::File) ?: return null
        val temp = File(original.parentFile ?: return null, tempName)
        RenameJournal.add(temp, original, alternative)
        return temp
    }

    private fun uniqueTempSibling(parent: File, id: Long, extension: String): File {
        var n = 0
        while (n < 10_000) {
            val name = if (extension.isNotBlank()) {
                "__alexet_swap_${id}_${System.nanoTime()}_$n.$extension"
            } else {
                "__alexet_swap_${id}_${System.nanoTime()}_$n"
            }
            val f = File(parent, name)
            if (!f.exists()) return f
            n++
        }
        return File(parent, "__alexet_swap_${id}_${System.nanoTime()}")
    }

    private fun uniqueTempDisplayName(item: GalleryMedia, salt: Int = 0): String {
        val ext = item.displayName.substringAfterLast('.', missingDelimiterValue = "")
        val base = "__alexet_tmp_${item.id}_${System.nanoTime()}_$salt"
        return if (ext.isNotEmpty()) "$base.$ext" else base
    }

    private fun moveOne(
        item: GalleryMedia,
        dest: Album,
        destDir: File,
        relative: String,
        isAnonymous: Boolean = false,
        randomizeFileNames: Boolean = true,
        stripExif: Boolean = true,
    ): Pair<File, File?>? {
        val srcFile = fileForMedia(item)
        val baseName = if (isAnonymous && randomizeFileNames) ExifUtils.generateAnonymousFileName(item.displayName) else item.displayName
        val target = resolveUniqueTargetFile(destDir, baseName)
        markOwnWrite(target)
        if (srcFile != null && srcFile.exists()) {
            if (destDir.exists() || destDir.mkdirs()) {
                if (srcFile.renameTo(target)) {
                    if (isAnonymous && stripExif && !item.isOtherFile) {
                        ExifUtils.stripExifExceptDate(target)
                    }
                    // A plain file (listed from disk) has no MediaStore row of its own here.
                    if (item.uri.scheme == "file") return target to srcFile
                    // Keep MediaStore in sync so F1 does not resurrect a stale row.
                    val values = ContentValues().apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                        }
                        if (target.name != item.displayName) {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
                        }
                    }
                    val updated = try {
                        resolver.update(item.uri, values, null, null) > 0
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        rememberRecoverable(e)
                        false
                    }
                    if (!updated) {
                        try {
                            resolver.delete(item.uri, null, null)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            rememberRecoverable(e)
                            // pendingMoves mask remains as fallback until orphan is gone
                        }
                    } else {
                        runCatching { resolver.notifyChange(item.uri, null) }
                    }
                    return target to srcFile
                }
            }
        }
        val values = ContentValues().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            }
            if (target.name != item.displayName) {
                put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
            }
        }
        val ok = try {
            resolver.update(item.uri, values, null, null) > 0
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            rememberRecoverable(e)
            false
        }
        if (!ok) return null
        if (isAnonymous && stripExif) {
            val after = fileForMedia(item) ?: target
            if (after.exists()) {
                ExifUtils.stripExifExceptDate(after)
            } else {
                ExifUtils.stripExifExceptDate(appContext, item.uri)
            }
        }
        runCatching { resolver.notifyChange(item.uri, null) }
        return target to srcFile
    }

    suspend fun moveToAlbum(
        items: List<GalleryMedia>,
        dest: Album,
        isAnonymous: Boolean = false,
        randomizeFileNames: Boolean = true,
        stripExif: Boolean = true,
        stampSelectionOrderMtimes: Boolean = false,
        uiBatchSize: Int = 1,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Set<Long> = withContext(Dispatchers.IO) {
        val relative = albumRelativePath(dest)
        val destDir = directoryForAlbum(dest)
            ?: File(Environment.getExternalStorageDirectory(), relative.trim().trim('/'))
        val moved = LinkedHashSet<Long>()
        var touchedNomedia = false
        val validItems = items.filter { it.bucketId != dest.id }
        val total = validItems.size
        var current = 0
        val scanPaths = ArrayList<String>()
        val movedUpdates = HashMap<Long, Triple<String, String, String>>()
        val orderedMovedTargets = ArrayList<File>()
        val srcDirsToRescan = HashSet<File>()

        val destIsHidden = dest.isHidden || File(destDir, ".nomedia").exists()
        val destRawBucketId = mediaStoreBucketIdOf(destDir)

        // Moved files leave the source folder a row at a time (uiBatchSize = its columns): the
        // tiles behind them then move up by whole rows instead of re-flowing after every file.
        // A batch that is not full is shown after BATCH_MAX_WAIT_MS anyway (large files).
        val batchSize = uiBatchSize.coerceAtLeast(1)
        val batchLock = Any()
        val pendingUi = ArrayList<MovedForUi>()
        var batchStartedAt = 0L
        fun publishBatch() {
            if (pendingUi.isEmpty()) return
            publishMoved(pendingUi.toList(), dest, relative, destIsHidden, destRawBucketId)
            pendingUi.clear()
        }
        val batchTimer = launch {
            while (true) {
                delay(BATCH_TICK_MS)
                synchronized(batchLock) {
                    if (pendingUi.isNotEmpty() &&
                        android.os.SystemClock.elapsedRealtime() - batchStartedAt >= BATCH_MAX_WAIT_MS
                    ) {
                        publishBatch()
                    }
                }
            }
        }

        try {
            for (item in validItems) {
                if (downloadTracker.isActive(item.absolutePath)) continue
                val pair = moveOne(item, dest, destDir, relative, isAnonymous, randomizeFileNames, stripExif)
                current++
                onProgress(current, total)
                if (pair == null) continue

                val (targetFile, srcFile) = pair
                finishOwnWrite(targetFile)
                moved += item.id
                if (destIsHidden) {
                    touchedNomedia = true
                }
                scanPaths += targetFile.absolutePath
                if (srcFile != null && srcFile.absolutePath != targetFile.absolutePath) {
                    scanPaths += srcFile.absolutePath
                    srcFile.parentFile?.let { srcDirsToRescan += it }
                }
                item.absolutePath?.let { scanPaths += it }

                val newAbsPath = targetFile.absolutePath
                pendingMoves[item.id] = PendingMove(
                    oldMediaId = item.id,
                    srcBucketId = item.bucketId,
                    destBucketId = dest.id,
                    destBucketName = dest.name,
                    destRelativePath = relative,
                    destAbsolutePath = newAbsPath,
                    destRawBucketId = destRawBucketId,
                    displayName = targetFile.name,
                    srcDisplayName = item.displayName,
                    srcAbsolutePath = (srcFile?.absolutePath ?: item.absolutePath)?.lowercase(),
                )
                mutationEpoch.incrementAndGet()
                movedUpdates[item.id] = Triple(relative, newAbsPath, targetFile.name)
                if (stampSelectionOrderMtimes) {
                    orderedMovedTargets += targetFile
                }

                // Optimistic UI in batches (see publishMoved).
                synchronized(batchLock) {
                    if (pendingUi.isEmpty()) batchStartedAt = android.os.SystemClock.elapsedRealtime()
                    pendingUi += MovedForUi(item, newAbsPath, targetFile.name)
                    if (pendingUi.size >= batchSize) publishBatch()
                }
            }
        } finally {
            // Also when stopped part-way: the files already moved are shown where they are.
            batchTimer.cancel()
            synchronized(batchLock) { publishBatch() }
        }

        if (stampSelectionOrderMtimes && orderedMovedTargets.isNotEmpty()) {
            applySelectionOrderModifiedDates(orderedMovedTargets)
        }

        ioScope.launch {
            nomediaCache = null
            if (scanPaths.isNotEmpty()) {
                val pathsArray = scanPaths.distinct().toTypedArray()
                val remaining = java.util.concurrent.atomic.AtomicInteger(pathsArray.size)
                MediaScannerConnection.scanFile(
                    appContext,
                    pathsArray,
                    null,
                ) { _, _ ->
                    if (remaining.decrementAndGet() == 0) {
                        requestRefresh()
                    }
                }
            }
            // The moved files themselves (old and new paths) are in scanPaths. Whole trees only for
            // hidden folders: a visible folder of 10,000 files sent each of them to the scanner.
            if (destDir.exists() && destIsHidden) {
                nomediaScanner.rescanTree(destDir)
            }
            for (srcDir in srcDirsToRescan) {
                if (srcDir.exists() && File(srcDir, ".nomedia").exists()) {
                    nomediaScanner.rescanTree(srcDir)
                }
            }
            if (scanPaths.isEmpty()) {
                syncAfterOperation()
            }
        }
        moved
    }


    private class MovedForUi(val item: GalleryMedia, val newAbsPath: String, val newName: String)

    /** Moved files out of their folders and into [dest] in the shown lists, in one step. */
    private fun publishMoved(
        batch: List<MovedForUi>,
        dest: Album,
        relative: String,
        destIsHidden: Boolean,
        destRawBucketId: Long,
    ) {
        val byId = batch.associateBy { it.item.id }
        fun relocate(existing: GalleryMedia, m: MovedForUi) = existing.copy(
            bucketId = dest.id,
            bucketName = dest.name,
            relativePath = relative,
            absolutePath = m.newAbsPath,
            displayName = m.newName,
            isHidden = existing.isHidden || destIsHidden,
            rawBucketId = destRawBucketId,
        )
        synchronized(publishLock) {
            mutationEpoch.incrementAndGet()
            // The relocated rows are kept here: looking them up again meant a second pass (and a
            // map) over the whole library for every batch.
            val relocatedById = HashMap<Long, GalleryMedia>(batch.size * 2)
            _currentMedia.update { list ->
                list.map { existing ->
                    byId[existing.id]?.let { m -> relocate(existing, m).also { relocatedById[existing.id] = it } } ?: existing
                }
            }
            _folderMedia.update { map ->
                val mutable = map.toMutableMap()
                for ((albumId, list) in map) {
                    if (albumId == dest.id) continue
                    val filtered = list.filter { it.id !in byId }
                    if (filtered.size != list.size) mutable[albumId] = filtered
                }
                val destList = mutable[dest.id].orEmpty().filter { it.id !in byId }
                // Other files are listed from the destination itself (if it shows them).
                val relocated = batch.filter { !it.item.isOtherFile }.map { m -> relocatedById[m.item.id] ?: relocate(m.item, m) }
                mutable[dest.id] = (destList + relocated).distinctBy { it.id }
                mutable
            }
        }
        if (batch.any { it.item.isOtherFile }) {
            dropOtherFiles(batch.filter { it.item.isOtherFile }.mapTo(HashSet()) { it.item.id })
            scheduleOtherFilesRefresh()
        }
    }

    // ---------------------------------------------------------------- "See all files"

    private val _otherFiles = MutableStateFlow<Map<Long, List<GalleryMedia>>>(emptyMap())
    /** Other files (documents, archives, apps…) of the folders shown with "see all files", by album. */
    val otherFiles: StateFlow<Map<Long, List<GalleryMedia>>> = _otherFiles.asStateFlow()

    private class OtherDir(val dir: File, val name: String, val relativePath: String)
    /** Folders whose other files are listed; listed again after file operations and disk changes. */
    private val otherFileDirs = java.util.concurrent.ConcurrentHashMap<Long, OtherDir>()
    private val otherRefreshLock = Any()
    private var otherRefreshJob: Job? = null

    private val _fileTableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * A change of files MediaStore does not list as photos or videos (documents, archives...), or
     * any change on an Android without exact notifications: what the folders with other files are made of.
     */
    val fileTableChanges: SharedFlow<Unit> = _fileTableChanges.asSharedFlow()

    private val _otherFolders = MutableStateFlow<List<Album>>(emptyList())
    /** Folders that hold other files, from MediaStore's file table ("show folders with other files"). */
    val otherFolders: StateFlow<List<Album>> = _otherFolders.asStateFlow()

    /** Folders with a kept list, least recently opened first (see [OTHER_FILES_KEPT_MAX]). */
    private val otherFilesOpened = LinkedHashSet<Long>()
    /** Which folders may show other files; the lists of the others are dropped (see [retainOtherFiles]). */
    @Volatile private var otherFilesEligible: ((Long) -> Boolean)? = null

    init {
        // Lists kept by an earlier run first, then every change is saved (a second later).
        ioScope.launch {
            val saved = OtherFilesCache.load(appContext)
            if (saved.isNotEmpty()) {
                synchronized(otherFilesOpened) {
                    val opened = otherFilesOpened.toList()
                    otherFilesOpened.clear()
                    otherFilesOpened.addAll(saved.keys)
                    otherFilesOpened.addAll(opened)
                }
                _otherFiles.update { current ->
                    val eligible = otherFilesEligible
                    // A folder listed meanwhile keeps its fresh list.
                    saved.filterKeys { it !in current && (eligible == null || eligible(it)) } + current
                }
            }
            _otherFiles.debounce(OTHER_FILES_SAVE_MS).collect { OtherFilesCache.save(appContext, it) }
        }
    }

    /**
     * Lists [albumId]'s other files and keeps them fresh until [pauseOtherFiles]. A list kept from
     * before is already on screen; it only changes when the folder did.
     */
    suspend fun loadOtherFiles(albumId: Long, album: Album?) = withContext(Dispatchers.IO) {
        val resolved = album
            ?: _seedAlbums.value.firstOrNull { it.id == albumId }
            ?: _otherFolders.value.firstOrNull { it.id == albumId }
            ?: return@withContext
        if (resolved.isSecure) return@withContext
        val dir = directoryForAlbum(resolved) ?: return@withContext
        if (SecureFolderManager.isFolderSecure(dir)) return@withContext
        val entry = OtherDir(dir, resolved.name, resolved.relativePath)
        otherFileDirs[albumId] = entry
        val list = OtherFiles.list(dir, albumId, entry.name, entry.relativePath)
        val evicted = synchronized(otherFilesOpened) {
            otherFilesOpened.remove(albumId)
            otherFilesOpened.add(albumId)
            val old = ArrayList<Long>()
            val iterator = otherFilesOpened.iterator()
            while (otherFilesOpened.size - old.size > OTHER_FILES_KEPT_MAX && iterator.hasNext()) {
                val id = iterator.next()
                if (otherFileDirs.containsKey(id)) continue
                old += id
            }
            otherFilesOpened.removeAll(old.toSet())
            old
        }
        _otherFiles.update { map ->
            val trimmed = if (evicted.any { it in map }) map - evicted.toSet() else map
            if (trimmed[albumId] == list) trimmed else trimmed + (albumId to list)
        }
    }

    /** The folder left the screen: its list is kept (opening it again shows it at once) but no longer refreshed. */
    fun pauseOtherFiles(albumId: Long) {
        otherFileDirs.remove(albumId)
    }

    /** The folder no longer shows other files: its list goes. */
    fun forgetOtherFiles(albumId: Long) {
        otherFileDirs.remove(albumId)
        synchronized(otherFilesOpened) { otherFilesOpened.remove(albumId) }
        _otherFiles.update { if (albumId in it) it - albumId else it }
    }

    /** Drops the kept lists of folders that no longer show other files (a switch was turned off). */
    fun retainOtherFiles(eligible: (Long) -> Boolean) {
        otherFilesEligible = eligible
        val gone = _otherFiles.value.keys.filterNot(eligible)
        gone.forEach { forgetOtherFiles(it) }
    }

    /** Lists the shown folders' other files again shortly (after an operation, a change on disk). */
    fun scheduleOtherFilesRefresh() {
        if (otherFileDirs.isEmpty()) return
        synchronized(otherRefreshLock) {
            otherRefreshJob?.cancel()
            otherRefreshJob = ioScope.launch {
                delay(OTHER_FILES_REFRESH_MS)
                val fresh = otherFileDirs.mapValues { (id, d) -> OtherFiles.list(d.dir, id, d.name, d.relativePath) }
                _otherFiles.update { map ->
                    var changed = false
                    val next = map.toMutableMap()
                    for ((id, list) in fresh) {
                        if (otherFileDirs.containsKey(id) && next[id] != list) {
                            next[id] = list
                            changed = true
                        }
                    }
                    if (changed) next else map
                }
            }
        }
    }

    /** Takes [ids] off the other-file lists at once (deleted or moved). */
    private fun dropOtherFiles(ids: Set<Long>) {
        if (ids.isEmpty() || _otherFiles.value.isEmpty()) return
        _otherFiles.update { map ->
            if (map.values.none { list -> list.any { it.id in ids } }) map
            else map.mapValues { (_, list) -> list.filter { it.id !in ids } }
        }
    }

    /**
     * Folders holding other files, from MediaStore's table of all files (only names and dates are
     * read). Hidden folders (a dot in the path, `.nomedia`) are marked hidden; Android/ is skipped.
     */
    suspend fun scanOtherFolders() = withContext(Dispatchers.IO) {
        if (!StorageAccess.canReadAllFiles()) {
            _otherFolders.value = emptyList()
            return@withContext
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        class Acc(var count: Int, var newestSec: Long)
        val byDir = HashMap<String, Acc>()
        runCatching {
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
            )
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} AND " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO} AND " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL"
            resolver.query(MediaStore.Files.getContentUri("external"), projection, selection, null, null)?.use { rows ->
                while (rows.moveToNext()) {
                    @Suppress("DEPRECATION")
                    val data = rows.getString(0) ?: continue
                    val slash = data.lastIndexOf('/')
                    if (slash <= 0) continue
                    if (!OtherFiles.isListedName(data.substring(slash + 1))) continue
                    val acc = byDir.getOrPut(data.substring(0, slash)) { Acc(0, 0L) }
                    acc.count++
                    acc.newestSec = maxOf(acc.newestSec, rows.getLong(1))
                }
            }
        }.onFailure { AppLog.w("GalleryRepository", "Folders with other files: query failed", it) }
        val albums = byDir.mapNotNull { (path, acc) ->
            val dir = File(path)
            val rel = OtherFiles.relativePathOf(dir) ?: return@mapNotNull null
            val trimmed = rel.trim('/')
            if (trimmed.startsWith("Android/", ignoreCase = true) || trimmed.equals("Android", ignoreCase = true)) return@mapNotNull null
            val dotted = trimmed.split('/').any { it.startsWith(".") }
            Album(
                id = NomediaScanner.syntheticBucketId(trimmed),
                name = trimmed.substringAfterLast('/'),
                cover = null,
                itemCount = acc.count,
                relativePath = rel,
                isHidden = dotted || File(dir, ".nomedia").exists(),
                latestAddedMs = acc.newestSec * 1000L,
            )
        }
        _otherFolders.value = albums
        AppLog.event("Sync") {
            "folders with other files: ${albums.size} in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
        }
    }

    /** Drops the file lists of [albumIds] (private folders that were locked). */
    fun forgetFolderMedia(albumIds: Set<Long>) {
        _folderMedia.update { map -> if (map.keys.any { it in albumIds }) map - albumIds else map }
    }

    /** AES Skarbiec listings live only in folderMedia (.enc never appears in MediaStore). */
    private fun isSecureFolderCache(items: List<GalleryMedia>): Boolean {
        if (items.isEmpty()) return false
        return items.any { item ->
            item.absolutePath?.endsWith(SecureFolderManager.ENC_EXTENSION, ignoreCase = true) == true
        }
    }

    /**
     * Keep an already-shown Images/Video URI when a later query returns the same file
     * under the Downloads collection (that URI skips MediaStore thumbs and looks empty).
     */
    private fun preferStableFolderRows(
        currentItems: List<GalleryMedia>,
        fromFresh: List<GalleryMedia>,
    ): List<GalleryMedia> {
        if (currentItems.isEmpty() || fromFresh.isEmpty()) return fromFresh
        val byPath = HashMap<String, GalleryMedia>(currentItems.size)
        val byId = HashMap<Long, GalleryMedia>(currentItems.size)
        for (item in currentItems) {
            item.absolutePath?.lowercase()?.let { byPath.putIfAbsent(it, item) }
            byId.putIfAbsent(item.id, item)
        }
        return fromFresh.map { incoming ->
            val path = incoming.absolutePath?.lowercase()
            val existing = (if (path != null) byPath[path] else null) ?: byId[incoming.id] ?: return@map incoming
            val isUnchanged = existing.id == incoming.id &&
                existing.sizeBytes == incoming.sizeBytes &&
                kotlin.math.abs(existing.dateModifiedMs - incoming.dateModifiedMs) < 2000L &&
                existing.displayName == incoming.displayName &&
                existing.uri == incoming.uri

            if (isUnchanged) {
                existing
            } else if (DownloadsWatchLogic.isDownloadsCollectionUri(incoming.uri) &&
                !DownloadsWatchLogic.isDownloadsCollectionUri(existing.uri)
            ) {
                existing
            } else {
                incoming
            }
        }
    }

    /** Publish a full MediaStore result and persist startup snapshot (not for optimistic patches). */
    /** @return false when [publish] said no (a patch came in meanwhile); nothing is changed then. */
    private fun applyQueryResult(marked: Marked, publish: () -> Boolean = { true }): Boolean {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val fresh = marked.rows
        synchronized(publishLock) {
            if (!publish()) return false
            _currentMedia.value = fresh
            markedList = fresh
            markedWith = marked.with
        }
        val albums = albumsFrom(fresh)
        _seedAlbums.value = albums
        var changedFolders = 0
        if (_folderMedia.value.isNotEmpty()) {
            // One pass over the library instead of one filter per open folder: with ~10k items and
            // a dozen cached folders the per-folder scans (string trims on every row) dominated.
            val index = folderRowIndexOf(fresh)
            val albumsById = HashMap<Long, Album>(albums.size * 2).also { map -> albums.forEach { map.putIfAbsent(it.id, it) } }
            _folderMedia.update { currentMap ->
                if (currentMap.isEmpty()) return@update currentMap
                changedFolders = 0
                val next = currentMap.mapValues { (albumId, currentItems) ->
                    val updated = rebuildFolderRows(albumId, currentItems, albumsById[albumId], index)
                    // Same rows: keep the old list so the open grid isn't recomposed for nothing.
                    if (updated == currentItems) currentItems else updated.also { changedFolders++ }
                }
                if (changedFolders == 0) currentMap else next
            }
        }
        if (albums != lastSavedAlbums) {
            lastSavedAlbums = albums
            ioScope.launch {
                AlbumCache.save(appContext, albums)
            }
        }
        AppLog.event("Sync") {
            "published ${fresh.size} items, ${albums.size} folders, $changedFolders cached folder(s) changed " +
                "in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
        }
        return true
    }

    private fun rebuildFolderRows(
        albumId: Long,
        currentItems: List<GalleryMedia>,
        album: Album?,
        index: FolderRowIndex,
    ): List<GalleryMedia> {
        // Never replace AES .enc listings with an empty MediaStore snapshot.
        if (isSecureFolderCache(currentItems)) {
            return currentItems.filter { !matchesPendingDelete(it) }
        }
        val fromFresh = if (album != null) {
            val positions = java.util.TreeSet<Int>()
            index.byBucket[albumId]?.let(positions::addAll)
            album.legacyBucketIds.forEach { legacy -> index.byRawBucket[legacy]?.let(positions::addAll) }
            val rel = FolderRowIndex.normalizeRel(album.relativePath)
            if (rel.isNotEmpty()) index.byRelPath[rel]?.let(positions::addAll)
            // TreeSet keeps the library order, as the old filter did.
            positions.map { i ->
                val item = index.rows[i]
                if (item.bucketId != albumId) item.copy(bucketId = albumId) else item
            }.distinctBy { it.id }
        } else {
            index.byBucket[albumId]?.map { index.rows[it] }?.distinctBy { it.id }.orEmpty()
        }
        val preferred = preferStableFolderRows(currentItems, fromFresh)
        val pendingTargetingThis = if (pendingMoves.isEmpty()) {
            emptyList()
        } else {
            val freshIds = preferred.mapTo(HashSet()) { it.id }
            val freshPaths = preferred.mapNotNullTo(HashSet()) { it.absolutePath?.lowercase() }
            val movesHere = pendingMoves.values.filter { it.destBucketId == albumId }
            val destPaths = movesHere.mapTo(HashSet()) { it.destAbsolutePath }
            val destNames = movesHere.mapTo(HashSet()) { it.displayName.lowercase() }
            currentItems.filter { item ->
                item.bucketId == albumId && item.id !in freshIds &&
                    (item.absolutePath == null || item.absolutePath.lowercase() !in freshPaths) &&
                    (item.absolutePath in destPaths || item.displayName.lowercase() in destNames)
            }
        }
        val combined = if (pendingTargetingThis.isEmpty()) preferred else preferred + pendingTargetingThis
        val visible = if (pendingDeletes.isEmpty()) combined else combined.filter { !matchesPendingDelete(it) }
        return visible.distinctBy { it.id }
    }

    /**
     * @param fullStore [store] is a complete MediaStore read. A delta list is built on the already
     *   masked published list, where a moved/deleted source row can never be seen, so masks may
     *   only expire against a full read.
     */
    private fun finishQuery(store: List<GalleryMedia>, fullStore: Boolean): Marked {
        val reconciled = reconcileStore(store, allowMaskExpiry = fullStore)
        if (!scanHidden.get()) return Marked(reconciled, null)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val nomedia = nomediaForMerge() ?: return Marked(reconciled, null)
        return Marked(mergeNomedia(reconciled, nomedia), nomedia).also {
            val ms = android.os.SystemClock.elapsedRealtime() - startedAt
            if (ms >= 30L) {
                AppLog.event("Sync") {
                    "hidden-folder merge: ${reconciled.size} rows, ${nomedia.hiddenRelativePaths.size} hidden folders, " +
                        "${nomedia.media.size} hidden files in $ms ms"
                }
            }
        }
    }

    /** A list ready to publish, and the read of the hidden folders its rows are marked with (null: none). */
    private class Marked(val rows: List<GalleryMedia>, val with: NomediaScanResult?)

    /**
     * [base] (the published list) with a MediaStore delta applied, ready to publish. When [base] is
     * the list last marked with the hidden folders as they are cached now, it already holds their
     * files and marks: only the rows the delta brought (and moves relocated) are marked, instead
     * of every row of the library on every change (150-220 ms with 13k rows).
     */
    private fun finishDelta(base: List<GalleryMedia>, upserts: List<GalleryMedia>, deleted: List<Long>): Marked {
        val merged = MediaIndexLogic.mergeDelta(base, upserts, deleted.toHashSet())
        val cached = nomediaCache
        if (!scanHidden.get() || cached == null || base !== markedList || cached !== markedWith) {
            return finishQuery(merged, fullStore = false)
        }
        val reconciled = reconcileStore(merged, allowMaskExpiry = false)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val unchanged = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<GalleryMedia, Boolean>(base.size * 2))
        unchanged.addAll(base)
        val index = HiddenFolderMarker.Index(cached.hiddenRelativePaths)
        var marked = 0
        val rows = reconciled.map { item ->
            if (item in unchanged) item else HiddenFolderMarker.markRow(item, index).also { marked++ }
        }
        AppLog.event("Sync") {
            "hidden-folder marks: $marked new row(s) of ${rows.size} in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
        }
        return Marked(rows, cached)
    }

    /**
     * Hidden-folder rows to merge into a MediaStore read: the cached scan, or, while there is
     * none, the hidden rows already listed (a fresh read of the hidden folders then runs in the
     * background). Walking the hidden folders inside every sync made each new photo wait seconds.
     */
    private fun nomediaForMerge(): NomediaScanResult? {
        nomediaCache?.let { return it }
        refreshNomediaInBackground()
        val paths = settings.settings.value.nomediaFolderPaths
        val rows = _currentMedia.value.filter { it.isHidden && it.uri.scheme == "file" }
        if (paths.isEmpty() && rows.isEmpty()) return null
        return NomediaScanResult(rows, paths)
    }

    /** Bumped whenever the hidden folders change; a background read started before is stale. */
    private val nomediaVersion = java.util.concurrent.atomic.AtomicInteger(0)
    private val nomediaRefreshRunning = AtomicBoolean(false)

    /** The hidden folders changed (files copied, deleted, renamed): read them again in the background. */
    private fun invalidateNomediaCache() {
        nomediaCache = null
        nomediaVersion.incrementAndGet()
        refreshNomediaInBackground()
    }

    private fun refreshNomediaInBackground() {
        if (!scanHidden.get()) return
        if (!nomediaRefreshRunning.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                while (nomediaCache == null && scanHidden.get()) {
                    val version = nomediaVersion.get()
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    val fresh = quickNomediaFromIndex() ?: NomediaScanResult.Empty
                    if (version != nomediaVersion.get()) continue
                    // A full scan that finished meanwhile is newer and complete: keep it.
                    if (!offerQuickNomedia(fresh)) break
                    publishNomedia(fresh)
                    AppLog.event("Sync") {
                        "hidden folders read in the background: ${fresh.media.size} file(s) " +
                            "in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
                    }
                }
            } finally {
                nomediaRefreshRunning.set(false)
            }
        }
    }

    /**
     * The refresh gesture on the folder list: every known hidden folder read from disk again now
     * (as on a start), and the storage walked in the background for hidden folders not known yet.
     */
    private fun rereadHiddenFolders() {
        if (!scanHidden.get()) return
        // A background read started before this one is older: it is not stored.
        nomediaVersion.incrementAndGet()
        quickNomediaFromIndex(listAll = true)?.let { takeInHiddenRead(it) }
        ensureFullNomediaScan(minAgeMs = HIDDEN_REREAD_MS, listAll = true)
    }

    /**
     * Coming back to the app: the known hidden folders read again in the background, at most once a
     * minute. Only folders whose date changed are listed (0.5-0.75 s for all of them before), the
     * cached read stays in use meanwhile, and the lists change only when the folders did.
     */
    private fun rereadHiddenFoldersLater() {
        // Nothing cached: a read is on its way already.
        if (!scanHidden.get() || nomediaCache == null) return
        if (android.os.SystemClock.elapsedRealtime() - nomediaReadAt < HIDDEN_REREAD_MS) return
        nomediaReadAt = android.os.SystemClock.elapsedRealtime()
        val version = nomediaVersion.get()
        ioScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val fresh = quickNomediaFromIndex() ?: NomediaScanResult.Empty
            // Something newer was stored meanwhile (a walk, a hidden folder read again): it stays.
            if (version != nomediaVersion.get()) return@launch
            val changed = takeInHiddenRead(fresh) == true
            AppLog.event("Hidden") {
                "hidden folders checked again: ${fresh.media.size} file(s), ${if (changed) "changed" else "unchanged"}, " +
                    "in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
            }
        }
    }

    /**
     * Reads one hidden folder from disk again and puts what it holds now into the cached read of the
     * hidden folders and the lists: files other apps put there (downloads) show up, gone ones go.
     */
    private fun rereadHiddenFolder(relativePath: String) {
        if (!scanHidden.get()) return
        val folder = relativePath.trim().trim('/')
        if (folder.isEmpty()) return
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val fresh = nomediaScanner.scanKnown(listOf(folder))
        AppLog.event("Hidden") {
            "hidden folder read again: ${fresh.media.size} file(s) in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
        }
        // Not (or no longer) a hidden folder: left to the walk of the storage.
        if (fresh.hiddenRelativePaths.isEmpty()) return
        val key = folder.lowercase()
        val intake = synchronized(nomediaCacheLock) {
            // Nothing cached: the read running in the background sees the folder as it is now.
            val cached = nomediaCache ?: return
            val next = NomediaScanResult(
                media = cached.media.filter { it.relativePath.trim().trim('/').lowercase() != key } + fresh.media,
                hiddenRelativePaths = (cached.hiddenRelativePaths + fresh.hiddenRelativePaths)
                    .distinctBy { it.trim().trim('/').lowercase() },
            )
            // A read of all known folders started before this one is older: it is not stored.
            intakeLocked(next).also { if (it.changed) nomediaVersion.incrementAndGet() }
        }
        // Into the lists when they lack it, even with the cached read unchanged: the folder's own
        // read could show a file the library list never got (opened and refreshed, gone again).
        intake.publish?.let { publishNomedia(it) }
    }

    /**
     * Files another app put into (or took from) hidden folders while Alexet runs: the cached read of
     * the hidden folders doesn't have them. A known hidden folder is read again; one not known yet
     * (a new folder with `.nomedia`) is found by the walk of the storage.
     */
    private fun onHiddenFilesChanged(paths: Collection<String>) {
        if (!scanHidden.get() || paths.isEmpty()) return
        ioScope.launch {
            val known = settings.settings.value.nomediaFolderPaths
                .map { it.trim().trim('/') }
                .filter { it.isNotEmpty() }
            val roots by lazy { storageRoots().mapTo(HashSet()) { it.absolutePath } }
            val folders = LinkedHashSet<String>()
            var unknown = false
            for (path in paths) {
                // Thumbnails, trash, app-private folders: never walked, so never a hidden folder's
                // (a thumbnail written there walked the whole storage each time).
                if (NomediaScanner.isInUnwalkedFolder(path)) continue
                File(path).parentFile?.let { nomediaScanner.forget(it) }
                val rel = HiddenFolderMarker.volumeRelative(path)
                val folder = rel?.let { r -> known.firstOrNull { r.startsWith("$it/", ignoreCase = true) } }
                when {
                    folder != null -> folders += folder
                    !unknown && hasNomediaAbove(File(path), roots) -> unknown = true
                }
            }
            folders.forEach { rereadHiddenFolder(it) }
            // A burst of files (a folder copied in) gives one walk, two seconds after the last one.
            if (unknown) rescanHiddenNow(delayMs = HIDDEN_WALK_SETTLE_MS)
        }
    }

    /**
     * Rows MediaStore added or changed only in its file table: not photos or videos it lists, but
     * documents, or photos and videos in hidden folders (seen on Android 17: such a file is a
     * pending row of the file table). The hidden ones are read in at once instead of at the next
     * walk of the storage (up to 30 minutes later).
     */
    private fun noteFileOnlyChanges(ids: Set<Long>) {
        if (ids.isEmpty() || !scanHidden.get()) return
        ioScope.launch {
            val paths = queryPathsOfIds(ids).filter { path ->
                NomediaScanner.mimeOf(path.substringAfterLast('/')) != null && !isOwnWrite(path)
            }
            if (paths.isEmpty()) return@launch
            AppLog.event("Hidden") { "file-table change: ${paths.size} photo(s) or video(s) outside MediaStore's lists" }
            onHiddenFilesChanged(paths)
        }
    }

    /** Paths of the file-table rows [ids], pending ones too. */
    private fun queryPathsOfIds(ids: Collection<Long>): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val out = ArrayList<String>()
        for (chunk in ids.chunked(500)) {
            val selection = "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",")})"
            runCatching {
                resolver.query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    @Suppress("DEPRECATION") arrayOf(MediaStore.MediaColumns.DATA),
                    pendingQueryArgs(selection, null, null),
                    null,
                )?.use { rows ->
                    while (rows.moveToNext()) rows.getString(0)?.takeIf { it.isNotBlank() }?.let { out += it }
                }
            }.onFailure { AppLog.w("GalleryRepository", "File-table rows query failed", it) }
        }
        return out
    }

    /** A folder above [file], up to its storage volume ([roots]), holds a `.nomedia` file. */
    private fun hasNomediaAbove(file: File, roots: Set<String>): Boolean {
        var dir = file.parentFile
        while (dir != null && dir.absolutePath !in roots) {
            if (File(dir, ".nomedia").exists()) return true
            dir = dir.parentFile
        }
        return false
    }

    private fun externalVolumes(): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getExternalVolumeNames(appContext) }.getOrDefault(emptySet())
        } else {
            setOf(MediaStore.VOLUME_EXTERNAL)
        }

    private fun currentGenerations(volumes: Set<String>): Map<String, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val out = HashMap<String, Long>(volumes.size)
        for (volume in volumes) {
            val gen = runCatching { MediaStore.getGeneration(appContext, volume) }.getOrNull() ?: return null
            out[volume] = gen
        }
        return out
    }

    /** MediaStore database version per volume (changes whenever the database is created anew). */
    private fun currentStoreVersions(volumes: Set<String>): Map<String, String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> volumes.mapNotNull { volume ->
            runCatching { MediaStore.getVersion(appContext, volume) }.getOrNull()?.let { volume to it }
        }.toMap()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> runCatching { MediaStore.getVersion(appContext) }.getOrNull()
            ?.let { mapOf(MediaStore.VOLUME_EXTERNAL_PRIMARY to it) }.orEmpty()
        else -> emptyMap()
    }

    /**
     * Drops the persisted index and everything keyed by MediaStore ids when the index was built
     * against another MediaStore database (app data carried over to a new phone, media storage
     * cleared). Favorites, hidden files and manual orders are moved to path references first, and
     * are attached again by path once the new rows are read.
     * @return true when the index was discarded.
     */
    private suspend fun discardIndexIfStoreReplaced(): Boolean {
        val volumes = externalVolumes()
        val current = currentStoreVersions(volumes)
        if (current.isEmpty()) return false
        val dropped = indexMutex.withLock {
            val stored = volumes.associateWith { mediaIndex.storeVersion(it) }
            val reason = MediaIndexLogic.storeReplacedReason(
                storedVersions = stored,
                currentVersions = current,
                storedGenerations = volumes.associateWith { mediaIndex.generation(it) },
                currentGenerations = currentGenerations(volumes),
            )
            if (reason == null) {
                if (current.any { (volume, version) -> stored[volume] != version }) mediaIndex.putStoreVersions(current)
                return@withLock null
            }
            val old = mediaIndex.loadAll()
            if (old.isEmpty() && mediaIndex.count() > 0) {
                // Unreadable right now: keep everything and decide on the next sync, or the
                // id-keyed settings would lose the paths they are moved to.
                AppLog.w("GalleryRepository", "Media index looks stale ($reason) but could not be read; retry later")
                return@withLock null
            }
            // Settings first: once the versions below are stored this check never fires again.
            if (old.isNotEmpty()) {
                val byId = HashMap<Long, GalleryMedia>(old.size * 2).also { map -> old.forEach { map[it.id] = it } }
                settings.update { s ->
                    MediaRefs.remap(
                        settings = s,
                        withPaths = true,
                        pathOfImportedId = { id -> byId[id]?.let(MediaRefs::portablePath) },
                        idForPath = { null }, // new ids are known after the full sync; pending until then
                        importedPending = s.pendingMedia,
                    )
                }
                // On disk before the index is cleared: if the app died in between, the remap
                // would be lost and never run again (favourites pointing at the wrong files).
                try {
                    settings.flush()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    AppLog.e("GalleryRepository", "Could not save remapped settings; index kept", e)
                    return@withLock null
                }
            }
            mediaIndex.clear()
            mediaIndex.putStoreVersions(current)
            persisted = HashMap()
            reason to old
        } ?: return false
        val (reason, old) = dropped
        AppLog.w("GalleryRepository", "Media index discarded: $reason (${old.size} rows)")
        runCatching { AlbumCache.file(appContext).delete() }
        lastSavedAlbums = null
        _seedAlbums.value = emptyList()
        // update {} and the publish lock: a change made at this moment by another thread is not
        // overwritten with an older list, and a sync result read before this is not published.
        synchronized(publishLock) {
            mutationEpoch.incrementAndGet()
            _currentMedia.update { list -> list.filterNot { MediaIndexLogic.isMediaStoreRow(it) } }
            _folderMedia.value = emptyMap()
        }
        folderLoadTimestamps.clear()
        // Thumbnails are cached under MediaStore uris, which now name other files.
        runCatching {
            val loader = SingletonImageLoader.get(appContext)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        FolderCoverCache.clear()
        dev.ander.alexet.ui.components.VideoDurationCache.clear()
        return true
    }

    /**
     * Bring the published list and the on-disk index in line with MediaStore.
     *
     * Delta mode (default): only rows with `GENERATION_MODIFIED` above the stored generation
     * are re-read, plus an id-only query to detect deletions. Full mode re-reads everything
     * (first run, manual refresh, daily background reconcile, pre-Android-11 devices).
     *
     * @param publish Evaluated after the query; returning false discards the result (a
     *   concurrent optimistic patch made it stale). Index generations are still not advanced
     *   in that case, so the rows are re-fetched by the follow-up refresh.
     * @return true when the result was published.
     */
    /**
     * @param deletedById The rows MediaStore's notifications named as deleted (Android 11+): used
     *   instead of reading every id (55-130 ms) to find them. Null: every id is read.
     * @param onUpserts Told the ids of the rows read as changed.
     */
    private suspend fun syncFromMediaStore(
        full: Boolean,
        background: Boolean = false,
        publish: () -> Boolean = { true },
        deletedById: Set<Long>? = null,
        onUpserts: (Set<Long>) -> Unit = {},
    ): Boolean = withContext(queryDispatcher) {
        queryMutex.withLock {
            val syncStartedAt = android.os.SystemClock.elapsedRealtime()
            // MediaStore rebuilt while running (media storage cleared): start over with a full sync.
            // Checked whenever every id is read too (start, coming back, now and then), not for
            // each change told by a notification.
            if (deletedById == null) {
                try {
                    discardIndexIfStoreReplaced()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    AppLog.e("GalleryRepository", "MediaStore identity check failed", e)
                }
            }
            val volumes = externalVolumes()
            val generations = currentGenerations(volumes)
            val storedGenerations = volumes.associateWith { mediaIndex.generation(it) }
            // An SD card that came or went: its rows are only added or dropped by a full read (a
            // delta sees no changed rows for a card that comes back, so they'd stay missing).
            val storedVolumes = runCatching { mediaIndex.volumeSet() }.getOrNull()
            val volumesChanged = storedVolumes != null && storedVolumes != volumes
            val needFull = full || !indexReady || generations == null || persistedIsEmpty() ||
                storedGenerations.values.any { it <= 0L } || volumesChanged
            val pause: suspend () -> Unit = if (background) {
                { delay(LAZY_CHUNK_PAUSE_MS) }
            } else {
                { kotlinx.coroutines.yield() }
            }
            if (needFull) {
                return@withLock fullSync(generations.orEmpty(), background, pause, publish).also { published ->
                    if (published) runCatching { mediaIndex.putVolumeSet(volumes) }
                    if (volumesChanged) AppLog.event("Sync") { "volumes changed: $storedVolumes -> $volumes" }
                    AppLog.event("Sync") {
                        "full sync (background=$background) in ${android.os.SystemClock.elapsedRealtime() - syncStartedAt} ms"
                    }
                }
            }
            if (storedVolumes == null) runCatching { mediaIndex.putVolumeSet(volumes) }
            val changedVolumes = volumes.filter { (generations[it] ?: 0L) > (storedGenerations[it] ?: 0L) }
            val queryStartedAt = android.os.SystemClock.elapsedRealtime()
            val upserts = if (changedVolumes.isEmpty()) {
                emptyList()
            } else {
                lowPriority(background) {
                    MediaIndexLogic.dedupeById(queryChangedRows(changedVolumes, storedGenerations))
                }
            }
            val changedQueriedAt = android.os.SystemClock.elapsedRealtime()
            onUpserts(upserts.mapTo(HashSet()) { it.id })
            val base = _currentMedia.value
            // Deletes bump the volume generation too, so an unchanged generation means no deletes.
            val liveIds = if (changedVolumes.isEmpty() || deletedById != null) null else lowPriority(background) { queryLiveIds(volumes) }
            val idsQueriedAt = android.os.SystemClock.elapsedRealtime()
            val deleted = if (deletedById != null) {
                if (deletedById.isEmpty()) emptyList() else base.filter { MediaIndexLogic.isMediaStoreRow(it) && it.id in deletedById }.map { it.id }
            } else if (liveIds == null) {
                emptyList()
            } else if (liveIds.isEmpty() && base.any { MediaIndexLogic.isMediaStoreRow(it) }) {
                // MediaStore returned nothing at all: permission lost or provider hiccup, not a wipe.
                emptyList()
            } else {
                MediaIndexLogic.missingIds(base, liveIds)
            }
            AppLog.event("Sync") {
                "delta: ${upserts.size} changed, ${deleted.size} deleted, volumes ${changedVolumes.joinToString()} " +
                    "queried in ${android.os.SystemClock.elapsedRealtime() - syncStartedAt} ms " +
                    "(checks ${queryStartedAt - syncStartedAt} ms, changed rows ${changedQueriedAt - queryStartedAt} ms, " +
                    if (deletedById != null) "deletions from notifications)" else "all ids ${idsQueriedAt - changedQueriedAt} ms)"
            }
            if (!publish()) return@withLock false
            val nothingChanged = upserts.isEmpty() && deleted.isEmpty()
            if (nothingChanged && pendingMoves.isEmpty() && pendingDeletes.isEmpty()) {
                if (changedVolumes.isNotEmpty()) {
                    indexMutex.withLock {
                        mediaIndex.applyDelta(emptyList(), emptyList(), generations)
                    }
                }
                return@withLock true
            }
            val fresh = finishDelta(base, upserts, deleted)
            // Publish first, index after: a result discarded here must leave the stored generation
            // where it was, or the follow-up delta would see "nothing changed" and the new rows
            // would never be shown.
            if (!applyQueryResult(fresh, publish)) return@withLock false
            val indexStartedAt = android.os.SystemClock.elapsedRealtime()
            indexMutex.withLock {
                mediaIndex.applyDelta(upserts, deleted, generations, pauseBetweenChunks = pause)
                deleted.forEach { persisted.remove(it) }
                upserts.forEach { persisted[it.id] = it }
            }
            AppLog.event("Sync") { "index written in ${android.os.SystemClock.elapsedRealtime() - indexStartedAt} ms" }
            true
        }
    }

    private suspend fun fullSync(
        generations: Map<String, Long>,
        background: Boolean,
        pause: suspend () -> Unit,
        publish: () -> Boolean,
    ): Boolean {
        val store = lowPriority(background) { queryStore() }
        val base = _currentMedia.value
        // Hidden `file://` rows and secure-folder rows live outside MediaStore; keep them unless
        // the fresh store now covers the same file.
        val storePaths = store.mapNotNullTo(HashSet(store.size)) { it.absolutePath?.lowercase() }
        val extras = base.filter { item ->
            !MediaIndexLogic.isMediaStoreRow(item) &&
                item.bucketId != AlbumIds.TRASH &&
                item.absolutePath?.lowercase().let { it == null || it !in storePaths } &&
                // A hidden file deleted or moved by another app: nothing else ever drops its row.
                (item.uri.scheme != "file" || item.absolutePath == null || File(item.absolutePath).exists())
        }
        val marked = finishQuery(if (extras.isEmpty()) store else store + extras, fullStore = true)
        // Published before the index is written (see the delta path).
        if (!applyQueryResult(marked, publish)) return false
        val fresh = marked.rows
        val now = System.currentTimeMillis()
        indexMutex.withLock {
            if (background) {
                // Chunked so a process kill mid-way keeps a consistent (older) index.
                val freshIds = fresh.mapTo(HashSet(fresh.size)) { it.id }
                val stale = persisted.keys.filter { it !in freshIds }
                mediaIndex.applyDelta(fresh, stale, generations, fullReconcileAt = now, pauseBetweenChunks = pause)
            } else {
                mediaIndex.replaceAll(fresh, generations, now)
            }
            persisted = HashMap<Long, GalleryMedia>(fresh.size * 2).also { map -> fresh.forEach { map[it.id] = it } }
            indexReady = true
        }
        return true
    }

    private suspend fun persistedIsEmpty(): Boolean = indexMutex.withLock { persisted.isEmpty() }

    /** Runs blocking MediaStore work at background thread priority when [enabled]. */
    private inline fun <T> lowPriority(enabled: Boolean, block: () -> T): T {
        if (!enabled) return block()
        val tid = android.os.Process.myTid()
        val previous = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(0)
        runCatching { android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            return block()
        } finally {
            runCatching { android.os.Process.setThreadPriority(tid, previous) }
        }
    }

    /**
     * Journal: every published list is diffed against what the index holds and only the
     * difference is written. Optimistic move/rename/delete patches therefore survive a
     * restart without waiting for MediaStore to notice them.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun journal(published: List<GalleryMedia>) {
        indexMutex.withLock {
            if (!indexReady || persisted.isEmpty()) return
            // The list as it is now, not the debounced one: a sync may have written newer rows
            // to the index while this waited for the lock, and the older list would delete them.
            val diff = MediaIndexLogic.diff(persisted, _currentMedia.value)
            if (diff.isEmpty) return
            if (MediaIndexLogic.isSuspiciousShrink(persisted.size, diff.deletes.size)) {
                dev.ander.alexet.data.AppLog.w(
                    "GalleryRepository",
                    "Journal skipped: ${diff.deletes.size} of ${persisted.size} rows vanished at once",
                )
                return
            }
            mediaIndex.applyDelta(diff.upserts, diff.deletes)
            diff.deletes.forEach { persisted.remove(it) }
            diff.upserts.forEach { persisted[it.id] = it }
        }
    }

    private fun queryChangedRows(volumes: List<String>, sinceGeneration: Map<String, Long>): List<GalleryMedia> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val genCol = MediaStore.MediaColumns.GENERATION_MODIFIED
        val rows = ArrayList<GalleryMedia>()
        for (volume in volumes) {
            val since = (sinceGeneration[volume] ?: 0L).toString()
            rows += queryCollection(
                MediaStore.Images.Media.getContentUri(volume),
                isVideo = false,
                selection = "$genCol > ?",
                selectionArgs = arrayOf(since),
                strict = true,
            )
            rows += queryCollection(
                MediaStore.Video.Media.getContentUri(volume),
                isVideo = true,
                selection = "$genCol > ?",
                selectionArgs = arrayOf(since),
                strict = true,
            )
            rows += queryCollection(
                MediaStore.Downloads.getContentUri(volume),
                isVideo = false,
                selection = "($MEDIA_MIME_SELECTION) AND $genCol > ?",
                selectionArgs = arrayOf(since),
                strict = true,
            )
        }
        return rows
    }

    /** Ids of every row MediaStore currently lists; null when any query failed. */
    private fun queryLiveIds(volumes: Set<String>): HashSet<Long>? {
        val ids = HashSet<Long>(maxOf(_currentMedia.value.size, 16))
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val targets = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                volumes.forEach { volume ->
                    add(MediaStore.Images.Media.getContentUri(volume) to null)
                    add(MediaStore.Video.Media.getContentUri(volume) to null)
                    add(MediaStore.Downloads.getContentUri(volume) to MEDIA_MIME_SELECTION)
                }
            } else {
                add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to null)
                add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI to null)
            }
        }
        for ((uri, selection) in targets) {
            val ok = runCatching {
                // Pending rows count as live: a file shown at once by publishWaitingFiles is still
                // IS_PENDING until its scan ends, and must not be dropped as deleted meanwhile.
                val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    resolver.query(uri, projection, pendingQueryArgs(selection, null, null), null)
                } else {
                    resolver.query(uri, projection, selection, null, null)
                }
                cursor?.use { c ->
                    while (c.moveToNext()) ids += c.getLong(0)
                }
            }.isSuccess
            if (!ok) return null
        }
        return ids
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun pendingQueryArgs(selection: String?, selectionArgs: Array<String>?, sort: String?): android.os.Bundle =
        android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (selectionArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            if (sort != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
        }

    // --- Files other apps wrote by path ---------------------------------------------------------

    /** Paths [runWaitingScan] handed to the scanner (normalized) -> elapsedRealtime. */
    private val publishedWaiting = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val waitingScanRunning = AtomicBoolean(false)
    @Volatile private var lastWaitingScanAt = 0L

    private fun recentlyPublishedWaiting(path: String): Boolean {
        val cutoff = android.os.SystemClock.elapsedRealtime() - WAITING_PUBLISHED_KEEP_MS
        publishedWaiting.entries.removeIf { it.value < cutoff }
        return publishedWaiting.containsKey(DownloadMediaFilter.normalizePath(path))
    }

    /** Quick scan of the whole library (see [runWaitingScan]), at most every few seconds. */
    fun publishWaitingFiles(reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWaitingScanAt < WAITING_SCAN_THROTTLE_MS) return
        lastWaitingScanAt = now
        ioScope.launch { runWaitingScan(reason) }
    }

    /**
     * A file another app writes by path is listed in MediaStore at once, but flagged IS_PENDING,
     * and only a media scan clears the flag. Android scans such files by itself only in its idle
     * maintenance (charging and idle, once a day); plain queries skip them meanwhile, so new
     * downloads showed up only after seconds to minutes. Here the finished ones are shown at once
     * (they already have their MediaStore id) and handed to the scanner in one batch; files still
     * being written stay with the download tracker.
     */
    // On IO always: the folder quick scan calls it from the UI scope, and on a slow phone with an SD
    // card these queries took 12 s on the main thread (ANR).
    private suspend fun runWaitingScan(reason: String): Unit = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext
        if (!waitingScanRunning.compareAndSet(false, true)) return@withContext
        try {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            publishWaiting(reason, queryWaitingRows(externalVolumes()), startedAt)
        } finally {
            waitingScanRunning.set(false)
        }
    }

    /**
     * Waiting rows MediaStore's notifications named ([ids]): checked at once, by id, instead of on
     * entering a folder or coming back to the app (a file written by path waited 1.4 s, or until then).
     */
    private fun publishWaitingIds(ids: Set<Long>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        ioScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val waiting = queryWaitingRowsById(ids)
            if (waiting.isNotEmpty()) publishWaiting("notified", waiting, startedAt)
        }
    }

    /** See [runWaitingScan]: [waiting] finished ones are shown now and handed to the scanner. */
    private suspend fun publishWaiting(reason: String, waiting: List<GalleryMedia>, startedAt: Long) {
        val nowWall = System.currentTimeMillis()
        val hiddenDirs = HashMap<String, Boolean>()
        val ready = ArrayList<GalleryMedia>()
        // Finished files in hidden folders: scanned too (clears the flag), not shown as rows.
        val hiddenReady = ArrayList<GalleryMedia>()
        var writing = 0
        for (row in waiting) {
            val path = row.absolutePath ?: continue
            val file = File(path)
            // Listed under a hidden (.nomedia) folder: never a gallery row, the scan says so.
            val inHidden = file.parentFile?.let { isInHiddenTree(it, hiddenDirs) } == true
            val tracked = downloadTracker.get(path)
            val decision = WaitingFileRule.decide(
                length = file.length(),
                ageMs = nowWall - file.lastModified(),
                complete = FileCompleteness.check(path),
                // Alexet's own copy/edit/export in progress counts as being written too.
                liveDownload = (tracked != null && tracked.source != DownloadSource.MEDIASTORE_PENDING) || isOwnWrite(path),
            )
            when (decision) {
                WaitingFileRule.Decision.READY -> if (inHidden) hiddenReady += row else ready += row.copy(sizeBytes = file.length())
                WaitingFileRule.Decision.WRITING -> writing++
                WaitingFileRule.Decision.SKIP -> Unit
            }
        }
        AppLog.event("Sync") {
            "waiting files ($reason): ${waiting.size} pending, ${ready.size} ready, $writing still writing, " +
                "${hiddenReady.size} in hidden folders, checked in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
        }
        // Shown by the hidden-folder read, which doesn't have them yet.
        onHiddenFilesChanged(hiddenReady.mapNotNull { it.absolutePath })
        val toScan = ready + hiddenReady
        if (toScan.isEmpty()) return
        val stamp = android.os.SystemClock.elapsedRealtime()
        toScan.forEach { row -> row.absolutePath?.let { publishedWaiting[DownloadMediaFilter.normalizePath(it)] = stamp } }
        val paths = toScan.mapNotNull { it.absolutePath }
        val scanStartedAt = android.os.SystemClock.elapsedRealtime()
        // The scan of all waiting rows and the one by id can meet on the same file: scanned once.
        scanOnce(paths, paths.map { DownloadsWatchLogic.mediaMimeOf(it) }) {
            AppLog.event("Sync") {
                "waiting files ($reason): ${paths.size} scanned in ${android.os.SystemClock.elapsedRealtime() - scanStartedAt} ms"
            }
            requestRefresh()
        }
        // Shown at once even when the scan was left to another path (the rows are new or not, by id).
        if (ready.isNotEmpty()) showWaitingRows(ready)
    }

    /** Puts finished waiting files in the lists now; their scan fills in size, date and dimensions later. */
    private suspend fun showWaitingRows(ready: List<GalleryMedia>) {
        // Cold start: merged into an empty list it would be the whole library for a moment.
        if (_currentMedia.value.isEmpty()) {
            withTimeoutOrNull(WAITING_SHOW_WAIT_MS) { _currentMedia.first { it.isNotEmpty() } } ?: return
        }
        withContext(queryDispatcher) {
            queryMutex.withLock {
                val epoch = mutationEpoch.get()
                val base = _currentMedia.value
                val known = base.mapTo(HashSet(base.size)) { it.id }
                val fresh = ready.filter { it.id !in known }
                if (fresh.isEmpty()) return@withLock
                // A patch made meanwhile wins; the rows come with the next sync then.
                val shown = applyQueryResult(finishQuery(MediaIndexLogic.mergeDelta(base, fresh, emptySet()), fullStore = false)) {
                    epoch == mutationEpoch.get()
                }
                if (!shown) return@withLock
                AppLog.event("Sync") { "waiting files: ${fresh.size} shown before their scan" }
            }
        }
    }

    /** [queryWaitingRows] of the rows [ids] only. */
    private fun queryWaitingRowsById(ids: Set<Long>): List<GalleryMedia> {
        val rows = ArrayList<GalleryMedia>()
        for (chunk in ids.chunked(500)) {
            val selection = "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",")})"
            rows += queryCollection(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), isVideo = false, selection = selection, includePending = true)
            rows += queryCollection(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), isVideo = true, selection = selection, includePending = true)
        }
        return MediaIndexLogic.dedupeById(rows).filter { row ->
            val name = row.absolutePath?.substringAfterLast('/') ?: return@filter false
            !name.startsWith(".pending-")
        }
    }

    /** IS_PENDING media rows another app created by writing a file path (not `.pending-` MediaStore inserts). */
    private fun queryWaitingRows(volumes: Set<String>): List<GalleryMedia> {
        val pending = "${MediaStore.MediaColumns.IS_PENDING}=1"
        val rows = ArrayList<GalleryMedia>()
        for (volume in volumes) {
            rows += queryCollection(MediaStore.Images.Media.getContentUri(volume), isVideo = false, selection = pending, includePending = true)
            rows += queryCollection(MediaStore.Video.Media.getContentUri(volume), isVideo = true, selection = pending, includePending = true)
            // No Downloads query: images and videos there are in the two collections above already.
        }
        // A `.pending-…` file is an insert its owner is still writing through MediaStore.
        return MediaIndexLogic.dedupeById(rows).filter { row ->
            val name = row.absolutePath?.substringAfterLast('/') ?: return@filter false
            !name.startsWith(".pending-")
        }
    }

    /** The folder or one above it is hidden (a dot name or a `.nomedia` file). */
    private fun isInHiddenTree(dir: File, cache: MutableMap<String, Boolean>): Boolean {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()?.absolutePath
        val key = dir.absolutePath
        cache[key]?.let { return it }
        val hidden = when {
            root != null && (key == root || !key.startsWith("$root/")) -> false
            dir.name.startsWith(".") || File(dir, ".nomedia").exists() -> true
            else -> dir.parentFile?.let { isInHiddenTree(it, cache) } == true
        }
        cache[key] = hidden
        return hidden
    }


    private fun matchesPendingDelete(item: GalleryMedia): Boolean {
        if (pendingDeletes.containsKey(item.id)) return true
        if (pendingDeletes.isEmpty()) return false
        return pendingDeleteIndex().matches(item.absolutePath?.lowercase(), item.displayName.lowercase(), item.bucketId)
    }

    private fun pendingDeleteIndex(): PendingDeleteIndex {
        val version = pendingDeletesVersion.get()
        pendingDeleteIndexCache?.takeIf { it.version == version }?.let { return it }
        return PendingDeleteIndex(version, pendingDeletes.values).also { pendingDeleteIndexCache = it }
    }

    private fun mediaStoreBucketIdOf(dir: File): Long =
        dir.absolutePath.replace('\\', '/').trimEnd('/').lowercase().hashCode().toLong()

    private fun GalleryMedia.relocatedBy(move: PendingMove, keepPathIfMissing: Boolean = false): GalleryMedia =
        copy(
            bucketId = move.destBucketId,
            bucketName = move.destBucketName,
            relativePath = move.destRelativePath,
            absolutePath = move.destAbsolutePath ?: if (keepPathIfMissing) absolutePath else null,
            displayName = move.displayName,
            rawBucketId = move.destRawBucketId,
        )

    /** Rows owned by an in-flight move/delete; raw MediaStore merges must not publish them. */
    private fun isMaskedByPendingOps(item: GalleryMedia, moveKeys: Set<String>): Boolean {
        if (matchesPendingDelete(item)) return true
        if (pendingMoves.isEmpty()) return false
        if (pendingMoves.containsKey(item.id)) return true
        return srcRowKeys(item).any { it in moveKeys }
    }

    /** Keys of every pending move's source orphan, for [isMaskedByPendingOps]. */
    private fun pendingMoveKeys(): Set<String> =
        pendingMoves.entries.flatMapTo(HashSet()) { (oldId, move) -> moveOrphanKeys(oldId, move) }

    private fun belongsToAlbum(item: GalleryMedia, albumId: Long, album: Album?): Boolean {
        if (item.bucketId == albumId) return true
        val legacyIds = album?.legacyBucketIds.orEmpty()
        if (legacyIds.isNotEmpty() && item.rawBucketId != 0L && item.rawBucketId in legacyIds) return true
        val relPath = album?.relativePath?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: return false
        return item.relativePath.trim().trim('/').equals(relPath, ignoreCase = true)
    }

    /**
     * @param allowMaskExpiry Only full MediaStore snapshots may expire pendingMoves.
     *   Per-folder reconciles must NOT drop masks — a list without F1 would look like
     *   "orphan gone" and resurrect files in F1 on the next full refresh (raport_błędu.md).
     */
    private fun reconcileStore(
        store: List<GalleryMedia>,
        allowMaskExpiry: Boolean = true,
    ): List<GalleryMedia> {
        if (pendingMoves.isEmpty() && pendingDeletes.isEmpty()) return store

        val now = android.os.SystemClock.elapsedRealtime()
        val storeIds = store.mapTo(HashSet(store.size)) { it.id }
        // Built once: asking "is the source orphan of this move still in the store?" with a scan
        // of the whole store per move hung large moves.
        val storeRowKeys by lazy(LazyThreadSafetyMode.NONE) { store.flatMapTo(HashSet(store.size * 3)) { srcRowKeys(it) } }
        fun orphanStillPresent(oldId: Long, move: PendingMove) = moveOrphanKeys(oldId, move).any { it in storeRowKeys }

        // Hard ceiling 5 min (was 30s) — MediaProvider can lag far longer.
        var expiredByCeiling = false
        pendingMoves.entries.removeIf { (id, move) ->
            val age = now - move.movedAt
            if (age > 300_000L) return@removeIf true.also { expiredByCeiling = true }
            if (!allowMaskExpiry || age < 5_000L) return@removeIf false
            // Drop mask only when the src orphan is gone from the FULL store.
            !orphanStillPresent(id, move)
        }
        // Do NOT drop the delete mask on the first MediaStore flicker after 5s —
        // that resurrected brown "ghost" thumbnails (URI still listed, file already in trash).
        val deletesExpired = pendingDeletes.entries.removeIf { (id, pd) ->
            val age = now - pd.markedAt
            if (age > 45_000L) return@removeIf true.also { expiredByCeiling = true }
            if (age < 20_000L || !allowMaskExpiry) return@removeIf false
            id !in storeIds
        }
        if (deletesExpired) pendingDeletesVersion.incrementAndGet()

        if (expiredByCeiling && !allowMaskExpiry) {
            // A delta list is built on the masked list: rows the mask hid (operation never
            // finished) can only come back from a full read.
            fullRefreshRequested.set(true)
            refresh.tryEmit(Unit)
        }

        val destFileKeys = store
            .filter { it.bucketId != AlbumIds.NONE }
            .map { "${it.bucketId}:${it.displayName.lowercase()}" }
            .toHashSet()

        val destPaths = store
            .mapNotNull { it.absolutePath?.lowercase() }
            .toHashSet()

        val srcOrphans = HashSet<String>()
        val srcOrphanPaths = HashSet<String>()
        for (move in pendingMoves.values) {
            srcOrphans += "${move.srcBucketId}:${move.srcDisplayName.lowercase()}"
            srcOrphans += "${move.srcBucketId}:${move.displayName.lowercase()}"
            move.srcAbsolutePath?.let { srcOrphanPaths += it }
        }

        val handledMoveOldIds = HashSet<Long>()
        val result = ArrayList<GalleryMedia>(store.size + pendingMoves.size)
        for (item in store) {
            if (matchesPendingDelete(item)) continue

            val move = pendingMoves[item.id]
            if (move != null) {
                handledMoveOldIds += item.id
                if (item.bucketId == move.destBucketId) {
                    // Same MediaStore id already reassigned to dest (update path) —
                    // only drop mask when src orphan is also gone (full store) or after grace.
                    if (allowMaskExpiry && !orphanStillPresent(item.id, move)) {
                        pendingMoves.remove(item.id)
                    }
                    result += if (move.destAbsolutePath != null) item.copy(absolutePath = move.destAbsolutePath) else item
                    continue
                }

                // Src-bucket orphan: never keep under source album.
                if (item.bucketId == move.srcBucketId) {
                    val destKey = "${move.destBucketId}:${move.displayName.lowercase()}"
                    if (destKey in destFileKeys) {
                        // Dest row present — hide orphan, keep mask until expiry logic says so.
                        continue
                    }
                    result += item.relocatedBy(move, keepPathIfMissing = true)
                    continue
                }

                result += item.relocatedBy(move)
                continue
            }

            val itemSrcKey = "${item.bucketId}:${item.displayName.lowercase()}"
            val itemPath = item.absolutePath?.lowercase()
            if (itemSrcKey in srcOrphans || (itemPath != null && itemPath in srcOrphanPaths)) {
                continue
            }

            result += item
        }

        val currentById by lazy(LazyThreadSafetyMode.NONE) {
            HashMap<Long, GalleryMedia>().also { map -> _currentMedia.value.forEach { map.putIfAbsent(it.id, it) } }
        }
        for ((oldId, move) in pendingMoves) {
            if (oldId in handledMoveOldIds) continue
            val destKey = "${move.destBucketId}:${move.displayName.lowercase()}"
            val destPathMatch = move.destAbsolutePath?.lowercase()?.let { it in destPaths } ?: false
            if (destKey !in destFileKeys && !destPathMatch) {
                val existing = currentById[oldId]
                if (existing != null) {
                    result += existing.relocatedBy(move)
                }
            } else if (allowMaskExpiry) {
                // Dest present is NOT enough to drop the mask while src orphan still lives.
                if (!orphanStillPresent(oldId, move) && now - move.movedAt > 15_000L) {
                    pendingMoves.remove(oldId)
                }
            }
        }
        return result.distinctBy { it.id }
    }


    private data class QueryTarget(
        val uri: Uri,
        val isVideo: Boolean,
        val selection: String? = null,
    )

    private fun queryStore(): List<GalleryMedia> {
        val mimeSel = MEDIA_MIME_SELECTION
        val targets = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.getExternalVolumeNames(appContext).forEach { volume ->
                    add(QueryTarget(MediaStore.Images.Media.getContentUri(volume), isVideo = false))
                    add(QueryTarget(MediaStore.Video.Media.getContentUri(volume), isVideo = true))
                    add(QueryTarget(MediaStore.Downloads.getContentUri(volume), isVideo = false, selection = mimeSel))
                }
            } else {
                add(QueryTarget(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false))
                add(QueryTarget(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true))
            }
        }
        return targets.flatMap { target -> queryCollection(target.uri, target.isVideo, target.selection, strict = true) }
            .distinctBy { it.absolutePath?.lowercase() ?: it.uri.toString() }
    }


    fun queryFolderFromMediaStore(
        albumId: Long,
        album: Album?,
    ): List<GalleryMedia> {
        val resolvedAlbum = album ?: _seedAlbums.value.firstOrNull { it.id == albumId }
        val relPath = resolvedAlbum?.relativePath?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
        val albumName = resolvedAlbum?.name?.takeIf { it.isNotBlank() }
        val isDownloads = DownloadsWatchLogic.isDownloadsFolderItem(relPath.orEmpty(), albumName.orEmpty())

        val mimeSel =
            "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%' OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'video/%' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.jpg' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.jpeg' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.png' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.webp' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.gif' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.mp4' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.mkv' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.webm'"
        val targets = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val volumes = MediaStore.getExternalVolumeNames(appContext)
                    .sortedByDescending { it.contains("primary", ignoreCase = true) }
                volumes.forEach { volume ->
                    add(QueryTarget(MediaStore.Images.Media.getContentUri(volume), isVideo = false))
                    add(QueryTarget(MediaStore.Video.Media.getContentUri(volume), isVideo = true))
                    if (isDownloads) {
                        add(QueryTarget(MediaStore.Downloads.getContentUri(volume), isVideo = false, selection = mimeSel))
                    }
                }
            } else {
                add(QueryTarget(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false))
                add(QueryTarget(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true))
            }
        }

        val legacyIds = resolvedAlbum?.legacyBucketIds.orEmpty().filter { it != 0L }.toMutableSet()
        if (legacyIds.isEmpty() && relPath != null) {
            legacyIds.addAll(computeMediaStoreBucketIds(relPath))
        }

        val (folderSelection, folderArgs) = when {
            relPath != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                if (legacyIds.isNotEmpty()) {
                    val inClause = legacyIds.joinToString(",")
                    "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.BUCKET_ID} IN ($inClause))" to arrayOf("$relPath/", relPath)
                } else {
                    "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)" to arrayOf("$relPath/", relPath)
                }
            }
            legacyIds.isNotEmpty() -> {
                val inClause = legacyIds.joinToString(",")
                "${MediaStore.MediaColumns.BUCKET_ID} IN ($inClause)" to null
            }
            albumName != null -> {
                "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?" to arrayOf(albumName)
            }
            albumId in Int.MIN_VALUE..Int.MAX_VALUE && albumId != 0L -> {
                "${MediaStore.MediaColumns.BUCKET_ID} = ?" to arrayOf(albumId.toString())
            }
            else -> {
                null to null
            }
        }

        val allLegacyIds = legacyIds + (resolvedAlbum?.legacyBucketIds.orEmpty())
        val items = targets.flatMap { target ->
            val finalSel = when {
                target.selection != null && folderSelection != null -> "(${target.selection}) AND ($folderSelection)"
                target.selection != null -> target.selection
                else -> folderSelection
            }
            queryCollection(target.uri, target.isVideo, finalSel, folderArgs)
        }
        .filter { item ->
            item.bucketId == albumId ||
                (allLegacyIds.isNotEmpty() && item.rawBucketId != 0L && item.rawBucketId in allLegacyIds) ||
                (relPath != null && item.relativePath.trim().trim('/').equals(relPath, ignoreCase = true))
        }
        .map { item ->
            if (item.bucketId != albumId) item.copy(bucketId = albumId) else item
        }
        .distinctBy { it.absolutePath?.lowercase() ?: it.uri.toString() }

        if (resolvedAlbum != null && items.isNotEmpty()) {
            val discoveredRaw = items.mapNotNull { it.rawBucketId.takeIf { raw -> raw != 0L } }.toSet()
            if (discoveredRaw.isNotEmpty() && !resolvedAlbum.legacyBucketIds.containsAll(discoveredRaw)) {
                _seedAlbums.update { list ->
                    list.map { if (it.id == albumId) it.copy(legacyBucketIds = it.legacyBucketIds + discoveredRaw) else it }
                }
            }
        }

        return items
    }

    private val quickScanAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /**
     * Quick scan of one folder, run when it has been open for a moment: finished files waiting in
     * MediaStore are published (one cheap query for the whole library), the changed rows are read
     * (delta), and the folder is listed on disk for files MediaStore doesn't list at all.
     */
    suspend fun quickScanFolder(albumId: Long) {
        if (albumId == AlbumIds.NONE || albumId == AlbumIds.TRASH || albumId == AlbumIds.FAVORITES) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = quickScanAt[albumId]
        if (last != null && now - last < QUICK_SCAN_FOLDER_THROTTLE_MS) return
        quickScanAt[albumId] = now
        // Android 11+: waiting rows and changes come with MediaStore's notifications, which are
        // handled as they come; this folder's own look is all that is left to do here.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            runWaitingScan("folder")
            requestRefresh()
        }
        val album = _seedAlbums.value.firstOrNull { it.id == albumId } ?: return
        // Hidden folders are not MediaStore's: read from disk again for files other apps put there.
        if (album.isHidden && !album.isSecure) withContext(Dispatchers.IO) { rereadHiddenFolder(album.relativePath) }
        val sent = scanFolderDisk(album)
        AppLog.event("Sync") {
            "quick scan of folder ${album.id}: $sent file(s) from disk sent to the scanner " +
                "in ${android.os.SystemClock.elapsedRealtime() - now} ms"
        }
    }

    /**
     * Half scan (refresh gesture in a folder): the quick scan, the folder read again from
     * MediaStore, plus a close look at [focus] (the part on screen, or the whole folder when it is
     * small): files whose size or date on disk no longer match their row, or that are gone, are
     * scanned again. A rescanned file gets a new date, so its thumbnail is made again too.
     * @return the number of files sent to the scanner.
     */
    suspend fun halfScanFolder(albumId: Long, focus: List<GalleryMedia>): Int = withContext(Dispatchers.IO) {
        if (albumId == AlbumIds.NONE || albumId == AlbumIds.TRASH || albumId == AlbumIds.FAVORITES) return@withContext 0
        val startedAt = android.os.SystemClock.elapsedRealtime()
        quickScanAt[albumId] = startedAt
        runWaitingScan("half")
        val album = _seedAlbums.value.firstOrNull { it.id == albumId }
        // A hidden folder is read from disk again first: the load below lists what it holds now.
        if (album != null && album.isHidden && !album.isSecure) rereadHiddenFolder(album.relativePath)
        loadFolderMedia(albumId, album, force = true)
        val fromDisk = if (album != null) scanFolderDisk(album, force = true) else 0
        val stale = ArrayList<String>()
        for (item in focus) {
            ensureActive()
            // MediaStore rows only: hidden-folder and private files are read from disk anyway.
            if (item.uri.scheme != ContentResolver.SCHEME_CONTENT || item.isEncrypted) continue
            val path = item.absolutePath ?: continue
            if (recentlyPublishedWaiting(path)) continue
            val file = File(path)
            val gone = !file.exists()
            val differs = !gone && (
                file.length() != item.sizeBytes || kotlin.math.abs(file.lastModified() - item.dateModifiedMs) > 2_000L
                )
            if (!gone && !differs) continue
            if (!gone && (downloadTracker.isActive(path) || isOwnWrite(path))) continue
            stale += path
        }
        if (stale.isNotEmpty()) {
            val stamp = android.os.SystemClock.elapsedRealtime()
            stale.forEach { publishedWaiting[DownloadMediaFilter.normalizePath(it)] = stamp }
            val remaining = java.util.concurrent.atomic.AtomicInteger(stale.size)
            // A path that is gone drops its row in the scan.
            MediaScannerConnection.scanFile(appContext, stale.toTypedArray(), null) { _, _ ->
                if (remaining.decrementAndGet() == 0) requestRefresh()
            }
        }
        AppLog.event("Sync") {
            "half scan of folder $albumId: ${focus.size} checked closely, ${stale.size} stale, " +
                "$fromDisk new from disk, in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
        }
        fromDisk + stale.size
    }

    private val fullScanRunning = AtomicBoolean(false)

    /**
     * Takes the full-scan slot ahead of [runFullScan] (then called with claimed = true): the
     * manual Re-scan clears the thumbnails and says "started" only when its scan really starts.
     */
    fun tryClaimFullScan(): Boolean = fullScanRunning.compareAndSet(false, true)
    private val _fullScan = MutableStateFlow<FullScanProgress?>(null)
    /** Progress of the running full scan; null when none runs. */
    val fullScan: StateFlow<FullScanProgress?> = _fullScan.asStateFlow()

    /**
     * Full scan ("Re-scan" in the settings, and the daily background job): MediaStore is read again
     * in full and the index rewritten, then every folder on the storage is walked (hidden ones are
     * left to the hidden-folder scan) and files MediaStore doesn't list, lists with another size or
     * date, or still lists after they are gone, are scanned in batches.
     * @param background Low thread priority and pauses (the daily job).
     * @return null when a full scan was already running.
     */
    suspend fun runFullScan(trigger: String, background: Boolean, claimed: Boolean = false): FullScanResult? = withContext(Dispatchers.IO) {
        if (!claimed && !fullScanRunning.compareAndSet(false, true)) return@withContext null
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            _fullScan.value = FullScanProgress(FullScanProgress.Phase.LIBRARY)
            runWaitingScan("full")
            syncFromMediaStore(full = true, background = background)
            val libraryMs = android.os.SystemClock.elapsedRealtime() - startedAt

            val known = HashMap<String, GalleryMedia>(_currentMedia.value.size * 2)
            for (item in _currentMedia.value) {
                if (item.uri.scheme != ContentResolver.SCHEME_CONTENT || item.isEncrypted) continue
                item.absolutePath?.lowercase()?.let { known[it] = item }
            }
            val toScan = LinkedHashSet<String>()
            val nowWall = System.currentTimeMillis()
            var folders = 0
            val stack = ArrayDeque<Pair<File, Int>>()
            storageRoots().forEach { stack.addLast(it to 0) }
            while (stack.isNotEmpty()) {
                ensureActive()
                val (dir, depth) = stack.removeLast()
                // Hidden (.nomedia) folders are not MediaStore's: the hidden-folder scan reads them.
                if (File(dir, ".nomedia").exists()) continue
                val children = lowPriority(background) { dir.listFiles() } ?: continue
                folders++
                if (folders % 50 == 0) {
                    _fullScan.value = FullScanProgress(FullScanProgress.Phase.FOLDERS, folders)
                    if (background) delay(LAZY_CHUNK_PAUSE_MS)
                }
                for (child in children) {
                    val name = child.name
                    if (name.startsWith(".")) continue
                    val mime = NomediaScanner.mimeOf(name)
                    if (mime == null) {
                        if (depth >= FULL_SCAN_MAX_DEPTH || !child.isDirectory) continue
                        if (depth == 0 && name == "Android") {
                            // Android/media holds messenger media; data and obb are app-private.
                            File(child, "media").takeIf { it.isDirectory }?.let { stack.addLast(it to depth + 2) }
                        } else {
                            stack.addLast(child to depth + 1)
                        }
                        continue
                    }
                    val path = child.absolutePath
                    val existing = known.remove(path.lowercase())
                    val length = child.length()
                    val changed = existing == null || length != existing.sizeBytes ||
                        kotlin.math.abs(child.lastModified() - existing.dateModifiedMs) > 2_000L
                    if (!changed || recentlyPublishedWaiting(path)) continue
                    val decision = WaitingFileRule.decide(
                        length = length,
                        ageMs = nowWall - child.lastModified(),
                        complete = if (existing == null) FileCompleteness.check(path) else null,
                        liveDownload = downloadTracker.isActive(path) || isOwnWrite(path),
                    )
                    if (decision == WaitingFileRule.Decision.READY) toScan += path
                }
            }
            // Rows left over whose file is gone: a scan of the path drops them.
            var gone = 0
            for ((_, item) in known) {
                val path = item.absolutePath ?: continue
                if (!File(path).exists()) {
                    toScan += path
                    gone++
                }
            }
            val diskMs = android.os.SystemClock.elapsedRealtime() - startedAt - libraryMs
            val paths = toScan.toList()
            var scanned = 0
            val stamp = android.os.SystemClock.elapsedRealtime()
            paths.forEach { publishedWaiting[DownloadMediaFilter.normalizePath(it)] = stamp }
            for (batch in paths.chunked(FULL_SCAN_BATCH)) {
                ensureActive()
                _fullScan.value = FullScanProgress(FullScanProgress.Phase.FILES, scanned, paths.size)
                scanAndWait(batch)
                scanned += batch.size
            }
            if (scanHidden.get()) rescanHiddenNow()
            requestRefresh()
            val result = FullScanResult(folders = folders, sent = paths.size, gone = gone)
            AppLog.event("Sync") {
                "full scan ($trigger): library $libraryMs ms, $folders folders walked in $diskMs ms, " +
                    "${paths.size} file(s) scanned ($gone gone) in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms total"
            }
            result
        } finally {
            _fullScan.value = null
            fullScanRunning.set(false)
        }
    }

    /** Hands [paths] to the scanner and waits until it is through them (or a minute plus a second per file). */
    private suspend fun scanAndWait(paths: List<String>) {
        if (paths.isEmpty()) return
        withTimeoutOrNull(60_000L + paths.size * 1_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val remaining = java.util.concurrent.atomic.AtomicInteger(paths.size)
                MediaScannerConnection.scanFile(appContext, paths.toTypedArray(), null) { _, _ ->
                    if (remaining.decrementAndGet() == 0 && cont.isActive) cont.resumeWith(Result.success(Unit))
                }
            }
        }
    }

    /** The primary storage and any SD card (the parts of the app's external dirs before /Android/). */
    private fun storageRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        @Suppress("DEPRECATION")
        Environment.getExternalStorageDirectory()?.let { roots += it }
        appContext.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            val path = dir.absolutePath
            val cut = path.indexOf("/Android/")
            if (cut > 0) roots += File(path.substring(0, cut))
        }
        return roots.filter { it.isDirectory }
    }

    /** The folder on disk, or null for private, virtual and hidden folders (not MediaStore's to list). */
    private fun diskDirOf(album: Album): File? {
        if (album.isSecure || album.isHidden) return null
        val rel = album.relativePath.trim()
        if (rel.isEmpty()) return null
        val dir = File(rel).takeIf { it.isAbsolute && it.isDirectory }
            ?: NomediaScanner.directoryForRelativePath(rel)
            ?: return null
        if (isInHiddenTree(dir, HashMap())) return null
        return dir
    }

    /**
     * Lists [album]'s folder on disk and hands files MediaStore doesn't list to the scanner in one
     * batch. Files still being written stay with the download tracker.
     * @return the number of files sent to the scanner.
     */
    /**
     * @param force List the folder even when its date says nothing came or went (the half scan).
     */
    private suspend fun scanFolderDisk(album: Album, force: Boolean = false): Int = withContext(Dispatchers.IO) {
        val dir = diskDirOf(album) ?: return@withContext 0
        val date = DirSnapshotCache.folderDate(dir) ?: return@withContext 0
        val nowWall = System.currentTimeMillis()
        if (!folderDiskState.needsListing(dir.absolutePath, date, nowWall, force)) {
            AppLog.event("Folder") { "folder ${album.id}: unchanged on disk since its last listing, not listed" }
            return@withContext 0
        }
        val listStartedAt = android.os.SystemClock.elapsedRealtime()
        val names = listNames(dir, date) ?: return@withContext 0
        AppLog.event("Folder") {
            "folder ${album.id} listed on disk: ${names.size} entries in ${android.os.SystemClock.elapsedRealtime() - listStartedAt} ms"
        }
        val known = HashSet<String>()
        for (item in _currentMedia.value) {
            if (item.bucketId == album.id) item.absolutePath?.lowercase()?.let { known += it }
        }
        _folderMedia.value[album.id]?.forEach { item -> item.absolutePath?.lowercase()?.let { known += it } }
        val paths = ArrayList<String>()
        val mimes = ArrayList<String>()
        // Files not finished yet (being written, empty): the folder is listed again next time,
        // as their date will not change it.
        var unsettled = 0
        for (name in names) {
            ensureActive()
            // Name checks first: a stat per file through the storage layer made a big folder
            // (Camera) take 1.5-2 s even when nothing was new.
            if (name.startsWith(".")) continue
            val mime = NomediaScanner.mimeOf(name) ?: continue
            val path = File(dir, name).absolutePath
            if (path.lowercase() in known || recentlyPublishedWaiting(path)) continue
            val entry = DirSnapshotCache.entryOf(dir, name) ?: continue
            if (!entry.isFile) continue
            val decision = WaitingFileRule.decide(
                length = entry.size,
                ageMs = nowWall - entry.modifiedMs,
                complete = FileCompleteness.check(path),
                liveDownload = downloadTracker.isActive(path) || isOwnWrite(path),
            )
            if (decision != WaitingFileRule.Decision.READY) {
                unsettled++
                continue
            }
            paths += path
            mimes += mime
        }
        if (unsettled == 0) folderDiskState.listed(dir.absolutePath, date, nowWall)
        if (paths.isEmpty()) return@withContext 0
        val stamp = android.os.SystemClock.elapsedRealtime()
        paths.forEach { publishedWaiting[DownloadMediaFilter.normalizePath(it)] = stamp }
        val remaining = java.util.concurrent.atomic.AtomicInteger(paths.size)
        MediaScannerConnection.scanFile(appContext, paths.toTypedArray(), mimes.toTypedArray()) { _, _ ->
            if (remaining.decrementAndGet() == 0) requestRefresh()
        }
        paths.size
    }

    suspend fun loadFolderMedia(albumId: Long, album: Album? = null, force: Boolean = false): List<GalleryMedia> {
        val requestedAt = android.os.SystemClock.elapsedRealtime()
        // [force] (the half scan) reads MediaStore again on purpose.
        if (!force) withContext(Dispatchers.IO) { loadFolderFromLibrary(albumId, album, requestedAt) }?.let { return it }
        return withContext(queryDispatcher) { loadFolderMediaQueued(albumId, album, force, requestedAt) }
    }

    /**
     * An ordinary folder's rows taken from the library list, which every sync keeps in line with
     * MediaStore (and which is reconciled and holds the hidden folders): no MediaStore query, and
     * no waiting behind a sync for the query lock (1.2-1.6 s seen). Null when the folder needs the
     * query: nothing read yet (a cold start), a private folder, or no row of it in the library.
     */
    private fun loadFolderFromLibrary(albumId: Long, album: Album?, requestedAt: Long): List<GalleryMedia>? {
        if (albumId == AlbumIds.NONE || albumId == AlbumIds.TRASH || albumId == AlbumIds.FAVORITES) return null
        val library = _currentMedia.value
        if (!indexReady || library.isEmpty()) return null
        val resolved = album ?: _seedAlbums.value.firstOrNull { it.id == albumId } ?: return null
        if (resolved.isSecure) return null
        val current = _folderMedia.value[albumId].orEmpty()
        if (isSecureFolderCache(current)) return null
        if (SecureFolderManager.getKnownSecurePaths().isNotEmpty()) {
            val dir = File(resolved.relativePath).takeIf { it.isAbsolute && it.isDirectory }
                ?: NomediaScanner.directoryForRelativePath(resolved.relativePath)
            if (dir != null && SecureFolderManager.isFolderSecure(dir)) return null
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val rows = rebuildFolderRows(albumId, current, resolved, folderRowIndexOf(library))
        if (rows.isEmpty()) return null
        _folderMedia.update { it + (albumId to rows) }
        folderLoadTimestamps[albumId] = android.os.SystemClock.elapsedRealtime()
        // The folder's watcher and size snapshot read the disk: not on the way to the folder's list.
        ioScope.launch { watchOpenAlbum(resolved, rows.firstOrNull { !it.absolutePath.isNullOrBlank() }) }
        AppLog.event("Folder") {
            "folder $albumId load from the library: ${rows.size} file(s) in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms " +
                "(${startedAt - requestedAt} ms to start)"
        }
        return rows
    }

    /** The library list [rowIndex] was built from (by identity). */
    @Volatile private var rowIndexOf: List<GalleryMedia>? = null
    @Volatile private var rowIndex: FolderRowIndex? = null

    /** [FolderRowIndex] of [library], built once per list (each publish and each folder opened used to build one). */
    private fun folderRowIndexOf(library: List<GalleryMedia>): FolderRowIndex {
        rowIndex?.takeIf { rowIndexOf === library }?.let { return it }
        return FolderRowIndex(library).also {
            rowIndex = it
            rowIndexOf = library
        }
    }

    private suspend fun loadFolderMediaQueued(albumId: Long, album: Album?, force: Boolean, requestedAt: Long): List<GalleryMedia> {
        if (albumId == AlbumIds.NONE || albumId == AlbumIds.TRASH || albumId == AlbumIds.FAVORITES) {
            return emptyList()
        }
        val cached = _folderMedia.value[albumId]
        val lastLoaded = folderLoadTimestamps.getOrDefault(albumId, 0L)
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && cached != null && cached.isNotEmpty() && lastLoaded > lastMediaStoreChangeAt && (now - lastLoaded) < 4_000L) {
            AppLog.event("Folder") { "folder $albumId load: kept list of ${cached.size} (loaded ${now - lastLoaded} ms ago)" }
            return cached
        }

        return queryMutex.withLock {
            val lockedAt = android.os.SystemClock.elapsedRealtime()
            val resolvedAlbum = album
                ?: _seedAlbums.value.firstOrNull { it.id == albumId }
                ?: SecureFolderManager.unlockedFolderPaths.value.firstNotNullOfOrNull { path ->
                    val dir = File(path)
                    val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
                    if (NomediaScanner.syntheticBucketId(canonical) == albumId) {
                        val encFiles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(SecureFolderManager.ENC_EXTENSION) }.orEmpty()
                        Album(
                            id = albumId,
                            name = dir.name,
                            cover = null,
                            itemCount = encFiles.size,
                            relativePath = dir.absolutePath,
                            isHidden = false,
                            isSecure = true,
                            latestAddedMs = encFiles.maxOfOrNull { it.lastModified() } ?: dir.lastModified(),
                        )
                    } else null
                }
            val dir = resolvedAlbum?.relativePath?.let { File(it) }?.takeIf { it.isDirectory }
                ?: resolvedAlbum?.relativePath?.let { NomediaScanner.directoryForRelativePath(it) }
            val isFolderSecure = resolvedAlbum?.isSecure == true || (dir != null && SecureFolderManager.isFolderSecure(dir))
            if (isFolderSecure) {
                val secureDir = dir
                    ?: SecureFolderManager.unlockedFolderPaths.value.firstNotNullOfOrNull { path ->
                        val candidate = File(path)
                        val canonical = runCatching { candidate.canonicalPath }.getOrDefault(candidate.absolutePath)
                        candidate.takeIf { NomediaScanner.syntheticBucketId(canonical) == albumId && it.isDirectory }
                    }
                if (secureDir == null) {
                    // Do not clobber a good .enc cache with an empty list when path resolution fails.
                    return@withLock _folderMedia.value[albumId].orEmpty()
                }
                val encFiles = secureDir.listFiles()?.filter { it.isFile && it.name.endsWith(SecureFolderManager.ENC_EXTENSION) }.orEmpty()
                val items = encFiles.map { file ->
                    val origName = file.name.removeSuffix(SecureFolderManager.ENC_EXTENSION)
                    val mime = NomediaScanner.mimeOf(File(origName)) ?: "image/jpeg"
                    val modified = file.lastModified()
                    GalleryMedia(
                        id = NomediaScanner.syntheticId(file.absolutePath),
                        uri = Uri.fromFile(file),
                        displayName = origName,
                        mimeType = mime,
                        dateTakenMs = 0L,
                        dateModifiedMs = modified,
                        dateAddedMs = modified,
                        sizeBytes = file.length(),
                        width = 0,
                        height = 0,
                        durationMs = 0L,
                        bucketId = albumId,
                        bucketName = resolvedAlbum?.name ?: secureDir.name,
                        relativePath = secureDir.absolutePath,
                        isHidden = true,
                        absolutePath = file.absolutePath,
                    )
                }.sortedByDescending { it.dateModifiedMs }

                _folderMedia.update { it + (albumId to items) }
                folderLoadTimestamps[albumId] = android.os.SystemClock.elapsedRealtime()
                return@withLock items
            }
            val epoch = mutationEpoch.get()
            val queryStartedAt = android.os.SystemClock.elapsedRealtime()
            val folderItems = queryFolderFromMediaStore(albumId, resolvedAlbum)
            val queriedAt = android.os.SystemClock.elapsedRealtime()
            fun build(): List<GalleryMedia> {
                val reconciled = reconcileStore(folderItems, allowMaskExpiry = false)
                val merged = if (scanHidden.get()) {
                    val nomedia = nomediaForMerge()
                    if (nomedia != null) mergeNomedia(reconciled, nomedia) else reconciled
                } else {
                    reconciled
                }
                // reconcileStore also emits in-flight moves relocated to their destination — keep only this album's rows.
                val finalItems = merged
                    .filter { belongsToAlbum(it, albumId, resolvedAlbum) }
                    .map { item -> if (item.bucketId != albumId) item.copy(bucketId = albumId) else item }
                return finalItems.filter { !matchesPendingDelete(it) }.distinctBy { it.id }
            }
            var finalDistinct = build()
            // A move or delete into / out of this folder happened while MediaStore was read: its
            // masks are applied again, or the moved file vanished from the folder until the next load.
            if (epoch != mutationEpoch.get()) finalDistinct = build()
            _folderMedia.update { it + (albumId to finalDistinct) }
            val builtAt = android.os.SystemClock.elapsedRealtime()
            folderLoadTimestamps[albumId] = builtAt
            // Outside the query lock: listing a big folder there held every sync up for seconds.
            ioScope.launch { watchOpenAlbum(resolvedAlbum, finalDistinct.firstOrNull { !it.absolutePath.isNullOrBlank() }) }
            AppLog.event("Folder") {
                "folder $albumId load: ${finalDistinct.size} file(s); waited ${lockedAt - requestedAt} ms, " +
                    "album ${queryStartedAt - lockedAt} ms, query ${queriedAt - queryStartedAt} ms (${folderItems.size} rows), " +
                    "build ${builtAt - queriedAt} ms"
            }
            finalDistinct
        }
    }

    /** @param listAll List every folder again (the refresh gesture), not only those whose date changed. */
    private fun quickNomediaFromIndex(listAll: Boolean = false): NomediaScanResult? {
        val paths = settings.settings.value.nomediaFolderPaths
        if (paths.isEmpty()) return null
        return nomediaScanner.scanKnown(paths, force = listAll)
    }

    /** Hidden-folder settings changed from outside (config import): walk the folders now (or after [delayMs]). */
    fun rescanHiddenNow(delayMs: Long = 0L) {
        if (!scanHidden.get()) return
        nomediaFullScanAt = 0L
        hiddenScanJob?.cancel()
        scheduleDeferredNomediaScan(delayMs = delayMs)
    }

    /** The check of [hiddenScanJob] and the start of a new one are one step (two heavy walks otherwise). */
    private val hiddenScanLock = Any()

    fun scheduleDeferredNomediaScan(delayMs: Long = 6_000L): Unit = synchronized(hiddenScanLock) {
        if (!scanHidden.get()) return
        if (hiddenScanJob?.isActive == true) return
        val age = System.currentTimeMillis() - nomediaFullScanAt
        if (nomediaFullScanAt > 0L && age < FULL_NOMEDIA_SCAN_MS) return
        val epoch = nomediaScanEpoch.incrementAndGet()
        hiddenScanJob = ioScope.launch {
            delay(delayMs)
            if (epoch != nomediaScanEpoch.get() || !scanHidden.get()) return@launch
            val full = withContext(choresDispatcher) { walkHiddenFolders() }
            if (epoch != nomediaScanEpoch.get() || !scanHidden.get()) return@launch
            storeWalk(full)
        }
    }

    /** The walk of the whole storage for hidden folders and their files; [listAll] lists unchanged folders too. */
    private fun walkHiddenFolders(listAll: Boolean = false): NomediaScanResult {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        return nomediaScanner.scan(force = listAll).also { full ->
            AppLog.event("Hidden") {
                "hidden-folder walk: ${full.hiddenRelativePaths.size} folder(s), ${full.media.size} file(s) " +
                    "in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
            }
        }
    }

    /** Keeps a walk's result; the lists change only when the hidden folders did. */
    private fun storeWalk(full: NomediaScanResult) {
        nomediaVersion.incrementAndGet()
        nomediaFullScanAt = System.currentTimeMillis()
        settings.setNomediaFolderPaths(full.hiddenRelativePaths)
        takeInHiddenRead(full)
    }

    /** Puts a hidden-folder scan into the library list, the folder list and the open folders. */
    private fun publishNomedia(full: NomediaScanResult) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        mergeNomediaIntoLists(full)
        AppLog.event("Hidden") {
            "hidden folders put into the lists: ${full.media.size} file(s) in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
        }
    }

    private fun mergeNomediaIntoLists(full: NomediaScanResult) {
        val merged = _currentMedia.updateAndGet { current -> mergeNomedia(withoutVanishedHidden(current, full), full) }
        synchronized(publishLock) {
            // Only while no other list was published meanwhile.
            if (_currentMedia.value === merged) {
                markedList = merged
                markedWith = full
            }
        }
        val albums = albumsFrom(merged)
        _seedAlbums.update { current ->
            if (albums.isNotEmpty()) albums else current
        }
        _folderMedia.update { currentMap ->
            if (currentMap.isEmpty()) currentMap
            else currentMap.mapValues { (albumId, existing) ->
                if (isSecureFolderCache(existing)) {
                    return@mapValues existing
                }
                val album = albums.firstOrNull { it.id == albumId }
                if (album != null) {
                    merged.filter {
                        it.bucketId == albumId ||
                            (album.legacyBucketIds.isNotEmpty() && it.rawBucketId != 0L && it.rawBucketId in album.legacyBucketIds) ||
                            (album.relativePath.isNotBlank() && it.relativePath.trim().trim('/').equals(album.relativePath.trim().trim('/'), ignoreCase = true))
                    }.map { if (it.bucketId != albumId) it.copy(bucketId = albumId) else it }
                        .distinctBy { it.id }
                } else existing
            }
        }
    }

    /**
     * @param minAgeMs Without [force], no walk when the last one is younger than this.
     * @param listAll List every folder of the storage again (the refresh gesture).
     */
    private fun ensureFullNomediaScan(
        force: Boolean = false,
        minAgeMs: Long = FULL_NOMEDIA_SCAN_MS,
        listAll: Boolean = false,
    ): Unit = synchronized(hiddenScanLock) {
        if (!scanHidden.get()) return
        if (hiddenScanJob?.isActive == true && !force) return
        val age = System.currentTimeMillis() - nomediaFullScanAt
        if (!force && nomediaFullScanAt > 0L && age < minAgeMs) return
        val epoch = nomediaScanEpoch.incrementAndGet()
        hiddenScanJob = ioScope.launch {
            val full = walkHiddenFolders(listAll)
            if (epoch != nomediaScanEpoch.get()) return@launch
            storeWalk(full)
        }
    }

    /**
     * Rows of hidden (`file://`) files that a complete hidden-folder scan no longer finds: deleted,
     * moved or renamed outside Alexet. Also rows the scan reads with another size or date (written
     * again, a download that finished): the scan's row takes their place. Private-folder rows are
     * left alone.
     */
    private fun withoutVanishedHidden(list: List<GalleryMedia>, full: NomediaScanResult): List<GalleryMedia> {
        val found = HashMap<String, GalleryMedia>(full.media.size * 2)
        full.media.forEach { item -> item.absolutePath?.lowercase()?.let { found[it] = item } }
        return list.filter { item ->
            val path = item.absolutePath
            if (item.uri.scheme != "file" || path == null || path.endsWith(SecureFolderManager.ENC_EXTENSION)) {
                return@filter true
            }
            val fresh = found[path.lowercase()]
            if (fresh != null) {
                fresh.sizeBytes == item.sizeBytes && fresh.dateModifiedMs == item.dateModifiedMs
            } else {
                File(path).exists()
            }
        }
    }

    /** See [HiddenFolderMarker.merge]. */
    private fun mergeNomedia(store: List<GalleryMedia>, nomedia: NomediaScanResult): List<GalleryMedia> =
        HiddenFolderMarker.merge(store, nomedia)

    // Last in the class: its coroutines start at once on other threads and use fields declared
    // above. Placed before them, a coroutine could reach a field not yet set (the NewPipe folder
    // watcher died on a null lock at a cold start).
    init {
        downloadTracker.onCompleted = { _, display -> onDownloadReady(display) }
        ioScope.launch {
            var seen: String? = null
            settings.settings.collect { value ->
                val path = value.newpipeFolderRelativePath
                if (path == seen) return@collect
                seen = path
                applyNewpipeFolder(path)
            }
        }
        ioScope.launch {
            _currentMedia.debounce(JOURNAL_DEBOUNCE_MS).collect {
                // One database error (full storage, locked file) must not end journaling for good.
                try {
                    journal(it)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    AppLog.e("GalleryRepository", "Index journal failed", e)
                }
            }
        }
        // Cache hydration runs off the main thread: the repository is built inside the first
        // composition, so reading the index here would freeze the system splash and skip the loader.
        ioScope.launch {
            // Before anything cached is shown: an index from another MediaStore database maps ids
            // to the wrong files (wrong thumbnails, files in the wrong folders).
            try {
                discardIndexIfStoreReplaced()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e("GalleryRepository", "MediaStore identity check failed", e)
            }
            runCatching {
                val cachedAlbums = AlbumCache.load(appContext)
                if (!cachedAlbums.isNullOrEmpty() && _seedAlbums.value.isEmpty()) {
                    _seedAlbums.value = cachedAlbums
                }
            }
            // Legacy one-shot snapshot; superseded by the incremental index.
            runCatching { File(appContext.filesDir, "media_snapshot.json").delete() }
            if (_currentMedia.value.isEmpty()) {
                val loadStarted = android.os.SystemClock.elapsedRealtime()
                val indexed = mediaIndex.loadAll()
                AppLog.event("Startup") { "index loadAll: ${indexed.size} rows in ${android.os.SystemClock.elapsedRealtime() - loadStarted} ms" }
                indexMutex.withLock {
                    persisted = HashMap<Long, GalleryMedia>(indexed.size * 2).also { map ->
                        indexed.forEach { map[it.id] = it }
                    }
                    indexReady = true
                }
                if (indexed.isNotEmpty() && _currentMedia.value.isEmpty()) {
                    // Only while still empty: a download merged in meanwhile is kept.
                    _currentMedia.update { if (it.isEmpty()) indexed else it }
                    if (_seedAlbums.value.isEmpty()) {
                        _seedAlbums.value = albumsFrom(indexed)
                    }
                }
            }

            _isScanning.value = true
            val syncStarted = android.os.SystemClock.elapsedRealtime()
            try {
                syncFromMediaStore(full = fullRefreshRequested.getAndSet(false))
                lastFullCheckAt = android.os.SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                dev.ander.alexet.data.AppLog.e("GalleryRepository", "Error during initial media scan", e)
            } finally {
                _isScanning.value = false
                AppLog.event("Startup") { "initial sync took ${android.os.SystemClock.elapsedRealtime() - syncStarted} ms" }
            }
            // Before the download tracker looks at pending rows: finished ones are published here.
            runWaitingScan("start")
            syncPendingDownloads()
            seedRecentInProgressWrites()

            if (scanHidden.get()) {
                // Into the lists at once: the list from the index is the earlier run's, and files
                // put into hidden folders since then would wait for the walk (or never show).
                if (nomediaCache == null) {
                    quickNomediaFromIndex()?.let { takeInHiddenRead(it, onlyIfNothingCached = true) }
                }
                scheduleDeferredNomediaScan()
            }

            merge(
                callbackFlow {
                    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            onChange(selfChange, null as Uri?)
                        }

                        // Android 8-10: no row ids or kinds of change, so such a change is checked in full.
                        override fun onChange(selfChange: Boolean, uri: Uri?) {
                            onMediaStoreChange(DownloadsWatchLogic.isDownloadsUri(uri), null, 0)
                        }

                        override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
                            if (uri == null) onMediaStoreChange(false, null, 0) else onChange(selfChange, listOf(uri), flags)
                        }

                        override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                            val texts = uris.map { it.toString() }
                            AppLog.event("Notify") { "flags=$flags " + MediaChange.summary(texts.map { MediaChange.parse(it, flags) }) }
                            onMediaStoreChange(uris.any { DownloadsWatchLogic.isDownloadsUri(it) }, texts, flags)
                        }

                        /** @param uris What the notification named (read when a sync takes them), or null: nothing exact. */
                        private fun onMediaStoreChange(downloads: Boolean, uris: List<String>?, flags: Int) {
                            lastMediaStoreChangeAt = android.os.SystemClock.elapsedRealtime()
                            // Android 11+: the delta reads changed Downloads rows itself; this
                            // merge read the whole collection again on every download's change.
                            if (downloads && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                                scheduleDownloadsMerge()
                            }
                            if (!watching) {
                                mediaDirtyWhileBackgrounded = true
                                return
                            }
                            if (uris == null) mediaChanges.markUnknown() else mediaChanges.add(uris, flags)
                            trySend(Unit)
                        }
                    }
                    val urisToObserve = buildList {
                        add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                            add(MediaStore.Files.getContentUri("external"))
                        }
                    }
                    urisToObserve.forEach { uri ->
                        runCatching {
                            resolver.registerContentObserver(uri, true, observer)
                        }
                    }
                    // Changes made between the start of the first sync and this moment had no
                    // observer, and refresh requests sent before this flow ran were dropped: one
                    // (cheap, delta) sync now catches up with both, checking every id (no
                    // notification named those changes).
                    mediaChanges.markUnknown()
                    trySend(Unit)
                    awaitClose {
                        runCatching {
                            resolver.unregisterContentObserver(observer)
                        }
                    }
                },
                refresh,
            ).onEach { firstChangeAt.compareAndSet(0L, android.os.SystemClock.elapsedRealtime()) }
                // Changes during a sync become one follow-up sync. A debounce waited for 600 ms of
                // quiet, which a camera saving a photo (and our own scans) kept putting off.
                .conflate()
                .collect {
                delay(CONTENT_SETTLE_MS)
                val since = firstChangeAt.getAndSet(0L)
                val changes = mediaChanges.drain()
                val explicit = explicitRefresh.getAndSet(false)
                val full = fullRefreshRequested.getAndSet(false)
                // Files of hidden folders are only in the file table: Alexet reads them itself.
                noteFileOnlyChanges(changes.fileOnlyIds)
                if (changes.unknown || changes.fileTableChanged) _fileTableChanges.tryEmit(Unit)
                // Only rows MediaStore does not list changed (a document, a file in a hidden
                // folder): nothing to sync. Each such change cost a sync of 100-180 ms before.
                if (!full && !explicit && !changes.unknown && changes.listedIds.isEmpty()) {
                    AppLog.event("Sync") { "no sync: ${changes.changes.size} change(s), none of a listed photo or video" }
                    return@collect
                }
                // Exact changes: the deleted rows are named, so not every id is read. Everything
                // is checked when a change named nothing exact, and now and then anyway.
                val exact = !full && !changes.unknown && !fullCheckDue()
                // Only a sync that may change something shows as scanning (each one rebuilt the screen state twice).
                val shows = full || !exact || changes.listedIds.isNotEmpty()
                try {
                    if (since > 0L) {
                        AppLog.event("Sync") { "sync starts ${android.os.SystemClock.elapsedRealtime() - since} ms after the first change" }
                    }
                    if (shows) _isScanning.value = true
                    val epoch = mutationEpoch.get()
                    var read: Set<Long> = emptySet()
                    val published = syncFromMediaStore(
                        full = full,
                        publish = { epoch == mutationEpoch.get() },
                        deletedById = if (exact) changes.deletedListedIds else null,
                        onUpserts = { read = it },
                    )
                    if (exact) {
                        exactSyncs.incrementAndGet()
                    } else {
                        exactSyncs.set(0)
                        lastFullCheckAt = android.os.SystemClock.elapsedRealtime()
                    }
                    if (published) {
                        // Downloads in progress are pending rows: they can only have come with a new or changed row.
                        if (!exact || changes.addedListedIds.isNotEmpty()) syncPendingDownloads()
                        // Rows named by a notification but not read: still pending (a file another
                        // app wrote by path, not scanned yet). Checked now, by id.
                        val waiting = changes.addedListedIds - read
                        if (waiting.isNotEmpty()) publishWaitingIds(waiting)
                    } else {
                        // A move/delete patched the UI mid-query; this snapshot predates its masks.
                        if (full) fullRefreshRequested.set(true)
                        // Its changes (deletions named by id) were not applied: every id next time.
                        mediaChanges.markUnknown()
                        requestRefresh()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // What the notifications said was not applied: everything is checked next time.
                    mediaChanges.markUnknown()
                    dev.ander.alexet.data.AppLog.e("GalleryRepository", "Error during media refresh", e)
                } finally {
                    if (shows) _isScanning.value = false
                }
            }
        }
    }

    companion object {
        /** See [takeRecoverableSender]. */
        private const val RECOVERABLE_TTL_MS = 5_000L
        /** Other files are listed again this long after the last operation or disk change. */
        private const val OTHER_FILES_REFRESH_MS = 250L
        /** Recently opened folders whose other files stay listed when they are not on screen. */
        private const val OTHER_FILES_KEPT_MAX = 32
        private const val OTHER_FILES_SAVE_MS = 1_000L
        /** A batch of moved files not yet a full row is shown after this long (see moveToAlbum). */
        private const val BATCH_MAX_WAIT_MS = 1_000L
        private const val BATCH_TICK_MS = 100L
        /** Seconds between download polls while nothing is downloading. */
        private const val IDLE_POLL_SECONDS = 3
        /** While downloading: folders and DownloadManager are polled on every 4th tick (1 s). */
        private const val BUSY_TICKS_PER_POLL = 4
        /** Copy buffer (the same as the trash): the default 8 KB took many more reads for big videos. */
        private const val COPY_BUFFER_BYTES = 128 * 1024
        /** While idle, an unchanged folder is still listed in full this often. */
        private const val IDLE_FULL_POLL_MS = 30_000L
        private const val CONTENT_DEBOUNCE_MS = 600L
        /** A burst of change signals (one photo saved) becomes one sync. */
        private const val CONTENT_SETTLE_MS = 150L
        private const val WAITING_SCAN_THROTTLE_MS = 2_000L
        private const val QUICK_SCAN_FOLDER_THROTTLE_MS = 10_000L
        /** Syncs on exact changes between two that read every id (see fullCheckDue). */
        private const val EXACT_SYNCS_PER_FULL_CHECK = 20
        private const val FULL_CHECK_MS = 10 * 60_000L
        private const val FULL_SCAN_MAX_DEPTH = 16
        private const val FULL_SCAN_BATCH = 200
        private const val WAITING_PUBLISHED_KEEP_MS = 2 * 60_000L
        private const val WAITING_SHOW_WAIT_MS = 15_000L
        /** Journal batches optimistic UI patches before diffing them into the index. */
        private const val JOURNAL_DEBOUNCE_MS = 400L
        private const val LAZY_CHUNK_PAUSE_MS = 150L
        /** Downloads-collection filter: media by MIME, plus common media extensions with an unknown MIME. */
        private val MEDIA_MIME_SELECTION =
            "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%' OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'video/%' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.jpg' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.jpeg' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.png' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.webp' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.gif' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.mp4' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.mkv' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.webm'"
        private const val FULL_NOMEDIA_SCAN_MS = 30 * 60 * 1000L
        /** The refresh gesture and coming back to the app read the hidden folders again at most this often. */
        private const val HIDDEN_REREAD_MS = 60_000L
        /** Files found in a hidden folder not known yet: the storage is walked this long after the last one. */
        private const val HIDDEN_WALK_SETTLE_MS = 2_000L
        private const val MAX_WATCH_DIRS = 32
        /** Folders whose names are kept for a second look (see listNames). */
        private const val RECENT_LISTINGS = 4
        /** How long after an in-app write its filesystem events are still ignored by the download tracker. */
        private const val OWN_WRITE_GRACE_MS = 10_000L
        /** A file handed to the scanner is not handed to it again for this long (see scanOnce). */
        private const val SCAN_ONCE_MS = 3_000L

        fun computeMediaStoreBucketIds(relativePath: String): Set<Long> {
            val rel = relativePath.trim().trim('/')
            if (rel.isEmpty()) return emptySet()
            val candidates = LinkedHashSet<Long>()
            val candidateRoots = listOf(
                "/storage/emulated/0",
                "/sdcard",
                Environment.getExternalStorageDirectory()?.absolutePath.orEmpty(),
            ).filter { it.isNotBlank() }.distinct()

            for (root in candidateRoots) {
                val full = "$root/$rel".replace('\\', '/')
                candidates += full.lowercase().hashCode().toLong()
            }
            candidates += rel.lowercase().hashCode().toLong()
            return candidates
        }
    }

    /**
     * @param strict Throw when the query fails (or the provider returns no cursor) instead of
     *   returning what was read: a sync must not record "no changes" for a collection it could
     *   not read, or those rows stay missing until the next full reconcile.
     */
    private fun queryCollection(
        collection: Uri,
        isVideo: Boolean,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        strict: Boolean = false,
        /** Also list IS_PENDING rows (Android 11+); a plain query leaves them out. */
        includePending: Boolean = false,
    ): List<GalleryMedia> {
        val isDownloadsCollection = collection.toString().contains("download", ignoreCase = true)
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            @Suppress("DEPRECATION")
            add(MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.DATE_TAKEN)
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            if (isVideo) {
                add(MediaStore.Video.Media.DURATION)
            }
        }.toTypedArray()

        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val items = ArrayList<GalleryMedia>()
        val outcome = runCatching {
            val cursor = if (includePending && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolver.query(collection, projection, pendingQueryArgs(selection, selectionArgs, sort), null)
            } else {
                resolver.query(collection, projection, selection, selectionArgs, sort)
            }
            if (cursor == null && strict) throw java.io.IOException("No cursor for $collection")
            cursor?.use { rows ->
                val idIdx = rows.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = rows.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIdx = rows.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val addedIdx = rows.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedIdx = rows.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeIdx = rows.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val widthIdx = rows.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightIdx = rows.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val bucketIdIdx = rows.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                val bucketNameIdx = rows.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val dataIdx = rows.getColumnIndex(MediaStore.MediaColumns.DATA)
                val takenIdx = rows.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                val pathIdx = rows.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val durationIdx = if (isVideo) rows.getColumnIndex(MediaStore.Video.Media.DURATION) else -1

                if (idIdx < 0 || nameIdx < 0 || mimeIdx < 0) return@use

                while (rows.moveToNext()) {
                    val id = rows.getLong(idIdx)
                    val name = rows.getString(nameIdx).orEmpty()
                    val addedSec = if (addedIdx >= 0) rows.getLong(addedIdx) else 0L
                    val taken = if (takenIdx >= 0) rows.getLong(takenIdx) else 0L
                    val modifiedSec = if (modifiedIdx >= 0) rows.getLong(modifiedIdx) else addedSec
                    val rawMime = rows.getString(mimeIdx)
                    // A photo or video the system gave a general type ("application/binary"...) gets its own.
                    val extMime = NomediaScanner.mediaMimeOf(rawMime, name)
                    val mime = extMime ?: if (isVideo) "video/*" else "image/*"
                    val itemIsVideo = isVideo || mime.startsWith("video/", ignoreCase = true) || NomediaScanner.mimeOf(File(name))?.startsWith("video/") == true
                    val isMedia = itemIsVideo || mime.startsWith("image/", ignoreCase = true) || NomediaScanner.mimeOf(File(name))?.startsWith("image/") == true
                    if (!isMedia) continue

                    val rawBucketId = if (bucketIdIdx >= 0) rows.getLong(bucketIdIdx) else 0L
                    val path = if (pathIdx >= 0) rows.getString(pathIdx).orEmpty() else ""
                    val bucketName = if (bucketNameIdx >= 0) rows.getString(bucketNameIdx).orEmpty() else ""
                    val dataPath = if (dataIdx >= 0) rows.getString(dataIdx) else null

                    val resolvedPath = if (path.isNotBlank()) {
                        path
                    } else if (!dataPath.isNullOrBlank()) {
                        val rootPath = Environment.getExternalStorageDirectory()?.absolutePath.orEmpty()
                        if (rootPath.isNotBlank() && dataPath.startsWith(rootPath)) {
                            val rel = dataPath.removePrefix(rootPath).trimStart('/')
                            val parent = rel.substringBeforeLast('/', "")
                            if (parent.isNotEmpty()) "$parent/" else ""
                        } else {
                            val parent = File(dataPath).parentFile?.name
                            if (parent != null) "$parent/" else ""
                        }
                    } else if (isDownloadsCollection) {
                        "Download/"
                    } else {
                        ""
                    }

                    val folderKey = if (resolvedPath.isNotBlank()) {
                        resolvedPath.trim().trim('/')
                    } else if (bucketName.isNotBlank()) {
                        bucketName.trim()
                    } else if (isDownloadsCollection) {
                        "Download"
                    } else {
                        "Unknown"
                    }

                    val bucketId = NomediaScanner.syntheticBucketId(folderKey)

                    val resolvedBucketName = bucketName.ifBlank {
                        if (resolvedPath.isNotBlank()) {
                            resolvedPath.trim().trim('/').substringAfterLast('/')
                        } else if (isDownloadsCollection) {
                            "Download"
                        } else {
                            "Unknown"
                        }
                    }

                    val resolvedAbsolutePath = if (!dataPath.isNullOrBlank()) {
                        dataPath
                    } else {
                        val root = Environment.getExternalStorageDirectory()
                        if (root != null && resolvedPath.isNotBlank() && name.isNotBlank()) {
                            File(root, "${resolvedPath.trim('/')}/$name").absolutePath
                        } else if (root != null && isDownloadsCollection && name.isNotBlank()) {
                            File(root, "Download/$name").absolutePath
                        } else null
                    }

                    val durationMs = if (durationIdx >= 0 && itemIsVideo) rows.getLong(durationIdx) else 0L

                    items += GalleryMedia(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        displayName = name,
                        mimeType = mime,
                        dateTakenMs = if (taken > 0L) taken else 0L,
                        dateModifiedMs = modifiedSec * 1000L,
                        dateAddedMs = if (addedSec > 0L) addedSec * 1000L else modifiedSec * 1000L,
                        sizeBytes = if (sizeIdx >= 0) rows.getLong(sizeIdx) else 0L,
                        width = if (widthIdx >= 0) rows.getInt(widthIdx) else 0,
                        height = if (heightIdx >= 0) rows.getInt(heightIdx) else 0,
                        durationMs = durationMs,
                        bucketId = bucketId,
                        bucketName = resolvedBucketName,
                        relativePath = resolvedPath,
                        absolutePath = resolvedAbsolutePath,
                        rawBucketId = rawBucketId,
                    )
                }
            }
        }.onFailure { e ->
            dev.ander.alexet.data.AppLog.w("GalleryRepository", "Failed querying $collection: ${e.message}")
        }
        if (strict) outcome.getOrThrow()
        return items
    }

    suspend fun hideAlbumsWithNomedia(albums: List<Album>): Int = withContext(Dispatchers.IO) {
        var written = 0
        albums.forEach { album ->
            val dir = directoryForAlbum(album)
            if (dir != null && writeNomediaFile(dir)) written++
        }
        if (written > 0) {
            settings.changeNomediaFolderPaths(add = albums.map { it.relativePath })
            takeInHiddenRead(nomediaScanner.scanKnown(settings.settings.value.nomediaFolderPaths))
            requestRefresh()
            ensureFullNomediaScan(force = true)
        }
        written
    }

    suspend fun unhideAlbumsNomedia(albums: List<Album>): Int = withContext(Dispatchers.IO) {
        var removed = 0
        albums.forEach { album ->
            val dir = directoryForAlbum(album)
            if (dir != null) removed += deleteNomediaFile(dir)
        }
        if (removed > 0) {
            settings.changeNomediaFolderPaths(remove = albums.map { it.relativePath })
            takeInHiddenRead(nomediaScanner.scanKnown(settings.settings.value.nomediaFolderPaths))
            requestRefresh()
            ensureFullNomediaScan(force = true)
        }
        removed
    }

    suspend fun renameAlbum(album: Album, newName: String): Boolean = withContext(Dispatchers.IO) {
        val cleanName = newName.trim()
        if (cleanName.isBlank() || cleanName == album.name) return@withContext false
        if (cleanName.any { it in "/\\:*?\"<>|" }) return@withContext false

        val dir = directoryForAlbum(album) ?: return@withContext false
        val parent = dir.parentFile ?: return@withContext false
        val target = File(parent, cleanName)
        if (target.exists()) return@withContext false

        val oldPath = dir.absolutePath
        val newPath = target.absolutePath
        val oldCanonical = runCatching { dir.canonicalPath }.getOrDefault(oldPath).trimEnd('/')
        val renamed = dir.renameTo(target)
        if (!renamed) return@withContext false
        if (SecureFolderManager.isFolderSecure(target)) {
            SecureFolderManager.folderRenamed(dir, oldCanonical, target)
        }

        nomediaCache = null
        nomediaScanner.rescanTree(target)

        val newFiles = target.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
        val oldFiles = dir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
        val allScan = (newFiles + oldFiles + listOf(newPath, oldPath)).distinct().toTypedArray()
        if (allScan.isNotEmpty()) {
            val remaining = java.util.concurrent.atomic.AtomicInteger(allScan.size)
            MediaScannerConnection.scanFile(appContext, allScan, null) { _, _ ->
                if (remaining.decrementAndGet() == 0) {
                    requestRefresh()
                }
            }
        }

        val root = Environment.getExternalStorageDirectory()
        val newRelPath = (target.relativeToOrNull(root)?.path?.replace('\\', '/')
            ?: Regex("^/storage/[^/]+/(.+)$").find(target.absolutePath)?.groupValues?.get(1))
            ?.trim('/')?.let { "$it/" }.orEmpty()
        // The id the library gives the folder on its next read: path-derived, relative for
        // MediaStore and hidden folders, the full path for private ones.
        val newBucketId = if (album.isSecure) {
            NomediaScanner.syntheticBucketId(runCatching { target.canonicalPath }.getOrDefault(target.absolutePath))
        } else {
            NomediaScanner.syntheticBucketId(newRelPath.trim('/').ifEmpty { cleanName })
        }
        settings.update { s ->
            var updated = s
            if (album.id in updated.hiddenAlbumIds) {
                updated = updated.copy(hiddenAlbumIds = (updated.hiddenAlbumIds - album.id) + newBucketId)
            }
            if (album.id in updated.anonymousFolderIds) {
                updated = updated.copy(anonymousFolderIds = (updated.anonymousFolderIds - album.id) + newBucketId)
            }
            if (album.id in updated.smartIndexIncludedFolderIds) {
                updated = updated.copy(smartIndexIncludedFolderIds = (updated.smartIndexIncludedFolderIds - album.id) + newBucketId)
            }
            if (album.id in updated.smartIndexExcludedFolderIds) {
                updated = updated.copy(smartIndexExcludedFolderIds = (updated.smartIndexExcludedFolderIds - album.id) + newBucketId)
            }
            val existingSort = updated.folderSorts[album.id]
            if (existingSort != null) {
                updated = updated.copy(folderSorts = (updated.folderSorts - album.id) + (newBucketId to existingSort))
            }
            val existingOrder = updated.folderContentOrders[album.id]
            if (existingOrder != null) {
                updated = updated.copy(folderContentOrders = (updated.folderContentOrders - album.id) + (newBucketId to existingOrder))
            }
            updated
        }

        // A sync reading MediaStore meanwhile must not undo this.
        patchMedia { list ->
            list.map { item ->
                // "/DCIM/Cam" must not match "/DCIM/Camera".
                if (item.bucketId == album.id || (item.absolutePath != null && item.absolutePath.startsWith("$oldPath/"))) {
                    val relSub = item.absolutePath?.removePrefix(oldPath)?.removePrefix("/") ?: item.displayName
                    val itemNewAbs = File(target, relSub).absolutePath
                    item.copy(
                        bucketId = newBucketId,
                        bucketName = cleanName,
                        relativePath = newRelPath,
                        absolutePath = itemNewAbs,
                    )
                } else {
                    item
                }
            }
        }

        _folderMedia.update { map ->
            val list = map[album.id]
            if (list != null) {
                val updated = list.map { item ->
                    val relSub = item.absolutePath?.removePrefix(oldPath)?.removePrefix("/") ?: item.displayName
                    item.copy(
                        bucketId = newBucketId,
                        bucketName = cleanName,
                        relativePath = newRelPath,
                        absolutePath = File(target, relSub).absolutePath,
                    )
                }
                (map - album.id) + (newBucketId to updated)
            } else {
                map
            }
        }

        requestRefresh()
        true
    }

    /** [album]'s folder on the disk; null when it can't be found. Reads the disk. */
    fun folderDirectory(album: Album): File? = directoryForAlbum(album)

    private fun directoryForAlbum(album: Album): File? {
        nomediaScanner.directoryForRelativePath(album.relativePath)?.let { return it }
        album.cover?.let { parentDirectory(it) }?.let { return it }
        if (album.relativePath.isNotBlank()) {
            val rel = album.relativePath.trim().trim('/')
            val f = File(Environment.getExternalStorageDirectory(), rel)
            if (f.exists() && f.isDirectory) return f
        }
        val def = File(Environment.getExternalStorageDirectory(), "${Environment.DIRECTORY_PICTURES}/${album.name}")
        if (def.exists() && def.isDirectory) return def
        return null
    }

    private fun parentDirectory(item: GalleryMedia): File? {
        item.absolutePath?.let { path -> return File(path).parentFile }
        if (item.uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                @Suppress("DEPRECATION")
                resolver.query(
                    item.uri,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null,
                )?.use { rows ->
                    if (rows.moveToFirst()) {
                        val data = rows.getString(0)
                        if (!data.isNullOrBlank()) return File(data).parentFile
                    }
                }
            }
        }
        return null
    }

    private fun writeNomediaFile(dir: File): Boolean {
        val file = File(dir, ".nomedia")
        if (file.exists()) return true
        val created = runCatching { file.createNewFile() }.getOrDefault(false)
        if (created) nomediaScanner.rescanTree(dir)
        return created
    }

    private fun deleteNomediaFile(dir: File): Int {
        val file = File(dir, ".nomedia")
        if (!file.exists()) return 0
        val removed = file.delete()
        if (removed) nomediaScanner.rescanTree(dir)
        return if (removed) 1 else 0
    }

    fun persistTreePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(treeUri, flags) }
    }

    fun destinationFromTree(treeUri: Uri, known: List<Album>): TreeDestination {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        val sep = docId.indexOf(':')
        val volume = if (sep >= 0) docId.substring(0, sep) else "primary"
        val path = if (sep >= 0) docId.substring(sep + 1).trim('/') else docId.trim('/')
        val relative = if (path.isEmpty()) "" else "$path/"
        val existing = known.firstOrNull { album ->
            album.relativePath.equals(relative, ignoreCase = true)
        }
        val name = path.substringAfterLast('/').ifBlank { path.ifBlank { volume } }
        val album = existing ?: Album(
            id = NomediaScanner.syntheticBucketId(relative.trim().trim('/').ifBlank { name.ifBlank { "Download" } }),
            name = name.ifBlank { "Folder" },
            cover = null,
            itemCount = 0,
            relativePath = relative,
        )
        val useMediaStore = volume.equals("primary", ignoreCase = true) ||
            volume.equals("external_primary", ignoreCase = true)
        return TreeDestination(album = album, treeUri = treeUri, useMediaStore = useMediaStore)
    }

    suspend fun copyToDocumentTree(
        items: List<GalleryMedia>,
        treeUri: Uri,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Set<Long> = withContext(Dispatchers.IO) {
        persistTreePermission(treeUri)
        val root = DocumentFile.fromTreeUri(appContext, treeUri) ?: return@withContext emptySet()
        val total = items.size
        var current = 0
        // The folder is listed once: findFile lists all of it on every call, and was called at
        // least once per copied file.
        val takenNames = root.listFiles().mapNotNullTo(HashSet()) { it.name?.lowercase() }
        buildSet {
            items.forEach { item ->
                if (downloadTracker.isActive(item.absolutePath)) {
                    current++
                    onProgress(current, total)
                    return@forEach
                }
                val mime = item.mimeType.ifBlank { "application/octet-stream" }
                val name = uniqueDocumentName(takenNames, sanitizeFileName(item.displayName.ifBlank { "file" }))
                val dest = root.createFile(mime, name)
                if (dest != null) {
                    // The provider may still have picked another name.
                    takenNames += (dest.name ?: name).lowercase()
                    val ok = runCatching {
                        resolver.openOutputStream(dest.uri, "w")?.use { out ->
                            resolver.openInputStream(item.uri)?.use { input -> input.copyTo(out, COPY_BUFFER_BYTES) }
                        }
                    }.getOrNull() != null
                    if (ok) {
                        add(item.id)
                    } else {
                        // No empty or cut-off file left in the target folder.
                        runCatching { dest.delete() }
                    }
                }
                current++
                onProgress(current, total)
            }
        }
    }

    private fun uniqueDocumentName(taken: Set<String>, name: String): String {
        if (name.lowercase() !in taken) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while ("$base ($index)$ext".lowercase() in taken) index++
        return "$base ($index)$ext"
    }
}
