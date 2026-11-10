package dev.ander.alexet.ui.videoeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R
import dev.ander.alexet.data.videoeditor.ExportFormat
import dev.ander.alexet.data.videoeditor.ExportQuality
import dev.ander.alexet.data.videoeditor.ExportResolution
import dev.ander.alexet.data.videoeditor.VideoExportConfig
import dev.ander.alexet.data.videoeditor.VideoExportEngine
import dev.ander.alexet.data.videoeditor.VideoProjectState
import dev.ander.alexet.ui.gallery.formatFileSize

@Composable
fun VideoExportDialog(
    initialConfig: VideoExportConfig,
    project: VideoProjectState,
    onConfirm: (VideoExportConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var config by remember { mutableStateOf(initialConfig.forFormat(initialConfig.format)) }
    val estimatedBytes = remember(config, project) { VideoExportEngine.estimatedSizeBytes(project, config) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.export_settings), style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. Format Selection
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.format), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ExportFormat.entries) { fmt ->
                            FilterChip(
                                selected = config.format == fmt,
                                onClick = { config = config.forFormat(fmt) },
                                label = { Text(fmt.name) },
                            )
                        }
                    }
                }

                // 2. Resolution Selection
                if (config.format != ExportFormat.AUDIO_ONLY) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.resolution), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(resolutionsFor(config.format)) { res ->
                                FilterChip(
                                    selected = config.resolution == res,
                                    onClick = { config = config.copy(resolution = res) },
                                    label = { Text(res.label) },
                                )
                            }
                        }
                    }
                }

                // 3. Quality & Compression Slider
                if (config.format != ExportFormat.GIF) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.quality),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${config.qualityPercent}%",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        androidx.compose.material3.Slider(
                            value = config.qualityPercent.toFloat(),
                            onValueChange = { config = config.copy(qualityPercent = kotlin.math.round(it).toInt()) },
                            valueRange = 10f..100f,
                            steps = 17, // 5% increments
                        )
                        if (estimatedBytes != null) {
                            Text(
                                stringResource(
                                    R.string.export_estimated_size,
                                    formatFileSize(estimatedBytes),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(config) }) {
                Text(stringResource(R.string.export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_no))
            }
        },
    )
}

/** GIF stops at 1080p: a 4K GIF is hundreds of MB and takes very long to make. */
private fun resolutionsFor(format: ExportFormat): List<ExportResolution> =
    if (format == ExportFormat.GIF) ExportResolution.entries.filter { it != ExportResolution.RES_4K }
    else ExportResolution.entries

private fun VideoExportConfig.forFormat(format: ExportFormat): VideoExportConfig = copy(
    format = format,
    resolution = if (resolution in resolutionsFor(format)) resolution else ExportResolution.RES_1080P,
)
