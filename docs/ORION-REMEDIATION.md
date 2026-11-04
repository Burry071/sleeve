# Orion Store remediation runbook

Scope: the Orion Store entry for Sleeve (`com.sleeve.app`) and the GitHub release
asset it points at. Orion catalog data lives in a separate repository
(`RookieEnough/Orion-Data`), not here, so this runbook never edits it. It
diagnoses, decides, and hands the owner an approval gate for every action that
touches a shared system.

Everything in section 3 is read-only. Nothing in this document runs automatically.

## 1. Ground truth (checked 2026-11-05)

| Fact | Value |
| --- | --- |
| Live GitHub releases | `v2.0.0-beta.10`, `v2.0.0-beta.3`, `v2.0.0-beta.2`, `v2.0.0-beta.1` |
| Orion mirror key | `github::github.com::burry071/sleeve` |
| Mirror newest entry | `v2.0.0-beta.10` |
| Mirror download URL | `https://github.com/Burry071/sleeve/releases/download/v2.0.0-beta.10/Sleeve-generic-release.apk` |
| Asset response | `302` → `200`, `106,859,077` bytes, `Accept-Ranges: bytes`, range → `206` |
| APK versionCode / SHA-256 | `200000302` / `4f515709e27bb1e0444fa60816c0328e4db81d0ef4f59d12181c8cac95e32d93` |
| Signing certificate SHA-256 | `2d5fd54b83c2b24d5fc267b8d082c936c44fd113db4267eb1c2f573a36adcad3` |
| Verifier result | 10 passed, 0 warnings, 0 failures; mirror status `OK (v2.0.0-beta.10)` |

The 2026-11-03 incident (mirror pointing at the deleted beta.7 asset, store stuck
at 0%) is resolved. Do not re-run emergency actions; start from section 3.

## 2. The invariant

A published release asset URL is a public interface. Third parties cache it and
fetch it long after publication. Deleting or replacing an asset breaks every
consumer that cached the URL, and the failure surfaces as a hang (0%) rather than
an error message.

Therefore remediation is **forward-only**:

- fix forward with a new tag and a strictly higher versionCode;
- never delete a release, asset, or tag;
- never reuse a published version name or code;
- never republish a deleted tag under its old name.

`RELEASE.md` rules 1, 2 and 9 are the normative source for this.

## 3. Read-only diagnostics

```bash
# Human-readable: release discovery, asset URL, redirect chain, range probe, mirror
python scripts/verify_release.py --channel beta --limit 30

# Machine-readable summary (stdout is JSON, the log goes to stderr)
python scripts/verify_release.py --channel beta --limit 30 --json

# Pin the check to a specific release
python scripts/verify_release.py --channel beta --expect-tag v2.0.0-beta.10
```

Use `--channel auto` to check the stable channel once a stable release exists.
`--limit` must be high enough to include the release under investigation.

### Reading `mirror status`

| Status | Meaning | Severity |
| --- | --- | --- |
| `OK (tag)` | Mirror's newest entry is the verified live release and its URL serves bytes | none |
| `BEHIND (tag)` | Mirror still lists an older release; Orion offers an older build | expected for ~a day after a release; escalation if longer |
| `AHEAD (tag)` | Mirror lists a release the GitHub API does not return | investigate; usually a draft or a deleted release |
| `STALE_MIRROR (tag -> HTTP 404)` | Mirror's URL points at a deleted asset. This is the incident shape | release-blocking |
| `STALE_MIRROR (tag -> HTTP nnn)` | URL reachable but not serving the asset | release-blocking |
| `UNREACHABLE` | Mirror JSON could not be fetched or parsed | retry; external outage |
| `KEY_MISSING` | `github::github.com::burry071/sleeve` absent or empty | escalate to Orion maintainer |
| `NO_URL` | Entry exists but carries no APK URL | escalate to Orion maintainer |
| `SKIPPED` | `--no-verify-mirror` was passed | not a result |

## 4. Decision tree

1. **`OK`** — nothing to do. Record the run (date, tag, SHA-256, mirror status).
2. **`BEHIND` right after a release** — normal. The Orion Mirror workflow runs on
   a schedule; wait for the next refresh and re-check within 24 hours.
   Users who already installed the newer build must not be offered the older one:
   if the mirror is still behind after 24 hours, go to step 7.
3. **`AHEAD`** — run the verifier again with `--limit 100`. If the release is
   absent from the API and the mirror URL returns 404, treat it as
   `STALE_MIRROR`. If the mirror URL serves bytes for a newer release, that is
   safe for users and only the local record needs updating.
4. **`STALE_MIRROR`** — release-blocking.
   - Confirm it is not transient: re-run once with `--no-verify-mirror` then with
     mirror verification, and probe the URL directly.
   - The only in-repo fix is a forward release with a higher versionCode
     (approval required, section 5). Orion will pick it up on its next refresh.
   - Do not republish the deleted tag or restore the deleted asset.
   - Notify the Orion maintainer with the template in section 6 (approval required
     to post).
5. **`UNREACHABLE`** — retry after a few minutes; check
   `https://www.githubstatus.com` and `raw.githubusercontent.com`. This is an
   external outage, not a release failure. Do not publish anything to "fix" it.
6. **`KEY_MISSING` / `NO_URL`** — external data problem. Draft the report in
   section 6; do not edit Orion-Data from this repository.
7. **Mirror still behind after 24 hours with a live, verified newer release** —
   escalate to the Orion maintainer. Include both URLs and both status codes so
   the stale entry can be identified unambiguously.

## 5. Approval-gated actions

Nothing below may be done without the repository owner's explicit approval.
Approval is per action, not standing.

| Action | Mechanism | Why approval is required |
| --- | --- | --- |
| Publish a forward release | Bump `Version.kt`, commit, push a new `v*` tag | Public, irreversible distribution; spends a version code |
| Post or send an Orion report | Issue/comment/message to the Orion maintainer | Visible to third parties |
| Edit Orion-Data | Out of scope for this repo | Shared external state owned by another project |
| Change release notes | `gh release edit` | Public-facing edit of a shipped release |

A forward release is only compliant when `python scripts/release_guard.py --tag
<new>` passes: tag matches `Version.kt`, Gradle matches, and the shipped code
exceeds every distributed code in `release-record.json`.

## 6. Orion report template

Only the values below; never include signing passwords, keystore material, or
`.properties` contents.

```text
App: Sleeve (com.sleeve.app)
Mirror key: github::github.com::burry071/sleeve

Observed: <date/time UTC>
Mirror newest tag: <tag>
Mirror downloadUrl: <url>
URL response: HTTP <status> (<content-length> bytes)
Live GitHub releases: <comma-separated tags>

Expected: the mirror should point at the newest live release:
  <tag> — <url> — HTTP 200, <bytes> bytes

Impact: Orion shows <0% / an install failure> for new installs because the
configured URL does not serve APK bytes.

Request: please refresh or update the mirror entry for Sleeve.
```

## 7. Post-remediation verification

A remediation is complete only when all of these are true:

- the mirror's newest tag equals a live release whose asset returns `200`
  (`302` redirect allowed) and answers a range request with `206`;
- the verifier exits `0` with `mirror status: OK (<tag>)`;
- the APK's SHA-256 and signer certificate match `release-record.json`;
- the previous working release is still published and downloadable;
- `release-record.json` is updated only when the measured facts are complete.

Do not claim Orion production verification from an emulator, a local build, or a
mirror refresh that has not happened. The verifier's mirror check is the evidence.

## 8. What never to do

- Delete a release, asset, or tag to "return to" an earlier build.
- Reuse a version name or version code, even one that was deleted.
- Lower a version code to match an older APK.
- Edit Orion-Data or the Orion client from this repository.
- Treat a 0% download indicator as a downloader bug before probing the URL.
- Report "Orion fixed" based on a mirror refresh alone; the asset URL must serve
  bytes for the exact tag the mirror selects.
