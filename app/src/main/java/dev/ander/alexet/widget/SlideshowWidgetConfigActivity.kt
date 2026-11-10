package dev.ander.alexet.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.ander.alexet.MainActivity
import dev.ander.alexet.R
import dev.ander.alexet.data.AppGraph
import dev.ander.alexet.data.NomediaScanner
import dev.ander.alexet.data.StorageAccess
import dev.ander.alexet.ui.gallery.SettingsSegmentedRow
import dev.ander.alexet.ui.theme.AlexetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The slideshow widget's settings: opened when the widget is added (and from its settings later).
 * Photos are chosen in any number of folders, then the time between them.
 */
class SlideshowWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Left without "Done" while adding it: the widget is not added.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        // Only this app's own slideshow widgets (the screen can be opened by other apps too).
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            provider != android.content.ComponentName(this, SlideshowWidget::class.java)
        ) {
            finish()
            return
        }
        enableEdgeToEdge()
        val chosen = SlideshowWidgetStore.photos(this, widgetId).map(Uri::toString)
        val interval = SlideshowWidgetStore.interval(this, widgetId)
        setContent {
            AlexetTheme {
                SlideshowPicker(
                    initialPhotos = chosen,
                    initialInterval = interval,
                    onCancel = ::finish,
                    onDone = { photos, seconds ->
                        SlideshowWidgetStore.save(this, widgetId, photos.map(Uri::parse), seconds)
                        SlideshowWidget.update(this, AppWidgetManager.getInstance(this), widgetId)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                        finish()
                    },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                )
            }
        }
    }
}

/** A folder with its photos, newest first ([newestMs]: the newest one's date). */
private class PickerFolder(val id: Long, val name: String, val photos: List<Uri>, val newestMs: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlideshowPicker(
    initialPhotos: List<String>,
    initialInterval: Int,
    onCancel: () -> Unit,
    onDone: (List<String>, Int) -> Unit,
    onOpenApp: () -> Unit,
) {
    val context = LocalContext.current
    val hasAccess = remember { hasPhotoAccess(context) }
    var folders by remember { mutableStateOf<List<PickerFolder>?>(null) }
    LaunchedEffect(hasAccess) {
        folders = if (hasAccess) withContext(Dispatchers.IO) { loadFolders(context) } else emptyList()
    }
    // In the order they were chosen: the widget shows them in that order.
    val selected = remember { mutableStateListOf<String>().apply { addAll(initialPhotos) } }
    val selectedSet by remember { derivedStateOf { selected.toHashSet() } }
    var interval by rememberSaveable { mutableStateOf(initialInterval) }
    var openFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    val openFolder = folders?.firstOrNull { it.id == openFolderId }

    BackHandler(enabled = openFolder != null) { openFolderId = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = openFolder?.name ?: stringResource(R.string.slideshow_widget_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (openFolder != null) openFolderId = null else onCancel() }) {
                        Icon(
                            imageVector = if (openFolder != null) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Close,
                            contentDescription = stringResource(if (openFolder != null) R.string.navigate_up else R.string.cancel),
                        )
                    }
                },
                actions = {
                    if (openFolder != null) {
                        val keys = openFolder.photos.map(Uri::toString)
                        val all = keys.all { it in selectedSet }
                        TextButton(onClick = {
                            if (all) {
                                val drop = keys.toHashSet()
                                selected.removeAll { it in drop }
                            } else {
                                keys.filterNot { it in selectedSet }.forEach(selected::add)
                            }
                        }) {
                            Text(stringResource(if (all) R.string.slideshow_widget_select_none else R.string.slideshow_widget_select_all))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.slideshow_widget_interval),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    SettingsSegmentedRow(
                        options = SlideshowWidget.INTERVALS.map { it to intervalLabel(context, it) },
                        selected = interval,
                        onSelect = { interval = it },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.slideshow_widget_chosen, selected.size),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { onDone(selected.toList(), interval) },
                            enabled = selected.isNotEmpty(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.slideshow_widget_done))
                        }
                    }
                }
            }
        },
    ) { padding ->
        val list = folders
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !hasAccess || list.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(if (hasAccess) R.string.slideshow_widget_no_photos else R.string.slideshow_widget_no_access),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                if (!hasAccess) {
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = onOpenApp, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.slideshow_widget_open_app))
                    }
                }
            }
            openFolder == null -> LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(12.dp) + padding,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { folder ->
                    val chosenHere = remember(folder, selectedSet) { folder.photos.count { it.toString() in selectedSet } }
                    FolderTile(folder = folder, chosen = chosenHere, onClick = { openFolderId = folder.id })
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                contentPadding = PaddingValues(4.dp) + padding,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(openFolder.photos, key = { it.toString() }) { uri ->
                    val key = uri.toString()
                    val chosen = key in selectedSet
                    PhotoTile(
                        uri = uri,
                        chosen = chosen,
                        onClick = { if (chosen) selected.remove(key) else selected.add(key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderTile(folder: PickerFolder, chosen: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box {
            Thumbnail(
                uri = folder.photos.first(),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp)),
            )
            if (chosen > 0) {
                Text(
                    text = chosen.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            text = folder.photos.size.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun PhotoTile(uri: Uri, chosen: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .then(if (chosen) Modifier.border(3.dp, accent, RoundedCornerShape(6.dp)) else Modifier),
    ) {
        Thumbnail(uri = uri, modifier = Modifier.fillMaxSize())
        if (chosen) {
            Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.18f)))
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun Thumbnail(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(uri) { ImageRequest.Builder(context).data(uri).size(256).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

private operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    val dir = androidx.compose.ui.unit.LayoutDirection.Ltr
    return PaddingValues(
        start = calculateStartPadding(dir) + other.calculateStartPadding(dir),
        top = calculateTopPadding() + other.calculateTopPadding(),
        end = calculateEndPadding(dir) + other.calculateEndPadding(dir),
        bottom = calculateBottomPadding() + other.calculateBottomPadding(),
    )
}

private fun intervalLabel(context: Context, seconds: Int): String = when {
    seconds < 60 -> context.getString(R.string.slideshow_widget_seconds, seconds)
    seconds < 3600 -> context.getString(R.string.slideshow_widget_minutes, seconds / 60)
    else -> context.getString(R.string.slideshow_widget_hours, seconds / 3600)
}

/** The app may read the photos (granted in Alexet itself). */
private fun hasPhotoAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) return true
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return true
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
}

/**
 * Photo folders, the one with the newest photo first. Hidden folders (.nomedia ones Alexet knows,
 * and the ones hidden in Alexet) only with "Show hidden"; private folders never.
 */
private fun loadFolders(context: Context): List<PickerFolder> {
    val settings = runCatching { AppGraph.settings(context).settings.value }.getOrNull()
    val showHidden = settings?.showHidden == true
    val hiddenInApp = settings?.hiddenAlbumIds.orEmpty()
    val folders = ArrayList<PickerFolder>()
    folders += systemFolders(context, skip = { !showHidden && it in hiddenInApp })
    if (showHidden && StorageAccess.canReadAllFiles()) {
        folders += hiddenFolders(context, settings.nomediaFolderPaths)
    }
    return folders.sortedByDescending { it.newestMs }
}

/** The folders the system lists, without those [skip] names. */
private fun systemFolders(context: Context, skip: (Long) -> Boolean): List<PickerFolder> {
    // Name, newest date, photos: the query gives the newest first.
    val byFolder = LinkedHashMap<Long, Triple<String, Long, MutableList<Uri>>>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATE_MODIFIED,
    )
    runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val bucket = cursor.getLong(bucketCol)
                if (skip(bucket)) continue
                val entry = byFolder.getOrPut(bucket) {
                    Triple(cursor.getString(nameCol) ?: "", cursor.getLong(dateCol) * 1000L, mutableListOf())
                }
                entry.third += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
            }
        }
    }.onFailure { SlideshowWidget.log("photos not listed", it) }
    return byFolder.map { (id, entry) -> PickerFolder(id, entry.first, entry.third, entry.second) }
}

/** The photos of the hidden (.nomedia) folders Alexet has found (the system does not list them). */
private fun hiddenFolders(context: Context, knownPaths: List<String>): List<PickerFolder> = runCatching {
    NomediaScanner(context).scanKnown(knownPaths).media
        .filter { m ->
            !m.isVideo && !m.isOtherFile && !m.isEncrypted &&
                (m.mimeType.startsWith("image/", ignoreCase = true) ||
                    NomediaScanner.mimeOf(m.displayName)?.startsWith("image/") == true)
        }
        .groupBy { it.bucketId }
        .map { (id, photos) ->
            val newest = photos.sortedByDescending { it.dateModifiedMs }
            PickerFolder(id, newest.first().bucketName, newest.map { it.uri }, newest.first().dateModifiedMs)
        }
}.onFailure { SlideshowWidget.log("hidden photos not listed", it) }.getOrDefault(emptyList())
