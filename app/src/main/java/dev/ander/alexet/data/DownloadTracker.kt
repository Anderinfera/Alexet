package dev.ander.alexet.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DownloadPhase {
    ACTIVE,
    SETTLING,
}

enum class DownloadSource {
    FILESYSTEM,
    DOWNLOAD_MANAGER,
    MEDIASTORE_PENDING,
    DOWNLOADER,
}

data class ActiveDownload(
    val path: String,
    val phase: DownloadPhase,
    val bytes: Long,
    val totalBytes: Long? = null,
    val source: DownloadSource,
    val lastEventAt: Long,
    val lastPublishedAt: Long = lastEventAt,
    val settleBytes: Long = bytes,
    val settleAt: Long = lastEventAt,
)

/**
 * In-memory map of files that are still being written. Completed files are removed.
 * [nowMs] should be monotonic (elapsedRealtime). File [lastModified] is only used via [snapshot].
 */
/**
 * Mutating calls are synchronized: FileObserver threads, the tick loop and taps on the UI
 * thread all read-modify-write the same entries.
 */
class DownloadTracker(
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val snapshot: (String) -> Pair<Long, Long>? = { path ->
        val file = File(path)
        if (!file.isFile) null else file.length() to file.lastModified()
    },
    /**
     * MediaStore lists the file as finished (not IS_PENDING): the same evidence the grid uses to
     * show its thumbnail. Lets a stable file go without waiting [IDLE_COMPLETE_MS] when no
     * close/move event arrived (writes by other apps often give none).
     */
    private val isPublished: (String) -> Boolean = { false },
    /**
     * The file's own structure says it was written to the end (true), still misses data (false),
     * or can't tell (null). See [FileCompleteness].
     */
    private val isComplete: (String) -> Boolean? = { null },
) {
    var onCompleted: ((normalizedPath: String, displayPath: String) -> Unit)? = null

    private val entries = ConcurrentHashMap<String, ActiveDownload>()
    private val displayPaths = ConcurrentHashMap<String, String>()
    /** Pending-row downloads released as finished, with their size then (see [syncPending]). */
    private val releasedPending = ConcurrentHashMap<String, Long>()
    private val _active = MutableStateFlow<Map<String, ActiveDownload>>(emptyMap())
    val active: StateFlow<Map<String, ActiveDownload>> = _active.asStateFlow()

    fun isActive(path: String?): Boolean = get(path) != null

    /**
     * Same as [isActive], but first re-checks a tracked file on the spot (user tapped it): a file
     * that stopped growing and is structurally complete is released right away instead of on the
     * next [tick] or after the idle timeout.
     */
    fun isStillDownloading(path: String?): Boolean {
        // Called on the UI thread. The file checks run before taking the lock, which tick() holds
        // while it checks every tracked file; untracked files (almost every tap) never wait.
        val seen = get(path) ?: return false
        val seenDisplay = displayPaths[seen.path] ?: seen.path
        val seenBytes = snapshot(seenDisplay)?.first
        val seenComplete = if (seenBytes != null) isComplete(seenDisplay) else null
        val still = synchronized(this) {
            val entry = get(path) ?: return@synchronized false
            if (entry.path != seen.path) return@synchronized true
            stillDownloadingLocked(entry, seenBytes, seenComplete)
        }
        notifyCompleted()
        return still
    }

    private fun stillDownloadingLocked(entry: ActiveDownload, bytes: Long?, complete: Boolean?): Boolean {
        if (entry.source == DownloadSource.DOWNLOADER) return true
        val key = entry.path
        val display = displayPaths[key] ?: key
        if (bytes == null) {
            AppLog.event("Downloads") { "dropped on open (file gone): $display" }
            entries.remove(key)
            displayPaths.remove(key)
            publish()
            return false
        }
        val now = nowMs()
        val lastBytes = if (entry.phase == DownloadPhase.SETTLING) entry.settleBytes else entry.bytes
        val quiet = entry.phase == DownloadPhase.SETTLING || now - entry.lastEventAt >= CONTENT_STABLE_MS
        if (bytes == lastBytes && quiet && managerAllows(entry, bytes) && complete == true) {
            AppLog.event("Downloads") { "ready on open (complete, ${bytes} B): $display" }
            val done = ArrayList<String>(1)
            complete(key, display, done)
            publish()
            return false
        }
        AppLog.event("Downloads") {
            "still downloading on open: phase=${entry.phase} source=${entry.source} bytes=$lastBytes -> $bytes " +
                "quietMs=${now - entry.lastEventAt} complete=$complete: $display"
        }
        return true
    }

    /** DownloadManager rows only go once the manager reports every byte. */
    private fun managerAllows(entry: ActiveDownload, bytes: Long): Boolean =
        entry.source != DownloadSource.DOWNLOAD_MANAGER ||
            (entry.totalBytes != null && bytes >= entry.totalBytes)

    fun get(path: String?): ActiveDownload? = lookup(entries, path)

    fun onCreated(path: String, source: DownloadSource = DownloadSource.FILESYSTEM): Boolean {
        if (!DownloadMediaFilter.isMediaDownload(path)) return false
        // The file is looked at before the lock: a tap on the UI thread may be waiting for it.
        val bytes = snapshot(path)?.first ?: 0L
        return synchronized(this) { createdLocked(path, source, bytes) }
    }

    private fun createdLocked(path: String, source: DownloadSource, bytes: Long): Boolean {
        val key = DownloadMediaFilter.normalizePath(path)
        if (entries[key]?.source == DownloadSource.DOWNLOADER) return true
        AppLog.event("Downloads") { "active ($source): $path" }
        val now = nowMs()
        displayPaths[key] = path
        entries[key] = ActiveDownload(
            path = key,
            phase = DownloadPhase.ACTIVE,
            bytes = bytes,
            source = source,
            lastEventAt = now,
        )
        publish()
        return true
    }

    /**
     * A file written shortly before the watch started (app was in the background): no events were
     * seen, so it may just as well be finished. It only has to keep its size for [SETTLE_MS];
     * if it still grows, [tick] turns it back into an active download.
     */
    fun onSeeded(path: String): Boolean {
        if (!DownloadMediaFilter.isMediaDownload(path)) return false
        if (entries.containsKey(DownloadMediaFilter.normalizePath(path))) return false
        AppLog.event("Downloads") { "seeded (written while in background): $path" }
        return onCloseOrMovedTo(path)
    }

    fun onModified(path: String): Boolean {
        if (!DownloadMediaFilter.isMediaDownload(path)) return false
        val snap = snapshot(path)?.first
        return synchronized(this) { modifiedLocked(path, snap) }
    }

    private fun modifiedLocked(path: String, snap: Long?): Boolean {
        val key = DownloadMediaFilter.normalizePath(path)
        val now = nowMs()
        val bytes = snap ?: entries[key]?.bytes ?: 0L
        val existing = entries[key]
        if (existing == null) {
            return createdLocked(path, DownloadSource.FILESYSTEM, snap ?: 0L)
        }
        if (existing.source == DownloadSource.DOWNLOADER) return true
        val shouldPublish = now - existing.lastPublishedAt >= MODIFY_THROTTLE_MS
        entries[key] = existing.copy(
            phase = DownloadPhase.ACTIVE,
            bytes = bytes,
            lastEventAt = now,
            lastPublishedAt = if (shouldPublish) now else existing.lastPublishedAt,
        )
        displayPaths.putIfAbsent(key, path)
        if (shouldPublish) publish()
        return true
    }

    /**
     * The writer says it is done (closed, moved, manager or MediaStore row finished). The file is
     * looked at right away: a structurally complete one is ready at once instead of on the next
     * [tick]; anything else settles as before.
     */
    fun onCloseOrMovedTo(path: String): Boolean {
        if (!DownloadMediaFilter.isMediaDownload(path)) return false
        // Read before the lock (see onCreated): a tap on the UI thread may be waiting for it.
        val snap = snapshot(path)?.first
        val whole = if (snap != null) isComplete(path) else null
        val tracked = synchronized(this) { closeOrMovedToLocked(path, snap, whole) }
        // Called with the lock held (syncPending): that caller notifies once it lets go.
        if (!Thread.holdsLock(this)) notifyCompleted()
        return tracked
    }

    private fun closeOrMovedToLocked(path: String, snap: Long?, whole: Boolean?): Boolean {
        val key = DownloadMediaFilter.normalizePath(path)
        val existing = entries[key]
        // Closed between chunks: the downloader says when it is done.
        if (existing?.source == DownloadSource.DOWNLOADER) return true
        AppLog.event("Downloads") { "settling (closed / moved / finished): $path" }
        val now = nowMs()
        val bytes = snap ?: existing?.bytes ?: 0L
        val settled = existing?.copy(
            phase = DownloadPhase.SETTLING,
            bytes = bytes,
            lastEventAt = now,
            lastPublishedAt = now,
            settleBytes = bytes,
            settleAt = now,
        ) ?: ActiveDownload(
            path = key,
            phase = DownloadPhase.SETTLING,
            bytes = bytes,
            source = DownloadSource.FILESYSTEM,
            lastEventAt = now,
            settleBytes = bytes,
            settleAt = now,
        )
        entries[key] = settled
        if (existing == null) displayPaths[key] = path else displayPaths.putIfAbsent(key, path)
        if (snap != null && whole == true && managerAllows(settled, bytes)) {
            AppLog.event("Downloads") { "ready (finished, content complete): $path" }
            complete(key, displayPaths[key] ?: path, ArrayList(1))
        }
        publish()
        return true
    }

    /**
     * A rename is atomic, so a file arriving via MOVED_TO is already complete. Only a file that was
     * being tracked under this exact path is settled; anything else (moves inside Alexet or a file
     * manager, a browser's temp -> final rename) is not a download and must stay accessible.
     */
    fun onMovedTo(path: String): Boolean {
        if (!DownloadMediaFilter.isMediaDownload(path)) return false
        if (!entries.containsKey(DownloadMediaFilter.normalizePath(path))) return false
        return onCloseOrMovedTo(path)
    }

    @Synchronized
    fun onDeleted(path: String) {
        val key = DownloadMediaFilter.normalizePath(path)
        if (entries.remove(key) != null) {
            AppLog.event("Downloads") { "removed (deleted / moved away): $path" }
            displayPaths.remove(key)
            publish()
        }
    }

    @Synchronized
    fun onManagerProgress(path: String, bytes: Long, totalBytes: Long?) {
        if (!DownloadMediaFilter.isMediaDownload(path)) return
        val key = DownloadMediaFilter.normalizePath(path)
        val now = nowMs()
        val existing = entries[key]
        val total = totalBytes?.takeIf { it > 0L }
        if (existing == null) AppLog.event("Downloads") { "active (DownloadManager $bytes/$total): $path" }
        displayPaths[key] = path
        entries[key] = (existing ?: ActiveDownload(
            path = key,
            phase = DownloadPhase.ACTIVE,
            bytes = bytes,
            totalBytes = total,
            source = DownloadSource.DOWNLOAD_MANAGER,
            lastEventAt = now,
        )).copy(
            phase = DownloadPhase.ACTIVE,
            bytes = bytes,
            totalBytes = total ?: existing?.totalBytes,
            source = DownloadSource.DOWNLOAD_MANAGER,
            lastEventAt = now,
            lastPublishedAt = now,
        )
        publish()
    }

    val followsDownloader: Boolean
        get() = entries.values.any { it.source == DownloadSource.DOWNLOADER }

    /**
     * Everything the downloader lists now. While it lists a file, only it says when the file is
     * done: the size says nothing (reserved in advance, 0 B until the end, or written again after
     * muxing), and neither do pauses or errors, the file stays incomplete until the download goes
     * on. A file it no longer lists without finishing it (its process died, the download was
     * removed) goes back to the rules for other files.
     */
    fun onDownloaderMissions(missions: List<DownloaderMission>) {
        synchronized(this) { downloaderMissionsLocked(missions) }
        notifyCompleted()
    }

    private fun downloaderMissionsLocked(missions: List<DownloaderMission>) {
        val now = nowMs()
        val listed = HashSet<String>()
        var changed = false
        for (mission in missions) {
            if (!DownloadMediaFilter.isMediaDownload(mission.path)) continue
            val key = DownloadMediaFilter.normalizePath(mission.path)
            listed += key
            val existing = entries[key]
            if (mission.state == DownloaderState.FINISHED) {
                // Stays listed for a while after it ended: only a file still followed is let go.
                if (existing?.source == DownloadSource.DOWNLOADER) {
                    AppLog.event("Downloads") { "ready (downloader finished): ${mission.path}" }
                    complete(key, displayPaths[key] ?: mission.path, ArrayList(1))
                    changed = true
                }
                continue
            }
            val bytes = mission.bytes.takeIf { it >= 0L } ?: existing?.bytes ?: 0L
            // Muxing has no percent of its own; a paused or failed download stays where it got to.
            val total = when {
                mission.state == DownloaderState.POSTPROCESSING -> null
                mission.total > 0L -> mission.total
                else -> existing?.totalBytes
            }
            if (existing?.source == DownloadSource.DOWNLOADER && existing.phase == DownloadPhase.ACTIVE &&
                existing.bytes == bytes && existing.totalBytes == total
            ) {
                continue
            }
            if (existing?.source != DownloadSource.DOWNLOADER) {
                AppLog.event("Downloads") { "active (downloader ${mission.state}): ${mission.path}" }
            }
            displayPaths[key] = mission.path
            entries[key] = (existing ?: ActiveDownload(
                path = key,
                phase = DownloadPhase.ACTIVE,
                bytes = bytes,
                source = DownloadSource.DOWNLOADER,
                lastEventAt = now,
            )).copy(
                phase = DownloadPhase.ACTIVE,
                bytes = bytes,
                totalBytes = total,
                source = DownloadSource.DOWNLOADER,
                lastEventAt = now,
                lastPublishedAt = now,
            )
            changed = true
        }
        for ((key, entry) in entries) {
            if (entry.source != DownloadSource.DOWNLOADER || key in listed) continue
            AppLog.event("Downloads") { "no longer listed by the downloader: ${displayPaths[key] ?: key}" }
            entries[key] = entry.copy(
                source = DownloadSource.FILESYSTEM,
                totalBytes = null,
                lastEventAt = now,
                lastPublishedAt = now,
            )
            changed = true
        }
        if (changed) publish()
    }

    fun syncPending(pendingPaths: Collection<String>) {
        // Sizes read before the lock (see onCreated).
        val sizes = HashMap<String, Long?>()
        for (raw in pendingPaths) {
            if (DownloadMediaFilter.isMediaDownload(raw)) sizes[raw] = snapshot(raw)?.first
        }
        synchronized(this) { syncPendingLocked(pendingPaths, sizes) }
        notifyCompleted()
    }

    private fun syncPendingLocked(pendingPaths: Collection<String>, sizes: Map<String, Long?>) {
        val wanted = LinkedHashSet<String>()
        for (raw in pendingPaths) {
            if (!DownloadMediaFilter.isMediaDownload(raw)) continue
            val key = DownloadMediaFilter.normalizePath(raw)
            wanted += key
            val released = releasedPending[key]
            if (released != null && released == sizes[raw]) continue
            releasedPending.remove(key)
            if (!entries.containsKey(key)) {
                createdLocked(raw, DownloadSource.MEDIASTORE_PENDING, sizes[raw] ?: 0L)
            }
        }
        releasedPending.keys.retainAll(wanted)
        var changed = false
        for (key in entries.keys.toList()) {
            val entry = entries[key] ?: continue
            if (entry.source != DownloadSource.MEDIASTORE_PENDING) continue
            if (key !in wanted) {
                val display = displayPaths[key] ?: key
                onCloseOrMovedTo(display)
                changed = true
            }
        }
        if (changed) {
            // onCloseOrMovedTo already published
        }
    }

    /**
     * Periodic maintenance: settle stable files, drop stale cancelled downloads.
     * @return display paths that just became ready (for scan + thumb refresh).
     */
    /** File and MediaStore evidence about one entry, gathered before the lock is taken. */
    private class Probe(val entry: ActiveDownload, val bytes: Long?, val complete: Boolean?, val published: Boolean)

    fun tick(): List<String> {
        val now = nowMs()
        // Reads files (and queries MediaStore) for every tracked entry: done without the lock,
        // so a tap on the UI thread (isStillDownloading) doesn't wait for all of it.
        val probes = HashMap<String, Probe>()
        for ((key, entry) in entries) {
            if (entry.source == DownloadSource.DOWNLOADER) continue
            val display = displayPaths[key] ?: key
            val bytes = snapshot(display)?.first
            val idle = now - entry.lastEventAt
            val needsCompleteness = bytes != null && when (entry.phase) {
                DownloadPhase.SETTLING -> true
                DownloadPhase.ACTIVE -> idle in CONTENT_STABLE_MS until IDLE_COMPLETE_MS && bytes == entry.bytes
            }
            val complete = if (needsCompleteness) isComplete(display) else null
            val published = needsCompleteness && entry.phase == DownloadPhase.ACTIVE &&
                idle >= PUBLISHED_STABLE_MS && entry.source != DownloadSource.DOWNLOAD_MANAGER &&
                complete != false && !(managerAllows(entry, bytes!!) && complete == true) &&
                isPublished(display)
            probes[key] = Probe(entry, bytes, complete, published)
        }
        val completed = synchronized(this) { tickLocked(now, probes) }
        notifyCompleted()
        return completed
    }

    private fun tickLocked(now: Long, probes: Map<String, Probe>): List<String> {
        val completed = ArrayList<String>()
        var changed = false
        for ((key, entry) in entries) {
            val display = displayPaths[key] ?: key
            // Changed (or added) since the checks above: looked at again on the next tick.
            val probe = probes[key]?.takeIf { it.entry == entry } ?: continue
            val snap = probe.bytes
            if (snap == null) {
                AppLog.event("Downloads") { "dropped (file gone): $display" }
                entries.remove(key)
                displayPaths.remove(key)
                changed = true
                continue
            }
            val bytes: Long = snap
            when (entry.phase) {
                DownloadPhase.SETTLING -> {
                    if (bytes != entry.settleBytes) {
                        AppLog.event("Downloads") { "still growing (${entry.settleBytes} -> $bytes), active again: $display" }
                        entries[key] = entry.copy(
                            phase = DownloadPhase.ACTIVE,
                            bytes = bytes,
                            lastEventAt = now,
                            lastPublishedAt = now,
                            settleBytes = bytes,
                            settleAt = now,
                        )
                        changed = true
                    } else if (now - entry.settleAt >= SETTLE_MS) {
                        if (probe.complete == false) {
                            // Closed between chunks: still missing data. Back to the active rules
                            // (idle timeout, stale drop) instead of releasing a cut-off file.
                            AppLog.event("Downloads") { "closed but incomplete, active again: $display" }
                            entries[key] = entry.copy(phase = DownloadPhase.ACTIVE, lastEventAt = entry.settleAt, lastPublishedAt = now)
                        } else {
                            AppLog.event("Downloads") { "ready (size stable ${SETTLE_MS} ms): $display" }
                            complete(key, display, completed)
                        }
                        changed = true
                    } else if (probe.complete == true) {
                        AppLog.event("Downloads") { "ready (closed, content complete): $display" }
                        complete(key, display, completed)
                        changed = true
                    }
                }
                DownloadPhase.ACTIVE -> {
                    val idle = now - entry.lastEventAt
                    if (idle >= STALE_REMOVE_MS) {
                        AppLog.event("Downloads") { "dropped (no activity ${idle / 1000} s): $display" }
                        entries.remove(key)
                        displayPaths.remove(key)
                        changed = true
                    } else if (idle >= IDLE_COMPLETE_MS) {
                        if (bytes == entry.bytes) {
                            AppLog.event("Downloads") { "ready (idle ${idle / 1000} s): $display" }
                            complete(key, display, completed)
                            changed = true
                        } else {
                            entries[key] = entry.copy(
                                bytes = bytes,
                                lastEventAt = now,
                                lastPublishedAt = now,
                            )
                            changed = true
                        }
                    } else if (idle >= CONTENT_STABLE_MS) {
                        if (bytes != entry.bytes) {
                            // Grew without an event: still writing.
                            entries[key] = entry.copy(bytes = bytes, lastEventAt = now, lastPublishedAt = now)
                            changed = true
                        } else if (managerAllows(entry, bytes) && probe.complete == true) {
                            AppLog.event("Downloads") { "ready (stable ${idle} ms, content complete): $display" }
                            complete(key, display, completed)
                            changed = true
                        } else if (
                            idle >= PUBLISHED_STABLE_MS &&
                            entry.source != DownloadSource.DOWNLOAD_MANAGER &&
                            probe.complete != false &&
                            probe.published
                        ) {
                            // Our own scan on CREATE publishes a row at once, so a structurally
                            // incomplete file must not go on this evidence alone.
                            AppLog.event("Downloads") { "ready (stable, published in MediaStore): $display" }
                            complete(key, display, completed)
                            changed = true
                        }
                    }
                }
            }
        }
        if (changed) publish()
        return completed
    }

    private fun complete(key: String, display: String, completed: MutableList<String>) {
        val entry = entries[key]
        if (entry?.source == DownloadSource.MEDIASTORE_PENDING) {
            // Its row may stay IS_PENDING (abandoned by the writer): don't track it again for that.
            releasedPending[key] = entry.bytes
        }
        entries.remove(key)
        displayPaths.remove(key)
        completed += display
        toNotify += key to display
    }

    /** Finished files whose [onCompleted] is called once the lock is let go (it scans and refreshes). */
    private val toNotify = ArrayList<Pair<String, String>>()

    private fun notifyCompleted() {
        val batch = synchronized(this) {
            if (toNotify.isEmpty()) return
            toNotify.toList().also { toNotify.clear() }
        }
        val callback = onCompleted ?: return
        for ((key, display) in batch) callback(key, display)
    }

    private fun publish() {
        _active.value = HashMap(entries)
    }

    companion object {
        const val MODIFY_THROTTLE_MS = 1_000L
        /** Closed and the size held for this long (format can't be checked): ready. */
        const val SETTLE_MS = 500L
        const val IDLE_COMPLETE_MS = 30_000L
        const val PUBLISHED_STABLE_MS = 1_000L
        /** No growth for this long plus a structurally complete file: ready. */
        const val CONTENT_STABLE_MS = 500L
        /** How often tracked files are looked at while something downloads. */
        const val ACTIVE_TICK_MS = 250L
        const val STALE_REMOVE_MS = 5 * 60_000L
        const val RECENT_WRITE_SEED_MS = 30_000L

        fun lookup(map: Map<String, ActiveDownload>, path: String?): ActiveDownload? {
            if (map.isEmpty() || path.isNullOrBlank()) return null
            val key = DownloadMediaFilter.normalizePath(path)
            map[key]?.let { return it }
            val stripped = strippedKey(key)
            if (stripped != key) map[stripped]?.let { return it }
            return map.values.firstOrNull { strippedKey(it.path) == stripped }
        }

        fun strippedKey(normalized: String): String {
            val slash = normalized.lastIndexOf('/')
            val name = if (slash >= 0) normalized.substring(slash + 1) else normalized
            val clean = DownloadMediaFilter.stripTempSuffixes(name)
            return if (slash >= 0) normalized.substring(0, slash + 1) + clean else clean
        }
    }
}
