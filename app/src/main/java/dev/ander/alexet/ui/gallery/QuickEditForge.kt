package dev.ander.alexet.ui.gallery

import android.graphics.drawable.Animatable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ander.alexet.R
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.QuickEditTarget
import kotlinx.coroutines.flow.StateFlow

/** The video a quick edit is making and its percent. */
class QuickEditTile(val target: QuickEditTarget, val percent: StateFlow<Int>) {
    /** The tile where the file comes into being: the new copy's, or the original's when it is replaced. */
    fun matches(item: GalleryMedia): Boolean {
        val output = target.outputPath
        return if (output != null) item.absolutePath == output else item.id == target.source.id
    }
}

/**
 * Read by the gallery tiles: the one where the file is being made gets the forge. Changes only
 * when an export starts or ends; the percent is read by that tile alone.
 */
val LocalQuickEditTile = compositionLocalOf<QuickEditTile?> { null }

/**
 * Over the tile of a video being quick-edited: the hammer beating the anvil
 * ([R.drawable.hammer_anvil_anim], looping), under it the percent, and a progress bar.
 */
@Composable
fun QuickEditForgeOverlay(tile: QuickEditTile, modifier: Modifier = Modifier) {
    val percent by tile.percent.collectAsStateWithLifecycle()
    Box(modifier.background(Color.Black.copy(alpha = 0.5f))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        val forge = ContextCompat.getDrawable(context, R.drawable.hammer_anvil_anim)
                        setImageDrawable(forge)
                        (forge as? Animatable)?.start()
                    }
                },
                // Off screen (the tile scrolled away or the export ended): the animation stops.
                onRelease = { view -> (view.drawable as? Animatable)?.stop() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            Text(
                "$percent%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(4.dp),
        )
    }
}
