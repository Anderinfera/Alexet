package dev.ander.alexet.ui.editor

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.InsertEmoticon
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ander.alexet.R

enum class PhotoEditorTab(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    ASPECT(R.string.tab_aspect, Icons.Rounded.AspectRatio),
    PARAMETERS(R.string.tab_parameters, Icons.Rounded.Tune),
    BLUR(R.string.tab_blur, Icons.Rounded.BlurOn),
    STICKERS(R.string.tab_stickers, Icons.Rounded.InsertEmoticon),
    TEXT(R.string.tab_text, Icons.Rounded.TextFields),
    BRUSH(R.string.tab_brush, Icons.Rounded.Brush),
}

enum class AdjustParam(
    @StringRes val labelRes: Int,
) {
    BRIGHTNESS(R.string.brightness),
    CONTRAST(R.string.contrast),
    SATURATION(R.string.saturation),
    SHADOWS(R.string.shadows),
    HIGHLIGHTS(R.string.highlights),
    EXPOSURE(R.string.exposure),
    GAMMA(R.string.gamma),
    TEMPERATURE(R.string.temperature),
    SHARPNESS(R.string.sharpness),
}

enum class AspectOption(
    @StringRes val labelRes: Int,
    val ratio: Float?,
) {
    CUSTOM(R.string.aspect_custom, null),
    SQUARE(R.string.aspect_square, 1f),
    RATIO_16_9(R.string.aspect_16_9, 16f / 9f),
    RATIO_16_10(R.string.aspect_16_10, 16f / 10f),
    RATIO_3_2(R.string.aspect_3_2, 3f / 2f),
    RATIO_2_1(R.string.aspect_2_1, 2f / 1f),
    RATIO_18_9(R.string.aspect_18_9, 18f / 9f),
    RATIO_21_9(R.string.aspect_21_9, 21f / 9f),
    RATIO_5_4(R.string.aspect_5_4, 5f / 4f),
    EXACT(R.string.aspect_exact, null),
}

enum class BrushToolMode {
    NONE,
    COLOR,
    SIZE,
    HARDNESS,
}
