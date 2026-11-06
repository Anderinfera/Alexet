package dev.ander.alexet.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ander.alexet.R
import dev.ander.alexet.data.AlphanumericComparator
import dev.ander.alexet.data.ArchiveReader
import dev.ander.alexet.ui.components.fileIconStyle
import dev.ander.alexet.ui.components.formatBytes

/** One row of the archive window: a folder (derived from the paths) or a file. */
private class ArchiveRow(
    val name: String,
    /** Full path inside the archive (the folder to enter, or the file). */
    val path: String,
    val entry: ArchiveReader.Entry?,
    /** For a folder: files somewhere below it. */
    val fileCount: Int,
)

/** Folders first, then files, of [dir] ("" = the top). Folders without an entry of their own are made up from the paths. */
private fun rowsIn(entries: List<ArchiveReader.Entry>, dir: String): List<ArchiveRow> {
    val prefix = if (dir.isEmpty()) "" else "$dir/"
    val folders = LinkedHashMap<String, Int>()
    val files = ArrayList<ArchiveRow>()
    for (entry in entries) {
        if (!entry.path.startsWith(prefix)) continue
        val rest = entry.path.substring(prefix.length)
        if (rest.isEmpty()) continue
        val slash = rest.indexOf('/')
        when {
            slash >= 0 -> {
                val name = rest.substring(0, slash)
                folders[name] = (folders[name] ?: 0) + if (entry.isDirectory) 0 else 1
            }
            entry.isDirectory -> folders.putIfAbsent(rest, 0)
            else -> files += ArchiveRow(rest, entry.path, entry, 0)
        }
    }
    val folderRows = folders.entries
        .sortedWith(compareBy(AlphanumericComparator) { it.key })
        .map { (name, count) -> ArchiveRow(name, prefix + name, null, count) }
    return folderRows + files.sortedWith(compareBy(AlphanumericComparator) { it.name })
}

/**
 * The contents of an archive, like a file manager: a path bar, folders then files, opened by a
 * tap (folders go in, files open in the viewer or in another app). Same size as the folder
 * picker. Back goes one folder up; a tap outside closes it.
 */
@Composable
fun ArchiveBrowserDialog(
    state: GalleryViewModel.ArchiveBrowserState,
    onNavigate: (String) -> Unit,
    onOpenEntry: (ArchiveReader.Entry) -> Unit,
    onOpenOutside: () -> Unit,
    onCancelExtract: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val listing = state.listing
    val entries = (listing as? ArchiveReader.Listing.Ok)?.entries.orEmpty()
    val rows = remember(entries, state.dir) { rowsIn(entries, state.dir) }
    val summary = remember(entries) {
        val files = entries.filter { !it.isDirectory }
        files.size to files.sumOf { it.size.coerceAtLeast(0L) }
    }
    val dateFormat = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    Dialog(
        // Back (the only other way this is called): one folder up, closed at the top.
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Title
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.FolderZip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = state.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                        }
                    }
                    PathBar(archiveName = state.name, dir = state.dir, onNavigate = onNavigate)
                    if (listing is ArchiveReader.Listing.Ok && !listing.canExtract) {
                        Note(stringResource(R.string.archive_list_only))
                    }
                    if (listing is ArchiveReader.Listing.Ok && listing.truncated) {
                        Note(stringResource(R.string.archive_truncated, entries.size))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        when (listing) {
                            null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            is ArchiveReader.Listing.Encrypted -> Message(Icons.Rounded.Lock, stringResource(R.string.archive_encrypted))
                            is ArchiveReader.Listing.Failed -> Message(Icons.Rounded.FolderZip, stringResource(R.string.archive_failed))
                            is ArchiveReader.Listing.Ok -> {
                                if (rows.isEmpty()) {
                                    Message(Icons.Rounded.Folder, stringResource(R.string.archive_empty))
                                } else {
                                    val listState = rememberLazyListState()
                                    LaunchedEffect(state.dir) { listState.scrollToItem(0) }
                                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                        items(rows, key = { it.path + (if (it.entry == null) "/" else "") }) { row ->
                                            ArchiveRowItem(
                                                row = row,
                                                subtitle = if (row.entry == null) {
                                                    stringResource(R.string.archive_folder_files, row.fileCount)
                                                } else {
                                                    listOfNotNull(
                                                        row.entry.size.takeIf { it >= 0 }?.let { formatBytes(it) },
                                                        row.entry.modifiedMs.takeIf { it > 0 }?.let { dateFormat.format(java.util.Date(it)) },
                                                    ).joinToString(" · ")
                                                },
                                                busy = row.entry != null && state.extracting == row.path,
                                                onClick = {
                                                    if (state.extracting != null) return@ArchiveRowItem
                                                    if (row.entry == null) onNavigate(row.path) else onOpenEntry(row.entry)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Unpacking a file to open it
                    if (state.extracting != null) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (state.progress >= 0) {
                                        stringResource(R.string.archive_extracting_percent, state.progress)
                                    } else {
                                        stringResource(R.string.archive_extracting)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onCancelExtract) { Text(stringResource(R.string.cancel)) }
                            }
                            if (state.progress >= 0) {
                                LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    // Summary and the whole archive in another app
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (listing is ArchiveReader.Listing.Ok) {
                                stringResource(R.string.archive_summary, summary.first, formatBytes(summary.second))
                            } else {
                                ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenOutside) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.archive_open_in_app))
                        }
                    }
                }
            }
        }
    }
}

/** "name.zip › Folder › Sub": each part goes back to that level. */
@Composable
private fun PathBar(archiveName: String, dir: String, onNavigate: (String) -> Unit) {
    val parts = if (dir.isEmpty()) emptyList() else dir.split('/')
    val scroll = rememberScrollState()
    LaunchedEffect(dir) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PathChip(text = archiveName, current = parts.isEmpty(), onClick = { onNavigate("") })
        parts.forEachIndexed { index, part ->
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            PathChip(
                text = part,
                current = index == parts.lastIndex,
                onClick = { onNavigate(parts.take(index + 1).joinToString("/")) },
            )
        }
    }
}

@Composable
private fun PathChip(text: String, current: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 180.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Message(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ArchiveRowItem(row: ArchiveRow, subtitle: String, busy: Boolean, onClick: () -> Unit) {
    val (color, icon) = if (row.entry == null) {
        MaterialTheme.colorScheme.primary to Icons.Rounded.Folder
    } else {
        remember(row.name) { fileIconStyle(row.name) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (row.entry?.encrypted == true) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
