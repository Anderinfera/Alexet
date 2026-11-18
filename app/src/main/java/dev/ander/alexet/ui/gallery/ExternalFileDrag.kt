package dev.ander.alexet.ui.gallery

import android.content.ClipData
import android.content.ClipDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import androidx.compose.runtime.compositionLocalOf

/** In split screen (any multi-window mode) two fingers drag the selected files out into the other app. */
val LocalExternalFileDrag = compositionLocalOf { false }

/** The selection made ready for a drag into another app: addresses as a share gives them, their types, a picture. */
class ExternalDragFiles(
    val uris: List<Uri>,
    val mimeTypes: List<String>,
    val thumbnail: Bitmap?,
)

/** Starts Android's drag and drop of [files] into other apps; false when the system refused it. */
fun startExternalFileDrag(view: View, files: ExternalDragFiles, accent: Int, onAccent: Int): Boolean {
    if (files.uris.isEmpty()) return false
    val types = files.mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray()
    val clip = ClipData(ClipDescription("Alexet", types), ClipData.Item(files.uris.first()))
    files.uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    // Global: other apps may take it; URI read: they may open the files.
    val flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
    return runCatching {
        view.startDragAndDrop(clip, FilesDragShadow(view, files, accent, onAccent), null, flags)
    }.getOrDefault(false)
}

/** The first file's picture (or the accent colour) in a rounded square, with the count when there are more. */
private class FilesDragShadow(
    view: View,
    private val files: ExternalDragFiles,
    private val accent: Int,
    private val onAccent: Int,
) : View.DragShadowBuilder() {
    private val density = view.resources.displayMetrics.density
    private val sizePx = (88 * density).toInt()

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(sizePx, sizePx)
        outShadowTouchPoint.set(sizePx / 2, sizePx / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        val size = sizePx.toFloat()
        val radius = 16 * density
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, size, size), radius, radius, Path.Direction.CW) })
        val thumb = files.thumbnail
        if (thumb != null && thumb.width > 0 && thumb.height > 0) {
            // Centre crop.
            val scale = maxOf(size / thumb.width, size / thumb.height)
            val w = thumb.width * scale
            val h = thumb.height * scale
            val dst = RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f)
            canvas.drawBitmap(thumb, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawColor(accent)
        }
        canvas.restore()
        val count = files.uris.size
        if (count > 1) {
            val badgeR = 14 * density
            val cx = size - badgeR - 4 * density
            val cy = badgeR + 4 * density
            canvas.drawCircle(cx, cy, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = onAccent
                textSize = 13 * density
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(if (count > 99) "99+" else count.toString(), cx, cy - (text.descent() + text.ascent()) / 2f, text)
        }
    }
}
