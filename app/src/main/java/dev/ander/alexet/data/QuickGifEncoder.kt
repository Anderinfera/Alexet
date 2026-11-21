package dev.ander.alexet.data

import android.content.Context
import android.graphics.Bitmap
import dev.ander.alexet.data.videoeditor.GifEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.TreeSet
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Size of a quick-edited animated GIF, measured with [GifEncoder] (exact for short GIFs,
 * sampled frame pairs for longer ones). Rendered frames are kept between calls so moving the
 * quality slider only encodes again; [clear] frees them when quick edit closes.
 */
class QuickGifEncoder {
    private val mutex = Mutex()

    private var sourceKey: Pair<Long, Long>? = null
    private var sourceInfo: GifFrameReader.Info? = null
    private val sourceFrames = ArrayList<Bitmap>()
    private val sourceDelays = ArrayList<Int>()
    private val samplePairs = ArrayList<Pair<Int, Int>>()
    private var allFramesCached = false

    private var renderedKey: Any? = null
    private val renderedFrames = ArrayList<Bitmap>()
    private var scaledKey: Float = 1f
    private val scaledFrames = ArrayList<Bitmap>()

    /** Sizes already measured for [renderedFrames]; cleared whenever edits change. */
    private val sizes = HashMap<Pair<Float, Int>, Long>()

    suspend fun bytes(context: Context, source: QuickEditSource, file: File?, settings: QuickEditSettings): Long? =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                try {
                    val sizeKey = settings.scale to settings.quality
                    val rKey = renderedKeyOf(source, settings)
                    if (renderedKey == rKey) sizes[sizeKey]?.let { return@withLock it }
                    val frames = prepareFrames(context, source, file, settings) ?: return@withLock null
                    val info = sourceInfo ?: return@withLock null
                    if (frames.isEmpty()) return@withLock null
                    measureBytes(info, frames, settings)?.also { sizes[sizeKey] = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: OutOfMemoryError) {
                    clearLocked()
                    null
                }
            }
        }

    suspend fun clear() = mutex.withLock { clearLocked() }

    private fun renderedKeyOf(source: QuickEditSource, settings: QuickEditSettings): Any =
        listOf(
            source.media.id,
            source.media.dateModifiedMs,
            settings.rotation,
            settings.ratio,
            settings.panX,
            settings.panY,
        )

    private suspend fun prepareFrames(
        context: Context,
        source: QuickEditSource,
        file: File?,
        settings: QuickEditSettings,
    ): List<Bitmap>? {
        val sKey = source.media.id to source.media.dateModifiedMs
        if (sourceKey != sKey || sourceInfo == null || sourceFrames.isEmpty() || sourceFrames.any { it.isRecycled }) {
            if (!loadSourceSamples(context, source, file, sKey)) return null
        }
        val rKey = renderedKeyOf(source, settings)
        if (renderedKey != rKey || renderedFrames.size != sourceFrames.size || renderedFrames.any { it.isRecycled }) {
            clearRenderedLocked()
            val editState = QuickEdit.photoEditState(source, settings)
            val built = ArrayList<Bitmap>(sourceFrames.size)
            try {
                for (src in sourceFrames) {
                    coroutineContext.ensureActive()
                    val copy = src.copy(Bitmap.Config.ARGB_8888, true)
                    if (copy == null) {
                        built.forEach { if (!it.isRecycled) it.recycle() }
                        return null
                    }
                    built.add(ImageEditEngine.renderEdits(context, copy, editState))
                }
            } catch (t: Throwable) {
                built.forEach { if (!it.isRecycled) it.recycle() }
                throw t
            }
            renderedFrames.addAll(built)
            renderedKey = rKey
        }
        if (settings.scale >= 0.999f) return renderedFrames
        if (scaledKey == settings.scale &&
            scaledFrames.size == renderedFrames.size &&
            scaledFrames.none { it.isRecycled }
        ) {
            return scaledFrames
        }
        clearScaledLocked()
        val builtScaled = ArrayList<Bitmap>(renderedFrames.size)
        try {
            for (base in renderedFrames) {
                coroutineContext.ensureActive()
                val w = (base.width * settings.scale).roundToInt().coerceAtLeast(1)
                val h = (base.height * settings.scale).roundToInt().coerceAtLeast(1)
                builtScaled.add(Bitmap.createScaledBitmap(base, w, h, true))
            }
        } catch (t: Throwable) {
            for (i in builtScaled.indices) {
                val bmp = builtScaled[i]
                if (bmp !== renderedFrames[i] && !bmp.isRecycled) bmp.recycle()
            }
            throw t
        }
        scaledFrames.addAll(builtScaled)
        scaledKey = settings.scale
        return scaledFrames
    }

    private suspend fun loadSourceSamples(
        context: Context,
        source: QuickEditSource,
        file: File?,
        sKey: Pair<Long, Long>,
    ): Boolean {
        clearLocked()
        val data = ImageEditEngine.readSourceBytes(context, file, source.media.uri) ?: return false
        val info = GifFrameReader.info(data) ?: return false
        if (info.frameCount <= 0 || info.width <= 0 || info.height <= 0) return false
        val perFrameBytes = info.width.toLong() * info.height * 4L
        if (perFrameBytes <= 0L) return false

        val cacheAll = info.frameCount <= MAX_FULL_FRAMES &&
            info.frameCount.toLong() * perFrameBytes <= MAX_SAMPLE_BYTES

        val needed = TreeSet<Int>()
        val chosenK = ArrayList<Int>()
        if (!cacheAll) {
            needed.add(0)
            val maxFrames = (MAX_SAMPLE_BYTES / perFrameBytes).toInt().coerceIn(1, 1 + 2 * MAX_SAMPLE_PAIRS)
            val pairCount = ((maxFrames - 1) / 2).coerceIn(0, minOf(MAX_SAMPLE_PAIRS, info.frameCount - 1))
            for (i in 0 until pairCount) {
                val k = (((i + 1L) * info.frameCount) / (pairCount + 1)).toInt()
                    .coerceIn(1, info.frameCount - 1)
                chosenK.add(k)
                needed.add(k - 1)
                needed.add(k)
            }
        }
        val lastNeeded = if (cacheAll) info.frameCount - 1 else needed.last()

        val decoded = ArrayList<Bitmap>()
        val delays = ArrayList<Int>()
        val posByFrame = HashMap<Int, Int>()
        var frameIdx = 0
        try {
            val scope = coroutineContext
            GifFrameReader.forEachFrame(data) { pixels, w, h, delayMs ->
                if (!scope.isActive) return@forEachFrame false
                if (cacheAll || frameIdx in needed) {
                    posByFrame[frameIdx] = decoded.size
                    decoded.add(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888))
                    if (cacheAll) delays.add(delayMs)
                }
                val done = frameIdx >= lastNeeded
                frameIdx++
                !done
            }
            coroutineContext.ensureActive()
        } catch (t: Throwable) {
            decoded.forEach { if (!it.isRecycled) it.recycle() }
            throw t
        }
        if (decoded.isEmpty()) return false

        sourceKey = sKey
        sourceInfo = info
        sourceFrames.addAll(decoded)
        sourceDelays.addAll(delays)
        allFramesCached = cacheAll
        if (!cacheAll) {
            for (k in chosenK) {
                val prevPos = posByFrame[k - 1]
                val currPos = posByFrame[k]
                if (prevPos != null && currPos != null) {
                    samplePairs.add(prevPos to currPos)
                }
            }
        }
        return true
    }

    private suspend fun measureBytes(
        info: GifFrameReader.Info,
        frames: List<Bitmap>,
        settings: QuickEditSettings,
    ): Long? {
        val profile = QuickEdit.gifProfile(settings.quality)
        val allowDelta = !info.hasDisposalClear && !ImageEditEngine.hasTransparentPixel(frames[0])
        val counter = CountingStream()
        val encoder = GifEncoder().apply {
            keepTransparency = true
            setRepeat(0)
            maxColors = profile.maxColors
            temporalMaxDist = profile.temporalMaxDist
            spatialMaxDist = profile.spatialMaxDist
            optimizeDelta = allowDelta
        }
        if (!encoder.start(counter)) return null
        try {
            if (allFramesCached) {
                var pendingBmp: Bitmap? = null
                var pendingDelay = 0
                var ok = true
                for (i in frames.indices) {
                    coroutineContext.ensureActive()
                    val delayMs = sourceDelays.getOrElse(i) { 100 }
                    if (pendingBmp != null && profile.minFrameDelayMs > 0 && pendingDelay < profile.minFrameDelayMs) {
                        pendingDelay += delayMs
                        continue
                    }
                    pendingBmp?.let { pending ->
                        encoder.setDelay(pendingDelay)
                        ok = encoder.addFrame(pending)
                    }
                    if (!ok) return null
                    pendingBmp = frames[i]
                    pendingDelay = delayMs
                }
                if (pendingBmp != null) {
                    encoder.setDelay(pendingDelay)
                    if (!encoder.addFrame(pendingBmp)) return null
                }
                if (!encoder.finish()) return null
                return counter.count
            }

            val first = frames[0]
            val w = first.width
            val h = first.height
            val total = w * h
            if (total <= 0) return null
            val p0 = IntArray(total)
            first.getPixels(p0, 0, w, 0, 0, w, h)
            encoder.setDelay(info.frameDelaysMs.firstOrNull() ?: 100)
            if (!encoder.addFramePixels(p0, w, h)) return null
            val headerAndFrame0 = counter.count

            val keptCount = if (info.frameDelaysMs.isNotEmpty()) {
                QuickEdit.keptGifFrameCount(info.frameDelaysMs, profile.minFrameDelayMs)
            } else {
                info.frameCount
            }
            if (keptCount <= 1) {
                if (!encoder.finish()) return null
                return counter.count
            }
            if (samplePairs.isEmpty()) {
                if (!encoder.finish()) return null
                return headerAndFrame0 + headerAndFrame0 * (keptCount - 1L)
            }

            var deltaSum = 0L
            val prevPx = IntArray(total)
            val currPx = IntArray(total)
            for ((prevPos, currPos) in samplePairs) {
                coroutineContext.ensureActive()
                frames[currPos].getPixels(currPx, 0, w, 0, 0, w, h)
                if (allowDelta) {
                    frames[prevPos].getPixels(prevPx, 0, w, 0, 0, w, h)
                    encoder.seedDeltaCanvas(prevPx, w, h)
                }
                val before = counter.count
                if (!encoder.addFramePixels(currPx, w, h)) return null
                deltaSum += counter.count - before
            }
            if (!encoder.finish()) return null
            val avgDelta = deltaSum.toDouble() / samplePairs.size
            return headerAndFrame0 + (avgDelta * (keptCount - 1)).roundToLong() + 1L
        } finally {
            encoder.close()
        }
    }

    private fun clearScaledLocked() {
        for (i in scaledFrames.indices) {
            val bmp = scaledFrames[i]
            val base = renderedFrames.getOrNull(i)
            if (bmp !== base && !bmp.isRecycled) bmp.recycle()
        }
        scaledFrames.clear()
        scaledKey = 1f
    }

    private fun clearRenderedLocked() {
        clearScaledLocked()
        for (bmp in renderedFrames) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        renderedFrames.clear()
        renderedKey = null
        sizes.clear()
    }

    private fun clearLocked() {
        clearRenderedLocked()
        for (bmp in sourceFrames) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        sourceFrames.clear()
        sourceDelays.clear()
        samplePairs.clear()
        sourceInfo = null
        sourceKey = null
        allFramesCached = false
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

    private companion object {
        const val MAX_SAMPLE_BYTES = 24L * 1024L * 1024L
        const val MAX_FULL_FRAMES = 12
        const val MAX_SAMPLE_PAIRS = 5
    }
}
