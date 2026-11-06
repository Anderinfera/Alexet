package dev.ander.alexet.integration

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.ander.alexet.data.GalleryMedia

/**
 * Launches Next Player (`new-nextplayer`) with MediaStore video URIs.
 * Matches [dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi] extras.
 */
object NextPlayerClient {
    const val ACTION_BROWSE_GALLERY = "dev.ander.alexet.action.BROWSE"

    private val packages = listOf(
        "dev.ander.anderyer",
        "dev.ander.anderyer.debug",
        "dev.anilbeesetti.nextplayer.debug",
        "dev.anilbeesetti.nextplayer.release",
        "dev.anilbeesetti.nextplayer",
    )

    private const val PLAYER_ACTIVITY =
        "dev.anilbeesetti.nextplayer.feature.player.PlayerActivity"

    private const val API_TITLE = "title"
    private const val API_PLAYLIST = "video_list"
    private const val MAX_PLAYLIST = 24
    private const val CACHE_TTL_MS = 15_000L

    /** Package and check time as one value: two threads checking at once can't pair them wrongly. */
    private class Check(val pkg: String?, val atMs: Long)

    @Volatile
    private var cached = Check(null, 0L)

    fun installedPackage(context: Context, forceRefresh: Boolean = false): String? {
        val started = System.currentTimeMillis()
        val current = cached
        if (!forceRefresh && started - current.atMs < CACHE_TTL_MS) {
            return current.pkg
        }
        val pm = context.packageManager
        val pkg = packages.firstOrNull { p -> packageInstalled(pm, p) }
            ?: packages.firstOrNull { p -> pm.getLaunchIntentForPackage(p) != null }
            ?: resolveNextPlayerPackage(pm)
        synchronized(this) {
            // A slower check that began earlier must not overwrite a newer answer.
            if (started >= cached.atMs) cached = Check(pkg, started)
        }
        return pkg
    }

    fun isInstalled(context: Context, forceRefresh: Boolean = false): Boolean =
        installedPackage(context, forceRefresh) != null

    fun invalidateCache() {
        cached = Check(cached.pkg, 0L)
    }

    fun openLibrary(context: Context): Boolean {
        val pkg = installedPackage(context) ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(launchFlags(context))
        return start(context, launch)
    }

    fun play(
        context: Context,
        start: GalleryMedia,
        playlist: List<GalleryMedia> = listOf(start),
    ): Boolean {
        val videos = playlist.filter { it.isVideo }.ifEmpty { listOf(start) }
        val compact = surround(start, videos, MAX_PLAYLIST)
        val pkg = installedPackage(context)
        val attempts = buildList {
            add(viewIntent(context, start, compact, pkg, explicit = false))
            add(viewIntent(context, start, listOf(start), pkg, explicit = false))
            if (pkg != null) {
                add(viewIntent(context, start, listOf(start), pkg, explicit = true))
            }
            add(viewIntent(context, start, listOf(start), pkg = null, explicit = false))
        }
        return attempts.any { intent ->
            grantRead(context, pkg, intent)
            start(context, intent)
        }
    }

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
        pkg: String?,
        explicit: Boolean,
    ): Intent {
        val mime = start.mimeType.ifBlank { "video/*" }
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
            clipData = clipFor(context, videoUris)
            if (pkg != null) {
                setPackage(pkg)
                if (explicit) setClassName(pkg, PLAYER_ACTIVITY)
            }
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

    private fun resolveNextPlayerPackage(pm: PackageManager): String? {
        val video = Intent(Intent.ACTION_VIEW).apply { type = "video/*" }
        val resolved = queryActivities(pm, video)
        return resolved
            .map { it.activityInfo.packageName }
            .firstOrNull { pkg ->
                pkg in packages || pkg.contains("nextplayer", ignoreCase = true)
            }
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

    private fun packageInstalled(pm: PackageManager, pkg: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
        }.isSuccess
    }

    private fun grantRead(context: Context, pkg: String?, intent: Intent) {
        val uri = intent.data ?: return
        if (pkg != null) {
            runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val clip = intent.clipData ?: return
        if (pkg == null) return
        for (i in 0 until clip.itemCount) {
            val itemUri = clip.getItemAt(i).uri ?: continue
            runCatching {
                context.grantUriPermission(pkg, itemUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
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

    private fun clipFor(context: Context, uris: List<Uri>): ClipData {
        val first = uris.firstOrNull() ?: return ClipData.newPlainText("", "")
        val mime = runCatching { context.contentResolver.getType(first) }.getOrNull() ?: "video/*"
        val clip = ClipData(
            android.content.ClipDescription("media", arrayOf(mime)),
            ClipData.Item(first),
        )
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }
}
