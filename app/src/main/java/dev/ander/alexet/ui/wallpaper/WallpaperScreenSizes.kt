package dev.ander.alexet.ui.wallpaper

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.window.layout.WindowMetricsCalculator
import dev.ander.alexet.data.AppSettings
import dev.ander.alexet.foldable.FoldableState
import kotlin.math.roundToInt

enum class WallpaperPreviewScreen {
    FOLDED,
    UNFOLDED,
}

object WallpaperResolutionMemory {
    @Volatile var detectedFoldedWidth: Int = 0
    @Volatile var detectedFoldedHeight: Int = 0
    @Volatile var detectedUnfoldedWidth: Int = 0
    @Volatile var detectedUnfoldedHeight: Int = 0

    fun update(isWide: Boolean, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (isWide) {
            detectedUnfoldedWidth = width
            detectedUnfoldedHeight = height
        } else {
            detectedFoldedWidth = width
            detectedFoldedHeight = height
        }
    }

    fun hasFolded(): Boolean = detectedFoldedWidth > 0 && detectedFoldedHeight > 0
    fun hasUnfolded(): Boolean = detectedUnfoldedWidth > 0 && detectedUnfoldedHeight > 0
}

data class TypicalWallpaperAspect(
    val id: String,
    val label: String,
    val foldedRatio: String,
    val unfoldedRatio: String,
    val foldedAspect: Float,
    val unfoldedAspect: Float,
    val sampleFoldedW: Int,
    val sampleFoldedH: Int,
    val sampleUnfoldedW: Int,
    val sampleUnfoldedH: Int,
)

val TYPICAL_WALLPAPER_RATIOS: List<TypicalWallpaperAspect> = listOf(
    TypicalWallpaperAspect(
        id = "ratio_20_9_square",
        label = "20:9 / 1:1 (~kwadrat)",
        foldedRatio = "20:9",
        unfoldedRatio = "1:1",
        foldedAspect = 1080f / 2424f,
        unfoldedAspect = 2076f / 2152f,
        sampleFoldedW = 1080,
        sampleFoldedH = 2424,
        sampleUnfoldedW = 2076,
        sampleUnfoldedH = 2152,
    ),
    TypicalWallpaperAspect(
        id = "ratio_21_9_4_3",
        label = "21:9 / 4:3",
        foldedRatio = "21:9",
        unfoldedRatio = "4:3",
        foldedAspect = 1080f / 2400f,
        unfoldedAspect = 2160f / 1620f,
        sampleFoldedW = 1080,
        sampleFoldedH = 2400,
        sampleUnfoldedW = 2160,
        sampleUnfoldedH = 1620,
    ),
    TypicalWallpaperAspect(
        id = "ratio_22_9_6_5",
        label = "22:9 / 6:5",
        foldedRatio = "22:9",
        unfoldedRatio = "6:5",
        foldedAspect = 968f / 2376f,
        unfoldedAspect = 1856f / 2160f,
        sampleFoldedW = 968,
        sampleFoldedH = 2376,
        sampleUnfoldedW = 1856,
        sampleUnfoldedH = 2160,
    ),
    TypicalWallpaperAspect(
        id = "ratio_16_9_square",
        label = "16:9 / 1:1",
        foldedRatio = "16:9",
        unfoldedRatio = "1:1",
        foldedAspect = 1080f / 1920f,
        unfoldedAspect = 2160f / 2160f,
        sampleFoldedW = 1080,
        sampleFoldedH = 1920,
        sampleUnfoldedW = 2160,
        sampleUnfoldedH = 2160,
    ),
)

data class WallpaperDevicePreset(
    val id: String,
    val name: String,
    val foldedWidth: Int,
    val foldedHeight: Int,
    val unfoldedWidth: Int,
    val unfoldedHeight: Int,
)

@Deprecated("Use dynamic aspect ratio detection or TYPICAL_WALLPAPER_RATIOS instead. Retained for backwards compatibility.")
val WALLPAPER_DEVICE_PRESETS = listOf(
    WallpaperDevicePreset(
        id = "pixel_9_pro_fold",
        name = "Google Pixel 9 Pro Fold",
        foldedWidth = 1080,
        foldedHeight = 2424,
        unfoldedWidth = 2076,
        unfoldedHeight = 2152,
    ),
    WallpaperDevicePreset(
        id = "pixel_fold",
        name = "Google Pixel Fold",
        foldedWidth = 1080,
        foldedHeight = 2092,
        unfoldedWidth = 2208,
        unfoldedHeight = 1840,
    ),
    WallpaperDevicePreset(
        id = "galaxy_z_fold",
        name = "Samsung Galaxy Z Fold 4/5/6",
        foldedWidth = 968,
        foldedHeight = 2376,
        unfoldedWidth = 1856,
        unfoldedHeight = 2160,
    ),
    WallpaperDevicePreset(
        id = "oneplus_open",
        name = "OnePlus Open",
        foldedWidth = 1116,
        foldedHeight = 2484,
        unfoldedWidth = 2268,
        unfoldedHeight = 2440,
    ),
    WallpaperDevicePreset(
        id = "square",
        name = "Kwadrat 1:1",
        foldedWidth = 1080,
        foldedHeight = 2400,
        unfoldedWidth = 2160,
        unfoldedHeight = 2160,
    ),
)

data class WallpaperScreenTargets(
    val foldedWidth: Int,
    val foldedHeight: Int,
    val unfoldedWidth: Int,
    val unfoldedHeight: Int,
    val isCustom: Boolean = false,
) {
    val foldedAspect: Float
        get() = if (foldedHeight <= 0) FALLBACK_COVER_ASPECT
        else foldedWidth.toFloat() / foldedHeight.toFloat()

    val unfoldedAspect: Float
        get() = if (unfoldedHeight <= 0) FALLBACK_INNER_ASPECT
        else unfoldedWidth.toFloat() / unfoldedHeight.toFloat()

    fun sizeFor(screen: WallpaperPreviewScreen): IntSize2 = when (screen) {
        WallpaperPreviewScreen.FOLDED -> IntSize2(foldedWidth.coerceAtLeast(1), foldedHeight.coerceAtLeast(1))
        WallpaperPreviewScreen.UNFOLDED -> IntSize2(unfoldedWidth.coerceAtLeast(1), unfoldedHeight.coerceAtLeast(1))
    }

    fun aspectFor(screen: WallpaperPreviewScreen): Float = when (screen) {
        WallpaperPreviewScreen.FOLDED -> foldedAspect
        WallpaperPreviewScreen.UNFOLDED -> unfoldedAspect
    }
}

/** Typical cover / outer display aspect (~20:9 phone, e.g. Pixel 9 Pro Fold 1080x2424). */
const val FALLBACK_COVER_ASPECT = 1080f / 2424f // ~0.4455f

/** Typical inner foldable aspect: near 1:1 square (e.g. Pixel 9 Pro Fold is 2076x2152 ~0.965f). */
const val FALLBACK_INNER_ASPECT = 2076f / 2152f // ~0.9647f (square-ish)

/**
 * Resolve folded (cover) vs unfolded (inner) preview sizes.
 *
 * Priority:
 * 1. User custom resolution override from [settings] (if set > 0).
 * 2. Real physical displays detected via DisplayManager (supportedModes, mode) & current window.
 * 3. In-memory detected resolutions ([WallpaperResolutionMemory]).
 * 4. Fallback: tall cover aspect (~20:9) & square inner aspect (~1:1, e.g. 2076x2152).
 */
fun resolveWallpaperScreenTargets(
    context: Context,
    foldableState: FoldableState? = null,
    settings: AppSettings? = null,
): WallpaperScreenTargets {
    val customFoldedW = settings?.customWallpaperFoldedWidth ?: 0
    val customFoldedH = settings?.customWallpaperFoldedHeight ?: 0
    val customUnfoldedW = settings?.customWallpaperUnfoldedWidth ?: 0
    val customUnfoldedH = settings?.customWallpaperUnfoldedHeight ?: 0
    val hasCustom = (customFoldedW > 0 && customFoldedH > 0) || (customUnfoldedW > 0 && customUnfoldedH > 0)

    val activity = context.findActivity()
    val current = currentWindowSize(activity, context)

    val detected = detectPhysicalScreenSizes(context, activity)
    val detectedFolded = detected.first
    val detectedUnfolded = detected.second

    val memoryFolded = if (WallpaperResolutionMemory.hasFolded()) {
        IntSize2(WallpaperResolutionMemory.detectedFoldedWidth, WallpaperResolutionMemory.detectedFoldedHeight)
    } else if ((settings?.lastDetectedFoldedWidth ?: 0) > 0 && (settings?.lastDetectedFoldedHeight ?: 0) > 0) {
        IntSize2(settings!!.lastDetectedFoldedWidth, settings.lastDetectedFoldedHeight)
    } else null

    val memoryUnfolded = if (WallpaperResolutionMemory.hasUnfolded()) {
        IntSize2(WallpaperResolutionMemory.detectedUnfoldedWidth, WallpaperResolutionMemory.detectedUnfoldedHeight)
    } else if ((settings?.lastDetectedUnfoldedWidth ?: 0) > 0 && (settings?.lastDetectedUnfoldedHeight ?: 0) > 0) {
        IntSize2(settings!!.lastDetectedUnfoldedWidth, settings.lastDetectedUnfoldedHeight)
    } else null

    val looksFolded = isLikelyFolded(foldableState, current, detectedUnfolded ?: memoryUnfolded)

    val finalFolded = when {
        customFoldedW > 0 && customFoldedH > 0 -> IntSize2(customFoldedW, customFoldedH)
        looksFolded && current != null -> current
        memoryFolded != null -> memoryFolded
        detectedFolded != null -> detectedFolded
        current != null && current.aspect in 0.35f..0.65f -> current
        else -> IntSize2(1080, 2424)
    }

    val finalUnfolded = when {
        customUnfoldedW > 0 && customUnfoldedH > 0 -> IntSize2(customUnfoldedW, customUnfoldedH)
        !looksFolded && current != null -> current
        memoryUnfolded != null -> memoryUnfolded
        detectedUnfolded != null -> detectedUnfolded
        else -> {
            // Inner display for foldables is near 1:1 square.
            val h = if (finalFolded.height > 0) (finalFolded.height * (2152f / 2424f)).roundToInt() else 2152
            val w = if (finalFolded.height > 0) (finalFolded.height * (2076f / 2424f)).roundToInt() else 2076
            IntSize2(w, h)
        }
    }

    return WallpaperScreenTargets(
        foldedWidth = finalFolded.width,
        foldedHeight = finalFolded.height,
        unfoldedWidth = finalUnfolded.width,
        unfoldedHeight = finalUnfolded.height,
        isCustom = hasCustom,
    )
}

/**
 * Dynamically detects aspect ratios (Pair(coverAspect, innerAspect)) from active display/window metrics.
 */
fun detectDynamicAspectRatios(context: Context, activity: Activity? = null): Pair<Float, Float> {
    val detected = detectPhysicalScreenSizes(context, activity)
    val cur = currentWindowSize(activity, context)
    val coverAspect = detected.first?.aspect
        ?: if (cur != null && cur.aspect in 0.35f..0.65f) cur.aspect
        else if (WallpaperResolutionMemory.hasFolded()) WallpaperResolutionMemory.detectedFoldedWidth.toFloat() / WallpaperResolutionMemory.detectedFoldedHeight
        else FALLBACK_COVER_ASPECT

    val innerAspect = detected.second?.aspect
        ?: if (cur != null && cur.aspect in 0.70f..1.35f) cur.aspect
        else if (WallpaperResolutionMemory.hasUnfolded()) WallpaperResolutionMemory.detectedUnfoldedWidth.toFloat() / WallpaperResolutionMemory.detectedUnfoldedHeight
        else FALLBACK_INNER_ASPECT

    return Pair(coverAspect, innerAspect)
}

/**
 * Detect physical display candidates for folded (outer) vs unfolded (inner) screens.
 * Returns Pair(coverScreen, innerScreen).
 */
fun detectPhysicalScreenSizes(context: Context, activity: Activity? = null): Pair<IntSize2?, IntSize2?> {
    val candidates = mutableSetOf<IntSize2>()

    val current = currentWindowSize(activity, context)
    if (current != null && current.width > 0 && current.height > 0) {
        candidates.add(normalizePortrait(current))
    }

    val dm = context.getSystemService(DisplayManager::class.java)
    if (dm != null) {
        for (display in dm.displays) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    for (mode in display.supportedModes) {
                        if (mode.physicalWidth > 0 && mode.physicalHeight > 0) {
                            candidates.add(normalizePortrait(IntSize2(mode.physicalWidth, mode.physicalHeight)))
                        }
                    }
                    display.mode?.let { m ->
                        if (m.physicalWidth > 0 && m.physicalHeight > 0) {
                            candidates.add(normalizePortrait(IntSize2(m.physicalWidth, m.physicalHeight)))
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    display.getRealMetrics(metrics)
                    if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                        candidates.add(normalizePortrait(IntSize2(metrics.widthPixels, metrics.heightPixels)))
                    }
                }
            }
        }
    }

    val coverCandidates = candidates.filter { it.aspect in 0.35f..0.65f }
    val innerCandidates = candidates.filter { it.aspect in 0.70f..1.35f }

    val cover = coverCandidates.maxByOrNull { it.width * it.height }
        ?: coverCandidates.firstOrNull()

    val inner = innerCandidates.maxByOrNull { it.width * it.height }
        ?: innerCandidates.firstOrNull()

    return Pair(cover, inner)
}

private fun normalizePortrait(size: IntSize2): IntSize2 {
    return if (size.width > size.height && (size.width.toFloat() / size.height.toFloat()) > 1.4f) {
        IntSize2(size.height, size.width)
    } else {
        size
    }
}

private fun isLikelyFolded(
    foldableState: FoldableState?,
    current: IntSize2?,
    unfoldedTarget: IntSize2?,
): Boolean {
    val folding = foldableState?.foldingFeature
    // A reported fold means the inner screen. No fold doesn't prove the cover screen: tablets and
    // phones have none, and an inner-screen split window that doesn't cross the hinge gets none
    // either, so that case is decided by the display size below.
    // (Width classes flip with landscape and a split window, so they can't tell the screens apart.)
    if (foldableState != null && folding != null) return false
    if (current != null && unfoldedTarget != null) {
        val curArea = current.width.toLong() * current.height
        val maxArea = unfoldedTarget.width.toLong() * unfoldedTarget.height
        if (maxArea > 0 && curArea * 1.5 < maxArea) return true
    }
    return current != null && current.width < 700
}

/**
 * Size of the screen the app is on, in its natural (portrait) orientation: the whole display,
 * not a split-screen window, and not swapped by a landscape rotation (wallpapers are portrait).
 */
internal fun currentWindowSize(activity: Activity?, context: Context): IntSize2? {
    val raw = activity?.let {
        runCatching {
            val b = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(it).bounds
            if (b.width() > 0 && b.height() > 0) IntSize2(b.width(), b.height()) else null
        }.getOrNull()
    } ?: legacyDisplaySize(context) ?: return null
    @Suppress("DEPRECATION")
    val rotation = runCatching {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity?.display else null
        (display ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay)?.rotation
    }.getOrNull() ?: android.view.Surface.ROTATION_0
    val rotated = rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270
    return if (rotated) IntSize2(raw.height, raw.width) else raw
}

@Suppress("DEPRECATION")
private fun legacyDisplaySize(context: Context): IntSize2? {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
    val metrics = DisplayMetrics()
    wm.defaultDisplay?.getRealMetrics(metrics) ?: return null
    if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
    return IntSize2(metrics.widthPixels, metrics.heightPixels)
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
