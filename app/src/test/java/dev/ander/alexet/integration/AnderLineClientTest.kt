package dev.ander.alexet.integration

import dev.ander.alexet.data.SponsorBlockCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnderLineClientTest {

    @Test
    fun testDefaultConfig() {
        val config = AnderLineConfig()
        assertTrue(config.useMaterialYouControls)
        assertEquals(10, config.seekIncrement)
        assertEquals(4, config.controllerAutoHideTimeout)
        assertTrue(config.visibleShortcuts.contains(PlayerShortcut.PLAYBACK_SPEED))
        assertTrue(config.visibleShortcuts.contains(PlayerShortcut.SCREEN_ROTATION))
        assertTrue(config.visibleShortcuts.contains(PlayerShortcut.AUDIO_TRACK))
        assertTrue(config.visibleShortcuts.contains(PlayerShortcut.LOOP))
        assertTrue(config.visibleShortcuts.contains(PlayerShortcut.LOCK_CONTROLS))
    }

    @Test
    fun testPlayerShortcut_fromName() {
        assertEquals(PlayerShortcut.PLAYBACK_SPEED, PlayerShortcut.fromName("PLAYBACK_SPEED"))
        assertEquals(PlayerShortcut.SCREEN_ROTATION, PlayerShortcut.fromName("SCREEN_ROTATION"))
        assertEquals(PlayerShortcut.AUDIO_TRACK, PlayerShortcut.fromName("AUDIO_TRACK"))
        assertEquals(PlayerShortcut.LOOP, PlayerShortcut.fromName("LOOP"))
        assertEquals(null, PlayerShortcut.fromName("NON_EXISTENT_SHORTCUT"))
    }

    @Test
    fun testSponsorBlock_defaultsWhenConfigHasNoEntries() {
        val config = AnderLineClient.parseConfig("""{"player":{"seekIncrement":15}}""")

        assertTrue(config.sponsorBlockEnabled)
        assertEquals(setOf(SponsorBlockCategory.SPONSOR), config.sponsorBlockAutoSkip)
    }

    @Test
    fun testSponsorBlock_readsChoicesAndKeepsAnEmptyList() {
        val chosen = AnderLineClient.parseConfig(
            """{"player":{"sponsorBlockEnabled":false,"sponsorBlockAutoSkip":["selfpromo","outro","unknown"]}}""",
        )
        val none = AnderLineClient.parseConfig("""{"player":{"sponsorBlockAutoSkip":[]}}""")

        assertFalse(chosen.sponsorBlockEnabled)
        assertEquals(setOf(SponsorBlockCategory.SELF_PROMO, SponsorBlockCategory.OUTRO), chosen.sponsorBlockAutoSkip)
        assertEquals(emptySet<SponsorBlockCategory>(), none.sponsorBlockAutoSkip)
    }

    @Test
    fun testButtonsPositionAndRememberedState_roundTrip() {
        val saved = AnderLineConfig(
            controlButtonsX = 0.2f,
            controlButtonsY = 0.8f,
            rememberedSpeed = 1.5f,
            rememberedLoop = true,
            rememberedContentScale = "ZOOM",
        )
        val read = AnderLineClient.parseConfig(AnderLineClient.configToJson(saved).toString())

        assertEquals(0.2f, read.controlButtonsX, 0.0001f)
        assertEquals(0.8f, read.controlButtonsY, 0.0001f)
        assertEquals(1.5f, read.rememberedSpeed, 0.0001f)
        assertTrue(read.rememberedLoop)
        assertEquals("ZOOM", read.rememberedContentScale)
    }

    @Test
    fun testButtonsPosition_missingKeepsFallbackAndOutOfRangeIsCoerced() {
        val fallback = AnderLineConfig(controlButtonsX = 0.3f, controlButtonsY = 0.6f)
        val missing = AnderLineClient.parseConfig("""{"player":{"seekIncrement":15}}""", fallback)
        val outside = AnderLineClient.parseConfig("""{"player":{"controlButtonsX":-1.0,"controlButtonsY":2.0}}""")

        assertEquals(0.3f, missing.controlButtonsX, 0.0001f)
        assertEquals(0.6f, missing.controlButtonsY, 0.0001f)
        assertEquals(0.05f, outside.controlButtonsX, 0.0001f)
        assertEquals(0.95f, outside.controlButtonsY, 0.0001f)
    }

    @Test
    fun testVisibleShortcuts_keepTheirOrder() {
        val order = linkedSetOf(PlayerShortcut.LOOP, PlayerShortcut.PLAYBACK_SPEED, PlayerShortcut.SCREEN_ROTATION)
        val read = AnderLineClient.parseConfig(AnderLineClient.configToJson(AnderLineConfig(visibleShortcuts = order)).toString())

        assertEquals(order.toList(), read.visibleShortcuts.toList())
    }

    @Test
    fun backgroundPlaySwitchIsAddedOnceToAnOldList() {
        // Saved before the switch existed: it is added at the end.
        val old = """{"player":{"visibleShortcuts":["PLAYBACK_SPEED","LOOP"]}}"""
        assertEquals(
            listOf(PlayerShortcut.PLAYBACK_SPEED, PlayerShortcut.LOOP, PlayerShortcut.PLAY_IN_BACKGROUND),
            AnderLineClient.parseConfig(old).visibleShortcuts.toList(),
        )
        // Saved after: removing it is the user's choice and stays.
        val withoutIt = AnderLineConfig(visibleShortcuts = linkedSetOf(PlayerShortcut.PLAYBACK_SPEED, PlayerShortcut.LOOP))
        val read = AnderLineClient.parseConfig(AnderLineClient.configToJson(withoutIt).toString())
        assertFalse(PlayerShortcut.PLAY_IN_BACKGROUND in read.visibleShortcuts)
    }
}
