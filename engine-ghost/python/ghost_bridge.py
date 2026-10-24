"""Ghost engine bridge for the Seal fork (GPL-3.0).

Chaquopy entry point that hosts the Ghost Downloader engine
(XiaoYouChR/Ghost-Downloader-3, GPL-3.0) inside the Seal app process and
exposes a small JSON-RPC control surface on 127.0.0.1, mirroring the aria2 RPC
pattern Ghost itself ships (app/services/aria2_rpc.py).

Stage A: control surface only; task execution lands with the vendored engine.
Standard library only, so the module also runs under a desktop CPython for
harness tests without Chaquopy.
"""

from __future__ import annotations

import json
import socketserver
import threading

BRIDGE_HOST = "127.0.0.1"
BRIDGE_PORT = 7435


class EngineHost:
    """Owns the Ghost engine instance once its packages are vendored.

    Stage B wiring (see ../README.md):
      from app.engine import Engine            # Ghost's engine assembly
      from app.services.task_service import TaskService
      self.engine = Engine(...)
    Until then every method that needs the engine reports "engine_not_ready".
    """

    def __init__(self) -> None:
        self._engine = None
        self._lock = threading.Lock()

    @property
    def engine(self):
        with self._lock:
            return self._engine

    def start(self) -> None:
        """Construct and start the Ghost engine (Stage B)."""
        # from app.engine import Engine
        # self._engine = Engine(coroutine_runner, file_watcher, ...)
        # self._engine.start()
        raise NotImplementedError("Stage B: vendor Ghost's app/ package first")

    def add_uri(self, url, options=None):
        """Queue a direct-URL download through Ghost's TaskService (Stage B).

        options maps to Ghost's TaskOptions: headers, userAgent, subworkerCount,
        outputDirectory, fileName.
        """
        # task = engine.taskService.add(FeatureService.parse(url, options))
        # return {"taskId": task.id}
        return {"error": "engine_not_ready"}

    def progress(self, task_id):
        """Return {state, downloaded, total, speed} for a task (Stage B)."""
        return {"error": "engine_not_ready"}


HOST = EngineHost()

METHODS = {
    "ghost.addUri": lambda params: HOST.add_uri(params.get("uri"), params.get("options")),
    "ghost.progress": lambda params: HOST.progress(params.get("taskId")),
    "ghost.ping": lambda params: {"ok": True, "engine": HOST.engine is not None},
}


class BridgeHandler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        for line in self.rfile:
            line = line.strip()
            if not line:
                continue
            try:
                request = json.loads(line)
                method = METHODS.get(request.get("method"))
                result = method(request.get("params") or {}) if method else {"error": "unknown_method"}
            except Exception as exc:  # noqa: BLE001 - control surface must never die
                result = {"error": f"bridge_fault: {exc}"}
            response = {"id": request.get("id") if "request" in dir() else None, "result": result}
            try:
                self.wfile.write(json.dumps(response).encode() + b"\n")
            except OSError:
                return


def serve(host: str = BRIDGE_HOST, port: int = BRIDGE_PORT) -> None:
    with socketserver.ThreadingTCPServer((host, port), BridgeHandler) as server:
        server.daemon_threads = True
        server.serve_forever()
