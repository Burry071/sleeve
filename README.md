<div align="center">

# Selkie

### A Seal that shed its skin — video downloader with an embedded multi-connection engine

Selkie is a fork of [Seal](https://github.com/JunkFood02/Seal) (GPL-3.0): the same
Material You yt-dlp frontend, reworked as its own app with a download engine built
from the architecture of [Ghost Downloader](https://github.com/XiaoYouChR/Ghost-Downloader-3)
(GPL-3.0). yt-dlp still resolves every site it knows; the embedded engine does the
downloading with parallel range requests.

</div>

## What is different from Seal

- **Embedded engine** — a Python task engine (segmented HTTP, 8 parallel
  connections, byte-range resume, single-stream fallback) runs inside the app via
  Chaquopy. Toggle it in Settings → Network → Ghost engine.
- **Own identity** — renamed, re-attributed, no upstream donation links; app
  self-update disabled (yt-dlp component updates stay).
- **Material 3 Expressive** motion theme.

## Licensing & attribution

Seal and Ghost Downloader are GPL-3.0; Selkie is GPL-3.0 too. See
[THIRD_PARTY.md](THIRD_PARTY.md) for the full component list. Selkie is not
endorsed by either upstream project.

## Build

```bash
./gradlew :app:assembleGenericDebug -PnoSplits -PABI_FILTERS="arm64-v8a;x86_64"
```

Requires JDK 21, an Android SDK, and a local Python 3.13 for the Chaquopy build
step (`buildPython` path is set in `app/build.gradle.kts`).
