package dev.ander.alexet.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R
import kotlin.math.roundToInt

/**
 * Moves the playback buttons (Next Player's position editor): two sliders on a card, the real
 * buttons drawn where they will sit. Done, back or a tap beside the card saves the position.
 */
@Composable
fun PlayerButtonsPositionEditor(
    x: Float,
    y: Float,
    onChange: (x: Float, y: Float) -> Unit,
    onFinish: () -> Unit,
    /** The playback buttons, given the modifier that places them. */
    preview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onFinish)
    // The card moves out of the buttons' way: to the top when they sit low.
    val cardBias by animateFloatAsState(if (y > 0.45f) -0.85f else 0.35f, tween(300), label = "cardBias")

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onFinish,
            ),
    ) {
        preview(Modifier.align(BiasAlignment(positionToBias(x), positionToBias(y))))

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
            tonalElevation = 6.dp,
            modifier = Modifier
                .align(BiasAlignment(0f, cardBias))
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                // Taps on the card stay on it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.player_buttons_position),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onChange(0.5f, 0.5f) }) {
                        Text(stringResource(R.string.player_buttons_position_reset))
                    }
                    TextButton(onClick = onFinish) {
                        Text(stringResource(R.string.done))
                    }
                }
                PositionSlider(
                    label = stringResource(R.string.player_position_y),
                    value = y,
                    onValueChange = { onChange(x, it) },
                )
                PositionSlider(
                    label = stringResource(R.string.player_position_x),
                    value = x,
                    onValueChange = { onChange(it, y) },
                )
            }
        }
    }
}

@Composable
private fun PositionSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${(value * 100f).roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = 0.05f..0.95f,
    )
}
