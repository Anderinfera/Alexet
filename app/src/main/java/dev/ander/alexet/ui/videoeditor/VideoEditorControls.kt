package dev.ander.alexet.ui.videoeditor

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ander.alexet.R
import dev.ander.alexet.data.videoeditor.VideoProjectAspectRatio
import kotlin.math.roundToInt

@Composable
fun VideoEditorControls(
    state: VideoEditorStateHolder,
    onAddAudioClick: () -> Unit,
    onAddVideoAsAudioClick: () -> Unit,
    onAddStickerClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Another picture for the sticker with this id. */
    onReplaceStickerClick: (String) -> Unit = {},
) {
    val selectedClip = state.project.selectedClip
    // System (Material You) accent instead of a fixed blue.
    val accent = MaterialTheme.colorScheme.primary

    var showRotationDialog by remember { mutableStateOf(false) }
    var showAudioSourceDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF18181B)),
    ) {
        // --- Tool Specific Content Area ---
        when (state.currentTab) {
            VideoEditorTab.TIMELINE, VideoEditorTab.TRIM_SPLIT -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.trim_split),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    // Stepper buttons to move the playhead by exact amounts: equal widths, one line each,
                    // so all four fit on a narrow screen.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(-1000L to "-1s", -100L to "-100ms", 100L to "+100ms", 1000L to "+1s").forEach { (delta, label) ->
                            FilledTonalButton(
                                onClick = {
                                    state.seekTo(state.playheadMs + delta)
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(label, maxLines = 1, softWrap = false)
                            }
                        }
                    }

                    // Split Button
                    Button(
                        onClick = { state.splitSelectedClipAtPlayhead() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.ContentCut, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.split_clip))
                    }
                }
            }

            VideoEditorTab.SPEED -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    var applyToAll by remember { mutableStateOf(false) }
                    val currentSpeed = selectedClip?.speed ?: 1.0f

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${stringResource(R.string.speed)}: ${currentSpeed}x",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.apply_to_all), style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
                            Spacer(Modifier.width(6.dp))
                            Switch(checked = applyToAll, onCheckedChange = { applyToAll = it })
                        }
                    }

                    // Speed Chips
                    val speedOptions = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(speedOptions) { spd ->
                            FilterChip(
                                selected = currentSpeed == spd,
                                onClick = { state.updateSpeed(spd, applyToAll) },
                                label = { Text("${spd}x") },
                            )
                        }
                    }
                }
            }

            VideoEditorTab.AUDIO -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val clip = selectedClip
                    val isMuted = clip?.isMuted ?: false
                    val volume = clip?.volume ?: 1.0f

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${stringResource(R.string.clip_volume)}: ${(volume * 100).roundToInt()}%",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.mute_original), style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = isMuted,
                                onCheckedChange = { state.updateVolume(volume, it) },
                            )
                        }
                    }

                    if (!isMuted) {
                        Slider(
                            value = volume,
                            // One undo step per slide (updateVolume records it): a second one
                            // here made the first Undo after it do nothing.
                            onValueChange = { state.updateVolume(it, false, recordUndo = false) },
                            valueRange = 0f..2f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Add Audio Options
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showAudioSourceDialog = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.add_audio))
                        }
                    }

                    // Audio Tracks List
                    if (state.project.audioTracks.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.project.audioTracks.forEach { track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF27272A), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            if (track.isVideoSource) Icons.Rounded.VideoLibrary else Icons.Rounded.Audiotrack,
                                            contentDescription = null,
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(track.displayName, color = Color.White, fontSize = 12.sp, maxLines = 1)
                                    }
                                    IconButton(
                                        onClick = { state.removeAudioTrack(track.id) },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            VideoEditorTab.ADJUST -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val clip = selectedClip ?: state.project.clips.firstOrNull() ?: return@Column
                    var currentParam by remember { mutableStateOf("brightness") }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when (currentParam) {
                                "brightness" -> "${stringResource(R.string.brightness)}: ${(clip.brightness * 100).roundToInt()}"
                                "contrast" -> "${stringResource(R.string.contrast)}: ${(clip.contrast * 100).roundToInt()}"
                                "saturation" -> "${stringResource(R.string.saturation)}: ${(clip.saturation * 100).roundToInt()}"
                                else -> "${stringResource(R.string.gamma)}: ${String.format("%.2f", clip.gamma)}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )

                        // Rotation and Flip
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalIconButton(
                                onClick = { state.updateRotationFlip((clip.rotation - 90 + 360) % 360, clip.flipH, clip.flipV) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.RotateLeft, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            FilledTonalIconButton(
                                onClick = { state.updateRotationFlip((clip.rotation + 90) % 360, clip.flipH, clip.flipV) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            FilledTonalIconButton(
                                onClick = { state.updateRotationFlip(clip.rotation, !clip.flipH, clip.flipV) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Rounded.Flip, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { state.updateAdjust(0f, 0f, 0f, 1.0f) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    val currentValue = when (currentParam) {
                        "brightness" -> clip.brightness
                        "contrast" -> clip.contrast
                        "saturation" -> clip.saturation
                        else -> clip.gamma - 1f
                    }

                    Slider(
                        value = currentValue,
                        onValueChange = { newVal ->
                            when (currentParam) {
                                "brightness" -> state.updateAdjust(newVal, clip.contrast, clip.saturation, clip.gamma)
                                "contrast" -> state.updateAdjust(clip.brightness, newVal, clip.saturation, clip.gamma)
                                "saturation" -> state.updateAdjust(clip.brightness, clip.contrast, newVal, clip.gamma)
                                "gamma" -> state.updateAdjust(clip.brightness, clip.contrast, clip.saturation, 1f + newVal)
                            }
                        },
                        valueRange = -1f..1f,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        FilterChip(
                            selected = currentParam == "brightness",
                            onClick = { currentParam = "brightness" },
                            label = { Text(stringResource(R.string.brightness)) },
                        )
                        FilterChip(
                            selected = currentParam == "contrast",
                            onClick = { currentParam = "contrast" },
                            label = { Text(stringResource(R.string.contrast)) },
                        )
                        FilterChip(
                            selected = currentParam == "saturation",
                            onClick = { currentParam = "saturation" },
                            label = { Text(stringResource(R.string.saturation)) },
                        )
                        FilterChip(
                            selected = currentParam == "gamma",
                            onClick = { currentParam = "gamma" },
                            label = { Text(stringResource(R.string.gamma)) },
                        )
                    }
                }
            }

            VideoEditorTab.ASPECT -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.aspect_ratio),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // "Fit" toggle: the picture fits the frame and stays inside it; lit while on.
                            FilledTonalButton(
                                onClick = { state.toggleFit() },
                                colors = if (state.fitLocked) {
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = accent,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    ButtonDefaults.filledTonalButtonColors()
                                },
                            ) {
                                Icon(Icons.Rounded.FitScreen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.fit_screen), fontSize = 12.sp)
                            }

                            // Video rotation button opening popup dialog
                            IconButton(onClick = { showRotationDialog = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.RotateRight,
                                    contentDescription = stringResource(R.string.rotate_right),
                                    tint = Color.White,
                                )
                            }

                            val currentClipForFraming = selectedClip ?: state.project.clips.firstOrNull()
                            if (currentClipForFraming?.let { it.zoomScale != 1.0f || it.panX != 0f || it.panY != 0f } == true) {
                                IconButton(onClick = {
                                    state.fitLocked = false
                                    state.framingLocked = false
                                    state.updateFraming(0f, 0f, 1.0f)
                                }) {
                                    Icon(Icons.Rounded.RestartAlt, contentDescription = stringResource(R.string.reset_changes), tint = Color.LightGray)
                                }
                            }
                        }
                    }

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(VideoProjectAspectRatio.entries) { opt ->
                            FilterChip(
                                selected = state.project.aspectRatio == opt,
                                onClick = { state.setAspectRatio(opt) },
                                label = { Text(stringResource(opt.labelRes)) },
                            )
                        }
                    }
                }
            }

            VideoEditorTab.TEXT -> {
                val selectedText = state.project.textOverlays.find { it.id == state.project.selectedTextId }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selectedText != null) {
                        // The selected text: its panel, turn and size (as a sticker has).
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = selectedText.text,
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalButton(onClick = { state.textPanel = TextPanelRequest(selectedText.id) }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.edit), fontSize = 12.sp)
                                }
                                IconButton(onClick = { state.removeTextOverlay(selectedText.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete), tint = Color(0xFFEF4444))
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${stringResource(R.string.rotate_overlay)}: ${selectedText.rotationDeg.roundToInt()}°",
                                style = MaterialTheme.typography.labelMedium,
                                color = accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(0f, 90f, 180f, 270f).forEach { deg ->
                                    FilterChip(
                                        selected = selectedText.rotationDeg.roundToInt() == deg.toInt(),
                                        onClick = {
                                            state.setTextRotation(selectedText.id, deg)
                                            state.endGesture()
                                        },
                                        label = { Text("${deg.toInt()}°", fontSize = 10.sp) },
                                    )
                                }
                            }
                        }
                        Slider(
                            value = selectedText.rotationDeg,
                            onValueChange = { state.setTextRotation(selectedText.id, it) },
                            onValueChangeFinished = { state.endGesture() },
                            valueRange = 0f..360f,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${stringResource(R.string.scale_overlay)}: ${String.format("%.1fx", selectedText.scale)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                            )
                            FilledTonalButton(onClick = { state.textPanel = TextPanelRequest(null) }) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.add_text), fontSize = 12.sp)
                            }
                        }
                        Slider(
                            value = selectedText.scale.coerceIn(0.3f, 3f),
                            onValueChange = { newScale -> state.scaleTextOverlay(selectedText.id, newScale - selectedText.scale) },
                            onValueChangeFinished = { state.endGesture() },
                            valueRange = 0.3f..3f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Button(
                            onClick = { state.textPanel = TextPanelRequest(null) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.add_text))
                        }
                    }
                }
            }

            VideoEditorTab.STICKER -> {
                val selectedImage = state.project.imageOverlays.find { it.id == state.project.selectedImageId }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selectedImage != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.add_sticker),
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalButton(onClick = { onReplaceStickerClick(selectedImage.id) }) {
                                    Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.change_image), fontSize = 12.sp)
                                }
                                IconButton(onClick = { state.removeImageOverlay(selectedImage.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete), tint = Color(0xFFEF4444))
                                }
                            }
                        }

                        // Rotation Section
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${stringResource(R.string.rotate_overlay)}: ${selectedImage.rotationDeg.roundToInt()}°",
                                style = MaterialTheme.typography.labelMedium,
                                color = accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(0f, 90f, 180f, 270f).forEach { deg ->
                                    FilterChip(
                                        selected = selectedImage.rotationDeg.roundToInt() == deg.toInt(),
                                        onClick = { state.setImageRotation(selectedImage.id, deg) },
                                        label = { Text("${deg.toInt()}°", fontSize = 10.sp) },
                                    )
                                }
                            }
                        }

                        Slider(
                            value = selectedImage.rotationDeg,
                            onValueChange = { state.setImageRotation(selectedImage.id, it) },
                            valueRange = 0f..360f,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Scale Section
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${stringResource(R.string.scale_overlay)}: ${String.format("%.1fx", selectedImage.scale)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                            )
                            FilledTonalButton(onClick = onAddStickerClick) {
                                Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.add_sticker), fontSize = 12.sp)
                            }
                        }

                        Slider(
                            value = selectedImage.scale,
                            onValueChange = { newScale ->
                                state.scaleImageOverlay(selectedImage.id, newScale - selectedImage.scale)
                            },
                            valueRange = 0.4f..3.0f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Button(
                            onClick = onAddStickerClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Image, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.add_sticker))
                        }
                    }
                }
            }
        }

        // --- Bottom Tool Navigation Tabs ---
        // Default height: a fixed 64 dp cut the selected tab's indicator at the top. Insets come from
        // the parent (navigationBarsPadding), so none here.
        NavigationBar(
            containerColor = Color(0xFF121214),
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            NavigationBarItem(
                selected = state.currentTab == VideoEditorTab.TRIM_SPLIT,
                onClick = { state.currentTab = VideoEditorTab.TRIM_SPLIT },
                icon = { Icon(Icons.Rounded.ContentCut, contentDescription = null) },
                label = { TabLabel(stringResource(R.string.tab_trim)) },
            )
            NavigationBarItem(
                selected = state.currentTab == VideoEditorTab.SPEED,
                onClick = { state.currentTab = VideoEditorTab.SPEED },
                icon = { Icon(Icons.Rounded.Speed, contentDescription = null) },
                label = { TabLabel(stringResource(R.string.speed)) },
            )
            NavigationBarItem(
                selected = state.currentTab == VideoEditorTab.AUDIO,
                onClick = { state.currentTab = VideoEditorTab.AUDIO },
                icon = { Icon(Icons.Rounded.Audiotrack, contentDescription = null) },
                label = { TabLabel(stringResource(R.string.audio_mixer)) },
            )
            NavigationBarItem(
                selected = state.currentTab == VideoEditorTab.ADJUST,
                onClick = { state.currentTab = VideoEditorTab.ADJUST },
                icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                label = { TabLabel(stringResource(R.string.adjust)) },
            )
            NavigationBarItem(
                selected = state.currentTab == VideoEditorTab.ASPECT,
                onClick = { state.currentTab = VideoEditorTab.ASPECT },
                icon = { Icon(Icons.Rounded.AspectRatio, contentDescription = null) },
                label = { TabLabel(stringResource(R.string.aspect_ratio)) },
            )
            NavigationBarItem(
                selected = state.currentTab == VideoEditorTab.TEXT,
                onClick = { state.currentTab = VideoEditorTab.TEXT },
                icon = { Icon(Icons.Rounded.TextFields, contentDescription = null) },
                label = { TabLabel(stringResource(R.string.tab_text)) },
            )
            // The sticker tab was there but had no button to open it.
            NavigationBarItem(
                selected = state.currentTab == VideoEditorTab.STICKER,
                onClick = { state.currentTab = VideoEditorTab.STICKER },
                icon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                label = { TabLabel(stringResource(R.string.tab_stickers)) },
            )
        }
    }

    // Add Audio Source Chooser Dialog
    if (showAudioSourceDialog) {
        AlertDialog(
            onDismissRequest = { showAudioSourceDialog = false },
            title = { Text(stringResource(R.string.add_audio)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            showAudioSourceDialog = false
                            onAddAudioClick()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Audiotrack, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.select_audio_file))
                    }
                    FilledTonalButton(
                        onClick = {
                            showAudioSourceDialog = false
                            onAddVideoAsAudioClick()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.VideoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.extract_audio_from_video))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAudioSourceDialog = false }) {
                    Text(stringResource(R.string.action_no))
                }
            },
        )
    }

    if (showRotationDialog) {
        val targetClip = selectedClip ?: state.project.clips.firstOrNull()
        if (targetClip != null) {
            AlertDialog(
                onDismissRequest = { showRotationDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.RotateRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(stringResource(R.string.rotate_overlay))
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // Current angle display
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.ve_rotation_angle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${targetClip.rotation}°",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        // Presets: -90°, +90°, 180°, 0°
                        Text(
                            text = stringResource(R.string.ve_quick_options),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    val newRot = (targetClip.rotation - 90 + 360) % 360
                                    state.updateRotationFlip(newRot, targetClip.flipH, targetClip.flipV)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                            ) {
                                Text("-90°", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = {
                                    val newRot = (targetClip.rotation + 90) % 360
                                    state.updateRotationFlip(newRot, targetClip.flipH, targetClip.flipV)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                            ) {
                                Text("+90°", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = {
                                    val newRot = (targetClip.rotation + 180) % 360
                                    state.updateRotationFlip(newRot, targetClip.flipH, targetClip.flipV)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                            ) {
                                Text("180°", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = {
                                    state.updateRotationFlip(0, targetClip.flipH, targetClip.flipV)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                            ) {
                                Text("0°", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }

                        // Custom angle slider (0°..360°)
                        Text(
                            text = stringResource(R.string.ve_custom_angle),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Slider(
                            value = targetClip.rotation.toFloat(),
                            onValueChange = { newVal ->
                                // One undo step per drag (recorded inside updateRotationFlip).
                                val rounded = (newVal.roundToInt() % 360 + 360) % 360
                                state.updateRotationFlip(rounded, targetClip.flipH, targetClip.flipV, recordUndo = false)
                            },
                            valueRange = 0f..360f,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Stepper buttons -1° / +1°
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    val newRot = (targetClip.rotation - 1 + 360) % 360
                                    state.updateRotationFlip(newRot, targetClip.flipH, targetClip.flipV)
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Rounded.Remove, contentDescription = "-1°", modifier = Modifier.size(18.dp))
                            }

                            Text(
                                text = stringResource(R.string.ve_step_1deg),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )

                            FilledTonalIconButton(
                                onClick = {
                                    val newRot = (targetClip.rotation + 1) % 360
                                    state.updateRotationFlip(newRot, targetClip.flipH, targetClip.flipV)
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = "+1°", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showRotationDialog = false }) {
                        Text(stringResource(R.string.action_yes))
                    }
                },
            )
        }
    }
}

/** Bottom tab label: one line, never wrapped (a second line pushed the tab out of the bar). */
@Composable
private fun TabLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
