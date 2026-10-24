# engine-ghost — packing the Ghost Downloader engine into Seal

Goal: Seal keeps doing site extraction with yt-dlp, but hands resolved direct
URLs to the Ghost Downloader engine (multi-thread segmented HTTP, HLS via
N_m3u8DL-RE, ffmpeg merge) instead of yt-dlp's built-in downloader.

Licensing: Seal is GPL-3.0, Ghost-Downloader-3 is GPL-3.0. The combined work
stays GPL-3.0; keep both copyright headers. Do not ship under the "Ghost
Downloader" product name — this is "Seal with the Ghost engine".

## Verified architecture (2026-10-24, from APK + source)

| Fact | Seal 1.13.1 | Ghost 4.4.5 Android |
|---|---|---|
| UI | Kotlin + Compose | native Android shell (compose-style) |
| Engine | `io.github.junkfood02.youtubedl-android:0.17.3` (library+ffmpeg+aria2c) | Chaquopy + CPython 3.14 (`libpython3.14.so`, `assets/chaquopy/*`) |
| Downloader | yt-dlp native, optional aria2c toggle | own engine: `HttpTaskStep` (8 subworkers, Range fallback), m3u8 pack → `libnm3u8dlre.so`, `libqjs.so` (QuickJS-NG for yt-dlp JS), `libffmpeg.so`, `libkelpie.so` (Go eD2k) |
| yt-dlp role | extract + download | extract-only library mode (ADR-0007) |

The single choke point where every Seal download passes:
`app/src/main/java/com/junkfood/seal/util/DownloadUtil.kt` — the
`YoutubeDL.getInstance().execute(request, processId, callback)` calls.

## Stage A (this branch) — seams and toggle, zero behavior change

- `GhostEngine.kt` — reflection-detected availability, progress flow, download
  stub returning null (caller falls back to yt-dlp).
- `GHOST_ENGINE` preference + Settings → Network → "Ghost engine" toggle.
- `python/ghost_bridge.py` — stdlib JSON-RPC control surface (127.0.0.1:7435),
  mirroring Ghost's own aria2-RPC service.

## Stage B — embed the engine (Chaquopy)

1. Add the Chaquopy Gradle plugin to `app/build.gradle.kts`:

   ```kotlin
   plugins { id("com.chaquo.python") version "16.0.0" } // match Ghost's build (Python 3.14)
   android { defaultConfig { ndk { abiFilters += "arm64-v8a" } } }
   chaquopy {
       defaultConfig { version = "3.14" }
       sourceSets { main { pythonDir("src/main/python") } }
   }
   ```

2. Vendor Ghost's engine into `app/src/main/python/`: copy `app/` (models,
   services, config — NOT the Qt view layer) and the packs you want from
   `features/` (http_pack, m3u8_pack, ffmpeg_pack; skip yt_dlp_pack first —
   Seal already extracts via youtubedl-android). Strip Qt imports; Ghost's
   `app/platform/android.py` is the reference for Android projections.

3. Native engines: copy `libnm3u8dlre.so`, `libffmpeg.so`, `libffprobe.so`,
   `libqjs.so` from the Ghost 4.4.5 APK `lib/arm64-v8a/` into
   `app/src/main/jniLibs/arm64-v8a/` (GPL/LGPL notice: record source + commit
   in THIRD_PARTY.md).

4. Implement `EngineHost.start()` in `python/ghost_bridge.py` (construct Ghost's
   `Engine`, wire `TaskService`), then replace the `TODO(stage-b)` in
   `GhostEngine.download()` with the Chaquopy `PyProxy` call.

5. Route at the seam: in `DownloadUtil.kt`, before each
   `YoutubeDL.getInstance().execute(...)` for the media-download call (not the
   metadata call), when `GHOST_ENGINE.getBoolean() && GhostEngine.isAvailable()`:
   run yt-dlp with `--simulate` style extraction only (format URL already
   available from `extractInfo`) and enqueue the URL via `GhostEngine.download()`.

## Stage C — convergence (optional, big)

Replace youtubedl-android entirely: run yt-dlp in library mode inside Chaquopy
(Ghost's ADR-0007 pattern with QuickJS) — one Python runtime, −15 MB, full
Ghost feature parity (BT/ed2k packs, browser-extension RPC).

## Constraints

- Two Python runtimes coexist in Stage B (youtubedl-android's JNI CPython for
  extraction, Chaquopy's for the engine): ~18 MB APK cost, no symbol conflicts
  (separate classloaders/lib names: `libpython.so` vs `libpython3.14.so`).
- Ghost engine expects an asyncio loop with a coroutine runner — the bridge
  owns a background thread + `asyncio.new_event_loop()`, same as Ghost's own
  shell does.
- minSdk 24 vs Chaquopy 16.x: check Chaquopy's current minSdk (21+) — fine.
