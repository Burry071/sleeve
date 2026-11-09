# Third-party components

Sleeve is a fork of Seal (GPL-3.0) and includes code derived from the projects
below. The combined work is distributed under GPL-3.0 (see LICENSE). This fork
is published as "Sleeve" and is not endorsed by any upstream project.

| Component | Source | License | Used for |
|---|---|---|---|
| Seal | github.com/JunkFood02/Seal | GPL-3.0 | App shell: UI, download flow, database |
| Ghost Downloader | github.com/XiaoYouChR/Ghost-Downloader-3 | GPL-3.0 | Segmented download engine design |
| yt-dlp | github.com/yt-dlp/yt-dlp | Unlicense | Site extraction |
| youtubedl-android | github.com/JunkFood02/youtubedl-android | Apache-2.0 / LGPL-2.1 | yt-dlp runtime on Android |
| gallery-dl | github.com/mikf/gallery-dl | GPL-2.0 | Image/media site extraction |
| N_m3u8DL-RE | github.com/nilaoda/N_m3u8DL-RE | MIT | HLS/DASH engine (`libnm3u8dlre.so`), upstream v0.6.0-beta android-bionic builds, unmodified |
| FFmpeg | ffmpeg.org | LGPL-2.1+ | Merge and metadata (`libffmpeg.so`) |
| Chaquopy | github.com/chaquo/chaquopy | MIT | Embedded Python 3.13 runtime |
| requests | github.com/psf/requests | Apache-2.0 | HTTP for gallery-dl (vendored) |
| urllib3 | github.com/urllib3/urllib3 | MIT | vendored |
| certifi | github.com/certifi/python-certifi | MPL-2.0 | vendored CA bundle |
| idna | github.com/jd/idna | BSD-3-Clause | vendored |
| charset_normalizer | github.com/Ousret/charset_normalizer | MIT | vendored |
| Jetpack Compose / Kotlin | androidx, jetbrains | Apache-2.0 | Android app stack |
| monet | github.com/Kyant0/monet | Apache-2.0 | Dynamic color |
| Space Grotesk | Florian Karsten | SIL OFL 1.1 | Typography |

## Bundled N_m3u8DL-RE binaries

Sleeve ships the upstream executables unmodified, renamed to
`libnm3u8dlre.so` so Android extracts them as executable native payloads.
Both files come from the same upstream release and build pipeline:

| ABI | Upstream asset | SHA-256 |
|---|---|---|
| arm64-v8a | `N_m3u8DL-RE_v0.6.0-beta_android-bionic-arm64_20260629.tar.gz` | `ea473427fcee215223815eaab607e31e8f114557d0ef13f311af3e408dbe729d` |
| x86_64 | `N_m3u8DL-RE_v0.6.0-beta_android-bionic-x64_20260629.tar.gz` | `b47f72547e75b0fdd86fb379d06d18a30d79b5d1f8e26998fa0b9a86fb535c43` |

Download URL pattern:
`https://github.com/nilaoda/N_m3u8DL-RE/releases/download/v0.6.0-beta/<asset>`.
Update this table whenever either binary is replaced.
