package dev.ander.alexet.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * `.alexet_secure.json`. Version 2: files are encrypted with a random data key, stored wrapped
 * (AES-GCM) under a key derived from the password and, separately, from the backup password.
 * A password is checked by unwrapping, so nothing faster than PBKDF2 is kept next to the files.
 * Version 1 (older folders) kept SHA-256 check values and encrypted files with the key derived
 * from the main password itself; such folders are upgraded on the first unlock.
 */
data class SecureFolderMeta(
    val version: Int = 2,
    val saltBase64: String,
    val passwordHashHex: String? = null,
    val backupPasswordHashHex: String? = null,
    val wrappedKeyBase64: String? = null,
    val backupSaltBase64: String? = null,
    val wrappedBackupKeyBase64: String? = null,
    val hint: String? = null,
    val encryptedDateMs: Long = System.currentTimeMillis(),
    /** Alexet created the `.nomedia` file (it is removed again on permanent decryption). */
    val createdNomedia: Boolean = true,
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("salt", saltBase64)
        if (passwordHashHex != null) obj.put("passwordHash", passwordHashHex)
        if (backupPasswordHashHex != null) obj.put("backupHash", backupPasswordHashHex)
        if (wrappedKeyBase64 != null) obj.put("wrappedKey", wrappedKeyBase64)
        if (backupSaltBase64 != null) obj.put("backupSalt", backupSaltBase64)
        if (wrappedBackupKeyBase64 != null) obj.put("wrappedBackupKey", wrappedBackupKeyBase64)
        if (!hint.isNullOrBlank()) obj.put("hint", hint)
        obj.put("date", encryptedDateMs)
        obj.put("createdNomedia", createdNomedia)
        return obj.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): SecureFolderMeta? = runCatching {
            val obj = JSONObject(jsonStr)
            fun opt(name: String): String? = if (obj.has(name)) obj.getString(name) else null
            SecureFolderMeta(
                version = obj.optInt("version", 1),
                saltBase64 = obj.getString("salt"),
                passwordHashHex = opt("passwordHash"),
                backupPasswordHashHex = opt("backupHash"),
                wrappedKeyBase64 = opt("wrappedKey"),
                backupSaltBase64 = opt("backupSalt"),
                wrappedBackupKeyBase64 = opt("wrappedBackupKey"),
                hint = opt("hint"),
                encryptedDateMs = obj.optLong("date", System.currentTimeMillis()),
                createdNomedia = obj.optBoolean("createdNomedia", true),
            )
        }.getOrNull()
    }
}

object SecureFolderManager {
    const val SECURE_CONFIG_FILE = ".alexet_secure.json"
    const val NOMEDIA_FILE = ".nomedia"
    const val ENC_EXTENSION = ".enc"

    private const val PBKDF2_ITERATIONS = 65536
    private const val KEY_LENGTH_BITS = 256
    const val GCM_IV_LENGTH = 12
    const val GCM_TAG_LENGTH = 128
    private const val BUFFER_SIZE = 64 * 1024
    private const val TEMP_ENC_SUFFIX = ".tmp_enc"
    private const val TEMP_DEC_SUFFIX = ".tmp_dec"
    private val KEY_WRAP_AAD = "alexet-secure-key-v2".toByteArray(StandardCharsets.UTF_8)

    /** Called after a lock so decoded images held elsewhere (image memory cache) can be dropped. */
    @Volatile var onLocked: (() -> Unit)? = null

    private const val PREFS_NAME = "alexet_secure_folders"
    private const val KEY_KNOWN_PATHS = "known_paths"

    private val _unlockedFolderPaths = MutableStateFlow<Set<String>>(emptySet())
    val unlockedFolderPaths: StateFlow<Set<String>> = _unlockedFolderPaths.asStateFlow()

    private val activeKeys = ConcurrentHashMap<String, SecretKey>()
    private var prefs: SharedPreferences? = null

    /** Bumped by every lock: work that started before a lock must not unlock again at its end. */
    private val lockGeneration = AtomicLong(0L)
    /**
     * Held while the folders are locked and while a finished encryption opens its folder: the
     * check "not locked meanwhile" and the opening happen as one, so a lock that came in between
     * is never undone.
     */
    private val lockStateLock = Any()

    /** The known-folder list is read, changed and written as one step (several threads write it). */
    private val knownPathsLock = Any()

    /**
     * One lock per folder for the operations that write its config or its files (encrypt, decrypt,
     * the version upgrade): two of them at once could leave the config of one over the files of
     * the other. Monitors are re-entrant, so these operations can call each other.
     */
    private val folderLocks = ConcurrentHashMap<String, Any>()

    private fun folderLock(dir: File): Any {
        val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath).trimEnd('/')
        return folderLocks.computeIfAbsent(key) { Any() }
    }

    /**
     * Marks a folder whose encryption is in progress (name starts like the config, so the file
     * lists skip it). Found later, the encryption was cut off: [verifyQueryAgainstKnownFolders]
     * finishes it with the key.
     */
    private const val ENCRYPTING_MARKER = "$SECURE_CONFIG_FILE.encrypting"

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun isFolderSecure(dir: File): Boolean {
        return File(dir, SECURE_CONFIG_FILE).isFile
    }

    fun isFolderUnlocked(dir: File): Boolean {
        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath).trimEnd('/')
        val absolute = dir.absolutePath.trimEnd('/')
        val unlocked = _unlockedFolderPaths.value
        return canonical in unlocked || absolute in unlocked ||
            unlocked.any { it.trimEnd('/').equals(canonical, ignoreCase = true) || it.trimEnd('/').equals(absolute, ignoreCase = true) }
    }

    fun getActiveKey(dir: File): SecretKey? {
        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath).trimEnd('/')
        val absolute = dir.absolutePath.trimEnd('/')
        val path = dir.path.trimEnd('/')

        activeKeys[canonical]?.let { return it }
        activeKeys[absolute]?.let { return it }
        activeKeys[path]?.let { return it }

        for ((keyPath, key) in activeKeys) {
            val keyClean = keyPath.trimEnd('/')
            if (keyClean.equals(canonical, ignoreCase = true) ||
                keyClean.equals(absolute, ignoreCase = true) ||
                keyClean.equals(path, ignoreCase = true)
            ) {
                return key
            }
            val keyCanonical = runCatching { File(keyPath).canonicalPath }.getOrDefault(keyPath).trimEnd('/')
            if (keyCanonical.equals(canonical, ignoreCase = true) ||
                keyCanonical.equals(absolute, ignoreCase = true) ||
                keyCanonical.equals(path, ignoreCase = true)
            ) {
                return key
            }
        }
        return null
    }

    fun getActiveKeyForFile(file: File): SecretKey? {
        val parent = file.parentFile
        if (parent != null) {
            getActiveKey(parent)?.let { return it }
        }

        val fileCanonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val fileAbs = file.absolutePath
        for ((folderPath, key) in activeKeys) {
            val folderCanonical = runCatching { File(folderPath).canonicalPath }.getOrDefault(folderPath).trimEnd('/')
            val folderAbs = folderPath.trimEnd('/')
            if (fileCanonical.startsWith(folderCanonical + "/") ||
                fileAbs.startsWith(folderAbs + "/") ||
                fileCanonical.startsWith(folderAbs + "/") ||
                fileAbs.startsWith(folderCanonical + "/")
            ) {
                return key
            }
        }
        return null
    }

    /** How long folders stay open while the app is off screen (screen off, another app). */
    const val LOCK_GRACE_MS = 60_000L
    private val lockHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val delayedLock = Runnable { lockAll() }
    /** elapsedRealtime when the app left the screen; 0 while it is on screen. */
    @Volatile private var hiddenAt = 0L

    /** Folders unlocked now, by their bucket id (the synthetic id their album has). */
    private val unlockedBucketIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * The app left the screen: everything locks after [LOCK_GRACE_MS] unless it comes back first.
     * The delayed lock does not run while the phone sleeps; [onAppShown] checks the real time.
     */
    fun onAppHidden() {
        hiddenAt = android.os.SystemClock.elapsedRealtime()
        lockHandler.removeCallbacks(delayedLock)
        if (activeKeys.isNotEmpty()) lockHandler.postDelayed(delayedLock, LOCK_GRACE_MS)
    }

    /** Back on screen: still open within the grace time, locked (before anything shows) after it. */
    fun onAppShown() {
        lockHandler.removeCallbacks(delayedLock)
        val left = hiddenAt
        hiddenAt = 0L
        if (left > 0L && android.os.SystemClock.elapsedRealtime() - left >= LOCK_GRACE_MS && activeKeys.isNotEmpty()) {
            lockAll()
        }
    }

    /** A private folder of this phone that is locked now (its files cannot be read). */
    fun isSecureBucketLocked(bucketId: Long): Boolean {
        if (bucketId in unlockedBucketIds) return false
        return getKnownSecurePaths().any { NomediaScanner.syntheticBucketId(it) == bucketId }
    }

    fun lockAll() {
        lockHandler.removeCallbacks(delayedLock)
        synchronized(lockStateLock) {
            lockGeneration.incrementAndGet()
            _unlockedFolderPaths.value = emptySet()
            unlockedBucketIds.clear()
            activeKeys.clear()
        }
        SecureRamCache.clear()
        onLocked?.invoke()
    }

    fun lockFolder(dir: File) {
        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath).trimEnd('/')
        val absolute = dir.absolutePath.trimEnd('/')
        synchronized(lockStateLock) {
            lockGeneration.incrementAndGet()
            // update {}: an unlock of another folder on another thread at the same moment stays.
            _unlockedFolderPaths.update { paths ->
                paths.filterNot {
                    val c = it.trimEnd('/')
                    c.equals(canonical, ignoreCase = true) || c.equals(absolute, ignoreCase = true)
                }.toSet()
            }
            activeKeys.remove(canonical)
            activeKeys.remove(absolute)
            activeKeys.remove(dir.path.trimEnd('/'))
            unlockedBucketIds.remove(NomediaScanner.syntheticBucketId(canonical))
        }
        SecureRamCache.removeForFolder(dir)
        onLocked?.invoke()
    }

    fun unlockFolder(dir: File, key: SecretKey) {
        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath).trimEnd('/')
        val absolute = dir.absolutePath.trimEnd('/')
        activeKeys[canonical] = key
        activeKeys[absolute] = key
        activeKeys[dir.path.trimEnd('/')] = key
        unlockedBucketIds += NomediaScanner.syntheticBucketId(canonical)
        _unlockedFolderPaths.update { it + canonical + absolute }
        registerKnownSecurePath(canonical)
    }

    fun readMeta(dir: File): SecureFolderMeta? {
        val file = File(dir, SECURE_CONFIG_FILE)
        if (!file.isFile) return null
        return runCatching { SecureFolderMeta.fromJson(file.readText()) }.getOrNull()
    }

    fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    /** Version 1 check value; only read for folders created before version 2. */
    private fun computeVerificationHash(password: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val hash = md.digest(password.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun newDataKey(): SecretKey {
        val bytes = ByteArray(KEY_LENGTH_BITS / 8)
        SecureRandom().nextBytes(bytes)
        return SecretKeySpec(bytes, "AES")
    }

    /** iv + AES-GCM(kek, data key). */
    private fun wrapKey(dataKey: SecretKey, kek: SecretKey): String {
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.updateAAD(KEY_WRAP_AAD)
        return Base64.encodeToString(iv + cipher.doFinal(dataKey.encoded), Base64.NO_WRAP)
    }

    /** The data key, or null when [kek] is not the one it was wrapped with (wrong password). */
    private fun unwrapKey(wrappedBase64: String, kek: SecretKey): SecretKey? = runCatching {
        val blob = Base64.decode(wrappedBase64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_LENGTH, blob, 0, GCM_IV_LENGTH))
        cipher.updateAAD(KEY_WRAP_AAD)
        SecretKeySpec(cipher.doFinal(blob, GCM_IV_LENGTH, blob.size - GCM_IV_LENGTH), "AES")
    }.getOrNull()

    /** Written next to the old one and renamed over it, so a crash never leaves half a config. */
    private fun writeMeta(dir: File, meta: SecureFolderMeta) {
        val target = File(dir, SECURE_CONFIG_FILE)
        val temp = File(dir, "$SECURE_CONFIG_FILE.tmp")
        FileOutputStream(temp).use { out ->
            out.write(meta.toJson().toByteArray(StandardCharsets.UTF_8))
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("Cannot write ${target.path}")
        }
    }

    /**
     * Encrypts every file in [dir], all or nothing: if any file fails, the ones done are decrypted
     * back, the config is removed and false is returned, so no plain file is left hidden inside a
     * folder that claims to be private.
     */
    suspend fun encryptFolder(
        dir: File,
        password: String,
        hint: String?,
        backupPassword: String?,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) { synchronized(folderLock(dir)) { encryptFolderLocked(dir, password, hint, backupPassword, onProgress) } }

    private fun encryptFolderLocked(
        dir: File,
        password: String,
        hint: String?,
        backupPassword: String?,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        // Already private: a new key would replace the only copy of the one its files use.
        if (isFolderSecure(dir)) return false
        val startGeneration = lockGeneration.get()

        val dataKey = newDataKey()
        val salt = generateSalt()
        val backup = backupPassword?.takeIf { it.isNotBlank() }
        val backupSalt = if (backup != null) generateSalt() else null
        val meta = SecureFolderMeta(
            saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP),
            wrappedKeyBase64 = wrapKey(dataKey, deriveKey(password, salt)),
            backupSaltBase64 = backupSalt?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            wrappedBackupKeyBase64 = if (backup != null && backupSalt != null) wrapKey(dataKey, deriveKey(backup, backupSalt)) else null,
            hint = hint?.takeIf { it.isNotBlank() },
            createdNomedia = !File(dir, NOMEDIA_FILE).exists(),
        )

        val filesToEncrypt = dir.listFiles()?.filter { file ->
            file.isFile &&
                file.name != NOMEDIA_FILE &&
                !file.name.startsWith(SECURE_CONFIG_FILE) &&
                !file.name.endsWith(ENC_EXTENSION) &&
                !file.name.endsWith(TEMP_ENC_SUFFIX) &&
                !file.name.endsWith(TEMP_DEC_SUFFIX)
        }.orEmpty()

        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        // Known before the first file changes: a process killed half-way leaves a folder the
        // password search still finds (and then finishes, see the marker).
        registerKnownSecurePath(canonical)
        runCatching { File(dir, ENCRYPTING_MARKER).createNewFile() }
        if (meta.createdNomedia) runCatching { File(dir, NOMEDIA_FILE).createNewFile() }
        if (runCatching { writeMeta(dir, meta) }.isFailure) {
            if (meta.createdNomedia) File(dir, NOMEDIA_FILE).delete()
            File(dir, ENCRYPTING_MARKER).delete()
            unregisterKnownSecurePath(canonical)
            return false
        }

        val done = ArrayList<File>(filesToEncrypt.size)
        var failed: File? = null
        for ((index, file) in filesToEncrypt.withIndex()) {
            val enc = encryptFile(file, dataKey)
            if (enc == null) {
                failed = file
                break
            }
            done += enc
            onProgress?.invoke(index + 1, filesToEncrypt.size)
        }
        if (failed != null) {
            AppLog.w("AlexetSecure", "Encrypting ${failed.name} failed; restoring ${done.size} file(s) in ${dir.name}")
            val restored = done.count { decryptFile(it, dataKey) != null }
            if (restored == done.size) {
                File(dir, SECURE_CONFIG_FILE).delete()
                if (meta.createdNomedia) File(dir, NOMEDIA_FILE).delete()
                unregisterKnownSecurePath(canonical)
            }
            // Otherwise some files stay encrypted: the config stays (and the folder stays known)
            // so the folder can still be opened.
            File(dir, ENCRYPTING_MARKER).delete()
            return false
        }

        File(dir, ENCRYPTING_MARKER).delete()
        // Locked (app left) while this ran: the folder stays locked instead of opening again.
        synchronized(lockStateLock) {
            if (lockGeneration.get() == startGeneration) unlockFolder(dir, dataKey)
        }
        return true
    }

    /**
     * Decrypts every file back. The config (holding the key material) and the `.nomedia` Alexet
     * added are removed only when every file was decrypted; otherwise false and nothing is lost.
     */
    suspend fun decryptFolderPermanently(
        dir: File,
        key: SecretKey,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) { synchronized(folderLock(dir)) { decryptFolderLocked(dir, key, onProgress) } }

    private fun decryptFolderLocked(
        dir: File,
        key: SecretKey,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val meta = readMeta(dir)

        val encFiles = dir.listFiles()?.filter { file ->
            file.isFile && file.name.endsWith(ENC_EXTENSION)
        }.orEmpty()

        var failures = 0
        encFiles.forEachIndexed { index, file ->
            if (decryptFile(file, key) == null) failures++
            onProgress?.invoke(index + 1, encFiles.size)
        }
        if (failures > 0) {
            AppLog.w("AlexetSecure", "Permanent decryption of ${dir.name}: $failures of ${encFiles.size} file(s) failed; folder kept private")
            return false
        }

        File(dir, SECURE_CONFIG_FILE).delete()
        File(dir, ENCRYPTING_MARKER).delete()
        if (meta?.createdNomedia != false) File(dir, NOMEDIA_FILE).delete()

        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        lockFolder(dir)
        unregisterKnownSecurePath(canonical)
        return true
    }

    /** Encrypts [file] to `<name>.enc` and removes the original; returns the new file or null. */
    fun encryptFile(file: File, key: SecretKey): File? {
        if (!file.exists() || !file.isFile || file.name.endsWith(ENC_EXTENSION)) return null
        val encFile = File(file.parentFile, file.name + ENC_EXTENSION)
        if (encFile.exists()) return null
        val tempFile = File(file.parentFile, file.name + TEMP_ENC_SUFFIX)
        return try {
            FileOutputStream(tempFile).use { out ->
                val buffered = BufferedOutputStream(out, BUFFER_SIZE)
                BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { input ->
                    EncryptedFormat.encrypt(input, buffered, key)
                }
                buffered.flush()
                out.fd.sync() // on disk before the original goes
            }
            if (!tempFile.renameTo(encFile)) {
                tempFile.delete()
                return null
            }
            if (!file.delete()) {
                encFile.delete()
                return null
            }
            encFile
        } catch (e: Throwable) { // OutOfMemoryError included: never leave a half-written file
            tempFile.delete()
            AppLog.w("AlexetSecure", "Encrypt ${file.name} failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Decrypts [encFile] next to it (a free name if the original name is taken); null on failure. */
    fun decryptFile(encFile: File, key: SecretKey): File? {
        val parent = encFile.parentFile ?: return null
        // Two decryptions of the same file (folder decrypt and a single file) would otherwise share
        // the temp file and the free name.
        return synchronized(folderLock(parent)) { decryptFileLocked(encFile, parent, key) }
    }

    private fun decryptFileLocked(encFile: File, parent: File, key: SecretKey): File? {
        if (!encFile.exists() || !encFile.isFile || !encFile.name.endsWith(ENC_EXTENSION)) return null
        val origName = encFile.name.removeSuffix(ENC_EXTENSION)
        val targetFile = freeName(parent, origName)
        val tempFile = File(parent, "$origName.${java.util.UUID.randomUUID().toString().take(8)}$TEMP_DEC_SUFFIX")
        return try {
            val input = openDecryptedStream(encFile, key) ?: return null
            input.use { plain ->
                FileOutputStream(tempFile).use { out ->
                    val buffered = BufferedOutputStream(out, BUFFER_SIZE)
                    plain.copyTo(buffered, bufferSize = BUFFER_SIZE)
                    buffered.flush()
                    out.fd.sync()
                }
            }
            if (tempFile.renameTo(targetFile)) {
                encFile.delete()
                targetFile
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Throwable) {
            tempFile.delete()
            AppLog.w("AlexetSecure", "Decrypt ${encFile.name} failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun freeName(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 1
        while (true) {
            val candidate = File(dir, "$base ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /** Largest old-format file rewritten in the chunked format (it is opened in memory). */
    private const val UPGRADE_MAX_BYTES = 32L * 1024L * 1024L

    /**
     * Rewrites the folder's old-format files (one GCM message per file) in the chunked format,
     * which opens in milliseconds. For each file: written to a temp file, read back and compared
     * with the original, then renamed over it with its date kept (the list is sorted by date).
     * Stops when the folder is locked. Larger files stay as they are (they still open, slower).
     */
    fun upgradeLegacyFiles(dir: File, key: SecretKey) {
        val generation = lockGeneration.get()
        val legacy = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(ENC_EXTENSION) && !EncryptedFormat.isChunked(it) }
            .orEmpty()
        if (legacy.isEmpty()) return
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var upgraded = 0
        var skipped = 0
        var failed = 0
        for (file in legacy) {
            if (lockGeneration.get() != generation) break
            if (file.length() > UPGRADE_MAX_BYTES) {
                skipped++
                continue
            }
            val ok = synchronized(folderLock(dir)) {
                if (lockGeneration.get() != generation) null else upgradeOne(file, key)
            } ?: break
            if (ok) upgraded++ else failed++
        }
        AppLog.event("Secure") {
            "${dir.name}: $upgraded of ${legacy.size} old-format file(s) rewritten in the chunked format " +
                "in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms ($skipped too large, $failed failed)"
        }
    }

    private fun upgradeOne(file: File, key: SecretKey): Boolean {
        if (!file.isFile || EncryptedFormat.isChunked(file)) return true
        val temp = File(file.parentFile, file.name + TEMP_ENC_SUFFIX)
        return try {
            val source = openDecryptedStream(file, key) ?: return false
            FileOutputStream(temp).use { out ->
                val buffered = BufferedOutputStream(out, BUFFER_SIZE)
                source.use { EncryptedFormat.encrypt(it, buffered, key) }
                buffered.flush()
                out.fd.sync()
            }
            if (!sameContent(file, temp, key)) {
                temp.delete()
                AppLog.w("AlexetSecure", "Upgrade of ${file.name}: the new copy differs; kept the old one")
                return false
            }
            temp.setLastModified(file.lastModified())
            // One step from the old file to the new one (no moment without either).
            java.nio.file.Files.move(
                temp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (e: Throwable) { // OutOfMemoryError included: the original is untouched
            temp.delete()
            AppLog.w("AlexetSecure", "Upgrade of ${file.name} failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Both files decrypt to the same bytes. */
    private fun sameContent(a: File, b: File, key: SecretKey): Boolean {
        val first = openDecryptedStream(a, key) ?: return false
        val second = openDecryptedStream(b, key) ?: run {
            first.close()
            return false
        }
        first.use { x ->
            second.use { y ->
                val bufX = ByteArray(BUFFER_SIZE)
                val bufY = ByteArray(BUFFER_SIZE)
                try {
                    while (true) {
                        val nx = fill(x, bufX)
                        val ny = fill(y, bufY)
                        if (nx != ny) return false
                        if (nx == 0) return true
                        for (i in 0 until nx) if (bufX[i] != bufY[i]) return false
                    }
                } finally {
                    java.util.Arrays.fill(bufX, 0)
                    java.util.Arrays.fill(bufY, 0)
                }
            }
        }
    }

    /** [plain] holds the same bytes as [enc] decrypted. */
    private fun sameAsPlain(plain: File, enc: File, key: SecretKey): Boolean {
        val decrypted = openDecryptedStream(enc, key) ?: return false
        val original = runCatching { plain.inputStream() }.getOrElse {
            decrypted.close()
            return false
        }
        original.use { x ->
            decrypted.use { y ->
                val bufX = ByteArray(BUFFER_SIZE)
                val bufY = ByteArray(BUFFER_SIZE)
                try {
                    while (true) {
                        val nx = fill(x, bufX)
                        val ny = fill(y, bufY)
                        if (nx != ny) return false
                        if (nx == 0) return true
                        for (i in 0 until nx) if (bufX[i] != bufY[i]) return false
                    }
                } catch (e: java.io.IOException) {
                    return false
                } finally {
                    java.util.Arrays.fill(bufX, 0)
                    java.util.Arrays.fill(bufY, 0)
                }
            }
        }
    }

    /** Reads until [buf] is full or the stream ends; returns the count. */
    private fun fill(input: InputStream, buf: ByteArray): Int {
        var done = 0
        while (done < buf.size) {
            val n = input.read(buf, done, buf.size - done)
            if (n < 0) break
            done += n
        }
        return done
    }

    /** Plaintext of an `.enc` file (either format); null when it can't be opened with [key]. */
    fun openDecryptedStream(encFile: File, key: SecretKey): InputStream? {
        if (!encFile.exists() || !encFile.isFile) return null
        var raw: InputStream? = null
        return try {
            raw = BufferedInputStream(FileInputStream(encFile), BUFFER_SIZE)
            EncryptedFormat.decryptingStream(raw, key)
        } catch (e: Exception) {
            runCatching { raw?.close() }
            null
        }
    }

    /** Plaintext size of an `.enc` file, from its length and format. */
    fun plaintextSize(encFile: File): Long = runCatching {
        FileInputStream(encFile).use { EncryptedFormat.plaintextSize(encFile.length(), it) }
    }.getOrDefault(0L)

    /** Result of trying a password against the known private folders. */
    data class PasswordMatch(
        val unlocked: List<Pair<File, SecretKey>>,
        /** Folders from before version 2 where this is the backup password, which never held a key there. */
        val legacyBackupOnly: List<File>,
    )

    /** Slow (PBKDF2 per folder, up to the first match): call off the main thread. */
    fun verifyQueryAgainstKnownFolders(query: String): PasswordMatch {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return PasswordMatch(emptyList(), emptyList())

        val results = ArrayList<Pair<File, SecretKey>>()
        val legacyBackup = ArrayList<File>()
        for (path in getKnownSecurePaths()) {
            // The first folder this opens is the one shown: each further one cost a PBKDF2 run
            // (two when the password is wrong), seconds of CPU with many private folders.
            if (results.isNotEmpty()) break
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) continue
            val meta = readMeta(dir) ?: continue
            val salt = runCatching { Base64.decode(meta.saltBase64, Base64.NO_WRAP) }.getOrNull() ?: continue

            val wrapped = meta.wrappedKeyBase64
            if (wrapped != null) {
                val key = unwrapKey(wrapped, deriveKey(trimmed, salt)) ?: run {
                    val backupSalt = meta.backupSaltBase64?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                    val wrappedBackup = meta.wrappedBackupKeyBase64
                    if (backupSalt != null && wrappedBackup != null) unwrapKey(wrappedBackup, deriveKey(trimmed, backupSalt)) else null
                }
                if (key != null) {
                    runCatching { finishInterrupted(dir, key) }
                        .onFailure { AppLog.w("AlexetSecure", "Finishing ${dir.name} failed: ${it.javaClass.simpleName}") }
                    results += dir to key
                }
                continue
            }

            // Version 1.
            val hash = computeVerificationHash(trimmed, salt)
            if (meta.passwordHashHex != null && hash.equals(meta.passwordHashHex, ignoreCase = true)) {
                val key = deriveKey(trimmed, salt)
                runCatching {
                    synchronized(folderLock(dir)) {
                        // Only over the config that was checked: decrypted or re-encrypted meanwhile,
                        // writing it back would revive an old key.
                        if (readMeta(dir) == meta) upgradeLegacyMeta(dir, meta, trimmed, key)
                    }
                }.onFailure { AppLog.w("AlexetSecure", "Upgrading ${dir.name} to version 2 failed: ${it.javaClass.simpleName}") }
                // Plain originals left by a kill are checked here too, now that the key is known.
                runCatching { finishInterrupted(dir, key) }
                    .onFailure { AppLog.w("AlexetSecure", "Finishing ${dir.name} failed: ${it.javaClass.simpleName}") }
                results += dir to key
            } else if (meta.backupPasswordHashHex != null && hash.equals(meta.backupPasswordHashHex, ignoreCase = true)) {
                legacyBackup += dir
            }
        }
        return PasswordMatch(results, legacyBackup)
    }

    /**
     * Version 1 -> 2 with the files untouched: the key they are encrypted with becomes the data
     * key, wrapped under the main password with a fresh salt. The version 1 backup password
     * never led to that key, so it can't be carried over; the SHA-256 check values are dropped.
     */
    private fun upgradeLegacyMeta(dir: File, meta: SecureFolderMeta, password: String, key: SecretKey) {
        val salt = generateSalt()
        writeMeta(
            dir,
            meta.copy(
                version = 2,
                saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP),
                passwordHashHex = null,
                backupPasswordHashHex = null,
                wrappedKeyBase64 = wrapKey(key, deriveKey(password, salt)),
                backupSaltBase64 = null,
                wrappedBackupKeyBase64 = null,
            ),
        )
    }

    fun authenticateWithBiometrics(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No screen lock or fingerprint on this device: the prompt can only fail, and the
            // correct password (already checked) would never open the folder. The password is
            // the protection here; the prompt is an extra step where the device offers one.
            onSuccess()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(promptInfo)
    }

    /**
     * A private folder was renamed: forget the old path, remember the new one and keep it open
     * (with its key) if it was open, or the password search could no longer find it.
     */
    fun folderRenamed(oldDir: File, oldCanonical: String, newDir: File) {
        val newCanonical = runCatching { newDir.canonicalPath }.getOrDefault(newDir.absolutePath).trimEnd('/')
        val key = getActiveKey(oldDir)
        if (key != null) {
            lockFolder(oldDir)
            unlockFolder(newDir, key)
        }
        changeKnownPaths { current ->
            current -= oldCanonical
            current -= oldDir.absolutePath.trimEnd('/')
            current += newCanonical
        }
    }

    fun getKnownSecurePaths(): Set<String> {
        return prefs?.getStringSet(KEY_KNOWN_PATHS, emptySet())?.toSet() ?: emptySet()
    }

    fun registerKnownSecurePath(path: String) {
        if (path in getKnownSecurePaths()) return
        changeKnownPaths { it += path }
    }

    fun unregisterKnownSecurePath(path: String) {
        changeKnownPaths { it -= path }
    }

    /**
     * Read, change and write in one step: the encryption, the scanner, a rename and an import
     * write the list from different threads and would otherwise drop each other's folder. Written
     * to disk at once off the main thread (a private folder must not be forgotten by a killed process).
     */
    private fun changeKnownPaths(change: (MutableSet<String>) -> Unit) {
        val p = prefs ?: return
        synchronized(knownPathsLock) {
            val current = getKnownSecurePaths().toMutableSet()
            change(current)
            val editor = p.edit().putStringSet(KEY_KNOWN_PATHS, current)
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) editor.apply() else editor.commit()
        }
    }

    /**
     * Leftovers of work cut off by a killed process, in the known private folders. Needs no key:
     * only temp files (a partial copy, the source still exists) are removed. A plain file next to
     * an `.enc` of its name waits for [finishInterrupted], which has the key to compare them: by
     * the size alone a different file of the same name and size was deleted.
     */
    fun cleanUpInterrupted() {
        for (path in getKnownSecurePaths()) {
            val dir = File(path)
            if (!dir.isDirectory || !isFolderSecure(dir)) continue
            synchronized(folderLock(dir)) {
                val files = dir.listFiles().orEmpty()
                for (file in files) {
                    if (!file.isFile) continue
                    if (file.name.endsWith(TEMP_ENC_SUFFIX) || file.name.endsWith(TEMP_DEC_SUFFIX)) {
                        file.delete()
                    }
                }
            }
        }
    }

    /**
     * Work cut off by a killed process, now that the folder's key is known: a plain file whose
     * `.enc` holds the very same bytes is the original the encryption had not removed yet; an
     * encryption cut off half-way (marker still there) gets its files left plain encrypted.
     */
    private fun finishInterrupted(dir: File, key: SecretKey) {
        synchronized(folderLock(dir)) {
            val interrupted = File(dir, ENCRYPTING_MARKER).exists()
            val plain = dir.listFiles()?.filter { file ->
                file.isFile &&
                    file.name != NOMEDIA_FILE &&
                    !file.name.startsWith(SECURE_CONFIG_FILE) &&
                    !file.name.endsWith(ENC_EXTENSION) &&
                    !file.name.endsWith(TEMP_ENC_SUFFIX) &&
                    !file.name.endsWith(TEMP_DEC_SUFFIX)
            }.orEmpty()
            var failed = 0
            for (file in plain) {
                val enc = File(dir, file.name + ENC_EXTENSION)
                if (enc.isFile && plaintextSize(enc) == file.length() && sameAsPlain(file, enc, key)) {
                    AppLog.w("AlexetSecure", "Removing the plain original of ${enc.name} left by an interrupted encryption")
                    file.delete()
                    continue
                }
                if (!interrupted) continue
                if (encryptFile(file, key) == null) failed++
            }
            if (interrupted) {
                AppLog.w("AlexetSecure", "Finished the interrupted encryption of ${dir.name}: ${plain.size - failed} file(s), $failed failed")
                if (failed == 0) File(dir, ENCRYPTING_MARKER).delete()
            }
        }
    }
}
