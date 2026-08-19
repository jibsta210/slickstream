package com.slickstream.data.torrent

/**
 * "Which file inside this season pack is season S, episode E?"
 *
 * Pure and unit-tested on purpose: getting this wrong has two expensive failure modes, and the engine
 * cannot tell them apart from a `FileStorage`. Too STRICT and a pack full of perfectly good episodes is
 * rejected as unplayable — the first real report was a Law & Order S07 pack whose files are named
 * "701 - Causa Mortis.avi" … "710 - Family Business.avi", where every pattern the engine knew
 * (S07E10 / 7x10 / "season 7 episode 10") missed, so it failed with "archive/RAR release" about a
 * torrent containing 23 ordinary .avi episodes. Too LOOSE and it silently plays the WRONG episode,
 * which is worse than an error because nothing tells the viewer.
 *
 * The second real report was Octonauts: Above & Beyond S01E15. That season is 25 episodes on TVDB but
 * Netflix ships the 11-minute segments PAIRED, so the season pack holds 13 files — E01 alone plus
 * twelve doubles — and episode 15 exists only as the SECOND number of
 * "Octonauts.Above.and.Beyond.S01E14E15.….1080p.NF.WEB-DL.DDP5.1.x264-LAZY.mkv". The old regex anchored
 * `e` immediately before the episode digits, so it read `E14` and stopped; nothing matched, and
 * `selectFile` (correctly) refused to guess among 13 videos, so a healthy pack hard-failed.
 *
 * ## How this file is built
 *
 * The old code asked "does this path contain S<requested>E<requested>?" — a per-file yes/no. That shape
 * caused three of the bugs found in review: the season number had no trailing-digit boundary (so a
 * request for S01E05 matched `Show/Season 10/Episode 5.mkv`), a weak compact match on file 0 beat an
 * exact SxxExx match on file 19, and two files claiming the same episode were silently resolved by
 * torrent order.
 *
 * So the direction is inverted. Each file is PARSED into the episode codes it actually carries, and the
 * pack is then arbitrated as a whole, in strict tiers (see [resolve]). Parsing the real number out of
 * the name is what makes the boundary bugs impossible: `Season 10` yields season 10 and can never be
 * mistaken for season 1.
 */
object EpisodeFileMatcher {

    /** The outcome of identifying one episode inside a pack. */
    sealed class Match {
        /** Exactly one file carries the requested episode. [evidence] is for logs/error text. */
        data class Unique(val index: Int, val evidence: String) : Match()

        /**
         * Several files are equally plausible. NOT a pick: the caller must break the tie with outside
         * evidence (an addon fileIdx pointing at one of them) or fail with a message naming them.
         * Silently taking the first — which is what `firstOrNull` used to do — is the exact
         * "wrong episode plays and nothing tells the viewer" failure this file exists to prevent.
         */
        data class Ambiguous(val indices: List<Int>, val evidence: String) : Match()

        /** Nothing in the pack identifiably carries the requested episode. */
        object None : Match()
    }

    // ---------------------------------------------------------------------------------------------
    // Token vocabulary
    // ---------------------------------------------------------------------------------------------

    /** `S01`, `Season 1`, `Series 1`, `Temporada 1`, `Staffel 1`, `Se.01` (NL). */
    private const val SEASON_WORD = "(?:s(?:eason|eries|erie|e)?|temporada|staffel|saison|sezon|stagione)"

    /**
     * The episode marker. `Ep`/`EP` was previously unmatchable (`e0*15` needs the digits directly after
     * the `e`, and the `P` broke it) even though Sonarr carries dedicated patterns for it — real names
     * like `Show.S01EP15.mkv` and `Title S01 - EP14` simply missed. Localized markers cost nothing to
     * add and cannot widen the match, because the season stays explicitly bound either side of them.
     * Longest alternatives first, or `ep` would win over `episode`.
     */
    private const val EP_WORD = "(?:episodio|episodes?|epis|epi|eps|ep|aflevering|afl|capitulos?|cap|folge|odc|e)"

    /** Not a digit, and not a resolution/scan marker: `720p`, `1080i`, `2160P`, `1080I`. */
    private const val NOT_RES = "(?![0-9])(?![pi](?![a-zA-Z]))"

    /** True separator before a bare number: start, `.`, `-`, `_`, space, `[`, `(`. */
    private const val SEP_BEFORE = "(?<![a-zA-Z0-9])"

    /**
     * A combined-episode run trailing the first episode number: `E15` (S01E14E15), `-E03` (S02E01-E03),
     * `-03` (S02E01-03), `-16` (1x15-16). Every element must be introduced by an explicit `e` or a dash,
     * which is what stops `.1080p` in `Show.S01E05.1080p.mkv` from being read as "episodes 5 through
     * 1080". The `x` exclusion stops `1x15-1x16` from parsing its second season number as an episode.
     */
    private const val RUN_TAIL =
        "((?:[^a-zA-Z0-9]{0,2}e\\d{1,3}(?![a-zA-Z0-9])|-\\d{1,3}(?![a-zA-Z0-9]))*)"

    /**
     * `S07E10`, `s7.e10`, `S01E14E15`, `S01 - EP14`, `S01D02.E15` (disc-numbered DVD packs),
     * `Season 7 Episode 10`, `Se.01.afl.15`.
     */
    private val SXE = Regex(
        "(?i)$SEP_BEFORE$SEASON_WORD[^a-zA-Z0-9]{0,2}(\\d{1,2})(?![0-9])" +
            "(?:[^a-zA-Z0-9]{0,2}d\\d{1,2})?" +
            "[^a-zA-Z0-9]{0,3}$EP_WORD[^a-zA-Z0-9]{0,2}(\\d{1,3})(?![0-9])$RUN_TAIL",
    )

    /** `7x10`, `07x10`, `1x15-16`. `(\d{1,2})` plus [SEP_BEFORE] is what keeps `1920x1080` out. */
    private val NXN = Regex("(?i)$SEP_BEFORE(\\d{1,2})x(\\d{1,3})$NOT_RES$RUN_TAIL")

    /**
     * `Show/Season 1/Episode 15.mkv` — the layout Plex and Jellyfin document, where the show name sits
     * between the two halves. The gap is bounded to the SAME path component or the very next one:
     * with an unbounded `.*` the season folder and the episode file need not be related at all.
     */
    private val WORDS = Regex(
        "(?i)$SEP_BEFORE(?:season|series|temporada|staffel|saison|sezon|stagione)[^a-zA-Z0-9]{0,2}" +
            "(\\d{1,2})(?![0-9])[^/]*(?:/[^/]*)?" +
            "$SEP_BEFORE(?:episode|episodio|capitulo|folge|afl|odc)[^a-zA-Z0-9]{0,2}(\\d{1,3})(?![0-9])",
    )

    /**
     * COMPACT numeric: `710` = S7E10, `0710` = S07E10. Very common on older TV packs, and the form that
     * produced the Law & Order bug.
     *
     * Two guards, both of which were holes:
     *  - [SEP_BEFORE] requires a real separator, not merely "not a digit". Without it `x264` satisfies a
     *    request for S02E64, `x265`/`H265` satisfy S02E65 — deterministically, not by chance.
     *  - [NOT_RES] is IGNORE_CASE here. The old guard was built without `(?i)` while every sibling
     *    pattern had it, so `Show.S01.720P.mkv` matched a request for S07E20 and `1080P`/`1080I` matched
     *    S10E80. The old test only covered lowercase, so the suite stayed green over the hole.
     */
    private val COMPACT = Regex("$SEP_BEFORE(\\d{3,4})$NOT_RES", RegexOption.IGNORE_CASE)

    /** A season stated by a DIRECTORY component: `Season 3/`, `S03/`, `Temporada 3/`. */
    private val SEASON_DIR = Regex("(?i)$SEP_BEFORE$SEASON_WORD[^a-zA-Z0-9]{0,2}(\\d{1,2})(?![0-9])")

    /** Bonus material that must never be chosen for a requested episode. Matched on path components. */
    /**
     * UNAMBIGUOUS bonus material — these words never appear in a real episode title in a way that could
     * be confused for the episode itself. Excluded wherever they appear, folder or file name.
     */
    private val EXTRAS_STRONG = Regex(
        "(?i)(?<![a-z0-9])(?:extras?|featurettes?|deleted[ ._-]scenes?|bloopers?|" +
            "behind[ ._-]the[ ._-]scenes|making[ ._-]of|trailers?|sample)(?![a-z0-9])",
    )

    /**
     * AMBIGUOUS words: real episode titles use them constantly ("The Christmas Special", "Bonus Round").
     * Excluded when a DIRECTORY says it ("Specials/"), because there the word describes the whole folder —
     * but never allowed to delete a file whose own name states the REQUESTED code. Suppressing those was
     * dropping the genuine episode from the candidate set, after which tier 1's early exit returned None
     * and an otherwise healthy pack hard-failed.
     */
    private val EXTRAS_WEAK = Regex("(?i)(?<![a-z0-9])(?:specials?|bonus)(?![a-z0-9])")

    /** A double/triple episode is real; `S01E01-E25` is a whole-season LABEL, not a 25-episode file. */
    private const val MAX_COMBINED_SPAN = 6

    // ---------------------------------------------------------------------------------------------
    // Per-file parsing
    // ---------------------------------------------------------------------------------------------

    /**
     * Every (season, episode) this path explicitly states, with combined runs expanded.
     * `…S01E14E15….mkv` yields (1,14) AND (1,15) — the Octonauts fix.
     */
    internal fun explicitCodes(path: String): List<Pair<Int, Int>> {
        val out = LinkedHashSet<Pair<Int, Int>>()
        // Scanned over the FULL path, because a legitimate layout states the season in the FOLDER and the
        // episode in the FILE ("Show/Season 1/Episode 15.mkv" — the shape Plex and Jellyfin document).
        // What is position-dependent is RANGE EXPANSION, not detection.
        val baseStart = path.lastIndexOf('/') + 1
        for (r in listOf(SXE, NXN)) {
            for (m in r.findAll(path)) {
                val season = m.groupValues[1].toIntOrNull() ?: continue
                val first = m.groupValues[2].toIntOrNull() ?: continue
                if (m.range.first >= baseStart) {
                    // In the FILE NAME: the run belongs to this file. "S01E14E15" really does contain 15.
                    for (e in expandRun(first, m.groupValues[3])) out += season to e
                } else {
                    // In a DIRECTORY: the range describes the TORRENT, not any one file in it. A batch-drop
                    // root like "Show.S04E01-E03-GRP/" was attributing episodes 1, 2 AND 3 to every file
                    // underneath, so every file "claimed" whatever was requested; the tie could not be
                    // broken and the caller fell back to the largest file — the wrong episode playing with
                    // nothing on screen to say so. Take the stated number only, never the span.
                    out += season to first
                }
            }
        }
        for (m in WORDS.findAll(path)) {
            val season = m.groupValues[1].toIntOrNull() ?: continue
            val ep = m.groupValues[2].toIntOrNull() ?: continue
            out += season to ep
        }
        return out.toList()
    }

    /** Codes stated by the FILE NAME alone, runs expanded. Used where folder text must not vote:
     *  the extras keyword test, and the interchangeability check that decides whether a tie may be
     *  collapsed to the largest file. */
    internal fun basenameCodes(path: String): List<Pair<Int, Int>> {
        val base = path.substringAfterLast('/')
        val out = LinkedHashSet<Pair<Int, Int>>()
        for (r in listOf(SXE, NXN)) {
            for (m in r.findAll(base)) {
                val season = m.groupValues[1].toIntOrNull() ?: continue
                val first = m.groupValues[2].toIntOrNull() ?: continue
                for (e in expandRun(first, m.groupValues[3])) out += season to e
            }
        }
        for (m in WORDS.findAll(base)) {
            val season = m.groupValues[1].toIntOrNull() ?: continue
            val ep = m.groupValues[2].toIntOrNull() ?: continue
            out += season to ep
        }
        return out.toList()
    }

    /**
     * `E14` + tail `E15` -> [14, 15]; `E01` + tail `-E03` -> [1, 2, 3] (the release itself states the
     * range, so interpolating is not a guess); an absurd span is a season label and yields only the
     * first number rather than claiming 25 episodes live in one file.
     */
    private fun expandRun(first: Int, tail: String): List<Int> {
        if (tail.isEmpty()) return listOf(first)
        val rest = Regex("\\d{1,3}").findAll(tail).mapNotNull { it.value.toIntOrNull() }.toList()
        if (rest.isEmpty()) return listOf(first)
        // A range RUNS FORWARD. "S02E01-E03" is episodes 1-3; "S01E10-5.1" is episode 10 followed by an
        // audio layout, and reading it as 5-through-10 invented five episodes the file does not contain.
        // Requiring every element to ascend is what tells one from the other — a token that happens to
        // sit after a dash cannot masquerade as an endpoint unless it is genuinely a later episode.
        if (rest.any { it <= first }) return listOf(first)
        val all = listOf(first) + rest
        val lo = all.min()
        val hi = all.max()
        if (hi - lo > MAX_COMBINED_SPAN) return listOf(first)
        return if (tail.contains('-')) (lo..hi).toList() else all.distinct()
    }

    /**
     * Compact codes carried by a FILE NAME (never the folder, which is where show/quality/year digits
     * legitimately live). A bracketed 19xx/20xx is a release year — `Show (2021).mkv` is not S20E21.
     */
    internal fun compactCodes(fileName: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (m in COMPACT.findAll(fileName)) {
            val code = m.groupValues[1]
            val before = fileName.getOrNull(m.range.first - 1)
            val after = fileName.getOrNull(m.range.last + 1)
            val n = code.toIntOrNull() ?: continue
            if (code.length == 4 && n in 1900..2099 &&
                (before == '(' || before == '[') && (after == ')' || after == ']')
            ) {
                continue
            }
            if (code.length == 3) {
                out += (code[0] - '0') to code.substring(1).toInt()
            } else {
                out += code.substring(0, 2).toInt() to code.substring(2).toInt()
            }
        }
        return out
    }

    /**
     * The season stated by the DEEPEST directory component that names exactly one. Deepest-first is what
     * makes `Show S01-S05 Complete/Season 3/07 - Title.mkv` resolve to 3 instead of throwing up its
     * hands at {1, 5, 3}.
     */
    internal fun folderSeason(path: String): Int? {
        val dirs = path.split('/').dropLast(1)
        for (dir in dirs.asReversed()) {
            val found = SEASON_DIR.findAll(dir).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
            if (found.size == 1) return found.first()
        }
        return null
    }

    /**
     * One naming shape for an episode number that carries NO season of its own. Never trusted per-file —
     * only when every playable file in the pack agrees on the same shape (see [resolve] tier 3).
     */
    private enum class Shape { ITUNES, EP, LEAD, DASH, PART }

    /** `1-05 Title (HD).m4v` — iTunes. Returns season and episode; both must agree with the request. */
    private val ITUNES_RE = Regex("^(\\d{1,2})-(\\d{1,3})(?![0-9])")
    private val EP_RE = Regex("(?i)$SEP_BEFORE(?:episodes?|epis|epi|eps|ep|e)[^a-zA-Z0-9]{0,2}(\\d{1,3})$NOT_RES")
    private val LEAD_RE = Regex("^(\\d{1,3})$NOT_RES", RegexOption.IGNORE_CASE)
    private val DASH_RE = Regex("[-–][ ._]{0,2}(\\d{1,3})$NOT_RES", RegexOption.IGNORE_CASE)
    private val PART_RE = Regex("(?i)$SEP_BEFORE(?:parts?|pt)[^a-zA-Z0-9]{0,2}(\\d{1,3})$NOT_RES")

    /**
     * The episode number this basename states under [shape], or null. Requires EXACTLY ONE match: a name
     * with two candidate numbers under the same shape is not evidence of anything.
     */
    private fun shapeValue(baseNoExt: String, shape: Shape, season: Int): Int? {
        val re = when (shape) {
            Shape.ITUNES -> ITUNES_RE
            Shape.EP -> EP_RE
            Shape.LEAD -> LEAD_RE
            Shape.DASH -> DASH_RE
            Shape.PART -> PART_RE
        }
        val hits = re.findAll(baseNoExt).toList()
        if (hits.size != 1) return null
        val m = hits.first()
        if (shape == Shape.ITUNES) {
            if (m.groupValues[1].toIntOrNull() != season) return null
            return m.groupValues[2].toIntOrNull()
        }
        return m.groupValues[1].toIntOrNull()
    }

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    /**
     * True when [path], on its own, states season [season] episode [episode].
     *
     * Per-file only: this covers the two tiers that carry their own season (explicit and compact). The
     * episode-only forms are deliberately absent here because they are only safe as a whole-pack
     * decision — see [resolve].
     */
    fun matches(path: String, season: Int, episode: Int): Boolean {
        if (season < 0 || episode < 0) return false
        if (explicitCodes(path).any { it.first == season && it.second == episode }) return true
        return compactCodes(path.substringAfterLast('/')).any { it.first == season && it.second == episode }
    }

    /**
     * Identify season [season] episode [episode] among [paths] (full relative paths, so folder text is
     * available). [isPlayable] lets the caller exclude samples and non-video entries without this file
     * needing to know anything about containers.
     *
     * Strict precedence, most specific first. A tier only runs when every stronger tier came up empty,
     * so a weak compact hit on file 0 can no longer beat an exact `S07E20` on file 19:
     *
     *  1. EXPLICIT — the file states both numbers: `S01E15`, `S01E14E15`, `S02E01-E03`, `1x15`,
     *     `Season 1/Episode 15`.
     *  2. COMPACT — `715`/`0715`, and only when a MAJORITY of the pack carries the same shape and no two
     *     files claim the same episode. That corroboration is what keeps a lone `x264`/`(2021)` from
     *     being read as an episode: one stray number cannot out-vote a pack.
     *  3. EPISODE-ONLY + corroborated season — `Season 1/15 - Little Goby.mkv`, `Season 01/05 Title.m4v`,
     *     `Season 1/Ep15.mkv`. Requires ALL candidate files to agree on ONE shape, produce DISTINCT
     *     numbers forming a plausible run starting at 0 or 1, and the season to be corroborated by the
     *     folder, by explicit codes elsewhere in the pack, or (absent any season evidence at all) by the
     *     request being season 1, where absolute and per-season numbering coincide. Anime absolute
     *     numbering is deliberately NOT supported past season 1: absolute 15 is S02E02 of a 13-episode
     *     season, not S01E15, and this file has no episode-count table to convert with.
     */
    /**
     * Bonus material that must never answer for a requested episode.
     *
     * The keyword test runs on DIRECTORY components ("Specials/", "Extras/") and on a basename that does
     * NOT state the requested code — never on the whole path unconditionally. An episode whose TITLE
     * happens to contain one of these words ("S01E15.The.Christmas.Special.mkv") was otherwise deleted
     * from the candidate set; its siblings still stated the season, so tier 1's early exit then returned
     * None and a perfectly healthy pack hard-failed. An explicit code for the requested episode outranks
     * a word in the title, always.
     */
    private fun isExtras(path: String, season: Int, episode: Int): Boolean {
        val dirs = path.substringBeforeLast('/', "")
        // A directory is authoritative about what it holds: "Extras/" or "Specials/" means bonus
        // material even when the file inside names the episode it accompanies
        // ("Extras/Show.S01E15.Behind.The.Scenes.mkv" is about E15, it is not E15).
        if (dirs.isNotEmpty() && (EXTRAS_STRONG.containsMatchIn(dirs) || EXTRAS_WEAK.containsMatchIn(dirs))) {
            return true
        }
        val name = base(path)
        if (EXTRAS_STRONG.containsMatchIn(name)) return true
        // Only an AMBIGUOUS word is left, so an explicit claim on the requested episode wins: a real
        // "S01E15.The.Christmas.Special.mkv" must stay playable.
        if (EXTRAS_WEAK.containsMatchIn(name) &&
            !basenameCodes(path).any { it.first == season && it.second == episode }
        ) {
            return true
        }
        return explicitCodes(path).let { c -> c.isNotEmpty() && c.all { it.first == 0 } }
    }

    fun resolve(
        paths: List<String>,
        season: Int?,
        episode: Int?,
        isPlayable: (String) -> Boolean,
    ): Match {
        if (season == null || episode == null || season < 0 || episode < 0) return Match.None
        val playable = paths.indices.filter { isPlayable(paths[it]) }
        if (playable.isEmpty()) return Match.None

        // Specials and bonus material are never the requested episode unless season 0 was asked for.
        // Dropping them also stops "S01E15 Behind The Scenes.mkv" from tying with the real episode.
        val candidates = if (season == 0) {
            playable
        } else {
            playable.filterNot { i -> isExtras(paths[i], season, episode) }.ifEmpty { playable }
        }

        // --- tier 1: the FILE NAME states both numbers ----------------------------------------------
        // Candidacy is decided by the BASENAME alone. A directory may confirm a season, but a folder that
        // states an episode range (a batch-drop root like "Show.S04E01-E03-GRP/") applies to the torrent,
        // not to any single file in it — counting it made every file claim the request, which collapsed
        // into "pick the largest" and played the wrong episode with nothing on screen to say so.
        // Folder text still participates — "Show/Season 1/Episode 15.mkv" states the season in the
        // FOLDER and the episode in the FILE, which is the layout Plex and Jellyfin document. What was
        // removed is RANGE EXPANSION from directories (see [directoryCodes]): a batch-drop root like
        // "Show.S04E01-E03-GRP/" now contributes only its first episode instead of attributing three to
        // every file under it. That, plus resolveMatch's refusal to collapse a tie between files whose
        // episode sets DIFFER, is what stops "every file claims it -> play the largest".
        val explicit = candidates.associateWith { explicitCodes(paths[it]) }
        val explicitAny = explicit
        val tier1 = candidates.filter { i -> explicit[i]!!.any { it.first == season && it.second == episode } }
        if (tier1.size == 1) return Match.Unique(tier1.first(), "SxxExx in '${base(paths[tier1.first()])}'")
        if (tier1.size > 1) {
            // A pack can hold both "S01E15.mkv" and "S01E14E15.mkv". They both legitimately contain
            // episode 15, but the single-episode file STARTS at episode 15 while the double starts a
            // whole episode early — so the more specific claim wins outright rather than being a tie.
            val single = tier1.filter { i -> explicit[i]!!.count { it.first == season } == 1 }
            if (single.size == 1) {
                return Match.Unique(single.first(), "S${season}E$episode alone in '${base(paths[single.first()])}'")
            }
            return Match.Ambiguous(tier1, "${tier1.size} files claim S${season}E$episode")
        }

        // A pack that explicitly states OTHER episodes of this season is a pack whose naming we can read.
        // If it does not contain the requested one, the episode is genuinely absent — do not fall through
        // to weaker tiers and manufacture a match out of a codec token.
        if (candidates.any { i -> explicitAny[i]!!.any { it.first == season } }) {
            return Match.None
        }

        // --- tier 2: compact SEE / SSEE, corroborated by the pack ------------------------------------
        val compact = candidates.associateWith { i ->
            compactCodes(base(paths[i])).filter { it.first == season }.map { it.second }.distinct()
        }
        // In a COMPLETE-SERIES pack only 1/N of the files belong to the requested season, so a majority
        // over the whole pack is unreachable and the compact rule (the Law & Order fix) silently stopped
        // working for N >= 3. When the folders state seasons, judge corroboration within the requested
        // season's own folder — that is the population the shape has to agree across.
        val seasonScoped = candidates.filter { folderSeason(paths[it]) == season }
        val pool = if (seasonScoped.isNotEmpty()) seasonScoped else candidates
        val contributing = pool.filter { compact[it]!!.isNotEmpty() }
        val claimed = contributing.flatMap { compact[it]!! }
        // "A majority of the pack shares this shape" is the corroboration. One file carrying a 3-4 digit
        // number proves nothing — that is what a stray `1920x1080` or a year looks like — so a lone
        // contributor is never enough unless the pack is a single file.
        val corroborated = contributing.size * 2 >= pool.size &&
            (contributing.size >= 2 || pool.size == 1)
        if (corroborated && claimed.size == claimed.distinct().size) {
            val hits = contributing.filter { episode in compact[it]!! }
            if (hits.size == 1) return Match.Unique(hits.first(), "compact ${season}${"%02d".format(episode)}")
            if (hits.size > 1) return Match.Ambiguous(hits, "${hits.size} files carry compact code")
            // The pack IS compact-numbered and this episode is not in it. Same reasoning as tier 1:
            // stop, rather than let a weaker rule invent a match out of some other number.
            return Match.None
        }

        // --- tier 3: episode-only in the name, season corroborated elsewhere -------------------------
        return episodeOnlyPass(paths, candidates, season, episode)
    }

    private fun episodeOnlyPass(
        paths: List<String>,
        candidates: List<Int>,
        season: Int,
        episode: Int,
    ): Match {
        // Season evidence, in descending strength. A multi-season pack narrows to the season's OWN
        // folder first, so `…/Season 3/07 - Title.mkv` is judged against its twelve siblings and not
        // against five seasons of files that would all collide on the same numbers.
        val byFolder = candidates.filter { folderSeason(paths[it]) == season }
        val anyFolderSeason = candidates.any { folderSeason(paths[it]) != null }
        val pool: List<Int>
        val why: String
        when {
            byFolder.isNotEmpty() -> {
                pool = byFolder
                why = "folder says season $season"
            }
            anyFolderSeason -> {
                // Every folder names a season and none of them is the one we want.
                return Match.None
            }
            else -> {
                val seasons = candidates.flatMap { explicitCodes(paths[it]) }.map { it.first }.toSet()
                when {
                    seasons.size == 1 && seasons.first() == season -> {
                        pool = candidates
                        why = "pack states season $season"
                    }
                    seasons.isEmpty() && season == 1 -> {
                        // No season stated anywhere. Only season 1 is safe: for season 1 (and only
                        // season 1) absolute numbering and per-season numbering are the same numbers.
                        pool = candidates
                        why = "single unnumbered season"
                    }
                    else -> return Match.None
                }
            }
        }
        if (pool.size < 2) return Match.None

        for (shape in Shape.values()) {
            val values = pool.map { it to shapeValue(baseNoExt(paths[it]), shape, season) }
            if (values.any { it.second == null }) continue          // the pack does not agree on this shape
            val nums = values.map { it.second!! }
            if (nums.size != nums.distinct().size) continue          // two files claiming one number is noise
            val lo = nums.min()
            val hi = nums.max()
            if (lo > 2) continue                                     // a real season starts at 0, 1 or 2
            if (hi - lo + 1 > nums.size + 2) continue                // must be a mostly-contiguous run
            val hits = values.filter { it.second == episode }.map { it.first }
            if (hits.size == 1) return Match.Unique(hits.first(), "$why, consistent ${shape.name} numbering")
            if (hits.size > 1) return Match.Ambiguous(hits, "${hits.size} files numbered $episode")
            return Match.None                                        // consistent numbering, episode absent
        }
        return Match.None
    }

    /**
     * Index of the file that is season [season] episode [episode], or null when it cannot be identified
     * UNAMBIGUOUSLY. Callers that can do something useful with a tie should use [resolve] instead.
     */
    fun indexOf(
        names: List<String>,
        season: Int?,
        episode: Int?,
        isPlayable: (String) -> Boolean,
    ): Int? = (resolve(names, season, episode, isPlayable) as? Match.Unique)?.index

    private fun base(path: String) = path.substringAfterLast('/')

    private fun baseNoExt(path: String) = base(path).substringBeforeLast('.')
}
