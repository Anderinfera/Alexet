package dev.ander.alexet.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A photo of a mosaic as it is laid out: its file, its size as shown (EXIF turn applied). */
data class MosaicSource(val media: GalleryMedia, val width: Int, val height: Int)

/**
 * One photo placed on the mosaic, in the mosaic's own pixels: where its top-left corner is and
 * how big it is drawn (its own resolution at scale 1).
 */
data class MosaicPiece(val source: MosaicSource, val x: Float, val y: Float, val scale: Float) {
    val width: Float get() = source.width * scale
    val height: Float get() = source.height * scale
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/** The part of the plane the pieces cover. */
data class MosaicBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

object MosaicRenderer {
    /** Above this the whole mosaic is made smaller (a bitmap that big would not fit in memory). */
    const val MAX_PIXELS = 64_000_000L

    /** The part of the plane the pieces cover. */
    fun bounds(pieces: List<MosaicPiece>): MosaicBounds {
        if (pieces.isEmpty()) return MosaicBounds(0f, 0f, 0f, 0f)
        return MosaicBounds(
            pieces.minOf { it.x },
            pieces.minOf { it.y },
            pieces.maxOf { it.right },
            pieces.maxOf { it.bottom },
        )
    }

    /** Size of the saved picture and the factor the layout is drawn at (1 unless too big). */
    fun outputSize(pieces: List<MosaicPiece>): Triple<Int, Int, Float> {
        val b = bounds(pieces)
        val w = b.width.coerceAtLeast(1f)
        val h = b.height.coerceAtLeast(1f)
        val pixels = w.toDouble() * h.toDouble()
        val factor = if (pixels > MAX_PIXELS) sqrt(MAX_PIXELS / pixels).toFloat() else 1f
        return Triple((w * factor).roundToInt().coerceAtLeast(1), (h * factor).roundToInt().coerceAtLeast(1), factor)
    }

    /** The size of [file] as shown: its EXIF turn applied. Null when it can't be read. */
    fun shownSize(file: File): Pair<Int, Int>? = imageInfo(file)?.shown

    /** Stored size and EXIF orientation (null when unreadable), read once for both uses. */
    private class ImageInfo(val width: Int, val height: Int, val orientation: Int?) {
        val shown: Pair<Int, Int>
            get() {
                val o = orientation
                val turned = o == android.media.ExifInterface.ORIENTATION_ROTATE_90 || o == android.media.ExifInterface.ORIENTATION_ROTATE_270 ||
                    o == android.media.ExifInterface.ORIENTATION_TRANSPOSE || o == android.media.ExifInterface.ORIENTATION_TRANSVERSE
                return if (turned) height to width else width to height
            }
    }

    private fun imageInfo(file: File): ImageInfo? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = runCatching {
            android.media.ExifInterface(file.absolutePath)
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        }.getOrNull()
        return ImageInfo(bounds.outWidth, bounds.outHeight, orientation)
    }

    /**
     * Draws the pieces (in their order: later ones on top) onto one picture, the empty parts black,
     * and writes it as JPEG to [out]. Each photo is read only as big as it is drawn.
     */
    fun render(pieces: List<MosaicPiece>, out: File, quality: Int = 95): Boolean {
        if (pieces.isEmpty()) return false
        val b = bounds(pieces)
        val (outW, outH, factor) = outputSize(pieces)
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            for (piece in pieces) {
                val path = piece.source.media.absolutePath ?: continue
                val dest = RectF(
                    (piece.x - b.left) * factor,
                    (piece.y - b.top) * factor,
                    (piece.right - b.left) * factor,
                    (piece.bottom - b.top) * factor,
                )
                val bitmap = decodeFor(File(path), dest.width(), dest.height()) ?: continue
                canvas.drawBitmap(bitmap, null, dest, paint)
                bitmap.recycle()
            }
            return runCatching {
                out.outputStream().use { stream -> result.compress(Bitmap.CompressFormat.JPEG, quality, stream) }
            }.getOrDefault(false)
        } finally {
            result.recycle()
        }
    }

    /** [file] read at the smallest whole factor still at least [w] x [h], turned upright. */
    private fun decodeFor(file: File, w: Float, h: Float): Bitmap? {
        val info = imageInfo(file) ?: return null
        val shown = info.shown
        var sample = 1
        while (shown.first / (sample * 2) >= w && shown.second / (sample * 2) >= h) sample *= 2
        val raw = runCatching {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        val orientation = info.orientation ?: return raw
        return dev.ander.alexet.ui.components.orientByExif(raw, orientation)
    }
}
