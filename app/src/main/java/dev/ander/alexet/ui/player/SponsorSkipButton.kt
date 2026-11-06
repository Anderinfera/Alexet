package dev.ander.alexet.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R

/** Skip button for the segment being played, which sits above the seekbar while the controls show. */
@Composable
fun SponsorSkipButton(
    segment: SponsorSegment,
    controlsVisible: Boolean,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onSkip,
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(end = 16.dp, bottom = if (controlsVisible) 92.dp else 24.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.7f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.sponsorblock_skip_segment,
                    stringResource(segment.categories.first().nameRes),
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(
                imageVector = Icons.Rounded.FastForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
