# Handoff — TV UI focus, search titles, RD scrub previews, torrent start latency

**Date:** 2026-08-08 · **Branch:** `fix/tv-ui-focus-and-torrent-start` (3 commits, pushed) · **Base:** `e559b72`

This work was done by a *different* agent session than the one that normally owns SlickStream.
It was stopped part-way on purpose so the owning agent can take it over. Nothing was merged to `main`.

## Diagnoses (read these first — they are the valuable part)

### Search result titles are truncated far too aggressively: they are hard-limited to ONE line (`maxLines = 1`, ellipsis) inside a grid cell that is only ~95dp wide on a 960x540dp TV, so a 14sp title shows roughly 12-14 characters before "…". Nothing is being clipped by a fixed card height — the truncation is entirely the one-line cap fighting a very narrow cell.

**Confidence:** proven

**Files:**

```
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/components/TvPosterCard.kt (CardTitle, lines 253-263; call at 235-241; signature 63-72)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/screen/TvSearchScreen.kt (ResultsGrid, lines 319-369)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/TvTheme.kt (TvTypography, lines 35-48 — bodyMedium 14sp/20sp, bodySmall 13sp/18sp, labelMedium 12sp/16sp)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/components/TvNavRail.kt:81 (.width(212.dp))
SHARED — do not change globally: /home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/components/TvMediaRow.kt:58, /home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/screen/TvFavoritesScreen.kt:118, /home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/screen/TvCategoryScreen.kt:154
```

**Root cause:**

Three facts compose into the symptom:

(1) THE CAP. The title is rendered by the shared `CardTitle` inside `TvPosterCard`, hardcoded to `maxLines = 1` with `TextOverflow.Ellipsis` and `style = MaterialTheme.typography.bodyMedium`. Because `TvPosterCard` imports `androidx.tv.material3.MaterialTheme`, the effective style is TvTheme's `bodyMedium` = **14sp / 20sp lineHeight**, not the phone theme's 13sp.

(2) THE WIDTH. In `ResultsGrid` the cell width is derived from the viewport, and it lands very small. With the 212dp nav rail plus the screen's 48dp side padding, the grid's `maxWidth` on a 960x540dp TV is 960 - 212 - 48 - 48 = 652dp. The screen's own comment fixes the grid height at 433dp, so: rowHeight = (433-18)/2 = 207.5dp; posterHeight = 207.5 - 46 = 161.5dp; targetCardWidth = 161.5 x 2/3 = 107.7dp; columns = ceil(652 / (107.7+16)) = 6; actual cell width = (652 - 5x16)/6 = **95.3dp**. A 95dp line of 14sp text is ~12-14 glyphs. That is the "far too aggressive" truncation.

(3) NO CLIPPING. In `fillCell = true` mode the card's `Column` has no height constraint (only the Surface gets `aspectRatio(2f/3f)`), and `LazyVerticalGrid` sizes each row to its tallest item. So a second title line will NOT be clipped by the card. What it WILL do is push the row taller than the layout's own reserve: `textBlock = 46.dp` is the screen's assumption about how tall "title + year" is (8dp pad + 20dp one bodyMedium line + 16dp labelMedium year = 44dp, 2dp slack). Add a line and the real block is ~60dp while the reserve still says 46dp, so the two rows the grid is engineered to show would overflow the viewport. That reserve is therefore the constraint that must move with the maxLines change — not the card.

Secondary detail worth knowing: the existing `rowHeight` formula ignores the grid's own `contentPadding` (top 4 + bottom 24 = 28dp), so today's real budget is 405dp, not 433dp. Today's cards are 143+8+20+16 = 187dp, and 2x187+18 = 392dp fits inside 405dp. With two title lines the card becomes ~203dp and 2x203+18 = 424dp, which overruns 405dp by ~19dp — that is the only place the extra line can actually get sliced.

**Evidence:**

THE CAP — TvPosterCard.kt:253-263 (the only place a card title is rendered):

    @Composable
    private fun CardTitle(title: String, focused: State<Boolean>, modifier: Modifier = Modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused.value) Brand.OnSurface else Brand.OnSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }

with the import at TvPosterCard.kt:39 `import androidx.tv.material3.MaterialTheme`, so the style resolves to TvTheme.kt:44:

    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),

THE WIDTH RESERVE — TvSearchScreen.kt:330-337:

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val hGap = 16.dp
        val vGap = 18.dp
        val textBlock = 46.dp
        val rowHeight = (maxHeight - vGap) / 2
        val posterHeight = (rowHeight - textBlock).coerceAtLeast(80.dp)
        val targetCardWidth = (posterHeight * (2f / 3f)).coerceIn(90.dp, 165.dp)
        val columns = kotlin.math.ceil(maxWidth / (targetCardWidth + hGap)).toInt().coerceAtLeast(3)

and the ignored padding, TvSearchScreen.kt:344:

    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),

NO FIXED CARD HEIGHT in grid mode — TvPosterCard.kt:91-92 and 117-121: the Column is `modifier` only when `fillCell`, and only the Surface is constrained:

    modifier = if (fillCell) modifier else modifier.width(cardWidth),
    ...
    modifier = if (fillCell) {
        Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f)
    } else {
        Modifier.width(cardWidth).height(cardHeight)
    },

The title block is a plain sibling below it — TvPosterCard.kt:235-249 — `CardTitle(...) .padding(top = 8.dp)` then the year `Text(style = labelMedium, maxLines = 1)` (labelMedium = 12sp/16sp, TvTheme.kt:47). 8 + 20 + 16 = 44dp, which is what the 46dp reserve encodes.

**Proposed fix:**

Two edits. The card change must be OPT-IN, because `TvPosterCard` is shared with Home/Catalog/Details rows (TvMediaRow.kt:58), Favorites (TvFavoritesScreen.kt:118) and Category (TvCategoryScreen.kt:154) — a blanket `maxLines = 2` would retitle every screen and, in the fixed-width LazyRow case, make wrapped tiles taller than unwrapped neighbours so the year line loses its shared baseline.

EDIT 1 — TvPosterCard.kt. Add a parameter, default 1, so every existing call site is byte-identical in behaviour:

  * signature (after `fillCell: Boolean = false,` on line 71): add
        titleMaxLines: Int = 1,
  * call site (line 235): pass `maxLines = titleMaxLines` into `CardTitle`.
  * CardTitle (lines 253-263): take `maxLines: Int`, and pick the smaller token when wrapping is on —

        val style = if (maxLines > 1) MaterialTheme.typography.bodySmall
                    else MaterialTheme.typography.bodyMedium

    `bodySmall` is already defined in TvTheme.kt:45 as 13sp / 18sp (FontWeight.Medium), so this needs no new `sp` literal: 14sp -> 13sp is exactly the "slightly smaller" the user asked for, and 2 x 18sp costs less than 2 x 20sp would. If the Medium weight is unwanted, use `MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp)` instead — same metrics, Normal weight.
  * Also reserve the full block so cells in a grid row stay the same height and the year keeps one baseline:

        modifier = modifier.then(
            if (maxLines > 1) Modifier.height(with(LocalDensity.current) { style.lineHeight.toDp() } * maxLines)
            else Modifier
        )

    `height` is already imported (line 10); add `androidx.compose.ui.platform.LocalDensity`. Using `toDp()` rather than a dp literal keeps it correct under a non-1.0 font scale.

EDIT 2 — TvSearchScreen.kt ResultsGrid (lines 330-346), so the extra line is not sliced off the second row:

  * `val textBlock = 46.dp` -> `val textBlock = 64.dp`  (8 pad + 2 x 18 title + 16 year = 60, +4 slack)
  * `val vGap = 18.dp` -> `val vGap = 12.dp`
  * `contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)` -> `PaddingValues(top = 4.dp, bottom = 8.dp)`
  * pass `titleMaxLines = 2` to the `TvPosterCard(` call at line 350.

Why those exact numbers (960x540dp TV, grid = 652dp wide x 433dp tall per the file's own comment):
  - columns are UNCHANGED: posterHeight = (433-12)/2 - 64 = 146.5dp -> targetCardWidth = 97.7dp -> columns = ceil(652/113.7) = 6 -> cell width still 95.3dp. Poster art does not shrink.
  - height now fits: card = 143 (poster) + 8 + 36 (2 x 18) + 16 (year) = 203dp; 2 x 203 + 12 = 418dp against a real budget of 433 - 4 - 8 = 421dp. Today's single-line card is 187dp and 392 of 405 — so this stays inside the viewport with comparable slack, and the "~2 rows visible" property the comment defends is preserved.
  - text capacity goes from 1 x 95dp at 14sp to 2 x 95dp at 13sp, i.e. roughly 2.2x the characters before the ellipsis.
  - the focused card's 1.06 scale needs ~6dp o

---

### Issue 4 — scrub-preview filmstrip frames never appear for Real-Debrid (direct) sources, only for torrents. The filmstrip UI itself DOES render for RD; every slot is just an empty placeholder, because FrameThumbnailExtractor never decodes a single frame for a direct source.

Not the cause (ruled out by reading the code): the UI is NOT gated on source kind — PlayerScreen.kt:427 shows the strip on `if (previewMs != null && !isCasting)` and TvPlayerScreen.kt:1241 on `if (scrubbing)`; only the torrent chunk bar is isDirect-gated (PlayerScreen.kt:448 `currentSource?.isDirect != true`). It is not the libVLC skip (`usingVlcForSource` latches only for `playable == false`). And there is no deliberate "remote seeking is too slow, disable it" condition anywhere in the tree.

**Confidence:** proven

**Files:**

```
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/feature/player/FrameThumbnailExtractor.kt (lines 76-95 start guard, 123-211 run loop, 138-161 retriever construction, 186-195 availability gate)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/feature/player/PlayerViewModel.kt (lines 2042-2048 maybeStartThumbnails; 754-788 the direct-source early return; 936-980 validateDirectUrl; 1254-1266 buildMediaSourceFactory headers; 1395 the READY call site)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/data/torrent/TorrentEngine.kt (line 1274-1293 availableByteOffsets; 1296 filePath; 1352 fileLength)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/core/model/Stream.kt (lines 30-44 directUrl/requestHeaders/isDirect)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/data/source/SourceRepositoryImpl.kt (lines 291-333 requestHeaders + syntheticHash)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/feature/player/PlayerScreen.kt (lines 423-436 ScrubFilmstrip call, 448 chunk-bar isDirect gate)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/screen/TvPlayerScreen.kt (lines 1241-1243 TvFilmstrip call, 1224/1234 onScrubbingChange, 1342 TV_FILMSTRIP_STEP_MS)
```

**Root cause:**

Three stacked blockers, all from the extractor being designed exclusively around libtorrent's piece bitfield. The FIRST one alone is fatal — the sampling job never launches.

1. HARD STOP — fileLength is 0 for a direct source. `maybeStartThumbnails` feeds the extractor from the torrent engine: `torrentStreamer.fileLength(hash)` and `torrentStreamer.filePath(hash)`. But `startSource` returns at PlayerViewModel.kt:787 (inside `if (source.isDirect)`) BEFORE `torrentStreamer.start(source, startFraction)` is ever called, so the engine's `torrents` map has no entry for that hash. `TorrentEngine.fileLength` is a plain map lookup with a 0 default, so it returns 0L (and `filePath` returns null). `FrameThumbnailExtractor.start` then hits `if (durationMs <= 0L || fileLength <= 0L) return` and never launches the job. Zero frames, permanently. (`activeInfoHash` is NOT the gate — `StreamSource.infoHash` is a non-null String, real for an RD-resolved torrent or a `syntheticHash(url)` SHA-1 for a pure direct addon, so line 2044 passes fine.)

2. WOULD STILL BLOCK if you only fixed #1 — the per-round decode gate. Every sample is gated on `streamer.availableByteOffsets(hash, ...)`, whose impl bails on `val active = torrents[infoHash] ?: return@synchronized result`, returning an ALL-FALSE array for a direct source. Every candidate hits `if (!available.getOrElse(i) { false }) continue`, nothing is ever added to `done`, so `coarseDone && denseDone` is never true and the loop also never breaks — it would spin forever at IDLE_POLL_MS decoding nothing.

3. CORRECTNESS for header-gated hosts — the remote fallback sends NO headers. `candidate.setDataSource(streamUrl, HashMap<String, String>())` passes an empty map. `source.requestHeaders` (Referer/Origin/User-Agent, parsed from `behaviorHints.proxyHeaders.request` at SourceRepositoryImpl.kt:317) is handed to ExoPlayer (PlayerViewModel.kt:1257-1261) and to libVLC (line 1686) but never to the retriever. RD's own unrestricted `*.download.real-debrid.com` links are token-in-path so they need no auth header, but they do reject a blank User-Agent, and non-RD direct addons 403 without Referer/Origin.

Bonus: the same `fileLength <= 0` stop also silently kills thumbnails for OFFLINE `file://` sources (isOffline), which would otherwise decode instantly from local disk.

**Evidence:**

PlayerViewModel.kt:2042-2048 — the torrent-only inputs:
    private fun maybeStartThumbnails(player: Player) {
        if (usingVlcForSource) return
        val hash = activeInfoHash ?: return
        val url = currentMediaUrl ?: return
        val dur = player.duration.takeIf { it > 0 } ?: return
        thumbnails.start(url, hash, dur, torrentStreamer.fileLength(hash), torrentStreamer.filePath(hash))
    }

TorrentEngine.kt:1352 — returns 0 because no torrent was ever started for a direct source:
    fun fileLength(infoHash: String): Long = torrents[infoHash]?.fileLength ?: 0L

PlayerViewModel.kt:754 + 787 — why the map is empty: the direct path returns before torrentStreamer.start:
        if (source.isDirect) {
            ...
            directValidationJob = viewModelScope.launch { ... }
            return
        }
        streamJob = viewModelScope.launch { ... torrentStreamer.start(source, startFraction).collect { ... } }

FrameThumbnailExtractor.kt:79 — the fatal early return (fileLength == 0L):
        if (durationMs <= 0L || fileLength <= 0L) return

FrameThumbnailExtractor.kt:186-191 — the second blocker; all-false for a direct source, so every sample is skipped:
                val available = streamer.availableByteOffsets(hash, pending.map { it.second })
                var decodedThisRound = 0
                for (i in pending.indices) {
                    if (!currentCoroutineContext().isActive || decodedThisRound >= MAX_DECODES_PER_ROUND) break
                    if (!available.getOrElse(i) { false }) continue

TorrentEngine.kt:1277 — inside availableByteOffsets, the all-false bail:
        val active = torrents[infoHash] ?: return@synchronized result

FrameThumbnailExtractor.kt:144-145 — the remote fallback with an EMPTY header map:
                    if (path != null && java.io.File(path).exists()) candidate.setDataSource(path)
                    else candidate.setDataSource(streamUrl, HashMap<String, String>())

PlayerViewModel.kt:1257 — proof the headers exist and are used everywhere EXCEPT the retriever:
        val headers = src?.takeIf { it.isDirect }?.requestHeaders ?: emptyMap()

PlayerScreen.kt:427 — proof the UI is not the gate (strip renders for RD, just empty):
            if (previewMs != null && !isCasting) {

**Proposed fix:**

Give the extractor a DIRECT mode that is demand-driven and byte-availability-free, leaving the torrent path untouched (its free-only dense tier and MAX_DECODES_PER_ROUND cap are load-bearing for swarm bandwidth — do not disturb them).

1) Widen the entry point (FrameThumbnailExtractor.start):
    fun start(
        streamUrl: String, hash: String, durationMs: Long, fileLength: Long,
        filePath: String? = null,
        direct: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    )
Relax the guard so a direct source doesn't need a byte length at all:
    if (durationMs <= 0L) return
    if (!direct && fileLength <= 0L) return
This is safe because fileLength is used ONLY by byteOffsetForTime, which exists only to build the availableByteOffsets query — dead weight in direct mode.

2) Wire it up (PlayerViewModel.maybeStartThumbnails):
    val src = _currentSource.value ?: return
    if (src.isDirect) {
        val url = currentMediaUrl ?: return
        // HLS can't be frame-seeked by MediaMetadataRetriever — skip rather than burn decodes.
        if (url.substringBefore('?').endsWith(".m3u8", true)) return
        thumbnails.start(
            url, hash, dur, fileLength = 0L,
            filePath = if (src.isOffline) android.net.Uri.parse(url).path else null,
            direct = true, headers = src.requestHeaders,
        )
    } else {
        thumbnails.start(url, hash, dur, torrentStreamer.fileLength(hash), torrentStreamer.filePath(hash))
    }
The isOffline branch is a free win: a file:// source hands the retriever a real local path and decodes instantly.

3) Headers/auth (this is all RD needs). Build the retriever map from source.requestHeaders and guarantee a UA, mirroring buildMediaSourceFactory:
    val h = HashMap(headers)
    if (h.keys.none { it.equals("User-Agent", true) }) h["User-Agent"] = DEFAULT_USER_AGENT
    candidate.setDataSource(streamUrl, h)
IMPORTANT: RD unrestricted links (*.download.real-debrid.com) carry the token in the PATH — do NOT attach the RD API bearer token as an Authorization header to the CDN host. Referer/Origin/User-Agent from behaviorHints.proxyHeaders.request are the only headers that matter, and they matter mostly for non-RD direct addons.

4) Replace the poll loop with a demand-driven worker in direct mode — this is what keeps playback un-janked:
 - NO grid prefetch. getFrameAtTime on an HTTP URL opens a SECOND connection to the RD CDN and does its own ranged reads; a 20+180-point sweep would compete with ExoPlayer's buffer and can trip RD per-file connection limits. Decode only what the strip actually asks for.
 - Coalesce requests. thumbnailAt already records lastScrubMs; also publish the position SNAPPED to the strip's own grid (FILMSTRIP_STEP_MS / TV_FILMSTRIP_STEP_MS, both 60_000L) into a MutableStateFlow<Long>, and have one worker consume it with collectLatest. A fast D-pad scrub then collapses to the newest position instead of queueing dozens of decodes, and decoded keys land 

---

### Issue 5 — "fresh torrents take far too long to start; 3-4 teal head blocks are visible yet the player still says low buffer / keeps waiting."

Two independent defects produce the one symptom, and they must be separated:

(A) THE "low buffer" TEXT IS A GUARANTEED FALSE ALARM AT 3-4 BLOCKS. It is not the start gate at all — it is `healthOf()` in PieceBar.kt, which measures the downloaded lead as a FRACTION OF THE WHOLE FILE across 110 buckets and demands >= 4%. ceil(0.04 * 110) = 4.4, so the bar cannot report "healthy" until the 5th bucket fills. Exactly 3 blocks = 2.73%, 4 blocks = 3.64% -> both fall through to `BarHealth(Brand.Star, "low buffer")`. The user's "3-4 teal blocks and still low buffer" is literally arithmetic, independent of swarm health.

(B) THE REAL START GATE IS NOT THE HEAD — IT IS THE MP4 EOF TAIL, AND IT IS BOTH TOO STRICT AND NOT STRICT ENOUGH. The head requirement is only 2 MB and is long since satisfied by the time even ONE bucket is teal. What actually holds `canStart` false is `tailAvailable()`, which requires EVERY WHOLE PIECE covering the last 8 MB of the file, deadlined BEHIND the entire 6 MB head band.

**Confidence:** strong

**Files:**

```
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/data/torrent/TorrentStreamerImpl.kt (gate at 237-281; READY_HEAD_BYTES:714; MOOV_TAIL_BYTES:717; TAIL_STALL_TIMEOUT_MS:740; PIECE_MAP_CACHE_BUCKETS:731)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/data/torrent/TorrentEngine.kt (prioritizeHeadAndTail:943-1024; cancelStartupTail:1027; contiguousHeadBytes:1122-1147; contiguousBytesFrom:1150-1161; tailAvailable:1176-1197; pieceMap:1231-1252; mp4MoovInHead:1315-1349; advanceReadHead:1538-1583; tailFromPiece:1592-1596; HEAD_PRIORITY_BYTES/TAIL_PRIORITY_BYTES:1631-1632; HEAD_DEADLINE_STEP_MS:1642)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/feature/player/PieceBar.kt (PIECE_BAR_BUCKETS:88; healthOf:164-178)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/feature/player/PlayerViewModel.kt (LoadControl:1316-1333; bufferFillPercent:1205-1206; PLAYABLE_TARGET_BYTES:2546)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/data/torrent/StreamHttpServer.kt (fillChunk/ensureRange read path:270-321)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/screen/TvPlayerScreen.kt (chunk bar shown during BUFFERING:517-546)
```

**Root cause:**

QUANTIFIED, WITH REAL NUMBERS FROM THE CODE.

1. What the gate demands (TorrentStreamerImpl.kt:242-281):
   - head: READY_HEAD_BYTES = 2 MB contiguous from the file start, AND 2 MB contiguous from the resume anchor (identical for a fresh play, since selectFile sets `active.streamStartPiece = active.firstPiece`).
   - tail: for mp4/m4v/mov that are not faststart, ALL pieces covering TAIL_PRIORITY_BYTES = 8 MB at EOF.
   Because both are measured in WHOLE PIECES, the true cost is piece-rounded: on a 32 MB-piece torrent the "2 MB" head is one 32 MB piece and the "8 MB" tail is 1-2 pieces = 32-64 MB. Worst realistic case is ~96 MB of specific bytes before READY can fire — at 1 MB/s that is ~96 s, and the head keeps filling the whole time, which is precisely what paints the 3-4 teal blocks the user is staring at.

2. Why the head races ahead while the tail lags (TorrentEngine.kt:967-1024). `headPieces = ceilDiv(HEAD_PRIORITY_BYTES=6 MB, pieceLen)` and `tailBase = headPieces * HEAD_DEADLINE_STEP_MS (3000)`. The ENTIRE 6 MB head band is deadlined ahead of the moov band, then the tail pieces are staggered a further 3 s apart on top of that. With a 1 MB piece length: headPieces = 6, tailBase = 18 s, and the 8 tail pieces carry deadlines 18 s, 21 s ... 39 s. So the swarm is explicitly told to finish 6 MB of head (plus the un-deadlined sequential fill behind it) before the moov — even though the gate only needs 2 MB of that head. The one artefact that is actually blocking READY is scheduled last.

3. The bar and the gate measure different things (this is the candidate cause that is confirmed):
   - bar: per-bucket piece availability over the WHOLE FILE, 256 raw buckets resampled to 110, teal at fill >= 0.999 (PieceBar.kt:64-66). One teal bucket on an 8 GB / 8 MB-piece torrent is ~9 contiguous pieces ~= 72 MB.
   - gate: 2 MB contiguous head + an 8 MB EOF band the bar renders at the FAR RIGHT of the strip.
   So the head can show 3-4 teal blocks (roughly 50-300 MB depending on piece size) while the byte the gate is waiting on is off at the other end of the bar. Nothing in the head region can ever satisfy it.

4. The tail gate is also NOT SUFFICIENT, which produces the other half of the wait. `tailAvailable()` only checks the last 8 MB. A feature-length 4K/multi-track moov routinely exceeds 8 MB, so its start offset is BEFORE `tailFromPiece`. In that case the gate passes, READY is emitted, ExoPlayer's Mp4Extractor issues RESULT_SEEK to the real moov offset, and `ensureRange` then has to deadline-fetch from that offset to lastPiece while the user sits on "Almost ready…" with a healthy-looking bar. The gate is simultaneously ~8x too strict for a small moov and too loose for a large one, because it guesses a fixed 8 MB window instead of reading the actual moov extent.

5. Not the cause, ruled out with evidence:
   - No fixed time window: the poll loop has no wall-clock start cap, and the old 15 s tail-grace was deliberately removed.
   - ExoPlayer is not the gate: DefaultLoadControl uses bufferForPlaybackMs = 1_500 (PlayerViewModel.kt:1320), so once bytes exist first frame is ~1.5 s.
   - Not anchoring at the head while the playhead is elsewhere: `contiguousResumeBytes` correctly tracks `streamStartPiece`. BUT the BAR does have this bug: during BUFFERING `playheadFraction` is 0 (player duration is 0), so on a RESUME `healthOf` measures the lead at the FILE HEAD while the download is concentrated at the resume anchor -> lead 0 -> "stalled"/"low buffer" while the resume region is filling perfectly.
   - Concentration is off (`concentrate = false`, TorrentStreamerImpl.kt:153), so the base sequential fill is live; the tail is not being starved by an IGNORE base.

WHAT IS GENUINELY REQUIRED TO DECODE A FIRST FRAME, versus what is demanded:
   - mkv/webm/avi: EBML header + Tracks (typically < 1 MB) + ~1.5 s of media. Demanded: 2 MB head. Already correct — these are not the slow case.
   - faststart mp4: ftyp + moov at the front + ~1.5 s of mdat. Demanded: 2 MB head, tail correctly cancelled via `mp4MoovInHead` -> `cancelStartupTail`. Also correct.
   - non-faststart mp4/mov: ftyp + the EXACT moov atom + ~1.5 s of mdat from the first sample. That is typically 1-3 MB of moov, at an offset that is exactly computable. Demanded instead: a blind 8 MB EOF band rounded up to whole pieces (up to 64 MB), fetched last. This is the entire delay.

**Evidence:**

GATE — TorrentStreamerImpl.kt:242-281
    val headReady = headBytes >= READY_HEAD_BYTES && snap.contiguousResumeBytes >= READY_HEAD_BYTES
    val moovInHead = if (containerNeedsTail) engine.mp4MoovInHead(infoHash, headBytes) else null
    if (moovInHead == true) engine.cancelStartupTail(infoHash)
    val needsTail = containerNeedsTail && moovInHead != true
    val tailReady = !needsTail || engine.tailAvailable(infoHash)
    ...
    val canStart = headReady && tailReady
TorrentStreamerImpl.kt:714
    private const val READY_HEAD_BYTES = 2L * 1024L * 1024L

BLIND 8 MB EOF BAND — TorrentEngine.kt:1592-1596 and 1632
    private fun tailFromPiece(active: ActiveTorrent): Int {
        val firstTailByte = (active.fileLength - TAIL_PRIORITY_BYTES).coerceAtLeast(0L)
        return ((active.fileOffset + firstTailByte) / active.pieceLength).toInt()
            .coerceIn(active.firstPiece, active.lastPiece)
    }
    const val TAIL_PRIORITY_BYTES = 8 * 1024 * 1024
TorrentEngine.kt:1181-1195 (tailAvailable — whole pieces, all-or-nothing)
    val tailFrom = tailFromPiece(active)
    ...
    for (p in active.lastPiece downTo tailFrom) {
        if (p in active.firstPiece..active.lastPiece) {
            if (p >= pieceCount || !pieces.getBit(p)) return@synchronized false

MOOV SCHEDULED BEHIND THE WHOLE 6 MB HEAD BAND — TorrentEngine.kt:967-1020
    val headPieces = maxOf(1, ceilDiv(HEAD_PRIORITY_BYTES, pieceLen))
    val tailBase = headPieces * HEAD_DEADLINE_STEP_MS
    ...
    if (tailFrom != null) {
        var i = 0
        for (p in active.lastPiece downTo tailFrom) {
            ...
            handle.setPieceDeadline(p, maxOf(tailBase, resumeBase) + i * HEAD_DEADLINE_STEP_MS)
(HEAD_PRIORITY_BYTES = 6 MB, HEAD_DEADLINE_STEP_MS = 3_000 -> with 1 MB pieces the moov band starts at 18 s and runs to 39 s, while the gate only needs 2 MB of that head.)

THE MOOV OFFSET IS ALREADY IN HAND AND THROWN AWAY — TorrentEngine.kt:1329-1341
    when (String(hdr, 4, 4, Charsets.US_ASCII)) {
        "moov" -> return@use true               // index up front -> faststart, no tail wait
        "mdat", "moof" -> return@use false      // media before index -> moov at EOF
    }
    val boxLen: Long = when {
        size32 == 1 -> { ... }
        size32 == 0 -> return@use false
        else -> size32.toLong()
    }
(It returns `false` on "mdat" BEFORE computing boxLen — yet pos + boxLen is exactly where the next box, normally moov, begins.)

FALSE "low buffer" — PieceBar.kt:88 and 169-178
    const val PIECE_BAR_BUCKETS = 110
    val ph = (playheadFraction * map.size).toInt().coerceIn(0, map.size - 1)
    var lead = 0
    var i = ph
    while (i < map.size && map[i] >= 0.85f) { lead++; i++ }
    val leadFraction = lead.toFloat() / map.size
    if (leadFraction >= 0.04f) return BarHealth(Brand.Cyan, null)
    ...
    return BarHealth(Brand.Star, "low buffer")
(3/110 = 0.0273 and 4/110 = 0.0364, both < 0.04. 5 buckets = 4.5% of the FILE; on an 8 GB file that is ~370 MB of lead demanded, versus the 40 MB / 50 s ExoPlayer will ever hold — PlayerViewModel.kt:1317-1330.)

FIRST FRAME IS CHEAP ONCE BYTES EXIST — PlayerViewModel.kt:1317-1322
    .setBufferDurationsMs(
        if (lowPower) 15_000 else 15_000,  // minBufferMs
        if (lowPower) 45_000 else 50_000,  // maxBufferMs
        1_500,                             // bufferForPlaybackMs — begin playback this fast
        4_000,                             // bufferForPlaybackAfterRebufferMs

**Proposed fix:**

Four surgical changes. None touches the no-time-escape rule (still no wall-clock hatch), the container-aware tail (still container-gated), failoverToSmaller, resume anchoring, or chunk-bar honesty.

FIX 1 (the real one) — replace the blind 8 MB EOF band with the EXACT moov extent.
In TorrentEngine.mp4MoovInHead, when the walk hits "mdat"/"moof", do not discard the box length. Compute `nextBox = pos + boxLen` and return a small result type carrying it (e.g. `MoovLocation.AtOffset(nextBox)` alongside InHead / Unknown). Then:
  - Add `fun ensureMoovHeader(infoHash, offset)`: deadline just the one piece covering `offset`, read 8 (or 16 for 64-bit largesize) bytes, and if the type is "moov" you now have the exact [moovStart, moovStart + moovSize) range. Store it on ActiveTorrent as `moovRange`.
  - `tailAvailable()` becomes: if `moovRange != null`, require only the pieces covering that range; otherwise fall back to today's last-TAIL_PRIORITY_BYTES check verbatim (fragmented mp4, `free`/`udta` sitting between mdat and moov, or an unreadable header).
  - `prioritizeHeadAndTail` band (3) deadlines exactly `moovRange`'s pieces instead of `lastPiece downTo tailFromPiece`; keep the existing band for the fallback path.
Effect: a 1.5 MB moov costs 1.5 MB (piece-rounded), not 8-64 MB. And when the moov is 12 MB the gate now covers all of it, killing the "READY but sits at Almost ready…" case the fixed window silently allows. Everything mkv/webm/avi and every faststart mp4 is unchanged.

FIX 2 — stop scheduling the moov behind head bytes nobody is waiting for.
The gate needs READY_HEAD_BYTES (2 MB), not HEAD_PRIORITY_BYTES (6 MB). Split band (1): deadline `gateHeadPieces = max(1, ceilDiv(2 MB, pieceLen))` at 0, 3000, ...; put the moov/tail band immediately after that (`tailBase = gateHeadPieces * HEAD_DEADLINE_STEP_MS`); put the REMAINDER of the 6 MB comfort head band after the moov. Same total work, same in-order convergence, but the only two artefacts that gate READY are now first and second in the deadline queue instead of first and seventh. Add READY_HEAD_BYTES to TorrentEngine's companion (or pass it in) so the two files cannot drift.

FIX 3 — make the bar's health honest about what "enough lead" means.
In PieceBar.healthOf, replace the file-fraction test with a TIME test: bytesAhead = lead * (fileLength / map.size); leadSeconds = bytesAhead / (fileLength / durationSeconds). Treat >= ~30 s of video as healthy (matched to maxBufferMs = 50_000), keeping the existing rate/stall fallbacks untouched for a thin lead. Pass fileLength + duration through StreamStats (both already available to the VM: `torrentStreamer.fileLength(infoHash)` and `_currentPlayer.value?.duration`); when either is unknown, fall back to the current fraction rule. Also anchor `ph` at the resume fraction while `playheadFraction` is still 0 (pre-first-frame), so a resumed title is not judged at a file head it will never play.

FIX 4 (cheap correctness) — while the gate is pre-READY, the buff

---

### Issues 1 and 2 are one bug with two faces: nothing in the TV shell preserves *which item had D-pad focus* across a navigation to Details and back. (1) Home/Movies/TV carousels have zero focus-restore machinery, so Back returns with NOTHING focused and the first D-pad press re-enters at the top of the screen (and the row's horizontal offset then gets snapped to 0 by bringIntoView when focus lands on the first card). (2) Search is the only screen that hand-rolls a restore, so its symptom is milder but wrong: the restore misses and falls back to the mic orb / lands in the first *visible* grid row — which is always exactly one row above the row you left, because D-pad scrolling in a 2-row viewport leaves your row as the BOTTOM visible row.

**Confidence:** strong

**Files:**

```
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/TvApp.kt
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/screen/TvHomeScreen.kt
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/screen/TvCatalogScreen.kt
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/screen/TvSearchScreen.kt
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/components/TvMediaRow.kt
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/components/TvPosterCard.kt
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/screen/TvDetailsScreen.kt
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/screen/TvCategoryScreen.kt (same broken idiom)
/home/jakes/SlickSTream/app/src/main/java/com/slickstream/tv/screen/TvFavoritesScreen.kt (same broken idiom)
```

**Root cause:**

Three stacked causes.

(A) THE STRUCTURAL ONE — the destination composable is destroyed, and Compose has no cross-composition focus memory.
navigation-compose renders each destination inside AnimatedContent; when the transition to Routes.DETAILS finishes, the browse destination is removed from composition. Scroll positions survive this (rememberLazyListState / rememberLazyGridState are rememberSaveable-backed — I confirmed LazyGridState$Companion.getSaver() and LazyListStateKt.rememberLazyListState exist in foundation 1.7.6, and NavHost wraps each entry in a SaveableStateHolder), but FOCUS does not: every FocusTargetNode is rebuilt unfocused, and Modifier.focusRestorer() (present in ui 1.7.6 as FocusRestorerKt) only remembers a focus group's last child *within a live composition* — it cannot survive disposal either. Meanwhile TvDetailsScreen grabs focus onto its Play button (LaunchedEffect at TvDetailsScreen.kt:473); when Details is finally disposed at the end of the back transition, the focused node disappears and focus is cleared to the root. TvHomeScreen/TvCatalogScreen request nothing on entry (deliberately — see the comments at TvHomeScreen.kt:160-164 and TvCatalogScreen.kt:86-89), and TvApp's rail grab is LaunchedEffect(Unit), i.e. app-launch only (TvApp.kt:62-68). So the screen settles with nothing focused; the first D-pad press runs a focus search from the root and lands on the first focusable in that direction (nav rail / hero), never on the poster you came from. TvMediaRow has no FocusRequester, no focusGroup, no focusRestorer, and does not hoist its LazyRow state — there is literally nothing to restore to.

(B) THE HOUSE RETRY IDIOM IS A FALSE POSITIVE — it reports success when nothing got focused.
Every screen (TvSearchScreen:150, TvCategoryScreen:70, TvFavoritesScreen:69, TvDetailsScreen:476, TvSearchBar:264, TvApp:66) uses `if (runCatching { target.requestFocus() }.isSuccess)`. I decompiled FocusRequester from ui-1.7.6: `requestFocus()` is `void`, and its body is `focus$ui_release(); pop; return` — the boolean result is DISCARDED, and the only throw path is "requester is Default/Cancel" or "focusRequesterNodes is empty" (not initialized). So `.isSuccess` means only "a node with this requester is attached", not "focus moved". On Search that means the loop can stop, set `focusTarget = null`, and skip the mic fallback while nothing at all is focused.

(C) WHY SEARCH IS OFF BY EXACTLY ONE ROW — the rail is yanked out from under the outgoing screen, so the grid's saved scroll comes from a different layout than the one you return to.
In TvApp the rail is a conditional child of the same Row as the NavHost, and `showRail` is false for DETAILS. currentBackStackEntryAsState flips the moment you navigate, but the Search screen stays composed for the whole exit transition — so it re-measures 212dp WIDER (rail gone) before it is disposed. TvSearchScreen derives its column count from that transient viewport (`columns = ceil(maxWidth / (targetCardWidth + hGap))`, line 337), so the LazyGridState value that gets saved (firstVisibleItemIndex) is the wide, more-columns layout's top item. Coming back, the rail is present again, columns drops, and the restored index maps to a viewport shifted by a row. If the cell at `returnIndex` is not in that shifted window it is never composed, `gridReturnFocus` has no attached node, requestFocus() throws "not initialized" for all 12 retries, and the code deliberately falls back to the mic orb (line 158). One DOWN press from the orb enters the grid at its FIRST VISIBLE row — and because focus-driven scrolling always leaves the row you are on as the LAST visible row, that first visible row is precisely the row above the one you came from. That is the off-by-one, and it is geometric, not an index arithmetic error: `lastOpenedIndex` itself is recorded and restored correctly.

**Evidence:**

1) TvMediaRow.kt:46-57 — the carousel has no focus state at all and does not hoist its list state:
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 22.dp),
    ) {
        items(items, key = { "${it.mediaType.name}-${it.id}" }, contentType = ...) { item ->
            TvPosterCard(item = item, onClick = onItemClick, wide = wide, progress = progressFor?.invoke(item))
        }
    }
TvHomeScreen.kt:213-217 calls it with only title/items/onItemClick — no index, no "which one did I open".

2) TvHomeScreen.kt:160-164 — the one FocusRequester on the screen is deliberately never requested:
    // ...we DON'T auto-focus it on entry any more...
    val heroPlayFocus = remember { FocusRequester() }
and TvApp.kt:63 `androidx.compose.runtime.LaunchedEffect(Unit) { repeat(15) { ... railFocus.requestFocus() ... } }` fires once per process, not per Back.

3) FocusRequester (ui-1.7.6, javap -c) — the success test in the retry loop is meaningless:
    public final void requestFocus();
      0: aload_0
      1: invokevirtual  // Method focus$ui_release:()Z
      4: pop
      5: return
Only `check(focusRequesterNodes.isNotEmpty())` throws. Callers use `if (runCatching { target.requestFocus() }.isSuccess)` — TvSearchScreen.kt:150, TvCategoryScreen.kt:70, TvFavoritesScreen.kt:69, TvDetailsScreen.kt:476, TvApp.kt:66.

4) TvSearchScreen.kt:144-159 — on a miss the loop hands focus to the mic, not to the grid:
    LaunchedEffect(focusTarget) {
        val target = focusTarget ?: return@LaunchedEffect
        repeat(12) {
            kotlinx.coroutines.delay(40)
            if (runCatching { target.requestFocus() }.isSuccess) { focusTarget = null; return@LaunchedEffect }
        }
        focusTarget = if (target === micFocus) null else micFocus
    }

5) TvSearchScreen.kt:355-357 — the requester exists ONLY on a composed cell, so a shifted restore window makes it unresolvable:
    .then(if (index == returnIndex) Modifier.focusRequester(returnFocus) else Modifier)
and nothing scrolls the grid to `returnIndex` first (the only scroll is `LaunchedEffect(state.isLoading) { if (state.isLoading) gridState.scrollToItem(0) }`, line 172).

6) TvSearchScreen.kt:330-337 — column count (and therefore index→row mapping and the meaning of the saved firstVisibleItemIndex) is derived from the transient viewport:
    val rowHeight = (maxHeight - vGap) / 2
    val posterHeight = (rowHeight - textBlock).coerceAtLeast(80.dp)
    val targetCardWidth = (posterHeight * (2f / 3f)).coerceIn(90.dp, 165.dp)
    val columns = kotlin.math.ceil(maxWidth / (targetCardWidth + hGap)).toInt().coerceAtLeast(3)

7) TvApp.kt:75-82 + 118-126 — that viewport changes by the full 212dp rail width the instant you navigate to Details, while the outgoing screen is still composed and measuring:
    val showRail = currentRoute == null || currentRoute == Routes.HOME || ... || currentRoute == Routes.PROFILE
    Row(Modifier.fillMaxSize()) {
        if (showRail) { TvNavRail(...) }
        Box(Modifier.weight(1f).fillMaxSize()) { NavHost(...) }
    }
(TvNavRail.kt:81 — `.width(212.dp)`.)

8) TvPosterCard.kt:91-95 vs 117-121 — the caller's modifier (with the focusRequester) lands on the outer Column, while the actual focus target is the inner Surface:
    Column(modifier = if (fillCell) modifier else modifier.width(cardWidth), ...) {
        Surface(onClick = { onClick(item) }, ..., modifier = if (fillCell) Modifier.fillMaxWidth().aspectRatio(...) else Modifier.width(cardWidth).height(cardHeight))
It happens to work (FocusRequesterModifierNode.requestFocus visits child layout nodes), but it means the request is order-dependent and cannot be used with focusRestorer.

**Proposed fix:**

Minimal, surgical, no navigation or player changes. Three parts; part 1 is the prerequisite for the other two.

1) Fix the retry idiom so "focused" means focused (shared helper, ~20 lines, e.g. tv/components/TvFocusRestore.kt). Do not infer success from the absence of an exception. Have the target report reality:
   - give TvPosterCard a real `focusRequester: FocusRequester? = null` parameter and put it on the SURFACE (`Modifier.focusRequester(it)` merged into the Surface's modifier, not the outer Column), plus an optional `onFocused: (() -> Unit)?` via `Modifier.onFocusChanged { if (it.isFocused) onFocused() }`;
   - the restore loop becomes `repeat(N) { delay(40); runCatching { target.requestFocus() }; if (landed) return; }` where `landed` is set by that onFocusChanged. Only after the loop truly fails do you take the fallback. Apply the same correction to TvSearchScreen:150; TvCategoryScreen:70 / TvFavoritesScreen:69 / TvDetailsScreen:476 can follow in the same pass.

2) Issue 2 (Search) — guarantee the cell is composed before requesting it, and stop trusting the saved offset:
   In the `returning` branch, before the focus loop, scroll the grid to the target row:
       LaunchedEffect(Unit) { if (returnIndex >= 0) runCatching { gridState.scrollToItem(returnIndex) } }
   (scrollToItem takes an item index and snaps to its line, so it is column-count agnostic — this is exactly what immunises the restore against the rail-width re-measure described in cause C). With (1) in place, the mic fallback then only fires when the item genuinely no longer exists.
   Optional hardening for the same cause: derive `columns` from a stable width (screen width minus the rail) rather than the transient `maxWidth`, in TvSearchScreen/TvCategoryScreen/TvFavoritesScreen; or reserve the rail's 212dp while a transition is in flight. Not required once the restore scrolls by index.

3) Issue 1 (Home / Movies / TV rows) — port Search's re-entry ticket to the rows, which is the smallest change that satisfies "restore the exact item and the row's scroll offset":
   - TvMediaRow gains: a hoisted `state: LazyListState = rememberLazyListState()` (already saveable, so the horizontal offset restores), `restoreIndex: Int = -1`, `restoreFocus: FocusRequester? = null`, and `onItemClick: (Int, MediaItem) -> Unit` (index included). It attaches `restoreFocus` to the card whose index == restoreIndex via the new TvPosterCard parameter.
   - TvHomeScreen.HomeContent and TvCatalogScreen.CatalogContent hoist one ticket for the whole screen:
         var lastOpened by rememberSaveable { mutableStateOf("") }   // "$rowKey#$index"
         val restoreFocus = remember { FocusRequester() }
         val columnState = rememberLazyListState()   // pass to the LazyColumn
     each row's click wrapper records `lastOpened = "${row.title}#$index"` before calling onMediaClick, and one screen-level effect (guarded by `remember { lastOpened }` so it only runs on a Back, exactly like TvSearchScreen's `return

---


## What was actually changed (3 commits on the branch)

| Commit | Subject |
|---|---|
| `f1e0066` | Sample scrub previews from the stream itself when nothing torrent-backed is behind it |
| `8ad7904` | Start the moment the bytes are there, and stop the bar disagreeing with the gate |
| `5bc674d` | Put BACK where I left it: carry focus across the trip into Details |

Files touched (17 files, +1552 / -156):

- **New:** `tv/components/TvFocusRestore.kt` (202 lines) — focus-ticket mechanism
- **New tests:** `data/torrent/StartGateTest.kt` (263), `tv/components/TvFocusTicketTest.kt` (121)
- **Modified:** `tv/screen/{TvHomeScreen,TvSearchScreen,TvCatalogScreen,TvCategoryScreen,TvFavoritesScreen}.kt`,
  `tv/components/{TvMediaRow,TvPosterCard}.kt`, `feature/player/PlayerViewModel.kt`, and others.

## ⚠ Status — NOT verified, NOT finished

- **No Android device was attached**, so *nothing* was runtime-verified. Compile-only at best.
- The workflow was **stopped before its adversarial review and final build gate ran**. So:
  - the build was NOT confirmed green after the last commit;
  - no review checked for regressions (focus stranding, clipped layouts, retriever leaks, starting playback too early and stuttering).
- Treat this branch as **an informed draft**, not a finished change.

## Recommended next steps for the owning agent

1. Read the diagnoses above before reading the diff — they are more reliable than the code.
2. Build: `/home/jakes/android-dev/slbuild.sh :app:assembleDebug` (system JDK is wrong; this script sets JDK 17 + SDK; it echoes `BUILD_EXIT_CODE`).
3. Run the new unit tests: `/home/jakes/android-dev/slbuild.sh :app:testDebugUnitTest`.
4. Decide per-issue whether to keep, rework, or discard each commit. Issue 5's diagnosis (the PieceBar 4%-of-whole-file arithmetic, and the mp4 EOF-tail gate) is the highest-value finding and is worth keeping even if the implementation is replaced.
5. Verify on a real device — especially: focus restore on Home / Movies & TV / Search, title wrapping, RD scrub previews, and time-to-first-frame on a fresh torrent.

## Environment changes made OUTSIDE the repo (please be aware)

- Created `~/.gradle/gradle.properties` capping Gradle/Kotlin daemon memory
  (`org.gradle.jvmargs=-Xmx2g`, `kotlin.daemon.jvmargs=-Xmx2g`, `org.gradle.workers.max=4`).
  Previous file, if any, backed up to `~/.gradle/gradle.properties.bak`.
  **Note:** the project's own `gradle.properties` sets `-Xmx4096m`, which takes precedence over the user-level file.
- Reason: an unrelated uncapped test run earlier that day reached 19 GB RSS and nearly froze the machine.
