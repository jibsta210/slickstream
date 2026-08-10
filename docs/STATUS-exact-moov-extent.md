# Status — `fix/exact-moov-extent` — NOT SHIPPABLE AS-IS

**Verdict: do not merge.** Build is green and 68 unit tests pass, but three adversarial review
rounds each found real defects, and the same failure class keeps returning through new paths.

## What the change is worth (the diagnosis is solid — keep it)

A non-faststart MP4 keeps its index (`moov`) at EOF. The engine never located it: it required the
last `TAIL_PRIORITY_BYTES` (8 MB) rounded up to whole pieces — up to **64 MB** on a 32 MB-piece
torrent, at the far end of the file — to obtain an atom that is typically **1–3 MB**. It is also
wrong the other way: a moov larger than 8 MB starts OUTSIDE the guessed window, so the gate passes,
READY fires, and the player sits on "Almost ready…" reading an atom nobody prioritised.

The exact offset is already computable and thrown away: `mp4MoovInHead` walks the top-level box
chain and returns `false` on reaching `mdat` **before** using `boxLen` — yet `pos + boxLen` is
exactly where the next box (normally `moov`) begins. `Mp4BoxScan` (+21 tests) implements this and
is sound in isolation.

## Why it is blocked — one root cause, not a list of bugs

The probe must mutate **shared piece priorities/deadlines** concurrently with (a) the player's own
reads and (b) season-pack episode re-selection. The codebase has no lock discipline for that:
`nativeLock` serialises *native calls*, not the logical state machine.

Recurring class — **the probe lowers a piece the player is blocked on**:
* R2 (critical): `releaseProbePin` demoted a piece `ensureRange` had raised for a read ExoPlayer was
  blocked on (up to 75 s in `StreamHttpServer`), stopping its download → read fails → **source
  failover on a healthy swarm**. Fixed by never lowering priority there.
* R3 (major): the SAME failure via `applyIndexBand`'s unwind, whose skip-list covers the head band,
  the resume band and the new index range — but **not** the live read-ahead window
  (`readHeadPiece..+readahead`) or an in-flight `ensureRange` span.

Second class — **check-then-act on probe state**:
* `moovLocation` writes `headScan` / `moovProbe` / launches the probe with no epoch guard, so a walk
  that straddles a `selectFile` caches episode N's verdict onto episode N+1. Worst case: a cached
  `MoovFirst` makes the gate DROP the moov requirement for a non-faststart file → READY with no moov
  on disk → permanent "100% · Almost ready" (breaches the top invariant).
* `probeMoov`'s guard at the commit is check-then-act across a `nativeLock` acquisition, so a stale
  probe can still commit an `Exact` span computed from the wrong file.

## What a real fix needs (bigger than the optimisation justifies right now)

1. **Single owner for piece priority.** Nothing but the prioritisation pass should ever lower a
   priority; the probe should only ever *raise* + set deadlines, and clear deadlines on exit. Any
   unwind must skip the live read window as well as the head/resume/index bands.
2. **Atomic probe state.** Collapse epoch + verdict + latch into one immutable `ProbeState` behind an
   `AtomicReference`, committed with `compareAndSet(expected = state observed at launch)`, so a stale
   probe's write simply loses instead of racing. Or guard `selectFile`'s reset and the probe's commit
   with the same per-`ActiveTorrent` monitor.
3. **Runtime verification on a device** — none of this has ever run on hardware.

## Cheaper alternative that captures most of the win

Keep the exact extent for the **gate only** and never unwind anything: let `tailAvailable` require
just the proven moov range (so playback starts as soon as the index is really present), while the
old blind band keeps downloading in the background exactly as today. That deletes the entire
"demote a piece the player needs" class, leaving only the stale-verdict race to solve — which is
strictly the smaller problem.

## Also on this branch (independent, low risk, already reviewed)

`StartGate.fillFraction` now reads 100% **iff** the gate is open, and `estimateEta` stays silent
rather than freezing at its floor — killing the "100% · Almost ready…" that never starts. That part
is already merged to main via v1.6.12/v1.6.13 lineage and is not blocked by any of the above.
