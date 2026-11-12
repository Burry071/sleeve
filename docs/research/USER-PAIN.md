# Sleeve — User Pain & Feature-Request Mining

Date: 2026-11-11
Scope: Android audiobook downloaders, yt-dlp front-ends, audiobook library tools, and Sleeve's own public issue tracker.
Method: 13 targeted web searches (Reddit, GitHub issues, vendor docs) + full-text reads of 12 primary pages + local code grounding on `HEAD ba79e156` (v2.1.0-beta.3).
No code was changed. No piracy/DRM material was collected; all product proposals below stay on the verification/UX side of the line.

**Status note (2026-11-12), so §5 is not read as still open:** shipped since this research —
- **5.1 post-download duration gate** → `AudioVerify.withinTolerance` + `Completed.warning` (empty on pass); the card shows the warning instead of a silent success.
- **5.5 manual override** → the review sheet accepts a user-pasted YouTube URL for a held book and runs it through the normal swap/fetch path.
- **Held-book review** → `MatchResult.held` / `MatchPhase.Held`: a gate hold is reviewable even when the ranker was confident (`MatchOps.reviewable`).

Still open: 5.2 review *before* the download (the entry still waits for the batch to finish), 5.3 completeness receipt, 5.4 stall watchdog and honest resume, 5.6 orphan/duplicate reconciliation. Line numbers in §3 and §5 refer to `ba79e156` and may have moved.

---

## 0. TL;DR

The strongest evidence is not from Reddit — it is Sleeve's own issue **#4**: a user reported that a shared Audible link *showed the correct title and length* but downloaded a different YouTube video. The maintainer confirmed the mechanism in-thread. That single report reframes the whole category: **downloaders verify bytes, not books.** Everything else in this document is secondary to that.

---

## 1. Evidence grading used below

| Tier | Meaning | How to treat it |
|---|---|---|
| **A — Primary, first-party** | Sleeve's own issue tracker, maintainer replies, current repo code | Highest. Direct evidence about *this* product. |
| **B — Primary, third-party** | Upstream GitHub issues (yt-dlp, YTDLnis, Audiobookshelf app), user-authored bug reports with reproduction steps | Strong. Same failure class, different codebase. |
| **C — Snippet-level secondary** | Reddit threads located via search, quoted from the result snippet without a full read | Directional only. Counted once, never load-bearing alone. |
| **D — Inference** | My own judgment on frequency/severity, or mechanism guesses | Labelled explicitly. Never presented as a finding. |

Search-snippet quotes are marked `[snippet]`. Full-text reads are marked `[read]`. Anything I could not open is not quoted.

---

## 2. Source table

| # | URL | Quote (verbatim) | Pain | Proposed feature | Tier |
|---|---|---|---|---|---|
| 1 | https://github.com/Burry071/sleeve/issues/4 | "Tried downloading audible content and it showed the correct title and length but then for soem reason it fetched a youtube video with different content" … "No errors shown" `[read]` | Wrong audio under correct metadata; no error surfaced | Post-download duration gate + visible "what was matched" receipt | **A** |
| 2 | https://github.com/Burry071/sleeve/issues/4 (owner reply, 2026-11-10) | "When the top score lands on the wrong upload, the sheet still shows the right title and length — so the mismatch only shows up once the audio downloads." `[read]` | Detection happens *after* a multi-hour download | Verify the delivered file, not the candidate list | **A** |
| 3 | https://github.com/Burry071/sleeve/issues/4 (reporter, 2026-11-10) | "I was just going to comment that I reproduced it on several audible links (all I have tired)." `[read]` | Not a one-off link; systematic | Same | **A** |
| 4 | https://github.com/Burry071/sleeve/issues/14 | "the old audio stays on disk with no task pointing at it, so it never shows in the app and cannot be removed from it." `[read]` | Orphaned duplicate files after failed swap | Dedupe/reconcile pass + "found on disk, not in queue" surfacing | **A** |
| 5 | https://github.com/Burry071/sleeve/issues | Issue list shows "Issues 2 (2)" with exactly one closed user-reported issue (#4, labelled `bug`) `[read]` | Public feedback volume is tiny | — (calibration only: do not over-weight Reddit volume for a product this young) | **A** |
| 6 | https://github.com/advplyr/audiobookshelf-app/issues/1479 | "Sometimes, especially with a unreliable connection to the server (mobile data/bad wifi), single files will get stuck when trying to download a full audiobook." `[read]` | Stall under bad network, mid-file | Stall watchdog + per-file retry | **B** |
| 7 | same, comment by pvince | "Does restarting the app and trying to download the "stuck" book continue from where the download failed? — Nope, it starts over at the beginning." (single file book, ~1 GB) `[read]` | No resume; full re-download | Persist partial + resume | **B** |
| 8 | same, comment by DownloadableFox | "There's never a message telling the user the download has failed." `[read]` | Silent failure | Explicit failure state + reason | **B** |
| 9 | same, comment by damccull | "it loses all progress. This also makes me wonder if I now have partially completed downloads taking up space somewhere on my device with random names I'll never identify." `[read]` | Hidden partials eat storage; unfindable | Storage accounting + orphan sweep | **B** |
| 10 | https://github.com/yt-dlp/yt-dlp/issues/15227 | "Persist download queues across sessions - If yt-dlp is interrupted or the system restarts, there's no way to resume pending downloads" `[read]` | Queue does not survive process death | Durable queue (Room-backed, already the case in Sleeve — verify) | **B** |
| 11 | same | "Risk re-downloading already completed videos (unless using `--download-archive`)" `[read]` | Duplicate downloads on retry | Download archive / dedupe keyed on source id | **B** |
| 12 | https://github.com/deniscerri/ytdlnis/issues/1304 | "paste a URL → nothing happens, no error, no format list." `[read]` | Silent no-op on paste | Pre-flight validation with explicit reason | **B** |
| 13 | same | "`UNIQUE constraint failed: downloads.id` … The queue-ordering code renumbers download row IDs using temporary negative IDs." `[read]` | Queue reorder corrupts rows | (Design note: Sleeve's batch state is in-memory + Room; check reorder path for the same class of bug) | **B** |
| 14 | https://github.com/JunkFood02/Seal/issues/2675 | "Audio or video extraction and download failed." with an empty "To Reproduce" and no logs attached `[read]` | Error reports carry no diagnostic value | One-tap log export attached to the task | **B** |
| 15 | https://github.com/JunkFood02/Seal/issues/1248 | "Unable To Download Any Audio from Youtube" `[snippet]` | Core audio path breaks when yt-dlp drifts | Extractor-health check + pinned nightly channel | **C** |
| 16 | https://github.com/androidx/media/issues/1712 | "Occasionally one or more downloads will get stuck in the DOWNLOADING state and never resume, while others will complete just fine" — **closed by reporter as "a blocked thread on my side"** `[read]` | Stuck-state symptom exists in the wild, but this instance was self-inflicted | Cite only as symptom vocabulary, not as platform proof | **B (weak)** |
| 17 | https://www.reddit.com/r/NewPipe/comments/vnxse7/ | "I have to download them separately when I want to listen to the next episode of an audiobook, not horrible but it can be tedious" `[read]` | No multi-select / no part grouping | Part/playlist batch as first-class object | **C+** |
| 18 | https://www.reddit.com/r/howto/comments/2pqhi2/ | "otherwise you'll have to do each one individually" … "rename the individual files … then i re-tag the files so that their meta properties are correct/same" `[read]` | Manual rename/retag after download | Ordered naming + embedded metadata + manifest | **C+** |
| 19 | https://www.reddit.com/r/audiobookshelf/comments/1nbilv4/ | "one book \"should\" be 40chapters, but the mp3s are split into 526 different mp3s" `[read]` | Part explosion makes the book unusable as a book | Coverage receipt: N parts, total runtime | **C+** |
| 20 | https://www.reddit.com/r/audiobookshelf/comments/1gzpn87/ | "it finds the books but it just sits there and doesn't match them almost all the books have enough metadata to match" `[read]` | Auto-match fails at scale; manual per-book fixing | Bulk review queue with confidence sort | **C+** |
| 21 | https://www.reddit.com/r/audiobookshelf/comments/1exs1k7/ | "My download client will fetch it, but readarr will place it in the wrong book!" `[snippet]` | Wrong-bucket placement downstream | Filename/ID scheme stable enough to survive import | **C** |
| 22 | https://www.reddit.com/r/audiobookshelf/comments/1tyxav0/ | "identify cases where the scraper matched the wrong book and the metadata no longer corresponds to the folder's actual contents" `[snippet]` | Metadata drift after the fact | Receipt that records the match at download time | **C** |
| 23 | https://www.reddit.com/r/audiobookshelf/comments/1t7fg8z/ | "Does anyone else spend more time cleaning audiobook files" `[snippet]` | Cleanup tax after every batch | Correct-first-time metadata | **C** |
| 24 | https://www.reddit.com/r/PleX/comments/10wrasa/ | "books that matched wrong and it was a manual process to fix it all" `[snippet]` | Bulk wrongness is expensive to unwind | Batch review before download | **C** |
| 25 | https://www.reddit.com/r/foss/comments/1w72sd5/ | "Long videos (over 55 min) auto split into Part 1 / Part 2 tracks" `[snippet]` | A competitor already ships part-splitting as a headline feature | Table stakes: parts are computed, not accidental | **C** |
| 26 | https://www.reddit.com/r/software/comments/1htilb2/ | "The playlist has around 200 videos." `[snippet]` | Large playlists are normal, not edge case | Pre-flight size/time estimate | **C** |
| 27 | https://www.reddit.com/r/truespotify/comments/1qcmn3h/ | "Audio book no longer split into chapters?" `[snippet]` | Source-side chapter structure is unstable | Never trust source chapter count; derive from audio | **C** |
| 28 | https://www.reddit.com/r/WoT/comments/1kxoanz/ | "I now get a note that \"Sorry, that's currently not available …\"" (mid-book) `[snippet]` | Source disappears mid-series | Detect + report missing installments | **C** |
| 29 | https://www.reddit.com/r/truespotify/comments/1uyh6r8/ | "Audiobook chapters repeating" `[snippet]` | Source-side duplication | Dedupe by content hash | **C** |
| 30 | https://www.reddit.com/r/qBittorrent/comments/xtzrk3/ | "Downloads won't resume after running out of disk space" `[snippet]` | Disk-full leaves dead transfers | Pre-flight free-space check, resumable after cleanup | **C** |
| 31 | https://www.reddit.com/r/Xiaomi/comments/qh5y8z/ | "I tried downloading it again, it's downloaded as a duplicate file \"(1)\"" `[snippet]` | Re-download duplicates instead of reusing | "Already have this" detection | **C** |
| 32 | https://www.reddit.com/r/AndroidQuestions/comments/1ck2yga/ | "I have to go into my downloads folder, find the file, and open it manually." `[snippet]` | Finding the artifact is its own task | In-app "open file / open folder" affordance | **C** |
| 33 | https://github.com/advplyr/audiobookshelf-app/issues/1970 | "Downloads are broken for 0.14.0-beta" `[snippet]` | Beta regressions break the whole download path | Beta channel discipline / rollback | **C** |
| 34 | https://kitedownloader.app/blog/video-downloader-stopped-working-android | "Extractor rot, dead builds, login walls and Wi-Fi-only switches" `[snippet]` (vendor blog — commercial interest, treat as SEO content) | Whole class of apps dies when extraction breaks | Health check, not more sources | **D+** |

Count: **27 distinct URLs** (34 rows, several share a URL). 12 pages read in full, 15 rows snippet-level, plus local code citations in §3.

---

## 3. Local code grounding (what Sleeve already does)

This matters because three of the "obvious" features are already built, and recommending them again would waste a cycle.

| Fact | Evidence |
|---|---|
| Matching already scores **duration**, weighted 0.4 name / 0.35 artist / 0.25 duration, with a duration veto that rejects implausible uploads | `app/src/main/python/sleeve_spotify.py:306-310`, `:437-485` |
| Audible runtime is converted to `durationMs` and fed into the ranker | `app/src/main/python/sleeve_audible.py:44-49`; `app/src/main/java/com/junkfood/seal/util/DownloadUtil.kt:301-312` |
| Low-confidence picks are already flagged and reviewable, on a 0–100 score with a 75 floor and 15-point gap rule | `app/src/main/java/com/junkfood/seal/download/MatchOps.kt:16-22` |
| **But review only appears after the batch has finished** — `reviewVisible` requires `unfinished == 0` | `MatchOps.kt:32-34` |
| Completed files are **probed** for duration — then the value is only used to fill the card's display fields. Nothing compares it to the expected runtime | `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:619-643` |
| "Resume" resets task state (`restartImpl` → `ReadyWithInfo` / `Idle`); `resumeBatch` simply restarts each resumable task. No persisted byte offset, no partial accounting | `DownloaderV2.kt:1025-1032`, `:84-86` |
| Playlist entries already become one task per entry, tagged with a batch id | `app/src/main/java/com/junkfood/seal/download/TaskFactory.kt:84-105` |

**Consequence for the roadmap:** candidate scoring is not the weak point. The weak point is that nothing checks *the artifact that actually landed on disk* against *the book the user asked for*, and nothing represents "a book made of 13 parts" as a single thing that can be complete or incomplete.

---

## 4. Ranked pain list

Scoring: Frequency 1–5 (how many independent sources / how often it recurs), Severity 1–5 (user cost when it hits). Score = F × S. **Frequency and severity are my judgment (D).** The evidence column is not.

| Rank | Pain | F | S | Score | Why it hurts | Primary evidence |
|---|---|---|---|---|---|---|
| **1** | **Wrong book / wrong audio, discovered only after the download** | 4 | 5 | **20** | Irreversible time cost (a 13-hour audiobook is a multi-hour download), and the failure is *invisible at review time* because metadata comes from the good source while audio comes from the bad one | #1, #2, #3 (Sleeve's own bug); #20–#24 (same class downstream) |
| **2** | **Stalls and silent death; "resume" restarts from zero** | 4 | 5 | **20** | Users lose hours and cannot tell whether the app is working. One reporter's ~1 GB single-file book restarted from the beginning after a stall | #6, #7, #8, #9, #10, #14 |
| **3** | **A book arrives as 8–500 loose parts with no notion of completeness** | 5 | 4 | **20** | The download "succeeds" but the book is unusable or half-missing; no signal that part 9 of 13 never arrived | #17, #18, #19, #25, #26 |
| **4** | **Duplicates and orphans: files on disk that no task owns** | 3 | 4 | 12 | Wastes storage, defeats dedupe, and is invisible in-app; users then re-download the same book | #4 (Sleeve), #11, #31, #30 |
| **5** | **Batch control: no pre-flight, no bulk retry granularity** | 4 | 3 | 12 | You commit 200 items blind, then learn about failures one row at a time | #26, #10, #12, #20 |
| **6** | **Source-side gaps: edition/narrator mismatch, chapters removed mid-series** | 3 | 3 | 9 | The "right" book is not the edition the user owns; series holes appear later | #19, #27, #28, #29 |
| **7** | **Error opacity: no reason, no log, no next step** | 4 | 2 | 8 | Turns a 30-second fix into a support thread; also blocks the maintainer from fixing real bugs | #14, #8, #12 |

Notes on the top three, since they tie at 20:

- **#1 ranks first on trust, not volume.** One confirmed report on a product with two open issues is thin frequency evidence — but the failure is unrecoverable *and undetectable by the user* until playback. Trust damage from one wrong 13-hour download exceeds a hundred cosmetic bugs. The owner's own reply (#2) confirms the mechanism, which is why this sits above better-attested pains.
- **#2 is the best-attested.** Six independent reports across two codebases, including a reproduction recipe and a "starts over at the beginning" confirmation. If ranking purely on evidence volume, this is #1.
- **#3 is the most under-served relative to how often it happens.** Every long audiobook is multi-part; no surveyed app models "book = set of parts, complete or not".

---

## 5. What to build next

Ordered by (user pain relieved) ÷ (engineering cost), and constrained to what is legal and in-scope for Sleeve.

### 5.1 Post-download duration gate — the single highest-leverage change
`enrichWithCompletedFile` already measures the delivered file's duration (`DownloaderV2.kt:619-643`). Today the number is decorative. Compare it against the expected runtime (Audible `durationMs`, or the sum of the batch's parts) and:
- within tolerance → mark verified;
- outside tolerance → keep the file, but set a `suspect` flag on the task and surface it in the same review sheet that already exists for low-confidence picks;
- wildly off → refuse to mark the task complete and say why.

This is the only proposal that actually catches Sleeve #4's failure mode, because candidate scores were fine — the *file* was wrong. Cheap: no new dependency, reuses `MediaMetadataRetriever`, reuses `MatchOps` flag plumbing.

### 5.2 Bring review *before* the download, not after
`MatchOps.reviewVisible` requires `unfinished == 0` (`MatchOps.kt:32-34`), so a 13-part batch downloads all 13 parts before the user is told two picks look shaky. Move the same review UI to a pre-flight step: show flagged picks, candidate list, and per-item runtime, with "download the confident ones now / review the rest". The UI already exists (`MatchReviewSheet`); this is a sequencing change, not a new screen.

### 5.3 A book-level completeness receipt
Introduce a lightweight manifest per batch: title, source id, matched candidate URL, expected runtime, list of parts with their measured durations, total, and a `complete | incomplete | suspect` verdict. Store it next to the files as JSON and show it as the batch's summary card. This is the feature nobody in the surveyed set ships — every tool reports *transfers*, none reports *the book*. It also gives #19's 526-MP3 case a human-readable answer, and gives #4/#22 a record of what was matched at download time.

### 5.4 Stall watchdog + honest resume
Two separate problems, both cheap relative to re-downloading:
- **Watchdog:** if a task's progress does not advance for N minutes, transition to a visible `Stalled` state with a retry action, instead of sitting in `Downloading` forever (#6, #8).
- **Resume:** today `restartImpl` resets to `ReadyWithInfo` (`DownloaderV2.kt:1025-1032`) and `resumeBatch` restarts (`:84-86`). Even before byte-level resume exists, persist and display "resuming — 62% already on disk" so a retry is not blind, and reuse the partial when the extractor supports it.

### 5.5 Manual override: "use this link instead"
The owner's #2 diagnosis is explicit: no way to override the automatic choice. The beta.3 review sheet now offers *candidate selection*, which covers most of it — the remaining gap is the case where the correct upload is **not in the candidate list at all** (wrong search query, region-locked, metadata in another language). A "paste a YouTube URL for this book" field turns an unfixable failure into a 10-second fix. Keep it explicit and user-supplied; do not build a general search-and-grab surface.

### 5.6 Orphan/duplicate reconciliation
Sleeve #14 describes files that exist, consume storage, and are unreachable from the UI. A startup sweep comparing files in the audiobook directory against the task DB — offering "adopt / delete / ignore" — closes #4 and #9 in one pass, and gives the dedupe story (#11, #31) a foundation.

### Deliberately *not* on this list
Merging parts into a single chaptered `.m4b`. It is the loudest request in the audiobook community (#18, #19), but it is a media-processing project (chapter detection, silence trimming, ffmpeg pipeline, failure modes on 13-hour inputs), and the cheap 80% is ordered, correctly-named, metadata-tagged parts plus the receipt from 5.3. Audiobookshelf and similar players ingest multi-file books fine. Revisit only if receipts show users still exporting to third-party mergers.

---

## 6. Feature-request shortlist (what users actually asked for, mapped)

| User ask (paraphrase) | Source | Mapped feature |
|---|---|---|
| "Let me pick the right upload" | #1, #2 | 5.2 pre-flight review, 5.5 manual link |
| "Tell me it failed, and why" | #8, #14 | 5.4 watchdog, error reason on task row |
| "Continue where it stopped" | #7, #9 | 5.4 resume |
| "Give me the whole book, not 13 files" | #17, #19 | 5.3 receipt, ordered parts |
| "Don't leave junk on my phone" | #4, #9 | 5.6 reconciliation |
| "Don't re-download what I already have" | #11, #31 | 5.6 + source-id dedupe key |

---

## 7. What I could not establish (open items)

1. **No Sleeve-specific volume data.** The public tracker has one user-reported issue (#4). Any statement like "Sleeve users mostly complain about X" would be invention. All frequency claims here are borrowed from adjacent products and should be re-tested against Sleeve's own telemetry/logs if they exist.
2. **Whether yt-dlp's own `--continue` currently rescues partial files in Sleeve's invocation.** I found no explicit `continuedl`/`--continue` handling in the Kotlin or Python layer (grep returned nothing relevant). yt-dlp's default behavior may still resume, but Sleeve's state machine discards the task's progress regardless. Needs a live test: kill a download at 60%, restart, watch the filesystem.
3. **Whether `restartImpl` deletes the partial file.** #14's ownership bug suggests leftovers are possible; the code path was not traced end to end. Verify with a failed-swap reproduction.
4. **Spotify-side matching quality.** `sleeve_spotify.py` ranks with duration, but no public report either confirms or denies Spotify→YouTube mismatch at audiobook length. The Audible case (#4) is proven; the Spotify case is inferred by analogy and should not be quoted as fact.
5. **Orion Store / Obtainium distribution complaints.** Searches surfaced only generic Obtainium documentation and an unrelated Orion Store comparison page — no Sleeve-specific update/install friction. Treated as an empty result, not a silent gap.

---

## 8. Method log (so the next run does not repeat it)

| Round | Query shape | Yield |
|---|---|---|
| 1 | `site:reddit.com Android audiobook downloader wrong book matched` | Audiobookshelf mismatch cluster (#20–#24) |
| 2 | `site:reddit.com YouTube audiobook downloaded as many parts one file` | Merge/part threads (#18, #19) |
| 3 | `site:reddit.com audiobook "part 1" "part 2" playlist` | Playlist batch (#17, #25, #26) |
| 4 | `Seal Android video downloader github issues stuck 90% resume` | Generic stall issues (#16), mostly not Seal |
| 5 | `site:github.com JunkFood02 Seal issues audio only m4a` | Seal #1248, #2675 (#14, #15) |
| 6 | `YTDLnis github issues queue failed resume` | #12, #13, yt-dlp #15227 (#10, #11) |
| 7 | `site:reddit.com Audible "wrong book" narrator edition` | Weak — mostly reading-recommendation noise |
| 8 | `Sleeve audiobook Android Orion Store Obtainium` | **Empty for Sleeve**; only Obtainium docs → led to direct repo inspection |
| 9 | `site:reddit.com Android downloader "can't find" duplicate files` | Findability (#31, #32) |
| 10 | `site:reddit.com Spotify audiobooks missing chapters` | Source gaps (#27–#29) |
| 11 | `site:github.com JunkFood02 Seal "no space" OR "stuck"` | Near-empty (1 result) → stopped this line |
| 12 | `site:reddit.com audiobookshelf duplicate files` | Near-empty → stopped |
| 13 | `github Burry071 sleeve issues` | **Sleeve repo found** → #1–#5, the best evidence in this document |

Stopped at 13 searches: rounds 11–12 produced no new evidence and further Reddit querying was returning the same ten threads. The decisive move was switching from general search to the product's own tracker — that is where a niche product's real pain lives.
