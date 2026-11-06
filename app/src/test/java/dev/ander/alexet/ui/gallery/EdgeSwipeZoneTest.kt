package dev.ander.alexet.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EdgeSwipeZoneTest {

    // A 2400 px tall full-screen viewer: 100 px status bar, 60 px gesture bar, 100 px bands.
    private fun zone(y: Float, areaTop: Float = 0f, areaBottom: Float = 2400f) = edgeSwipeZone(
        yInWindow = y,
        areaTop = areaTop,
        areaBottom = areaBottom,
        windowHeight = 2400f,
        topSystem = 100f,
        bottomSystem = 60f,
        band = 100f,
    )

    @Test
    fun theBandsStartRightPastTheSystemAreas() {
        // The system's own areas stay the system's.
        assertNull(zone(50f))
        assertNull(zone(2380f))
        // Right past them: the swipes.
        assertEquals(EdgeSwipe.FROM_TOP, zone(100f))
        assertEquals(EdgeSwipe.FROM_TOP, zone(199f))
        assertEquals(EdgeSwipe.FROM_BOTTOM, zone(2340f))
        assertEquals(EdgeSwipe.FROM_BOTTOM, zone(2241f))
        // Further in: the photo's.
        assertNull(zone(200f))
        assertNull(zone(1200f))
        assertNull(zone(2240f))
    }

    @Test
    fun aViewerNotReachingTheEdgesUsesItsOwnEdges() {
        // A pane from 300 px down to 2000 px: the bands follow it.
        assertEquals(EdgeSwipe.FROM_TOP, zone(350f, areaTop = 300f, areaBottom = 2000f))
        assertNull(zone(150f, areaTop = 300f, areaBottom = 2000f))
        assertEquals(EdgeSwipe.FROM_BOTTOM, zone(1950f, areaTop = 300f, areaBottom = 2000f))
        assertNull(zone(2300f, areaTop = 300f, areaBottom = 2000f))
    }

    @Test
    fun tooShortForTwoBandsHasNone() {
        assertNull(zone(150f, areaTop = 100f, areaBottom = 250f))
    }
}
