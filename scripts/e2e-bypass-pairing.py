#!/usr/bin/env python3
"""Mint the isolated bypass fixture once, when its emulator scenario reaches pairing."""

import argparse
import base64
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess


def mint_pairing(args, name, privileged=False):
    command = [args.pyry, "pair", "-pyry-name=" + args.instance, "--name=" + name]
    if privileged:
        command.append("--allow-remote-permissions")
    result = subprocess.run(command, env={**os.environ, "HOME": args.home, "PYRY_RELAY_URL": args.daemon_relay},
                            capture_output=True, timeout=20, check=True)
    for line in result.stdout.splitlines():
        try:
            payload = json.loads(base64.urlsafe_b64decode(line + b"=" * (-len(line) % 4)))
        except (ValueError, UnicodeError):
            continue
        if isinstance(payload, dict) and all(isinstance(payload.get(key), str) and payload[key]
                                            for key in ("server", "token", "server_static_pubkey")):
            return payload
    raise ValueError("pairing payload missing")


def main():
    parser = argparse.ArgumentParser()
    for option in ("port-file", "pyry", "home", "instance", "phone-name", "peer-name", "daemon-relay", "phone-relay"):
        parser.add_argument("--" + option, required=True)
    args = parser.parse_args()
    if not args.instance.startswith("e2e-"):
        parser.error("isolated e2e instance required")
    capability = secrets.token_urlsafe(32)
    consumed = False

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, _format, *_args):
            pass  # Request headers and pairing material must never reach diagnostics.

        def do_POST(self):
            nonlocal consumed
            if self.path != "/pair":
                self.send_error(404, "unknown fixture operation")
                return
            supplied = self.headers.get("Authorization", "").encode()
            if not hmac.compare_digest(supplied, ("Bearer " + capability).encode()):
                self.send_error(403, "fixture authorization failed")
                return
            if consumed:
                self.send_error(409, "fixture already consumed")
                return
            consumed = True  # Even failure cannot mint again or retry an expired pairing.
            try:
                phone = mint_pairing(args, args.phone_name)
                peer = mint_pairing(args, args.peer_name, privileged=True)
                if any(phone[key] != peer[key] for key in ("server", "server_static_pubkey")):
                    raise ValueError("fixture host mismatch")
                phone["relay"] = args.phone_relay
                code = base64.urlsafe_b64encode(json.dumps(phone, separators=(",", ":")).encode()).decode().rstrip("=")
                payload = json.dumps({"serverId": phone["server"], "pairCode": code,
                                      "peerToken": peer["token"], "serverStaticPublicKey": peer["server_static_pubkey"]}).encode()
                if len(payload) > 16_384:
                    raise ValueError("fixture too large")
                print("event=bypass_pairing_fixture outcome=minted", flush=True)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except (OSError, ValueError, subprocess.SubprocessError):
                print("event=bypass_pairing_fixture outcome=failed", flush=True)
                self.send_error(503, "fixture mint failed")

    server = HTTPServer(("127.0.0.1", 0), Handler)
    # HTTPServer's default error handler emits tracebacks. A disconnected client is a static failure.
    server.handle_error = lambda *_args: print("event=bypass_pairing_fixture outcome=connection_failed", flush=True)

    def shutdown(_signum, _frame):
        raise SystemExit(0)

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    try:
        with open(args.port_file, "w", opener=lambda path, flags: os.open(path, flags, 0o600)) as config:
            json.dump({"port": server.server_port, "authorization": capability}, config)
        print("event=bypass_pairing_fixture outcome=ready", flush=True)
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
