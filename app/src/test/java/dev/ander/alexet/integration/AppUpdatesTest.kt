package dev.ander.alexet.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {

    @Test
    fun versionsCompareByNumbersNotText() {
        assertTrue(AppUpdates.isNewer("1.0.10", "1.0.9"))
        assertTrue(AppUpdates.isNewer("1.1.0", "1.0.9"))
        assertTrue(AppUpdates.isNewer("1.0.2.1", "1.0.2"))
        assertFalse(AppUpdates.isNewer("1.0.2", "1.0.2"))
        assertFalse(AppUpdates.isNewer("1.0.1", "1.0.2"))
        assertFalse(AppUpdates.isNewer("1.0", "1.0.0"))
    }

    private val body = """
        First public release of Alexet.

        **Files**
        - `Alexet-release.apk` — the app.

        Guide: see [How to install](https://github.com/Anderinfera/Alexet#how-to-install).

        ---

        Pierwsze publiczne wydanie Alexeta.

        **Pliki**
        - `Alexet-release.apk` — aplikacja.
    """.trimIndent()

    @Test
    fun notesInTheAppsLanguageWithoutMarkdown() {
        assertEquals(
            "First public release of Alexet.\n\nFiles\n• Alexet-release.apk — the app.\n\nGuide: see How to install.",
            AppUpdates.notesFor(body, "en"),
        )
        assertEquals(
            "Pierwsze publiczne wydanie Alexeta.\n\nPliki\n• Alexet-release.apk — aplikacja.",
            AppUpdates.notesFor(body, "pl"),
        )
    }

    @Test
    fun notesWithOneLanguageAreShownAsTheyAre() {
        assertEquals("# Changes", AppUpdates.notesFor("# Changes", "pl").let { "# $it" })
        assertEquals("• One", AppUpdates.notesFor("- One", "pl"))
    }
}
