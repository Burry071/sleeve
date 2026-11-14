#!/usr/bin/env python3
"""Unit tests for app/src/main/python/sleeve_engine.py.

Three lifecycle promises: a settled task is evicted so a long session cannot
grow without bound, cancel() wakes a blocked socket read instead of waiting for
the next chunk, and gallery-dl's process-global cookie path is reset on every
resolve so an authenticated call cannot leak into an anonymous one.
"""

from __future__ import annotations

import json
import os
import socket
import sys
import tempfile
import threading
import time
import types
import unittest
import urllib.parse
from unittest import mock

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_ROOT, "app", "src", "main", "python"))

import sleeve_engine


class FakeSock:
    def __init__(self):
        self.shutdown_calls = []

    def shutdown(self, how):
        self.shutdown_calls.append(how)


class TrackedConn:
    def __init__(self, with_sock=True):
        self.sock = FakeSock() if with_sock else None
        self.close_calls = 0
        self.closed = threading.Event()

    def close(self):
        self.close_calls += 1
        self.closed.set()


class BlockingResp:
    """read() blocks like a socket waiting on the next chunk of the body."""

    status = 200

    def __init__(self, conn):
        self.conn = conn
        self.reading = threading.Event()

    def getheader(self, name):
        return None

    def read(self, n=-1):
        self.reading.set()
        self.conn.closed.wait(5.0)
        raise OSError("socket closed")

    def close(self):
        pass


class SocketCancelTest(unittest.TestCase):
    def make_step(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(tmp, ignore_errors=True))
        return sleeve_engine.SegmentedHttpStep(
            "https://example.invalid/file.bin", os.path.join(tmp, "file.bin")
        )

    def test_connect_tracks_the_connection(self):
        step = self.make_step()
        conn = TrackedConn()

        with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", return_value=conn):
            returned = step._connect(urllib.parse.urlsplit("https://example.invalid/x"))

        self.assertIs(conn, returned)
        self.assertEqual({conn}, step._conns)

        step.cancel()

        self.assertEqual([socket.SHUT_RDWR], conn.sock.shutdown_calls)
        self.assertEqual(0, len(step._conns))
        self.assertTrue(conn.closed.is_set())

    def test_cancel_wakes_a_read_blocked_mid_body(self):
        step = self.make_step()
        conn = TrackedConn()
        resp = BlockingResp(conn)
        step.probe = lambda: (-1, False)  # force the single-stream lane
        step._request_range = lambda url, start, end: (conn, "/x", url, resp)
        step._conns.add(conn)

        worker = threading.Thread(target=step.run, daemon=True)
        worker.start()
        self.assertTrue(resp.reading.wait(2.0), "the fake reader never blocked")
        started = time.monotonic()
        step.cancel()
        worker.join(2.0)

        self.assertFalse(worker.is_alive(), "cancel did not wake the blocked read")
        self.assertLess(time.monotonic() - started, 2.0)
        self.assertEqual(sleeve_engine.CANCELLED, step.state)
        self.assertEqual("", step.error)


class FakeStep:
    def __init__(self, state):
        self.state = state
        self.cancel_calls = 0

    def snapshot(self):
        return {
            "state": self.state,
            "downloaded": 3,
            "total": 9,
            "speedBps": 0,
            "error": "",
        }

    def cancel(self):
        self.cancel_calls += 1


class FakeTask:
    def __init__(self, state):
        self.step = FakeStep(state)


class EngineRegistryTest(unittest.TestCase):
    def setUp(self):
        with sleeve_engine._ENGINE_LOCK:
            self.saved_tasks = dict(sleeve_engine._TASKS)
            self.saved_history = dict(sleeve_engine._TASK_HISTORY)
            sleeve_engine._TASKS.clear()
            sleeve_engine._TASK_HISTORY.clear()

    def tearDown(self):
        with sleeve_engine._ENGINE_LOCK:
            sleeve_engine._TASKS.clear()
            sleeve_engine._TASKS.update(self.saved_tasks)
            sleeve_engine._TASK_HISTORY.clear()
            sleeve_engine._TASK_HISTORY.update(self.saved_history)

    def register(self, task_id, state):
        with sleeve_engine._ENGINE_LOCK:
            sleeve_engine._TASKS[task_id] = FakeTask(state)

    def test_a_settled_task_is_evicted_after_its_verdict_is_read(self):
        self.register("t1", sleeve_engine.COMPLETED)

        snap = json.loads(sleeve_engine.progress("t1"))

        self.assertEqual("t1", snap["taskId"])
        self.assertEqual(sleeve_engine.COMPLETED, snap["state"])
        self.assertNotIn("t1", sleeve_engine._TASKS)
        self.assertIn("t1", sleeve_engine._TASK_HISTORY)

    def test_a_running_task_stays_registered(self):
        self.register("t2", sleeve_engine.RUNNING)

        sleeve_engine.progress("t2")

        self.assertIn("t2", sleeve_engine._TASKS)
        self.assertNotIn("t2", sleeve_engine._TASK_HISTORY)

    def test_a_late_poll_after_eviction_still_gets_the_verdict(self):
        self.register("t3", sleeve_engine.ERROR)
        first = json.loads(sleeve_engine.progress("t3"))

        second = json.loads(sleeve_engine.progress("t3"))

        self.assertEqual(first, second)
        self.assertEqual(sleeve_engine.ERROR, second["state"])

    def test_the_history_keeps_only_the_newest_entries(self):
        for i in range(10):
            self.register("task-%d" % i, sleeve_engine.COMPLETED)
            sleeve_engine.progress("task-%d" % i)

        self.assertEqual(sleeve_engine._TASK_HISTORY_MAX, len(sleeve_engine._TASK_HISTORY))
        self.assertEqual(
            ["task-%d" % i for i in range(2, 10)],
            list(sleeve_engine._TASK_HISTORY.keys()),
        )

    def test_cancel_after_eviction_succeeds_with_the_final_state(self):
        self.register("t4", sleeve_engine.CANCELLED)
        sleeve_engine.progress("t4")

        reply = json.loads(sleeve_engine.cancel("t4"))

        self.assertTrue(reply["ok"])
        self.assertEqual(sleeve_engine.CANCELLED, reply["state"])

    def test_cancel_of_an_unknown_task_still_fails(self):
        self.assertEqual({"ok": False}, json.loads(sleeve_engine.cancel("nope")))

    def test_progress_of_an_unknown_task_is_an_error(self):
        snap = json.loads(sleeve_engine.progress("nope"))
        self.assertEqual(sleeve_engine.ERROR, snap["state"])
        self.assertEqual("unknown task", snap["error"])


class GalleryCookieResetTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        fake = types.ModuleType("gallery_dl")
        fake.config = types.SimpleNamespace(
            set=lambda path, key, value: self.calls.append((path, key, value))
        )
        fake.extractor = types.SimpleNamespace(find=lambda url: None)
        self.saved = sys.modules.get("gallery_dl")
        sys.modules["gallery_dl"] = fake

    def tearDown(self):
        if self.saved is not None:
            sys.modules["gallery_dl"] = self.saved
        else:
            sys.modules.pop("gallery_dl", None)

    def test_an_anonymous_call_clears_the_previous_session(self):
        first = json.loads(
            sleeve_engine.gallery_resolve("https://example.invalid/a", "/tmp/cookies.txt")
        )
        second = json.loads(sleeve_engine.gallery_resolve("https://example.invalid/b", ""))

        self.assertEqual({"ok": False, "error": "no site module matched"}, first)
        self.assertEqual({"ok": False, "error": "no site module matched"}, second)
        self.assertEqual(
            [
                (("extractor",), "cookies", "/tmp/cookies.txt"),
                (("extractor",), "cookies", None),
            ],
            self.calls,
        )

    def test_every_call_sets_the_path_exactly_once(self):
        sleeve_engine.gallery_resolve("https://example.invalid/c")
        self.assertEqual([(("extractor",), "cookies", None)], self.calls)


if __name__ == "__main__":
    unittest.main()
