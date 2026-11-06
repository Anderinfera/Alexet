package dev.ander.alexet.ui.player

import androidx.media3.common.C
import androidx.media3.common.Label
import androidx.media3.extractor.metadata.Chapter
import dev.ander.alexet.data.SponsorBlockCategory
import dev.ander.alexet.data.SponsorBlockCategory.INTRO
import dev.ander.alexet.data.SponsorBlockCategory.MUSIC_OFFTOPIC
import dev.ander.alexet.data.SponsorBlockCategory.OUTRO
import dev.ander.alexet.data.SponsorBlockCategory.SELF_PROMO
import dev.ander.alexet.data.SponsorBlockCategory.SPONSOR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SponsorSegmentTest {
    private fun chapter(startMs: Long, endMs: Long, title: String): Chapter =
        Chapter.Builder().setStartTimeMs(startMs).setEndTimeMs(endMs).setTitle(Label(null, title)).build()

    @Test
    fun readsCategoriesFromOwnAndYtDlpTitles() {
        assertEquals(listOf(SPONSOR), sponsorBlockCategoriesOf("[SponsorBlock] sponsor"))
        assertEquals(listOf(SPONSOR, OUTRO), sponsorBlockCategoriesOf("[SponsorBlock] sponsor, outro"))
        assertEquals(listOf(SPONSOR), sponsorBlockCategoriesOf("[SponsorBlock]: Sponsor"))
        assertEquals(
            listOf(SELF_PROMO, INTRO),
            sponsorBlockCategoriesOf("[SponsorBlock]: Unpaid/Self Promotion, Intermission/Intro Animation"),
        )
        assertEquals(listOf(MUSIC_OFFTOPIC), sponsorBlockCategoriesOf("[SponsorBlock]: Non-Music Section"))
    }

    @Test
    fun everyCategoryIsFoundByKeyAndByYtDlpName() {
        SponsorBlockCategory.entries.forEach { category ->
            assertEquals(category, SponsorBlockCategory.fromName(category.key))
            assertEquals(category, SponsorBlockCategory.fromName(category.ytDlpName))
        }
    }

    @Test
    fun ignoresOrdinaryChaptersAndUnknownCategories() {
        assertEquals(emptyList<SponsorBlockCategory>(), sponsorBlockCategoriesOf("Film"))
        assertEquals(emptyList<SponsorBlockCategory>(), sponsorBlockCategoriesOf("sponsor"))
        assertEquals(emptyList<SponsorBlockCategory>(), sponsorBlockCategoriesOf("[SponsorBlock] unknown"))
        assertEquals(listOf(OUTRO), sponsorBlockCategoriesOf("[SponsorBlock] unknown, outro"))
    }

    @Test
    fun keepsOnlySponsorChaptersWithTheirTimes() {
        val segments = listOf(
            chapter(0, 1_500, "Film"),
            chapter(1_500, 3_000, "[SponsorBlock] sponsor"),
            chapter(3_000, 5_000, "Film"),
            chapter(5_000, 6_000, "[SponsorBlock] outro"),
        ).toSponsorSegments(durationMs = 6_000)

        assertEquals(
            listOf(
                SponsorSegment(1_500, 3_000, listOf(SPONSOR)),
                SponsorSegment(5_000, 6_000, listOf(OUTRO)),
            ),
            segments,
        )
    }

    @Test
    fun chaptersWithoutEndTimeLastUntilTheNextOneOrTheEndOfTheVideo() {
        val segments = listOf(
            chapter(1_000, C.TIME_UNSET, "[SponsorBlock] sponsor"),
            chapter(2_000, C.TIME_UNSET, "Film"),
            chapter(5_000, C.TIME_UNSET, "[SponsorBlock] outro"),
        ).toSponsorSegments(durationMs = 6_000)

        assertEquals(listOf(1_000L to 2_000L, 5_000L to 6_000L), segments.map { it.startMs to it.endMs })
    }

    @Test
    fun skipsOnlyTheChosenCategories() {
        val segments = listOf(
            SponsorSegment(1_000, 2_000, listOf(SPONSOR)),
            SponsorSegment(3_000, 4_000, listOf(SELF_PROMO)),
        )
        val autoSkip = setOf(SPONSOR)

        assertEquals(segments[0], segments.autoSkipSegment(900, 1_100, autoSkip))
        assertNull(segments.autoSkipSegment(2_900, 3_100, autoSkip))
        assertEquals(segments[1], segments.autoSkipSegment(2_900, 3_100, setOf(SELF_PROMO)))
        assertNull(segments.autoSkipSegment(900, 1_100, emptySet()))
    }

    @Test
    fun aSegmentSharedByTwoCategoriesIsSkippedForEitherOfThem() {
        val segments = listOf(SponsorSegment(1_000, 2_000, listOf(SELF_PROMO, OUTRO)))

        assertEquals(segments[0], segments.autoSkipSegment(900, 1_100, setOf(OUTRO)))
    }

    @Test
    fun aSegmentEnteredByASeekOrAlreadyPassedIsNotSkipped() {
        val segments = listOf(SponsorSegment(1_000, 2_000, listOf(SPONSOR)))
        val autoSkip = setOf(SPONSOR)

        assertNull(segments.autoSkipSegment(1_500, 1_700, autoSkip))
        assertNull(segments.autoSkipSegment(900, 2_100, autoSkip))
    }

    @Test
    fun aSegmentAtTheVeryStartIsSkipped() {
        val segments = listOf(SponsorSegment(0, 2_000, listOf(INTRO)))

        assertEquals(segments[0], segments.autoSkipSegment(0, 200, setOf(INTRO)))
    }

    @Test
    fun nothingCanBeSkippedAtTheVeryEndOfTheVideo() {
        val endsWithTheVideo = listOf(SponsorSegment(32_000, 40_000, listOf(OUTRO)))
        val endsPastTheVideo = listOf(SponsorSegment(32_000, 41_000, listOf(OUTRO)))

        assertEquals(endsWithTheVideo[0], endsWithTheVideo.skippableSegmentAt(39_900, 40_000))
        assertNull(endsWithTheVideo.skippableSegmentAt(39_999, 40_000))
        assertNull(endsPastTheVideo.skippableSegmentAt(39_999, 40_000))
    }

    @Test
    fun aSegmentCanBeSkippedBeforeTheDurationIsKnown() {
        val segments = listOf(SponsorSegment(0, 2_000, listOf(INTRO)))

        assertEquals(segments[0], segments.skippableSegmentAt(0, 0))
    }

    @Test
    fun findsTheSegmentAtAPosition() {
        val segments = listOf(SponsorSegment(1_000, 2_000, listOf(SPONSOR)))

        assertEquals(segments[0], segments.segmentAt(1_000))
        assertEquals(segments[0], segments.segmentAt(1_999))
        assertNull(segments.segmentAt(2_000))
        assertNull(segments.segmentAt(999))
    }
}
