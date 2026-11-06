package dev.ander.alexet.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderDiskStateTest {

    private val saved = HashMap<String, String>()
    private val state = FolderDiskState(object : FolderDiskState.Store {
        override fun get(key: String) = saved[key]
        override fun put(key: String, value: String) {
            saved[key] = value
        }
    })

    private val camera = "/storage/emulated/0/DCIM/Camera"
    private val now = 1_800_000_000_000L

    /** A folder date (ns) [agoMs] before [now]. */
    private fun dated(agoMs: Long) = (now - agoMs) * 1_000_000L

    @Test
    fun aFolderIsListedOnceUntilItsDateMoves() {
        val date = dated(60_000L)
        assertTrue(state.needsListing(camera, date, nowMs = now))
        state.listed(camera, date, nowMs = now)
        assertFalse(state.needsListing(camera, date, nowMs = now + 1_000L))
        // A file came or went.
        assertTrue(state.needsListing(camera, dated(10L), nowMs = now + 1_000L))
    }

    @Test
    fun anOldListingTheGestureAndAClockGoingBackListAgain() {
        val date = dated(60_000L)
        state.listed(camera, date, nowMs = now)
        assertTrue(state.needsListing(camera, date, nowMs = now + FolderDiskState.FULL_LIST_MS))
        assertTrue(state.needsListing(camera, date, nowMs = now + 1_000L, force = true))
        assertTrue(state.needsListing(camera, date, nowMs = now - 1_000L))
    }

    @Test
    fun aListingRightAfterAChangeIsNotTrusted() {
        // The folder changed half a second before it was listed: a change in that moment (or a
        // card keeping dates to two seconds) may not show in its date.
        val date = dated(500L)
        state.listed(camera, date, nowMs = now)
        assertTrue(state.needsListing(camera, date, nowMs = now + 5_000L))
    }

    @Test
    fun aDamagedEntryListsAgain() {
        saved[camera] = "garbage"
        assertTrue(state.needsListing(camera, dated(60_000L), nowMs = now))
        saved[camera] = "7:"
        assertTrue(state.needsListing(camera, 7L, nowMs = now))
    }
}
