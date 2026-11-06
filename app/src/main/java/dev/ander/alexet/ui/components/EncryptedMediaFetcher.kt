package dev.ander.alexet.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import dev.ander.alexet.data.NomediaScanner
import dev.ander.alexet.data.SecureFolderManager
import dev.ander.alexet.data.SecureRamCache
import java.io.File
import java.util.Arrays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Coil model for a file inside a secure folder. A plain [File] or Uri cannot be used here: Coil's built-in
 * FileMapper / AndroidUriMapper turn them into `coil3.Uri` before fetcher lookup, so the request would be
 * served by the stock file fetcher and the ciphertext would be handed straight to the image decoder.
 */
data class EncryptedFile(val path: String)

class EncryptedMediaFetcher(
    private val file: File,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val targetW = options.size.width.pxOrElse { 2560 }
        val targetH = options.size.height.pxOrElse { 2560 }
        val maxDim = maxOf(targetW, targetH).coerceIn(256, 3840)

        val key = SecureFolderManager.getActiveKeyForFile(file)
        if (key == null) {
            dev.ander.alexet.data.AppLog.e("AlexetSecure", "EncryptedMediaFetcher: no active key for ${file.path}")
            return@withContext null
        }

        val origName = file.name.removeSuffix(SecureFolderManager.ENC_EXTENSION)
        val isVideo = NomediaScanner.mimeOf(File(origName))?.startsWith("video/") == true
        // A still frame of a large video is not worth decrypting it whole in memory (the player
        // decrypts its own copy at the same time): same cap as the grid thumbnails.
        if (isVideo && SecureFolderManager.plaintextSize(file) > MAX_VIDEO_FRAME_SOURCE_BYTES) return@withContext null

        // Two at a time: each one decrypts a whole file and decodes it at screen size; swiping fast
        // through private photos (the pager keeps three pages) ran weak phones out of memory.
        val waitFrom = android.os.SystemClock.elapsedRealtime()
        decodePermits.withPermit {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            decodeLocked(key, isVideo, maxDim).also { result ->
                dev.ander.alexet.data.AppLog.event("Secure") {
                    "viewer ${file.name}: waited ${startedAt - waitFrom} ms, decrypt + decode " +
                        "${android.os.SystemClock.elapsedRealtime() - startedAt} ms${if (result == null) " (failed)" else ""}"
                }
            }
        }
    }

    private fun decodeLocked(key: javax.crypto.SecretKey, isVideo: Boolean, maxDim: Int): FetchResult? {
        // Private copy owned by this fetcher; wiped once decoded
        val bytes = SecureRamCache.getOrDecrypt(file, key, cache = !isVideo)
        if (bytes == null || bytes.isEmpty()) {
            dev.ander.alexet.data.AppLog.e("AlexetSecure", "EncryptedMediaFetcher: failed to decrypt ${file.path}")
            return null
        }

        return try {
            if (isVideo) {
                val bmp = videoFrameFromBytes(bytes, maxDim)
                if (bmp == null) {
                    dev.ander.alexet.data.AppLog.e("AlexetSecure", "EncryptedMediaFetcher: videoFrameFromBytes failed for ${file.path}")
                    return null
                }
                return ImageFetchResult(
                    image = bmp.asImage(),
                    isSampled = true,
                    dataSource = DataSource.DISK,
                )
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                dev.ander.alexet.data.AppLog.e("AlexetSecure", "EncryptedMediaFetcher: decodeBounds failed for ${file.name}, size=${bytes.size}")
                return null
            }

            val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (decoded == null) {
                dev.ander.alexet.data.AppLog.e("AlexetSecure", "EncryptedMediaFetcher: decodeByteArray returned null for ${file.name}")
                return null
            }
            // The encrypted bytes are the camera's file: upright only by its EXIF orientation.
            val bitmap = orientByExif(decoded) { android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)) }

            ImageFetchResult(
                image = bitmap.asImage(),
                isSampled = sample > 1,
                dataSource = DataSource.DISK,
            )
        } finally {
            Arrays.fill(bytes, 0.toByte())
        }
    }

    private fun videoFrameFromBytes(bytes: ByteArray, sizePx: Int): Bitmap? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    retriever.setDataSource(dev.ander.alexet.data.Mp4MetaRepair.dataSourceFor(bytes))
                    extractVideoFrame(retriever, sizePx)
                } else null
            } finally {
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
                1_000_000L to MediaMetadataRetriever.OPTION_CLOSEST,
            )
            for ((timeUs, option) in attempts) {
                val bmp = runCatching {
                    retriever.getScaledFrameAtTime(timeUs, option, sizePx, sizePx)
                }.getOrNull()
                if (bmp != null) return bmp
            }
        }
        val raw = runCatching {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
        }.getOrNull() ?: return null
        val scaled = scaleBitmap(raw, sizePx)
        // The full-size frame (tens of MB for 4K) goes at once, not whenever the GC gets to it.
        if (scaled !== raw) raw.recycle()
        return scaled
    }

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
        // Up to 1.5x the target: 2x let a 5000 px photo decode at full size (about 75 MB each,
        // for the shown page and both neighbours).
        while (largest / sample > target * 3 / 2) sample *= 2
        return sample
    }

    class Factory : Fetcher.Factory<EncryptedFile> {
        override fun create(data: EncryptedFile, options: Options, imageLoader: ImageLoader): Fetcher =
            EncryptedMediaFetcher(File(data.path), options)
    }
}

/** Largest private video decrypted only to show a still frame (matches the thumbnail path). */
private const val MAX_VIDEO_FRAME_SOURCE_BYTES = 48L * 1024L * 1024L

/** Full-size private decodes running at once (see [EncryptedMediaFetcher.fetch]). */
private val decodePermits = kotlinx.coroutines.sync.Semaphore(2)
