#!/usr/bin/env python3
"""Unit tests for app/src/main/python/sleeve_spotify.py rank_candidates.

The long-form cases are the live corpus: three Audible links whose pools
carried only a fraction of the book and were still queued.
"""

from __future__ import annotations

import json
import os
import sys
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_ROOT, "app", "src", "main", "python"))

import sleeve_spotify


def book(title="Dune", artist="Frank Herbert", duration_ms=75_720_000):
    return {"title": title, "artist": artist, "album": "Audible", "durationMs": duration_ms}


def candidate(title, duration, uploader="Audiobook Channel", id="vid"):
    return {
        "id": id,
        "url": "https://www.youtube.com/watch?v=" + id,
        "title": title,
        "uploader": uploader,
        "channel": uploader,
        "duration": duration,
    }


def rank(track, candidates, strictness=1, top_n=3):
    return json.loads(
        sleeve_spotify.rank_candidates(
            json.dumps(track), json.dumps(candidates), strictness, top_n
        )
    )


def veto_reasons(result):
    return [entry["reason"] for entry in result.get("rejected", [])]


MARTIAN = book("The Martian", "Andy Weir", 39_540_000)
DCC = book("Dungeon Crawler Carl", "Matt Dinniman", 48_660_000)
DUNE = book("Dune", "Frank Herbert", 75_720_000)


class LongFormDurationVetoTest(unittest.TestCase):
    def test_a_fifty_second_trailer_cannot_be_the_martian(self):
        result = rank(
            MARTIAN,
            [candidate("The Martian by Andy Weir [Full Audiobook Free]", 50.0)],
        )
        self.assertFalse(result["ok"])
        self.assertEqual(
            veto_reasons(result),
            ["duration 0% of book — below 25% floor, cannot be the full book"],
        )

    def test_a_seventy_nine_minute_excerpt_cannot_be_dungeon_crawler_carl(self):
        result = rank(
            DCC,
            [candidate("20: Dungeon Crawler Carl: Book 1 - Matt Dinniman", 4_762.0)],
        )
        self.assertFalse(result["ok"])
        self.assertEqual(
            veto_reasons(result),
            ["duration 10% of book — below 25% floor, cannot be the full book"],
        )

    def test_an_eighty_six_minute_documentary_cannot_be_dune(self):
        result = rank(DUNE, [candidate("The real Dune", 5_172.0)])
        self.assertFalse(result["ok"])
        self.assertEqual(
            veto_reasons(result),
            ["duration 7% of book — below 25% floor, cannot be the full book"],
        )

    def test_everything_vetoed_reports_the_honest_error(self):
        result = rank(MARTIAN, [candidate("The Martian by Andy Weir", 50.0)])
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "no candidate matched the book's duration/length")
        self.assertEqual(len(result["rejected"]), 1)

    def test_a_vetoed_candidate_never_reaches_the_review_list(self):
        result = rank(
            DUNE,
            [
                candidate("Dune", 5_172.0, id="short"),
                candidate("Dune", 43_000.0, id="part"),
            ],
        )
        self.assertTrue(result["ok"])
        self.assertNotIn("short", [c["id"] for c in result["candidates"]])
        self.assertEqual([c["id"] for c in result["candidates"]], ["part"])

    def test_a_part_above_the_floor_is_still_scored_as_before(self):
        result = rank(DUNE, [candidate("Dune", 43_000.0)])
        self.assertTrue(result["ok"])
        self.assertEqual([c["score"] for c in result["candidates"]], [65.5])
        self.assertIn("duration differs 43%", result["candidates"][0]["reasons"])
        self.assertEqual(result["rejected"], [])

    def test_the_corpus_part_is_floor_filtered_not_vetoed(self):
        # The real 57% upload is decorated, so it fails the 60 point name
        # floor today. That is a ranking-quality gap, not the duration veto.
        result = rank(DUNE, [candidate("Dune - Book One | Full Audiobook", 43_000.0)])
        self.assertFalse(result["ok"])
        self.assertEqual(result["rejected"], [])

    def test_unknown_duration_is_judged_as_today(self):
        result = rank(DUNE, [candidate("Dune", None)])
        self.assertTrue(result["ok"])
        self.assertIn("duration unknown", result["candidates"][0]["reasons"])
        self.assertEqual(result["rejected"], [])


class MusicRankingTest(unittest.TestCase):
    def test_the_veto_does_not_touch_a_music_track(self):
        result = rank(
            {"title": "Song Name", "artist": "The Artist", "album": "", "durationMs": 200_000},
            [candidate("Song Name", 20.0, uploader="The Artist")],
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["candidates"][0]["score"], 81.2)
        self.assertEqual(result["rejected"], [])

    def test_a_floor_rejection_keeps_the_old_music_error(self):
        result = rank(
            {"title": "Song Name", "artist": "The Artist", "album": "", "durationMs": 200_000},
            [candidate("Unrelated Upload", 200.0, uploader="Nobody")],
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "no candidate passed the floor")


if __name__ == "__main__":
    unittest.main()
