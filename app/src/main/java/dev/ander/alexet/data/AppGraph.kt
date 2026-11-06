package dev.ander.alexet.data

import android.content.Context

/**
 * One settings store, repository and trash per process. A folder deletion running in
 * [dev.ander.alexet.FolderDeleteService] outlives the screen; a new screen must then work on the
 * same trash (two TrashManagers each write their own copy of the trash index and lose entries).
 */
object AppGraph {
    @Volatile private var settingsStore: SettingsStore? = null
    @Volatile private var galleryRepository: GalleryRepository? = null
    @Volatile private var trashManager: TrashManager? = null
    @Volatile private var smartSearchEngine: dev.ander.alexet.data.smartsearch.SmartSearch? = null

    fun settings(context: Context): SettingsStore =
        settingsStore ?: synchronized(this) {
            settingsStore ?: SettingsStore(context.applicationContext).also { settingsStore = it }
        }

    fun repository(context: Context): GalleryRepository =
        galleryRepository ?: synchronized(this) {
            galleryRepository ?: GalleryRepository(context.applicationContext, settings(context)).also { galleryRepository = it }
        }

    fun smartSearch(context: Context): dev.ander.alexet.data.smartsearch.SmartSearch =
        smartSearchEngine ?: synchronized(this) {
            smartSearchEngine ?: dev.ander.alexet.data.smartsearch.SmartSearch(
                context.applicationContext,
                settings(context),
                repository(context),
            ).also { smartSearchEngine = it }
        }

    /** The smart search when something already made it (memory trimming must not start it). */
    fun smartSearchIfCreated(): dev.ander.alexet.data.smartsearch.SmartSearch? = smartSearchEngine

    fun trash(context: Context): TrashManager =
        trashManager ?: synchronized(this) {
            trashManager ?: TrashManager(context.applicationContext).also { tm ->
                val repository = repository(context)
                // Restored files appear in watched folders: they are Alexet's writes, not downloads.
                tm.ownWriteListener = { file, done -> if (done) repository.endOwnWrite(file) else repository.noteOwnWrite(file) }
                trashManager = tm
            }
        }
}
