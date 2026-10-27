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

Public surface (JSON strings, like selkie_engine):
    resolve(url, client_id, client_secret) ->
        '{"ok": true, "kind": "...", "title": ..., "tracks":
         [{"title", "artist", "album", "durationMs", "isrc"}]}'

urllib only, so it runs on Chaquopy as-is.
"""

import json
import re
import time
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


def _http_post_form(url, fields):
    body = urllib.parse.urlencode(fields).encode()
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "User-Agent": _UA,
        },
    )
    with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
        return resp.read().decode()


def _api_get(url, token):
    return json.loads(_http_get(url, {"Authorization": "Bearer " + token}))


# ---------------------------------------------------------------- strategy 1
def _via_embed(kind, obj_id):
    """The public embed page embeds entity JSON — zero auth, zero API."""
    html = _http_get("https://open.spotify.com/embed/%s/%s" % (kind, obj_id))
    m = _NEXT_DATA_RE.search(html)
    if not m:
        raise RuntimeError("embed: no __NEXT_DATA__")
    data = json.loads(m.group(1))
    page = (data.get("props") or {}).get("pageProps") or {}
    if str(page.get("status")) not in (None, "None", "200"):
        raise RuntimeError("embed: page status %s" % page.get("status"))
    entity = (((page.get("state") or {}).get("data") or {}).get("entity")) or {}
    track_list = entity.get("trackList") or []
    if not track_list:
        raise RuntimeError("embed: no trackList")

    def artist_names(t):
        arts = t.get("artists") or []
        return ", ".join(
            (a.get("name") if isinstance(a, dict) else str(a)) or "" for a in arts
        )

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
    if kind == "track" and tracks:
        title = tracks[0]["title"]
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
    data = json.loads(
        _http_post_form(
            "https://accounts.spotify.com/api/token",
            {
                "grant_type": "client_credentials",
                "client_id": client_id,
                "client_secret": client_secret,
            },
        )
    )
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
