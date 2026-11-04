#!/usr/bin/env python3
"""Preflight guard for a Sleeve release tag.

Runs before CI builds anything and proves four things that the old pipeline
only assumed:

1. the tag matches ``currentVersion.name`` in ``Version.kt``;
2. the Gradle ``versionCode`` literal equals ``currentVersion.code``;
3. the new shipped version code is strictly greater than every code already
   distributed (recorded in ``release-record.json``);
4. every live GitHub release is present in the record, so the record cannot
   silently fall behind.

It never publishes, uploads, tags, deletes or edits anything. ``--print-cert``
and ``--print-shipped-code`` let the release job reuse the same arithmetic
instead of duplicating it in YAML.

Fail-safe by default: a missing or stale record, an unreachable live release
list, or any disagreement exits non-zero. ``--allow-offline`` downgrades only
the live check to a warning for local use.

Usage::

    python scripts/release_guard.py --tag v2.0.0-beta.11
    python scripts/release_guard.py --tag v2.0.0-beta.11 --allow-offline
    python scripts/release_guard.py --print-shipped-code --no-split-apks

Exit codes: ``0`` pass, ``1`` check failed, ``2`` usage/config error.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Optional

DEFAULT_REPO = "Burry071/sleeve"
DEFAULT_VERSION_FILE = "buildSrc/src/main/kotlin/Version.kt"
DEFAULT_GRADLE_FILE = "app/build.gradle.kts"
DEFAULT_RECORD = "release-record.json"
DEFAULT_ABI_FILTERS = "arm64-v8a;x86_64"

ABI_CODES = {"armeabi-v7a": 1, "arm64-v8a": 2, "x86": 3, "x86_64": 4}
MAJOR = 100_000_000
MINOR = 1_000_000
PATCH = 10_000
BUILD = 10
VARIANT_OFFSETS = {
    "Alpha": 100,
    "Beta": 200,
    "ReleaseCandidate": 300,
    "Stable": 400,
}

CURRENT_VERSION_RE = re.compile(
    r"currentVersion\s*:\s*Version\s*=\s*Version\.(Alpha|Beta|Stable|ReleaseCandidate)\s*\(([^)]*)\)",
    re.S,
)
VERSION_ARG_RE = re.compile(r"(version[A-Za-z]+)\s*=\s*(\d+)")
GRADLE_VERSION_CODE_RE = re.compile(r"versionCode\s*=\s*([0-9_]+)")


class GuardError(Exception):
    """Configuration or input that cannot be checked safely."""


@dataclass(frozen=True)
class VersionSpec:
    variant: str
    name: str
    code: int
    build: int

    @property
    def tag(self) -> str:
        return "v" + self.name


@dataclass(frozen=True)
class Issue:
    level: str
    message: str


def _read(path: str) -> str:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError as exc:
        raise GuardError(f"cannot read {path}: {exc}") from exc


def parse_version_kt(text: str) -> VersionSpec:
    """Parse the ``currentVersion`` assignment without running Kotlin."""
    match = CURRENT_VERSION_RE.search(text)
    if not match:
        raise GuardError("currentVersion assignment not found in Version.kt")
    variant = match.group(1)
    args = {key: int(value) for key, value in VERSION_ARG_RE.findall(match.group(2))}
    missing = [key for key in ("versionMajor", "versionMinor", "versionPatch") if key not in args]
    if missing:
        raise GuardError("currentVersion is missing " + ", ".join(missing))
    major = args["versionMajor"]
    minor = args["versionMinor"]
    patch = args["versionPatch"]
    build = args.get("versionBuild", 0)
    if variant == "Stable" and build:
        raise GuardError("a Stable version must not set versionBuild")
    code = (
        major * MAJOR
        + minor * MINOR
        + patch * PATCH
        + build * BUILD
        + VARIANT_OFFSETS[variant]
    )
    if variant == "Stable":
        name = f"{major}.{minor}.{patch}"
    elif variant == "ReleaseCandidate":
        name = f"{major}.{minor}.{patch}-rc.{build}"
    else:
        name = f"{major}.{minor}.{patch}-{variant.lower()}.{build}"
    return VersionSpec(variant, name, code, build)


def parse_gradle_version_code(text: str) -> int:
    match = GRADLE_VERSION_CODE_RE.search(text)
    if not match:
        raise GuardError("versionCode assignment not found in app/build.gradle.kts")
    try:
        return int(match.group(1).replace("_", ""))
    except ValueError as exc:
        raise GuardError(
            f"versionCode in app/build.gradle.kts is not a literal number: {match.group(1)!r}"
        ) from exc


def compute_shipped_codes(
    base_code: int, abi_filters: str, split_apks: bool
) -> dict[str, int]:
    """Return the APK versionCode each configured ABI would carry."""
    abis = [part.strip() for part in abi_filters.split(";") if part.strip()]
    if not abis:
        raise GuardError("ABI filter list is empty")
    unknown = [abi for abi in abis if abi not in ABI_CODES]
    if unknown:
        raise GuardError("unknown ABI(s): " + ", ".join(unknown))
    if split_apks:
        return {abi: base_code + ABI_CODES[abi] for abi in abis}
    # A no-splits APK takes the offset of the first ABI in the list.
    first = abis[0]
    return {first: base_code + ABI_CODES[first]}


def empty_record(repo: str = DEFAULT_REPO) -> dict[str, Any]:
    return {"schema": 1, "repo": repo, "releases": []}


def load_record(path: str) -> dict[str, Any]:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except FileNotFoundError:
        raise GuardError(f"release record not found: {path}") from None
    except (OSError, json.JSONDecodeError) as exc:
        raise GuardError(f"cannot read release record {path}: {exc}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("releases"), list):
        raise GuardError(f"release record {path} must be an object with a releases list")
    return data


def save_record(path: str, record: dict[str, Any]) -> None:
    """Write the record atomically so a crash cannot leave half a JSON file."""
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(record, handle, indent=2)
        handle.write("\n")
    os.replace(temp, path)


def _record_sort_key(entry: dict[str, Any]) -> tuple[int, str]:
    base = entry.get("base_version_code")
    return (base if isinstance(base, int) else 0, str(entry.get("tag") or ""))


def upsert_record(record: dict[str, Any], entry: dict[str, Any]) -> dict[str, Any]:
    releases = record.setdefault("releases", [])
    tag = entry.get("tag")
    for index, existing in enumerate(releases):
        if isinstance(existing, dict) and existing.get("tag") == tag:
            releases[index] = {**existing, **entry}
            break
    else:
        releases.append(entry)
    releases.sort(key=_record_sort_key)
    return record


def highest_distributed_code(record: dict[str, Any]) -> tuple[int, Optional[dict[str, Any]]]:
    highest = 0
    owner: Optional[dict[str, Any]] = None
    for entry in record.get("releases", []):
        if not isinstance(entry, dict) or entry.get("distributed") is False:
            continue
        code = entry.get("shipped_version_code")
        if isinstance(code, int) and not isinstance(code, bool) and code > highest:
            highest, owner = code, entry
    return highest, owner


def newest_certificate(record: dict[str, Any]) -> Optional[str]:
    for entry in reversed(record.get("releases", [])):
        if isinstance(entry, dict):
            cert = entry.get("cert_sha256")
            if isinstance(cert, str) and len(cert) == 64:
                return cert.lower()
    return None


def _live_tags_via_gh(repo: str) -> Optional[list[str]]:
    if not shutil.which("gh"):
        return None
    try:
        proc = subprocess.run(
            ["gh", "api", f"repos/{repo}/releases?per_page=100", "-H", "Accept: application/vnd.github+json"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=90,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise GuardError(f"gh api could not run: {exc}") from exc
    if proc.returncode != 0:
        raise GuardError(f"gh api exited {proc.returncode}: {proc.stderr.strip()[:200]}")
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise GuardError(f"gh api returned invalid JSON: {exc}") from exc
    if not isinstance(data, list):
        raise GuardError("gh api returned a non-list payload")
    return [
        str(item.get("tag_name"))
        for item in data
        if isinstance(item, dict) and not item.get("draft")
    ]


def fetch_live_tags(repo: str, timeout: float = 30.0) -> list[str]:
    url = f"https://api.github.com/repos/{repo}/releases?per_page=100"
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "sleeve-release-guard/1.0",
    }
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read()
    except urllib.error.HTTPError as exc:
        if exc.code in (403, 429):
            fallback = _live_tags_via_gh(repo)
            if fallback is not None:
                return fallback
        raise GuardError(f"cannot list live releases for {repo}: HTTP {exc.code}") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise GuardError(f"cannot list live releases for {repo}: {exc}") from exc
    try:
        data = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GuardError(f"live release list for {repo} is not valid JSON: {exc}") from exc
    if not isinstance(data, list):
        raise GuardError(f"live release list for {repo} is not a JSON array")
    return [
        str(item.get("tag_name"))
        for item in data
        if isinstance(item, dict) and not item.get("draft")
    ]


def check_release(
    tag: str,
    version: VersionSpec,
    gradle_code: int,
    shipped_codes: dict[str, int],
    record: dict[str, Any],
    live_tags: Optional[list[str]],
) -> list[Issue]:
    issues: list[Issue] = []

    issues.append(Issue("ok", f"Version.kt: {version.name} (base code {version.code})"))

    if gradle_code != version.code:
        issues.append(
            Issue(
                "fail",
                f"app/build.gradle.kts versionCode {gradle_code} != Version.kt code {version.code}",
            )
        )
    else:
        issues.append(Issue("ok", f"Gradle versionCode matches ({gradle_code})"))

    normalized_tag = tag.strip()
    if normalized_tag.lstrip("vV") != version.name:
        issues.append(
            Issue(
                "fail",
                f"tag {tag!r} does not match Version.kt name {version.name!r}",
            )
        )
    else:
        issues.append(Issue("ok", f"tag {tag} matches Version.kt"))

    highest, owner = highest_distributed_code(record)
    new_lowest = min(shipped_codes.values())
    new_highest = max(shipped_codes.values())
    detail = ", ".join(f"{abi}={code}" for abi, code in shipped_codes.items())
    if new_lowest <= highest:
        owner_tag = owner.get("tag") if owner else "unknown"
        issues.append(
            Issue(
                "fail",
                f"new shipped version codes ({detail}) are not greater than the "
                f"highest distributed code {highest} ({owner_tag})",
            )
        )
    else:
        issues.append(
            Issue(
                "ok",
                f"shipped version codes ({detail}) exceed the highest distributed "
                f"code {highest}" + (f" ({owner.get('tag')})" if owner else ""),
            )
        )

    max_base = 0
    for entry in record.get("releases", []):
        if not isinstance(entry, dict):
            continue
        base = entry.get("base_version_code")
        if isinstance(base, int) and base > max_base:
            max_base = base
    if version.code <= max_base:
        issues.append(
            Issue(
                "fail",
                f"base version code {version.code} is not greater than the "
                f"highest recorded base {max_base}",
            )
        )
    else:
        issues.append(Issue("ok", f"base version code {version.code} > {max_base}"))

    record_tags = {
        str(entry.get("tag"))
        for entry in record.get("releases", [])
        if isinstance(entry, dict)
    }
    if any(
        isinstance(entry, dict) and entry.get("tag") == tag
        for entry in record.get("releases", [])
    ):
        issues.append(
            Issue("warn", f"{tag} is already present in the record (re-verification?)")
        )

    if live_tags is not None:
        published = set(live_tags)
        if tag in published:
            issues.append(
                Issue(
                    "fail",
                    f"{tag} already has a published release; version names are never reused",
                )
            )
        missing = sorted(published - record_tags)
        if missing:
            issues.append(
                Issue(
                    "fail",
                    "release record is stale; live releases missing from the record: "
                    + ", ".join(missing),
                )
            )
        else:
            issues.append(
                Issue("ok", f"record covers all {len(published)} live release(s)")
            )
        deleted = sorted(
            tag_name
            for tag_name in record_tags - published
            if any(
                isinstance(entry, dict)
                and entry.get("tag") == tag_name
                and entry.get("release_deleted") is True
                for entry in record.get("releases", [])
            )
        )
        if deleted:
            issues.append(
                Issue(
                    "info",
                    "recorded but no longer published (kept for history): "
                    + ", ".join(deleted),
                )
            )
        unrecorded_deletions = [
            tag_name
            for tag_name in sorted(record_tags - published - set(deleted))
            if tag_name not in {"None"}
            and any(
                isinstance(entry, dict)
                and entry.get("tag") == tag_name
                and entry.get("published") is True
                and entry.get("distributed") is not False
                for entry in record.get("releases", [])
            )
        ]
        if unrecorded_deletions:
            issues.append(
                Issue(
                    "warn",
                    "recorded as distributed but absent from GitHub and not marked "
                    "release_deleted: " + ", ".join(unrecorded_deletions),
                )
            )

    return issues


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="release_guard.py",
        description=(
            "Read-only preflight: tag ↔ Version.kt ↔ Gradle versionCode ↔ "
            "release-record.json monotonicity."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "examples:\n"
            "  python scripts/release_guard.py --tag v2.0.0-beta.11\n"
            "  python scripts/release_guard.py --tag v2.0.0-beta.11 --allow-offline\n"
            "  python scripts/release_guard.py --print-shipped-code --no-split-apks\n"
        ),
    )
    parser.add_argument("--tag", help="tag being released (e.g. v2.0.0-beta.11)")
    parser.add_argument("--repo", default=DEFAULT_REPO, help=f"owner/name (default {DEFAULT_REPO})")
    parser.add_argument("--version-file", default=DEFAULT_VERSION_FILE)
    parser.add_argument("--gradle-file", default=DEFAULT_GRADLE_FILE)
    parser.add_argument("--record", default=DEFAULT_RECORD)
    parser.add_argument("--abi-filters", default=DEFAULT_ABI_FILTERS)
    parser.add_argument(
        "--split-apks",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="the build splits per ABI (default: no-splits, first ABI offset applies)",
    )
    parser.add_argument(
        "--allow-offline",
        action="store_true",
        help="warn instead of failing when the live release list is unreachable",
    )
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument(
        "--print-cert",
        action="store_true",
        help="print the newest recorded signing certificate SHA-256 and exit",
    )
    parser.add_argument(
        "--print-base-code",
        action="store_true",
        help="print the Version.kt base versionCode and exit",
    )
    parser.add_argument(
        "--print-shipped-code",
        action="store_true",
        help="print the shipped version code(s) for the configured ABIs and exit",
    )
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.print_cert:
            cert = newest_certificate(load_record(args.record))
            if cert is None:
                raise GuardError(f"no signing certificate recorded in {args.record}")
            print(cert)
            return 0

        version = parse_version_kt(_read(args.version_file))
        if args.print_base_code:
            print(version.code)
            return 0

        codes = compute_shipped_codes(version.code, args.abi_filters, args.split_apks)
        if args.print_shipped_code:
            for abi, code in codes.items():
                print(f"{abi}={code}")
            return 0

        if not args.tag:
            parser.error("--tag is required unless --print-cert/--print-base-code/--print-shipped-code is used")

        gradle_code = parse_gradle_version_code(_read(args.gradle_file))
        record = load_record(args.record)

        live_tags: Optional[list[str]] = None
        try:
            live_tags = fetch_live_tags(args.repo, args.timeout)
        except GuardError as exc:
            if not args.allow_offline:
                raise
            print(f"WARN  live release check skipped: {exc}", file=sys.stderr)

        print(f"Sleeve release guard  tag={args.tag}  repo={args.repo}")
        print()
        issues = check_release(args.tag, version, gradle_code, codes, record, live_tags)
        for issue in issues:
            print(f"{issue.level.upper():4}  {issue.message}")
        failures = [issue for issue in issues if issue.level == "fail"]
        passed = [issue for issue in issues if issue.level == "ok"]
        print()
        print(
            f"result: {'FAILED' if failures else 'OK'} "
            f"({len(passed)} passed, {len(failures)} failure(s))"
        )
        return 1 if failures else 0
    except GuardError as exc:
        print(f"FAIL  {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        sys.exit(130)
