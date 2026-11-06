package dev.ander.alexet.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import dev.ander.alexet.integration.FolderDeleteNotifications
import dev.ander.alexet.integration.FolderDeleteService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A whole folder being deleted (or moved to the trash) in the background. */
@Immutable
data class FolderDeleteProgress(
    val albumId: Long,
    val name: String,
    val toTrash: Boolean,
    val total: Int,
    /** Files handled so far, failed ones included. */
    val done: Int = 0,
    val failed: Int = 0,
    /** False while waiting behind another folder. */
    val running: Boolean = false,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

data class FolderDeleteResult(
    val albumId: Long,
    val name: String,
    val toTrash: Boolean,
    val total: Int,
    val removed: Int,
    val failed: Int,
    val cancelled: Boolean,
)

/**
 * Deletes whole folders one after another, a small batch of files at a time, so the app stays
 * usable (one big batch froze it). [FolderDeleteService] keeps the process alive meanwhile; the
 * queue is also saved to a file and resumes on the next start if the process dies anyway.
 */
object FolderDeleteQueue {
    private const val FILE_NAME = "folder_delete_queue.json"
    /**
     * Ids of the files already handled, one line per batch ("albumId id id ..."). A file restored
     * from the trash to its old place exists again; without this a resumed queue deleted it again.
     */
    private const val DONE_FILE_NAME = "folder_delete_queue.done"
    private const val DELETE_BATCH = 50
    private const val TAG = "FolderDeleteQueue"

    private class QueuedFolder(
        val albumId: Long,
        val name: String,
        val toTrash: Boolean,
        val total: Int,
        /** Files still to handle (on a resume: the ones that still exist). */
        val items: List<GalleryMedia>,
        /** The whole list as queued, kept in the file until the folder is finished. */
        val allItems: List<GalleryMedia> = items,
    ) {
        @Volatile var cancelled = false
    }

    private val lock = Any()
    private val queued = LinkedHashMap<Long, QueuedFolder>()
    private var worker: Job? = null

    private val _jobs = MutableStateFlow<Map<Long, FolderDeleteProgress>>(emptyMap())
    val jobs: StateFlow<Map<Long, FolderDeleteProgress>> = _jobs.asStateFlow()

    private val _results = MutableSharedFlow<FolderDeleteResult>(extraBufferCapacity = 16)
    val results: SharedFlow<FolderDeleteResult> = _results.asSharedFlow()

    private val handler = CoroutineExceptionHandler { _, e -> AppLog.e(TAG, "Uncaught", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
    /** File reads and writes run one at a time, in the order they were asked for. */
    private val fileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + handler)
    @Volatile private var restoreAsked = false

    fun isDeleting(albumId: Long): Boolean = albumId in _jobs.value

    fun enqueue(context: Context, albumId: Long, name: String, items: List<GalleryMedia>, toTrash: Boolean) {
        if (items.isEmpty()) return
        val app = context.applicationContext
        synchronized(lock) {
            if (albumId in queued) return
            queued[albumId] = QueuedFolder(albumId, name, toTrash, items.size, items)
            _jobs.value = _jobs.value + (albumId to FolderDeleteProgress(albumId, name, toTrash, items.size))
            startWorkerLocked(app)
        }
        AppLog.event("Action") { "folderDelete queued album=$albumId files=${items.size} toTrash=$toTrash" }
        save(app)
        FolderDeleteService.start(app)
    }

    fun cancel(albumId: Long) {
        val waiting = synchronized(lock) {
            val folder = queued[albumId] ?: return
            folder.cancelled = true
            // A folder still waiting is dropped right away; the running one stops after its batch.
            if (_jobs.value[albumId]?.running == true) {
                null
            } else {
                queued.remove(albumId)
                _jobs.value = _jobs.value - albumId
                folder
            }
        }
        AppLog.event("Action") { "folderDelete cancel album=$albumId" }
        if (waiting != null) {
            val result = FolderDeleteResult(waiting.albumId, waiting.name, waiting.toTrash, waiting.total,
                removed = waiting.total - waiting.items.size, failed = 0, cancelled = true)
            _results.tryEmit(result)
        }
    }

    fun cancelAll() {
        synchronized(lock) { queued.keys.toList() }.forEach { cancel(it) }
    }

    /** Picks up folders a killed process did not finish. Called when the app opens. */
    fun restore(context: Context) {
        if (restoreAsked) return
        restoreAsked = true
        val app = context.applicationContext
        fileScope.launch {
            val file = File(app.filesDir, FILE_NAME)
            if (!file.exists()) return@launch
            val saved = runCatching { decode(file.readText()) }.getOrElse { e ->
                AppLog.e(TAG, "Unreadable queue file", e)
                file.delete()
                File(app.filesDir, DONE_FILE_NAME).delete()
                return@launch
            }
            val handled = readHandled(app)
            // Checked before taking the lock: thousands of files on an SD card take a while, and
            // a delete or cancel from the screen waits for the lock on the main thread.
            val leftByAlbum = saved.associate { folder ->
                val done = handled[folder.albumId].orEmpty()
                // Files already gone, or handled before the process died (even if the user put
                // them back from the trash since), are not deleted again.
                folder.albumId to folder.allItems.filter { item ->
                    item.id !in done && (item.absolutePath == null || File(item.absolutePath).exists())
                }
            }
            var added = false
            synchronized(lock) {
                for (folder in saved) {
                    if (folder.albumId in queued) continue
                    val left = leftByAlbum[folder.albumId].orEmpty()
                    if (left.isEmpty()) continue
                    val restored = QueuedFolder(folder.albumId, folder.name, folder.toTrash, folder.total, left, folder.allItems)
                    queued[folder.albumId] = restored
                    _jobs.value = _jobs.value + (folder.albumId to FolderDeleteProgress(
                        folder.albumId, folder.name, folder.toTrash, folder.total, done = folder.total - left.size,
                    ))
                    added = true
                }
                if (added) startWorkerLocked(app)
            }
            AppLog.event("Action") { "folderDelete restore folders=${saved.size} resumed=$added" }
            if (added) FolderDeleteService.start(app) else saveNow(app)
        }
    }

    private fun startWorkerLocked(app: Context) {
        if (worker != null) return
        // Started lazily under the lock: the loop clears [worker] under the same lock when it
        // runs out of folders, so a folder queued at that moment always gets a worker.
        val job = scope.launch(start = CoroutineStart.LAZY) { runQueue(app) }
        worker = job
        job.start()
    }

    private suspend fun runQueue(app: Context) {
        while (true) {
            val folder = synchronized(lock) {
                val next = queued.values.firstOrNull() ?: run { worker = null; null }
                // Running from this moment: a cancel now stops it after its batch instead of
                // dropping it as a waiting folder (and reporting the result twice).
                next?.let { f ->
                    _jobs.value[f.albumId]?.let { _jobs.value = _jobs.value + (f.albumId to it.copy(running = true)) }
                }
                next
            } ?: break
            val result = runFolder(app, folder)
            synchronized(lock) {
                queued.remove(folder.albumId)
                _jobs.value = _jobs.value - folder.albumId
            }
            save(app)
            AppLog.event("Action") { "folderDelete done $result" }
            _results.tryEmit(result)
            FolderDeleteNotifications.showResult(app, result)
        }
    }

    private suspend fun runFolder(app: Context, folder: QueuedFolder): FolderDeleteResult {
        val repository = AppGraph.repository(app)
        val trash = if (folder.toTrash) AppGraph.trash(app) else null
        var done = folder.total - folder.items.size
        var failed = 0
        fun publish(running: Boolean) {
            synchronized(lock) {
                if (folder.albumId !in queued) return
                _jobs.value = _jobs.value + (folder.albumId to FolderDeleteProgress(
                    folder.albumId, folder.name, folder.toTrash, folder.total, done, failed, running,
                ))
            }
        }
        publish(running = true)
        // Each trash batch rewrites the whole trash index: fewer, bigger batches there.
        val batchSize = if (trash != null) maxOf(100, folder.items.size / 80) else DELETE_BATCH
        for (batch in folder.items.chunked(batchSize)) {
            if (folder.cancelled) break
            val ok = try {
                if (trash == null) {
                    repository.deleteDirect(batch)
                } else {
                    val trashed = trash.moveToTrash(batch) { repository.fileForMedia(it) }
                    val trashedIds = trashed.mapTo(HashSet()) { it.id }
                    // The MediaStore rows of the moved files go too (as the selection delete does).
                    val rows = batch.filter { it.id in trashedIds }
                    if (rows.isNotEmpty()) repository.deleteDirect(rows)
                    trashed.size
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.e(TAG, "Batch failed in album ${folder.albumId}", e)
                0
            }
            done += batch.size
            failed += batch.size - ok
            publish(running = true)
            markHandled(app, folder.albumId, batch)
        }
        repository.requestRefresh()
        return FolderDeleteResult(
            albumId = folder.albumId,
            name = folder.name,
            toTrash = folder.toTrash,
            total = folder.total,
            removed = done - failed,
            failed = failed,
            cancelled = folder.cancelled && done < folder.total,
        )
    }

    /** All queue file writes go through [fileScope], one at a time, each with the newest state. */
    private fun save(app: Context) {
        fileScope.launch { saveNow(app) }
    }

    private fun markHandled(app: Context, albumId: Long, batch: List<GalleryMedia>) {
        val line = buildString {
            append(albumId)
            batch.forEach { append(' ').append(it.id) }
            append('\n')
        }
        fileScope.launch {
            runCatching { File(app.filesDir, DONE_FILE_NAME).appendText(line) }
                .onFailure { AppLog.e(TAG, "Saving the progress failed", it) }
        }
    }

    private fun readHandled(app: Context): Map<Long, Set<Long>> {
        val file = File(app.filesDir, DONE_FILE_NAME)
        if (!file.isFile) return emptyMap()
        val out = HashMap<Long, MutableSet<Long>>()
        runCatching {
            file.forEachLine { line ->
                val parts = line.trim().split(' ')
                val album = parts.firstOrNull()?.toLongOrNull() ?: return@forEachLine
                val ids = out.getOrPut(album) { HashSet() }
                for (i in 1 until parts.size) parts[i].toLongOrNull()?.let { ids += it }
            }
        }
        return out
    }

    /** Only from [fileScope] (or code already running there). */
    private fun saveNow(app: Context) {
        val snapshot = synchronized(lock) { queued.values.toList() }
        val file = File(app.filesDir, FILE_NAME)
        if (snapshot.isEmpty()) {
            file.delete()
            File(app.filesDir, DONE_FILE_NAME).delete()
            return
        }
        runCatching {
            val tmp = File(app.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(encode(snapshot))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { AppLog.e(TAG, "Saving the queue failed", it) }
    }

    private fun encode(folders: List<QueuedFolder>): String {
        val array = JSONArray()
        for (folder in folders) {
            array.put(JSONObject().apply {
                put("albumId", folder.albumId)
                put("name", folder.name)
                put("toTrash", folder.toTrash)
                put("total", folder.total)
                put("items", JSONArray().apply { folder.allItems.forEach { put(it.toQueueJson()) } })
            })
        }
        return JSONObject().put("version", 1).put("folders", array).toString()
    }

    private fun decode(text: String): List<QueuedFolder> {
        val array = JSONObject(text).getJSONArray("folders")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val itemsJson = o.getJSONArray("items")
            val items = (0 until itemsJson.length()).map { itemsJson.getJSONObject(it).toQueueMedia() }
            QueuedFolder(o.getLong("albumId"), o.getString("name"), o.getBoolean("toTrash"), o.getInt("total"), items)
        }
    }

    private fun GalleryMedia.toQueueJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("uri", uri.toString())
        put("name", displayName)
        put("mime", mimeType)
        put("taken", dateTakenMs)
        put("modified", dateModifiedMs)
        put("added", dateAddedMs)
        put("size", sizeBytes)
        put("w", width)
        put("h", height)
        put("duration", durationMs)
        put("bucket", bucketId)
        put("bucketName", bucketName)
        put("rel", relativePath)
        put("hidden", isHidden)
        absolutePath?.let { put("path", it) }
        put("rawBucket", rawBucketId)
    }

    private fun JSONObject.toQueueMedia(): GalleryMedia = GalleryMedia(
        id = getLong("id"),
        uri = Uri.parse(getString("uri")),
        displayName = getString("name"),
        mimeType = optString("mime"),
        dateTakenMs = optLong("taken"),
        dateModifiedMs = optLong("modified"),
        dateAddedMs = optLong("added"),
        sizeBytes = optLong("size"),
        width = optInt("w"),
        height = optInt("h"),
        durationMs = optLong("duration"),
        bucketId = getLong("bucket"),
        bucketName = optString("bucketName"),
        relativePath = optString("rel"),
        isHidden = optBoolean("hidden"),
        absolutePath = if (has("path")) getString("path") else null,
        rawBucketId = optLong("rawBucket"),
    )
}
