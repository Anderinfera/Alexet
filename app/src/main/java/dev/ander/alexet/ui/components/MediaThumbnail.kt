package dev.ander.alexet.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.VideoDurationFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import android.content.Context
import android.graphics.drawable.Animatable
import coil3.SingletonImageLoader
import coil3.asDrawable

val LocalFolderThumbEpoch = compositionLocalOf { 0 }
val LocalFolderCoverBumps = compositionLocalOf<Map<Long, Int>> { emptyMap() }
val LocalVideoDurationFormat = compositionLocalOf { VideoDurationFormat.MINUTES }

/** Side in pixels of grid thumbnails; [THUMB_LIGHT_PX] unless high-resolution thumbnails are on. */
val LocalThumbnailPx = compositionLocalOf { THUMB_LIGHT_PX }

const val THUMB_LIGHT_PX = 320
private const val THUMB_MAX_PX = 768
private const val THUMB_STEP_PX = 64

/**
 * Thumbnail side for a grid of [columns] on a screen [screenWidthPx] wide: one tile in physical
 * pixels (so the phone's density is already in it), rounded up to a step so the cache key stays
 * the same while the layout is unchanged. Light mode keeps [THUMB_LIGHT_PX].
 */
fun thumbnailPx(highRes: Boolean, screenWidthPx: Int, columns: Int): Int {
    if (!highRes) return THUMB_LIGHT_PX
    val tile = screenWidthPx / columns.coerceAtLeast(1)
    val stepped = (tile + THUMB_STEP_PX - 1) / THUMB_STEP_PX * THUMB_STEP_PX
    return stepped.coerceIn(THUMB_LIGHT_PX, THUMB_MAX_PX)
}

/** Cache key suffix; empty for the light size so existing cache entries stay valid. */
private fun thumbSizeSuffix(px: Int) = if (px == THUMB_LIGHT_PX) "" else "-s$px"

@Composable
fun MediaThumbnail(
    media: GalleryMedia,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showPlayIcon: Boolean = true,
    showDuration: Boolean = true,
    thumbnail: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    onDecodedSize: ((IntSize) -> Unit)? = null,
    cacheKey: String? = null,
    thumbGeneration: Int = 0,
    isFavorite: Boolean = false,
    skipLoad: Boolean = false,
    isGifPlaying: Boolean = true,
    onAnimatableReady: ((Animatable?) -> Unit)? = null,
    durationFormat: VideoDurationFormat = LocalVideoDurationFormat.current,
    isFolderCover: Boolean = false,
) {
    val context = LocalContext.current
    val thumbPx = LocalThumbnailPx.current
    val resolvedKey = cacheKey ?: if (thumbnail) {
        "thumb-${media.id}-${media.dateModifiedMs}"
    } else {
        "full-${media.id}-${media.dateModifiedMs}"
    }
    val sizeSuffix = if (thumbnail) thumbSizeSuffix(thumbPx) else ""
    val coverCacheKey = "$resolvedKey-g$thumbGeneration$sizeSuffix"
    // A cover once shown stays on its tile even after the pinned cache dropped it.
    val shownCover = remember(coverCacheKey) { arrayOfNulls<android.graphics.Bitmap>(1) }
    val pinnedCover = if (isFolderCover) {
        (dev.ander.alexet.data.FolderCoverCache.get(coverCacheKey, resolvedKey + sizeSuffix) ?: shownCover[0])
            ?.also { shownCover[0] = it }
    } else null
    var attempt by remember(media.id, resolvedKey) { mutableIntStateOf(0) }
    var failed by remember(media.id, resolvedKey, attempt) { mutableStateOf(false) }
    // Failed every try a moment ago: shown as broken right away, nothing is loaded again.
    val failureKey = "$resolvedKey-${media.sizeBytes}"
    val knownBroken = remember(failureKey) { ThumbnailFailures.isFailed(failureKey) }
    // Key of the image last shown here: stays on screen while a new size loads (fold / unfold).
    val shownKey = remember(media.id, resolvedKey) { arrayOfNulls<String>(1) }
    var currentAnimatable by remember { mutableStateOf<Animatable?>(null) }
    // MediaStore had no duration (file it could not parse): use the one read for the thumbnail.
    var fallbackDurationMs by remember(media.id) { mutableLongStateOf(VideoDurationCache.get(media.id)) }
    val durationMs = if (media.durationMs > 0L) media.durationMs else fallbackDurationMs
    val isEncrypted = media.absolutePath?.endsWith(dev.ander.alexet.data.SecureFolderManager.ENC_EXTENSION, ignoreCase = true) == true ||
        media.displayName.endsWith(dev.ander.alexet.data.SecureFolderManager.ENC_EXTENSION, ignoreCase = true) ||
        media.uri.path?.endsWith(dev.ander.alexet.data.SecureFolderManager.ENC_EXTENSION, ignoreCase = true) == true
    // Light mode: a thumbnail appears at once, without fading in over two drawn layers.
    val lightMode = LocalLightMode.current
    val request = remember(media.id, media.isVideo, thumbnail, thumbPx, resolvedKey, thumbGeneration, attempt, media.absolutePath, skipLoad, pinnedCover != null, isEncrypted, knownBroken, lightMode) {
        if (skipLoad || pinnedCover != null || knownBroken) return@remember null
        val resolvedPath = media.absolutePath ?: run {
            val root = android.os.Environment.getExternalStorageDirectory()
            if (root != null && media.relativePath.isNotBlank() && media.displayName.isNotBlank()) {
                java.io.File(root, "${media.relativePath.trim('/')}/${media.displayName}").absolutePath
            } else null
        }
        ImageRequest.Builder(context)
            .data(
                if (thumbnail) {
                    MediaThumb(
                        uri = media.uri,
                        id = media.id,
                        isVideo = media.isVideo,
                        path = resolvedPath,
                        generation = thumbGeneration,
                        maxPx = maxOf(512, thumbPx),
                        highRes = thumbPx != THUMB_LIGHT_PX,
                    )
                } else if (isEncrypted && resolvedPath != null) {
                    EncryptedFile(resolvedPath)
                } else if (media.isVideo) {
                    // Same frame pipeline as the tiles (tolerates files MediaStore cannot parse).
                    MediaThumb(
                        uri = media.uri,
                        id = media.id,
                        isVideo = true,
                        path = resolvedPath,
                        maxPx = 1280,
                    )
                } else {
                    resolvedPath?.let { java.io.File(it) } ?: media.uri
                },
            )
            .memoryCacheKey("$resolvedKey-g$thumbGeneration$sizeSuffix")
            .diskCacheKey("$resolvedKey-g$thumbGeneration$sizeSuffix")
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(if (isEncrypted) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .crossfade(thumbnail && attempt == 0 && !lightMode)
            .apply {
                if (thumbnail) {
                    size(Size(thumbPx, thumbPx))
                    // Thumbnails keep their aspect ratio: an exact size would never match the memory cache.
                    precision(coil3.size.Precision.INEXACT)
                    shownKey[0]?.takeIf { it != coverCacheKey }?.let { placeholderMemoryCacheKey(it) }
                } else {
                    size(Size(2560, 2560))
                    precision(coil3.size.Precision.INEXACT)
                }
            }
            .build()
    }
    // A photo just tapped is already being decoded (ViewerPrefetch): its request waits for that
    // decode and then finds the picture in the memory cache, instead of decoding it a second time.
    val prefetch = remember(resolvedKey, thumbGeneration) {
        if (thumbnail) null else ViewerPrefetch.running("$resolvedKey-g$thumbGeneration")
    }
    var prefetchDone by remember(prefetch) { mutableStateOf(prefetch == null) }
    if (prefetch != null) {
        LaunchedEffect(prefetch) {
            runCatching { prefetch.await() }
            prefetchDone = true
        }
    }
    LaunchedEffect(failed, attempt) {
        if (failed && attempt < 3) {
            delay(500L * (attempt + 1))
            attempt += 1
        }
    }

    LaunchedEffect(currentAnimatable, isGifPlaying, thumbnail) {
        val anim = currentAnimatable
        if (anim != null && !thumbnail) {
            if (isGifPlaying) anim.start() else anim.stop()
        }
    }

    LaunchedEffect(pinnedCover) {
        if (pinnedCover != null && onDecodedSize != null) {
            onDecodedSize.invoke(IntSize(pinnedCover.width, pinnedCover.height))
        }
    }

    Box(modifier = modifier.clip(shape)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (thumbnail) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Black),
        )
        if (pinnedCover != null) {
            Image(
                bitmap = remember(pinnedCover) { pinnedCover.asImageBitmap() },
                contentDescription = media.displayName,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (request != null && prefetchDone) {
            AsyncImage(
                model = request,
                contentDescription = media.displayName,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { err ->
                    failed = true
                    // The last try failed too: remembered, so the tile doesn't try again each time.
                    if (attempt >= 3) ThumbnailFailures.markFailed(failureKey)
                    dev.ander.alexet.data.AppLog.e("AlexetMediaThumb", "AsyncImage load failed for id=${media.id}, name=${media.displayName}, path=${media.absolutePath}, uri=${media.uri}", err.result.throwable)
                },
                onSuccess = { state ->
                    failed = false
                    if (thumbnail) shownKey[0] = coverCacheKey
                    if (media.isVideo && media.durationMs <= 0L) {
                        fallbackDurationMs = VideoDurationCache.get(media.id)
                    }
                    val size = state.painter.intrinsicSize
                    if (size.isSpecified && size.width > 0f && size.height > 0f) {
                        onDecodedSize?.invoke(
                            IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        )
                    }
                    if (isFolderCover) {
                        val bmp = (state.result.image as? coil3.BitmapImage)?.bitmap
                            ?: (state.result.image.asDrawable(context.resources) as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bmp != null) {
                            dev.ander.alexet.data.FolderCoverCache.put(coverCacheKey, resolvedKey + sizeSuffix, bmp)
                            shownCover[0] = bmp
                        }
                    }
                    if (!thumbnail) {
                        val anim = runCatching { state.result.image.asDrawable(context.resources) as? Animatable }.getOrNull()
                        currentAnimatable = anim
                        onAnimatableReady?.invoke(anim)
                        if (isGifPlaying) anim?.start() else anim?.stop()
                    }
                },
            )
        }
        if (((failed && attempt >= 3) || knownBroken) && !skipLoad) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isFavorite) {
            FavoriteBadge(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
        }
        if (media.isVideo && showPlayIcon) {
            VideoBadge(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
            if (showDuration && durationMs > 0) {
                Text(
                    text = formatDuration(durationMs, durationFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

fun formatDuration(
    ms: Long,
    format: VideoDurationFormat = VideoDurationFormat.MINUTES,
): String {
    val totalSec = (ms / 1000.0).roundToInt().coerceAtLeast(0)
    return when (format) {
        VideoDurationFormat.MINUTES -> {
            val minutes = totalSec / 60
            val seconds = totalSec % 60
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
        VideoDurationFormat.HOURS_MINUTES_SECONDS -> {
            val hours = totalSec / 3600
            val minutes = (totalSec % 3600) / 60
            val seconds = totalSec % 60
            if (hours > 0) {
                String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    return String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0)
}

/**
 * Warms Coil's memory cache for grid thumbnails. Data, keys and size must stay identical to the
 * `thumbnail = true` request in [MediaThumbnail], otherwise the grid misses the cache.
 */
suspend fun prefetchThumbnails(context: Context, items: List<Pair<GalleryMedia, Int>>, thumbPx: Int = THUMB_LIGHT_PX) {
    if (items.isEmpty()) return
    val loader = SingletonImageLoader.get(context)
    val semaphore = Semaphore(4)
    coroutineScope {
        items.map { (media, generation) ->
            async {
                semaphore.withPermit {
                    val baseKey = "thumb-${media.id}-${media.dateModifiedMs}"
                    val key = "$baseKey-g$generation${thumbSizeSuffix(thumbPx)}"
                    val request = ImageRequest.Builder(context)
                        .data(
                            MediaThumb(
                                uri = media.uri,
                                id = media.id,
                                isVideo = media.isVideo,
                                path = media.absolutePath,
                                generation = generation,
                                maxPx = maxOf(512, thumbPx),
                                highRes = thumbPx != THUMB_LIGHT_PX,
                            ),
                        )
                        .memoryCacheKey(key)
                        .diskCacheKey(key)
                        .size(Size(thumbPx, thumbPx))
                        .precision(coil3.size.Precision.INEXACT)
                        .build()
                    val result = loader.execute(request)
                    if (result is coil3.request.SuccessResult) {
                        val bmp = (result.image as? coil3.BitmapImage)?.bitmap
                            ?: (result.image.asDrawable(context.resources) as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bmp != null) {
                            dev.ander.alexet.data.FolderCoverCache.put(key, baseKey + thumbSizeSuffix(thumbPx), bmp)
                        }
                    }
                }
            }
        }.awaitAll()
    }
}

@Composable
fun SquareThumbnail(
    media: GalleryMedia,
    modifier: Modifier = Modifier,
    showPlayIcon: Boolean = true,
    isFavorite: Boolean = false,
    thumbGeneration: Int = LocalFolderThumbEpoch.current,
    skipLoad: Boolean = false,
    showDuration: Boolean = true,
) {
    if (media.isOtherFile) {
        // Documents, archives, apps…: their own tile (type icon, preview, type badge).
        OtherFileThumbnail(media = media, modifier = modifier)
        return
    }
    MediaThumbnail(
        media = media,
        modifier = modifier.aspectRatio(1f),
        showPlayIcon = showPlayIcon,
        showDuration = showDuration,
        isFavorite = isFavorite,
        thumbGeneration = thumbGeneration,
        skipLoad = skipLoad,
    )
}
