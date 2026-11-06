package dev.ander.alexet.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** Look of everything AI (smart search): a ready palette, or [CUSTOM] = a base palette plus per-effect changes. */
enum class AiEffectsPreset { MULTI_COLOR, SYSTEM, CUSTOM }

/** Where the effect colors come from: a fixed multi-color set, or Material You. */
enum class AiPalette { MULTI_COLOR, SYSTEM }

enum class AiLoaderStyle { ORB, RING, DOTS }

/** Where the background glow ("curtain") of the AI search comes from. */
enum class AiCurtainEdge { TOP, BOTTOM, LEFT, RIGHT, SIDES }

enum class AiEffect {
    /** Ring around "AI search" in the settings, when it is switched on. */
    SETTINGS_RING,
    /** Circling colors around the search field in AI mode. */
    SEARCH_FIELD,
    /** Soft glow from an edge of the screen while searching in AI mode (the curtain). */
    SEARCH_BACKGROUND,
    /** Glow on the edges of the results when they arrive. */
    RESULTS_EDGE,
    /** Animation in the middle while a search takes a moment. */
    LOADER,
    /** Glint sweeping over the folders the AI search covers. */
    FOLDER_GLINT,
    /** Lights running along the gaps between the result tiles. */
    RESULTS_NEXUS,
    ;

    /** Shown once and gone (the others last while their state does, unless set to timed). */
    val alwaysTimed: Boolean get() = this == SETTINGS_RING || this == RESULTS_EDGE

    /** The option of drawing it under the photos instead of over them. */
    val canBeBehind: Boolean get() = this == SEARCH_BACKGROUND || this == RESULTS_EDGE

    /** Lasting effects that can be switched to timed. */
    val canBeTimed: Boolean get() = this == SEARCH_FIELD || this == SEARCH_BACKGROUND || this == FOLDER_GLINT || this == RESULTS_NEXUS
}

@Immutable
data class AiEffectStyle(
    val enabled: Boolean = true,
    /** Own colors (ARGB); null: the palette's. */
    val customColors: List<Int>? = null,
    /** Glow strength, 1 = default. */
    val brightness: Float = 1f,
    /** Animation tempo, 1 = default. */
    val speed: Float = 1f,
    /** Glow reach, 1 = default. */
    val width: Float = 1f,
    /** How long a timed effect shows. */
    val durationMs: Int = DEFAULT_DURATION_MS,
    /** A lasting effect set to go out after [durationMs]. */
    val timed: Boolean = false,
    /** Drawn under the photos (seen in the gaps and on free background) instead of over them. */
    val behind: Boolean = false,
    /** How many trails (Nexus). */
    val count: Int = DEFAULT_COUNT,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        customColors?.let { put("colors", JSONArray(it)) }
        put("brightness", brightness.toDouble())
        put("speed", speed.toDouble())
        put("width", width.toDouble())
        put("durationMs", durationMs)
        put("timed", timed)
        put("behind", behind)
        put("count", count)
    }

    companion object {
        const val MIN_BRIGHTNESS = 0.2f
        const val MAX_BRIGHTNESS = 1.6f
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 3f
        const val MIN_WIDTH = 0.4f
        const val MAX_WIDTH = 2.5f
        const val MIN_DURATION_MS = 500
        const val MAX_DURATION_MS = 15_000
        const val DEFAULT_DURATION_MS = 5_000
        const val MAX_COLORS = 5
        const val MIN_COUNT = 1
        const val MAX_COUNT = 20
        const val DEFAULT_COUNT = 6

        fun parse(o: JSONObject?, fallback: AiEffectStyle): AiEffectStyle {
            if (o == null) return fallback
            val colors = o.optJSONArray("colors")?.let { a ->
                (0 until minOf(a.length(), MAX_COLORS)).map { a.optLong(it).toInt() }
            }?.takeIf { it.isNotEmpty() }
            // Older saves wrote 0 for lasting effects: their default duration then.
            val duration = o.optInt("durationMs", fallback.durationMs).takeIf { it > 0 } ?: fallback.durationMs
            return AiEffectStyle(
                enabled = o.optBoolean("enabled", fallback.enabled),
                customColors = colors,
                brightness = o.optDouble("brightness", fallback.brightness.toDouble()).toFloat().coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
                speed = o.optDouble("speed", fallback.speed.toDouble()).toFloat().coerceIn(MIN_SPEED, MAX_SPEED),
                width = o.optDouble("width", fallback.width.toDouble()).toFloat().coerceIn(MIN_WIDTH, MAX_WIDTH),
                durationMs = duration.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
                timed = o.optBoolean("timed", fallback.timed),
                behind = o.optBoolean("behind", fallback.behind),
                count = o.optInt("count", fallback.count).coerceIn(MIN_COUNT, MAX_COUNT),
            )
        }
    }
}

@Immutable
data class AiEffectsConfig(
    val preset: AiEffectsPreset = AiEffectsPreset.MULTI_COLOR,
    /** The palette [AiEffectsPreset.CUSTOM] starts from. */
    val customBase: AiPalette = AiPalette.MULTI_COLOR,
    /** Per-effect settings of [AiEffectsPreset.CUSTOM]; kept while another preset is chosen. */
    val custom: Map<AiEffect, AiEffectStyle> = DEFAULT_STYLES,
    val customLoaderStyle: AiLoaderStyle = AiLoaderStyle.ORB,
    val customCurtainEdge: AiCurtainEdge = AiCurtainEdge.TOP,
    /** Color sets saved to put on any effect at once (ARGB, up to [MAX_SAVED_PALETTES]). */
    val savedPalettes: List<List<Int>> = emptyList(),
) {
    val palette: AiPalette
        get() = when (preset) {
            AiEffectsPreset.MULTI_COLOR -> AiPalette.MULTI_COLOR
            AiEffectsPreset.SYSTEM -> AiPalette.SYSTEM
            AiEffectsPreset.CUSTOM -> customBase
        }

    fun style(effect: AiEffect): AiEffectStyle =
        if (preset == AiEffectsPreset.CUSTOM) custom[effect] ?: defaultStyle(effect) else defaultStyle(effect)

    /** [effect] goes out after its duration. */
    fun isTimed(effect: AiEffect): Boolean = effect.alwaysTimed || (effect.canBeTimed && style(effect).timed)

    val loaderStyle: AiLoaderStyle
        get() = if (preset == AiEffectsPreset.CUSTOM) customLoaderStyle else AiLoaderStyle.ORB

    val curtainEdge: AiCurtainEdge
        get() = if (preset == AiEffectsPreset.CUSTOM) customCurtainEdge else AiCurtainEdge.TOP

    fun withCustom(effect: AiEffect, transform: (AiEffectStyle) -> AiEffectStyle): AiEffectsConfig =
        copy(custom = custom + (effect to transform(custom[effect] ?: defaultStyle(effect))))

    /** Custom back to the base palette's look; the saved palettes stay. */
    fun resetCustom(): AiEffectsConfig =
        copy(custom = DEFAULT_STYLES, customLoaderStyle = AiLoaderStyle.ORB, customCurtainEdge = AiCurtainEdge.TOP)

    /** Room for one more saved palette. */
    val canSavePalette: Boolean get() = savedPalettes.size < MAX_SAVED_PALETTES

    /** [colors] added to the saved palettes (not twice, not past the limit). */
    fun withSavedPalette(colors: List<Int>): AiEffectsConfig {
        val palette = colors.take(AiEffectStyle.MAX_COLORS)
        if (palette.isEmpty() || palette in savedPalettes || !canSavePalette) return this
        return copy(savedPalettes = savedPalettes + listOf(palette))
    }

    fun withoutSavedPalette(index: Int): AiEffectsConfig =
        copy(savedPalettes = savedPalettes.filterIndexed { i, _ -> i != index })

    fun toJson(): JSONObject = JSONObject().apply {
        put("preset", preset.name)
        put("customBase", customBase.name)
        put("loaderStyle", customLoaderStyle.name)
        put("curtainEdge", customCurtainEdge.name)
        put("custom", JSONObject().apply { custom.forEach { (effect, style) -> put(effect.name, style.toJson()) } })
        put("palettes", JSONArray().apply { savedPalettes.forEach { put(JSONArray(it)) } })
    }

    companion object {
        const val MAX_SAVED_PALETTES = 10

        /** Blue, violet, pink, amber, green. */
        val MULTI_COLORS: List<Int> = listOf(
            0xFF4C8DFF.toInt(),
            0xFF9B72F2.toInt(),
            0xFFE86AA6.toInt(),
            0xFFF6B44B.toInt(),
            0xFF3CC48A.toInt(),
        )

        fun defaultStyle(effect: AiEffect): AiEffectStyle = when (effect) {
            AiEffect.SETTINGS_RING -> AiEffectStyle(durationMs = 3_000)
            // Off unless turned on: the rest (ring, field, loader, folders) is enough by default.
            AiEffect.SEARCH_BACKGROUND, AiEffect.RESULTS_EDGE, AiEffect.RESULTS_NEXUS -> AiEffectStyle(enabled = false)
            else -> AiEffectStyle()
        }

        val DEFAULT_STYLES: Map<AiEffect, AiEffectStyle> = AiEffect.entries.associateWith { defaultStyle(it) }

        /** [fallback] gives what [o] does not carry (a settings import keeps the current values). */
        fun parse(o: JSONObject?, fallback: AiEffectsConfig = AiEffectsConfig()): AiEffectsConfig {
            if (o == null) return fallback
            val customJson = o.optJSONObject("custom")
            return AiEffectsConfig(
                preset = enumOr(o.optString("preset"), fallback.preset),
                customBase = enumOr(o.optString("customBase"), fallback.customBase),
                custom = AiEffect.entries.associateWith {
                    AiEffectStyle.parse(customJson?.optJSONObject(it.name), fallback.custom[it] ?: defaultStyle(it))
                },
                // "SPARK": the first loader, replaced by the orb.
                customLoaderStyle = enumOr(o.optString("loaderStyle").replace("SPARK", "ORB"), fallback.customLoaderStyle),
                customCurtainEdge = enumOr(o.optString("curtainEdge"), fallback.customCurtainEdge),
                savedPalettes = if (o.has("palettes")) parsePalettes(o.optJSONArray("palettes")) else fallback.savedPalettes,
            )
        }

        private fun parsePalettes(a: JSONArray?): List<List<Int>> {
            if (a == null) return emptyList()
            val out = ArrayList<List<Int>>()
            for (i in 0 until a.length()) {
                val p = a.optJSONArray(i) ?: continue
                val colors = (0 until minOf(p.length(), AiEffectStyle.MAX_COLORS)).map { p.optLong(it).toInt() }
                if (colors.isNotEmpty() && colors !in out) out += colors
                if (out.size == MAX_SAVED_PALETTES) break
            }
            return out
        }

        private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }
}
