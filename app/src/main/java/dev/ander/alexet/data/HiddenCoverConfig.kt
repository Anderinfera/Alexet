package dev.ander.alexet.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** What a hidden (.nomedia) folder's cover shows instead of its picture. */
enum class HiddenCoverStyle { OFF, PIXELATE, BLUR, IMAGE, COLOR, STRIPES, CHECKER }

/** What stripes and the checkerboard draw anew on each start of the app. */
enum class HiddenCoverRandom { NONE, ORIENTATION, COLOR, ALL }

/** Where random colors come from: anywhere, a hue range or the saved palette. */
enum class HiddenCoverColorLimit { NONE, RANGE, PALETTE }

data class HiddenCoverConfig(
    val style: HiddenCoverStyle = HiddenCoverStyle.OFF,
    /** Pixelation: blocks along a side of the tile (Y×Y). */
    val pixels: Int = 8,
    /** Blur strength, [MIN_BLUR]..[MAX_BLUR]. */
    val blur: Int = 12,
    /** Copy of the chosen picture in the app's files; null: none chosen yet. */
    val imagePath: String? = null,
    /** Bumped on each new picture: the copy keeps its name, the tiles load it again. */
    val imageVersion: Long = 0L,
    val color: Int = DEFAULT_COLOR,
    /** Stripes across a tile. */
    val stripeCount: Int = 6,
    /** Width of a stripe, percent of its period. */
    val stripeWidth: Int = 50,
    /** Turn of the stripes / checkerboard, degrees. */
    val angle: Int = 45,
    /** Checkerboard: squares along a side of the tile. */
    val checkerCells: Int = 6,
    /** Stripes / squares, and the ground between them. */
    val colorA: Int = DEFAULT_COLOR_A,
    val colorB: Int = DEFAULT_COLOR_B,
    val random: HiddenCoverRandom = HiddenCoverRandom.NONE,
    val colorLimit: HiddenCoverColorLimit = HiddenCoverColorLimit.NONE,
    /** Hue range of random colors (degrees); [hueFrom] past [hueTo] goes round through red. */
    val hueFrom: Int = 0,
    val hueTo: Int = 120,
    /** Colors random ones are taken from (ARGB). */
    val palette: List<Int> = emptyList(),
) {
    val randomOrientation: Boolean get() = random == HiddenCoverRandom.ORIENTATION || random == HiddenCoverRandom.ALL
    val randomColors: Boolean get() = random == HiddenCoverRandom.COLOR || random == HiddenCoverRandom.ALL

    fun toJson(): JSONObject = JSONObject().apply {
        put("style", style.name)
        put("pixels", pixels)
        put("blur", blur)
        imagePath?.let { put("imagePath", it) }
        put("imageVersion", imageVersion)
        put("color", color)
        put("stripeCount", stripeCount)
        put("stripeWidth", stripeWidth)
        put("angle", angle)
        put("checkerCells", checkerCells)
        put("colorA", colorA)
        put("colorB", colorB)
        put("random", random.name)
        put("colorLimit", colorLimit.name)
        put("hueFrom", hueFrom)
        put("hueTo", hueTo)
        put("palette", JSONArray(palette))
    }

    companion object {
        const val MIN_PIXELS = 2
        const val MAX_PIXELS = 32
        const val MIN_BLUR = 1
        const val MAX_BLUR = 25
        const val MIN_STRIPES = 2
        const val MAX_STRIPES = 20
        const val MIN_STRIPE_WIDTH = 10
        const val MAX_STRIPE_WIDTH = 90
        const val MIN_CELLS = 2
        const val MAX_CELLS = 16
        const val MAX_PALETTE = 12

        val DEFAULT_COLOR = 0xFF37474F.toInt()
        val DEFAULT_COLOR_A = 0xFF263238.toInt()
        val DEFAULT_COLOR_B = 0xFF78909C.toInt()

        /** [fallback] gives what [o] does not carry (an import keeps the current values). */
        fun parse(o: JSONObject?, fallback: HiddenCoverConfig = HiddenCoverConfig()): HiddenCoverConfig {
            if (o == null) return fallback
            val f = fallback
            return HiddenCoverConfig(
                style = enumOr(o.optString("style"), f.style),
                pixels = o.optInt("pixels", f.pixels).coerceIn(MIN_PIXELS, MAX_PIXELS),
                blur = o.optInt("blur", f.blur).coerceIn(MIN_BLUR, MAX_BLUR),
                imagePath = if (o.has("imagePath")) o.optString("imagePath").takeIf { it.isNotBlank() } else f.imagePath,
                imageVersion = o.optLong("imageVersion", f.imageVersion),
                color = o.optLong("color", f.color.toLong()).toInt(),
                stripeCount = o.optInt("stripeCount", f.stripeCount).coerceIn(MIN_STRIPES, MAX_STRIPES),
                stripeWidth = o.optInt("stripeWidth", f.stripeWidth).coerceIn(MIN_STRIPE_WIDTH, MAX_STRIPE_WIDTH),
                angle = o.optInt("angle", f.angle).mod(180),
                checkerCells = o.optInt("checkerCells", f.checkerCells).coerceIn(MIN_CELLS, MAX_CELLS),
                colorA = o.optLong("colorA", f.colorA.toLong()).toInt(),
                colorB = o.optLong("colorB", f.colorB.toLong()).toInt(),
                random = enumOr(o.optString("random"), f.random),
                colorLimit = enumOr(o.optString("colorLimit"), f.colorLimit),
                hueFrom = o.optInt("hueFrom", f.hueFrom).mod(360),
                hueTo = o.optInt("hueTo", f.hueTo).mod(360),
                palette = o.optJSONArray("palette")?.let { a ->
                    (0 until minOf(a.length(), MAX_PALETTE)).map { a.optLong(it).toInt() }.distinct()
                } ?: f.palette,
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }
}

/** Turn and colors the stripes / checkerboard are drawn with. */
data class HiddenCoverLook(val angle: Float, val colorA: Int, val colorB: Int)

/**
 * Random looks are drawn once per start of the app: the same on every tile and every redraw
 * until the next start.
 */
object HiddenCoverSession {
    private val seed = System.nanoTime()

    fun look(config: HiddenCoverConfig): HiddenCoverLook {
        val angleRandom = Random(seed)
        val angle = if (config.randomOrientation) angleRandom.nextInt(0, 180).toFloat() else config.angle.toFloat()
        if (!config.randomColors) return HiddenCoverLook(angle, config.colorA, config.colorB)
        val colorRandom = Random(seed xor 0x5DEECE66DL)
        val palette = config.palette
        if (config.colorLimit == HiddenCoverColorLimit.PALETTE && palette.isNotEmpty()) {
            val a = colorRandom.nextInt(palette.size)
            // Two different colors when the palette has them.
            val b = if (palette.size > 1) (a + 1 + colorRandom.nextInt(palette.size - 1)) % palette.size else a
            return HiddenCoverLook(angle, palette[a], palette[b])
        }
        val useRange = config.colorLimit == HiddenCoverColorLimit.RANGE
        val a = randomColor(colorRandom, useRange, config.hueFrom, config.hueTo, dark = true)
        val b = randomColor(colorRandom, useRange, config.hueFrom, config.hueTo, dark = false)
        return HiddenCoverLook(angle, a, b)
    }

    /** A dark and a light one, so the pattern always shows. */
    private fun randomColor(random: Random, useRange: Boolean, from: Int, to: Int, dark: Boolean): Int {
        val hue = if (!useRange) {
            random.nextFloat() * 360f
        } else {
            val span = if (to >= from) to - from else to + 360 - from
            (from + random.nextFloat() * span) % 360f
        }
        val saturation = 0.5f + random.nextFloat() * 0.35f
        val value = if (dark) 0.3f + random.nextFloat() * 0.2f else 0.72f + random.nextFloat() * 0.23f
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
    }
}
