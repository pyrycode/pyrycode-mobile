#!/usr/bin/env python3
"""Own one e2e daemon and expose stop/start to the emulator's host-loopback alias.

The port and process IDs are test diagnostics. Pairing material stays in the
daemon's existing private home and never passes through this control protocol.
"""

import argparse
from e2e_session_cleanup import TestSessions
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import math
from pathlib import Path
import re
import socket
import signal
import subprocess
import time


def post_batch(socket_path, body):
    if not isinstance(body, dict):
        raise ValueError("invalid synthetic post control")
    name, prefix, count = body.get("name"), body.get("prefix"), body.get("count")
    if (not isinstance(name, str) or not re.fullmatch(r"e2e-[A-Za-z0-9_.-]{1,80}", name)
            or not isinstance(prefix, str) or not re.fullmatch(r"e2e1833-[A-Za-z0-9-]{1,80}", prefix)
            or type(count) is not int or not 1 <= count <= 200):
        raise ValueError("invalid synthetic post control")
    for index in range(count):
        with socket.socket(socket.AF_UNIX) as client:
            client.settimeout(10)
            client.connect(socket_path)
            request = {"verb": "channel.post", "channelPost": {"name": name, "text": f"{prefix}-{index:03d}"}}
            client.sendall(json.dumps(request).encode() + b"\n")
            reply = json.loads(client.makefile("rb").readline(4096))
            if reply.get("ok") is not True or reply.get("error"):
                raise RuntimeError("owned daemon post refused")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port-file", required=True)
    parser.add_argument("--log", required=True)
    parser.add_argument("--ready-token", default="")
    parser.add_argument("--ready-timeout", type=float, default=15)
    parser.add_argument("--control-socket", required=True)
    parser.add_argument("--cleanup-sockets-file", default="")
    parser.add_argument("--stream-release-file", default="")
    parser.add_argument("--stream-result-release-file", default="")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("daemon command is required")
    if not math.isfinite(args.ready_timeout) or not 0 < args.ready_timeout <= 15:
        parser.error("readiness timeout must be positive and at most 15 seconds")
    token = args.ready_token.encode()
    if len(token) > 256:
        parser.error("readiness token is too large")

    log = open(args.log, "ab", buffering=0)
    child = None
    generation = 0
    log_offset = 0
    ready = False
    phase = "stopped"

    def refresh():
        nonlocal ready, phase
        running = child is not None and child.poll() is None
        if not running:
            ready = False
            if child is not None and phase != "stopped":
                phase = "exited"
        return running

    def evidence(action, status, started):
        running = refresh()
        return {"action": action, "http_status": status, "running": running,
                "pid": child.pid if running else None, "generation": generation,
                "ready": ready, "phase": phase, "log_offset": log_offset,
                "elapsed_ms": int((time.monotonic() - started) * 1000)}

    def record(action, status, started):
        print(json.dumps({"event": "daemon_fault_transition", "time": time.time(),
                          **evidence(action, status, started)}), flush=True)

    def start():
        nonlocal child, generation, log_offset, ready, phase
        if not refresh():
            log_offset = log.seek(0, 2)
            generation += 1
            ready = False
            phase = "spawning"
            child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            phase = "spawned"
            record("spawn", 0, time.monotonic())

    def await_relay_ready():
        nonlocal ready, phase
        if not refresh():
            raise RuntimeError("owned daemon exited before relay registration")
        if ready:
            return
        if not token:
            ready, phase = True, "disabled"
            return
        phase = "waiting"
        deadline = time.monotonic() + args.ready_timeout
        offset, tail = log_offset, b""
        while True:
            if not refresh():
                raise RuntimeError("owned daemon exited before relay registration")
            with open(args.log, "rb") as reader:
                reader.seek(offset)
                chunk = reader.read(4096)
            if chunk:
                scanned = tail + chunk
                offset += len(chunk)
                if token in scanned and refresh():
                    ready, phase = True, "ready"
                    return
                tail = scanned[-(len(token) - 1):] if len(token) > 1 else b""
            if time.monotonic() >= deadline:
                phase = "registration_timeout"
                raise RuntimeError("owned daemon did not register at relay")
            if chunk:
                continue
            time.sleep(0.05)

    def stop():
        nonlocal ready, phase
        ready = False
        phase = "stopped"
        if child is not None and child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                phase = "stop_timeout"
                raise

    def cleanup_sockets():
        if not args.cleanup_sockets_file:
            raise RuntimeError("session cleanup is not configured")
        paths = json.loads(Path(args.cleanup_sockets_file).read_text())
        if args.control_socket not in paths or not Path(args.control_socket).exists():
            raise RuntimeError("primary test daemon is unavailable for cleanup")
        return [path for path in paths if Path(path).exists()]

    test_sessions = TestSessions(cleanup_sockets)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, _format, *_args):
            pass

        def do_POST(self):
            started = time.monotonic()
            actions = {"/test-begin", "/test-end", "/posts", "/stop", "/start", "/stream-release", "/stream-finish", "/status"}
            action = self.path[1:] if self.path in actions else "unknown"
            status = 200
            try:
                if self.path == "/test-begin":
                    test_sessions.begin()
                elif self.path == "/test-end":
                    removed = test_sessions.end()
                    print(json.dumps({"event": "test_session_cleanup", "time": time.time(),
                                      "removed": removed, "remaining_new": 0}), flush=True)
                elif self.path == "/posts":
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= 1024:
                        raise ValueError("invalid synthetic post control")
                    post_batch(args.control_socket, json.loads(self.rfile.read(size)))
                elif self.path == "/stop":
                    stop()
                elif self.path == "/start":
                    start()
                    await_relay_ready()
                elif self.path in ("/stream-release", "/stream-finish"):
                    release = args.stream_release_file if self.path == "/stream-release" else args.stream_result_release_file
                    if not release:
                        status = 404
                    else:
                        # Paths are fixed by the isolated harness; this request accepts no path or content.
                        Path(release).touch()
                elif self.path != "/status":
                    status = 404
            except (ValueError, KeyError, OSError, RuntimeError, subprocess.TimeoutExpired):
                status = 503
            payload = json.dumps(evidence(action, status, started)).encode()
            record(action, status, started)
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except OSError:
                record("response_transport", 0, started)

    server = HTTPServer(("127.0.0.1", 0), Handler)

    def shutdown(_signum, _frame):
        raise SystemExit(0)

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    try:
        start()
        Path(args.port_file).write_text(str(server.server_port))
        server.serve_forever()
    finally:
        server.server_close()
        try:
            stop()
        finally:
            log.close()


if __name__ == "__main__":
    main()
