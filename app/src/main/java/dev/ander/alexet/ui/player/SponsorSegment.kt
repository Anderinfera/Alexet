package dev.ander.alexet.ui.player

import androidx.annotation.OptIn
import androidx.compose.ui.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter
import dev.ander.alexet.data.SponsorBlockCategory

private const val CHAPTER_TITLE_PREFIX = "[SponsorBlock]"

/** A stretch of a video marked with SponsorBlock categories (more than one when they overlap). */
data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val categories: List<SponsorBlockCategory>,
) {
    val color: Color get() = categories.first().color

    fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
}

private val SponsorBlockCategory.color: Color
    get() = when (this) {
        SponsorBlockCategory.SPONSOR -> Color(0xFF00D400)
        SponsorBlockCategory.SELF_PROMO -> Color(0xFFFFFF00)
        SponsorBlockCategory.INTERACTION -> Color(0xFFCC00FF)
        SponsorBlockCategory.INTRO -> Color(0xFF00FFFF)
        SponsorBlockCategory.OUTRO -> Color(0xFF0202ED)
        SponsorBlockCategory.PREVIEW -> Color(0xFF008FD6)
        SponsorBlockCategory.HOOK -> Color(0xFF395699)
        SponsorBlockCategory.FILLER -> Color(0xFF7300FF)
        SponsorBlockCategory.MUSIC_OFFTOPIC -> Color(0xFFFF9900)
    }

/**
 * Reads titles like `[SponsorBlock] sponsor, outro` and yt-dlp's `[SponsorBlock]: Sponsor`; any
 * other chapter is an ordinary one and gives no categories.
 */
fun sponsorBlockCategoriesOf(chapterTitle: String): List<SponsorBlockCategory> {
    if (!chapterTitle.startsWith(CHAPTER_TITLE_PREFIX)) return emptyList()
    return chapterTitle.removePrefix(CHAPTER_TITLE_PREFIX)
        .trimStart(':', ' ')
        .split(',')
        .mapNotNull { SponsorBlockCategory.fromName(it.trim()) }
}

/** The chapters of the file, once each: every track carries them in its metadata. */
@OptIn(UnstableApi::class)
fun Tracks.chapters(durationMs: Long): List<Chapter> {
    if (durationMs <= 0L) return emptyList()
    return groups.flatMap { group ->
        (0 until group.length).flatMap { trackIndex ->
            group.getTrackFormat(trackIndex).metadata?.getEntriesOfType(Chapter::class.java).orEmpty()
        }
    }.filter { !it.isHidden && it.startTimeMs in 0L until durationMs }
        .sortedBy { it.startTimeMs }
        .distinctBy { it.startTimeMs }
}

@OptIn(UnstableApi::class)
fun List<Chapter>.toSponsorSegments(durationMs: Long): List<SponsorSegment> {
    val chapters = sortedBy { it.startTimeMs }
    return chapters.mapIndexedNotNull { index, chapter ->
        val categories = chapter.title?.value?.let(::sponsorBlockCategoriesOf).orEmpty()
        if (categories.isEmpty()) return@mapIndexedNotNull null
        // Nero chapters have no end time: they last until the next chapter.
        val endMs = chapter.endTimeMs.takeIf { it != C.TIME_UNSET }
            ?: chapters.getOrNull(index + 1)?.startTimeMs
            ?: durationMs
        SponsorSegment(chapter.startTimeMs, endMs, categories).takeIf { endMs > chapter.startTimeMs }
    }
}

fun List<SponsorSegment>.segmentAt(positionMs: Long): SponsorSegment? =
    firstOrNull { it.contains(positionMs) }

/** The segment that can be skipped at [positionMs]: none at the very end, where a seek stops just before [durationMs]. */
fun List<SponsorSegment>.skippableSegmentAt(positionMs: Long, durationMs: Long): SponsorSegment? =
    if (durationMs > 0L && positionMs >= durationMs - 1L) null else segmentAt(positionMs)

/**
 * The segment to skip when playback moved from [previousMs] to [positionMs]: only one entered from
 * its start, so a seek into the middle of a segment plays it, and only one of [autoSkip].
 */
fun List<SponsorSegment>.autoSkipSegment(
    previousMs: Long,
    positionMs: Long,
    autoSkip: Set<SponsorBlockCategory>,
): SponsorSegment? = firstOrNull { segment ->
    previousMs <= segment.startMs && segment.contains(positionMs) && segment.categories.any { it in autoSkip }
}
