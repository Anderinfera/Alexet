package dev.ander.alexet.data

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

/**
 * Multi-step renames (numbered names, swaps) first move files to temporary names
 * (`rename_...`, `__alexet_swap_...`). Each temporary name is noted here before the file gets it
 * and dropped once the operation is over; a process killed in between leaves the note, and the
 * next start puts the file back under its original name ([recover]).
 */
object RenameJournal {
    private const val FILE_NAME = "rename_journal.txt"
    private val lock = Any()
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        dir = context.applicationContext.filesDir
    }

    /** [alternative]: where the file may go when its original place is taken (a swap's other name). */
    fun add(temp: File, original: File, alternative: File? = null) {
        val d = dir ?: return
        synchronized(lock) {
            runCatching {
                File(d, FILE_NAME).appendText("${temp.absolutePath}\t${original.absolutePath}\t${alternative?.absolutePath.orEmpty()}\n")
            }
        }
    }

    /** [temp] is only a working copy (the original is untouched until it takes its place): removed after a kill. */
    fun addDiscard(temp: File) {
        val d = dir ?: return
        synchronized(lock) {
            runCatching {
                File(d, FILE_NAME).appendText("${temp.absolutePath}\t\t\n")
            }
        }
    }

    fun remove(temps: Collection<File>) {
        if (temps.isEmpty()) return
        val d = dir ?: return
        val paths = temps.mapTo(HashSet()) { it.absolutePath }
        synchronized(lock) {
            val file = File(d, FILE_NAME)
            runCatching {
                if (!file.isFile) return@runCatching
                val left = file.readLines().filter { line -> line.isNotBlank() && line.substringBefore('\t') !in paths }
                if (left.isEmpty()) file.delete() else file.writeText(left.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** Called once at process start (nothing is renaming then). */
    fun recover(context: Context) {
        val d = dir ?: context.applicationContext.filesDir
        val scan = ArrayList<String>()
        synchronized(lock) {
            val file = File(d, FILE_NAME)
            if (!file.isFile) return
            for (line in file.readLines()) {
                val parts = line.split('\t')
                if (parts.size < 2) continue
                val temp = File(parts[0])
                if (!temp.isFile) continue
                if (parts[1].isEmpty()) {
                    // A working copy (see addDiscard): its original never changed.
                    if (temp.delete()) AppLog.w("RenameJournal", "Removed ${temp.name} left by an interrupted change")
                    continue
                }
                val original = File(parts[1])
                val alternative = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::File)
                val target = when {
                    !original.exists() -> original
                    alternative != null && !alternative.exists() -> alternative
                    else -> null
                }
                if (target != null && temp.renameTo(target)) {
                    AppLog.w("RenameJournal", "Put ${temp.name} back as ${target.name} after an interrupted rename")
                    scan += temp.absolutePath
                    scan += target.absolutePath
                } else {
                    AppLog.w("RenameJournal", "Could not put ${temp.path} back")
                }
            }
            file.delete()
        }
        if (scan.isNotEmpty()) MediaScannerConnection.scanFile(context.applicationContext, scan.toTypedArray(), null, null)
    }
}
