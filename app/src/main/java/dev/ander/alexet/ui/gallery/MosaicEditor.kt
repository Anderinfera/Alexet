package dev.ander.alexet.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.ander.alexet.R
import dev.ander.alexet.data.MosaicBounds
import dev.ander.alexet.data.MosaicPiece
import dev.ander.alexet.data.MosaicRenderer
import dev.ander.alexet.data.MosaicSnap
import dev.ander.alexet.data.MosaicSource
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Laying several photos out into one: each is dragged where it should go (its edges pull to the
 * edges of the others), and the one picked gets a handle in its corner to make it bigger or
 * smaller. Every photo keeps its own resolution; the result is as big as the whole layout.
 */
@Composable
fun MosaicEditorPane(
    sources: List<MosaicSource>,
    saving: Boolean,
    onCancel: () -> Unit,
    onDone: (List<MosaicPiece>) -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!saving) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pieces = remember(sources) { mutableStateListOf<MosaicPiece>().apply { addAll(MosaicSnap.initialRow(sources)) } }
        var selected by remember(sources) { mutableIntStateOf(-1) }
        // The part of the plane on screen: fitted to the layout again after each move.
        var view by remember(sources) { mutableStateOf(viewOf(MosaicRenderer.bounds(pieces))) }
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel, enabled = !saving) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.mosaic_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    val (w, h, factor) = MosaicRenderer.outputSize(pieces)
                    Text(
                        text = if (factor < 1f) stringResource(R.string.mosaic_size_reduced, w, h) else stringResource(R.string.mosaic_size, w, h),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Button(
                    onClick = { onDone(pieces.toList()) },
                    enabled = !saving && pieces.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(stringResource(R.string.mosaic_next))
                    }
                }
            }
            Text(
                stringResource(R.string.mosaic_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                val density = LocalDensity.current
                val areaW = with(density) { maxWidth.toPx() }
                val areaH = with(density) { maxHeight.toPx() }
                val scale = min(areaW / view.width.coerceAtLeast(1f), areaH / view.height.coerceAtLeast(1f))
                val originX = (areaW - view.width * scale) / 2f - view.left * scale
                val originY = (areaH - view.height * scale) / 2f - view.top * scale
                val snapWithin = with(density) { 14.dp.toPx() } / scale
                val handleRadius = with(density) { 26.dp.toPx() }
                val latestScale by rememberUpdatedState(scale)
                val latestOrigin by rememberUpdatedState(Offset(originX, originY))
                val latestSnap by rememberUpdatedState(snapWithin)

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(sources, saving) {
                            if (saving) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val s = latestScale
                                val o = latestOrigin
                                fun toPlane(p: Offset) = Offset((p.x - o.x) / s, (p.y - o.y) / s)
                                val at = toPlane(down.position)
                                // The picked photo's corner handle first, then the topmost photo under the finger.
                                val sel = selected.takeIf { it in pieces.indices }
                                val handle = sel?.let { pieces[it] }?.let { p ->
                                    val corner = Offset(o.x + p.right * s, o.y + p.bottom * s)
                                    (corner - down.position).getDistance() <= handleRadius
                                } == true
                                var index = if (handle) sel!! else pieces.indices.lastOrNull { i ->
                                    val p = pieces[i]
                                    at.x in p.x..p.right && at.y in p.y..p.bottom
                                } ?: -1
                                if (index < 0) {
                                    selected = -1
                                    return@awaitEachGesture
                                }
                                if (!handle && index != pieces.lastIndex) {
                                    // Picked: on top of the others.
                                    val p = pieces.removeAt(index)
                                    pieces.add(p)
                                    index = pieces.lastIndex
                                }
                                selected = index
                                val start = pieces[index]
                                var total = Offset.Zero
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    total += change.positionChange() / s
                                    change.consume()
                                    val others = pieces.filterIndexed { i, _ -> i != index }
                                    val xs = others.map { it.x to it.right }
                                    val ys = others.map { it.y to it.bottom }
                                    pieces[index] = if (handle) {
                                        resized(start, total, xs, ys, latestSnap)
                                    } else {
                                        val x = MosaicSnap.snapPosition(start.x + total.x, start.width, xs, latestSnap)
                                        val y = MosaicSnap.snapPosition(start.y + total.y, start.height, ys, latestSnap)
                                        start.copy(x = x, y = y)
                                    }
                                }
                                view = viewOf(MosaicRenderer.bounds(pieces))
                            }
                        },
                ) {
                    val context = LocalContext.current
                    pieces.forEachIndexed { i, p ->
                        val left = originX + p.x * scale
                        val top = originY + p.y * scale
                        val w = with(density) { (p.width * scale).toDp() }
                        val h = with(density) { (p.height * scale).toDp() }
                        val path = p.source.media.absolutePath
                        val request = remember(path) {
                            ImageRequest.Builder(context)
                                .data(path?.let { File(it) } ?: p.source.media.uri)
                                .size(1024)
                                .build()
                        }
                        Box(
                            Modifier
                                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                .size(w, h)
                                .then(
                                    if (i == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                                    else Modifier,
                                ),
                        ) {
                            AsyncImage(
                                model = request,
                                contentDescription = p.source.media.displayName,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    // The picked photo's handle for its size.
                    pieces.getOrNull(selected)?.let { p ->
                        val cx = originX + p.right * scale
                        val cy = originY + p.bottom * scale
                        val half = with(density) { 11.dp.toPx() }
                        Box(
                            Modifier
                                .offset { IntOffset((cx - half).roundToInt(), (cy - half).roundToInt()) }
                                .size(22.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .border(2.dp, Color.White, CircleShape),
                        )
                    }
                }
            }
            Spacer(Modifier.width(0.dp))
        }
    }
}

/** [layout] with a margin around it, so photos can be dragged a little beyond it. */
private fun viewOf(layout: MosaicBounds): MosaicBounds {
    val m = max(layout.width, layout.height) * 0.08f
    return MosaicBounds(layout.left - m, layout.top - m, layout.right + m, layout.bottom + m)
}

/**
 * [start] made bigger or smaller by dragging its corner by [drag], keeping its proportions; its
 * right or bottom edge pulls to the edges of the others.
 */
private fun resized(
    start: MosaicPiece,
    drag: Offset,
    xs: List<Pair<Float, Float>>,
    ys: List<Pair<Float, Float>>,
    within: Float,
): MosaicPiece {
    val aspect = start.source.width.toFloat() / start.source.height
    // The larger of the two moves decides, as the corner follows the finger along the diagonal.
    val widthFromX = start.width + drag.x
    val widthFromY = (start.height + drag.y) * aspect
    var width = if (kotlin.math.abs(drag.x) >= kotlin.math.abs(drag.y) * aspect) widthFromX else widthFromY
    width = width.coerceAtLeast(64f)
    val right = MosaicSnap.snapEdge(start.x + width, xs, within)
    val bottom = MosaicSnap.snapEdge(start.y + width / aspect, ys, within)
    when {
        right != null && (bottom == null || kotlin.math.abs(right - (start.x + width)) <= kotlin.math.abs(bottom - (start.y + width / aspect)) * aspect) ->
            width = right - start.x
        bottom != null -> width = (bottom - start.y) * aspect
    }
    return start.copy(scale = (width / start.source.width).coerceAtLeast(64f / start.source.width))
}
