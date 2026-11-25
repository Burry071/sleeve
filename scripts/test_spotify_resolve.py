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

    def test_embed_reports_total_tracks_and_truncated_flag(self):
        def make_items(count):
            return [
                {
                    "title": f"Track {i}",
                    "artists": [{"name": "Artist"}],
                    "duration": 180000,
                }
                for i in range(count)
            ]

        # > 100 tracks in trackList -> truncated=True, totalTracks=135
        html_over = _embed_html({"name": "Huge Mix", "trackList": make_items(135)})
        with mock.patch.object(sleeve_spotify, "_http_get", return_value=html_over):
            parsed = json.loads(
                sleeve_spotify.resolve("https://open.spotify.com/playlist/PLOVER135")
            )
        self.assertTrue(parsed["ok"])
        self.assertEqual(100, len(parsed["tracks"]))
        self.assertEqual(135, parsed["totalTracks"])
        self.assertTrue(parsed["truncated"])

        # Embed capped to 100 items with entity.total=180 -> truncated=True, totalTracks=180
        html_total = _embed_html(
            {"name": "Capped Mix", "total": 180, "trackList": make_items(100)}
        )
        with mock.patch.object(sleeve_spotify, "_http_get", return_value=html_total):
            parsed_total = json.loads(
                sleeve_spotify.resolve("https://open.spotify.com/playlist/PLTOTAL180")
            )
        self.assertTrue(parsed_total["ok"])
        self.assertEqual(100, len(parsed_total["tracks"]))
        self.assertEqual(180, parsed_total["totalTracks"])
        self.assertTrue(parsed_total["truncated"])

        # Exactly 100 tracks -> truncated=False, totalTracks=100
        html_exact = _embed_html({"name": "Exact 100", "trackList": make_items(100)})
        with mock.patch.object(sleeve_spotify, "_http_get", return_value=html_exact):
            parsed_exact = json.loads(
                sleeve_spotify.resolve("https://open.spotify.com/playlist/PLEXACT100")
            )
        self.assertTrue(parsed_exact["ok"])
        self.assertEqual(100, len(parsed_exact["tracks"]))
        self.assertEqual(100, parsed_exact["totalTracks"])
        self.assertFalse(parsed_exact["truncated"])

        # Single track -> totalTracks=1, truncated=False
        html_single = _embed_html(
            {
                "type": "track",
                "title": "Solo",
                "artists": [{"name": "Artist"}],
                "duration": 200000,
            }
        )
        with mock.patch.object(sleeve_spotify, "_http_get", return_value=html_single):
            parsed_single = json.loads(
                sleeve_spotify.resolve("https://open.spotify.com/track/TRKSOLO")
            )
        self.assertTrue(parsed_single["ok"])
        self.assertEqual(1, parsed_single["totalTracks"])
        self.assertFalse(parsed_single["truncated"])

    def test_v1_playlist_and_album_truncation_metadata(self):
        playlist_items = [
            {
                "track": {
                    "name": f"Song {i}",
                    "artists": [{"name": "Band"}],
                    "album": {"name": "LP"},
                    "duration_ms": 200000,
                    "external_ids": {"isrc": f"ISRC{i:04d}"},
                }
            }
            for i in range(100)
        ]
        album_items_p1 = [
            {"name": f"A{i}", "artists": [{"name": "Band"}], "duration_ms": 150000}
            for i in range(50)
        ]
        album_items_p2 = [
            {"name": f"B{i}", "artists": [{"name": "Band"}], "duration_ms": 150000}
            for i in range(50)
        ]
        api_map = {
            "https://api.spotify.com/v1/playlists/PLBIG?fields=name,tracks(total)": {
                "name": "Mega Playlist",
                "tracks": {"total": 250},
            },
            "https://api.spotify.com/v1/playlists/PLBIG/tracks?limit=100": {
                "items": playlist_items,
                "next": "https://api.spotify.com/v1/playlists/PLBIG/tracks?offset=100",
            },
            "https://api.spotify.com/v1/playlists/PLSMALL?fields=name,tracks(total)": {
                "name": "Small Playlist",
                "tracks": {"total": 12},
            },
            "https://api.spotify.com/v1/playlists/PLSMALL/tracks?limit=100": {
                "items": playlist_items[:12],
                "next": None,
            },
            "https://api.spotify.com/v1/albums/ALBBIG": {
                "name": "Box Set",
                "total_tracks": 120,
                "tracks": {
                    "total": 120,
                    "items": album_items_p1,
                    "next": "https://api.spotify.com/v1/albums/ALBBIG/tracks?offset=50",
                },
            },
            "https://api.spotify.com/v1/albums/ALBBIG/tracks?offset=50": {
                "total": 120,
                "items": album_items_p2,
                "next": "https://api.spotify.com/v1/albums/ALBBIG/tracks?offset=100",
            },
        }

        def fake_get_anon(req_url, headers=None):
            if "/embed/" in req_url:
                raise RuntimeError("embed unavailable")
            if "get_access_token" in req_url:
                return json.dumps({"accessToken": "anon-tok"})
            return json.dumps(api_map[req_url])

        with mock.patch.object(sleeve_spotify, "_http_get", side_effect=fake_get_anon):
            pl_big = json.loads(
                sleeve_spotify.resolve("https://open.spotify.com/playlist/PLBIG")
            )
            pl_small = json.loads(
                sleeve_spotify.resolve("https://open.spotify.com/playlist/PLSMALL")
            )
            alb_big = json.loads(
                sleeve_spotify.resolve("https://open.spotify.com/album/ALBBIG")
            )

        self.assertEqual(100, len(pl_big["tracks"]))
        self.assertEqual(250, pl_big["totalTracks"])
        self.assertTrue(pl_big["truncated"])

        self.assertEqual(12, len(pl_small["tracks"]))
        self.assertEqual(12, pl_small["totalTracks"])
        self.assertFalse(pl_small["truncated"])

        self.assertEqual(100, len(alb_big["tracks"]))
        self.assertEqual(120, alb_big["totalTracks"])
        self.assertTrue(alb_big["truncated"])

        # Client-credentials strategy also exposes totalTracks and truncated.
        def fake_get_cc(req_url, headers=None):
            if "/embed/" in req_url or "get_access_token" in req_url:
                raise RuntimeError("blocked")
            return json.dumps(api_map[req_url])

        with mock.patch.object(sleeve_spotify, "_http_get", side_effect=fake_get_cc), \
             mock.patch.object(
                 sleeve_spotify,
                 "_http_post_form",
                 return_value=json.dumps({"access_token": "cc-tok"}),
             ):
            cc_big = json.loads(
                sleeve_spotify.resolve(
                    "https://open.spotify.com/playlist/PLBIG",
                    client_id="cid",
                    client_secret="csec",
                )
            )

        self.assertTrue(cc_big["ok"])
        self.assertEqual(100, len(cc_big["tracks"]))
        self.assertEqual(250, cc_big["totalTracks"])
        self.assertTrue(cc_big["truncated"])


if __name__ == "__main__":
    unittest.main()
