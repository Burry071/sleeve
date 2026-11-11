#!/usr/bin/env python3
"""Unit tests for app/src/main/python/sleeve_spotify.py rank_candidates.

The long-form cases are the live corpus: three Audible links whose pools
carried only a fraction of the book and were still queued. The Dune 57%
fixture is the decorated upload that used to fail the name floor purely on
"- Book One | Full Audiobook" wording.
"""

from __future__ import annotations

import json
import os
import sys
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_ROOT, "app", "src", "main", "python"))

import sleeve_audible_gate
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


def name_points(cand):
    """The "title NN" component of one ranked candidate."""
    for reason in cand["reasons"]:
        if reason.startswith("title "):
            return int(reason.split()[1])
    raise AssertionError("no title reason in %r" % (cand,))


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

    def test_the_corpus_part_is_accepted_once_decorated(self):
        # The real 57% upload: "Dune - Book One | Full Audiobook", 43 000 s of
        # 75 720 s. The decoration is stripped for long-form name scoring, so
        # it clears the 60 point name floor and stays above the 25% duration
        # floor. 65.5 is the pinned regression score: identical to the clean
        # "Dune" upload in test_a_part_above_the_floor_is_still_scored_as_before.
        result = rank(DUNE, [candidate("Dune - Book One | Full Audiobook", 43_000.0)])
        self.assertTrue(result["ok"])
        self.assertEqual(result["rejected"], [])
        self.assertGreaterEqual(name_points(result["candidates"][0]), 60)
        self.assertEqual(result["candidates"][0]["score"], 65.5)

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

    def test_a_remix_still_loses_fifteen_name_points(self):
        # Music control: the long-form strip must not touch the forbidden-word
        # penalty. A bracketed "(Remix)" is unmatchable decoration for _slug,
        # but the raw form still carries the word, so the name score drops 15
        # (0.4 * 15 = 6 points of final score).
        result = rank(
            {"title": "Song Name", "artist": "The Artist", "album": "", "durationMs": 200_000},
            [
                candidate("Song Name", 200.0, uploader="The Artist", id="clean"),
                candidate("Song Name (Remix)", 200.0, uploader="The Artist", id="remix"),
            ],
        )
        self.assertTrue(result["ok"])
        by_id = {c["id"]: c for c in result["candidates"]}
        self.assertEqual(name_points(by_id["clean"]), 100)
        self.assertEqual(name_points(by_id["remix"]), 85)
        self.assertAlmostEqual(by_id["clean"]["score"] - by_id["remix"]["score"], 6.0)

    def test_a_music_title_saying_book_one_is_not_stripped(self):
        # Music control for the other direction: the strip is gated on
        # durationMs > 1800_000, so a 3:20 song keeps the words and keeps
        # losing name points for them (67, not a stripped 100).
        track = {"title": "Song Name", "artist": "The Artist", "album": "", "durationMs": 200_000}
        result = rank(track, [candidate("Song Name Book One", 200.0, uploader="The Artist")])
        self.assertTrue(result["ok"])
        self.assertEqual(name_points(result["candidates"][0]), 67)
        self.assertEqual(result["candidates"][0]["score"], 86.7)
        # Same title scored as long-form would be stripped to an exact match;
        # the duration gate is what keeps the music score honest.
        self.assertEqual(
            round(
                sleeve_spotify._name_score(
                    "Song Name", "The Artist", "Song Name Book One", long_form=True
                )
            ),
            100,
        )


class CandidateMetadataEchoTest(unittest.TestCase):
    def test_every_kept_candidate_and_best_echo_uploader_channel_duration(self):
        result = rank(
            DUNE,
            [
                candidate(
                    "Dune - Book One | Full Audiobook", 43_000.0, uploader="Full Books", id="full"
                ),
                candidate("Dune", None, uploader="Other Channel", id="unknown"),
            ],
            top_n=3,
        )
        self.assertTrue(result["ok"])
        echoed = {
            c["id"]: (c["uploader"], c["channel"], c["duration"])
            for c in result["candidates"]
        }
        self.assertEqual(echoed["full"], ("Full Books", "Full Books", 43000))
        # Unknown duration echoes as null; the caller keeps 0 and hides it.
        self.assertEqual(echoed["unknown"], ("Other Channel", "Other Channel", None))
        # best is the top candidate plus the ok/candidates/rejected keys.
        self.assertEqual(result["id"], result["candidates"][0]["id"])
        self.assertEqual(
            (result["uploader"], result["channel"], result["duration"]),
            (
                result["candidates"][0]["uploader"],
                result["candidates"][0]["channel"],
                result["candidates"][0]["duration"],
            ),
        )


class RankThenGateIntegrationTest(unittest.TestCase):
    """The split path must survive the ranker that feeds the gate.

    Phase 2 review 2.1: the phase-1 ranker used to drop ``Part N`` uploads on
    the name floor and the 25% duration veto, so the gate never saw the set
    that would have split. These are the join tests, not gate-only tests.
    """

    def dune_parts(self):
        return [
            candidate(
                "DUNE - Book 1 | Full Audiobook | Epic Science Fiction | PART I",
                42_840,
                uploader="Starlit Pages",
                id="part1",
            ),
            candidate(
                "DUNE - Book 1 | Full Audiobook | Epic Science Fiction | PART II",
                32_880,
                uploader="Starlit Pages",
                id="part2",
            ),
            candidate("Dune - Book One | Full Audiobook", 26_028, id="partial"),
        ]

    def test_dune_part_set_survives_ranking_and_splits(self):
        ranked = rank(DUNE, self.dune_parts(), top_n=10)
        self.assertTrue(ranked["ok"])
        ids = [c["id"] for c in ranked["candidates"]]
        self.assertIn("part1", ids)
        self.assertIn("part2", ids)

        gate = json.loads(
            sleeve_audible_gate.gate_audible(
                json.dumps({**DUNE, "language": "english"}),
                json.dumps(ranked["candidates"]),
            )
        )
        self.assertEqual("split", gate["verdict"])
        self.assertEqual([1, 2], [p["part_index"] for p in gate["parts"]])

    def test_a_five_part_set_is_not_vetoed_before_the_gate(self):
        parts = [
            candidate(
                "Dune | Full Audiobook | Part %d" % i,
                75_720 // 5,
                uploader="One Channel",
                id="p%d" % i,
            )
            for i in range(1, 6)
        ]
        ranked = rank(DUNE, parts, top_n=10)
        self.assertTrue(ranked["ok"])
        self.assertEqual(
            ["p1", "p2", "p3", "p4", "p5"],
            [c["id"] for c in ranked["candidates"]],
        )


if __name__ == "__main__":
    unittest.main()
