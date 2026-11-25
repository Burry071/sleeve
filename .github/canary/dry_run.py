#!/usr/bin/env python3
"""Extractor canary dry-run matrix for Sleeve.

Runs offline integrity / local-server checks and (unless --offline-only) live
upstream metadata probes across Sleeve's extractor lanes without short-circuiting.
Metadata only for online probes: no media is downloaded and no account, cookie,
or secret is used.

Usage:
  python .github/canary/dry_run.py
  python .github/canary/dry_run.py --offline-only
  python .github/canary/dry_run.py --json canary-report.json --summary-md canary-summary.md
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import http.client
import http.server
import importlib
import io
import json
import os
import re
import shutil
import socket
import socketserver
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from unittest import mock

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PYTHON_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "python")
if PYTHON_DIR not in sys.path:
    sys.path.insert(0, PYTHON_DIR)

# Stable public fixtures (metadata-only, no auth/cookies).
GALLERY_URL = (
    "https://raw.githubusercontent.com/Burry071/sleeve/main/"
    "fastlane/metadata/android/en-US/images/phoneScreenshots/1.png"
)
WIKIMEDIA_URL = "https://commons.wikimedia.org/wiki/File:Example.jpg"
YOUTUBE_VIDEO_ID = "jNQXAC9IVRw"
YOUTUBE_URL = f"https://www.youtube.com/watch?v={YOUTUBE_VIDEO_ID}"
SPOTIFY_PLAYLIST_ID = "37i9dQZF1DXcBWIGoYBM5M"
SPOTIFY_PLAYLIST_URL = f"https://open.spotify.com/playlist/{SPOTIFY_PLAYLIST_ID}"
SPOTIFY_TRACK_ID = "4cOdK2wGLETKBW3PvgPWqT"
SPOTIFY_TRACK_URL = f"https://open.spotify.com/track/{SPOTIFY_TRACK_ID}"
AUDIBLE_LETTER_URL = "https://www.audible.com/pd/B002V1OF70"
AUDIBLE_NUMERIC_URL = "https://www.audible.com/pd/1705047572"
IG_PUBLIC_URL = "https://www.instagram.com/p/C8xYz9wO_1A/"
REDDIT_JSON_URL = "https://www.reddit.com/r/announcements/top.json?limit=1&raw_json=1"

# Matches EngineSupport.kt YOUTUBE_CLIENT_LADDER.
YOUTUBE_CLIENT_LADDER: tuple[tuple[str, str | None], ...] = (
    ("default", None),
    ("tv,web_embedded", "tv,web_embedded"),
    ("visionos", "visionos"),
)

# ELF e_machine values for bundled N_m3u8DL-RE binaries.
ELF_MACHINES = {
    "arm64-v8a": 183,  # EM_AARCH64
    "x86_64": 62,  # EM_X86_64
}

TIER_BLOCKING = "blocking"
TIER_ADVISORY = "advisory"

STATUS_PASS = "PASS"
STATUS_WARN = "WARN"
STATUS_FAIL = "FAIL"

CLASS_OK = "OK"
CLASS_BOT_429 = "BOT_CHECK_OR_429"
CLASS_HTTP_403 = "HTTP_403"
CLASS_SCHEMA = "SCHEMA_BREAK"
CLASS_TIMEOUT = "TIMEOUT"
CLASS_IMPORT = "IMPORT_ERROR"

IMMEDIATE_ALERT_CLASSES = frozenset({CLASS_IMPORT, CLASS_SCHEMA})
VALID_TIERS = frozenset({TIER_BLOCKING, TIER_ADVISORY})
VALID_STATUSES = frozenset({STATUS_PASS, STATUS_WARN, STATUS_FAIL})
VALID_CLASSIFICATIONS = frozenset(
    {
        CLASS_OK,
        CLASS_BOT_429,
        CLASS_HTTP_403,
        CLASS_SCHEMA,
        CLASS_TIMEOUT,
        CLASS_IMPORT,
    }
)

_SHA256_ROW_RE = re.compile(
    r"^\|\s*(arm64-v8a|x86_64)\s*\|\s*`[^`]+`\s*\|\s*`([0-9a-fA-F]{64})`\s*\|$"
)
_HTTP_429_WORD_RE = re.compile(r"\b429\b")
_HTTP_403_WORD_RE = re.compile(r"\b403\b")


def classify_error(err: BaseException | str, default: str = CLASS_SCHEMA) -> str:
    """Classify an exception or error message into a canary failure taxonomy."""
    if isinstance(err, ImportError):
        return CLASS_IMPORT
    if isinstance(err, (TimeoutError, socket.timeout, subprocess.TimeoutExpired)):
        return CLASS_TIMEOUT
    if isinstance(err, urllib.error.HTTPError):
        if err.code == 429:
            return CLASS_BOT_429
        if err.code == 403:
            return CLASS_HTTP_403
    if isinstance(err, urllib.error.URLError):
        reason = getattr(err, "reason", None)
        if isinstance(reason, (TimeoutError, socket.timeout)):
            return CLASS_TIMEOUT
    if isinstance(err, (json.JSONDecodeError, KeyError, TypeError, AssertionError)):
        return CLASS_SCHEMA

    text = str(err).lower()
    if any(
        tok in text
        for tok in ("importerror", "modulenotfounderror", "import failed", "no module named")
    ):
        return CLASS_IMPORT
    if any(tok in text for tok in ("timed out", "timeout", "deadline exceeded")):
        return CLASS_TIMEOUT
    if _HTTP_429_WORD_RE.search(text) or any(
        tok in text
        for tok in (
            "too many requests",
            "rate limit",
            "rate-limit",
            "sign in to confirm",
            "not a bot",
            "bot check",
            "captcha",
            "login required",
            "login wall",
        )
    ):
        return CLASS_BOT_429
    if _HTTP_403_WORD_RE.search(text) or "forbidden" in text:
        return CLASS_HTTP_403
    return default


def make_record(
    lane: str,
    tier: str,
    status: str,
    classification: str,
    duration_s: float,
    version: str,
    detail: str,
) -> dict[str, object]:
    return {
        "lane": lane,
        "tier": tier,
        "status": status,
        "classification": classification,
        "duration_s": round(float(duration_s), 3),
        "version": str(version or "unknown"),
        "detail": str(detail or ""),
    }


def _sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            chunk = fh.read(1 << 16)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _parse_elf_machine(path: str) -> tuple[int, int]:
    """Return (ei_class, e_machine) after verifying ELF magic and endianness."""
    with open(path, "rb") as fh:
        hdr = fh.read(20)
    if len(hdr) < 20 or hdr[:4] != b"\x7fELF":
        raise ValueError(f"{path}: invalid ELF magic")
    ei_class = hdr[4]
    ei_data = hdr[5]
    if ei_data == 1:
        endian = "<"
    elif ei_data == 2:
        endian = ">"
    else:
        raise ValueError(f"{path}: invalid ELF endianness byte {ei_data}")
    _e_type, e_machine = struct.unpack(endian + "HH", hdr[16:20])
    return ei_class, e_machine


def _parse_third_party_sha256(third_party_path: str) -> dict[str, str]:
    pins: dict[str, str] = {}
    with open(third_party_path, encoding="utf-8") as fh:
        for line in fh:
            m = _SHA256_ROW_RE.match(line.strip())
            if m:
                pins[m.group(1)] = m.group(2).lower()
    return pins


# ---------------------------------------------------------------------------
# Lane 1: manifest-integrity (offline, blocking)
# ---------------------------------------------------------------------------


def check_manifest_integrity(
    repo_root: str = REPO_ROOT,
) -> tuple[str, str, str, str]:
    manifest_path = os.path.join(repo_root, "VENDOR_MANIFEST.json")
    third_party_path = os.path.join(repo_root, "THIRD_PARTY.md")
    py_dir = os.path.abspath(os.path.join(repo_root, "app", "src", "main", "python"))

    with open(manifest_path, encoding="utf-8") as fh:
        manifest = json.load(fh)

    packages = manifest.get("pythonPackages")
    if not isinstance(packages, list) or not packages:
        return STATUS_FAIL, CLASS_SCHEMA, "manifest", "VENDOR_MANIFEST.json missing pythonPackages"

    added_path = False
    if py_dir not in sys.path:
        sys.path.insert(0, py_dir)
        added_path = True

    checked_pkgs: list[str] = []
    gdl_version = "unknown"
    try:
        for entry in packages:
            name = entry.get("name")
            expected_ver = entry.get("version")
            if not name or not expected_ver:
                return (
                    STATUS_FAIL,
                    CLASS_SCHEMA,
                    "manifest",
                    f"malformed pythonPackages entry: {entry!r}",
                )
            mod = importlib.import_module(name)
            mod_file = os.path.abspath(getattr(mod, "__file__", "") or "")
            if not mod_file.startswith(py_dir + os.sep):
                return (
                    STATUS_FAIL,
                    CLASS_IMPORT,
                    f"{name}",
                    f"{name} imported from outside vendored dir: {mod_file}",
                )
            actual_ver = getattr(mod, "__version__", None)
            if actual_ver != expected_ver:
                return (
                    STATUS_FAIL,
                    CLASS_SCHEMA,
                    f"{name}={actual_ver}",
                    f"{name} version mismatch: manifest={expected_ver}, module={actual_ver}",
                )
            checked_pkgs.append(f"{name}=={actual_ver}")
            if name == "gallery_dl":
                gdl_version = str(actual_ver)
    finally:
        if added_path:
            try:
                sys.path.remove(py_dir)
            except ValueError:
                pass

    pins = _parse_third_party_sha256(third_party_path)
    missing_pins = [abi for abi in ELF_MACHINES if abi not in pins]
    if missing_pins:
        return (
            STATUS_FAIL,
            CLASS_SCHEMA,
            f"gallery_dl={gdl_version}",
            f"THIRD_PARTY.md missing N_m3u8DL-RE SHA-256 pin(s) for: {', '.join(missing_pins)}",
        )

    verified_abis: list[str] = []
    for abi, expected_machine in ELF_MACHINES.items():
        so_path = os.path.join(repo_root, "app", "src", "main", "jniLibs", abi, "libnm3u8dlre.so")
        if not os.path.isfile(so_path):
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                f"gallery_dl={gdl_version}",
                f"missing binary: {so_path}",
            )
        ei_class, e_machine = _parse_elf_machine(so_path)
        if ei_class != 2 or e_machine != expected_machine:
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                f"gallery_dl={gdl_version}",
                f"{abi}/libnm3u8dlre.so ELF mismatch: class={ei_class}, machine={e_machine} (expected 2, {expected_machine})",
            )
        actual_sha = _sha256_file(so_path)
        if actual_sha != pins[abi]:
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                f"gallery_dl={gdl_version}",
                f"{abi}/libnm3u8dlre.so SHA-256 mismatch: actual={actual_sha[:12]} expected={pins[abi][:12]}",
            )
        verified_abis.append(f"{abi}(e_machine={e_machine})")

    return (
        STATUS_PASS,
        CLASS_OK,
        f"gallery_dl={gdl_version}",
        f"{len(checked_pkgs)} vendored pkgs + {len(verified_abis)} ELF/SHA-256 binaries verified ({', '.join(verified_abis)})",
    )


# ---------------------------------------------------------------------------
# Lane 2: sleeve-segmented-http (offline local http.server, blocking)
# ---------------------------------------------------------------------------


class _ThreadingHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def check_segmented_http() -> tuple[str, str, str, str]:
    import sleeve_engine

    payload_main = bytes((i * 31 + 7) % 251 for i in range(192 * 1024))
    payload_tiny = b"SLV"
    payload_ignore = b"X" * (32 * 1024)
    range_headers_seen: list[str] = []
    range_lock = threading.Lock()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # noqa: A002
            return

        def do_HEAD(self):  # noqa: N802
            if self.path == "/range-no-head-cl":
                # Deliberately omit Content-Length and Accept-Ranges to force
                # probe() into the Range: bytes=0-0 fallback path.
                self.send_response(200)
                self.send_header("ETag", '"canary-seg-v1"')
                self.end_headers()
            elif self.path == "/tiny":
                self.send_response(200)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(len(payload_tiny)))
                self.end_headers()
            elif self.path == "/ignore-range":
                self.send_response(200)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(len(payload_ignore)))
                self.end_headers()
            else:
                self.send_response(404)
                self.end_headers()

        def do_GET(self):  # noqa: N802
            rng = self.headers.get("Range")
            if rng is not None:
                with range_lock:
                    range_headers_seen.append(f"{self.path}:{rng}")

            if self.path == "/range-no-head-cl":
                data = payload_main
            elif self.path == "/tiny":
                data = payload_tiny
            elif self.path == "/ignore-range":
                # Deliberately violate Range and return HTTP 200.
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload_ignore)))
                self.end_headers()
                try:
                    self.wfile.write(payload_ignore)
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass
                return
            else:
                self.send_response(404)
                self.end_headers()
                return

            if rng and rng.startswith("bytes="):
                spec = rng[len("bytes=") :]
                start_s, _, end_s = spec.partition("-")
                start = int(start_s)
                end = int(end_s) if end_s else len(data) - 1
                end = min(end, len(data) - 1)
                chunk = data[start : end + 1]
                self.send_response(206)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("ETag", '"canary-seg-v1"')
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
                self.send_header("Content-Length", str(len(chunk)))
                self.end_headers()
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass
            else:
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass

    server = _ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    tmpdir = tempfile.mkdtemp(prefix="sleeve_canary_seg_")
    try:
        base = f"http://127.0.0.1:{port}"

        # 1. HEAD without Content-Length -> bytes=0-0 probe + parallel 206 download
        out_main = os.path.join(tmpdir, "main.bin")
        step_main = sleeve_engine.SegmentedHttpStep(
            f"{base}/range-no-head-cl", out_main, subworkers=4
        )
        step_main.run()
        if step_main.state != sleeve_engine.COMPLETED:
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                "in-repo",
                f"range-no-head-cl failed: state={step_main.state}, err={step_main.error}",
            )
        if "/range-no-head-cl:bytes=0-0" not in range_headers_seen:
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                "in-repo",
                f"probe did not send Range: bytes=0-0 (seen={range_headers_seen[:6]})",
            )
        with open(out_main, "rb") as fh:
            if fh.read() != payload_main:
                return STATUS_FAIL, CLASS_SCHEMA, "in-repo", "range-no-head-cl byte mismatch"

        # 2. Tiny file with subworkers > total bytes
        out_tiny = os.path.join(tmpdir, "tiny.bin")
        step_tiny = sleeve_engine.SegmentedHttpStep(f"{base}/tiny", out_tiny, subworkers=8)
        step_tiny.run()
        if step_tiny.state != sleeve_engine.COMPLETED:
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                "in-repo",
                f"tiny file failed: state={step_tiny.state}, err={step_tiny.error}",
            )
        with open(out_tiny, "rb") as fh:
            if fh.read() != payload_tiny:
                return STATUS_FAIL, CLASS_SCHEMA, "in-repo", "tiny file byte mismatch"

        # 3. HTTP 200-on-range rejection + clean scratch removal
        out_ign = os.path.join(tmpdir, "ign.bin")
        step_ign = sleeve_engine.SegmentedHttpStep(
            f"{base}/ignore-range", out_ign, subworkers=4
        )
        step_ign.run()
        if step_ign.state != sleeve_engine.ERROR or "200" not in (step_ign.error or ""):
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                "in-repo",
                f"expected HTTP 200 range rejection, got state={step_ign.state} err={step_ign.error!r}",
            )
        if os.path.exists(out_ign):
            return STATUS_FAIL, CLASS_SCHEMA, "in-repo", "ignore-range unexpectedly created output"

        # 4. Verify zero scratch leftovers (.tmp / .parts-*)
        leftovers = [
            name
            for name in os.listdir(tmpdir)
            if name.endswith(".tmp") or ".parts-" in name
        ]
        if leftovers:
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                "in-repo",
                f"scratch files leaked after runs: {leftovers}",
            )

        return (
            STATUS_PASS,
            CLASS_OK,
            "in-repo",
            "bytes=0-0 probe + 206 segmented (192 KiB), tiny (3 B), 200-on-range rejection, clean temp removal ok",
        )
    finally:
        server.shutdown()
        server.server_close()
        shutil.rmtree(tmpdir, ignore_errors=True)


# ---------------------------------------------------------------------------
# Lane 3: sleeve-sniff (offline SSRF + HTML candidate extraction, blocking)
# ---------------------------------------------------------------------------


def check_sniff() -> tuple[str, str, str, str]:
    import sleeve_engine

    # (a) SSRF guard checks: must reject non-http schemes and loopback hosts.
    for blocked_url in ("file:///etc/passwd", "http://127.0.0.1/", "http://[::1]/"):
        res = json.loads(sleeve_engine.sniff_media(blocked_url))
        if res.get("ok") is not False:
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                "in-repo",
                f"SSRF guard failed to block {blocked_url!r}: {res}",
            )

    # (b) End-to-end HTML candidate extraction served by local http.server via
    # stubbed opener presenting a public URL so sniff_media's SSRF filter runs
    # against extracted candidates too.
    fixture_html = (
        "<!doctype html><html><head>"
        '<meta property="og:video" content="https://cdn.example.invalid/og_clip.mp4?a=1&amp;b=2">'
        '<meta name="twitter:player:stream" content="https://cdn.example.invalid/tw_stream.mp4">'
        "</head><body>"
        '<video src="/media/inline_vid.mp4"></video>'
        '<video src="http://127.0.0.1/secret.mp4"></video>'
        '<script>var hls="https://cdn.example.invalid/hls/master.m3u8?tok=1";'
        'var dash="https://cdn.example.invalid/dash/manifest.mpd";</script>'
        "</body></html>"
    ).encode("utf-8")

    class HtmlHandler(http.server.BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # noqa: A002
            return

        def do_GET(self):  # noqa: N802
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(fixture_html)))
            self.end_headers()
            self.wfile.write(fixture_html)

    server = _ThreadingHTTPServer(("127.0.0.1", 0), HtmlHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        public_page = "https://canary.example.invalid/watch"

        class _ProxyResponse:
            def __init__(self, body_bytes: bytes, content_type: str):
                self._buf = io.BytesIO(body_bytes)
                self.headers = http.client.HTTPMessage()
                self.headers["Content-Type"] = content_type

            def geturl(self) -> str:
                return public_page

            def read(self, n: int = -1) -> bytes:
                return self._buf.read(n)

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                self._buf.close()
                return False

        class _StubOpener:
            def open(self, req, timeout=20):
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                try:
                    conn.request("GET", "/watch")
                    resp = conn.getresponse()
                    data = resp.read()
                    ct = resp.getheader("Content-Type", "text/html; charset=utf-8")
                finally:
                    conn.close()
                return _ProxyResponse(data, ct)

        with mock.patch("urllib.request.build_opener", return_value=_StubOpener()):
            parsed = json.loads(sleeve_engine.sniff_media(public_page))

        if not parsed.get("ok"):
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                "in-repo",
                f"sniff_media failed on HTML fixture: {parsed.get('error')}",
            )
        urls = {item.get("url") for item in (parsed.get("items") or [])}
        expected = {
            "https://cdn.example.invalid/og_clip.mp4?a=1&b=2",
            "https://cdn.example.invalid/tw_stream.mp4",
            "https://canary.example.invalid/media/inline_vid.mp4",
            "https://cdn.example.invalid/hls/master.m3u8?tok=1",
            "https://cdn.example.invalid/dash/manifest.mpd",
        }
        missing = sorted(expected - urls)
        if missing:
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                "in-repo",
                f"sniff_media missing expected candidates: {missing}",
            )
        if any("127.0.0.1" in (u or "") for u in urls):
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                "in-repo",
                "sniff_media leaked loopback candidate URL",
            )
        return (
            STATUS_PASS,
            CLASS_OK,
            "in-repo",
            f"SSRF guard blocked file:// + loopback; extracted {len(urls)} HTML candidates (og:video, twitter:player:stream, video, m3u8, mpd)",
        )
    finally:
        server.shutdown()
        server.server_close()


# ---------------------------------------------------------------------------
# Lane 4: gallery-dl-vendored (offline warmup + live check, blocking)
# ---------------------------------------------------------------------------


def check_gallery_dl(offline_only: bool = False) -> tuple[str, str, str, str]:
    import gallery_dl
    import sleeve_engine

    version = f"gallery_dl={getattr(gallery_dl, '__version__', 'unknown')}"
    warmup = json.loads(sleeve_engine.gallery_warmup())
    if not warmup.get("ok"):
        err = str(warmup.get("error") or "warmup failed")
        return STATUS_FAIL, classify_error(err, default=CLASS_IMPORT), version, f"warmup failed: {err}"

    if not sleeve_engine.gallery_matches(GALLERY_URL):
        return STATUS_FAIL, CLASS_SCHEMA, version, "gallery_matches returned False for directlink PNG"
    if not sleeve_engine.gallery_matches(WIKIMEDIA_URL):
        return STATUS_FAIL, CLASS_SCHEMA, version, "gallery_matches returned False for Wikimedia Commons"
    if sleeve_engine.gallery_matches("https://example.com/clip.mp4"):
        return STATUS_FAIL, CLASS_SCHEMA, version, "gallery_matches unexpectedly matched direct .mp4"

    if offline_only:
        return (
            STATUS_PASS,
            CLASS_OK,
            version,
            f"warmup ok ({warmup.get('seconds')}s), directlink + wikimediacommons match ok",
        )

    direct = json.loads(sleeve_engine.gallery_resolve(GALLERY_URL))
    if not direct.get("ok"):
        err = str(direct.get("error") or "directlink resolve failed")
        return STATUS_FAIL, classify_error(err), version, f"directlink failed: {err}"
    direct_items = direct.get("items") or []
    if not direct_items:
        return STATUS_FAIL, CLASS_SCHEMA, version, "directlink returned no items"

    wiki = json.loads(sleeve_engine.gallery_resolve(WIKIMEDIA_URL))
    if not wiki.get("ok"):
        err = str(wiki.get("error") or "wikimedia resolve failed")
        cls = classify_error(err)
        if cls in (CLASS_BOT_429, CLASS_HTTP_403):
            return (
                STATUS_WARN,
                cls,
                version,
                f"warmup + directlink({len(direct_items)}) ok; wikimediacommons blocked ({err})",
            )
        return STATUS_FAIL, cls, version, f"wikimediacommons failed: {err}"
    wiki_items = wiki.get("items") or []
    wiki_cat = wiki.get("category") or (wiki_items[0].get("category") if wiki_items else "")
    if not wiki_items or wiki_cat == "directlink":
        return (
            STATUS_FAIL,
            CLASS_SCHEMA,
            version,
            f"wikimediacommons schema/category unexpected: items={len(wiki_items)}, category={wiki_cat!r}",
        )

    return (
        STATUS_PASS,
        CLASS_OK,
        version,
        f"warmup({warmup.get('seconds')}s), directlink={len(direct_items)}, {wiki_cat}={len(wiki_items)}",
    )


# ---------------------------------------------------------------------------
# Lane 5: yt-dlp-ladder (online, blocking)
# ---------------------------------------------------------------------------


def _ytdlp_cmd() -> list[str]:
    exe = shutil.which("yt-dlp")
    if exe:
        return [exe]
    return [sys.executable, "-m", "yt_dlp"]


def _get_ytdlp_version() -> str:
    try:
        proc = subprocess.run(
            [*_ytdlp_cmd(), "--version"],
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )
        if proc.returncode == 0 and proc.stdout.strip():
            return proc.stdout.strip().splitlines()[0]
    except Exception:
        pass
    return "unavailable"


def check_yt_dlp_ladder() -> tuple[str, str, str, str]:
    cmd_base = _ytdlp_cmd()
    ver = _get_ytdlp_version()
    if ver == "unavailable":
        return STATUS_FAIL, CLASS_IMPORT, "yt-dlp=unavailable", "yt-dlp binary/module not found"
    version = f"yt-dlp={ver}"

    rung_summaries: list[str] = []
    rung_ok_count = 0
    rung_errors: list[tuple[str, str]] = []

    for label, client_arg in YOUTUBE_CLIENT_LADDER:
        args = [
            *cmd_base,
            "--dump-single-json",
            "--skip-download",
            "--no-warnings",
        ]
        if client_arg:
            args.extend(["--extractor-args", f"youtube:player_client={client_arg}"])
        args.extend(["--", YOUTUBE_URL])
        try:
            proc = subprocess.run(
                args, capture_output=True, text=True, timeout=60, check=False
            )
        except subprocess.TimeoutExpired:
            rung_summaries.append(f"{label}=TIMEOUT")
            rung_errors.append((label, "timed out"))
            continue

        if proc.returncode != 0:
            err_msg = (proc.stderr or proc.stdout or f"exit {proc.returncode}").strip()
            cls = classify_error(err_msg)
            rung_summaries.append(f"{label}=FAIL({cls})")
            rung_errors.append((label, err_msg))
            continue

        try:
            info = json.loads(proc.stdout)
        except json.JSONDecodeError as exc:
            rung_summaries.append(f"{label}=SCHEMA_BREAK")
            rung_errors.append((label, f"invalid JSON: {exc}"))
            continue

        if not isinstance(info, dict) or info.get("id") != YOUTUBE_VIDEO_ID:
            rung_summaries.append(f"{label}=SCHEMA_BREAK")
            rung_errors.append((label, f"unexpected video id: {info.get('id')!r}"))
            continue

        formats = info.get("formats") or []
        downloadable = [
            f
            for f in formats
            if isinstance(f, dict)
            and f.get("url")
            and not (f.get("vcodec") == "none" and f.get("acodec") == "none")
        ]
        if not downloadable:
            rung_summaries.append(f"{label}=0_fmts")
            rung_errors.append((label, "0 downloadable formats"))
            continue

        rung_ok_count += 1
        rung_summaries.append(f"{label}=OK({len(downloadable)} fmts)")

    search_summaries: list[str] = []
    search_failures: list[tuple[str, str]] = []
    for query in ("ytsearch3:test", "ytmsearch3:Daft Punk"):
        args = [
            *cmd_base,
            "--flat-playlist",
            "--dump-single-json",
            "--no-warnings",
            "--",
            query,
        ]
        try:
            proc = subprocess.run(
                args, capture_output=True, text=True, timeout=60, check=False
            )
        except subprocess.TimeoutExpired:
            search_summaries.append(f"{query}=TIMEOUT")
            search_failures.append((query, "timed out"))
            continue
        if proc.returncode != 0:
            err_msg = (proc.stderr or proc.stdout or f"exit {proc.returncode}").strip()
            cls = classify_error(err_msg)
            search_summaries.append(f"{query}=FAIL({cls})")
            search_failures.append((query, err_msg))
            continue
        try:
            data = json.loads(proc.stdout)
            entries = data.get("entries") if isinstance(data, dict) else None
        except json.JSONDecodeError as exc:
            search_summaries.append(f"{query}=SCHEMA_BREAK")
            search_failures.append((query, f"invalid JSON: {exc}"))
            continue
        if not isinstance(entries, list) or not entries:
            search_summaries.append(f"{query}=0_entries")
            search_failures.append((query, "0 search entries"))
            continue
        search_summaries.append(f"{query}=OK({len(entries)})")

    detail = "; ".join(rung_summaries + search_summaries)
    if rung_ok_count == 0 or search_failures:
        first_err = (
            rung_errors[0][1] if rung_ok_count == 0 and rung_errors else search_failures[0][1]
        )
        return STATUS_FAIL, classify_error(first_err), version, detail

    return STATUS_PASS, CLASS_OK, version, detail


# ---------------------------------------------------------------------------
# Lane 6: sleeve-spotify (online, blocking)
# ---------------------------------------------------------------------------


def _validate_spotify_payload(obj: object, expected_kind: str | None = None) -> str | None:
    """Validate Spotify JSON object contract expected by SpotifyBridge.kt."""
    if not isinstance(obj, dict):
        return f"expected JSON object dict, got {type(obj).__name__}"
    required = ("ok", "kind", "title", "tracks", "totalTracks", "truncated")
    missing = [k for k in required if k not in obj]
    if missing:
        return f"missing required keys: {missing}"
    if obj.get("ok") is not True:
        return f"ok is not True: {obj.get('error')!r}"
    if expected_kind and obj.get("kind") != expected_kind:
        return f"expected kind={expected_kind!r}, got {obj.get('kind')!r}"
    tracks = obj.get("tracks")
    if not isinstance(tracks, list) or not tracks:
        return "tracks must be a non-empty list"
    if not isinstance(obj.get("totalTracks"), int) or obj["totalTracks"] < len(tracks):
        return f"invalid totalTracks={obj.get('totalTracks')!r}"
    if not isinstance(obj.get("truncated"), bool):
        return f"truncated must be bool, got {type(obj.get('truncated')).__name__}"
    return None


def check_spotify() -> tuple[str, str, str, str]:
    import sleeve_spotify

    # 1. Public playlist via resolve()
    pl_payload = json.loads(sleeve_spotify.resolve(SPOTIFY_PLAYLIST_URL))
    err = _validate_spotify_payload(pl_payload, expected_kind="playlist")
    if err:
        raw_err = str(pl_payload.get("error") or err) if isinstance(pl_payload, dict) else err
        return STATUS_FAIL, classify_error(raw_err), "in-repo", f"playlist resolve: {raw_err}"

    # 2. Public track via resolve()
    tr_payload = json.loads(sleeve_spotify.resolve(SPOTIFY_TRACK_URL))
    err = _validate_spotify_payload(tr_payload, expected_kind="track")
    if err:
        raw_err = str(tr_payload.get("error") or err) if isinstance(tr_payload, dict) else err
        return STATUS_FAIL, classify_error(raw_err), "in-repo", f"track resolve: {raw_err}"

    # 3. Direct strategy 1: _via_embed
    embed_obj = sleeve_spotify._via_embed("playlist", SPOTIFY_PLAYLIST_ID)
    err = _validate_spotify_payload(embed_obj, expected_kind="playlist")
    if err:
        return STATUS_FAIL, CLASS_SCHEMA, "in-repo", f"_via_embed schema break: {err}"

    # 4. Direct strategy 2: _via_anon_token (validate schema strictly whenever it returns)
    anon_note = "anon_token=OK"
    try:
        anon_obj = sleeve_spotify._via_anon_token("track", SPOTIFY_TRACK_ID)
        err = _validate_spotify_payload(anon_obj, expected_kind="track")
        if err:
            return STATUS_FAIL, CLASS_SCHEMA, "in-repo", f"_via_anon_token schema break: {err}"
    except Exception as exc:  # noqa: BLE001
        cls = classify_error(exc)
        if cls in (CLASS_BOT_429, CLASS_HTTP_403, CLASS_TIMEOUT):
            anon_note = f"anon_token=blocked({cls})"
        else:
            # Spotify's /get_access_token sometimes rejects datacenter IPs with 400/token error
            # while embed remains healthy; fail on Python/schema errors, note network/token block.
            if isinstance(exc, (TypeError, KeyError, ValueError, json.JSONDecodeError)):
                return STATUS_FAIL, CLASS_SCHEMA, "in-repo", f"_via_anon_token error: {exc}"
            anon_note = f"anon_token=unavailable({type(exc).__name__}: {exc})"

    return (
        STATUS_PASS,
        CLASS_OK,
        "in-repo",
        f"playlist={len(pl_payload['tracks'])}/{pl_payload['totalTracks']} tracks, "
        f"track={tr_payload['title']!r}, embed=OK, {anon_note}",
    )


# ---------------------------------------------------------------------------
# Lane 7: sleeve-audible (offline gate + online ASIN resolve, blocking)
# ---------------------------------------------------------------------------


def check_audible(offline_only: bool = False) -> tuple[str, str, str, str]:
    import sleeve_audible
    import sleeve_audible_gate

    # Always run offline gate_audible() smoke checks including () regression.
    book_track = {
        "title": "Dune",
        "artist": "Frank Herbert",
        "authors": "Frank Herbert",
        "durationMs": 75_720_000,
        "language": "english",
    }
    for title in (
        "Dune Full Audiobook",
        "Dune () Full Audiobook []",
        "Dune {} Full Audiobook",
        "Dune ( ) [ ] { } | Full Audiobook",
    ):
        gate_res = json.loads(
            sleeve_audible_gate.gate_audible(
                book_track,
                [
                    {
                        "id": "canary-dune",
                        "url": "https://youtu.be/canary-dune",
                        "title": title,
                        "uploader": "Audiobooks",
                        "channel": "Audiobooks",
                        "duration": 75_720,
                    }
                ],
            )
        )
        if not gate_res.get("ok") or gate_res.get("verdict") != "pass":
            return (
                STATUS_FAIL,
                CLASS_SCHEMA,
                "in-repo",
                f"gate_audible failed for {title!r}: {gate_res}",
            )

    conflict_res = json.loads(
        sleeve_audible_gate.gate_audible(
            book_track,
            [
                {
                    "id": "canary-dune-de",
                    "url": "https://youtu.be/canary-dune-de",
                    "title": "Dune () (German) [] Full Audiobook {}",
                    "uploader": "Audiobooks",
                    "channel": "Audiobooks",
                    "duration": 75_720,
                }
            ],
        )
    )
    if not conflict_res.get("ok") or conflict_res.get("verdict") != "hold":
        return (
            STATUS_FAIL,
            CLASS_SCHEMA,
            "in-repo",
            f"gate_audible language conflict failed: {conflict_res}",
        )

    if offline_only:
        return (
            STATUS_PASS,
            CLASS_OK,
            "in-repo",
            "gate_audible ok (match, empty-bracket ()/[]/{} regression, language hold)",
        )

    resolved_notes: list[str] = []
    for label, url in (("letter", AUDIBLE_LETTER_URL), ("numeric", AUDIBLE_NUMERIC_URL)):
        payload = json.loads(sleeve_audible.resolve(url))
        if not payload.get("ok"):
            err = str(payload.get("error") or "resolve failed")
            return STATUS_FAIL, classify_error(err), "in-repo", f"{label} ASIN failed: {err}"
        tracks = payload.get("tracks") or []
        if not tracks or not (tracks[0].get("title") or "").strip():
            return STATUS_FAIL, CLASS_SCHEMA, "in-repo", f"{label} ASIN returned empty tracks/title"
        resolved_notes.append(f"{label}={tracks[0]['title']!r}")

    return (
        STATUS_PASS,
        CLASS_OK,
        "in-repo",
        f"gate_audible ok; {', '.join(resolved_notes)}",
    )


# ---------------------------------------------------------------------------
# Lane 8a: sleeve-instagram-embed (online, advisory)
# ---------------------------------------------------------------------------


def check_instagram_embed() -> tuple[str, str, str, str]:
    import sleeve_engine

    raw = sleeve_engine.instagram_embed(IG_PUBLIC_URL, max_seconds=15.0)
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        return STATUS_FAIL, CLASS_SCHEMA, "in-repo", f"invalid JSON from instagram_embed: {exc}"

    if not isinstance(payload, dict) or "ok" not in payload:
        return STATUS_FAIL, CLASS_SCHEMA, "in-repo", f"unexpected payload shape: {type(payload).__name__}"

    if payload.get("ok"):
        items = payload.get("items")
        if not isinstance(items, list) or not items or not str(items[0].get("url", "")).startswith("http"):
            return STATUS_FAIL, CLASS_SCHEMA, "in-repo", f"ok=True but invalid items: {items!r}"
        return STATUS_PASS, CLASS_OK, "in-repo", f"resolved {len(items)} item(s)"

    err = str(payload.get("error") or "unknown error")
    cls = classify_error(err, default=CLASS_BOT_429)
    if cls in (CLASS_BOT_429, CLASS_HTTP_403, CLASS_TIMEOUT) or any(
        tok in err.lower() for tok in ("no media", "login", "http 401", "http 404")
    ):
        warn_cls = cls if cls in (CLASS_BOT_429, CLASS_HTTP_403, CLASS_TIMEOUT) else CLASS_BOT_429
        return STATUS_WARN, warn_cls, "in-repo", f"datacenter/anonymous block: {err}"
    return STATUS_FAIL, CLASS_SCHEMA, "in-repo", f"instagram_embed error: {err}"


# ---------------------------------------------------------------------------
# Lane 8b: reddit-json (online, advisory)
# ---------------------------------------------------------------------------


def check_reddit_json() -> tuple[str, str, str, str]:
    req = urllib.request.Request(
        REDDIT_JSON_URL,
        headers={
            "User-Agent": "SleeveCanary/1.0 (+https://github.com/Burry071/sleeve)",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read(1 << 20).decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        cls = classify_error(exc)
        if exc.code in (403, 429):
            return STATUS_WARN, cls, "urllib", f"datacenter HTTP {exc.code}"
        return STATUS_WARN, cls, "urllib", f"HTTP {exc.code}: {exc.reason}"
    except (TimeoutError, socket.timeout) as exc:
        return STATUS_WARN, CLASS_TIMEOUT, "urllib", f"timeout: {exc}"
    except urllib.error.URLError as exc:
        cls = classify_error(exc, default=CLASS_TIMEOUT)
        return STATUS_WARN, cls, "urllib", f"network error: {exc}"

    try:
        data = json.loads(body)
    except json.JSONDecodeError as exc:
        return STATUS_FAIL, CLASS_SCHEMA, "urllib", f"invalid JSON: {exc}"

    if not isinstance(data, dict) or data.get("kind") != "Listing":
        return STATUS_FAIL, CLASS_SCHEMA, "urllib", f"unexpected Reddit root kind={data.get('kind') if isinstance(data, dict) else type(data).__name__}"
    children = (data.get("data") or {}).get("children")
    if not isinstance(children, list):
        return STATUS_FAIL, CLASS_SCHEMA, "urllib", "missing data.children list"
    return STATUS_PASS, CLASS_OK, "urllib", f"Listing ok ({len(children)} child(ren))"


# ---------------------------------------------------------------------------
# Runner, reporting & CLI
# ---------------------------------------------------------------------------


def run_lane(lane: str, tier: str, fn) -> dict[str, object]:
    started = time.monotonic()
    try:
        status, classification, version, detail = fn()
    except Exception as exc:  # noqa: BLE001 - non-short-circuiting runner
        cls = classify_error(exc)
        status = (
            STATUS_WARN
            if tier == TIER_ADVISORY and cls in (CLASS_BOT_429, CLASS_HTTP_403, CLASS_TIMEOUT)
            else STATUS_FAIL
        )
        classification = cls
        version = "error"
        detail = f"{type(exc).__name__}: {exc}"
    elapsed = time.monotonic() - started
    return make_record(lane, tier, status, classification, elapsed, version, detail)


def compute_exit_code(records: list[dict[str, object]]) -> int:
    """Exit 0 when all blocking lanes are non-FAIL (even if advisory lanes WARN)."""
    for rec in records:
        if rec.get("tier") == TIER_BLOCKING and rec.get("status") == STATUS_FAIL:
            return 1
    return 0


def build_report(
    records: list[dict[str, object]],
    *,
    offline_only: bool,
    versions: dict[str, str] | None = None,
) -> dict[str, object]:
    blocking_failures = sum(
        1
        for r in records
        if r.get("tier") == TIER_BLOCKING and r.get("status") == STATUS_FAIL
    )
    advisory_failures = sum(
        1
        for r in records
        if r.get("tier") == TIER_ADVISORY and r.get("status") == STATUS_FAIL
    )
    advisory_warnings = sum(
        1 for r in records if r.get("status") == STATUS_WARN
    )
    immediate_alert = any(
        r.get("status") == STATUS_FAIL
        and r.get("classification") in IMMEDIATE_ALERT_CLASSES
        for r in records
    )
    failing_lanes = [str(r["lane"]) for r in records if r.get("status") == STATUS_FAIL]
    return {
        "timestamp_utc": datetime.datetime.now(datetime.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        ),
        "mode": "offline" if offline_only else "full",
        "python_version": sys.version.split()[0],
        "versions": dict(versions or {}),
        "ok": blocking_failures == 0,
        "blocking_failures": blocking_failures,
        "advisory_failures": advisory_failures,
        "advisory_warnings": advisory_warnings,
        "immediate_alert": immediate_alert,
        "failing_lanes": failing_lanes,
        "lanes": records,
    }


def _md_escape(value: object) -> str:
    return str(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def render_summary_markdown(report: dict[str, object]) -> str:
    verdict = "PASS" if report.get("ok") else "FAIL"
    lines = [
        f"## Extractor Canary Report ({verdict})",
        "",
        f"- **Timestamp (UTC):** `{report.get('timestamp_utc', '')}`",
        f"- **Mode:** `{report.get('mode', '')}`",
        f"- **Python:** `{report.get('python_version', '')}`",
        f"- **Blocking failures:** `{report.get('blocking_failures', 0)}` | "
        f"**Warnings:** `{report.get('advisory_warnings', 0)}` | "
        f"**Immediate alert:** `{str(bool(report.get('immediate_alert'))).lower()}`",
    ]
    versions = report.get("versions") or {}
    if isinstance(versions, dict) and versions:
        ver_str = ", ".join(f"`{k}={v}`" for k, v in sorted(versions.items()))
        lines.append(f"- **Tool versions:** {ver_str}")
    lines.extend(
        [
            "",
            "| Lane | Tier | Status | Classification | Duration (s) | Version | Detail |",
            "|---|---|---|---|---:|---|---|",
        ]
    )
    for rec in report.get("lanes") or []:
        lines.append(
            f"| `{_md_escape(rec['lane'])}` "
            f"| {_md_escape(rec['tier'])} "
            f"| **{_md_escape(rec['status'])}** "
            f"| `{_md_escape(rec['classification'])}` "
            f"| {rec['duration_s']:.3f} "
            f"| `{_md_escape(rec['version'])}` "
            f"| {_md_escape(rec['detail'])} |"
        )
    lines.append("")
    return "\n".join(lines)


def collect_versions(offline_only: bool) -> dict[str, str]:
    versions: dict[str, str] = {"python": sys.version.split()[0]}
    try:
        import gallery_dl

        versions["gallery_dl_vendored"] = str(getattr(gallery_dl, "__version__", "unknown"))
    except Exception:
        versions["gallery_dl_vendored"] = "import_error"
    if not offline_only:
        versions["yt-dlp"] = _get_ytdlp_version()
    return versions


def run_all(offline_only: bool = False) -> tuple[dict[str, object], int]:
    lane_specs: list[tuple[str, str, object]] = [
        ("manifest-integrity", TIER_BLOCKING, check_manifest_integrity),
        ("sleeve-segmented-http", TIER_BLOCKING, check_segmented_http),
        ("sleeve-sniff", TIER_BLOCKING, check_sniff),
        (
            "gallery-dl-vendored",
            TIER_BLOCKING,
            lambda: check_gallery_dl(offline_only=offline_only),
        ),
    ]
    if not offline_only:
        lane_specs.extend(
            [
                ("yt-dlp-ladder", TIER_BLOCKING, check_yt_dlp_ladder),
                ("sleeve-spotify", TIER_BLOCKING, check_spotify),
            ]
        )
    lane_specs.append(
        (
            "sleeve-audible",
            TIER_BLOCKING,
            lambda: check_audible(offline_only=offline_only),
        )
    )
    if not offline_only:
        lane_specs.extend(
            [
                ("sleeve-instagram-embed", TIER_ADVISORY, check_instagram_embed),
                ("reddit-json", TIER_ADVISORY, check_reddit_json),
            ]
        )

    records: list[dict[str, object]] = []
    for name, tier, fn in lane_specs:
        rec = run_lane(name, tier, fn)
        records.append(rec)
        print(
            f"{rec['status']} [{rec['tier']}] {rec['lane']} "
            f"({rec['classification']}, {rec['duration_s']:.3f}s): {rec['detail']}"
        )

    report = build_report(
        records, offline_only=offline_only, versions=collect_versions(offline_only)
    )
    exit_code = compute_exit_code(records)
    return report, exit_code


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Run Sleeve extractor canary lanes (non-short-circuiting)."
    )
    parser.add_argument(
        "--json",
        dest="json_path",
        default="",
        help="Write structured JSON report to this path.",
    )
    parser.add_argument(
        "--summary-md",
        dest="summary_md_path",
        default="",
        help="Write Markdown summary table to this path.",
    )
    parser.add_argument(
        "--offline-only",
        action="store_true",
        help="Run only offline/local-server lanes without live internet access.",
    )
    args = parser.parse_args(argv)

    report, exit_code = run_all(offline_only=args.offline_only)

    if args.json_path:
        parent = os.path.dirname(os.path.abspath(args.json_path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(args.json_path, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2)
            fh.write("\n")

    if args.summary_md_path:
        parent = os.path.dirname(os.path.abspath(args.summary_md_path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(args.summary_md_path, "w", encoding="utf-8") as fh:
            fh.write(render_summary_markdown(report))

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
