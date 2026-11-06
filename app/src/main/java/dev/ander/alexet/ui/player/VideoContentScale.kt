package dev.ander.alexet.ui.player

import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import dev.ander.alexet.R

@OptIn(UnstableApi::class)
enum class VideoContentScale(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val resizeMode: Int,
    /** The picture is forced into this width/height (stretched to fill it). */
    val forcedAspect: Float? = null,
    /** One video pixel per screen pixel (cropped when larger than the screen). */
    val pixelExact: Boolean = false,
) {
    FIT(
        titleRes = R.string.scale_fit,
        icon = Icons.Rounded.FitScreen,
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    ),
    ZOOM(
        titleRes = R.string.scale_zoom,
        icon = Icons.Rounded.ZoomOutMap,
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    ),
    STRETCH(
        titleRes = R.string.scale_stretch,
        icon = Icons.Rounded.Fullscreen,
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
    ),
    ASPECT_16_9(
        titleRes = R.string.scale_16_9,
        icon = Icons.Rounded.AspectRatio,
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
        forcedAspect = 16f / 9f,
    ),
    ASPECT_4_3(
        titleRes = R.string.scale_4_3,
        icon = Icons.Rounded.PictureInPicture,
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
        forcedAspect = 4f / 3f,
    ),
    ORIGINAL(
        titleRes = R.string.scale_original,
        icon = Icons.Rounded.OpenInFull,
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
        pixelExact = true,
    );

    fun next(): VideoContentScale {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}
