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
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val requested = maxOf(
            options.size.width.pxOrElse { 256 },
            options.size.height.pxOrElse { 256 },
        ).coerceIn(96, data.maxPx.coerceAtLeast(96))
        val signal = android.os.CancellationSignal()
        val bitmap = try {
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
        return ImageFetchResult(
            image = bitmap.asImage(),
            // The thumbnail's size is chosen here: Coil must not drop a smaller one (video, PDF) from
            // its memory cache and load it again whenever a tile restarts its request.
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(
            data: MediaThumb,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = MediaStoreThumbnailFetcher(data, options)
    }
}

private suspend fun loadThumbnail(
    context: Context,
    data: MediaThumb,
    sizePx: Int,
    signal: android.os.CancellationSignal? = null,
    skipStoreThumb: Boolean = false,
): Bitmap? {
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
                                if (bmp != null) return bmp
                            }
                        }
                    }
                } else {
                    val bmp = decodeSampledEncryptedFile(file, sizePx)
                    if (bmp != null) return bmp
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
    val storeThumb: suspend () -> Bitmap? = {
        loadStoreThumbnailBounded(resolver, data.uri, data.id, data.isVideo, sizePx, signal)
    }
    val fileBitmap: suspend () -> Bitmap? = {
        data.path?.let { path ->
            val f = java.io.File(path)
            if (f.exists()) {
                if (data.isVideo) {
                    videoFrameFromFile(context, path, sizePx, data.id, signal)
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

    val order = if (skipStoreThumb) {
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
            return bitmap
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
): Bitmap? {
    val signal = android.os.CancellationSignal()
    outer?.setOnCancelListener { signal.cancel() }
    val timeout = storeThumbTimeouts.schedule(
        { signal.cancel() },
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
): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return resolver.loadThumbnail(uri, Size(sizePx, sizePx), signal)
    }
    @Suppress("DEPRECATION")
    val kind = if (sizePx <= 96) {
        MediaStore.Images.Thumbnails.MICRO_KIND
    } else {
        MediaStore.Images.Thumbnails.MINI_KIND
    }
    return if (isVideo) {
        MediaStore.Video.Thumbnails.getThumbnail(resolver, id, kind, null)
    } else {
        MediaStore.Images.Thumbnails.getThumbnail(resolver, id, kind, null)
    }
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
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { bmp ->
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
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(path, opts)
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
): Bitmap? = withVideoFramePermit {
    systemVideoThumbnail(path, sizePx, signal) ?: videoFrameFromFileUnbounded(context, path, sizePx, id)
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
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, opts)
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
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
