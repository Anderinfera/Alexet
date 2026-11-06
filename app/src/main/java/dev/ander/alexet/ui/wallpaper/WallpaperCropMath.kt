package dev.ander.alexet.ui.wallpaper

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure wallpaper crop / aspect helpers (unit-testable, no Android deps).
 *
 * Model: at userScale=1 the image covers the fixed frame (center-crop).
 * Larger userScale zooms in; offset pans the image under the frame.
 */
data class IntSize2(val width: Int, val height: Int) {
    val aspect: Float get() = if (height == 0) 1f else width.toFloat() / height.toFloat()
}

data class CropRectPx(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/**
 * Cover scale: scale factor applied to image pixels so the image covers [frameW]x[frameH].
 * Resulting drawn size = (imageW * scale, imageH * scale).
 */
fun coverScale(imageWidth: Float, imageHeight: Float, frameWidth: Float, frameHeight: Float): Float {
    if (imageWidth <= 0f || imageHeight <= 0f || frameWidth <= 0f || frameHeight <= 0f) return 1f
    return max(frameWidth / imageWidth, frameHeight / imageHeight)
}

/**
 * Maximum pan so the scaled image still covers the frame.
 * [drawnW]/[drawnH] are the on-screen image size after baseCover * userScale.
 */
fun maxPanOffset(drawnW: Float, drawnH: Float, frameW: Float, frameH: Float): Pair<Float, Float> {
    val maxX = max(0f, (drawnW - frameW) / 2f)
    val maxY = max(0f, (drawnH - frameH) / 2f)
    return maxX to maxY
}

fun clampPan(
    offsetX: Float,
    offsetY: Float,
    drawnW: Float,
    drawnH: Float,
    frameW: Float,
    frameH: Float,
): Pair<Float, Float> {
    val (maxX, maxY) = maxPanOffset(drawnW, drawnH, frameW, frameH)
    return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
}

/**
 * Map the fixed frame back into source image pixel crop rect.
 *
 * Image is drawn centered on the frame center, then translated by [offsetX]/[offsetY]
 * (positive offset moves image right/down). Total drawn size = imageSize * coverScale * userScale.
 */
fun cropRectInImagePixels(
    imageWidth: Int,
    imageHeight: Int,
    frameWidth: Float,
    frameHeight: Float,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
): CropRectPx {
    val iw = imageWidth.coerceAtLeast(1)
    val ih = imageHeight.coerceAtLeast(1)
    val base = coverScale(iw.toFloat(), ih.toFloat(), frameWidth, frameHeight)
    val total = base * userScale.coerceAtLeast(1f)
    val drawnW = iw * total
    val drawnH = ih * total
    val (ox, oy) = clampPan(offsetX, offsetY, drawnW, drawnH, frameWidth, frameHeight)

    // Image top-left in frame-local coords (frame origin at top-left of frame).
    val imgLeft = (frameWidth - drawnW) / 2f + ox
    val imgTop = (frameHeight - drawnH) / 2f + oy

    val srcLeft = ((0f - imgLeft) / total).coerceIn(0f, iw.toFloat())
    val srcTop = ((0f - imgTop) / total).coerceIn(0f, ih.toFloat())
    val srcRight = ((frameWidth - imgLeft) / total).coerceIn(0f, iw.toFloat())
    val srcBottom = ((frameHeight - imgTop) / total).coerceIn(0f, ih.toFloat())

    val left = srcLeft.roundToInt().coerceIn(0, iw - 1)
    val top = srcTop.roundToInt().coerceIn(0, ih - 1)
    val right = srcRight.roundToInt().coerceIn(left + 1, iw)
    val bottom = srcBottom.roundToInt().coerceIn(top + 1, ih)

    return CropRectPx(left, top, right - left, bottom - top)
}

/**
 * Choose output bitmap size for [frameAspect] that fits inside [desiredW]x[desiredH]
 * (WallpaperManager desired minimum), preserving aspect. Falls back to a reasonable
 * minimum dimension when desired values are missing.
 */
fun outputSizeForAspect(
    frameAspect: Float,
    desiredWidth: Int,
    desiredHeight: Int,
    minEdge: Int = 1080,
): IntSize2 {
    val aspect = frameAspect.coerceAtLeast(0.05f)
    val dw = desiredWidth
    val dh = desiredHeight
    if (dw > 0 && dh > 0) {
        val desiredAspect = dw.toFloat() / dh.toFloat()
        return if (aspect >= desiredAspect) {
            val w = dw
            val h = max(1, (w / aspect).roundToInt())
            IntSize2(w, h)
        } else {
            val h = dh
            val w = max(1, (h * aspect).roundToInt())
            IntSize2(w, h)
        }
    }
    return if (aspect >= 1f) {
        IntSize2(max(minEdge, (minEdge * aspect).roundToInt()), minEdge)
    } else {
        IntSize2(minEdge, max(minEdge, (minEdge / aspect).roundToInt()))
    }
}

/** Largest centered rect of [targetAspect] that fits inside [containerW]x[containerH]. */
fun fitFrameSize(containerW: Float, containerH: Float, targetAspect: Float): Pair<Float, Float> {
    if (containerW <= 0f || containerH <= 0f || targetAspect <= 0f) return 1f to 1f
    val containerAspect = containerW / containerH
    return if (containerAspect > targetAspect) {
        val h = containerH
        val w = h * targetAspect
        w to h
    } else {
        val w = containerW
        val h = w / targetAspect
        w to h
    }
}
