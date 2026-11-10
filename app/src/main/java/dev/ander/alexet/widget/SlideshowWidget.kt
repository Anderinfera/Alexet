@file:Suppress("DEPRECATION") // The collection adapter: its replacement (RemoteCollectionItems) sends all photos at once.

package dev.ander.alexet.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.exifinterface.media.ExifInterface
import dev.ander.alexet.MainActivity
import dev.ander.alexet.R
import dev.ander.alexet.data.AppLog as Log
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Home screen widget showing chosen photos one after another (photos from any folders, never
 * from private folders). Short intervals are flipped by the home screen itself while it is
 * visible, so the app is not woken; long ones by a non-waking alarm that only moves to the next
 * photo. A tap opens the photo in Alexet.
 */
class SlideshowWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        // Resized: the photos are loaded again in the new size.
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.slideshow_flipper)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            cancelAlarm(context, id)
            SlideshowWidgetStore.remove(context, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_NEXT) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val views = RemoteViews(context.packageName, R.layout.widget_slideshow_manual)
                views.showNext(R.id.slideshow_flipper)
                AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(id, views)
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val TAG = "SlideshowWidget"
        private const val ACTION_NEXT = "dev.ander.alexet.widget.SLIDESHOW_NEXT"

        /** A tap's address for a photo of a hidden folder (see [tapUri]). */
        private const val FILE_SCHEME = "alexet-file"

        /**
         * The address a tap hands the app. A file's own address (file://) would make the home
         * screen, which sends it, fail (file addresses may not leave an app): it goes renamed.
         */
        internal fun tapUri(uri: Uri): Uri =
            if (uri.scheme == "file") uri.buildUpon().scheme(FILE_SCHEME).build() else uri

        /** The photo a tap opens: [tapUri] undone. */
        fun openedUri(uri: Uri): Uri =
            if (uri.scheme == FILE_SCHEME) uri.buildUpon().scheme("file").build() else uri

        /** "Show hidden" changed: hidden folders' photos show or go at once. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, SlideshowWidget::class.java))
            if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.slideshow_flipper)
        }

        /** Seconds between photos offered in the widget's settings. */
        val INTERVALS = listOf(5, 15, 60, 15 * 60, 60 * 60)
        const val DEFAULT_INTERVAL = 15

        /** Shows the widget's photos (after its settings were changed too). */
        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val interval = SlideshowWidgetStore.interval(context, id)
            val views = RemoteViews(context.packageName, layoutFor(interval))
            val adapter = Intent(context, SlideshowWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                // One adapter per widget: intents that only differ in extras would share one.
                .setData(Uri.parse("alexet-widget://slideshow/$id"))
            views.setRemoteAdapter(R.id.slideshow_flipper, adapter)
            views.setEmptyView(R.id.slideshow_flipper, R.id.slideshow_empty)
            // A tap on a photo opens it in Alexet (the photo's address is filled in per photo).
            val open = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setPendingIntentTemplate(
                R.id.slideshow_flipper,
                PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            // No photos (all deleted or moved): a tap opens the settings to choose new ones.
            val configure = Intent(context, SlideshowWidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("alexet-widget://slideshow-config/$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(
                R.id.slideshow_empty,
                PendingIntent.getActivity(context, id, configure, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.slideshow_flipper)
            scheduleAlarm(context, id, interval)
        }

        private fun layoutFor(intervalSeconds: Int): Int = when (intervalSeconds) {
            5 -> R.layout.widget_slideshow_5s
            15 -> R.layout.widget_slideshow_15s
            60 -> R.layout.widget_slideshow_60s
            else -> R.layout.widget_slideshow_manual
        }

        /** Only long intervals need the app: the home screen flips the short ones itself. */
        private fun scheduleAlarm(context: Context, id: Int, intervalSeconds: Int) {
            cancelAlarm(context, id)
            if (layoutFor(intervalSeconds) != R.layout.widget_slideshow_manual) return
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val ms = intervalSeconds * 1000L
            // Not waking: the next photo comes when the phone is in use anyway.
            alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + ms, ms, nextIntent(context, id))
        }

        private fun cancelAlarm(context: Context, id: Int) {
            context.getSystemService(AlarmManager::class.java)?.cancel(nextIntent(context, id))
        }

        private fun nextIntent(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, SlideshowWidget::class.java)
                .setAction(ACTION_NEXT)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        internal fun log(message: String, error: Throwable? = null) {
            Log.w(TAG, message, error)
        }
    }
}

/** The photos and the interval of each widget. */
object SlideshowWidgetStore {
    private const val PREFS = "slideshow_widgets"

    fun photos(context: Context, id: Int): List<Uri> =
        prefs(context).getString("photos_$id", null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.map(Uri::parse)
            .orEmpty()

    fun interval(context: Context, id: Int): Int =
        prefs(context).getInt("interval_$id", SlideshowWidget.DEFAULT_INTERVAL)

    fun isConfigured(context: Context, id: Int): Boolean = prefs(context).contains("photos_$id")

    fun save(context: Context, id: Int, photos: List<Uri>, intervalSeconds: Int) {
        prefs(context).edit()
            .putString("photos_$id", photos.joinToString("\n"))
            .putInt("interval_$id", intervalSeconds)
            .commit()
    }

    fun remove(context: Context, id: Int) {
        prefs(context).edit().remove("photos_$id").remove("interval_$id").apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Hands the home screen the widget's photos, decoded at the widget's size. */
class SlideshowWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return SlideshowFactory(applicationContext, id)
    }
}

private class SlideshowFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var photos: List<Uri> = emptyList()
    private var boxWidth = 600
    private var boxHeight = 600

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        // Photos deleted or moved since they were chosen are left out, and hidden folders' photos
        // while "Show hidden" is off.
        val showHidden = runCatching { dev.ander.alexet.data.AppGraph.settings(context).settings.value.showHidden }.getOrDefault(false)
        photos = SlideshowWidgetStore.photos(context, widgetId).filter { (showHidden || it.scheme != "file") && exists(it) }
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        val density = context.resources.displayMetrics.density
        val wDp = max(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
        val hDp = max(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
        var w = ((if (wDp > 0) wDp else 200) * density).roundToInt()
        var h = ((if (hDp > 0) hDp else 200) * density).roundToInt()
        // Enough for the widget, and small enough for the home screen's memory.
        val longest = max(w, h)
        if (longest > MAX_SIDE) {
            w = w * MAX_SIDE / longest
            h = h * MAX_SIDE / longest
        }
        boxWidth = w.coerceAtLeast(1)
        boxHeight = h.coerceAtLeast(1)
    }

    override fun onDestroy() = Unit

    override fun getCount(): Int = photos.size

    override fun getViewAt(position: Int): RemoteViews {
        val uri = photos.getOrNull(position)
        val views = RemoteViews(context.packageName, R.layout.widget_slideshow_item)
        if (uri != null) {
            decode(uri)?.let { views.setImageViewBitmap(R.id.slideshow_photo, it) }
            views.setOnClickFillInIntent(R.id.slideshow_photo, Intent().setData(SlideshowWidget.tapUri(uri)))
        }
        return views
    }

    // Black while a photo loads, not the default "Loading" text.
    override fun getLoadingView(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_slideshow_item)

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = photos.getOrNull(position)?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun exists(uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") {
            java.io.File(uri.path.orEmpty()).isFile
        } else {
            context.contentResolver.query(uri, arrayOf(android.provider.BaseColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
        }
    }.getOrDefault(false)

    /** The photo covering the widget (cropped in the middle), upright, never larger than needed. */
    private fun decode(uri: Uri): Bitmap? = try {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = coverScale(info.size.width, info.size.height)
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
                // Sent to the home screen: no hardware bitmaps; no alpha needed for photos.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
        } else {
            decodeLegacy(uri)
        }
        bitmap?.let(::cropToBox)
    } catch (e: Exception) {
        SlideshowWidget.log("photo not loaded: $uri", e)
        null
    } catch (e: OutOfMemoryError) {
        SlideshowWidget.log("photo too large: $uri", null)
        null
    }

    /** Scale (at most 1) at which the photo still covers the whole widget. */
    private fun coverScale(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 1f
        return max(boxWidth.toFloat() / width, boxHeight.toFloat() / height).coerceAtMost(1f)
    }

    private fun decodeLegacy(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val rotation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).rotationDegrees
        } ?: 0
        val (w, h) = if (rotation % 180 != 0) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        var sample = 1
        while (w / (sample * 2) >= boxWidth && h / (sample * 2) >= boxHeight) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        if (rotation == 0) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /** The middle part with the widget's shape, scaled down to the widget's size. */
    private fun cropToBox(source: Bitmap): Bitmap {
        val scale = coverScale(source.width, source.height)
        val cropW = (boxWidth / scale).roundToInt().coerceIn(1, source.width)
        val cropH = (boxHeight / scale).roundToInt().coerceIn(1, source.height)
        if (cropW == source.width && cropH == source.height && scale >= 1f) return source
        val matrix = Matrix().apply { if (scale < 1f) postScale(scale, scale) }
        val cropped = Bitmap.createBitmap(
            source,
            (source.width - cropW) / 2,
            (source.height - cropH) / 2,
            cropW,
            cropH,
            matrix,
            true,
        )
        if (cropped !== source) source.recycle()
        return cropped
    }

    private companion object {
        /** Longest side of a photo sent to the home screen, in pixels. */
        const val MAX_SIDE = 1080
    }
}
