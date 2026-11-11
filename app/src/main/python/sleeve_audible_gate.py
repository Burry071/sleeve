"""Pre-download identity gate for Audible audiobooks.

Precision first: an upload may start downloading only when it proves the same
title *and* a full-book duration, before any media byte is requested. A 0-100
ranker score is not a probability; this module is the deterministic check that
sits between a ranker pick and `ReadyWithInfo`.

Scope (phase 2 v1): one full-length video, or a complete contiguous part set
(YouTube's 12 h upload cap forces long books to be split) downloaded as
separate files - no concatenation. A book uploaded only in fragments that do
not form a complete set is held, never auto-downloaded.

Public surface (JSON in / JSON out, like sleeve_audible):

    gate_audible(track_json, candidates_json) -> '{"ok": true, "verdict":
        "pass"|"split"|"hold"|"no_source", "reason": ..., "candidate_id": ...,
        "ratio": ..., "best_id": ..., "best_ratio": ..., "parts": [...],
        "part_count": N}'

`parts` and `part_count` exist only on `split`; `parts` is ordered by part
number and holds {id,url,title,duration,part_index,channel} with `duration` in
whole seconds. `candidate_id` stays empty on `split`: no single upload is the
book, the set is.

`track_json` is a resolved Audible track: {"title","artist","album",
"durationMs",...}. `candidates_json` is the ranker's shortlist:
[{"id","url","title","uploader","channel","duration"}], with `duration` in
whole seconds and `durationMs` in milliseconds. The conversion happens exactly
once, at the boundary in `_expected_seconds`: a 1000x mix-up looks like a
duration mismatch and would hold every book.

Pure and stdlib-only: no I/O, no network, unittest-friendly.
"""

import json
import re
import unicodedata

# Uploads that are about a book, not a reading of it. Token-boundary matching
# is deliberate: the correct Atomic Habits upload sits on "JunkLoader", so a
# raw "junk" substring rule would veto the only correct candidate.
_JUNK_WORDS = (
    "summary",
    "recap",
    "explained",
    "review",
    "trailer",
    "reaction",
    "analysis",
    "sample",
    "excerpt",
    "preview",
    "scene",
    "asmr",
    "podcast",
)
_JUNK_PHRASES = ("sleep story", "full movie")
# Channels that re-host audio rather than publish the book; same idea as the
# music ranker's junk-channel list, widened for long-form.
_JUNK_CHANNEL_WORDS = _JUNK_WORDS + ("lyrics", "lyric", "karaoke", "nightcore")

_MARKETING_RE = re.compile(
    r"\b(?:full|complete)\s+audiobook\b"
    r"|\bunabridged\b"
    r"|\baudiobook\b"
    r"|\b(?:science\s+fiction|sci[\s-]*fi|epic|saga|fantasy|thriller|novel"
    r"|best[\s-]*seller)\b",
    re.I,
)
_NUMBER_WORDS = (
    "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
    "|first|second|third|fourth|fifth"
)
# Part markers are neutralized on both sides: "Book 1" is a series label as
# often as it is an internal part, so the duration gate must decide identity,
# not the wording. The Harry Potter sample is a correct 100.1% single upload
# titled "... Book 1"; it has to pass.
_PART_RE = re.compile(
    # "Part 1 of 2" / "Part 1/2" first: the shorter alternative would leave
    # the denominator behind as a stray title token.
    r"\bpart\s+\d{1,2}\s*(?:of|/)\s*\d{1,2}\b"
    r"|\bpart\s+(?:\d{1,2}|[ivxlcdm]{1,6}|" + _NUMBER_WORDS + r")\b"
    r"|\bbook\s+(?:\d{1,2}|[ivxlcdm]{1,6}|" + _NUMBER_WORDS + r")\b"
    r"|\bvol(?:ume)?\s*\.?\s*(?:\d{1,2}|[ivxlcdm]{1,6})\b"
    r"|\bchapter\s+\d{1,3}\b",
    re.I,
)

# Part numbers are read from the RAW title: `_normalize_title` deliberately
# strips the markers, which is exactly what makes the parts of one book group
# together ("Dune - Book One | Part I" and "Dune Part II" -> "dune").
_ROMAN_VALUES = {"i": 1, "v": 5, "x": 10, "l": 50, "c": 100, "d": 500, "m": 1000}
_ROMAN_PAIRS = {"iv": 4, "ix": 9, "xl": 40, "xc": 90, "cd": 400, "cm": 900}
_NUMBER_WORD_VALUES = {
    "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6,
    "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
    "first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5,
}
_NUMBER_TOKEN = r"(?:\d{1,2}|[ivxlcdm]{1,6}|" + _NUMBER_WORDS + r")"
_PART_MARKER_RE = re.compile(
    # "Part 1 of 2" / "Part 1/2" before the bare form: the shorter
    # alternative would stop at "Part 1" and drop the declared count.
    r"\bpart\s+(?P<part_index>" + _NUMBER_TOKEN + r")\s*(?:of|/)\s*"
    r"(?P<part_count>" + _NUMBER_TOKEN + r")\b"
    r"|\bpart\s+(?P<part_only>" + _NUMBER_TOKEN + r")\b"
    r"|\(\s*(?P<frac_index>\d{1,2})\s*/\s*(?P<frac_count>\d{1,2})\s*\)"
    r"|\bbook\s+(?P<book_index>" + _NUMBER_TOKEN + r")\b"
    r"|\bvol(?:ume)?\s*\.?\s*(?P<vol_index>" + _NUMBER_TOKEN + r")\b"
    r"|\bchapter\s+(?P<chapter_index>\d{1,3})\b",
    re.I,
)
# Lower priority wins: "Dune - Book 1 | ... PART II" is part 2 of the book,
# not book 2, and a chapter number is the weakest signal of the four.
_PART_FAMILIES = (
    ("part_index", "part_count", 0),
    ("part_only", None, 0),
    ("frac_index", "frac_count", 0),
    ("book_index", None, 1),
    ("vol_index", None, 1),
    ("chapter_index", None, 2),
)

# Part-set policy (research 9.2). A part is a real upload, not a chapter
# fragment; 12 h is YouTube's upload cap, and 20 parts is the point where the
# set is a chapter-level playlist that needs a human, not 20 queue entries.
_MIN_PART_SECONDS = 60.0
_MAX_PART_SECONDS = 12 * 3600.0
_MAX_PARTS = 20
# A set can fail several ways at once; the lowest number is the reason the
# user is told, because it is the one they can act on.
_PART_FAILURES = {
    "duplicate": 1,
    "channels": 2,
    "languages": 3,
    "junk": 4,
    "language": 5,
    "size": 6,
    "length": 7,
    "channel": 8,
    "duration": 9,
    "missing": 10,
    "sum": 11,
    "narrator": 12,
    "edition": 13,
}

_NARRATED_RE = re.compile(r"\bnarrated\s+by\b.*$", re.I | re.S)
_NARRATOR_RAW_RE = re.compile(
    r"\b(?:narrated|read)\s+by\s+(.+?)(?:\s*[\(\[]|\s*[-\u2013\u2014|]\s*|$)",
    re.I | re.S,
)
_EDITION_RAW_RE = re.compile(r"\b(abridged|unabridged|dramatized|full\s+cast)\b", re.I)
_BRACKET_RE = re.compile(r"\([^)]*\)|\[[^\]]*\]|\{[^}]*\}")
_LEADING_NUMBER_RE = re.compile(r"^\s*\d{1,3}\s*[)\].:\-\u2013\u2014]\s*")
_NON_WORD_RE = re.compile(r"[^\w\s]")
_ARTICLES = frozenset(("a", "an", "the"))

# Audible returns a language name; YouTube candidates usually carry the
# language in the raw title ("Dune (German)", "Hörbuch deutsch"). Scan the raw
# title before bracket stripping, because the normalizer deliberately removes
# the brackets for title scoring.
_LANGUAGE_ALIASES = {
    "english": "en", "en": "en",
    "german": "de", "deutsch": "de", "de": "de",
    "spanish": "es", "espanol": "es", "español": "es", "es": "es",
    "french": "fr", "francais": "fr", "français": "fr", "fr": "fr",
    "italian": "it", "italiano": "it", "it": "it",
    "portuguese": "pt", "portugues": "pt", "português": "pt", "pt": "pt",
    "russian": "ru", "русский": "ru", "ru": "ru",
    "hindi": "hi", "hi": "hi",
    "urdu": "ur", "ur": "ur",
    "japanese": "ja", "日本語": "ja", "ja": "ja",
    "chinese": "zh", "mandarin": "zh", "中文": "zh", "zh": "zh",
    "polish": "pl", "polski": "pl", "pl": "pl",
    "dutch": "nl", "nederlands": "nl", "nl": "nl",
    "turkish": "tr", "türkçe": "tr", "turkce": "tr", "tr": "tr",
}
_LANGUAGE_MARKERS = (
    ("en", ("english",)),
    ("de", ("german", "deutsch")),
    ("es", ("spanish", "espanol", "español")),
    ("fr", ("french", "francais", "français")),
    ("it", ("italian", "italiano")),
    ("pt", ("portuguese", "portugues", "português")),
    ("ru", ("russian", "русский")),
    ("hi", ("hindi",)),
    ("ur", ("urdu",)),
    ("ja", ("japanese", "日本語")),
    ("zh", ("chinese", "mandarin", "中文")),
    ("pl", ("polish", "polski")),
    ("nl", ("dutch", "nederlands")),
    ("tr", ("turkish", "türkçe", "turkce")),
)


def _normalize_language(value):
    return _LANGUAGE_ALIASES.get(_fold(value).strip(), "")


_LANGUAGE_QUALIFIER_RE = re.compile(
    r"\b(?:edition|version|audiobook|audio\s+book|language|narration|narrated"
    r"|h(?:ö|o)rbuch|h(?:ö|o)rspiel)\b",
    re.I,
)
_BRACKET_SEGMENT_RE = re.compile(r"\(([^)]*)\)|\[([^]]*)\]|\{([^}]*)\}")


def _narrator_key(raw_title):
    """Narrator named by the upload, if any; unknown is an empty wildcard."""
    match = _NARRATOR_RAW_RE.search(raw_title or "")
    if not match:
        return ""
    return " ".join(_fold(match.group(1)).replace("'", "").split())[:80]


def _edition_key(raw_title):
    """Explicit edition marker; unknown is an empty wildcard."""
    match = _EDITION_RAW_RE.search(raw_title or "")
    if not match:
        return ""
    return " ".join(_fold(match.group(1)).split())


def _language_declarations(raw_title):
    """Language codes explicitly declared by a title, not nationality words.

    "The Dutch House" is an English book; "(German)" or "German edition" is a
    declaration. Only bracketed segments, "<language> edition/audiobook/...",
    "in <language>", and a trailing "| German" tag count.
    """
    raw = _fold(raw_title)
    found = set()
    segments = [next(g for g in match.groups() if g) for match in _BRACKET_SEGMENT_RE.finditer(raw)]
    for segment in segments:
        for code, markers in _LANGUAGE_MARKERS:
            if any(
                (re.search(r"\b" + re.escape(marker) + r"\b", segment) if marker.isascii() else marker in segment)
                for marker in markers
            ):
                found.add(code)
    for code, markers in _LANGUAGE_MARKERS:
        for marker in markers:
            escaped = re.escape(marker)
            boundary = r"\b" + escaped + r"\b" if marker.isascii() else escaped
            if re.search(boundary + r"\s+" + _LANGUAGE_QUALIFIER_RE.pattern, raw):
                found.add(code)
            if re.search(r"\b(?:in|auf|en)\s+" + boundary, raw):
                found.add(code)
            if re.search(r"(?:[|,;/\-\u2013\u2014]\s*)" + boundary + r"\s*$", raw):
                found.add(code)
    return found


def _declared_conflict(expected, declared):
    """Return the first conflicting language code, or None when safe/unknown."""
    if not expected:
        return None
    for code in declared:
        if code != expected:
            return code
    return None


def _junk_re(words):
    alternatives = [r"\b" + re.escape(w) + r"\b" for w in words]
    alternatives += [
        r"\b" + r"\s+".join(re.escape(part) for part in phrase.split()) + r"\b"
        for phrase in _JUNK_PHRASES
    ]
    return re.compile("|".join(alternatives), re.I)


_JUNK_TITLE_RE = _junk_re(_JUNK_WORDS)
_JUNK_CHANNEL_RE = _junk_re(_JUNK_CHANNEL_WORDS)


def _as_json(value, default):
    if value is None:
        return default
    if isinstance(value, str):
        return json.loads(value) if value.strip() else default
    return value


def _fold(text):
    """Lowercase + NFKD with combining marks dropped."""
    normalized = unicodedata.normalize("NFKD", text or "")
    return "".join(ch for ch in normalized if not unicodedata.combining(ch)).lower()


def _normalize_title(title):
    """Core title: no marketing, genre, part marker, brackets or separators."""
    text = _fold(title)
    # Catalogue titles and uploads spell these two differently; normalizing
    # both sides keeps "&" vs "and" and "Sorcerer's" vs "Sorcerers" from
    # causing a false hold.
    text = text.replace("&", " and ")
    text = text.replace("'", "").replace("\u2019", "")
    text = _NARRATED_RE.sub(" ", text)
    text = _BRACKET_RE.sub(" ", text)
    text = _LEADING_NUMBER_RE.sub(" ", text)
    text = _MARKETING_RE.sub(" ", text)
    text = _PART_RE.sub(" ", text)
    text = _NON_WORD_RE.sub(" ", text)
    return " ".join(text.split())


def _title_tokens(title):
    """Token set used for F1; leading articles carry no identity."""
    return {token for token in _normalize_title(title).split() if token not in _ARTICLES}


def _strip_artist_byline(candidate_title, artist):
    """Candidate tokens with the exact artist byline removed.

    Uploads spell the book as "Title by Author"; symmetric F1 counts the author
    words as noise ("Atomic Habits by James Clear" scores 0.57). Only the exact
    artist sequence from the resolved track is dropped, plus one preceding
    " by ", so a "by" inside the title itself stays and the sequel guard still
    sees the extra word in "Dune Messiah".
    """
    tokens = _normalize_title(candidate_title).split()
    artist_tokens = _normalize_title(artist).split()
    if not artist_tokens:
        return tokens
    width = len(artist_tokens)
    for start in range(len(tokens) - width + 1):
        if tokens[start:start + width] != artist_tokens:
            continue
        begin = start - 1 if start > 0 and tokens[start - 1] == "by" else start
        return tokens[:begin] + tokens[start + width:]
    return tokens


def _title_f1(expected_title, candidate_title, artist=""):
    """Token-set F1; 0.90 rejects sequels ("Dune Messiah" vs "Dune" = 0.67)."""
    expected = _title_tokens(expected_title)
    candidate = {
        token
        for token in _strip_artist_byline(candidate_title, artist)
        if token not in _ARTICLES
    }
    if not expected or not candidate:
        return 0.0
    overlap = len(expected & candidate)
    if not overlap:
        return 0.0
    precision = overlap / len(candidate)
    recall = overlap / len(expected)
    return 2.0 * precision * recall / (precision + recall)


def _roman_to_int(token):
    total = 0
    index = 0
    while index < len(token):
        pair = token[index:index + 2]
        if pair in _ROMAN_PAIRS:
            total += _ROMAN_PAIRS[pair]
            index += 2
        else:
            total += _ROMAN_VALUES[token[index]]
            index += 1
    return total


def _part_number(token):
    """Part number from an Arabic, Roman or word token; None if unparseable."""
    token = (token or "").strip().lower()
    if not token:
        return None
    if token.isdigit():
        return int(token)
    if token in _NUMBER_WORD_VALUES:
        return _NUMBER_WORD_VALUES[token]
    if re.fullmatch(r"[ivxlcdm]{1,6}", token):
        value = _roman_to_int(token)
        # "mix" would read as 1011; no book has that many parts.
        return value if 0 < value < 100 else None
    return None


def _parse_part(raw_title):
    """(index, declared_count) from the RAW title, or (None, None).

    Explicit ``part``/``(n/m)`` markers beat ``book``/``vol``/``chapter``:
    "Dune - Book 1 | ... PART II" is part 2 of the book, not book 2. ``book``
    stays usable because "Book One" is how most split uploads label parts.
    """
    text = _fold(raw_title)
    best = None
    for match in _PART_MARKER_RE.finditer(text):
        for group, count_group, priority in _PART_FAMILIES:
            token = match.group(group)
            if token is None:
                continue
            index = _part_number(token)
            if index is None:
                break
            count = _part_number(match.group(count_group)) if count_group else None
            found = (priority, match.start(), index, count)
            if best is None or found[:2] < best[:2]:
                best = found
            break
    if best is None:
        return None, None
    return best[2], best[3]


def _expected_seconds(duration_ms):
    """Audible runtime in seconds; 0.0 when unknown. The only ms conversion."""
    try:
        value = float(duration_ms or 0)
    except (TypeError, ValueError):
        return 0.0
    return value / 1000.0 if value > 0 else 0.0


def _candidate_seconds(value):
    """Candidate runtime in seconds; None when unknown, zero or non-numeric."""
    if value is None or value == "":
        return None
    try:
        seconds = float(value)
    except (TypeError, ValueError):
        return None
    return seconds if seconds > 0 else None


def _is_junk(text, pattern):
    return bool(pattern.search(text or ""))


def _ratio_label(ratio):
    return "%d%%" % round(ratio * 100.0)


def _result(
    verdict,
    reason,
    candidate_id="",
    ratio=0.0,
    best_id="",
    best_ratio=0.0,
    parts=None,
):
    payload = {
        "ok": True,
        "verdict": verdict,
        "reason": reason,
        "candidate_id": candidate_id,
        "ratio": round(float(ratio), 4),
        "best_id": best_id,
        "best_ratio": round(float(best_ratio), 4),
    }
    # Only a split carries parts: pass/hold/no_source keep the phase-1 shape so
    # the existing bridge keeps parsing them unchanged.
    if parts is not None:
        payload["parts"] = parts
        payload["part_count"] = len(parts)
    return json.dumps(payload)


def _part_entries(checked):
    """Candidates that could be a part of this book, as evaluation dicts."""
    entries = []
    for item in checked:
        if item["part_index"] is None or not item["seconds"]:
            continue
        if item["f1"] < 0.90:
            continue
        core = _normalize_title(item["title"])
        if not core:
            continue
        channel = item["channel"] or item["uploader"] or ""
        entries.append(
            {
                "id": item["id"],
                "url": item["url"],
                "title": item["title"],
                "channel": channel,
                "channel_key": " ".join(_fold(channel).split()),
                "core": core,
                "languages": tuple(sorted(item["languages"])),
                "narrator": item.get("narrator") or "",
                "edition": item.get("edition") or "",
                "seconds": item["seconds"],
                "index": item["part_index"],
                "declared": item["part_declared"],
                "junk": item["junk"],
            }
        )
    return entries


def _complete_indices(indices):
    """True when the numbers are exactly 1..N, or 0..N-1 for zero-based sets."""
    if len(indices) < 2:
        return False
    first = 0 if min(indices) == 0 else 1
    return sorted(indices) == list(range(first, first + len(indices)))


def _evaluate_part_set(parts, expected_s, tolerance_s, expected_language):
    """(payload, failure) for one same-channel/same-title/same-language group."""
    if not parts:
        return None, None

    if len(parts) == 1:
        # A lone "Part 1 of 2" is already actionable: say which part is absent
        # instead of only quoting a percentage.
        part = parts[0]
        declared = part["declared"]
        if declared and declared >= 2:
            first = 0 if part["index"] == 0 else 1
            missing = next(
                (n for n in range(first, first + declared) if n != part["index"]),
                None,
            )
            if missing is not None:
                return None, (
                    _PART_FAILURES["missing"],
                    "part set incomplete: missing part %d" % missing,
                )
        return None, None

    # Dedupe by video id first: dirty playlists repeat the same upload many
    # times (research 3.3). A repeated *part number* across different ids is a
    # real ambiguity and rejects the set.
    ordered = sorted(parts, key=lambda p: (p["index"], p["title"]))
    unique = []
    seen_ids = set()
    for part in ordered:
        if part["id"]:
            if part["id"] in seen_ids:
                continue
            seen_ids.add(part["id"])
        unique.append(part)

    indices = [p["index"] for p in unique]

    # Junk first: two summaries that both say "Part 1" are junk uploads, not a
    # numbering collision the user could fix by picking another upload.
    junk = [p for p in unique if p["junk"]]
    if junk:
        return None, (_PART_FAILURES["junk"], 'junk part "%s"' % junk[0]["title"])

    duplicates = sorted({i for i in indices if indices.count(i) > 1})
    if duplicates:
        return None, (
            _PART_FAILURES["duplicate"],
            "duplicate part %d" % duplicates[0],
        )

    if len(unique) > _MAX_PARTS:
        return None, (
            _PART_FAILURES["size"],
            "part set too large: %d parts (max %d)" % (len(unique), _MAX_PARTS),
        )

    if "" in {p["channel_key"] for p in unique}:
        return None, (
            _PART_FAILURES["channel"],
            "part set has an upload without a channel",
        )

    if len({p["languages"] for p in unique}) > 1:
        return None, (_PART_FAILURES["languages"], "mixed languages")

    narrators = {p["narrator"] for p in unique if p["narrator"]}
    if len(narrators) > 1:
        return None, (
            _PART_FAILURES["narrator"],
            "mixed narrators: %s" % ", ".join(sorted(narrators)),
        )

    editions = {p["edition"] for p in unique if p["edition"]}
    if len(editions) > 1:
        return None, (
            _PART_FAILURES["edition"],
            "mixed editions: %s" % ", ".join(sorted(editions)),
        )

    conflicts = sorted(
        {
            code
            for p in unique
            for code in p["languages"]
            if expected_language and code != expected_language
        }
    )
    if conflicts:
        return None, (
            _PART_FAILURES["language"],
            'wrong-language part set ("%s")' % unique[0]["title"],
        )

    for part in unique:
        if part["seconds"] < _MIN_PART_SECONDS:
            return None, (
                _PART_FAILURES["length"],
                "part %d is under a minute" % part["index"],
            )
        if part["seconds"] > _MAX_PART_SECONDS:
            return None, (
                _PART_FAILURES["length"],
                "part %d is over the 12 h upload cap" % part["index"],
            )

    first = 0 if min(indices) == 0 else 1
    expected_indices = list(range(first, first + len(unique)))
    if sorted(indices) != expected_indices:
        missing = next((n for n in expected_indices if n not in indices), None)
        if missing is None:
            return None, (
                _PART_FAILURES["missing"],
                "part set has unexpected part numbers",
            )
        return None, (
            _PART_FAILURES["missing"],
            "part set incomplete: missing part %d" % missing,
        )

    total = sum(p["seconds"] for p in unique)
    if abs(total - expected_s) > tolerance_s:
        return None, (
            _PART_FAILURES["sum"],
            "part sum %s of the book" % _ratio_label(total / expected_s),
        )

    return {
        "ratio": total / expected_s,
        "parts": [
            {
                "id": p["id"],
                "url": p["url"],
                "title": p["title"],
                "duration": int(round(p["seconds"])),
                "part_index": p["index"],
                "channel": p["channel"],
            }
            for p in unique
        ],
    }, None


def _part_set_verdict(checked, expected_s, tolerance_s, expected_language):
    """(payload, failure) of the best complete part set for this book."""
    entries = _part_entries(checked)
    if not entries:
        return None, None

    groups = {}
    for entry in entries:
        key = (entry["channel_key"], entry["core"], entry["languages"])
        groups.setdefault(key, []).append(entry)

    best = None
    failure = None

    def note(problem):
        nonlocal failure
        if problem and (failure is None or problem[0] < failure[0]):
            failure = problem

    for parts in groups.values():
        payload, problem = _evaluate_part_set(
            parts, expected_s, tolerance_s, expected_language
        )
        if payload:
            if best is None or abs(payload["ratio"] - 1.0) < abs(best["ratio"] - 1.0):
                best = payload
        else:
            note(problem)

    if best:
        return best, None

    # A complete set re-uploaded across two channels is the common real shape
    # (research 3.4): "mixed channels" is the actionable reason, not
    # "part 2 missing" twice.
    families = {}
    for entry in entries:
        families.setdefault((entry["core"], entry["languages"]), []).append(entry)
    for family in families.values():
        by_channel = {}
        for entry in family:
            by_channel.setdefault(entry["channel_key"], set()).add(entry["index"])
        if len(by_channel) < 2:
            continue
        # Only explain the failure as mixed channels when no single channel
        # already has the full numbering; otherwise the real defect (a short
        # part, a bad sum) is on that channel and must be reported instead.
        if any(_complete_indices(indices) for indices in by_channel.values()):
            continue
        if _complete_indices({e["index"] for e in family}):
            names = sorted({e["channel"] for e in family})
            note(
                (
                    _PART_FAILURES["channels"],
                    'mixed channels ("%s" vs "%s")' % (names[0], names[1]),
                )
            )
            break

    language_families = {}
    for entry in entries:
        language_families.setdefault((entry["core"], entry["channel_key"]), []).append(
            entry
        )
    for family in language_families.values():
        by_language = {}
        for entry in family:
            by_language.setdefault(entry["languages"], set()).add(entry["index"])
        if len(by_language) < 2:
            continue
        if any(_complete_indices(indices) for indices in by_language.values()):
            continue
        if _complete_indices({e["index"] for e in family}):
            note((_PART_FAILURES["languages"], "mixed languages"))
            break

    return None, failure


def gate_audible(track_json, candidates_json):
    """Decide whether one candidate may download the book, before any bytes.

    verdict:
      pass      a full-length candidate matched the title (closest wins)
      split     no single upload is the book, but a complete contiguous
                same-channel part set is; `parts` lists it in order
      hold      candidates exist, none is a verifiable full book or part set
      no_source there is nothing to gate
    """
    try:
        track = _as_json(track_json, {}) or {}
        candidates = _as_json(candidates_json, []) or []
        if isinstance(track, list):
            track = track[0] if track else {}
        candidates = list(candidates)

        if not candidates:
            return _result("no_source", "no candidates to gate")

        expected_s = _expected_seconds(track.get("durationMs"))
        if expected_s <= 0:
            return _result("hold", "expected runtime unknown")

        title = track.get("title") or ""
        expected_language = _normalize_language(track.get("language"))
        tolerance_s = max(120.0, expected_s * 0.02)
        # A part set is allowed a little more slack than one file: per-part
        # credits and intros add seconds that the full book does not have.
        part_tolerance_s = max(180.0, expected_s * 0.02)

        checked = []
        for index, candidate in enumerate(candidates):
            if not isinstance(candidate, dict):
                continue
            candidate_title = candidate.get("title") or ""
            channel = candidate.get("channel") or ""
            uploader = candidate.get("uploader") or ""
            seconds = _candidate_seconds(candidate.get("duration"))
            declared_languages = _language_declarations(candidate_title)
            part_index, part_declared = _parse_part(candidate_title)
            checked.append(
                {
                    "index": index,
                    "id": candidate.get("id") or "",
                    "url": candidate.get("url") or "",
                    "title": candidate_title,
                    "channel": channel,
                    "uploader": uploader,
                    "seconds": seconds,
                    "ratio": (seconds / expected_s) if seconds else 0.0,
                    "f1": _title_f1(
                        title, candidate_title, track.get("artist") or ""
                    ),
                    "languages": declared_languages,
                    "language_conflict": _declared_conflict(
                        expected_language, declared_languages
                    ),
                    "junk": (
                        _is_junk(candidate_title, _JUNK_TITLE_RE)
                        or _is_junk(channel, _JUNK_CHANNEL_RE)
                        or _is_junk(uploader, _JUNK_CHANNEL_RE)
                    ),
                    "part_index": part_index,
                    "part_declared": part_declared,
                    "narrator": _narrator_key(candidate_title),
                    "edition": _edition_key(candidate_title),
                }
            )

        if not checked:
            return _result("no_source", "no usable candidates to gate")

        known = [c for c in checked if c["seconds"]]
        # The display pick should be a real upload when one exists: a summary
        # with an exact runtime is the closest numerically but a bad preview.
        preferred = [
            c for c in known if not c["junk"] and not c["language_conflict"]
        ] or known
        best = (
            min(preferred, key=lambda c: (abs(c["ratio"] - 1.0), c["index"]))
            if preferred
            else None
        )
        best_id = best["id"] if best else ""
        best_ratio = best["ratio"] if best else 0.0

        passing = [
            c
            for c in checked
            if c["seconds"]
            and not c["junk"]
            and not c["language_conflict"]
            and c["f1"] >= 0.90
            and abs(c["seconds"] - expected_s) <= tolerance_s
        ]
        if passing:
            winner = min(passing, key=lambda c: (abs(c["ratio"] - 1.0), -c["f1"], c["index"]))
            reason = 'full-length match: "%s" is %s of the book' % (
                winner["title"],
                _ratio_label(winner["ratio"]),
            )
            return _result(
                "pass",
                reason,
                candidate_id=winner["id"],
                ratio=winner["ratio"],
                best_id=best_id,
                best_ratio=best_ratio,
            )

        # Single first, parts second: a full-length upload titled "Book 1" has
        # already passed above, so a series label never becomes a part set.
        part_set, part_problem = _part_set_verdict(
            checked, expected_s, part_tolerance_s, expected_language
        )
        if part_set:
            return _result(
                "split",
                "complete %d-part set: %s of the book"
                % (len(part_set["parts"]), _ratio_label(part_set["ratio"])),
                ratio=part_set["ratio"],
                best_id=best_id,
                best_ratio=best_ratio,
                parts=part_set["parts"],
            )

        matching = [c for c in checked if c["f1"] >= 0.90]
        wrong_language = [
            c for c in matching if c["language_conflict"]
        ]
        if all(c["junk"] for c in checked):
            # The reason must name the actionable problem: this pool is
            # summaries/reviews, not merely a title miss.
            reason = "only junk uploads (summary/recap/review/etc.)"
        elif not matching:
            reason = "no candidate with a matching title"
        elif wrong_language and len(wrong_language) == len(matching):
            reason = 'wrong-language upload ("%s")' % wrong_language[0]["title"]
        elif all(c["junk"] for c in matching):
            reason = "only junk uploads (summary/recap/review/etc.)"
        elif not any(c["seconds"] for c in matching):
            reason = "candidate duration unknown"
        else:
            closest = min(
                [c for c in matching if c["seconds"]],
                key=lambda c: (abs(c["ratio"] - 1.0), c["index"]),
            )
            reason = 'no full-length candidate - best match "%s" is %s of the book' % (
                closest["title"],
                _ratio_label(closest["ratio"]),
            )
        if part_problem:
            # A part set was actually attempted and failed; naming the defect
            # ("missing part 2", "mixed channels") beats quoting a percentage.
            reason = part_problem[1]
        return _result(
            "hold",
            reason,
            best_id=best_id,
            best_ratio=best_ratio,
        )
    except Exception as e:  # noqa: BLE001 - bridge contract: failures are JSON
        return json.dumps({"ok": False, "error": str(e)})
