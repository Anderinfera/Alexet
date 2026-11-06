package dev.ander.alexet.ui.player

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.OtherFiles
import dev.ander.alexet.ui.components.LocalLightMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Tags and cover read from an audio file. */
private class AudioInfo(
    val title: String?,
    val artist: String?,
    val cover: ImageBitmap?,
    /** "44.1 kHz · 16 bit · 320 kb/s": what the file says of its quality. */
    val quality: String?,
)

private val LOSSLESS = setOf("flac", "wav", "alac", "aiff", "aif")

private fun readAudioInfo(path: String, extension: String): AudioInfo {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        fun key(k: Int) = runCatching { retriever.extractMetadata(k) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val title = key(MediaMetadataRetriever.METADATA_KEY_TITLE)
        val artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        val cover = runCatching {
            retriever.embeddedPicture?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 700) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }
        }.getOrNull()
        val parts = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            key(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.takeIf { it > 0 }?.let { hz ->
                val khz = hz / 1000.0
                parts += if (hz % 1000 == 0) "${khz.toInt()} kHz" else String.format(java.util.Locale.getDefault(), "%.1f kHz", khz)
            }
            key(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()?.takeIf { it > 0 }?.let { parts += "$it bit" }
        }
        if (extension !in LOSSLESS) {
            key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.takeIf { it > 0 }?.let { parts += "${(it / 1000.0).roundToInt()} kb/s" }
        }
        AudioInfo(title, artist, cover, parts.joinToString(" · ").ifEmpty { null })
    } catch (e: Exception) {
        AudioInfo(null, null, null, null)
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * What the player shows for an audio file, in the Material You colours: a vinyl record (its
 * label carries the cover, if the file has one) turning while it plays, with two reflections
 * that stay with the light and sway a little up and down while it plays, inside a ring of
 * rounded bars that follow the playback state (playing: lively, faster with the speed; paused:
 * settled low; loading: pulsing; ended: flat, with a replay button). The bars follow the
 * player's state, not the sound (that would need the microphone permission). Below: title and
 * artist from the tags (else the file name), then the format and quality.
 */
@Composable
fun AudioVisual(
    media: GalleryMedia,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isEnded: Boolean,
    speed: Float,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val light = LocalLightMode.current
    val scheme = MaterialTheme.colorScheme
    val extension = remember(media.displayName) { OtherFiles.extensionOf(media.displayName) }
    val label = extension.uppercase().ifEmpty { "AUDIO" }
    val info by produceState<AudioInfo?>(null, media.absolutePath) {
        val path = media.absolutePath ?: return@produceState
        value = withContext(Dispatchers.IO) { readAudioInfo(path, extension) }
    }
    // Dark in every theme: the player's controls are white.
    val backdrop = Color.Black
    val glowColor = scheme.primary
    val barStart = scheme.primary
    val barEnd = scheme.tertiary
    val labelColor = scheme.primaryContainer
    val labelEdge = scheme.primary
    val labelMark = scheme.onPrimaryContainer

    // Bar shapes: the same for a file every time it is opened.
    val bars = 48
    val seeds = remember(media.id) {
        val r = kotlin.random.Random(media.id)
        Array(bars) { floatArrayOf(0.9f + r.nextFloat() * 2.6f, r.nextFloat() * 6.28f, 0.4f + r.nextFloat() * 1.3f, r.nextFloat() * 6.28f) }
    }

    // Time, disc angle and the lights' sway advance only while playing.
    var time by remember(media.id) { mutableFloatStateOf(0f) }
    var angle by remember(media.id) { mutableFloatStateOf(0f) }
    var swayPhase by remember(media.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, light, speed) {
        if (!isPlaying || light) return@LaunchedEffect
        var last = 0L
        val rate = speed.coerceIn(0.25f, 4f)
        while (isActive) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    time += dt * rate
                    angle = (angle + dt * 22f * rate) % 360f
                    // One full sway (up, down, back) every LIGHT_SWAY_PERIOD_S seconds.
                    swayPhase = (swayPhase + dt * 2f * PI.toFloat() / LIGHT_SWAY_PERIOD_S) % (2f * PI.toFloat())
                }
                last = now
            }
        }
    }
    val energy by animateFloatAsState(
        targetValue = when {
            isEnded -> 0f
            isPlaying -> 1f
            else -> 0.18f
        },
        animationSpec = if (light) tween(0) else tween(650, easing = FastOutSlowInEasing),
        label = "audioEnergy",
    )
    // The lights sway only while it plays; paused, they settle back to their place.
    val sway by animateFloatAsState(
        targetValue = if (isPlaying && !light) 1f else 0f,
        animationSpec = tween(if (light) 0 else 900, easing = FastOutSlowInEasing),
        label = "audioLightSway",
    )
    // The glow under the record: lit while it plays, fading out when it stops.
    val glow by animateFloatAsState(
        targetValue = if (isPlaying && !isBuffering) 1f else 0f,
        animationSpec = tween(if (light) 0 else 700, easing = FastOutSlowInEasing),
        label = "audioGlow",
    )
    val pulse = if (isBuffering && !light) {
        val transition = rememberInfiniteTransition(label = "audioBuffering")
        transition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
            label = "audioPulse",
        ).value
    } else {
        1f
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backdrop),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(top = 96.dp),
            contentAlignment = Alignment.Center,
        ) {
            val side = min(maxWidth.value, maxHeight.value).coerceAtMost(520f).dp
            Box(modifier = Modifier.size(side), contentAlignment = Alignment.Center) {
                val cover = info?.cover
                // The glow's gradient, made once per size and colour (a new one every frame before);
                // its strength is the alpha it is drawn with.
                val glowCache = remember(glowColor) { GlowBrushCache() }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val discR = size.minDimension * 0.29f * pulse
                    val inner = size.minDimension * 0.335f
                    val maxLen = size.minDimension * 0.14f
                    val barWidth = (2f * PI.toFloat() * inner / bars) * 0.5f
                    // Glow spreading from under the record (drawn past the box: nothing clips it)
                    if (glow > 0f) {
                        val glowR = size.minDimension * 0.95f
                        val glowBrush = glowCache.brush.takeIf { glowCache.size == size } ?: Brush.radialGradient(
                            0f to glowColor.copy(alpha = 0.42f),
                            0.45f to glowColor.copy(alpha = 0.16f),
                            1f to glowColor.copy(alpha = 0f),
                            center = c,
                            radius = glowR,
                        ).also {
                            glowCache.size = size
                            glowCache.brush = it
                        }
                        drawCircle(
                            brush = glowBrush,
                            radius = glowR,
                            center = c,
                            alpha = glow.coerceIn(0f, 1f),
                        )
                    }
                    // Rounded bars in the Material You colours
                    for (i in 0 until bars) {
                        val s = seeds[i]
                        val wave = 0.5f + 0.5f * sin(time * s[0] * 3.1f + s[1]) * (0.65f + 0.35f * sin(time * s[2] * 1.7f + s[3]))
                        val len = maxLen * (0.06f + 0.94f * energy * wave.coerceIn(0f, 1f))
                        val a = (i.toFloat() / bars) * 2f * PI.toFloat() - PI.toFloat() / 2f
                        val dir = Offset(cos(a), sin(a))
                        val t = (sin((i.toFloat() / bars) * 2f * PI.toFloat()) + 1f) / 2f
                        drawLine(
                            color = lerp(barStart, barEnd, t).copy(alpha = 0.35f + 0.65f * (0.25f + 0.75f * energy)),
                            start = c + dir * inner,
                            end = c + dir * (inner + len),
                            strokeWidth = barWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                    // The record, turning
                    rotate(angle, pivot = c) {
                        drawRecord(c, discR, cover, labelColor, labelEdge, labelMark)
                    }
                    // Reflections: they stay with the light while the record turns, and sway.
                    val swayDeg = sway * LIGHT_SWAY_DEG * sin(swayPhase)
                    drawLights(c, discR, labelR = discR * LABEL_RATIO, rotationDeg = LIGHT_BASE_DEG + swayDeg)
                    // Spindle hole
                    drawCircle(backdrop, radius = discR * 0.035f, center = c)
                }
                if (isEnded) {
                    Box(
                        modifier = Modifier
                            .size(side * 0.2f)
                            .clip(CircleShape)
                            .background(scheme.primary)
                            .clickable(onClick = onReplay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Replay, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(side * 0.1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = info?.title ?: media.displayName,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        info?.artist?.let { artist ->
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.primary.copy(alpha = 0.9f).compositeOver(Color.White.copy(alpha = 0.4f)),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 2.dp),
            )
        }
        Text(
            text = listOfNotNull(label, info?.quality).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 4.dp, bottom = 140.dp),
        )
    }
}

/** Label size against the record. */
private const val LABEL_RATIO = 0.4f
/** Where the reflections sit (degrees, clockwise from 3 o'clock) and how far they sway. */
private const val LIGHT_BASE_DEG = -40f
private const val LIGHT_SWAY_DEG = 9f
private const val LIGHT_SWAY_PERIOD_S = 2.6f

/** A black record with grooves and a label (the cover on it, if any). Drawn turned by the caller. */
private fun DrawScope.drawRecord(c: Offset, r: Float, cover: ImageBitmap?, labelColor: Color, labelEdge: Color, labelMark: Color) {
    // Vinyl, a little lighter towards the edge
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color(0xFF0B0B0C),
            0.75f to Color(0xFF141416),
            1f to Color(0xFF1C1C1F),
            center = c,
            radius = r,
        ),
        radius = r,
        center = c,
    )
    // Grooves
    val labelR = r * LABEL_RATIO
    val grooves = 16
    for (k in 0 until grooves) {
        val gr = labelR * 1.12f + (r * 0.97f - labelR * 1.12f) * k / (grooves - 1)
        drawCircle(
            color = Color.White.copy(alpha = if (k % 3 == 0) 0.11f else 0.05f),
            radius = gr,
            center = c,
            style = Stroke(width = if (k % 3 == 0) 1.6f else 1f),
        )
    }
    // Rim
    drawCircle(Color.White.copy(alpha = 0.10f), radius = r, center = c, style = Stroke(width = 2f))
    // Dark band around the label (the dead wax)
    drawCircle(Color.Black, radius = labelR * 1.08f, center = c)
    // Label
    if (cover != null) {
        clipPath(Path().apply { addOval(Rect(c, labelR)) }) {
            val scale = (labelR * 2f) / min(cover.width, cover.height).toFloat()
            val w = cover.width * scale
            val h = cover.height * scale
            drawImage(
                image = cover,
                dstOffset = IntOffset((c.x - w / 2f).roundToInt(), (c.y - h / 2f).roundToInt()),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            )
        }
    } else {
        drawCircle(labelColor, radius = labelR, center = c)
        drawCircle(labelEdge.copy(alpha = 0.55f), radius = labelR * 0.82f, center = c, style = Stroke(width = 1.5f))
        // A printed mark on the label: shows the record turning
        drawArc(
            color = labelMark.copy(alpha = 0.5f),
            startAngle = -120f,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = c - Offset(labelR * 0.6f, labelR * 0.6f),
            size = androidx.compose.ui.geometry.Size(labelR * 1.2f, labelR * 1.2f),
            style = Stroke(width = labelR * 0.07f, cap = StrokeCap.Round),
        )
    }
    drawCircle(labelEdge, radius = labelR, center = c, style = Stroke(width = 2f))
}

/**
 * Two opposite light reflections on the vinyl (between the label and the rim), soft at their
 * sides, like light on a real record.
 */
private fun DrawScope.drawLights(c: Offset, r: Float, labelR: Float, rotationDeg: Float) {
    val clear = Color.White.copy(alpha = 0f)
    val glow = Color.White.copy(alpha = 0.20f)
    val half = 0.045f // half width of a reflection, as a share of the circle
    val stops = arrayOf(
        0f to glow,
        half to clear,
        0.5f - half to clear,
        0.5f to glow,
        0.5f + half to clear,
        1f - half to clear,
        1f to glow,
    )
    val ring = Path().apply {
        addOval(Rect(c, r * 0.985f))
        addOval(Rect(c, labelR * 1.1f))
        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
    }
    rotate(rotationDeg, pivot = c) {
        clipPath(ring) {
            drawCircle(brush = Brush.sweepGradient(*stops, center = c), radius = r, center = c)
        }
    }
}

/** The audio visual's glow gradient for one size of the canvas. */
private class GlowBrushCache {
    var size: androidx.compose.ui.geometry.Size = androidx.compose.ui.geometry.Size.Unspecified
    var brush: Brush? = null
}
