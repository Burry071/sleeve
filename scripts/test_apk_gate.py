#!/usr/bin/env python3
"""Unit tests for scripts/apk_gate.py (no Android SDK needed)."""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import apk_gate as gate


def badging(code=201_000_220, name="com.sleeve.app", version_name="2.1.0-beta.2"):
    return (
        f"package: name='{name}' versionCode='{code}' versionName='{version_name}' "
        "platformBuildVersionName='15' platformBuildVersionCode='35' "
        "compileSdkVersion='35' compileSdkVersionCodename='15'\n"
        "sdkVersion:'24'\ntargetSdkVersion:'35'\n"
    )


def badge(path, code=201_000_220, name="com.sleeve.app", version_name="2.1.0-beta.2"):
    return gate.parse_badging(path, badging(code, name, version_name))


class ParseBadgingTest(unittest.TestCase):
    def test_reads_package_line(self):
        parsed = gate.parse_badging("Sleeve-arm64-release.apk", badging())
        self.assertEqual(parsed.package, "com.sleeve.app")
        self.assertEqual(parsed.version_code, 201_000_220)
        self.assertEqual(parsed.version_name, "2.1.0-beta.2")

    def test_missing_package_line_fails(self):
        with self.assertRaises(gate.GateError):
            gate.parse_badging("x.apk", "sdkVersion:'24'\n")

    def test_non_numeric_code_is_none(self):
        parsed = gate.parse_badging(
            "x.apk",
            "package: name='com.sleeve.app' versionCode='' versionName='1.0'\n",
        )
        self.assertIsNone(parsed.version_code)


class CheckBadgesTest(unittest.TestCase):
    def codes(self, *codes):
        return [
            badge(f"Sleeve-{index}-release.apk", code) for index, code in enumerate(codes)
        ]

    def levels(self, issues):
        return [issue.level for issue in issues]

    def test_identical_codes_pass(self):
        issues = gate.check_badges(
            self.codes(201_000_220, 201_000_220, 201_000_220),
            expect_package="com.sleeve.app",
            expect_version_name="2.1.0-beta.2",
            expect_code=201_000_220,
            previous_code=201_000_212,
        )
        self.assertNotIn("fail", self.levels(issues))

    def test_divergent_codes_fail(self):
        issues = gate.check_badges(self.codes(201_000_220, 201_000_222))
        self.assertIn("fail", self.levels(issues))

    def test_code_equal_to_previous_fails(self):
        issues = gate.check_badges(self.codes(201_000_212), previous_code=201_000_212)
        self.assertIn("fail", self.levels(issues))

    def test_code_above_previous_passes(self):
        issues = gate.check_badges(self.codes(201_000_220), previous_code=201_000_212)
        self.assertNotIn("fail", self.levels(issues))

    def test_wrong_expected_code_fails(self):
        issues = gate.check_badges(self.codes(201_000_220), expect_code=201_000_230)
        self.assertIn("fail", self.levels(issues))

    def test_wrong_package_fails(self):
        issues = gate.check_badges(
            [badge("x.apk", name="com.seal.app")], expect_package="com.sleeve.app"
        )
        self.assertIn("fail", self.levels(issues))

    def test_wrong_version_name_fails(self):
        issues = gate.check_badges(
            [badge("x.apk", version_name="2.1.0")], expect_version_name="2.1.0-beta.2"
        )
        self.assertIn("fail", self.levels(issues))

    def test_missing_required_artifact_fails(self):
        issues = gate.check_badges(
            [badge("Sleeve-arm64-release.apk")],
            required_names=["Sleeve-arm64-release.apk", "Sleeve-generic-release.apk"],
        )
        self.assertIn("fail", self.levels(issues))

    def test_no_artifacts_fails(self):
        self.assertEqual(self.levels(gate.check_badges([])), ["fail"])


if __name__ == "__main__":
    unittest.main()
