package dev.ander.alexet.ui.components.aiglow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.ander.alexet.data.AiEffect
import dev.ander.alexet.data.AiEffectsConfig
import dev.ander.alexet.ui.components.LocalLightMode
import kotlin.random.Random

/** A visible tile for [AiNexus]: where it is (in the area's coordinates) and its place in the grid. */
class NexusCell(val rect: Rect, val row: Int, val column: Int)

/**
 * A gap a trail can run along. [key] names it from frame to frame (row or column), [at] is where
 * it lies across, [from]..[to] its span along (content y for gaps running down, which scroll).
 */
class NexusLine(val key: Int, val at: Float, val from: Float, val to: Float)

/**
 * Where trails may run, for [AiNexus]: only the gaps between two tiles, never the margins between
 * the tiles and the area's border. [tiles] in the area's coordinates; [scrollY]: how far the
 * content is scrolled (so trails move with it).
 */
class NexusGrid(val tiles: List<Rect>, val scrollY: Float, val across: List<NexusLine>, val down: List<NexusLine>) {
    companion object {
        /**
         * The gaps between the visible [cells]: across between two rows (none above the grid's
         * first row), from the first column to the last tile of the lower row; down between two
         * columns (none at the sides), from the grid's first row to its last tile in that column.
         * [endsHere]: the last cell is the grid's last item.
         */
        fun of(cells: List<NexusCell>, gap: Float, scrollY: Float, endsHere: Boolean): NexusGrid? {
            if (cells.isEmpty()) return null
            val rows = cells.groupBy { it.row }
            val left = cells.minOf { it.rect.left }
            val across = rows.filterKeys { it > 0 }.map { (row, inRow) ->
                NexusLine(row, inRow.minOf { it.rect.top } - gap / 2f + scrollY, left, inRow.maxOf { it.rect.right })
            }
            val top = rows[0]?.minOf { it.rect.top }?.plus(scrollY) ?: Float.NEGATIVE_INFINITY
            val columns = cells.maxOf { it.column } + 1
            val down = (1 until columns).mapNotNull { c ->
                val inColumn = cells.filter { it.column == c }
                if (inColumn.isEmpty()) return@mapNotNull null
                val bottom = if (endsHere) inColumn.maxOf { it.rect.bottom } + scrollY else Float.POSITIVE_INFINITY
                NexusLine(c, inColumn.minOf { it.rect.left } - gap / 2f, top, bottom)
            }
            return NexusGrid(cells.map { it.rect }, scrollY, across, down)
        }
    }
}

/**
 * The Nexus lights (like the old Nexus wallpaper): glowing heads with long fading tails running
 * straight along the gaps between the tiles: across between rows, down between columns. Never
 * over a tile (everything is clipped to the area minus the rounded tiles) and never along the
 * outer margins; they fade out before a gap ends.
 */
@Composable
fun AiNexus(
    config: AiEffectsConfig,
    active: Boolean,
    grid: () -> NexusGrid?,
    modifier: Modifier = Modifier,
    trigger: Any? = null,
    loop: Boolean = false,
) {
    val style = config.style(AiEffect.RESULTS_NEXUS)
    val strength by if (loop && config.isTimed(AiEffect.RESULTS_NEXUS)) {
        rememberLoopingGlow(style.durationMs)
    } else {
        rememberEffectStrength(config, AiEffect.RESULTS_NEXUS, active, trigger)
    }
    if (!style.enabled || strength <= 0f || LocalLightMode.current) return
    val colors = aiColors(config, AiEffect.RESULTS_NEXUS)
    val trails = remember { NexusTrails() }
    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frameNanos = it }
    }
    // One path for the mask, refilled each frame instead of a new one.
    val mask = remember { Path().apply { fillType = PathFillType.EvenOdd } }
    // Its own layer: each frame redraws only the lights, not the photos.
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val now = frameNanos
        val g = grid() ?: return@Canvas
        if (g.tiles.isEmpty()) return@Canvas
        // The speed is read every frame: a change applies to the trails already running.
        trails.step(now, g, size.width, size.height, style.count, style.speed * 1.dp.toPx())
        mask.rewind()
        mask.fillType = PathFillType.EvenOdd
        mask.addRect(Rect(Offset.Zero, size))
        val r = CornerRadius(TILE_RADIUS.toPx())
        for (t in g.tiles) mask.addRoundRect(RoundRect(t, r))
        val head = 2.6.dp.toPx() * style.width.coerceIn(0.6f, 1.8f)
        val fade = END_FADE.toPx()
        clipPath(mask) {
            for (t in trails.list) drawTrail(t, g, colors, head, fade, strength * style.brightness)
        }
    }
}

private val TILE_RADIUS = 20.dp

/** How long a trail takes to fade in and out at the ends of its gap. */
private val END_FADE = 22.dp

private class Trail(
    val horizontal: Boolean,
    /** The gap's [NexusLine.key]. */
    val key: Int,
    /** Head position along the gap (x for gaps across; content y for gaps down). */
    var pos: Float,
    val dir: Float,
    /** Pace in dp/s at speed 1. */
    val pace: Float,
    val length: Float,
    val colorAt: Float,
)

private class NexusTrails {
    val list = ArrayList<Trail>()
    private var lastNanos = 0L
    private val rnd = Random(42)

    fun step(now: Long, g: NexusGrid, width: Float, height: Float, count: Int, unit: Float) {
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = now
        if (g.across.isEmpty() && g.down.isEmpty()) {
            list.clear()
            return
        }
        while (list.size > count) list.removeAt(list.lastIndex)
        while (list.size < count) list += spawn(g, width, height, unit, stagger = true) ?: break
        for (i in list.indices.reversed()) {
            val t = list[i]
            t.pos += t.dir * t.pace * unit * dt
            val line = g.lineOf(t)
            val gone = if (line == null) {
                true
            } else {
                val (viewStart, viewEnd) = view(t.horizontal, g, width, height)
                // Past the end of its gap (or of the screen), or its gap scrolled away.
                val end = if (t.dir > 0) minOf(line.to, viewEnd + t.length) else maxOf(line.from, viewStart - t.length)
                val past = if (t.dir > 0) t.pos - t.length > end else t.pos + t.length < end
                val across = if (t.horizontal) line.at - g.scrollY else 0f
                past || (t.horizontal && (across < -t.length || across > height + t.length))
            }
            if (gone) {
                val fresh = spawn(g, width, height, unit, stagger = false)
                if (fresh != null) list[i] = fresh else list.removeAt(i)
            }
        }
    }

    private fun view(horizontal: Boolean, g: NexusGrid, width: Float, height: Float): Pair<Float, Float> =
        if (horizontal) 0f to width else g.scrollY to g.scrollY + height

    private fun spawn(g: NexusGrid, width: Float, height: Float, unit: Float, stagger: Boolean): Trail? {
        val horizontal = when {
            g.across.isEmpty() -> false
            g.down.isEmpty() -> true
            else -> rnd.nextBoolean()
        }
        val lines = if (horizontal) g.across else g.down
        val line = lines[rnd.nextInt(lines.size)]
        val (viewStart, viewEnd) = view(horizontal, g, width, height)
        val lo = maxOf(line.from, viewStart)
        val hi = minOf(line.to, viewEnd)
        if (hi - lo < unit * 24f) return null
        val dir = if (rnd.nextBoolean()) 1f else -1f
        val length = unit * (70f + rnd.nextFloat() * 120f)
        // Starts at the beginning of its gap, or just off the screen when the gap goes on beyond it
        // (or anywhere at first, so they don't arrive together).
        val pos = when {
            stagger -> lo + rnd.nextFloat() * (hi - lo)
            dir > 0 -> maxOf(line.from, viewStart - length * 0.2f)
            else -> minOf(line.to, viewEnd + length * 0.2f)
        }
        return Trail(horizontal, line.key, pos, dir, 90f + rnd.nextFloat() * 140f, length, rnd.nextFloat())
    }
}

private fun NexusGrid.lineOf(t: Trail): NexusLine? = (if (t.horizontal) across else down).firstOrNull { it.key == t.key }

private fun smooth(edge: Float, x: Float): Float {
    val k = (x / edge).coerceIn(0f, 1f)
    return k * k * (3 - 2 * k)
}

private fun DrawScope.drawTrail(t: Trail, g: NexusGrid, colors: List<Color>, head: Float, fade: Float, strength: Float) {
    val line = g.lineOf(t) ?: return
    val color = paletteAt(colors, t.colorAt)
    // Gone to nothing near both ends of the gap: no trail is cut off where it ends.
    fun along(s: Float): Float = smooth(fade, s - line.from) * smooth(fade, line.to - s)
    fun point(s: Float): Offset =
        if (t.horizontal) Offset(s, line.at - g.scrollY) else Offset(line.at, s - g.scrollY)
    val a = strength.coerceIn(0f, 1f)
    val tail = t.pos - t.dir * t.length
    val stops = TAIL_STOPS
    // Tail: fades to nothing behind the head, round ends.
    drawLine(
        brush = Brush.linearGradient(
            List(stops + 1) { i ->
                val s = tail + (t.pos - tail) * i / stops
                color.copy(alpha = 0.85f * (i / stops.toFloat()) * along(s))
            },
            start = point(tail),
            end = point(t.pos),
        ),
        start = point(tail),
        end = point(t.pos),
        strokeWidth = head * 0.9f,
        cap = StrokeCap.Round,
        alpha = a,
    )
    // Head: a soft round glow with a bright core.
    val headAlpha = a * along(t.pos)
    if (headAlpha <= 0.001f) return
    val center = point(t.pos)
    drawCircle(
        brush = Brush.radialGradient(listOf(color, color.copy(alpha = 0.35f), Color.Transparent), center = center, radius = head * 1.6f),
        radius = head * 1.6f,
        center = center,
        alpha = headAlpha,
    )
    drawCircle(Color.White.copy(alpha = 0.7f), radius = head * 0.45f, center = center, alpha = headAlpha)
}

private const val TAIL_STOPS = 10
