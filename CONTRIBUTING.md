# Contributing

Sleeve is a free, open-source video and audio downloader for Android. Share a
link from any app or paste it inside Sleeve, choose a format, and the file
lands in `Download/Sleeve/` — with no ads, accounts, or tracking. This file
covers where to report a problem, how changes get accepted, and how to build
the app yourself.

## Lineage and license

Sleeve is a fork of [Seal](https://github.com/JunkFood02/Seal) by JunkFood02.
Seal provided the Android front end and the yt-dlp integration this project
grew out of, and the code that came from upstream remains under the copyright
of its authors. Those notices stay in the source, and the attribution stays in
the app. Sleeve inherits Seal's GPL-3.0 license, so the whole tree is GPL-3.0
and stays that way: if you redistribute Sleeve or a modified build, ship it
with the license and the upstream attribution intact.

## Reporting a bug

Bug reports go to [GitHub Issues](https://github.com/Burry071/sleeve/issues)
using the bug report template. Search existing issues first, including closed
ones; most common problems have already been reported and answered. The
reports that get diagnosed quickly share a few things:

- **The link that failed**, copied exactly. If the content needs a login, say
  so; that changes how the download is attempted.
- **Reproduction steps in order**: what you shared or pasted, which format or
  preset you chose, and what appeared on screen.
- **The task log** from the failed download. Open the task card and copy its
  error report; it carries the yt-dlp or gallery-dl output the maintainer
  needs.
- **Your app and Android version.** Settings → About copies a version report
  with the app version, Android release, and supported ABIs to the clipboard.

Screenshots help when something looks wrong rather than fails outright.

## Questions and ideas

GitHub Issues is the channel. Use the feature request template for behavior
you would like to see, and a regular issue for questions about using the app.
The issue tracker is also where release problems get discussed; there is no
separate chat room to watch.

## Building from source

You need:

- **JDK 21** (the Gradle toolchain and the Android plugin both target it).
- **Android SDK** with build-tools and the API 35 platform.
- **Python 3.13**, because [Chaquopy](https://chaquo.com/chaquopy/) compiles
  the embedded Python engine at build time. `app/build.gradle.kts` points
  `chaquopy.buildPython` at a local Python 3.13 executable; on your machine,
  set that line to your own interpreter's path.

Then, from the repository root:

```bash
./gradlew :app:assembleGenericDebug -PnoSplits -PABI_FILTERS="arm64-v8a;x86_64"
```

That produces a debug APK under `app/build/outputs/apk/generic/debug/`. Release
builds are signed only when `keystore.properties` exists at the repository
root; see [SIGNING.md](SIGNING.md). The debug package installs alongside the
release package, so you can test without replacing your installed build.

## How changes are accepted

- Work on a feature branch and keep one concern in it. A fix and a refactor
  belong in separate branches and separate PRs.
- Every behavior change comes with a test that pins it. If a change cannot be
  tested, say in the PR why, and name what you verified by hand.
- The existing invariant tests must stay green. They encode decisions such as
  one versionCode across ABI artifacts and the quick-download lane rules; a
  red invariant test means the premise changed and needs a discussion before
  the code.
- The full diff is reviewed before merge, and CI must pass. Merges to `main`
  happen only after both.
- Releases are tags only. Do not bump the version or tag a release in a pull
  request.

## Commit messages

A short imperative subject that says what the commit does — `Fix stale
notification after task delete`, not `fixed stuff` and not `feat: ...`. No
type prefixes. One change per commit. Add a body only when the reason for the
change is not obvious from the diff, and use it to explain why, not what.

## AI assistance

Development here uses AI assistance. Every change is reviewed by the
maintainer and pinned by tests before it merges; the review and the tests, not
the tool, decide what ships.

## License

Sleeve is licensed under the GPL-3.0. Contributions are accepted under the
same license.
