package dev.ander.alexet.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import dev.ander.alexet.data.EncryptedFormat
import java.io.File
import javax.crypto.SecretKey

/**
 * Plays a private-folder video straight from its `.enc` file: each read decrypts only the 1 MB
 * chunk it needs, so a large video no longer has to fit in memory (and seeking stays cheap).
 */
@OptIn(UnstableApi::class)
class EncryptedVideoDataSource(
    private val file: File,
    private val key: SecretKey,
) : BaseDataSource(/* isNetwork = */ false) {
    private var reader: EncryptedFormat.RandomAccessReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val r = EncryptedFormat.openRandomAccess(file, key)
            ?: throw java.io.IOException("Not a chunked encrypted file")
        // Checked before the reader is kept: a failed open closes it here, and close() then has no
        // "transfer ended" without a "transfer started".
        if (dataSpec.position > r.size) {
            r.close()
            throw androidx.media3.datasource.DataSourceException(
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            )
        }
        reader = r
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.size - position
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: return C.RESULT_END_OF_INPUT
        val n = r.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val r = reader
        reader = null
        uri = null
        if (r != null) {
            r.close()
            transferEnded()
        }
    }

    companion object {
        /** True when [file] can be streamed this way (version 2 format). */
        fun canStream(file: File, key: SecretKey): Boolean =
            runCatching { EncryptedFormat.openRandomAccess(file, key)?.also { it.close() } != null }.getOrDefault(false)
    }
}
