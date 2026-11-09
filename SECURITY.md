# Security

Sleeve downloads media from sites you choose and keeps your cookie sessions on
the device, so security reports get priority. This page covers what is in
scope, how to report a problem privately, and what the release pipeline already
guarantees.

## Supported versions

Security fixes ship in the latest release only. Older releases are not patched;
update to the current one. Only builds of `com.sleeve.app` signed with the
project certificate are supported, so reports against re-signed or modified
builds from other sources are out of scope.

## Reporting a vulnerability

Use GitHub's private security advisory for this repository:

https://github.com/Burry071/sleeve/security/advisories/new

Please do not open a public issue for an unfixed vulnerability. A useful report
includes the app version and version code (Settings → About copies them), what
you did, what happened, and a proof of concept if you have one. You will get a
reply on the advisory, and any fix ships in a new release.

## In scope

- **The update pipeline.** Update discovery, the APK download, the digest and
  signing checks, and the handoff to the system installer.
- **Cookie storage.** The cookies file, saved profiles, and anything that could
  expose a signed-in session to another app or to shared storage.
- **The share and quick-download surface.** Share targets, quick-download
  intents, the activity that receives them, and notifications with actions.

## What Sleeve already guarantees

- **No telemetry and no accounts.** No analytics, no account, no identifiers
  sent anywhere. Apart from the requests implied by the link you asked to
  download and the update check against GitHub, nothing leaves the device.
- **Update APKs are verified before install.** When GitHub publishes a SHA-256
  digest for the asset, the downloaded APK must match it, and in every case the
  APK's signing certificate must match the installed app's before the installer
  is shown.
- **One signing identity.** Releases are signed with the certificate
  `CN=Sleeve, O=Burry071`, and CI verifies that certificate on every artifact
  plus a byte-identical re-download of each published asset. The certificate
  digest is documented in the README and never changes.
- **Cookies stay app-private.** Your imported session lives in the app's
  private storage, not on shared storage, and is only used for download
  requests you start.
- **A forged quick-download launch cannot use your session.** Quick mode runs
  without cookies, and share launches stop at a confirmation sheet instead of
  queueing directly.

## Not a vulnerability

- **DRM-protected content is unsupported by design.** Netflix, Disney+, and
  Widevine streams are out of scope, and a bypass for them will not be added.
- **A site refusing to serve a download.** Extractor behavior follows upstream
  yt-dlp and gallery-dl; a broken extractor on one site is a bug to report
  through the issue tracker, not a security issue.
