<div align="center">

# Sleeve

### A Seal that shed its skin — a downloader for (almost) everything, with a multi-connection engine inside
[![Get it on Orion Store](https://raw.githubusercontent.com/RookieEnough/Orion-Store/refs/heads/main/assets/orion-badge.png)](https://rookieenough.github.io/Orion-Data/app/?id=sleeve)

Sleeve started as a fork of [Seal](https://github.com/JunkFood02/Seal) (GPL-3.0),
a Material You yt-dlp frontend. It now carries an embedded download engine built
from the architecture of [Ghost Downloader](https://github.com/XiaoYouChR/Ghost-Downloader-3)
(GPL-3.0), an N_m3u8DL-RE binary for HLS/DASH, and a metadata-to-YouTube
matching pipeline for Spotify and Audible playlists. Own identity, own colors,
own type — no Seal skin left.

</div>

## What it downloads

| Source | How |
| --- | --- |
| 1000+ video/audio sites (YouTube, Vimeo, …) | yt-dlp extraction + download |
| Direct file links (CDN, mp4, zip, …) | embedded Ghost engine — 8 parallel range connections, resume, single-stream fallback |
| HLS / DASH streams (m3u8, mpd) | bundled N_m3u8DL-RE (multi-threaded segments, AES-128) |
| Spotify playlists / albums / tracks | spotDL pattern: Spotify metadata (public API) → best YouTube match → audio download |
| Audible books | Libation-derived public catalog metadata → YouTube match |

Every route fails open: if the engine, N_m3u8DL-RE, or a resolver is
unavailable, the stock yt-dlp path takes over — a download never gets *worse*
than upstream Seal.

## How a download flows

1. **Share or paste** a link. Spotify/Audible links are detected and resolved
   to a track list you confirm before queuing.
2. **yt-dlp extracts** the media info and formats (configure format, quality,
   subtitles, SponsorBlock… as in Seal).
3. **The right engine transfers the bytes**, chosen automatically per download:
   direct HTTP → segmented Ghost engine; manifests → N_m3u8DL-RE; anything
   needing yt-dlp post-processing → yt-dlp itself.
4. **ffmpeg embeds** title/artist metadata into engine-transferred files.
5. The file lands in `Download/Sleeve/`, is media-scanned, and shows in the
   history with cover art.

## Matching, not guessing

Spotify/Audible tracks are matched against YouTube with a scored ranker
(title similarity, artist match incl. official "- Topic" channels, duration
decay, forbidden-word penalties — live/remix/nightcore/cover…). Strictness is
configurable in Settings → Format → Match quality. Batch caps: 100 tracks per
resolve, 3 concurrent downloads, per-route timeouts and retry limits.

## Interface

Material 3 Expressive: Sleeve's own deep-sea teal palette (wallpaper dynamic
color optional), Space Grotesk typography, wavy progress indicators, cookie-
shaped brand mark, branded splash, animated transitions, press feedback.
Light and dark.

## Privacy & security

- No telemetry, no accounts, no tracking. Spotify/Audible access is public
  metadata only — audio always comes from YouTube; no DRM is touched.
- All download URLs are validated (scheme + private-address blocking) before
  any binary fetches them.
- Optional Spotify client credentials are stored app-privately; shared public
  defaults are rate-limited by Spotify — paste your own in Settings → Spotify.

## Build

```bash
./gradlew :app:assembleGenericDebug -PnoSplits -PABI_FILTERS="arm64-v8a;x86_64"
```

JDK 21, Android SDK (+NDK), and a local Python 3.13 for the Chaquopy build step
(`buildPython` path in `app/build.gradle.kts`).

## CI

`.github/workflows/ci.yml` and `.gitlab-ci.yml` build the APK on every push/PR
and on tags — point the repo at your Git host and updates ship themselves.
In-app links (Troubleshooting page) read the repo URL from
`app/src/main/java/com/junkfood/seal/SleeveRepo.kt`.

## Licensing & attribution

Seal, Ghost Downloader, and Sleeve are GPL-3.0. N_m3u8DL-RE is MIT. Bundled
binaries (`libnm3u8dlre.so`, `libffmpeg.so`) come from those projects. Space
Grotesk is OFL. See [THIRD_PARTY.md](THIRD_PARTY.md) for the full list. Sleeve
is not endorsed by any upstream project.
