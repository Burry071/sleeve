<div align="center">

<img src="app/src/main/res/drawable-nodpi/seal_mascot.png" width="96" alt="Sleeve app icon">

# Sleeve

**Open-source video and audio downloader for Android**

[![Release](https://img.shields.io/github/v/release/Burry071/sleeve?label=release)](https://github.com/Burry071/sleeve/releases/latest)
[![License](https://img.shields.io/github/license/Burry071/sleeve)](LICENSE)
[![CI](https://img.shields.io/github/actions/workflow/status/Burry071/sleeve/ci.yml?branch=main&label=build)](https://github.com/Burry071/sleeve/actions/workflows/ci.yml)
[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84)](https://developer.android.com)

[![Get it on Orion Store](https://raw.githubusercontent.com/RookieEnough/Orion-Store/refs/heads/main/assets/orion-badge.png)](https://rookieenough.github.io/Orion-Data/app/?id=sleeve)

</div>

Sleeve is a free, open-source download manager for Android. Share a link from
YouTube, Instagram, Reddit, or any of 1,800+ supported sites, choose a format,
and Sleeve saves the video or audio file to your device. It combines yt-dlp
and gallery-dl extraction with a built-in multi-connection download engine
based on [Ghost Downloader](https://github.com/XiaoYouChR/Ghost-Downloader-3).

Sleeve is a community fork of [Seal](https://github.com/JunkFood02/Seal)
(GPL-3.0). It contains no ads, no tracking, and no accounts.

## Features

- **1,800+ sites** — YouTube, Vimeo, Reddit, Twitter/X, TikTok, and everything
  else yt-dlp supports
- **500+ image and media sites** — Instagram, Reddit galleries, Pixiv, Imgur,
  and more via embedded gallery-dl
- **Multi-connection engine** — 8 parallel connections per file with resume
  support, based on the Ghost Downloader architecture
- **HLS and DASH streams** — m3u8/mpd handled by the bundled N_m3u8DL-RE
- **Spotify playlists** — resolves public playlist metadata and downloads the
  best-matching audio from YouTube (spotDL method)
- **Audible audiobooks** — public catalog lookup via the Libation method
- **Format control** — resolution, audio-only, subtitles, SponsorBlock,
  metadata embedding, filename templates
- **Material 3 Expressive UI** — dynamic color, light and dark themes,
  portrait and landscape

## Supported sources

| Content | Method |
| --- | --- |
| Video and audio sites (YouTube, Vimeo, TikTok, …) | yt-dlp extraction, native Android clients |
| Direct file links (CDN, mp4, mp3, zip, …) | Ghost engine — segmented HTTP, resume |
| Live and on-demand HLS/DASH (m3u8, mpd) | N_m3u8DL-RE, multi-threaded segments, AES-128 |
| Image hosts and galleries (Instagram, Reddit, Pixiv, Imgur, …) | gallery-dl |
| Pages without a dedicated extractor | generic media sniffing (og:video, stream URLs) |
| Spotify playlists, albums, and tracks | public metadata → ranked YouTube match → audio |
| Audible audiobooks | public catalog metadata → ranked YouTube match |

Content protected by DRM (Netflix, Disney+, Widevine streams) is not
supported, by design.

## How it works

1. Share a link to Sleeve, or paste one in the app.
2. Sleeve identifies the source and picks the right extraction path.
3. A resolved download is transferred by the fastest available engine:
   segmented HTTP for direct files, N_m3u8DL-RE for stream manifests,
   yt-dlp for anything needing its post-processing.
4. ffmpeg embeds title and artist metadata into engine-transferred files.
5. Completed files appear in `Download/Sleeve/` and in the in-app history.

Every route fails open: if an engine or resolver is unavailable, the stock
yt-dlp path takes over.

## Download

**[Latest release](https://github.com/Burry071/sleeve/releases/latest)** —
signed APK, requires Android 7.0 or newer (arm64-v8a or x86_64).

Also available on the
[Orion Store](https://rookieenough.github.io/Orion-Data/app/?id=sleeve).

Verify the APK signature: the release certificate is `CN=Sleeve, O=Burry071`.

## Privacy

Sleeve collects nothing. There is no telemetry, no analytics, and no account
system. Spotify and Audible integration reads public metadata only; audio for
matched tracks comes from YouTube. Optional Spotify API credentials you paste
yourself are stored in the app's private storage. All download URLs are
validated before any network request leaves the app.

## FAQ

**Is Sleeve free?**
Yes. Sleeve is GPL-3.0 software with no paid tiers, no ads, and no
subscriptions.

**Does Sleeve work with YouTube?**
Yes. YouTube downloads use yt-dlp with native Android player clients, which
avoids the JavaScript-runtime requirement that breaks most Android YouTube
downloaders.

**Can Sleeve download a Spotify playlist as MP3?**
Yes. Sleeve reads the public playlist metadata from Spotify, ranks the
matching YouTube audio tracks (title, artist, duration, live/remix
penalties), and downloads the best match. Strictness is configurable in
Settings.

**Why does an Instagram post fail with "This content needs a login"?**
Instagram requires an authenticated session for most content. Log in once via
Settings → Cookies & profiles; Sleeve reuses that session for subsequent
downloads.

**How is Sleeve different from Seal?**
Sleeve adds an embedded multi-connection download engine, HLS/DASH support via
N_m3u8DL-RE, gallery-dl site coverage, Spotify and Audible playlist
resolution, and a redesigned Material 3 Expressive interface. All original
Seal features (SponsorBlock, subtitles, cookies, playlists, custom commands)
remain.

**Which sites are supported?**
Anything listed by [yt-dlp](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md)
and [gallery-dl](https://github.com/mikf/gallery-dl/blob/master/docs/supportedsites.md),
plus direct file links and most pages containing a video stream.

## Build

Requirements: JDK 21, Android SDK, and Python 3.13 for the Chaquopy build
step.

```bash
./gradlew :app:assembleGenericDebug -PnoSplits -PABI_FILTERS="arm64-v8a;x86_64"
```

Release builds are signed when `keystore.properties` exists at the repo root —
see [SIGNING.md](SIGNING.md). CI builds every push and publishes signed APKs
for tagged releases.

## Contributing

Bug reports and feature requests go to the
[issue tracker](https://github.com/Burry071/sleeve/issues). Pull requests are
welcome — engine work follows the notes in
[engine-ghost](engine-ghost).

## License and attribution

Sleeve is GPL-3.0, as are Seal and Ghost Downloader. N_m3u8DL-RE is MIT.
See [THIRD_PARTY.md](THIRD_PARTY.md) for the complete list of bundled
components and licenses. Sleeve is an independent project and is not endorsed
by Seal, Ghost Downloader, yt-dlp, or gallery-dl.
