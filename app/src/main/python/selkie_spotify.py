"""Spotify metadata resolver for Sleeve.

spotDL pattern (https://github.com/spotDL/spotify-downloader, GPL-3.0):
Spotify's public Web API is used for METADATA ONLY — titles, artists,
albums, durations. Audio is never fetched from Spotify; each track is
matched on YouTube and downloaded through yt-dlp.

Public surface (JSON strings, like selkie_engine):
    resolve(url, client_id, client_secret) ->
        '{"ok": true, "kind": "playlist|album|track", "title": ..., "tracks":
         [{"title", "artist", "album", "durationMs", "isrc"}]}'

No third-party dependencies: urllib only, so it runs on Chaquopy as-is.
"""

import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request

_TOKEN = {"value": None, "expires": 0.0}

# Playlist/album pages are capped so a 1000-track paste cannot flood the queue.
MAX_TRACKS = 100

_URL_RE = re.compile(r"(track|album|playlist)[/:]([A-Za-z0-9]+)")


def _token(client_id, client_secret):
    if _TOKEN["value"] and time.time() < _TOKEN["expires"]:
        return _TOKEN["value"]
    body = urllib.parse.urlencode(
        {
            "grant_type": "client_credentials",
            "client_id": client_id,
            "client_secret": client_secret,
        }
    ).encode()
    req = urllib.request.Request(
        "https://accounts.spotify.com/api/token",
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    last_error = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode())
            _TOKEN["value"] = data["access_token"]
            _TOKEN["expires"] = time.time() + float(data.get("expires_in", 3600)) - 60.0
            return _TOKEN["value"]
        except urllib.error.HTTPError as e:
            last_error = e
            if e.code == 429:
                # The shared bundled credentials are globally rate-limited;
                # back off twice before surfacing a clear message.
                time.sleep(2.0 * (attempt + 1))
                continue
            break
        except Exception as e:  # noqa: BLE001
            last_error = e
            break
    if isinstance(last_error, urllib.error.HTTPError) and last_error.code == 429:
        raise RuntimeError(
            "Spotify rejected these client credentials (HTTP 429). "
            "Add your own free credentials from developer.spotify.com "
            "in Settings."
        )
    if last_error is not None:
        raise last_error
    raise RuntimeError("could not obtain spotify token")


def _api(url, token):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode())


def _track_dto(t, album=None):
    if album is not None:
        t = dict(t)
        t["album"] = album
    return {
        "title": t.get("name") or "",
        "artist": ", ".join(a.get("name", "") for a in (t.get("artists") or [])),
        "album": (t.get("album") or {}).get("name", ""),
        "durationMs": t.get("duration_ms") or 0,
        "isrc": (t.get("external_ids") or {}).get("isrc") or "",
    }


def resolve(url, client_id, client_secret):
    try:
        token = _token(client_id, client_secret)
        m = _URL_RE.search(url or "")
        if not m:
            return json.dumps({"ok": False, "error": "unsupported spotify url"})
        kind, obj_id = m.group(1), m.group(2)

        if kind == "track":
            t = _api("https://api.spotify.com/v1/tracks/" + obj_id, token)
            return json.dumps(
                {"ok": True, "kind": "track", "title": t.get("name", ""),
                 "tracks": [_track_dto(t)]}
            )

        if kind == "album":
            album = _api("https://api.spotify.com/v1/albums/" + obj_id, token)
            items = []
            page = album.get("tracks") or {}
            for t in page.get("items", []):
                items.append(_track_dto(t, album={"name": album.get("name"),
                                                  "artists": album.get("artists")}))
                if len(items) >= MAX_TRACKS:
                    break
            nxt = page.get("next")
            while nxt and len(items) < MAX_TRACKS:
                page = _api(nxt, token)
                for t in page.get("items", []):
                    items.append(_track_dto(t, album={"name": album.get("name"),
                                                      "artists": album.get("artists")}))
                    if len(items) >= MAX_TRACKS:
                        break
                nxt = page.get("next")
            return json.dumps(
                {"ok": True, "kind": "album", "title": album.get("name", ""),
                 "tracks": items}
            )

        playlist = _api(
            "https://api.spotify.com/v1/playlists/"
            + obj_id
            + "?fields=name,tracks(total)",
            token,
        )
        items = []
        page = _api(
            "https://api.spotify.com/v1/playlists/" + obj_id + "/tracks?limit=100",
            token,
        )
        while True:
            for it in page.get("items", []):
                t = it.get("track")
                if t:
                    items.append(_track_dto(t))
                    if len(items) >= MAX_TRACKS:
                        break
            nxt = page.get("next")
            if not nxt or len(items) >= MAX_TRACKS:
                break
            page = _api(nxt, token)
        return json.dumps(
            {"ok": True, "kind": "playlist", "title": playlist.get("name", ""),
             "tracks": items}
        )
    except Exception as e:  # noqa: BLE001 — bridge contract: every failure is JSON
        return json.dumps({"ok": False, "error": str(e)})
