#!/usr/bin/env python3
"""Live dry-run of the Python lanes Sleeve ships.

Runs the three in-repo modules against fixed public inputs and prints one
PASS/FAIL line per lane. Metadata only: nothing is downloaded and no account,
cookie or secret is used. A non-zero exit is what the extractor-canary workflow
turns into (or closes) the upstream-canary issue.

Fixtures were verified live on 2026-11-15:
  gallery  - a PNG hosted in this repo (raw.githubusercontent.com)
  spotify  - "Today's Top Hits" public playlist
  audible  - Dune, ASIN B002V1OF70

Usage: python .github/canary/dry_run.py
"""

from __future__ import annotations

import json
import os
import sys

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "python")
)

GALLERY_URL = (
    "https://raw.githubusercontent.com/Burry071/sleeve/main/"
    "fastlane/metadata/android/en-US/images/phoneScreenshots/1.png"
)
SPOTIFY_URL = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
AUDIBLE_URL = "https://www.audible.com/pd/B002V1OF70"


def check_gallery() -> tuple[bool, str]:
    import sleeve_engine

    payload = json.loads(sleeve_engine.gallery_resolve(GALLERY_URL))
    if not payload.get("ok"):
        return False, f"gallery_resolve not ok: {payload.get('error')}"
    items = payload.get("items") or []
    if not items:
        return False, "gallery_resolve returned no items"
    return True, f"{len(items)} item(s), category={items[0].get('category')}"


def check_spotify() -> tuple[bool, str]:
    import sleeve_spotify

    payload = json.loads(sleeve_spotify.resolve(SPOTIFY_URL))
    if not payload.get("ok"):
        return False, f"spotify resolve not ok: {payload.get('error')}"
    tracks = payload.get("tracks") or []
    if not tracks:
        return False, "spotify resolve returned no tracks"
    return True, f"{len(tracks)} track(s), kind={payload.get('kind')}"


def check_audible() -> tuple[bool, str]:
    import sleeve_audible

    payload = json.loads(sleeve_audible.resolve(AUDIBLE_URL))
    if not payload.get("ok"):
        return False, f"audible resolve not ok: {payload.get('error')}"
    tracks = payload.get("tracks") or []
    if not tracks:
        return False, "audible resolve returned no tracks"
    return True, f"{len(tracks)} track(s), title={tracks[0].get('title')!r}"


def main() -> int:
    lanes = (
        ("sleeve_engine (gallery-dl lane)", check_gallery),
        ("sleeve_spotify", check_spotify),
        ("sleeve_audible", check_audible),
    )
    failures = 0
    for name, lane in lanes:
        try:
            ok, detail = lane()
        except Exception as exc:  # noqa: BLE001 - any exception is a canary failure
            ok, detail = False, f"{type(exc).__name__}: {exc}"
        print(f"{'PASS' if ok else 'FAIL'} {name}: {detail}")
        failures += 0 if ok else 1
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
