package dev.ander.alexet.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaStoreIdentityTest {

    private val vol = "external_primary"

    @Test
    fun sameDatabaseIsKept() {
        assertNull(MediaIndexLogic.storeReplacedReason(mapOf(vol to "1300:abc"), mapOf(vol to "1300:abc"), mapOf(vol to 50L), mapOf(vol to 60L)))
    }

    @Test
    fun newDatabaseUuidDiscards() {
        assertNotNull(MediaIndexLogic.storeReplacedReason(mapOf(vol to "1300:abc"), mapOf(vol to "1300:xyz"), mapOf(vol to 50L), mapOf(vol to 60L)))
    }

    @Test
    fun schemaUpgradeAloneKeeps() {
        // System update bumps the schema; rows and ids stay.
        assertNull(MediaIndexLogic.storeReplacedReason(mapOf(vol to "1300:abc"), mapOf(vol to "1400:abc"), mapOf(vol to 50L), mapOf(vol to 60L)))
    }

    @Test
    fun generationGoingBackDiscards() {
        // Index from before this check (no stored version) on a database that started over.
        assertNotNull(MediaIndexLogic.storeReplacedReason(mapOf(vol to null), mapOf(vol to "1300:abc"), mapOf(vol to 90_000L), mapOf(vol to 1_200L)))
    }

    @Test
    fun firstRunAndMissingVolumesAreNotCompared() {
        assertNull(MediaIndexLogic.storeReplacedReason(mapOf(vol to null), mapOf(vol to "1300:abc"), mapOf(vol to 0L), mapOf(vol to 10L)))
        assertNull(MediaIndexLogic.storeReplacedReason(mapOf("sdcard" to "1:a"), mapOf(vol to "1300:abc"), mapOf("sdcard" to 99L), mapOf(vol to 10L)))
    }
}
