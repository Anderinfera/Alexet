package dev.ander.alexet.integration

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import dev.ander.alexet.data.DownloaderState

/** Reads the progress of downloads from the downloader app (PipePipe Andercoded, the PipePipe fork). */
object DownloaderProgressClient {
    /** PipePipe Andercoded, the published release build (https://github.com/Anderinfera/PipePipeClient). */
    private val PACKAGES = listOf("dev.ander.pipepipe")

    data class Row(
        val relativePath: String,
        val bytes: Long,
        val total: Long,
        val state: DownloaderState,
    )

    fun missionsUris(): List<Uri> = PACKAGES.map { Uri.parse("content://$it.downloadprogress/missions") }

    /**
     * Newer builds only answer apps the user allowed. Only the permissions the installed builds
     * declare and that are not granted yet: asking for an unknown one does nothing.
     */
    fun missingPermissions(context: Context): List<String> {
        val pm = context.packageManager
        return PACKAGES.map { "$it.permission.READ_DOWNLOAD_PROGRESS" }.filter { permission ->
            val declared = runCatching { pm.getPermissionInfo(permission, 0) }.isSuccess
            declared && ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    /** Starts the downloader's process when it isn't running: off the main thread. Null when no build can be asked. */
    fun read(context: Context): List<Row>? {
        val all = missionsUris().map { read(context, it) }
        if (all.all { it == null }) return null
        return all.flatMap { it.orEmpty() }
    }

    private fun read(context: Context, uri: Uri): List<Row>? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val pathIdx = cursor.getColumnIndex("relativePath")
            val bytesIdx = cursor.getColumnIndex("bytes")
            val totalIdx = cursor.getColumnIndex("total")
            val stateIdx = cursor.getColumnIndex("state")
            if (pathIdx < 0 || bytesIdx < 0 || totalIdx < 0 || stateIdx < 0) return@use null
            val rows = ArrayList<Row>(cursor.count)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathIdx)?.takeIf { it.isNotBlank() } ?: continue
                val state = DownloaderState.fromName(cursor.getString(stateIdx)) ?: continue
                rows += Row(path, cursor.getLong(bytesIdx), cursor.getLong(totalIdx), state)
            }
            rows
        }
    } catch (_: Exception) {
        // Not installed, refused or gone mid-read: the same as nothing known.
        null
    }
}
