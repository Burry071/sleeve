# Blueprint: Packing the Ghost Downloader engine into Seal

Date: 2026-10-24 · Branch: `ghost-engine` · Both projects GPL-3.0 → combined work stays GPL-3.0.

## 1. Verified facts (REA + source, not assumptions)

**Ghost Downloader 4.4.5 (APK + XiaoYouChR/Ghost-Downloader-3):**
- Python engine (asyncio, PySide6 UI on desktop), GPL-3.0.
- Android build = native shell + **Chaquopy + CPython 3.14** (proven by APK contents:
  `libchaquopy_java.so`, `libpython3.14.so`, `assets/chaquopy/*`).
- Engine = task/step model (`app/engine.py`): `TaskService`, `FeatureService`,
  packs in `features/` (`http_pack`, `m3u8_pack`, `ffmpeg_pack`, `yt_dlp_pack`,
  `bittorrent_pack`, `ed2k_pack`, …) shipped as hot-loadable zips (`scripts/build_pack.py`).
- HTTP core: `HttpTaskStep` — 8 subworkers, Range-request segmentation with
  `RangeNotSupportedError` fallback (`features/http_pack/task.py`).
- Native engines in APK `lib/arm64-v8a/`: `libnm3u8dlre.so` (N_m3u8DL-RE HLS/DASH),
  `libqjs.so` (QuickJS-NG), `libffmpeg.so`/`libffprobe.so`, `libkelpie.so` (Go eD2k).
- ADR-0007: yt-dlp runs in **extract-only library mode**; downloading is done by
  Ghost's own steps. Engine also embeds an aria2-compatible RPC service.

**Seal 1.13.1 (APK + JunkFood02/Seal):**
- Kotlin + Compose, GPL-3.0, extraction AND download via
  `io.github.junkfood02.youtubedl-android:0.17.3` (library+ffmpeg+aria2c) with its
  own JNI CPython (`libpython.so` + `libpython.zip.so`).
- Already ships: aria2c external-downloader toggle, `concurrentFragments` option
  (Settings → Network).
- Choke point: every download funnels through
  `DownloadUtil.kt` → `YoutubeDL.getInstance().execute(request, processId, callback)`.

## 2. The core constraint

Seal (JNI CPython) and Ghost (Chaquopy CPython) each embed their own Python
runtime. Naive merging = two runtimes in one APK (~18 MB extra, isolated
classloaders/lib names — works, but wasteful long-term).

## 3. Strategy decision

| | A. Dual runtime (Seal extracts, Ghost downloads) | B. Chaquopy-only (replace youtubedl-android) | C. Port packs to Kotlin |
|---|---|---|---|
| Effort | Low–medium | High | Very high |
| Risk | Low (fallback path intact) | High (rewrite all Seal↔yt-dlp API) | Highest, loses engine parity |
| Feature delta | Segmented HTTP + N_m3u8DL-RE HLS + ffmpeg merge for resolved URLs | Full Ghost parity: BT, ed2k, m3u8, aria2-RPC, browser ext; −18 MB | Only what gets ported |

**Chosen: A now, converge to B later (staged).** Stage A gives the user-visible
win ("yt-dlp only downloads" fixed) with a compile-safe fallback; Stage C/B is
the endgame already proven by Ghost's own Android shell.

## 4. Stage plan

**Stage A — seams (this branch, compiles clean):**
`GhostEngine.kt` (reflection availability, progress flow, stub), `GHOST_ENGINE`
preference + Network settings toggle, `engine-ghost/python/ghost_bridge.py`
(stdlib JSON-RPC control surface), this blueprint + `engine-ghost/README.md`.

**Stage B — embed engine:** Chaquopy plugin (Python 3.14, arm64-v8a), vendor
Ghost's `app/` core + http/m3u8/ffmpeg packs (Qt-free), copy native engines from
the Ghost APK into `jniLibs`, implement `EngineHost.start()` + PyProxy call,
route at the `DownloadUtil` seam (extraction stays yt-dlp; media URLs go to
Ghost). APK grows ~40–55 MB with native engines.

**Stage C — converge:** move extraction to Ghost's `yt_dlp_pack` library mode
(QuickJS), drop youtubedl-android, one Python runtime. Optional BT/ed2k packs.

## 5. Compliance

GPL-3.0 both sides: combined work GPL-3.0, keep copyright headers, add
THIRD_PARTY.md listing Ghost components + versions + source commits. Rename the
fork (now named "Sleeve"); "Ghost Downloader" is the upstream product name.
