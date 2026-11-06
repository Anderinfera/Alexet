package dev.ander.alexet.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Leftovers of work a killed process could not finish, cleaned up once per process start (when
 * nothing of that work can still be running): an original emptied by an interrupted overwrite,
 * half-written hidden copies, MediaStore rows Alexet inserted whose copy never finished, temp
 * files of private folders and video export temp files.
 */
object StartupRecovery {
    private const val TAG = "StartupRecovery"
    private const val PARTIALS_FILE = "pending_partials.txt"
    private const val EXPORT_TEMP_PREFIX = "alexet_export_"

    private val handler = CoroutineExceptionHandler { _, e -> AppLog.e(TAG, "Uncaught", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
    private val partialsLock = Any()
    @Volatile private var started = false

    fun run(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            runCatching { RenameJournal.recover(app) }.onFailure { AppLog.e(TAG, "Rename recovery failed", it) }
            runCatching { restoreInterruptedOverwrites(app) }.onFailure { AppLog.e(TAG, "Overwrite recovery failed", it) }
            runCatching { deleteLeftoverPartials(app) }.onFailure { AppLog.e(TAG, "Partial cleanup failed", it) }
            runCatching { SecureFolderManager.cleanUpInterrupted() }.onFailure { AppLog.e(TAG, "Private folder cleanup failed", it) }
            runCatching { deleteExportTemps(app) }.onFailure { AppLog.e(TAG, "Export temp cleanup failed", it) }
        }
    }

    // --- Overwrite of an original through its content Uri ("rwt" empties it first) ---------------

    fun overwriteBackupDir(context: Context): File =
        File(context.applicationContext.filesDir, "overwrite_backups").apply { mkdirs() }

    /** Written (and synced) before the original is emptied; removed once the write is done. */
    fun writeOverwriteNote(note: File, uri: Uri, backup: File, newSize: Long) {
        java.io.FileOutputStream(note).use { out ->
            out.write(
                JSONObject()
                    .put("uri", uri.toString())
                    .put("backup", backup.absolutePath)
                    .put("newSize", newSize)
                    .toString()
                    .toByteArray(Charsets.UTF_8),
            )
            out.fd.sync()
        }
    }

    private fun restoreInterruptedOverwrites(context: Context) {
        val dir = File(context.filesDir, "overwrite_backups")
        val notes = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        for (note in notes) {
            val o = runCatching { JSONObject(note.readText()) }.getOrNull()
            if (o == null) {
                note.delete()
                continue
            }
            val uri = Uri.parse(o.optString("uri"))
            val backup = File(o.optString("backup"))
            val newSize = o.optLong("newSize", -1L)
            if (!backup.isFile) {
                note.delete()
                continue
            }
            val size = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull()
            if (size != null && size == newSize && newSize > 0L) {
                // The new content made it before the process died: nothing to undo.
                backup.delete()
                note.delete()
                continue
            }
            val restored = runCatching {
                context.contentResolver.openOutputStream(uri, "rwt")?.use { out ->
                    backup.inputStream().use { it.copyTo(out) }
                } != null
            }.getOrDefault(false)
            if (restored) {
                AppLog.w(TAG, "Wrote the original of $uri back after an interrupted overwrite")
                backup.delete()
                note.delete()
            } else {
                AppLog.e(TAG, "Could not write the original of $uri back; kept at ${backup.path}")
            }
        }
    }

    // --- Unfinished copies: hidden ".name.part" files and inserted MediaStore rows ------------------

    /**
     * Remembered until [donePartial]: a process killed meanwhile leaves the hidden partial file (or
     * the pending MediaStore row) for the next start to remove. Only what is noted here is removed:
     * a pending row of a file written some other way may hold a complete file.
     */
    fun notePartial(context: Context, file: File) = note(context, file.absolutePath)

    fun donePartial(context: Context, file: File) = done(context, file.absolutePath)

    fun notePartial(context: Context, uri: Uri) = note(context, uri.toString())

    fun donePartial(context: Context, uri: Uri) = done(context, uri.toString())

    private fun note(context: Context, entry: String) {
        synchronized(partialsLock) {
            runCatching { File(context.applicationContext.filesDir, PARTIALS_FILE).appendText(entry + "\n") }
        }
    }

    private fun done(context: Context, entry: String) {
        synchronized(partialsLock) {
            val list = File(context.applicationContext.filesDir, PARTIALS_FILE)
            runCatching {
                if (!list.isFile) return@runCatching
                val left = list.readLines().filter { it.isNotBlank() && it != entry }
                if (left.isEmpty()) list.delete() else list.writeText(left.joinToString("\n", postfix = "\n"))
            }
        }
    }

    private fun deleteLeftoverPartials(context: Context) {
        synchronized(partialsLock) {
            val list = File(context.filesDir, PARTIALS_FILE)
            if (!list.isFile) return
            for (entry in list.readLines()) {
                if (entry.isBlank()) continue
                if (entry.startsWith("content://")) {
                    deleteIfPending(context, Uri.parse(entry))
                    continue
                }
                val file = File(entry)
                // Only Alexet's own hidden partial names.
                val ownName = file.name.endsWith(".part") || file.name.endsWith(".alexet_tmp") || file.name.startsWith(".tmp_")
                if (file.name.startsWith(".") && ownName && file.isFile) {
                    AppLog.w(TAG, "Removing an unfinished copy left by a killed process: $entry")
                    file.delete()
                }
            }
            list.delete()
        }
    }

    /** A row Alexet inserted and was still filling (IS_PENDING = 1) when the process died. */
    private fun deleteIfPending(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
        val pending = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), args, null)?.use { c ->
                c.moveToFirst() && c.getInt(0) == 1
            } ?: false
        }.getOrDefault(false)
        if (pending) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            AppLog.w(TAG, "Removed an unfinished MediaStore row of Alexet: $uri")
        }
    }

    // --- Video export temp files in the cache (hundreds of MB each) ---------------------------------

    private fun deleteExportTemps(context: Context) {
        context.cacheDir.listFiles { f -> f.isFile && f.name.startsWith(EXPORT_TEMP_PREFIX) }
            .orEmpty()
            .forEach { it.delete() }
    }
}
