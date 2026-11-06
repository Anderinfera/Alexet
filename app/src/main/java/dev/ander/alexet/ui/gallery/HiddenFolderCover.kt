package dev.ander.alexet.ui.gallery

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.transform.Transformation
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.HiddenCoverConfig
import dev.ander.alexet.data.HiddenCoverSession
import dev.ander.alexet.data.HiddenCoverStyle
import dev.ander.alexet.ui.components.MediaThumb
import kotlin.math.ceil
import kotlin.math.sqrt

/** How hidden (.nomedia) folders' covers are drawn; provided by the gallery screen. */
val LocalHiddenCoverConfig = compositionLocalOf { HiddenCoverConfig() }

/**
 * A hidden folder's cover with [config]'s filter instead of the plain picture. [cover] may be null
 * (an empty folder): the picture-based styles then show the folder's color.
 */
@Composable
fun HiddenFolderCover(
    cover: GalleryMedia?,
    config: HiddenCoverConfig,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().clipToBounds()) {
        when (config.style) {
            HiddenCoverStyle.OFF -> Unit
            HiddenCoverStyle.PIXELATE -> if (cover != null) {
                DownscaledCover(cover, sidePx = config.pixels, smooth = false, modifier = Modifier.fillMaxSize())
            } else {
                PlainColor(config.color)
            }
            HiddenCoverStyle.BLUR -> if (cover != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // A small picture blurred: cheap, and the blur reaches the whole tile.
                    DownscaledCover(
                        cover,
                        sidePx = 48,
                        smooth = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur((config.blur * 1.6f).dp, BlurredEdgeTreatment.Rectangle),
                    )
                } else {
                    // No blur before Android 12: a smaller picture smoothly stretched instead.
                    val side = (48f / (1f + config.blur / 3f)).toInt().coerceAtLeast(2)
                    DownscaledCover(cover, sidePx = side, smooth = true, modifier = Modifier.fillMaxSize())
                }
            } else {
                PlainColor(config.color)
            }
            HiddenCoverStyle.IMAGE -> {
                val path = config.imagePath
                if (path != null && java.io.File(path).exists()) {
                    val context = LocalContext.current
                    val request = remember(path, config.imageVersion) {
                        ImageRequest.Builder(context)
                            .data(java.io.File(path))
                            .memoryCacheKey("hidden-cover-image-${config.imageVersion}")
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(Color(config.color)),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color(config.color)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
                    }
                }
            }
            HiddenCoverStyle.COLOR -> PlainColor(config.color)
            HiddenCoverStyle.STRIPES, HiddenCoverStyle.CHECKER -> {
                val look = remember(config) { HiddenCoverSession.look(config) }
                // Over the folder's own picture; an empty folder gets the ground color under them.
                if (cover != null) {
                    dev.ander.alexet.ui.components.MediaThumbnail(
                        media = cover,
                        modifier = Modifier.fillMaxSize(),
                        showPlayIcon = false,
                        showDuration = false,
                        shape = androidx.compose.ui.graphics.RectangleShape,
                    )
                }
                val ground = if (cover == null) Color(look.colorB) else null
                Canvas(Modifier.fillMaxSize()) {
                    if (config.style == HiddenCoverStyle.STRIPES) {
                        drawStripes(config.stripeCount, config.stripeWidth / 100f, look.angle, Color(look.colorA), ground)
                    } else {
                        drawChecker(config.checkerCells, look.angle, Color(look.colorA), ground)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainColor(color: Int) {
    Box(Modifier.fillMaxSize().background(Color(color)))
}

/** The cover decoded down to [sidePx] on its shorter side and stretched back over the tile. */
@Composable
private fun DownscaledCover(cover: GalleryMedia, sidePx: Int, smooth: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val request = remember(cover.id, cover.dateModifiedMs, sidePx) {
        ImageRequest.Builder(context)
            .data(MediaThumb(uri = cover.uri, id = cover.id, isVideo = cover.isVideo, path = cover.absolutePath))
            .transformations(DownscaleTransformation(sidePx))
            .memoryCacheKey("hidden-cover-${cover.id}-${cover.dateModifiedMs}-$sidePx")
            // Only in memory: nothing of a hidden folder is written to the cache on disk.
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // Nearest neighbour keeps the blocks sharp; bilinear smears them (blur).
        filterQuality = if (smooth) FilterQuality.Low else FilterQuality.None,
        modifier = modifier,
    )
}

private class DownscaleTransformation(private val sidePx: Int) : Transformation() {
    override val cacheKey: String = "downscale-$sidePx"

    override suspend fun transform(input: Bitmap, size: coil3.size.Size): Bitmap {
        val shorter = minOf(input.width, input.height).coerceAtLeast(1)
        val scale = sidePx.toFloat() / shorter
        if (scale >= 1f) return input
        val w = (input.width * scale).toInt().coerceAtLeast(1)
        val h = (input.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(input, w, h, true)
    }
}

/** [count] stripes across the tile, each [widthFraction] of its period, turned by [angle]; [ground] null: what is under shows between them. */
internal fun DrawScope.drawStripes(count: Int, widthFraction: Float, angle: Float, stripe: Color, ground: Color?) {
    ground?.let { drawRect(it) }
    val diagonal = sqrt(size.width * size.width + size.height * size.height)
    val period = size.minDimension / count.coerceAtLeast(1)
    val stripeW = period * widthFraction
    val n = ceil(diagonal / period).toInt() + 2
    rotate(angle, pivot = center) {
        val left = center.x - diagonal / 2f
        val top = center.y - diagonal / 2f
        for (i in -1..n) {
            drawRect(stripe, topLeft = Offset(left + i * period, top), size = Size(stripeW, diagonal))
        }
    }
}

/** A checkerboard of [cells] squares along the tile's shorter side, turned by [angle]; [ground] null: every other square shows what is under it. */
internal fun DrawScope.drawChecker(cells: Int, angle: Float, square: Color, ground: Color?) {
    ground?.let { drawRect(it) }
    val diagonal = sqrt(size.width * size.width + size.height * size.height)
    val cell = size.minDimension / cells.coerceAtLeast(1)
    val n = ceil(diagonal / cell).toInt() + 2
    rotate(angle, pivot = center) {
        // Lined up on the centre, so a turn spins the board about the tile's middle.
        val origin = center - Offset(cell * (n / 2), cell * (n / 2))
        for (row in 0 until n) {
            for (col in 0 until n) {
                if ((row + col) % 2 == 0) {
                    drawRect(square, topLeft = origin + Offset(col * cell, row * cell), size = Size(cell, cell))
                }
            }
        }
    }
}
