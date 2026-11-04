#!/usr/bin/env python3
"""Verifier for Sleeve's published GitHub release assets and Orion mirror.

Sleeve (repo ``Burry071/sleeve``) publishes APKs to GitHub Releases; the Orion
Store catalog mirrors those URLs.  The known failure mode is a mirror entry
that points at a *deleted* release asset: the URL returns 404 and the store
sits at 0% forever.  This script proves, without downloading the whole APK,
that the newest release the store could pick is actually fetchable, and that
the mirror entry is not stale.

Network access is strictly read-only: it never uploads, edits, deletes, or
"fixes" anything, and it never prints credentials or signed query strings.
``--download`` writes the APK locally and ``--record`` updates the local
``release-record.json``; both are opt-in.

Usage examples
--------------
1. Check the newest beta (the channel the Orion mirror tracks)::

       python scripts/verify_release.py --channel beta --limit 30

2. Check that a *stable* release exists and its arm64-v8a APK is fetchable::

       python scripts/verify_release.py --channel stable --abi arm64-v8a --limit 50

3. Archive the newest generic APK locally and inspect it with aapt/apksigner::

       python scripts/verify_release.py --channel auto --download ./apk-out

Exit codes: ``0`` all checks passed (warnings allowed), ``1`` at least one
FAIL, ``130`` interrupted.
"""

from __future__ import annotations

import argparse
import functools
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Optional

try:  # imported as scripts.verify_release (package / unittest)
    from . import release_guard
except ImportError:  # run directly: python scripts/verify_release.py
    import release_guard

DEFAULT_REPO = "Burry071/sleeve"
DEFAULT_LIMIT = 30
GENERIC_APK_NAME = "Sleeve-generic-release.apk"
EXPECTED_PACKAGE = "com.sleeve.app"
MIRROR_URL = (
    "https://raw.githubusercontent.com/RookieEnough/Orion-Data/data/mirror.json"
)
MIRROR_KEY = "github::github.com::burry071/sleeve"
USER_AGENT = "sleeve-verify-release/1.1 (read-only release verifier)"
DEFAULT_RECORD = "release-record.json"

REDIRECT_CODES = {301, 302, 303, 307, 308}
RETRYABLE_STATUSES = {429, 500, 502, 503, 504}
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
TAG_RE = re.compile(
    r"^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z][0-9A-Za-z.-]*))?(?:\+[0-9A-Za-z.-]+)?$"
)


# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------


class Log:
    """One line per check, PASS/FAIL/WARN plus INFO context lines."""

    def __init__(self, stream=None) -> None:
        self.passed = 0
        self.warned = 0
        self.failed = 0
        # --json reserves stdout for the machine-readable summary.
        self.stream = stream or sys.stdout

    def _emit(self, level: str, scope: str, message: str) -> None:
        print(f"{level:<4} {scope:<13} {message}", file=self.stream, flush=True)

    def info(self, scope: str, message: str) -> None:
        self._emit("INFO", scope, message)

    def ok(self, scope: str, message: str) -> None:
        self.passed += 1
        self._emit("PASS", scope, message)

    def warn(self, scope: str, message: str) -> None:
        self.warned += 1
        self._emit("WARN", scope, message)

    def fail(self, scope: str, message: str) -> None:
        self.failed += 1
        self._emit("FAIL", scope, message)


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------


def human_bytes(size: Optional[int]) -> str:
    if size is None:
        return "unknown"
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if value < 1024 or unit == "GiB":
            if unit == "B":
                return f"{int(size)} B"
            return f"{value:.1f} {unit} ({int(size):,} bytes)"
        value /= 1024
    return f"{int(size)} bytes"


def redact_url(url: str) -> str:
    """Drop the query string: GitHub asset URLs carry signed JWT/STS tokens."""
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return "<unparsable url>"
    query = "?<redacted>" if parts.query else ""
    return f"{parts.scheme}://{parts.netloc}{parts.path}{query}"


def url_host(url: str) -> str:
    try:
        return urllib.parse.urlsplit(url).netloc or "(none)"
    except ValueError:
        return "(none)"


def parse_content_length(headers: dict[str, str]) -> Optional[int]:
    raw = headers.get("content-length")
    if raw is None:
        return None
    try:
        return int(raw.strip())
    except (TypeError, ValueError):
        return None


def first_line(text: str, limit: int = 200) -> str:
    for line in (text or "").splitlines():
        line = line.strip()
        if line:
            return line[:limit]
    return ""


# ---------------------------------------------------------------------------
# HTTP (manual redirect walking so the chain can be reported)
# ---------------------------------------------------------------------------


class FetchError(Exception):
    """Network-level failure (DNS, TLS, timeout, connection reset)."""


@dataclass
class FetchResult:
    requested_url: str
    final_url: str
    status: int
    headers: dict[str, str]
    body: bytes
    chain: list[tuple[int, str]] = field(default_factory=list)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Turn 3xx into HTTPError so the redirect chain can be logged by hand."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102
        return None


def _read_body(stream, read_body: int) -> bytes:
    if read_body == 0:
        return b""
    try:
        return stream.read(read_body)
    except (OSError, ValueError):
        return b""


def _fetch_once(
    url: str, method: str, headers: dict[str, str], timeout: float, read_body: int
) -> tuple[int, dict[str, str], bytes]:
    request = urllib.request.Request(url, method=method)
    request.add_header("User-Agent", USER_AGENT)
    for name, value in headers.items():
        request.add_header(name, value)
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(request, timeout=timeout) as response:
            return (
                response.status,
                {k.lower(): v for k, v in response.headers.items()},
                _read_body(response, read_body),
            )
    except urllib.error.HTTPError as exc:
        return (
            exc.code,
            {k.lower(): v for k, v in (exc.headers or {}).items()},
            _read_body(exc, read_body),
        )
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        reason = getattr(exc, "reason", exc)
        raise FetchError(f"{type(exc).__name__}: {reason}") from exc


def cache_busted(url: str) -> str:
    """Append a throwaway query param so a CDN cannot serve a cached copy.

    Used for raw.githubusercontent.com, where mirror.json can lag a day behind
    the GitHub API through no fault of ours.
    """
    parts = urllib.parse.urlsplit(url)
    query = [
        (key, value)
        for key, value in urllib.parse.parse_qsl(parts.query, keep_blank_values=True)
        if key.lower() != "_cb"
    ]
    query.append(("_cb", str(int(time.time() * 1000))))
    return urllib.parse.urlunsplit(parts._replace(query=urllib.parse.urlencode(query)))


def _retry_after_seconds(headers: dict[str, str]) -> Optional[float]:
    raw = headers.get("retry-after")
    if not raw:
        return None
    try:
        value = float(raw.strip())
    except (TypeError, ValueError):
        return None
    return max(0.0, min(value, 60.0))


def _backoff_seconds(attempt: int, base: float) -> float:
    return min(base * (2 ** (attempt - 1)), 8.0)


def fetch_follow(
    url: str,
    *,
    method: str = "GET",
    headers: Optional[dict[str, str]] = None,
    timeout: float = 30.0,
    read_body: int = 0,
    max_hops: int = 10,
    retries: int = 2,
    retry_delay: float = 0.5,
    log: Optional[Log] = None,
    scope: str = "http",
) -> FetchResult:
    """GET/HEAD a URL, walking redirects manually.

    Transient failures (network errors, 429, 5xx) are retried ``retries``
    times with exponential backoff, honouring ``Retry-After`` when present.
    A retry never hides the eventual result: the last response is returned
    unchanged, so callers still see a 404/500 as a check failure.
    """
    headers = headers or {}
    chain: list[tuple[int, str]] = []
    current = url
    current_method = method
    attempts = 0
    hop = 0
    while hop <= max_hops:
        try:
            status, response_headers, body = _fetch_once(
                current, current_method, headers, timeout, read_body
            )
        except FetchError as exc:
            if attempts >= retries:
                raise
            attempts += 1
            delay = _backoff_seconds(attempts, retry_delay)
            if log is not None:
                log.warn(
                    scope,
                    f"network error ({exc}); retry {attempts}/{retries} in {delay:.1f}s",
                )
            time.sleep(delay)
            continue
        if status in RETRYABLE_STATUSES and attempts < retries:
            attempts += 1
            wait = _retry_after_seconds(response_headers)
            delay = wait if wait is not None else _backoff_seconds(attempts, retry_delay)
            if log is not None:
                log.warn(
                    scope,
                    f"HTTP {status}; retry {attempts}/{retries} in {delay:.1f}s",
                )
            time.sleep(delay)
            continue
        chain.append((status, current))
        location = response_headers.get("location")
        if status in REDIRECT_CODES and location:
            current = urllib.parse.urljoin(current, location)
            if status == 303 and current_method != "HEAD":
                current_method = "GET"
            hop += 1
            continue
        return FetchResult(url, current, status, response_headers, body, chain)
    raise FetchError(f"more than {max_hops} redirects starting at {redact_url(url)}")


# ---------------------------------------------------------------------------
# Semver
# ---------------------------------------------------------------------------


def _compare_prerelease(left: tuple[str, ...], right: tuple[str, ...]) -> int:
    """Semver rule: a prerelease sorts *below* the release; numeric ids first."""
    if not left and not right:
        return 0
    if not left:
        return 1
    if not right:
        return -1
    for a, b in zip(left, right):
        if a == b:
            continue
        a_num, b_num = a.isdigit(), b.isdigit()
        if a_num and b_num:
            return -1 if int(a) < int(b) else 1
        if a_num:
            return -1
        if b_num:
            return 1
        return -1 if a < b else 1
    if len(left) == len(right):
        return 0
    return -1 if len(left) < len(right) else 1


@functools.total_ordering
class Version:
    __slots__ = ("major", "minor", "patch", "prerelease")

    def __init__(
        self, major: int, minor: int, patch: int, prerelease: tuple[str, ...] = ()
    ) -> None:
        self.major = major
        self.minor = minor
        self.patch = patch
        self.prerelease = prerelease

    @property
    def is_prerelease(self) -> bool:
        return bool(self.prerelease)

    def _core(self) -> tuple[int, int, int]:
        return (self.major, self.minor, self.patch)

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Version):
            return NotImplemented
        return self._core() == other._core() and self.prerelease == other.prerelease

    def __lt__(self, other: object) -> bool:
        if not isinstance(other, Version):
            return NotImplemented
        if self._core() != other._core():
            return self._core() < other._core()
        return _compare_prerelease(self.prerelease, other.prerelease) < 0

    def __hash__(self) -> int:
        return hash((self._core(), self.prerelease))

    def __str__(self) -> str:
        base = f"{self.major}.{self.minor}.{self.patch}"
        if self.prerelease:
            base += "-" + ".".join(self.prerelease)
        return base


def parse_version(tag: str) -> Optional[Version]:
    match = TAG_RE.match((tag or "").strip())
    if not match:
        return None
    prerelease: tuple[str, ...] = ()
    if match.group(4):
        prerelease = tuple(part for part in match.group(4).split(".") if part)
    return Version(
        int(match.group(1)), int(match.group(2)), int(match.group(3)), prerelease
    )


# ---------------------------------------------------------------------------
# GitHub release discovery
# ---------------------------------------------------------------------------


def github_headers() -> dict[str, str]:
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        # Value is never logged.
        headers["Authorization"] = f"Bearer {token}"
    return headers


def _releases_via_gh(repo: str, limit: int, log: Log) -> Optional[list]:
    if not shutil.which("gh"):
        return None
    log.warn("api", "falling back to authenticated `gh api` (unauthenticated rate limit hit)")
    cmd = [
        "gh",
        "api",
        f"repos/{repo}/releases?per_page={limit}",
        "-H",
        "Accept: application/vnd.github+json",
    ]
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=90,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        log.fail("api", f"gh api could not run: {exc}")
        return None
    if proc.returncode != 0:
        log.fail("api", f"gh api exited {proc.returncode}: {first_line(proc.stderr)}")
        return None
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        log.fail("api", f"gh api returned invalid JSON: {exc}")
        return None
    if not isinstance(data, list):
        log.fail("api", "gh api returned JSON that is not a release list")
        return None
    log.ok("api", f"gh api returned {len(data)} release(s)")
    return data


def fetch_releases(repo: str, limit: int, log: Log, timeout: float) -> Optional[list]:
    url = f"https://api.github.com/repos/{repo}/releases?per_page={limit}"
    log.info("api", f"GET {url}")
    try:
        result = fetch_follow(
            url,
            headers=github_headers(),
            timeout=timeout,
            read_body=-1,
            log=log,
            scope="api",
        )
    except FetchError as exc:
        log.fail("api", f"request failed: {exc}")
        return None
    if result.status == 200:
        try:
            data = json.loads(result.body)
        except json.JSONDecodeError as exc:
            log.fail("api", f"HTTP 200 but body is not JSON: {exc}")
            return None
        if not isinstance(data, list):
            log.fail("api", "HTTP 200 but payload is not a release list")
            return None
        log.ok("api", f"HTTP 200, {len(data)} release(s) returned")
        return data
    if result.status in (403, 429):
        detail = first_line(result.body.decode("utf-8", "replace"), 120)
        log.warn("api", f"HTTP {result.status} (rate limited?) {detail}")
        fallback = _releases_via_gh(repo, limit, log)
        if fallback is not None:
            return fallback
        log.fail("api", "GitHub API rate limit reached and no gh fallback available")
        return None
    log.fail(
        "api",
        f"HTTP {result.status} for {url} "
        f"(content-type={result.headers.get('content-type', 'unknown')})",
    )
    return None


@dataclass
class Candidate:
    tag: str
    version: Version
    raw: dict


def select_release(
    releases: list, channel: str, log: Log
) -> tuple[list[Candidate], list[Candidate], str]:
    """Return (eligible candidates, all parsed candidates, effective channel)."""
    parsed: list[Candidate] = []
    for release in releases:
        if not isinstance(release, dict):
            continue
        tag = str(release.get("tag_name") or "")
        if release.get("draft"):
            log.info("discovery", f"skipping draft {tag or '(no tag)'}")
            continue
        version = parse_version(tag)
        if version is None:
            log.warn("discovery", f"skipping unparsable tag {tag!r} (want vX.Y.Z[-pre])")
            continue
        parsed.append(Candidate(tag, version, release))
    if not parsed:
        log.fail("discovery", "no published release with a semantic-version tag")
        return [], [], channel

    parsed.sort(key=lambda c: c.version, reverse=True)
    tags = ", ".join(c.tag for c in parsed)
    log.ok("discovery", f"{len(parsed)} published release(s): {tags}")

    by_version: dict[Version, list[str]] = {}
    for candidate in parsed:
        by_version.setdefault(candidate.version, []).append(candidate.tag)
    for version, duplicate_tags in sorted(by_version.items()):
        if len(duplicate_tags) > 1:
            log.fail(
                "discovery",
                f"version {version} has {len(duplicate_tags)} published tags "
                f"({', '.join(duplicate_tags)}); a version name must never be reused",
            )

    for candidate in parsed:
        marked = bool(candidate.raw.get("prerelease"))
        if candidate.version.is_prerelease and not marked:
            log.info(
                "discovery",
                f"{candidate.tag} is a prerelease tag but GitHub does not mark it "
                "prerelease (intentional: /releases/latest must serve the APK link)",
            )
        elif marked and not candidate.version.is_prerelease:
            log.warn(
                "discovery",
                f"{candidate.tag} is marked prerelease on GitHub but has a stable tag name",
            )

    effective = channel
    if channel == "auto":
        effective = "stable" if any(not c.version.is_prerelease for c in parsed) else "beta"
        log.info("discovery", f"auto channel resolves to {effective}")

    if effective == "stable":
        eligible = [c for c in parsed if not c.version.is_prerelease]
    else:
        eligible = list(parsed)

    if not eligible:
        log.fail(
            "discovery",
            f"no {effective} release among published tags: {tags}",
        )
        if effective == "stable":
            log.info(
                "discovery",
                "hint: --channel beta (or auto) accepts prereleases; stable needs a vX.Y.Z tag",
            )
        return [], parsed, effective

    log.info("discovery", f"channel {effective}: {len(eligible)} eligible candidate(s)")
    return eligible, parsed, effective


# ---------------------------------------------------------------------------
# Asset selection
# ---------------------------------------------------------------------------


def pick_asset(assets: list, abi: Optional[str]) -> Optional[dict]:
    apks = [
        asset
        for asset in assets
        if isinstance(asset, dict)
        and str(asset.get("name") or "").lower().endswith(".apk")
    ]
    if not apks:
        return None
    apks.sort(key=lambda a: str(a.get("name") or "").lower())
    if abi:
        wanted = abi.lower()
        for asset in apks:
            if wanted in str(asset.get("name") or "").lower():
                return asset
    for asset in apks:
        if str(asset.get("name") or "").lower() == GENERIC_APK_NAME.lower():
            return asset
    return apks[0]


def choose_candidate(
    eligible: list[Candidate], abi: Optional[str], log: Log
) -> tuple[Optional[Candidate], Optional[dict]]:
    for candidate in eligible:
        assets = candidate.raw.get("assets") or []
        if not isinstance(assets, list):
            assets = []
        asset = pick_asset(assets, abi)
        if asset is None:
            log.warn("asset", f"{candidate.tag}: no .apk asset attached, skipping")
            continue
        name = str(asset.get("name") or "<unnamed>")
        size = asset.get("size")
        if not isinstance(size, int) or size <= 0:
            log.warn(
                "asset",
                f"{candidate.tag}: {name} reported size {size!r}, skipping "
                "(missing or zero-size asset)",
            )
            continue
        state = str(asset.get("state") or "").lower()
        if state and state != "uploaded":
            log.warn(
                "asset",
                f"{candidate.tag}: {name} state={state!r}; upload incomplete, skipping",
            )
            continue
        if abi and abi.lower() not in name.lower():
            log.warn(
                "asset",
                f"{candidate.tag}: no asset name contains ABI {abi}; using {name}",
            )
        digest = str(asset.get("digest") or "").strip().lower()
        digest_value = digest.split(":", 1)[1] if digest.startswith("sha256:") else ""
        if SHA256_RE.match(digest_value):
            log.info("asset", f"{candidate.tag}: GitHub digest sha256:{digest_value}")
        else:
            log.warn(
                "asset",
                f"{candidate.tag}: GitHub reports no usable sha256 digest for {name}; "
                "pin the APK with --expect-sha256 after downloading it once",
            )
        if name.lower() != GENERIC_APK_NAME.lower() and not any(
            str(item.get("name") or "").lower() == GENERIC_APK_NAME.lower()
            for item in assets
            if isinstance(item, dict)
        ):
            log.warn(
                "asset",
                f"{candidate.tag}: no asset named {GENERIC_APK_NAME}; "
                "the README /releases/latest/download link would 404",
            )
        return candidate, asset
    log.fail("asset", "no eligible release carries a non-empty .apk asset")
    return None, None


# ---------------------------------------------------------------------------
# URL validation (no full download)
# ---------------------------------------------------------------------------


@dataclass
class UrlReport:
    ok: bool = False
    final_url: str = ""
    final_status: int = 0
    content_length: Optional[int] = None
    total_from_range: Optional[int] = None
    accept_ranges: str = ""
    host: str = ""


def _log_chain(result: FetchResult, log: Log, scope: str = "url") -> None:
    if len(result.chain) <= 1:
        log.info(scope, f"no redirects; final {redact_url(result.final_url)}")
        return
    log.info(scope, f"redirect chain ({len(result.chain)} hop(s)):")
    for index, (status, hop_url) in enumerate(result.chain[:-1], start=1):
        log.info(scope, f"  hop {index}: HTTP {status} {redact_url(hop_url)}")
    log.info(
        scope,
        f"  final : HTTP {result.status} {redact_url(result.final_url)}",
    )


def _looks_like_html(body: bytes) -> bool:
    prefix = body[:512].lstrip().lower()
    return prefix.startswith(b"<!doctype html") or prefix.startswith(b"<html")


def validate_url(
    url: str, expected_size: Optional[int], log: Log, timeout: float
) -> UrlReport:
    failures_before = log.failed
    report = UrlReport(final_url=url)
    log.info("url", f"HEAD {redact_url(url)}")
    try:
        head = fetch_follow(url, method="HEAD", timeout=timeout, log=log, scope="url")
    except FetchError as exc:
        log.fail("url", f"HEAD failed: {exc}")
        return report

    _log_chain(head, log)
    report.final_url = head.final_url
    report.final_status = head.status
    report.host = url_host(head.final_url)

    head_content_type = (head.headers.get("content-type") or "").lower()
    if head.status != 200:
        if head.status == 404:
            log.fail(
                "url",
                "HTTP 404 - the asset does not exist (deleted release asset?)",
            )
        else:
            log.fail("url", f"HTTP {head.status} (expected 200)")
    if head.status == 200 and "html" in head_content_type:
        log.fail("url", f"final response is HTML ({head_content_type}), not an APK")

    length = parse_content_length(head.headers)
    report.content_length = length
    if length is None:
        log.warn("url", "Content-Length header missing (chunked response?)")
    elif length == 0:
        log.fail("url", "Content-Length is 0 - empty asset")
    else:
        log.ok("url", f"Content-Length {length:,} bytes ({human_bytes(length)})")
        if expected_size is not None and length != expected_size:
            log.warn(
                "url",
                f"Content-Length {length:,} != API asset size {expected_size:,} "
                f"(delta {length - expected_size:+,})",
            )

    accept_ranges = head.headers.get("accept-ranges") or ""
    report.accept_ranges = accept_ranges
    if accept_ranges.lower() == "bytes":
        log.ok("url", "Accept-Ranges: bytes")
    else:
        log.warn("url", f"Accept-Ranges not advertised ({accept_ranges or 'absent'})")

    if head.status != 200 or "html" in head_content_type:
        log.info("url", "Range probe skipped: no usable APK response to probe")
        report.ok = False
        return report

    # Range probe: proves bytes are really served, without downloading the APK.
    log.info("url", "GET with Range: bytes=0-0")
    range_body = b""
    try:
        probe = fetch_follow(
            url,
            headers={"Range": "bytes=0-0"},
            timeout=timeout,
            read_body=4,
            log=log,
            scope="url",
        )
        range_body = probe.body
    except FetchError as exc:
        log.fail("url", f"Range probe failed: {exc}")
        return report

    if probe.status == 206:
        content_range = probe.headers.get("content-range") or ""
        total: Optional[int] = None
        match = re.match(r"\s*bytes\s+\d+-\d+/(\d+|\*)", content_range)
        if match and match.group(1) != "*":
            total = int(match.group(1))
            report.total_from_range = total
        if content_range:
            log.ok("url", f"HTTP 206, Content-Range: {content_range}")
        else:
            log.warn("url", "HTTP 206 but Content-Range header missing")
        if total is not None:
            if total == 0:
                log.fail("url", "Content-Range reports total size 0")
            elif expected_size is not None and total != expected_size:
                log.warn(
                    "url",
                    f"Content-Range total {total:,} != API asset size {expected_size:,}",
                )
    elif probe.status == 200:
        log.warn(
            "url",
            "HTTP 200 for a ranged request - server ignored Range; "
            "random access not proven (still a full-body 200)",
        )
    elif probe.status == 416:
        log.fail("url", "HTTP 416 Range Not Satisfiable - asset is empty or unreadable")
    elif probe.status == 404:
        log.fail("url", "HTTP 404 on Range probe - asset vanished during verification")
    else:
        log.fail("url", f"HTTP {probe.status} on Range probe (expected 206)")

    if _looks_like_html(range_body):
        log.fail("url", "Range body looks like HTML, not an APK")
    elif len(range_body) >= 4:
        if range_body.startswith(b"PK\x03\x04"):
            log.ok("url", "body starts with ZIP magic 'PK\\x03\\x04' (APK container)")
        else:
            log.warn(
                "url",
                f"body does not start with ZIP magic; first bytes {range_body[:4].hex()}"
                " (not necessarily an APK)",
            )
    elif range_body:
        # bytes=0-0 only returns one byte; ask for four to test the ZIP header.
        try:
            magic = fetch_follow(
                url,
                headers={"Range": "bytes=0-3"},
                timeout=timeout,
                read_body=4,
                log=log,
                scope="url",
            )
        except FetchError as exc:
            log.warn("url", f"ZIP magic probe failed: {exc}")
        else:
            if magic.body.startswith(b"PK\x03\x04"):
                log.ok("url", "body starts with ZIP magic 'PK\\x03\\x04' (APK container)")
            elif _looks_like_html(magic.body):
                log.fail("url", "body is an HTML page, not an APK")
            else:
                log.warn(
                    "url",
                    f"body does not start with ZIP magic; first bytes {magic.body[:4].hex()}"
                    " (not necessarily an APK)",
                )
    else:
        log.warn("url", "Range probe returned no bytes to inspect")

    report.ok = log.failed == failures_before
    return report


# ---------------------------------------------------------------------------
# Orion mirror check (read-only)
# ---------------------------------------------------------------------------


def _extract_json_value(text: str, key: str) -> Any:
    """Locate ``"key": <value>`` and parse only that value (mirror.json is ~35 MB)."""
    needle = json.dumps(key)
    index = text.find(needle)
    if index < 0:
        raise KeyError(key)
    cursor = index + len(needle)
    while cursor < len(text) and text[cursor].isspace():
        cursor += 1
    if cursor >= len(text) or text[cursor] != ":":
        raise ValueError(f"found {needle} but no ':' after it")
    cursor += 1
    while cursor < len(text) and text[cursor].isspace():
        cursor += 1
    value, _ = json.JSONDecoder().raw_decode(text, cursor)
    return value


def _mirror_releases(value: Any) -> list[dict]:
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    if isinstance(value, dict):
        for nested_key in ("releases", "releases_list", "items", "assets"):
            nested = value.get(nested_key)
            if isinstance(nested, list):
                return [item for item in nested if isinstance(item, dict)]
        return [value]
    return []


def _pick_mirror_release(entries: list[dict]) -> tuple[Optional[dict], Optional[Version]]:
    best: Optional[dict] = None
    best_version: Optional[Version] = None
    for entry in entries:
        version = parse_version(str(entry.get("tag_name") or ""))
        if version is None:
            continue
        if best_version is None or version > best_version:
            best, best_version = entry, version
    if best is None and entries:
        return entries[0], None
    return best, best_version


def _mirror_download_url(entry: dict) -> str:
    direct = entry.get("downloadUrl") or entry.get("download_url")
    if isinstance(direct, str) and direct and direct != "#":
        return direct
    assets = entry.get("assets")
    if isinstance(assets, list):
        for asset in assets:
            if not isinstance(asset, dict):
                continue
            name = str(asset.get("name") or asset.get("fileName") or "")
            if name.lower().endswith(".apk"):
                candidate = asset.get("browser_download_url") or asset.get("url")
                if isinstance(candidate, str) and candidate.startswith("http"):
                    return candidate
        for asset in assets:
            if isinstance(asset, dict):
                candidate = asset.get("browser_download_url")
                if isinstance(candidate, str) and candidate.startswith("http"):
                    return candidate
    return ""


def _mirror_recorded_size(entry: dict) -> Optional[int]:
    direct = entry.get("size") or entry.get("fileSize")
    if isinstance(direct, int) and not isinstance(direct, bool):
        return direct
    assets = entry.get("assets")
    if isinstance(assets, list):
        for asset in assets:
            if isinstance(asset, dict):
                size = asset.get("size")
                if isinstance(size, int) and not isinstance(size, bool):
                    return size
    return None


def _urls_match(left: str, right: str) -> bool:
    """Same host+path means the same asset; signed query strings are ignored."""

    def normalize(value: str) -> tuple[str, str]:
        parts = urllib.parse.urlsplit(value or "")
        return (parts.netloc.lower(), parts.path.rstrip("/"))

    if not left or not right:
        return False
    return normalize(left) == normalize(right)


def check_mirror(
    log: Log,
    selected: Optional[Candidate],
    verified_url: str,
    verified_size: Optional[int],
    timeout: float,
) -> str:
    """Return a short mirror status for the summary block."""
    log.info("mirror", f"GET {MIRROR_URL} (cache-busted)")
    try:
        result = fetch_follow(
            cache_busted(MIRROR_URL),
            timeout=max(timeout, 60.0),
            read_body=-1,
            log=log,
            scope="mirror",
        )
    except FetchError as exc:
        log.warn("mirror", f"could not fetch mirror JSON: {exc}")
        return "UNREACHABLE"

    if result.status != 200:
        log.warn("mirror", f"mirror JSON HTTP {result.status}; cannot verify mirror")
        return "UNREACHABLE"

    try:
        text = result.body.decode("utf-8")
        raw_value = _extract_json_value(text, MIRROR_KEY)
    except (UnicodeDecodeError, ValueError, KeyError, json.JSONDecodeError) as exc:
        log.warn("mirror", f"key {MIRROR_KEY} missing/unparsable in mirror JSON ({exc})")
        return "KEY_MISSING"
    finally:
        # Release the 35 MB payload before any further network work.
        result.body = b""
        text = ""

    entries = _mirror_releases(raw_value)
    if not entries:
        log.warn("mirror", f"key {MIRROR_KEY} has no release entries")
        return "KEY_MISSING"

    entry, mirror_version = _pick_mirror_release(entries)
    assert entry is not None
    mirror_tag = str(entry.get("tag_name") or entry.get("version") or "(unknown)")
    mirror_url = _mirror_download_url(entry)
    recorded_size = _mirror_recorded_size(entry)

    log.info("mirror", f"records {len(entries)} release(s); newest {mirror_tag}")
    if mirror_url:
        log.info("mirror", f"downloadUrl {redact_url(mirror_url)}")
    else:
        log.warn("mirror", f"no download URL recorded for {mirror_tag}")
        return "NO_URL"

    if verified_url:
        if _urls_match(mirror_url, verified_url):
            log.ok("mirror", "downloadUrl is exactly the verified release asset URL")
        else:
            log.warn(
                "mirror",
                "downloadUrl differs from the verified release asset URL "
                f"({redact_url(verified_url)})",
            )

    if verified_url:
        recommendation = (
            "fall back to the verified latest live release "
            f"{selected.tag if selected else ''}: {redact_url(verified_url)}"
        )
    else:
        recommendation = (
            "publish a live release first; no verified live release exists "
            "to fall back to"
        )

    status = ""
    try:
        mirror_head = fetch_follow(
            mirror_url, method="HEAD", timeout=timeout, log=log, scope="mirror"
        )
    except FetchError as exc:
        log.warn("mirror", f"HEAD {redact_url(mirror_url)} failed: {exc}")
        return "UNREACHABLE"

    _log_chain(mirror_head, log, scope="mirror")
    if mirror_head.status == 200:
        length = parse_content_length(mirror_head.headers)
        log.ok("mirror", f"mirror URL is live (HTTP 200, {human_bytes(length)})")
        if recorded_size is not None and length is not None and length != recorded_size:
            log.warn(
                "mirror",
                f"mirror recorded {recorded_size:,} bytes but URL serves {length:,}",
            )
        if (
            verified_size is not None
            and mirror_version is not None
            and selected is not None
            and mirror_version == selected.version
            and recorded_size is not None
            and recorded_size != verified_size
        ):
            log.warn(
                "mirror",
                f"mirror recorded {recorded_size:,} bytes but the release asset is "
                f"{verified_size:,} bytes",
            )
        if mirror_version is not None and selected is not None:
            if mirror_version == selected.version:
                log.ok("mirror", f"mirror matches latest verified release {selected.tag}")
                status = f"OK ({mirror_tag})"
            elif mirror_version < selected.version:
                log.warn(
                    "mirror",
                    f"mirror is behind ({mirror_tag} < {selected.tag}); "
                    "Orion would offer an older build (downgrade risk for existing users)",
                )
                status = f"BEHIND ({mirror_tag})"
            else:
                log.warn(
                    "mirror",
                    f"mirror references {mirror_tag}, newer than verified {selected.tag}",
                )
                status = f"AHEAD ({mirror_tag})"
        else:
            status = f"OK ({mirror_tag})"
        return status

    if mirror_head.status == 404:
        log.fail(
            "mirror",
            f"STALE_MIRROR - {MIRROR_KEY} points at {mirror_tag}, "
            "whose asset URL returns HTTP 404 (deleted release asset)",
        )
        log.info("mirror", f"recommendation: {recommendation}")
        log.info("mirror", "the mirror is external and read-only here; do not edit it from this repo")
        return f"STALE_MIRROR ({mirror_tag} -> HTTP 404)"

    log.fail(
        "mirror",
        f"STALE_MIRROR? mirror URL for {mirror_tag} returns HTTP {mirror_head.status}",
    )
    log.info("mirror", f"recommendation: {recommendation}")
    return f"STALE_MIRROR ({mirror_tag} -> HTTP {mirror_head.status})"


# ---------------------------------------------------------------------------
# Optional download + APK inspection
# ---------------------------------------------------------------------------


def sdk_dir_from_local_properties() -> Optional[str]:
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    props = os.path.join(repo_root, "local.properties")
    try:
        with open(props, "r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if line.startswith("sdk.dir="):
                    return line.split("=", 1)[1].strip().replace("\\", "/")
    except OSError:
        return None
    return None


def _version_key(name: str) -> tuple:
    parts = re.split(r"[.\-]", name)
    return tuple(int(part) if part.isdigit() else -1 for part in parts)


def find_android_tool(base_name: str) -> Optional[str]:
    found = shutil.which(base_name)
    if found:
        return found
    suffixes = [".exe", ".bat", ""] if os.name == "nt" else [""]
    roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        sdk_dir_from_local_properties(),
    ]
    for root in roots:
        if not root:
            continue
        build_tools = os.path.join(root, "build-tools")
        if not os.path.isdir(build_tools):
            continue
        for version_dir in sorted(os.listdir(build_tools), key=_version_key, reverse=True):
            for suffix in suffixes:
                candidate = os.path.join(build_tools, version_dir, base_name + suffix)
                if os.path.isfile(candidate):
                    return candidate
    return None


def _run_tool(cmd: list[str], timeout: float = 180.0) -> tuple[int, str]:
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        return 127, str(exc)
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


@dataclass
class ApkReport:
    package: str = ""
    version_code: Optional[int] = None
    version_name: str = ""
    cert_sha256: str = ""
    aapt_checked: bool = False
    signer_checked: bool = False


def inspect_apk(
    path: str,
    expected_tag: str,
    log: Log,
    expected_version_code: Optional[int] = None,
    expected_cert: Optional[str] = None,
) -> ApkReport:
    report = ApkReport()
    aapt = find_android_tool("aapt") or find_android_tool("aapt2")
    if aapt:
        code, output = _run_tool([aapt, "dump", "badging", path])
        if code != 0:
            log.warn("apk", f"aapt exited {code}: {first_line(output)}")
        else:
            name = re.search(r"package: name='([^']*)'", output)
            code_match = re.search(r"versionCode='([^']*)'", output)
            version_name = re.search(r"versionName='([^']*)'", output)
            if name:
                report.package = name.group(1)
                if code_match and code_match.group(1).isdigit():
                    report.version_code = int(code_match.group(1))
                report.version_name = version_name.group(1) if version_name else ""
                report.aapt_checked = True
                log.ok(
                    "apk",
                    f"package={report.package} "
                    f"versionCode={report.version_code if report.version_code is not None else '?'} "
                    f"versionName={report.version_name or '?'}",
                )
                if report.package != EXPECTED_PACKAGE:
                    log.fail(
                        "apk",
                        f"package {report.package!r} != expected {EXPECTED_PACKAGE!r} "
                        "(wrong APK attached to the release?)",
                    )
                expected_name = expected_tag.lstrip("vV")
                if report.version_name and report.version_name != expected_name:
                    log.warn(
                        "apk",
                        f"versionName {report.version_name!r} does not match tag "
                        f"{expected_name!r} (wrong APK attached?)",
                    )
                if expected_version_code is not None:
                    if report.version_code == expected_version_code:
                        log.ok("apk", f"versionCode matches expected {expected_version_code}")
                    else:
                        log.fail(
                            "apk",
                            f"versionCode {report.version_code} != expected {expected_version_code}",
                        )
            else:
                log.warn("apk", "aapt output did not contain a package line")
    else:
        log.warn("apk", "aapt not found (PATH/ANDROID_SDK_ROOT/build-tools); skipping package metadata")

    apksigner = find_android_tool("apksigner")
    if apksigner:
        code, output = _run_tool([apksigner, "verify", "--print-certs", path])
        if code != 0:
            lowered = output.lower()
            environment_problem = (
                "java_home" in lowered
                or "no java runtime" in lowered
                or "could not find java" in lowered
            )
            if environment_problem:
                log.warn(
                    "apk",
                    "apksigner could not start (JDK/JAVA_HOME problem): "
                    f"{first_line(output)}; signer was NOT verified",
                )
            else:
                log.fail("apk", f"apksigner verify failed (exit {code}): {first_line(output)}")
            return report
        digests = {
            digest.lower()
            for digest in re.findall(
                r"certificate SHA-256 digest:\s*([0-9A-Fa-f]{64})", output
            )
        }
        if digests:
            report.signer_checked = True
            report.cert_sha256 = sorted(digests)[0]
            log.ok("apk", f"signer certificate SHA-256 {report.cert_sha256}")
            if expected_cert:
                if expected_cert.strip().lower() in digests:
                    log.ok("apk", "signer matches the expected certificate")
                else:
                    log.fail(
                        "apk",
                        f"signer {report.cert_sha256} != expected {expected_cert.strip().lower()} "
                        "(signing identity changed)",
                    )
        else:
            log.fail("apk", "apksigner reported no certificate SHA-256 digest (unsigned APK?)")
    else:
        log.warn("apk", "apksigner not found (PATH/ANDROID_SDK_ROOT/build-tools); skipping signer")
    return report


@dataclass
class DownloadReport:
    path: str = ""
    bytes_written: int = 0
    sha256: str = ""
    verified: bool = False


def _unlink_quietly(path: str) -> None:
    try:
        os.unlink(path)
    except OSError:
        pass


def download_asset(
    asset: dict,
    directory: str,
    log: Log,
    timeout: float,
    expect_sha256: Optional[str] = None,
    retries: int = 2,
) -> Optional[DownloadReport]:
    """Download to ``<name>.part`` and keep it only if size and SHA-256 agree."""
    name = os.path.basename(str(asset.get("name") or "asset.apk")) or "asset.apk"
    url = str(asset.get("browser_download_url") or "")
    if not url:
        log.fail("download", "asset has no browser_download_url")
        return None
    try:
        os.makedirs(directory, exist_ok=True)
        destination = os.path.join(os.path.abspath(directory), name)
    except OSError as exc:
        log.fail("download", f"cannot create {directory}: {exc}")
        return None

    part = destination + ".part"
    _unlink_quietly(part)  # never append to bytes from an unknown earlier run
    log.info("download", f"GET {redact_url(url)} -> {destination}")

    hasher = hashlib.sha256()
    written = 0
    completed = False
    for attempt in range(1, retries + 2):
        hasher = hashlib.sha256()
        written = 0
        try:
            request = urllib.request.Request(url, method="GET")
            request.add_header("User-Agent", USER_AGENT)
            with urllib.request.urlopen(request, timeout=timeout) as response, open(
                part, "wb"
            ) as handle:
                while True:
                    chunk = response.read(1 << 20)
                    if not chunk:
                        break
                    handle.write(chunk)
                    hasher.update(chunk)
                    written += len(chunk)
            completed = True
            break
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            if attempt <= retries:
                delay = _backoff_seconds(attempt, 1.0)
                log.warn(
                    "download",
                    f"attempt {attempt} failed after {written:,} bytes ({exc}); "
                    f"retry in {delay:.1f}s",
                )
                time.sleep(delay)
                continue
            log.fail("download", f"download failed after {written:,} bytes: {exc}")
            _unlink_quietly(part)
            return DownloadReport(bytes_written=written, sha256=hasher.hexdigest())

    if not completed:
        _unlink_quietly(part)
        return DownloadReport(bytes_written=written, sha256=hasher.hexdigest())

    digest = hasher.hexdigest()
    valid = True

    expected_size = asset.get("size")
    if (
        isinstance(expected_size, int)
        and not isinstance(expected_size, bool)
        and expected_size > 0
    ):
        if written != expected_size:
            log.fail(
                "download", f"wrote {written:,} bytes but GitHub reports {expected_size:,}"
            )
            valid = False
        else:
            log.ok(
                "download",
                f"{written:,} bytes ({human_bytes(written)}), matches GitHub asset size",
            )

    expected_digests: list[tuple[str, str]] = []
    api_digest = str(asset.get("digest") or "").strip().lower()
    if api_digest.startswith("sha256:"):
        expected_digests.append((api_digest.split(":", 1)[1].strip(), "GitHub API digest"))
    if expect_sha256:
        expected_digests.append((expect_sha256.strip().lower(), "--expect-sha256"))
    if not expected_digests:
        log.warn(
            "download",
            "no pinned SHA-256 (API digest absent, no --expect-sha256); "
            "only the measured hash can be recorded",
        )
    for expected, source in expected_digests:
        if not SHA256_RE.match(expected):
            log.fail(
                "download",
                f"{source} {expected!r} is not a 64-character SHA-256 hex digest",
            )
            valid = False
        elif expected != digest:
            log.fail(
                "download",
                f"SHA-256 mismatch vs {source}: expected {expected}, measured {digest}",
            )
            valid = False
        else:
            log.ok("download", f"SHA-256 matches {source}")
    log.info("download", f"SHA-256 {digest}")

    if not valid:
        log.warn("download", "discarded the mismatched .part file; no APK kept")
        _unlink_quietly(part)
        return DownloadReport(bytes_written=written, sha256=digest)

    try:
        os.replace(part, destination)
    except OSError as exc:
        log.fail("download", f"cannot move .part into place: {exc}")
        _unlink_quietly(part)
        return DownloadReport(bytes_written=written, sha256=digest)
    log.ok("download", f"verified APK saved to {destination}")
    return DownloadReport(destination, written, digest, verified=True)


# ---------------------------------------------------------------------------
# Summary + CLI
# ---------------------------------------------------------------------------


def build_summary(
    repo: str,
    channel: str,
    effective_channel: str,
    selected: Optional[Candidate],
    asset: Optional[dict],
    report: Optional[UrlReport],
    mirror_status: str,
    download: Optional[DownloadReport],
    apk: Optional[ApkReport],
    log: Log,
    elapsed: float,
) -> dict[str, Any]:
    size: Optional[int] = None
    if asset is not None and isinstance(asset.get("size"), int):
        size = asset.get("size")
    if size is None and report is not None:
        size = report.content_length
    return {
        "schema": 1,
        "repo": repo,
        "channel": channel,
        "effective_channel": effective_channel,
        "selected_tag": selected.tag if selected else None,
        "asset_name": str(asset.get("name")) if asset else None,
        "asset_size": size,
        "asset_url": (
            redact_url(str(asset.get("browser_download_url") or "")) if asset else None
        ),
        "final_url_host": report.host if report else None,
        "url_ok": bool(report.ok) if report else False,
        "mirror_status": mirror_status,
        "download": (
            {
                "path": download.path or None,
                "bytes": download.bytes_written,
                "sha256": download.sha256,
                "verified": download.verified,
            }
            if download
            else None
        ),
        "apk": (
            {
                "package": apk.package or None,
                "version_code": apk.version_code,
                "version_name": apk.version_name or None,
                "cert_sha256": apk.cert_sha256 or None,
                "aapt_checked": apk.aapt_checked,
                "signer_checked": apk.signer_checked,
            }
            if apk
            else None
        ),
        "checks": {
            "passed": log.passed,
            "warned": log.warned,
            "failed": log.failed,
        },
        "result": "FAILED" if log.failed else "OK",
        "elapsed_seconds": round(elapsed, 1),
    }


def print_summary(summary: dict[str, Any], stream) -> None:
    channel = summary["channel"]
    if summary["effective_channel"] != channel:
        channel = f"{channel} -> {summary['effective_channel']}"
    download = summary.get("download") or {}
    apk = summary.get("apk") or {}
    checks = summary["checks"]
    lines = [
        ("repo", summary["repo"]),
        ("channel", channel),
        ("selected tag", summary["selected_tag"] or "(none)"),
        ("asset name", summary["asset_name"] or "(none)"),
        ("asset size", human_bytes(summary["asset_size"])),
        ("final URL host", summary["final_url_host"] or "(none)"),
        ("mirror status", summary["mirror_status"]),
    ]
    if download:
        lines.append(("downloaded", download.get("path") or "(discarded)"))
        lines.append(("sha256", download.get("sha256") or "(none)"))
    if apk:
        lines.append(("apk package", apk.get("package") or "(not inspected)"))
        lines.append(
            (
                "apk version",
                f"{apk.get('version_name') or '?'} "
                f"({apk.get('version_code') if apk.get('version_code') is not None else '?'})",
            )
        )
        if apk.get("cert_sha256"):
            lines.append(("apk signer", apk["cert_sha256"]))
    lines.append(
        (
            "checks",
            f"{checks['passed']} passed, {checks['warned']} warning(s), "
            f"{checks['failed']} failure(s)",
        )
    )
    lines.append(("elapsed", f"{summary['elapsed_seconds']:.1f}s"))
    lines.append(("result", summary["result"]))
    print(file=stream)
    print("Summary", file=stream)
    for label, value in lines:
        print(f"  {label:<15} : {value}", file=stream)


def emit_summary(summary: dict[str, Any], as_json: bool, stream) -> None:
    if as_json:
        json.dump(summary, sys.stdout, indent=2)
        sys.stdout.write("\n")
        sys.stdout.flush()
    else:
        print_summary(summary, stream)


def derive_version_code_parts(shipped: int) -> tuple[Optional[int], Optional[str]]:
    """Invert the ABI offset: base codes always end in 0, offsets are 1..4."""
    remainder = shipped % 10
    abi = {code: name for name, code in release_guard.ABI_CODES.items()}.get(remainder)
    if abi is None:
        return None, None
    return shipped - remainder, abi


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="verify_release.py",
        description=(
            "Read-only verification of Sleeve's GitHub release assets and the "
            "Orion Store mirror entry. Never publishes, uploads, or deletes."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "examples:\n"
            "  python scripts/verify_release.py --channel beta --limit 30\n"
            "  python scripts/verify_release.py --channel stable --abi arm64-v8a\n"
            "  python scripts/verify_release.py --channel auto --download ./apk-out\n"
        ),
    )
    parser.add_argument("--repo", default=DEFAULT_REPO, help=f"owner/name (default {DEFAULT_REPO})")
    parser.add_argument(
        "--channel",
        choices=("auto", "stable", "beta"),
        default="auto",
        help="auto: newest stable if any, else newest beta; beta: newest incl. prereleases",
    )
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT, help="releases to fetch (default 30, max 100)")
    parser.add_argument("--abi", default=None, help="prefer an asset whose name contains this ABI (e.g. arm64-v8a)")
    parser.add_argument(
        "--validate-url",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="HEAD + Range probe of the asset URL (default: on)",
    )
    parser.add_argument(
        "--verify-mirror",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="check the Orion mirror entry (default: on, read-only)",
    )
    parser.add_argument("--download", metavar="DIR", default=None, help="opt-in: download the APK into DIR")
    parser.add_argument("--timeout", type=float, default=30.0, help="per-request timeout in seconds (default 30)")
    parser.add_argument(
        "--retries",
        type=int,
        default=2,
        help="retries for transient network/HTTP failures (default 2)",
    )
    parser.add_argument(
        "--expect-sha256",
        metavar="HEX",
        default=None,
        help="with --download: fail unless the APK's SHA-256 matches",
    )
    parser.add_argument(
        "--expect-version-code",
        type=int,
        default=None,
        help="with --download: fail unless the APK carries this versionCode "
        "(default: the value recorded for the selected tag)",
    )
    parser.add_argument(
        "--expect-cert",
        metavar="HEX",
        default=None,
        help="with --download: fail unless the signer certificate SHA-256 matches "
        "(default: the newest certificate in the release record)",
    )
    parser.add_argument(
        "--expect-tag",
        metavar="TAG",
        default=None,
        help="fail unless the selected release is exactly this tag (CI uses this)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="write a machine-readable summary to stdout and the human log to stderr",
    )
    parser.add_argument(
        "--record",
        nargs="?",
        const=DEFAULT_RECORD,
        default=None,
        metavar="PATH",
        help=f"opt-in: record measured release facts in PATH (default {DEFAULT_RECORD})",
    )
    return parser


def record_release(
    path: str,
    repo: str,
    selected: Candidate,
    asset: dict,
    apk: ApkReport,
    sha256: str,
    size: int,
    log: Log,
) -> None:
    """Persist measured facts, refusing to rewrite a published artifact's identity."""
    if os.path.isfile(path):
        try:
            record = release_guard.load_record(path)
        except release_guard.GuardError as exc:
            log.fail("record", str(exc))
            return
    else:
        record = release_guard.empty_record(repo)

    existing = next(
        (
            entry
            for entry in record.get("releases", [])
            if isinstance(entry, dict) and entry.get("tag") == selected.tag
        ),
        None,
    )
    pinned = str((existing or {}).get("sha256") or "").strip().lower()
    if pinned and pinned != sha256.strip().lower():
        log.fail(
            "record",
            f"{selected.tag} is recorded with SHA-256 {pinned}, measured {sha256}; "
            "refusing to overwrite a published artifact's identity",
        )
        return

    base_code, abi = derive_version_code_parts(apk.version_code or 0)
    entry = {
        "tag": selected.tag,
        "version_name": apk.version_name or selected.tag.lstrip("vV"),
        "base_version_code": base_code,
        "shipped_version_code": apk.version_code,
        "asset_name": str(asset.get("name") or ""),
        "asset_size": size,
        "sha256": sha256,
        "cert_sha256": apk.cert_sha256 or None,
        "asset_url": str(asset.get("browser_download_url") or ""),
        "published_at": selected.raw.get("published_at"),
        "published": True,
        "distributed": True,
        "verified_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    release_guard.upsert_record(record, entry)
    try:
        release_guard.save_record(path, record)
    except OSError as exc:
        log.fail("record", f"cannot write {path}: {exc}")
        return
    log.ok("record", f"recorded {selected.tag} in {path} (abi {abi or 'unknown'})")


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    started = time.monotonic()
    human = sys.stderr if args.json else sys.stdout
    log = Log(stream=human)

    print(
        f"Sleeve release verifier (read-only)  repo={args.repo}  "
        f"channel={args.channel}  limit={args.limit}",
        file=human,
    )
    print(file=human)

    limit = args.limit
    if limit < 1 or limit > 100:
        limit = max(1, min(100, limit))
        log.info("limit", f"--limit clamped to {limit} (GitHub API allows 1..100)")

    record_path = args.record or DEFAULT_RECORD
    record: Optional[dict] = None
    if os.path.isfile(record_path):
        try:
            record = release_guard.load_record(record_path)
        except release_guard.GuardError as exc:
            log.warn("record", f"ignoring unreadable record: {exc}")
    else:
        log.info("record", f"no release record at {record_path}")

    expected_cert = (args.expect_cert or "").strip().lower() or None
    if expected_cert is None and record is not None:
        expected_cert = release_guard.newest_certificate(record)
        if expected_cert:
            log.info("record", f"expected signer from {record_path}: {expected_cert}")

    releases = fetch_releases(args.repo, limit, log, args.timeout)
    if releases is None:
        summary = build_summary(
            args.repo,
            args.channel,
            args.channel,
            None,
            None,
            None,
            "SKIPPED",
            None,
            None,
            log,
            time.monotonic() - started,
        )
        emit_summary(summary, args.json, human)
        return 1

    eligible, _parsed, effective_channel = select_release(releases, args.channel, log)

    selected: Optional[Candidate] = None
    asset: Optional[dict] = None
    report: Optional[UrlReport] = None
    if eligible:
        selected, asset = choose_candidate(eligible, args.abi, log)
    if selected is not None and asset is not None:
        log.ok(
            "select",
            f"{selected.tag} / {asset.get('name')} "
            f"({human_bytes(asset.get('size') if isinstance(asset.get('size'), int) else None)})",
        )
        log.info("select", f"release published {selected.raw.get('published_at') or 'unknown'}")
        if args.validate_url:
            report = validate_url(
                str(asset.get("browser_download_url") or ""),
                asset.get("size") if isinstance(asset.get("size"), int) else None,
                log,
                args.timeout,
            )
        else:
            log.info("url", "skipped (--no-validate-url)")
    else:
        log.info("select", "no release selected; skipping asset URL validation")

    if args.expect_tag and selected is not None and selected.tag != args.expect_tag:
        log.fail(
            "select",
            f"selected {selected.tag} but --expect-tag is {args.expect_tag} "
            "(release list stale or the wrong channel?)",
        )

    mirror_status = "SKIPPED"
    if args.verify_mirror:
        mirror_status = check_mirror(
            log,
            selected,
            str(asset.get("browser_download_url") or "") if asset else "",
            asset.get("size") if asset and isinstance(asset.get("size"), int) else None,
            args.timeout,
        )
    else:
        log.info("mirror", "skipped (--no-verify-mirror)")

    expected_code = args.expect_version_code
    if expected_code is None and record is not None and selected is not None:
        for entry in record.get("releases", []):
            if isinstance(entry, dict) and entry.get("tag") == selected.tag:
                recorded_code = entry.get("shipped_version_code")
                if isinstance(recorded_code, int) and not isinstance(recorded_code, bool):
                    expected_code = recorded_code
                    log.info(
                        "record",
                        f"{selected.tag} is recorded with versionCode {recorded_code}",
                    )
                break

    download_report: Optional[DownloadReport] = None
    apk_report: Optional[ApkReport] = None
    if args.download:
        if selected is not None and asset is not None:
            download_report = download_asset(
                asset,
                args.download,
                log,
                args.timeout,
                expect_sha256=args.expect_sha256,
                retries=args.retries,
            )
            if download_report is not None and download_report.verified:
                apk_report = inspect_apk(
                    download_report.path,
                    selected.tag,
                    log,
                    expected_version_code=expected_code,
                    expected_cert=expected_cert,
                )
        else:
            log.warn("download", "nothing to download (no verified release selected)")
    elif args.expect_sha256 or args.expect_version_code or args.expect_cert:
        log.warn(
            "apk",
            "--expect-sha256/--expect-version-code/--expect-cert are only checked "
            "with --download",
        )

    if args.record:
        if (
            selected is not None
            and asset is not None
            and download_report is not None
            and download_report.verified
            and apk_report is not None
        ):
            if log.failed:
                log.warn("record", "not updating the record while a check is failing")
            else:
                record_release(
                    args.record,
                    args.repo,
                    selected,
                    asset,
                    apk_report,
                    download_report.sha256,
                    download_report.bytes_written,
                    log,
                )
        else:
            log.warn(
                "record", "--record needs a verified --download to record measured facts"
            )

    summary = build_summary(
        args.repo,
        args.channel,
        effective_channel,
        selected,
        asset,
        report,
        mirror_status,
        download_report,
        apk_report,
        log,
        time.monotonic() - started,
    )
    emit_summary(summary, args.json, human)
    return 1 if log.failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        sys.exit(130)
