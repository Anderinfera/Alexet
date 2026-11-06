package dev.ander.alexet.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Size
import coil3.size.pxOrElse
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.OtherFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Colour and icon of a kind of file. */
private class KindStyle(val color: Color, val icon: ImageVector)

private fun styleOf(kind: OtherFiles.Kind, extension: String): KindStyle = when (kind) {
    OtherFiles.Kind.PDF -> KindStyle(Color(0xFFE53935), Icons.Rounded.PictureAsPdf)
    OtherFiles.Kind.WORD -> KindStyle(Color(0xFF1E88E5), Icons.Rounded.Description)
    OtherFiles.Kind.SHEET -> KindStyle(Color(0xFF43A047), Icons.Rounded.TableChart)
    OtherFiles.Kind.SLIDES -> KindStyle(Color(0xFFFB8C00), Icons.Rounded.Slideshow)
    OtherFiles.Kind.TEXT -> KindStyle(Color(0xFF78909C), Icons.Rounded.Article)
    OtherFiles.Kind.CODE -> KindStyle(Color(0xFF7E57C2), Icons.Rounded.Code)
    OtherFiles.Kind.EBOOK -> KindStyle(Color(0xFF8D6E63), Icons.Rounded.MenuBook)
    OtherFiles.Kind.ARCHIVE -> KindStyle(Color(0xFFF9A825), Icons.Rounded.FolderZip)
    OtherFiles.Kind.APP -> KindStyle(
        Color(0xFF00897B),
        if (extension.startsWith("apk") || extension == "xapk" || extension == "aab") Icons.Rounded.Android else Icons.Rounded.Apps,
    )
    OtherFiles.Kind.AUDIO -> KindStyle(Color(0xFFD81B60), Icons.Rounded.AudioFile)
    OtherFiles.Kind.FONT -> KindStyle(Color(0xFF546E7A), Icons.Rounded.FontDownload)
    OtherFiles.Kind.OTHER -> KindStyle(Color(0xFF607D8B), Icons.Rounded.InsertDriveFile)
}

/** Colour and icon for any file name (also photos and videos), e.g. rows of an archive's contents. */
internal fun fileIconStyle(name: String): Pair<Color, ImageVector> {
    val mime = dev.ander.alexet.data.NomediaScanner.mimeOf(name)
    return when {
        mime?.startsWith("image/") == true -> Color(0xFF26A69A) to Icons.Rounded.Image
        mime?.startsWith("video/") == true -> Color(0xFF5C6BC0) to Icons.Rounded.Movie
        else -> styleOf(OtherFiles.kindOf(name), OtherFiles.extensionOf(name)).let { it.color to it.icon }
    }
}

/** Kinds with a real preview: the first PDF page, an APK's app icon, a text file's first lines. */
private fun hasPreview(kind: OtherFiles.Kind, extension: String): Boolean = when (kind) {
    OtherFiles.Kind.PDF, OtherFiles.Kind.TEXT, OtherFiles.Kind.CODE -> true
    OtherFiles.Kind.APP -> extension == "apk"
    else -> false
}

/**
 * A square tile for a file that is not a photo or video: its type's icon and extension on the
 * type's colour, a real preview where there is one, and always a type badge in the corner so a
 * PDF page is never taken for a photo.
 */
@Composable
fun OtherFileThumbnail(media: GalleryMedia, modifier: Modifier = Modifier) {
    val extension = remember(media.displayName) { OtherFiles.extensionOf(media.displayName) }
    val kind = remember(media.displayName) { OtherFiles.kindOf(media.displayName) }
    val style = remember(kind, extension) { styleOf(kind, extension) }
    val label = extension.uppercase().take(5).ifEmpty { "?" }
    val context = LocalContext.current
    val path = media.absolutePath
    val request = remember(path, media.dateModifiedMs, media.sizeBytes) {
        if (path == null || !hasPreview(kind, extension)) {
            null
        } else {
            val key = "other-${path}-${media.dateModifiedMs}-${media.sizeBytes}"
            ImageRequest.Builder(context)
                .data(OtherFilePreview(path))
                .memoryCacheKey(key)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .size(Size(PREVIEW_PX, PREVIEW_PX))
                .build()
        }
    }
    var previewShown by remember(request) { mutableStateOf(false) }
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val tint = style.color.copy(alpha = 0.16f).compositeOver(base)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(tint),
    ) {
        if (!previewShown) {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier.fillMaxWidth(0.42f).aspectRatio(1f),
                )
                Text(
                    text = label,
                    color = style.color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        if (request != null) {
            val isAppIcon = kind == OtherFiles.Kind.APP
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = if (isAppIcon) ContentScale.Fit else ContentScale.Crop,
                alignment = if (isAppIcon) Alignment.Center else Alignment.TopCenter,
                onSuccess = { previewShown = true },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isAppIcon) Modifier.padding(18.dp) else Modifier),
            )
        }
        FileTypeBadge(
            label = label,
            color = style.color,
            icon = style.icon,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(5.dp),
        )
    }
}

/** "PDF", "APK"…: on every file tile, also over a preview. */
@Composable
private fun FileTypeBadge(label: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Coil model for the preview of a non-photo file. */
data class OtherFilePreview(val path: String)

private const val PREVIEW_PX = 384

/** Previews made at once (each opens a file; PDFs are rendered). */
private val previewPermits = Semaphore(2)

class OtherFilePreviewFetcher(
    private val data: OtherFilePreview,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val file = File(data.path)
        if (!file.isFile) return@withContext null
        val size = maxOf(options.size.width.pxOrElse { PREVIEW_PX }, options.size.height.pxOrElse { PREVIEW_PX })
            .coerceIn(96, 768)
        val bitmap = previewPermits.withPermit {
            runCatching {
                when (OtherFiles.kindOf(file.name)) {
                    OtherFiles.Kind.PDF -> pdfFirstPage(file, size)
                    OtherFiles.Kind.APP -> apkIcon(options.context, file, size)
                    OtherFiles.Kind.TEXT, OtherFiles.Kind.CODE -> textLines(file, size)
                    else -> null
                }
            }.getOrNull()
        } ?: return@withContext null
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun pdfFirstPage(file: File, size: Int): Bitmap? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val scale = size.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    private fun apkIcon(context: Context, file: File, size: Int): Bitmap? {
        if (OtherFiles.extensionOf(file.name) != "apk") return null
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.path, 0) ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = file.path
        app.publicSourceDir = file.path
        val drawable = app.loadIcon(pm) ?: return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    private fun textLines(file: File, size: Int): Bitmap? {
        val buffer = ByteArray(TEXT_PREVIEW_BYTES)
        val read = file.inputStream().use { input ->
            var done = 0
            while (done < buffer.size) {
                val n = input.read(buffer, done, buffer.size - done)
                if (n < 0) break
                done += n
            }
            done
        }
        if (read <= 0) return null
        val text = String(buffer, 0, read, Charsets.UTF_8)
        // A binary file with a text extension: no preview, the icon stays.
        val control = text.count { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }
        if (control > text.length / 20) return null
        val lines = text.replace("\r", "").replace("\t", "  ").lines().take(TEXT_PREVIEW_LINES)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.rgb(250, 250, 248))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(55, 60, 66)
            typeface = Typeface.MONOSPACE
            textSize = size / 15f
        }
        val pad = size / 14f
        var y = pad + paint.textSize
        for (line in lines) {
            if (y > size - pad / 2) break
            canvas.drawText(line.take(60), pad, y, paint)
            y += paint.textSize * 1.25f
        }
        return bmp
    }

    class Factory : Fetcher.Factory<OtherFilePreview> {
        override fun create(data: OtherFilePreview, options: Options, imageLoader: ImageLoader): Fetcher =
            OtherFilePreviewFetcher(data, options)
    }
}

private const val TEXT_PREVIEW_BYTES = 2048
private const val TEXT_PREVIEW_LINES = 16
