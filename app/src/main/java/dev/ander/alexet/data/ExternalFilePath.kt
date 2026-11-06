package dev.ander.alexet.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Where a file another app hands over by a `content://` address lies on the storage, when that can
 * be known: the provider's own path column, a storage, downloads or media document id, a path the
 * address carries itself (file managers), the one library file with the same name and size, or the
 * one MediaStore row with that name and size. Only a file that is there and readable is returned.
 */
object ExternalFilePath {

    private const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
    private const val DOWNLOADS_DOCUMENTS = "com.android.providers.downloads.documents"
    private const val MEDIA_DOCUMENTS = "com.android.providers.media.documents"

    /** @param sizeBytes The size the provider reports, 0 when unknown (no name-and-size match then). */
    fun resolve(
        context: Context,
        uri: Uri,
        displayName: String,
        sizeBytes: Long,
        library: List<GalleryMedia>,
    ): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.takeIf(::isReadableFile)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val resolver = context.contentResolver
        dataColumn(resolver, uri)?.let { return it }
        documentPath(context, uri)?.let { return it }
        fromUriPath(uri.path)?.takeIf(::isReadableFile)?.let { return it }
        if (sizeBytes <= 0L || displayName.isBlank()) return null
        // Name and size both: a name alone is often shared (IMG_0001.jpg), and two copies leave it unknown.
        library.filter { item ->
            !item.isEncrypted && item.sizeBytes == sizeBytes && item.displayName == displayName && item.absolutePath != null
        }.singleOrNull()?.absolutePath?.takeIf(::isReadableFile)?.let { return it }
        return byNameAndSize(resolver, displayName, sizeBytes)
    }

    /** A path written in a document id: `primary:Download/a.png`, `1234-ABCD:DCIM/a.jpg`, `raw:/storage/…`. */
    internal fun pathFromDocumentId(authority: String?, documentId: String, primaryRoot: String): String? =
        when (authority) {
            EXTERNAL_STORAGE_DOCUMENTS -> {
                val volume = documentId.substringBefore(':', missingDelimiterValue = "")
                val rest = documentId.substringAfter(':', missingDelimiterValue = "")
                when {
                    volume.isEmpty() || rest.isEmpty() -> null
                    volume.equals("primary", ignoreCase = true) -> "$primaryRoot/$rest"
                    volume.equals("home", ignoreCase = true) -> "$primaryRoot/${Environment.DIRECTORY_DOCUMENTS}/$rest"
                    else -> "/storage/$volume/$rest"
                }
            }
            DOWNLOADS_DOCUMENTS -> documentId.takeIf { it.startsWith("raw:") }?.removePrefix("raw:")
            else -> null
        }

    /** The MediaStore id in a media (`image:123`) or downloads (`msf:123`) document id. */
    internal fun mediaStoreIdOf(authority: String?, documentId: String): Long? = when (authority) {
        MEDIA_DOCUMENTS -> documentId.substringAfter(':', missingDelimiterValue = "").toLongOrNull()
        DOWNLOADS_DOCUMENTS -> documentId.takeIf { it.startsWith("msf:") }?.substringAfter(':')?.toLongOrNull()
        else -> null
    }

    /** A path the address carries (`content://<file manager>/storage/emulated/0/…`, `…/file:///storage/…`). */
    internal fun fromUriPath(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        val start = path.indexOf("/storage/").takeIf { it >= 0 }
            ?: path.indexOf("/sdcard/").takeIf { it >= 0 }
            ?: return null
        return path.substring(start)
    }

    private fun documentPath(context: Context, uri: Uri): String? {
        if (!runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)) return null
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        @Suppress("DEPRECATION")
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        pathFromDocumentId(uri.authority, id, primaryRoot)?.takeIf(::isReadableFile)?.let { return it }
        val resolver = context.contentResolver
        mediaStoreIdOf(uri.authority, id)?.let { mediaId ->
            return dataColumn(resolver, ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), mediaId))
        }
        // An id of the download manager itself: its own path column (not readable on every Android version).
        val downloadId = id.takeIf { uri.authority == DOWNLOADS_DOCUMENTS }?.toLongOrNull() ?: return null
        return dataColumn(resolver, ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), downloadId))
    }

    @Suppress("DEPRECATION")
    private fun dataColumn(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            val index = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (index >= 0 && c.moveToFirst() && !c.isNull(index)) c.getString(index) else null
        }
    }.getOrNull()?.takeIf(::isReadableFile)

    @Suppress("DEPRECATION")
    private fun byNameAndSize(resolver: ContentResolver, displayName: String, sizeBytes: Long): String? = runCatching {
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns.DATA),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?",
            arrayOf(displayName, sizeBytes.toString()),
            null,
        )?.use { c ->
            // Only the one row: two copies elsewhere leave it unknown.
            if (c.count == 1 && c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()?.takeIf(::isReadableFile)

    private fun isReadableFile(path: String): Boolean =
        path.isNotBlank() && runCatching { File(path).let { it.isFile && it.canRead() } }.getOrDefault(false)
}
