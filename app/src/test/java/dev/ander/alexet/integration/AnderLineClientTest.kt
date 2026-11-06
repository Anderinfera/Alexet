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
}
