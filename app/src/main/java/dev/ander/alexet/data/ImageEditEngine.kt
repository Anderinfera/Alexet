package dev.ander.alexet.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val isValid: Boolean get() = right > left && bottom > top
}

data class NormPoint(val x: Float = 0.5f, val y: Float = 0.5f)

enum class BlurType {
    NONE,
    RADIAL,
    MIRROR,
    LINEAR,
    GAUSSIAN,
}

data class BlurConfig(
    val type: BlurType = BlurType.NONE,
    val radius: Float = 25f, // 0..100
    val focusCenter: NormPoint = NormPoint(0.5f, 0.5f),
    val focusSize: Float = 0.35f, // size / radius / band width (0..1)
    val focusAngle: Float = 0f, // rotation angle degrees
    val falloff: Float = 0.25f, // 0..1: how quickly sharp turns into full blur (0 = near hard edge)
)

data class StickerOverlay(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val scale: Float = 1.0f,
    val rotationDeg: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
)

data class TextOverlay(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val scale: Float = 1.0f,
    val rotationDeg: Float = 0f,
    val alignment: Int = 1, // 0: Left, 1: Center, 2: Right
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrikethrough: Boolean = false,
    val fontSizeSp: Float = 24f,
    val color: Int = Color.WHITE,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
)

data class BrushStroke(
    val id: String = java.util.UUID.randomUUID().toString(),
    val points: List<NormPoint> = emptyList(),
    val color: Int = Color.RED,
    val strokeWidthPx: Float = 16f,
    val alpha: Float = 1.0f,
    val hardness: Float = 1.0f,
)

data class ImageEditState(
    val rotation: Int = 0, // 0, 90, 180, 270 or arbitrary
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val brightness: Float = 0f, // -1f .. 1f
    val contrast: Float = 0f,   // -1f .. 1f
    val saturation: Float = 0f, // -1f .. 1f
    val shadows: Float = 0f,    // -1f .. 1f
    val highlights: Float = 0f, // -1f .. 1f
    val exposure: Float = 0f,   // -1f .. 1f
    val gamma: Float = 0f,      // -1f .. 1f
    val temperature: Float = 0f,// -1f .. 1f
    val sharpness: Float = 0f,  // -1f .. 1f
    val crop: CropRect = CropRect(),
    val aspectRatio: Float? = null,
    val blur: BlurConfig = BlurConfig(),
    val stickers: List<StickerOverlay> = emptyList(),
    val texts: List<TextOverlay> = emptyList(),
    val brushStrokes: List<BrushStroke> = emptyList(),
) {
    val isModified: Boolean
        get() = rotation != 0 || flipH || flipV ||
            brightness != 0f || contrast != 0f || saturation != 0f ||
            shadows != 0f || highlights != 0f || exposure != 0f ||
            gamma != 0f || temperature != 0f || sharpness != 0f ||
            crop.left > 0.001f || crop.top > 0.001f ||
            crop.right < 0.999f || crop.bottom < 0.999f ||
            aspectRatio != null || blur.type != BlurType.NONE ||
            stickers.isNotEmpty() || texts.isNotEmpty() || brushStrokes.isNotEmpty()
}

/** One matrix step matching Coil/AndroidX Exif orientation handling. */
data class ExifMatrixOp(
    val rotateDeg: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
)

enum class ExportResult { SAVED, OUT_OF_MEMORY, FAILED }

object ImageEditEngine {

    /** Encoder chosen from the MIME type when known (external URIs may lack an extension). */
    fun encoderExtensionFor(fileName: String, mimeType: String?): String = when (mimeType?.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/jpeg", "image/jpg" -> "jpg"
        else -> encoderExtensionFor(fileName)
    }

    fun encoderExtensionFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "png"
            "webp" -> "webp"
            "jpg", "jpeg" -> "jpg"
            else -> "jpg"
        }
    }

    fun encoderMatchesOriginalExtension(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "webp", "jpg", "jpeg")
    }

    fun exifOrientationOps(orientation: Int): List<ExifMatrixOp> = when (orientation) {
        2 -> listOf(ExifMatrixOp(scaleX = -1f))
        3 -> listOf(ExifMatrixOp(rotateDeg = 180f))
        4 -> listOf(ExifMatrixOp(scaleY = -1f))
        5 -> listOf(ExifMatrixOp(rotateDeg = 90f), ExifMatrixOp(scaleX = -1f))
        6 -> listOf(ExifMatrixOp(rotateDeg = 90f))
        7 -> listOf(ExifMatrixOp(rotateDeg = -90f), ExifMatrixOp(scaleX = -1f))
        8 -> listOf(ExifMatrixOp(rotateDeg = -90f))
        else -> emptyList()
    }

    fun applyExifOpsToMatrix(matrix: Matrix, ops: List<ExifMatrixOp>) {
        for (op in ops) {
            if (op.rotateDeg != 0f) matrix.postRotate(op.rotateDeg)
            if (op.scaleX != 1f || op.scaleY != 1f) matrix.postScale(op.scaleX, op.scaleY)
        }
    }

    fun userGeometryScaleThenRotate(
        rotation: Int,
        flipH: Boolean,
        flipV: Boolean,
    ): Triple<Float, Float, Float> {
        val sx = if (flipH) -1f else 1f
        val sy = if (flipV) -1f else 1f
        return Triple(sx, sy, rotation.toFloat())
    }

    fun applyUserGeometryToMatrix(
        matrix: Matrix,
        width: Int,
        height: Int,
        rotation: Int,
        flipH: Boolean,
        flipV: Boolean,
    ) {
        val (sx, sy, rot) = userGeometryScaleThenRotate(rotation, flipH, flipV)
        val cx = width / 2f
        val cy = height / 2f
        if (sx != 1f || sy != 1f) {
            matrix.postScale(sx, sy, cx, cy)
        }
        if (rot != 0f) {
            matrix.postRotate(rot, cx, cy)
        }
    }

    fun gammaRgbScale(gamma: Float): Float {
        return if (gamma != 1.0f && gamma > 0.1f) 1f / gamma else 1.0f
    }

    fun createColorMatrixArray(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        exposure: Float = 0f,
        gamma: Float = 0f,
        temperature: Float = 0f,
    ): FloatArray {
        // 1. Saturation
        val satMatrix = ColorMatrix().apply {
            setSaturation((1f + saturation).coerceIn(0f, 3f))
        }

        // 2. Contrast
        val c = if (contrast >= 0f) 1f + contrast * 2f else 1f + contrast
        val translate = (-0.5f * c + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, translate,
                0f, c, 0f, 0f, translate,
                0f, 0f, c, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            ),
        )

        // 3. Brightness, Exposure & Gamma
        val actualGamma = if (gamma >= 0f) 1f + gamma * 2f else 1f / (1f - gamma * 2f).coerceAtLeast(0.01f)
        val gFactor = gammaRgbScale(actualGamma)
        val expScale = 2.0.pow(exposure.toDouble()).toFloat()
        val totalRgbScale = gFactor * expScale
        val bShift = (brightness * 128f).coerceIn(-128f, 128f)
        val brightMatrix = ColorMatrix(
            floatArrayOf(
                totalRgbScale, 0f, 0f, 0f, bShift,
                0f, totalRgbScale, 0f, 0f, bShift,
                0f, 0f, totalRgbScale, 0f, bShift,
                0f, 0f, 0f, 1f, 0f,
            ),
        )

        // 4. Temperature (White balance: cool blue <-> warm yellow)
        val tempR = if (temperature > 0f) 1f + temperature * 0.35f else 1f
        val tempG = if (temperature > 0f) 1f + temperature * 0.12f else 1f
        val tempB = if (temperature < 0f) 1f - temperature * 0.35f else 1f
        val tempMatrix = ColorMatrix(
            floatArrayOf(
                tempR, 0f, 0f, 0f, 0f,
                0f, tempG, 0f, 0f, 0f,
                0f, 0f, tempB, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )

        val combined = ColorMatrix()
        combined.postConcat(satMatrix)
        combined.postConcat(contrastMatrix)
        combined.postConcat(brightMatrix)
        combined.postConcat(tempMatrix)

        return combined.array
    }

    /** Overload matching legacy call signature. */
    fun createColorMatrixArray(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        gamma: Float,
    ): FloatArray {
        val gammaDelta = if (gamma != 1.0f) (gamma - 1f) / 2f else 0f
        return createColorMatrixArray(
            brightness = brightness,
            contrast = contrast,
            saturation = saturation,
            exposure = 0f,
            gamma = gammaDelta,
            temperature = 0f,
        )
    }

    /** Fast Stack Blur implementation in pure Kotlin/Java with memory protection. */
    fun fastStackBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius < 1 || src.isRecycled) return src
        val maxDim = 1920
        if (src.width > maxDim || src.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(src.width, src.height)
            val sw = (src.width * scale).roundToInt().coerceAtLeast(1)
            val sh = (src.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
            val scaledRadius = (radius * scale).roundToInt().coerceAtLeast(1)
            val blurred = fastStackBlur(scaled, scaledRadius)
            if (blurred != scaled) scaled.recycle()
            val upscaled = Bitmap.createScaledBitmap(blurred, src.width, src.height, true)
            if (upscaled != blurred) blurred.recycle()
            return upscaled
        }

        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (yIdx in 0 until h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            for (iIdx in -radius..radius) {
                p = pix[yi + minOf(wm, maxOf(iIdx, 0))]
                sir = stack[iIdx + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - kotlin.math.abs(iIdx)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (iIdx > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (xIdx in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (yIdx == 0) {
                    vmin[xIdx] = minOf(xIdx + radius + 1, wm)
                }
                p = pix[yw + vmin[xIdx]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (xIdx in 0 until w) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (iIdx in -radius..radius) {
                yi = maxOf(0, yp) + xIdx
                sir = stack[iIdx + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - kotlin.math.abs(iIdx)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (iIdx > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (iIdx < hm) {
                    yp += w
                }
            }
            yi = xIdx
            stackpointer = radius
            for (yIdx in 0 until h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (xIdx == 0) {
                    vmin[yIdx] = minOf(yIdx + r1, hm) * w
                }
                p = xIdx + vmin[yIdx]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        val blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        blurred.setPixels(pix, 0, w, 0, 0, w, h)
        return blurred
    }



    private var cachedBlurSourceRef: java.lang.ref.WeakReference<Bitmap>? = null
    private var cachedBlurRadius: Int = -1
    private var cachedBlurBitmapRef: java.lang.ref.WeakReference<Bitmap>? = null

    // ---- Shared preview/export units: every size is relative to the image, never to the screen ----

    /** Text: fontSizeSp is expressed for a 1080 px wide image. */
    const val TEXT_REFERENCE_WIDTH = 1080f

    /** Brush: strokeWidthPx is expressed for a 1000 px wide image. */
    const val BRUSH_REFERENCE_WIDTH = 1000f

    /** Blur: radius (0..100) is expressed for an image whose long side is 2000 px. */
    const val BLUR_REFERENCE_LONG_SIDE = 2000f

    fun textFontPx(fontSizeSp: Float, imageWidthPx: Float): Float = fontSizeSp * imageWidthPx / TEXT_REFERENCE_WIDTH

    fun brushWidthPx(strokeWidthPx: Float, imageWidthPx: Float): Float = strokeWidthPx * imageWidthPx / BRUSH_REFERENCE_WIDTH

    private const val SHARPEN_REFERENCE_LONG_SIDE = 2048f
    /** Pixels per band in [applyShadowsHighlights] and [applySharpness] (4 MB per array). */
    private const val BAND_PIXELS = 1 shl 20

    fun blurRadiusPx(radius: Float, width: Int, height: Int): Float =
        radius * maxOf(width, height) / BLUR_REFERENCE_LONG_SIDE

    /** Sticker box (longer side) is a quarter of the image width. */
    fun stickerBasePx(imageWidthPx: Float): Float = imageWidthPx * 0.25f

    // ---- Geometry: displayed image = rotate(k*90 clockwise) applied after flip, around the centre ----

    private fun normalizedRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    fun pointSourceToDisplay(p: NormPoint, rotation: Int, flipH: Boolean, flipV: Boolean): NormPoint {
        var a = p.x - 0.5f
        var b = p.y - 0.5f
        if (flipH) a = -a
        if (flipV) b = -b
        val (ra, rb) = when (normalizedRotation(rotation)) {
            90 -> -b to a
            180 -> -a to -b
            270 -> b to -a
            else -> a to b
        }
        return NormPoint(ra + 0.5f, rb + 0.5f)
    }

    fun pointDisplayToSource(p: NormPoint, rotation: Int, flipH: Boolean, flipV: Boolean): NormPoint {
        val a = p.x - 0.5f
        val b = p.y - 0.5f
        var (sa, sb) = when (normalizedRotation(rotation)) {
            90 -> b to -a
            180 -> -a to -b
            270 -> -b to a
            else -> a to b
        }
        if (flipH) sa = -sa
        if (flipV) sb = -sb
        return NormPoint(sa + 0.5f, sb + 0.5f)
    }

    private fun mapRect(crop: CropRect, map: (NormPoint) -> NormPoint): CropRect {
        val p1 = map(NormPoint(crop.left, crop.top))
        val p2 = map(NormPoint(crop.right, crop.bottom))
        return CropRect(minOf(p1.x, p2.x), minOf(p1.y, p2.y), maxOf(p1.x, p2.x), maxOf(p1.y, p2.y))
    }

    fun cropDisplayToSource(crop: CropRect, rotation: Int, flipH: Boolean, flipV: Boolean): CropRect =
        mapRect(crop) { pointDisplayToSource(it, rotation, flipH, flipV) }

    fun cropSourceToDisplay(crop: CropRect, rotation: Int, flipH: Boolean, flipV: Boolean): CropRect =
        mapRect(crop) { pointSourceToDisplay(it, rotation, flipH, flipV) }

    /** [inner] is relative to [outer]; the result is relative to what [outer] is relative to. */
    fun composeCrop(outer: CropRect, inner: CropRect): CropRect {
        val w = outer.right - outer.left
        val h = outer.bottom - outer.top
        return CropRect(
            left = outer.left + inner.left * w,
            top = outer.top + inner.top * h,
            right = outer.left + inner.right * w,
            bottom = outer.top + inner.bottom * h,
        )
    }

    private fun normalizedAngle(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    // focusAngle rotates the vertical normal (0, -1) of the focus line: its side vector is
    // (sin a, -cos a). A horizontal flip (x -> -x) keeps that side as -a, a vertical flip as 180 - a.
    fun blurSourceToDisplay(config: BlurConfig, rotation: Int, flipH: Boolean, flipV: Boolean): BlurConfig {
        var angle = config.focusAngle
        if (flipH) angle = -angle
        if (flipV) angle = 180f - angle
        return config.copy(
            focusCenter = pointSourceToDisplay(config.focusCenter, rotation, flipH, flipV),
            focusAngle = normalizedAngle(angle + normalizedRotation(rotation)),
        )
    }

    fun blurDisplayToSource(config: BlurConfig, rotation: Int, flipH: Boolean, flipV: Boolean): BlurConfig {
        var angle = config.focusAngle - normalizedRotation(rotation)
        if (flipH) angle = -angle
        if (flipV) angle = 180f - angle
        return config.copy(
            focusCenter = pointDisplayToSource(config.focusCenter, rotation, flipH, flipV),
            focusAngle = normalizedAngle(angle),
        )
    }

    /**
     * Keeps the blur focus on the same content after cropping to [sub] of an image with aspect
     * [imageAspect] (w/h). focusSize is relative to the shorter side, which the crop changes.
     */
    fun remapBlurToCrop(config: BlurConfig, sub: CropRect, imageAspect: Float): BlurConfig {
        val cw = (sub.right - sub.left).coerceAtLeast(0.001f)
        val ch = (sub.bottom - sub.top).coerceAtLeast(0.001f)
        val oldMin = minOf(imageAspect, 1f)
        val newMin = minOf(imageAspect * cw, ch).coerceAtLeast(0.0001f)
        // The blur radius is relative to the long side, which the crop shortens too.
        val oldMax = maxOf(imageAspect, 1f)
        val newMax = maxOf(imageAspect * cw, ch).coerceAtLeast(0.0001f)
        return config.copy(
            radius = config.radius * oldMax / newMax,
            focusCenter = NormPoint(
                (config.focusCenter.x - sub.left) / cw,
                (config.focusCenter.y - sub.top) / ch,
            ),
            focusSize = config.focusSize * oldMin / newMin,
            falloff = run {
                val fraction = blurTransitionFraction(config.falloff) * oldMin / newMin
                ((fraction - BLUR_TRANSITION_MIN) / (BLUR_TRANSITION_MAX - BLUR_TRANSITION_MIN)).coerceIn(0f, 1f)
            },
        )
    }


    // ---- Focus blur geometry (shared by the export, the live preview and the on-screen guides) ----

    /** Sharp area: circle radius (RADIAL), half band (MIRROR) or offset from the line (LINEAR). */
    fun blurFocusPx(config: BlurConfig, width: Int, height: Int): Float =
        config.focusSize.coerceIn(BLUR_FOCUS_MIN, BLUR_FOCUS_MAX) * minOf(width, height) / 2f

    /** Distance from the sharp edge to full blur: falloff 0 = near hard edge, 1 = 60% of the shorter side. */
    fun blurTransitionFraction(falloff: Float): Float =
        BLUR_TRANSITION_MIN + (BLUR_TRANSITION_MAX - BLUR_TRANSITION_MIN) * falloff.coerceIn(0f, 1f)

    fun blurTransitionPx(config: BlurConfig, width: Int, height: Int): Float =
        blurTransitionFraction(config.falloff) * minOf(width, height)

    const val BLUR_FOCUS_MIN = 0.05f
    const val BLUR_FOCUS_MAX = 0.95f
    private const val BLUR_TRANSITION_MIN = 0.01f
    private const val BLUR_TRANSITION_MAX = 0.60f

    /**
     * Alpha ramp (smoothstep) between gradient positions [from]..[to]: 0 = sharp, 1 = fully blurred.
     * No flat "dead" start: the blur begins right at the sharp edge.
     */
    private fun rampStops(from: Float, to: Float, rising: Boolean): Pair<IntArray, FloatArray> {
        val steps = 6
        val colors = IntArray(steps + 1)
        val stops = FloatArray(steps + 1)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val s = t * t * (3f - 2f * t)
            val a = if (rising) s else 1f - s
            colors[i] = Color.argb((a * 255f).roundToInt(), 255, 255, 255)
            stops[i] = from + (to - from) * t
        }
        return colors to stops
    }

    /** Blur by [radiusPx]; large radii run on a downscaled copy so the stack blur stays small. */
    private fun blurForRadius(source: Bitmap, radiusPx: Float): Bitmap {
        val factor = kotlin.math.ceil(radiusPx / 25f).toInt().coerceAtLeast(1)
        if (factor == 1) return fastStackBlur(source, radiusPx.roundToInt().coerceAtLeast(1))
        val small = Bitmap.createScaledBitmap(
            source,
            (source.width / factor).coerceAtLeast(1),
            (source.height / factor).coerceAtLeast(1),
            true,
        )
        // Out of memory in a later step: the intermediate copies go before the error moves on.
        val blurredSmall = try {
            fastStackBlur(small, (radiusPx / factor).roundToInt().coerceAtLeast(1))
        } catch (oom: OutOfMemoryError) {
            if (small !== source) small.recycle()
            throw oom
        }
        if (blurredSmall != small) small.recycle()
        val upscaled = try {
            Bitmap.createScaledBitmap(blurredSmall, source.width, source.height, true)
        } catch (oom: OutOfMemoryError) {
            if (blurredSmall !== source) blurredSmall.recycle()
            throw oom
        }
        if (upscaled != blurredSmall) blurredSmall.recycle()
        return upscaled
    }

    /**
     * Guards only the cache fields, never the blur work itself: the main thread clears the caches
     * (editor closed, low memory) and waited hundreds of ms for a blur on another thread.
     */
    private val cacheLock = Any()
    /** Bumped by [clearCaches]; a result computed before it is not stored. */
    private var cacheGeneration = 0

    fun clearCaches() = synchronized(cacheLock) {
        cacheGeneration++
        cachedBlurBitmapRef = null
        cachedBlurSourceRef = null
        cachedBlurRadius = -1
        cachedPreRef = null
        cachedPreKey = null
        cachedPreSourceRef = null
    }

    private fun getOrCreateBlurredBase(source: Bitmap, radius: Float): Bitmap {
        if (source.isRecycled) return source
        val radiusKey = blurRadiusPx(radius, source.width, source.height).roundToInt().coerceAtLeast(1)
        val generation = synchronized(cacheLock) {
            val cached = cachedBlurBitmapRef?.get()
            val cachedSource = cachedBlurSourceRef?.get()
            if (cached != null && !cached.isRecycled && cachedSource === source && cachedBlurRadius == radiusKey) {
                return cached
            }
            cacheGeneration
        }
        val created = blurForRadius(source, radiusKey.toFloat())
        synchronized(cacheLock) {
            if (generation == cacheGeneration) {
                cachedBlurBitmapRef = java.lang.ref.WeakReference(created)
                cachedBlurSourceRef = java.lang.ref.WeakReference(source)
                cachedBlurRadius = radiusKey
            }
        }
        return created
    }

    /** Applies blur based on BlurConfig (Gaussian, Radial, Mirror, Linear). */
    fun applyBlur(source: Bitmap, config: BlurConfig, precomputedBlurred: Bitmap? = null): Bitmap {
        if (source.isRecycled) return source
        if (config.type == BlurType.NONE || config.radius <= 0.5f) return source
        val ownsBlurred = precomputedBlurred == null
        val blurredAll = precomputedBlurred ?: blurForRadius(source, blurRadiusPx(config.radius, source.width, source.height))
        if (blurredAll.isRecycled) return source

        if (config.type == BlurType.GAUSSIAN) {
            // Own intermediate can be returned as-is; a shared (cached) one must be copied.
            if (ownsBlurred && blurredAll !== source) return blurredAll
            return try {
                blurredAll.copy(Bitmap.Config.ARGB_8888, true)
            } catch (_: Exception) {
                source
            } catch (_: OutOfMemoryError) {
                source
            }
        }

        val result = try {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: Exception) {
            return source
        } catch (_: OutOfMemoryError) {
            return source
        }
        val canvas = Canvas(result)

        val w = source.width.toFloat()
        val h = source.height.toFloat()
        val cx = config.focusCenter.x.coerceIn(0f, 1f) * w
        val cy = config.focusCenter.y.coerceIn(0f, 1f) * h

        when (config.type) {
            BlurType.RADIAL -> {
                // Sharp inside the focus circle, then [transition] px of smooth ramp to full blur.
                val focusRadius = blurFocusPx(config, source.width, source.height)
                val transition = blurTransitionPx(config, source.width, source.height)
                val blurEnd = focusRadius + transition
                val rInner = (focusRadius / blurEnd).coerceIn(0f, 0.999f)
                val (colors, stops) = rampStops(rInner, 1f, rising = true)
                val shader = RadialGradient(cx, cy, blurEnd, colors, stops, Shader.TileMode.CLAMP)

                val layerId = canvas.saveLayer(0f, 0f, w, h, null)
                canvas.drawBitmap(blurredAll, 0f, 0f, null)
                val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.shader = shader
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                canvas.drawRect(0f, 0f, w, h, blendPaint)
                canvas.restoreToCount(layerId)
            }
            BlurType.MIRROR -> {
                // Sharp band of +-bandHalf around the line, ramps outward on both sides.
                val bandHalf = blurFocusPx(config, source.width, source.height)
                val transition = blurTransitionPx(config, source.width, source.height)
                val topOuter = cy - bandHalf - transition
                val botOuter = cy + bandHalf + transition
                val totalH = botOuter - topOuter
                val pTopInner = (transition / totalH).coerceIn(0.001f, 0.499f)
                val pBotInner = 1f - pTopInner
                val (fallColors, fallStops) = rampStops(0f, pTopInner, rising = false)
                val (riseColors, riseStops) = rampStops(pBotInner, 1f, rising = true)
                val mirrorShader = LinearGradient(
                    cx, topOuter, cx, botOuter,
                    fallColors + riseColors,
                    fallStops + riseStops,
                    Shader.TileMode.CLAMP,
                )

                val layerId = canvas.saveLayer(0f, 0f, w, h, null)
                canvas.drawBitmap(blurredAll, 0f, 0f, null)
                canvas.save()
                canvas.rotate(config.focusAngle, cx, cy)
                val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.shader = mirrorShader
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                val maxDim = maxOf(w, h) * 3f
                canvas.drawRect(cx - maxDim, cy - maxDim, cx + maxDim, cy + maxDim, blendPaint)
                canvas.restore()
                canvas.restoreToCount(layerId)
            }
            BlurType.LINEAR -> {
                // Sharp below the line and for [offset] px above it, then ramps up to full blur.
                val offset = blurFocusPx(config, source.width, source.height)
                val transition = blurTransitionPx(config, source.width, source.height)
                val yFull = cy - offset - transition
                val yStart = cy - offset
                val (colors, stops) = rampStops(0f, 1f, rising = false)
                val linearShader = LinearGradient(cx, yFull, cx, yStart, colors, stops, Shader.TileMode.CLAMP)

                val layerId = canvas.saveLayer(0f, 0f, w, h, null)
                canvas.drawBitmap(blurredAll, 0f, 0f, null)
                canvas.save()
                canvas.rotate(config.focusAngle, cx, cy)
                val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.shader = linearShader
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                val maxDim = maxOf(w, h) * 3f
                canvas.drawRect(cx - maxDim, cy - maxDim, cx + maxDim, cy + maxDim, blendPaint)
                canvas.restore()
                canvas.restoreToCount(layerId)
            }
            BlurType.NONE, BlurType.GAUSSIAN -> Unit
        }
        if (ownsBlurred && blurredAll !== source && blurredAll !== result && !blurredAll.isRecycled) {
            blurredAll.recycle()
        }
        return result
    }

    /** Applies Shadows and Highlights pixel adjustment strictly targeting deep shadows (< 0.35 lum) and bright highlights (> 0.65 lum). */
    /** [isStale]: checked between bands; once true the work stops and [source] is returned. */
    fun applyShadowsHighlights(
        source: Bitmap,
        shadows: Float,
        highlights: Float,
        isStale: () -> Boolean = { false },
    ): Bitmap {
        if (shadows == 0f && highlights == 0f) return source
        if (source.isRecycled) return source
        val w = source.width
        val h = source.height
        val out = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return source
        } catch (_: OutOfMemoryError) {
            return source
        }
        val s = shadows.coerceIn(-1f, 1f)
        val hl = highlights.coerceIn(-1f, 1f)
        // A band of rows at a time: a full-size photo as one array was 50-70 MB on the heap.
        val bandRows = bandRows(w, h)
        val pixels = IntArray(w * bandRows)
        var y0 = 0
        while (y0 < h) {
            if (isStale()) {
                out.recycle()
                return source
            }
            val rows = minOf(bandRows, h - y0)
            try {
                source.getPixels(pixels, 0, w, 0, y0, w, rows)
            } catch (_: Exception) {
                out.recycle()
                return source
            }
            shadowsHighlights(pixels, w * rows, s, hl)
            out.setPixels(pixels, 0, w, 0, y0, w, rows)
            y0 += rows
        }
        return out
    }

    /** The Shadows and Highlights change of the first [count] [pixels], in place. */
    internal fun shadowsHighlights(pixels: IntArray, count: Int, s: Float, hl: Float) {
        for (i in 0 until count) {
            val p = pixels[i]
            val a = (p ushr 24)
            var r = (p shr 16) and 0xff
            var g = (p shr 8) and 0xff
            var b = p and 0xff

            val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f

            // Shadows: affects dark areas (lum < 0.65) with smooth linear falloff
            val shadowWeight = ((0.65f - lum) / 0.65f).coerceIn(0f, 1f)

            // Highlights: affects bright areas (lum > 0.35) with smooth linear falloff
            val highlightWeight = ((lum - 0.35f) / 0.65f).coerceIn(0f, 1f)

            if (shadowWeight > 0f && s != 0f) {
                if (s > 0f) {
                    // Brighten dark fragments with strong exposure lift + multiplicative boost
                    val lift = s * shadowWeight * 150f
                    val mult = 1f + s * shadowWeight * 2.5f
                    r = (r * mult + lift).roundToInt().coerceIn(0, 255)
                    g = (g * mult + lift).roundToInt().coerceIn(0, 255)
                    b = (b * mult + lift).roundToInt().coerceIn(0, 255)
                } else {
                    // Darken dark fragments deeper
                    val mult = 1f + s * shadowWeight * 1.2f
                    val crush = s * shadowWeight * 60f
                    r = (r * mult + crush).roundToInt().coerceIn(0, 255)
                    g = (g * mult + crush).roundToInt().coerceIn(0, 255)
                    b = (b * mult + crush).roundToInt().coerceIn(0, 255)
                }
            }

            if (highlightWeight > 0f && hl != 0f) {
                // Adjust exposure for bright fragments with strong delta
                val delta = hl * highlightWeight * 140f
                r = (r + delta).roundToInt().coerceIn(0, 255)
                g = (g + delta).roundToInt().coerceIn(0, 255)
                b = (b + delta).roundToInt().coerceIn(0, 255)
            }

            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Applies 3x3 convolution sharpening filter. */
    /** [isStale]: checked between bands; once true the work stops and [source] is returned. */
    fun applySharpness(source: Bitmap, sharpness: Float, isStale: () -> Boolean = { false }): Bitmap {
        if (abs(sharpness) <= 0.01f) return source
        if (source.isRecycled) return source
        val w = source.width
        val h = source.height
        val out = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return source
        } catch (_: OutOfMemoryError) {
            return source
        }
        // Below 0 the same filter softens: at -100% each pixel is the even mix of itself and its
        // four neighbours (a light blur).
        val s = if (sharpness > 0f) (sharpness * 3.5f).coerceIn(0f, 8.0f) else (sharpness * 0.2f).coerceIn(-0.2f, 0f)
        // Neighbour distance grows with the image: the preview (downsampled) and the saved file
        // (full size) then sharpen the same detail and look the same.
        val step = (maxOf(w, h) / SHARPEN_REFERENCE_LONG_SIDE).roundToInt().coerceAtLeast(1)
        // A band of rows at a time, read with the [step] rows above and below it that it looks at
        // (two full-size arrays were 100-130 MB on the heap).
        val bandRows = bandRows(w, h)
        val srcPixels = IntArray(w * minOf(h, bandRows + 2 * step))
        val dstPixels = IntArray(w * bandRows)
        var y0 = 0
        while (y0 < h) {
            if (isStale()) {
                out.recycle()
                return source
            }
            val rows = minOf(bandRows, h - y0)
            val readFrom = maxOf(0, y0 - step)
            val readTo = minOf(h, y0 + rows + step)
            try {
                source.getPixels(srcPixels, 0, w, 0, readFrom, w, readTo - readFrom)
            } catch (_: Exception) {
                out.recycle()
                return source
            }
            sharpenRows(srcPixels, readFrom, dstPixels, y0, rows, w, h, step, s)
            out.setPixels(dstPixels, 0, w, 0, y0, w, rows)
            y0 += rows
        }
        return out
    }

    /**
     * Sharpens rows [y0] until [y0] + [rows] of a [w] x [h] image into [dst] (from its start).
     * [srcPixels] holds the image's rows from [srcFirstRow] on, at least [step] rows around the band.
     */
    internal fun sharpenRows(
        srcPixels: IntArray,
        srcFirstRow: Int,
        dst: IntArray,
        y0: Int,
        rows: Int,
        w: Int,
        h: Int,
        step: Int,
        s: Float,
    ) {
        val centerWeight = 1f + 4f * s
        for (y in y0 until y0 + rows) {
            val yOffset = (y - srcFirstRow) * w
            val yPrev = (maxOf(0, y - step) - srcFirstRow) * w
            val yNext = (minOf(h - 1, y + step) - srcFirstRow) * w
            val dstOffset = (y - y0) * w
            for (x in 0 until w) {
                val idx = yOffset + x
                val xPrev = maxOf(0, x - step)
                val xNext = minOf(w - 1, x + step)

                val c = srcPixels[idx]
                val a = c ushr 24
                val cr = (c shr 16) and 0xff
                val cg = (c shr 8) and 0xff
                val cb = c and 0xff

                val top = srcPixels[yPrev + x]
                val bot = srcPixels[yNext + x]
                val left = srcPixels[yOffset + xPrev]
                val right = srcPixels[yOffset + xNext]

                val neighborR = ((top shr 16) and 0xff) + ((bot shr 16) and 0xff) +
                    ((left shr 16) and 0xff) + ((right shr 16) and 0xff)
                val neighborG = ((top shr 8) and 0xff) + ((bot shr 8) and 0xff) +
                    ((left shr 8) and 0xff) + ((right shr 8) and 0xff)
                val neighborB = (top and 0xff) + (bot and 0xff) + (left and 0xff) + (right and 0xff)

                val newR = (cr * centerWeight - s * neighborR).roundToInt().coerceIn(0, 255)
                val newG = (cg * centerWeight - s * neighborG).roundToInt().coerceIn(0, 255)
                val newB = (cb * centerWeight - s * neighborB).roundToInt().coerceIn(0, 255)

                dst[dstOffset + x] = (a shl 24) or (newR shl 16) or (newG shl 8) or newB
            }
        }
    }

    /** Rows per band for the per-band filters: about [BAND_PIXELS] pixels, at least one row. */
    private fun bandRows(w: Int, h: Int): Int = (BAND_PIXELS / w.coerceAtLeast(1)).coerceIn(1, h.coerceAtLeast(1))

    private var cachedPreKey: List<Any>? = null
    private var cachedPreRef: java.lang.ref.WeakReference<Bitmap>? = null
    /** The source the cached result was made from, compared by identity (a hash code can repeat). */
    private var cachedPreSourceRef: java.lang.ref.WeakReference<Bitmap>? = null

    private fun preEffectsFor(
        source: Bitmap,
        shadows: Float,
        highlights: Float,
        sharpness: Float,
        isStale: () -> Boolean,
    ): Bitmap? {
        val key = listOf(source.generationId, shadows, highlights, sharpness)
        val generation = synchronized(cacheLock) {
            cachedPreRef?.get()?.let { cached ->
                if (!cached.isRecycled && cachedPreKey == key && cachedPreSourceRef?.get() === source) return cached
            }
            cacheGeneration
        }
        // The steps read the source and write new bitmaps, so an ARGB_8888 source needs no copy
        // first (a full extra copy on every slider move); others (hardware) are copied to be read.
        var current = if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            try {
                source.copy(Bitmap.Config.ARGB_8888, true)
            } catch (_: Exception) {
                return null
            } catch (_: OutOfMemoryError) {
                return null
            }
        }
        var owned = current !== source
        // Each step makes a new bitmap: the one it was made from, when this function made it,
        // goes at once (left to GC, native memory grew faster than it was freed).
        fun advance(next: Bitmap) {
            if (next === current) return
            if (owned) current.recycle()
            current = next
            owned = true
        }
        fun dropIfStale(): Boolean {
            if (!isStale()) return false
            if (owned) current.recycle()
            return true
        }
        if (shadows != 0f || highlights != 0f) {
            advance(applyShadowsHighlights(current, shadows, highlights, isStale))
        }
        if (dropIfStale()) return null
        if (abs(sharpness) > 0.01f) {
            advance(applySharpness(current, sharpness, isStale))
        }
        if (dropIfStale()) return null
        // Both steps failed (out of memory): the cached result must never be the source itself.
        if (!owned) {
            current = try {
                source.copy(Bitmap.Config.ARGB_8888, true)
            } catch (_: Exception) {
                return null
            } catch (_: OutOfMemoryError) {
                return null
            }
        }
        synchronized(cacheLock) {
            if (generation == cacheGeneration) {
                cachedPreKey = key
                cachedPreRef = java.lang.ref.WeakReference(current)
                cachedPreSourceRef = java.lang.ref.WeakReference(source)
            }
        }
        return current
    }

    /**
     * Live preview with shadows, highlights, sharpness and blur applied. The result may be a cached
     * bitmap: callers must not recycle it (the editor lets GC reclaim preview bitmaps).
     */
    fun generateLivePreviewBitmap(
        source: Bitmap,
        editState: ImageEditState,
        context: Context? = null,
        /** True once a newer slider value made this result useless: the rest is skipped. */
        isStale: () -> Boolean = { false },
    ): Bitmap? {
        if (source.isRecycled) return null

        val hasShadowsHighlights = editState.shadows != 0f || editState.highlights != 0f
        val hasSharpness = abs(editState.sharpness) > 0.01f
        val hasBlur = editState.blur.type != BlurType.NONE && editState.blur.radius > 0.5f

        if (!hasShadowsHighlights && !hasSharpness && !hasBlur) {
            return null
        }

        val pre = if (hasShadowsHighlights || hasSharpness) {
            preEffectsFor(source, editState.shadows, editState.highlights, editState.sharpness, isStale) ?: return null
        } else {
            source
        }
        if (!hasBlur) return pre.takeIf { !it.isRecycled }
        if (isStale()) return null

        val precomputed = getOrCreateBlurredBase(pre, editState.blur.radius)
        if (pre.isRecycled || precomputed.isRecycled) return null
        // A plain blur over everything is the blurred base itself; the preview may be a cached
        // bitmap (see above), so no full copy is needed for it.
        if (editState.blur.type == BlurType.GAUSSIAN) return precomputed
        if (isStale()) return null
        val blurred = applyBlur(pre, editState.blur, precomputedBlurred = precomputed)
        return if (blurred.isRecycled) null else blurred
    }

    /** Samples pixel color at normalized (0..1) coordinates. */
    fun sampleBitmapPixelColor(bitmap: Bitmap, normalizedX: Float, normalizedY: Float): Int {
        val px = (normalizedX * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val py = (normalizedY * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        return bitmap.getPixel(px, py)
    }

    /** Crops bitmap according to normalized CropRect. */
    fun applyCrop(source: Bitmap, crop: CropRect): Bitmap {
        if (!crop.isValid || source.isRecycled) return source
        if (crop.left <= 0.001f && crop.top <= 0.001f && crop.right >= 0.999f && crop.bottom >= 0.999f) return source
        val cropX = (crop.left.coerceIn(0f, 1f) * source.width).roundToInt().coerceIn(0, source.width - 1)
        val cropY = (crop.top.coerceIn(0f, 1f) * source.height).roundToInt().coerceIn(0, source.height - 1)
        val cropW = ((crop.right - crop.left).coerceIn(0.01f, 1f) * source.width).roundToInt().coerceIn(1, source.width - cropX)
        val cropH = ((crop.bottom - crop.top).coerceIn(0.01f, 1f) * source.height).roundToInt().coerceIn(1, source.height - cropY)
        return try {
            Bitmap.createBitmap(source, cropX, cropY, cropW, cropH)
        } catch (_: Exception) {
            source
        }
    }

    suspend fun processAndSave(
        context: Context,
        sourceFile: File?,
        sourceUri: Uri?,
        destinationFile: File,
        editState: ImageEditState,
        webpLossless: Boolean = false,
        /** The result replaces the original (overwrite): its location stays with it. */
        keepLocation: Boolean = false,
    ): ExportResult = withContext(Dispatchers.IO) {
        var finalBitmap: Bitmap? = null
        try {
            // 1. Decode original bitmap
            val sourceBitmap = decodeForExport(context, sourceFile, sourceUri) ?: return@withContext ExportResult.FAILED

            // 2-6. Crop, geometry, effects, colour and the layers on top.
            finalBitmap = renderEdits(context, sourceBitmap, editState)

            // 7. Compress and save
            destinationFile.parentFile?.mkdirs()
            val format = when (encoderExtensionFor(destinationFile.name)) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        if (webpLossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                }
                else -> Bitmap.CompressFormat.JPEG
            }

            // compress() reports a failed encode (full disk, WebP side over 16383 px) by returning
            // false and leaves a partial file: the caller would then overwrite the original with it.
            val written = FileOutputStream(destinationFile).use { out ->
                finalBitmap.compress(format, if (webpLossless) 100 else 95, out).also { ok ->
                    if (ok) {
                        out.flush()
                        runCatching { out.fd.sync() }
                    }
                }
            }
            val check = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destinationFile.absolutePath, check)
            if (!written || check.outWidth != finalBitmap.width || check.outHeight != finalBitmap.height) {
                dev.ander.alexet.data.AppLog.w("ImageEditEngine", "Encoded file is incomplete (written=$written, ${check.outWidth}x${check.outHeight})")
                destinationFile.delete()
                return@withContext ExportResult.FAILED
            }
            copyExifForExport(context, sourceFile, sourceUri, destinationFile, finalBitmap.width, finalBitmap.height, keepLocation)
            ExportResult.SAVED
        } catch (oom: OutOfMemoryError) {
            dev.ander.alexet.data.AppLog.e("ImageEditEngine", "Out of memory while exporting", oom)
            ExportResult.OUT_OF_MEMORY
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            dev.ander.alexet.data.AppLog.e("ImageEditEngine", "Error processing image", t)
            ExportResult.FAILED
        } finally {
            finalBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /**
     * An animated GIF with the edits on every frame: each frame keeps its delay and see-through
     * pixels. Frames are read, edited and written one at a time, so memory does not grow with the
     * length of the GIF. [onProgress] gets 0..1.
     */
    suspend fun processAndSaveGif(
        context: Context,
        sourceFile: File?,
        sourceUri: Uri?,
        destinationFile: File,
        editState: ImageEditState,
        onProgress: (Float) -> Unit = {},
    ): ExportResult = withContext(Dispatchers.IO) {
        val job = coroutineContext[kotlinx.coroutines.Job]
        // Stickers are decoded once for all the frames.
        val stickers = HashMap<Uri, Bitmap?>()
        val encoder = dev.ander.alexet.data.videoeditor.GifEncoder().apply {
            keepTransparency = true
            setRepeat(0)
        }
        try {
            val data = readSourceBytes(context, sourceFile, sourceUri) ?: return@withContext ExportResult.FAILED
            val info = GifFrameReader.info(data) ?: return@withContext ExportResult.FAILED
            if (info.frameCount <= 0) return@withContext ExportResult.FAILED
            destinationFile.parentFile?.mkdirs()
            if (!encoder.start(destinationFile)) return@withContext ExportResult.FAILED
            var ok = true
            var done = 0
            val given = GifFrameReader.forEachFrame(data) { pixels, w, h, delayMs ->
                if (job?.isActive == false) return@forEachFrame false
                val frame = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                val edited = renderEdits(context, frame, editState, stickerBitmap = { uri ->
                    stickers.getOrPut(uri) { decodeSourceBitmap(context, null, uri, maxDim = 2048) }
                }, ownsStickers = false)
                encoder.setDelay(delayMs)
                ok = encoder.addFrame(edited)
                edited.recycle()
                done++
                onProgress(done.toFloat() / info.frameCount)
                ok
            }
            job?.let { if (!it.isActive) throw CancellationException() }
            if (given <= 0 || !ok || !encoder.finish()) {
                destinationFile.delete()
                return@withContext ExportResult.FAILED
            }
            ExportResult.SAVED
        } catch (oom: OutOfMemoryError) {
            dev.ander.alexet.data.AppLog.e("ImageEditEngine", "Out of memory while exporting a GIF", oom)
            destinationFile.delete()
            ExportResult.OUT_OF_MEMORY
        } catch (t: Throwable) {
            destinationFile.delete()
            if (t is CancellationException) throw t
            dev.ander.alexet.data.AppLog.e("ImageEditEngine", "Error processing GIF", t)
            ExportResult.FAILED
        } finally {
            encoder.close()
            stickers.values.forEach { it?.recycle() }
        }
    }

    /** The whole file (a GIF is read at once to step through its frames); null over 256 MB or unreadable. */
    fun readSourceBytes(context: Context, sourceFile: File?, sourceUri: Uri?): ByteArray? = runCatching {
        if (sourceFile != null && sourceFile.exists()) {
            if (sourceFile.length() > MAX_GIF_BYTES) return null
            sourceFile.readBytes()
        } else if (sourceUri != null) {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_GIF_BYTES) return null
                }
                out.toByteArray()
            }
        } else {
            null
        }
    }.getOrNull()

    private const val MAX_GIF_BYTES = 256L * 1024L * 1024L

    /**
     * Steps 2-6 of the export on [source] (taken over: recycled unless it is the result): crop in
     * source space, flip and rotation, pixel effects, colour, then stickers, texts and brush
     * strokes. Returns a mutable ARGB bitmap the caller owns. [stickerBitmap] gives each sticker's
     * picture; with [ownsStickers] they are recycled once drawn.
     */
    fun renderEdits(
        context: Context,
        source: Bitmap,
        editState: ImageEditState,
        stickerBitmap: (Uri) -> Bitmap? = { uri -> decodeSourceBitmap(context, null, uri, maxDim = 2048) },
        ownsStickers: Boolean = true,
    ): Bitmap {
        // The bitmap alive at each step; recycled on failure.
        var current: Bitmap = source
        try {
            // 2. Crop in SOURCE space (committed + pending crop, mapped back through the geometry
            //    by the editor), then geometry: the same order the preview uses (cropped bitmap,
            //    rotated/flipped by the image layer).
            if (editState.crop.isValid &&
                (editState.crop.left > 0.001f || editState.crop.top > 0.001f ||
                    editState.crop.right < 0.999f || editState.crop.bottom < 0.999f)
            ) {
                val cropped = applyCrop(current, editState.crop)
                if (cropped != current) {
                    current.recycle()
                    current = cropped
                }
            }

            // 3. Geometry: flip FIRST then rotate (matches graphicsLayer in the preview)
            val matrix = Matrix()
            applyUserGeometryToMatrix(
                matrix,
                current.width,
                current.height,
                editState.rotation,
                editState.flipH,
                editState.flipV,
            )
            if (!matrix.isIdentity) {
                val created = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
                if (created != current) {
                    current.recycle()
                    current = created
                }
            }

            // 4. Pixel effects before colour: the preview runs them on the raw bitmap and applies
            //    the colour matrix last (as a draw-time ColorFilter).
            if (editState.shadows != 0f || editState.highlights != 0f) {
                val withShadows = applyShadowsHighlights(current, editState.shadows, editState.highlights)
                if (withShadows != current) {
                    current.recycle()
                    current = withShadows
                }
            }
            if (abs(editState.sharpness) > 0.01f) {
                val sharpened = applySharpness(current, editState.sharpness)
                if (sharpened != current) {
                    current.recycle()
                    current = sharpened
                }
            }
            if (editState.blur.type != BlurType.NONE) {
                val blurred = applyBlur(current, editState.blur)
                if (blurred != current) {
                    current.recycle()
                    current = blurred
                }
            }

            // 5. Colour adjustments last
            val hasColorAdjustments = editState.brightness != 0f || editState.contrast != 0f ||
                editState.saturation != 0f || editState.exposure != 0f ||
                editState.gamma != 0f || editState.temperature != 0f
            if (hasColorAdjustments) {
                val colorArray = createColorMatrixArray(
                    brightness = editState.brightness,
                    contrast = editState.contrast,
                    saturation = editState.saturation,
                    exposure = editState.exposure,
                    gamma = editState.gamma,
                    temperature = editState.temperature,
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix(colorArray))
                }
                val target = Bitmap.createBitmap(
                    current.width,
                    current.height,
                    current.config ?: Bitmap.Config.ARGB_8888,
                )
                Canvas(target).drawBitmap(current, 0f, 0f, paint)
                current.recycle()
                current = target
            }

            // 6. Final Canvas Layers: Stickers, Texts, Brush Strokes
            val destW = current.width
            val destH = current.height
            // Draw straight onto the result when it is already a mutable ARGB bitmap: a copy would
            // double the peak memory of a full-size export.
            if (!current.isMutable || current.config != Bitmap.Config.ARGB_8888) {
                val copy = current.copy(Bitmap.Config.ARGB_8888, true)
                current.recycle()
                current = copy
            }
            val finalCanvas = Canvas(current)

            // 6a. Stickers (Images)
            if (editState.stickers.isNotEmpty()) {
                val stickerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                for (sticker in editState.stickers) {
                    val stickerBmp = stickerBitmap(sticker.uri)?.takeIf { !it.isRecycled } ?: continue
                    val stickerMatrix = Matrix()
                    val sw = stickerBmp.width.toFloat()
                    val sh = stickerBmp.height.toFloat()

                    val baseTargetSize = stickerBasePx(destW.toFloat())
                    val baseScale = baseTargetSize / maxOf(sw, sh)
                    val scaleX = baseScale * sticker.scale * (if (sticker.flipH) -1f else 1f)
                    val scaleY = baseScale * sticker.scale * (if (sticker.flipV) -1f else 1f)

                    stickerMatrix.postTranslate(-sw / 2f, -sh / 2f)
                    stickerMatrix.postScale(scaleX, scaleY)
                    stickerMatrix.postRotate(sticker.rotationDeg)
                    stickerMatrix.postTranslate(sticker.normalizedX * destW, sticker.normalizedY * destH)

                    finalCanvas.drawBitmap(stickerBmp, stickerMatrix, stickerPaint)
                    if (ownsStickers) stickerBmp.recycle()
                }
            }

            // 6b. Texts
            if (editState.texts.isNotEmpty()) {
                for (text in editState.texts) {
                    if (text.text.isBlank()) continue
                    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = text.color
                        // Unscaled size; text.scale is applied to the canvas like graphicsLayer in the preview.
                        textSize = textFontPx(text.fontSizeSp, destW.toFloat())
                        isUnderlineText = text.isUnderline
                        isStrikeThruText = text.isStrikethrough
                        val style = when {
                            text.isBold && text.isItalic -> Typeface.BOLD_ITALIC
                            text.isBold -> Typeface.BOLD
                            text.isItalic -> Typeface.ITALIC
                            else -> Typeface.NORMAL
                        }
                        typeface = Typeface.create(Typeface.DEFAULT, style)
                    }

                    // Box as wide as the longest line (preview wraps content), capped at the image width.
                    val desired = kotlin.math.ceil(Layout.getDesiredWidth(text.text, textPaint)).toInt()
                    val maxTextW = desired.coerceIn(1, destW.coerceAtLeast(1))
                    val alignment = when (text.alignment) {
                        0 -> Layout.Alignment.ALIGN_NORMAL
                        2 -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_CENTER
                    }

                    val layout = StaticLayout.Builder.obtain(text.text, 0, text.text.length, textPaint, maxTextW)
                        .setAlignment(alignment)
                        .build()

                    finalCanvas.save()
                    finalCanvas.translate(text.normalizedX * destW, text.normalizedY * destH)
                    val textScaleX = if (text.flipH) -1f else 1f
                    val textScaleY = if (text.flipV) -1f else 1f
                    finalCanvas.rotate(text.rotationDeg)
                    finalCanvas.scale(textScaleX * text.scale, textScaleY * text.scale)
                    finalCanvas.translate(-layout.width / 2f, -layout.height / 2f)
                    layout.draw(finalCanvas)
                    finalCanvas.restore()
                }
            }

            // 6c. Brush strokes (Rendered on top of stickers and text to match overlay canvas)
            if (editState.brushStrokes.isNotEmpty()) {
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                for (stroke in editState.brushStrokes) {
                    if (stroke.points.isEmpty()) continue
                    val strokeW = brushWidthPx(stroke.strokeWidthPx, destW.toFloat())
                    // Colour alpha (picker) x brush opacity, same as the preview.
                    val colorAlpha = Color.alpha(stroke.color) / 255f
                    val alphaInt = (colorAlpha * stroke.alpha * 255).roundToInt().coerceIn(0, 255)
                    if (stroke.points.size == 1) {
                        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.FILL
                            color = stroke.color
                            alpha = alphaInt
                        }
                        val pt = stroke.points[0]
                        finalCanvas.drawCircle(pt.x * destW, pt.y * destH, strokeW / 2f, dotPaint)
                    } else {
                        strokePaint.color = stroke.color
                        strokePaint.alpha = alphaInt
                        strokePaint.strokeWidth = strokeW
                        val path = android.graphics.Path()
                        val p0 = stroke.points[0]
                        path.moveTo(p0.x * destW, p0.y * destH)
                        for (k in 1 until stroke.points.size) {
                            val pt = stroke.points[k]
                            path.lineTo(pt.x * destW, pt.y * destH)
                        }
                        finalCanvas.drawPath(path, strokePaint)
                    }
                }
            }
            return current
        } catch (t: Throwable) {
            if (!current.isRecycled) current.recycle()
            throw t
        }
    }

    fun readExifOrientation(context: Context, sourceFile: File?, sourceUri: Uri?): Int {
        return try {
            if (sourceFile != null && sourceFile.exists()) {
                ExifInterface(sourceFile.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } else if (sourceUri != null) {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } else {
                ExifInterface.ORIENTATION_NORMAL
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val ops = exifOrientationOps(orientation)
        if (ops.isEmpty()) return bitmap
        val matrix = Matrix()
        applyExifOpsToMatrix(matrix, ops)
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun decodeSourceBitmap(context: Context, sourceFile: File?, sourceUri: Uri?, maxDim: Int = 4096): Bitmap? {
        val (width, height) = readImageBounds(context, sourceFile, sourceUri) ?: return null
        var sample = 1
        val largest = maxOf(width, height)
        while (largest / sample > maxDim) {
            sample *= 2
        }
        val decoded = decodeWithSample(context, sourceFile, sourceUri, sample) ?: return null
        return orient(context, sourceFile, sourceUri, decoded)
    }

    /**
     * Decode for saving: full resolution up to [maxPixels]; larger sources are scaled
     * proportionally to that budget (not by power-of-two steps, which quartered e.g. 4100 px).
     */
    fun decodeForExport(context: Context, sourceFile: File?, sourceUri: Uri?, maxPixels: Long = EXPORT_MAX_PIXELS): Bitmap? {
        val (width, height) = readImageBounds(context, sourceFile, sourceUri) ?: return null
        val (targetW, targetH) = exportTargetSize(width, height, maxPixels)
        val sample = exportSampleSize(width, height, targetW, targetH, maxPixels)
        val decoded = decodeWithSample(context, sourceFile, sourceUri, sample) ?: return null
        val sized = if (decoded.width > targetW || decoded.height > targetH) {
            val scaled = Bitmap.createScaledBitmap(
                decoded,
                minOf(targetW, decoded.width),
                minOf(targetH, decoded.height),
                true,
            )
            if (scaled != decoded) decoded.recycle()
            scaled
        } else {
            decoded
        }
        return orient(context, sourceFile, sourceUri, sized)
    }

    /** Raw (pre-EXIF-rotation) pixel size read from the header only. */
    fun readImageBounds(context: Context, sourceFile: File?, sourceUri: Uri?): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            if (sourceFile != null && sourceFile.exists()) {
                BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            } else if (sourceUri != null) {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return bounds.outWidth to bounds.outHeight
    }

    /** Size the export will have: unchanged when within [maxPixels], else scaled to fit it. */
    fun exportTargetSize(width: Int, height: Int, maxPixels: Long = EXPORT_MAX_PIXELS): Pair<Int, Int> {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels || pixels <= 0L) return width to height
        val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble())
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /**
     * Largest power-of-two sample that still decodes at or above the target size, so the final
     * resize only goes down. Capped so the intermediate decode stays within 2x [maxPixels].
     */
    fun exportSampleSize(width: Int, height: Int, targetW: Int, targetH: Int, maxPixels: Long = EXPORT_MAX_PIXELS): Int {
        var sample = 1
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) {
            sample *= 2
        }
        while ((width / sample).toLong() * (height / sample).toLong() > 2L * maxPixels) {
            sample *= 2
        }
        return sample
    }

    private fun decodeWithSample(context: Context, sourceFile: File?, sourceUri: Uri?, sample: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return if (sourceFile != null && sourceFile.exists()) {
            BitmapFactory.decodeFile(sourceFile.absolutePath, options)
        } else if (sourceUri != null) {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } else {
            null
        }
    }

    private fun orient(context: Context, sourceFile: File?, sourceUri: Uri?, decoded: Bitmap): Bitmap {
        val orientation = readExifOrientation(context, sourceFile, sourceUri)
        val oriented = try {
            applyExifOrientation(decoded, orientation)
        } catch (t: Throwable) {
            // Out of memory for the turned copy: the decoded one is not leaked meanwhile.
            if (!decoded.isRecycled) decoded.recycle()
            throw t
        }
        if (oriented != decoded && !decoded.isRecycled) {
            decoded.recycle()
        }
        return oriented
    }

    enum class WebpKind { NOT_WEBP, LOSSY, LOSSLESS, ANIMATED }

    /** Classifies a WebP from its first bytes (RIFF header + first chunk). */
    fun webpKind(header: ByteArray): WebpKind {
        if (header.size < 16) return WebpKind.NOT_WEBP
        fun tag(offset: Int) = String(header, offset, 4, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WEBP") return WebpKind.NOT_WEBP
        return when (tag(12)) {
            "VP8L" -> WebpKind.LOSSLESS
            // VP8X flags byte: bit 1 = animation.
            "VP8X" -> if (header.size > 20 && (header[20].toInt() and 0x02) != 0) WebpKind.ANIMATED else WebpKind.LOSSY
            else -> WebpKind.LOSSY
        }
    }

    fun readWebpKind(context: Context, sourceFile: File?, sourceUri: Uri?): WebpKind {
        val header = ByteArray(32)
        val read = runCatching {
            val stream = if (sourceFile != null && sourceFile.exists()) {
                sourceFile.inputStream()
            } else {
                sourceUri?.let { context.contentResolver.openInputStream(it) }
            }
            stream?.use { it.read(header) } ?: 0
        }.getOrDefault(0)
        return if (read <= 0) WebpKind.NOT_WEBP else webpKind(header.copyOf(read))
    }

    /**
     * Copies camera metadata (date, make/model, exposure, lens...) from the source into the saved
     * file. GPS is deliberately dropped; orientation is reset because rotation is baked into pixels.
     */
    fun copyExifForExport(
        context: Context,
        sourceFile: File?,
        sourceUri: Uri?,
        destinationFile: File,
        width: Int,
        height: Int,
        keepLocation: Boolean = false,
    ) {
        try {
            val source = if (sourceFile != null && sourceFile.exists()) {
                ExifInterface(sourceFile.absolutePath)
            } else {
                sourceUri?.let { uri ->
                    context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                }
            } ?: return
            val target = ExifInterface(destinationFile.absolutePath)
            EXPORT_EXIF_TAGS.forEach { tag ->
                source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
            }
            if (keepLocation) {
                // Overwriting the original must not erase where the photo was taken.
                GPS_EXIF_TAGS.forEach { tag ->
                    source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
                }
            }
            target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            target.saveAttributes()
        } catch (e: Exception) {
            // Metadata is best effort: the edited pixels are already written.
            dev.ander.alexet.data.AppLog.w("ImageEditEngine", "EXIF copy failed", e)
        }
    }

    /** 16.7 MP (4096x4096): largest export kept in memory with effects applied. */
    const val EXPORT_MAX_PIXELS: Long = 4096L * 4096L

    private val GPS_EXIF_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
    )

    // GPS tags are intentionally absent: edited copies do not carry location (overwrite adds them).
    private val EXPORT_EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_SENSITIVITY_TYPE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_SENSING_METHOD,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_GAIN_CONTROL,
        ExifInterface.TAG_EXIF_VERSION,
    )
}
