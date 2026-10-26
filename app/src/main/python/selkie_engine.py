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


class Task:
    def __init__(self, url, options):
        self.id = uuid.uuid4().hex[:12]
        self.url = url
        self.step = SegmentedHttpStep(
            url,
            output_path=os.path.join(
                options.get("outputDir") or os.getcwd(),
                options.get("fileName") or os.path.basename(urllib.parse.urlsplit(url).path) or "download.bin",
            ),
            headers=options.get("headers") or {},
            user_agent=options.get("userAgent"),
            subworkers=options.get("subworkerCount") or 8,
        )
        self.thread = threading.Thread(target=self.step.run, daemon=True)

    def start(self):
        self.thread.start()


_ENGINE_LOCK = threading.Lock()
_TASKS: dict[str, Task] = {}


def add_uri(url, options=None):
    opts = dict(options or {})
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
    task = Task(url, {"outputDir": output_dir, "fileName": "selkie-selftest.bin", "subworkerCount": 8})
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
