package dev.ander.alexet.integration

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.ander.alexet.data.SponsorBlockCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class PlayerShortcut {
    PLAYBACK_SPEED,
    SCREEN_ROTATION,
    AUDIO_TRACK,
    LOOP,
    PLAYLIST,
    SUBTITLE_TRACK,
    VIDEO_CONTENT_SCALE,
    PLAY_IN_BACKGROUND,
    SHUFFLE,
    LOCK_CONTROLS,
    PICTURE_IN_PICTURE;

    companion object {
        val DEFAULT_VISIBLE = setOf(
            PLAYBACK_SPEED,
            SCREEN_ROTATION,
            AUDIO_TRACK,
            LOOP,
            LOCK_CONTROLS,
            VIDEO_CONTENT_SCALE,
        )

        fun fromName(name: String): PlayerShortcut? = try {
            valueOf(name)
        } catch (_: Exception) {
            null
        }
    }
}

data class AnderLineConfig(
    val useMaterialYouControls: Boolean = true,
    val visibleShortcuts: Set<PlayerShortcut> = PlayerShortcut.DEFAULT_VISIBLE,
    val seekIncrement: Int = 10,
    val controllerAutoHideTimeout: Int = 4,
    val useLongPressControls: Boolean = true,
    val longPressControlsSpeed: Float = 2.0f,
    val enableVolumeSwipeGesture: Boolean = true,
    val enableBrightnessSwipeGesture: Boolean = true,
    val playerScreenOrientation: String = "PORTRAIT",
    val disableAutoPipOnHome: Boolean = true,
    /** Alexet's player: a video goes on playing (sound only) after the app is left or the screen goes off. */
    val autoBackgroundPlay: Boolean = true,
    /** Alexet's player: a button that shrinks the video into a small window over other apps. */
    val pipButton: Boolean = true,
    val showPlayerOnLaunch: Boolean = true,
    val themeConfig: String = "ON",
    val autoResumeAfterSettings: Boolean = true,
    val subtitleTextBold: Boolean = false,
    val enableDoubleTapSeek: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val sponsorBlockAutoSkip: Set<SponsorBlockCategory> = SponsorBlockCategory.DEFAULT_AUTO_SKIP,
)

object AnderLineClient {
    private val authorities = listOf(
        "dev.ander.anderyer.anderline",
        "dev.ander.anderyer.debug.anderline",
        "dev.anilbeesetti.nextplayer.anderline",
        "dev.anilbeesetti.nextplayer.debug.anderline",
        "dev.anilbeesetti.nextplayer.release.anderline",
    )

    fun fetchLocalConfig(context: Context): AnderLineConfig {
        val localStr = context.getSharedPreferences("alexet_settings", Context.MODE_PRIVATE)
            .getString("player_config_json", null)
        return if (!localStr.isNullOrBlank()) parseConfig(localStr) else AnderLineConfig()
    }

    fun fetchLiveConfig(context: Context): AnderLineConfig? {
        for (authority in authorities) {
            if (context.packageManager.resolveContentProvider(authority, 0) == null) continue
            val uri = Uri.parse("content://$authority/player_config")
            try {
                val bundle = context.contentResolver.call(uri, "getPlayerConfig", null, null)
                val jsonStr = bundle?.getString("config")
                if (!jsonStr.isNullOrBlank()) {
                    return parseConfig(jsonStr)
                }

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val colIdx = cursor.getColumnIndex("config")
                        if (colIdx >= 0) {
                            val str = cursor.getString(colIdx)
                            if (!str.isNullOrBlank()) {
                                return parseConfig(str)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Provider not available for this authority, try next
            }
        }
        return null
    }

    fun fetchConfig(context: Context): AnderLineConfig {
        return fetchLiveConfig(context) ?: fetchLocalConfig(context)
    }

    /**
     * The config as {"application": {...}, "player": {...}}: one writer for the saved settings and
     * the export, so a new field cannot go missing from one of them.
     */
    fun configToJson(config: AnderLineConfig): JSONObject = JSONObject().apply {
        put("application", JSONObject().apply {
            put("themeConfig", config.themeConfig)
        })
        put("player", JSONObject().apply {
            put("playerScreenOrientation", config.playerScreenOrientation)
            put("disableAutoPipOnHome", config.disableAutoPipOnHome)
            put("autoBackgroundPlay", config.autoBackgroundPlay)
            put("pipButton", config.pipButton)
            put("showPlayerOnLaunch", config.showPlayerOnLaunch)
            put("enableVolumeSwipeGesture", config.enableVolumeSwipeGesture)
            put("enableBrightnessSwipeGesture", config.enableBrightnessSwipeGesture)
            put("useLongPressControls", config.useLongPressControls)
            put("useMaterialYouControls", config.useMaterialYouControls)
            put("autoResumeAfterSettings", config.autoResumeAfterSettings)
            put("subtitleTextBold", config.subtitleTextBold)
            put("seekIncrement", config.seekIncrement)
            put("controllerAutoHideTimeout", config.controllerAutoHideTimeout)
            put("longPressControlsSpeed", config.longPressControlsSpeed.toDouble())
            put("enableDoubleTapSeek", config.enableDoubleTapSeek)
            put("sponsorBlockEnabled", config.sponsorBlockEnabled)
            put("sponsorBlockAutoSkip", JSONArray(config.sponsorBlockAutoSkip.map { it.key }))
            put("visibleShortcuts", JSONArray(config.visibleShortcuts.map { it.name }))
        })
    }

    /** [fallback] gives each value the file does not carry (a settings import keeps the current ones). */
    fun parseConfig(jsonStr: String, fallback: AnderLineConfig = AnderLineConfig()): AnderLineConfig {
        val f = fallback
        return try {
            val root = JSONObject(jsonStr)
            val playerObj = root.optJSONObject("player") ?: root
            val appObj = root.optJSONObject("application") ?: root

            val useMaterialYou = playerObj.optBoolean("useMaterialYouControls", root.optBoolean("useMaterialYouControls", f.useMaterialYouControls))
            val seekIncrement = playerObj.optInt("seekIncrement", root.optInt("seekIncrement", f.seekIncrement))
            val autoHide = playerObj.optInt("controllerAutoHideTimeout", root.optInt("controllerAutoHideTimeout", f.controllerAutoHideTimeout))
            val useLongPress = playerObj.optBoolean("useLongPressControls", root.optBoolean("useLongPressControls", f.useLongPressControls))
            val longPressSpeed = playerObj.optDouble("longPressControlsSpeed", root.optDouble("longPressControlsSpeed", f.longPressControlsSpeed.toDouble())).toFloat()
            val volSwipe = playerObj.optBoolean("enableVolumeSwipeGesture", root.optBoolean("enableVolumeSwipeGesture", f.enableVolumeSwipeGesture))
            val brightSwipe = playerObj.optBoolean("enableBrightnessSwipeGesture", root.optBoolean("enableBrightnessSwipeGesture", f.enableBrightnessSwipeGesture))
            val orientation = playerObj.optString("playerScreenOrientation", root.optString("playerScreenOrientation", f.playerScreenOrientation))
            val disablePip = playerObj.optBoolean("disableAutoPipOnHome", root.optBoolean("disableAutoPipOnHome", f.disableAutoPipOnHome))
            val autoBg = playerObj.optBoolean("autoBackgroundPlay", root.optBoolean("autoBackgroundPlay", f.autoBackgroundPlay))
            val pipButton = playerObj.optBoolean("pipButton", root.optBoolean("pipButton", f.pipButton))
            val showOnLaunch = playerObj.optBoolean("showPlayerOnLaunch", root.optBoolean("showPlayerOnLaunch", f.showPlayerOnLaunch))
            val theme = appObj.optString("themeConfig", root.optString("themeConfig", f.themeConfig))
            val autoResume = playerObj.optBoolean("autoResumeAfterSettings", root.optBoolean("autoResumeAfterSettings", f.autoResumeAfterSettings))
            val subtitleBold = playerObj.optBoolean("subtitleTextBold", root.optBoolean("subtitleTextBold", f.subtitleTextBold))
            val doubleTapSeek = playerObj.optBoolean("enableDoubleTapSeek", root.optBoolean("enableDoubleTapSeek", f.enableDoubleTapSeek))
            val sponsorBlock = playerObj.optBoolean("sponsorBlockEnabled", root.optBoolean("sponsorBlockEnabled", f.sponsorBlockEnabled))
            val autoSkipArray = playerObj.optJSONArray("sponsorBlockAutoSkip") ?: root.optJSONArray("sponsorBlockAutoSkip")
            // No list at all -> defaults; an empty list is a choice ("skip none") and stays empty.
            val autoSkip = autoSkipArray?.let { array ->
                (0 until array.length()).mapNotNull { SponsorBlockCategory.fromName(array.optString(it)) }.toSet()
            } ?: f.sponsorBlockAutoSkip

            val shortcuts = mutableSetOf<PlayerShortcut>()
            val array = playerObj.optJSONArray("visibleShortcuts") ?: root.optJSONArray("visibleShortcuts")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val sName = array.optString(i)
                    PlayerShortcut.fromName(sName)?.let { shortcuts.add(it) }
                }
            }
            // No list at all -> defaults; an empty list is a choice ("show none") and stays empty.
            val finalShortcuts = if (array != null) shortcuts else f.visibleShortcuts

            AnderLineConfig(
                useMaterialYouControls = useMaterialYou,
                visibleShortcuts = finalShortcuts,
                seekIncrement = seekIncrement,
                controllerAutoHideTimeout = autoHide,
                useLongPressControls = useLongPress,
                longPressControlsSpeed = longPressSpeed,
                enableVolumeSwipeGesture = volSwipe,
                enableBrightnessSwipeGesture = brightSwipe,
                playerScreenOrientation = orientation,
                disableAutoPipOnHome = disablePip,
                autoBackgroundPlay = autoBg,
                pipButton = pipButton,
                showPlayerOnLaunch = showOnLaunch,
                themeConfig = theme,
                autoResumeAfterSettings = autoResume,
                subtitleTextBold = subtitleBold,
                enableDoubleTapSeek = doubleTapSeek,
                sponsorBlockEnabled = sponsorBlock,
                sponsorBlockAutoSkip = autoSkip,
            )
        } catch (_: Exception) {
            fallback
        }
    }
}

@Composable
fun rememberAnderLineConfig(): AnderLineConfig {
    val context = LocalContext.current
    var config by remember { mutableStateOf(AnderLineConfig()) }

    val activity = context as? android.app.Activity
    val configKey = activity?.hashCode() ?: System.nanoTime()
    LaunchedEffect(configKey) {
        withContext(Dispatchers.IO) {
            val fetched = AnderLineClient.fetchLocalConfig(context)
            withContext(Dispatchers.Main) {
                config = fetched
            }
        }
    }

    return config
}
