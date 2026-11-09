# Release Safety

Repo: `Burry071/sleeve`. Application ID: `com.sleeve.app` (debug variant appends `.debug`).

The rules below are also embedded as a comment block at the top of
`.github/workflows/ci.yml`. Treat them as mandatory for every manual and
automated release.

## Rules

1. never delete a published GitHub release or its APK asset as part of routine development;
   - Operational meaning: a shipped release and its asset stay in place; fixes ship as a new release.
2. never reuse a published version name or version code;
   - Operational meaning: once a name/code has been distributed, it is permanently spent.
3. every new release's version code must be greater than every previously distributed build, including other channels;
   - Operational meaning: compare against the highest code ever distributed, not just the last stable.
4. preserve application ID and signing identity;
   - Operational meaning: keep `com.sleeve.app` and the existing keystore; changing either breaks updates.
5. verify final APK exists, non-empty, installs, expected package metadata;
   - Operational meaning: inspect the produced APK, do not assume the build step succeeded.
6. verify GitHub release asset URL returns success and supports expected download behavior;
   - Operational meaning: the asset URL must actually serve bytes, including redirect and range handling.
7. verify Orion Store can discover and fetch the exact new release asset;
   - Operational meaning: the Orion entry must resolve to this specific asset, not an older one.
8. never publish before Orion distribution compatibility has been checked;
   - Operational meaning: Orion compatibility is validated before the release goes public, not after.
9. never allow Orion to select a deleted/stale/unavailable release;
   - Operational meaning: Orion must never be able to point at a release or asset that no longer resolves.
10. retain the previous working release as a recovery option;
    - Operational meaning: the last known-good release remains published and downloadable.
11. record version, version code, commit, APK SHA-256, signing certificate fingerprint, asset URL;
    - Operational meaning: every release has a durable record sufficient to audit and re-verify it.
12. do not claim a release successful until the actual distribution path is verified.
    - Operational meaning: success means an end-to-end fetch through the real distribution path.

## Release asset URL and the Orion mirror

A published asset URL is a public interface. Third parties cache it and fetch it
long after publication, and one of them is the Orion Store mirror
(`github::github.com::burry071/sleeve`) behind the store listing. Deleting or
replacing an asset breaks every cached consumer, and the failure surfaces as a
download that sits at 0% rather than as an error. That is why rules 1, 2 and 9
exist, and why remediation is forward-only.

The repository's read-only verifier is the check:

```bash
python scripts/verify_release.py --channel beta --limit 30 --expect-tag vX.Y.Z
```

`--channel auto` checks the stable channel once one exists. `--json` is the form CI
gates on: the release job verifies the universal, arm64 and x86_64 asset this way,
retries ten times, and fails the release if any run is not green.

`mirror status` in the output means:

| Status | Meaning | Action |
| --- | --- | --- |
| `OK (tag)` | Mirror's newest entry is the verified live release and serves bytes | none |
| `BEHIND (tag)` | Mirror still lists an older release | normal for up to about a day after publishing; escalate if longer |
| `AHEAD (tag)` | Mirror lists a release the API does not return | re-run with `--limit 100`; usually a draft or a deleted release |
| `STALE_MIRROR (tag -> HTTP nnn)` | Mirror's URL does not serve APK bytes; this is the incident shape | release-blocking; fix forward |
| `UNREACHABLE` | Mirror JSON could not be fetched | retry; external outage, publish nothing |
| `KEY_MISSING` / `NO_URL` | Entry missing or carrying no URL | escalate to the Orion maintainer |

Escalation, and anything that touches Orion-Data, is an approval-gated action
rather than routine maintenance. If the mirror is still behind 24 hours after a
live, verified release, send the maintainer both URLs and both status codes so the
stale entry can be identified without ambiguity.

A remediation is complete only when the mirror's newest tag equals a live release,
that asset returns `200` (`302` allowed) and answers a range request with `206`,
the verifier exits `0` with `mirror status: OK`, the APK's SHA-256 and signer match
`release-record.json`, and the previous working release is still downloadable. A
mirror refresh alone is not evidence: the asset URL has to serve bytes for the
exact tag the mirror selects.

## Pre-publish checklist

- [ ] Version code is monotonic. Base `versionCode` in `app/build.gradle.kts` equals `currentVersion.code`
      (`check(versionCode == currentVersionCode)`); it must exceed the highest previously distributed code
      on every channel.
- [ ] ABI offsets accounted for. Split builds add `armeabi-v7a` +1, `arm64-v8a` +2, `x86` +3, `x86_64` +4.
      No-splits builds add the offset of the first ABI in `ABI_FILTERS` (CI uses `arm64-v8a;x86_64`, so +2).
      The new base code plus the lowest offset must still exceed the previous base code plus the highest
      offset actually distributed.
- [ ] Git tag matches `currentVersion.name` in `buildSrc/src/main/kotlin/Version.kt`
      (tag `vX.Y.Z[-pre.N]` ↔ version name `X.Y.Z[-pre.N]`).
- [ ] APK exists and is non-zero: release artifact present under
      `app/build/outputs/apk/generic/release/*.apk` and the published asset `Sleeve-generic-release.apk`
      has non-zero size.
- [ ] Package metadata verified: `applicationId` is `com.sleeve.app`, `versionName`/`versionCode` as intended,
      APK installs on a device or emulator.
- [ ] APK SHA-256 and signing certificate fingerprint recorded (SHA-256 of the APK file; SHA-256 fingerprint
      of the signing certificate), together with version, version code, commit and asset URL.
- [ ] Asset URL verified: the release asset download URL redirects (302) to storage and resolves to 200,
      supports `Accept-Ranges` and answers a range request with 206.
- [ ] Orion mirror entry points at an existing asset of this release (correct tag, correct asset name),
      and the asset URL resolves.
- [ ] Previous working release retained and still downloadable as the recovery option.

Recorded per release (rule 11): version, version code, commit, APK SHA-256, signing certificate
fingerprint, asset URL.

## Signing identity

The signing identity is preserved through the existing repository secrets, consumed by the `release` job:

- `SLEEVE_KEYSTORE_BASE64`
- `SLEEVE_STORE_PASSWORD`
- `SLEEVE_KEY_ALIAS`
- `SLEEVE_KEY_PASSWORD`

Do not rotate, regenerate or replace these for a routine release, and never commit their values.

## Release failure recovery

- Prefer a NEW release with a higher version code over re-publishing or deleting anything.
- Never reuse a version code, and never reuse a published version name.
- Never force-delete a release or its asset to "fix" a routine problem; ship a new version instead.
- Keep the previous working release published until the new one is verified end to end.
- If the published APK, asset URL or Orion entry is wrong, fix forward with a higher version code and
  re-run the full checklist; leave the broken release in place unless it actively misleads users,
  in which case update its notes to point at the working release.
