package dev.ander.alexet.ui.components.aiglow

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random

enum class AiGlowMode {
    /** A ring just outside a rounded rectangle, fading outwards. */
    HALO,
    /** Glow from the edges of a (rounded) rectangle inwards; with a stroke, a ring along them too. */
    EDGE,
    /** A soft, wavy wash from the top edge down. */
    TOP_WASH,
}

/** The corner radii of an [AiGlowMode.EDGE] area, in px. */
class AiCorners(val topLeft: Float, val topRight: Float, val bottomRight: Float, val bottomLeft: Float) {
    companion object {
        fun all(radius: Float) = AiCorners(radius, radius, radius, radius)
    }
}

/**
 * Draws the AI glow. On Android 13+ an AGSL shader computes it per pixel and adds triangular
 * noise of about one 8-bit level, so gradients stay smooth on 8-bit panels instead of showing
 * bands. Older versions use Compose gradients with a faint noise texture laid over the glow
 * (only where it shows) to the same end.
 */
class AiGlowRenderer {
    /** Null before Android 13, or when the shader does not compile on this phone (the gradients take over). */
    private var shader: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { RuntimeShader(SHADER) }
            .onFailure { dev.ander.alexet.data.AppLog.e("AiGlow", "Glow shader unavailable", it) }
            .getOrNull()
    } else {
        null
    }

    /**
     * [rect]: the shape the glow belongs to, in this scope's coordinates. [phase]: 0..1 turn of
     * the colors. [glowPx]: how far it reaches. [alpha]: overall strength (brightness x fade).
     * [corners]: the rounded corners of [AiGlowMode.EDGE] (default: [cornerRadiusPx] on all).
     */
    fun DrawScope.draw(
        mode: AiGlowMode,
        rect: Rect,
        cornerRadiusPx: Float,
        glowPx: Float,
        strokePx: Float,
        colors: List<Color>,
        phase: Float,
        alpha: Float,
        corners: AiCorners? = null,
    ) {
        if (alpha <= 0.001f || colors.isEmpty() || rect.isEmpty) return
        val area = when (mode) {
            AiGlowMode.HALO -> rect.inflate(glowPx + strokePx)
            AiGlowMode.EDGE, AiGlowMode.TOP_WASH -> rect
        }
        val radii = corners ?: AiCorners.all(cornerRadiusPx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            try {
                drawShader(mode, rect, area, cornerRadiusPx, radii, glowPx, strokePx, colors, phase, alpha)
                return
            } catch (e: IllegalArgumentException) {
                dev.ander.alexet.data.AppLog.e("AiGlow", "Glow shader failed", e)
                shader = null
            }
        }
        drawFallback(mode, rect, area, cornerRadiusPx, radii, glowPx, strokePx, colors, phase, alpha)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun DrawScope.drawShader(
        mode: AiGlowMode,
        rect: Rect,
        area: Rect,
        radius: Float,
        corners: AiCorners,
        glow: Float,
        stroke: Float,
        colors: List<Color>,
        phase: Float,
        alpha: Float,
    ) {
        val s = shader as RuntimeShader
        s.setFloatUniform("rect", rect.left, rect.top, rect.right, rect.bottom)
        s.setFloatUniform("radius", radius)
        s.setFloatUniform("radii", corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)
        s.setFloatUniform("glow", glow.coerceAtLeast(1f))
        s.setFloatUniform("stroke", stroke)
        s.setFloatUniform("mode", mode.ordinal.toFloat())
        s.setFloatUniform("phase", phase)
        s.setFloatUniform("intensity", alpha.coerceIn(0f, 2f))
        val count = colors.size.coerceAtMost(MAX_COLORS)
        val labs = oklabOf(colors)
        for (i in 0 until MAX_COLORS) {
            // Blended in Oklab by the shader: even steps between the colors, no gray middles.
            val lab = labs[i % count]
            s.setFloatUniform("c$i", lab[0], lab[1], lab[2], lab[3])
        }
        s.setFloatUniform("count", count.toFloat())
        drawRect(brush = ShaderBrush(s), topLeft = area.topLeft, size = area.size)
    }

    private fun DrawScope.drawFallback(
        mode: AiGlowMode,
        rect: Rect,
        area: Rect,
        radius: Float,
        corners: AiCorners,
        glow: Float,
        stroke: Float,
        colors: List<Color>,
        phase: Float,
        alpha: Float,
    ) {
        val a = alpha.coerceIn(0f, 1f)
        drawIntoCanvas { canvas -> canvas.saveLayer(area, Paint()) }
        when (mode) {
            AiGlowMode.HALO -> {
                val sweep = Brush.sweepGradient(paletteStops(colors, phase), center = rect.center)
                val steps = 12
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val grow = stroke + glow * t
                    val w = if (i == 0) stroke.coerceAtLeast(1f) else glow / steps + 1f
                    val k = if (i == 0) 1f else exp(-3f * t) * 0.35f
                    val r = rect.inflate(grow - w / 2f)
                    drawRoundRect(
                        brush = sweep,
                        topLeft = r.topLeft,
                        size = r.size,
                        cornerRadius = CornerRadius(radius + grow),
                        style = Stroke(width = w),
                        alpha = (a * k).coerceIn(0f, 1f),
                    )
                }
            }
            AiGlowMode.EDGE -> {
                val shape = RoundRect(
                    rect,
                    CornerRadius(corners.topLeft),
                    CornerRadius(corners.topRight),
                    CornerRadius(corners.bottomRight),
                    CornerRadius(corners.bottomLeft),
                )
                // The rounded corners cut the glow along the shape's own curve.
                clipPath(Path().apply { addRoundRect(shape) }) {
                    val g = if (stroke > 0f) a * 0.6f else a
                    val edges = listOf(0f, 0.25f, 0.5f, 0.75f).map { paletteAt(colors, phase + it) }
                    drawRect(Brush.verticalGradient(listOf(edges[0], Color.Transparent), rect.top, rect.top + glow), rect.topLeft, Size(rect.width, glow), alpha = g)
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, edges[1]), rect.right - glow, rect.right), Offset(rect.right - glow, rect.top), Size(glow, rect.height), alpha = g)
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, edges[2]), rect.bottom - glow, rect.bottom), Offset(rect.left, rect.bottom - glow), Size(rect.width, glow), alpha = g)
                    drawRect(Brush.horizontalGradient(listOf(edges[3], Color.Transparent), rect.left, rect.left + glow), rect.topLeft, Size(glow, rect.height), alpha = g)
                    if (stroke > 0f) {
                        val r = rect.deflate(stroke / 2f)
                        drawRoundRect(
                            brush = Brush.sweepGradient(paletteStops(colors, phase), center = rect.center),
                            topLeft = r.topLeft,
                            size = r.size,
                            cornerRadius = CornerRadius(corners.topLeft),
                            style = Stroke(width = stroke),
                            alpha = a,
                        )
                    }
                }
            }
            AiGlowMode.TOP_WASH -> {
                drawRect(Brush.horizontalGradient(paletteStops(colors, phase, turns = 0.85f), rect.left, rect.right), rect.topLeft, rect.size, alpha = a)
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black, Color.Black.copy(alpha = 0.35f), Color.Transparent), rect.top, rect.top + glow),
                    rect.topLeft,
                    rect.size,
                    blendMode = BlendMode.DstIn,
                )
            }
        }
        drawRect(brush = noiseBrush, topLeft = area.topLeft, size = area.size, blendMode = BlendMode.SrcAtop)
        drawIntoCanvas { canvas -> canvas.restore() }
    }

    private companion object {
        /** Gray noise with a little alpha: laid over the glow (SrcAtop) it dithers its gradient. */
        val noiseBrush: Brush by lazy {
            val size = 64
            val rnd = Random(7)
            val pixels = IntArray(size * size) {
                val v = rnd.nextInt(256)
                (NOISE_ALPHA shl 24) or (v shl 16) or (v shl 8) or v
            }
            val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
            val image: ImageBitmap = bitmap.asImageBitmap()
            androidx.compose.ui.graphics.ShaderBrush(
                androidx.compose.ui.graphics.ImageShader(image, TileMode.Repeated, TileMode.Repeated),
            )
        }
        const val NOISE_ALPHA = 7

        const val SHADER = """
            uniform float4 rect;
            uniform float radius;
            uniform float4 radii;
            uniform float glow;
            uniform float stroke;
            uniform float mode;
            uniform float phase;
            uniform float intensity;
            uniform float count;
            // The palette in Oklab (L, a, b) with alpha.
            uniform float4 c0;
            uniform float4 c1;
            uniform float4 c2;
            uniform float4 c3;
            uniform float4 c4;
            uniform float4 c5;
            uniform float4 c6;
            uniform float4 c7;
            uniform float4 c8;

            const float TAU = 6.2831853;

            float4 pick(float i) {
                if (i < 0.5) return c0;
                if (i < 1.5) return c1;
                if (i < 2.5) return c2;
                if (i < 3.5) return c3;
                if (i < 4.5) return c4;
                if (i < 5.5) return c5;
                if (i < 6.5) return c6;
                if (i < 7.5) return c7;
                return c8;
            }

            float3 oklabToSrgb(float3 c) {
                float l = c.x + 0.3963377774 * c.y + 0.2158037573 * c.z;
                float m = c.x - 0.1055613458 * c.y - 0.0638541728 * c.z;
                float s = c.x - 0.0894841775 * c.y - 1.2914855480 * c.z;
                l = l * l * l;
                m = m * m * m;
                s = s * s * s;
                float3 lin = clamp(float3(
                    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                    -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
                ), 0.0, 1.0);
                float3 lo = lin * 12.92;
                float3 hi = 1.055 * pow(lin, float3(1.0 / 2.4)) - 0.055;
                return mix(lo, hi, step(float3(0.0031308), lin));
            }

            // A closed Catmull-Rom curve through the colors: steady pace, no stop at each color.
            float4 palette(float t) {
                float x = fract(t) * count;
                float i = floor(x);
                float f = x - i;
                float4 p0 = pick(mod(i - 1.0 + count, count));
                float4 p1 = pick(i);
                float4 p2 = pick(mod(i + 1.0, count));
                float4 p3 = pick(mod(i + 2.0, count));
                float f2 = f * f;
                float f3 = f2 * f;
                float4 lab = 0.5 * (2.0 * p1 + (p2 - p0) * f
                    + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f2
                    + (3.0 * p1 - p0 - 3.0 * p2 + p3) * f3);
                return float4(oklabToSrgb(lab.xyz), clamp(lab.w, 0.0, 1.0));
            }

            float hash(float2 p) {
                float3 p3 = fract(float3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            float sdRoundRect(float2 p, float2 c, float2 h, float r) {
                float2 q = abs(p - c) - h + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 p) {
                float2 c = (rect.xy + rect.zw) * 0.5;
                float2 h = (rect.zw - rect.xy) * 0.5;
                float a = 0.0;
                float t = 0.0;
                if (mode < 0.5) {
                    float d = sdRoundRect(p, c, h, min(radius, min(h.x, h.y)));
                    float ring = smoothstep(-1.0, 0.0, d) * (1.0 - smoothstep(stroke, stroke + 1.0, d));
                    float beyond = max(d - stroke, 0.0);
                    // Fades to exactly 0 before the end of the drawn area: no visible border.
                    float halo = d > 0.0 ? exp(-beyond / glow * 3.0) * 0.55 * (1.0 - smoothstep(glow * 0.55, glow, beyond)) : 0.0;
                    a = max(ring, halo);
                    float2 v = (p - c) / max(h, float2(1.0));
                    t = atan(v.y, v.x) / TAU + phase;
                } else if (mode < 1.5) {
                    float2 rel = p - c;
                    float r = rel.x < 0.0 ? (rel.y < 0.0 ? radii.x : radii.w) : (rel.y < 0.0 ? radii.y : radii.z);
                    r = min(r, min(h.x, h.y));
                    float dx = min(p.x - rect.x, rect.z - p.x);
                    float dy = min(p.y - rect.y, rect.w - p.y);
                    if (dx >= 0.0 && dy >= 0.0) {
                        // Each edge glows on its own and the two nearest add up like light
                        // (screen): no crease along the diagonals. In a rounded corner the near
                        // edge is the arc, and the far edge's term is bent to meet the straight
                        // parts smoothly at both ends of the arc.
                        float u = dx - r;
                        float w = dy - r;
                        float dNear = dx;
                        float dFar = dy;
                        float dEdge = min(dx, dy);
                        if (u < 0.0 && w < 0.0) {
                            float rho = length(float2(u, w));
                            dNear = r - rho;
                            dFar = r + u + w + rho;
                            dEdge = dNear;
                        }
                        float an = exp(-max(dNear, 0.0) / glow * 3.0);
                        float af = exp(-max(dFar, 0.0) / glow * 3.0);
                        float g = 1.0 - (1.0 - an) * (1.0 - af);
                        if (stroke > 0.0) {
                            // A line along the border, the glow fainter under it.
                            float line = 1.0 - smoothstep(stroke, stroke + 1.0, dEdge);
                            g = max(g * 0.6, line);
                        }
                        // Smooth outer curve of the corners.
                        a = g * clamp(dEdge + 0.5, 0.0, 1.0);
                    }
                    float2 v = rel / max(h, float2(1.0));
                    t = atan(v.y, v.x) / TAU + phase;
                } else {
                    float x = (p.x - rect.x) / max(rect.z - rect.x, 1.0);
                    float y = p.y - rect.y;
                    float wave = 1.0 + 0.22 * sin(x * TAU * 1.3 + phase * TAU) + 0.12 * sin(x * TAU * 2.7 - phase * TAU * 2.0);
                    a = y >= 0.0 ? exp(-y / (glow * wave) * 2.2) * 0.8 : 0.0;
                    // Soft ends along the edge and to 0 well before the far side: no hard lines.
                    a *= smoothstep(0.0, 0.1, x) * smoothstep(0.0, 0.1, 1.0 - x);
                    a *= 1.0 - smoothstep(glow * 1.4, glow * 2.4, y);
                    t = x * 0.85 + phase;
                }
                // No glow here (most of the area): nothing to blend, the colour is not needed.
                if (a <= 0.0) return half4(0.0);
                float4 col = palette(t);
                a = clamp(a * intensity * col.a, 0.0, 1.0);
                // Triangular dither of about one 8-bit level: no bands in the falloff.
                float n = (hash(p) + hash(p + 71.7) - 1.0) / 255.0;
                // No noise where there is no glow: the area's border stays invisible.
                n *= smoothstep(0.0, 0.02, a);
                a = clamp(a + n, 0.0, 1.0);
                return half4(clamp(col.rgb * a + n, 0.0, 1.0), a);
            }
        """

        /** Color slots of the shader; a longer palette uses its first ones. */
        const val MAX_COLORS = 9
    }
}

/**
 * The palette's color at [t] (0..1, wrapping), like the shader: a closed Catmull-Rom curve
 * through the colors in Oklab, so the colors change at a steady pace without stopping at each
 * one or passing through gray.
 */
fun paletteAt(colors: List<Color>, t: Float): Color {
    if (colors.isEmpty()) return Color.Transparent
    if (colors.size == 1) return colors[0]
    val n = colors.size
    val x = ((t % 1f) + 1f) % 1f * n
    val i = floor(x).toInt().coerceIn(0, n - 1)
    val f = x - i
    val labs = oklabOf(colors)
    val p0 = labs[(i - 1 + n) % n]
    val p1 = labs[i]
    val p2 = labs[(i + 1) % n]
    val p3 = labs[(i + 2) % n]
    val f2 = f * f
    val f3 = f2 * f
    val out = FloatArray(4) { k ->
        0.5f * (2f * p1[k] + (p2[k] - p0[k]) * f +
            (2f * p0[k] - 5f * p1[k] + 4f * p2[k] - p3[k]) * f2 +
            (3f * p1[k] - p0[k] - 3f * p2[k] + p3[k]) * f3)
    }
    return oklabToColor(out[0], out[1], out[2], out[3])
}

/**
 * Stops of a gradient running through the palette from [start] for [turns] turns: at least
 * [perColor] per color, so a gradient between them follows the curve (too few made the colors
 * pulse as they moved).
 */
fun paletteStops(colors: List<Color>, start: Float, turns: Float = 1f, perColor: Int = 6): List<Color> {
    if (colors.size <= 1) return List(2) { colors.firstOrNull() ?: Color.Transparent }
    val steps = maxOf(8, ceil(colors.size * perColor * turns).toInt())
    return List(steps + 1) { paletteAt(colors, start + turns * it / steps) }
}

/**
 * The palettes' colors in Oklab, kept for the few palettes on screen: they are drawn every frame,
 * and each conversion takes cube roots and powers.
 */
private val oklabCache = object : LinkedHashMap<List<Color>, Array<FloatArray>>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Color>, Array<FloatArray>>?): Boolean = size > 8
}

private fun oklabOf(colors: List<Color>): Array<FloatArray> = synchronized(oklabCache) {
    oklabCache.getOrPut(colors) { Array(colors.size) { colors[it].toOklab() } }
}

/** This color in Oklab: L, a, b and alpha. */
internal fun Color.toOklab(): FloatArray {
    val c = convert(ColorSpaces.Srgb)
    val r = srgbToLinear(c.red)
    val g = srgbToLinear(c.green)
    val b = srgbToLinear(c.blue)
    val l = Math.cbrt((0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b).toDouble()).toFloat()
    val m = Math.cbrt((0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b).toDouble()).toFloat()
    val s = Math.cbrt((0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b).toDouble()).toFloat()
    return floatArrayOf(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        c.alpha,
    )
}

private fun oklabToColor(lightness: Float, a: Float, b: Float, alpha: Float): Color {
    var l = lightness + 0.3963377774f * a + 0.2158037573f * b
    var m = lightness - 0.1055613458f * a - 0.0638541728f * b
    var s = lightness - 0.0894841775f * a - 1.2914855480f * b
    l *= l * l
    m *= m * m
    s *= s * s
    return Color(
        red = linearToSrgb(4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s),
        green = linearToSrgb(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s),
        blue = linearToSrgb(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

private fun srgbToLinear(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

private fun linearToSrgb(v: Float): Float {
    val x = v.coerceIn(0f, 1f)
    return if (x <= 0.0031308f) x * 12.92f else (1.055 * x.toDouble().pow(1.0 / 2.4) - 0.055).toFloat().coerceIn(0f, 1f)
}
