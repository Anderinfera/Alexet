package dev.ander.alexet.data

import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Files that are neither photos nor videos (documents, archives, apps…), shown in a folder with
 * "See all files". They are listed from the folder on disk (all-files access) and never enter the
 * photo library: syncs, covers and counts stay as they were.
 */
object OtherFiles {

    /** What a file is, for its tile: colour, icon and label. */
    enum class Kind { PDF, WORD, SHEET, SLIDES, TEXT, CODE, EBOOK, ARCHIVE, APP, AUDIO, FONT, OTHER }

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else ""
    }

    fun kindOf(name: String): Kind = when (extensionOf(name)) {
        "pdf" -> Kind.PDF
        "doc", "docx", "odt", "rtf", "pages" -> Kind.WORD
        "xls", "xlsx", "ods", "csv", "tsv", "numbers" -> Kind.SHEET
        "ppt", "pptx", "odp", "key" -> Kind.SLIDES
        "txt", "md", "log", "nfo", "srt", "vtt", "ass", "ini", "cfg", "conf" -> Kind.TEXT
        "json", "xml", "html", "htm", "css", "js", "ts", "kt", "kts", "java", "py", "c", "cpp", "h",
        "cs", "sh", "bat", "ps1", "yml", "yaml", "toml", "gradle", "sql", "php", "rb", "go", "rs" -> Kind.CODE
        "epub", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr" -> Kind.EBOOK
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "img" -> Kind.ARCHIVE
        "apk", "apks", "xapk", "apkm", "aab", "exe", "msi", "dmg", "deb", "rpm", "appimage", "jar" -> Kind.APP
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "amr", "mid", "midi" -> Kind.AUDIO
        "ttf", "otf", "woff", "woff2" -> Kind.FONT
        else -> Kind.OTHER
    }

    /** Audio the built-in player plays (MIDI and WMA need decoders Android does not have). */
    private val PLAYABLE_AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr")

    fun isPlayableAudio(name: String): Boolean = extensionOf(name) in PLAYABLE_AUDIO

    /** Text files whose first lines make a preview. */
    fun hasTextPreview(name: String): Boolean = kindOf(name) == Kind.TEXT || kindOf(name) == Kind.CODE

    fun mimeOf(name: String): String {
        val ext = extensionOf(name)
        val known = runCatching { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()
        if (!known.isNullOrBlank()) return known
        return when (ext) {
            "apk", "apks", "xapk", "apkm" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "txt", "log", "nfo" -> "text/plain"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "epub" -> "application/epub+zip"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    /** A name of a file the gallery does not show: not a photo or video, not hidden, not ours. */
    fun isListedName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith(".")) return false
        if (NomediaScanner.mimeOf(name) != null) return false
        if (name.endsWith(SecureFolderManager.ENC_EXTENSION)) return false
        if (name.endsWith(".tmp_enc") || name.endsWith(".tmp_dec")) return false
        return true
    }

    /** A plain file of the folder that the gallery does not show. */
    fun isListed(file: File): Boolean = isListedName(file.name) && file.isFile

    /** The folder's other files, as tiles of album [albumId]. */
    fun list(dir: File, albumId: Long, bucketName: String, relativePath: String): List<GalleryMedia> {
        val children = dir.listFiles() ?: return emptyList()
        val out = ArrayList<GalleryMedia>()
        for (file in children) {
            if (!isListed(file)) continue
            out += tile(file, file.length(), file.lastModified(), albumId, bucketName, relativePath)
        }
        return out
    }

    /** The tile of one other file (also rebuilt from [OtherFilesCache] without touching the disk). */
    fun tile(
        file: File,
        sizeBytes: Long,
        modifiedMs: Long,
        albumId: Long,
        bucketName: String,
        relativePath: String,
    ): GalleryMedia = GalleryMedia(
        id = NomediaScanner.syntheticId("other:${file.absolutePath}"),
        uri = Uri.fromFile(file),
        displayName = file.name,
        mimeType = mimeOf(file.name),
        dateTakenMs = 0L,
        dateModifiedMs = modifiedMs,
        dateAddedMs = modifiedMs,
        sizeBytes = sizeBytes,
        width = 0,
        height = 0,
        durationMs = 0L,
        bucketId = albumId,
        bucketName = bucketName,
        relativePath = relativePath,
        absolutePath = file.absolutePath,
        isOtherFile = true,
    )

    /** Relative path ("Documents/Work/") of [dir] on the main storage; null elsewhere. */
    fun relativePathOf(dir: File): String? {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()?.absolutePath ?: return null
        val path = dir.absolutePath
        if (!path.startsWith(root)) return null
        val rel = path.removePrefix(root).trim('/')
        return if (rel.isEmpty()) null else "$rel/"
    }
}
