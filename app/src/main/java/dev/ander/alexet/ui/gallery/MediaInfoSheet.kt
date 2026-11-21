package dev.ander.alexet.ui.gallery

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.AlbumIds
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
    val singleFile = media.size == 1 && albums.isEmpty()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // One file: the sheet opens half way with the usual info; pulled up, it shows the rest
        // (camera, exposure, place, codecs...) below it.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !singleFile),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // No stretch at the ends: with the sheet's own drag it shook the sheet when the
                // list (two files compared) was pulled up slowly.
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
        ) {
            when {
                singleFile -> {
                    SingleFileInfo(media.first())
                    MoreFileInfo(media.first())
                }
                albums.size == 1 && media.isEmpty() -> SingleAlbumInfo(albums.first(), details, foldersSize)
                else -> {
                    SelectionSummary(media, albums, foldersSize)
                    // Two files: compared detail by detail under the totals.
                    if (media.size == 2 && albums.isEmpty()) FileComparison(media[0], media[1])
                }
            }
        }
    }
}

@Composable
internal fun SingleFileInfo(item: GalleryMedia) {
    val context = LocalContext.current
    val resolution = rememberResolution(item)
    val durationMs = rememberDurationMs(item)

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
    InfoRow(stringResource(R.string.info_path), pathText(item))
    originalPathOf(item)?.let { InfoRow(stringResource(R.string.info_original_path), it) }
    InfoRow(stringResource(R.string.size), formatBytes(item.sizeBytes))

    resolutionText(resolution)?.let { InfoRow(stringResource(R.string.info_resolution), it) }

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

/** Width × height as shown; read from the file when the list doesn't have it. */
@Composable
private fun rememberResolution(item: GalleryMedia): Pair<Int, Int>? {
    val context = LocalContext.current
    val resolution by produceState(
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
    return resolution
}

/** A video's length (0 for a photo); read from the file when the list doesn't have it. */
@Composable
private fun rememberDurationMs(item: GalleryMedia): Long {
    val context = LocalContext.current
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
    return durationMs
}

/**
 * What a pulled-up info sheet adds for one file: the camera and its settings, the place and the
 * app that saved it for a photo; codecs, bit rate, frame rate, sound and HDR for a video. Read
 * in the background; a private (encrypted) file shows only what needs no reading of its content.
 */
@Composable
private fun MoreFileInfo(item: GalleryMedia) {
    val context = LocalContext.current
    val rows by produceState<List<Pair<String, String>>?>(initialValue = null, item.id, item.absolutePath) {
        value = withContext(Dispatchers.IO) { runCatching { readMoreInfo(context, item) }.getOrDefault(emptyList()) }
    }
    // Some room from the start: the sheet can then open half way and be pulled up to here.
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp)) {
        HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp))
        Text(
            text = stringResource(R.string.info_more),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        val list = rows
        when {
            list == null -> InfoRow(stringResource(R.string.info_more), "…")
            list.isEmpty() -> Text(
                text = stringResource(R.string.info_more_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            else -> list.forEach { (label, value) -> InfoRow(label, value) }
        }
    }
}

private fun readMoreInfo(context: Context, item: GalleryMedia): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    fun add(labelRes: Int, value: String?) {
        if (!value.isNullOrBlank()) out += context.getString(labelRes) to value
    }
    if (item.sizeBytes > 0L) {
        add(R.string.info_exact_size, String.format(Locale.getDefault(), "%,d B", item.sizeBytes))
    }
    val readable = !item.isEncrypted && !item.isOtherFile
    if (readable && item.isVideo) {
        readVideoInfo(context, item, ::add)
    } else if (readable) {
        readPhotoInfo(context, item, ::add)
    }
    add(R.string.info_saved_by, ownerAppOf(context, item))
    return out
}

private fun openExif(context: Context, item: GalleryMedia): ExifInterface? = runCatching {
    val path = item.absolutePath
    if (path != null && File(path).exists()) {
        ExifInterface(path)
    } else {
        context.contentResolver.openInputStream(item.uri)?.use { ExifInterface(it) }
    }
}.getOrNull()

private fun readPhotoInfo(context: Context, item: GalleryMedia, add: (Int, String?) -> Unit) {
    val exif = openExif(context, item) ?: return
    val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
    val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
    add(
        R.string.info_camera,
        when {
            model.isEmpty() -> make
            make.isEmpty() || model.startsWith(make, ignoreCase = true) -> model
            else -> "$make $model"
        },
    )
    add(R.string.info_lens, exif.getAttribute("LensModel")?.trim())
    exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0.0 }?.let {
        add(R.string.info_aperture, "f/" + String.format(Locale.US, "%.1f", it).removeSuffix(".0"))
    }
    exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0.0 }?.let { sec ->
        add(
            R.string.info_exposure,
            if (sec < 1.0) "1/${Math.round(1.0 / sec)} s" else String.format(Locale.getDefault(), "%.1f s", sec),
        )
    }
    (exif.getAttribute("PhotographicSensitivity")
        ?: @Suppress("DEPRECATION") exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS))
        ?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
        ?.let { add(R.string.info_iso, "ISO $it") }
    exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0.0 }?.let { mm ->
        val shown = String.format(Locale.getDefault(), "%.1f mm", mm)
        val equiv = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
        add(R.string.info_focal, if (equiv > 0) context.getString(R.string.info_focal_35, shown, equiv) else shown)
    }
    if (exif.getAttribute(ExifInterface.TAG_FLASH) != null) {
        val fired = exif.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1 == 1
        add(R.string.info_flash, context.getString(if (fired) R.string.info_flash_fired else R.string.info_flash_off))
    }
    val latLong = FloatArray(2)
    @Suppress("DEPRECATION")
    if (exif.getLatLong(latLong)) {
        add(R.string.info_location, formatCoordinates(latLong[0].toDouble(), latLong[1].toDouble()))
        exif.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.let {
            add(R.string.info_altitude, String.format(Locale.getDefault(), "%.0f m", it))
        }
    }
    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> add(R.string.info_orientation, "90°")
        ExifInterface.ORIENTATION_ROTATE_180 -> add(R.string.info_orientation, "180°")
        ExifInterface.ORIENTATION_ROTATE_270 -> add(R.string.info_orientation, "270°")
    }
    add(R.string.info_software, exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim())
}

private fun readVideoInfo(context: Context, item: GalleryMedia, add: (Int, String?) -> Unit) {
    var videoMime: String? = null
    var frameRate = 0f
    var audio: String? = null
    var hasAudio = false
    val extractor = android.media.MediaExtractor()
    try {
        val path = item.absolutePath
        if (path != null && File(path).exists()) extractor.setDataSource(path) else extractor.setDataSource(context, item.uri, null)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoMime == null) {
                videoMime = mime
                if (format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                    frameRate = runCatching { format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toFloat() }
                        .getOrElse { runCatching { format.getFloat(android.media.MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f) }
                }
            } else if (mime.startsWith("audio/") && !hasAudio) {
                hasAudio = true
                val parts = ArrayList<String>()
                parts += codecName(mime)
                if (format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                    val hz = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    parts += String.format(Locale.getDefault(), "%.1f kHz", hz / 1000.0).replace(",0 ", " ").replace(".0 ", " ")
                }
                if (format.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                    parts += when (val ch = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                        1 -> "mono"
                        2 -> "stereo"
                        else -> "$ch ch"
                    }
                }
                audio = parts.joinToString(", ")
            }
        }
    } catch (_: Throwable) {
    } finally {
        runCatching { extractor.release() }
    }
    add(R.string.info_video_codec, videoMime?.let(::codecName))

    val retriever = MediaMetadataRetriever()
    try {
        dev.ander.alexet.data.Mp4MetaRepair.setDataSource(retriever, context, item.absolutePath, item.uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 }?.let { bps ->
            add(
                R.string.info_bitrate,
                if (bps >= 1_000_000) String.format(Locale.getDefault(), "%.1f Mb/s", bps / 1_000_000.0)
                else "${bps / 1000} kb/s",
            )
        }
        if (frameRate <= 0f && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (frames > 0 && ms > 0) frameRate = frames * 1000f / ms
        }
        if (frameRate > 0f) {
            add(R.string.info_frame_rate, String.format(Locale.getDefault(), "%.2f", frameRate).trimEnd('0').trimEnd(',', '.') + " fps")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            when (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()) {
                android.media.MediaFormat.COLOR_TRANSFER_ST2084 -> add(R.string.info_hdr, "HDR10 (PQ)")
                android.media.MediaFormat.COLOR_TRANSFER_HLG -> add(R.string.info_hdr, "HLG")
            }
        }
        add(R.string.info_audio, if (hasAudio) audio else context.getString(R.string.info_audio_none))
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::parseIso6709)?.let { (lat, lon) ->
            add(R.string.info_location, formatCoordinates(lat, lon))
        }
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            ?.takeIf { it != 0 }?.let { add(R.string.info_orientation, "$it°") }
    } catch (_: Throwable) {
    } finally {
        runCatching { retriever.release() }
    }
}

/** "H.265 (HEVC)" for "video/hevc"; the type itself when unknown. */
private fun codecName(mime: String): String = when (mime.lowercase(Locale.US)) {
    "video/avc" -> "H.264 (AVC)"
    "video/hevc" -> "H.265 (HEVC)"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/mp4v-es" -> "MPEG-4"
    "video/3gpp" -> "H.263"
    "video/dolby-vision" -> "Dolby Vision"
    "audio/mp4a-latm" -> "AAC"
    "audio/opus" -> "Opus"
    "audio/vorbis" -> "Vorbis"
    "audio/mpeg" -> "MP3"
    "audio/flac" -> "FLAC"
    "audio/ac3" -> "AC-3"
    "audio/eac3" -> "E-AC-3"
    "audio/3gpp" -> "AMR-NB"
    "audio/amr-wb" -> "AMR-WB"
    "audio/raw" -> "PCM"
    else -> mime.substringAfter('/')
}

/** "+52.2297+021.0122/" or "+52.2297+021.0122+100.5/" (a video's place) to latitude, longitude. */
private fun parseIso6709(text: String): Pair<Double, Double>? {
    val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(text) ?: return null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null
    val lon = m.groupValues[2].toDoubleOrNull() ?: return null
    return if (lat == 0.0 && lon == 0.0) null else lat to lon
}

private fun formatCoordinates(lat: Double, lon: Double): String =
    String.format(Locale.US, "%.6f, %.6f", lat, lon)

/** The app that saved the file (Android 11+), by its name when it can be read. */
private fun ownerAppOf(context: Context, item: GalleryMedia): String? {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
    if (item.uri.scheme != "content" || item.uri.authority != android.provider.MediaStore.AUTHORITY) return null
    val pkg = runCatching {
        context.contentResolver.query(item.uri, arrayOf(android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val label = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
    return if (label.isNullOrBlank() || label == pkg) pkg else "$label ($pkg)"
}

/** Where each trashed file was before it was deleted, by its id; empty when not provided. */
val LocalTrashOriginalPaths = compositionLocalOf<Map<Long, String>> { emptyMap() }

/** The path a file in the trash was deleted from; null for any other file. */
@Composable
private fun originalPathOf(item: GalleryMedia): String? =
    if (item.bucketId == AlbumIds.TRASH) LocalTrashOriginalPaths.current[item.id]?.takeIf { it.isNotBlank() } else null

/** A file from another app whose place is unknown: the address it came by, not a bare name. */
private fun pathText(item: GalleryMedia): String =
    item.storagePath.takeIf { '/' in it || item.uri.scheme != "content" } ?: item.uri.toString()

/** "4000 × 3000 (12.0 MP)"; null when the size is unknown. */
private fun resolutionText(resolution: Pair<Int, Int>?): String? {
    val (w, h) = resolution?.takeIf { it.first > 0 && it.second > 0 } ?: return null
    val mp = (w.toLong() * h.toLong()) / 1_000_000.0
    return if (mp >= 0.1) "$w × $h (${String.format(Locale.US, "%.1f", mp)} MP)" else "$w × $h"
}

/**
 * Two files under a "T": a line across, and from its middle a line down with file A on the left
 * and B on the right. Their names on top, then each detail: its label over the line, A's value
 * left of it and B's right of it ("—" where a file has none, such as a photo's length).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileComparison(a: GalleryMedia, b: GalleryMedia) {
    val context = LocalContext.current
    val durationFormat = LocalVideoDurationFormat.current
    val resolutionA = rememberResolution(a)
    val resolutionB = rememberResolution(b)
    val durationA = rememberDurationMs(a)
    val durationB = rememberDurationMs(b)
    fun duration(item: GalleryMedia, ms: Long) = if (item.isVideo && ms > 0L) formatDuration(ms, durationFormat) else NONE
    fun added(item: GalleryMedia) = if (item.dateAddedMs > 0L) formatDate(item.dateAddedMs) else NONE
    val originalA = originalPathOf(a)
    val originalB = originalPathOf(b)
    val originalLabel = stringResource(R.string.info_original_path)
    val rows = listOfNotNull(
        Triple(stringResource(R.string.info_path), pathText(a), pathText(b)),
        // Either file in the trash: where it was deleted from.
        if (originalA != null || originalB != null) Triple(originalLabel, originalA ?: NONE, originalB ?: NONE) else null,
        Triple(stringResource(R.string.size), formatBytes(a.sizeBytes), formatBytes(b.sizeBytes)),
        Triple(
            stringResource(R.string.info_resolution),
            resolutionText(resolutionA) ?: NONE,
            resolutionText(resolutionB) ?: NONE,
        ),
        Triple(stringResource(R.string.info_modified), formatModifiedWithTaken(a), formatModifiedWithTaken(b)),
        Triple(stringResource(R.string.duration), duration(a, durationA), duration(b, durationB)),
        Triple(stringResource(R.string.info_type), a.mimeType.ifBlank { NONE }, b.mimeType.ifBlank { NONE }),
        Triple(stringResource(R.string.info_folder), a.bucketName.ifBlank { NONE }, b.bucketName.ifBlank { NONE }),
        Triple(stringResource(R.string.info_added), added(a), added(b)),
    )
    val line = MaterialTheme.colorScheme.outlineVariant
    // The label sits on the line down: the sheet's colour behind it breaks the line there.
    val sheet = BottomSheetDefaults.ContainerColor
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = line)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = size.width / 2f
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ComparisonPair(a.displayName, b.displayName, isName = true) { copyFileName(context, it) }
        rows.forEach { (label, valueA, valueB) ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .background(sheet)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            ComparisonPair(valueA, valueB) { copyInfo(context, it) }
        }
    }
}

private const val NONE = "—"

/** A's value right-aligned against the line, B's left-aligned; a tap copies one. */
@Composable
private fun ComparisonPair(left: String, right: String, isName: Boolean = false, onCopy: (String) -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val style = if (isName) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    val color = if (isName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        listOf(left, right).forEachIndexed { i, value ->
            val copyable = value != NONE
            Text(
                text = value,
                style = style,
                fontWeight = if (isName) FontWeight.Bold else null,
                color = color,
                textAlign = if (i == 0) TextAlign.End else TextAlign.Start,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (i == 0) 0.dp else 10.dp, end = if (i == 0) 10.dp else 0.dp)
                    .clip(shape)
                    .clickable(enabled = copyable) { onCopy(value) }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
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
