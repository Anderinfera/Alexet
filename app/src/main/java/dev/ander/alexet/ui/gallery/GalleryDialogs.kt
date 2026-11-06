package dev.ander.alexet.ui.gallery

import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import dev.ander.alexet.ui.components.LocalLightMode
import dev.ander.alexet.ui.components.AnimatedVisibility
import androidx.compose.animation.core.Spring
import dev.ander.alexet.ui.components.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ander.alexet.R
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.RenameRequest
import dev.ander.alexet.data.SortField
import dev.ander.alexet.data.SortOrder
import dev.ander.alexet.data.SortSpec
import androidx.compose.material3.Checkbox

enum class AlbumPickMode { COPY, MOVE }

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "kB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val formatted = String.format(java.util.Locale.getDefault(), "%.1f", bytes / Math.pow(1024.0, digitGroups.toDouble()))
    return "$formatted ${units[digitGroups]}"
}

@Composable
fun DeleteConfirmDialog(
    itemName: String? = null,
    itemSize: Long? = null,
    count: Int = 1,
    inTrash: Boolean = false,
    onConfirm: (dontAskAgainSession: Boolean, skipTrash: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontAskSession by remember { mutableStateOf(false) }
    var skipTrash by remember { mutableStateOf(false) }

    val titleText = when {
        inTrash -> {
            if (!itemName.isNullOrBlank()) {
                stringResource(R.string.delete_permanently_confirm_single, itemName)
            } else {
                stringResource(R.string.delete_permanently_confirm_multiple, count)
            }
        }
        !itemName.isNullOrBlank() && itemSize != null -> {
            stringResource(R.string.move_to_trash_confirm_single, itemName, formatFileSize(itemSize))
        }
        !itemName.isNullOrBlank() -> {
            stringResource(R.string.move_to_trash_confirm_single, itemName, "")
        }
        else -> {
            stringResource(R.string.move_to_trash_confirm_multiple, count)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = if (!inTrash) {
            {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dontAskSession = !dontAskSession },
                    ) {
                        Checkbox(
                            checked = dontAskSession,
                            onCheckedChange = { dontAskSession = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.dont_ask_again_session),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { skipTrash = !skipTrash },
                    ) {
                        Checkbox(
                            checked = skipTrash,
                            onCheckedChange = { skipTrash = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.skip_trash_delete_direct),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else null,
        confirmButton = {
            TextButton(onClick = { onConfirm(dontAskSession, skipTrash) }) {
                Text(stringResource(R.string.action_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_no))
            }
        },
    )
}

@Composable
fun RenameDialog(
    onConfirm: (RenameRequest) -> Unit,
    onDismiss: () -> Unit,
    selectionCount: Int = 1,
    /** Name of the one selected file: "Zamień nazwę" starts with it (the extension stays as it is). */
    initialName: String? = null,
) {
    // Saved: after a process restart the dialog comes back with what was typed.
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var simpleMode by rememberSaveable { mutableStateOf(2) } // 0: Append, 1: Prepend, 2: Replace, 3: Swap, 4: ReverseOrder
    var simpleText by rememberSaveable { mutableStateOf("") }
    var replaceFindText by rememberSaveable { mutableStateOf("") }
    var replaceNewText by rememberSaveable {
        mutableStateOf(initialName?.let { if (it.contains('.')) it.substringBeforeLast('.') else it }.orEmpty())
    }
    var patternText by rememberSaveable { mutableStateOf("") }
    val canSwap = selectionCount == 2
    val canReverseOrder = selectionCount >= 2

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                // Header Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Tab 0: Zwykła zmiana
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { selectedTab = 0 },
                    ) {
                        Text(
                            text = stringResource(R.string.rename_simple_tab),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(if (selectedTab == 0) MaterialTheme.colorScheme.primary else Color.Transparent),
                        )
                    }

                    // Tab 1: Wzorcowa
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { selectedTab = 1 },
                    ) {
                        Text(
                            text = stringResource(R.string.rename_pattern_tab),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.Transparent),
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                if (selectedTab == 0) {
                    // Simple rename text fields depending on mode
                    if (simpleMode == 0 || simpleMode == 1) {
                        OutlinedTextField(
                            value = simpleText,
                            onValueChange = { simpleText = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.rename_string_to_add),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                    } else if (simpleMode == 2) {
                        if (selectionCount > 1) {
                            OutlinedTextField(
                                value = replaceFindText,
                                onValueChange = { replaceFindText = it },
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.rename_find_text),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedTextField(
                            value = replaceNewText,
                            onValueChange = { replaceNewText = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.rename_replace_text),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    // Radio 3: Replace name (Zamień nazwę)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { simpleMode = 2 }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(
                            selected = simpleMode == 2,
                            onClick = { simpleMode = 2 },
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.rename_replace),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.rename_replace_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Radio 1: Add after name (Append)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { simpleMode = 0 }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(
                            selected = simpleMode == 0,
                            onClick = { simpleMode = 0 },
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.rename_append),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.rename_append_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Radio 2: Add before name (Prepend)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { simpleMode = 1 }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(
                            selected = simpleMode == 1,
                            onClick = { simpleMode = 1 },
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.rename_prepend),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.rename_prepend_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Radio 4: Swap names (only 2 items)
                    if (canSwap) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { simpleMode = 3 }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RadioButton(
                                selected = simpleMode == 3,
                                onClick = { simpleMode = 3 },
                            )
                            Text(
                                text = stringResource(R.string.rename_swap),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // Radio 5: Reverse order (1↔N, 2↔N-1, …)
                    if (canReverseOrder) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { simpleMode = 4 }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RadioButton(
                                selected = simpleMode == 4,
                                onClick = { simpleMode = 4 },
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.rename_reverse_order),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.rename_reverse_order_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    // Pattern rename
                    OutlinedTextField(
                        value = patternText,
                        onValueChange = { patternText = it },
                        placeholder = {
                            Text(
                                text = "IMG_%Y%M%D_%h%m%s.jpg",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(14.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(R.string.rename_pattern_legend_y), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.rename_pattern_legend_m_cap), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.rename_pattern_legend_d), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.rename_pattern_legend_h), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.rename_pattern_legend_m), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.rename_pattern_legend_s), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.rename_pattern_legend_i), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Bottom actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.cancel),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (selectedTab == 0) {
                                when (simpleMode) {
                                    0 -> {
                                        if (simpleText.isNotEmpty()) onConfirm(RenameRequest.Simple(text = simpleText, isAppend = true))
                                    }
                                    1 -> {
                                        if (simpleText.isNotEmpty()) onConfirm(RenameRequest.Simple(text = simpleText, isAppend = false))
                                    }
                                    2 -> {
                                        if (selectionCount == 1) {
                                            if (replaceNewText.isNotBlank()) onConfirm(RenameRequest.SetBase(newBase = replaceNewText.trim()))
                                        } else {
                                            if (replaceFindText.isNotEmpty()) {
                                                onConfirm(RenameRequest.Replace(findText = replaceFindText, replaceText = replaceNewText))
                                            } else if (replaceNewText.isNotBlank()) {
                                                onConfirm(RenameRequest.SetBase(newBase = replaceNewText.trim()))
                                            }
                                        }
                                    }
                                    3 -> {
                                        if (canSwap) onConfirm(RenameRequest.Swap)
                                    }
                                    4 -> {
                                        if (canReverseOrder) onConfirm(RenameRequest.ReverseOrder)
                                    }
                                }
                            } else {
                                val finalPattern = patternText.trim().ifEmpty { "IMG_%Y%M%D_%h%m%s" }
                                onConfirm(RenameRequest.Pattern(pattern = finalPattern))
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.action_ok),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RenameFolderDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var textValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = currentName,
                selection = TextRange(0, currentName.length),
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    val trimmed = textValue.text.trim()
    val isValid = trimmed.isNotEmpty() &&
        trimmed != currentName &&
        !trimmed.contains('/') &&
        !trimmed.contains('\\') &&
        !trimmed.contains(':') &&
        !trimmed.contains('*') &&
        !trimmed.contains('?') &&
        !trimmed.contains('"') &&
        !trimmed.contains('<') &&
        !trimmed.contains('>') &&
        !trimmed.contains('|')

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Text(
                    text = stringResource(R.string.rename_folder_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { if (isValid) onConfirm(trimmed) },
                        enabled = isValid,
                    ) {
                        Text(stringResource(R.string.rename))
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumPickerDialog(
    albums: List<Album>,
    allAlbums: List<Album>,
    hiddenAlbumIds: Set<Long>,
    showingHidden: Boolean,
    showHiddenToggle: Boolean = true,
    onPick: (Long) -> Unit,
    onOtherFolder: () -> Unit,
    onRevealHidden: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var showHidden by remember { mutableStateOf(showingHidden) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val displayed = remember(showHidden, allAlbums, albums) {
        (if (showHidden) allAlbums else albums).distinctBy { it.id }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.choose_destination),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (showHiddenToggle) {
                        FilledTonalIconButton(
                            onClick = {
                                showHidden = !showHidden
                                onRevealHidden(showHidden)
                            },
                            colors = if (showHidden) {
                                IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                IconButtonDefaults.filledTonalIconButtonColors()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Visibility,
                                contentDescription = stringResource(R.string.picker_show_hidden),
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        items(displayed, key = { it.id }) { album ->
                            val isSelected = album.id == selectedId
                            val itemScale by animateFloatAsState(
                                targetValue = if (isSelected) 0.93f else 1f,
                                animationSpec = spring(
                                    stiffness = Spring.StiffnessMediumLow,
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                ),
                                label = "albumPickerDialogTileScale",
                            )
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .then(if (LocalLightMode.current) Modifier else Modifier.animateItem())
                                    .graphicsLayer {
                                        scaleX = itemScale
                                        scaleY = itemScale
                                    }
                                    .clickable { selectedId = if (isSelected) null else album.id },
                            ) {
                                FolderCover(
                                    album = album,
                                    stamped = isSelected,
                                    hidden = album.isHidden || album.id in hiddenAlbumIds,
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOtherFolder) {
                        Text(stringResource(R.string.other_folder))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = { selectedId?.let(onPick) },
                        enabled = selectedId != null,
                    ) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            }
        }
        }
    }
}

@Composable
fun SortDialog(
    sortSpec: SortSpec,
    folderSortOnly: Boolean,
    hasContentOrder: Boolean = false,
    onApply: (SortSpec, Boolean, Boolean) -> Unit,
    onStripPriority: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(sortSpec) }
    var draftOnly by remember { mutableStateOf(folderSortOnly) }
    var forceSort by remember { mutableStateOf(false) }
    var showingExtras by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (!showingExtras) {
                Text(
                    text = stringResource(R.string.sort_by),
                    style = MaterialTheme.typography.headlineSmall,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showingExtras = false }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.sort_extras),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                if (!showingExtras) {
                    SortField.entries.forEach { field ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { draft = draft.copy(field = field) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = draft.field == field,
                                onClick = { draft = draft.copy(field = field) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(sortFieldLabel(field)),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    if (draft.field != SortField.RANDOM) {
                        androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { draft = draft.copy(order = SortOrder.ASCENDING) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = draft.order == SortOrder.ASCENDING,
                                onClick = { draft = draft.copy(order = SortOrder.ASCENDING) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.sort_asc),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { draft = draft.copy(order = SortOrder.DESCENDING) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = draft.order == SortOrder.DESCENDING,
                                onClick = { draft = draft.copy(order = SortOrder.DESCENDING) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.sort_desc),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { draftOnly = !draftOnly }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = draftOnly,
                            onCheckedChange = { draftOnly = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sort_only_this_folder),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    Text(
                        text = stringResource(R.string.sort_grouping_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { forceSort = !forceSort }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = forceSort,
                            onCheckedChange = { forceSort = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sort_force),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text(
                        text = stringResource(
                            if (hasContentOrder) R.string.sort_force_hint_active else R.string.sort_force_hint,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 48.dp, top = 0.dp, bottom = 8.dp),
                    )

                    if (onStripPriority != null) {
                        androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onStripPriority()
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CleaningServices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.strip_priority_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(R.string.strip_priority_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!showingExtras) {
                    TextButton(onClick = { showingExtras = true }) {
                        Text(stringResource(R.string.sort_extras))
                    }
                } else {
                    TextButton(onClick = { showingExtras = false }) {
                        Text(stringResource(R.string.back))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = {
                        onApply(draft, draftOnly, forceSort)
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        },
        dismissButton = null,
    )
}

fun sortFieldLabel(field: SortField): Int = when (field) {
    SortField.NAME -> R.string.sort_name
    SortField.PATH -> R.string.sort_path
    SortField.SIZE -> R.string.sort_size
    SortField.DATE_MODIFIED -> R.string.sort_modified
    SortField.DATE_TAKEN -> R.string.sort_taken
    SortField.RANDOM -> R.string.sort_random
}

@Composable
fun SetAsDialog(
    media: GalleryMedia,
    onSetWallpaperHome: () -> Unit,
    onSetWallpaperLock: () -> Unit,
    onSetWallpaperBoth: () -> Unit,
    onSetContact: () -> Unit,
    onSetSystem: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.set_as_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SetAsOptionRow(
                    icon = Icons.Rounded.Wallpaper,
                    title = stringResource(R.string.wallpaper_home),
                    onClick = {
                        onSetWallpaperHome()
                        onDismiss()
                    },
                )
                SetAsOptionRow(
                    icon = Icons.Rounded.Lock,
                    title = stringResource(R.string.wallpaper_lock),
                    onClick = {
                        onSetWallpaperLock()
                        onDismiss()
                    },
                )
                SetAsOptionRow(
                    icon = Icons.Rounded.PhoneAndroid,
                    title = stringResource(R.string.wallpaper_both),
                    onClick = {
                        onSetWallpaperBoth()
                        onDismiss()
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SetAsOptionRow(
                    icon = Icons.Rounded.AccountCircle,
                    title = stringResource(R.string.set_as_contact),
                    onClick = {
                        onSetContact()
                        onDismiss()
                    },
                )
                SetAsOptionRow(
                    icon = Icons.Rounded.Settings,
                    title = stringResource(R.string.set_as_system),
                    onClick = {
                        onSetSystem()
                        onDismiss()
                    },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SetAsOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun SetPrivateFolderDialog(
    folderName: String,
    onDismiss: () -> Unit,
    onConfirm: (password: String, hint: String?, backupPassword: String?) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var hasBackupPassword by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var repeatBackupPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val passwordFocus = remember { FocusRequester() }
    val repeatFocus = remember { FocusRequester() }
    val hintFocus = remember { FocusRequester() }
    val backupFocus = remember { FocusRequester() }
    val repeatBackupFocus = remember { FocusRequester() }

    val passMismatchMsg = stringResource(R.string.passwords_do_not_match)
    val passEmptyMsg = stringResource(R.string.password_cannot_be_empty)
    val backupEmptyMsg = stringResource(R.string.backup_password_cannot_be_empty)
    val backupMismatchMsg = stringResource(R.string.backup_passwords_do_not_match)

    LaunchedEffect(Unit) {
        passwordFocus.requestFocus()
    }

    fun confirm() {
        when {
            password.isBlank() -> errorText = passEmptyMsg
            password != repeatPassword -> errorText = passMismatchMsg
            hasBackupPassword && backupPassword.isBlank() -> errorText = backupEmptyMsg
            hasBackupPassword && backupPassword != repeatBackupPassword -> errorText = backupMismatchMsg
            else -> onConfirm(
                password.trim(),
                hint.trim().takeIf { it.isNotBlank() },
                if (hasBackupPassword) backupPassword.trim() else null,
            )
        }
    }

    // A full-window dialog laid out above the keyboard: an AlertDialog stays centred on the whole
    // screen, so the keyboard covered its buttons.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.set_private_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorText = null
                    },
                    label = { Text(stringResource(R.string.password_label)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { repeatFocus.requestFocus() }),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                )

                OutlinedTextField(
                    value = repeatPassword,
                    onValueChange = {
                        repeatPassword = it
                        errorText = null
                    },
                    label = { Text(stringResource(R.string.repeat_password_label)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { hintFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(repeatFocus),
                )

                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it },
                    label = { Text(stringResource(R.string.password_hint_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = if (hasBackupPassword) ImeAction.Next else ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onNext = { if (hasBackupPassword) backupFocus.requestFocus() },
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(hintFocus),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { hasBackupPassword = !hasBackupPassword }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = hasBackupPassword,
                        onCheckedChange = { hasBackupPassword = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.add_backup_password),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                AnimatedVisibility(visible = hasBackupPassword) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = backupPassword,
                            onValueChange = {
                                backupPassword = it
                                errorText = null
                            },
                            label = { Text(stringResource(R.string.backup_password_label)) },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { repeatBackupFocus.requestFocus() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .focusRequester(backupFocus),
                        )
                        OutlinedTextField(
                            value = repeatBackupPassword,
                            onValueChange = {
                                repeatBackupPassword = it
                                errorText = null
                            },
                            label = { Text(stringResource(R.string.repeat_backup_password_label)) },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { confirm() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(repeatBackupFocus),
                        )
                    }
                }

                if (errorText != null) {
                    Text(
                        text = errorText.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onClick = { confirm() }) {
                    Text(stringResource(R.string.encrypt_and_hide))
                }
            }
        }
        }
        }
    }
}

@Composable
fun DecryptFolderDialog(
    folderName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.decrypt_folder_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.decrypt_folder_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.decrypt_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
