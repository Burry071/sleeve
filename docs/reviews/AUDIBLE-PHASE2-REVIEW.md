# Audible phase 2 v1 review - multi-part books

Baseline: `HEAD ba79e156` plus the uncommitted beta.4 + phase-1 + phase-2-v1 tree, reviewed 2026-11-11.
Read-only: no code was edited. Every test number below was produced on this tree; nothing is taken
from the brief.

**Acceptance standard used.** `docs/research/AUDIBLE-RELIABILITY.md` section 9.4 (multi-part decision:
separate files, no concatenation; missing/duplicate/mixed-edition/out-of-order/one-part-fails rules),
9.2 (sum tolerance, min part, max parts, language), 9.1 step 5 (contiguous 1..N, same channel, same
language, per-part limits), 9.3 (`INCOMPLETE` is resumable, never a silent `Completed`), 9.5 (split
book card, candidate rows, batch "needs a pick"), and 11.5 AC4/AC5/AC6. Phase-1 findings in
`docs/reviews/AUDIBLE-PHASE1-REVIEW.md` and `AUDIBLE-PHASE1-FINAL.md` were used as the baseline so
this report adds phase-2 findings rather than re-reporting accepted ones.

**Test runs (mine):**
- `:app:testGenericDebugUnitTest -PnoSplits --rerun-tasks` -> BUILD SUCCESSFUL; 194 tests in 32
  result files, 0 failures, 0 skipped.
- `:app:assembleGenericDebug -PnoSplits` -> BUILD SUCCESSFUL.
- `python -m unittest discover -s scripts -p "test_*.py"` -> Ran 131 tests, OK.

Green suites do not cover the ranker -> gate hand-off; that is where the blocking finding is.

---

## 1. Conclusion

**Not shippable as-is: four must-fix findings, all correctness.** The Python split core is careful:
contiguity, zero-based sets, duplicate ids, per-part length, same channel, per-part declared language,
sum tolerance and single-before-split ordering are all implemented and unit-tested, and the Kotlin
plumbing (`AudibleGateResult.parts`, `MatchResult.parts`, `directUrl`, per-part runtime verification)
is coherent. What blocks release is that in production the ranker drops the common `Part N` uploads (including the research corpus) and at least one part of every 5+-part set before the gate can see them, so the phase-2 split path is unreachable for exactly those cases; the review sheet labels every part one number too high; a same-channel set
can splice two narrators/editions because narrator/edition is deliberately erased before comparison;
and a failed override of a held book clears the hold, taking the only picker with it.

The green tests are real but test the gate and the ranker separately. Section 2.1 contains the probe
that joins them.

---

## 2. Must fix (4)

### 2.1 The split path is unreachable: the ranker discards the part uploads before the gate runs

**Location**
- `app/src/main/python/sleeve_spotify.py:333-341` - `_LONG_FORM_DECOR_RE` strips `full audiobook`,
  `unabridged`, `audiobook` and `book N`, but **not** `part N` / `PART I`.
- `app/src/main/python/sleeve_spotify.py:378-390` - `_name_score` uses a difflib ratio over the whole
  slug, not token F1; `:529-531` drops anything below the `strictness=1` name floor of 60.
- `app/src/main/python/sleeve_spotify.py:506-526` - any long-form candidate below 25 % of the book
  runtime is rejected before ranking ("cannot be the full book").
- `app/src/main/java/com/junkfood/seal/util/DownloadUtil.kt:414-436` - only the ranked top 10 reach
  `gateAudibleCandidates` (`:458`); `sleeve_audible_gate.py:128` accepts up to 20 parts.

**Problem.** The gate supports `2..20` parts, but the ranker that feeds it applies phase-1
single-upload precision rules first. A part title with `Part N` scores below the name floor; a part
that is under 25 % of the book is discarded as a partial. Both happen before `ranked` is passed to
`gate_audible`. The gate tests and the ranker tests each pass, but nothing tests the join.

**Evidence (shipped modules, this run, no network).** Ranking the exact research-corpus Dune set
(11 h 54 m + 9 h 08 m on "Starlit Pages") plus the 34 % "Book One" partial:

```
ranked: [("partial", 66.0, "Dune - Book One | Full Audiobook")]
rejected: []
gate on ranked:  hold  - no full-length candidate - best match ... is 34% of the book
gate on raw pool: split - complete 2-part set: 100% of the book
```

A five-part Dune set of 20 % each (e.g. a 60 h book split 5 x 12 h) is worse: the ranker returns
`ok=false`, all five parts in `rejected`, and `DownloadUtil` turns that into
`No confident YouTube match` (`DownloadUtil.kt:445-447`) - an `Error`, not even a hold/picker.
Name scores alone: `Dune Pt. 1` = 55.2, `DUNE - Book 1 | Full Audiobook | Epic Science Fiction |
PART I` = 36.0, against a floor of 60.

**Impact.** AC4 ("a complete part set ... downloads all parts in order") is not met for the corpus the
design names. Dune still holds, and for any set of five or more parts at least one part is under 25 % (pigeonhole) and is dropped, so the set is never complete; a book whose pool
contains only parts becomes a retryable `Error` instead of the phase-2 split card. The headline
phase-2 capability is effectively dead in production while all suites are green.

**Fix direction.** Do not let the single-upload ranker decide part membership. Either gate the raw
Audible search pool before the ranker floors (use the ranker for display order only), or add a
part-aware prefilter: strip `part N` / `part I` / `N of M` for long-form name scoring (reuse the
gate's `_PART_RE` vocabulary) and exempt part-marked candidates from the 25 % veto. Then make the
pool large enough to contain every part up to 20 (a part probe or `ytsearch` sized to N). Add a
rank -> gate integration test with `DUNE_PARTS` and a 5-part/12-part fixture; the existing
`test_the_dune_part_set_splits` only feeds the gate directly.

### 2.2 A "complete" set can splice two narrators or two editions

**Location**
- `app/src/main/python/sleeve_audible_gate.py:145` - `_NARRATED_RE` removes `narrated by ...` to the
  end of the title; `:276-277` applies it, then removes bracketed text.
- `app/src/main/python/sleeve_audible_gate.py:415-451` - `_part_entries` carries only
  `channel`, `core` title and `languages`; `:453-580` checks channel, declared language, junk, size,
  per-part length, indices and sum. There is no narrator or edition field to compare.
- `app/src/main/python/sleeve_audible_gate.py:590-591` - the grouping key is
  `(channel_key, core, languages)`.

**Problem.** The design's 9.4 failure table requires "Mixed editions/narrators" detection (same
channel + language; narrator check when the description/title has it). The implementation strips
exactly the words that carry it, so two uploads on one channel with complementary part numbers and a
matching sum are accepted as one book.

**Evidence (this run).**
```
Dune - Part 1 - Narrated by Scott Brick   42,840 s
Dune - Part 2 - Narrated by Simon Vance   32,868 s
-> split, "complete 2-part set: 100% of the book"
```
The bracket form is just as invisible: `Dune Part 1 (Abridged)` 26,000 s + `Part 2` 25,000 s +
`Part 3` 24,720 s -> `split` (the `(Abridged)` marker is removed by `_BRACKET_RE` before scoring).
A same-channel mismatch is rejected only when the channel string differs; two editions on one
channel are not.

**Impact.** A user downloads a "2-part book" that switches narrator or mixes abridged and unabridged
content at the seam. At 100 % sum and contiguous numbers nothing downstream catches it; the file
plays, so post-download duration verification passes too. This is the design's explicit multi-part
failure and the most likely false split in the current code.

**Fix direction.** Extract narrator/edition from the raw title before `_normalize_title` (the text
after `narrated by` / `read by`, bracketed `abridged`/`unabridged`/`dramatized`, and any edition
qualifier), carry it per part in `_part_entries`, and reject the set when two known values disagree.
Unknown vs known should not split the group (see 3.3). Add a two-narrator and an abridged-mix test.

### 2.3 A failed override of a held book clears the hold and the only picker

**Location**
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:527-529` - for a held pick,
  `match.withSelection(candidate).copy(held = false)` clears the hold **at queue time**, before any
  byte is fetched or verified.
- `app/src/main/java/com/junkfood/seal/download/Match.kt:120-129` - `cancelPending()` restores
  `selectedUrl`/`selectedScore`/`pending`/`acknowledged` but **not** `held`.
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:510, 706, 726` - `cancelPending()` is
  called on a fetch failure, a swap that produced no file, and a download failure.
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:299-312` - process-death restore calls
  `restoreAfterProcessDeath(completed = false)` -> `cancelPending()`.
- `app/src/main/java/com/junkfood/seal/download/MatchOps.kt:31-36` - `reviewable` requires
  `flagged || held` and not acknowledged; `DownloaderV2.kt:1219-1235` refuses a `Download` restart only
  while `match.held == true`.

**Problem.** For a gate-only hold whose ranker score was high (a language hold, an unknown-runtime
hold, or a 76 % partial that still scores above 75 with a wide gap), `flagged` is false. After a
failed pick or a process death during the pick, `held` is already false and `cancelPending` does not
put it back: the book becomes `reviewable == false`, disappears from "Review matches", and its
`Restart` no longer refuses - it downloads the stale `info` from the failed candidate again. The
review sheet is gone, so the user cannot choose a different upload.

**Evidence.** State transition by reading: `withSelection` keeps `held = true`; `swapOne` then clears
it; every failure path funnels through `cancelPending`, which copies five fields and omits `held`.
`MatchGateTest` covers `withSelection` alone but not `withSelection -> cancelPending`; no test covers a
held swap followed by a download failure. This is new in phase 2: `cancelPending` and the
held-at-queue-time copy were introduced/changed with the pending-swap work.

**Impact.** A held book can silently lose its refusal after one network failure or one app kill. The
task stays "Canceled"/"Error" with no review entry, and the retry begins the unverified pick again -
the exact state the gate was built to prevent.

**Fix direction.** Keep `held` true through `withSelection`; clear it only in `confirmSelection`
(which already does). If the queue-time write must clear it for the scheduler, store `previousHeld`
and restore it in `cancelPending`. Add a test: held -> withSelection -> cancelPending gives
`held == true` and `reviewable == true`; same through `restoreAfterProcessDeath(false)`.

### 2.4 The review sheet labels every 1-based part one number too high

**Location**
- `app/src/main/python/sleeve_audible_gate.py:565-582` - the split payload's `part_index` is the
  parsed part number (`DUNE_PARTS` returns `[1, 2]`; the unit tests assert that).
- `app/src/main/java/com/junkfood/seal/util/DownloadUtil.kt:254-269` - `AudiblePart.toMatchCandidate`
  copies `index = index` unchanged.
- `app/src/main/java/com/junkfood/seal/ui/page/downloadv2/MatchReviewSheet.kt:274` -
  `stringResource(R.string.match_part_label, part.index + 1)`.
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:400-404` - the queue title uses
  `position + 1`, which is correct; the sheet and the batch row therefore disagree.

**Problem.** `AudiblePart.index` is already the part number for 1-based sets (`AudiblePart`'s own
comment says so). Adding one again makes the two rows of the Dune set read "Part 2" (for `PART I`) and
"Part 3" (for `PART II`), under a header that says "Complete set - 2 parts" and a button "Download all
2 parts". For a zero-based set the same expression happens to be right, which is why the two bases
cannot share one display rule.

**Evidence.** Direct read of the gate output: `DUNE_PARTS` -> `part_index` `[1, 2]`;
`MatchCandidate.index` is that value; the sheet adds one. `AudiblePartsTest` never renders the label,
and a grep finds no `match_part_label` test.

**Impact.** Every normal split (1-based, the common case) shows parts 2..N+1 of N. A user checking
"is part 1 missing?" sees a set that appears to start at 2 and may not download; the error is on the
primary new UI and is deterministic, not an edge case.

**Fix direction.** Normalise once when the plan is built: store a 1-based `displayIndex` in
`MatchCandidate` (or pass the set base) and render it in the sheet, leaving the parsed `index` for
grouping/sorting. Add a Compose/unit test asserting the Dune set renders "Part 1"/"Part 2".

---

## 3. Worth considering (10)

1. **A split is presented as a hold, and no single upload can be chosen.** `MatchReviewSheet.kt:217`
   renders the full `match.held` block for a split (`gateReason` "complete 2-part set: 100% ..." in
   red, plus `match_held_hint` "No full-length version was proven ... Pick the right version"),
   `:318` hides every candidate row whenever `match.parts` is non-empty, and `PlaylistBatchCard.kt:246,274`
   shows the "Needs a pick" badge for both states. A split has a proven complete set and no picker;
   design 9.5 asks for a distinct "Split book" card and a "Choose a file" path. Impact: a false split
   (2.2) has no in-sheet override, and the copy tells the user to pick when there is nothing to pick.
   Suggest: branch on `parts.isNotEmpty()` for the badge/hint, keep the candidate rows visible under
   the plan, and make "Download all N parts" the primary CTA rather than the only one.
2. **A rejected replacement remains a normal history entry.** `DownloadUtil.kt:1086-1092,2042` inserts
   the new file into history inside `downloadVideo`; `DownloaderV2.kt:1097-1119` verifies only after
   that, and the failure branch keeps the old path but never deletes the new row/file
   (`abandonSwappedFile`, `:723-727`, only drops the pending marker). This is the still-open item 3.4
   of `AUDIBLE-PHASE1-FINAL.md`; per-part verification makes it more visible. Impact: after a bad swap
   the library holds both the good old file and the short replacement, the short one unlabelled.
   Suggest: delete `newPath`'s row (and quarantine or delete the file) when abandoning, or insert
   history only after verification passes.
3. **`Pt. 1` and bare `N of 2` are not part markers.** `_PART_RE`/`_PART_MARKER_RE`
   (`sleeve_audible_gate.py:78-124`) accept `part`, `(1/2)`, `book`, `vol`, `chapter`, but not
   `Pt.` or a leading `1 of 2`. Reproduced: `Dune Pt. 1` + `Dune Pt. 2` (42,840 + 32,868 s) -> `hold`,
   reason "no candidate with a matching title"; `Dune - 1 of 2` likewise. The `pt`/`1` tokens also
   lower the F1 below 0.90, so the parts never even enter `_part_entries`. Suggest: accept
   `pt\.?`/`pt` and bare `N of M` / `N/M` after a separator, and add the two cases to
   `PartMarkerTest`.
4. **One language tag on one part is a false "mixed languages" hold.** The grouping key includes the
   whole declared-language tuple (`:590-591`), and `_evaluate_part_set` rejects a mixed tuple (`:516-521`), and the family fallback reports "mixed languages" (`:641-655`). Reproduced: `Dune | Part 1` (no tag) + `Dune (English) | Part 2` ->
   `hold`, "mixed languages", although both are English. Suggest: treat an unknown declaration as a
   wildcard for grouping and only fail when two *known* codes conflict; a single known code still
   has to match the catalogue.
5. **The gate can support 20 parts, the pool can only carry 10.** `DownloadUtil.kt:414` sets
   `poolLimit = 10`; `sleeve_audible_gate.py:128` allows 20. A 12-part set of clean `Book N` titles
   is cut to 10 before the gate, which then sees a short sum (or a missing part) and holds. The
   Python 20-part test (`test_twenty_parts_still_split`) feeds the gate directly and does not prove
   the production path. Suggest: for Audible, probe/return enough candidates for the set (or a
   dedicated part search), and test 12/20 parts end to end.
6. **`enqueueParts` removes the held book before adding any part, with no rollback.**
   `DownloaderV2.kt:433-434`: `remove(task)` (which also clears the task's audit log and posts a
   batch notification) runs before `partTasks.forEach { enqueue(...) }`. The map writes are unlikely
   to throw, but a process death between the two lines leaves the batch with neither the book nor the
   parts; nothing restores the book. The function comment claims building the parts protects the
   batch, but only the build step, not the add step. Suggest: add all part tasks first, then remove
   the book (or restore it if the add loop fails). Also note `AudiblePartsTest.kt:22-26` says the
   queue side "is covered only by instrumentation"; there is no instrumentation test for
   `enqueueParts` (`ExampleInstrumentedTest` only checks the package name), so this path is entirely
   unverified.
7. **Part files are named by the upload title, not the plan number.** `DownloadUtil.kt:1993-1999`
   derives the filename from `videoInfo.title`; `enqueueParts` never sets `newTitle`, and the queue
   title "$bookTitle - Part N" is metadata only. For 10+ parts, lexicographic sorting puts
   `Part 10` before `Part 2`; channels with inconsistent naming can sort arbitrarily, and the
   map-backed queue does not guarantee the plan order either. Design 9.4 asks for `Part 01.m4a ...`
   with album/track tags. Suggest: set a zero-padded `newTitle` per part (or rename after download),
   and assert the order in the batch card.
8. **An Incomplete part still counts as completed in batch math and the notification.**
   `BatchOps.kt:55-69` has no `incomplete` bucket; a `Completed(warning)` part counts as completed,
   so a 2-part set with one short file reports 2/2 and "download finished". This is the phase-1 review's worth-considering 5, but phase 2 makes it a per-part outcome. Suggest: add `incomplete` to
   `BatchSummary` and use it in the header/notification.
9. **Resume is a dead action for held and split books.** A held book is `Canceled(Download)`
   (`MatchOps.kt:60-64`), so `PlaylistBatchCard.kt:183` shows "Resume"; `restartImpl`
   (`DownloaderV2.kt:1219-1235`) returns silently while `held` is true (and for a split `held` is
   also true). A split card therefore offers Resume that does nothing, plus the review sheet.
   Suggest: exclude `held` from `resumeTargets` and render "Pick a version"/"Download parts" instead.
10. **Title normalisation can erase a legitimate expected title, and `read by` is not stripped.**
    `_MARKETING_RE` removes genre words from the *catalogue* title too; `Saga`, `Epic`, `Thriller`
    normalise to `""` and `_title_f1` returns 0 for every candidate, so the book is held forever
    (reproduced). Separately, `_NARRATED_RE` handles `narrated by` but not `read by`, which adds
    tokens and drops a correct single/part below F1 0.90 (phase-1 review 3.1, still open). Suggest:
    fall back to the raw title when the normalized title has no tokens, and strip `read by ...`.

---

## 4. Only recorded (8)

- `AudibleGateResult.partCount` is decoded but never read (`GhostEngine.kt:321,571`); `expectedMs` is
  likewise carried but unused.
- `remove(task)` clears `TaskAuditLog` for the book (`DownloaderV2.kt:364`), so the gate decision that
  produced the split plan is gone as soon as parts are queued.
- `MatchResult.selected` still falls back to `candidates.first()` when `selectedUrl` is empty
  (`Match.kt:87-88`); unused for splits today, misleading for the next caller.
- A zero-based set's upload titles say `Part 0` while the sheet/queue say `Part 1`; only a display
  concern once 2.4 is fixed.
- `MatchCandidate.durationText` is `m:ss` (`Match.kt:36-41`), so an 11 h 54 m part renders `714:00`
  in the parts list; the phase-1 review already noted this for candidates.
- `AudioVerify.mismatchReason` says "downloaded file is X% of the book" (`AudioVerify.kt:115-117`),
  but for a part the denominator is the part runtime, not the book.
- The gate still fails open to rank #1 if the Python bridge throws (`DownloadUtil.kt:308-340`), as
  recorded in phase-1 review 3.4; a cached split plan is also queued without re-gating after a rule
  change.
- A part task has `match = null`, so a failed part verification cannot produce a review entry; the
  only signal is the Incomplete badge on its batch row.

---

## 5. Review scope and what was not covered

**Verified by reading and by re-running:** parser families and priority (`_parse_part`), grouping by
channel/core/language, contiguity including 0-based sets, duplicate id/number handling, min/max part
length, 20-part ceiling, sum tolerance, per-part language declarations, single-before-split ordering;
`AudibleGateResult.parts`/`AudiblePart` bridge decoding; `MatchResult.parts` and JSON back-compat;
`DownloadUtil.toMatchCandidate`/`decideGatePick`/pool limit; `DownloaderV2.enqueueParts` (remove,
title/index/duration mapping, batch, `directUrl`), `prepare()` direct-URL branch, part task
`fetchInfo`, per-part `verifyAudibleDuration` and the swap/verify ordering; `MatchReviewSheet` parts
rows, button, paste-link conditions, hold/pending state; `DownloadPageV2` wiring; `PlaylistBatchCard`
badge/review entry; `AudioVerify` tolerance; non-Audible metadata paths. Ran the three requested
commands myself (194 Kotlin tests, 131 Python tests, assemble green). Ran direct module probes for
the rank->gate corpus, five-part sets, narrator/edition mixes, `Pt.`/`N of M`, mixed language tags
and genre-word titles.

**Not covered, and why:** no device or emulator run, so `MediaMetadataRetriever` on real
m4a/webm/opus part files, notification rendering, the parts sheet on a short/landscape screen, and
real 20-part queues are unverified. No live Audible or YouTube request was made; all probes are
offline JSON against the shipped Python modules, so the live search pool depth and the ranker's
behaviour on a real Dune query are inferred from the fixtures, not observed. No Compose UI or
instrumentation test exists in the repo for the parts sheet or `enqueueParts`; every UI statement
here is from source. The 9-book research corpus was not replayed live (the rank->gate probe above is
the closest offline equivalent). No Room schema or migration was involved.






