package dev.ander.alexet.integration

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.NomediaScanner

/**
 * Plays videos in the player the phone uses for them: the one set as default, or the system's
 * list of players when none is. Alexet asks for no particular app.
 */
object ExternalPlayer {
    const val ACTION_BROWSE_GALLERY = "dev.ander.alexet.action.BROWSE"

    // Read by players that know them (Next Player and its forks), passed over by the others.
    private const val API_TITLE = "title"
    private const val API_PLAYLIST = "video_list"
    private const val MAX_PLAYLIST = 24
    private const val CACHE_TTL_MS = 15_000L

    /** The answer and when it was found, as one value: two threads can't pair them wrongly. */
    private class Check(val available: Boolean, val atMs: Long)

    @Volatile
    private var cached = Check(false, 0L)

    /** An app other than Alexet plays videos. */
    fun isAvailable(context: Context): Boolean {
        val started = System.currentTimeMillis()
        val current = cached
        if (current.atMs != 0L && started - current.atMs < CACHE_TTL_MS) return current.available
        val probe = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://media/external/video/media/1"), "video/mp4")
        val available = runCatching {
            queryActivities(context.packageManager, probe).any { it.activityInfo.packageName != context.packageName }
        }.getOrDefault(false)
        synchronized(this) {
            // A slower check that began earlier must not overwrite a newer answer.
            if (started >= cached.atMs) cached = Check(available, started)
        }
        return available
    }

    /** False when no app takes the video. */
    fun play(
        context: Context,
        start: GalleryMedia,
        playlist: List<GalleryMedia> = listOf(start),
    ): Boolean {
        val videos = playlist.filter { it.isVideo }.ifEmpty { listOf(start) }
        val compact = surround(start, videos, MAX_PLAYLIST)
        // The folder's videos when the player takes them, else the one video.
        return start(context, viewIntent(context, start, compact)) ||
            (compact.size > 1 && start(context, viewIntent(context, start, listOf(start))))
    }

    /** A video type players accept, even when the system gave the file a wrong one. */
    internal fun videoMime(media: GalleryMedia): String =
        NomediaScanner.mediaMimeOf(media.mimeType, media.displayName)
            ?.takeIf { it.startsWith("video/", ignoreCase = true) }
            ?: "video/*"

    private fun shareableUri(context: Context, media: GalleryMedia): Uri {
        val file = media.absolutePath?.let { java.io.File(it) }
            ?: (if (media.uri.scheme == "file") media.uri.path?.let { java.io.File(it) } else null)
            ?: runCatching {
                if (media.uri.scheme == "content") {
                    context.contentResolver.query(
                        media.uri,
                        arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                        null, null, null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0)?.let { java.io.File(it) } else null
                    }
                } else null
            }.getOrNull()

        if (file != null && file.exists()) {
            val contentUri = runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }.getOrNull()
            if (contentUri != null) return contentUri
        }
        return media.uri
    }

    private fun viewIntent(
        context: Context,
        start: GalleryMedia,
        videos: List<GalleryMedia>,
    ): Intent {
        val mime = videoMime(start)
        val startUri = shareableUri(context, start)
        val videoUris = videos.map { shareableUri(context, it) }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(startUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(launchFlags(context))
            putExtra(API_TITLE, start.displayName)
            if (videoUris.size > 1) {
                putParcelableArrayListExtra(API_PLAYLIST, ArrayList(videoUris))
            }
            // The read grant reaches the other videos through the clip.
            clipData = clipFor(videoUris, mime)
        }
    }

    private fun surround(
        start: GalleryMedia,
        videos: List<GalleryMedia>,
        max: Int,
    ): List<GalleryMedia> {
        if (videos.size <= max) return videos
        val index = videos.indexOfFirst { it.id == start.id }.coerceAtLeast(0)
        val half = max / 2
        val from = (index - half).coerceAtLeast(0)
        val to = (from + max).coerceAtMost(videos.size)
        val sliceStart = (to - max).coerceAtLeast(0)
        return videos.subList(sliceStart, to)
    }

    private fun queryActivities(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

    private fun launchFlags(context: Context): Int {
        var flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (context !is Activity) flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK
        return flags
    }

    private fun clipFor(uris: List<Uri>, mime: String): ClipData {
        val clip = ClipData(ClipDescription("media", arrayOf(mime)), ClipData.Item(uris.first()))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }
}
