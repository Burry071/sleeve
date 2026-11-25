#!/usr/bin/env python3
"""Offline unit tests for .github/canary/dry_run.py and .github/workflows/extractor-canary.yml."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import urllib.error
from unittest import mock

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_DRY_RUN_PATH = os.path.join(_ROOT, ".github", "canary", "dry_run.py")
_WORKFLOW_PATH = os.path.join(_ROOT, ".github", "workflows", "extractor-canary.yml")

_SPEC = importlib.util.spec_from_file_location("sleeve_canary_dry_run", _DRY_RUN_PATH)
assert _SPEC is not None and _SPEC.loader is not None
dry_run = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(dry_run)


class ClassificationTest(unittest.TestCase):
    def test_429_and_bot_check_classify_as_bot_check_or_429(self):
        err_429 = urllib.error.HTTPError(
            "https://example.com", 429, "Too Many Requests", {}, None
        )
        self.assertEqual(dry_run.CLASS_BOT_429, dry_run.classify_error(err_429))
        self.assertEqual(
            dry_run.CLASS_BOT_429,
            dry_run.classify_error("ERROR: Sign in to confirm you're not a bot"),
        )
        self.assertEqual(
            dry_run.CLASS_BOT_429,
            dry_run.classify_error("HTTP 429 rate limited"),
        )

    def test_403_classifies_as_http_403(self):
        err_403 = urllib.error.HTTPError(
            "https://example.com", 403, "Forbidden", {}, None
        )
        self.assertEqual(dry_run.CLASS_HTTP_403, dry_run.classify_error(err_403))
        self.assertEqual(
            dry_run.CLASS_HTTP_403,
            dry_run.classify_error("HTTP Error 403: Forbidden"),
        )

    def test_import_error_classifies_as_import_error(self):
        self.assertEqual(
            dry_run.CLASS_IMPORT,
            dry_run.classify_error(ImportError("No module named 'gallery_dl'")),
        )
        self.assertEqual(
            dry_run.CLASS_IMPORT,
            dry_run.classify_error(ModuleNotFoundError("missing pkg")),
        )
        self.assertEqual(
            dry_run.CLASS_IMPORT,
            dry_run.classify_error("gallery-dl import failed: broken"),
        )

    def test_timeout_classifies_as_timeout(self):
        self.assertEqual(
            dry_run.CLASS_TIMEOUT,
            dry_run.classify_error(TimeoutError("read timed out")),
        )
        self.assertEqual(
            dry_run.CLASS_TIMEOUT,
            dry_run.classify_error(subprocess.TimeoutExpired(["yt-dlp"], 60)),
        )
        self.assertEqual(
            dry_run.CLASS_TIMEOUT,
            dry_run.classify_error("request timed out after 15s"),
        )

    def test_schema_mismatch_classifies_as_schema_break(self):
        self.assertEqual(
            dry_run.CLASS_SCHEMA,
            dry_run.classify_error(json.JSONDecodeError("bad", "{", 1)),
        )
        self.assertEqual(
            dry_run.CLASS_SCHEMA,
            dry_run.classify_error(KeyError("tracks")),
        )
        self.assertEqual(
            dry_run.CLASS_SCHEMA,
            dry_run.classify_error("unexpected payload shape"),
        )


class ExitCodeAndReportTest(unittest.TestCase):
    def test_blocking_pass_with_advisory_warn_exits_zero(self):
        records = [
            dry_run.make_record("manifest-integrity", "blocking", "PASS", "OK", 0.1, "1.0", "ok"),
            dry_run.make_record("reddit-json", "advisory", "WARN", "HTTP_403", 0.2, "urllib", "403"),
        ]
        self.assertEqual(0, dry_run.compute_exit_code(records))
        report = dry_run.build_report(records, offline_only=False, versions={"python": "3.13"})
        self.assertTrue(report["ok"])
        self.assertEqual(0, report["blocking_failures"])
        self.assertEqual(1, report["advisory_warnings"])
        self.assertFalse(report["immediate_alert"])

        md = dry_run.render_summary_markdown(report)
        self.assertIn("## Extractor Canary Report (PASS)", md)
        self.assertIn("| Lane | Tier | Status | Classification | Duration (s) | Version | Detail |", md)
        self.assertIn("`manifest-integrity`", md)
        self.assertIn("`reddit-json`", md)
        self.assertIn("`HTTP_403`", md)

    def test_blocking_fail_bot_check_exits_one_without_immediate_alert(self):
        records = [
            dry_run.make_record("yt-dlp-ladder", "blocking", "FAIL", "BOT_CHECK_OR_429", 1.2, "2026", "bot"),
        ]
        self.assertEqual(1, dry_run.compute_exit_code(records))
        report = dry_run.build_report(records, offline_only=False)
        self.assertFalse(report["ok"])
        self.assertEqual(1, report["blocking_failures"])
        self.assertFalse(report["immediate_alert"])
        self.assertEqual(["yt-dlp-ladder"], report["failing_lanes"])

    def test_blocking_fail_import_or_schema_triggers_immediate_alert(self):
        for cls in (dry_run.CLASS_IMPORT, dry_run.CLASS_SCHEMA):
            with self.subTest(classification=cls):
                records = [
                    dry_run.make_record("sleeve-spotify", "blocking", "FAIL", cls, 0.3, "in-repo", "broken"),
                ]
                self.assertEqual(1, dry_run.compute_exit_code(records))
                report = dry_run.build_report(records, offline_only=False)
                self.assertFalse(report["ok"])
                self.assertTrue(report["immediate_alert"])

    def test_run_lane_catches_exceptions_and_classifies_advisory_vs_blocking(self):
        def _boom_429():
            raise urllib.error.HTTPError("https://example.com", 429, "Too Many", {}, None)

        adv = dry_run.run_lane("reddit-json", dry_run.TIER_ADVISORY, _boom_429)
        self.assertEqual(dry_run.STATUS_WARN, adv["status"])
        self.assertEqual(dry_run.CLASS_BOT_429, adv["classification"])

        blk = dry_run.run_lane("sleeve-spotify", dry_run.TIER_BLOCKING, _boom_429)
        self.assertEqual(dry_run.STATUS_FAIL, blk["status"])
        self.assertEqual(dry_run.CLASS_BOT_429, blk["classification"])

        def _boom_import():
            raise ImportError("missing gallery_dl")

        imp = dry_run.run_lane("gallery-dl-vendored", dry_run.TIER_BLOCKING, _boom_import)
        self.assertEqual(dry_run.STATUS_FAIL, imp["status"])
        self.assertEqual(dry_run.CLASS_IMPORT, imp["classification"])


class OfflineEndToEndCanaryTest(unittest.TestCase):
    def test_dry_run_offline_only_end_to_end_against_real_repo(self):
        tmpdir = tempfile.mkdtemp(prefix="sleeve_test_canary_")
        try:
            json_out = os.path.join(tmpdir, "canary-report.json")
            md_out = os.path.join(tmpdir, "canary-summary.md")
            proc = subprocess.run(
                [
                    sys.executable,
                    _DRY_RUN_PATH,
                    "--offline-only",
                    "--json",
                    json_out,
                    "--summary-md",
                    md_out,
                ],
                cwd=_ROOT,
                capture_output=True,
                text=True,
                timeout=60,
                check=False,
            )
            self.assertEqual(
                0,
                proc.returncode,
                f"dry_run.py --offline-only failed:\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}",
            )
            self.assertTrue(os.path.isfile(json_out))
            self.assertTrue(os.path.isfile(md_out))

            with open(json_out, encoding="utf-8") as fh:
                report = json.load(fh)
            self.assertTrue(report["ok"])
            self.assertEqual("offline", report["mode"])
            self.assertEqual(0, report["blocking_failures"])
            self.assertFalse(report["immediate_alert"])

            lanes_by_name = {rec["lane"]: rec for rec in report["lanes"]}
            expected_lanes = {
                "manifest-integrity",
                "sleeve-segmented-http",
                "sleeve-sniff",
                "gallery-dl-vendored",
                "sleeve-audible",
            }
            self.assertEqual(expected_lanes, set(lanes_by_name.keys()))

            for name, rec in lanes_by_name.items():
                with self.subTest(lane=name):
                    self.assertEqual(dry_run.TIER_BLOCKING, rec["tier"])
                    self.assertEqual(dry_run.STATUS_PASS, rec["status"], rec)
                    self.assertEqual(dry_run.CLASS_OK, rec["classification"], rec)
                    self.assertIsInstance(rec["duration_s"], (int, float))
                    self.assertGreaterEqual(rec["duration_s"], 0.0)
                    self.assertTrue(rec["version"])
                    self.assertTrue(rec["detail"])

            with open(md_out, encoding="utf-8") as fh:
                md = fh.read()
            self.assertIn("## Extractor Canary Report (PASS)", md)
            for name in expected_lanes:
                self.assertIn(f"`{name}`", md)
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

    def test_manifest_integrity_detects_elf_and_sha256_tampering(self):
        tmpdir = tempfile.mkdtemp(prefix="sleeve_test_manifest_")
        fake_mod_name = "_sleeve_canary_fake_pkg"
        try:
            py_dir = os.path.join(tmpdir, "app", "src", "main", "python")
            pkg_dir = os.path.join(py_dir, fake_mod_name)
            os.makedirs(pkg_dir)
            with open(os.path.join(pkg_dir, "__init__.py"), "w", encoding="utf-8") as fh:
                fh.write('__version__ = "1.2.3"\n')

            with open(os.path.join(tmpdir, "VENDOR_MANIFEST.json"), "w", encoding="utf-8") as fh:
                json.dump(
                    {
                        "pythonPackages": [
                            {"name": fake_mod_name, "version": "1.2.3"}
                        ]
                    },
                    fh,
                )

            def _make_elf(machine: int) -> bytes:
                return (
                    b"\x7fELF\x02\x01\x01"
                    + b"\x00" * 9
                    + struct.pack("<HH", 3, machine)
                    + b"\x00" * 16
                )

            arm_bytes = _make_elf(183)
            bad_x64_bytes = _make_elf(3)  # EM_386 instead of EM_X86_64 (62)
            for abi, data in (("arm64-v8a", arm_bytes), ("x86_64", bad_x64_bytes)):
                d = os.path.join(tmpdir, "app", "src", "main", "jniLibs", abi)
                os.makedirs(d, exist_ok=True)
                with open(os.path.join(d, "libnm3u8dlre.so"), "wb") as fh:
                    fh.write(data)

            arm_sha = hashlib.sha256(arm_bytes).hexdigest()
            x64_sha = hashlib.sha256(bad_x64_bytes).hexdigest()
            with open(os.path.join(tmpdir, "THIRD_PARTY.md"), "w", encoding="utf-8") as fh:
                fh.write(
                    "| ABI | Upstream asset | SHA-256 |\n"
                    "|---|---|---|\n"
                    f"| arm64-v8a | `a.tar.gz` | `{arm_sha}` |\n"
                    f"| x86_64 | `b.tar.gz` | `{x64_sha}` |\n"
                )

            # 1. ELF machine mismatch detected
            status, cls, _, detail = dry_run.check_manifest_integrity(tmpdir)
            self.assertEqual(dry_run.STATUS_FAIL, status)
            self.assertEqual(dry_run.CLASS_SCHEMA, cls)
            self.assertIn("ELF mismatch", detail)

            # 2. Fix ELF machine to 62, keep stale SHA-256 -> SHA-256 mismatch detected
            good_x64_bytes = _make_elf(62)
            with open(
                os.path.join(tmpdir, "app", "src", "main", "jniLibs", "x86_64", "libnm3u8dlre.so"),
                "wb",
            ) as fh:
                fh.write(good_x64_bytes)
            status, cls, _, detail = dry_run.check_manifest_integrity(tmpdir)
            self.assertEqual(dry_run.STATUS_FAIL, status)
            self.assertEqual(dry_run.CLASS_SCHEMA, cls)
            self.assertIn("SHA-256 mismatch", detail)
        finally:
            sys.modules.pop(fake_mod_name, None)
            shutil.rmtree(tmpdir, ignore_errors=True)


class MockedOnlineLaneContractTest(unittest.TestCase):
    def test_spotify_lane_catches_tuple_schema_regression_in_anon_token(self):
        import sleeve_spotify

        valid_pl = {
            "ok": True,
            "kind": "playlist",
            "title": "Hits",
            "tracks": [{"title": "Song", "artist": "Art", "album": "", "durationMs": 180000, "isrc": ""}],
            "totalTracks": 1,
            "truncated": False,
        }
        valid_tr = {
            "ok": True,
            "kind": "track",
            "title": "Song",
            "tracks": [{"title": "Song", "artist": "Art", "album": "", "durationMs": 180000, "isrc": ""}],
            "totalTracks": 1,
            "truncated": False,
        }

        with (
            mock.patch.object(
                sleeve_spotify,
                "resolve",
                side_effect=[json.dumps(valid_pl), json.dumps(valid_tr)],
            ),
            mock.patch.object(sleeve_spotify, "_via_embed", return_value=valid_pl),
            mock.patch.object(
                sleeve_spotify,
                "_via_anon_token",
                return_value=("Song", valid_tr["tracks"]),  # Wave 0 tuple bug!
            ),
        ):
            status, cls, _, detail = dry_run.check_spotify()
            self.assertEqual(dry_run.STATUS_FAIL, status)
            self.assertEqual(dry_run.CLASS_SCHEMA, cls)
            self.assertIn("_via_anon_token", detail)

    def test_yt_dlp_ladder_tests_all_three_rungs_and_both_searches(self):
        calls: list[list[str]] = []

        def _fake_run(args, **kwargs):
            calls.append(list(args))
            if "--version" in args:
                return subprocess.CompletedProcess(args, 0, stdout="2026.11.01\n", stderr="")
            target = args[-1]
            if target.startswith(("ytsearch3:", "ytmsearch3:")):
                payload = {"entries": [{"id": "abc", "title": "Track"}]}
                return subprocess.CompletedProcess(args, 0, stdout=json.dumps(payload), stderr="")
            if "youtube:player_client=visionos" in args:
                return subprocess.CompletedProcess(
                    args, 1, stdout="", stderr="ERROR: Sign in to confirm you're not a bot"
                )
            payload = {
                "id": dry_run.YOUTUBE_VIDEO_ID,
                "formats": [
                    {"format_id": "18", "url": "https://cdn.example/v.mp4", "vcodec": "avc1", "acodec": "mp4a"},
                    {"format_id": "sb0", "url": "https://cdn.example/sb.jpg", "vcodec": "none", "acodec": "none"},
                ],
            }
            return subprocess.CompletedProcess(args, 0, stdout=json.dumps(payload), stderr="")

        with mock.patch("subprocess.run", side_effect=_fake_run):
            status, cls, version, detail = dry_run.check_yt_dlp_ladder()

        self.assertEqual(dry_run.STATUS_PASS, status, detail)
        self.assertEqual(dry_run.CLASS_OK, cls)
        self.assertEqual("yt-dlp=2026.11.01", version)
        self.assertIn("default=OK(1 fmts)", detail)
        self.assertIn("tv,web_embedded=OK(1 fmts)", detail)
        self.assertIn("visionos=FAIL(BOT_CHECK_OR_429)", detail)
        self.assertIn("ytsearch3:test=OK(1)", detail)
        self.assertIn("ytmsearch3:Daft Punk=OK(1)", detail)

    def test_reddit_advisory_classifies_403_as_warn_and_bad_json_as_fail(self):
        err_403 = urllib.error.HTTPError(dry_run.REDDIT_JSON_URL, 403, "Blocked", {}, None)
        with mock.patch("urllib.request.urlopen", side_effect=err_403):
            status, cls, _, _ = dry_run.check_reddit_json()
        self.assertEqual(dry_run.STATUS_WARN, status)
        self.assertEqual(dry_run.CLASS_HTTP_403, cls)

        class _FakeResp:
            def read(self, n=-1):
                return b"not-json-at-all"

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        with mock.patch("urllib.request.urlopen", return_value=_FakeResp()):
            status, cls, _, _ = dry_run.check_reddit_json()
        self.assertEqual(dry_run.STATUS_FAIL, status)
        self.assertEqual(dry_run.CLASS_SCHEMA, cls)

    def test_instagram_advisory_classifies_datacenter_block_vs_schema_break(self):
        import sleeve_engine

        with mock.patch.object(
            sleeve_engine,
            "instagram_embed",
            return_value=json.dumps({"ok": False, "error": "no media found (HTTP 429)"}),
        ):
            status, cls, _, _ = dry_run.check_instagram_embed()
        self.assertEqual(dry_run.STATUS_WARN, status)
        self.assertEqual(dry_run.CLASS_BOT_429, cls)

        with mock.patch.object(sleeve_engine, "instagram_embed", return_value="{broken"):
            status, cls, _, _ = dry_run.check_instagram_embed()
        self.assertEqual(dry_run.STATUS_FAIL, status)
        self.assertEqual(dry_run.CLASS_SCHEMA, cls)


class WorkflowYamlHygieneTest(unittest.TestCase):
    EXPECTED_PINS = {
        "actions/checkout": "11d5960a326750d5838078e36cf38b85af677262",
        "actions/setup-python": "a26af69be951a213d495a4c3e4e4022e16d87065",
        "actions/upload-artifact": "ea165f8d65b6e75b540449e92b4886f43607fa02",
    }

    def setUp(self):
        with open(_WORKFLOW_PATH, encoding="utf-8") as fh:
            self.raw = fh.read()
        self.lines = self.raw.splitlines()

    def test_yaml_basic_syntax_and_optional_pyyaml_parse(self):
        # No hard tabs anywhere in YAML indentation.
        for idx, line in enumerate(self.lines, 1):
            self.assertFalse(
                line.startswith("\t"),
                f"tab indentation at {_WORKFLOW_PATH}:{idx}",
            )
        # Required top-level workflow keys.
        for key in ("name:", "on:", "permissions:", "concurrency:", "jobs:"):
            self.assertTrue(
                any(line.startswith(key) for line in self.lines),
                f"missing top-level key {key!r}",
            )
        # If PyYAML is available in the environment, parse strictly with safe_load.
        try:
            import yaml  # type: ignore[import-not-found]
        except ImportError:
            yaml = None
        if yaml is not None:
            doc = yaml.safe_load(self.raw)
            self.assertIsInstance(doc, dict)
            self.assertEqual("extractor-canary", doc.get("name"))

    def test_all_actions_are_sha_pinned_with_no_unpinned_tags(self):
        uses_lines = [
            line.strip()
            for line in self.lines
            if re.match(r"^\s*-?\s*uses:\s+", line)
        ]
        self.assertGreaterEqual(len(uses_lines), 3)
        found_actions: dict[str, str] = {}
        for line in uses_lines:
            m = re.match(r"^-?\s*uses:\s+([^@\s]+)@([^\s#]+)", line)
            self.assertIsNotNone(m, f"malformed uses line: {line!r}")
            assert m is not None
            action, ref = m.group(1), m.group(2)
            self.assertRegex(
                ref,
                r"^[0-9a-f]{40}$",
                f"action {action} is not pinned to a 40-char commit SHA: {line!r}",
            )
            found_actions[action] = ref
        for action, expected_sha in self.EXPECTED_PINS.items():
            self.assertEqual(
                expected_sha,
                found_actions.get(action),
                f"missing or mismatched SHA pin for {action}",
            )
        # Ensure zero unpinned @v4 / @v5 references remain anywhere in uses: lines.
        self.assertNotRegex(self.raw, r"uses:\s+\S+@v\d+")

    def test_timeouts_reporting_and_debounce_hygiene_present(self):
        self.assertRegex(self.raw, r"(?m)^\s{4}timeout-minutes:\s*15\s*$")
        self.assertGreaterEqual(
            len(re.findall(r"(?m)^\s{8}timeout-minutes:\s*5\s*$", self.raw)),
            2,
            "expected timeout-minutes: 5 on network steps",
        )
        self.assertIn("--json canary-report.json", self.raw)
        self.assertIn("--summary-md canary-summary.md", self.raw)
        self.assertIn("GITHUB_STEP_SUMMARY", self.raw)
        self.assertIn("retention-days: 30", self.raw)
        self.assertIn('in:title "upstream-canary"', self.raw)
        self.assertIn("gh run list", self.raw)
        self.assertIn("--workflow extractor-canary.yml", self.raw)
        self.assertIn("--limit 2", self.raw)
        self.assertIn("--json conclusion", self.raw)
        self.assertIn("IMPORT_ERROR", self.raw)
        self.assertIn("SCHEMA_BREAK", self.raw)

    def test_embedded_bash_scripts_pass_bash_n_syntax_check(self):
        # Extract every multiline `run: |` block and validate syntax with `bash -n`.
        blocks: list[str] = []
        i = 0
        while i < len(self.lines):
            m = re.match(r"^(\s*)run:\s*\|\s*$", self.lines[i])
            if not m:
                i += 1
                continue
            key_indent = len(m.group(1))
            i += 1
            block_lines: list[str] = []
            body_indent: int | None = None
            while i < len(self.lines):
                line = self.lines[i]
                if line.strip() == "":
                    block_lines.append("")
                    i += 1
                    continue
                cur_indent = len(line) - len(line.lstrip(" "))
                if cur_indent <= key_indent:
                    break
                if body_indent is None:
                    body_indent = cur_indent
                block_lines.append(line[body_indent:] if len(line) >= body_indent else line.lstrip(" "))
                i += 1
            blocks.append("\n".join(block_lines) + "\n")

        self.assertGreaterEqual(len(blocks), 4)
        for idx, script in enumerate(blocks):
            with self.subTest(block_index=idx):
                proc = subprocess.run(
                    ["bash", "-n"],
                    input=script,
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(
                    0,
                    proc.returncode,
                    f"bash -n failed for run block #{idx}:\n{proc.stderr}\nSCRIPT:\n{script}",
                )


if __name__ == "__main__":
    unittest.main()
