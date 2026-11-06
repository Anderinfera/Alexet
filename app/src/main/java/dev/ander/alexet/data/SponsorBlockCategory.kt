package dev.ander.alexet.data

import androidx.annotation.StringRes
import dev.ander.alexet.R

enum class SponsorBlockCategory(
    val key: String,
    val ytDlpName: String,
    @StringRes val nameRes: Int,
) {
    SPONSOR("sponsor", "Sponsor", R.string.sponsorblock_category_sponsor),
    SELF_PROMO("selfpromo", "Unpaid/Self Promotion", R.string.sponsorblock_category_selfpromo),
    INTERACTION("interaction", "Interaction Reminder", R.string.sponsorblock_category_interaction),
    INTRO("intro", "Intermission/Intro Animation", R.string.sponsorblock_category_intro),
    OUTRO("outro", "Endcards/Credits", R.string.sponsorblock_category_outro),
    PREVIEW("preview", "Preview/Recap", R.string.sponsorblock_category_preview),
    HOOK("hook", "Hook/Greetings", R.string.sponsorblock_category_hook),
    FILLER("filler", "Filler Tangent", R.string.sponsorblock_category_filler),
    MUSIC_OFFTOPIC("music_offtopic", "Non-Music Section", R.string.sponsorblock_category_music_offtopic),
    ;

    companion object {
        val DEFAULT_AUTO_SKIP = setOf(SPONSOR)

        /** Finds a category by its SponsorBlock key or by the name yt-dlp puts in chapter titles. */
        fun fromName(name: String): SponsorBlockCategory? = entries.firstOrNull {
            it.key.equals(name, ignoreCase = true) || it.ytDlpName.equals(name, ignoreCase = true)
        }
    }
}
