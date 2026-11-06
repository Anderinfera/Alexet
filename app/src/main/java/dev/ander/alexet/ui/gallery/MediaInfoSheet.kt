package dev.ander.alexet.ui.gallery

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.FolderDetails
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.ui.components.LocalVideoDurationFormat
import dev.ander.alexet.ui.components.formatBytes
import dev.ander.alexet.ui.components.formatDuration
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInfoSheet(
    media: List<GalleryMedia>,
    albums: List<Album> = emptyList(),
    /** Total size of the given folders' files; runs off the main thread. */
    albumsSizeBytes: (List<Album>) -> Long = { 0L },
    /** One folder's full info (path, files, dates); runs off the main thread. */
    folderDetails: (Album) -> FolderDetails? = { null },
    onDismiss: () -> Unit,
) {
    // Summed over the whole library (~10k rows): computed once per opened sheet, in the background.
    val foldersSize by produceState<Long?>(initialValue = null, albums) {
        value = if (albums.isEmpty()) 0L else withContext(Dispatchers.Default) { albumsSizeBytes(albums) }
    }
    val singleFolder = albums.singleOrNull()?.takeIf { media.isEmpty() }
    val details by produceState<FolderDetails?>(initialValue = null, singleFolder) {
        value = singleFolder?.let { album -> withContext(Dispatchers.IO) { runCatching { folderDetails(album) }.getOrNull() } }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
        ) {
            when {
                media.size == 1 && albums.isEmpty() -> SingleFileInfo(media.first())
                albums.size == 1 && media.isEmpty() -> SingleAlbumInfo(albums.first(), details, foldersSize)
                else -> SelectionSummary(media, albums, foldersSize)
            }
        }
    }
}

@Composable
internal fun SingleFileInfo(item: GalleryMedia) {
    val context = LocalContext.current
    val resolution by produceState<Pair<Int, Int>?>(
        initialValue = if (item.width > 0 && item.height > 0) item.width to item.height else null,
        key1 = item.id,
    ) {
        if (item.width > 0 && item.height > 0) {
            value = item.width to item.height
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            resolveMediaDimensions(context, item)
        }
    }

    val durationMs by produceState(
        initialValue = if (item.durationMs > 0) item.durationMs else 0L,
        key1 = item.id,
    ) {
        if (item.durationMs > 0) {
            value = item.durationMs
            return@produceState
        }
        if (item.isVideo) {
            value = withContext(Dispatchers.IO) {
                resolveVideoDuration(context, item)
            }
        }
    }

    // A tap copies the name (with its extension) to the clipboard.
    Text(
        text = item.displayName,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable { copyFileName(context, item.displayName) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
    // A file from another app whose place is unknown: the address it came by, not a bare name.
    val path = item.storagePath.takeIf { '/' in it || item.uri.scheme != "content" } ?: item.uri.toString()
    InfoRow(stringResource(R.string.info_path), path)
    InfoRow(stringResource(R.string.size), formatBytes(item.sizeBytes))
    
    val res = resolution
    if (res != null && res.first > 0 && res.second > 0) {
        val w = res.first
        val h = res.second
        val mp = (w.toLong() * h.toLong()) / 1_000_000.0
        val text = if (mp >= 0.1) {
            "$w × $h (${String.format(Locale.US, "%.1f", mp)} MP)"
        } else {
            "$w × $h"
        }
        InfoRow(stringResource(R.string.info_resolution), text)
    }

    InfoRow(
        stringResource(R.string.info_modified),
        formatModifiedWithTaken(item),
    )
    if (item.isVideo && durationMs > 0) {
        val durationFormat = LocalVideoDurationFormat.current
        InfoRow(stringResource(R.string.duration), formatDuration(durationMs, durationFormat))
    }
    InfoRow(stringResource(R.string.info_type), item.mimeType.ifBlank { "—" })
    InfoRow(stringResource(R.string.info_folder), item.bucketName.ifBlank { "—" })
    if (item.dateAddedMs > 0L) {
        InfoRow(stringResource(R.string.info_added), formatDate(item.dateAddedMs))
    }
}

private fun resolveMediaDimensions(context: Context, item: GalleryMedia): Pair<Int, Int>? {
    return if (item.isVideo) {
        val retriever = MediaMetadataRetriever()
        try {
            dev.ander.alexet.data.Mp4MetaRepair.setDataSource(retriever, context, item.absolutePath, item.uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) {
                if (rotation == 90 || rotation == 270) h to w else w to h
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    } else {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (item.absolutePath != null && File(item.absolutePath).exists()) {
                BitmapFactory.decodeFile(item.absolutePath, options)
            } else {
                context.contentResolver.openInputStream(item.uri)?.use { inp ->
                    BitmapFactory.decodeStream(inp, null, options)
                }
            }
            var w = options.outWidth
            var h = options.outHeight
            if (w > 0 && h > 0) {
                val orientation = try {
                    if (item.absolutePath != null && File(item.absolutePath).exists()) {
                        ExifInterface(item.absolutePath).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL,
                        )
                    } else {
                        context.contentResolver.openInputStream(item.uri)?.use { inp ->
                            ExifInterface(inp).getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL,
                            )
                        } ?: ExifInterface.ORIENTATION_NORMAL
                    }
                } catch (_: Throwable) {
                    ExifInterface.ORIENTATION_NORMAL
                }
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                    val temp = w
                    w = h
                    h = temp
                }
                w to h
            } else null
        } catch (_: Throwable) {
            null
        }
    }
}

private fun resolveVideoDuration(context: Context, item: GalleryMedia): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        dev.ander.alexet.data.Mp4MetaRepair.setDataSource(retriever, context, item.absolutePath, item.uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Throwable) {
        0L
    } finally {
        runCatching { retriever.release() }
    }
}

/** One folder: its whole path, files, size, the oldest file's date and when it was last opened here. */
@Composable
private fun SingleAlbumInfo(album: Album, details: FolderDetails?, sizeBytes: Long?) {
    val context = LocalContext.current
    // A tap copies the name to the clipboard, like a file's.
    Text(
        text = album.name,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable { copyFileName(context, album.name) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
    InfoRow(
        stringResource(R.string.info_path),
        when {
            details == null -> "…"
            else -> details.path ?: "—"
        },
    )
    InfoRow(stringResource(R.string.info_file_count), details?.fileCount?.toString() ?: album.itemCount.toString())
    InfoRow(stringResource(R.string.size), (details?.sizeBytes ?: sizeBytes)?.let { formatBytes(it) } ?: "…")
    InfoRow(stringResource(R.string.info_last_used), details?.let { formatDate(it.lastUsedMs) } ?: "…")
    InfoRow(stringResource(R.string.info_created), details?.let { formatDate(it.createdMs) } ?: "…")
}

@Composable
private fun SelectionSummary(media: List<GalleryMedia>, albums: List<Album>, foldersSizeBytes: Long?) {
    val title = buildString {
        if (albums.isNotEmpty()) append(albums.joinToString { it.name })
        if (albums.isNotEmpty() && media.isNotEmpty()) append(" · ")
        if (media.size == 1) append(media.first().displayName)
        else if (media.size > 1 && albums.isEmpty()) append(stringResource(R.string.selected_count, media.size))
    }
    Text(
        text = title.ifBlank { stringResource(R.string.selected_count, media.size + albums.size) },
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    )
    if (media.size > 1) {
        InfoRow(stringResource(R.string.info_files), media.size.toString())
    }
    if (media.size > 1 || albums.isNotEmpty()) {
        // Selected files plus everything inside the selected folders.
        val folderIds = albums.mapTo(HashSet()) { it.id }
        val total = foldersSizeBytes?.let { folders ->
            folders + media.filter { it.bucketId !in folderIds }.sumOf { it.sizeBytes } // no double count
        }
        InfoRow(stringResource(R.string.size), total?.let { formatBytes(it) } ?: "…")
    }
    val videosDurationMs = media
        .filter { it.isVideo }
        .sumOf { it.durationMs.coerceAtLeast(0L) }
    if (videosDurationMs > 0L) {
        val durationFormat = LocalVideoDurationFormat.current
        InfoRow(
            stringResource(R.string.info_videos_duration),
            formatDuration(videosDurationMs, durationFormat),
        )
    }
    if (albums.isNotEmpty()) {
        InfoRow(stringResource(R.string.info_folders), albums.size.toString())
        InfoRow(stringResource(R.string.item_count_label), albums.sumOf { it.itemCount }.toString())
    }
}

/**
 * One line of info. A tap on the value copies the value ("177,6 KB"); a tap on the label copies
 * the whole line ("Rozmiar: 177,6 KB"). Nothing is copied while it is still being worked out.
 */
@Composable
private fun InfoRow(label: String, value: String) {
    val context = LocalContext.current
    val ready = value != "…"
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(0.42f)
                .padding(end = 8.dp)
                .clip(shape)
                .clickable(enabled = ready) { copyInfo(context, infoLine(label, value)) }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(0.58f)
                .clip(shape)
                .clickable(enabled = ready) { copyInfo(context, value) }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
    Spacer(Modifier.height(2.dp))
}

/** "Rozmiar: 177,6 KB" (a label written with its own colon keeps one). */
internal fun infoLine(label: String, value: String): String = "${label.trimEnd(':', ' ')}: $value"

private fun formatDate(ms: Long): String =
    if (ms <= 0L) "—" else DateFormat.getDateTimeInstance().format(Date(ms))

private fun formatModifiedWithTaken(item: GalleryMedia): String {
    val modified = formatDate(item.dateModifiedMs)
    return if (item.dateTakenMs > 0L) {
        "$modified (${formatDate(item.dateTakenMs)})"
    } else {
        modified
    }
}

private fun copyInfo(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(text, text))
    // Android 13+ shows its own "Copied" confirmation.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        android.widget.Toast.makeText(context, R.string.info_copied, android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun copyFileName(context: android.content.Context, name: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(name, name))
    // Android 13+ shows its own "Copied" confirmation.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        android.widget.Toast.makeText(context, R.string.file_name_copied, android.widget.Toast.LENGTH_SHORT).show()
    }
}
