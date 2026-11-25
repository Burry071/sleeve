#!/usr/bin/env python3
"""Offline unit tests for app/src/main/python/sleeve_spotify.py resolve().

Verifies all three resolution strategies (embed, anon-token fallback, and
client-credentials fallback) emit a JSON object with {"ok", "kind", "title",
"tracks"} matching SpotifyBridge.kt and .github/canary/dry_run.py.
"""

from __future__ import annotations

import json
import os
import sys
import unittest
import urllib.error
from unittest import mock

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_ROOT, "app", "src", "main", "python"))

import sleeve_spotify


def _embed_html(entity):
    payload = {
        "props": {
            "pageProps": {
                "status": 200,
                "state": {"data": {"entity": entity}},
            }
        }
    }
    return (
        '<html><body><script id="__NEXT_DATA__" type="application/json">'
        + json.dumps(payload)
        + "</script></body></html>"
    )


class SpotifyResolveTest(unittest.TestCase):
    def test_embed_track_returns_json_object(self):
        html = _embed_html(
            {
                "type": "track",
                "title": "Nights",
                "artists": [{"name": "Frank Ocean"}],
                "duration": 307151,
            }
        )
        with mock.patch.object(sleeve_spotify, "_http_get", return_value=html):
            parsed = json.loads(
                sleeve_spotify.resolve("https://open.spotify.com/track/7eqoqGkKwgOaWNNHx90uEZ")
            )

        self.assertIsInstance(parsed, dict)
        self.assertTrue(parsed["ok"])
        self.assertEqual("track", parsed["kind"])
        self.assertEqual("Nights", parsed["title"])
        self.assertEqual(1, len(parsed["tracks"]))
        self.assertEqual(
            {
                "title": "Nights",
                "artist": "Frank Ocean",
                "album": "",
                "durationMs": 307151,
                "isrc": "",
            },
            parsed["tracks"][0],
        )

    def test_embed_playlist_returns_json_object(self):
        html = _embed_html(
            {
                "name": "Focus Mix",
                "trackList": [
                    {
                        "title": "Track One",
                        "artists": [{"name": "Artist A"}, {"name": "Artist B"}],
                        "duration": 210000,
                    }
                ],
            }
        )
        with mock.patch.object(sleeve_spotify, "_http_get", return_value=html):
            parsed = json.loads(
                sleeve_spotify.resolve("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
            )

        self.assertIsInstance(parsed, dict)
        self.assertTrue(parsed["ok"])
        self.assertEqual("playlist", parsed["kind"])
        self.assertEqual("Focus Mix", parsed["title"])
        self.assertEqual("Artist A, Artist B", parsed["tracks"][0]["artist"])

    def test_anon_token_fallback_returns_json_object_for_track_album_and_playlist(self):
        cases = (
            (
                "https://open.spotify.com/track/TRK123",
                "track",
                "Midnight City",
                {
                    "https://api.spotify.com/v1/tracks/TRK123": {
                        "name": "Midnight City",
                        "artists": [{"name": "M83"}],
                        "album": {"name": "Hurry Up, We're Dreaming"},
                        "duration_ms": 243960,
                        "external_ids": {"isrc": "USNO11100422"},
                    }
                },
                1,
            ),
            (
                "https://open.spotify.com/album/ALB456",
                "album",
                "Discovery",
                {
                    "https://api.spotify.com/v1/albums/ALB456": {
                        "name": "Discovery",
                        "tracks": {
                            "items": [
                                {
                                    "name": "One More Time",
                                    "artists": [{"name": "Daft Punk"}],
                                    "duration_ms": 320357,
                                }
                            ],
                            "next": "https://api.spotify.com/v1/albums/ALB456/tracks?offset=1",
                        },
                    },
                    "https://api.spotify.com/v1/albums/ALB456/tracks?offset=1": {
                        "items": [
                            {
                                "name": "Aerodynamic",
                                "artists": [{"name": "Daft Punk"}],
                                "duration_ms": 212546,
                            }
                        ],
                        "next": None,
                    },
                },
                2,
            ),
            (
                "https://open.spotify.com/playlist/PL789",
                "playlist",
                "Road Trip",
                {
                    "https://api.spotify.com/v1/playlists/PL789?fields=name,tracks(total)": {
                        "name": "Road Trip",
                        "tracks": {"total": 1},
                    },
                    "https://api.spotify.com/v1/playlists/PL789/tracks?limit=100": {
                        "items": [
                            {
                                "track": {
                                    "name": "Dreams",
                                    "artists": [{"name": "Fleetwood Mac"}],
                                    "album": {"name": "Rumours"},
                                    "duration_ms": 257800,
                                    "external_ids": {"isrc": "USWB10100123"},
                                }
                            }
                        ],
                        "next": None,
                    },
                },
                1,
            ),
        )

        for url, expected_kind, expected_title, api_map, expected_count in cases:
            with self.subTest(kind=expected_kind):
                def fake_get(req_url, headers=None):
                    if "/embed/" in req_url:
                        raise RuntimeError("embed blocked")
                    if "get_access_token" in req_url:
                        return json.dumps({"accessToken": "anon-tok-123"})
                    self.assertEqual("Bearer anon-tok-123", (headers or {}).get("Authorization"))
                    return json.dumps(api_map[req_url])

                with mock.patch.object(sleeve_spotify, "_http_get", side_effect=fake_get):
                    parsed = json.loads(sleeve_spotify.resolve(url))

                self.assertIsInstance(parsed, dict)
                self.assertTrue(parsed["ok"])
                self.assertEqual(expected_kind, parsed["kind"])
                self.assertEqual(expected_title, parsed["title"])
                self.assertEqual(expected_count, len(parsed["tracks"]))
                for track in parsed["tracks"]:
                    self.assertIn("title", track)
                    self.assertIn("artist", track)
                    self.assertIn("album", track)
                    self.assertIn("durationMs", track)
                    self.assertIn("isrc", track)

    def test_client_credentials_fallback_returns_json_object(self):
        post_calls = []

        def fake_get(req_url, headers=None):
            if "/embed/" in req_url:
                raise urllib.error.HTTPError(req_url, 403, "Forbidden", {}, None)
            if "get_access_token" in req_url:
                raise urllib.error.HTTPError(req_url, 401, "Unauthorized", {}, None)
            self.assertEqual("Bearer cc-tok-999", (headers or {}).get("Authorization"))
            self.assertEqual("https://api.spotify.com/v1/tracks/CC123", req_url)
            return json.dumps(
                {
                    "name": "Time",
                    "artists": [{"name": "Pink Floyd"}],
                    "album": {"name": "The Dark Side of the Moon"},
                    "duration_ms": 413947,
                    "external_ids": {"isrc": "GBN9Y1100079"},
                }
            )

        def fake_post(req_url, fields, timeout=15):
            post_calls.append((req_url, fields))
            return json.dumps({"access_token": "cc-tok-999"})

        with mock.patch.object(sleeve_spotify, "_http_get", side_effect=fake_get), \
             mock.patch.object(sleeve_spotify, "_http_post_form", side_effect=fake_post), \
             mock.patch.object(sleeve_spotify.time, "sleep"):
            parsed = json.loads(
                sleeve_spotify.resolve(
                    "https://open.spotify.com/track/CC123",
                    client_id="my-id",
                    client_secret="my-secret",
                )
            )

        self.assertIsInstance(parsed, dict)
        self.assertTrue(parsed["ok"])
        self.assertEqual("track", parsed["kind"])
        self.assertEqual("Time", parsed["title"])
        self.assertEqual(1, len(parsed["tracks"]))
        self.assertEqual("Pink Floyd", parsed["tracks"][0]["artist"])
        self.assertEqual("GBN9Y1100079", parsed["tracks"][0]["isrc"])
        self.assertEqual(
            [
                (
                    "https://accounts.spotify.com/api/token",
                    {
                        "grant_type": "client_credentials",
                        "client_id": "my-id",
                        "client_secret": "my-secret",
                    },
                )
            ],
            post_calls,
        )

    def test_unsupported_url_returns_error_object(self):
        parsed = json.loads(sleeve_spotify.resolve("https://example.com/not-spotify"))
        self.assertIsInstance(parsed, dict)
        self.assertFalse(parsed["ok"])
        self.assertEqual("unsupported spotify url", parsed["error"])

    def test_all_strategies_failed_returns_error_object_with_hint(self):
        with mock.patch.object(sleeve_spotify, "_http_get", side_effect=RuntimeError("boom")):
            parsed = json.loads(
                sleeve_spotify.resolve("https://open.spotify.com/track/FAIL123")
            )

        self.assertIsInstance(parsed, dict)
        self.assertFalse(parsed["ok"])
        self.assertIn("embed:", parsed["error"])
        self.assertIn("anon-token:", parsed["error"])
        self.assertIn("Settings > Spotify", parsed["error"])


if __name__ == "__main__":
    unittest.main()
