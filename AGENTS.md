# Agent rules

For AI coding agents and IDE plug-ins (Claude Code, Qoder, Copilot, Cursor,
Codex and similar) working in this repository. These restate the parts of
CONTRIBUTING.md and RELEASE.md an automated assistant most often gets wrong, and
they take precedence over a generic default.

## Release and git

- Never push, tag, merge, publish a release, delete a release or its asset, or
  rewrite published history unless the owner asked for that exact action in the
  current conversation.
- Never reuse a version name or version code. Pre-release builds stop at 9 —
  `alpha.N`, `beta.N`, `rc.N` with N in `1..9` — and `Version.kt` refuses
  anything else. The next tags are `v2.1.0-rc.1`, then `v2.1.0` published as
  latest. The full scheme is in RELEASE.md, "Version naming and numbering".
- `buildSrc/src/main/kotlin/Version.kt` is the only place a version is written;
  the Gradle literal must match its computed code.

## Identity and legal

- Keep `com.sleeve.app`, the `com.junkfood.seal` namespace, the keystore and
  signing identity, and the GPL-3.0 licence and Seal copyright headers intact.
- Signing material (`keystore.jks`, `keystore.properties`, CI secrets) is never
  copied into new files, logs, commits or chat output.
- Do not add analytics, telemetry, accounts, remote config, or a second signing
  identity.

## Working rules

- Run both gates before claiming a change works:
  `python3 -m unittest discover -s scripts -p "test_*.py"` and
  `./gradlew :app:testGenericDebugUnitTest`. A successful build alone is not
  evidence that the behaviour is right.
- Do not run `ktfmtFormat`; match the surrounding formatting by hand.
- Keep only reader-facing docs. Research notes, review reports, code maps and
  design specs stay out of this repository.
