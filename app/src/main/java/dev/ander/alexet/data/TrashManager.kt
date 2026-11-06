package dev.ander.alexet.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun TrashedItem.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("originalUriString", originalUriString)
    put("originalPath", originalPath)
    put("originalBucketId", originalBucketId)
    put("originalBucketName", originalBucketName)
    put("originalRelativePath", originalRelativePath)
    put("displayName", displayName)
    put("mimeType", mimeType)
    put("sizeBytes", sizeBytes)
    put("dateTakenMs", dateTakenMs)
    put("trashedAtMs", trashedAtMs)
    put("trashFilePath", trashFilePath)
    put("sourceRemoved", sourceRemoved)
    put("trashUriString", trashUriString)
}

class TrashManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        dev.ander.alexet.data.AppLog.e("TrashManager", "Uncaught coroutine exception", throwable)
    }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)
    private val mutex = Mutex()

    /**
     * Told before ([done] = false) and after (true) the trash writes a file in a gallery folder,
     * so the folder watcher doesn't take a restored file for a download.
     */
    @Volatile var ownWriteListener: ((file: File, done: Boolean) -> Unit)? = null

    private fun resolveTrashDir(): File {
        val root = Environment.getExternalStorageDirectory()
        val dir = if (root != null && root.canWrite()) {
            File(root, ".alexet_trash")
        } else {
            val extFiles = appContext.getExternalFilesDir(null)
            if (extFiles != null) {
                File(extFiles, ".alexet_trash")
            } else {
                File(appContext.filesDir, ".alexet_trash")
            }
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        return dir
    }

    private val trashDir: File by lazy { resolveTrashDir() }

    /**
     * Trash folders used by earlier runs: the location depends on all-files access, which can be
     * granted later. Their entries are merged into the current index and their files stay valid.
     */
    private fun otherTrashDirs(): List<File> {
        val current = runCatching { trashDir.canonicalFile }.getOrNull()
        return listOfNotNull(
            Environment.getExternalStorageDirectory()?.let { File(it, ".alexet_trash") },
            appContext.getExternalFilesDir(null)?.let { File(it, ".alexet_trash") },
            File(appContext.filesDir, ".alexet_trash"),
        ).filter { dir ->
            dir.isDirectory && runCatching { dir.canonicalFile }.getOrNull() != current
        }
    }

    /** Set once the index was read; every operation reads it first so none saves over it unread. */
    private var loaded = false

    private fun ensureLoadedLocked() {
        if (!loaded) loadInternal()
    }

    private val indexFile: File
        get() = File(trashDir, INDEX_NAME)

    private val _indexedItems = MutableStateFlow<List<TrashedItem>>(emptyList())
    private val _pendingDeleteIds = MutableStateFlow<Set<Long>>(emptySet())
    val items: StateFlow<List<TrashedItem>> = combine(_indexedItems, _pendingDeleteIds) { all, pending ->
        if (pending.isEmpty()) all else all.filter { it.id !in pending }
    }.stateIn(ioScope, SharingStarted.Eagerly, emptyList())

    fun markPendingDelete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        _pendingDeleteIds.update { it + ids }
    }

    fun unmarkPendingDelete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        _pendingDeleteIds.update { it - ids.toSet() }
    }

    init {
        ioScope.launch {
            mutex.withLock {
                ensureLoadedLocked()
                purgeExpiredInternal()
            }
        }
    }

    private fun loadInternal() {
        val merged = LinkedHashMap<Long, TrashedItem>()
        readIndex(indexFile).forEach { merged[it.id] = it }
        val migratedFrom = ArrayList<File>()
        for (dir in otherTrashDirs()) {
            val other = File(dir, INDEX_NAME)
            if (!other.isFile) continue
            readIndex(other).forEach { merged.putIfAbsent(it.id, it) }
            migratedFrom += other
        }
        // Files moved in by a batch whose index write never happened (process killed, write
        // failed) or kept by an index that had to be set aside: entries again, so they show in the
        // trash and can be restored or purged.
        val recovered = recoverUnindexed(merged)
        _indexedItems.value = merged.values.toList()
        loaded = true
        if (migratedFrom.isNotEmpty() || recovered > 0) {
            if (saveInternal(merged.values.toList())) pendingJournal.delete()
            migratedFrom.forEach { old -> old.renameTo(File(old.parentFile, "$INDEX_NAME.merged")) }
        } else if (pendingJournal.exists()) {
            pendingJournal.delete()
        }
    }

    /** Entries of the batch being moved, written before its first file moves (see [moveToTrash]). */
    private val pendingJournal: File
        get() = File(trashDir, "$INDEX_NAME.pending")

    private fun recoverUnindexed(merged: LinkedHashMap<Long, TrashedItem>): Int {
        val indexedPaths = merged.values.mapTo(HashSet()) { it.trashFilePath }
        var recovered = 0
        // 1. The journal knows the original place of every file of the interrupted batch.
        if (pendingJournal.isFile) {
            val planned = runCatching { parseEntries(JSONArray(pendingJournal.readText())) }.getOrDefault(emptyList())
            for (entry in planned) {
                if (entry.trashFilePath in indexedPaths || !File(entry.trashFilePath).isFile) continue
                merged[entry.id] = entry
                indexedPaths += entry.trashFilePath
                recovered++
            }
        }
        // 2. Any other file in the trash folder without an entry (index set aside, older crash):
        // its name is "<trashedAt>_<id>_<name>"; it is restored to Pictures/Restored.
        val files = trashDir.listFiles().orEmpty()
        for (file in files) {
            if (!file.isFile || file.name.startsWith(INDEX_NAME) || file.name.startsWith(".")) continue
            if (file.absolutePath in indexedPaths) continue
            val parts = file.name.split('_', limit = 3)
            val trashedAt = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val id = parts.getOrNull(1)?.toLongOrNull() ?: continue
            val name = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: continue
            if (merged.containsKey(id)) continue
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()).orEmpty()
            val restoreDir = File(File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_PICTURES), "Restored")
            merged[id] = TrashedItem(
                id = id,
                originalUriString = "",
                originalPath = File(restoreDir, name).absolutePath,
                originalBucketId = 0L,
                originalBucketName = "Restored",
                originalRelativePath = "${Environment.DIRECTORY_PICTURES}/Restored/",
                displayName = name,
                mimeType = mime,
                sizeBytes = file.length(),
                dateTakenMs = file.lastModified(),
                trashedAtMs = trashedAt,
                trashFilePath = file.absolutePath,
                sourceRemoved = true,
                trashUriString = contentUriForTrash(file),
            )
            indexedPaths += file.absolutePath
            recovered++
        }
        if (recovered > 0) AppLog.w("TrashManager", "Recovered $recovered trash file(s) without an index entry")
        return recovered
    }

    /**
     * Entries of one index file. A file that can't be read as a whole is set aside (renamed, not
     * deleted) so the next save does not overwrite the only record of what is in the trash;
     * a single bad entry is skipped.
     */
    private fun readIndex(file: File): List<TrashedItem> {
        if (!file.exists()) return emptyList()
        if (file.length() > MAX_INDEX_BYTES) {
            AppLog.w("TrashManager", "Trash index ${file.path} is ${file.length()} bytes; set aside")
            setAside(file, "oversize")
            return emptyList()
        }
        // A read error is not an empty index: retried, then the file is set aside like a corrupt
        // one, or the next save would write only the new entries over it.
        var text: String? = null
        for (attempt in 0 until 3) {
            text = runCatching { file.readText() }.getOrNull()
            if (text != null) break
            Thread.sleep(50L)
        }
        if (text == null) {
            AppLog.w("TrashManager", "Trash index ${file.path} could not be read; set aside")
            setAside(file, "unreadable")
            return emptyList()
        }
        if (text.isBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrElse {
            AppLog.w("TrashManager", "Trash index ${file.path} unreadable; set aside")
            setAside(file, "corrupt")
            return emptyList()
        }
        return parseEntries(array)
    }

    private fun setAside(file: File, reason: String) {
        runCatching { file.renameTo(File(file.parentFile, "${file.name}.$reason-${System.currentTimeMillis()}")) }
    }

    /**
     * Writes the index; false when it could not be written. The list is shown either way: the
     * files are in the trash folder, and the next load finds them again (see [recoverUnindexed]).
     */
    private fun saveInternal(list: List<TrashedItem>): Boolean {
        val array = JSONArray()
        list.forEach { item ->
            array.put(item.toJson())
        }
        val parent = indexFile.parentFile ?: trashDir
        if (!parent.exists()) parent.mkdirs()

        val tempFile = File(parent, "$INDEX_NAME.tmp")
        try {
            java.io.FileOutputStream(tempFile).use { out ->
                // Without indentation: a third of the size, so a big trash stays under the limit.
                out.write(array.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tempFile.renameTo(indexFile)) {
                tempFile.copyTo(indexFile, overwrite = true)
                tempFile.delete()
            }
            _indexedItems.value = list
            return true
        } catch (e: Exception) {
            dev.ander.alexet.data.AppLog.e("TrashManager", "Failed to save trash index", e)
            _indexedItems.value = list
            return false
        }
    }

    private fun contentUriForTrash(file: File): String =
        runCatching {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            ).toString()
        }.getOrDefault("")

    /** Canonical paths of the trash folders; worked out once per load, not once per entry. */
    private fun trashRootPaths(): List<String> =
        (listOf(trashDir) + otherTrashDirs()).mapNotNull { root -> runCatching { root.canonicalFile.path }.getOrNull() }

    private fun isUnderTrashRoot(file: File, rootPaths: List<String> = trashRootPaths()): Boolean {
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return rootPaths.any { root ->
            candidate.path == root || candidate.path.startsWith(root + File.separator)
        }
    }

    private fun parseEntries(array: JSONArray): List<TrashedItem> {
        val list = mutableListOf<TrashedItem>()
        val rootPaths = trashRootPaths()
        for (i in 0 until array.length()) {
            val entry = runCatching { parseEntry(array.getJSONObject(i), rootPaths) }
                .onFailure { AppLog.w("TrashManager", "Skip unreadable trash index entry $i: ${it.message}") }
                .getOrNull()
            if (entry != null) list += entry
        }
        return list
    }

    private fun parseEntry(obj: org.json.JSONObject, rootPaths: List<String>): TrashedItem? {
        val trashPath = obj.getString("trashFilePath")
        val targetFile = File(trashPath)
        if (!isUnderTrashRoot(targetFile, rootPaths)) {
            AppLog.w("TrashManager", "Skip uncontained trash index entry: $trashPath")
            return null
        }
        return TrashedItem(
            id = obj.getLong("id"),
            originalUriString = obj.optString("originalUriString"),
            originalPath = obj.getString("originalPath"),
            originalBucketId = obj.optLong("originalBucketId"),
            originalBucketName = obj.getString("originalBucketName"),
            originalRelativePath = obj.getString("originalRelativePath"),
            displayName = obj.getString("displayName"),
            mimeType = obj.getString("mimeType"),
            sizeBytes = obj.getLong("sizeBytes"),
            dateTakenMs = obj.getLong("dateTakenMs"),
            trashedAtMs = obj.getLong("trashedAtMs"),
            trashFilePath = trashPath,
            sourceRemoved = obj.optBoolean("sourceRemoved", true),
            trashUriString = obj.optString("trashUriString").ifBlank { contentUriForTrash(targetFile) },
        )
    }

    private fun purgeExpiredInternal(): List<TrashedItem> {
        val current = _indexedItems.value
        val expired = current.filter { it.isExpired }
        if (expired.isEmpty()) return emptyList()
        val actuallyPurged = mutableListOf<TrashedItem>()
        val rootPaths = trashRootPaths()
        expired.forEach { item ->
            val file = File(item.trashFilePath)
            if (!isUnderTrashRoot(file, rootPaths)) {
                dev.ander.alexet.data.AppLog.w("TrashManager", "Skip purge delete outside trash root: ${item.trashFilePath}")
                actuallyPurged.add(item)
            } else {
                val ok = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
                if (ok) {
                    actuallyPurged.add(item)
                }
            }
        }
        val remaining = current.filterNot { it in actuallyPurged }
        saveInternal(remaining)
        return actuallyPurged
    }

    suspend fun purgeExpired(): List<TrashedItem> = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureLoadedLocked()
            purgeExpiredInternal()
        }
    }

    private fun copyStreamToFile(input: java.io.InputStream, target: File): Boolean {
        return try {
            input.use { inp ->
                target.outputStream().use { out -> inp.copyTo(out, bufferSize = 131072) }
            }
            target.exists() && target.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    private fun discardUnindexedTrashCopy(targetFile: File) {
        if (!isUnderTrashRoot(targetFile)) {
            dev.ander.alexet.data.AppLog.w("TrashManager", "Skip discard outside trash root: ${targetFile.path}")
            return
        }
        if (targetFile.exists()) {
            runCatching { targetFile.delete() }
        }
    }

    suspend fun moveToTrash(
        mediaItems: List<GalleryMedia>,
        fileResolver: (GalleryMedia) -> File? = { null },
    ): List<TrashedItem> = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureLoadedLocked()
            if (mediaItems.isEmpty()) return@withContext emptyList()
            val current = _indexedItems.value.toMutableList()
            val trashed = ArrayList<TrashedItem>()
            val now = System.currentTimeMillis()
            val trashRoot = trashDir.canonicalFile
            writePendingJournal(mediaItems, now, fileResolver)

            try {
                for (item in mediaItems) {
                    try {
                        val srcFile = item.absolutePath?.let { File(it) } ?: fileResolver(item)
                        val safeDisplayName = sanitizeFileName(item.displayName)
                        val targetName = "${now}_${item.id}_${safeDisplayName}"
                        val targetFile = File(trashDir, targetName)
                        val targetCanonical = runCatching { targetFile.canonicalFile }.getOrNull()
                        if (targetCanonical == null ||
                            (targetCanonical != trashRoot &&
                                !targetCanonical.path.startsWith(trashRoot.path + File.separator))
                        ) {
                            continue
                        }

                        var moved = false
                        var sourceRemoved = false
                        if (srcFile != null && srcFile.exists()) {
                            moved = srcFile.renameTo(targetFile)
                            if (moved) {
                                sourceRemoved = true
                            } else {
                                val copyOk = runCatching {
                                    srcFile.inputStream().use { inp -> copyStreamToFile(inp, targetFile) }
                                }.getOrDefault(false)
                                if (copyOk) {
                                    val deleted = runCatching { srcFile.delete() }.getOrDefault(false)
                                    if (!deleted && srcFile.exists()) {
                                        // Scoped Storage fallback: File.delete() may fail for non-owned files
                                        val uriDeleted = runCatching {
                                            appContext.contentResolver.delete(item.uri, null, null) > 0
                                        }.getOrDefault(false)
                                        moved = true
                                        sourceRemoved = (uriDeleted || !srcFile.exists())
                                    } else {
                                        moved = true
                                        sourceRemoved = (deleted || !srcFile.exists())
                                    }
                                } else {
                                    discardUnindexedTrashCopy(targetFile)
                                }
                            }
                        } else {
                            val copyOk = runCatching {
                                appContext.contentResolver.openInputStream(item.uri)?.use { inp ->
                                    copyStreamToFile(inp, targetFile)
                                } ?: false
                            }.getOrDefault(false)
                            if (copyOk) {
                                moved = true
                                sourceRemoved = false
                            } else {
                                discardUnindexedTrashCopy(targetFile)
                            }
                        }

                        val absOriginalPath = srcFile?.absolutePath ?: run {
                            val rel = item.relativePath.trim().trim('/')
                            File(
                                Environment.getExternalStorageDirectory(),
                                if (rel.isEmpty()) item.displayName else "$rel/${item.displayName}",
                            ).absolutePath
                        }

                        if (moved && targetFile.exists()) {
                            val trashedItem = TrashedItem(
                                id = item.id,
                                originalUriString = item.uri.toString(),
                                originalPath = absOriginalPath,
                                originalBucketId = item.bucketId,
                                originalBucketName = item.bucketName,
                                originalRelativePath = item.relativePath,
                                displayName = item.displayName,
                                mimeType = item.mimeType,
                                sizeBytes = if (item.sizeBytes > 0L) item.sizeBytes else targetFile.length(),
                                dateTakenMs = item.dateTakenMs,
                                trashedAtMs = now,
                                trashFilePath = targetFile.absolutePath,
                                sourceRemoved = sourceRemoved,
                                trashUriString = contentUriForTrash(targetFile),
                            )
                            trashed += trashedItem
                            current += trashedItem
                        } else {
                            discardUnindexedTrashCopy(targetFile)
                        }
                    } catch (e: Exception) {
                        // One file failing (I/O error, odd path) does not cost the entries of the others.
                        AppLog.e("TrashManager", "Failed to move ${item.displayName} to trash", e)
                    }
                }
            } finally {
                if (saveInternal(current)) pendingJournal.delete()
            }
            trashed
        }
    }

    /**
     * The batch's entries as they will be, before any file moves: if the process dies before the
     * index is written, the next load adds the files that made it (see [recoverUnindexed]).
     */
    private fun writePendingJournal(
        mediaItems: List<GalleryMedia>,
        now: Long,
        fileResolver: (GalleryMedia) -> File?,
    ) {
        runCatching {
            val array = JSONArray()
            for (item in mediaItems) {
                val target = File(trashDir, "${now}_${item.id}_${sanitizeFileName(item.displayName)}")
                val src = item.absolutePath?.let { File(it) } ?: runCatching { fileResolver(item) }.getOrNull()
                val original = src?.absolutePath ?: run {
                    val rel = item.relativePath.trim().trim('/')
                    File(
                        Environment.getExternalStorageDirectory(),
                        if (rel.isEmpty()) item.displayName else "$rel/${item.displayName}",
                    ).absolutePath
                }
                array.put(
                    TrashedItem(
                        id = item.id,
                        originalUriString = item.uri.toString(),
                        originalPath = original,
                        originalBucketId = item.bucketId,
                        originalBucketName = item.bucketName,
                        originalRelativePath = item.relativePath,
                        displayName = item.displayName,
                        mimeType = item.mimeType,
                        sizeBytes = item.sizeBytes,
                        dateTakenMs = item.dateTakenMs,
                        trashedAtMs = now,
                        trashFilePath = target.absolutePath,
                        sourceRemoved = true,
                        trashUriString = "",
                    ).toJson(),
                )
            }
            java.io.FileOutputStream(pendingJournal).use { out ->
                out.write(array.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        }.onFailure { AppLog.w("TrashManager", "Could not write the trash journal: ${it.message}") }
    }

    private fun restoreOne(item: TrashedItem): Pair<File, Boolean>? {
        val trashFile = File(item.trashFilePath)
        if (!isUnderTrashRoot(trashFile)) {
            dev.ander.alexet.data.AppLog.w("TrashManager", "Skip restore: trash path outside trash root: ${item.trashFilePath}")
            return null
        }
        if (!trashFile.exists()) return null
        val original = File(item.originalPath)
        val extRoot = Environment.getExternalStorageDirectory()
        // Any mounted storage volume (internal or SD card), not only the primary one.
        val volumeRoots = buildList {
            extRoot?.let { add(it) }
            appContext.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
                // .../<volume>/Android/data/<pkg>/files -> .../<volume>
                appDir.parentFile?.parentFile?.parentFile?.parentFile?.let { add(it) }
            }
        }.mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.distinct()
        val originalCanonical = runCatching { original.canonicalPath }.getOrNull()
        val isSafePath = !item.originalPath.contains("..") && originalCanonical != null &&
            volumeRoots.any { root -> originalCanonical.startsWith("$root/") }
        val effectiveOriginal = if (isSafePath) {
            original
        } else {
            File(File(extRoot ?: appContext.filesDir, Environment.DIRECTORY_PICTURES), "Restored/${original.name.ifBlank { item.displayName }}")
        }
        val destDir = effectiveOriginal.parentFile ?: return null
        if (!destDir.exists()) destDir.mkdirs()
        val destFile = resolveUniqueTargetFile(destDir, effectiveOriginal.name.ifBlank { item.displayName })

        var actualDestFile = destFile
        ownWriteListener?.invoke(destFile, false)
        var ok = trashFile.renameTo(destFile)
        var restoredViaInsert = false
        if (!ok) {
            ok = runCatching {
                val copied = trashFile.inputStream().use { inp -> copyStreamToFile(inp, destFile) }
                if (copied) {
                    trashFile.delete()
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            // A copy that failed part-way is not a restored file: remove it (the trash copy stays).
            // destFile is a fresh unique name, so whatever is there came from this copy.
            if (!ok && destFile.exists()) destFile.delete()
        }

        if (!ok && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ok = runCatching {
                val mime = item.mimeType.ifBlank { if (item.isVideo) "video/mp4" else "image/jpeg" }
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, destFile.name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    val rel = item.originalRelativePath.ifBlank { if (item.isVideo) "Movies" else "Pictures" }
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (item.isVideo) {
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val outUri = appContext.contentResolver.insert(collection, values) ?: return@runCatching false
                // A copy that throws part-way must not leave a hidden, partial pending row behind.
                val copied = try {
                    appContext.contentResolver.openOutputStream(outUri)?.use { out ->
                        trashFile.inputStream().use { inp -> inp.copyTo(out, bufferSize = 131072) }
                        true
                    } ?: false
                } catch (e: Exception) {
                    runCatching { appContext.contentResolver.delete(outUri, null, null) }
                    throw e
                }
                if (copied) {
                    val done = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    appContext.contentResolver.update(outUri, done, null, null)
                    trashFile.delete()
                    restoredViaInsert = true
                    val queriedPath = runCatching {
                        appContext.contentResolver.query(
                            outUri,
                            arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val col = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                                if (col >= 0) cursor.getString(col) else null
                            } else null
                        }
                    }.getOrNull()
                    if (!queriedPath.isNullOrBlank()) {
                        actualDestFile = File(queriedPath)
                    }
                    true
                } else {
                    appContext.contentResolver.delete(outUri, null, null)
                    false
                }
            }.getOrDefault(false)
        }

        ownWriteListener?.invoke(actualDestFile, true)
        return if (ok && (actualDestFile.exists() || restoredViaInsert)) {
            actualDestFile to restoredViaInsert
        } else {
            null
        }
    }

    private fun deleteOne(item: TrashedItem): Boolean {
        val file = File(item.trashFilePath)
        if (!isUnderTrashRoot(file)) {
            dev.ander.alexet.data.AppLog.w("TrashManager", "Skip permanent delete outside trash root: ${item.trashFilePath}")
            // Drop poisoned index entry without deleting outside trash.
            return true
        }
        return if (!file.exists() || file.delete()) true else false
    }

    suspend fun restore(itemsToRestore: List<TrashedItem>): List<File> = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureLoadedLocked()
            restoreInternal(itemsToRestore)
        }
    }

    private fun restoreInternal(itemsToRestore: List<TrashedItem>): List<File> {
        if (itemsToRestore.isEmpty()) return emptyList()
        val current = _indexedItems.value.toMutableList()
        val restoredFiles = ArrayList<File>()
        val restoredIds = HashSet<Long>()

        for (item in itemsToRestore) {
            val result = restoreOne(item) ?: continue
            restoredFiles += result.first
            restoredIds += item.id
        }

        if (restoredIds.isNotEmpty()) {
            current.removeAll { it.id in restoredIds }
            saveInternal(current)
        }
        _pendingDeleteIds.update { it - itemsToRestore.map { item -> item.id }.toSet() }

        if (restoredFiles.isNotEmpty()) {
            val pathsToScan = restoredFiles.filter { it.exists() }.map { it.absolutePath }.toTypedArray()
            if (pathsToScan.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    appContext,
                    pathsToScan,
                    null,
                    null,
                )
            }
        }
        return restoredFiles
    }

    suspend fun deletePermanently(itemsToDelete: List<TrashedItem>): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureLoadedLocked()
            deletePermanentlyInternal(itemsToDelete)
        }
    }

    private fun deletePermanentlyInternal(itemsToDelete: List<TrashedItem>): Int {
        if (itemsToDelete.isEmpty()) return 0
        val current = _indexedItems.value.toMutableList()
        val deletedIds = HashSet<Long>()
        var deletedCount = 0

        for (item in itemsToDelete) {
            if (deleteOne(item)) {
                deletedCount++
                deletedIds += item.id
            }
        }

        if (deletedIds.isNotEmpty()) {
            current.removeAll { it.id in deletedIds }
            saveInternal(current)
        }
        _pendingDeleteIds.update { it - itemsToDelete.map { item -> item.id }.toSet() }
        return deletedCount
    }

    /**
     * MediaStore delete was denied. Restore copies whose source was already removed;
     * drop trash copies that were URI-copied while the original stayed on disk.
     */
    suspend fun rollbackDeniedDelete(items: List<TrashedItem>): List<File> = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureLoadedLocked()
            if (items.isEmpty()) return@withContext emptyList()
            val toRestore = ArrayList<TrashedItem>()
            val toDrop = ArrayList<TrashedItem>()
            for (item in items) {
                val originalExists = item.originalPath.isNotBlank() && File(item.originalPath).exists()
                if (shouldRestoreTrashOnDeny(item.sourceRemoved, originalExists)) {
                    toRestore += item
                } else {
                    toDrop += item
                }
            }
            val restored = restoreInternal(toRestore)
            deletePermanentlyInternal(toDrop)
            restored
        }
    }

    private companion object {
        const val INDEX_NAME = "trash_index.json"
        /** About 60k entries; larger is not a real index. */
        const val MAX_INDEX_BYTES = 32L * 1024L * 1024L
    }
}
