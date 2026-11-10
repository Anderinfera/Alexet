package dev.ander.alexet

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.StrictMode
import dev.ander.alexet.data.AppLog as Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import dev.ander.alexet.data.ImageEditEngine
import dev.ander.alexet.data.SecureFolderManager
import dev.ander.alexet.ui.components.EncryptedMediaFetcher
import dev.ander.alexet.ui.components.MediaStoreThumbnailFetcher
import okio.Path.Companion.toOkioPath

class AlexetApplication : Application(), SingletonImageLoader.Factory {
    companion object {
        private const val TAG = "AlexetApplication"
        lateinit var instance: AlexetApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyLog()
                    .build(),
            )
        }
        instance = this
        SecureFolderManager.init(this)
        dev.ander.alexet.data.RenameJournal.init(this)
        // Finish or undo what a killed process left half done (off the main thread).
        dev.ander.alexet.data.StartupRecovery.run(this)
        // The settings file is read and parsed here, off the main thread, while the activity is
        // being set up: the screen's first use then finds it ready (it was read on the main thread).
        Thread({ runCatching { dev.ander.alexet.data.AppGraph.settings(this) } }, "SettingsWarmup").start()
        // Decoded private images must not outlive a lock in the image memory cache.
        SecureFolderManager.onLocked = {
            runCatching { coil3.SingletonImageLoader.get(this).memoryCache?.clear() }
        }
        // Debug log: keep the last minutes on disk when the app crashes (read on the next "Create log").
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            dev.ander.alexet.data.AppLog.persistCrash(filesDir, thread, error)
            previous?.uncaughtException(thread, error)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .components {
                add(EncryptedMediaFetcher.Factory())
                add(dev.ander.alexet.ui.components.OtherFilePreviewFetcher.Factory())
                add(MediaStoreThumbnailFetcher.Factory())
                add(VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    /**
     * Shrink Coil memory thumbnails when the system is under background memory pressure so LMK is
     * less likely to kill the process while Alexet sits in the background.
     * The memory cache is only halved on BACKGROUND (Android 14+ sends it soon after every app
     * switch) and cleared fully on COMPLETE; otherwise every tile reloads after returning (from
     * the thumbnails kept in Coil's disk cache, see MediaStoreThumbnailFetcher).
     * FolderCoverCache is preserved so folder thumbnails do not flicker upon return.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            runCatching {
                SingletonImageLoader.get(this).memoryCache?.let { cache ->
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) cache.clear()
                    else cache.trimToSize(cache.maxSize / 2)
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to trim Coil memory cache", e)
            }
            runCatching {
                ImageEditEngine.clearCaches()
            }.onFailure { e ->
                Log.w(TAG, "Failed to clear ImageEditEngine caches on trim", e)
            }
            dev.ander.alexet.data.AppGraph.smartSearchIfCreated()?.trimMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching {
            SingletonImageLoader.get(this).memoryCache?.clear()
        }.onFailure { e ->
            Log.w(TAG, "Failed to clear Coil memory cache on low memory", e)
        }
        runCatching {
            ImageEditEngine.clearCaches()
        }.onFailure { e ->
            Log.w(TAG, "Failed to clear ImageEditEngine caches on low memory", e)
        }
        runCatching {
            dev.ander.alexet.data.AppGraph.smartSearchIfCreated()?.trimMemory()
        }.onFailure { e ->
            Log.w(TAG, "Failed to free the smart search model on low memory", e)
        }
        runCatching {
            dev.ander.alexet.data.FolderCoverCache.clear()
        }.onFailure { e ->
            Log.w(TAG, "Failed to clear FolderCoverCache on low memory", e)
        }
        // Decrypted private files (up to 128 MB); they are decrypted again when opened.
        runCatching {
            dev.ander.alexet.data.SecureRamCache.clear()
        }.onFailure { e ->
            Log.w(TAG, "Failed to clear SecureRamCache on low memory", e)
        }
    }
}
