#!/usr/bin/env python3
"""Own one e2e daemon and expose stop/start to the emulator's host-loopback alias.

The port and process IDs are test diagnostics. Pairing material stays in the
daemon's existing private home and never passes through this control protocol.
"""

import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
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
    parser.add_argument("--control-socket", required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("daemon command is required")

    log = open(args.log, "ab", buffering=0)
    child = None

    def start():
        nonlocal child
        if child is None or child.poll() is not None:
            child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            return True
        return False

    def await_relay_ready(offset):
        if not args.ready_token:
            return
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if child.poll() is not None:
                raise RuntimeError("owned daemon exited before relay registration")
            with open(args.log, "rb") as reader:
                reader.seek(offset)
                if args.ready_token.encode() in reader.read():
                    return
            time.sleep(0.05)
        raise RuntimeError("owned daemon did not register at relay")

    def stop():
        nonlocal child
        if child is not None and child.poll() is None:
            child.terminate()
            child.wait(timeout=10)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, _format, *_args):
            pass

        def do_POST(self):
            try:
                if self.path == "/posts":
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= 1024:
                        raise ValueError("invalid synthetic post control")
                    post_batch(args.control_socket, json.loads(self.rfile.read(size)))
                elif self.path == "/stop":
                    stop()
                elif self.path == "/start":
                    offset = Path(args.log).stat().st_size
                    if start():
                        await_relay_ready(offset)
                elif self.path != "/status":
                    self.send_error(404)
                    return
                payload = json.dumps({"running": child is not None and child.poll() is None,
                                      "pid": child.pid if child is not None and child.poll() is None else None}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except (ValueError, OSError, RuntimeError, subprocess.TimeoutExpired):
                self.send_error(503, "owned daemon transition failed")

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
