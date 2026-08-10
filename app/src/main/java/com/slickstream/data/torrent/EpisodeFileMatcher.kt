package com.slickstream.data.torrent

/**
 * "Which file inside this season pack is season S, episode E?"
 *
 * Pure and unit-tested on purpose: getting this wrong has two expensive failure modes, and the engine
 * cannot tell them apart from a `FileStorage`. Too STRICT and a pack full of perfectly good episodes is
 * rejected as unplayable — the real report was a Law & Order S07 pack whose files are named
 * "701 - Causa Mortis.avi" … "710 - Family Business.avi", where every pattern the engine knew
 * (S07E10 / 7x10 / "season 7 episode 10") missed, so it failed with "archive/RAR release" about a
 * torrent containing 23 ordinary .avi episodes. Too LOOSE and it silently plays the WRONG episode,
 * which is worse than an error because nothing tells the viewer.
 */
object EpisodeFileMatcher {

    /**
     * True when [path] names season [season], episode [episode].
     *
     * Ordered from most explicit to least, and every form is delimiter-anchored so a match cannot start
     * or end mid-number ("1710" must never satisfy a request for 710).
     */
    fun matches(path: String, season: Int, episode: Int): Boolean {
        if (season < 0 || episode < 0) return false
        // S07E10 / s7.e10 / S07 E10
        val sxe = Regex("(?i)(?:^|[^a-z0-9])s0*$season[^a-z0-9]*e0*$episode(?:[^0-9]|$)")
        // 7x10 / 07x10
        val x = Regex("(?i)(?:^|[^0-9])0*${season}x0*$episode(?:[^0-9]|$)")
        // "Season 7 … Episode 10"
        val words = Regex("(?i)(?:^|[^a-z0-9])season[^0-9]*0*$season.*episode[^0-9]*0*$episode(?:[^0-9]|$)")
        if (sxe.containsMatchIn(path) || x.containsMatchIn(path) || words.containsMatchIn(path)) return true

        // COMPACT numeric ("710" = S7E10, "0710" = S07E10). Very common on older TV packs, and the form
        // that produced the bug above.
        //
        // This one needs guarding, because a bare 3-4 digit run is exactly what a RESOLUTION looks like:
        // a request for S7E20 would otherwise match the "720" in "720p", and S10E80 the "1080" in
        // "1080p". So a compact code is accepted ONLY when it is not immediately followed by a
        // resolution/scan marker, and only against the FILE NAME (not the containing folder, which
        // normally carries the show/season/quality text where those numbers live).
        val fileName = path.substringAfterLast('/')
        val compact = listOf(
            "$season%02d".format(episode),   // 710
            "%02d%02d".format(season, episode), // 0710
        ).distinct()
        return compact.any { code ->
            Regex("(?:^|[^0-9])$code(?![0-9])(?![pi]\\b)(?![pi][^a-z0-9])").containsMatchIn(fileName)
        }
    }

    /**
     * Index of the file that is season [season] episode [episode], or null when none is identifiable.
     * [isPlayable] lets the caller exclude samples and non-video entries without this file needing to
     * know anything about containers.
     */
    fun indexOf(
        names: List<String>,
        season: Int?,
        episode: Int?,
        isPlayable: (String) -> Boolean,
    ): Int? {
        if (season == null || episode == null) return null
        return names.indices.firstOrNull { i -> isPlayable(names[i]) && matches(names[i], season, episode) }
    }
}
