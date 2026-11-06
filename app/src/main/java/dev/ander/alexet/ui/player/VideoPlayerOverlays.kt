package dev.ander.alexet.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaybackSpeedSheet(
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var speed by remember(currentSpeed) { mutableFloatStateOf(currentSpeed) }
    val presets = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.select_playback_speed),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )

            // Value stepper row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FilledTonalIconButton(
                    onClick = {
                        val next = (speed - 0.1f).coerceAtLeast(0.25f)
                        speed = (Math.round(next * 100f) / 100f)
                        onSelectSpeed(speed)
                    },
                ) {
                    Icon(Icons.Rounded.Remove, contentDescription = null)
                }

                Text(
                    text = String.format(androidx.compose.ui.platform.LocalLocale.current.platformLocale, "%.2fx", speed),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    ),
                    textAlign = TextAlign.Center,
                )

                FilledTonalIconButton(
                    onClick = {
                        val next = (speed + 0.1f).coerceAtMost(3.0f)
                        speed = (Math.round(next * 100f) / 100f)
                        onSelectSpeed(speed)
                    },
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                }
            }

            Slider(
                value = speed,
                valueRange = 0.25f..3.0f,
                steps = 27,
                onValueChange = {
                    speed = (Math.round(it * 20f) / 20f)
                    onSelectSpeed(speed)
                },
            )

            // Preset chips
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    val isSelected = Math.abs(preset - speed) < 0.04f
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            speed = preset
                            onSelectSpeed(preset)
                        },
                        label = {
                            Text(
                                text = if (preset == 1.0f) "1.0x (Normal)" else "${preset}x",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScaleSheet(
    currentScale: VideoContentScale,
    onSelectScale: (VideoContentScale) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.video_scale),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            VideoContentScale.entries.forEach { scaleOption ->
                val isSelected = scaleOption == currentScale
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectScale(scaleOption)
                            onDismiss()
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = scaleOption.icon,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )

                    Text(
                        text = stringResource(scaleOption.titleRes),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.weight(1f),
                    )

                    RadioButton(
                        selected = isSelected,
                        onClick = {
                            onSelectScale(scaleOption)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}
