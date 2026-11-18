package dev.ander.alexet.data

import java.io.File

/** Paths on the phone's storages: the main one (/storage/emulated/0) and others such as an SD card (/storage/6FFF-B5B1). */
object StoragePaths {

    /**
     * [dir] as a path inside [root] ("a/b"), "" for [root] itself, null when it is not inside it.
     * Kotlin's relativeToOrNull gives "../../6FFF-B5B1/…" for an SD card folder instead of null; used
     * as a MediaStore RELATIVE_PATH, that moved files into a "_.." folder on the main storage.
     */
    fun pathInside(dir: File, root: File): String? {
        val rel = dir.relativeToOrNull(root)?.invariantSeparatorsPath ?: return null
        return when {
            rel.isEmpty() || rel == "." -> ""
            rel == ".." || rel.startsWith("../") -> null
            else -> rel.trim('/')
        }
    }

    /**
     * [path] relative to the root of its own storage ("Gallery/Eruu Claymie"): inside [primaryRoot],
     * or after /storage/<volume>/ for another storage. Null when it is neither.
     */
    fun pathOnItsVolume(path: String, primaryRoot: String): String? {
        val p = path.replace('\\', '/').trimEnd('/')
        val primary = primaryRoot.replace('\\', '/').trimEnd('/')
        if (p == primary) return ""
        if (p.startsWith("$primary/")) return p.removePrefix("$primary/").takeUnless { it.split('/').any { s -> s == ".." } }
        val root = volumeRootOf(p, primary) ?: return null
        return p.removePrefix(root).trim('/').takeUnless { it.split('/').any { s -> s == ".." } }
    }

    /** The storage [path] is on: [primaryRoot] or "/storage/<volume>"; null for any other path. */
    fun volumeRootOf(path: String, primaryRoot: String): String? {
        val p = path.replace('\\', '/')
        val primary = primaryRoot.replace('\\', '/').trimEnd('/')
        if (p == primary || p.startsWith("$primary/")) return primary
        val volume = Regex("^/storage/([^/]+)(/|$)").find(p)?.groupValues?.get(1) ?: return null
        // /storage/emulated and /storage/self are views of the main storage, not volumes of their own.
        if (volume == "emulated" || volume == "self") return null
        return "/storage/$volume"
    }

    /**
     * [name] as any SD card takes it: no emoji or other characters beyond U+FFFF (older exFAT
     * drivers answer EINVAL to them) and none of the characters FAT forbids. The extension stays.
     */
    fun storageSafeName(name: String): String {
        val sb = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp > 0xFFFF -> Unit
                // Joiners and variation selectors left behind by a removed emoji.
                cp == 0x200D || cp in 0xFE00..0xFE0F -> Unit
                cp < 0x20 || cp == 0x7F || "\"*/:<>?\\|".indexOf(cp.toChar()) >= 0 -> sb.append('_')
                else -> sb.appendCodePoint(cp)
            }
        }
        val cleaned = sb.toString().replace(Regex(" {2,}"), " ")
        val ext = cleaned.substringAfterLast('.', missingDelimiterValue = "")
        val base = (if (ext.isEmpty()) cleaned else cleaned.dropLast(ext.length + 1)).trim()
        val safeBase = base.ifEmpty { "file" }
        return if (ext.isEmpty()) safeBase else "$safeBase.$ext"
    }

    /** False only when both paths are on known storages and those differ (main ↔ SD card). */
    fun sameVolume(a: String, b: String, primaryRoot: String): Boolean {
        val va = volumeRootOf(a, primaryRoot) ?: return true
        val vb = volumeRootOf(b, primaryRoot) ?: return true
        return va == vb
    }
}
