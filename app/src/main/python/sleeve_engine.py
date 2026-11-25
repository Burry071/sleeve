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
import ipaddress
import json
import os
import re
import shutil
import socket
import ssl
import threading
import time
import urllib.error
import urllib.parse
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed

WAITING = "waiting"
RUNNING = "running"
COMPLETED = "completed"
ERROR = "error"
CANCELLED = "cancelled"

_CHUNK = 65536
_MAX_REDIRECTS = 6
_SPEED_WINDOW = 3.0
_DIRECT_VIDEO_EXT = {
    ".mp4",
    ".m4v",
    ".mov",
    ".webm",
    ".mkv",
    ".avi",
    ".flv",
    ".ts",
    ".m3u8",
    ".mpd",
}


_SENSITIVE_HEADERS = frozenset({
    "authorization",
    "cookie",
    "proxy-authorization",
    "referer",
})

_CONTENT_RANGE_RE = re.compile(r"^bytes\s+(\d+)-(\d+)/(\d+|\*)$", re.IGNORECASE)


def _is_nonstandard_ipv4(host):
    """Return True if `host` uses decimal, hex, octal, or shorthand IPv4 notation."""
    parts = host.split(".")
    if not (1 <= len(parts) <= 4):
        return False
    for part in parts:
        if not part:
            return False
        if part.startswith(("0x", "0X")):
            digits = part[2:]
            if not digits or any(ch not in "0123456789abcdefABCDEF" for ch in digits):
                return False
        elif part.startswith(("0o", "0O")):
            digits = part[2:]
            if not digits or any(ch not in "01234567" for ch in digits):
                return False
        elif part.isdigit():
            continue
        else:
            return False
    return True


def _is_blocked_ip(ip):
    """Return True if `ip` is non-public (loopback, private, link-local, etc.)."""
    if isinstance(ip, ipaddress.IPv6Address):
        if ip.ipv4_mapped is not None:
            return True
        if ip.sixtofour is not None and _is_blocked_ip(ip.sixtofour):
            return True
        if ip.teredo is not None:
            server, client = ip.teredo
            if _is_blocked_ip(server) or _is_blocked_ip(client):
                return True
    return (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_unspecified
        or ip.is_reserved
        or ip.is_multicast
        or not ip.is_global
    )


def _is_safe_http_url(url, allow_local=False):
    """Validate that `url` is http/https and (unless allow_local) targets a public host."""
    if not isinstance(url, str) or not url:
        return False
    if any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in url):
        return False
    try:
        parsed = urllib.parse.urlsplit(url)
        _ = parsed.port
    except ValueError:
        return False
    if parsed.scheme.lower() not in ("http", "https"):
        return False
    host = parsed.hostname
    if not host:
        return False
    if allow_local:
        return True
    norm_host = host.rstrip(".")
    if not norm_host:
        return False
    if (
        norm_host == "localhost"
        or norm_host.endswith(".localhost")
        or norm_host.endswith(".local")
    ):
        return False
    ip_cand = norm_host.split("%", 1)[0]
    try:
        ip = ipaddress.ip_address(ip_cand)
    except ValueError:
        if _is_nonstandard_ipv4(norm_host):
            return False
        return True
    return not _is_blocked_ip(ip)


def _url_origin(url):
    parsed = urllib.parse.urlsplit(url)
    scheme = parsed.scheme.lower()
    host = (parsed.hostname or "").lower()
    default_port = 443 if scheme == "https" else 80
    return (scheme, host, parsed.port or default_port)


def _strip_sensitive_headers(headers):
    return {k: v for k, v in headers.items() if k.lower() not in _SENSITIVE_HEADERS}


class Cancelled(Exception):
    pass


class SegmentedHttpStep:
    """One HTTP resource downloaded with parallel range requests, resumable.

    Mirrors Ghost's HttpTaskStep contract: probe, segment with N subworkers,
    fall back to a single stream when the server rejects ranges.
    """

    def __init__(self, url, output_path, headers=None, user_agent=None, subworkers=8):
        self.url = url
        # Redirect-resolved GET target (set by probe). Range fetches must use
        # the FINAL url: the original can 302 on every request, and a raw
        # http.client GET on a 302 is a non-200 status, not a transfer.
        self._final_url = url
        self._initial_is_public = _is_safe_http_url(url, allow_local=False)
        self._initial_origin = (
            _url_origin(url) if _is_safe_http_url(url, allow_local=True) else None
        )
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
        # Live connections, so cancel() can close them and unblock a reader.
        self._conns = set()
        # Validator captured during probe() for segmented If-Range requests.
        self._if_range = None

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
            conn = http.client.HTTPSConnection(
                parsed.hostname, parsed.port or 443, context=ssl.create_default_context(), timeout=30
            )
        else:
            conn = http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=30)
        with self._lock:
            if self._cancel.is_set():
                try:
                    conn.close()
                except Exception:
                    pass
                raise Cancelled()
            self._conns.add(conn)
        return conn

    def _release(self, conn, resp=None):
        """Close a tracked connection (and its response) exactly once."""
        with self._lock:
            self._conns.discard(conn)
        if resp is not None:
            try:
                resp.close()
            except Exception:
                pass
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass

    def _validate_url(self, url):
        if not _is_safe_http_url(url, allow_local=True):
            raise IOError(f"blocked url: {url}")
        if self._initial_is_public and not _is_safe_http_url(url, allow_local=False):
            raise IOError(f"blocked redirect to non-public host: {url}")

    def _headers_for_url(self, url, base_headers):
        if self._initial_origin is None or _url_origin(url) != self._initial_origin:
            return _strip_sensitive_headers(base_headers)
        return dict(base_headers)

    def _resolve(self):
        """Follow redirects, returning (connection, path_with_query, final_url, resp)."""
        url = self.url
        headers = dict(self.headers)
        for _ in range(_MAX_REDIRECTS):
            self._validate_url(url)
            headers = self._headers_for_url(url, headers)
            parsed = urllib.parse.urlsplit(url)
            target = parsed.path or "/"
            if parsed.query:
                target += "?" + parsed.query
            conn = self._connect(parsed)
            resp = None
            try:
                conn.request("HEAD", target, headers=headers)
                resp = conn.getresponse()
                resp.read(16)
                print(f"[engine] head {parsed.netloc} -> {resp.status}")
                if resp.status in (301, 302, 303, 307, 308):
                    location = resp.getheader("Location")
                    self._release(conn, resp)
                    if not location:
                        raise IOError("redirect without Location")
                    next_url = urllib.parse.urljoin(url, location)
                    self._validate_url(next_url)
                    if _url_origin(next_url) != _url_origin(url):
                        headers = _strip_sensitive_headers(headers)
                    url = next_url
                    continue
                return conn, target, url, resp
            except Exception:
                self._release(conn, resp)
                raise
        raise IOError("too many redirects")

    @staticmethod
    def _extract_if_range(resp):
        etag = (resp.getheader("ETag") or "").strip()
        if etag and not etag.startswith(("W/", "w/")):
            return etag
        last_mod = (resp.getheader("Last-Modified") or "").strip()
        if last_mod:
            return last_mod
        return None

    def probe(self):
        """HEAD probe for size + range support, HEAD-fallback to 1-byte GET."""
        self._if_range = None
        conn, target, url, resp = self._resolve()
        self._final_url = url
        accept_ranges = (resp.getheader("Accept-Ranges") or "").lower() == "bytes"
        length = resp.getheader("Content-Length")
        head_if_range = self._extract_if_range(resp)
        probe_if_range = None
        self._release(conn, resp)
        print(f"[engine] probe final={urllib.parse.urlsplit(url).netloc} status={resp.status} ranges={accept_ranges} len={length}")
        if not accept_ranges or length is None:
            conn2, target2, url2, resp2 = self._request_range(url, 0, 0)
            try:
                cr = resp2.getheader("Content-Range") or ""
                probe_if_range = self._extract_if_range(resp2)
                resp2.read(16)
                if resp2.status == 200 and not cr:
                    accept_ranges = False
                    if length is None:
                        length = resp2.getheader("Content-Length")
                elif cr.rpartition("/")[2].isdigit():
                    accept_ranges = True
                    length = cr.rpartition("/")[2]
            finally:
                self._release(conn2, resp2)
            self._final_url = url2
        self._if_range = head_if_range or probe_if_range
        self.total = int(length) if length and length.isdigit() else -1
        return self.total, accept_ranges

    def _request_range(self, url, start, end):
        """Range GET on `url`, following redirects defensively (a CDN may 302
        on every request even after the probe resolved it once)."""
        redirects = 0
        headers = self._headers_for_url(url, self.headers)
        while True:
            self._validate_url(url)
            headers = self._headers_for_url(url, headers)
            parsed = urllib.parse.urlsplit(url)
            target = parsed.path or "/"
            if parsed.query:
                target += "?" + parsed.query
            conn = self._connect(parsed)
            req_headers = dict(headers)
            req_headers["Range"] = f"bytes={start}-{end}" if end is not None else f"bytes={start}-"
            if self._if_range and end is not None:
                req_headers["If-Range"] = self._if_range
            resp = None
            try:
                conn.request("GET", target, headers=req_headers)
                resp = conn.getresponse()
                if resp.status in (301, 302, 303, 307, 308) and resp.getheader("Location"):
                    location = resp.getheader("Location")
                    resp.read(16)
                    self._release(conn, resp)
                    redirects += 1
                    if redirects >= _MAX_REDIRECTS:
                        raise IOError("too many redirects during range request")
                    next_url = urllib.parse.urljoin(url, location)
                    self._validate_url(next_url)
                    if _url_origin(next_url) != _url_origin(url):
                        headers = _strip_sensitive_headers(headers)
                    print(f"[engine] range GET redirect {redirects} -> {location[:120]}")
                    url = next_url
                    continue
                print(f"[engine] range GET {parsed.netloc} -> {resp.status}")
                return conn, target, url, resp
            except Exception:
                self._release(conn, resp)
                raise

    def _fetch_range(self, url, start, end, part_path):
        """Fetch [start, end] into part_path; resume if a shorter part exists."""
        if self._cancel.is_set():
            raise Cancelled()
        done = os.path.getsize(part_path) if os.path.exists(part_path) else 0
        expected = end - start + 1
        if done >= expected:
            self._add_bytes(expected)
            return
        pos = start + done
        conn, _, resolved, resp = self._request_range(url, pos, end)
        try:
            if self._cancel.is_set():
                raise Cancelled()
            if resp.status == 200:
                raise RuntimeError("server ignored Range (HTTP 200) or resource changed")
            if resp.status != 206:
                raise IOError(f"HTTP {resp.status} for range {start}-{end}")
            cr = resp.getheader("Content-Range")
            if cr:
                m = _CONTENT_RANGE_RE.match(cr.strip())
                if not m or int(m.group(1)) != pos:
                    raise RuntimeError(
                        f"Content-Range start mismatch: expected {pos}, got {cr!r}"
                    )
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
            self._release(conn, resp)
        got = os.path.getsize(part_path)
        if got != expected:
            raise IOError(f"segment truncated: {got}/{expected}")

    def _stream_single(self, url):
        conn, _, _, resp = self._request_range(url, 0, None)
        tmp = self.output_path + ".tmp"
        try:
            if resp.status not in (200, 206):
                raise IOError(f"HTTP {resp.status}")
            with open(tmp, "wb") as fh:
                while True:
                    if self._cancel.is_set():
                        raise Cancelled()
                    chunk = resp.read(_CHUNK)
                    if not chunk:
                        break
                    fh.write(chunk)
                    self._add_bytes(len(chunk))
            # Publish only a complete file: a leftover output from an earlier
            # install may be unwritable, while replacing it needs only a
            # writable directory.
            os.replace(tmp, self.output_path)
        finally:
            self._release(conn, resp)
            if os.path.exists(tmp):
                try:
                    os.remove(tmp)
                except OSError:
                    pass

    def run(self):
        self.state = RUNNING
        aborted_by_error = False
        print(f"[engine] step started: {self.url[:140]}")
        try:
            total, accept_ranges = self.probe()
            print(f"[engine] run probe: total={self.total} ranges={accept_ranges}")
            if self._cancel.is_set():
                raise Cancelled()
            if not accept_ranges or self.total <= 0 or self.subworkers <= 1:
                self._stream_single(self._final_url)
            else:
                workers = min(self.subworkers, self.total)
                if workers <= 1:
                    self._stream_single(self._final_url)
                else:
                    # A fixed ".parts" directory can be left behind by a previous
                    # install (different app UID) and then rejects every write with
                    # EACCES. A per-attempt directory can never be poisoned.
                    part_dir = self.output_path + ".parts-" + uuid.uuid4().hex[:8]
                    os.makedirs(part_dir)
                    tmp = self.output_path + ".tmp"
                    try:
                        span = self.total // workers
                        bounds = [
                            (i * span, (self.total - 1) if i == workers - 1 else ((i + 1) * span - 1))
                            for i in range(workers)
                        ]
                        pool = ThreadPoolExecutor(max_workers=workers)
                        try:
                            futures = [
                                pool.submit(
                                    self._fetch_range,
                                    self._final_url,
                                    s,
                                    e,
                                    os.path.join(part_dir, f"{i:03d}.part"),
                                )
                                for i, (s, e) in enumerate(bounds)
                            ]
                            for f in as_completed(futures):
                                try:
                                    f.result()
                                except Cancelled:
                                    self.cancel()
                                    raise
                                except Exception:
                                    if not self._cancel.is_set():
                                        aborted_by_error = True
                                    self.cancel()
                                    raise
                        finally:
                            pool.shutdown(wait=True, cancel_futures=True)
                        with open(tmp, "wb") as out:
                            for i in range(workers):
                                with open(os.path.join(part_dir, f"{i:03d}.part"), "rb") as part:
                                    while True:
                                        chunk = part.read(_CHUNK)
                                        if not chunk:
                                            break
                                        out.write(chunk)
                        os.replace(tmp, self.output_path)
                    finally:
                        # Clean up scratch files on success, error, or cancel;
                        # failing to remove them must not mask the outcome.
                        if os.path.exists(tmp):
                            try:
                                os.remove(tmp)
                            except OSError:
                                pass
                        try:
                            shutil.rmtree(part_dir, ignore_errors=True)
                        except OSError:
                            pass
            self.state = CANCELLED if self._cancel.is_set() else COMPLETED
        except Cancelled:
            self.state = CANCELLED
        except Exception as exc:  # noqa: BLE001 - surfaced to Kotlin as step error
            if self._cancel.is_set() and not aborted_by_error:
                # The read failure is the cancel landing: cancel() shuts the
                # socket down under a blocked read. Reporting ERROR here would
                # turn a user cancel into a failure card.
                self.state = CANCELLED
            else:
                self.error = str(exc)
                self.state = ERROR

    def cancel(self):
        self._cancel.set()
        # A thread blocked in resp.read() only notices on the next chunk.
        # Shutting the tracked sockets down makes that read fail now; run()
        # maps the failure back to CANCELLED.
        with self._lock:
            conns = list(self._conns)
            self._conns.clear()
        for conn in conns:
            sock = getattr(conn, "sock", None)
            if sock is not None:
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            try:
                conn.close()
            except Exception:
                pass


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
_GALLERY_LOCK = threading.Lock()
_TASKS: dict[str, Task] = {}
# Final verdicts of settled tasks, so a late poll still gets its answer after
# the live Task is evicted. Oldest entries fall out past _TASK_HISTORY_MAX.
_TASK_HISTORY: dict[str, dict] = {}
_TASK_HISTORY_MAX = 8


def _remember_terminal(task_id, snap):
    """Record a settled snapshot, oldest first. Call under _ENGINE_LOCK."""
    _TASK_HISTORY.pop(task_id, None)
    _TASK_HISTORY[task_id] = snap
    while len(_TASK_HISTORY) > _TASK_HISTORY_MAX:
        _TASK_HISTORY.pop(next(iter(_TASK_HISTORY)), None)


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
        with _ENGINE_LOCK:
            settled = _TASK_HISTORY.get(task_id)
        if settled is not None:
            return json.dumps(settled)
        return json.dumps({"state": ERROR, "error": "unknown task"})
    snap = task.step.snapshot()
    snap["taskId"] = task_id
    if snap["state"] in (COMPLETED, ERROR, CANCELLED):
        # A settled task pins a finished thread and its step. Keep only the
        # verdict: nothing else reads the live object again, and a long
        # session would otherwise grow without bound.
        with _ENGINE_LOCK:
            _TASKS.pop(task_id, None)
            _remember_terminal(task_id, snap)
    return json.dumps(snap)


def cancel(task_id):
    settled = None
    with _ENGINE_LOCK:
        task = _TASKS.get(task_id)
        if task is None:
            settled = _TASK_HISTORY.get(task_id)
    if task is None:
        if settled is not None:
            # Already finished: cancelling is a no-op that still succeeds, and
            # the caller gets the final verdict it may have missed.
            return json.dumps({"ok": True, **settled})
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

        # gallery-dl's registry is a lazily built generator; calling find()
        # from two threads (startup warmup vs a share arriving) raises
        # ValueError("generator already executing"). Serialize all registry
        # access behind one lock so a cold start cannot silently report "no
        # match" and skip the configure sheet.
        with _GALLERY_LOCK:
            ext = extractor.find(url)
            if ext is None:
                return False
            # gallery-dl's directlink extractor claims every media URL. A
            # direct video must still reach the configure sheet
            # (quality/audio choice), like any page-based video; direct
            # images can stay on the quick lane.
            if getattr(ext, "category", "") == "directlink":
                path = urllib.parse.urlsplit(url).path
                if os.path.splitext(path)[1].lower() in _DIRECT_VIDEO_EXT:
                    return False
            return True
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
        try:
            from gallery_dl import config as _gdl_config
        except Exception:
            _gdl_config = None
        with _GALLERY_LOCK:
            # gallery-dl's config is process-global, so a path left by an
            # authenticated call would ride into the next anonymous one and
            # send the user's session cookies to a site it was never meant
            # for. Assign on every call, including the empty one that clears.
            if _gdl_config is not None:
                _gdl_config.set(("extractor",), "cookies", cookies_file or None)
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


def _make_safe_redirect_handler():
    import urllib.error
    import urllib.request

    class _SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            resolved = urllib.parse.urljoin(req.full_url, newurl)
            if not _is_safe_http_url(resolved, allow_local=False):
                raise urllib.error.URLError("blocked redirect url")
            new_req = super().redirect_request(req, fp, code, msg, headers, resolved)
            if new_req is not None and _url_origin(req.full_url) != _url_origin(resolved):
                for hdr_name in list(new_req.headers.keys()):
                    if hdr_name.lower() in _SENSITIVE_HEADERS:
                        del new_req.headers[hdr_name]
                for hdr_name in list(new_req.unredirected_hdrs.keys()):
                    if hdr_name.lower() in _SENSITIVE_HEADERS:
                        del new_req.unredirected_hdrs[hdr_name]
            return new_req

    return _SafeRedirectHandler()


def sniff_media(url):
    if not isinstance(url, str) or not _is_safe_http_url(url, allow_local=False):
        return json.dumps({"ok": False, "error": "blocked url"})
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
        opener = urllib.request.build_opener(_make_safe_redirect_handler())
        with opener.open(req, timeout=20) as resp:
            final_url = resp.geturl()
            if not _is_safe_http_url(final_url, allow_local=False):
                return json.dumps({"ok": False, "error": "blocked url"})
            charset = resp.headers.get_content_charset() or "utf-8"
            body = resp.read(1 << 20).decode(charset, "ignore")
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})

    out = []

    def _absolute(u):
        u = u.replace("&amp;", "&").strip()
        if u.startswith("//"):
            return "https:" + u
        return urllib.parse.urljoin(final_url, u)

    seen = set()
    for _tag, pattern, _flags in _SNIFF_RES:
        for match in re.findall(pattern, body, re.I):
            item = _absolute(match)
            if item in seen or len(item) > 2000 or not _is_safe_http_url(item, allow_local=False):
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
        with _GALLERY_LOCK:
            extractor.find("https://warmup.invalid/warmup")
        return json.dumps({"ok": True, "seconds": round(time.monotonic() - started, 2)})
    except Exception as e:
        print("gallery warmup find failed: %r" % (e,), file=_sys.stderr)
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})


_IG_SHORTCODE_ALPHABET = (
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
)


def _shortcode_to_pk(code):
    """Decode an Instagram base64url shortcode to its numeric media pk."""
    if not code:
        return None
    pk = 0
    for ch in code:
        idx = _IG_SHORTCODE_ALPHABET.find(ch)
        if idx < 0:
            return None
        pk = pk * 64 + idx
    return pk


def instagram_embed(url, max_seconds=25.0):
    """Anonymous Instagram resilience ladder, tried after gallery-dl fails so
    private posts still end in the cookies guidance instead of a dead end.
    Lanes, first hit wins:
      1. embed pages (captioned reel/p/tv + plain embed) — video_url/og:video,
         then deeper video_versions / playable_url gql markers
      2. anonymous graphql shortcode query (3 doc_id variants)
      3. anonymous /api/v1/media/<pk>/info/ (www + i.instagram.com)
    """
    import random
    import string
    import urllib.request

    m = re.search(
        r"instagram\.com/(?:[A-Za-z0-9_.]+/)?(?:p|reels?|tv)/([A-Za-z0-9_-]+)", url
    )
    if not m:
        return json.dumps({"ok": False, "error": "not an instagram post url"})
    code = m.group(1)

    deadline = time.monotonic() + max(0.0, float(max_seconds))
    definitive_error = None

    def _remaining():
        return deadline - time.monotonic()

    # Round-robin between a mobile and a desktop Chrome fingerprint: embed and
    # graphql endpoints login-wall some datacenter IPs per-UA.
    ua_rotation = (
        _GALLERY_UA,
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like"
        " Gecko) Chrome/131.0.0.0 Safari/537.36",
    )

    def _fetch(path, headers, data=None, user_agent=None):
        nonlocal definitive_error
        # 2 attempts per url; 400-800ms sleep after the first failure; 1 MiB
        # cap + per-request timeout bounded by the overall monotonic deadline.
        for attempt in range(2):
            if definitive_error is not None:
                return None
            rem = _remaining()
            if rem <= 0:
                return None
            ua = user_agent or ua_rotation[attempt % len(ua_rotation)]
            try:
                req = urllib.request.Request(
                    path, data=data, headers=dict(headers, **{"User-Agent": ua})
                )
                with urllib.request.urlopen(req, timeout=min(20.0, rem)) as resp:
                    return resp.read(1 << 20).decode("utf-8", "ignore")
            except urllib.error.HTTPError as e:
                code_http = getattr(e, "code", None)
                try:
                    e.close()
                except Exception:
                    pass
                if code_http in (401, 404):
                    definitive_error = code_http
                    return None
                if attempt == 0:
                    sleep_s = min(random.uniform(0.4, 0.8), max(0.0, _remaining()))
                    if sleep_s <= 0:
                        return None
                    time.sleep(sleep_s)
            except Exception:
                if attempt == 0:
                    sleep_s = min(random.uniform(0.4, 0.8), max(0.0, _remaining()))
                    if sleep_s <= 0:
                        return None
                    time.sleep(sleep_s)
        return None

    def _unescape(raw):
        # Embed pages are HTML: attribute values carry &amp; for every
        # ampersand in a signed CDN query (sniff_media's _absolute does the
        # same). Without this the queued URL 403s on Instagram's CDN.
        try:
            return json.loads('"%s"' % raw).replace("&amp;", "&")
        except Exception:
            return raw.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")

    def _emit(target, is_video, referer):
        ext = "mp4" if is_video else "jpg"
        return json.dumps(
            {
                "ok": True,
                "category": "instagram-embed",
                "items": [
                    {
                        "url": target,
                        "filename": "instagram_%s.%s" % (code, ext),
                        "referer": referer,
                    }
                ],
            }
        )

    # -- lane 1: embed pages ---------------------------------------------------
    # Order matters: video_url/og:video first (same markers as the parse test),
    # then the progressive gql blobs, then the poster-image fallback for image
    # posts.
    def _scan_embed(body):
        target = None
        is_video = False

        video_json = re.search(r'"video_url":"((?:[^"\\]|\\.)*)"', body)
        og_video = re.search(
            r'property=["\']og:video(?::secure_url)?["\'][^>]+content=["\']([^"\']+)',
            body,
        )
        raw_video = (
            video_json.group(1) if video_json else (og_video.group(1) if og_video else None)
        )
        if raw_video:
            cand = _unescape(raw_video)
            if isinstance(cand, str) and cand.startswith("http"):
                target, is_video = cand, True

        if not target:
            # gql_data ships progressive mp4s (higher quality than the 640px
            # video_url): first "url" inside ~600 chars after each marker.
            for vm in re.finditer(r'"video_versions":\[', body):
                um = re.search(
                    r'"url":"((?:[^"\\]|\\.)*)"', body[vm.end() : vm.end() + 600]
                )
                if um:
                    cand = _unescape(um.group(1))
                    if isinstance(cand, str) and cand.startswith("http"):
                        target, is_video = cand, True
                        break

        if not target:
            pu = re.search(r'"playable_url":"((?:[^"\\]|\\.)*)"', body)
            if pu:
                cand = _unescape(pu.group(1))
                if isinstance(cand, str) and cand.startswith("http"):
                    target, is_video = cand, True

        if not target:
            # A gql payload (shortcode_media / gql_data) marks a video post
            # even when the stream regexes missed its url — do not serve the
            # poster frame as the media; fall through to the deeper lanes.
            if re.search(r'"(?:shortcode_media|gql_data)"', body) and (
                '"is_video":true' in body
                or '"video_duration"' in body
                or '"product_type":"clips"' in body
            ):
                return None, False
            # Image posts (and video poster fallback, unchanged order).
            img = re.search(r'class="EmbeddedMediaImage"[^>]*src="([^"]+)"', body)
            og_img = re.search(
                r'property=["\']og:image["\'][^>]+content=["\']([^"\']+)', body
            )
            raw_img = img.group(1) if img else (og_img.group(1) if og_img else None)
            if raw_img:
                cand = _unescape(raw_img)
                if isinstance(cand, str) and cand.startswith("http"):
                    target = cand

        return target, is_video

    embed_headers = {
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "en-US,en;q=0.9",
        "Referer": "https://www.instagram.com/",
        "Sec-Fetch-Dest": "iframe",
        "Sec-Fetch-Mode": "navigate",
        "Sec-Fetch-Site": "cross-site",
    }
    for path in (
        "https://www.instagram.com/reel/%s/embed/captioned/" % code,
        "https://www.instagram.com/p/%s/embed/captioned/" % code,
        "https://www.instagram.com/tv/%s/embed/captioned/" % code,
        "https://www.instagram.com/p/%s/embed/" % code,
    ):
        if definitive_error is not None or _remaining() <= 0:
            break
        body = _fetch(path, embed_headers)
        if not body:
            continue
        target, is_video = _scan_embed(body)
        if isinstance(target, str) and target.startswith("http"):
            return _emit(target, is_video, path)

    # -- lane 2: anonymous graphql shortcode query ------------------------------
    lsd = "".join(random.choices(string.ascii_letters + string.digits, k=20))
    csrf = uuid.uuid4().hex  # 32 hex chars

    def _media_from_node(node):
        """video_versions (node + item) → playable_url → display_url/image."""
        if not isinstance(node, dict):
            return None, False
        item = node.get("item") if isinstance(node.get("item"), dict) else {}
        for vv in (node.get("video_versions"), item.get("video_versions")):
            if isinstance(vv, list) and vv:
                u = vv[0].get("url") if isinstance(vv[0], dict) else None
                if isinstance(u, str) and u:
                    return u, True
        u = node.get("playable_url") or item.get("playable_url")
        if isinstance(u, str) and u:
            return u, True
        for source in (node, item):
            u = source.get("display_url")
            if isinstance(u, str) and u:
                return u, False
            iv2 = source.get("image_versions2")
            candidates = iv2.get("candidates") if isinstance(iv2, dict) else None
            if isinstance(candidates, list) and candidates:
                u = candidates[0].get("url") if isinstance(candidates[0], dict) else None
                if isinstance(u, str) and u:
                    return u, False
        return None, False

    def _gql_nodes(payload):
        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(data, dict):
            return []
        nodes = []
        detail = data.get("xdt_api__v1__media__detail")
        if isinstance(detail, list):
            for nd in detail:
                if isinstance(nd, dict):
                    nodes.append(
                        nd["media"] if isinstance(nd.get("media"), dict) else nd
                    )
        elif isinstance(detail, dict):
            nodes.append(detail["media"] if isinstance(detail.get("media"), dict) else detail)
        shortcode_media = data.get("shortcode_media")
        if isinstance(shortcode_media, dict):
            nodes.append(shortcode_media)
        return nodes

    gql_headers = {
        "x-ig-app-id": "936619743392459",
        "x-fb-lsd": lsd,
        "x-asbd-id": "129477",
        "x-csrftoken": csrf,
        "Content-Type": "application/x-www-form-urlencoded",
        "Referer": "https://www.instagram.com/p/%s/" % code,
    }
    for doc_id in (24368985919464652, 8845758582119845, 9510064595728286):
        if definitive_error is not None or _remaining() <= 0:
            break
        data = urllib.parse.urlencode(
            {"variables": json.dumps({"shortcode": code}), "doc_id": doc_id}
        ).encode("utf-8")
        body = _fetch("https://www.instagram.com/graphql/query", gql_headers, data)
        if not body:
            continue
        try:
            payload = json.loads(body)
        except Exception:
            continue
        for node in _gql_nodes(payload):
            target, is_video = _media_from_node(node)
            if isinstance(target, str) and target.startswith("http"):
                return _emit(target, is_video, "https://www.instagram.com/")

    # -- lane 3: anonymous /api/v1/media info ------------------------------------
    pk = _shortcode_to_pk(code)
    if pk is not None and definitive_error is None and _remaining() > 0:
        app_headers = {
            "x-ig-app-id": "936619743392459",
            "x-asbd-id": "359341",
            "x-requested-with": "XMLHttpRequest",
        }
        app_ua = (
            "Instagram 275.0.0.27.301 Android (33/13; 420dpi; 1080x2400; samsung;"
            " SM-S911B; e1q; samsung; en_US; 345634560)"
        )
        for pattern in (
            "https://www.instagram.com/api/v1/media/%s/info/",
            "https://i.instagram.com/api/v1/media/%s/info/",
        ):
            if definitive_error is not None or _remaining() <= 0:
                break
            body = _fetch(pattern % pk, app_headers, user_agent=app_ua)
            if not body:
                continue
            try:
                payload = json.loads(body)
            except Exception:
                continue
            media_items = payload.get("items") if isinstance(payload, dict) else None
            if not isinstance(media_items, list) or not media_items:
                continue
            target, is_video = _media_from_node(
                media_items[0] if isinstance(media_items[0], dict) else {}
            )
            if isinstance(target, str) and target.startswith("http"):
                return _emit(target, is_video, "https://www.instagram.com/")

    return json.dumps(
        {
            "ok": False,
            "error": "instagram: all public access lanes failed (embed, graphql, api) —"
            " the post is private, deleted, or this network requires login",
        }
    )
