package dev.ander.alexet.ui.wallpaper

import androidx.compose.foundation.layout.requiredSize
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ander.alexet.R
import dev.ander.alexet.data.AppSettings
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.ImageEditEngine
import dev.ander.alexet.foldable.rememberFoldableState
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WallpaperCropRequest(
    val media: GalleryMedia,
    val whichFlags: Int,
)

data class WallpaperCropResult(
    val crop: CropRectPx,
    val outputWidth: Int,
    val outputHeight: Int,
    val previewScreen: WallpaperPreviewScreen,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperCropScreen(
    media: GalleryMedia,
    @Suppress("UNUSED_PARAMETER") whichFlags: Int,
    applying: Boolean,
    settings: AppSettings? = null,
    onSaveResolutions: ((Int, Int, Int, Int) -> Unit)? = null,
    onConfirm: (WallpaperCropResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val foldableState = rememberFoldableState()
    var showResolutionsDialog by rememberSaveable { mutableStateOf(false) }
    val targets = remember(context, foldableState.windowSizeClass, foldableState.foldingFeature, settings) {
        resolveWallpaperScreenTargets(context, foldableState, settings)
    }

    // Saved (with the zoom and position below): a process restart brings back the same crop.
    var previewScreen by rememberSaveable {
        mutableStateOf(
            if (foldableState.isMediumWidth || foldableState.isExpandedWidth) {
                WallpaperPreviewScreen.UNFOLDED
            } else {
                WallpaperPreviewScreen.FOLDED
            },
        )
    }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    var userScale by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    // A restored crop is not reset by the first decode and the first screen pick.
    val restoredCrop = remember { userScale != 1f || offsetX != 0f || offsetY != 0f }
    var keepCropOnDecode by remember { mutableStateOf(restoredCrop) }
    var keepCropOnScreen by remember { mutableStateOf(restoredCrop) }
    var frameW by remember { mutableFloatStateOf(1f) }
    var frameH by remember { mutableFloatStateOf(1f) }

    BackHandler(enabled = !applying) { onCancel() }

    LaunchedEffect(media.id, media.uri, media.absolutePath) {
        loadFailed = false
        bitmap = null
        val decoded = withContext(Dispatchers.IO) {
            val file = media.absolutePath?.let { File(it) }?.takeIf { it.exists() }
            ImageEditEngine.decodeSourceBitmap(
                context = context,
                sourceFile = file,
                sourceUri = media.uri,
                maxDim = 4096,
            )
        }
        if (decoded == null) {
            loadFailed = true
        } else {
            bitmap = decoded
            if (keepCropOnDecode) {
                keepCropOnDecode = false
            } else {
                userScale = 1f
                offsetX = 0f
                offsetY = 0f
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            bitmap?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    LaunchedEffect(previewScreen) {
        if (keepCropOnScreen) {
            keepCropOnScreen = false
            return@LaunchedEffect
        }
        userScale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    val frameAspect = targets.aspectFor(previewScreen)
    val desiredForOutput = targets.sizeFor(previewScreen)

    Surface(
        modifier = modifier
            .fillMaxSize()
            .zIndex(400f),
        color = Color.Black,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel, enabled = !applying) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.cancel),
                        tint = Color.White,
                    )
                }
                Text(
                    text = stringResource(R.string.wallpaper_crop_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                if (onSaveResolutions != null && settings != null) {
                    IconButton(
                        onClick = { showResolutionsDialog = true },
                        enabled = !applying,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = stringResource(R.string.wallpaper_resolutions_title),
                            tint = Color.White,
                        )
                    }
                }
                TextButton(
                    onClick = {
                        val bmp = bitmap ?: return@TextButton
                        val crop = cropRectInImagePixels(
                            imageWidth = bmp.width,
                            imageHeight = bmp.height,
                            frameWidth = frameW,
                            frameHeight = frameH,
                            userScale = userScale,
                            offsetX = offsetX,
                            offsetY = offsetY,
                        )
                        val out = outputSizeForAspect(
                            frameAspect = frameAspect,
                            desiredWidth = desiredForOutput.width,
                            desiredHeight = desiredForOutput.height,
                        )
                        onConfirm(
                            WallpaperCropResult(
                                crop = crop,
                                outputWidth = out.width,
                                outputHeight = out.height,
                                previewScreen = previewScreen,
                            ),
                        )
                    },
                    enabled = !applying && bitmap != null && !loadFailed,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.done), color = Color.White)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                ScreenChip(
                    selected = previewScreen == WallpaperPreviewScreen.FOLDED,
                    label = stringResource(R.string.wallpaper_screen_folded),
                    shortLabel = "${targets.foldedWidth}×${targets.foldedHeight}",
                    enabled = !applying,
                    onClick = { previewScreen = WallpaperPreviewScreen.FOLDED },
                )
                ScreenChip(
                    selected = previewScreen == WallpaperPreviewScreen.UNFOLDED,
                    label = stringResource(R.string.wallpaper_screen_unfolded),
                    shortLabel = "${targets.unfoldedWidth}×${targets.unfoldedHeight}",
                    enabled = !applying,
                    onClick = { previewScreen = WallpaperPreviewScreen.UNFOLDED },
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clipToBounds(),
            ) {
                val containerW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val (fw, fh) = fitFrameSize(containerW, containerH, frameAspect)
                androidx.compose.runtime.SideEffect {
                    frameW = fw
                    frameH = fh
                }

                val bmp = bitmap
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loadFailed -> {
                            Text(
                                text = stringResource(R.string.wallpaper_set_failed),
                                color = Color.White,
                            )
                        }
                        bmp == null -> {
                            CircularProgressIndicator(color = Color.White)
                        }
                        else -> {
                            WallpaperCropViewport(
                                bitmap = bmp,
                                frameWidth = fw,
                                frameHeight = fh,
                                userScale = userScale,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                gestureEnabled = !applying,
                                onTransform = { zoomChange, pan ->
                                    val newScale = (userScale * zoomChange).coerceIn(1f, 8f)
                                    val base = coverScale(
                                        bmp.width.toFloat(),
                                        bmp.height.toFloat(),
                                        fw,
                                        fh,
                                    )
                                    val drawnW = bmp.width * base * newScale
                                    val drawnH = bmp.height * base * newScale
                                    val (cx, cy) = clampPan(
                                        offsetX + pan.x,
                                        offsetY + pan.y,
                                        drawnW,
                                        drawnH,
                                        fw,
                                        fh,
                                    )
                                    userScale = newScale
                                    offsetX = cx
                                    offsetY = cy
                                },
                            )
                        }
                    }

                    if (applying) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_wallpaper),
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.wallpaper_crop_hint),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }

    if (showResolutionsDialog && settings != null && onSaveResolutions != null) {
        WallpaperScreenResolutionsDialog(
            settings = settings,
            onSave = onSaveResolutions,
            onDismiss = { showResolutionsDialog = false },
        )
    }
}

@Composable
private fun ScreenChip(
    selected: Boolean,
    label: String,
    shortLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                text = "$shortLabel · $label",
                maxLines = 1,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            containerColor = Color.White.copy(alpha = 0.12f),
            labelColor = Color.White,
        ),
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun WallpaperCropViewport(
    bitmap: Bitmap,
    frameWidth: Float,
    frameHeight: Float,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
    gestureEnabled: Boolean,
    onTransform: (zoomChange: Float, pan: Offset) -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current
    val base = coverScale(
        bitmap.width.toFloat(),
        bitmap.height.toFloat(),
        frameWidth,
        frameHeight,
    )
    val total = base * userScale
    val onTransformState = rememberUpdatedState(onTransform)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(gestureEnabled, frameWidth, frameHeight, bitmap.width, bitmap.height) {
                if (!gestureEnabled) return@pointerInput
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransformState.value(zoom, pan)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            // Exactly the bitmap's pixel size, unconstrained by the screen, drawn edge to edge: the
            // crop math takes the drawn size as bitmap x total. (width/height were shrunk to the
            // parent and then fitted again, so the preview looked smaller than the saved crop.)
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
            modifier = Modifier
                .requiredSize(
                    width = with(density) { bitmap.width.toDp() },
                    height = with(density) { bitmap.height.toDp() },
                )
                .graphicsLayer {
                    scaleX = total
                    scaleY = total
                    translationX = offsetX
                    translationY = offsetY
                },
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val frameLeft = (size.width - frameWidth) / 2f
            val frameTop = (size.height - frameHeight) / 2f
            val scrim = Color.Black.copy(alpha = 0.55f)
            drawRect(scrim, Offset.Zero, Size(size.width, frameTop.coerceAtLeast(0f)))
            val bottomTop = frameTop + frameHeight
            drawRect(
                scrim,
                Offset(0f, bottomTop),
                Size(size.width, (size.height - bottomTop).coerceAtLeast(0f)),
            )
            drawRect(scrim, Offset(0f, frameTop), Size(frameLeft.coerceAtLeast(0f), frameHeight))
            val rightLeft = frameLeft + frameWidth
            drawRect(
                scrim,
                Offset(rightLeft, frameTop),
                Size((size.width - rightLeft).coerceAtLeast(0f), frameHeight),
            )
            drawRect(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = Offset(frameLeft, frameTop),
                size = Size(frameWidth, frameHeight),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
