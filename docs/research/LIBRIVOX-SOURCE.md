# LibriVox as a source: what the API gives us and what we can honestly verify

**Status:** research only. No implementation is approved. This document exists so the
v1 scope decision (especially §7) can be made before any code is written.
**Date:** 2026-11-12
**Scope:** the LibriVox catalog API, its archive.org hosting, how a LibriVox book
would map onto an Audible request, and whether the phase-2 parts machinery can
carry chaptered books. YouTube matching and the existing Audible gate are
referenced but not redesigned here.
**Verification:** API shape, paging limits and the archive.org layout were probed
live on 2026-11-11/12. Items I could not confirm are marked **UNVERIFIED** and
must be re-checked before implementation.

---

## 0. Executive answer

**Recommendation.** Add LibriVox as a resolver *tier* between Audible and YouTube,
restricted to exact-metadata resolutions of public-domain audiobooks. Chaptered
books reuse the phase-2 parts machinery rather than a new download path. Anything
the source cannot prove — author, section count, runtime — is held for the user,
never auto-downloaded.

**In one sentence:** LibriVox is a high-precision, low-recall source; the whole
design is about refusing the 12 % of the catalog that would be a near-miss, because
its wrong answers (the *other* Austen, the abridged reader) look exactly like its
right ones.

**What it buys:** a legal, complete, per-chapter audiobook file set with declared
durations before any byte is spent — something YouTube cannot promise and Audible
does not host.

**What it costs:** coverage. 87 % of the catalog is English; there is no language
query parameter and no publication-year field, so "is this the book the user
asked for" is answered with title + author + language + runtime, not with an
edition identifier.

---

## 1. API shape

Base documentation page: `https://librivox.org/api/info`.

### 1.1 Endpoints

| Endpoint | Returns |
|---|---|
| `https://librivox.org/api/feed/audiobooks` | book records (the one we need) |
| `https://librivox.org/api/feed/audiotracks` | individual section records |
| `https://librivox.org/api/feed/authors` | author records |

All three accept `format=json`; without it the default is XML. JSON is the only
format we would parse.

### 1.2 Parameters (`audiobooks`)

| Param | Meaning | Notes |
|---|---|---|
| `id` | one or more LibriVox book ids | comma-separated |
| `since` | books added/updated after an epoch | for incremental sync, not for us |
| `author` | author name filter | free text, server-side |
| `title` | title filter | free text, server-side |
| `genre` | genre filter | — |
| `fields` | restrict returned fields | — |
| `extended=1` | add archive.org id and section list | **required for us** |
| `coverart=1` | add cover URLs | optional |
| `limit` | page size | default 50 |
| `offset` | paging start | there is **no `page` param**; page N = `offset=(N-1)*limit` |
| `sort_order` | `asc` / `desc` | — |
| `format` | `json` | required |

**There is no `language` parameter.** Passing one is silently ignored, not a 400 —
so a client that "filters by language" server-side gets the full English catalog
back and believes it filtered. Language is only on each record and must be
filtered client-side (§6).

### 1.3 Paging and limits

- Default page size is 50.
- The documented hard ceiling is **500 per request**, live since the 2026-09-26
  change described in the LibriVox blog post *"LibriVox API Update"*
  (16 Sep 2026). `limit=501` returns **HTTP 400**, not a clamped 500.
- The same post asks clients to leave **seconds between requests** and says a
  429 response is planned. Treat the API as single-flight with a minimum
  interval; do not fan out per-task requests.

**Implication:** we never enumerate the catalog. Every query is a targeted
`title`/`author` search with `extended=1`, one request per task, cached per
session by (title, author, language).

### 1.4 Response fields we would rely on

On each book record:

| Field | Use |
|---|---|
| `id` | stable book identity |
| `title` | mapping (title gate) |
| `language` | mapping (exact-language gate) |
| `num_sections` | chapter count; must match the section list |
| `totaltime` | human string, display only |
| `totaltimesecs` | numeric runtime — the only runtime we compare |
| `url_librivox` | attribution link shown on the card |
| `url_zip_file` | single zip of all sections, when present |
| `authors[]` | author gate |
| `url_iarchive` (extended) | archive.org identifier — how we find the files |
| `sections[]` (extended) | one entry per chapter |

Each `sections[]` entry carries `listen_url`, `playtime`, and `readers`.

**Parse defensively.** The API omits fields it has no value for (a book with no
zip has no `url_zip_file`; `readers` may be empty). A missing required field is a
mapping failure, not a default to zero.

---

## 2. Terms and attribution asks for apps

LibriVox recordings are dedicated to the public domain; the catalog exists so
other people can redistribute the audio. That is the point of the source and the
reason it is safe to download from.

What the source asks of an app in practice:

1. **Name the source before the download.** The card should say the book comes
   from LibriVox and link `url_librivox`. This is attribution, not decoration —
   users should know why this book appeared with no search.
2. **Credit the readers.** Reader names are in `sections[].readers`. They belong
   in the file metadata and in the task log, the same way the Audible path logs
   its chosen candidate.
3. **Do not imply endorsement.** "Powered by LibriVox" or a LibriVox logo inside
   the app suggests a partnership that does not exist. Text attribution + link
   only, unless permission is obtained.
4. **Do not mirror or bulk-copy the catalog.** Our use is per-request download of
   one book the user asked for. No catalog caching beyond the session, no bulk
   enumeration (§1.3), no redistribution of the API data.
5. **Respect the rate request.** The blog asks for spacing between requests;
   honoring it is a term of use in spirit even before 429s exist.

Exact wording of the copyright and attribution pages must be quoted at
implementation time, not paraphrased from memory. **UNVERIFIED:** the precise
attribution sentence LibriVox asks apps to display; confirm on
`librivox.org/pages/copyright` and the volunteer/forum etiquette pages before
writing the string.

---

## 3. File hosting — archive.org

Every LibriVox book is one archive.org item, identified by `url_iarchive`
(typically `<slug>_librivox`).

- **Metadata:** `https://archive.org/metadata/<identifier>` returns the file list
  with, per file: `name`, `format`, `length`, `size`. This is a cheap request —
  it can be made during resolution, before any media byte.
- **Download:** `https://archive.org/download/<identifier>/<file name>`. The
  `archive.org/download` form redirects to a rotating file server; use it, not a
  captured `ia######.us.archive.org` host, or downloads break when the host
  rotates.
- **Formats present:** 64 Kbps MP3, 128 Kbps MP3, VBR MP3, Ogg Vorbis, and for
  some books a single `.m4b`. Availability varies per book — never assume 128 Kbps
  exists.
- **Granularity:** chaptered books have one file per section; `url_zip_file`
  offers the whole book as a zip when the uploader made one.

**Implication for us:** download per-chapter MP3s (64 Kbps by default, 128 Kbps
when present and requested), tag each file with book, author and chapter number,
and never depend on a concatenated or zipped artifact. The zip is a convenience
for desktop users, not a download unit for a mobile queue that must survive
process death mid-book.

---

## 4. Per-book mapping — tier discipline

The resolver answers one question: *is this LibriVox record the book the user
asked for?* It answers from the record's own metadata, never by trusting the
search that found it.

### 4.1 Hard requirements (all must pass)

| Check | Rule |
|---|---|
| Title | normalized equality, or a title score at or above the phase-2 F1 gate (≥ 0.90) |
| Author | **exact author match** after normalizing initials, punctuation and name order |
| Language | exact match between the requested language and the record's `language` (§6) |
| Structure | `num_sections` equals `sections.size`; every section has a numeric `playtime` |
| Runtime | sum of `sections[].playtime` equals `totaltimesecs` within tolerance (§5) |

### 4.2 Why author is a hard gate, not a score

One Austen never resolves to another. *Pride and Prejudice*, *Persuasion* and
*Emma* share an author and share vocabulary; a title score alone will happily
pick the wrong one when the requested title is a subtitle or a series name. Two
readers of the same book are also a real ambiguity — the mapping must not pick
one silently when more than one record ties.

### 4.3 Unverifiable is never promoted

These outcomes park the task in a picker, they do not download:

- author missing or not equal after normalization;
- more than one record passes all checks (two readers, two editions);
- section list absent while `num_sections` is non-zero;
- declared durations that do not sum;
- a title match with no author match at all — this is the "different edition"
  trap and is the most common false positive.

The picker is the existing `MatchGateSheet` shape: "nothing has been downloaded
yet", candidate rows with runtime and reader, a `Choose a book` action, and a
confirmed override. "No LibriVox copy" is a state with a reason, not an error.

---

## 5. Runtime — total vs per-chapter, and what the gate can verify

`totaltime` is a display string (`"7:23:41"`); `totaltimesecs` is the number to
compare. Per-chapter durations come from `sections[].playtime`.

**Verifiable before any media byte:**

- declared chapter count and each chapter's declared length;
- the sum of chapter lengths against `totaltimesecs`;
- per-file `length` and `size` from the archive.org metadata call;
- consistency between the API's section count and the item's file count.

**Not verifiable before download:** the actual audio content, whether a file was
truncated at upload, and reader identity against the requested edition (there is
no edition to compare — LibriVox is one reading per record).

**After download:** probe each file's real duration and compare to its declared
length; a mismatch marks the task `Incomplete` rather than `Completed`, using the
same post-download safety net as the Audible path.

**Tolerance:** the phase-2 numbers (`max(120 s, 2 %)` for a single duration,
`max(180 s, 2 %)` for a part sum) were sized for matching *different* uploads of
the same book. LibriVox chapters are declared, not inferred, so a tighter
per-section tolerance is probably right — but that is a test-first decision, not
a guess. **Decision for review:** keep phase-2 tolerances in v1, tighten only if
the declared-vs-actual measurement says they are too loose.

---

## 6. Language coverage vs our language gate

Catalog counts, 12 Nov 2026 (total 21,945 books):

| Language | Books | Share |
|---|---|---|
| English (`eng`) | 19,180 | 87.4 % |
| German (`deu`) | 848 | 3.9 % |
| Spanish (`spa`) | 564 | 2.6 % |
| French (`fre` / `fra`) | 315 | 1.4 % |
| Dutch (`nld`) | 210 | 1.0 % |
| Multilingual (`mul`) | 180 | 0.8 % |
| Portuguese (`por`) | 107 | 0.5 % |
| Italian (`ita`) | 89 | 0.4 % |
| Russian (`rus`) | 76 | 0.3 % |
| Japanese (`jpn`) | 37 | 0.2 % |
| Polish (`pol`) | 31 | — |
| Latin (`lat`) | 16 | — |
| Swedish (`swe`) | 13 | — |

Notes that matter more than the counts:

- **The API cannot filter by language** (§1.2). Filtering is client-side, on the
  record's `language` field.
- **The app has no content-language enum.** `MetadataTrack.language` is whatever
  the provider sent; the only language setting in Sleeve is the UI locale
  (`LanguageSettings.kt`). So the LibriVox gate must compare against the requested
  book's language carried on the task, mapped through a small exact table
  (`english` ↔ `eng`, `french` ↔ `fre`/`fra`, …).
- **`mul` must never pass an exact-language gate.** A multilingual record is not
  a match for any single-language request.
- **Consequence:** v1 is realistically English-first. Non-English requests can
  already profit — German and Spanish have real catalogs — but they need the
  mapping table in §4.1, not a default.

---

## 7. The chapter = parts synergy — and what it decides for v1

A LibriVox book *is* a parts plan: N declared parts, each with a declared length,
summing to a declared total, from one source. That is the same shape phase 2
already built for split Audible books.

**What phase 2 already provides and can be reused as-is:**

- `MatchResult.parts` (a `List<MatchCandidate>`) and the part-sum gate;
- the completeness receipt computed after download;
- per-part tagging and one batch card with `Download all N parts`;
- the pattern where a failed part marks the batch `Incomplete` instead of
  silently completing.

**What differs — and must NOT be reused blindly:**

- candidate identity is an archive.org file, not a YouTube URL;
- there is no channel-consistency check, because there is one item identifier;
- there is no search ranking — the parts are declared, not discovered;
- the "is this the whole book" question is answered by the API's section list,
  not by summing discovered uploads.

**Scope decision for review:**

1. **Preferred:** represent a LibriVox book as a parts batch whose candidates are
   archive.org files. Reuse the sum gate, the receipt and the batch card. This
   requires `MatchCandidate` to accept a non-YouTube URL and the batch enqueue to
   accept explicit part URLs — both are additive.
2. **Fallback:** enqueue N independent tasks in one batch. Correct, less UI, no
   touch to match types.
   Do **not** concatenate parts into one file, and do **not** write a second
   download path for LibriVox.

If (1) cannot be done without changing serialized fields that beta.4 backups
depend on, take (2).

---

## 8. Resolver order sketch

```
Audible metadata (existing)
        │
        ▼
Language + author + PD plausibility
        │
        ├── LibriVox tier (exact-metadata only) ──► accepted → parts batch
        │      miss/failure → log reason, continue
        ▼
YouTube tier (existing gate: duration + title + language + junk veto)
```

- **Why after Audible, not before:** LibriVox only covers public-domain works, and
  Audible metadata is the version the user actually asked for. Trying LibriVox
  first would spend a request on every in-copyright book and produce near-miss
  titles for exactly the requests it cannot serve.
- **What triggers the tier:** the book resolved to an author+title+language that
  can be matched exactly. A miss costs one targeted API call, cached for the
  session.
- **Failure handling:** API down or 4xx → skip the tier, continue to YouTube, log
  it in `TaskAuditLog`. Never fall back to "download the first title search hit".
- **Ordering with the existing gate:** the LibriVox tier runs *before* any YouTube
  search, so a clean LibriVox hit never pays the YouTube search cost. A LibriVox
  hold (ambiguous or unverifiable) does not silently continue to YouTube — the
  user chose the book, and the picker is where they choose the source.

---

## 9. Open questions for review

1. Is the LibriVox tier v1 scope at all, or does it wait until the beta.5 arc
   (#19, #18) closes?
2. Parts-batch reuse (§7 option 1) or N independent tasks (§7 option 2)?
3. Language mapping table: which targets does v1 claim — English only, or
   English + German + Spanish?
4. Attribution string: exact wording must be confirmed against the LibriVox
   pages before it is written into strings.
5. Rate-limit budget: one serialized request per task with a fixed minimum
   interval; confirm the interval against the blog post's wording.

---

## References

- LibriVox API documentation — `https://librivox.org/api/info`
- LibriVox catalog API — `https://librivox.org/api/feed/audiobooks`
- LibriVox blog, *"LibriVox API Update"* (16 Sep 2026) — 500-result ceiling live
  2026-09-26, 4xx above the ceiling, spacing between requests, 429 planned
- archive.org item metadata — `https://archive.org/metadata/<identifier>`
- archive.org download — `https://archive.org/download/<identifier>/<file>`
- Sleeve phase-2 record — `docs/research/AUDIBLE-RELIABILITY.md` (parts gate,
  completeness receipt, `MatchGateSheet`)
