package dev.ander.alexet.data

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.io.File
import java.util.Calendar
import java.util.Locale

private val ILLEGAL_FILENAME_CHARS = Regex("""[\\/:*?"<>|\x00-\x1F]""")

fun sanitizeFileName(name: String, fallback: String = "file"): String {
    val sanitized = name.replace(ILLEGAL_FILENAME_CHARS, "_").trim(' ', '.')
    return sanitized.ifBlank { fallback }
}

fun resolveUniqueTargetFile(dir: File, baseDisplayName: String): File {
    val safeName = sanitizeFileName(baseDisplayName)
    var target = File(dir, safeName)
    if (!target.exists()) return target
    val dotIdx = safeName.lastIndexOf('.')
    val nameWithoutExt = if (dotIdx > 0) safeName.substring(0, dotIdx) else safeName
    val ext = if (dotIdx > 0) safeName.substring(dotIdx + 1) else ""
    var index = 1
    while (target.exists()) {
        val candidateName = if (ext.isNotEmpty()) "$nameWithoutExt ($index).$ext" else "$nameWithoutExt ($index)"
        target = File(dir, candidateName)
        index++
        if (index > 10_000) break
    }
    return target
}

fun shouldRestoreTrashOnDeny(sourceRemoved: Boolean, originalExists: Boolean): Boolean =
    sourceRemoved || !originalExists

fun isPathUnderDirectory(baseDir: File, file: File): Boolean {
    val root = runCatching { baseDir.canonicalFile }.getOrNull() ?: return false
    val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
    return canonical == root || canonical.path.startsWith(root.path + File.separator)
}

@Immutable
data class GalleryMedia(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateTakenMs: Long,
    val dateModifiedMs: Long,
    val dateAddedMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,
    val isHidden: Boolean = false,
    val absolutePath: String? = null,
    val rawBucketId: Long = 0L,
    /** Not a photo or video (a document, an archive, an app…): shown with "See all files", opened outside. */
    val isOtherFile: Boolean = false,
) {
    val isVideo: Boolean = mimeType.startsWith("video/", ignoreCase = true) || NomediaScanner.mimeOf(displayName)?.startsWith("video/", ignoreCase = true) == true
    val capturedAtMs: Long
        get() {
            if (dateTakenMs > 0L) return dateTakenMs
            if (dateAddedMs > 0L && dateModifiedMs > 0L) {
                return maxOf(dateAddedMs, dateModifiedMs)
            }
            return if (dateAddedMs > 0L) dateAddedMs else dateModifiedMs
        }
    /** An audio file the built-in player plays (shown with "See all files"). */
    val isAudio: Boolean
        get() = isOtherFile && OtherFiles.isPlayableAudio(displayName)

    /** A file of a private folder: its bytes on disk are encrypted (`name.ext.enc`). */
    val isEncrypted: Boolean
        get() = absolutePath?.endsWith(SecureFolderManager.ENC_EXTENSION) == true

    val storagePath: String
        get() {
            if (!absolutePath.isNullOrBlank()) return absolutePath
            val dir = relativePath.trim().trim('/')
            return if (dir.isEmpty()) displayName else "$dir/$displayName"
        }
}

@Immutable
data class Album(
    val id: Long,
    val name: String,
    val cover: GalleryMedia?,
    val itemCount: Int,
    val relativePath: String,
    val isHidden: Boolean = false,
    val latestAddedMs: Long = 0L,
    val legacyBucketIds: Set<Long> = emptySet(),
    val isSecure: Boolean = false,
)

@Immutable
data class TreeDestination(
    val album: Album,
    val treeUri: Uri,
    val useMediaStore: Boolean,
)

object AlbumIds {
    const val NONE = 0L
    const val TRASH = -100L
    const val FAVORITES = -200L
}

enum class SelectionAction(val key: String) {
    DELETE("delete"),
    SHARE("share"),
    INFO("info"),
    MOVE("move"),
    RENAME("rename"),
    EDIT("edit"),
    COPY("copy"),
    SELECT_ALL("select_all"),
    HIDE("hide"),
    FAVORITE("favorite"),
    SET_AS("set_as"),
    STRIP_EXIF("strip_exif"),
    CONTENT_ORDER("content_order"),
    SET_PRIVATE("set_private"),
    QUICK_EDIT("quick_edit");

    companion object {
        val DEFAULT_ORDER = listOf(
            DELETE,
            SHARE,
            INFO,
            SET_AS,
            MOVE,
            RENAME,
            EDIT,
            QUICK_EDIT,
            COPY,
            SELECT_ALL,
            HIDE,
            FAVORITE,
            STRIP_EXIF,
            CONTENT_ORDER,
            SET_PRIVATE,
        )

        fun fromKey(key: String): SelectionAction? = entries.firstOrNull { it.key == key }
    }
}

@Immutable
sealed interface RenameRequest {
    @Immutable
    data class Simple(val text: String, val isAppend: Boolean) : RenameRequest
    @Immutable
    data class Pattern(val pattern: String) : RenameRequest
    @Immutable
    data class Replace(val findText: String, val replaceText: String) : RenameRequest
    @Immutable
    data class SetBase(val newBase: String) : RenameRequest
    @Immutable
    data object Swap : RenameRequest
    @Immutable
    data object ReverseOrder : RenameRequest

    fun applyTo(item: GalleryMedia, index: Int, padDigits: Int = 3): String {
        val original = item.displayName
        val hasDot = original.contains('.')
        val baseName = if (hasDot) original.substringBeforeLast('.') else original
        val ext = if (hasDot) original.substringAfterLast('.') else ""

        val rawNewBaseName = when (this) {
            is Simple -> {
                if (isAppend) {
                    baseName + text
                } else {
                    text + baseName
                }
            }
            is Replace -> {
                if (findText.isNotEmpty()) {
                    baseName.replace(findText, replaceText)
                } else {
                    baseName
                }
            }
            is SetBase -> newBase
            is Swap, is ReverseOrder -> baseName
            is Pattern -> {
                val ts = if (item.capturedAtMs > 0) {
                    item.capturedAtMs
                } else if (item.dateModifiedMs > 0) {
                    item.dateModifiedMs
                } else {
                    System.currentTimeMillis()
                }
                val cal = Calendar.getInstance().apply { timeInMillis = ts }
                val yyyy = String.format(Locale.US, "%04d", cal.get(Calendar.YEAR))
                val mm = String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)
                val dd = String.format(Locale.US, "%02d", cal.get(Calendar.DAY_OF_MONTH))
                val hh = String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY))
                val min = String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE))
                val ss = String.format(Locale.US, "%02d", cal.get(Calendar.SECOND))
                val safePad = maxOf(1, padDigits)
                val inc = String.format(Locale.US, "%0${safePad}d", index)

                var result = pattern
                result = result.replace("%Y", yyyy)
                result = result.replace("%M", mm)
                result = result.replace("%D", dd)
                result = result.replace("%h", hh)
                result = result.replace("%m", min)
                result = result.replace("%s", ss)
                result = result.replace("%i", inc)

                if (ext.isNotEmpty() && result.endsWith(".$ext", ignoreCase = true)) {
                    result = result.substring(0, result.length - (ext.length + 1))
                }
                result
            }
        }
        val cleanBaseName = sanitizeFileName(rawNewBaseName, fallback = baseName.ifBlank { "file" })
        return if (ext.isNotEmpty()) "$cleanBaseName.$ext" else cleanBaseName
    }
}

@Immutable
data class TrashedItem(
    val id: Long,
    val originalUriString: String,
    val originalPath: String,
    val originalBucketId: Long,
    val originalBucketName: String,
    val originalRelativePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateTakenMs: Long,
    val trashedAtMs: Long = System.currentTimeMillis(),
    val trashFilePath: String,
    val sourceRemoved: Boolean = true,
    val trashUriString: String = "",
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val daysRemaining: Int
        get() = kotlin.math.max(0, 7 - ((System.currentTimeMillis() - trashedAtMs) / (24 * 60 * 60 * 1000L)).toInt())
    val isExpired: Boolean
        get() = (System.currentTimeMillis() - trashedAtMs) > 7 * 24 * 60 * 60 * 1000L

    fun toGalleryMedia(context: android.content.Context? = null): GalleryMedia = GalleryMedia(
        id = id,
        uri = trashContentUri(context),
        displayName = displayName,
        mimeType = mimeType,
        dateTakenMs = dateTakenMs,
        dateModifiedMs = trashedAtMs,
        dateAddedMs = trashedAtMs,
        sizeBytes = sizeBytes,
        width = 0,
        height = 0,
        durationMs = 0L,
        bucketId = AlbumIds.TRASH,
        bucketName = "Trash",
        relativePath = "",
        isHidden = true,
        absolutePath = trashFilePath,
        isOtherFile = !mimeType.startsWith("image/") && !mimeType.startsWith("video/") &&
            NomediaScanner.mimeOf(displayName) == null,
    )

    private fun trashContentUri(context: android.content.Context?): Uri {
        val stored = trashUriString.trim()
        if (stored.startsWith("content:", ignoreCase = true)) {
            return Uri.parse(stored)
        }
        val file = File(trashFilePath)
        if (context != null && file.exists()) {
            val provided = runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }.getOrNull()
            if (provided != null) return provided
        }
        // Never emit file:// — that URI leaves the app via share / NextPlayer.
        return Uri.EMPTY
    }
}

@Immutable
data class TransferProgress(
    val current: Int,
    val total: Int,
    val isMove: Boolean = true,
    val isDone: Boolean = false,
    val isRename: Boolean = false,
    val isSavingOrder: Boolean = false,
) {
    val fraction: Float get() = if (isDone) 1f else if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val percent: Int get() = if (isDone) 100 else if (total > 0) ((current * 100) / total).coerceIn(0, 100) else 0
}

@Immutable
data class FolderScrollPos(
    val index: Int = 0,
    val offset: Int = 0,
)
