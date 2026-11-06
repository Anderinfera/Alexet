package dev.ander.alexet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ander.alexet.R

/**
 * A small round indicator on a folder or file tile. Material You tonal colours on a solid
 * container keep it readable on any photo (the bare white icons got lost on bright ones).
 */
@Composable
fun TileBadge(
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Hidden file or folder. */
@Composable
fun HiddenBadge(modifier: Modifier = Modifier) = TileBadge(
    icon = Icons.Rounded.VisibilityOff,
    container = MaterialTheme.colorScheme.secondaryContainer,
    content = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier = modifier,
)

/** Folder in the name mixer. */
@Composable
fun NameMixerBadge(modifier: Modifier = Modifier) = TileBadge(
    icon = Icons.Rounded.Shuffle,
    container = MaterialTheme.colorScheme.tertiaryContainer,
    content = MaterialTheme.colorScheme.onTertiaryContainer,
    modifier = modifier,
    contentDescription = stringResource(R.string.anonymous_folder),
)

/** Favorite file. */
@Composable
fun FavoriteBadge(modifier: Modifier = Modifier) = TileBadge(
    icon = Icons.Rounded.Star,
    container = MaterialTheme.colorScheme.tertiary,
    content = MaterialTheme.colorScheme.onTertiary,
    modifier = modifier,
)

/** Video file. */
@Composable
fun VideoBadge(modifier: Modifier = Modifier) = TileBadge(
    icon = Icons.Rounded.PlayArrow,
    container = MaterialTheme.colorScheme.primary,
    content = MaterialTheme.colorScheme.onPrimary,
    modifier = modifier,
)

/** File or folder still downloading, with how far it got when that is known. */
@Composable
fun DownloadBadge(modifier: Modifier = Modifier, percent: Int? = null) {
    val container = MaterialTheme.colorScheme.primaryContainer
    val content = MaterialTheme.colorScheme.onPrimaryContainer
    if (percent == null) {
        TileBadge(
            icon = Icons.Rounded.FileDownload,
            container = container,
            content = content,
            modifier = modifier,
            contentDescription = stringResource(R.string.downloading),
        )
        return
    }
    Row(
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(start = 5.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.FileDownload,
            contentDescription = stringResource(R.string.downloading),
            tint = content,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}
