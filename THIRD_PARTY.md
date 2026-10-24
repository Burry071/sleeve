# Third-party components — Selkie

Selkie is a fork of [Seal](https://github.com/JunkFood02/Seal) (GPL-3.0) with an
embedded download engine built from the architecture of
[Ghost Downloader](https://github.com/XiaoYouChR/Ghost-Downloader-3) (GPL-3.0).

| Component | Upstream | License | Used for |
|---|---|---|---|
| Seal | JunkFood02/Seal @ v1.13.1 | GPL-3.0 | App shell: UI, extraction flow, database |
| Ghost Downloader engine design | XiaoYouChR/Ghost-Downloader-3 | GPL-3.0 | Task/step engine model, segmented download approach (ADR-0007) |
| selkie_engine.py | written for this fork, modelled on Ghost's HttpTaskStep | GPL-3.0 (this repo) | Embedded engine core |
| Chaquopy | chaquo/chaquopy | MIT | Python 3.13 runtime embedded in the app |
| youtubedl-android | JunkFood02/youtubedl-android | Apache-2.0 / LGPL-2.1 (components) | yt-dlp extraction runtime |
| yt-dlp | yt-dlp/yt-dlp | Unlicense | Site extraction |
| N_m3u8DL-RE (planned, Stage B v1) | nilaoda/N_m3u8DL-RE | MIT | Native HLS/DASH engine |

The combined work is licensed GPL-3.0 (see LICENSE). "Ghost Downloader" and
"Seal" are upstream project names; this fork is distributed as "Selkie" and is
not endorsed by either upstream project.
