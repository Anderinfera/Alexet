package dev.ander.alexet.data

/** Where a running full scan is; [total] is 0 when not known yet. */
data class FullScanProgress(val phase: Phase, val done: Int = 0, val total: Int = 0) {
    enum class Phase {
        /** MediaStore read again in full. */
        LIBRARY,
        /** Folders on the storage walked ([done] so far). */
        FOLDERS,
        /** Files handed to the system scanner ([done] of [total]). */
        FILES,
    }
}

data class FullScanResult(val folders: Int, val sent: Int, val gone: Int)
