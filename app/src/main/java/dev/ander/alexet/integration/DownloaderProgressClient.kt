package dev.ander.alexet.integration

import android.content.Context
import android.net.Uri
import dev.ander.alexet.data.DownloaderState

/** Reads the progress of downloads from the downloader app (NewPipe Enhanced, the PipePipe fork). */
object DownloaderProgressClient {
    private const val AUTHORITY = "InfinityLoop1309.NewPipeEnhanced.downloadprogress"

    data class Row(
        val relativePath: String,
        val bytes: Long,
        val total: Long,
        val state: DownloaderState,
    )

    fun missionsUri(): Uri = Uri.parse("content://$AUTHORITY/missions")

    /** Starts the downloader's process when it isn't running: off the main thread. Null when it can't be asked. */
    fun read(context: Context): List<Row>? = try {
        context.contentResolver.query(missionsUri(), null, null, null, null)?.use { cursor ->
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
