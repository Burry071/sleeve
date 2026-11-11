# Audible phase 1 review - pre-download gate and post-download verify

Baseline: `HEAD ba79e156` plus the uncommitted beta.4 + phase-1 tree, reviewed 2026-11-11.
Read-only: no code was edited. Test evidence was re-run on this tree, not taken from the brief.

**Acceptance standard used.** `docs/research/AUDIBLE-RELIABILITY.md` section 0.1 (acceptance table),
2.3 (root causes), 9.1-9.5 (gate, thresholds, states, UI); phase-1a scope as declared in
`sleeve_audible_gate.py:1-12` (single full-length video only, no part sets).

**Test runs (mine):**
- `:app:testGenericDebugUnitTest -PnoSplits --rerun-tasks` -> BUILD SUCCESSFUL, 182 tests, 0 failures.
- `:app:assembleGenericDebug -PnoSplits` -> BUILD SUCCESSFUL.
- `python -m unittest discover -s scripts -p "test_*.py"` -> Ran 103 tests, OK.

---

## 1. Conclusion

**Not shippable as-is: three must-fix findings, all correctness.** The gate does stop the reported
62 %-class download before any media byte, the hold cannot be cleared by Accept all, and a held task
cannot be restarted into a download - those parts work and are tested. What blocks release is that
the gate has no language check at all (a complete foreign-language edition passes, reproduced
below), it only ever sees the ranker's three candidates while the hold sheet offers no way to reach
any other upload, and the swap path deletes the file it replaces before the new post-download check
runs, so a bad replacement destroys a previously good one.

Everything else is non-blocking and listed in section 3.

---

## 2. Must fix (3)

### 2.1 The gate has no language check, and title normalization deletes the markers that would carry it

**Location**
- `app/src/main/python/sleeve_audible_gate.py:192-250` - `gate_audible` compares title F1 and
  duration only; there is no language input, no language field and no language branch.
- `app/src/main/python/sleeve_audible_gate.py:81,123` - `_BRACKET_RE` removes `(...)`, `[...]` and
  `{...}` from the title before scoring, so `(German)`, `[Deutsch]`, `(Hörbuch)` disappear.
- `app/src/main/python/sleeve_audible.py:40-52` - `_product_dto` still returns only
  `title/artist/album/durationMs/authors`. Audible's `language` field is never read or carried.

**Problem.** The design makes language a hard gate ("language exact", section 9.2; root cause 6 in
2.3: "Duration + title alone can accept a foreign-language upload"). The implementation has no
language signal on either side, and the normalizer removes the one place the marker usually sits.

**Evidence (reproduced with the shipped module this run):**
```
gate_audible({"title":"Dune","durationMs":75720000},
             [{"title":"Dune (German) | Full Audiobook","duration":75720,...}])
-> {"verdict":"pass","reason":"full-length match: ... is 100% of the book"}
```
The same marker outside brackets is rejected: `Dune | Full Audiobook | Deutsch` -> `hold`. So the
failure is form-dependent and silent: an exact-runtime foreign edition in parentheses passes.

**Impact.** A 20 h wrong-language file is downloaded and marked `Completed`; the post-download check
cannot catch it because duration is correct. This is the one false pass the design explicitly built
a gate input for.

**Fix direction.** Read `language` in `_product_dto`, carry it on `SpotifyTrack` -> gate JSON, and
normalize ISO codes/English names on both sides. Scan the *raw* candidate title for language tokens
before bracket stripping (keep brackets stripped for F1), and hold on a mismatch. When the
catalogue language is unknown, record it and do not guess.

### 2.2 The gate only ever sees the ranker's three candidates, and the hold sheet has no other exit

**Location**
- `app/src/main/java/com/junkfood/seal/ghostengine/GhostEngine.kt:442` - `topN: Int = 3` default;
  `DownloadUtil.kt:395` calls it without an override.
- `app/src/main/java/com/junkfood/seal/util/DownloadUtil.kt:208,224` - `limit: Int = 3` and
  `.take(limit)`, so the gate input is the same three entries.
- `app/src/main/java/com/junkfood/seal/ui/page/downloadv2/MatchReviewSheet.kt:74-84,236-260` - the
  sheet renders `match.candidates` (those three) as radios only. No `Search again`, `Paste link`,
  `Open Audible` or `Download all parts`, although section 9.5 requires at least three exits.

**Problem.** Two independent caps (`top_n=3` in Python, `take(3)` in Kotlin) mean a correct
full-length upload ranked 4th-10th in a `ytsearch10` pool is never shown to the gate and never
cached, so the hold sheet cannot offer it. The only action on a held book is to pick one of the
three uploads the gate has already rejected.

**Impact.** For a held book whose only full upload sits below rank 3, the "Needs a pick" state is a
dead end: no candidate in the sheet is the book, and the one control that could fix it (paste the
right URL) does not exist in the sheet. This is a false hold that the user cannot resolve in place.

**Fix direction.** For Audible, rank the full 10-result pool (pass `topN = 10`) and gate all of it
while keeping the sheet's top-3 display plus a "show more" row; or add a targeted full-length probe
before deciding. Add at least `Paste link` and `Search again` to the held sheet.

### 2.3 A swap deletes the old file and history row before the replacement is duration-verified

**Location**
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:996` - `cleanupSwappedFile(id, newPath)`
  runs first; `:1001` - `verifyAudibleDuration(it)` runs after it.
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:608-640` - `cleanupSwappedFile`
  calls `DatabaseUtil.deleteInfoByPath(oldPath)`, deletes the file, then `confirmSelection()`.
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:890-925` - the verify result only
  sets `Completed.warning` afterwards.

**Problem.** Phase 1b added a duration check but ordered it after the irreversible half of the swap.
A replacement that lands at 34 % of the book is still promoted, the old row and file are deleted,
and only then does the app discover the replacement is wrong.

**Impact.** A user with a correct 20 h file who picks a wrong row in Review (the exact action the
sheet invites) loses the good file and its history row permanently, and is left with a short file
labelled `Incomplete`. The app knows the new file is wrong before the user ever sees it; the old
file is already gone.

**Fix direction.** Run `verifyAudibleDuration` before the destructive step: delete and confirm only
when the file passes (or when the expected runtime is unknown). On a mismatch, keep both files, keep
the old selection, and surface the verify reason; let the user delete explicitly.

---

## 3. Worth considering (7)

1. **Title-form false holds on correct full uploads.** `sleeve_audible_gate.py:136-150` scores only
   the core title, while the ranker falls back to `artist + title` (`sleeve_spotify.py:_name_score`).
   Reproduced: `Dune by Frank Herbert | Full Audiobook` at 100.0 % runtime is `hold`, while the
   ranker gives it 89.0; `Dune: Book One of the Dune Chronicles | Full Audiobook` and
   `Dune - Read by Scott Brick - Unabridged` also hold (`read by` is not stripped like
   `narrated by`). Effect: the book is held even though the right upload is rank 2. Fix: pass when
   either the core-title F1 or the (title + author) F1 is >= 0.90, and strip `read by ...`.
2. **The held card's primary button is a silent no-op.** `MatchOps.resolvedState` maps a hold to
   `Canceled(Download)` (`MatchOps.kt:60-62`), `restartImpl` returns without feedback for a held
   pick (`DownloaderV2.kt:1102-1112`), and the row still renders the Download button
   (`VideoCardV2.kt:715`, `PlaylistBatchCard.kt:315`) plus the label "Canceled". The batch `Resume`
   button is shown for the same state (`PlaylistBatchCard.kt:183`) and does nothing for the held
   rows. Fix: pass `held` to the row/action button, render "Pick a version" that opens the sheet,
   and exclude held tasks from `resumeTargets`.
3. **No confirmation for a partial override.** `MatchReviewSheet.kt:330` applies any chosen row
   directly. The design (9.5) requires a confirmation that names the mismatch for "Download anyway".
   Picking a 34 % row and tapping Apply is currently an unconfirmed override.
4. **Gate failure fails open to rank #1.** `DownloadUtil.kt:285-300,423-428`: if the Python bridge
   or module fails, `audibleGate` returns null and the task downloads `best` with `held=false`. Any
   bridge regression silently restores the pre-phase behaviour for every Audible book. Holding with
   "gate unavailable" (or one retry, then hold) matches the precision-first contract.
5. **`Completed.warning` is invisible to batch math and notifications.** `BatchOps.kt:69` counts a
   warning-Completed as completed; `PlaylistBatchCard.kt:128-150` prints `completed/total` and only
   adds a failed count; `DownloaderV2.kt:1007-1013` still says "download finished" for a file marked
   Incomplete. A 20-book batch with 3 short files reports 20/20. Fix: add `incomplete` to
   `BatchSummary` and use it in the header and notification text.
6. **`no_source` is unreachable in the real flow.** `DownloadUtil.kt:405-409` throws
   "No confident YouTube match" before the gate runs, so a book with no usable candidate is an
   `Error` (retry re-runs the same search), not the picker the gate models
   (`sleeve_audible_gate.py:198`). Fix: build a held `MatchResult` with a "no candidates" reason.
7. **Long durations render as minutes.** `Match.kt:36-41` formats `%d:%02d`, so a 21 h candidate
   shows `1262:00` and `durationDelta` shows `-828:12` in the held sheet. Fix: `h:mm:ss` above one
   hour.

---

## 4. Only recorded (5)

- `MatchResult.selected` falls back to `candidates.first()` when `selectedUrl` is empty
  (`Match.kt:79-80`); correct today because nothing reads it for a hold, misleading for the next caller.
- `AudibleGateResult.expectedMs` is populated but never read (`GhostEngine.kt:290`).
- `swapOne` clears `held` at queue time (`DownloaderV2.kt:439`), while `MatchGateTest` documents
  "withSelection alone does not lift the hold"; consistent only because one caller does the override.
- The post-download reason overwrites `gateReason` (`DownloaderV2.kt:915`); the original refusal
  survives only in `TaskAuditLog`.
- A held-only batch dismisses the sheet as soon as its single hold is lifted
  (`DownloadPageV2.kt:805-810`), so the "Re-downloading" status line is never seen for that case.

---

## 5. Review scope and what was not covered

**Verified by reading and by re-running:** gate placement before `fetchVideoInfoFromUrl`;
`resolvedState` never writes `ReadyWithInfo` for a hold; `selectedUrl=""` leaves the sheet's Apply
disabled until a row is tapped and Accept all disabled for held rows; restart refusal; JSON
back-compat for `held/expectedDurationMs/gateReason/actualDurationMs/warning`; non-Audible tasks
skip the gate and keep the old path; swap override, pending/confirm/cancel and cleanup ordering;
post-download verify wiring and warning-driven UI; strings; both test suites and assembly.

**Not covered, and why:** no device or emulator run, so `MediaMetadataRetriever` behaviour on real
m4a/webm/opus output, notification rendering, sheet layout on a short screen, and actual yt-dlp pool
depth for a live Audible book are unverified. No live Audible or YouTube request was made (offline
review). There are no Compose UI or instrumentation tests in the repo, so every UI statement above
is from source, not from a rendered screen. The 9-book labelled corpus in the research doc was not
replayed; the gate's own unit cases and my direct Python probes are the evidence used.
