<div align="center">

<img src="app/src/main/res/drawable-nodpi/seal_mascot.png" width="96" alt="Sleeve app icon">

# Sleeve

**Paste a link. Pick a format. Get the file.**

**What is Sleeve?** A free, open-source (GPL-3.0) video and audio downloader
for Android 7.0+ with no ads, tracking, or accounts. It supports YouTube,
Instagram, TikTok, Twitter/X, Reddit and 1,800+ yt-dlp sites, 500+ gallery-dl
image hosts, HLS/DASH streams, Spotify playlists and Audible audiobooks, and
downloads each link by the fastest path available.

[![Release](https://img.shields.io/github/v/release/Burry071/sleeve?label=release)](https://github.com/Burry071/sleeve/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/Burry071/sleeve/total?label=downloads&color=3DDC84)](https://github.com/Burry071/sleeve/releases)
[![License](https://img.shields.io/github/license/Burry071/sleeve)](LICENSE)
[![CI](https://img.shields.io/github/actions/workflow/status/Burry071/sleeve/ci.yml?branch=main&label=build)](https://github.com/Burry071/sleeve/actions/workflows/ci.yml)
[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84)](https://developer.android.com)

[![Get it on Orion Store](https://raw.githubusercontent.com/RookieEnough/Orion-Store/refs/heads/main/assets/orion-badge.png)](https://rookieenough.github.io/Orion-Data/app/?id=sleeve)

<img width="280" alt="Sleeve home screen" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png">&nbsp;<img width="280" alt="Sleeve download options" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png">&nbsp;<img width="280" alt="Sleeve download queue" src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png">

</div>

Sleeve is a community fork of [Seal](https://github.com/JunkFood02/Seal)
(GPL-3.0). It combines yt-dlp and gallery-dl extraction with a built-in
multi-connection download engine based on
[Ghost Downloader](https://github.com/XiaoYouChR/Ghost-Downloader-3), so a
resolved link is downloaded by the fastest path available instead of one
single-threaded HTTP stream.

## What it does

- **1,800+ sites** through yt-dlp: YouTube, Vimeo, TikTok, Twitter/X,
  SoundCloud, and everything on the
  [supported sites list](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md)
- **Instagram, Reddit, Imgur, Pixiv and 500+ image hosts** through embedded
  gallery-dl, with anonymous resolution for most Reddit and Instagram posts
- **Multi-connection downloads**: 8 parallel connections per file with resume,
  for direct file links
- **HLS and DASH streams** (m3u8/mpd) through bundled N_m3u8DL-RE, including
  AES-128
- **Spotify playlists and Audible audiobooks**: public metadata is resolved
  and the best-matching audio is downloaded from YouTube (spotDL method)
- **Full format control**: resolution, audio-only, subtitles, SponsorBlock,
  metadata embedding, filename templates
- **Material 3 Expressive UI** with dynamic color, light and dark themes

## Quick start

1. Install the APK (below) and open Sleeve once.
2. Share any video or image link to Sleeve from another app, or copy the
   link and tap the clipboard card in the app.
3. Choose a format or just hit Download. Files land in `Download/Sleeve/`.

## Supported sources

| Content | How it is downloaded |
| --- | --- |
| Video and audio sites (YouTube, Vimeo, TikTok, …) | yt-dlp extraction, native Android clients |
| Direct file links (CDN, mp4, mp3, zip, …) | Ghost engine, segmented HTTP with resume |
| Live and on-demand HLS/DASH (m3u8, mpd) | N_m3u8DL-RE, multi-threaded segments |
| Image hosts and galleries (Instagram, Reddit, Pixiv, Imgur, …) | gallery-dl |
| Pages without a dedicated extractor | generic media sniffing (og:video, stream URLs) |
| Spotify playlists, albums, and tracks | public metadata, ranked YouTube match, audio |
| Audible audiobooks | public catalog metadata, ranked YouTube match |

Content protected by DRM (Netflix, Disney+, Widevine streams) is not
supported, by design.

## Privacy and safety

Sleeve collects nothing: no telemetry, no analytics, no accounts. Everything
runs on your device. Download URLs are validated before any request leaves
the app.

Only install APKs signed with the release certificate `CN=Sleeve, O=Burry071`
from this repository's Releases or the Orion Store listing. APKs from
third-party mirrors are not checked by anyone.

## Download

[![Download APK](https://img.shields.io/badge/Download_APK-latest-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Burry071/sleeve/releases/latest/download/Sleeve-generic-release.apk)

Direct, signed APK (arm64-v8a + x86_64, Android 7.0+). The button always
serves the current release, so there is no need to open the releases page
first. Release notes and older versions are on the
[releases page](https://github.com/Burry071/sleeve/releases).

Also available on the
[Orion Store](https://rookieenough.github.io/Orion-Data/app/?id=sleeve).

## FAQ

**Is Sleeve free?**
Yes. GPL-3.0 software with no paid tiers, no ads, and no subscriptions.

**Does Sleeve work with YouTube?**
Yes. YouTube downloads use yt-dlp with native Android player clients, which
avoids the JavaScript-runtime requirement that breaks most Android YouTube
downloaders.

**Can Sleeve download a Spotify playlist as MP3?**
Yes. Sleeve reads the public playlist metadata from Spotify, ranks the
matching YouTube audio tracks (title, artist, duration, live/remix
penalties), and downloads the best match. Match strictness is configurable in
Settings.

**Why does an Instagram or Reddit post fail with a login message?**
Some sites and subreddits require an authenticated session. Log in once via
Settings → Cookies & profiles; Sleeve reuses that session for later
downloads. Most public Reddit posts and Instagram reels work without it.

**How is Sleeve different from Seal?**
Sleeve keeps every Seal feature and adds a multi-connection engine, HLS/DASH
support, gallery-dl coverage, Spotify and Audible matching, and anonymous
Reddit resolution. See [Sleeve vs Seal](#sleeve-vs-seal) for the full table.

**Which sites are supported?**
Anything listed by
[yt-dlp](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md) and
[gallery-dl](https://github.com/mikf/gallery-dl/blob/master/docs/supportedsites.md),
plus direct file links and most pages containing a video stream.

## Sleeve vs Seal

Sleeve is a [Seal](https://github.com/JunkFood02/Seal) fork (GPL-3.0) that keeps
every original feature and adds the capabilities below. Comparison is against
Seal 1.13.1.

| Capability | Sleeve | Seal |
| --- | --- | --- |
| yt-dlp extraction, SponsorBlock, subtitles, cookies, custom commands | Yes | Yes |
| Multi-connection download engine (8 connections, resume) | Yes, Ghost | No |
| HLS/DASH via N_m3u8DL-RE, including AES-128 | Yes | No |
| gallery-dl image and gallery coverage (500+ hosts) | Yes | No |
| Spotify playlist and Audible audiobook matching | Yes | No |
| Anonymous Reddit resolution | Yes | No |
| Material 3 Expressive interface | Yes | No |

Both are GPL-3.0 and run entirely on device.

## Build

Requirements: JDK 21, Android SDK, and Python 3.13 for the Chaquopy build
step.

```bash
./gradlew :app:assembleGenericDebug -PnoSplits -PABI_FILTERS="arm64-v8a;x86_64"
```

Release builds are signed when `keystore.properties` exists at the repo root.
See [SIGNING.md](SIGNING.md). CI builds every push and publishes signed APKs
for tagged releases.

## Contributing

Bug reports and feature requests go to the
[issue tracker](https://github.com/Burry071/sleeve/issues). Pull requests are
welcome; keep them focused on one change per PR.

## Credits and license

Sleeve is GPL-3.0, as are Seal and Ghost Downloader. N_m3u8DL-RE is MIT.
See [THIRD_PARTY.md](THIRD_PARTY.md) for the complete list of bundled
components and licenses. Sleeve is an independent project and is not endorsed
by Seal, Ghost Downloader, yt-dlp, or gallery-dl.
