#!/usr/bin/env python3
"""Unit tests for app/src/main/python/sleeve_audible_gate.py.

Precision-first: every case that used to spend hours on a partial upload must
hold before a media request, and the one correct full-length upload must still
pass. Runtimes are the live Audible figures from the research corpus.
"""

from __future__ import annotations

import json
import os
import sys
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_ROOT, "app", "src", "main", "python"))

import sleeve_audible_gate


def book(title, duration_ms, artist="Author", language=""):
    return {
        "title": title,
        "artist": artist,
        "album": "Audible",
        "durationMs": duration_ms,
        "language": language,
    }


def candidate(title, duration, uploader="Audiobook Channel", id="vid", channel=None):
    return {
        "id": id,
        "url": "https://www.youtube.com/watch?v=" + id,
        "title": title,
        "uploader": uploader,
        "channel": uploader if channel is None else channel,
        "duration": duration,
    }


def gate(track, candidates):
    return json.loads(
        sleeve_audible_gate.gate_audible(json.dumps(track), json.dumps(candidates))
    )


# 5.58 h; the correct upload is on "JunkLoader", so a substring junk rule
# would veto the only right candidate in the sample.
ATOMIC = book("Atomic Habits", 20_088_000, "James Clear")
# 21.03 h; the 7.23 h "Book One" partial is the reported 34% failure.
DUNE = book("Dune", 75_720_000, "Frank Herbert")
# 11.10 h; the 3.73 h upload is the reported 33.6% failure.
HOBBIT = book("The Hobbit", 39_960_000, "J.R.R. Tolkien")
# 8.30 h; a correct single labelled "Book 1" must not be rejected on wording.
HARRY = book(
    "Harry Potter and the Sorcerer's Stone, Book 1", 29_880_000, "J.K. Rowling"
)

# The clean split set from the research corpus: same channel, 11.90 h + 9.13 h,
# summing to 100.0% of Audible's 21.03 h. Each part alone fails the single gate.
DUNE_PARTS = [
    candidate(
        "DUNE \u2013 Book 1 | Full Audiobook | Epic Science Fiction | PART I",
        42_840,
        uploader="Starlit Pages",
        id="ArfN9gWvT3I",
    ),
    candidate(
        "DUNE \u2013 Book 1 | Full Audiobook | Epic Science Fiction | PART II",
        32_868,
        uploader="Starlit Pages",
        id="ZdRRTZUQV5I",
    ),
]


class GateVerdictTest(unittest.TestCase):
    def test_atomic_habits_full_upload_passes(self):
        result = gate(
            ATOMIC,
            [
                candidate(
                    "Atomic Habits | Full Audiobook | Unabridged",
                    20_090,
                    uploader="JunkLoader",
                    id="atomic",
                )
            ],
        )

        self.assertTrue(result["ok"])
        self.assertEqual("pass", result["verdict"])
        self.assertEqual("atomic", result["candidate_id"])
        self.assertAlmostEqual(1.0, result["ratio"], places=2)
        self.assertTrue(result["reason"])

    def test_dune_book_one_partial_holds(self):
        result = gate(
            DUNE,
            [
                candidate(
                    "Dune - Book One | Full Audiobook",
                    26_028,
                    uploader="HK Book World",
                    id="book-one",
                )
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertEqual("", result["candidate_id"])
        self.assertEqual("book-one", result["best_id"])
        self.assertAlmostEqual(0.344, result["best_ratio"], places=3)
        self.assertIn("34%", result["reason"])

    def test_hobbit_partial_holds(self):
        result = gate(
            HOBBIT,
            [
                candidate(
                    "Free audiobook - The Hobbit by JRR Tolkien",
                    13_428,
                    id="hobbit-partial",
                )
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertEqual("hobbit-partial", result["best_id"])
        self.assertAlmostEqual(0.336, result["best_ratio"], places=3)

    def test_a_different_book_with_the_full_runtime_holds(self):
        result = gate(
            DUNE,
            [
                candidate(
                    "Dune Messiah | Full Audiobook",
                    75_730,
                    id="sequel",
                )
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertEqual("", result["candidate_id"])
        self.assertIn("title", result["reason"])

    def test_a_foreign_language_edition_in_parentheses_holds(self):
        # The bracket is stripped for title scoring, so the language marker
        # must be read from the raw title before that normalization.
        result = gate(
            book("Dune", 75_720_000, "Frank Herbert", language="english"),
            [
                candidate(
                    "Dune (German) | Full Audiobook",
                    75_720,
                    id="german",
                )
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("language", result["reason"])

    def test_the_same_language_marker_still_passes(self):
        result = gate(
            book("Dune", 75_720_000, "Frank Herbert", language="german"),
            [
                candidate(
                    "Dune (German) | Full Audiobook",
                    75_720,
                    id="german",
                )
            ],
        )

        self.assertEqual("pass", result["verdict"])
        self.assertEqual("german", result["candidate_id"])

    def test_a_nationality_word_in_the_title_is_not_a_language_declaration(self):
        # "The Dutch House" is an English audiobook, not a Dutch one. Only a
        # bracketed/qualifier language tag should trigger the mismatch.
        for title in (
            "The Dutch House",
            "The German House",
            "The French Lieutenant's Woman",
        ):
            with self.subTest(title=title):
                result = gate(
                    book(title, 5 * 3600 * 1000, "Author", language="english"),
                    [candidate(title + " | Full Audiobook", 5 * 3600, id="full")],
                )
                self.assertEqual("pass", result["verdict"])

    def test_unknown_catalogue_language_does_not_guess(self):
        result = gate(
            book("Dune", 75_720_000, "Frank Herbert"),
            [
                candidate(
                    "Dune (German) | Full Audiobook",
                    75_720,
                    id="german",
                )
            ],
        )

        # No catalogue language means no language verdict; duration and title
        # still decide. This is the documented phase-1 limitation.
        self.assertEqual("pass", result["verdict"])

    def test_a_summary_never_passes_even_at_full_runtime(self):
        result = gate(
            DUNE,
            [
                candidate(
                    "Dune Summary & Analysis | Book Review",
                    75_700,
                    id="summary",
                )
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("junk", result["reason"])

    def test_no_candidates_is_no_source(self):
        result = gate(DUNE, [])

        self.assertTrue(result["ok"])
        self.assertEqual("no_source", result["verdict"])
        self.assertEqual("", result["best_id"])
        self.assertEqual(0.0, result["best_ratio"])


class BylineTitleTest(unittest.TestCase):
    """"Book Title by Author" is a common upload spelling on YouTube."""

    # The live Atomic Habits shortlist: the top entries are the exact book at
    # 100% of runtime, but symmetric F1 counted "by james clear" as noise.
    ATOMIC_BYLINE = book(
        "Atomic Habits", 20_088_000, "James Clear (narrated by James Clear)"
    )

    def test_the_real_atomic_habits_upload_passes(self):
        result = gate(
            self.ATOMIC_BYLINE,
            [
                candidate(
                    "Atomic Habits by James Clear Full Audiobook",
                    20_090,
                    uploader="JunkLoader",
                    id="atomic-byline",
                )
            ],
        )

        self.assertEqual("pass", result["verdict"])
        self.assertEqual("atomic-byline", result["candidate_id"])

    def test_a_byline_upload_still_holds_on_duration(self):
        result = gate(
            self.ATOMIC_BYLINE,
            [
                candidate(
                    "Atomic Habits by James Clear Full Audiobook",
                    14_865,  # 74% of the book
                    uploader="JunkLoader",
                    id="atomic-byline-partial",
                )
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("no full-length candidate", result["reason"])

    def test_the_sequel_guard_survives_the_byline(self):
        result = gate(
            DUNE,
            [candidate("Dune Messiah by Frank Herbert", 75_720, id="messiah")],
        )

        self.assertEqual("hold", result["verdict"])

    def test_a_by_inside_the_real_title_is_not_stripped(self):
        result = gate(
            book("By the River", 10_800_000, "Jane Doe"),
            [candidate("By the River by Jane Doe", 10_800, id="river")],
        )

        self.assertEqual("pass", result["verdict"])


class GatePolicyTest(unittest.TestCase):
    def test_a_full_length_single_titled_book_one_passes(self):
        result = gate(
            HARRY,
            [
                candidate(
                    "Harry Potter & the Sorcerer's Stone: Book 1 - Narrated by Jim Dale",
                    29_916,
                    id="hp",
                )
            ],
        )

        self.assertEqual("pass", result["verdict"])
        self.assertEqual("hp", result["candidate_id"])
        self.assertAlmostEqual(1.001, result["ratio"], places=3)

    def test_duration_tolerance_is_two_percent_with_a_two_minute_floor(self):
        within = gate(DUNE, [candidate("Dune | Full Audiobook", 75_720 + 1_400, id="near")])
        outside = gate(DUNE, [candidate("Dune | Full Audiobook", 77_900, id="short")])

        self.assertEqual("pass", within["verdict"])
        self.assertEqual("hold", outside["verdict"])

    def test_two_minute_floor_applies_to_short_books(self):
        short = book("A Short Book", 30 * 60 * 1000)  # 30 min runtime
        # 2% would be 36 s; 100 s early is outside that but inside 120 s.
        result = gate(short, [candidate("A Short Book | Audiobook", 30 * 60 - 100, id="s")])

        self.assertEqual("pass", result["verdict"])

    def test_an_unknown_candidate_duration_fails(self):
        for duration in (None, 0, "", "unknown"):
            with self.subTest(duration=duration):
                result = gate(DUNE, [candidate("Dune | Full Audiobook", duration, id="x")])

                self.assertEqual("hold", result["verdict"])
                self.assertEqual("", result["best_id"])
                self.assertIn("duration", result["reason"])

    def test_an_unknown_expected_runtime_holds(self):
        result = gate(
            book("Dune", 0),
            [candidate("Dune | Full Audiobook", 75_720, id="full")],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("expected runtime", result["reason"])

    def test_a_junk_channel_vetoes_a_matching_full_length_upload(self):
        result = gate(
            DUNE,
            [
                candidate(
                    "Dune | Full Audiobook",
                    75_720,
                    uploader="Sleep Story Central",
                    id="sleep",
                )
            ],
        )

        self.assertEqual("hold", result["verdict"])

    def test_the_display_pick_skips_a_junk_candidate(self):
        result = gate(
            DUNE,
            [
                candidate("Dune Summary & Analysis", 75_700, id="summary"),
                candidate("Dune - Book One | Full Audiobook", 26_028, id="partial"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        # The summary is numerically closest but is not a preview of the book.
        self.assertEqual("partial", result["best_id"])

    def test_the_best_passing_candidate_wins_even_when_it_is_not_rank_one(self):
        result = gate(
            DUNE,
            [
                candidate("Dune - Book One | Full Audiobook", 26_028, id="partial"),
                candidate("Dune | Complete Audiobook", 75_700, id="full"),
            ],
        )

        self.assertEqual("pass", result["verdict"])
        self.assertEqual("full", result["candidate_id"])
        self.assertEqual("full", result["best_id"])
        self.assertAlmostEqual(0.9997, result["ratio"], places=3)

    def test_a_numeric_title_is_not_read_as_leading_numbering(self):
        book1984 = book("1984", 5 * 3600 * 1000, "George Orwell")
        result = gate(
            book1984,
            [
                candidate("4 | Full Audiobook", 5 * 3600, id="four"),
                candidate("1984 | Full Audiobook", 5 * 3600, id="nineteen-eighty-four"),
            ],
        )

        self.assertEqual("pass", result["verdict"])
        # Without the separator requirement "1984" and "4" both collapse to
        # "4", and the shorter title wins the tie on rank order.
        self.assertEqual("nineteen-eighty-four", result["candidate_id"])

    def test_two_partials_never_form_a_pass(self):
        # 37,000 + 38,000 s is 99.05% of Dune: by the phase-2 rules that is a
        # complete 2-part set, so the verdict is `split`. The guarantee this
        # test encodes is unchanged - two partials never become a `pass` with
        # one of them as the candidate.
        result = gate(
            DUNE,
            [
                candidate("Dune | Full Audiobook Part 1", 37_000, id="p1"),
                candidate("Dune | Full Audiobook Part 2", 38_000, id="p2"),
            ],
        )

        self.assertNotEqual("pass", result["verdict"])
        self.assertEqual("", result["candidate_id"])
        self.assertEqual("split", result["verdict"])
        self.assertEqual(2, result["part_count"])

    def test_two_partials_that_do_not_sum_hold(self):
        result = gate(
            DUNE,
            [
                candidate("Dune | Full Audiobook Part 1", 26_028, id="a"),
                candidate("Dune | Full Audiobook Part 2", 26_028, id="b"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertEqual("", result["candidate_id"])
        self.assertIn("part sum 69% of the book", result["reason"])

    def test_fractional_and_readable_durations_are_converted_once(self):
        # Ranker durations are whole seconds; a millisecond value here would
        # read as 0.1% of the book and must fail.
        result = gate(
            DUNE,
            [
                candidate("Dune | Full Audiobook", 75_720.0, id="seconds"),
                candidate("Dune | Full Audiobook (ms mix-up)", 75_720_000, id="millis"),
            ],
        )

        self.assertEqual("pass", result["verdict"])
        self.assertEqual("seconds", result["candidate_id"])

    def test_malformed_input_reports_a_bridge_failure(self):
        result = json.loads(sleeve_audible_gate.gate_audible("{not json", "[]"))

        self.assertFalse(result["ok"])
        self.assertIn("error", result)

    def test_the_reply_carries_exactly_the_bridge_contract(self):
        result = gate(DUNE, [candidate("Dune | Full Audiobook", 75_700, id="full")])

        self.assertEqual(
            {
                "ok",
                "verdict",
                "reason",
                "candidate_id",
                "ratio",
                "best_id",
                "best_ratio",
            },
            set(result),
        )


class GateSplitTest(unittest.TestCase):
    """Phase 2: a complete contiguous same-channel part set may download."""

    def test_the_dune_part_set_splits(self):
        result = gate(DUNE, DUNE_PARTS)

        self.assertEqual("split", result["verdict"])
        self.assertEqual("", result["candidate_id"])
        self.assertEqual(2, result["part_count"])
        self.assertEqual([1, 2], [part["part_index"] for part in result["parts"]])
        self.assertEqual(
            ["ArfN9gWvT3I", "ZdRRTZUQV5I"],
            [part["id"] for part in result["parts"]],
        )
        self.assertEqual(
            [42_840, 32_868], [part["duration"] for part in result["parts"]]
        )
        self.assertAlmostEqual(0.99984, result["ratio"], places=4)
        self.assertIn("2-part set", result["reason"])
        self.assertIn("100%", result["reason"])
        self.assertEqual("ArfN9gWvT3I", result["best_id"])

    def test_the_split_reply_carries_the_bridge_contract(self):
        result = gate(DUNE, DUNE_PARTS)

        self.assertEqual(
            {
                "ok",
                "verdict",
                "reason",
                "candidate_id",
                "ratio",
                "best_id",
                "best_ratio",
                "parts",
                "part_count",
            },
            set(result),
        )
        self.assertEqual(
            {"id", "url", "title", "duration", "part_index", "channel"},
            set(result["parts"][0]),
        )
        self.assertEqual(
            "https://www.youtube.com/watch?v=ArfN9gWvT3I",
            result["parts"][0]["url"],
        )
        self.assertEqual("Starlit Pages", result["parts"][0]["channel"])

    def test_parts_group_across_different_marker_spellings(self):
        # "Book One | Part I" and "Part II" must land in one group; the core
        # title normalizer strips both marker families.
        result = gate(
            DUNE,
            [
                candidate(
                    "Dune - Book One | Part I",
                    42_840,
                    uploader="Starlit Pages",
                    id="one",
                ),
                candidate(
                    "Dune Part II", 32_868, uploader="Starlit Pages", id="two"
                ),
            ],
        )

        self.assertEqual("split", result["verdict"])
        self.assertEqual([1, 2], [part["part_index"] for part in result["parts"]])

    def test_parts_are_sorted_by_number_not_by_search_rank(self):
        result = gate(DUNE, list(reversed(DUNE_PARTS)))

        self.assertEqual("split", result["verdict"])
        self.assertEqual(
            ["ArfN9gWvT3I", "ZdRRTZUQV5I"],
            [part["id"] for part in result["parts"]],
        )

    def test_a_single_full_upload_still_wins_over_a_part_set(self):
        result = gate(
            DUNE,
            [
                candidate(
                    "Dune | Full Audiobook", 75_700, uploader="Other Channel", id="full"
                )
            ]
            + DUNE_PARTS,
        )

        self.assertEqual("pass", result["verdict"])
        self.assertEqual("full", result["candidate_id"])

    def test_a_missing_part_holds_and_names_it(self):
        result = gate(
            DUNE,
            [candidate("Dune | Full Audiobook | Part 1 of 2", 42_840, id="p1")],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("missing part 2", result["reason"])

    def test_a_gap_in_the_part_numbers_holds(self):
        result = gate(
            DUNE,
            [
                candidate("Dune Part 1", 25_000, id="p1"),
                candidate("Dune Part 3", 25_000, id="p3"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("missing part 2", result["reason"])

    def test_a_junk_part_holds(self):
        result = gate(
            DUNE,
            [
                candidate("Dune | Full Audiobook Part 1", 42_840, id="p1"),
                candidate("Dune | Full Audiobook Part 2 (Sample)", 32_868, id="p2"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("junk part", result["reason"])

    def test_a_repeated_video_id_is_deduped(self):
        # Dirty playlists repeat one upload many times (research 3.3); the same
        # id twice must not read as two parts.
        result = gate(DUNE, DUNE_PARTS + [DUNE_PARTS[0]])

        self.assertEqual("split", result["verdict"])
        self.assertEqual(2, result["part_count"])
        self.assertEqual([1, 2], [part["part_index"] for part in result["parts"]])

    def test_a_duplicate_part_number_holds(self):
        result = gate(
            DUNE,
            [
                candidate("Dune | Full Audiobook Part 1", 40_000, id="dup-a"),
                candidate("Dune | Full Audiobook Part 1", 40_000, id="dup-b"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("duplicate part 1", result["reason"])

    def test_a_mixed_channel_set_holds(self):
        result = gate(
            DUNE,
            [
                candidate(
                    "Dune | Full Audiobook Part 1",
                    42_840,
                    uploader="Starlit Pages",
                    id="p1",
                ),
                candidate(
                    "Dune | Full Audiobook Part 2",
                    32_868,
                    uploader="Kadir Kacar",
                    id="p2",
                ),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("mixed channels", result["reason"])

    def test_a_reupload_elsewhere_does_not_hide_a_sum_failure(self):
        # The set exists on one channel with the wrong total; a Part 2
        # re-uploaded elsewhere must not turn that into "mixed channels".
        result = gate(
            DUNE,
            [
                candidate("Dune Part 1", 42_840, uploader="Starlit Pages", id="a1"),
                candidate("Dune Part 2", 26_028, uploader="Starlit Pages", id="a2"),
                candidate("Dune Part 2", 32_868, uploader="Kadir Kacar", id="b2"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("part sum 91% of the book", result["reason"])

    def test_a_set_containing_a_thirty_four_percent_part_holds(self):
        result = gate(
            DUNE,
            [
                candidate("Dune | Full Audiobook Part 1", 42_840, id="p1"),
                candidate("Dune | Full Audiobook Part 2", 26_028, id="p2"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("part sum 91% of the book", result["reason"])

    def test_twenty_parts_still_split(self):
        result = gate(
            DUNE,
            [
                candidate("Dune | Full Audiobook Part %d" % n, 3_786, id="p%d" % n)
                for n in range(1, 21)
            ],
        )

        self.assertEqual("split", result["verdict"])
        self.assertEqual(20, result["part_count"])

    def test_a_part_under_a_minute_holds(self):
        # Sums to exactly 21.03 h, but no single part is near full length, so
        # only the part-set rule can reject it.
        result = gate(
            DUNE,
            [
                candidate("Dune Part 1", 59, id="p1"),
                candidate("Dune Part 2", 37_000, id="p2"),
                candidate("Dune Part 3", 38_661, id="p3"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("under a minute", result["reason"])

    def test_twenty_one_parts_hold(self):
        result = gate(
            DUNE,
            [
                candidate("Dune | Full Audiobook Part %d" % n, 3_605, id="p%d" % n)
                for n in range(1, 22)
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("21 parts", result["reason"])

    def test_a_foreign_language_part_set_holds(self):
        result = gate(
            book("Dune", 75_720_000, "Frank Herbert", language="english"),
            [
                candidate("Dune (German) | Part 1", 42_840, id="p1"),
                candidate("Dune (German) | Part 2", 32_868, id="p2"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("language", result["reason"])

    def test_a_part_over_the_twelve_hour_cap_holds(self):
        result = gate(
            DUNE,
            [
                candidate("Dune Part 1", 43_300, id="p1"),
                candidate("Dune Part 2", 32_420, id="p2"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("12 h", result["reason"])

    def test_a_zero_based_part_set_is_contiguous(self):
        result = gate(
            DUNE,
            [
                candidate("Dune Part 0", 42_840, id="p0"),
                candidate("Dune Part 1", 32_868, id="p1"),
            ],
        )

        self.assertEqual("split", result["verdict"])
        self.assertEqual([0, 1], [part["part_index"] for part in result["parts"]])

    def test_a_part_set_for_a_different_book_holds(self):
        # "Dune Messiah" is 0.67 F1 against Dune; its parts must never sum into
        # Dune's runtime, however clean the set looks.
        result = gate(
            DUNE,
            [
                candidate("Dune Messiah | Part 1", 42_840, id="m1"),
                candidate("Dune Messiah | Part 2", 32_868, id="m2"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertNotIn("parts", result)


class PartEditionIntegrityTest(unittest.TestCase):
    def test_two_narrators_in_one_set_hold(self):
        result = gate(
            DUNE,
            [
                candidate("Dune - Part 1 - Narrated by Scott Brick", 42_840, uploader="One Channel", id="p1"),
                candidate("Dune - Part 2 - Narrated by Simon Vance", 32_880, uploader="One Channel", id="p2"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("mixed narrators", result["reason"])

    def test_an_abridged_unabridged_mix_holds(self):
        result = gate(
            DUNE,
            [
                candidate("Dune - Part 1 (Unabridged)", 26_000, uploader="One Channel", id="p1"),
                candidate("Dune - Part 2 (Abridged)", 25_000, uploader="One Channel", id="p2"),
                candidate("Dune - Part 3", 24_720, uploader="One Channel", id="p3"),
            ],
        )

        self.assertEqual("hold", result["verdict"])
        self.assertIn("mixed editions", result["reason"])


class PartMarkerTest(unittest.TestCase):
    def test_every_marker_form_is_read_from_the_raw_title(self):
        cases = {
            "Dune Part 1 of 2": (1, 2),
            "Dune Part 1/2": (1, 2),
            "Dune Part 1": (1, None),
            "Dune Part I": (1, None),
            "Dune (1/2)": (1, 2),
            "Dune Book 1": (1, None),
            "Dune Vol. 1": (1, None),
            "Dune Chapter 1": (1, None),
            "Dune Part Two": (2, None),
            "Dune Part IV": (4, None),
        }
        for title, expected in cases.items():
            with self.subTest(title=title):
                self.assertEqual(expected, sleeve_audible_gate._parse_part(title))

    def test_an_explicit_part_marker_beats_book_vol_and_chapter(self):
        self.assertEqual(
            (2, None),
            sleeve_audible_gate._parse_part("Dune - Book 1 | PART II | Chapter 9"),
        )

    def test_a_title_without_a_marker_has_no_part(self):
        for title in ("Dune | Full Audiobook", "1984", "Dune Messiah"):
            with self.subTest(title=title):
                self.assertEqual((None, None), sleeve_audible_gate._parse_part(title))


if __name__ == "__main__":
    unittest.main()
