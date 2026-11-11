# Audible phase 1 - final verification of the three must-fixes

Baseline: `HEAD ba79e156` plus the uncommitted beta.4 + phase-1 tree, verified 2026-11-11.
No product code was edited; this file is the only thing written by this review.

Acceptance standard: the three must-fix findings in `docs/reviews/AUDIBLE-PHASE1-REVIEW.md` section 2,
read against `docs/research/AUDIBLE-RELIABILITY.md` 0.1 and 9.2 (single-video gate: title F1 >= 0.90,
duration `max(120 s, 2 %)`, language exact) and 9.5 (a held book must stay actionable).

**Test runs (this tree, this session):**

- `:app:testGenericDebugUnitTest -PnoSplits --rerun-tasks` -> BUILD SUCCESSFUL in 43 s;
  **182 tests / 30 suites, 0 failures, 0 errors, 0 skipped** (counted from
  `app/build/test-results/testGenericDebugUnitTest/*.xml`). `:app:compileGenericDebugKotlin`
  executed in the same run.
- `:app:assembleGenericDebug -PnoSplits` -> BUILD SUCCESSFUL;
  `app/build/outputs/apk/generic/debug/Sleeve-2.1.0-beta.3-generic-debug.apk` written
  2026-11-11 18:50, 90,816,997 bytes. `assets/chaquopy/app.imy` inside the APK contains both
  `sleeve_audible.py` and `sleeve_audible_gate.py`.
- `python -m unittest discover -s scripts -p "test_*.py"` -> **Ran 106 tests, OK**
  (gate module alone: 22 tests, OK).

---

## 1. Conclusion

**All three must-fixes are fixed: 0 blocking findings remain.** The language check now exists on both
sides of the bridge and is exercised by a direct probe and by three new gate tests; the Audible path
ranks and gates up to 10 candidates while the sheet still shows 3 and the held sheet can take a pasted
link; and a swap is now duration-verified before the old file or history row is touched. The only new
behavioural defect found is a false-hold class introduced by the title-marker scan (section 3.1); it
refuses some same-language books, so it costs coverage rather than correctness and is not a blocker for
this phase.

| # | Must-fix | Verdict |
|---|---|---|
| 2.1 | Gate had no language check; normalizer deleted the markers | **PASS** |
| 2.2 | Gate only saw 3 candidates; held sheet had no other exit | **PASS** |
| 2.3 | Swap deleted the old file/row before the replacement was verified | **PASS** |

### 1.1 Must-fix 1 - language check: PASS

Evidence chain, end to end:

- `app/src/main/python/sleeve_audible.py:51` - `_product_dto` now returns
  `"language": product.get("language") or ""`; both `resolve()` and `search()` return that DTO.
  Host probe: `_product_dto({..., "language": "english"})` -> `{'language': 'english', ...}`.
- `app/src/main/java/com/junkfood/seal/ghostengine/GhostEngine.kt:266` - `SpotifyTrack.language`
  (defaulted), parsed from the resolver reply at `GhostEngine.kt:554`; `Task.kt:108` -
  `MetadataTrack.language` (defaulted, so cached beta.4 JSON still decodes).
- `DownloadDialogViewModel.kt:467` copies `track.language` into every `MetadataTrack`;
  `DownloadUtil.kt:362` copies `metadata.language` into the `SpotifyTrack` handed to the gate.
  `GhostEngine.kt:499-511` serialises that object with `gateJson`, which has `encodeDefaults = true`
  (`GhostEngine.kt:37-40`), so an unknown language arrives as `""` instead of disappearing.
- `app/src/main/python/sleeve_audible_gate.py:124-150` - `_normalize_language` maps ISO codes and
  English/native names from the catalogue, and `_language_conflict` scans the **raw** candidate title.
  `expected_language` is read at `:275`, per-candidate `language_conflict` is computed at `:294-296`,
  enforced in the pass filter at `:327`, and explained in the hold reason at `:348-352`.
  `_normalize_title` still strips brackets for F1 only (`:174-188`, bracket strip at `:183`), so `(German)` is invisible to
  title scoring but visible to the language check - the exact hole the earlier review reproduced.

Direct probe (host Python, the shipped module, this tree):

```
track Dune / 75_720_000 ms, language "English"
  "Dune (German) | Full Audiobook", 75_720 s
  -> hold, reason "wrong-language upload (\"Dune (German) | Full Audiobook\")"

track Dune, language "German", same candidate
  -> pass, ratio 1.0, candidate_id set

track Dune, no language field, same candidate
  -> pass (unchanged pre-fix behaviour: no catalogue language, no verdict - it does not guess)

track Dune, language "en", same candidate
  -> hold (ISO code normalisation works)
```

Tests: `scripts/test_audible_gate.py:133-179` defines
`test_a_foreign_language_edition_in_parentheses_holds`,
`test_the_same_language_marker_still_passes`, and
`test_unknown_catalogue_language_does_not_guess`; all pass in the 106-test run. The Python suite grew
from 103 to 106 for exactly these three cases. No Kotlin test exercises the field chain (section 3.3).

### 1.2 Must-fix 2 - top-10 pool, 3-row display, paste link: PASS

- `DownloadUtil.kt:382` - `poolLimit = if (provider == "audible") 10 else 3`; it is passed to the
  ranker as `topN` (`:393`) and to `matchBestCandidates(limit = poolLimit)` (`:404`). The Python
  ranker honours `top_n` (`sleeve_spotify.py:473-496`).
- The gate receives that same list, not the 3-row display: `DownloadUtil.kt:426`
  `audibleGate(taskKey, track, ranked)`, and `gate_audible` iterates every candidate.
- Display shortlist: `DownloadUtil.kt:435-438`
  `(listOf(pick) + ranked).distinctBy { ... }.take(3)`. A passing pick ranked 4th-10th is inserted
  first, so it is always one of the 3 rows; `matchCandidates` (`:439`) is what
  `MatchReviewSheet.kt:254` renders.
- Paste link: `MatchReviewSheet.kt:92-128` adds `pastedUrl` and `usePastedLink`; `singleHeld` is
  `flagged.singleOrNull()` with `match.held == true` (`:95`); the field is rendered at `:351-379`;
  `usePastedLink` calls the existing `onSwap` with a synthetic
  `MatchCandidate(id = url, url = url, title = url)` (`:109-117`). That `onSwap` is
  `downloader.swapMatches` (`DownloadPageV2.kt:310,816`).
- Compilation: `:app:compileGenericDebugKotlin` executed and `:app:assembleGenericDebug` succeeded;
  the new strings resolve (`strings.xml:476-477`).

Caveat, not a defect of this fix: `matchBestCandidates` runs the plain-YouTube `ytsearch10` leg only
when the YouTube Music top score is below 60 (`DownloadUtil.kt:191-195`). "topN = 10" therefore means
"gate up to 10 of whatever pool was merged", not "always search 10 plain-YouTube results". For Audible
the music pool is normally empty or floor-rejected, so widening runs; this was not exercised live.

### 1.3 Must-fix 3 - verify before delete: PASS

- `DownloaderV2.kt:1000` - `val verify = newPath?.let { verifyAudibleDuration(it) }` runs **before**
  `DownloaderV2.kt:1015` - `cleanupSwappedFile(id, newPath)`.
- Mismatch branch `:1001-1013`: `abandonSwappedFile(id)` at `:1004`, `Completed(newPath, warning =
  verify.reason)` at `:1013`, and `match.actualDurationMs = verify.actualMs` /
  `match.gateReason = verify.reason` at `:1005-1012`. Nothing on this path deletes a file or a row.
- `abandonSwappedFile` (`:634-638`) drops the parked old path and restores the previous selection with
  `cancelPending()`; `cleanupSwappedFile` (`:608-628`) is the only caller of
  `DatabaseUtil.deleteInfoByPath` (`:623`), and it is reached only from the pass branch (`:1015`).
- Pass still cleans up: `cleanupSwappedFile` deletes the old row, then the old file, then
  `confirmSelection()`. A swap that produced no file cancels the pending pick and keeps both.
- `Completed.warning` is declared at `Task.kt:170-177`; UI consumes it at `VideoCardV2.kt:485,563`,
  `PlaylistBatchCard.kt:284` and `MatchReviewSheet.kt:185`.

---

## 2. Must fix (0)

None. The three findings above are closed. Two candidates I considered and deliberately did not
promote: the false-hold class in 3.1 (a refusal, not a wrong download, and the sheet makes it
recoverable) and the missing Kotlin tests in 3.3 (a coverage gap, not a behavioural defect).

---

## 3. Worth considering (4)

### 3.1 The language scan over-matches English titles that contain a nationality word

**Location:** `app/src/main/python/sleeve_audible_gate.py:106-150` (`_LANGUAGE_MARKERS`,
`_language_conflict`).

**Problem:** any occurrence of a marker token anywhere in the raw title is treated as a declaration of
the audio language. Reproduced this run with the shipped module and an English catalogue:

```
track "The French Lieutenant's Woman", language "english",
  "The French Lieutenant's Woman | Full Audiobook" at 100 % runtime
  -> hold, reason "wrong-language upload"
track "The Dutch House", same shape -> hold
track "The German House", same shape -> hold
```

The correct, full-length, same-language upload is refused because the *book title* contains a
nationality adjective. `The Dutch House`, `The German House` and `The French Lieutenant's Woman` are
real English audiobooks.

**Impact:** a false hold for a class of English titles; the user must override in Review (the row is
shown) or paste the link. No wrong download, and the design explicitly prefers refusals to false
passes, so this is not a blocker - but the reason shown is factually wrong and the correct upload is
not auto-selected.

**Suggestion:** only treat a marker as a language declaration when it is in a qualifier position
(bracketed, or followed by `edition` / `version` / `audiobook` / `hörbuch`, or prefixed by `in` /
`auf`), or prefer the candidate's extracted `language` when available; a bare token in a title that
otherwise matches at F1 = 1.0 is weak evidence.

### 3.2 Only one of the design's three held-book exits exists

**Location:** `MatchReviewSheet.kt:92-95,351-379`; design `AUDIBLE-RELIABILITY.md` 9.5.

**Problem:** the sheet now offers candidate rows and `Paste link` for a single held book, but there is
still no `Search again` and no `Open Audible`. The earlier fix direction asked for at least two new
exits; only one was added. `Paste link` is also hidden when more than one entry is under review
(`singleHeld` requires `flagged.singleOrNull()`), by design.

**Impact:** a held book whose top-10 pool has no usable upload, or whose pasted link fails, still has
only the three rejected rows. Less severe than before (the top-10 gate removes the original dead end),
but 9.5's ">= 3 actions" is unmet.

**Suggestion:** add `Search again` (re-run `resolveMetadataTrack` for that task) and an `Open Audible`
intent; or record phase-1 scope as "two exits" in the research doc so the gap is explicit.

### 3.3 No automated test pins the plumbing that makes fixes 1 and 2 work

**Location:** `app/src/test/java/...` (Kotlin suite); `scripts/test_audible_gate.py` covers only the
gate function.

**Problem:** the Kotlin suite is unchanged at 182 tests, and a grep of the test tree finds no reference
to `language`, `topN`, `poolLimit`, `displayed` or the paste-link path. Deleting
`language = track.language` (`DownloadDialogViewModel.kt:467`) or `language = metadata.language`
(`DownloadUtil.kt:362`) would leave every suite green while silently reverting must-fix 1 to
"unknown language, no check" - the gate fails open by design when the catalogue language is unknown.
The same applies to `poolLimit` and to the `pick + ranked take 3` rule.

**Suggestion:** add a serialization test asserting `SpotifyTrack(language = "German")` encodes a
`language` field, a pure-function test for the 3-row shortlist with a rank-7 passing pick, and a
`matchBestCandidates(limit = 10)` case. The Python gate tests already cover the verdict logic.

### 3.4 A failed verification still makes the short replacement the task's completed file

**Location:** `DownloaderV2.kt:1004-1013`.

**Problem:** on a mismatch the old file and row survive (correct), but `downloadState` becomes
`Completed(newPath, warning = ...)` while `abandonSwappedFile` restores `match.selectedUrl` to the old
pick. The card then shows the old candidate as the match but opens the new, short file, and history
holds both files as separate entries.

**Impact:** no data loss, but a confusing card after the exact action the sheet invites, and a
duplicate-looking library entry. The user has to work out which row is the good file.

**Suggestion:** keep `Completed(oldPath, warning = ...)` when an old path exists (old file stays the
task's file), leaving the rejected replacement as an unowned file, or add an explicit
`Rejected` artifact entry that names the file it kept.

---

## 4. Only recorded (5)

- `sleeve_audible.py:11-13` still documents the pre-fix surface (`title`, `artist`, `authors`,
  `album`, `durationMs`) and omits `language`.
- `Task.MetadataTrack` equality now includes `language`, and `Task.isSameWork` compares
  `type == other.type` (`Task.kt:80-85`); a task restored from a beta.4 cache (language `""`) and a
  freshly resolved duplicate (`"english"`) compare unequal, so duplicate detection can miss after an
  upgrade.
- AC10 of the research doc asks the audit log to record candidate language; the log records the gate
  verdict and reason (`DownloadUtil.kt:298-311`) but not per-candidate language.
- The gate never uses yt-dlp's extracted `language` (design 9.1 step 3 prefers it over title markers);
  a foreign edition whose title keeps the English title and carries no marker can still pass when the
  runtime is exact. Extra title tokens usually drop F1 below 0.90, so this was not reproduced.
- `held` is cleared when a swap is queued (`DownloaderV2.kt:433-440`); a swap that then fails restores
  the rejected pick with `held = false`. No download path follows today (the state is `Completed`),
  but the hold contract is not restored.

---

## 5. Review scope and what was not covered

**Checked by reading, probing and re-running:** the full language chain (resolver -> `SpotifyTrack` ->
`MetadataTrack` -> `DownloadUtil` -> `gateJson` -> `sleeve_audible_gate`), including ISO/native-name
normalisation and the raw-title scan; the top-10 request and gate call, the 3-row shortlist, the
held-sheet paste flow and its `onSwap` wiring; the verify/cleanup ordering, the abandon path, the pass
path and the warning/`actualDurationMs`/`gateReason` writes; the Chaquopy packaging of the new module;
both test suites and the debug assemble.

**Not covered, and why:** no device or emulator run, so `MediaMetadataRetriever` behaviour on real
m4a/webm/opus output, Compose layout of the new paste row, and the Chaquopy bridge at runtime are
unverified - only compilation, host-Python probes and APK contents were checked. No live Audible or
YouTube request was made, so the real pool depth, Audible's actual `language` string and whether
`ytsearch10` widening triggers for a given book are unverified. There are no Compose UI or
instrumentation tests in the repo, so every UI statement is from source. The 106-test Python and
182-test Kotlin runs are the only dynamic evidence.