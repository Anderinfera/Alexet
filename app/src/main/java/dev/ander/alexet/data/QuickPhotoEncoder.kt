package dev.ander.alexet.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Size of a quick-edited photo, found by encoding it in memory as it would be saved (exact, not
 * a guess). The turned and cropped picture is kept between calls: moving the quality slider only
 * encodes again. One encode at a time; [clear] frees the pictures when quick edit closes.
 */
class QuickPhotoEncoder {
    private val mutex = Mutex()
    private var renderedKey: Any? = null
    private var rendered: Bitmap? = null
    private var scaledKey: Float = 1f
    private var scaled: Bitmap? = null
    /**
     * Sizes already measured for [rendered]: "fit to size" tries settings that are then set, and
     * the slider comes back to qualities it passed. Cleared with the picture.
     */
    private val sizes = HashMap<Triple<Float, Int, String>, Long>()

    suspend fun bytes(context: Context, source: QuickEditSource, file: File?, settings: QuickEditSettings): Long? =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                try {
                    val ext = QuickEdit.photoExtension(source.media, settings.quality)
                    val sizeKey = Triple(settings.scale, settings.quality, ext)
                    if (renderedKey == renderedKeyOf(source, settings)) sizes[sizeKey]?.let { return@withLock it }
                    val picture = picture(context, source, file, settings) ?: return@withLock null
                    val counter = CountingStream()
                    if (!picture.compress(formatFor(ext), settings.quality, counter)) return@withLock null
                    counter.count.also { sizes[sizeKey] = it }
                } catch (_: OutOfMemoryError) {
                    clearLocked()
                    null
                }
            }
        }

    suspend fun clear() = mutex.withLock { clearLocked() }

    private fun renderedKeyOf(source: QuickEditSource, settings: QuickEditSettings): Any =
        listOf(source.media.id, settings.rotation, settings.ratio, settings.panX, settings.panY)

    private fun picture(context: Context, source: QuickEditSource, file: File?, settings: QuickEditSettings): Bitmap? {
        val key = renderedKeyOf(source, settings)
        var base = rendered?.takeIf { renderedKey == key && !it.isRecycled }
        if (base == null) {
            clearLocked()
            val decoded = ImageEditEngine.decodeForExport(context, file, source.media.uri) ?: return null
            // renderEdits takes the decoded picture over (frees it when it makes a new one).
            base = ImageEditEngine.renderEdits(context, decoded, QuickEdit.photoEditState(source, settings))
            rendered = base
            renderedKey = key
        }
        if (settings.scale >= 0.999f) return base
        scaled?.takeIf { scaledKey == settings.scale && !it.isRecycled }?.let { return it }
        scaled?.recycle()
        val w = (base.width * settings.scale).roundToInt().coerceAtLeast(1)
        val h = (base.height * settings.scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(base, w, h, true).also {
            scaled = it
            scaledKey = settings.scale
        }
    }

    private fun clearLocked() {
        scaled?.takeIf { it !== rendered }?.recycle()
        rendered?.recycle()
        scaled = null
        rendered = null
        renderedKey = null
        sizes.clear()
    }

    private class CountingStream : OutputStream() {
        var count = 0L
        override fun write(b: Int) {
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
        }
    }

    companion object {
        fun formatFor(ext: String): Bitmap.CompressFormat = when (ext) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            else -> Bitmap.CompressFormat.JPEG
        }
    }
}
