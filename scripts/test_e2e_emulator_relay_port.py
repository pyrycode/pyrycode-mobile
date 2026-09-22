import http.server
import os
from pathlib import Path
import socket
import subprocess
import threading
import unittest


class _Healthz(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if self.path == "/healthz" else 404)
        self.end_headers()

    def log_message(self, *args):
        pass


class EmulatorRelayPortTest(unittest.TestCase):
    """Two harness runs on one host must never share a relay (2026-09-22)."""

    def setUp(self):
        self.script = Path(__file__).with_name("e2e-emulator.sh").read_text()

    def block(self, start, end):
        return self.script[self.script.index(start):self.script.index(end, self.script.index(start))]

    def test_default_port_is_a_free_ephemeral_port(self):
        line = self.block('PORT="${PORT:-', "\nDEVICE=")
        env = dict(os.environ, PORT="")
        picked = subprocess.run(["bash", "-c", "set -euo pipefail\n" + line + '\necho "$PORT"'],
                                env=env, capture_output=True, text=True, check=True).stdout.strip()
        self.assertTrue(picked.isdigit(), picked)
        self.assertNotEqual("8888", picked)
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", int(picked)))  # still free: nothing was left listening on it
        pinned = subprocess.run(["bash", "-c", "set -euo pipefail\n" + line + '\necho "$PORT"'],
                                env=dict(os.environ, PORT="8888"), capture_output=True, text=True, check=True)
        self.assertEqual("8888", pinned.stdout.strip())

    def test_guard_refuses_a_port_that_already_serves_a_relay(self):
        guard = self.block("  # Refuse a port that already answers", '  log "starting relay on')
        prelude = 'set -euo pipefail\nlog() { :; }\ndie() { echo "$1" >&2; exit 3; }\n'
        server = http.server.HTTPServer(("127.0.0.1", 0), _Healthz)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            taken = subprocess.run(["bash", "-c", prelude + guard], env=dict(os.environ, PORT=str(server.server_port)),
                                   capture_output=True, text=True)
            self.assertEqual(3, taken.returncode, taken.stderr)
            self.assertIn("already serves a relay", taken.stderr)
        finally:
            server.shutdown()
            server.server_close()
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            free = probe.getsockname()[1]
        clear = subprocess.run(["bash", "-c", prelude + guard], env=dict(os.environ, PORT=str(free)),
                               capture_output=True, text=True)
        self.assertEqual(0, clear.returncode, clear.stderr)


if __name__ == "__main__":
    unittest.main()
