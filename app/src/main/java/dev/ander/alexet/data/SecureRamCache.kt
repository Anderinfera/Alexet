package dev.ander.alexet.data

import java.io.File
import java.util.Arrays
import java.util.LinkedHashMap
import javax.crypto.SecretKey

/**
 * In-memory RAM cache for decrypted files from secure AES folders (.enc).
 * Strict constraints:
 * - Max 6 files OR 128 MB total decrypted payload (whichever is reached first).
 * - Arrays stored in the cache never leave it: readers always receive a private copy, so eviction can
 *   wipe an entry without corrupting a decode that is still running on another thread.
 * - Callers own every array returned from [get] / [getOrDecrypt] and should wipe it after use.
 * - Immediate wipe (zeroing memory) when locking folder, resetting session, or leaving.
 */
object SecureRamCache {
    private const val MAX_ENTRIES = 6
    private const val MAX_TOTAL_BYTES = 128L * 1024L * 1024L // 128 MB total RAM cache
    private const val MAX_SINGLE_FILE_BYTES = 64L * 1024L * 1024L // 64 MB max single file payload

    private val lock = Any()

    // Canonical path of the .enc file -> decrypted bytes (exactly one key per file)
    private val memoryCache = LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true)
    private var currentTotalBytes = 0L
    /** Bumped by every lock; a decrypt that started before it must not refill the cache. */
    private var lockGeneration = 0L

    private fun keyOf(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    /** Returns a private copy of the cached plaintext, or null on a cache miss. */
    fun get(file: File): ByteArray? {
        val key = keyOf(file)
        return synchronized(lock) { memoryCache[key]?.copyOf() }
    }

    /**
     * Returns the decrypted bytes of [file] (a private copy owned by the caller), or null if decryption fails.
     * On a miss the file is decrypted outside the lock; when [cache] is true and it fits, a copy is kept in RAM.
     * Pass `cache = false` for large payloads (videos) that would only evict the images being browsed.
     */
    fun getOrDecrypt(file: File, key: SecretKey, cache: Boolean = true): ByteArray? {
        get(file)?.let { return it }
        val generation = synchronized(lock) { lockGeneration }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val bytes = decrypt(file, key)
        AppLog.event("Secure") {
            "decrypted ${file.name}: ${bytes?.size ?: "failed"} B in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms " +
                "(format ${if (EncryptedFormat.isChunked(file)) "v2 chunked" else "v1 single"})"
        }
        if (bytes == null) return null
        if (cache && bytes.size <= MAX_SINGLE_FILE_BYTES) {
            put(keyOf(file), bytes.copyOf(), generation)
        }
        return bytes
    }

    /** Takes ownership of [bytes]; they are wiped on eviction or when they do not fit. */
    fun put(file: File, bytes: ByteArray): Boolean = put(keyOf(file), bytes)

    private fun put(key: String, bytes: ByteArray, generation: Long? = null): Boolean = synchronized(lock) {
        if (generation != null && generation != lockGeneration) {
            // Locked while this was being decrypted.
            Arrays.fill(bytes, 0.toByte())
            return@synchronized false
        }
        memoryCache.remove(key)?.let { old ->
            currentTotalBytes -= old.size
            Arrays.fill(old, 0.toByte())
        }
        if (bytes.size > MAX_SINGLE_FILE_BYTES) {
            Arrays.fill(bytes, 0.toByte())
            return@synchronized false
        }
        while (memoryCache.isNotEmpty() && (memoryCache.size >= MAX_ENTRIES || currentTotalBytes + bytes.size > MAX_TOTAL_BYTES)) {
            val eldest = memoryCache.entries.iterator().next()
            memoryCache.remove(eldest.key)
            currentTotalBytes -= eldest.value.size
            Arrays.fill(eldest.value, 0.toByte())
        }
        memoryCache[key] = bytes
        currentTotalBytes += bytes.size
        true
    }

    private fun decrypt(file: File, key: SecretKey): ByteArray? {
        val stream = SecureFolderManager.openDecryptedStream(file, key) ?: return null
        val expectedSize = SecureFolderManager.plaintextSize(file).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        // The buffer being filled: wiped when the decryption fails half-way (bad tag, I/O error).
        var out = ByteArray(0)
        return runCatching {
            stream.use { input ->
                out = ByteArray(expectedSize)
                var totalRead = 0
                while (true) {
                    if (totalRead == out.size) {
                        // Size estimate was too small: grow instead of truncating
                        val next = input.read()
                        if (next == -1) break
                        val grown = out.copyOf(maxOf(out.size * 2, 8192))
                        Arrays.fill(out, 0.toByte())
                        out = grown
                        out[totalRead++] = next.toByte()
                        continue
                    }
                    val read = input.read(out, totalRead, out.size - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                if (totalRead == out.size) {
                    out
                } else {
                    out.copyOf(totalRead).also { Arrays.fill(out, 0.toByte()) }
                }
            }
        }.getOrElse {
            Arrays.fill(out, 0.toByte())
            null
        }
    }

    /**
     * Wipes arrays taken out of the cache off the calling thread: a lock runs on the main thread
     * and zeroing up to 128 MB there froze the screen. The arrays are already out of the cache.
     */
    private val wiper = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "SecureRamCacheWipe").apply { isDaemon = true }
    }

    private fun wipeLater(arrays: List<ByteArray>) {
        if (arrays.isEmpty()) return
        wiper.execute { arrays.forEach { Arrays.fill(it, 0.toByte()) } }
    }

    fun preloadFolder(dir: File, key: SecretKey) {
        val encFiles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(SecureFolderManager.ENC_EXTENSION) }.orEmpty()
        val sortedFiles = encFiles
            .filter { file ->
                val origName = file.name.removeSuffix(SecureFolderManager.ENC_EXTENSION)
                val mime = NomediaScanner.mimeOf(File(origName))
                mime?.startsWith("image/") == true && file.length() <= 16L * 1024L * 1024L
            }
            .sortedWith(compareBy(AlphanumericComparator) { it.name })
        val generation = synchronized(lock) { lockGeneration }
        for (file in sortedFiles.take(MAX_ENTRIES)) {
            val cacheKey = keyOf(file)
            val alreadyCached = synchronized(lock) {
                if (currentTotalBytes + file.length() >= MAX_TOTAL_BYTES || memoryCache.size >= MAX_ENTRIES) return
                memoryCache.containsKey(cacheKey)
            }
            if (alreadyCached) continue
            val bytes = decrypt(file, key) ?: continue
            if (!put(cacheKey, bytes, generation)) return
        }
    }

    fun removeForFolder(dir: File) {
        val dirPrefix = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath).trimEnd(File.separatorChar) + File.separator
        val removed = ArrayList<ByteArray>()
        synchronized(lock) {
            lockGeneration++
            val toRemove = memoryCache.keys.filter { it.startsWith(dirPrefix) }
            for (key in toRemove) {
                val bytes = memoryCache.remove(key)
                if (bytes != null) {
                    currentTotalBytes -= bytes.size
                    removed += bytes
                }
            }
        }
        wipeLater(removed)
    }

    fun clear() {
        val removed = synchronized(lock) {
            lockGeneration++
            val all = memoryCache.values.toList()
            memoryCache.clear()
            currentTotalBytes = 0L
            all
        }
        wipeLater(removed)
    }
}
