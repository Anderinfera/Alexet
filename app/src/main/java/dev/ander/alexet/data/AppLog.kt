package dev.ander.alexet.data

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app debug log ("Debugowanie logów" in settings): the last [WINDOW_MS] of app events, kept in
 * memory so a log can be taken right after something goes wrong (logcat readers can lag hours).
 * Calls mirror android.util.Log and still forward there. While disabled nothing is stored and lazy
 * messages ([event]) are not even built.
 */
object AppLog {

    enum class Level(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

    class Entry(val timeMs: Long, val level: Level, val tag: String, val message: String, val error: Throwable?)

    const val WINDOW_MS = 5 * 60_000L
    const val MAX_ENTRIES = 20_000

    /** Mirrors the setting; read on every call. */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            synchronized(entries) {
                field = value
                if (!value) entries.clear()
            }
        }

    /** Wall clock; replaceable in tests. */
    @Volatile
    var nowMs: () -> Long = { System.currentTimeMillis() }

    private val entries = ArrayDeque<Entry>()

    // ---- android.util.Log-compatible calls (forwarded to logcat as before) ----

    fun d(tag: String, message: String, error: Throwable? = null): Int {
        record(Level.DEBUG, tag, message, error)
        return runCatching { android.util.Log.d(tag, message, error) }.getOrDefault(0)
    }

    fun w(tag: String, message: String, error: Throwable? = null): Int {
        record(Level.WARN, tag, message, error)
        return runCatching { android.util.Log.w(tag, message, error) }.getOrDefault(0)
    }

    fun e(tag: String, message: String, error: Throwable? = null): Int {
        record(Level.ERROR, tag, message, error)
        return runCatching { android.util.Log.e(tag, message, error) }.getOrDefault(0)
    }

    /** App event for the debug log only (not logcat); [message] runs only when enabled. */
    inline fun event(tag: String, message: () -> String) {
        if (enabled) record(Level.INFO, tag, message(), null)
    }

    fun record(level: Level, tag: String, message: String, error: Throwable?) {
        if (!enabled) return
        val now = nowMs()
        synchronized(entries) {
            // Checked again under the lock: logging switched off (and cleared) meanwhile must not
            // get an entry back.
            if (!enabled) return
            entries.addLast(Entry(now, level, tag, message, error))
            trim(now)
        }
    }

    private fun trim(now: Long) {
        val cutoff = now - WINDOW_MS
        while (entries.isNotEmpty() && (entries.size > MAX_ENTRIES || entries.first().timeMs < cutoff)) {
            entries.removeFirst()
        }
    }

    fun snapshot(): List<Entry> = synchronized(entries) {
        trim(nowMs())
        entries.toList()
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    /** "field: old -> new" for every field that differs (same class); values shortened. */
    /**
     * "field: old -> new" for each changed field. Fields in [redacted] (e.g. the search text, where
     * private-folder passwords are typed) are reported without their content.
     */
    fun describeChanges(old: Any, new: Any, redacted: Set<String> = emptySet()): List<String> {
        if (old.javaClass != new.javaClass) return listOf("${short(old)} -> ${short(new)}")
        val out = ArrayList<String>()
        for (field in old.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            runCatching {
                field.isAccessible = true
                val a = field.get(old)
                val b = field.get(new)
                // Same object (an unchanged field of a copied state): no deep comparison of big lists.
                if (a !== b && a != b) {
                    out += if (field.name in redacted) {
                        "${field.name}: changed (hidden)"
                    } else {
                        "${field.name}: ${short(a)} -> ${short(b)}"
                    }
                }
            }
        }
        return out
    }

    private fun short(value: Any?): String {
        // Collections are written only as far as shown: formatting a whole library list on every
        // change took the thread that made the change (often the main one).
        val text = when (value) {
            is Collection<*> -> buildString {
                append('(').append(value.size).append(") [")
                for ((i, item) in value.withIndex()) {
                    if (length > 160) break
                    if (i > 0) append(", ")
                    append(item)
                }
                append(']')
            }
            is Map<*, *> -> buildString {
                append('(').append(value.size).append(") {")
                var first = true
                for ((k, v) in value) {
                    if (length > 160) break
                    if (!first) append(", ")
                    first = false
                    append(k).append('=').append(v)
                }
                append('}')
            }
            else -> value.toString()
        }
        return if (text.length > 160) text.take(157) + "..." else text
    }

    // ---- formatting ----

    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun formatTime(ms: Long): String = timeFormat.get()!!.format(Date(ms))

    /** One block per entry; a stack trace stays with its entry. */
    fun format(entry: Entry): String = buildString {
        append(formatTime(entry.timeMs)).append(" APP ").append(entry.level.letter).append('/')
            .append(entry.tag).append(": ").append(entry.message)
        entry.error?.let { error ->
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            append('\n').append(sw.toString().trimEnd())
        }
    }

    /**
     * A block of the final log with the time it sorts by. Logcat lines and app entries are merged
     * into one chronological list.
     */
    class Block(val timeKey: String, val text: String)

    fun appBlocks(entries: List<Entry>): List<Block> = entries.map { Block(formatTime(it.timeMs), format(it)) }

    /**
     * Logcat lines (threadtime: "MM-dd HH:mm:ss.SSS  pid  tid L Tag: msg"); lines the app log
     * already has (forwarded calls) are dropped.
     */
    fun logcatBlocks(lines: List<String>, appEntries: List<Entry>): List<Block> {
        val known = appEntries.mapTo(HashSet()) { "${it.tag}: ${it.message.lineSequence().firstOrNull().orEmpty()}" }
        return lines.mapNotNull { line ->
            if (line.length < 18 || !line[2].equals('-') || line.startsWith("-")) return@mapNotNull null
            val tagAndMessage = line.substringAfter(": ", "").let { msg ->
                val head = line.substringBefore(": ")
                "${head.trimEnd().substringAfterLast(' ')}: $msg"
            }
            if (tagAndMessage in known) return@mapNotNull null
            Block(line.substring(0, 18), line)
        }
    }

    /** Stable chronological merge (time keys share one year, so string order is time order). */
    fun merge(vararg parts: List<Block>): List<Block> = parts.toList().flatten().sortedBy { it.timeKey }

    /**
     * Newest blocks that fit in [maxChars] together with [header]; the oldest are dropped.
     * Returns the text and the time key of the oldest block kept (null when none fit).
     */
    fun newestWithin(header: String, blocks: List<Block>, maxChars: Int): Pair<String, String?> {
        var budget = maxChars - header.length
        var first = blocks.size
        while (first > 0) {
            val cost = blocks[first - 1].text.length + 1
            if (cost > budget) break
            budget -= cost
            first--
        }
        val kept = blocks.subList(first, blocks.size)
        val text = buildString {
            append(header)
            kept.forEach { append(it.text).append('\n') }
        }
        return text to kept.firstOrNull()?.timeKey
    }

    // ---- crash ----

    private const val CRASH_FILE = "debug_crash_log.txt"

    /** Called from the uncaught-exception handler: keep the log for the next start. */
    fun persistCrash(dir: File, thread: Thread, error: Throwable) {
        if (!enabled) return
        runCatching {
            record(Level.ERROR, "Crash", "Uncaught exception in ${thread.name}", error)
            val text = buildString {
                append("=== Log z awarii ").append(formatTime(nowMs())).append(" ===\n")
                snapshot().forEach { append(format(it)).append('\n') }
            }
            File(dir, CRASH_FILE).writeText(text)
        }
    }

    fun crashLog(dir: File): String? = runCatching {
        File(dir, CRASH_FILE).takeIf { it.isFile }?.readText()
    }.getOrNull()

    fun clearCrashLog(dir: File) {
        runCatching { File(dir, CRASH_FILE).delete() }
    }
}
