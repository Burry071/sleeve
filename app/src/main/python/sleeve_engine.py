"""Sleeve embedded download engine — Ghost-shaped task model, standard library only.

Stage B v0: the task/step lifecycle mirrors Ghost Downloader's engine
(XiaoYouChR/Ghost-Downloader-3, GPL-3.0) so Ghost's packs can be vendored on
top later, while the HTTP core uses http.client threads so it runs on Android
through Chaquopy with zero native dependencies.

Public surface (called from Kotlin via Chaquopy) — every function returns a
JSON string so the bridge has no PyObject conversion ambiguity:
    add_uri(url, options) -> '{"taskId": str}'
    progress(task_id)     -> '{"state", "downloaded", "total", "speedBps", "error"}'
    cancel(task_id)       -> '{"ok": bool}'
    self_test(output_dir) -> '{"ok", "bytes", "seconds", "speedBps", "workers"}'
"""

from __future__ import annotations

import http.client
import json
import os
import ssl
import threading
import time
import urllib.parse
import uuid
from concurrent.futures import ThreadPoolExecutor

WAITING = "waiting"
RUNNING = "running"
COMPLETED = "completed"
ERROR = "error"
CANCELLED = "cancelled"

_CHUNK = 65536
_MAX_REDIRECTS = 6
_SPEED_WINDOW = 3.0


class Cancelled(Exception):
    pass


class SegmentedHttpStep:
    """One HTTP resource downloaded with parallel range requests, resumable.

    Mirrors Ghost's HttpTaskStep contract: probe, segment with N subworkers,
    fall back to a single stream when the server rejects ranges.
    """

    def __init__(self, url, output_path, headers=None, user_agent=None, subworkers=8):
        self.url = url
        self.output_path = output_path
        self.headers = dict(headers or {})
        if user_agent:
            self.headers["User-Agent"] = user_agent
        self.subworkers = max(1, int(subworkers))
        self.state = WAITING
        self.error = ""
        self._downloaded = 0
        self.total = -1
        self._lock = threading.RLock()
        self._samples = []
        self._cancel = threading.Event()

    # -- progress bookkeeping -------------------------------------------------

    def _add_bytes(self, n):
        now = time.monotonic()
        with self._lock:
            self._downloaded += n
            self._samples.append((now, self._downloaded))
            self._samples = [s for s in self._samples if now - s[0] <= _SPEED_WINDOW]

    def _speed(self):
        with self._lock:
            if len(self._samples) < 2:
                return 0
            (t0, b0), (t1, b1) = self._samples[0], self._samples[-1]
            dt = t1 - t0
            return int((b1 - b0) / dt) if dt > 0.2 else 0

    def snapshot(self):
        with self._lock:
            return {
                "state": self.state,
                "downloaded": self._downloaded,
                "total": self.total,
                "speedBps": self._speed(),
                "error": self.error,
            }

    # -- HTTP -----------------------------------------------------------------

    def _connect(self, parsed):
        if parsed.scheme == "https":
            return http.client.HTTPSConnection(
                parsed.hostname, parsed.port or 443, context=ssl.create_default_context(), timeout=30
            )
        return http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=30)

    def _resolve(self):
        """Follow redirects, returning (connection, path_with_query, final_url)."""
        url = self.url
        for _ in range(_MAX_REDIRECTS):
            parsed = urllib.parse.urlsplit(url)
            target = parsed.path or "/"
            if parsed.query:
                target += "?" + parsed.query
            conn = self._connect(parsed)
            conn.request("HEAD", target, headers=self.headers)
            resp = conn.getresponse()
            resp.read()
            if resp.status in (301, 302, 303, 307, 308):
                location = resp.getheader("Location")
                conn.close()
                if not location:
                    raise IOError("redirect without Location")
                url = urllib.parse.urljoin(url, location)
                continue
            return conn, target, url, resp
        raise IOError("too many redirects")

    def probe(self):
        """HEAD probe for size + range support, HEAD-fallback to 1-byte GET."""
        conn, target, url, resp = self._resolve()
        accept_ranges = (resp.getheader("Accept-Ranges") or "").lower() == "bytes"
        length = resp.getheader("Content-Length")
        conn.close()
        if not accept_ranges or length is None:
            conn2, target2, url2, resp2 = self._request_range(url, 0, 0)
            cr = resp2.getheader("Content-Range") or ""
            resp2.read()
            conn2.close()
            if cr.rpartition("/")[2].isdigit():
                accept_ranges = True
                length = cr.rpartition("/")[2]
        self.total = int(length) if length and length.isdigit() else -1
        return self.total, accept_ranges

    def _request_range(self, url, start, end):
        parsed = urllib.parse.urlsplit(url)
        target = parsed.path or "/"
        if parsed.query:
            target += "?" + parsed.query
        conn = self._connect(parsed)
        headers = dict(self.headers)
        headers["Range"] = f"bytes={start}-{end}" if end else f"bytes={start}-"
        conn.request("GET", target, headers=headers)
        return conn, target, url, conn.getresponse()

    def _fetch_range(self, url, start, end, part_path):
        """Fetch [start, end] into part_path; resume if a shorter part exists."""
        done = os.path.getsize(part_path) if os.path.exists(part_path) else 0
        expected = end - start + 1
        if done >= expected:
            self._add_bytes(expected)
            return
        conn, _, resolved, resp = self._request_range(url, start + done, end)
        try:
            if resp.status not in (200, 206):
                raise IOError(f"HTTP {resp.status} for range {start}-{end}")
            with open(part_path, "ab") as fh:
                while True:
                    if self._cancel.is_set():
                        raise Cancelled()
                    chunk = resp.read(_CHUNK)
                    if not chunk:
                        break
                    fh.write(chunk)
                    self._add_bytes(len(chunk))
        finally:
            conn.close()
        got = os.path.getsize(part_path)
        if got != expected:
            raise IOError(f"segment truncated: {got}/{expected}")

    def _stream_single(self, url):
        conn, _, _, resp = self._request_range(url, 0, None)
        try:
            if resp.status not in (200, 206):
                raise IOError(f"HTTP {resp.status}")
            with open(self.output_path, "wb") as fh:
                while True:
                    if self._cancel.is_set():
                        raise Cancelled()
                    chunk = resp.read(_CHUNK)
                    if not chunk:
                        break
                    fh.write(chunk)
                    self._add_bytes(len(chunk))
        finally:
            conn.close()

    def run(self):
        self.state = RUNNING
        try:
            total, accept_ranges = self.probe()
            if self._cancel.is_set():
                raise Cancelled()
            if not accept_ranges or self.total <= 0 or self.subworkers <= 1:
                self._stream_single(self.url)
            else:
                part_dir = self.output_path + ".parts"
                os.makedirs(part_dir, exist_ok=True)
                span = self.total // self.subworkers
                bounds = [
                    (i * span, (self.total - 1) if i == self.subworkers - 1 else ((i + 1) * span - 1))
                    for i in range(self.subworkers)
                ]
                with ThreadPoolExecutor(max_workers=self.subworkers) as pool:
                    futures = [
                        pool.submit(self._fetch_range, self.url, s, e, os.path.join(part_dir, f"{i:03d}.part"))
                        for i, (s, e) in enumerate(bounds)
                    ]
                    for f in futures:
                        f.result()
                with open(self.output_path, "wb") as out:
                    for i in range(self.subworkers):
                        with open(os.path.join(part_dir, f"{i:03d}.part"), "rb") as part:
                            while True:
                                chunk = part.read(_CHUNK)
                                if not chunk:
                                    break
                                out.write(chunk)
                for i in range(self.subworkers):
                    os.remove(os.path.join(part_dir, f"{i:03d}.part"))
                os.rmdir(part_dir)
            self.state = CANCELLED if self._cancel.is_set() else COMPLETED
        except Cancelled:
            self.state = CANCELLED
        except Exception as exc:  # noqa: BLE001 - surfaced to Kotlin as step error
            self.error = str(exc)
            self.state = ERROR

    def cancel(self):
        self._cancel.set()


def _java_to_dict(m):
    """Chaquopy passes Kotlin Maps as java.util.Map objects, which Python's
    dict() cannot iterate. Convert through keySet()/get() explicitly."""
    if m is None:
        return {}
    result = {}
    try:
        for k in m.keySet():
            result[str(k)] = m.get(k)
    except AttributeError:
        try:
            result = dict(m)
        except Exception:
            result = {}
    return result


class Task:
    def __init__(self, url, options):
        opts = _java_to_dict(options)
        self.id = uuid.uuid4().hex[:12]
        self.url = url
        # Defense in depth: the bridge is trusted, but never let a path
        # separator or dot-dot through into the output file name, and keep
        # the worker pool bounded.
        raw_name = str(
            opts.get("fileName")
            or os.path.basename(urllib.parse.urlsplit(url).path)
            or "download.bin"
        )
        safe_name = os.path.basename(raw_name.replace("\\", "/")) or "download.bin"
        self.step = SegmentedHttpStep(
            url,
            output_path=os.path.join(
                str(opts.get("outputDir") or os.getcwd()),
                safe_name,
            ),
            headers=_java_to_dict(opts.get("headers")),
            user_agent=opts.get("userAgent"),
            subworkers=max(1, min(int(opts.get("subworkerCount") or 8), 16)),
        )
        self.thread = threading.Thread(target=self.step.run, daemon=True)

    def start(self):
        self.thread.start()


_ENGINE_LOCK = threading.Lock()
_TASKS: dict[str, Task] = {}


def add_uri(url, options=None):
    if isinstance(options, str):
        opts = json.loads(options) if options else {}
    else:
        opts = _java_to_dict(options)
    task = Task(url, opts)
    with _ENGINE_LOCK:
        _TASKS[task.id] = task
    task.start()
    return json.dumps({"taskId": task.id})


def progress(task_id):
    with _ENGINE_LOCK:
        task = _TASKS.get(task_id)
    if task is None:
        return json.dumps({"state": ERROR, "error": "unknown task"})
    snap = task.step.snapshot()
    snap["taskId"] = task_id
    return json.dumps(snap)


def cancel(task_id):
    with _ENGINE_LOCK:
        task = _TASKS.get(task_id)
    if task is None:
        return json.dumps({"ok": False})
    task.step.cancel()
    return json.dumps({"ok": True})


def self_test(output_dir, url="https://proof.ovh.net/files/10Mb.dat"):
    """Download ~10MB through the full segmented path; used by the settings action.

    The endpoint must support Range requests (206) so the parallel path is
    actually exercised — speed.cloudflare.com/__down ignores Range and would
    silently degrade the test to a single stream.
    """
    started = time.monotonic()
    task = Task(url, {"outputDir": output_dir, "fileName": "sleeve-selftest.bin", "subworkerCount": 8})
    task.step.run()
    seconds = time.monotonic() - started
    snap = task.step.snapshot()
    if os.path.exists(task.step.output_path):
        os.remove(task.step.output_path)
    return json.dumps({
        "ok": snap["state"] == COMPLETED,
        "bytes": snap["downloaded"],
        "seconds": round(seconds, 2),
        "speedBps": snap["speedBps"],
        "workers": task.step.subworkers,
        "state": snap["state"],
        "error": snap["error"],
    })


# ---------------------------------------------------------------------------
# gallery-dl bridge: image/media sites yt-dlp cannot serve (Pixiv, danbooru
# family, imagehosts, ...). The package is embedded under app/src/main/python;
# every one of its non-stdlib dependencies is lazily imported and guarded, so
# requests (already present) is the only hard requirement.
# ---------------------------------------------------------------------------

_GALLERY_MAX_ITEMS = 30
_GALLERY_UA = (
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko)"
    " Chrome/126.0 Mobile Safari/537.36"
)


def gallery_matches(url):
    try:
        from gallery_dl import extractor

        return extractor.find(url) is not None
    except Exception:
        return False


def _gallery_scan(ext, out, pages, depth):
    from gallery_dl.extractor.message import Message

    category = getattr(ext, "category", "")
    for msg in ext:
        if not (isinstance(msg, tuple) and len(msg) >= 2):
            continue
        kind, item_url, kwdict = msg[0], msg[1], msg[2] if len(msg) > 2 else {}
        if kind == Message.Directory:
            continue
        if not isinstance(item_url, str) or not item_url.startswith(("http://", "https://")):
            continue
        if kind == Message.Queue and depth < 1 and len(pages) < 5:
            # Child page (album/post listing): recurse one level.
            pages.append(item_url)
            continue
        if kind != Message.Url:
            continue
        filename = ""
        referer = ""
        if isinstance(kwdict, dict):
            base = kwdict.get("title") or kwdict.get("name") or kwdict.get("id") or ""
            extn = kwdict.get("extension") or ""
            if extn and not str(base).endswith("." + str(extn)):
                filename = "%s.%s" % (base, extn) if base else ""
            elif base:
                filename = str(base)
            headers = kwdict.get("_http_headers")
            if isinstance(headers, dict):
                ref = headers.get("Referer") or headers.get("referer")
                if isinstance(ref, bytes):
                    ref = ref.decode("latin-1", "ignore")
                referer = str(ref or "")
        out.append(
            {
                "url": item_url,
                "filename": filename[-120:],
                "referer": referer,
                "category": category,
            }
        )
        if len(out) >= _GALLERY_MAX_ITEMS:
            return


def gallery_resolve(url, cookies_file=None, max_items=None):
    limit = max_items or _GALLERY_MAX_ITEMS
    try:
        from gallery_dl import extractor
    except Exception as e:
        return json.dumps({"ok": False, "error": "gallery-dl import failed: %s" % e})
    try:
        if cookies_file:
            # The app exports captured WebView logins as a Netscape cookies.txt;
            # hand the same file to gallery-dl (auth-walled sites like Instagram
            # need it). Cookies are domain-scoped inside the jar, so a global
            # assignment is safe.
            from gallery_dl import config as _gdl_config

            _gdl_config.set(("extractor",), "cookies", cookies_file)
        ext = extractor.find(url)
        if ext is None:
            return json.dumps({"ok": False, "error": "no site module matched"})
        out, pages = [], []
        _gallery_scan(ext, out, pages, 0)
        for page in pages:
            if len(out) >= limit:
                break
            try:
                sub = extractor.find(page)
                if sub is not None:
                    _gallery_scan(sub, out, pages, 1)
            except Exception:
                pass
        del out[limit:]
        return json.dumps(
            {
                "ok": True,
                "category": getattr(ext, "category", ""),
                "items": out,
            }
        )
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


# ---------------------------------------------------------------------------
# Generic page sniffer: last-resort pass for pages no extractor claims.
# Pulls the HTML once and reports direct media candidates found in meta tags
# and inline sources (og:video, twitter:player:stream, video/src, m3u8/mpd).
# ---------------------------------------------------------------------------

_SNIFF_RES = (
    ("property", r'<meta[^>]+property=["\']og:video(?::secure_url)?["\'][^>]+content=["\']([^"\']+)', 0),
    ("property", r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:video(?::secure_url)?["\']', 0),
    ("name", r'<meta[^>]+name=["\']twitter:player:stream["\'][^>]+content=["\']([^"\']+)', 0),
    ("source", r"<(?:video|audio|source)[^>]+src=[\"']([^\"']+)[\"']", 0),
    ("link", r'(https?://[^\s"\'<>]+?\.(?:m3u8|mpd)(?:\?[^\s"\'<>]*)?)', 0),
)


def sniff_media(url):
    try:
        import re
        import urllib.request

        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": _GALLERY_UA,
                "Accept": "text/html,application/xhtml+xml",
            },
        )
        with urllib.request.urlopen(req, timeout=20) as resp:
            final_url = resp.geturl()
            charset = resp.headers.get_content_charset() or "utf-8"
            body = resp.read(1 << 20).decode(charset, "ignore")
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})

    base = final_url.rsplit("/", 1)[0] + "/"
    out = []

    def _absolute(u):
        u = u.replace("&amp;", "&").strip()
        if u.startswith("//"):
            return "https:" + u
        if u.startswith("/"):
            m = re.match(r"^(https?://[^/]+)", final_url)
            return m.group(1) + u if m else u
        if not u.startswith(("http://", "https://")):
            return base + u
        return u

    seen = set()
    for _tag, pattern, _flags in _SNIFF_RES:
        for match in re.findall(pattern, body, re.I):
            item = _absolute(match)
            if item in seen or len(item) > 2000:
                continue
            seen.add(item)
            name = urllib.parse.unquote(item.rsplit("?", 1)[0].rsplit("/", 1)[-1])[:120]
            out.append({"url": item, "filename": name, "referer": final_url})
            if len(out) >= _GALLERY_MAX_ITEMS:
                break
        if len(out) >= _GALLERY_MAX_ITEMS:
            break

    return json.dumps({"ok": True, "items": out, "page": final_url})


def gallery_warmup():
    """Import + pattern-scan the whole gallery-dl registry once.

    The first find() compiles ~300 extractor modules (slow on device); doing
    it at app start means the first shared gallery URL feels instant. Also
    surfaces any import error instead of swallowing it.
    """
    import sys as _sys
    try:
        from gallery_dl import extractor
    except Exception as e:
        print("gallery warmup import failed: %r" % (e,), file=_sys.stderr)
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})
    started = time.monotonic()
    try:
        extractor.find("https://warmup.invalid/warmup")
        return json.dumps({"ok": True, "seconds": round(time.monotonic() - started, 2)})
    except Exception as e:
        print("gallery warmup find failed: %r" % (e,), file=_sys.stderr)
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


def instagram_embed(url):
    """Anonymous Instagram fallback: public posts and reels expose their media
    on the embed page, no session needed. Tried after gallery-dl fails so
    private posts still end in the cookies guidance instead of a dead end.
    """
    import re
    import urllib.request

    m = re.search(
        r"instagram\.com/(?:[A-Za-z0-9_.]+/)?(?:p|reels?|tv)/([A-Za-z0-9_-]+)", url
    )
    if not m:
        return json.dumps({"ok": False, "error": "not an instagram post url"})
    code = m.group(1)

    for path in (
        "https://www.instagram.com/reel/%s/embed/captioned/" % code,
        "https://www.instagram.com/p/%s/embed/captioned/" % code,
    ):
        try:
            req = urllib.request.Request(
                path,
                headers={
                    "User-Agent": _GALLERY_UA,
                    "Accept": "text/html,application/xhtml+xml",
                },
            )
            with urllib.request.urlopen(req, timeout=20) as resp:
                body = resp.read(1 << 20).decode("utf-8", "ignore")
        except Exception:
            continue

        target = None
        is_video = False

        video_json = re.search(r'"video_url":"((?:[^"\\]|\\.)*)"', body)
        og_video = re.search(r'property=["\']og:video(?::secure_url)?["\'][^>]+content=["\']([^"\']+)', body)
        raw_video = video_json.group(1) if video_json else og_video.group(1) if og_video else None
        if raw_video:
            try:
                target = json.loads('"%s"' % raw_video)
            except Exception:
                target = raw_video.replace("\u0026", "&").replace("\\/", "/")
            is_video = True

        if not target:
            img = re.search(r'class="EmbeddedMediaImage"[^>]*src="([^"]+)"', body)
            og_img = re.search(r'property=["\']og:image["\'][^>]+content=["\']([^"\']+)', body)
            raw_img = img.group(1) if img else og_img.group(1) if og_img else None
            if raw_img:
                target = raw_img.replace("\u0026", "&").replace("\\/", "/")

        if target and target.startswith("http"):
            ext = "mp4" if is_video else "jpg"
            return json.dumps(
                {
                    "ok": True,
                    "category": "instagram-embed",
                    "items": [
                        {
                            "url": target,
                            "filename": "instagram_%s.%s" % (code, ext),
                            "referer": path,
                        }
                    ],
                }
            )

    return json.dumps(
        {
            "ok": False,
            "error": "no public media on the embed page (private post, deleted, or login required)",
        }
    )
