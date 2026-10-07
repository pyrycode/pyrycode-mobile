#!/usr/bin/env python3
"""Loopback-only, per-scenario background Bash hold; only teardown releases it."""
import argparse
import http.server
from pathlib import Path
import re
import threading


class Hold:
    def __init__(self):
        self.arrived = threading.Event()
        self.released = threading.Event()


def server():
    holds = {}
    lock = threading.Lock()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_GET(self):
            match = re.fullmatch(r"/([0-9]{1,20})/(hold|status|release)", self.path)
            if match is None:
                self.send_error(404)
                return
            key, action = match.groups()
            with lock:
                held = holds.setdefault(key, Hold())
            if action == "hold":
                held.arrived.set()
                held.released.wait()
                body = b"released"
            elif action == "release":
                held.released.set()
                body = b"released"
            else:
                body = b"released" if held.released.is_set() else b"held" if held.arrived.is_set() else b"waiting"
            self.send_response(200)
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass  # Expected when Stop kills the waiting curl.

    instance = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    instance.daemon_threads = True
    instance.holds = holds
    return instance


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("port_file")
    args = parser.parse_args()
    with server() as instance:
        Path(args.port_file).write_text(str(instance.server_port))
        instance.serve_forever()
