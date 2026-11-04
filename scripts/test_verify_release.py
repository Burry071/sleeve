#!/usr/bin/env python3
"""Unit tests for scripts/verify_release.py (no network, no Android SDK)."""

from __future__ import annotations

import io
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verify_release as verify


class FakeResponse:
    def __init__(self, payload: bytes) -> None:
        self._payload = payload
        self._cursor = 0

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def read(self, size: int = -1) -> bytes:
        if size is None or size < 0:
            chunk = self._payload[self._cursor :]
        else:
            chunk = self._payload[self._cursor : self._cursor + size]
        self._cursor += len(chunk)
        return chunk


class FakeUrlOpen:
    """urlopen() replacement; ``payloads`` is consumed one item per attempt."""

    def __init__(self, payloads) -> None:
        self.payloads = list(payloads)
        self.calls = 0

    def __call__(self, request, timeout=None):
        self.calls += 1
        payload = self.payloads.pop(0)
        if isinstance(payload, Exception):
            raise payload
        return FakeResponse(payload)


def make_log() -> tuple[verify.Log, io.StringIO]:
    stream = io.StringIO()
    return verify.Log(stream=stream), stream


def release(tag, assets=None, prerelease=False, draft=False, published_at="2026-11-05T00:00:00Z"):
    return {
        "tag_name": tag,
        "draft": draft,
        "prerelease": prerelease,
        "published_at": published_at,
        "assets": assets or [],
    }


def asset(name="Sleeve-generic-release.apk", size=100, **extra):
    data = {
        "name": name,
        "size": size,
        "state": "uploaded",
        "browser_download_url": f"https://github.com/Burry071/sleeve/releases/download/v1/{name}",
    }
    data.update(extra)
    return data


class VersionTests(unittest.TestCase):
    def test_beta_order_is_numeric(self):
        self.assertGreater(
            verify.parse_version("v2.0.0-beta.10"), verify.parse_version("v2.0.0-beta.9")
        )

    def test_prerelease_sorts_below_release(self):
        self.assertLess(
            verify.parse_version("v2.0.0-beta.10"), verify.parse_version("v2.0.0")
        )

    def test_unparsable_tag_is_none(self):
        self.assertIsNone(verify.parse_version("nightly"))


class UrlHelperTests(unittest.TestCase):
    def test_redact_url_drops_signed_query(self):
        redacted = verify.redact_url(
            "https://example.test/a.apk?token=secret&x=1"
        )
        self.assertNotIn("secret", redacted)
        self.assertIn("<redacted>", redacted)

    def test_cache_busted_keeps_path_and_replaces_cb(self):
        first = verify.cache_busted("https://example.test/mirror.json?_cb=1&a=b")
        self.assertTrue(first.startswith("https://example.test/mirror.json?"))
        self.assertEqual(first.count("_cb="), 1)
        self.assertIn("a=b", first)

    def test_urls_match_ignores_query_and_trailing_slash(self):
        self.assertTrue(
            verify._urls_match(
                "https://github.com/o/r/releases/download/v1/a.apk?token=x",
                "https://github.com/o/r/releases/download/v1/a.apk/",
            )
        )
        self.assertFalse(
            verify._urls_match(
                "https://github.com/o/r/releases/download/v1/a.apk",
                "https://github.com/o/r/releases/download/v2/a.apk",
            )
        )

    def test_retry_after_is_clamped(self):
        self.assertEqual(verify._retry_after_seconds({"retry-after": "3"}), 3.0)
        self.assertEqual(verify._retry_after_seconds({"retry-after": "9999"}), 60.0)
        self.assertIsNone(verify._retry_after_seconds({"retry-after": "soon"}))

    def test_backoff_grows_and_caps(self):
        self.assertEqual(verify._backoff_seconds(1, 0.5), 0.5)
        self.assertEqual(verify._backoff_seconds(2, 0.5), 1.0)
        self.assertEqual(verify._backoff_seconds(10, 0.5), 8.0)


class FetchTests(unittest.TestCase):
    def test_retries_transient_status_then_succeeds(self):
        log, _ = make_log()
        responses = [
            (503, {}, b""),
            (503, {}, b""),
            (200, {"content-length": "4"}, b"data"),
        ]
        with mock.patch.object(verify, "_fetch_once", side_effect=responses) as once:
            result = verify.fetch_follow(
                "https://example.test/x",
                retries=2,
                retry_delay=0,
                read_body=-1,
                log=log,
            )
        self.assertEqual(result.status, 200)
        self.assertEqual(result.body, b"data")
        self.assertEqual(once.call_count, 3)
        self.assertGreaterEqual(log.warned, 2)

    def test_gives_up_after_retries(self):
        log, _ = make_log()
        with mock.patch.object(
            verify, "_fetch_once", side_effect=[(500, {}, b"")] * 3
        ):
            result = verify.fetch_follow(
                "https://example.test/x",
                retries=2,
                retry_delay=0,
                log=log,
            )
        self.assertEqual(result.status, 500)

    def test_404_is_not_retried(self):
        with mock.patch.object(
            verify, "_fetch_once", return_value=(404, {}, b"not found")
        ) as once:
            result = verify.fetch_follow("https://example.test/x", retry_delay=0)
        self.assertEqual(result.status, 404)
        self.assertEqual(once.call_count, 1)


class DiscoveryTests(unittest.TestCase):
    def test_duplicate_version_is_a_failure(self):
        log, _ = make_log()
        releases = [
            release("v2.0.0-beta.10", [asset()]),
            release("2.0.0-beta.10", [asset()]),
        ]
        eligible, _, _ = verify.select_release(releases, "beta", log)
        self.assertEqual(len(eligible), 2)
        self.assertEqual(log.failed, 1)

    def test_drafts_are_skipped(self):
        log, _ = make_log()
        eligible, _, _ = verify.select_release(
            [release("v2.0.0-beta.10", draft=True)], "beta", log
        )
        self.assertEqual(eligible, [])

    def test_unmarked_beta_prerelease_is_informational(self):
        log, _ = make_log()
        verify.select_release([release("v2.0.0-beta.10", [asset()])], "beta", log)
        self.assertEqual(log.failed, 0)

    def test_stable_channel_rejects_betas(self):
        log, _ = make_log()
        eligible, _, _ = verify.select_release(
            [release("v2.0.0-beta.10", [asset()])], "stable", log
        )
        self.assertEqual(eligible, [])
        self.assertEqual(log.failed, 1)


class AssetTests(unittest.TestCase):
    def test_prefers_generic_name_without_abi(self):
        chosen = verify.pick_asset(
            [asset("Sleeve-arm64-v8a-release.apk"), asset()], None
        )
        self.assertEqual(chosen["name"], "Sleeve-generic-release.apk")

    def test_prefers_abi_match(self):
        chosen = verify.pick_asset(
            [asset("Sleeve-generic-release.apk"), asset("Sleeve-x86_64-release.apk")],
            "x86_64",
        )
        self.assertEqual(chosen["name"], "Sleeve-x86_64-release.apk")

    def test_incomplete_upload_is_skipped(self):
        log, _ = make_log()
        candidate = verify.Candidate(
            "v2.0.0-beta.10",
            verify.parse_version("v2.0.0-beta.10"),
            release("v2.0.0-beta.10", [asset(state="starter")]),
        )
        selected, chosen = verify.choose_candidate([candidate], None, log)
        self.assertIsNone(selected)
        self.assertIsNone(chosen)

    def test_zero_size_asset_is_skipped(self):
        log, _ = make_log()
        candidate = verify.Candidate(
            "v2.0.0-beta.10",
            verify.parse_version("v2.0.0-beta.10"),
            release("v2.0.0-beta.10", [asset(size=0)]),
        )
        selected, _ = verify.choose_candidate([candidate], None, log)
        self.assertIsNone(selected)


class VersionCodeTests(unittest.TestCase):
    def test_inverts_arm64_offset(self):
        self.assertEqual(
            verify.derive_version_code_parts(200000302), (200000300, "arm64-v8a")
        )

    def test_inverts_x86_64_offset(self):
        self.assertEqual(
            verify.derive_version_code_parts(200000304), (200000300, "x86_64")
        )

    def test_unknown_offset_returns_none(self):
        self.assertEqual(verify.derive_version_code_parts(200000300), (None, None))


class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.mkdtemp()
        self.payload = b"PK\x03\x04" + b"x" * 96

    def test_writes_part_then_renames_on_success(self):
        log, _ = make_log()
        fake = FakeUrlOpen([self.payload])
        with mock.patch.object(verify.urllib.request, "urlopen", fake):
            report = verify.download_asset(
                asset(size=len(self.payload)),
                self.directory,
                log,
                timeout=5,
            )
        self.assertTrue(report.verified)
        self.assertTrue(os.path.isfile(report.path))
        self.assertFalse(os.path.exists(report.path + ".part"))
        self.assertEqual(log.failed, 0)

    def test_size_mismatch_discards_file(self):
        log, _ = make_log()
        fake = FakeUrlOpen([self.payload])
        with mock.patch.object(verify.urllib.request, "urlopen", fake):
            report = verify.download_asset(
                asset(size=len(self.payload) + 10), self.directory, log, timeout=5
            )
        self.assertFalse(report.verified)
        self.assertFalse(os.path.exists(os.path.join(self.directory, "Sleeve-generic-release.apk")))

    def test_expect_sha256_mismatch_discards_file(self):
        log, _ = make_log()
        fake = FakeUrlOpen([self.payload])
        with mock.patch.object(verify.urllib.request, "urlopen", fake):
            report = verify.download_asset(
                asset(size=len(self.payload)),
                self.directory,
                log,
                timeout=5,
                expect_sha256="0" * 64,
            )
        self.assertFalse(report.verified)
        self.assertGreaterEqual(log.failed, 1)

    def test_retries_network_error(self):
        import urllib.error

        log, _ = make_log()
        fake = FakeUrlOpen([urllib.error.URLError("reset"), self.payload])
        with mock.patch.object(verify.urllib.request, "urlopen", fake):
            report = verify.download_asset(
                asset(size=len(self.payload)),
                self.directory,
                log,
                timeout=5,
                retries=1,
            )
        self.assertEqual(fake.calls, 2)
        self.assertTrue(report.verified)


class ApkInspectionTests(unittest.TestCase):
    def test_package_and_signer_are_checked(self):
        log, _ = make_log()
        aapt_output = (
            "package: name='com.sleeve.app' versionCode='200000302' "
            "versionName='2.0.0-beta.10' platformBuildVersionName='15'\n"
        )
        signer_output = "Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n"

        def fake_run(cmd, timeout=180.0):
            return (0, aapt_output if "badging" in cmd else signer_output)

        with mock.patch.object(verify, "find_android_tool", return_value="tool"), mock.patch.object(
            verify, "_run_tool", side_effect=fake_run
        ):
            report = verify.inspect_apk(
                "fake.apk",
                "v2.0.0-beta.10",
                log,
                expected_version_code=200000302,
            )
        self.assertEqual(report.package, "com.sleeve.app")
        self.assertEqual(report.version_code, 200000302)
        self.assertEqual(report.cert_sha256, "a" * 64)
        self.assertEqual(log.failed, 0)

    def test_wrong_package_is_a_failure(self):
        log, _ = make_log()
        aapt_output = "package: name='com.example.other' versionCode='1' versionName='1.0'\n"
        signer_output = "Signer #1 certificate SHA-256 digest: " + "b" * 64 + "\n"

        def fake_run(cmd, timeout=180.0):
            return (0, aapt_output if "badging" in cmd else signer_output)

        with mock.patch.object(verify, "find_android_tool", return_value="tool"), mock.patch.object(
            verify, "_run_tool", side_effect=fake_run
        ):
            verify.inspect_apk("fake.apk", "v1.0", log)
        self.assertGreaterEqual(log.failed, 1)

    def test_apksigner_environment_failure_is_a_warning(self):
        log, _ = make_log()
        aapt_output = (
            "package: name='com.sleeve.app' versionCode='200000302' "
            "versionName='2.0.0-beta.10'\n"
        )

        def fake_run(cmd, timeout=180.0):
            if "badging" in cmd:
                return (0, aapt_output)
            return (1, "ERROR: JAVA_HOME is set to an invalid directory: X\n")

        with mock.patch.object(verify, "find_android_tool", return_value="tool"), mock.patch.object(
            verify, "_run_tool", side_effect=fake_run
        ):
            report = verify.inspect_apk("fake.apk", "v2.0.0-beta.10", log)
        self.assertEqual(log.failed, 0)
        self.assertGreaterEqual(log.warned, 1)
        self.assertFalse(report.signer_checked)

    def test_signer_mismatch_is_a_failure(self):
        log, _ = make_log()
        aapt_output = (
            "package: name='com.sleeve.app' versionCode='200000302' "
            "versionName='2.0.0-beta.10'\n"
        )
        signer_output = "Signer #1 certificate SHA-256 digest: " + "c" * 64 + "\n"

        def fake_run(cmd, timeout=180.0):
            return (0, aapt_output if "badging" in cmd else signer_output)

        with mock.patch.object(verify, "find_android_tool", return_value="tool"), mock.patch.object(
            verify, "_run_tool", side_effect=fake_run
        ):
            verify.inspect_apk(
                "fake.apk", "v2.0.0-beta.10", log, expected_cert="d" * 64
            )
        self.assertGreaterEqual(log.failed, 1)


class RecordTests(unittest.TestCase):
    def test_refuses_to_rewrite_a_published_sha(self):
        import json

        log, _ = make_log()
        directory = tempfile.mkdtemp()
        path = os.path.join(directory, "record.json")
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(
                {
                    "schema": 1,
                    "repo": "Burry071/sleeve",
                    "releases": [
                        {
                            "tag": "v2.0.0-beta.10",
                            "base_version_code": 200000300,
                            "shipped_version_code": 200000302,
                            "sha256": "e" * 64,
                            "published": True,
                            "distributed": True,
                        }
                    ],
                },
                handle,
            )
        candidate = verify.Candidate(
            "v2.0.0-beta.10",
            verify.parse_version("v2.0.0-beta.10"),
            release("v2.0.0-beta.10"),
        )
        apk = verify.ApkReport(
            package="com.sleeve.app",
            version_code=200000302,
            version_name="2.0.0-beta.10",
            cert_sha256="f" * 64,
            aapt_checked=True,
            signer_checked=True,
        )
        verify.record_release(
            path,
            "Burry071/sleeve",
            candidate,
            asset(),
            apk,
            "1" * 64,
            100,
            log,
        )
        self.assertEqual(log.failed, 1)

    def test_records_measured_facts(self):
        import json

        log, _ = make_log()
        directory = tempfile.mkdtemp()
        path = os.path.join(directory, "record.json")
        candidate = verify.Candidate(
            "v2.0.0-beta.11",
            verify.parse_version("v2.0.0-beta.11"),
            release("v2.0.0-beta.11"),
        )
        apk = verify.ApkReport(
            package="com.sleeve.app",
            version_code=200000312,
            version_name="2.0.0-beta.11",
            cert_sha256="f" * 64,
            aapt_checked=True,
            signer_checked=True,
        )
        verify.record_release(
            path,
            "Burry071/sleeve",
            candidate,
            asset(),
            apk,
            "2" * 64,
            100,
            log,
        )
        with open(path, "r", encoding="utf-8") as handle:
            saved = json.load(handle)
        self.assertEqual(saved["releases"][0]["tag"], "v2.0.0-beta.11")
        self.assertEqual(saved["releases"][0]["base_version_code"], 200000310)
        self.assertEqual(saved["releases"][0]["shipped_version_code"], 200000312)
        self.assertEqual(saved["releases"][0]["sha256"], "2" * 64)


if __name__ == "__main__":
    unittest.main()
