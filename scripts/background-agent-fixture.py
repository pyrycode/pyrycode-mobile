#!/usr/bin/env python3
"""Ticket #1783: loopback hold/release fixture for one real background Agent."""
import argparse
import http.server
from pathlib import Path
import threading


def serve(port_file):
    released = threading.Event()
    reply_released = threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_GET(self):
            if self.path in ("/hold", "/hold-reply"):
                release = reply_released if self.path == "/hold-reply" else released
                if not release.wait(180):
                    self.send_error(408)
                    return
            elif self.path in ("/release", "/release-reply"):
                release = reply_released if self.path == "/release-reply" else released
                release.set()
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"background_agent_released")

    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        Path(port_file).write_text(str(server.server_port))
        server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("port_file")
    serve(parser.parse_args().port_file)
