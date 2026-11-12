# Audible phase 2 - final verification of the four must-fixes

Baseline: `HEAD ba79e156` plus the uncommitted beta.4 + phase-1 + phase-2 tree, reviewed 2026-11-11.
Read-only: no code was edited. Every number below was produced on this tree.

**Acceptance standard used.** The four must-fix findings in
`docs/reviews/AUDIBLE-PHASE2-REVIEW.md` section 2 (2.1 ranker -> gate, 2.2 narrator/edition,
2.3 held override, 2.4 part labels), read against `docs/research/AUDIBLE-RELIABILITY.md` 9.4
(multi-part failure table), 9.5 (split card / batch review) and 11.5 AC4/AC5/AC6. A fix passes only
when the code path it names is closed and a test or an offline probe reproduces the intended
behaviour on this tree; a green suite alone is not evidence for the ranker -> gate join.

**Test runs (mine):**
- `:app:testGenericDebugUnitTest -PnoSplits --rerun-tasks` -> BUILD SUCCESSFUL; **196 tests in 32
  result files, 0 failures, 0 errors, 0 skipped** (was 194 in the phase-2 review; +2 in
  `MatchGateTest`).
- `:app:assembleGenericDebug -PnoSplits` -> BUILD SUCCESSFUL; APK 90,818,317 bytes.
- `python -m unittest discover -s scripts -p "test_*.py"` -> **Ran 135 tests, OK** (was 131; +2 in
  `test_match_rank.py`, +2 in `test_audible_gate.py`).

## 1. Conclusion

**All four must-fix findings are closed as written; the safety failures they describe are gone.**
The Dune corpus now ranks and splits, five- and twelve-part `Part N` sets split end to end, a
same-channel set with two known narrators or two known editions holds, `held` survives
`withSelection` / `cancelPending` / a non-completed process-death restore, and the sheet renders
1-based labels for both numbering bases. One **new** ranker regression introduced by fix 2.1 must be
fixed before ship: the `N/M` part strip eats slash-numbered titles, so `11/22/63 | Full Audiobook`
loses its name score and the task fails with `No confident YouTube match` (an `Error`, no picker).
Two narrower coverage gaps remain and are not safety issues: `Pt.` / bare `N of M` sets still cannot
split because the gate cannot parse those markers, and `read by` narration is extracted but never
reaches a part entry (plus one new false "mixed narrators" hold when the narrator parenthetical
precedes the part marker).

| # | Finding | Verdict | Decisive evidence |
|---|---|---|---|
| 2.1 | Ranker -> gate | **PASS** (with residual) | `sleeve_spotify.py:333-353,390,531`; `DownloadUtil.kt:416-417,461`; corpus probe -> `split` 2 parts; 5/12-part probes -> `split`; both new tests pass. Residual: gate still holds `Pt.` / bare `N of M` (3.1). |
| 2.2 | Narrator / edition | **PASS** (with residual) | `sleeve_audible_gate.py:147-152,208-221,459-460,544-556`; two new tests pass; probe: dramatized/unabridged -> `mixed editions`, known vs unknown -> `split`. Residual: `read by` / `)` capture (3.2, 3.3). |
| 2.3 | Held override | **PASS** | `Match.kt:97-137`; `DownloaderV2.kt:520-528,304,1149`; `MatchGateTest` 14 tests, including `a failed held override restores the hold and the picker`. |
| 2.4 | Part labels | **PASS** | `MatchOps.kt:65-68`; `MatchReviewSheet.kt:276-277`; `part labels are one-based for both numbering bases` passes. |

## 2. Must fix (1)

### 2.1 [NEW, introduced by fix 2.1] The `N/M` part strip destroys slash-numbered titles

**Location**
- `app/src/main/python/sleeve_spotify.py:351` - the new alternative
  `r"|\b\d{1,2}\s*(?:of|/)\s*\d{1,2}\b"`, applied by `_strip_long_form_decor` at `:390`.

**Problem.** The slash alternative is not anchored to a standalone part marker. In the title
`11/22/63 | Full Audiobook` it matches `11/22`, leaving `/63`; `_slug` turns that into `63` and the
name score falls to 50.0, below the long-form floor of 60 (`:547-548`). The candidate is dropped
before ranking, so `rank_candidates` returns `ok=false`, `error="no candidate matched the book's
duration/length"`, and `rejected=[]`. `DownloadUtil.kt:446-449` then throws
`No confident YouTube match`, which becomes an `Error` with no review picker. The same over-match
makes `_is_part_upload("11/22/63")` true (`:356-357`), disabling the 25 % veto for that book.

**Evidence (this run, no network).**
```
'11/22/63 | Full Audiobook' -> stripped '/63' | name 50.0
'11/22/63' vs '11/22/63 | Full Audiobook':
  ok=False, error=no candidate matched the book's duration/length, rejected=[]
'11/22/63 by Stephen King | Full Audiobook' -> stripped '/63 by Stephen King' | name 81.1 (passes only via the artist fallback)
```
The decor-only strip that existed before this fix leaves `11/22/63` intact and scores 100.0; the
regression is the part regex, not the pre-existing marketing strip.

**Impact.** A correct, full-length upload for a real audiobook (*11/22/63*, Stephen King) cannot be
matched at all; retrying produces the same hard Error. The failure is silent to the review UI
because the candidate never reaches `ranked`.

**Fix direction.** Make the numeric-slash alternative a standalone token, e.g.
`(?<![\d/])\d{1,2}/\d{1,2}(?![\d/])`, or accept `N/M` only inside brackets / after a separator, so
`Dune - 1/2` still matches but `11/22/63` does not. Add a `11/22/63 | Full Audiobook` regression
case next to `test_the_corpus_part_is_accepted_once_decorated`.

## 3. Worth considering (5)

1. **`Pt.` and bare `N of M` still cannot split: the gate vocabulary was not extended.**
   `sleeve_spotify.py:347-353` strips `Pt.` and `N of M`, but `sleeve_audible_gate.py:78-87`
   (`_PART_RE`) and `:100-111` (`_PART_MARKER_RE`) only accept `part N`, `(N/M)`, `book N`, `vol N`
   and `chapter N`. Probes: a five-part `Dune Pt. n` set and a five-part `Dune - n of 5` set both
   rank `ok=true` with name 100, then the gate returns `hold`, `no candidate with a matching title`.
   Impact: complete sets in those two forms never split (they hold with a picker); the ranker half
   of the claim is effective but the hand-off is not. Fix: add `pt\.?` and a standalone `N of M` /
   `N/M` to both gate regexes and extend `PartMarkerTest`.
2. **`read by` is extracted but never carried: `_NARRATED_RE` only strips `narrated by`.**
   `sleeve_audible_gate.py:147` does not remove `read by ...`, so
   `Dune - Part 1 - Read by Scott Brick` normalises to `dune read by scott brick`; F1 is 0.333,
   below the 0.90 part-entry filter at `:444`, and both parts are excluded. Probes: a same-narrator
   `read by` pair -> `hold`, `no candidate with a matching title`; a mixed `read by` pair -> the
   same hold, never reaching the narrator check at `:544-549`. Impact: `read by` sets cannot use the
   split path. Fix: `r"\b(?:narrated|read)\s+by\b.*$"`.
3. **The narrator capture runs past `)` and can invent a mixed-narrator hold.**
   `sleeve_audible_gate.py:148-151` stops at `(` / `[` / separators / end, but not at `)` or `]`.
   Probe: `Dune (Read by Scott Brick) Part 1` + `Dune (Read by Scott Brick) Part 2` -> `hold`,
   `mixed narrators: scott brick) part 1, scott brick) part 2`, although both name one narrator.
   The cores match (`dune`), so this is the new check producing a false positive; before the check
   the set would have split. Fix: add `[)\]]` to the delimiter class.
4. **The widening threshold can bypass `ytsearch30` for Audible.** `DownloadUtil.kt:191` widens only
   when the YouTube Music top score is `< 60`. If `ytmsearch5` returns any long-form upload at 60+
   (the corpus part in the new test scores 66.0), `matchBestCandidates` (`:213-224`) keeps only the
   <=5 music candidates and never searches the 30-row pool, so the second part is not seen. This is
   a code-path inference; the live composition of `ytmsearch5` for audiobooks was not observed
   (no network). Fix: always take the wide pool for Audible, or raise the Audible threshold above
   any possible score.
5. **The `resolvedState` KDoc is attached to the wrong function.** `MatchOps.kt:53-59` documents
   `resolvedState`, but `:60-64` (the `partDisplayNumber` doc) follows it and `:70` is the
   undocumented `resolvedState`. Impact: the next reader looking for the held -> `Canceled` rule
   finds it above `partDisplayNumber`. Fix: move the first block down to `:70`.

## 4. Only recorded (6)

- `AudiblePart.index` / `partDisplayNumber` fall back to `0` when `match.parts` is empty
  (`MatchOps.kt:66`); no row renders then, so the fallback is harmless.
- A 20-part set plus 10 duplicates can exceed the 30-candidate pool; the gate ceiling is 20
  (`sleeve_audible_gate.py:128`), but this was not exercised live.
- `_narrator_key` keeps trailing punctuation (`scott brick)`); equality still works when both parts
  use the same wording, as in the bracketed-after-part form.
- A full-length `Dune - Book One` upload on the same channel as the parts is now parsed as part 1
  and can collide with `PART I` (`duplicate part 1`). The real 34 % partial is on `HK Book World`
  (`docs/research/AUDIBLE-RELIABILITY.md:402-403`), which the shipped integration test mirrors.
- The shipped five-part test (`scripts/test_match_rank.py:269-284`) asserts the ranker keeps all five and never calls `gate_audible`; despite the class name, the five-part split assertion is only my probe.
- `MatchCandidate.durationText` is still `m:ss`, so an 11 h 54 m part renders `714:00`; unchanged
  from the phase-1 note.

## 5. Review scope and what was not covered

**Verified by reading and by re-running:** the long-form decor/part strip and its `long_form` gate
(`sleeve_spotify.py:333-353,387-390`), the 25 % veto exemption (`:528-531`), the ranker's
`title 100` scores for `Part N`/`Pt.`/`N of M`; `DownloadUtil`'s `poolLimit = 30` / `ytsearch30` and
the fact that the gate receives `ranked`, not the 3-row display list (`DownloadUtil.kt:416-417,434,
461`); `Match.withSelection` / `confirmSelection` / `cancelPending` / `restoreAfterProcessDeath`
(`Match.kt:97-137`), `swapOne` (`DownloaderV2.kt:520-528`), the process-death call (`:304`), the
download-failure `abandonSwappedFile` path (`:1149`), and `restartImpl`'s held refusal (`:1220`);
`MatchOps.partDisplayNumber` and its single render site (`MatchReviewSheet.kt:276-277`); the gate's
narrator/edition regexes, per-part fields and conflict checks
(`sleeve_audible_gate.py:147-152,208-221,459-460,544-556`).

**Probes run offline against the shipped modules (no network):** the exact research-corpus Dune set
on `Starlit Pages` + the 34 % `HK Book World` partial -> `split`, `[1, 2]`; five-part `Part N` ->
`split`; twelve-part `Part N` -> `split`; five-part `Pt.` and `n of 5` -> `hold`; mixed
`narrated by` -> `hold`; known vs unknown narrator -> `split`; `dramatized` vs `unabridged` ->
`hold`; `(Read by X) Part 1/2` -> false `mixed narrators`; `11/22/63 | Full Audiobook` -> rank
`ok=false`.

**Not covered, and why:** no live Audible or YouTube request, so the real `ytmsearch5` /
`ytsearch30` pools were not observed and 3.4 remains an inference; no device or emulator run, so
Compose rendering of the parts rows, `MediaMetadataRetriever` on real part files and notifications
are unverified; no instrumentation test exists for `enqueueParts`. Room schema and migrations were
not touched by these four fixes.
