package dev.ander.alexet.ui.player

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ander.alexet.R
import dev.ander.alexet.integration.PlayerShortcut

/**
 * Which shortcuts the player's top row shows, and in which order (Next Player's dialog): each
 * change is saved at once, "Revert" goes back to the set the dialog opened with.
 */
@Composable
fun PlayerShortcutsDialog(
    shortcuts: Set<PlayerShortcut>,
    onChange: (Set<PlayerShortcut>) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember { shortcuts }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            modifier = Modifier
                .padding(16.dp)
                .heightIn(max = 600.dp)
                .widthIn(max = if (landscape) 640.dp else 440.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 20.dp)) {
                Text(
                    text = stringResource(R.string.player_shortcuts),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val rows = if (landscape) DIALOG_SHORTCUTS.chunked(2) else DIALOG_SHORTCUTS.map { listOf(it) }
                    rows.forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            row.forEach { shortcut ->
                                ShortcutRow(
                                    shortcut = shortcut,
                                    number = shortcutNumber(shortcuts, shortcut),
                                    onToggle = { on -> onChange(toggleShortcut(shortcuts, shortcut, on)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // An odd last entry keeps the width of its column.
                            if (row.size == 1 && landscape) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = { onChange(initial) }) {
                        Text(stringResource(R.string.player_shortcuts_revert))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(
    shortcut: PlayerShortcut,
    number: Int?,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = number != null
    val (icon, label) = shortcut.dialogEntry()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (on) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent,
        modifier = modifier.toggleable(value = on, role = Role.Checkbox, onValueChange = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (number != null) {
                // Its place in the top row.
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = number.toString(),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            // The row toggles: the box only shows the state.
            Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        }
    }
}

private fun PlayerShortcut.dialogEntry(): Pair<ImageVector, Int> = when (this) {
    PlayerShortcut.PLAYBACK_SPEED -> Icons.Rounded.Speed to R.string.playback_speed
    PlayerShortcut.SCREEN_ROTATION -> Icons.Rounded.ScreenRotation to R.string.player_rotate_screen
    PlayerShortcut.VIDEO_CONTENT_SCALE -> Icons.Rounded.AspectRatio to R.string.video_scale
    PlayerShortcut.AUDIO_TRACK -> Icons.Rounded.Audiotrack to R.string.player_audio_track
    PlayerShortcut.SUBTITLE_TRACK -> Icons.Rounded.Subtitles to R.string.player_subtitles
    PlayerShortcut.LOOP -> Icons.Rounded.Repeat to R.string.player_loop
    PlayerShortcut.PLAY_IN_BACKGROUND -> Icons.Rounded.Headset to R.string.background_play_title
    PlayerShortcut.LOCK_CONTROLS -> Icons.Rounded.LockOpen to R.string.player_lock
    else -> Icons.Rounded.Speed to R.string.player_shortcuts
}
