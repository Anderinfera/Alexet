package dev.ander.alexet.ui.components

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import dev.ander.alexet.data.DownloadsWatchLogic
import dev.ander.alexet.data.SecureFolderManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

data class MediaThumb(
    val uri: Uri,
    val id: Long,
    val isVideo: Boolean,
    val path: String? = null,
    val generation: Int = 0,
    /** Upper bound of the decoded size: grid tiles stay small, the viewer asks for more. */
    val maxPx: Int = 512,
    /** High-resolution thumbnails: decoded from the file, MediaStore's small JPEG only as a fallback. */
    val highRes: Boolean = false,
)

/**
 * Durations read while making video thumbnails, for files MediaStore could not parse
 * (see [dev.ander.alexet.data.Mp4MetaRepair]): the tile can still show the length.
 */
object VideoDurationCache {
    private val durations = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    fun get(id: Long): Long = durations[id] ?: 0L

    /** Ids were reassigned (MediaStore database replaced). */
    fun clear() = durations.clear()

    internal fun record(id: Long, retriever: MediaMetadataRetriever) {
        if (durations.containsKey(id)) return
        val ms = runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }.getOrNull() ?: return
        if (ms > 0L) durations[id] = ms
    }
}

class MediaStoreThumbnailFetcher(
    private val data: MediaThumb,
    private val options: Options,
    private val diskCache: Lazy<coil3.disk.DiskCache?>,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val requested = maxOf(
            options.size.width.pxOrElse { 256 },
            options.size.height.pxOrElse { 256 },
        ).coerceIn(96, data.maxPx.coerceAtLeast(96))
        // Thumbnails are kept on disk: the next start reads the finished picture instead of
        // decoding the original again (one Alexet made) or asking MediaStore for it (its own
        // thumbnail, kept byte for byte). Never for private files (their pictures must not be
        // written anywhere).
        val diskKey = options.diskCacheKey
            ?.takeIf { data.path?.endsWith(SecureFolderManager.ENC_EXTENSION) != true }
        val cache = if (diskKey != null) diskCache.value else null
        if (cache != null && diskKey != null && options.diskCachePolicy.readEnabled) {
            val cached = withContext(Dispatchers.IO) { ThumbDiskCache.read(cache, diskKey, data.isVideo, requested) }
            if (cached != null) return result(cached)
        }
        val signal = android.os.CancellationSignal()
        val loaded = try {
            coroutineScope {
                val job = coroutineContext.job
                job.invokeOnCompletion { if (job.isCancelled) signal.cancel() }
                withContext(Dispatchers.IO) {
                    // MediaStore thumbnails are small: the viewer goes to the file directly.
                    loadThumbnail(options.context, data, requested, signal, skipStoreThumb = data.highRes || data.generation > 0 || requested > 512)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } ?: return null
        if (loaded.cacheable && cache != null && diskKey != null && options.diskCachePolicy.writeEnabled) {
            when {
                loaded.madeHere -> ThumbDiskCache.write(cache, diskKey, loaded.bitmap)
                loaded.storeRaw != null -> ThumbDiskCache.writeStore(cache, diskKey, loaded.storeRaw)
            }
        }
        return result(loaded.bitmap)
    }

    private fun result(bitmap: Bitmap) = ImageFetchResult(
        image = bitmap.asImage(),
        // The thumbnail's size is chosen here: Coil must not drop a smaller one (video, PDF) from
        // its memory cache and load it again whenever a tile restarts its request.
        isSampled = false,
        dataSource = DataSource.DISK,
    )

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(
            data: MediaThumb,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = MediaStoreThumbnailFetcher(data, options, lazy { imageLoader.diskCache })
    }
}

/**
 * A thumbnail and whether Alexet made it itself (from the file or a frame), not MediaStore; for
 * MediaStore's, the file it came from ([storeRaw], when read so).
 */
private class LoadedThumb(
    val bitmap: Bitmap,
    val madeHere: Boolean,
    val storeRaw: StoreRaw? = null,
    /**
     * False when the next try may give another picture (MediaStore too slow this time, a video's
     * plain frame when its cover could not be read): shown, but not kept.
     */
    val cacheable: Boolean = true,
)

/** MediaStore's thumbnail file as it gave it, and the turn it asked for. */
private class StoreRaw(val bytes: ByteArray, val orientation: Int)

private class StoreLoaded(val bitmap: Bitmap, val raw: StoreRaw?)

/** ContentResolver's (hidden) extra with the turn a thumbnail still needs. */
private const val EXTRA_ORIENTATION = "android.content.extra.ORIENTATION"

/**
 * MediaStore's thumbnail decoded as ContentResolver.loadThumbnail decodes it (software bitmap,
 * shrunk by a whole factor to [sizePx], turned by its orientation): the same picture, whether its
 * file came from MediaStore now or from the disk cache.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private fun decodeStoreThumb(raw: StoreRaw, sizePx: Int): Bitmap? {
    val decoded = runCatching {
        android.graphics.ImageDecoder.decodeBitmap(
            android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(raw.bytes)),
        ) { decoder, info, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val sample = maxOf(info.size.width / sizePx, info.size.height / sizePx)
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
    }.getOrNull() ?: return null
    if (raw.orientation == 0) return decoded
    val m = android.graphics.Matrix().apply {
        setRotate(raw.orientation.toFloat(), decoded.width / 2f, decoded.height / 2f)
    }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, false)
}

/**
 * Thumbnails kept in Coil's disk cache (same keys, size limit and clearing as the rest of it):
 * - one Alexet decoded itself, losslessly (WebP lossless, PNG before Android 11): the same
 *   pixels come back, with a small decode instead of the original's full one (a 12 MP JPEG, a
 *   video's cover);
 * - MediaStore's, its file byte for byte (decoded as before): no call to MediaStore.
 * Written on a background thread after the tile already shows the picture. The metadata file
 * tells the kinds apart.
 */
private object ThumbDiskCache {
    private const val KIND_STORE = "store"
    /** A picture Alexet made, kept losslessly (the same pixels come back). */
    private const val KIND_MADE = "lossless"
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "alexet-thumb-store").apply { isDaemon = true }
    }

    fun read(cache: coil3.disk.DiskCache, key: String, isVideo: Boolean, sizePx: Int): Bitmap? {
        val snapshot = runCatching { cache.openSnapshot(key) }.getOrNull() ?: return null
        val bitmap = snapshot.use {
            val kind = runCatching { File(it.metadata.toString()).readText() }.getOrNull().orEmpty()
            if (kind.startsWith(KIND_STORE)) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@use null
                val orientation = kind.substringAfter(' ', "0").trim().toIntOrNull() ?: 0
                runCatching { decodeStoreThumb(StoreRaw(File(it.data.toString()).readBytes(), orientation), sizePx) }.getOrNull()
            } else if (kind.startsWith(KIND_MADE)) {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = if (isVideo) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
                    inDither = false
                }
                runCatching { BitmapFactory.decodeFile(it.data.toString(), opts) }.getOrNull()
            } else {
                // Kept by an earlier version (lossy): made again.
                null
            }
        }
        // Unreadable (cut short, or not ours): made again next time.
        if (bitmap == null) runCatching { cache.remove(key) }
        return bitmap
    }

    fun write(cache: coil3.disk.DiskCache, key: String, bitmap: Bitmap) {
        writer.execute {
            val editor = runCatching { cache.openEditor(key) }.getOrNull() ?: return@execute
            val ok = runCatching {
                File(editor.metadata.toString()).writeText(KIND_MADE)
                java.io.FileOutputStream(editor.data.toString()).use { out ->
                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSLESS
                    } else {
                        Bitmap.CompressFormat.PNG
                    }
                    bitmap.compress(format, 100, out)
                }
            }.getOrDefault(false)
            runCatching { if (ok) editor.commit() else editor.abort() }
        }
    }

    fun writeStore(cache: coil3.disk.DiskCache, key: String, raw: StoreRaw) {
        writer.execute {
            val editor = runCatching { cache.openEditor(key) }.getOrNull() ?: return@execute
            val ok = runCatching {
                File(editor.metadata.toString()).writeText("$KIND_STORE ${raw.orientation}")
                File(editor.data.toString()).writeBytes(raw.bytes)
                true
            }.getOrDefault(false)
            runCatching { if (ok) editor.commit() else editor.abort() }
        }
    }
}

private suspend fun loadThumbnail(
    context: Context,
    data: MediaThumb,
    sizePx: Int,
    signal: android.os.CancellationSignal? = null,
    skipStoreThumb: Boolean = false,
): LoadedThumb? {
    if (data.path?.endsWith(SecureFolderManager.ENC_EXTENSION) == true) {
        val file = File(data.path)
        if (file.exists()) {
            if (signal?.isCanceled == true) return null
            // Each private tile decrypts its whole file: a few at a time, or a fast fling allocates
            // dozens of full-size copies at once.
            // Suspends (no IO thread held) until a permit comes; a tile scrolled away is cancelled
            // and leaves the queue.
            val waitFrom = android.os.SystemClock.elapsedRealtime()
            encryptedDecodeSemaphore.acquire()
            val startedAt = android.os.SystemClock.elapsedRealtime()
            try {
                if (signal?.isCanceled == true) return null
                if (data.isVideo) {
                    if (file.length() <= 48L * 1024L * 1024L) {
                        val key = SecureFolderManager.getActiveKeyForFile(file)
                        if (key != null) {
                            val bytes = dev.ander.alexet.data.SecureRamCache.getOrDecrypt(file, key, cache = false)
                            if (bytes != null) {
                                val bmp = try {
                                    videoFrameFromBytes(bytes, sizePx, data.id)
                                } finally {
                                    java.util.Arrays.fill(bytes, 0.toByte())
                                }
                                if (bmp != null) return LoadedThumb(bmp, madeHere = false)
                            }
                        }
                    }
                } else {
                    val bmp = decodeSampledEncryptedFile(file, sizePx)
                    if (bmp != null) return LoadedThumb(bmp, madeHere = false)
                }
            } finally {
                encryptedDecodeSemaphore.release()
                dev.ander.alexet.data.AppLog.event("Secure") {
                    "thumbnail ${file.name}: waited ${startedAt - waitFrom} ms, decrypt + decode " +
                        "${android.os.SystemClock.elapsedRealtime() - startedAt} ms"
                }
            }
        }
        return null
    }
    val resolver = context.contentResolver
    var storeRaw: StoreRaw? = null
    // MediaStore too slow this time: what is shown instead may differ from what it gives next
    // time, so it is not kept. (A file MediaStore has no thumbnail for gets the same every time.)
    var storeTimedOut = false
    val storeThumb: suspend () -> Bitmap? = {
        loadStoreThumbnailBounded(resolver, data.uri, data.id, data.isVideo, sizePx, signal) { storeTimedOut = true }
            ?.let { loaded -> storeRaw = loaded.raw; loaded.bitmap }
    }
    var videoFallback = false
    val fileBitmap: suspend () -> Bitmap? = {
        data.path?.let { path ->
            val f = java.io.File(path)
            if (f.exists()) {
                if (data.isVideo) {
                    videoFrameFromFile(context, path, sizePx, data.id, signal) { videoFallback = true }
                } else {
                    // BitmapFactory ignores EXIF orientation (MediaStore thumbnails don't).
                    decodeSampledFromFile(f, sizePx)?.let { orientByExif(it) { android.media.ExifInterface(path) } }
                }
            } else null
        }
    }
    val decoded: suspend () -> Bitmap? = {
        if (data.isVideo) {
            videoFrame(context, data.uri, sizePx, data.id)
        } else {
            decodeSampled(resolver, data.uri, sizePx)?.let { bmp ->
                orientByExif(bmp) { resolver.openInputStream(data.uri)?.use { android.media.ExifInterface(it) } }
            }
        }
    }

    // A big PNG's system thumbnail has its thin lines broken into dots: made here instead.
    val smoothFirst = !skipStoreThumb && !data.isVideo && data.path?.let { needsSmoothThumb(it, sizePx) } == true
    val order = if (skipStoreThumb || smoothFirst) {
        listOf("file" to fileBitmap, "uri" to decoded, "mediastore" to storeThumb)
    } else {
        listOf("mediastore" to storeThumb, "file" to fileBitmap, "uri" to decoded)
    }
    val failed = ArrayList<String>(3)
    for ((source, load) in order) {
        val bitmap = load()
        if (bitmap != null) {
            if (failed.isNotEmpty()) {
                dev.ander.alexet.data.AppLog.event("Thumb") { "id=${data.id} from $source after failing ${failed.joinToString()} (${data.path})" }
            }
            val cacheable = !storeTimedOut && !videoFallback
            return if (source == "mediastore") LoadedThumb(bitmap, madeHere = false, storeRaw = storeRaw, cacheable = cacheable)
            else LoadedThumb(bitmap, madeHere = true, cacheable = cacheable)
        }
        failed += source
    }
    dev.ander.alexet.data.AppLog.event("Thumb") { "id=${data.id} no thumbnail, failed ${failed.joinToString()} (${data.path})" }
    return null
}

// Suspending, not blocking: tiles waiting for a permit used to park Dispatchers.IO threads (shared
// with settings and repository work) and failed after a fixed wait.
private val fallbackDecodeSemaphore = kotlinx.coroutines.sync.Semaphore(3)
private val encryptedDecodeSemaphore = kotlinx.coroutines.sync.Semaphore(2)

private val storeThumbTimeouts = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "alexet-thumb-timeout").apply { isDaemon = true }
}

private const val STORE_THUMB_TIMEOUT_MS = 1_500L

private fun loadStoreThumbnailBounded(
    resolver: ContentResolver,
    uri: Uri,
    id: Long,
    isVideo: Boolean,
    sizePx: Int,
    outer: android.os.CancellationSignal?,
    onTimedOut: () -> Unit = {},
): StoreLoaded? {
    val signal = android.os.CancellationSignal()
    outer?.setOnCancelListener { signal.cancel() }
    val timeout = storeThumbTimeouts.schedule(
        {
            onTimedOut()
            signal.cancel()
        },
        STORE_THUMB_TIMEOUT_MS,
        java.util.concurrent.TimeUnit.MILLISECONDS,
    )
    return try {
        loadStoreThumbnail(resolver, uri, id, isVideo, sizePx, signal)
    } catch (e: Exception) {
        null
    } finally {
        // The request's signal outlives this load: it must not keep a link to it.
        runCatching { outer?.setOnCancelListener(null) }
        timeout.cancel(false)
    }
}

private fun loadStoreThumbnail(
    resolver: ContentResolver,
    uri: Uri,
    id: Long,
    isVideo: Boolean,
    sizePx: Int,
    signal: android.os.CancellationSignal?,
): StoreLoaded? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // What ContentResolver.loadThumbnail does, keeping the file it reads: the same picture,
        // and its bytes can go to the disk cache unchanged.
        val opts = android.os.Bundle().apply {
            putParcelable(ContentResolver.EXTRA_SIZE, android.graphics.Point(sizePx, sizePx))
        }
        val afd = resolver.openTypedAssetFile(uri, "image/*", opts, signal) ?: return null
        val orientation = afd.extras?.getInt(EXTRA_ORIENTATION, 0) ?: 0
        val bytes = afd.createInputStream().use { it.readBytes() }
        val raw = StoreRaw(bytes, orientation)
        return decodeStoreThumb(raw, sizePx)?.let { StoreLoaded(it, raw) }
    }
    @Suppress("DEPRECATION")
    val kind = if (sizePx <= 96) {
        MediaStore.Images.Thumbnails.MICRO_KIND
    } else {
        MediaStore.Images.Thumbnails.MINI_KIND
    }
    val bitmap = if (isVideo) {
        MediaStore.Video.Thumbnails.getThumbnail(resolver, id, kind, null)
    } else {
        MediaStore.Images.Thumbnails.getThumbnail(resolver, id, kind, null)
    }
    return bitmap?.let { StoreLoaded(it, null) }
}

private fun decodeSampledEncryptedFile(file: File, sizePx: Int): Bitmap? {
    val key = SecureFolderManager.getActiveKeyForFile(file) ?: return null
    // Grid thumbnails read from the RAM cache but do not populate it, so scrolling does not evict the viewer's files
    val bytes = dev.ander.alexet.data.SecureRamCache.getOrDecrypt(file, key, cache = false) ?: return null
    return try {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val plan = thumbDecode(bounds, sizePx)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, plan.options())?.let { plan.finish(it, sizePx) }?.let { bmp ->
                // Encrypted bytes are the camera's file: upright only by its EXIF orientation.
                orientByExif(bmp) { android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)) }
            }
        }.getOrNull()
    } finally {
        java.util.Arrays.fill(bytes, 0.toByte())
    }
}

private suspend fun decodeSampledFromFile(file: java.io.File, sizePx: Int): Bitmap? {
    fallbackDecodeSemaphore.acquire()
    try {
        return runCatching {
            val path = file.absolutePath
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val plan = thumbDecode(bounds, sizePx)
            BitmapFactory.decodeFile(path, plan.options())?.let { plan.finish(it, sizePx) }
        }.getOrNull()
    } finally {
        fallbackDecodeSemaphore.release()
    }
}

/**
 * Frames of ordinary videos, a few at a time: each one opens a MediaMetadataRetriever (a decoder)
 * and a fast fling started dozens at once.
 */
private val videoFrameSemaphore = kotlinx.coroutines.sync.Semaphore(3)

private suspend inline fun <T> withVideoFramePermit(block: () -> T): T? {
    videoFrameSemaphore.acquire()
    try {
        return block()
    } finally {
        videoFrameSemaphore.release()
    }
}

private suspend fun videoFrameFromFile(
    context: Context,
    path: String,
    sizePx: Int,
    id: Long,
    signal: android.os.CancellationSignal? = null,
    onFallback: () -> Unit = {},
): Bitmap? = withVideoFramePermit {
    systemVideoThumbnail(path, sizePx, signal) ?: run {
        onFallback()
        videoFrameFromFileUnbounded(context, path, sizePx, id)
    }
}

/**
 * The frame MediaStore's own thumbnail shows (embedded cover or a representative frame, not the
 * often black first one), made at the requested size; null for files the system cannot parse.
 */
private fun systemVideoThumbnail(path: String, sizePx: Int, signal: android.os.CancellationSignal?): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    if (signal?.isCanceled == true) return null
    return runCatching {
        android.media.ThumbnailUtils.createVideoThumbnail(File(path), Size(sizePx, sizePx), signal)
    }.getOrNull()
}

private fun videoFrameFromFileUnbounded(context: Context, path: String, sizePx: Int, id: Long): Bitmap? {
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            dev.ander.alexet.data.Mp4MetaRepair.setDataSource(retriever, context, path, null)
            VideoDurationCache.record(id, retriever)
            extractVideoFrame(retriever, sizePx)
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()
}

private fun videoFrameFromBytes(bytes: ByteArray, sizePx: Int, id: Long): Bitmap? {
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                retriever.setDataSource(dev.ander.alexet.data.Mp4MetaRepair.dataSourceFor(bytes))
                VideoDurationCache.record(id, retriever)
                extractVideoFrame(retriever, sizePx)
            } else {
                null
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()
}

private suspend fun decodeSampled(resolver: ContentResolver, uri: Uri, sizePx: Int): Bitmap? {
    fallbackDecodeSemaphore.acquire()
    try {
        return runCatching {
            val pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
            if (pfd != null) {
                pfd.use { fd ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                    val plan = thumbDecode(bounds, sizePx)
                    BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, plan.options())?.let { plan.finish(it, sizePx) }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val plan = thumbDecode(bounds, sizePx)
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, plan.options()) }?.let { plan.finish(it, sizePx) }
            }
        }.getOrNull()
    } finally {
        fallbackDecodeSemaphore.release()
    }
}

private suspend fun videoFrame(context: Context, uri: Uri, sizePx: Int, id: Long): Bitmap? = withVideoFramePermit {
    videoFrameUnbounded(context, uri, sizePx, id)
}

private fun videoFrameUnbounded(context: Context, uri: Uri, sizePx: Int, id: Long): Bitmap? {
    return runCatching {
        val retriever = MediaMetadataRetriever()
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            val setOk = runCatching {
                dev.ander.alexet.data.Mp4MetaRepair.setDataSource(retriever, context, null, uri)
                true
            }.getOrDefault(false)

            if (!setOk && uri.scheme == ContentResolver.SCHEME_CONTENT) {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                pfd?.fileDescriptor?.let { fd ->
                    retriever.setDataSource(fd)
                }
            }
            VideoDurationCache.record(id, retriever)
            extractVideoFrame(retriever, sizePx)
        } finally {
            runCatching { pfd?.close() }
            runCatching { retriever.release() }
        }
    }.getOrNull()
}

private fun extractVideoFrame(retriever: MediaMetadataRetriever, sizePx: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val attempts = listOf(
            0L to MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            -1L to MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            0L to MediaMetadataRetriever.OPTION_CLOSEST,
            -1L to MediaMetadataRetriever.OPTION_CLOSEST,
            1_000_000L to MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            1_000_000L to MediaMetadataRetriever.OPTION_CLOSEST,
        )
        for ((timeUs, option) in attempts) {
            val bmp = runCatching {
                retriever.getScaledFrameAtTime(timeUs, option, sizePx, sizePx)
            }.getOrNull()
            if (bmp != null) return bmp
        }
    }

    val unscaledAttempts = listOf(
        -1L to MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        0L to MediaMetadataRetriever.OPTION_CLOSEST,
        1_000_000L to MediaMetadataRetriever.OPTION_CLOSEST,
    )
    for ((timeUs, option) in unscaledAttempts) {
        val raw = runCatching {
            if (timeUs < 0) retriever.frameAtTime else retriever.getFrameAtTime(timeUs, option)
        }.getOrNull()
        if (raw != null) {
            return scaleAndRecycle(raw, sizePx)
        }
    }

    return runCatching { retriever.frameAtTime?.let { scaleAndRecycle(it, sizePx) } }.getOrNull()
}

/** The full-size frame (tens of MB for 4K) is freed at once instead of waiting for the GC. */
private fun scaleAndRecycle(raw: Bitmap, maxDim: Int): Bitmap =
    scaleBitmap(raw, maxDim).also { if (it !== raw) raw.recycle() }

private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
    val width = src.width
    val height = src.height
    if (width <= 0 || height <= 0) return src
    if (width <= maxDim && height <= maxDim) return src
    val ratio = minOf(maxDim.toFloat() / width, maxDim.toFloat() / height)
    val targetW = (width * ratio).toInt().coerceAtLeast(1)
    val targetH = (height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, targetW, targetH, true)
}

private fun sampleSize(width: Int, height: Int, target: Int): Int {
    var sample = 1
    val largest = maxOf(width, height)
    while (largest / sample > target * 2) sample *= 2
    return sample
}

/**
 * Android shrinks PNG, GIF and BMP by taking every n-th pixel (JPEG and WebP average them): in a
 * big drawing the thin lines break into dots. From this sample on, such images are read finer and
 * halved with averaging (see [thumbDecode]).
 */
private const val SMOOTH_FROM_SAMPLE = 8

/** At most this many pixels read for the averaging (about 8 MB as RGB_565). */
private const val SMOOTH_MAX_PIXELS = 4_000_000L

private val SKIPPING_EXTENSIONS = setOf("png", "gif", "bmp")

private fun skipsPixels(mime: String?): Boolean =
    mime == "image/png" || mime == "image/gif" || mime == "image/bmp" || mime == "image/x-ms-bmp"

/** How a thumbnail is read: the sample, and whether it is then halved with averaging. */
private class ThumbDecode(val sample: Int, val smooth: Boolean) {
    fun options() = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }

    /** The decoded [bitmap] at the usual thumbnail size (no larger than twice [target]). */
    fun finish(bitmap: Bitmap, target: Int): Bitmap {
        if (!smooth) return bitmap
        var current = bitmap
        // Each halving with filtering averages 2x2 pixels, so every line is counted.
        while (maxOf(current.width, current.height) > target * 2 && minOf(current.width, current.height) >= 2) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (next !== current) current.recycle()
            current = next
        }
        return current
    }
}

/**
 * The usual sample (about twice [target]); a big PNG, GIF or BMP is read up to 4x finer (within
 * [SMOOTH_MAX_PIXELS]) and halved down with averaging.
 */
private fun thumbDecode(bounds: BitmapFactory.Options, target: Int): ThumbDecode {
    val width = bounds.outWidth
    val height = bounds.outHeight
    val sample = sampleSize(width, height, target)
    if (sample < SMOOTH_FROM_SAMPLE || !skipsPixels(bounds.outMimeType)) return ThumbDecode(sample, false)
    var fine = sample / 4
    while ((width / fine).toLong() * (height / fine) > SMOOTH_MAX_PIXELS && fine < sample) fine *= 2
    return ThumbDecode(fine, fine < sample)
}

/** A big PNG, GIF or BMP at [path]: its system thumbnail would have broken lines. */
private fun needsSmoothThumb(path: String, target: Int): Boolean {
    if (path.substringAfterLast('.', "").lowercase() !in SKIPPING_EXTENSIONS) return false
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(path, bounds) }
    return bounds.outWidth > 0 && bounds.outHeight > 0 && thumbDecode(bounds, target).smooth
}

/** [bitmap] turned upright by its EXIF orientation; the same bitmap when there is none. */
internal fun orientByExif(bitmap: Bitmap, exif: () -> android.media.ExifInterface?): Bitmap {
    val orientation = runCatching {
        exif()?.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
    }.getOrNull() ?: return bitmap
    val ops = dev.ander.alexet.data.ImageEditEngine.exifOrientationOps(orientation)
    if (ops.isEmpty()) return bitmap
    val matrix = android.graphics.Matrix()
    for (op in ops) {
        if (op.rotateDeg != 0f) matrix.postRotate(op.rotateDeg)
        if (op.scaleX != 1f || op.scaleY != 1f) matrix.postScale(op.scaleX, op.scaleY)
    }
    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrNull()?.also { if (it !== bitmap) bitmap.recycle() } ?: bitmap
}
