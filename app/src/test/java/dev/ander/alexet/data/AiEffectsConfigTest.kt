package dev.ander.alexet.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEffectsConfigTest {

    @Test
    fun survivesJson() {
        val config = AiEffectsConfig(
            preset = AiEffectsPreset.CUSTOM,
            customBase = AiPalette.SYSTEM,
            custom = AiEffectsConfig.DEFAULT_STYLES + (
                AiEffect.SEARCH_FIELD to AiEffectStyle(
                    enabled = false,
                    customColors = listOf(0xFF0000FF.toInt(), 0x7F00FF00),
                    brightness = 0.5f,
                    speed = 2.5f,
                    width = 1.75f,
                    durationMs = 7_000,
                    timed = true,
                    behind = true,
                    count = 11,
                )
            ),
            customLoaderStyle = AiLoaderStyle.RING,
            customCurtainEdge = AiCurtainEdge.SIDES,
            savedPalettes = listOf(listOf(1, 2, 3, 4, 5), listOf(0xFF102030.toInt())),
        )
        assertEquals(config, AiEffectsConfig.parse(JSONObject(config.toJson().toString())))
    }

    @Test
    fun savedPalettesKeepTheirLimits() {
        var config = AiEffectsConfig()
        // At most 5 colors each, the same palette once.
        config = config.withSavedPalette(listOf(1, 2, 3, 4, 5, 6)).withSavedPalette(listOf(1, 2, 3, 4, 5))
        assertEquals(listOf(listOf(1, 2, 3, 4, 5)), config.savedPalettes)
        for (i in 10..30) config = config.withSavedPalette(listOf(i))
        assertEquals(AiEffectsConfig.MAX_SAVED_PALETTES, config.savedPalettes.size)
        assertFalse(config.canSavePalette)
        config = config.withoutSavedPalette(0)
        assertEquals(listOf(10), config.savedPalettes.first())
        assertTrue(config.canSavePalette)
        // Resetting Custom keeps the palettes and puts the curtain back on top.
        val reset = config.copy(customCurtainEdge = AiCurtainEdge.BOTTOM, customLoaderStyle = AiLoaderStyle.DOTS).resetCustom()
        assertEquals(config.savedPalettes, reset.savedPalettes)
        assertEquals(AiCurtainEdge.TOP, reset.customCurtainEdge)
        assertEquals(AiLoaderStyle.ORB, reset.customLoaderStyle)
        // A hand-edited file: too many palettes, too many colors, duplicates and junk.
        val parsed = AiEffectsConfig.parse(
            JSONObject(
                """{"palettes":[[1,2,3,4,5,6,7],[1,2,3,4,5],[],"x",[9],[10],[11],[12],[13],[14],[15],[16],[17],[18]]}""",
            ),
        )
        assertEquals(listOf(1, 2, 3, 4, 5), parsed.savedPalettes.first())
        assertEquals(AiEffectsConfig.MAX_SAVED_PALETTES, parsed.savedPalettes.size)
        assertEquals(parsed.savedPalettes.distinct(), parsed.savedPalettes)
    }

    @Test
    fun missingOrBadValuesFallBack() {
        assertEquals(AiEffectsConfig(), AiEffectsConfig.parse(null))
        assertEquals(AiEffectsConfig(), AiEffectsConfig.parse(JSONObject("""{"preset":"NOPE","loaderStyle":7}""")))
        val parsed = AiEffectsConfig.parse(
            JSONObject(
                """{"preset":"CUSTOM","custom":{"RESULTS_EDGE":{"brightness":99,"speed":0,"durationMs":1,"colors":[1,2,3,4,5,6]},
                   "SEARCH_FIELD":{"durationMs":4000}}}""",
            ),
        )
        val edge = parsed.style(AiEffect.RESULTS_EDGE)
        assertEquals(AiEffectStyle.MAX_BRIGHTNESS, edge.brightness)
        assertEquals(AiEffectStyle.MIN_SPEED, edge.speed)
        assertEquals(AiEffectStyle.MIN_DURATION_MS, edge.durationMs)
        assertEquals(AiEffectStyle.MAX_COLORS, edge.customColors!!.size)
        // A lasting effect keeps its duration for when it is set to timed.
        assertEquals(4_000, parsed.style(AiEffect.SEARCH_FIELD).durationMs)
        assertFalse(parsed.isTimed(AiEffect.SEARCH_FIELD))
        assertTrue(parsed.isTimed(AiEffect.RESULTS_EDGE))
        // Older saves wrote 0 for lasting effects.
        val old = AiEffectsConfig.parse(JSONObject("""{"preset":"CUSTOM","custom":{"LOADER":{"durationMs":0}}}"""))
        assertEquals(AiEffectStyle.DEFAULT_DURATION_MS, old.style(AiEffect.LOADER).durationMs)
        assertEquals(AiCurtainEdge.TOP, old.curtainEdge)
        assertEquals(AiEffectsConfig.defaultStyle(AiEffect.LOADER), parsed.style(AiEffect.LOADER))
    }

    @Test
    fun theFirstLoaderBecomesTheOrb() {
        val parsed = AiEffectsConfig.parse(JSONObject("""{"preset":"CUSTOM","loaderStyle":"SPARK"}"""))
        assertEquals(AiLoaderStyle.ORB, parsed.loaderStyle)
    }

    @Test
    fun readyPresetsIgnoreCustomChanges() {
        val custom = AiEffectsConfig().withCustom(AiEffect.LOADER) { it.copy(enabled = false) }
            .copy(customLoaderStyle = AiLoaderStyle.DOTS, customBase = AiPalette.SYSTEM)
        val system = custom.copy(preset = AiEffectsPreset.SYSTEM)
        assertEquals(AiPalette.SYSTEM, system.palette)
        assertEquals(AiEffectsConfig.defaultStyle(AiEffect.LOADER), system.style(AiEffect.LOADER))
        assertEquals(AiLoaderStyle.ORB, system.loaderStyle)
        assertNull(system.style(AiEffect.LOADER).customColors)

        val active = custom.copy(preset = AiEffectsPreset.CUSTOM)
        assertFalse(active.style(AiEffect.LOADER).enabled)
        assertEquals(AiLoaderStyle.DOTS, active.loaderStyle)
        assertEquals(AiPalette.SYSTEM, active.palette)
        assertEquals(AiPalette.MULTI_COLOR, custom.copy(preset = AiEffectsPreset.MULTI_COLOR).palette)
    }

    @Test
    fun onlyRingFieldLoaderAndFoldersAreOnByDefault() {
        val on = AiEffect.entries.filter { AiEffectsConfig().style(it).enabled }.toSet()
        org.junit.Assert.assertEquals(
            setOf(AiEffect.SETTINGS_RING, AiEffect.SEARCH_FIELD, AiEffect.LOADER, AiEffect.FOLDER_GLINT),
            on,
        )
    }
}
