"""Spotify metadata resolver for Sleeve — zero-credentials first.

Reverse-engineered public surfaces, tried in order:
  1. open.spotify.com/embed/... page: the __NEXT_DATA__ JSON carries the
     full entity (name + trackList with artists/durations) — no auth.
  2. open.spotify.com/get_access_token?reason=transport&productType=embed:
     the web player's anonymous token — no login; used against api.spotify.com
     v1 endpoints.
  3. Client credentials (spotDL pattern) — optional, for users who pasted
     their own pair; the bundled shared pair is heavily rate-limited.

Spotify is used for METADATA ONLY (spotDL pattern); audio is always
fetched from YouTube via yt-dlp. No DRM is touched.

Public surface (JSON strings, like sleeve_engine):
    resolve(url, client_id, client_secret) ->
        '{"ok": true, "kind": "...", "title": ..., "tracks":
         [{"title", "artist", "album", "durationMs", "isrc"}]}'

urllib only, so it runs on Chaquopy as-is.
"""

import json
import re
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

MAX_TRACKS = 100
_TIMEOUT = 15
_UA = (
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
)
_URL_RE = re.compile(r"(track|album|playlist)[/:]([A-Za-z0-9]+)")
_NEXT_DATA_RE = re.compile(
    r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', re.S
)


def _track_dto(title, artist, album="", duration_ms=0, isrc=""):
    return {
        "title": title or "",
        "artist": artist or "",
        "album": album or "",
        "durationMs": int(duration_ms or 0),
        "isrc": isrc,
    }


def _http_get(url, headers=None):
    req = urllib.request.Request(
        url, headers={"User-Agent": _UA, **(headers or {})}
    )
    with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
        return resp.read().decode()


def _http_post_form(url, fields, timeout=_TIMEOUT):
    body = urllib.parse.urlencode(fields).encode()
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "User-Agent": _UA,
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode()


def _api_get(url, token):
    return json.loads(_http_get(url, {"Authorization": "Bearer " + token}))


# ---------------------------------------------------------------- strategy 1
def _via_embed(kind, obj_id):
    """The public embed page embeds entity JSON — zero auth, zero API.

    Entity shapes: playlists/albums carry entity.trackList; a track embed
    IS the entity itself (type "track", title/name, artists, duration ms).
    """
    html = _http_get("https://open.spotify.com/embed/%s/%s" % (kind, obj_id))
    m = _NEXT_DATA_RE.search(html)
    if not m:
        raise RuntimeError("embed: no __NEXT_DATA__")
    data = json.loads(m.group(1))
    page = (data.get("props") or {}).get("pageProps") or {}
    if str(page.get("status")) not in (None, "None", "200"):
        raise RuntimeError("embed: page status %s" % page.get("status"))
    entity = (((page.get("state") or {}).get("data") or {}).get("entity")) or {}

    def artist_names(t):
        arts = t.get("artists") or []
        return ", ".join(
            (a.get("name") if isinstance(a, dict) else str(a)) or "" for a in arts
        )

    track_list = entity.get("trackList") or []
    if not track_list and entity.get("type") == "track":
        # Single-track embed: the entity is the track.
        return {
            "ok": True,
            "kind": "track",
            "title": entity.get("title") or entity.get("name") or "",
            "tracks": [
                _track_dto(
                    title=entity.get("title") or entity.get("name") or "",
                    artist=artist_names(entity),
                    duration_ms=entity.get("duration") or 0,
                )
            ],
        }
    if not track_list:
        raise RuntimeError("embed: no trackList and not a track entity")

    tracks = [
        _track_dto(
            title=t.get("title") or "",
            artist=artist_names(t) or t.get("subtitle") or "",
            album=entity.get("name") if kind == "album" else "",
            duration_ms=t.get("duration") or 0,
        )
        for t in track_list[:MAX_TRACKS]
    ]
    title = entity.get("name") or ""
    return {"ok": True, "kind": kind, "title": title, "tracks": tracks}


# ---------------------------------------------------------------- strategy 2
def _anon_token():
    body = _http_get(
        "https://open.spotify.com/get_access_token"
        "?reason=transport&productType=embed"
    )
    data = json.loads(body)
    token = data.get("accessToken") or ""
    if not token:
        raise RuntimeError("anon: no accessToken in response")
    return token


def _v1_tracks(kind, obj_id, token):
    """Web API v1 with any bearer token; returns (title, tracks)."""
    if kind == "track":
        t = _api_get("https://api.spotify.com/v1/tracks/" + obj_id, token)
        return t.get("name", ""), [
            _track_dto(
                title=t.get("name"),
                artist=", ".join(a.get("name", "") for a in (t.get("artists") or [])),
                album=(t.get("album") or {}).get("name", ""),
                duration_ms=t.get("duration_ms") or 0,
                isrc=(t.get("external_ids") or {}).get("isrc", ""),
            )
        ]
    if kind == "album":
        album = _api_get("https://api.spotify.com/v1/albums/" + obj_id, token)
        album_name = album.get("name", "")
        tracks, page = [], album.get("tracks") or {}

        def take(items):
            for t in items:
                if len(tracks) >= MAX_TRACKS:
                    return
                tracks.append(
                    _track_dto(
                        title=t.get("name"),
                        artist=", ".join(
                            a.get("name", "") for a in (t.get("artists") or [])
                        ),
                        album=album_name,
                        duration_ms=t.get("duration_ms") or 0,
                    )
                )

        take(page.get("items", []))
        nxt = page.get("next")
        while nxt and len(tracks) < MAX_TRACKS:
            page = _api_get(nxt, token)
            take(page.get("items", []))
            nxt = page.get("next")
        return album_name, tracks

    # playlist
    playlist = _api_get(
        "https://api.spotify.com/v1/playlists/"
        + obj_id
        + "?fields=name,tracks(total)",
        token,
    )
    tracks = []
    page = _api_get(
        "https://api.spotify.com/v1/playlists/" + obj_id + "/tracks?limit=100",
        token,
    )
    while True:
        for it in page.get("items", []):
            t = it.get("track")
            if t:
                tracks.append(
                    _track_dto(
                        title=t.get("name"),
                        artist=", ".join(
                            a.get("name", "") for a in (t.get("artists") or [])
                        ),
                        album=(t.get("album") or {}).get("name", ""),
                        duration_ms=t.get("duration_ms") or 0,
                        isrc=(t.get("external_ids") or {}).get("isrc", ""),
                    )
                )
                if len(tracks) >= MAX_TRACKS:
                    break
        nxt = page.get("next")
        if not nxt or len(tracks) >= MAX_TRACKS:
            break
        page = _api_get(nxt, token)
    return playlist.get("name", ""), tracks


def _via_anon_token(kind, obj_id):
    token = _anon_token()
    return _v1_tracks(kind, obj_id, token)


# ---------------------------------------------------------------- strategy 3
def _via_client_credentials(kind, obj_id, client_id, client_secret):
    data = None
    last_err = None
    for attempt in range(2):
        try:
            data = json.loads(
                _http_post_form(
                    "https://accounts.spotify.com/api/token",
                    {
                        "grant_type": "client_credentials",
                        "client_id": client_id,
                        "client_secret": client_secret,
                    },
                    timeout=30,
                )
            )
            break
        except Exception as e:  # noqa: BLE001 — one retry covers transient timeouts
            last_err = e
            time.sleep(1.5)
    if data is None:
        raise RuntimeError("creds: %s" % last_err)
    token = data.get("access_token") or ""
    if not token:
        raise RuntimeError("creds: no access_token in response")
    return _v1_tracks(kind, obj_id, token)


def resolve(url, client_id="", client_secret=""):
    m = _URL_RE.search(url or "")
    if not m:
        return json.dumps({"ok": False, "error": "unsupported spotify url"})
    kind, obj_id = m.group(1), m.group(2)

    attempts = [("embed", lambda: _via_embed(kind, obj_id))]
    attempts.append(("anon-token", lambda: _via_anon_token(kind, obj_id)))
    if (client_id or "").strip() and (client_secret or "").strip():
        attempts.append(
            (
                "client-credentials",
                lambda: _via_client_credentials(
                    kind, obj_id, client_id.strip(), client_secret.strip()
                ),
            )
        )

    errors = []
    for name, fn in attempts:
        try:
            return json.dumps(fn())
        except urllib.error.HTTPError as e:
            if e.code == 429:
                errors.append("%s: HTTP 429 (rate limited)" % name)
                time.sleep(1.5)
            else:
                errors.append("%s: HTTP %s" % (name, e.code))
        except Exception as e:  # noqa: BLE001 — every failure feeds the next attempt
            errors.append("%s: %s" % (name, e))

    hint = ""
    if not (client_id or "").strip():
        hint = (
            " (optional fix: paste free client credentials from "
            "developer.spotify.com in Settings > Spotify)"
        )
    return json.dumps(
        {"ok": False, "error": "all strategies failed: " + "; ".join(errors) + hint}
    )


# ------------------------------------------------------------ ranked matching
# Quality-ranked candidate picking (spotDL-adapted recipe):
#   - name match: difflib ratio on slugified token strings (floor 60); for
#     long-form items the candidate's marketing decoration ("Full Audiobook",
#     "Book One", "|" / "-" separators) is stripped first
#   - artist match: uploader vs artist (+10 for YouTube's official "- Topic"
#     artist channels), floor 40
#   - duration: exp decay of |delta seconds| (x100), slower decay for long
#     items (audiobooks) where YouTube copies are often split/partial
#   - forbidden-keyword penalties: -15 per word present in the candidate
#     title but not the track (live, remix, cover, sped up, ...)
#   - final = 0.4*name + 0.35*artist + 0.25*duration

import difflib as _difflib

_FORBIDDEN_WORDS = [
    "bassboosted", "remix", "remastered", "remaster", "reverb", "bassboost",
    "live", "acoustic", "8daudio", "concert", "acapella", "slowed",
    "instrumental", "cover", "karaoke", "nightcore", "spedup", "speedup",
    "1hour", "fullalbum", "reaction",
]

# Channels that host re-uploads rather than the artist's own audio.
_JUNK_CHANNEL_WORDS = (
    "lyrics", "lyric", "mix", "mixes", "bootleg", "slowed", "nightcore",
    "cover", "karaoke",
)
_ARTIST_SPLIT_RE = re.compile(r"\s*(?:,|&|\band\b|\bx\b|\bft\b|\bfeat\b)\s*")
_BRACKET_RE = re.compile(r"\([^)]*\)|\[[^]]*]")

# Marketing decoration that audiobook uploads carry and the catalogue title
# never does. Stripped from the *candidate* title for the name score, and only
# when the track is long-form: a music title saying "book one" is the artist's
# own wording and must keep costing name points.
_LONG_FORM_DECOR_RE = re.compile(
    r"\b(?:full|complete)\s+audiobook\b"
    r"|\bunabridged\b"
    r"|\baudiobook\b"
    r"|\b(?:science\s+fiction|sci[\s-]*fi|epic|saga|fantasy|thriller|novel"
    r"|best[\s-]*seller)\b"
    r"|\bbook\s+(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven"
    r"|twelve|\d{1,2})\b",
    re.I,
)
_LONG_FORM_SEP_RE = re.compile(r"\s*\|\s*|\s+[-\u2013\u2014]+\s*|\s*[-\u2013\u2014]+\s+")
# Part markers are not part of the book identity; strip them for the name
# score so "Dune - Part 1" still matches the catalogue title. The gate does the
# actual part parsing later.
_LONG_FORM_PART_RE = re.compile(
    r"\bpart\s+(?:\d{1,2}|[ivxlcdm]{1,6}|one|two|three|four|five|six|seven|eight|nine|ten"
    r"|eleven|twelve)\b"
    r"|\bpt\.?\s*\d{1,2}\b"
    r"|\b\d{1,2}\s*(?:of|/)\s*\d{1,2}\b",
    re.I,
)


def _is_part_upload(title):
    return bool(_LONG_FORM_PART_RE.search(title or ""))


def _strip_marks(text):
    normalized = unicodedata.normalize("NFKD", text)
    return "".join(ch for ch in normalized if not unicodedata.combining(ch))


def _base_slug(text):
    text = _strip_marks((text or "").lower()).replace("&", " and ")
    text = re.sub(r"[^\w\s]", "", text)
    return " ".join(text.split())


def _slug(text):
    """Match form: bracketed decoration ("(feat. X)", "[Live]") removed."""
    return _base_slug(_BRACKET_RE.sub(" ", text or ""))


def _raw_slug(text):
    """Penalty form: keeps bracketed words so a "[Live]" upload still counts."""
    return _base_slug(text)


def _ratio(a, b):
    if not a or not b:
        return 0.0
    return _difflib.SequenceMatcher(None, a, b).ratio() * 100.0


def _strip_long_form_decor(title):
    """Drop audiobook marketing and part decoration from a candidate title."""
    text = _LONG_FORM_DECOR_RE.sub(" ", title or "")
    text = _LONG_FORM_PART_RE.sub(" ", text)
    text = _LONG_FORM_SEP_RE.sub(" ", text)
    return " ".join(text.split())


def _name_score(track_title, track_artist, cand_title, long_form=False):
    t = _slug(track_title)
    scored_title = _strip_long_form_decor(cand_title) if long_form else cand_title
    c = _slug(scored_title)
    score = _ratio(" ".join(sorted(t.split())), " ".join(sorted(c.split())))
    if score <= 75.0:
        full = _slug(track_artist + " " + track_title)
        score = max(score, _ratio(" ".join(sorted(full.split())), " ".join(sorted(c.split()))))
    # The penalty reads the title as uploaded: decoration stripping must not
    # hide a "[Live]" or "(Remix)" marker from it.
    cand_raw = _raw_slug(cand_title)
    track_raw = _raw_slug(track_artist + " " + track_title)
    for word in _FORBIDDEN_WORDS:
        if word in cand_raw.replace(" ", "") and word not in track_raw.replace(" ", ""):
            score -= 15.0
    return score


def _artist_names(track_artist):
    raw = _slug(track_artist)
    parts = [p.strip() for p in _ARTIST_SPLIT_RE.split(raw) if p.strip()]
    return parts or ([raw] if raw else [])


def _artist_score(track_artist, cand_uploader, cand_channel, cand_title):
    names = _artist_names(track_artist)
    if not names:
        return 40.0
    main = names[0]
    title_slug = _slug(cand_title)
    scores = []
    # The flat search exposes both the uploader and the channel; the official
    # channel is often the better signal (e.g. VEVO vs a personal uploader).
    for channel_name in (cand_uploader, cand_channel):
        uploader = _slug(channel_name)
        if not uploader:
            continue
        score = max(_ratio(name, uploader) for name in names)
        if main == uploader or score >= 99.0:
            score = 100.0
        elif main in uploader or uploader in main:
            score = max(score, 90.0)
        # Official artist topic channels ("Artist - Topic") are the
        # auto-generated home of the real audio on YouTube.
        if uploader.endswith("topic") and _ratio(main, uploader[:-5].strip()) >= 60.0:
            score = min(100.0, score + 10.0)
        if "vevo" in uploader:
            score = min(100.0, score + 10.0)
        if score < 50.0 and main.split()[0] in title_slug:
            score = max(score, 55.0)
        # Applied after the rescue: otherwise a junk channel whose name shares
        # one word with the artist gets lifted back to 55 and the penalty is
        # lost.
        if any(word in uploader for word in _JUNK_CHANNEL_WORDS):
            score -= 20.0
        scores.append(max(0.0, min(100.0, score)))
    return max(scores) if scores else 40.0


def _duration_delta(track_ms, cand_s):
    track_s = (track_ms or 0) / 1000.0
    if not track_s or cand_s is None or float(cand_s) <= 0:
        return None, None
    delta = abs(track_s - float(cand_s))
    return delta, delta / track_s * 100.0


def _duration_seconds(value):
    """Candidate runtime in whole seconds; None when unknown or non-numeric."""
    if value is None or value == "":
        return None
    try:
        return int(round(float(value)))
    except (TypeError, ValueError):
        return None


def _duration_score(track_ms, cand_s):
    track_s = (track_ms or 0) / 1000.0
    delta, pct = _duration_delta(track_ms, cand_s)
    if delta is None:
        return 55.0
    # Banded tolerance instead of an exponential: a 25-second-overshoot
    # "live version" must not survive next to an exact studio copy.
    if delta <= max(3.0, track_s * 0.01):
        return 100.0
    if pct <= 3.0:
        return 90.0
    if pct <= 5.0:
        return 75.0
    if pct <= 10.0:
        return 50.0
    return 25.0


def rank_candidates(track_json, candidates_json, strictness=1, top_n=3):
    """Pick the best YouTube candidate for a metadata track.

    track_json: {"title","artist","album","durationMs"}
    candidates_json: [{"id","url","title","uploader","channel","duration"}]
    strictness: 0=loose, 1=normal, 2=strict
    top_n: how many ranked candidates to keep for the review sheet.

    returns {"ok","index","id","url","title","score","uploader","channel",
    "duration","candidates":[...],"rejected":[...]} where candidates is the
    top_n list with per-component reasons and the candidate's uploader,
    channel and duration (whole seconds, null when unknown) echoed back, and
    rejected names the duration-vetoed uploads.
    """
    try:
        track = json.loads(track_json) if isinstance(track_json, str) else dict(track_json or {})
        candidates = json.loads(candidates_json) if isinstance(candidates_json, str) else list(candidates_json or [])
        title = track.get("title", "")
        artist = track.get("artist", "")
        duration_ms = track.get("durationMs", 0)
        try:
            keep = max(1, int(top_n))
        except (TypeError, ValueError):
            keep = 3

        long_form = (duration_ms or 0) > 1800_000
        track_s = (duration_ms or 0) / 1000.0
        ranked = []
        rejected = []
        for i, cand in enumerate(candidates):
            cand_title = cand.get("title", "") or ""
            cand_s = cand.get("duration")
            # An audiobook cannot be a fraction of its own runtime: a trailer,
            # an excerpt or a single part is a different recording. Only a
            # known duration can be vetoed — missing data stays neutral.
            if (
                long_form
                and track_s > 0
                and cand_s is not None
                and float(cand_s) > 0
                and float(cand_s) < track_s * 0.25
                and not _is_part_upload(cand_title)
            ):
                rejected.append(
                    {
                        "index": i,
                        "id": cand.get("id") or "",
                        "title": cand_title,
                        "reason": (
                            "duration %d%% of book — below 25%% floor, "
                            "cannot be the full book"
                        )
                        % round(float(cand_s) / track_s * 100.0),
                    }
                )
                continue
            name = _name_score(title, artist, cand_title, long_form=long_form)
            name_floor = {0: 50.0, 1: 60.0, 2: 75.0}.get(strictness, 60.0)
            if name < name_floor:
                continue
            artist_s = _artist_score(
                artist,
                cand.get("uploader") or "",
                cand.get("channel") or "",
                cand_title,
            )
            # Long-form uploads (audiobooks) live on channels that are not the
            # author — the artist signal is weak there; the title carries it.
            artist_floor = {0: 0.0, 1: 40.0, 2: 60.0}.get(strictness, 40.0)
            if artist_s < artist_floor and not (long_form and strictness < 2):
                continue
            _, dur_pct = _duration_delta(duration_ms, cand.get("duration"))
            if strictness == 2 and dur_pct is not None and dur_pct > 10.0:
                continue
            dur = _duration_score(duration_ms, cand.get("duration"))
            if long_form:
                final = 0.55 * name + 0.15 * artist_s + 0.30 * dur
            elif strictness == 2:
                final = 0.35 * name + 0.45 * artist_s + 0.20 * dur
            else:
                final = 0.4 * name + 0.35 * artist_s + 0.25 * dur
            reasons = [
                "title %d" % round(name),
                "artist %d" % round(artist_s),
                "duration %d" % round(dur),
            ]
            if dur_pct is None:
                reasons.append("duration unknown")
            elif dur_pct > 5.0:
                reasons.append("duration differs %d%%" % round(dur_pct))
            if long_form:
                reasons.append("long form")
            ranked.append(
                {
                    "index": i,
                    "id": cand.get("id") or "",
                    "url": cand.get("url") or "",
                    "title": cand_title,
                    "score": round(final, 1),
                    "uploader": cand.get("uploader") or "",
                    "channel": cand.get("channel") or "",
                    "duration": _duration_seconds(cand.get("duration")),
                    "reasons": reasons,
                }
            )
        if not ranked:
            error = (
                "no candidate matched the book's duration/length"
                if long_form
                else "no candidate passed the floor"
            )
            return json.dumps({"ok": False, "error": error, "rejected": rejected})
        ranked.sort(key=lambda item: item["score"], reverse=True)
        top = ranked[:keep]
        best = dict(top[0])
        best["ok"] = True
        best["candidates"] = top
        best["rejected"] = rejected
        return json.dumps(best)
    except Exception as e:  # noqa: BLE001
        return json.dumps({"ok": False, "error": str(e)})
