package dev.ander.alexet.data

object PriorityRolloffHelper {

    private val NUMERIC_PATTERN = Regex("""^(\d+)(?:~(\d+))?$""")

    data class NumericFileItem(
        val originalName: String,
        val baseNum: Long,
        val tildeLayer: Long?,
        val paddingWidth: Int,
        val extension: String,
    )

    fun parseItem(fileName: String): NumericFileItem? {
        val hasDot = fileName.contains('.')
        val base = if (hasDot) fileName.substringBeforeLast('.') else fileName
        val ext = if (hasDot) fileName.substringAfterLast('.') else ""

        val match = NUMERIC_PATTERN.matchEntire(base) ?: return null
        val digitsStr = match.groupValues[1]
        val baseNum = digitsStr.toLongOrNull() ?: return null
        val tildeStr = match.groupValues[2]
        val tildeLayer = if (tildeStr.isNotEmpty()) tildeStr.toLongOrNull() else null

        return NumericFileItem(
            originalName = fileName,
            baseNum = baseNum,
            tildeLayer = tildeLayer,
            paddingWidth = digitsStr.length,
            extension = ext,
        )
    }

    /**
     * Computes the cascading rename plan (Hilbert's Hotel) for the provided filenames.
     * - Slots are planned independently per extension (e.g. .mp4 and .jpg do not collide).
     * - Only extension groups that contain at least one file with '~' are modified.
     * - Ordering per group:
     *     1. baseNum ASCENDING (e.g. 001 before 003)
     *     2. For same baseNum: tildeLayer DESCENDING (~3, ~2, ~1), with no-tilde at the end.
     * - Rolloff formula: targetNum = max(baseNum, lastAllocated + 1).
     * - Padding: max of paddingWidths in the group, ensuring uniform leading zeros.
     * - Returns a list of (originalName -> targetName) pairs where originalName != targetName.
     */
    fun computeRolloffPlan(fileNames: List<String>): List<Pair<String, String>> {
        val parsedItems = fileNames.mapNotNull { parseItem(it) }
        if (parsedItems.none { it.tildeLayer != null }) {
            return emptyList()
        }

        // Group per extension (case-insensitive key, preserving extension)
        val groupedByExt = parsedItems.groupBy { it.extension.lowercase() }
        val overallPlan = mutableListOf<Pair<String, String>>()

        for ((_, itemsInGroup) in groupedByExt) {
            // Only process groups that actually have files with priority '~'
            if (itemsInGroup.none { it.tildeLayer != null }) {
                continue
            }

            val maxPadding = itemsInGroup.maxOf { it.paddingWidth }

            val sorted = itemsInGroup.sortedWith { a, b ->
                val numCmp = a.baseNum.compareTo(b.baseNum)
                if (numCmp != 0) return@sortedWith numCmp

                if (a.tildeLayer != null && b.tildeLayer == null) return@sortedWith -1
                if (a.tildeLayer == null && b.tildeLayer != null) return@sortedWith 1
                if (a.tildeLayer != null && b.tildeLayer != null) {
                    return@sortedWith b.tildeLayer.compareTo(a.tildeLayer)
                }
                0
            }

            var lastAllocated = 0L
            for (item in sorted) {
                val targetNum = maxOf(item.baseNum, lastAllocated + 1)
                lastAllocated = targetNum
                val pad = maxOf(maxPadding, targetNum.toString().length)
                val numStr = targetNum.toString().padStart(pad, '0')
                val targetName = if (item.extension.isNotEmpty()) "$numStr.${item.extension}" else numStr

                if (item.originalName != targetName) {
                    overallPlan.add(item.originalName to targetName)
                }
            }
        }

        return overallPlan
    }
}
