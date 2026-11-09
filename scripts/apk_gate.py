#!/usr/bin/env python3
"""Gate the packaged release APKs on identity and one shared versionCode.

One release ships several ABI artifacts. Every artifact must carry the *same*
versionCode — the ABI is not encoded in it, because switching between the
arm64, x86_64 and universal APKs must never look like a downgrade to Android's
installer — and that code must exceed every previously distributed code.

The gate reads ``aapt2 dump badging`` for each APK, so it checks the bytes
that were actually built rather than the build scripts' intent. It never
publishes, uploads, edits or deletes anything.

Usage::

    python scripts/apk_gate.py --aapt2 "$ANDROID_HOME/build-tools/35.0.0/aapt2" \\
        --expect-package com.sleeve.app --expect-version-name 2.1.0-beta.2 \\
        --expect-code 201000220 --previous-code 201000212 \\
        --require-name Sleeve-arm64-release.apk \\
        --require-name Sleeve-x86_64-release.apk \\
        --require-name Sleeve-generic-release.apk \\
        dist/*.apk

Exit codes: ``0`` pass, ``1`` failed check, ``2`` usage/tool error.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from typing import Optional

try:  # imported as scripts.apk_gate (package / unittest)
    from .release_guard import Issue
except ImportError:  # run directly: python scripts/apk_gate.py
    from release_guard import Issue

PACKAGE_LINE_RE = re.compile(r"^package: name='([^']*)'", re.M)
VERSION_CODE_RE = re.compile(r"\bversionCode='([^']*)'")
VERSION_NAME_RE = re.compile(r"\bversionName='([^']*)'")


class GateError(Exception):
    """Input or tool output that cannot be checked safely."""


@dataclass(frozen=True)
class Badge:
    path: str
    package: str
    version_code: Optional[int]
    version_name: str


def parse_badging(path: str, text: str) -> Badge:
    """Parse the ``package:`` line of ``aapt[2] dump badging`` output."""
    match = PACKAGE_LINE_RE.search(text)
    if not match:
        raise GateError(f"{path}: badging output has no package line")
    line = text[match.start() :].split("\n", 1)[0]
    code_match = VERSION_CODE_RE.search(line)
    version_code: Optional[int] = None
    if code_match and code_match.group(1).isdigit():
        version_code = int(code_match.group(1))
    name_match = VERSION_NAME_RE.search(line)
    return Badge(
        path=path,
        package=match.group(1),
        version_code=version_code,
        version_name=name_match.group(1) if name_match else "",
    )


def check_badges(
    badges: list[Badge],
    *,
    expect_package: Optional[str] = None,
    expect_version_name: Optional[str] = None,
    expect_code: Optional[int] = None,
    previous_code: Optional[int] = None,
    required_names: Optional[list[str]] = None,
) -> list[Issue]:
    """Return every identity and versionCode problem found in ``badges``."""
    issues: list[Issue] = []
    if not badges:
        return [Issue("fail", "no APKs were provided")]

    for badge in badges:
        name = os.path.basename(badge.path)
        if badge.version_code is None:
            issues.append(Issue("fail", f"{name}: versionCode is missing or not numeric"))
        if expect_package is not None:
            if badge.package == expect_package:
                issues.append(Issue("ok", f"{name}: package {badge.package}"))
            else:
                issues.append(
                    Issue(
                        "fail",
                        f"{name}: package {badge.package!r} != {expect_package!r}",
                    )
                )
        if expect_version_name is not None:
            if badge.version_name == expect_version_name:
                issues.append(Issue("ok", f"{name}: versionName {badge.version_name}"))
            else:
                issues.append(
                    Issue(
                        "fail",
                        f"{name}: versionName {badge.version_name!r} "
                        f"!= {expect_version_name!r}",
                    )
                )

    codes = {badge.version_code for badge in badges if badge.version_code is not None}
    if len(codes) > 1:
        detail = ", ".join(
            f"{os.path.basename(b.path)}={b.version_code}" for b in badges
        )
        issues.append(
            Issue(
                "fail",
                f"artifacts disagree on versionCode ({detail}); one release must "
                "ship one versionCode for every ABI",
            )
        )
    elif len(codes) == 1:
        code = codes.pop()
        issues.append(
            Issue(
                "ok",
                f"{len(badges)} artifact(s) share versionCode {code}",
            )
        )
        if expect_code is not None:
            if code == expect_code:
                issues.append(Issue("ok", f"versionCode matches expected {expect_code}"))
            else:
                issues.append(
                    Issue(
                        "fail",
                        f"versionCode {code} != expected {expect_code}",
                    )
                )
        if previous_code is not None:
            if code > previous_code:
                issues.append(
                    Issue("ok", f"versionCode {code} > previously distributed {previous_code}")
                )
            else:
                issues.append(
                    Issue(
                        "fail",
                        f"versionCode {code} is not greater than the previously "
                        f"distributed {previous_code}",
                    )
                )

    present = {os.path.basename(badge.path) for badge in badges}
    for required in required_names or []:
        if required in present:
            issues.append(Issue("ok", f"required artifact present: {required}"))
        else:
            issues.append(Issue("fail", f"required artifact missing: {required}"))

    return issues


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="apk_gate.py",
        description=(
            "Read-only gate: package identity, versionName, one shared "
            "versionCode and monotonicity for every release APK."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "examples:\n"
            "  python scripts/apk_gate.py --aapt2 $AAPT2 --expect-package com.sleeve.app \\\n"
            "      --expect-code 201000220 --previous-code 201000212 dist/*.apk\n"
        ),
    )
    parser.add_argument(
        "--aapt2",
        default="aapt2",
        help="path to aapt2/aapt (default: aapt2 from PATH)",
    )
    parser.add_argument("--expect-package", default=None)
    parser.add_argument("--expect-version-name", default=None)
    parser.add_argument("--expect-code", type=int, default=None)
    parser.add_argument(
        "--previous-code",
        type=int,
        default=None,
        help="highest previously distributed versionCode; every artifact must exceed it",
    )
    parser.add_argument(
        "--require-name",
        action="append",
        default=[],
        metavar="APK",
        help="artifact basename that must be present (repeatable)",
    )
    parser.add_argument("apks", nargs="+", help="built APK files to gate")
    return parser


def _dump_badging(aapt: str, path: str) -> str:
    try:
        proc = subprocess.run(
            [aapt, "dump", "badging", path],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise GateError(f"cannot run {aapt}: {exc}") from exc
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip().splitlines()
        raise GateError(
            f"{path}: {aapt} exited {proc.returncode}"
            + (f": {detail[0][:200]}" if detail else "")
        )
    return proc.stdout


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    badges: list[Badge] = []
    try:
        for path in args.apks:
            if not os.path.isfile(path):
                raise GateError(f"{path}: no such file")
            badges.append(parse_badging(path, _dump_badging(args.aapt2, path)))
    except GateError as exc:
        print(f"FAIL  {exc}", file=sys.stderr)
        return 2

    issues = check_badges(
        badges,
        expect_package=args.expect_package,
        expect_version_name=args.expect_version_name,
        expect_code=args.expect_code,
        previous_code=args.previous_code,
        required_names=args.require_name,
    )
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


if __name__ == "__main__":
    sys.exit(main())
