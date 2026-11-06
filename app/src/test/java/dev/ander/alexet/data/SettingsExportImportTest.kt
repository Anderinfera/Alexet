package dev.ander.alexet.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.ander.alexet.integration.AnderLineConfig
import dev.ander.alexet.integration.PlayerShortcut
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export -> import round trip of every setting, and persistence of the imported result
 * (a new store over the same preferences, i.e. an app restart).
 */
class SettingsExportImportTest {

    /** Minimal in-memory SharedPreferences (what SettingsStore uses). */
    private class MemoryPrefs : SharedPreferences {
        val data = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                synchronized(data) {
                    if (clear) data.clear()
                    removed.forEach { data.remove(it) }
                    data.putAll(pending)
                }
            }
        }
    }

    private class FakeContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    /** Every exported field set to a value different from its default. */
    private fun nonDefaultSettings() = AppSettings(
        contentViewMode = ContentViewMode.CLICK_DRAG,
        panelSplitPercent = 55,
        multiFolderMemory = MultiFolderMemory.FRESH,
        expandColumns = 4,
        expandRows = 3,
        gridColumns = 5,
        foldableFileColumns = 6,
        showFileNames = true,
        videoDurationFormat = VideoDurationFormat.HOURS_MINUTES_SECONDS,
        fileSelectMode = FileSelectMode.LINEAR,
        searchFilesFullScreen = false,
        sort = SortSpec(SortField.NAME, SortOrder.ASCENDING),
        folderSorts = mapOf(
            111L to SortSpec(SortField.SIZE, SortOrder.DESCENDING),
            -5L to SortSpec(SortField.DATE_MODIFIED, SortOrder.ASCENDING),
        ),
        hiddenMediaIds = setOf(1L, 2L, 3L),
        hiddenAlbumIds = setOf(40L, 41L),
        showHidden = true,
        albumOrder = listOf(9L, 7L, 8L),
        videoDoubleClickToGallery = true,
        nomediaFolderPaths = listOf("Download/Secret/", "Pictures/X/"),
        favoriteMediaIds = listOf(30L, 10L, 20L),
        hideFavoritesFolder = true,
        barActionSlots = listOf(SelectionAction.SHARE, null, SelectionAction.DELETE, null, SelectionAction.INFO, null),
        menuActionOrder = SelectionAction.entries.reversed(),
        hiddenSelectionActions = setOf(SelectionAction.STRIP_EXIF, SelectionAction.SET_AS),
        rememberFolderScrollAcrossSessions = true,
        newContentScrollsToTop = true,
        savedFolderScrolls = mapOf(111L to FolderScrollPos(12, 34)),
        autoplayGifs = false,
        playerConfig = AnderLineConfig(
            useMaterialYouControls = false,
            visibleShortcuts = setOf(PlayerShortcut.LOOP, PlayerShortcut.SHUFFLE),
            seekIncrement = 25,
            controllerAutoHideTimeout = 9,
            useLongPressControls = false,
            longPressControlsSpeed = 3.0f,
            enableVolumeSwipeGesture = false,
            enableBrightnessSwipeGesture = false,
            playerScreenOrientation = "LANDSCAPE",
            disableAutoPipOnHome = false,
            autoBackgroundPlay = false,
            showPlayerOnLaunch = false,
            themeConfig = "OFF",
            autoResumeAfterSettings = false,
            subtitleTextBold = true,
            enableDoubleTapSeek = false,
            sponsorBlockEnabled = false,
            sponsorBlockAutoSkip = setOf(SponsorBlockCategory.SELF_PROMO, SponsorBlockCategory.OUTRO),
        ),
        showNavArrows = true,
        anonymousFolderIds = setOf(77L),
        randomizeFileNamesInPrivateFolders = false,
        stripExifInPrivateFolders = false,
        stripExifOnShare = true,
        operationsFollowSelectionOrder = true,
        folderContentOrders = mapOf(111L to listOf(5L, 4L, 6L)),
        customWallpaperFoldedWidth = 1080,
        customWallpaperFoldedHeight = 2092,
        customWallpaperUnfoldedWidth = 2208,
        customWallpaperUnfoldedHeight = 1840,
        scrollerOnRight = true,
        scrollerEnabled = false,
        scrollerSmooth = false,
        highResThumbnails = true,
        lightMode = true,
        autoFoldSplitEnabled = false,
        foldSplitAngleThreshold = 33f,
        newpipeFolderRelativePath = "Gallery/Downloaded-NP",
        smartSearchEnabled = true,
        aiEffects = AiEffectsConfig(
            preset = AiEffectsPreset.CUSTOM,
            customBase = AiPalette.SYSTEM,
            custom = AiEffectsConfig.DEFAULT_STYLES + mapOf(
                AiEffect.RESULTS_EDGE to AiEffectStyle(
                    enabled = false,
                    customColors = listOf(0xFF112233.toInt(), 0x80FFFFFF.toInt(), 1, 2, 3),
                    brightness = 1.3f,
                    speed = 2f,
                    width = 0.5f,
                    durationMs = 8_000,
                    behind = true,
                ),
                AiEffect.RESULTS_NEXUS to AiEffectStyle(timed = true, durationMs = 3_500, count = 14, speed = 0.5f),
                AiEffect.FOLDER_GLINT to AiEffectStyle(width = 2f, timed = true),
                AiEffect.SEARCH_BACKGROUND to AiEffectStyle(behind = true, brightness = 0.4f),
            ),
            customLoaderStyle = AiLoaderStyle.DOTS,
            customCurtainEdge = AiCurtainEdge.SIDES,
            savedPalettes = listOf(listOf(0xFF445566.toInt(), 7), listOf(1, 2, 3, 4, 5)),
        ),
        hiddenCover = HiddenCoverConfig(
            style = HiddenCoverStyle.STRIPES,
            pixels = 12,
            blur = 20,
            imagePath = "/data/x/hidden_cover.jpg",
            imageVersion = 42L,
            color = 0xFF123456.toInt(),
            stripeCount = 9,
            stripeWidth = 30,
            angle = 120,
            checkerCells = 4,
            colorA = 0xFFAA0000.toInt(),
            colorB = 0xFF00AA00.toInt(),
            random = HiddenCoverRandom.ALL,
            colorLimit = HiddenCoverColorLimit.PALETTE,
            hueFrom = 300,
            hueTo = 60,
            palette = listOf(0xFF111111.toInt(), 0xFF222222.toInt()),
        ),
        smartIndexScope = SmartIndexScope.ALL_EXCEPT,
        smartIndexIncludedFolderIds = setOf(111L, -5L),
        smartIndexExcludedFolderIds = setOf(40L),
        pendingMedia = PendingMediaRefs(
            hidden = setOf("download/late.jpg"),
            favorites = listOf("dcim/camera/late.mp4"),
            contentOrders = mapOf(111L to listOf("pictures/late.png")),
        ),
    )

    /** Same phone: every referenced file has a path and keeps its id. */
    private val samePhonePaths: (Long) -> String? = { id -> "dir/file$id.jpg" }
    private val samePhoneIds: (String) -> Long? = { path ->
        path.removePrefix("dir/file").removeSuffix(".jpg").toLongOrNull()
    }

    /** Session state is deliberately not exported and must survive an import. */
    private fun withoutSession(s: AppSettings) = s.copy(
        lastOpenedFolderId = AlbumIds.NONE,
        rememberedOpenFolders = emptyList(),
    )

    @Test
    fun everyExportedSettingSurvivesExportImport() {
        val source = SettingsStore(FakeContext(MemoryPrefs()))
        val expected = nonDefaultSettings()
        source.update { expected }
        val json = source.exportJson(pathOf = samePhonePaths)

        val target = SettingsStore(FakeContext(MemoryPrefs()))
        assertTrue(target.importJson(json, idForPath = samePhoneIds))
        val imported = target.settings.value

        // Field by field first, so a failure names the setting.
        AppSettings::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                field.isAccessible = true
                assertEquals("setting ${field.name}", field.get(withoutSession(expected)), field.get(withoutSession(imported)))
            }
        assertEquals(withoutSession(expected), withoutSession(imported))
    }

    @Test
    fun importedSettingsArePersisted() {
        val prefs = MemoryPrefs()
        val store = SettingsStore(FakeContext(prefs))
        val source = SettingsStore(FakeContext(MemoryPrefs()))
        source.update { nonDefaultSettings() }
        assertTrue(store.importJson(source.exportJson(pathOf = samePhonePaths), idForPath = samePhoneIds))

        // Writes are debounced on a background dispatcher.
        val deadline = System.currentTimeMillis() + 5_000
        var restarted: AppSettings
        do {
            Thread.sleep(100)
            restarted = SettingsStore(FakeContext(prefs)).settings.value
        } while (withoutSession(restarted) != withoutSession(nonDefaultSettings()) && System.currentTimeMillis() < deadline)
        AppSettings::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                field.isAccessible = true
                assertEquals("persisted ${field.name}", field.get(withoutSession(nonDefaultSettings())), field.get(withoutSession(restarted)))
            }
    }

    @Test
    fun missingPlayerAndAiEffectKeysKeepCurrentValues() {
        val store = SettingsStore(FakeContext(MemoryPrefs()))
        store.update { nonDefaultSettings() }
        val json = JSONObject()
            .put("player", JSONObject().put("seekIncrement", 15))
            .put("aiEffects", JSONObject().put("preset", "SYSTEM"))
            .toString()
        assertTrue(store.importJson(json))
        val s = store.settings.value
        assertEquals(nonDefaultSettings().playerConfig.copy(seekIncrement = 15), s.playerConfig)
        assertEquals(nonDefaultSettings().aiEffects.copy(preset = AiEffectsPreset.SYSTEM), s.aiEffects)
        assertTrue(s.lightMode)
        assertTrue(s.highResThumbnails)
    }

    @Test
    fun importKeepsSessionStateAndRejectsBadInput() {
        val store = SettingsStore(FakeContext(MemoryPrefs()))
        store.update { it.copy(lastOpenedFolderId = 123L, rememberedOpenFolders = listOf(1L, 2L)) }
        assertTrue(store.importJson(JSONObject().put("gridColumns", 7).toString()))
        assertEquals(123L, store.settings.value.lastOpenedFolderId)
        assertEquals(listOf(1L, 2L), store.settings.value.rememberedOpenFolders)
        assertEquals(7, store.settings.value.gridColumns)

        assertFalse(store.importJson(""))
        assertFalse(store.importJson("not json"))
        assertFalse(store.importJson("{}"))
        assertFalse(store.importJson(JSONObject().put("schemaVersion", 2).put("gridColumns", 9).toString()))
        assertEquals(7, store.settings.value.gridColumns)
    }

    @Test
    fun emptyCollectionsSurviveExportImport() {
        val source = SettingsStore(FakeContext(MemoryPrefs()))
        source.update {
            nonDefaultSettings().copy(
                barActionSlots = List(6) { null },
                hiddenSelectionActions = emptySet(),
                playerConfig = nonDefaultSettings().playerConfig.copy(visibleShortcuts = emptySet()),
            )
        }
        val target = SettingsStore(FakeContext(MemoryPrefs()))
        assertTrue(target.importJson(source.exportJson(pathOf = samePhonePaths), idForPath = samePhoneIds))
        assertEquals("barActionSlots", List<SelectionAction?>(6) { null }, target.settings.value.barActionSlots)
        assertEquals("hiddenSelectionActions", emptySet<SelectionAction>(), target.settings.value.hiddenSelectionActions)
        assertEquals("visibleShortcuts", emptySet<PlayerShortcut>(), target.settings.value.playerConfig.visibleShortcuts)
    }

    @Test
    fun newPhoneMatchesFilesByPathAndAttachesLateOnesLater() {
        val old = SettingsStore(FakeContext(MemoryPrefs()))
        old.update {
            it.copy(
                hiddenMediaIds = setOf(10L, 11L, -77L, 99L),
                favoriteMediaIds = listOf(12L, 10L),
                folderContentOrders = mapOf(500L to listOf(13L, 12L, 11L)),
            )
        }
        // Old phone: 99 is a hidden file that no longer exists (no path); -77 a .nomedia file.
        val oldPaths = mapOf(10L to "DCIM/Camera/a.jpg", 11L to "DCIM/Camera/b.jpg", 12L to "Download/c.mp4", 13L to "Pictures/d.png")
        val json = old.exportJson(pathOf = { oldPaths[it] })

        // New phone: same files under other ids, d.png not copied yet.
        val newIds = mutableMapOf("dcim/camera/a.jpg" to 1010L, "dcim/camera/b.jpg" to 1011L, "download/c.mp4" to 1012L)
        val store = SettingsStore(FakeContext(MemoryPrefs()))
        assertTrue(store.importJson(json, idForPath = { newIds[it] }))
        val s = store.settings.value
        assertEquals(setOf(1010L, 1011L, -77L), s.hiddenMediaIds)
        assertEquals(listOf(1012L, 1010L), s.favoriteMediaIds)
        assertEquals(mapOf(500L to listOf(1012L, 1011L)), s.folderContentOrders)
        assertEquals(mapOf(500L to listOf("pictures/d.png")), s.pendingMedia.contentOrders)

        // d.png arrives: attached to the folder order, nothing left pending.
        newIds["pictures/d.png"] = 1013L
        val resolved = MediaRefs.resolvePending(s) { newIds[it] }!!
        assertEquals(listOf(1012L, 1011L, 1013L), resolved.folderContentOrders[500L])
        assertTrue(resolved.pendingMedia.isEmpty)
        assertEquals(null, MediaRefs.resolvePending(resolved) { newIds[it] })
    }

    @Test
    fun olderExportWithoutPathsKeepsIds() {
        val json = JSONObject()
            .put("hiddenMediaIds", org.json.JSONArray(listOf(5L, 6L)))
            .put("favoriteMediaIds", org.json.JSONArray(listOf(7L)))
            .toString()
        val store = SettingsStore(FakeContext(MemoryPrefs()))
        assertTrue(store.importJson(json, idForPath = { null }))
        assertEquals(setOf(5L, 6L), store.settings.value.hiddenMediaIds)
        assertEquals(listOf(7L), store.settings.value.favoriteMediaIds)
        assertTrue(store.settings.value.pendingMedia.isEmpty)
    }

    @Test
    fun securePathsAreExportedAndHandedBack() {
        val source = SettingsStore(FakeContext(MemoryPrefs()))
        val json = source.exportJson(securePaths = listOf("Pictures/Prywatne", "Download/X"))
        var received: List<String>? = null
        assertTrue(SettingsStore(FakeContext(MemoryPrefs())).importJson(json, onSecurePaths = { received = it }))
        assertEquals(listOf("Pictures/Prywatne", "Download/X"), received)
    }

    @Test
    fun portablePathsIgnoreStorageRootAndCase() {
        assertEquals("Pictures/Prywatne", MediaRefs.portableDirPath("/storage/emulated/0/Pictures/Prywatne"))
        assertEquals("Download/a", MediaRefs.portableDirPath("/sdcard/Download/a/"))
        val m = GalleryMedia(
            id = 1L, uri = android.net.DummyUri(), displayName = "IMG.JPG", mimeType = "image/jpeg",
            dateTakenMs = 0L, dateModifiedMs = 0L, dateAddedMs = 0L, sizeBytes = 0L, width = 0, height = 0,
            durationMs = 0L, bucketId = 0L, bucketName = "", relativePath = "DCIM/Camera/",
        )
        assertEquals("dcim/camera/img.jpg", MediaRefs.portablePath(m))
        val hidden = m.copy(relativePath = "", absolutePath = "/storage/emulated/0/Download/Secret/x.png")
        assertEquals("download/secret/x.png", MediaRefs.portablePath(hidden))
    }
}
