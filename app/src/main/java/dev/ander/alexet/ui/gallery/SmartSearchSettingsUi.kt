package dev.ander.alexet.ui.gallery

import androidx.compose.runtime.Immutable
import dev.ander.alexet.data.AiEffectsConfig
import dev.ander.alexet.data.Album
import dev.ander.alexet.data.SmartIndexScope
import dev.ander.alexet.data.smartsearch.SmartSearch

/** The AI search card of the settings: its state and actions. */
@Immutable
class SmartSearchSettingsUi(
    val status: SmartSearch.Status,
    val onEnable: (Boolean) -> Unit,
    val onImportModel: () -> Unit,
    val onRemoveModel: () -> Unit,
    val onClearIndex: () -> Unit,
    val loadLicenses: suspend () -> String?,
    val onAiEffects: ((AiEffectsConfig) -> AiEffectsConfig) -> Unit = {},
    /** Folders for the "which folders to index" choice. */
    val albums: List<Album> = emptyList(),
    val allAlbums: List<Album> = emptyList(),
    val onIndexScope: (SmartIndexScope) -> Unit = {},
    /** Changes the folder list of the current scope (only what the user changed). */
    val onIndexFolders: (added: Set<Long>, removed: Set<Long>) -> Unit = { _, _ -> },
    val onIndexPaused: (Boolean) -> Unit = {},
)
