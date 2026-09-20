package com.slickstream.feature.live

/**
 * Layout and resource policy for watching several live games at once — pure, so it is unit-tested.
 *
 * "1 PiP, and up to 4 using tiling-like logic." One game is the plain full-screen player; two is a
 * full-screen game with a second in the corner; three or four is a 2x2 grid. Every extra tile is its
 * own ExoPlayer and therefore its own hardware video decoder, and TV boxes have a small, fixed number
 * of those — so this file also decides how hard each tile is allowed to lean on the hardware.
 *
 * The rules are cheap on purpose: nothing here knows about Compose, players or coroutines. The
 * coordinator ([LivePlayerViewModel]) asks "what layout", "what cap", "who gets audio now" and applies
 * the answers.
 */
object LiveMultiView {

    /** Hard ceiling on simultaneous games. Beyond four the tiles are too small to read a score. */
    const val MAX_SLOTS = 4

    enum class Layout {
        /** One game, full screen — exactly the pre-multiview player. */
        SINGLE,

        /** Two games: one full screen, the other in a small corner overlay. */
        PIP,

        /** Three or four games in a 2x2 grid (a missing fourth cell is left empty). */
        GRID,
    }

    /** The largest decoded video size a tile may ask for, or null for "whatever the feed offers". */
    data class VideoCap(val maxWidth: Int, val maxHeight: Int)

    /**
     * A grid cell is at most half the screen in each direction, and a PiP overlay far less. Decoding
     * a 1080p rendition to paint a 960x540 cell wastes bandwidth AND, more importantly on a TV box,
     * decoder capacity — some boxes can run two 1080p sessions but four 720p ones. Capping the
     * rendition is what makes "4" reachable at all on modest hardware.
     */
    val GRID_CAP = VideoCap(1280, 720)

    /** A corner overlay is a fraction of the screen; 720p is already more than it can show. */
    val PIP_CAP = VideoCap(1280, 720)

    fun layoutFor(slotCount: Int, expanded: Boolean): Layout = when {
        expanded -> Layout.SINGLE
        slotCount <= 1 -> Layout.SINGLE
        slotCount == 2 -> Layout.PIP
        else -> Layout.GRID
    }

    /**
     * @param isPrimary the full-screen tile in [Layout.PIP], or the expanded tile in [Layout.SINGLE].
     *
     * The full-screen picture is never capped: it is what the viewer is actually watching. Everything
     * else is decoded no larger than it is drawn.
     */
    fun videoCapFor(layout: Layout, isPrimary: Boolean): VideoCap? = when (layout) {
        Layout.SINGLE -> null
        Layout.PIP -> if (isPrimary) null else PIP_CAP
        Layout.GRID -> GRID_CAP
    }

    /**
     * Whether a tile should keep DECODING video at all.
     *
     * When one tile is expanded to full screen the others are not drawn, and a decoder feeding an
     * invisible surface is the single most expensive way to do nothing on a TV box. Their video track
     * is disabled — which releases the codec — while the session, its feed and its watchdog stay
     * alive, so collapsing back to the grid is a ~1 s decoder re-init rather than a cold start.
     */
    fun videoEnabledFor(slotId: Int, expandedId: Int?): Boolean = expandedId == null || expandedId == slotId

    /**
     * Exactly one tile is audible. When the audible tile is removed, audio moves to the first
     * remaining tile rather than to nothing — a silent multiview reads as "broken", not "muted".
     */
    fun audibleAfterRemoval(remaining: List<Int>, removedId: Int, currentAudible: Int?): Int? = when {
        remaining.isEmpty() -> null
        currentAudible != null && currentAudible != removedId && currentAudible in remaining -> currentAudible
        else -> remaining.first()
    }

    /** True when there is room for another game. */
    fun canAdd(slotCount: Int): Boolean = slotCount < MAX_SLOTS

    /**
     * Which grid cell a slot occupies, by its position in the slot list. The primary (first) slot is
     * top-left, so adding games fills left-to-right, top-to-bottom — the order people expect.
     */
    data class Cell(val row: Int, val col: Int)

    fun cellFor(position: Int): Cell = Cell(row = position / 2, col = position % 2)

    /** "Title · S1 E3" for an episode, plain title for a movie. What a media tile is labelled. */
    fun mediaLabel(title: String, season: Int?, episode: Int?): String =
        if (season != null && episode != null) "$title · S$season E$episode" else title

    /**
     * The message shown when a box runs out of decoders. Stated as a fact about THIS device, with
     * the number it managed, so the user learns their ceiling instead of retrying forever.
     */
    fun decoderLimitMessage(streamsThatWorked: Int): String =
        "This device can't decode ${streamsThatWorked + 1} streams at once — it managed $streamsThatWorked."
}
