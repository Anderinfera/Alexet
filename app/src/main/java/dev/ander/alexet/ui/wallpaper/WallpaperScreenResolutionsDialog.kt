package dev.ander.alexet.ui.wallpaper

import android.widget.Toast
import dev.ander.alexet.ui.components.AnimatedContent
import dev.ander.alexet.ui.components.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ander.alexet.R
import dev.ander.alexet.data.AppSettings
import java.util.Locale

@Composable
fun WallpaperScreenResolutionsDialog(
    settings: AppSettings,
    onSave: (foldedW: Int, foldedH: Int, unfoldedW: Int, unfoldedH: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val hasCustom = (settings.customWallpaperFoldedWidth > 0 && settings.customWallpaperFoldedHeight > 0) ||
        (settings.customWallpaperUnfoldedWidth > 0 && settings.customWallpaperUnfoldedHeight > 0)

    val defaultFoldedW = if (settings.customWallpaperFoldedWidth > 0) settings.customWallpaperFoldedWidth
    else if (WallpaperResolutionMemory.hasFolded()) WallpaperResolutionMemory.detectedFoldedWidth
    else if (settings.lastDetectedFoldedWidth > 0) settings.lastDetectedFoldedWidth else 1080

    val defaultFoldedH = if (settings.customWallpaperFoldedHeight > 0) settings.customWallpaperFoldedHeight
    else if (WallpaperResolutionMemory.hasFolded()) WallpaperResolutionMemory.detectedFoldedHeight
    else if (settings.lastDetectedFoldedHeight > 0) settings.lastDetectedFoldedHeight else 2424

    val defaultUnfoldedW = if (settings.customWallpaperUnfoldedWidth > 0) settings.customWallpaperUnfoldedWidth
    else if (WallpaperResolutionMemory.hasUnfolded()) WallpaperResolutionMemory.detectedUnfoldedWidth
    else if (settings.lastDetectedUnfoldedWidth > 0) settings.lastDetectedUnfoldedWidth else 2076

    val defaultUnfoldedH = if (settings.customWallpaperUnfoldedHeight > 0) settings.customWallpaperUnfoldedHeight
    else if (WallpaperResolutionMemory.hasUnfolded()) WallpaperResolutionMemory.detectedUnfoldedHeight
    else if (settings.lastDetectedUnfoldedHeight > 0) settings.lastDetectedUnfoldedHeight else 2152

    var isCustomMode by rememberSaveable { mutableStateOf(hasCustom) }
    var foldedWText by rememberSaveable { mutableStateOf(defaultFoldedW.toString()) }
    var foldedHText by rememberSaveable { mutableStateOf(defaultFoldedH.toString()) }
    var unfoldedWText by rememberSaveable { mutableStateOf(defaultUnfoldedW.toString()) }
    var unfoldedHText by rememberSaveable { mutableStateOf(defaultUnfoldedH.toString()) }

    var selectedPresetId by rememberSaveable { mutableStateOf<String?>(null) }

    // Domyślny ekran w fokusie: rozłożony jeśli okno jest szerokie, inaczej złożony
    val activity = context.findActivity()
    val curWindow = remember { currentWindowSize(activity, context) }
    val initialScreen = remember {
        val widthDp = curWindow?.let { it.width / context.resources.displayMetrics.density } ?: 0f
        if (curWindow != null && (widthDp >= 600f || curWindow.width.toFloat() / curWindow.height.toFloat() > 0.65f)) {
            WallpaperPreviewScreen.UNFOLDED
        } else {
            WallpaperPreviewScreen.FOLDED
        }
    }
    var activeScreen by rememberSaveable { mutableStateOf(initialScreen) }

    // One reading of the fields for the preview and for Save: the preview showed 50 px while 100
    // was saved, and an empty field saved a fixed size instead of the one shown (detected).
    fun sizeOf(text: String, fallback: Int) = text.toIntOrNull()?.coerceAtLeast(MIN_WALLPAPER_SIDE_PX) ?: fallback
    val fW = sizeOf(foldedWText, defaultFoldedW)
    val fH = sizeOf(foldedHText, defaultFoldedH)
    val uW = sizeOf(unfoldedWText, defaultUnfoldedW)
    val uH = sizeOf(unfoldedHText, defaultUnfoldedH)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(min = 340.dp, max = 560.dp)
                .padding(vertical = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Nagłówek z ikoną i tytułem
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.wallpaper_resolutions_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.wallpaper_resolutions_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Przełącznik trybu: Auto vs Własna rozdzielczość
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (!isCustomMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    isCustomMode = false
                                    selectedPresetId = null
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                if (!isCustomMode) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    text = stringResource(R.string.wp_auto_detect),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (!isCustomMode) FontWeight.Bold else FontWeight.Medium,
                                    color = if (!isCustomMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCustomMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { isCustomMode = true },
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                if (isCustomMode) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    text = stringResource(R.string.wp_custom_resolution),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isCustomMode) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCustomMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Typowe proporcje ekranów (in-memory)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.wp_common_ratios),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TYPICAL_WALLPAPER_RATIOS.forEach { preset ->
                            val isSelected = selectedPresetId == preset.id ||
                                (fW == preset.sampleFoldedW && fH == preset.sampleFoldedH &&
                                    uW == preset.sampleUnfoldedW && uH == preset.sampleUnfoldedH && isCustomMode)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    isCustomMode = true
                                    selectedPresetId = preset.id
                                    foldedWText = preset.sampleFoldedW.toString()
                                    foldedHText = preset.sampleFoldedH.toString()
                                    unfoldedWText = preset.sampleUnfoldedW.toString()
                                    unfoldedHText = preset.sampleUnfoldedH.toString()
                                },
                                label = {
                                    Text(
                                        text = preset.label,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            )
                        }
                    }
                }

                // Sekcja wyboru ekranu i podglądu proporcji (Interaktywny selektor)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.wp_choose_screen),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.wp_tap_tile),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Karta ekranu złożonego
                        val isFoldedActive = activeScreen == WallpaperPreviewScreen.FOLDED
                        val foldedAspect = (fW.toFloat() / fH.toFloat()).coerceIn(0.2f, 2.5f)
                        val foldedBoxW = (54.dp * foldedAspect).coerceIn(24.dp, 54.dp)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isFoldedActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                            border = BorderStroke(
                                if (isFoldedActive) 2.dp else 1.dp,
                                if (isFoldedActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    activeScreen = WallpaperPreviewScreen.FOLDED
                                    isCustomMode = true
                                },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Smartphone,
                                        contentDescription = null,
                                        tint = if (isFoldedActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    if (isFoldedActive) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.wp_editing),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .height(54.dp)
                                        .width(foldedBoxW)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isFoldedActive) 0.25f else 0.12f))
                                        .border(
                                            BorderStroke(if (isFoldedActive) 2.dp else 1.dp, MaterialTheme.colorScheme.primary),
                                            RoundedCornerShape(6.dp),
                                        ),
                                )

                                Text(
                                    text = stringResource(R.string.wallpaper_screen_folded),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isFoldedActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isFoldedActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "$fW × $fH px",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        // Karta ekranu rozłożonego
                        val isUnfoldedActive = activeScreen == WallpaperPreviewScreen.UNFOLDED
                        val unfoldedAspect = (uW.toFloat() / uH.toFloat()).coerceIn(0.2f, 2.5f)
                        val unfoldedBoxW = (54.dp * unfoldedAspect).coerceIn(24.dp, 54.dp)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isUnfoldedActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                            border = BorderStroke(
                                if (isUnfoldedActive) 2.dp else 1.dp,
                                if (isUnfoldedActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    activeScreen = WallpaperPreviewScreen.UNFOLDED
                                    isCustomMode = true
                                },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.TabletAndroid,
                                        contentDescription = null,
                                        tint = if (isUnfoldedActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    if (isUnfoldedActive) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.wp_editing),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .height(54.dp)
                                        .width(unfoldedBoxW)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = if (isUnfoldedActive) 0.25f else 0.12f))
                                        .border(
                                            BorderStroke(if (isUnfoldedActive) 2.dp else 1.dp, MaterialTheme.colorScheme.secondary),
                                            RoundedCornerShape(6.dp),
                                        ),
                                )

                                Text(
                                    text = stringResource(R.string.wallpaper_screen_unfolded),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isUnfoldedActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isUnfoldedActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "$uW × $uH px",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                // Animowane pola wprowadzania: wyświetla TYLKO ten ekran, który jest aktualnie w fokusie
                AnimatedContent(
                    targetState = activeScreen,
                    transitionSpec = {
                        if (targetState == WallpaperPreviewScreen.UNFOLDED) {
                            (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(180)))
                        } else {
                            (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(180)))
                        }
                    },
                    label = "screenFieldsAnim",
                ) { screen ->
                    val isFolded = screen == WallpaperPreviewScreen.FOLDED
                    val currentW = if (isFolded) fW else uW
                    val currentH = if (isFolded) fH else uH
                    val currentTitle = stringResource(if (isFolded) R.string.wp_dims_folded_title else R.string.wp_dims_unfolded_title)

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = currentTitle,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    text = stringResource(R.string.wp_aspect_value, formatAspect(currentW, currentH)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = if (isFolded) foldedWText else unfoldedWText,
                                onValueChange = {
                                    isCustomMode = true
                                    selectedPresetId = null
                                    val clean = it.filter { ch -> ch.isDigit() }
                                    if (isFolded) foldedWText = clean else unfoldedWText = clean
                                },
                                label = { Text(stringResource(R.string.wp_width_px)) },
                                placeholder = { Text(if (isFolded) "1080" else "2076") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = if (isFolded) foldedHText else unfoldedHText,
                                onValueChange = {
                                    isCustomMode = true
                                    selectedPresetId = null
                                    val clean = it.filter { ch -> ch.isDigit() }
                                    if (isFolded) foldedHText = clean else unfoldedHText = clean
                                },
                                label = { Text(stringResource(R.string.wp_height_px)) },
                                placeholder = { Text(if (isFolded) "2424" else "2152") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                    }
                }

                // Przycisk "Pobierz z obecnego ekranu" (ustawia TYLKO aktywny ekran w fokusie!)
                val targetScreenLabel = stringResource(if (activeScreen == WallpaperPreviewScreen.FOLDED) R.string.wp_target_folded else R.string.wp_target_unfolded)
                OutlinedButton(
                    onClick = {
                        val cur = currentWindowSize(activity, context)
                        if (cur != null && cur.width > 0 && cur.height > 0) {
                            isCustomMode = true
                            selectedPresetId = null
                            if (activeScreen == WallpaperPreviewScreen.FOLDED) {
                                foldedWText = cur.width.toString()
                                foldedHText = cur.height.toString()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.wp_took_size_folded, cur.width, cur.height),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                unfoldedWText = cur.width.toString()
                                unfoldedHText = cur.height.toString()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.wp_took_size_unfolded, cur.width, cur.height),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        } else {
                            Toast.makeText(context, R.string.wp_read_screen_failed, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = if (activeScreen == WallpaperPreviewScreen.FOLDED) Icons.Rounded.Smartphone else Icons.Rounded.TabletAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wp_take_from_screen, targetScreenLabel))
                }

                // Dolne przyciski akcji: Przywróć auto, Anuluj, Zapisz
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            onSave(0, 0, 0, 0)
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.wallpaper_res_reset_auto))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = {
                                if (isCustomMode) {
                                    onSave(fW, fH, uW, uH)
                                } else {
                                    onSave(0, 0, 0, 0)
                                }
                                onDismiss()
                            },
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }
}

private fun formatAspect(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "—"
    val ratio = width.toFloat() / height.toFloat()
    return when {
        ratio in 0.92f..1.08f -> "1:1 (~kwadrat)"
        ratio < 1f -> {
            val inv = height.toFloat() / width.toFloat()
            String.format(Locale.US, "1:%.2f (~%.2f)", inv, ratio)
        }
        else -> {
            String.format(Locale.US, "%.2f:1 (~%.2f)", ratio, ratio)
        }
    }
}

/** Smallest wallpaper side accepted in the custom sizes. */
private const val MIN_WALLPAPER_SIDE_PX = 100
