package dev.ander.alexet.data

/**
 * Whether a file another app wrote by path (listed in MediaStore as IS_PENDING) is finished and
 * can be published now, or is still being written.
 */
object WaitingFileRule {
    enum class Decision { READY, WRITING, SKIP }

    /** No write for this long: a file whose completeness can't be checked counts as finished. */
    const val QUIET_MS = 5_000L

    /**
     * @param length Bytes on disk (0 when the file is missing).
     * @param ageMs Time since the file was last modified.
     * @param complete [FileCompleteness.check]: true/false when the format is known, else null.
     * @param liveDownload The download tracker sees the file being written right now.
     */
    fun decide(length: Long, ageMs: Long, complete: Boolean?, liveDownload: Boolean): Decision = when {
        length <= 0L -> Decision.SKIP
        liveDownload -> Decision.WRITING
        complete == false -> Decision.WRITING
        complete == true -> Decision.READY
        ageMs >= QUIET_MS -> Decision.READY
        else -> Decision.WRITING
    }
}
