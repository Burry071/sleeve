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


class FakeHttpResp:
    def __init__(self, status=200, headers=None, body=b"", max_read=None):
        self.status = status
        self._headers = {k.lower(): v for k, v in (headers or {}).items()}
        self._body = body
        self._pos = 0
        self._max_read = max_read
        self.read_args = []
        self.closed = False

    def getheader(self, name, default=None):
        return self._headers.get(name.lower(), default)

    def read(self, n=-1):
        self.read_args.append(n)
        if self._max_read is not None and (n is None or n < 0 or n > self._max_read):
            raise AssertionError(f"unbounded or oversized read({n!r})")
        if n is None or n < 0:
            chunk = self._body[self._pos :]
            self._pos = len(self._body)
            return chunk
        chunk = self._body[self._pos : self._pos + n]
        self._pos += len(chunk)
        return chunk

    def close(self):
        self.closed = True


class RecordingConn:
    def __init__(self, host, port, scheme, responses, recorded):
        self.host = host
        self.port = port
        self.scheme = scheme
        self._responses = responses
        self._recorded = recorded
        self.sock = None
        self.closed = False

    def request(self, method, target, headers=None):
        self._recorded.append(
            {
                "scheme": self.scheme,
                "host": self.host,
                "port": self.port,
                "method": method,
                "target": target,
                "headers": dict(headers or {}),
            }
        )

    def getresponse(self):
        return self._responses.pop(0)

    def close(self):
        self.closed = True


class UrlSafetyTest(unittest.TestCase):
    def test_allows_public_http_and_https_urls(self):
        for url in (
            "https://example.com/file.mp4",
            "http://cdn.example.org:8080/a/b?x=1",
            "https://93.184.216.34/video.mp4",
            "https://[2606:4700:4700::1111]/media",
        ):
            with self.subTest(url=url):
                self.assertTrue(sleeve_engine._is_safe_http_url(url, allow_local=False))

    def test_rejects_non_http_schemes(self):
        for url in (
            "file:///etc/passwd",
            "FILE:///etc/passwd",
            "ftp://example.com/a.bin",
            "gopher://example.com/1",
            "data:text/html,<h1>hi</h1>",
            "javascript:alert(1)",
            "",
            "https://example.com/a\r\nX-Bad: 1",
        ):
            with self.subTest(url=url):
                self.assertFalse(sleeve_engine._is_safe_http_url(url, allow_local=False))
                self.assertFalse(sleeve_engine._is_safe_http_url(url, allow_local=True))

    def test_rejects_private_loopback_link_local_and_obfuscated_ips(self):
        blocked = (
            "http://127.0.0.1/",
            "http://127.0.0.2:8080/secret",
            "http://[::1]/",
            "http://[::]/",
            "http://0.0.0.0/",
            "http://169.254.169.254/latest/meta-data/",
            "http://[fe80::1]/",
            "http://[fe80::1%25eth0]/",
            "http://10.0.0.1/",
            "http://172.16.0.1/",
            "http://172.31.255.255/",
            "http://192.168.1.1/",
            "http://[fc00::1]/",
            "http://[fd12:3456::1]/",
            "http://100.64.0.1/",
            "http://240.0.0.1/",
            "http://224.0.0.1/",
            "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:169.254.169.254]/",
            "http://[::ffff:8.8.8.8]/",
            "http://2130706433/",
            "http://0x7f000001/",
            "http://0177.0.0.1/",
            "http://0o177.0.0.1/",
            "http://127.1/",
            "http://localhost/",
            "http://localhost./",
            "http://api.localhost/",
            "http://nas.local/video.mp4",
        )
        for url in blocked:
            with self.subTest(url=url):
                self.assertFalse(sleeve_engine._is_safe_http_url(url, allow_local=False))

    def test_allow_local_permits_loopback_http_only(self):
        self.assertTrue(sleeve_engine._is_safe_http_url("http://127.0.0.1:8080/a", allow_local=True))
        self.assertFalse(sleeve_engine._is_safe_http_url("file:///etc/passwd", allow_local=True))


class ProbeRangeTest(unittest.TestCase):
    def _make_step(self, url="https://cdn.example.com/file.bin"):
        tmp = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(tmp, ignore_errors=True))
        return sleeve_engine.SegmentedHttpStep(url, os.path.join(tmp, "file.bin"))

    def test_probe_sends_bytes_0_to_0_when_head_omits_length_and_ranges(self):
        step = self._make_step()
        recorded = []
        responses = [
            FakeHttpResp(status=200, headers={}),
            FakeHttpResp(
                status=206,
                headers={"Content-Range": "bytes 0-0/1048576"},
                body=b"x",
                max_read=16,
            ),
        ]

        def fake_https(host, port, **kwargs):
            return RecordingConn(host, port, "https", responses, recorded)

        with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", side_effect=fake_https):
            total, accept_ranges = step.probe()

        self.assertEqual(1048576, total)
        self.assertTrue(accept_ranges)
        self.assertEqual("HEAD", recorded[0]["method"])
        self.assertEqual("GET", recorded[1]["method"])
        self.assertEqual("bytes=0-0", recorded[1]["headers"]["Range"])

    def test_probe_caps_body_drain_when_server_ignores_range_with_200(self):
        step = self._make_step()
        recorded = []
        huge_resp = FakeHttpResp(
            status=200,
            headers={"Content-Length": "1073741824"},
            body=b"A" * 65536,
            max_read=16,
        )
        responses = [
            FakeHttpResp(status=200, headers={}),
            huge_resp,
        ]

        def fake_https(host, port, **kwargs):
            return RecordingConn(host, port, "https", responses, recorded)

        with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", side_effect=fake_https):
            total, accept_ranges = step.probe()

        self.assertEqual("bytes=0-0", recorded[1]["headers"]["Range"])
        self.assertEqual([16], huge_resp.read_args)
        self.assertTrue(huge_resp.closed)
        self.assertFalse(accept_ranges)
        self.assertEqual(1073741824, total)


class RedirectSafetyTest(unittest.TestCase):
    def _make_step(self, url, headers=None):
        tmp = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(tmp, ignore_errors=True))
        return sleeve_engine.SegmentedHttpStep(
            url,
            os.path.join(tmp, "file.bin"),
            headers=headers,
            user_agent="SleeveTest/1.0",
        )

    def test_cross_origin_redirect_strips_sensitive_headers_in_resolve_and_range(self):
        step = self._make_step(
            "https://origin.example.com/start",
            headers={
                "Authorization": "Bearer secret",
                "Cookie": "sid=abc",
                "Proxy-Authorization": "Basic xyz",
                "Referer": "https://origin.example.com/page",
            },
        )
        recorded = []
        responses = [
            FakeHttpResp(status=302, headers={"Location": "https://cdn.other.example/file.bin"}),
            FakeHttpResp(status=200, headers={"Accept-Ranges": "bytes", "Content-Length": "100"}),
            FakeHttpResp(status=206, headers={"Content-Range": "bytes 0-9/100"}, body=b"0123456789"),
        ]

        def fake_https(host, port, **kwargs):
            return RecordingConn(host, port, "https", responses, recorded)

        with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", side_effect=fake_https):
            total, accept_ranges = step.probe()
            conn, _, _, resp = step._request_range(step._final_url, 0, 9)
            step._release(conn, resp)

        self.assertEqual((100, True), (total, accept_ranges))
        # Hop 1 to original origin still has credentials.
        self.assertEqual("Bearer secret", recorded[0]["headers"]["Authorization"])
        self.assertEqual("sid=abc", recorded[0]["headers"]["Cookie"])
        self.assertEqual("https://origin.example.com/page", recorded[0]["headers"]["Referer"])
        # Hop 2 (redirected HEAD) and Hop 3 (range GET on _final_url) must strip all sensitive headers.
        for req in (recorded[1], recorded[2]):
            self.assertEqual("cdn.other.example", req["host"])
            lowered = {k.lower() for k in req["headers"]}
            self.assertNotIn("authorization", lowered)
            self.assertNotIn("cookie", lowered)
            self.assertNotIn("proxy-authorization", lowered)
            self.assertNotIn("referer", lowered)
            self.assertEqual("SleeveTest/1.0", req["headers"]["User-Agent"])

    def test_https_to_http_downgrade_strips_sensitive_headers(self):
        step = self._make_step(
            "https://media.example.com/start",
            headers={
                "Authorization": "Bearer secret",
                "Cookie": "sid=abc",
                "Referer": "https://media.example.com/",
            },
        )
        recorded = []
        responses = [
            FakeHttpResp(status=302, headers={"Location": "http://media.example.com/start"}),
            FakeHttpResp(status=206, headers={"Content-Range": "bytes 0-3/4"}, body=b"abcd"),
        ]

        def fake_https(host, port, **kwargs):
            return RecordingConn(host, port, "https", responses, recorded)

        def fake_http(host, port, **kwargs):
            return RecordingConn(host, port, "http", responses, recorded)

        with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", side_effect=fake_https), \
             mock.patch.object(sleeve_engine.http.client, "HTTPConnection", side_effect=fake_http):
            conn, _, final_url, resp = step._request_range(step.url, 0, 3)
            step._release(conn, resp)

        self.assertEqual("http://media.example.com/start", final_url)
        self.assertEqual("https", recorded[0]["scheme"])
        self.assertIn("Authorization", recorded[0]["headers"])
        self.assertEqual("http", recorded[1]["scheme"])
        lowered = {k.lower() for k in recorded[1]["headers"]}
        self.assertNotIn("authorization", lowered)
        self.assertNotIn("cookie", lowered)
        self.assertNotIn("referer", lowered)

    def test_same_origin_redirect_preserves_headers(self):
        step = self._make_step(
            "https://media.example.com/a",
            headers={"Authorization": "Bearer secret", "Cookie": "sid=abc"},
        )
        recorded = []
        responses = [
            FakeHttpResp(status=302, headers={"Location": "/b"}),
            FakeHttpResp(status=200, headers={"Accept-Ranges": "bytes", "Content-Length": "10"}),
        ]

        def fake_https(host, port, **kwargs):
            return RecordingConn(host, port, "https", responses, recorded)

        with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", side_effect=fake_https):
            step.probe()

        self.assertEqual("Bearer secret", recorded[1]["headers"]["Authorization"])
        self.assertEqual("sid=abc", recorded[1]["headers"]["Cookie"])

    def test_public_to_private_and_file_redirects_are_blocked(self):
        targets = (
            "file:///etc/passwd",
            "ftp://example.com/file.bin",
            "http://127.0.0.1/admin",
            "http://[::1]/admin",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.1/internal",
            "http://192.168.1.1/router",
            "http://172.16.0.1/internal",
            "http://localhost/secret",
            "http://2130706433/secret",
            "http://[::ffff:127.0.0.1]/secret",
        )
        for bad_target in targets:
            with self.subTest(method="resolve", target=bad_target):
                step = self._make_step("https://public.example.com/dl")
                recorded = []
                responses = [FakeHttpResp(status=302, headers={"Location": bad_target})]

                def fake_https(host, port, **kwargs):
                    return RecordingConn(host, port, "https", responses, recorded)

                with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", side_effect=fake_https), \
                     mock.patch.object(sleeve_engine.http.client, "HTTPConnection", side_effect=AssertionError("must not connect")):
                    with self.assertRaises(OSError):
                        step._resolve()
                self.assertEqual(1, len(recorded))

            with self.subTest(method="request_range", target=bad_target):
                step = self._make_step("https://public.example.com/dl")
                recorded = []
                responses = [FakeHttpResp(status=302, headers={"Location": bad_target})]

                def fake_https(host, port, **kwargs):
                    return RecordingConn(host, port, "https", responses, recorded)

                with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", side_effect=fake_https), \
                     mock.patch.object(sleeve_engine.http.client, "HTTPConnection", side_effect=AssertionError("must not connect")):
                    with self.assertRaises(OSError):
                        step._request_range(step.url, 0, 10)
                self.assertEqual(1, len(recorded))


class SniffMediaSecurityTest(unittest.TestCase):
    def test_rejects_file_and_private_urls_without_opening_network_or_files(self):
        blocked = (
            "file:///etc/passwd",
            "data:text/html,<video src='https://example.com/a.mp4'>",
            "ftp://example.com/page.html",
            "http://127.0.0.1/",
            "http://[::1]/",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.1/",
            "http://192.168.1.1/",
            "http://172.16.0.1/",
            "http://localhost/",
            "http://2130706433/",
            "http://[::ffff:127.0.0.1]/",
        )
        with mock.patch("urllib.request.build_opener", side_effect=AssertionError("must not open blocked url")):
            for url in blocked:
                with self.subTest(url=url):
                    res = json.loads(sleeve_engine.sniff_media(url))
                    self.assertFalse(res["ok"])
                    self.assertIn("blocked", res["error"].lower())

    def test_safe_redirect_handler_blocks_private_and_file_and_strips_headers(self):
        import urllib.error
        import urllib.request

        handler = sleeve_engine._make_safe_redirect_handler()
        req = urllib.request.Request(
            "https://origin.example.com/page",
            headers={
                "Authorization": "Bearer secret",
                "Cookie": "sid=1",
                "Proxy-Authorization": "Basic abc",
                "Referer": "https://origin.example.com/",
                "User-Agent": "SleeveTest",
            },
        )
        for bad in (
            "file:///etc/passwd",
            "ftp://example.com/a",
            "http://127.0.0.1/secret",
            "http://[::1]/secret",
            "http://169.254.169.254/latest/meta-data/",
            "http://localhost/admin",
        ):
            with self.subTest(bad=bad):
                with self.assertRaises(urllib.error.URLError):
                    handler.redirect_request(req, None, 302, "Found", {}, bad)

        new_req = handler.redirect_request(
            req, None, 302, "Found", {}, "https://cdn.other.example/page"
        )
        self.assertEqual("https://cdn.other.example/page", new_req.full_url)
        lowered = {k.lower() for k in new_req.headers} | {k.lower() for k in new_req.unredirected_hdrs}
        self.assertNotIn("authorization", lowered)
        self.assertNotIn("cookie", lowered)
        self.assertNotIn("proxy-authorization", lowered)
        self.assertNotIn("referer", lowered)

    def test_sniff_media_blocks_redirect_to_private_or_file_url(self):
        import io
        import urllib.request
        from email.message import Message

        for target in ("http://127.0.0.1/secret", "file:///etc/passwd"):
            with self.subTest(target=target):
                class RedirectToBlockedHandler(urllib.request.BaseHandler):
                    handler_order = 100

                    def https_open(self, req):
                        headers = Message()
                        headers["Location"] = target
                        return self.parent.error(
                            "https", req, io.BytesIO(b""), 302, "Found", headers
                        )

                    def http_open(self, req):
                        raise AssertionError(f"must never connect to {req.full_url}")

                    def file_open(self, req):
                        raise AssertionError(f"must never open local file {req.full_url}")

                real_build_opener = urllib.request.build_opener

                def fake_build_opener(*handlers):
                    return real_build_opener(RedirectToBlockedHandler(), *handlers)

                with mock.patch("urllib.request.build_opener", side_effect=fake_build_opener):
                    res = json.loads(sleeve_engine.sniff_media("https://public.example.com/watch"))

                self.assertFalse(res["ok"])

    def test_sniff_media_extracts_public_candidates_and_skips_unsafe_ones(self):
        html = (
            b'<html><head>'
            b'<meta property="og:video" content="https://cdn.example.com/clip.mp4?a=1&amp;b=2">'
            b'</head><body>'
            b'<video src="file:///etc/passwd"></video>'
            b'<video src="http://127.0.0.1/private.mp4"></video>'
            b'<source src="/relative/video.mp4">'
            b'</body></html>'
        )

        class FakeResp:
            headers = type("H", (), {"get_content_charset": staticmethod(lambda: "utf-8")})()

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def geturl(self):
                return "https://public.example.com/watch/1"

            def read(self, n=-1):
                return html[:n] if n >= 0 else html

        fake_opener = mock.Mock()
        fake_opener.open.return_value = FakeResp()
        with mock.patch("urllib.request.build_opener", return_value=fake_opener):
            res = json.loads(sleeve_engine.sniff_media("https://public.example.com/watch/1"))

        self.assertTrue(res["ok"])
        urls = [item["url"] for item in res["items"]]
        self.assertEqual(
            [
                "https://cdn.example.com/clip.mp4?a=1&b=2",
                "https://public.example.com/relative/video.mp4",
            ],
            urls,
        )


class SegmentedRunSafetyTest(unittest.TestCase):
    def _start_server(self, payload, fail_range_from=None):
        import http.server

        class RangeHandler(http.server.BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                pass

            def do_HEAD(self):
                self.send_response(200)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()

            def do_GET(self):
                rng = self.headers.get("Range")
                if rng and rng.startswith("bytes="):
                    spec = rng[6:]
                    start_s, _, end_s = spec.partition("-")
                    start = int(start_s)
                    end = int(end_s) if end_s else len(payload) - 1
                    if fail_range_from is not None and start >= fail_range_from:
                        self.send_response(500)
                        self.end_headers()
                        return
                    chunk = payload[start : end + 1]
                    self.send_response(206)
                    self.send_header("Content-Range", f"bytes {start}-{end}/{len(payload)}")
                    self.send_header("Content-Length", str(len(chunk)))
                    self.end_headers()
                    self.wfile.write(chunk)
                else:
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), RangeHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server.server_address[1]

    def test_tiny_files_smaller_than_subworkers_download_cleanly(self):
        for payload in (b"X", b"abc"):
            with self.subTest(size=len(payload)):
                port = self._start_server(payload)
                tmp = tempfile.mkdtemp()
                self.addCleanup(lambda d=tmp: __import__("shutil").rmtree(d, ignore_errors=True))
                out = os.path.join(tmp, "tiny.bin")
                step = sleeve_engine.SegmentedHttpStep(
                    f"http://127.0.0.1:{port}/tiny.bin", out, subworkers=8
                )
                step.run()
                self.assertEqual(sleeve_engine.COMPLETED, step.state, step.error)
                with open(out, "rb") as fh:
                    self.assertEqual(payload, fh.read())
                self.assertEqual(["tiny.bin"], os.listdir(tmp))

    def test_segmented_failure_cleans_up_parts_directory(self):
        payload = b"0123456789abcdef" * 4
        port = self._start_server(payload, fail_range_from=16)
        tmp = tempfile.mkdtemp()
        self.addCleanup(lambda d=tmp: __import__("shutil").rmtree(d, ignore_errors=True))
        out = os.path.join(tmp, "fail.bin")
        step = sleeve_engine.SegmentedHttpStep(
            f"http://127.0.0.1:{port}/fail.bin", out, subworkers=4
        )
        step.run()
        self.assertEqual(sleeve_engine.ERROR, step.state)
        self.assertEqual([], os.listdir(tmp))

    def test_segmented_cancel_cleans_up_parts_directory(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(lambda d=tmp: __import__("shutil").rmtree(d, ignore_errors=True))
        out = os.path.join(tmp, "cancelled.bin")
        step = sleeve_engine.SegmentedHttpStep(
            "https://cdn.example.com/cancelled.bin", out, subworkers=4
        )
        step.probe = lambda: (1024, True)
        step.total = 1024
        started = threading.Event()

        def fake_fetch(url, start, end, part_path):
            with open(part_path, "wb") as fh:
                fh.write(b"partial")
            if start == 0:
                started.set()
                step._cancel.wait(2.0)
            raise sleeve_engine.Cancelled()

        step._fetch_range = fake_fetch
        worker = threading.Thread(target=step.run, daemon=True)
        worker.start()
        self.assertTrue(started.wait(2.0))
        step.cancel()
        worker.join(2.0)

        self.assertFalse(worker.is_alive())
        self.assertEqual(sleeve_engine.CANCELLED, step.state)
        self.assertEqual([], os.listdir(tmp))


class SegmentedRangeIntegrityTest(unittest.TestCase):
    def _make_step(self, url="https://cdn.example.com/file.bin", subworkers=4):
        tmp = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(tmp, ignore_errors=True))
        return tmp, sleeve_engine.SegmentedHttpStep(
            url, os.path.join(tmp, "file.bin"), subworkers=subworkers
        )

    def test_fetch_range_rejects_http_200_and_closes_response(self):
        tmp, step = self._make_step()
        part_path = os.path.join(tmp, "000.part")
        resp = FakeHttpResp(status=200, headers={"Content-Length": "100"}, body=b"A" * 100)
        recorded = []
        conns = []

        def fake_https(host, port, **kwargs):
            c = RecordingConn(host, port, "https", [resp], recorded)
            conns.append(c)
            return c

        with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", side_effect=fake_https):
            with self.assertRaises(RuntimeError) as ctx:
                step._fetch_range(step.url, 0, 15, part_path)

        self.assertIn("200", str(ctx.exception))
        self.assertTrue(resp.closed)
        self.assertTrue(conns[0].closed)
        self.assertEqual(0, len(step._conns))
        self.assertFalse(os.path.exists(part_path))

    def test_fetch_range_rejects_mismatched_content_range_start(self):
        cases = (
            (b"", 0, 15, "bytes 4-15/64"),
            (b"0123", 0, 15, "bytes 0-15/64"),
            (b"", 16, 31, "bytes */64"),
        )
        for existing, start, end, bad_cr in cases:
            with self.subTest(existing_len=len(existing), start=start, bad_cr=bad_cr):
                tmp, step = self._make_step()
                part_path = os.path.join(tmp, "seg.part")
                if existing:
                    with open(part_path, "wb") as fh:
                        fh.write(existing)
                resp = FakeHttpResp(
                    status=206,
                    headers={"Content-Range": bad_cr},
                    body=b"X" * (end - start + 1),
                )
                recorded = []

                def fake_https(host, port, **kwargs):
                    return RecordingConn(host, port, "https", [resp], recorded)

                with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", side_effect=fake_https):
                    with self.assertRaises(RuntimeError) as ctx:
                        step._fetch_range(step.url, start, end, part_path)

                self.assertIn("Content-Range", str(ctx.exception))
                self.assertTrue(resp.closed)

    def test_probe_captures_strong_etag_sends_if_range_and_fails_on_drift(self):
        tmp, step = self._make_step(subworkers=2)
        recorded = []
        responses = [
            FakeHttpResp(
                status=200,
                headers={
                    "Accept-Ranges": "bytes",
                    "Content-Length": "32",
                    "ETag": '"v1-strong"',
                },
            ),
            # Simulate server returning HTTP 200 when ETag drifted.
            FakeHttpResp(status=200, headers={"Content-Length": "32"}, body=b"B" * 32),
            FakeHttpResp(status=200, headers={"Content-Length": "32"}, body=b"B" * 32),
        ]

        def fake_https(host, port, **kwargs):
            return RecordingConn(host, port, "https", responses, recorded)

        with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", side_effect=fake_https):
            step.run()

        self.assertEqual('"v1-strong"', step._if_range)
        self.assertEqual(sleeve_engine.ERROR, step.state)
        self.assertIn("200", step.error)
        range_reqs = [r for r in recorded if r["method"] == "GET"]
        self.assertGreaterEqual(len(range_reqs), 1)
        for req in range_reqs:
            self.assertEqual('"v1-strong"', req["headers"].get("If-Range"))
        self.assertEqual([], os.listdir(tmp))

    def test_probe_ignores_weak_etag_and_falls_back_to_last_modified(self):
        _, step = self._make_step()
        recorded = []
        last_mod = "Wed, 21 Oct 2015 07:28:00 GMT"
        responses = [
            FakeHttpResp(status=200, headers={"ETag": 'W/"weak-1"'}),
            FakeHttpResp(
                status=206,
                headers={
                    "Content-Range": "bytes 0-0/64",
                    "ETag": 'W/"weak-1"',
                    "Last-Modified": last_mod,
                },
                body=b"x",
            ),
        ]

        def fake_https(host, port, **kwargs):
            return RecordingConn(host, port, "https", responses, recorded)

        with mock.patch.object(sleeve_engine.http.client, "HTTPSConnection", side_effect=fake_https):
            total, accept_ranges = step.probe()

        self.assertEqual((64, True), (total, accept_ranges))
        self.assertEqual(last_mod, step._if_range)
        # Initial 0-0 probe must not send If-Range.
        self.assertNotIn("If-Range", recorded[1]["headers"])

    def test_first_worker_failure_cancels_slow_sibling_promptly(self):
        tmp, step = self._make_step(subworkers=2)
        sibling_reading = threading.Event()
        sibling_conn = TrackedConn()
        sibling_resp = BlockingResp(sibling_conn)
        sibling_resp.status = 206
        sibling_resp.getheader = lambda name, default=None: (
            "bytes 0-511/1024" if name.lower() == "content-range" else default
        )
        orig_read = sibling_resp.read

        def blocked_read(n=-1):
            sibling_reading.set()
            return orig_read(n)

        sibling_resp.read = blocked_read

        fail_conn = TrackedConn()
        fail_resp = FakeHttpResp(status=200, headers={"Content-Length": "1024"}, body=b"Z" * 1024)

        step.probe = lambda: (1024, True)
        step.total = 1024

        def fake_request_range(url, start, end):
            if start == 0:
                with step._lock:
                    step._conns.add(sibling_conn)
                return sibling_conn, "/file.bin", url, sibling_resp
            # Wait until sibling is blocked inside read() before failing,
            # proving fail-fast cancellation actively wakes the sibling.
            sibling_reading.wait(2.0)
            with step._lock:
                step._conns.add(fail_conn)
            return fail_conn, "/file.bin", url, fail_resp

        step._request_range = fake_request_range

        started = time.monotonic()
        step.run()
        elapsed = time.monotonic() - started

        self.assertLess(elapsed, 2.0, "slow sibling was not aborted promptly")
        self.assertEqual(sleeve_engine.ERROR, step.state)
        self.assertIn("200", step.error)
        self.assertTrue(step._cancel.is_set())
        self.assertTrue(sibling_conn.closed.is_set())
        self.assertEqual([], os.listdir(tmp))


class InstagramEmbedTest(unittest.TestCase):
    def test_shortcode_to_pk_uses_base64url_alphabet(self):
        # RFC 4648 base64url: '-' == 62, '_' == 63
        self.assertEqual(62, sleeve_engine._shortcode_to_pk("-"))
        self.assertEqual(63, sleeve_engine._shortcode_to_pk("_"))
        self.assertEqual(62 * 64 + 63, sleeve_engine._shortcode_to_pk("-_"))
        self.assertEqual(63 * 64 + 62, sleeve_engine._shortcode_to_pk("_-"))
        self.assertEqual(0 * 64 + 62, sleeve_engine._shortcode_to_pk("A-"))
        self.assertEqual(0 * 64 + 63, sleeve_engine._shortcode_to_pk("A_"))
        self.assertIsNone(sleeve_engine._shortcode_to_pk(""))
        self.assertIsNone(sleeve_engine._shortcode_to_pk("abc!"))

    def test_lane3_requests_correct_pk_for_dash_and_underscore_shortcode(self):
        requested = []
        expected_pk = sleeve_engine._shortcode_to_pk("B-_9")
        expected_url = f"https://www.instagram.com/api/v1/media/{expected_pk}/info/"

        class FakeUrlopenResp:
            def __init__(self, body):
                self._body = body

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self, n=-1):
                return self._body[:n] if n >= 0 else self._body

        def fake_urlopen(req, timeout=None):
            requested.append((req.full_url, timeout))
            if "/api/v1/media/" in req.full_url:
                payload = json.dumps(
                    {"items": [{"video_versions": [{"url": "https://cdn.example.com/v.mp4"}]}]}
                ).encode("utf-8")
                return FakeUrlopenResp(payload)
            return FakeUrlopenResp(b"<html>no media</html>")

        with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen), \
             mock.patch.object(sleeve_engine.time, "sleep"):
            res = json.loads(
                sleeve_engine.instagram_embed("https://www.instagram.com/reel/B-_9/")
            )

        self.assertTrue(res["ok"])
        self.assertEqual("https://cdn.example.com/v.mp4", res["items"][0]["url"])
        api_urls = [u for u, _ in requested if "/api/v1/media/" in u]
        self.assertEqual([expected_url], api_urls)

    def test_instagram_embed_stops_immediately_on_404_and_401(self):
        import urllib.error

        for status_code in (404, 401):
            with self.subTest(status_code=status_code):
                calls = []

                def fake_urlopen(req, timeout=None):
                    calls.append(req.full_url)
                    raise urllib.error.HTTPError(
                        req.full_url, status_code, "Error", {}, None
                    )

                with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen), \
                     mock.patch.object(sleeve_engine.time, "sleep") as sleep_mock:
                    res = json.loads(
                        sleeve_engine.instagram_embed(
                            "https://www.instagram.com/p/C1234567890/"
                        )
                    )

                self.assertFalse(res["ok"])
                self.assertEqual(1, len(calls))
                sleep_mock.assert_not_called()

    def test_instagram_embed_honors_deadline_budget(self):
        now = [100.0]
        timeouts = []

        def fake_monotonic():
            return now[0]

        def fake_urlopen(req, timeout=None):
            timeouts.append(timeout)
            # Simulate each request consuming 1.5s of wall time.
            now[0] += 1.5
            raise OSError("timed out")

        def fake_sleep(seconds):
            now[0] += seconds

        with mock.patch.object(sleeve_engine.time, "monotonic", side_effect=fake_monotonic), \
             mock.patch.object(sleeve_engine.time, "sleep", side_effect=fake_sleep), \
             mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            res = json.loads(
                sleeve_engine.instagram_embed(
                    "https://www.instagram.com/p/C1234567890/",
                    max_seconds=2.5,
                )
            )

        self.assertFalse(res["ok"])
        # With max_seconds=2.5 and 1.5s per request, at most 2 calls can run
        # (far fewer than the full 9 endpoints x 2 attempts = 18 calls).
        self.assertLessEqual(len(timeouts), 2)
        self.assertGreaterEqual(len(timeouts), 1)
        for t in timeouts:
            self.assertLessEqual(t, 2.5)
            self.assertGreater(t, 0.0)


if __name__ == "__main__":
    unittest.main()
