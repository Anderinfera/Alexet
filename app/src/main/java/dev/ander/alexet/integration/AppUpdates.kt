package dev.ander.alexet.integration

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.ander.alexet.BuildConfig
import dev.ander.alexet.data.AppLog as Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * New versions from the app's GitHub releases: checked at most once a day at start (when turned
 * on) or by hand, downloaded on request and handed to the system installer. Nothing is sent but
 * the request itself; the APK must be signed like the installed app.
 */
object AppUpdates {
    private const val TAG = "AppUpdates"
    private const val LATEST_URL = "https://api.github.com/repos/Anderinfera/Alexet/releases/latest"
    private const val PREFS = "app_updates"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_DISMISSED = "dismissed_version"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    class Release(
        val version: String,
        /** What changed, in the app's language when the notes have it. */
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val pageUrl: String,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Failed(val release: Release?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The card shows a found version until it is closed (then only "Check now" shows it again). */
    private val _cardShown = MutableStateFlow(false)
    val cardShown: StateFlow<Boolean> = _cardShown.asStateFlow()

    /** At start: a check once a day, and the card only for a version not closed before. */
    suspend fun checkIfDue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < DAY_MS) return
        val release = check(context) ?: return
        if (prefs.getString(KEY_DISMISSED, null) != release.version) _cardShown.value = true
    }

    /** "Check now": the card shows whatever is found. */
    suspend fun checkNow(context: Context) {
        if (check(context) != null) _cardShown.value = true
    }

    fun closeCard(context: Context) {
        _cardShown.value = false
        val release = when (val s = _state.value) {
            is State.Available -> s.release
            is State.Failed -> s.release
            else -> null
        } ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DISMISSED, release.version).apply()
    }

    /** The newer release, or null (up to date, or the check failed). */
    private suspend fun check(context: Context): Release? {
        if (_state.value is State.Checking || _state.value is State.Downloading) return null
        _state.value = State.Checking
        return try {
            val release = withContext(Dispatchers.IO) { fetchLatest() }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (release != null && isNewer(release.version, BuildConfig.VERSION_NAME)) {
                _state.value = State.Available(release)
                release
            } else {
                _state.value = State.UpToDate
                null
            }
        } catch (e: CancellationException) {
            _state.value = State.Idle
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            _state.value = State.Failed(null)
            null
        }
    }

    private fun fetchLatest(): Release? {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Alexet/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = json.optString("tag_name").removePrefix("v").trim()
            if (version.isEmpty()) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val url = asset.optString("browser_download_url")
                if (asset.optString("name").endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                    return Release(
                        version = version,
                        notes = notesFor(json.optString("body"), Locale.getDefault().language),
                        apkUrl = url,
                        apkSize = asset.optLong("size"),
                        pageUrl = json.optString("html_url"),
                    )
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads [release] (progress in [state]) and opens the system installer. Without the
     * permission to install apps the system's page for it opens first; the card stays.
     */
    suspend fun downloadAndInstall(context: Context, release: Release) {
        if (_state.value is State.Downloading) return
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        _state.value = State.Downloading(release, 0f)
        try {
            val apk = withContext(Dispatchers.IO) { download(app, release) }
            if (!signedLikeThisApp(app, apk)) {
                Log.w(TAG, "downloaded APK is signed differently, not installed")
                apk.delete()
                _state.value = State.Failed(release)
                return
            }
            _state.value = State.Available(release)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: CancellationException) {
            _state.value = State.Available(release)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update download failed", e)
            _state.value = State.Failed(release)
        }
    }

    private suspend fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Only the newest download is kept.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "Alexet-${release.version.filter { it.isLetterOrDigit() || it == '.' }}.apk")
        val part = File(dir, target.name + ".part")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Alexet/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var shown = 0f
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        if (progress - shown >= 0.01f) {
                            shown = progress
                            _state.value = State.Downloading(release, progress)
                        }
                    }
                }
            }
            if (release.apkSize > 0 && done != release.apkSize) error("size $done, expected ${release.apkSize}")
            if (!part.renameTo(target)) error("not saved")
            return target
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    /** The system would refuse an APK signed with another key anyway; this says so before. */
    private fun signedLikeThisApp(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val mine = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }
            val theirs = pm.getPackageArchiveInfo(apk.path, flags)?.let { info ->
                if (info.packageName != context.packageName) null else info.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }
            }
            mine != null && theirs != null && mine.toSet() == theirs.toSet()
        } else {
            // Android 8 does not read an archive's signatures: the installer checks them there.
            pm.getPackageArchiveInfo(apk.path, 0)?.packageName == context.packageName
        }
    }

    /** "1.0.10" is newer than "1.0.9"; extra parts count ("1.0.2.1" > "1.0.2"). */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * The release notes have the English part, a "---" line and the Polish part: the one in the
     * app's language, without Markdown marks.
     */
    internal fun notesFor(body: String, language: String): String {
        val parts = body.split(Regex("(?m)^\\s*---+\\s*$")).map { it.trim() }.filter { it.isNotEmpty() }
        val chosen = if (language == "pl" && parts.size > 1) parts[1] else parts.firstOrNull().orEmpty()
        return chosen
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace("**", "")
            .replace("`", "")
            .replace(Regex("(?m)^#+\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
            .trim()
    }
}
