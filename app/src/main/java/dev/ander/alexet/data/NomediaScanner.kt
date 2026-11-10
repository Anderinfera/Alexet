package dev.ander.alexet.data

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import dev.ander.alexet.AlexetApplication
import java.io.File

data class NomediaScanResult(
    val media: List<GalleryMedia>,
    val hiddenRelativePaths: List<String>,
) {
    companion object {
        val Empty = NomediaScanResult(emptyList(), emptyList())
    }
}

object StorageAccess {
    fun canReadAllFiles(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun requestAllFiles(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        val pkg = Uri.parse("package:${app.packageName}")
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = pkg
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { app.startActivity(specific) }.onFailure {
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

internal class NomediaScanner(context: Context) {

    private val appContext = context.applicationContext

    /** The folder tree, for finding hidden folders: a folder's sub-folders, and whether it is hidden or private. */
    private class Structure(val subdirs: List<DirEntry>, val hasNomedia: Boolean, val isPrivate: Boolean)

    /** A folder of a hidden tree: its photos and videos (with size and date) and its sub-folders. */
    private class Contents(val media: List<DirEntry>, val subdirs: List<DirEntry>)

    /** No age limit: a sub-folder, `.nomedia` or private folder file that comes or goes changes the date. */
    private val structures = DirSnapshotCache(::readStructure)

    /** Sizes and dates of files rewritten in place do not change the folder's date: read again now and then. */
    private val contents = DirSnapshotCache(::readContents, maxAgeMs = CONTENTS_MAX_AGE_MS)

    /**
     * Every hidden folder of the storage and its files. Folders whose date did not change since the
     * last walk are not listed again; [force] (the refresh gesture) lists every folder.
     */
    fun scan(force: Boolean = false): NomediaScanResult {
        val roots = distinctRoots()
        if (roots.isEmpty()) return NomediaScanResult.Empty
        val nomediaDirs = ArrayList<Pair<File, File>>()
        val visited = HashSet<String>()
        for (root in roots) {
            findNomediaDirs(root, root, viaLink = false, depth = 0, nomediaDirs, visited, force)
        }
        if (nomediaDirs.isEmpty()) return NomediaScanResult.Empty
        val media = ArrayList<GalleryMedia>()
        val hiddenPaths = LinkedHashSet<String>()
        val seenFiles = HashSet<String>()
        for ((root, dir) in nomediaDirs) {
            hiddenPaths += relativePathOf(dir, root)
            collectMedia(root, dir, media, seenFiles, force = force)
        }
        return NomediaScanResult(media, hiddenPaths.toList())
    }

    /** The files of the hidden folders already known ([relativePaths]); unchanged folders are not listed again. */
    fun scanKnown(
        relativePaths: List<String>,
        maxFilesPerAlbum: Int = MAX_FILES_PER_ALBUM,
        force: Boolean = false,
    ): NomediaScanResult {
        if (relativePaths.isEmpty()) return NomediaScanResult.Empty
        val roots = distinctRoots()
        if (roots.isEmpty()) return NomediaScanResult.Empty
        val media = ArrayList<GalleryMedia>()
        val hiddenPaths = LinkedHashSet<String>()
        val seenFiles = HashSet<String>()
        for (raw in relativePaths) {
            val relative = raw.trim().trimStart('/').trimEnd('/')
            if (relative.isEmpty()) continue
            // The first storage holding the folder, as directoryForRelativePath picks it.
            var found: Pair<File, Structure>? = null
            for (root in roots) {
                val structure = structures.get(File(root, relative), force) ?: continue
                found = root to structure
                break
            }
            val (root, structure) = found ?: continue
            if (!structure.hasNomedia || structure.isPrivate) continue
            val dir = File(root, relative)
            hiddenPaths += relativePathOf(dir, root)
            collectMedia(root, dir, media, seenFiles, maxFilesPerAlbum, force)
        }
        return NomediaScanResult(media, hiddenPaths.toList())
    }

    /** A file in [dir] changed (a download finished, a file rewritten): the folder is listed again next time. */
    fun forget(dir: File) {
        contents.invalidate(dir)
        structures.invalidate(dir)
    }

    fun directoryForRelativePath(relativePath: String): File? = Companion.directoryForRelativePath(relativePath, appContext)

    fun rescanTree(dir: File) {
        val paths = ArrayList<String>()
        runCatching {
            dir.walkTopDown()
                .maxDepth(16)
                .onEnter { !it.name.startsWith(".") || it == dir }
                .filter { it.isFile && mimeOf(it) != null }
                .take(MAX_FILES) // stops the walk itself, not just one element
                .forEach { file -> paths += file.absolutePath }
        }
        if (paths.isEmpty()) {
            MediaScannerConnection.scanFile(appContext, arrayOf(dir.absolutePath), null, null)
        } else {
            paths.chunked(150).forEach { batch ->
                MediaScannerConnection.scanFile(appContext, batch.toTypedArray(), null, null)
            }
        }
    }

    private fun findNomediaDirs(
        root: File,
        dir: File,
        viaLink: Boolean,
        depth: Int,
        out: MutableList<Pair<File, File>>,
        visited: MutableSet<String>,
        force: Boolean,
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_NOMEDIA_DIRS) return
        val key = if (viaLink) canonicalOf(dir) else dir.path
        if (!visited.add(key)) return
        if (skippedByName(root, dir)) return
        val structure = structures.get(dir, force) ?: return
        if (structure.isPrivate) {
            registerPrivate(dir)
            return
        }
        if (structure.hasNomedia) {
            out += root to dir
            return
        }
        for (child in structure.subdirs) {
            findNomediaDirs(root, File(dir, child.name), viaLink || child.isLink, depth + 1, out, visited, force)
            if (out.size >= MAX_NOMEDIA_DIRS) return
        }
    }

    /**
     * A folder for the walk: private and hidden folders are told by name and one stat of that file,
     * so their other entries (thousands of `.enc` files, a hidden tree) are not stat-ed for it.
     */
    private fun readStructure(dir: File): Structure? {
        // Private first: a private folder holds a `.nomedia` too, and is never a hidden folder.
        // A stat each, before listing: a big hidden folder's names are not needed for the walk.
        if (File(dir, SecureFolderManager.SECURE_CONFIG_FILE).isFile) return Structure(emptyList(), hasNomedia = false, isPrivate = true)
        if (File(dir, ".nomedia").isFile) return Structure(emptyList(), hasNomedia = true, isPrivate = false)
        val names = dir.list() ?: return null
        // The same names written otherwise (a storage that tells case apart).
        val config = names.firstOrNull { it.equals(SecureFolderManager.SECURE_CONFIG_FILE, ignoreCase = true) }
        if (config != null && File(dir, config).isFile) return Structure(emptyList(), hasNomedia = false, isPrivate = true)
        val nomedia = names.firstOrNull { it.equals(".nomedia", ignoreCase = true) }
        if (nomedia != null && File(dir, nomedia).isFile) return Structure(emptyList(), hasNomedia = true, isPrivate = false)
        val subdirs = names.mapNotNull { name -> DirSnapshotCache.entryOf(dir, name)?.takeIf { it.isDirectory } }
        return Structure(subdirs, hasNomedia = false, isPrivate = false)
    }

    /** A folder of a hidden tree: one stat per entry gives the photos' sizes and dates and the sub-folders. */
    private fun readContents(dir: File): Contents? {
        val names = dir.list() ?: return null
        val media = ArrayList<DirEntry>()
        val subdirs = ArrayList<DirEntry>()
        for (name in names) {
            val entry = DirSnapshotCache.entryOf(dir, name) ?: continue
            when {
                entry.isDirectory -> subdirs += entry
                entry.isFile && mimeOf(name) != null -> media += entry
            }
        }
        return Contents(media, subdirs)
    }

    private fun collectMedia(
        root: File,
        start: File,
        media: MutableList<GalleryMedia>,
        seenFiles: MutableSet<String>,
        maxPerAlbum: Int = MAX_FILES_PER_ALBUM,
        force: Boolean = false,
    ) {
        val byParent = LinkedHashMap<String, List<DirEntry>>()
        val stack = ArrayDeque<Pair<File, Boolean>>()
        val walked = HashSet<String>()
        stack.add(start to false)
        var dirs = 0
        while (stack.isNotEmpty() && dirs < MAX_DIRS_PER_TREE) {
            val (dir, viaLink) = stack.removeFirst()
            val key = if (viaLink) canonicalOf(dir) else dir.path
            if (!walked.add(key)) continue
            if (dir != start && (skippedByName(root, dir) || isPrivate(dir))) continue
            val folder = contents.get(dir, force) ?: continue
            dirs++
            if (folder.media.isNotEmpty()) byParent[dir.path] = folder.media
            for (child in folder.subdirs) stack.addLast(File(dir, child.name) to (viaLink || child.isLink))
        }
        // Same for every file here (they all belong to [start]): worked out once, not per file.
        val relPath = relativePathOf(start, root)
        for ((parent, files) in byParent) {
            val dated = files.sortedByDescending { it.modifiedMs }
            var added = 0
            for (entry in dated) {
                if (added >= maxPerAlbum) break
                if (media.size >= MAX_FILES && added >= 1) break
                val path = "$parent/${entry.name}"
                if (!seenFiles.add(path)) continue
                val item = toMedia(entry, path, start, relPath) ?: continue
                media += item
                added++
            }
        }
    }

    private fun toMedia(entry: DirEntry, path: String, albumDir: File, relativePath: String): GalleryMedia? {
        val mime = mimeOf(entry.name) ?: return null
        val modified = entry.modifiedMs.coerceAtLeast(0L)
        val bucketKey = relativePath.trim().trim('/').ifBlank { albumDir.name }
        return GalleryMedia(
            id = syntheticId(path),
            uri = Uri.fromFile(File(path)),
            displayName = entry.name,
            mimeType = mime,
            dateTakenMs = 0L,
            dateModifiedMs = modified,
            dateAddedMs = modified,
            sizeBytes = entry.size,
            width = 0,
            height = 0,
            durationMs = 0L,
            bucketId = syntheticBucketId(bucketKey),
            bucketName = albumDir.name.ifBlank { "Album" },
            relativePath = relativePath,
            isHidden = true,
            absolutePath = path,
        )
    }

    /** Folders never walked: system and trash folders, thumbnails, and app-private parts of Android/. */
    private fun skippedByName(root: File, dir: File): Boolean {
        val name = dir.name
        if (name.equals("lost+found", ignoreCase = true)) return true
        if (name.equals(".thumbnails", ignoreCase = true) || name.equals("thumbnails", ignoreCase = true)) {
            return true
        }
        if (name.equals(".trashed", ignoreCase = true) ||
            name.equals(".recycle", ignoreCase = true) ||
            name.equals(".alexet_trash", ignoreCase = true) ||
            name.contains("alexet_trash", ignoreCase = true)
        ) {
            return true
        }
        val parent = dir.parentFile ?: return false
        if (
            parent.name.equals("Android", ignoreCase = true) &&
            parent.parentFile?.absolutePath.equals(root.absolutePath, ignoreCase = true)
        ) {
            return !name.equals("media", ignoreCase = true)
        }
        return false
    }

    /** A private folder (its key file inside): registered when found, never walked. */
    private fun isPrivate(dir: File): Boolean {
        if (!File(dir, SecureFolderManager.SECURE_CONFIG_FILE).isFile) return false
        registerPrivate(dir)
        return true
    }

    /** A private folder copied from another phone is unknown here until found: registered so its password unlocks it. */
    private fun registerPrivate(dir: File) {
        val canonical = canonicalOf(dir)
        if (canonical !in SecureFolderManager.getKnownSecurePaths()) {
            SecureFolderManager.registerKnownSecurePath(canonical)
        }
    }

    private fun canonicalOf(dir: File): String = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)

    /** The storages, each once: two roots naming the same storage (a link) are walked once. */
    private fun distinctRoots(): List<File> {
        val seen = HashSet<String>()
        return storageRoots().filter { seen.add(canonicalOf(it)) }
    }

    /** [dir] below [root] ("a/b/"); worked out once per hidden folder, not per file. */
    private fun relativePathOf(dir: File, root: File): String {
        val rel = runCatching {
            dir.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
        }.getOrDefault("")
        if (rel.isEmpty() || rel == ".") return ""
        return rel.trimEnd('/') + "/"
    }

    private fun storageRoots(): List<File> = Companion.storageRoots(appContext)

    companion object {
        private const val MAX_DEPTH = 16
        private const val MAX_FILES = 200_000
        private const val MAX_FILES_PER_ALBUM = 100_000
        private const val MAX_NOMEDIA_DIRS = 2_000
        private const val MAX_DIRS_PER_TREE = 20_000
        /** A hidden folder's files (sizes, dates) are listed again after this long even when the folder kept its date. */
        private const val CONTENTS_MAX_AGE_MS = 10 * 60_000L

        fun storageRoots(context: Context? = null): List<File> {
            val ctx = context ?: runCatching { AlexetApplication.instance }.getOrNull()
            val roots = LinkedHashSet<File>()
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()?.let { roots += it }
            if (ctx != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ctx.getSystemService(StorageManager::class.java)
                        ?.storageVolumes
                        ?.mapNotNull { it.directory }
                        ?.forEach { roots += it }
                }
                ctx.getExternalFilesDirs(null)?.forEach { filesDir ->
                    val volume = filesDir?.parentFile?.parentFile?.parentFile?.parentFile
                    if (volume != null) roots += volume
                }
            }
            return roots.filter { it.exists() }
        }

        fun directoryForRelativePath(relativePath: String, context: Context? = null): File? {
            val relative = relativePath.trim().trimStart('/').trimEnd('/')
            if (relative.isEmpty()) return null
            return storageRoots(context).firstNotNullOfOrNull { root ->
                File(root, relative).takeIf { it.isDirectory }
            }
        }

        fun mimeOf(name: String): String? {
            val dot = name.lastIndexOf('.')
            val ext = if (dot >= 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else ""
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                "heif" -> "image/heif"
                "bmp" -> "image/bmp"
                "dng" -> "image/x-adobe-dng"
                "avif" -> "image/avif"
                "tif", "tiff" -> "image/tiff"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "3gp" -> "video/3gpp"
                "mov" -> "video/quicktime"
                "m4v" -> "video/x-m4v"
                "avi" -> "video/x-msvideo"
                "ts" -> "video/mp2t"
                "mpeg", "mpg" -> "video/mpeg"
                else -> null
            }
        }

        fun mimeOf(file: File): String? = mimeOf(file.name)

        /**
         * A photo's or video's type: the system's, unless the system gave none or only a general
         * one ("application/binary", "application/octet-stream"...) to a file named as one.
         */
        fun mediaMimeOf(systemMime: String?, name: String): String? {
            val byName = mimeOf(name) ?: return systemMime
            val general = systemMime.isNullOrBlank() || systemMime.startsWith("application/", ignoreCase = true)
            return if (general) byName else systemMime
        }

        /**
         * [path] lies in a folder the walk for hidden folders never enters (thumbnails, trash and
         * system folders, app-private parts of Android/): a change there is never a hidden folder's.
         */
        fun isInUnwalkedFolder(path: String): Boolean {
            val segments = path.split('/')
            for (i in 0 until segments.size - 1) {
                val name = segments[i]
                if (name.equals("lost+found", ignoreCase = true) ||
                    name.equals(".thumbnails", ignoreCase = true) || name.equals("thumbnails", ignoreCase = true) ||
                    name.equals(".trashed", ignoreCase = true) || name.equals(".recycle", ignoreCase = true) ||
                    name.contains("alexet_trash", ignoreCase = true)
                ) {
                    return true
                }
            }
            val relative = HiddenFolderMarker.volumeRelative(path) ?: return false
            return relative.startsWith("Android/", ignoreCase = true) &&
                !relative.startsWith("Android/media/", ignoreCase = true)
        }

        fun syntheticId(key: String): Long {
            var hash = fnv64(key)
            if (hash >= 0L) hash = hash.inv()
            if (hash == 0L || hash == AlbumIds.TRASH || hash == AlbumIds.FAVORITES) {
                hash = -1000L - kotlin.math.abs(hash % 100000L)
            }
            return hash
        }

        fun syntheticBucketId(dirPath: String): Long {
            val norm = dirPath.trim().trim('/').replace('\\', '/').lowercase()
            val hash = fnv64("bucket:$norm") and 0x0000FFFFFFFFFFFFL
            return hash or (0x1ABC.toLong() shl 48)
        }

        private fun fnv64(key: String): Long {
            var hash = -0x340dcc4b64e4c9d6L
            for (char in key) {
                hash = hash xor char.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash
        }
    }
}
