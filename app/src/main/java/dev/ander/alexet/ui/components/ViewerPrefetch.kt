package dev.ander.alexet.ui.components

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.SecureFolderManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * A photo tapped in the grid starts decoding at once, while the viewer is still being built:
 * the viewer then waits for this one decode (a memory-cache hit afterwards) instead of starting
 * its own only once it is on screen. The request is the viewer's own (same file, key and size),
 * so the picture is the same one.
 */
object ViewerPrefetch {
    private val pending = ConcurrentHashMap<String, Deferred<Unit>>()

    /** The viewer's memory cache key of [media]'s full picture (see MediaThumbnail). */
    fun keyOf(media: GalleryMedia): String = "full-${media.id}-${media.dateModifiedMs}-g0"

    fun start(context: Context, media: GalleryMedia, scope: CoroutineScope) {
        if (media.isVideo || media.isAudio || media.isOtherFile) return
        // Animated pictures are not kept in the memory cache: the viewer decodes those itself.
        if (media.mimeType.equals("image/gif", ignoreCase = true)) return
        val path = media.absolutePath ?: return
        if (path.endsWith(SecureFolderManager.ENC_EXTENSION, ignoreCase = true) ||
            media.displayName.endsWith(SecureFolderManager.ENC_EXTENSION, ignoreCase = true)
        ) return
        val key = keyOf(media)
        if (pending[key]?.isActive == true) return
        val app = context.applicationContext
        val loader = SingletonImageLoader.get(app)
        if (loader.memoryCache?.get(coil3.memory.MemoryCache.Key(key)) != null) return
        val request = ImageRequest.Builder(app)
            .data(File(path))
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .size(Size(2560, 2560))
            .precision(Precision.INEXACT)
            .build()
        val job = scope.async { loader.execute(request); Unit }
        pending[key] = job
        job.invokeOnCompletion { pending.remove(key, job) }
    }

    /** The decode started for [key], if one is running. */
    fun running(key: String): Deferred<Unit>? = pending[key]?.takeIf { it.isActive }
}
