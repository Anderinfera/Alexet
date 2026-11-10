package dev.ander.alexet.data.smartsearch

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.GalleryRepository
import dev.ander.alexet.data.MediaRefs
import dev.ander.alexet.data.SecureFolderManager
import dev.ander.alexet.data.SettingsStore
import dev.ander.alexet.integration.SmartIndexJobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.math.ln
import dev.ander.alexet.data.AppLog as Log

/**
 * Text-to-photo search on the phone (SigLIP 2). The model is a pack the user imports
 * (tools/smart-search/build_pack.py makes it); photos are indexed while the app is open and,
 * by [SmartIndexJobService], while the phone charges.
 */
class SmartSearch(context: Context, private val settings: SettingsStore, private val repository: GalleryRepository) {

    sealed interface ModelState {
        data object Missing : ModelState
        data object Installing : ModelState
        data class Ready(val manifest: SmartManifest, val accelerator: Accelerator) : ModelState
        data class Failed(val reason: String) : ModelState
    }

    data class Status(
        val model: ModelState = ModelState.Missing,
        /** Photos that can be indexed, and how many of them are. */
        val total: Int = 0,
        val indexed: Int = 0,
        val indexing: Boolean = false,
        /** Indexing stopped by hand. */
        val paused: Boolean = false,
    ) {
        val ready: Boolean get() = model is ModelState.Ready
        val pending: Int get() = (total - indexed).coerceAtLeast(0)
    }

    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, "smart_search")
    private val modelDir = File(root, "model")
    private val stagingDir = File(root, "staging")
    private val oldDir = File(root, "old")
    private val store = SmartSearchStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SmartIndex").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }.asCoroutineDispatcher()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Guards the encoders, [index] and the model directory. */
    private val engineLock = Mutex()
    private var textEncoder: SmartTextEncoder? = null
    private var imageEncoder: SmartImageEncoder? = null
    private var index: SmartIndex? = null
    private val indexedKeys = HashMap<Long, IndexedKey>()
    private var loadedModelId: String? = null
    @Volatile private var lastQueryAt = 0L

    private val appVisible = MutableStateFlow(false)
    private val jobRunning = MutableStateFlow(false)
    /** Bumped to plan again (index cleared, model replaced). */
    private val replan = MutableStateFlow(0)
    /** The photos to index were counted at least once against a read gallery. */
    private val planned = MutableStateFlow(false)

    init {
        scope.launch {
            loadInstalledModel()
            watch()
        }
    }

    // ------------------------------------------------------------------ model

    private fun loadInstalledModel() {
        val manifestFile = File(modelDir, SmartManifest.MANIFEST)
        if (!manifestFile.isFile) {
            setModel(ModelState.Missing)
            return
        }
        val state = runCatching {
            val manifest = SmartManifest.parse(manifestFile.readText())
            for ((name, f) in manifest.files) {
                if (File(modelDir, name).length() != f.size) throw IOException("$name is damaged")
            }
            val accelerator = runCatching { Accelerator.valueOf(File(modelDir, ACCELERATOR_FILE).readText().trim()) }
                .getOrDefault(Accelerator.CPU)
            ModelState.Ready(manifest, accelerator)
        }.getOrElse {
            Log.e(TAG, "Installed model unusable", it)
            ModelState.Failed(it.message ?: "damaged")
        }
        setModel(state)
    }

    private fun setModel(state: ModelState) = _status.update { it.copy(model = state) }

    /** An empty staging directory for a pack being imported (see [installStaged]). */
    fun newStaging(): File {
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory) throw IOException("Cannot create $stagingDir")
        return stagingDir
    }

    /** Imports a .alexetai pack. */
    suspend fun installPack(open: () -> InputStream): Result<SmartManifest> = withContext(Dispatchers.IO) {
        val previous = _status.value.model
        setModel(ModelState.Installing)
        val manifest = runCatching { open().use { SmartPackWriter.readPack(it, newStaging()) } }
            .getOrElse { e ->
                failInstall(previous, e)
                return@withContext Result.failure(e)
            }
        runCatching { installStaged(manifest, previous) }.map { manifest }
    }

    /** Checks the pack in [newStaging] on this phone and puts it in place of the current model. */
    suspend fun installStaged(manifest: SmartManifest, previous: ModelState = _status.value.model) = withContext(Dispatchers.IO) {
        try {
            setModel(ModelState.Installing)
            val accelerator = selfTest(stagingDir, manifest)
            File(stagingDir, ACCELERATOR_FILE).writeText(accelerator.name)
            engineLock.withLock {
                closeEngines()
                oldDir.deleteRecursively()
                if (modelDir.exists() && !modelDir.renameTo(oldDir)) throw IOException("Cannot replace the model")
                if (!stagingDir.renameTo(modelDir)) {
                    oldDir.renameTo(modelDir)
                    throw IOException("Cannot install the model")
                }
                oldDir.deleteRecursively()
                loadedModelId = null
            }
            setModel(ModelState.Ready(manifest, accelerator))
            Log.d(TAG, "Model ${manifest.modelId} installed, images on $accelerator")
            replan.update { it + 1 }
        } catch (e: Exception) {
            failInstall(previous, e)
            throw e
        }
    }

    private fun failInstall(previous: ModelState, e: Throwable) {
        Log.e(TAG, "Model import failed", e)
        stagingDir.deleteRecursively()
        setModel(if (previous is ModelState.Ready) previous else ModelState.Failed(e.message ?: e.javaClass.simpleName))
    }

    /** The model's own outputs against the reference ones; the image encoder on the GPU when it passes there. */
    private fun selfTest(dir: File, manifest: SmartManifest): Accelerator {
        val test = SmartSelfTest.load(dir, manifest)
        SmartTextEncoder(dir, manifest).use { encoder ->
            val (tokens, cosine) = test.checkText(encoder)
            Log.d(TAG, "Self test text: tokens $tokens, cos $cosine")
            if (!tokens) throw IOException("Tokenizer self test failed")
            if (cosine < SmartSelfTest.TEXT_MIN) throw IOException("Text encoder self test failed ($cosine)")
        }
        for (accelerator in listOf(Accelerator.GPU, Accelerator.CPU)) {
            val cosine = runCatching { SmartImageEncoder(dir, manifest, accelerator).use { test.checkImages(it) } }
                .onFailure { Log.w(TAG, "Image encoder on $accelerator failed", it) }
                .getOrDefault(-1f)
            Log.d(TAG, "Self test images on $accelerator: cos $cosine")
            if (cosine >= SmartSelfTest.IMAGE_MIN) return accelerator
        }
        throw IOException("Image encoder self test failed")
    }

    suspend fun removeModel() = withContext(Dispatchers.IO) {
        engineLock.withLock {
            closeEngines()
            modelDir.deleteRecursively()
            store.clear()
            index = null
            indexedKeys.clear()
            loadedModelId = null
        }
        setModel(ModelState.Missing)
        _status.update { it.copy(indexed = 0, total = 0, indexing = false) }
        SmartIndexJobService.cancel(appContext)
    }

    suspend fun clearIndex() = withContext(Dispatchers.IO) {
        engineLock.withLock {
            store.clear()
            index?.clear()
            indexedKeys.clear()
        }
        _status.update { it.copy(indexed = 0) }
        replan.update { it + 1 }
    }

    /** LICENSE and NOTICE of the installed pack. */
    fun licenseText(): String? = runCatching {
        val notice = File(modelDir, SmartManifest.NOTICE).readText()
        val license = File(modelDir, SmartManifest.LICENSE).readText()
        "$notice\n\n$license"
    }.getOrNull()

    private fun closeEngines() {
        textEncoder?.close()
        textEncoder = null
        imageEncoder?.close()
        imageEncoder = null
    }

    /** Frees the encoders (hundreds of MB of native memory) unless one is working. */
    fun trimMemory() {
        if (!engineLock.tryLock()) return
        try {
            if (!_status.value.indexing) closeEngines()
        } finally {
            engineLock.unlock()
        }
    }

    // ------------------------------------------------------------------ index

    fun setAppVisible(visible: Boolean) {
        appVisible.value = visible
    }

    /** Runs from [SmartIndexJobService]: returns when every photo is indexed, or when cancelled. */
    suspend fun runBackgroundIndexing() {
        val prefs = settings.settings.value
        if (!prefs.smartSearchEnabled || prefs.smartIndexPaused) return
        jobRunning.value = true
        try {
            // The gallery list is read first (a fresh process): "nothing pending" means nothing yet.
            withTimeoutOrNull(JOB_PLAN_TIMEOUT_MS) { planned.first { it } }
            _status.first { !it.ready || it.paused || (!it.indexing && it.pending == 0) }
        } finally {
            jobRunning.value = false
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watch() {
        // Switch, folders, pause: a change stops the running pass at once and plans again.
        val prefs = settings.settings
            .map { Prefs(it.smartSearchEnabled, SmartFolderFilter.of(it), it.smartIndexPaused) }
            .distinctUntilChanged()
        val ready = _status.map { (it.model as? ModelState.Ready)?.manifest?.modelId }.distinctUntilChanged()
        val media = repository.media.debounce(MEDIA_DEBOUNCE_MS)
        val active = combine(appVisible, jobRunning) { visible, job -> visible || job }
        combine(prefs, ready, media, active, combine(repository.isScanning, replan) { scanning, _ -> scanning }) { p, modelId, list, run, scanning ->
            Inputs(p.enabled, modelId, list, run, scanning, p.folders, p.paused)
        }.collectLatest { input ->
            if (!input.enabled || input.modelId == null) {
                _status.update { it.copy(indexing = false) }
                if (!input.enabled) SmartIndexJobService.cancel(appContext)
                return@collectLatest
            }
            try {
                reconcile(input)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Indexing failed", e)
                _status.update { it.copy(indexing = false) }
            }
        }
    }

    private data class Prefs(val enabled: Boolean, val folders: SmartFolderFilter, val paused: Boolean)

    private class Inputs(
        val enabled: Boolean,
        val modelId: String?,
        val media: List<GalleryMedia>,
        val active: Boolean,
        val scanning: Boolean,
        val folders: SmartFolderFilter,
        val paused: Boolean,
    )

    private suspend fun reconcile(input: Inputs) {
        val manifest = (_status.value.model as? ModelState.Ready)?.manifest ?: return
        _status.update { it.copy(paused = input.paused) }
        ensureIndexLoaded(manifest)
        val secureDirs = SmartIndexPlanner.secureDirKeys(SecureFolderManager.getKnownSecurePaths())
        // Photos of folders taken out of the selection are in toDelete: they leave the index.
        val plan = engineLock.withLock { SmartIndexPlanner.plan(input.media, HashMap(indexedKeys), secureDirs, input.folders) }
        // While the gallery is still being read its list is incomplete: nothing is deleted then.
        if (!input.scanning && input.media.isNotEmpty() && plan.toDelete.isNotEmpty()) {
            engineLock.withLock {
                store.delete(plan.toDelete)
                for (id in plan.toDelete) {
                    indexedKeys.remove(id)
                    index?.remove(id)
                }
            }
        }
        val total = input.media.count { SmartIndexPlanner.isEligible(it, secureDirs, input.folders) }
        _status.update { it.copy(total = total, indexed = (total - plan.toIndex.size).coerceAtLeast(0)) }
        if (!input.scanning || input.media.isNotEmpty()) planned.value = true
        if (plan.toIndex.isEmpty() || input.paused) {
            _status.update { it.copy(indexing = false) }
            return
        }
        SmartIndexJobService.schedule(appContext)
        if (!input.active) {
            _status.update { it.copy(indexing = false) }
            return
        }
        _status.update { it.copy(indexing = true) }
        try {
            indexPhotos(plan.toIndex, manifest, total)
        } finally {
            _status.update { it.copy(indexing = false) }
        }
    }

    private suspend fun ensureIndexLoaded(manifest: SmartManifest) = engineLock.withLock {
        if (loadedModelId == manifest.modelId && index != null) return@withLock
        val rows = store.loadAll(manifest.modelId)
        val idx = SmartIndex(manifest.dim)
        indexedKeys.clear()
        for (row in rows) {
            indexedKeys[row.mediaId] = row.key
            row.vector?.takeIf { it.vec.size == manifest.dim }?.let { idx.put(row.mediaId, it) }
        }
        index = idx
        loadedModelId = manifest.modelId
        Log.d(TAG, "Index loaded: ${idx.size} vectors, ${rows.size} rows")
    }

    private suspend fun indexPhotos(items: List<GalleryMedia>, manifest: SmartManifest, total: Int) {
        var done = total - items.size
        var statusAt = 0L
        for (batch in items.chunked(BATCH)) {
            val rows = ArrayList<SmartSearchStore.Row>(batch.size)
            var saved = true
            try {
                withContext(indexDispatcher) {
                    for (media in batch) {
                        yield()
                        val argb = SmartImageLoader.load(appContext, media, manifest.imageSize)
                        val vector = argb?.let { pixels ->
                            engineLock.withLock { imageEncoderLocked(manifest).encode(pixels) }
                        }?.let(QuantizedVector::of)
                        rows += SmartSearchStore.Row(media.id, MediaRefs.portablePath(media), media.sizeBytes, media.dateModifiedMs, vector)
                    }
                }
            } finally {
                // Photos done before the pass stopped (the gallery changed, a download landed) are
                // kept: the whole batch was thrown away and encoded again.
                if (rows.isNotEmpty()) saved = withContext(NonCancellable) { saveRows(rows, manifest) }
            }
            if (!saved) return
            done += rows.size
            // The progress at most once a second: each update rebuilt the gallery screen.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - statusAt >= STATUS_INTERVAL_MS) {
                statusAt = now
                _status.update { it.copy(indexed = done.coerceAtMost(it.total)) }
            }
        }
        _status.update { it.copy(indexed = done.coerceAtMost(it.total)) }
        engineLock.withLock {
            imageEncoder?.close()
            imageEncoder = null
        }
    }

    /** Into the database and the index; false when the index is gone (the model was removed meanwhile). */
    private suspend fun saveRows(rows: List<SmartSearchStore.Row>, manifest: SmartManifest): Boolean = engineLock.withLock {
        store.upsert(rows, manifest.modelId)
        val idx = index ?: return@withLock false
        for (row in rows) {
            indexedKeys[row.mediaId] = row.key
            if (row.vector != null) idx.put(row.mediaId, row.vector) else idx.remove(row.mediaId)
        }
        true
    }

    private fun imageEncoderLocked(manifest: SmartManifest): SmartImageEncoder {
        imageEncoder?.let { return it }
        val accelerator = (_status.value.model as? ModelState.Ready)?.accelerator ?: Accelerator.CPU
        val encoder = runCatching { SmartImageEncoder(modelDir, manifest, accelerator) }.getOrElse {
            if (accelerator == Accelerator.CPU) throw it
            Log.w(TAG, "Image encoder on $accelerator failed, using the CPU", it)
            SmartImageEncoder(modelDir, manifest, Accelerator.CPU)
        }
        imageEncoder = encoder
        return encoder
    }

    // ------------------------------------------------------------------ search

    /** Indexed photos matching [query], best first. Empty without a model or an index. */
    suspend fun search(query: String, limit: Int = SEARCH_LIMIT): List<SmartIndex.Hit> = withContext(Dispatchers.Default) {
        val text = query.trim()
        if (text.isEmpty()) return@withContext emptyList()
        val manifest = (_status.value.model as? ModelState.Ready)?.manifest ?: return@withContext emptyList()
        ensureIndexLoaded(manifest)
        lastQueryAt = System.currentTimeMillis()
        val (vector, idx) = engineLock.withLock {
            val encoder = textEncoder ?: SmartTextEncoder(modelDir, manifest).also { textEncoder = it }
            encoder.encode(text) to index
        }
        scheduleTextRelease()
        idx?.search(vector, limit, minCosine(manifest)) ?: emptyList()
    }

    /** The indexed photos' embeddings (a copy), or null without a model or an index. */
    suspend fun embeddings(): dev.ander.alexet.data.DuplicateFinder.Embeddings? = withContext(Dispatchers.Default) {
        val manifest = (_status.value.model as? ModelState.Ready)?.manifest ?: return@withContext null
        ensureIndexLoaded(manifest)
        engineLock.withLock { index }?.snapshot()
    }

    private var releaseJob: Job? = null

    /** The text encoder is freed a while after the last query. */
    private fun scheduleTextRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(TEXT_IDLE_MS)
            engineLock.withLock {
                if (System.currentTimeMillis() - lastQueryAt >= TEXT_IDLE_MS) {
                    textEncoder?.close()
                    textEncoder = null
                }
            }
        }
    }

    private fun minCosine(m: SmartManifest): Float =
        ((ln(MIN_PROBABILITY / (1 - MIN_PROBABILITY)) - m.logitBias) / m.logitScale).toFloat()

    // ------------------------------------------------------------------ export / import

    /** Files of the installed model as entry name -> file, for a settings export. */
    fun modelFiles(): List<Pair<String, File>>? {
        val manifest = (_status.value.model as? ModelState.Ready)?.manifest ?: return null
        return (listOf(SmartManifest.MANIFEST) + manifest.files.keys).map { it to File(modelDir, it) }
    }

    /**
     * index.bin: 'AXIX', i32 version, UTF model id, i32 dim, i32 count, then per photo: UTF portable
     * path, i64 size, i64 date modified, f32 scale, dim int8 values. Undecodable photos are left out.
     */
    suspend fun writeIndex(out: OutputStream) = withContext(Dispatchers.IO) {
        val manifest = (_status.value.model as? ModelState.Ready)?.manifest ?: throw IOException("No model")
        val rows = engineLock.withLock { store.loadAll(manifest.modelId) }.filter { it.vector != null && it.portablePath != null }
        val data = DataOutputStream(out.buffered(SmartPackWriter.BUFFER))
        data.write(INDEX_MAGIC)
        data.writeInt(1)
        data.writeUTF(manifest.modelId)
        data.writeInt(manifest.dim)
        data.writeInt(rows.size)
        for (row in rows) {
            data.writeUTF(row.portablePath!!)
            data.writeLong(row.sizeBytes)
            data.writeLong(row.dateModifiedMs)
            data.writeFloat(row.vector!!.scale)
            data.write(row.vector.vec)
        }
        data.flush()
    }

    /**
     * An exported index on this phone: rows are matched to this phone's photos by path and size;
     * the rest is dropped (the indexer makes those). Returns how many were taken.
     */
    suspend fun readIndex(input: InputStream, media: List<GalleryMedia>): Int = withContext(Dispatchers.IO) {
        val manifest = (_status.value.model as? ModelState.Ready)?.manifest ?: return@withContext 0
        val byPath = HashMap<String, GalleryMedia>(media.size * 2)
        for (m in media) MediaRefs.portablePath(m)?.let { byPath.putIfAbsent(it, m) }
        val data = DataInputStream(input.buffered(SmartPackWriter.BUFFER))
        val magic = ByteArray(4).also { data.readFully(it) }
        if (!magic.contentEquals(INDEX_MAGIC) || data.readInt() != 1) throw IOException("Not an index")
        if (data.readUTF() != manifest.modelId) return@withContext 0
        val dim = data.readInt()
        if (dim != manifest.dim) return@withContext 0
        val count = data.readInt()
        if (count !in 0..MAX_INDEX_ROWS) throw IOException("Bad index size")
        val secureDirs = SmartIndexPlanner.secureDirKeys(SecureFolderManager.getKnownSecurePaths())
        val rows = ArrayList<SmartSearchStore.Row>()
        repeat(count) {
            val path = data.readUTF()
            val size = data.readLong()
            data.readLong()
            val scale = data.readFloat()
            val vec = ByteArray(dim).also { data.readFully(it) }
            val local = byPath[MediaRefs.key(path)] ?: return@repeat
            if (local.sizeBytes != size || !SmartIndexPlanner.isEligible(local, secureDirs)) return@repeat
            rows += SmartSearchStore.Row(local.id, path, local.sizeBytes, local.dateModifiedMs, QuantizedVector(vec, scale))
        }
        engineLock.withLock {
            store.upsert(rows, manifest.modelId)
            loadedModelId = null
        }
        replan.update { it + 1 }
        rows.size
    }

    companion object {
        private const val TAG = "SmartSearch"
        private const val ACCELERATOR_FILE = "accelerator"
        private const val BATCH = 32
        private const val STATUS_INTERVAL_MS = 1_000L
        private const val MEDIA_DEBOUNCE_MS = 2_000L
        private const val JOB_PLAN_TIMEOUT_MS = 120_000L
        private const val TEXT_IDLE_MS = 120_000L
        private const val SEARCH_LIMIT = 300
        /** SigLIP match probability below which a photo is not shown at all. */
        private const val MIN_PROBABILITY = 0.001
        private const val MAX_INDEX_ROWS = 5_000_000
        private val INDEX_MAGIC = byteArrayOf('A'.code.toByte(), 'X'.code.toByte(), 'I'.code.toByte(), 'X'.code.toByte())
        const val INDEX_ENTRY = "index.bin"
        const val MODEL_PREFIX = "model/"
    }
}
