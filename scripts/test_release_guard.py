#!/usr/bin/env python3
"""Unit tests for scripts/release_guard.py (no network)."""

from __future__ import annotations

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import release_guard as guard


VERSION_KT = """
sealed class Version(val major: Int, val minor: Int, val patch: Int, val build: Int = 0) {
    abstract val name: String
    abstract val code: Long
}

val currentVersion: Version =
    Version.Beta(versionMajor = 2, versionMinor = 0, versionPatch = 0, versionBuild = 11)
"""

STABLE_KT = """
val currentVersion: Version =
    Version.Stable(versionMajor = 2, versionMinor = 1, versionPatch = 0)
"""

RC_KT = """
val currentVersion: Version =
    Version.ReleaseCandidate(
        versionMajor = 2,
        versionMinor = 1,
        versionPatch = 0,
        versionBuild = 3
    )
"""

GRADLE_KT = """
    defaultConfig {
        versionCode = 200_000_310
    }
"""


def make_record(entries=None):
    return {"schema": 1, "repo": "Burry071/sleeve", "releases": entries or []}


def entry(tag, base, shipped, distributed=True, published=True, **extra):
    data = {
        "tag": tag,
        "base_version_code": base,
        "shipped_version_code": shipped,
        "distributed": distributed,
        "published": published,
    }
    data.update(extra)
    return data


class ParseVersionKtTest(unittest.TestCase):
    def test_beta(self):
        spec = guard.parse_version_kt(VERSION_KT)
        self.assertEqual(spec.name, "2.0.0-beta.11")
        self.assertEqual(spec.tag, "v2.0.0-beta.11")
        self.assertEqual(spec.code, 200_000_310)

    def test_stable_has_no_build(self):
        spec = guard.parse_version_kt(STABLE_KT)
        self.assertEqual(spec.name, "2.1.0")
        self.assertEqual(spec.code, 201_000_400)

    def test_release_candidate(self):
        spec = guard.parse_version_kt(RC_KT)
        self.assertEqual(spec.name, "2.1.0-rc.3")
        self.assertEqual(spec.code, 201_000_330)

    def test_missing_current_version_raises(self):
        with self.assertRaises(guard.GuardError):
            guard.parse_version_kt("val something = 1")


class ParseGradleTest(unittest.TestCase):
    def test_underscores_are_stripped(self):
        self.assertEqual(guard.parse_gradle_version_code(GRADLE_KT), 200_000_310)

    def test_non_literal_fails(self):
        with self.assertRaises(guard.GuardError):
            guard.parse_gradle_version_code("versionCode = currentVersionCode")


class ShippedCodesTest(unittest.TestCase):
    def test_no_splits_uses_first_abi(self):
        codes = guard.compute_shipped_codes(200_000_300, "arm64-v8a;x86_64", False)
        self.assertEqual(codes, {"arm64-v8a": 200_000_302})

    def test_split_builds_apply_each_offset(self):
        codes = guard.compute_shipped_codes(200_000_300, "arm64-v8a;x86_64", True)
        self.assertEqual(codes, {"arm64-v8a": 200_000_302, "x86_64": 200_000_304})

    def test_unknown_abi_fails(self):
        with self.assertRaises(guard.GuardError):
            guard.compute_shipped_codes(1, "mips", False)

    def test_empty_filters_fail(self):
        with self.assertRaises(guard.GuardError):
            guard.compute_shipped_codes(1, " ; ", False)


class RecordTest(unittest.TestCase):
    def test_round_trip_and_upsert(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "record.json")
            record = guard.empty_record()
            guard.upsert_record(record, entry("v2.0.0-beta.10", 300, 302))
            guard.upsert_record(record, entry("v2.0.0-beta.3", 230, 232))
            guard.save_record(path, record)
            loaded = guard.load_record(path)
            self.assertEqual([item["tag"] for item in loaded["releases"]], [
                "v2.0.0-beta.3",
                "v2.0.0-beta.10",
            ])
            guard.upsert_record(loaded, {"tag": "v2.0.0-beta.10", "note": "updated"})
            self.assertEqual(len(loaded["releases"]), 2)
            self.assertEqual(loaded["releases"][1]["note"], "updated")
            self.assertEqual(loaded["releases"][1]["shipped_version_code"], 302)

    def test_highest_distributed_code_skips_undistributed(self):
        record = make_record(
            [
                entry("v2.0.0-beta.10", 300, 302),
                entry("v2.0.0-beta.6", 260, 262, distributed=False),
            ]
        )
        highest, owner = guard.highest_distributed_code(record)
        self.assertEqual(highest, 302)
        self.assertEqual(owner["tag"], "v2.0.0-beta.10")

    def test_newest_certificate(self):
        record = make_record(
            [
                entry("a", 1, 2, cert_sha256=None),
                entry("b", 3, 4, cert_sha256="A" * 64),
                entry("c", 5, 6, cert_sha256="b" * 64),
            ]
        )
        self.assertEqual(guard.newest_certificate(record), "b" * 64)


class CheckReleaseTest(unittest.TestCase):
    def spec(self):
        return guard.parse_version_kt(VERSION_KT)

    def record(self):
        return make_record([entry("v2.0.0-beta.10", 200_000_300, 200_000_302)])

    def codes(self):
        return {"arm64-v8a": 200_000_312}

    def levels(self, issues):
        return [issue.level for issue in issues]

    def test_pass(self):
        issues = guard.check_release(
            "v2.0.0-beta.11",
            self.spec(),
            200_000_310,
            self.codes(),
            self.record(),
            ["v2.0.0-beta.10"],
        )
        self.assertNotIn("fail", self.levels(issues))

    def test_tag_mismatch_fails(self):
        issues = guard.check_release(
            "v2.0.0-beta.12", self.spec(), 200_000_310, self.codes(), self.record(), None
        )
        self.assertIn("fail", self.levels(issues))

    def test_gradle_mismatch_fails(self):
        issues = guard.check_release(
            "v2.0.0-beta.11", self.spec(), 200_000_300, self.codes(), self.record(), None
        )
        self.assertIn("fail", self.levels(issues))

    def test_non_monotonic_code_fails(self):
        record = make_record([entry("v2.0.0-beta.10", 200_000_300, 200_000_312)])
        issues = guard.check_release(
            "v2.0.0-beta.11", self.spec(), 200_000_310, self.codes(), record, None
        )
        self.assertIn("fail", self.levels(issues))

    def test_already_published_tag_fails(self):
        issues = guard.check_release(
            "v2.0.0-beta.10",
            self.spec(),
            200_000_300,
            self.codes(),
            self.record(),
            ["v2.0.0-beta.10"],
        )
        self.assertIn("fail", self.levels(issues))

    def test_stale_record_fails(self):
        issues = guard.check_release(
            "v2.0.0-beta.11",
            self.spec(),
            200_000_310,
            self.codes(),
            self.record(),
            ["v2.0.0-beta.10", "v2.0.0-beta.9"],
        )
        self.assertIn("fail", self.levels(issues))

    def test_recorded_deletion_is_not_a_failure(self):
        record = make_record(
            [
                entry("v2.0.0-beta.10", 200_000_300, 200_000_302),
                entry("v2.0.0-beta.7", 200_000_270, 200_000_272, published=False, release_deleted=True),
            ]
        )
        issues = guard.check_release(
            "v2.0.0-beta.11",
            self.spec(),
            200_000_310,
            self.codes(),
            record,
            ["v2.0.0-beta.10"],
        )
        self.assertNotIn("fail", self.levels(issues))
        self.assertIn("info", self.levels(issues))


if __name__ == "__main__":
    unittest.main()
