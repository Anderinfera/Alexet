package dev.ander.alexet.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The detail pane of the wide settings: a page opened from a card is shown over it. */
class SettingsSubPageSlot {
    var page by mutableStateOf<(@Composable () -> Unit)?>(null)
}

val LocalSettingsSubPageSlot = staticCompositionLocalOf<SettingsSubPageSlot?> { null }

/**
 * A page of its own opened from a settings card (e.g. "Customize"). Wide settings show it in their
 * detail pane, next to the list of categories; on a phone it covers the screen.
 */
@Composable
fun SettingsSubPage(
    open: Boolean,
    onClose: () -> Unit,
    content: @Composable (onBack: () -> Unit) -> Unit,
) {
    if (!open) return
    val latestContent = rememberUpdatedState(content)
    val latestClose = rememberUpdatedState(onClose)
    val slot = LocalSettingsSubPageSlot.current
    if (slot != null) {
        DisposableEffect(slot) {
            val page: @Composable () -> Unit = {
                BackHandler { latestClose.value() }
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    latestContent.value { latestClose.value() }
                }
            }
            slot.page = page
            onDispose { if (slot.page === page) slot.page = null }
        }
    } else {
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    latestContent.value { latestClose.value() }
                }
            }
        }
    }
}
