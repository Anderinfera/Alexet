package dev.ander.alexet

import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import dev.ander.alexet.data.AppLog as Log
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.ander.alexet.data.FrameMonitor
import dev.ander.alexet.ui.gallery.GalleryApp
import dev.ander.alexet.ui.gallery.GalleryViewModel
import dev.ander.alexet.ui.theme.AlexetTheme

private const val STARTUP_TAG = "AlexetStartup"

/** Tolerance around Alexet's visible spans (MediaStore dates have 1 s resolution). */
private const val SPAN_SLACK_MS = 2_000L

private const val HANDLED_PREFS = "incoming_intents"
private const val HANDLED_KEYS = "handled"
private const val HANDLED_LIST = "handled_ordered"
private const val HANDLED_MAX = 32

class MainActivity : FragmentActivity() {

    private val viewModel: GalleryViewModel by viewModels()

    /**
     * Wall-clock spans when the activity was visible (onStart..onStop): the current one and the
     * previous one. The screenshot "Edit" opens a chooser or editor over Alexet first, so Alexet is
     * usually stopped by the time ACTION_EDIT arrives; whether the screenshot was taken inside the
     * app is decided by its capture time instead.
     */
    private var visibleSince = 0L
    private var lastVisibleFrom = 0L
    private var lastVisibleUntil = 0L
    private var frameMonitor: FrameMonitor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val createdAt = SystemClock.elapsedRealtime()
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        if (!isTaskRoot
            && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
            && intent.action == Intent.ACTION_MAIN
        ) {
            finish()
            return
        }
        // Hold system splash only until the first Compose frame, then show in-app loader.
        val keepSplash = AtomicBoolean(true)
        splashScreen.setKeepOnScreenCondition { keepSplash.get() }
        setHighRefreshRate()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Recreated (rotation, fold, process restore) or reopened from Recents: the edit / view
        // intent that started the task was already handled; replaying it reopened the editor.
        val replayed = savedInstanceState != null ||
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0 ||
            wasHandledInThisTask(intent)
        Log.event("Lifecycle") { "onCreate task=$taskId restored=${savedInstanceState != null} intent=${intent.action} ${intent.data} replayed=$replayed" }
        if (!replayed) handleIncoming(intent)
        setContent {
            AlexetTheme {
                GalleryApp(
                    viewModel = viewModel,
                    onFoldersFirstShown = ::foldersFirstShown,
                    onSplashDismiss = {
                        if (keepSplash.getAndSet(false) && BuildConfig.DEBUG) {
                            Log.d(STARTUP_TAG, "splash -> loader after ${SystemClock.elapsedRealtime() - createdAt} ms")
                        }
                    },
                )
            }
        }
        startFrameMonitor()
    }

    override fun onDestroy() {
        frameMonitor?.stop()
        super.onDestroy()
    }

    /**
     * Runs while the folders are not shown yet for the first time (the one-off check for light
     * mode needs the frames of the loading animation); otherwise only for the log summary.
     */
    private fun foldersFirstShown() {
        val slow = FrameMonitor.isTooSlow(frameMonitor?.recentAverageGapMs())
        frameMonitor?.stop()
        viewModel.onFoldersFirstShown(slow)
    }

    private fun startFrameMonitor() {
        Log.event("FrameMonitor") {
            val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else null
            "device ${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "display=${d?.refreshRate} Hz mode=${d?.mode?.physicalWidth}x${d?.mode?.physicalHeight} " +
                "cpus=${Runtime.getRuntime().availableProcessors()}"
        }
        frameMonitor = FrameMonitor(
            maxRunMs = if (viewModel.needsFirstOpenCheck()) FrameMonitor.FIRST_RUN_MAX_MS else FrameMonitor.LOG_AFTER_MS,
        ).also { it.start() }
    }

    override fun onStart() {
        super.onStart()
        frameMonitor?.start()
        Log.event("Lifecycle") { "onStart task=$taskId" }
        visibleSince = System.currentTimeMillis()
        dev.ander.alexet.data.AppGraph.smartSearchIfCreated()?.setAppVisible(true)
    }

    override fun onStop() {
        super.onStop()
        frameMonitor?.stop()
        Log.event("Lifecycle") { "onStop" }
        dev.ander.alexet.data.AppGraph.smartSearchIfCreated()?.setAppVisible(false)
        // A change from the last moment is otherwise written ~120 ms later; the system may kill a
        // stopped app before that.
        dev.ander.alexet.data.AppGraph.settings(this).flushInBackground()
        lastVisibleFrom = visibleSince
        lastVisibleUntil = System.currentTimeMillis()
        visibleSince = 0L
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.event("Lifecycle") { "onNewIntent ${intent.action} ${intent.data}" }
        setIntent(intent)
        handleIncoming(intent)
    }

    /** Snapshot of the visible spans, checked once the screenshot's capture time is known. */
    private fun visibleSpans(): (Long) -> Boolean {
        val now = System.currentTimeMillis()
        val spans = buildList {
            if (lastVisibleFrom > 0L) add(lastVisibleFrom - SPAN_SLACK_MS..lastVisibleUntil + SPAN_SLACK_MS)
            if (visibleSince > 0L) add(visibleSince - SPAN_SLACK_MS..now + SPAN_SLACK_MS)
        }
        return { time -> spans.any { time in it } }
    }

    /**
     * The system keeps the intent that created the task and recreates the activity with it after
     * the process died (app update, low memory), even from the launcher icon and without saved
     * state. Handled intents are remembered per task on disk, so an old "Edit" is not replayed.
     * A new "Edit" either starts a new task (other id) or arrives through onNewIntent.
     */
    private fun handledKey(intent: Intent): String? {
        if (intent.action != Intent.ACTION_EDIT && intent.action != Intent.ACTION_VIEW && intent.data == null) return null
        return "$taskId|${intent.action}|${intent.data}"
    }

    private fun wasHandledInThisTask(intent: Intent): Boolean {
        val key = handledKey(intent) ?: return false
        return key in handledKeys()
    }

    /**
     * Oldest first. Kept as one ordered string: a StringSet has no order, so trimming it to the
     * limit dropped random entries, possibly the task's own root intent.
     */
    private fun handledKeys(): List<String> {
        val prefs = getSharedPreferences(HANDLED_PREFS, MODE_PRIVATE)
        prefs.getString(HANDLED_LIST, null)?.let { list -> return list.split('\n').filter { it.isNotEmpty() } }
        return prefs.getStringSet(HANDLED_KEYS, emptySet()).orEmpty().toList() // older versions
    }

    private fun rememberHandled(intent: Intent) {
        val key = handledKey(intent) ?: return
        val kept = handledKeys().filter { it != key }.takeLast(HANDLED_MAX - 1)
        getSharedPreferences(HANDLED_PREFS, MODE_PRIVATE).edit()
            .putString(HANDLED_LIST, (kept + key).joinToString("\n"))
            .remove(HANDLED_KEYS)
            .apply()
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        Log.event("Intent") { "handle ${intent.action} ${intent.data} flags=0x${Integer.toHexString(intent.flags)}" }
        rememberHandled(intent)
        when {
            intent.action == Intent.ACTION_EDIT -> intent.data?.let {
                viewModel.editUri(it, wasOnScreenAt = visibleSpans())
            }
            intent.action == Intent.ACTION_VIEW || intent.data != null -> intent.data?.let(viewModel::openUri)
            else -> return
        }
        // Handled once: a later recreate must not open the same file again.
        setIntent(Intent(Intent.ACTION_MAIN).setPackage(packageName))
    }

    private fun setHighRefreshRate() {
        val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        val current = currentDisplay?.mode ?: return
        // Only modes at the current resolution: on phones offering FHD+ and QHD+ the fastest mode
        // could be the other one, and switching to it blanked the screen at start.
        val maxMode = currentDisplay.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        val params = window.attributes
        params.preferredDisplayModeId = maxMode.modeId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.attributes = params
    }
}
