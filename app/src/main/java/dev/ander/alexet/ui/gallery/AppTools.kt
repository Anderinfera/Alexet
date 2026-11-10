package dev.ander.alexet.ui.gallery

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ander.alexet.BuildConfig
import dev.ander.alexet.R
import dev.ander.alexet.data.AppSettings
import dev.ander.alexet.data.DuplicateFinder
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.integration.AppUpdates
import dev.ander.alexet.ui.components.MediaThumbnail
import kotlinx.coroutines.flow.StateFlow

/** The tools of Settings → App that live in the view model: duplicates and updates. */
class AppTools(
    val duplicates: StateFlow<GalleryViewModel.DuplicateScan>,
    val onFindDuplicates: () -> Unit,
    val onCancelDuplicates: () -> Unit,
    val onTrashDuplicates: (Set<Long>) -> Unit,
    val updates: StateFlow<AppUpdates.State>,
    val onCheckUpdates: (Boolean) -> Unit,
    val onCheckUpdatesNow: () -> Unit,
)

val LocalAppTools = staticCompositionLocalOf<AppTools?> { null }

/** "Find duplicates": the row and its page. */
@Composable
internal fun DuplicatesSettingsRow() {
    val tools = LocalAppTools.current ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsActionRow(
        title = stringResource(R.string.duplicates_title),
        modifier = Modifier.settingsSearchTarget(R.string.duplicates_title),
        body = stringResource(R.string.duplicates_body),
        icon = Icons.Rounded.ContentCopy,
        onClick = { open = true },
    )
    SettingsSubPage(open = open, onClose = { open = false }) { onBack ->
        DuplicatesPage(tools, onBack)
    }
}

/** "Check for updates" and "Check now". */
@Composable
internal fun UpdatesSettingsRows(settings: AppSettings) {
    val tools = LocalAppTools.current ?: return
    val state by tools.updates.collectAsState()
    SettingsSwitchRow(
        title = stringResource(R.string.check_updates_title),
        modifier = Modifier.settingsSearchTarget(R.string.check_updates_title),
        body = stringResource(R.string.check_updates_body),
        checked = settings.checkUpdates,
        onCheckedChange = tools.onCheckUpdates,
        icon = Icons.Rounded.Update,
    )
    SettingsItemDivider()
    SettingsActionRow(
        title = stringResource(R.string.check_updates_now),
        modifier = Modifier.settingsSearchTarget(R.string.check_updates_now),
        body = when (val s = state) {
            AppUpdates.State.Checking -> stringResource(R.string.update_status_checking)
            AppUpdates.State.UpToDate -> stringResource(R.string.update_status_up_to_date, BuildConfig.VERSION_NAME)
            is AppUpdates.State.Available -> stringResource(R.string.update_status_available, s.release.version)
            is AppUpdates.State.Downloading -> stringResource(R.string.update_downloading, (s.progress * 100).toInt())
            is AppUpdates.State.Failed -> stringResource(R.string.update_status_failed)
            AppUpdates.State.Idle -> stringResource(R.string.update_status_current, BuildConfig.VERSION_NAME)
        },
        icon = Icons.Rounded.SystemUpdate,
        onClick = tools.onCheckUpdatesNow,
    )
}

@Composable
private fun DuplicatesPage(tools: AppTools, onBack: () -> Unit) {
    val scan by tools.duplicates.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PageBar(stringResource(R.string.duplicates_title), onBack)
        when (val s = scan) {
            GalleryViewModel.DuplicateScan.Idle -> DuplicatesIntro {
                Button(onClick = tools.onFindDuplicates, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.duplicates_start))
                }
            }
            is GalleryViewModel.DuplicateScan.Running -> DuplicatesIntro {
                LinearProgressIndicator(
                    progress = { s.progress },
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Text(
                    text = stringResource(R.string.duplicates_running, (s.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = tools.onCancelDuplicates) { Text(stringResource(R.string.cancel)) }
            }
            is GalleryViewModel.DuplicateScan.Done -> DuplicatesResults(s, tools)
        }
    }
}

@Composable
private fun DuplicatesIntro(actions: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.duplicates_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        actions()
    }
}

@Composable
private fun DuplicatesResults(done: GalleryViewModel.DuplicateScan.Done, tools: AppTools) {
    val context = LocalContext.current
    // All but the best copy of each group are chosen for the trash; a tap changes that.
    var marked by remember(done.scanId) {
        mutableStateOf(done.groups.flatMap { g -> g.items.drop(1).map { it.id } }.toSet())
    }
    val items = remember(done) { done.groups.flatMap { it.items }.associateBy { it.id } }
    val chosen = marked.filter { it in items }
    val bytes = chosen.sumOf { items.getValue(it).sizeBytes }
    if (done.groups.isEmpty()) {
        DuplicatesIntro {
            Text(stringResource(R.string.duplicates_none), style = MaterialTheme.typography.titleMedium)
            if (!done.usedAi) {
                Text(
                    text = stringResource(R.string.duplicates_no_ai),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = tools.onFindDuplicates, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.duplicates_again))
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(
                        R.string.duplicates_summary,
                        done.groups.size,
                        chosen.size,
                        formatFileSize(bytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!done.usedAi) {
                    Text(
                        text = stringResource(R.string.duplicates_no_ai),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val ids = chosen.toSet()
                            tools.onTrashDuplicates(ids)
                            marked = marked - ids
                        },
                        enabled = chosen.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.duplicates_trash, chosen.size))
                    }
                    TextButton(onClick = tools.onFindDuplicates) { Text(stringResource(R.string.duplicates_again)) }
                }
            }
        }
        items(done.groups, key = { group -> group.items.first().id }) { group ->
            DuplicateGroupCard(
                group = group,
                marked = marked,
                onToggle = { id -> marked = if (id in marked) marked - id else marked + id },
            )
        }
    }
}

@Composable
private fun DuplicateGroupCard(group: DuplicateFinder.Group, marked: Set<Long>, onToggle: (Long) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(if (group.identical) R.string.duplicates_identical else R.string.duplicates_similar),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(group.items, key = { it.id }) { media ->
                    DuplicateTile(media = media, toTrash = media.id in marked, onClick = { onToggle(media.id) })
                }
            }
        }
    }
}

@Composable
private fun DuplicateTile(media: GalleryMedia, toTrash: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val danger = MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(if (toTrash) Modifier.border(3.dp, danger, RoundedCornerShape(10.dp)) else Modifier),
        ) {
            MediaThumbnail(media = media, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(10.dp))
            if (toTrash) {
                Box(Modifier.fillMaxSize().background(danger.copy(alpha = 0.28f)))
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.duplicates_to_trash),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(danger, RoundedCornerShape(8.dp))
                        .padding(3.dp)
                        .size(18.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.duplicates_keep),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = media.bucketName,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            text = buildString {
                if (media.width > 0 && media.height > 0) append("${media.width}×${media.height} · ")
                append(formatFileSize(media.sizeBytes))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
        )
    }
}

/** A newer version was found: what changed, and its download with progress. */
@Composable
internal fun UpdateCard(
    state: AppUpdates.State,
    onInstall: () -> Unit,
    onClose: () -> Unit,
) {
    val release = when (state) {
        is AppUpdates.State.Available -> state.release
        is AppUpdates.State.Downloading -> state.release
        is AppUpdates.State.Failed -> state.release
        else -> null
    } ?: return
    val context = LocalContext.current
    val downloading = state as? AppUpdates.State.Downloading
    AlertDialog(
        onDismissRequest = { if (downloading == null) onClose() },
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = { Text(stringResource(R.string.update_card_title, release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.update_card_installed, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (release.notes.isNotBlank()) {
                    Text(
                        text = release.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                if (downloading != null) {
                    LinearProgressIndicator(
                        progress = { downloading.progress },
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                    Text(
                        text = stringResource(R.string.update_downloading, (downloading.progress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state is AppUpdates.State.Failed) {
                    Text(
                        text = stringResource(R.string.update_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (release.pageUrl.startsWith("https://")) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text(stringResource(R.string.update_open_page)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = downloading == null) {
                Text(stringResource(R.string.update_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = downloading == null) {
                Text(stringResource(R.string.update_later))
            }
        },
    )
}
