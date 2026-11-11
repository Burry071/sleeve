# Audible reliability: never spend bytes on a guess

**Status:** research + design. The gate, the split path and the byline fix are implemented; §2.3 records the shipped behaviour measured on device. Release pending real-phone QA and the beta.4 tag.
**Date:** 2026-11-11
**Repo:** Sleeve (`5ba15adc`, beta.4 hardening merged; this record was written on top of it)
**Scope:** the Audible metadata → YouTube audio path (`sleeve_audible.py`, `sleeve_spotify.py`, `DownloadUtil.resolveMetadataTrack`, `MatchOps`, `MatchReviewSheet`, `DownloaderV2`). Music matching is out of scope except where it shares code.

---

## 0. Executive answer

**Recommendation.** Make the audiobook path *precision-first*: do not start a download until a candidate passes a deterministic identity gate; otherwise hold the task in a pre-download picker. A 0–100 fuzzy ranker score is not a probability and must never be the sole reason to spend 10–20 hours of bandwidth. The gate is duration **and** title **and** language **and** a channel-junk veto, with a separate path for complete multi-part sets. Post-download duration verification is a safety net, not the primary defense.

**In one sentence:** “Fully reliable” here means *zero silent wrong downloads*, not *zero misses*. When YouTube has no verifiable complete copy, the app must say so before the download, not after.

**What this buys:** the reported 62 % class is reproducible today. In a 9-book spot check, the pre-gate ranker auto-selected a 3.73 h upload of the 11.1 h *The Hobbit* at score **62.3 (33.6 % of the book)** and a 7.23 h upload of the 21.0 h *Dune* at score **66.0 (34.4 %)**, then flagged them only after the bytes were spent. Those are the exact failures the gate eliminates. The shipped gate is measured separately in §2.3: 0 wrong downloads across 11 runs.

**What it costs:** recall. In the same 9-book sample, 3 books had no single full upload at all. A strict gate will correctly refuse them; some can be recovered as split books (Dune: 11.90 h + 9.13 h = 21.03 h), some cannot (Project Hail Mary, The Martian in the sampled pool). The UI must make “no verifiable source” a first-class outcome, not an error.

### 0.1 The brief, answered

| Question | Answer |
|---|---|
| What does “fully reliable” mean? | Zero silent wrong auto-downloads (precision first), every refusal actionable, every decision auditable. It does **not** mean 100 % coverage. |
| Refuse auto-download when no candidate passes a strict gate? | **Yes, by default.** Hold the task before any media request and show a pre-download picker. Keep an explicit, confirmed “Download anyway” override. |
| Thresholds? | Single duration `max(120 s, 2 % of R)`; part sum `max(180 s, 2 % of R)`; title F1 ≥ 0.90; language exact; parts contiguous, same channel, 2–20 parts, each ≥ 60 s; verified channel is a tie-breaker, not a requirement. |
| How to handle split books? | Detect a contiguous same-channel part set whose durations sum to the Audible runtime; download all parts as separate, tagged files in a book batch; do not concatenate by default. |
| What UI replaces the 62 % review? | A pre-download `MatchGateSheet`: “Nothing has been downloaded yet”, candidate rows with `% of book` / `Not the full book`, `Download all N parts`, `Choose a file`, `Search again`, `Paste link`, `Open Audible`, and a confirmed `Download anyway`. |
| Tests / acceptance? | 0 labelled partials auto-download; all labelled full books pass; every hold has a reason and ≥ 3 actions; post-download mismatch → `Incomplete`; 84 Python + 157 Kotlin tests stay green; no Room schema change. |

---

## 1. What “fully reliable” means

### 1.1 Three guarantees

1. **No silent wrong bytes.** A task may enter `Running` only if a candidate passes the gate below. If it does not, the task is held before any media request, with a visible reason.
2. **Every held book is actionable.** The user sees the Audible reference (runtime, chapters, narrator), the candidate list with duration ratios, and at least three exits: pick a candidate, search/paste a link, or open Audible. “No source” is a state, not a dead end.
3. **Every decision is auditable.** The gate records candidate, ratio, title score, language, channel, part structure and rejection reason in `TaskAuditLog`, so a bad pick can be explained without re-running the search.

### 1.2 Metrics

| Metric | Definition | Target |
|---|---|---|
| Silent-wrong rate | Auto-downloaded files whose duration is outside tolerance of the Audible runtime, or whose title/author is a different work | **0** on the labelled corpus; 0 in a 200-book manual audit |
| Auto-download precision | Correct auto-downloads ÷ all auto-downloads | ≥ 99.5 % (target 100 % in tests) |
| Coverage | Books with a gate-passing single or complete part set ÷ books attempted | Measure, do not promise; report by runtime bucket |
| Wasted bytes | Bytes downloaded for a candidate later rejected | 0 before the gate; only explicit “Download anyway” can exceed |
| Time to decision | Search + gate, Wi-Fi | < 8 s for a single-video decision; < 20 s including a part probe |
| Explainability | Held tasks with a reason and ≥ 3 actions | 100 % |

### 1.3 Non-goals

- 100 % coverage. YouTube does not have a complete, correct copy of every Audible title. No gate can create one.
- Downloading or decrypting Audible audio. Audible streams are DRM-protected; that is a hard product boundary (`sleeve_audible.py:1-10`). This document is metadata/verification design only.
- Proving legality of a YouTube upload. The app can verify identity, not rights.

---

## 2. Current behaviour and the exact failure

### 2.1 Flow

1. `DownloadUtil.resolveMetadataTrack` builds a query (`DownloadUtil.kt:164-183`), searches `ytmsearch5` and, if the top score is below 60, `ytsearch10` (`DownloadUtil.kt:332-333`), and ranks the pool (`sleeve_spotify.rank_candidates`).
2. It **auto-selects the first ranked candidate** and fetches that video’s full info (`DownloadUtil.kt:349-365`). The `flagged` boolean is computed here, but it is only stored in `MatchResult`; it does not stop anything.
3. `DownloaderV2.resolveMetadata` writes `downloadState = ReadyWithInfo` (`DownloaderV2.kt:826`). The scheduler treats `ReadyWithInfo` as “start downloading” (`DownloaderV2.kt:656-682`), so the bytes are spent.
4. Only after the batch has no unfinished tasks does `MatchOps.reviewVisible` expose the single “Review matches” sheet (`MatchOps.kt:33-37`; `DownloadPageV2.kt:812`; `MatchReviewSheet.kt:121-131`). The user is asked to review a 10–20 h file that is already on disk.

The low-confidence flag is `top < 75` or `top − second < 15` (`MatchOps.kt:13-21`). It is a UI badge, not a gate.

### 2.2 The 62 % case, reproduced

I ran the app’s real Python ranker against live `ytsearch30` pools on 2026-11-11 (yt-dlp 2026.10.26), with Audible runtime as the reference:

| Book | Audible runtime | Current top pick | Score | Candidate duration | Verdict |
|---|---:|---|---:|---:|---|
| Dune | 21.03 h | “Dune - Book One — Full Audiobook” | 66.0 | 7.23 h (34.4 %) | **wrong** |
| The Hobbit | 11.10 h | “Free audiobook - The Hobbit by JRR Tolkien” | 62.3 | 3.73 h (33.6 %) | **wrong** |
| Educated | 12.20 h | “Educated … Full Audiobook” | 53.0 | 9.31 h (76.3 %) | **wrong** (not full) |
| Atomic Habits | 5.58 h | “Atomic Habits … Full Audiobook” | 90.1 | 5.59 h (100.0 %) | correct |
| Harry Potter and the Sorcerer’s Stone | 8.30 h | “… Book 1 – Narrated …” | 73.7 | 8.31 h (100.1 %) | correct, but flagged |
| The Great Gatsby | 4.80 h | “The Great Gatsby … Full Audiobook” | 93.2 | 4.83 h (100.6 %) | correct |
| Project Hail Mary | 16.17 h | — | — | no candidate ≥ 90 % | refused |
| The Martian | 10.98 h | — | — | no candidate ≥ 90 % | refused |
| Sapiens | 15.30 h | — | — | no candidate ≥ 90 % | refused |

**Auto-selected 6, correct 3, wrong 3 → 50 % precision in this spot check** (small sample; not a population estimate). This is the ranker-only baseline **before the gate**: it measures the old auto-pick path, not the shipped one. Two facts matter more than the percentage:

- The score is a weighted fuzzy sum, not a probability. For the Dune pick the reasons were `title 100`, `artist 23`, `duration 25` (`sleeve_spotify.py:548`). A 66 % score means “the title looked perfect and the duration looked terrible”.
- The correct Harry Potter file scores **73.7** and would be flagged for review, while the wrong Hobbit file scores **62.3** and is also flagged. A score threshold cannot separate them cleanly; duration and title identity can.

### 2.3 The shipped gate, measured on device

§2.2 is the ranker alone on a research sample. This table is the shipped path on a different, device-run sample (queue-side gate from `sleeve_audible_gate.py`, verdict read from the `gate:` line in logcat), one run per book on the debug build, no retries:

| Book | Audible runtime | Run | Verdict | Rejected | Gate reason (from the audit line) |
|---|---|---:|---|---:|---|
| Atomic Habits | 5 h 35 m | pre-fix | hold | 9 | no candidate with a matching title (best 100 % of book) |
| Murder on the Orient Express | 6 h 37 m | pre-fix | hold | 12 | no candidate with a matching title (best 96 %) |
| The Silent Patient | 8 h 33 m | pre-fix | hold | 22 | duplicate part 1 (best 95 %) |
| Harry Potter and the Sorcerer’s Stone (Full-Cast) | 8 h 41 m | pre-fix | no source | 19 | ranker rejected every result → `No confident YouTube match` |
| Educated | 12 h 10 m | pre-fix | hold | 26 | no candidate with a matching title (best 76 %) |
| 1984 | 12 h 18 m | pre-fix | hold | 15 | no full-length candidate — best “1984” is 74 % (best 78 %) |
| Project Hail Mary | 16 h 10 m | pre-fix | no source | 30 | ranker rejected every result → `No confident YouTube match` |
| Dune | 21 h 02 m | pre-fix | **split**, 2 parts | 15 | complete 2-part set: 100 % (best single 57 %) |
| The Fellowship of the Ring | 22 h 38 m | pre-fix | hold | 19 | no candidate with a matching title (best 52 %) |
| Atomic Habits | 5 h 35 m | `f1-byline` | **pass** | 1 | full-length match “Atomic Habits by James Clear Full Audiobook”, 100 % |
| Murder on the Orient Express | 6 h 37 m | `f1-byline` | hold | 15 | no full-length candidate — best “Part 1 (Audiobook)” is 30 % (best 96 %) |

**1/1 then 2/3 auto decisions correct, 0 wrong downloads across all 11 measured runs — sample too small to quote a rate.**

Reading the table:

- **No wrong downloads anywhere.** No bytes were spent on a rejected or mismatched candidate. The only auto decisions were Dune (a complete 2-part set) and, after the fix, Atomic Habits (the exact book at 100 %). Every other hold pointed at a real duration or completeness mismatch, except the byline false hold that the `f1-byline` run removes.
- The two `no source` rows never reached the gate: the ranker rejected all 19 / 30 search results first, so the task failed honestly before any long-form decision. This is a recall gap, not a safety gap.
- **Atomic Habits is the byline false hold, now fixed.** The exact book at 100 % of runtime was held because symmetric token-F1 counted “by james clear” as title noise (0.57). The `f1-byline` run strips the exact artist byline from the candidate title only and passes at 100 %.
- **Murder on the Orient Express still holds, correctly.** Its best candidate is a genuine part 1 at 30 % of the book; only individual parts were found, no complete set. The byline fix does not manufacture a full-length upload that does not exist.
- **Dune split was decided correctly; the download failed in the emulator.** Both parts queued with the set summing to 100 %, but the batch ended `0/2 done · 2 failed` with an empty error after ~1.05 GB of media — the yt-dlp bot-wall signature from issue #113, not a gate error. Real-device confirmation is the remaining check.

**Build provenance.** Pre-fix rows: debug build of `7ad87364` (gate + split + crash fix) with the numeric-ASIN resolver compiled in but not yet committed, plus a temporary diagnostic log line. `f1-byline` rows: `5ba15adc` (byline-aware F1), same emulator (`emulator-5554`), same driver (`D:\Temp\spotcheck.py`, records in `D:\Temp\spotcheck.jsonl`). The driver taps “Download N tracks”, then classifies from `gate:` / `match rejected:` lines; it never infers a verdict from UI text alone.

### 2.4 Root causes in the current code

1. **No pre-download gate.** `flagged` is computed at `DownloadUtil.kt:349`, but `ReadyWithInfo` is written unconditionally at `DownloaderV2.kt:826`. That is the direct cause of “download first, ask later”.
2. **Duration scoring saturates.** `_duration_score` returns 25 for *any* delta above 10 % (`sleeve_spotify.py:455-470`). A 34 % partial and a 57 % partial get the same duration component; the title decides. That is why “Dune - Book One” (34 %) outranks the real 11.9 h Part I.
3. **The decoration stripper deletes “Book One”.** `_LONG_FORM_DECOR_RE` strips `book (one|two|…)` (`sleeve_spotify.py:333-340`) before title scoring. “Dune - Book One | Full Audiobook” collapses to “Dune” and gets `title 100`. That is not inherently wrong: “Book One” is ambiguous (a series number or an internal part). The bug is that the title score is the *only* thing standing between that partial and a download; there is no duration gate to overrule it.
4. **The only duration defense is a 25 % floor.** The candidate loop rejects an upload below 25 % of runtime (`sleeve_spotify.py:505-523`). The Hobbit’s 33.6 % and Educated’s 76.3 % pass it. It was a good emergency fix for trailers and 50-second clips, not a full-book test.
5. **The pool is shallow, and the ranker cannot represent a part set.** Only 5 YouTube Music results and 10 plain YouTube results (`DownloadUtil.kt:332-333`). The clean Dune pair appeared in a 10-result landscape query, but a 30-result run of the same ranker produced only two surviving candidates and neither was a complete part set: the ranker scores one video at a time, so it cannot say “these two together are the book.” In the Project Hail Mary sample, nothing close was in the top 30.
6. **No language check.** A 25.17 h German *Dune* (“komplettes Hörbuch deutsch”) and a 9.55 h Hindi *Dune* appeared in the same searches. Duration + title alone can accept a foreign-language upload.
7. **No part awareness.** When the full book is split, the current code either rejects the parts (25 % floor) or picks one part as if it were the book. There is no “download all parts” path.

---

## 3. Evidence: what YouTube audiobook uploads actually look like

**Method.** yt-dlp 2026.10.26, flat search (`extract_flat`), 2026-11-11. Four queries × 10 results for the initial landscape sample; nine books × 30 results for the ranker spot check; targeted part-1/part-2 queries and two playlist extractions. This is a spot sample, not a population study.

### 3.1 The mix is dominated by non-books

In the 40-result sample (`Dune`, `Project Hail Mary`, `Atomic Habits`, `The Martian`):

| Bucket | Count | Examples |
|---|---:|---|
| < 30 min | 13 | trailers, “explained in 8 minutes”, 3-minute summaries, animatics |
| 0.5–3 h | 16 | “Book Summary in English”, ASMR recaps, live readings, first chapters |
| 3–12 h | 11 | “Full Audiobook” partials, “Part I”, 4-hour “deep dives” |
| > 12 h | 0 | — (one 25.17 h older upload appeared in a deeper search) |

Duration: min 0.01 h, median **1.30 h**, max 11.90 h. Titles routinely contain `Full Audiobook`, `Complete`, `Book One`, `Part I`, `Summary`, `Recap`, `Explained`, `ASMR`, `Live Reading`, `Sleep Story`, `Hindi`, `deutsch`, `PL`.

**Implication:** “Full Audiobook” in a title is marketing, not a fact. The gate must use runtime against the Audible reference, not the word “full”.

### 3.2 The 12-hour upload cap forces split books

YouTube’s own help page: the maximum upload is **256 GB or 12 hours, whichever is less**; older videos may be longer because the limit changed ([support.google.com/youtube/answer/71673](https://support.google.com/youtube/answer/71673?hl=en)). Any new upload of a book longer than 12 h *must* be split.

Observed consequence: *Dune* is 21.03 h. The clean set is:

- `ArfN9gWvT3I` — “DUNE – Book 1 | Full Audiobook | Epic Science Fiction | PART I” — **11.90 h**
- `ZdRRTZUQV5I` — same channel, “PART II” — **9.13 h**
- Sum = **21.03 h**, ratio **1.000** against Audible’s 21.03 h.

That is not a coincidence; it is the shape a complete split book takes. A single-video gate would reject both parts (each is 43–57 % of the book) and leave the user with nothing.

### 3.3 Playlists are useful but dirty

Two real playlists:

- “Dune Audiobooks” — 107 entries, but the first 12 include the same “DuneTube 2019: Dune Part 1!” ten times, plus unrelated videos. Naive summing of the first N entries is wrong.
- “Frank Herbert - Diuna” — 36 chapter entries, Polish titles, 0.14–0.65 h each. This is a chapter-level set, not a part-level set.

**Implication:** playlist extraction is a candidate *source*, not a verdict. The gate must dedupe by video id, require contiguous part numbers, same channel, same language, and a runtime sum within tolerance.

### 3.4 Duplicates and re-uploads are normal

The same Dune Part II appears on `Starlit Pages` and `Kadir Kacar` with the same 9.13 h duration. The same 11.90 h Part I appears on `Starlit Pages` and `daniel peter rorvik`. A deep search returned the same video id twice. Any part-set algorithm must dedupe by id and must not mix channels.

### 3.5 Foreign-language editions are in the same pool

A deeper Dune search returned:

- 25.17 h German: “Dune – Der Wüstenplanet … komplettes Hörbuch deutsch”
- 12.00 h Spanish: “DUNA (Parte 1) … Voz Neural”
- 10.91 h Polish: “DIUNA (2/3) …”
- 9.55 h Hindi: “DUNE (Book One: Dune) … Hindi Audiobook”

A duration-only gate would accept a foreign complete edition. Audible’s `language` field and a title-marker veto are required.

---

## 4. yt-dlp / YouTube capabilities that matter

| Capability | Evidence | Use in Sleeve |
|---|---|---|
| `ytsearchN:` returns a flat list with `id`, `title`, `duration`, `channel`, `uploader`, `channel_is_verified` | Probe: 40/40 rows had `duration`; `channel_is_verified` present as `True`/`None`; README lists `duration` and `channel_is_verified` as filter fields ([yt-dlp README, Filtering Formats](https://github.com/yt-dlp/yt-dlp#filtering-formats)) | Gate input; no extra request for the first pass |
| `--match-filters` can filter flat search results by duration/channel | Probe: `duration > 10800` on a flat `ytsearch10` returned 7 of 10 entries; `--match-filters` is documented under [Video Selection](https://github.com/yt-dlp/yt-dlp#video-selection) | Cheap pre-filter for long-form pools; still re-check locally because the field can be missing |
| Playlist-filter search-results URL | README: YouTube extractor supports “Search (including filters)” and the `ytsearch:` prefix ([Extractor Options](https://github.com/yt-dlp/yt-dlp#extractor-options)); probe: playlist-filter URL returned a ~488-entry playlist soup | Do **not** treat the raw search page as a clean pool. Use `ytsearchN` plus targeted part queries, or extract specific playlists |
| Playlist extraction gives entries with durations | Probe: 107-entry and 36-entry playlists, each entry with duration | Multi-part candidate source; requires dedupe and contiguity checks |
| Full extraction gives `chapters`, `automatic_captions`, `language`, exact `duration` | Probe: wrong 7.23 h Dune had **23 chapters** and 157 auto-caption languages; correct 11.90 h Part I had **0 chapters**, `language: en` | Optional corroboration for finalists; language check when flat data lacks it |
| `--embed-chapters`, `--split-chapters` | README ([Post-processing Options](https://github.com/yt-dlp/yt-dlp#post-processing-options)); Sleeve already uses `--embed-chapters` at `DownloadUtil.kt:794` and `--split-chapters` at `DownloadUtil.kt:1758` | Chapter-aware output and future split-book handling |
| `channel_is_verified` | Probe: 13/40 search rows true; but the correct Dune parts and the correct Atomic Habits upload are **unverified** (`None`) | Tie-breaker only. A hard “verified channel” gate would reject the only correct candidates in this sample |

**Verdict:** yt-dlp already exposes everything needed for a deterministic gate. The missing piece is policy, not extraction capability.

---

## 5. Audible public API: what can be verified

`api.audible.com` exposes the catalog product and content-metadata endpoints used here without an account; the app already treats it as a public metadata source (`sleeve_audible.py:1-10`). I probed it live on 2026-11-11 (US marketplace ASINs).

### 5.1 Catalog product fields

`GET /1.0/catalog/products/{ASIN}?response_groups=product_attrs,contributors,media` returns, for Dune (`B002V1OF70`):

| Field | Observed | Verification use |
|---|---|---|
| `runtime_length_min` | `1262` (21 h 02 m) | Fallback runtime; rounded down to the minute |
| `content_delivery_type` | `MultiPartBook` | Tells us the publisher delivers in parts; not the same as YouTube parts |
| `has_children` | `true` | Same |
| `format_type` | `unabridged` | Reject abridged editions if the ASIN says unabridged |
| `publisher_name` | `Macmillan Audio` | Display, audit |
| `narrators` | 12 names, including Scott Brick | Display; narrator mismatch is a soft warning |
| `language` | `english` | Hard language gate |
| `is_listenable` | `true` | Availability signal |
| `title` | `Dune` (requires `product_desc` or `media`; with only `product_attrs,contributors` the search endpoint returns `title: null`) | Title reference |
| `subtitle` | `Book One in the Dune Chronicles` | Series disambiguation; must not be confused with a YouTube “Part I” |

The current resolver only reads `title`, `authors`, `narrators`, and `runtime_length_min` (`sleeve_audible.py:36-47`); it discards publisher, language, format, delivery type and ASIN. Those are free verification inputs.

### 5.2 Chapter endpoint is public and exact

`GET /1.0/content/{ASIN}/metadata?response_groups=chapter_info&chapter_titles_type=Flat` returned, without auth, for the US ASINs tested:

- `runtime_length_ms` — exact runtime (Dune: **75,736,746 ms**)
- `chapters[]` — `{title, length_ms, start_offset_ms, start_offset_sec}`
- `brandIntroDurationMs` / `brandOutroDurationMs` — e.g. 4,040 / 5,061 ms

Observed:

| ASIN | Book | Catalog min | Exact `runtime_length_ms` | Chapters | Sum of chapter lengths |
|---|---|---:|---:|---:|---:|
| B002V1OF70 | Dune | 1262 | 75,736,746 | 53 | 75,736,746 |
| B08G9PRS1K | Project Hail Mary | 970 | 58,245,891 | 33 | 58,245,891 |
| 1524779261 | Atomic Habits | 335 | 20,134,662 | 34 | 20,134,662 |
| B082BHJMFF | The Martian | 659 | 39,570,692 | 160 | 39,570,692 |

Two design consequences:

1. **Use the exact runtime when available.** Catalog minutes are rounded down; the worst observed difference in this sample is Atomic Habits (20,100 s vs 20,134.7 s, 0.17 %). That is still inside a 2 % tolerance, but exact is better and free.
2. **Chapter count is not a universal gate.** 53, 33, 34 and 160 chapters for four books of similar scale. A full upload may merge chapters; a partial may have many. Use chapter count as corroboration/soft signal, not a hard equality test.

`chapter_info` is listed as a response group on the content metadata/license endpoints in the Audible API docs ([audible.readthedocs.io, External Audible API](https://audible.readthedocs.io/en/latest/misc/external_api.html)). It is **not** accepted on `/1.0/catalog/products/{asin}` — that returns `{"message":"Invalid response group(s) requested: chapter_info"}`, which is why the resolver must make a second request to `/1.0/content/.../metadata`.

### 5.3 A current resolver risk

The keyword path takes `products[0]` blindly (`sleeve_audible.py:77-83`). “Dune” returns 69 products; the first may be a different edition, language, or abridgement. ASIN/URL resolution is exact; keyword search is not. The gate should require an ASIN when the user pastes an Audible URL, and the keyword path should show an edition picker or at least record `ambiguous=true` rather than silently trusting the first hit.

---

## 6. Legal / DRM-free alternatives: where they help and where they cannot

### 6.1 LibriVox

- LibriVox records **only texts in the public domain in the USA**, and states it can generally only record texts **published before 1923** ([librivox.org/pages/public-domain](https://librivox.org/pages/public-domain)).
- Its public API exposes `title`, `totaltime`, `totaltimesecs`, `num_sections`, `sections` ([librivox.org/api/info](https://librivox.org/api/info)).
- Live check: `title=Dune` → **0 books**; `title=Project Hail Mary` → **0 books**. These are modern in-copyright works.

**Where it helps:** public-domain classics (Austen, Dickens, Verne, Wells) where a volunteer recording is acceptable.
**Where it cannot help:** any modern Audible title; also, LibriVox uses different narrators and often different translations, so it is a *different edition*, not a match for an Audible ASIN. Duration/title verification against the Audible runtime would not apply.

### 6.2 Internet Archive

- Archive.org exposes an advanced-search JSON API (`https://archive.org/advancedsearch.php`) and hosts a “Librivox Free Audio” collection alongside public-domain/CC audio.
- It also contains user uploads of uncertain rights. It is **not** a lawful substitute for modern in-copyright Audible titles, and a general “search archive.org for the book” feature would be a piracy surface, not a reliability feature.

**Recommendation:** do not integrate archive.org as an automatic source for Audible titles. If a public-domain path is added later, make it an explicit “Public-domain edition” badge with a separate provenance model, not a fallback in the Audible match flow.

### 6.3 Audible audio itself

Audible’s audiobook streams are DRM-protected (AAX/AAXC); the app correctly treats them as out of scope (`sleeve_audible.py:1-10`). This document does not propose circumvention. The public API is used for **metadata only**, which is the existing product boundary.

**Product/legal risk:** YouTube uploads of in-copyright audiobooks may be unauthorized. Verification makes the app more accurate, not more lawful. That risk is unchanged by this design and should be stated in the UI/terms, not engineered away.

---

## 7. Post-download verification: what is practical on-device

| Option | What it proves | Practical on Android? | Verdict |
|---|---|---|---|
| Container duration (`MediaMetadataRetriever.METADATA_KEY_DURATION`) | The file is as long as expected; catches truncation, wrong part, wrong book of different length | Yes; platform API, ms resolution ([Android docs](https://developer.android.com/reference/android/media/MediaMetadataRetriever#METADATA_KEY_DURATION)) | **Do it.** Primary post-download check |
| File size vs duration × bitrate | Truncated/empty file, implausible bitrate | Yes; arithmetic | **Do it.** Cheap sanity band, not identity |
| Chapter count / chapter titles | If the upload carries chapters, a 23-chapter partial vs a 53-chapter book is suspicious | Medium: `ffmpeg -f ffmetadata` on the bundled `libffmpeg.so` can dump chapters; `MediaMetadataRetriever` does not expose chapters | **Optional corroboration**; do not hard-gate (full uploads may merge chapters) |
| Audio fingerprint (Chromaprint/AcoustID) | Same recording as a reference | No practical reference: the only reference is DRM-protected Audible audio. Fingerprinting candidates against each other only finds duplicates | **Reject** |
| Transcript / speech-to-text | Spoken content matches the book | 20 h of audio is hours of CPU; no reference text from Audible metadata; tiny models are inaccurate on names | **Reject for v1** |
| YouTube auto-captions spot-check | Opening seconds say the title/author | Possible (157 auto-caption languages observed), but another request and unreliable for music/ads | **Optional Tier-2**; not a gate |
| Checksum | File integrity only | Yes | Not identity |

**Key insight:** without a licensed reference audio or text, post-download verification can only prove *internal consistency* (duration, size, chapter structure), not *identity*. A wrong file of the same duration passes. Therefore the **pre-download gate is the primary defense**; post-download verification is a safety net for truncated/partial files and a way to mark `Incomplete` honestly.

---

## 8. Options considered

| Option | What it does | Pros | Cons | Effort | Risk |
|---|---|---|---|---|---|
| **A. Status quo** | Auto-pick top score; flag <75 after batch | No work; preserves recall | Silent wrong downloads; 50 % precision in the 9-book spot check measured **before the gate**; post-hoc review of 20 h files | — | **High** |
| **B. Raise/flag threshold only** | Block if score <80 or gap <15 | Tiny change; no new state | Score is not calibrated: Harry Potter 73.7 is correct, Hobbit 62.3 is wrong, Educated 76.3 is wrong. Any single threshold either blocks correct books or passes wrong ones; score carries no duration identity | S | **High** (false confidence) |
| **C. Strict single-video gate + pre-download hold** | Duration + title + language + junk veto; hold if fail | Eliminates silent wrong single-video downloads; simple; testable; no part complexity | Refuses most books >12 h that only exist as parts; recall drops; needs a new UI state | M | **Medium** (coverage) |
| **D. C + complete multi-part detection** | Find contiguous part sets, sum runtime, download all as separate files | Recovers Dune-style split books; matches how YouTube actually hosts long books; each part verifiable | Complex grouping/dedupe; mixed editions; storage; 20+ files; partial-failure UX | L | **Medium** |
| **E. Manual-only** | Always show picker or require paste | Zero silent wrong; trivial policy | Terrible UX for a 5 h book that has an obvious full upload; users will pick badly | S | **Low precision, high friction** |
| **F. Fingerprint/transcript verification** | Compare audio/text to a reference | Strong identity if a reference existed | No lawful reference; heavy on-device; 20 h CPU; false rejects | XL | **Impractical** |

**Recommendation: D, phased.** Ship C first (single-video gate + hold UI) to stop the bleeding; add D (multi-part) as phase 2 for books >6 h. Keep F out of scope.

---

## 9. Recommended design

### 9.1 The gate (deterministic, before any media request)

Inputs:

- `BookExpectation` (a value object built from `MetadataTrack`; not a separate persisted entity): `title`, `authors`, `language`, `runtimeMs` (exact `runtime_length_ms` when available, else `runtime_length_min × 60_000`), `chapterCount`, `asin`, `formatType` (unabridged).
- `Candidate`: `id`, `url`, `title`, `channel`, `uploader`, `duration`, `language?`, `chapterCount?`, `verifiedChannel?`.

**Scope:** apply the gate only when `Task.TypeInfo.MetadataTrack.provider == "Audible"`. Spotify, Gallery and plain URL tasks keep the existing ranker/review path; their thresholds and tests must not change.

**Units:** `MatchCandidate.duration` is whole seconds (`Match.kt:26`); `MetadataTrack.durationMs` is milliseconds (`Task.kt:106`). Convert once at the gate boundary. A 1000× mix-up looks exactly like a duration mismatch and would hold every book.

Decision order:

1. **Normalize the title.**
   - Strip marketing decoration: `full/complete audiobook`, `unabridged`, `audiobook`, genre words (`epic`, `saga`, `science fiction`, `sci-fi`, `fantasy`, `thriller`, `novel`, `bestseller`), trailing `narrated by …`, bracketed tags, leading `01)` numbering, separators.
   - Extract **explicit part markers** separately: `part 1/2`, `part I`, `(1/3)`, `vol. 1`, `chapter 7`. Treat `book N` as a **neutral series/part marker**: remove it from the F1 calculation, record it for grouping, but do not penalize a full-length single titled “… Book 1” (the Harry Potter sample).
   - Compute token-set F1 between the remaining core title and the Audible title. Require **F1 ≥ 0.90** and no extra significant token. Examples: “Dune Messiah” vs “Dune” = 0.67 (reject); “The Martian: A Novel — Part 1” after stripping genre/part = 1.00 (single candidate; duration decides); “Dune - Book One” after neutralizing the book marker = 1.00, but its 34 % duration fails the single gate. The duration gate, not the title score, is what rejects the Dune partial.
2. **Reject junk titles/channels.** `summary`, `recap`, `explained`, `review`, `trailer`, `reaction`, `analysis`, `in N minutes`, `sample`, `excerpt`, `preview`, `scene`, `ASMR`, `sleep story`, `full movie`, `audiobook scene`; channel names containing `summary`, `recap`, `lyrics`, `karaoke`, `nightcore`, etc. The existing music junk list is too narrow for audiobooks. Use token-boundary matching, not raw substring: the correct Atomic Habits upload is on **JunkLoader**, so a naive `junk` substring rule would reject the only correct candidate in that sample.
3. **Reject language mismatch.** Prefer the candidate `language` from full extraction; otherwise use title markers (`german`, `deutsch`, `hindi`, `español`, `spanish`, `polski`, `français`, `italiano`, `português`, `русский`, `日本語`, `mandarin`, `urdu`, etc.). Normalize ISO codes (`en` ↔ `english`, `de` ↔ `german`, …) before comparing. Hard reject if it conflicts with Audible `language`.
4. **Single-video pass (try this first).** A candidate passes only if:
   - `abs(D − R) ≤ tol_single`, where `tol_single = max(120 s, 2 % of R)`;
   - title F1 ≥ 0.90 after normalization;
   - not junk; language matches; channel not junk.

   Do **not** reject a full-length single merely because its title says “Book 1”. The Harry Potter sample is a correct 100.1 % single upload titled “… Book 1 – Narrated …”; it must pass. Part markers are recorded for grouping, but they only matter when the single pass fails. This ordering (single first, parts second) is what makes the gate both safe and tolerant of series labelling.
5. **Part-set pass.** If no single pass, group candidates by `(channel, core title)` and:
   - parse part numbers; require contiguous `1..N` (Arabic/Roman, leading zeros);
   - dedupe by video id; reject duplicate part numbers or duplicate ids;
   - same channel for all parts (do not mix re-uploads);
   - same language for all parts;
   - each part ≥ 60 s; each part ≤ 12 h (YouTube cap; older exceptions allowed but flagged);
   - `2 ≤ N ≤ 20` (above 20, treat as a chapter-level playlist and ask; do not silently create 60 tasks);
   - `abs(ΣD − R) ≤ tol_parts`, where `tol_parts = max(180 s, 2 % of R)`;
   - no junk part; if any part fails, the whole set fails (no partial “complete”).
6. **Chapter corroboration (soft).** If the candidate’s full metadata has chapters:
   - `count ≥ 0.8 × expected` → confidence plus;
   - `count < 0.5 × expected` → downgrade to hold **unless** duration is within 0.5 % (merged chapters are common; don’t hard-reject on this alone).
7. **Post-download verification.** After the file exists, read container duration via `MediaMetadataRetriever`; require `abs(actual − R) ≤ max(120 s, 2 % of R)` and a plausible file size. If it fails, mark `Incomplete`, keep the file for inspection/resume, and never mark `Completed`.

### 9.2 Thresholds and why

| Threshold | Value | Rationale |
|---|---|---|
| Single duration tolerance | `max(120 s, 2 % of R)` | Observed catalog rounding ≤ 0.17 %; brand intro/outro ≈ 9 s; correct candidates 100.0–100.6 %; wrong partials 33.6 %, 34.4 %, 76.3 %. 2 % separates them with margin |
| Part-set sum tolerance | `max(180 s, 2 % of R)` | Dune parts sum 100.000 %; per-part credits can add a few seconds; 2 % blocks missing/duplicated parts |
| Minimum part length | 60 s | Avoids trailers/chapters fragments; a real part is minutes |
| Maximum parts | 20 | Avoids creating 60 queue entries; chapter-level sets get a separate confirmation/playlist path |
| Title F1 | ≥ 0.90 | Rejects sequels (“Dune Messiah” 0.67) and unrelated extra tokens; `Book N` is neutralized and handled by duration/part logic, so a full-length “… Book 1” single is not falsely rejected |
| Language | exact match | Foreign complete editions exist; a duration-only gate would accept a 21 h German upload |
| Verified channel | **not a positive requirement** | 13/40 search rows verified; the correct Dune parts and the correct Atomic Habits upload are unverified. Use as tie-breaker only |
| Chapter count | soft 0.5/0.8 bands | 53/33/34/160 chapters across four books; full uploads may merge chapters |

### 9.3 Decision states

```
AUTO       gate Pass (single) or Split (complete part set) -> download
PICK       gate Hold: candidates exist but none pass -> pre-download picker
NO_SOURCE  no candidate at all / only junk -> picker with search, paste, Audible
INCOMPLETE post-download verification failed -> resume or pick another
```

`PICK` and `NO_SOURCE` are not errors. They are the honest answer. `INCOMPLETE` is a file that failed duration verification; keep it, label it, offer resume. A batch containing `PICK` items is “waiting for you”, not “still downloading”.

**Projected on the same 9-book spot check** (not a benchmark; the first row is the measured pre-gate baseline):

| Policy | Auto-downloaded | Held for a pick | No source | Correct auto | Precision |
|---|---:|---:|---:|---:|---:|
| Current, before the gate (measured) | 6 | 0 (reviewed after the bytes) | 3 | 3 | 50 % |
| Gate, phase 1 (single only) | 3 | 3 | 3 | 3 | 100 % |
| Gate, phase 3 (+ parts) | 4 | 2 | 3 | 4 | 100 % |

The trade is explicit: precision goes from the measured pre-gate 50 % to a projected 100 %, coverage of this sample goes from 3 correct auto-downloads to 4, and the user gets a picker instead of a wrong 20 h file. The remaining 2–3 books need a manual link or Audible. The shipped gate's own spot check in §2.3 is a different (11-run) sample and is the number to quote, not this projection.

### 9.4 Multi-part decision

**Ship “download all parts as separate files” first; do not concatenate by default.**

Why:

- No re-encode: each part is downloaded as-is; no quality loss.
- Per-part verification: each part’s duration can be checked; a missing part is visible.
- Resume: a failed part can be retried without re-downloading 20 h.
- Player compatibility: a folder of `Part 01.m4a …` with album/track tags works in most audiobook players; a 20 h+ single m4b is a bigger compatibility/storage bet.
- Concatenation can come later as an explicit “Merge into one file” action, using ffmpeg concat demuxer with `-c copy` only when all parts share codec/container parameters. Risks: timestamp gaps, chapter atom rebuild, 20 h+ file size, partial-failure loss.

What breaks in multi-part:

| Failure | Detection | Handling |
|---|---|---|
| Missing part | Part numbers not contiguous / sum short | Hold, show “Part 2 of 3 missing” |
| Duplicate part | Same id or same part number twice | Dedupe by id; reject set if duplicate numbers |
| Per-part intro/outro/ads | Sum overshoots > 2 % | Reject set; do not trim automatically (could cut content) |
| Mixed editions/narrators | Different channel, language, or description | Require same channel + language; narrator check if description has it |
| Parts out of order | Part parser / playlist index | Sort by parsed number; never by search rank |
| One part fails mid-download | Per-part state | Keep completed parts, mark `Incomplete`, offer retry |
| Storage | 21 h at ~128–160 kbps is ~1.2–1.5 GB | Preflight free-space check; show estimated size |
| 20+ chapter files | N > 20 | Do not auto-create; offer “Download playlist” confirmation or hold |

### 9.5 UI: replace the post-download review

**New pre-download sheet: `MatchGateSheet`**, shown before `ReadyWithInfo`. For a single-book submission, open it automatically when the task enters `AwaitingPick`; for a batch, surface a `Needs a pick (N)` entry at the top and open the first held book on tap (do not interrupt a 20-book batch with a modal).

Content:

```
Dune
Audible: 21 h 03 m · 53 chapters · narrated by Scott Brick, Orlagh Cassidy, …
[ No complete single upload found ]

  ○  DUNE – Book 1 | Full Audiobook | … PART I     11 h 54 m · 57 %
     Starlit Pages · Part 1 of 2
  ○  DUNE – Book 1 | Full Audiobook | … PART II     9 h 08 m · 43 %
     Starlit Pages · Part 2 of 2
  ○  Dune - Book One | Full Audiobook                7 h 14 m · 34 %
     HK Book World · Not the full book

[ Download all 2 parts ]   [ Choose a file ]
[ Search again ] [ Paste YouTube link ] [ Open Audible ]
                                             [ Download anyway… ]
```

Behaviour:

- **Primary CTA** is `Download all N parts` when a complete set passes; otherwise `Use this upload` for the selected row; otherwise `Search again` / `Paste link`.
- **“Not the full book” badge** on every candidate below 95 % of runtime, with the exact ratio. No score percentages in the main row; put the ranker score in a “Details” expander or audit log.
- **“Download anyway…”** is a secondary action behind a confirmation that names the mismatch: “This upload is 7 h 14 m. Audible says 21 h 03 m. It is probably not the full book.” This preserves user agency without making the wrong path the default.
- **No source state:** “YouTube has no complete copy we can verify. Open Audible or paste a link.” Buttons: `Open Audible`, `Search again`, `Paste link`.
- **Batch:** a `Needs a pick (N)` section at the top. Held books do not start; confident books proceed. `AwaitingPick` counts as unfinished, so the batch never falsely reports “done”; `BatchSummary.needsPick` and the notification say “N need a pick”. The old single “Review matches” entry is removed for audiobooks (keep `MatchReviewSheet` for music/legacy). A held batch can be left and resumed later; it must not block starting an unrelated download.
- **Cards:** `Needs a pick` (amber), `Split book` (blue), `Incomplete — 9 h 12 m of 12 h 12 m` (red). Content descriptions for accessibility; don’t rely on color alone.
- **After download:** if verification fails, card shows `Incomplete` with `Resume` / `Find another`; never a silent `Completed`.

### 9.6 Scheduler integration

Current scheduler picks `ReadyWithInfo` or `Idle` (`DownloaderV2.kt:656-682`). Add a held state that the scheduler does **not** pick:

- `FetchingInfo -> AwaitingPick` on gate Hold/NoSource;
- `AwaitingPick -> ReadyWithInfo` when the user picks a passing candidate (or explicitly overrides);
- `AwaitingPick -> FetchingInfo` on “Search again”;
- `AwaitingPick` counts as unfinished (`BatchOps.isUnfinished`) but not active; `stop` should cancel it.

This avoids the loop where leaving the task `Idle` causes `prepare()` to re-resolve it forever. It also makes “we have not downloaded anything” explicit in the state machine.

---

## 10. Implementation sketch (no code changes in this task)

### 10.1 Python: metadata and gate

**`app/src/main/python/sleeve_audible.py`**

- Extend `_product_dto` to return `runtimeMs`, `publisher`, `narrators`, `language`, `formatType`, `deliveryType`, `hasChildren`, `asin`. Set `durationMs` to the exact runtime when the chapter endpoint is available, falling back to `runtime_length_min × 60_000`, so the ranker and the gate share one value.
- Add `_chapter_info(asin)`: GET `https://api.audible.com/1.0/content/{asin}/metadata?response_groups=chapter_info&chapter_titles_type=Flat`; return `runtimeMs`, `chapters` (title/length/offset), `chapterCount`; fall back to catalog runtime on failure.
- `resolve(url, keywords)`: after the catalog product, merge chapter info; return `kind: "book"` plus the new fields.
- Keyword path: return top N matches or set `ambiguous=true`; do not silently take `products[0]` (`sleeve_audible.py:77-83`).

**`app/src/main/python/sleeve_spotify.py`**

- Split `_LONG_FORM_DECOR_RE` into:
  - `_MARKETING_RE`: `full/complete audiobook`, `unabridged`, `audiobook`, genre words, separators;
  - `_PART_RE`: `part N`, `part I`, `book N`, `(N/M)`, `vol. N`, `chapter N`.
- `_strip_long_form_decor` → `_normalize_long_form(title)` returning `(core_title, part_marker, part_index, part_count)`.
- `_name_score` for long-form: token-set F1 with extra-token penalty; keep the music path unchanged so existing tests stay green.
- New `gate_candidates(track_json, candidates_json, expectation_json)`:
  - returns `{verdict: "pass"|"split"|"hold"|"no_source", reason, best, parts, rejected}`;
  - pure, no I/O, unittest-friendly;
  - uses `_slug`, `_normalize_long_form`, `_duration_seconds`.
- New `group_parts(candidates_json, expected_ms)`: returns complete groups with `sum_ms`, `ratio`, `parts`; dedupes by id; rejects non-contiguous/duplicate/mixed-channel sets.

### 10.2 Kotlin: data and policy

**`app/src/main/java/com/junkfood/seal/download/Match.kt`**

```kotlin
@Serializable
data class MatchCandidate(
    /* existing fields */
    val language: String = "",
    val chapterCount: Int = 0,
    val partIndex: Int = 0,
    val partCount: Int = 0,
    val verifiedChannel: Boolean? = null,
)

@Serializable
enum class GateVerdict { Pass, Split, Hold, NoSource }

@Serializable
data class MatchGate(
    val verdict: GateVerdict = GateVerdict.Hold,
    val reason: String = "",
    val ratio: Double = 0.0,
    val expectedRuntimeMs: Long = 0L,
    val expectedChapters: Int = 0,
    val parts: List<MatchCandidate> = emptyList(),
)

@Serializable
data class MatchResult(
    /* existing fields */
    val gate: MatchGate? = null,
)
```

All new fields have defaults, so cached beta.11 JSON still decodes (same additive-field rule as `Match.kt:12-30`).

**`app/src/main/java/com/junkfood/seal/download/MatchOps.kt`**

- Keep the threshold constants in the Python gate (single source of truth), not duplicated in Kotlin. Kotlin maps the verdict to state/UI: `fun needsPick(state: Task.State): Boolean` — `state.downloadState is AwaitingPick`; `fun gateEntries(entries)` / `gateCount(entries)` — analog of `flaggedEntries`; `fun summaryNeedsPick(entries)`.
- Keep `isLowConfidence` for music; for Audible, `gate` supersedes it. Do not delete the old flag: it is still useful for non-audiobook providers and for audit.

**`app/src/main/java/com/junkfood/seal/download/Task.kt`**

- Add `@Serializable data object AwaitingPick : DownloadState`. Renumber the ordinal block to `Running 0, ReadyWithInfo 1, FetchingInfo 2, AwaitingPick 3, Idle 4, Canceled 5, Error 6, Completed 7`; the ordinal is computed, not persisted, so renumbering is safe. Update the `when` and `compareTo`.
- Every exhaustive `when (downloadState)` site must gain a branch: `DownloaderV2` (queue/prepare, ~`:1014-1030`), `ActionSheet`, `VideoCardV2`, `BatchOps`. This is the main mechanical cost of a new state; a compiler error is the safety net.
- Extend `TypeInfo.MetadataTrack` (`Task.kt:100`) with `asin`, `runtimeMs`, `language`, `chapterCount`, `publisher`, `formatType` (all defaulted). Because `Task.isSameWork` currently compares `type == other.type` (`Task.kt:80-84`), add a stable `workKey` (provider + title + artist + album + durationMs + authors) and compare that instead, ignoring the new verification fields. Otherwise two submissions of the same book resolved with/without chapter info look like different work and download twice.
- Add `@Serializable data class Incomplete(val filePath: String?, val expectedMs: Long = 0L, val actualMs: Long = 0L, val reason: String = "") : DownloadState` instead of changing `Completed`. It is a truthful partial result, not a retryable `Error`. `BatchOps.isResumable` includes it; the card offers `Resume` / `Find another`.

**`app/src/main/java/com/junkfood/seal/download/BatchOps.kt`**

- `isUnfinished`: include `AwaitingPick`; `isActive`: exclude; `isResumable`: include `Incomplete`.
- `summary`: add `needsPick` count for the UI/notification.

### 10.3 Kotlin: resolution and download

**`app/src/main/java/com/junkfood/seal/util/DownloadUtil.kt`**

- Change the return type of `resolveMetadataTrack` to a small sealed outcome: `sealed interface MetadataOutcome { data class Resolved(val info: VideoInfo, val match: MatchResult); data class Held(val match: MatchResult) }`. The caller then chooses `ReadyWithInfo` vs `AwaitingPick`.
- `resolveMetadataTrack` (`DownloadUtil.kt:283`):
  1. rank as today;
  2. call `GhostEngine.gateAudiobookCandidates(...)` (new bridge to `sleeve_spotify.gate_candidates`) with `runtimeMs`, authors, language, chapterCount;
  3. if `Pass`: fetch winner info as today (`:361`), return `Resolved(info, match)`;
  4. if `Split`: return `Held(match)` without fetching media; the sheet shows `Download all N parts`;
  5. if `Hold/NoSource`: return `Held(match)` without fetching media. This alone removes the 62 % download.
- Add `searchAudibleParts(track, taskKey, preferences)`:
  - start with `ytsearch10:"<title>" <author> audiobook part 1` and `part 2`; then extend to `part 3..N` while a contiguous part is found, or extract the channel/playlist once a part is identified; cap at 20;
  - optional: playlist-filter URL `https://www.youtube.com/results?search_query=...&sp=EgIQAw%3D%3D`, extract top 3 playlists, then entries;
  - dedupe by id; gate; cache by ASIN in `MatchResult`.
- Add `enqueueParts(task, parts)`: do not write a custom 20-hour download loop. Create a part batch of N `Task`s (one per part URL) using the existing playlist/batch machinery, with `Playlist(index)` ordering and a book-level `BatchInfo.title`. The scheduler already downloads batch tasks and reports one aggregate notification. Each part task verifies its own duration; the batch is complete only when all parts are `Completed`. This avoids new persistence and keeps resume/stop behaviour consistent with playlists.
- Optional staging: download each part to a `.part` folder and publish the book folder only when the whole set is verified; if one part fails, leave the rest and mark the batch `Incomplete` (do not present a partial set as complete).

**`app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt`**

- `resolveMetadata` (`:804`): on `Held`, set `match = ...; downloadState = AwaitingPick` instead of `ReadyWithInfo` (`:826`).
- `doYourWork` (`:656`): keep `ReadyWithInfo`/`Idle` only; `AwaitingPick` is naturally skipped. Add a unit-testable `nextTask(entries)` predicate.
- `swapMatches` (`:348`): after `fetchVideoInfoFromUrl`, re-run the gate on the candidate; if it fails, return `SwapOutcome.FetchFailed` with the gate reason and leave the old selection/file untouched. This prevents a “swap” from replacing a full book with a 34 % partial.
- After `downloadVideo` completes: call `AudioVerify.verifyDuration(...)`; if duration is outside tolerance, set `Incomplete` and keep the file; only `Completed` when it passes. For a part batch, the batch summary shows `Incomplete — k of N parts` until every part passes.

**`app/src/main/java/com/junkfood/seal/util/AudioVerify.kt` (new)**

```kotlin
data class VerifyResult(val ok: Boolean, val actualMs: Long, val expectedMs: Long, val reason: String)

fun verifyDuration(context: Context, file: File, expectedMs: Long): VerifyResult
```

Use `MediaMetadataRetriever.setDataSource(file.absolutePath)` + `extractMetadata(METADATA_KEY_DURATION)`; tolerance `max(120_000, 0.02 * expectedMs)`; file size sanity `0.5×–2×` expected bytes **only when the bitrate is known**, otherwise check non-zero size and duration. Optional phase 2: `chapters(file)` via `libffmpeg.so -f ffmetadata -`.

**`app/src/main/java/com/junkfood/seal/ghostengine/GhostEngine.kt`**

- Extend `SpotifyTrack` with `runtimeMs`, `language`, `chapterCount`, `asin`, `publisher`, `formatType` (defaults); map them in `audibleResolve`. `DownloadDialogViewModel` copies them into the `MetadataTrack` it creates.
- Add `gateAudiobookCandidates(track, candidatesJson, expectationJson): Result<MatchGate>` around `sleeve_spotify.gate_candidates`.
- Keep `rankCandidatesWithAlternatives` for ordering; the gate is a separate call so the score never decides identity.

### 10.4 UI

**`app/src/main/java/com/junkfood/seal/ui/page/downloadv2/MatchGateSheet.kt` (new)**

- States: `Auto` (brief “matched” then dismiss), `Pick`, `Split`, `NoSource`, `Incomplete`.
- Rows: title, channel, duration, ratio badge, part badge, language badge, chapter badge.
- Actions as in §9.5.
- Reuse the `SwapStatusLine` pattern for per-part results; reuse `SealModalBottomSheet`.
- Keep `MatchReviewSheet` for music only; or rename it `MusicMatchReviewSheet` and leave the audiobook path untouched.

**`app/src/main/java/com/junkfood/seal/ui/page/downloadv2/DownloadPageV2.kt`**

- Replace `reviewBatchId`/`MatchReviewSheet` (`:812`) with `gateBatchId`/`MatchGateSheet` for Audible batches.
- Add `Needs a pick (N)` entry above completed rows; tapping opens the gate sheet for the first held book.
- Add card badges in `VideoCardV2.kt` / `PlaylistBatchCard.kt`.

**`app/src/main/res/values/strings.xml`**

New strings (examples):

- `match_gate_title` = “Choose the book”
- `match_gate_no_download` = “Nothing has been downloaded yet.”
- `match_gate_not_full` = “%1$d%% of the book — not the full book”
- `match_gate_parts` = “%1$d parts · %2$s total”
- `match_gate_download_parts` = “Download all %1$d parts”
- `match_gate_open_audible` = “Open Audible”
- `match_gate_paste_link` = “Paste YouTube link”
- `match_gate_search_again` = “Search again”
- `match_gate_download_anyway` = “Download anyway…”
- `match_gate_anyway_confirm` = “This upload is %1$s. Audible says %2$s. It is probably not the full book.”
- `match_incomplete` = “Incomplete — %1$s of %2$s”

Remove/retire the audiobook use of `match_review_hint` (“Some songs were matched with low confidence…”) because it describes the old post-hoc model.

### 10.5 Suggested rollout

| Phase | Scope | Files | Effort |
|---|---|---|---|
| 1 | Single-video gate + `AwaitingPick` + pre-download sheet; no parts | Python gate, Match/MatchOps, Task, DownloadUtil, DownloaderV2, MatchGateSheet, strings | 2–3 days |
| 2 | Post-download duration verification + `Incomplete` | AudioVerify, DownloaderV2, UI badge | 1 day |
| 3 | Multi-part detection + “download all parts” | Python `group_parts`, DownloadUtil, part batch/enqueue, sheet | 3–5 days |
| 4 | Optional merge to m4b / chapter tagging | ffmpeg concat, output templates | 2–3 days, opt-in |

Phase 1 alone removes the reported 62 % download. Phase 3 recovers Dune-style books. Phase 4 is UX, not reliability.

---

## 11. Test plan and acceptance criteria

### 11.1 Python unit tests (`scripts/test_match_gate.py`)

Use the real rows from the spot check as fixtures; no network.

| Test | Fixture | Expected |
|---|---|---|
| Hobbit partial rejected | 3.73 h vs 11.10 h, score 62.3 | `hold`, reason `duration 34%` |
| Dune “Book One” rejected | 7.23 h vs 21.03 h, score 66.0 | `hold`, not `pass` |
| Dune two parts accepted | 11.90 h + 9.13 h, same channel | `split`, ratio 1.000, parts ordered |
| Dune parts missing Part II | only 11.90 h | `hold`, “incomplete set” |
| Dune duplicate Part II | same id twice | `hold`, “duplicate” |
| Dune non-contiguous 1,3 | 2 parts | `hold`, “missing 2” |
| PHM summaries rejected | 3.17 h / 2.15 h / 1.76 h vs 16.17 h | `no_source`/`hold`, no pass |
| Martian different work rejected | “George Du Maurier - The Martian: A Novel — Part 1”, 6.17 h | `hold`, author/title mismatch |
| Dune Messiah sequel rejected | 9.01 h vs 21.03 h | `hold`, title F1 0.67 |
| German complete edition rejected | 25.17 h, “komplettes Hörbuch deutsch” | `hold`, language |
| Atomic Habits full passes | 5.59 h vs 5.58 h | `pass` |
| Harry Potter full passes despite score 73.7 | 8.31 h vs 8.30 h | `pass` (score is not the gate) |
| Unknown duration holds | `duration=None` | `hold`, never `pass` |
| Tolerance boundaries | 0.981/1.019 pass; 0.969/1.031 fail | exact |
| Book One is a marker, not a title win | `_name_score` neutralizes `book one`; gate on 7.23 h vs 21.03 h | `hold`, duration reason; marker recorded |

Keep the existing 84 Python tests green; the music path must not change.

### 11.2 Kotlin unit tests

- `AudiobookGateTest.kt`: `GateVerdict.Hold` → `AwaitingPick`; no `downloadVideo` call (fake scheduler); `Split` → no media fetch until user confirms; `Pass` → `ReadyWithInfo`.
- `BatchOpsTest`: `AwaitingPick` counts as unfinished, not active; `stop` includes it; summary `needsPick`.
- `AudiobookPartsTest.kt`: part ordering, sum tolerance, duplicate id, missing part, mixed channel.
- `MatchTransactionTest.kt`: old JSON decodes with new fields defaulted; `swapMatches` re-gates and refuses a partial; pending selection still restores on failure.
- `AudioVerifyTest.kt`: duration within/outside tolerance; zero-length file; file size band.
- `CandidateMappingTest.kt`: new candidate fields map from JSON and survive a round trip.

### 11.3 Compose UI tests

- `MatchGateSheet` shows “Nothing has been downloaded yet.” for Hold.
- A 34 % candidate renders `34% · not the full book` and is not preselected as a full book.
- Split state renders `Download all 2 parts` and the total runtime.
- `Download anyway…` requires a confirmation; cancel returns to the sheet.
- `Open Audible` invokes the URI handler; `Paste link` opens the input.
- No score percentage in the primary row; it is available under Details.
- Accessibility: ratio badge has a content description; actions are reachable in landscape.

### 11.4 Corpus / live evaluation

- Build a fixture corpus of 30–50 ASINs across runtime buckets (<2 h, 2–6 h, 6–12 h, >12 h), languages, and genres. Record the `ytsearch30` + part-probe JSON once; commit under `app/src/test/resources/audible_corpus/` (JSON only, no media). Label each candidate `full`, `partial`, `summary`, `foreign`, `different_work`.
- CI: run the gate over fixtures; assert **0 partials pass**, **all labelled full pass**, and every hold has a reason. Do not gate CI on live YouTube.
- Manual/nightly: run 10 live books; record precision/coverage; compare to the fixture baseline. A change in YouTube search ordering should show up as a coverage delta, not a silent precision drop.

### 11.5 Acceptance criteria

1. **AC1:** No task reaches `Running` without a `Pass` or `Split` gate verdict, except an explicit, logged user override. A fake `Hold` must produce zero `downloadVideo` calls.
2. **AC2:** 0 of 20 labelled partial fixtures auto-download (including the 33.6 %, 34.4 %, 76.3 % rows).
3. **AC3:** 10 of 10 labelled full fixtures auto-download (including the 73.7-score Harry Potter row, proving score is not the gate).
4. **AC4:** A complete part set sums within 2 % of Audible runtime and downloads all parts in order; a missing/duplicate part produces `Hold`, not a partial “complete”.
5. **AC5:** Post-download duration outside tolerance marks `Incomplete`, never `Completed`.
6. **AC6:** Every held task has a visible reason and at least three actions (pick/search/paste/open Audible).
7. **AC7:** `Download anyway…` is opt-in, confirmed, and logged; default path never uses it.
8. **AC8:** Existing 157 Kotlin tests and 84 Python tests remain green; new tests are additive. (`python -m unittest discover -s scripts -p "test_*.py"` was 84/84 OK on 2026-11-11.)
9. **AC9:** No Room schema change; `assembleGenericDebug` stays green. New fields are additive serialized state only.
10. **AC10:** The audit log contains one line per candidate with gate verdict, ratio, title score, language, channel, and part marker. A user can explain a rejection from the log alone.

---

## 12. Residual risks and open questions

### 12.1 Biggest residual risk: coverage, not precision

A strict gate will refuse books that YouTube does not host completely. In the 9-book spot check, 3 of 9 had no single candidate ≥ 90 %; multi-part recovers Dune but not Project Hail Mary or The Martian in the sampled pool. Users may experience “the app got worse” even though it stopped downloading wrong files. The mitigation is product, not code: make `No source` a first-class, informative state with `Open Audible`, `Paste link`, and `Search again`; measure coverage by runtime bucket and publish it internally.

### 12.2 Other risks

| Risk | Why it matters | Mitigation |
|---|---|---|
| Mixed editions / abridgements | A complete set from a different narrator/edition can sum to 100 % and pass | Same channel, language, format (`unabridged`), narrator keywords in description; chapter-count corroboration; residual risk remains |
| Language detection | Flat search may omit `language`; title markers are brittle | Full-extract finalists; marker list; user-visible language badge; ASIN marketplace |
| YouTube search instability | Results vary by region/time; a title can disappear | Fixture corpus + live eval; cache candidates; never re-search on every UI open |
| Audible API drift | Undocumented endpoints can change; `chapter_info` may require auth in some marketplaces | Fallback to catalog runtime; treat chapter info as optional; log failures |
| 20+ part books | Creating 60 queue entries is bad UX; 21 h at ~128–160 kbps is ~1.2–1.5 GB | Cap N at 20; ask before chapter-level playlist; free-space preflight |
| Per-part ads/intros | Sum overshoots; trimming could cut content | Reject set; show the overshoot; do not auto-trim |
| Post-download identity | Duration only proves length, not identity | Pre-download gate is primary; keep the audit log; optional chapter/text spot-check later |
| Legal/rights | YouTube uploads may be unauthorized; verification does not grant rights | Keep the existing product boundary; do not integrate archive.org as a source; state the risk in terms |
| Score semantics | Users read 62 % as probability | Remove score from primary UI; show gate facts; keep score in Details/audit |

### 12.3 Open questions for the product owner

1. Should `Download anyway…` exist at all? It preserves agency but creates a path to a known-wrong 20 h file. My recommendation: keep it behind a typed/confirmed warning, off by default.
2. For a 30-chapter playlist, do we create 30 files, one merged m4b, or refuse? Recommendation: cap at 20 parts, ask; merge later.
3. Should a trusted channel (two prior correct downloads) bypass the verified-channel tie-break? Recommendation: yes, but never bypass duration/title/language.
4. Do we show the Audible purchase link for every hold, or only when no source exists? Recommendation: always; it is the honest alternative.
5. How do we handle a user-pasted YouTube link that fails the gate? Recommendation: show the gate facts and require an explicit override; do not silently accept a pasted link either.

---

## 13. Sources

All web sources accessed **2026-11-11**.

1. yt-dlp README — filtering fields (`duration`, `channel_is_verified`), video selection (`--match-filters`), post-processing (`--embed-chapters`, `--split-chapters`), extractor options (`ytsearch:`): [github.com/yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp)
2. YouTube Help — maximum upload size/length (256 GB or 12 hours): [support.google.com/youtube/answer/71673](https://support.google.com/youtube/answer/71673?hl=en)
3. Audible external API documentation — response groups, content metadata, `chapter_info`: [audible.readthedocs.io/en/latest/misc/external_api.html](https://audible.readthedocs.io/en/latest/misc/external_api.html)
4. Audible public catalog (live probe, Dune): [api.audible.com/1.0/catalog/products/B002V1OF70?response_groups=product_attrs,contributors,media](https://api.audible.com/1.0/catalog/products/B002V1OF70?response_groups=product_attrs,contributors,media)
5. Audible public chapter metadata (live probe, Dune): [api.audible.com/1.0/content/B002V1OF70/metadata?response_groups=chapter_info&chapter_titles_type=Flat](https://api.audible.com/1.0/content/B002V1OF70/metadata?response_groups=chapter_info&chapter_titles_type=Flat)
6. LibriVox — public domain policy (public-domain texts only; generally pre-1923): [librivox.org/pages/public-domain](https://librivox.org/pages/public-domain)
7. LibriVox — API fields (`totaltime`, `num_sections`, `sections`): [librivox.org/api/info](https://librivox.org/api/info)
8. Internet Archive — advanced search JSON API: [archive.org/advancedsearch.php](https://archive.org/advancedsearch.php)
9. Android — `MediaMetadataRetriever` / `METADATA_KEY_DURATION`: [developer.android.com/reference/android/media/MediaMetadataRetriever](https://developer.android.com/reference/android/media/MediaMetadataRetriever#METADATA_KEY_DURATION)
10. FFmpeg — metadata/chapter format (`ffmetadata`): [ffmpeg.org/ffmpeg-formats.html#Metadata-1](https://ffmpeg.org/ffmpeg-formats.html#Metadata-1)

### Local code references

- `app/src/main/python/sleeve_audible.py:36-47` — product DTO, runtime minutes, narrators
- `app/src/main/python/sleeve_audible.py:65-83` — response groups and `products[0]`
- `app/src/main/python/sleeve_spotify.py:333-341` — `_LONG_FORM_DECOR_RE` strips `book N`
- `app/src/main/python/sleeve_spotify.py:371-375` — decoration stripping
- `app/src/main/python/sleeve_spotify.py:455-470` — duration score saturates at 25
- `app/src/main/python/sleeve_spotify.py:498-523` — long-form flag and 25 % floor
- `app/src/main/python/sleeve_spotify.py:529-552` — name floor and long-form weights
- `app/src/main/java/com/junkfood/seal/util/DownloadUtil.kt:164-183` — query builders
- `app/src/main/java/com/junkfood/seal/util/DownloadUtil.kt:283-365` — resolve/rank/auto-select/fetch
- `app/src/main/java/com/junkfood/seal/util/DownloadUtil.kt:332-333` — `ytmsearch5` / `ytsearch10`
- `app/src/main/java/com/junkfood/seal/download/MatchOps.kt:13-37` — 75/15 flag and post-batch review
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:804-826` — `ReadyWithInfo` trigger
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:656-682` — scheduler
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:348-416` — swap path
- `app/src/main/java/com/junkfood/seal/download/Task.kt:100-166` — metadata track and download states
- `app/src/main/java/com/junkfood/seal/download/BatchOps.kt:47` — unfinished predicate
- `app/src/main/java/com/junkfood/seal/ui/page/downloadv2/MatchReviewSheet.kt:117-131` — post-hoc review list
- `app/src/main/java/com/junkfood/seal/ui/page/downloadv2/DownloadPageV2.kt:812` — sheet invocation
- `app/src/main/res/values/strings.xml:482-485` — current review copy

---

## Appendix A. Reproduction notes

The spot check used yt-dlp `2026.10.26` with `extract_flat`, `skip_download`; queries:

```
ytsearch30:"<title>" <author> audiobook
ytsearch20:"<title>" <author> audiobook part 1
ytsearch20:"<title>" <author> audiobook part 2
```

The ranker was the repo’s `sleeve_spotify.rank_candidates` with `strictness=1`, `top_n=3..30`, and the Audible `authors` as `artist`. Runtime references came from `runtime_length_min` (catalog) and `runtime_length_ms` (content chapter endpoint). The 40-row landscape sample and the 9-book ranker sample are recorded in the working notes; they are not committed as fixtures yet (see §11.4).

**Caveat:** YouTube search results are personalized/regional and change over time. The numbers in this document are a 2026-11-11 spot check, not a stable benchmark. The gate thresholds are designed to be robust to the observed variation (correct 100.0–100.6 %, wrong 33.6–76.3 %), not to fit one day’s results.
