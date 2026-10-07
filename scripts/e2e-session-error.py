#!/usr/bin/env python3
"""Consumer-only session-error control. Never restarts or signals a Runner.

The daemon contract is pyrycode/docs/knowledge/features/e2e-realclaude.md,
Test infrastructure. Only this fixture's tagged daemons receive its settings.
"""
import argparse
import base64
import hmac
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import uuid


def wait_state(predicate, seconds, code):
    deadline = time.monotonic() + seconds
    while True:
        value = predicate()
        if value:
            return value
        if time.monotonic() >= deadline:
            raise RuntimeError(code)
        time.sleep(0.05)  # Poll a state; elapsed time never establishes readiness.


def select_executable(path, executable):
    if not executable.is_absolute() or not executable.is_file() or not os.access(executable, os.X_OK):
        raise ValueError("recovery_executable_unavailable")
    temporary = path.with_suffix(".tmp")
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as writer:
        writer.write(str(executable) + "\n")
        writer.flush()
        os.fsync(writer.fileno())
    temporary.replace(path)


def write_failing_executable(path):
    path.write_text('#!/bin/sh\nexec 0<&-\n: > "$PYRY_E2E_EXIT_GATE.ready"\n'
                    'while [ ! -e "$PYRY_E2E_EXIT_GATE" ]; do sleep 0.02; done\nexit 1\n')
    path.chmod(0o700)


def assert_identity(before, after):
    if any(before[key] != after[key] for key in ("daemon_pid", "started_at", "session_id")):
        raise RuntimeError("identity_changed")
    if after["restart_count"] < before["restart_count"]:
        raise RuntimeError("restart_counter_reset")


def prompt_counts(data, held, fresh):
    counts = [0, 0]
    for line in data.splitlines():
        entry = json.loads(line)
        if entry.get("type") != "user":
            continue
        content = entry.get("message", {}).get("content", "")
        if isinstance(content, list):
            content = "\n".join(block.get("text", "") for block in content if block.get("type") == "text")
        for index, marker in enumerate((held, fresh)):
            counts[index] += content.count(marker)
    return tuple(counts)


def authorized(header, secret):
    return hmac.compare_digest(header or "", "Bearer " + secret)


def parse_action(path):
    parts = path.split("/")
    if len(parts) != 3 or parts[1] not in ("retained", "dropped") or parts[2] not in (
        "start", "ready", "exit", "release", "recovered", "complete", "close"
    ):
        raise ValueError("unknown_action")
    return parts[1], parts[2]


class Case:
    def __init__(self, args, arm):
        if not args.scripted and not (os.environ.get("CLAUDE_CODE_OAUTH_TOKEN") or os.environ.get("ANTHROPIC_API_KEY")):
            raise RuntimeError("session_error_live_credential_missing")
        self.args, self.arm = args, arm
        self.home = Path(tempfile.mkdtemp(prefix="pyry1731-", dir="/tmp"))
        self.evidence = Path(args.evidence) / arm
        self.evidence.mkdir(parents=True, mode=0o700)
        self.name = "e2e-session-error"
        self.session = str(uuid.uuid4())
        self.conversation = str(uuid.uuid4())
        self.channel = "e2e1731-" + arm + "-" + uuid.uuid4().hex[:8]
        self.held = "marker=held-" + self.conversation
        self.fresh = "marker=fresh-" + self.conversation
        self.selection = self.home / "selection"
        self.gate = self.home / "exit-gate"
        self.child = None
        self.log = None
        self.before = None
        self.released = False
        self.completed = False
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith(("PYRY_E2E_", "PYRY_FAKE_CLAUDE_"))}
        self.env.update(HOME=str(self.home), PYRY_MOBILE_V2="1", PYRY_RELAY_URL=args.daemon_relay,
                        PYRY_E2E_CLAUDE_BIN_FILE=str(self.selection), PYRY_E2E_EXIT_GATE=str(self.gate))
        if args.daemon_relay.startswith("ws://"):
            self.env["PYRY_ALLOW_INSECURE_RELAY"] = "1"
        if arm == "dropped":
            self.env["PYRY_E2E_QUEUE_GIVE_UP_AFTER"] = "3s"
        if args.scripted:
            self.env.pop("CLAUDE_CODE_OAUTH_TOKEN", None)
            self.env.pop("ANTHROPIC_API_KEY", None)
            # The canned echo is identical to the sent prompt and cannot prove assistant rendering.
            reply = self.home / "recovery-reply.jsonl"
            with reply.open("x") as output:
                reply.chmod(0o600)
                for frame in (
                    {"type": "assistant", "message": {"id": "recovery-" + self.conversation,
                     "role": "assistant", "content": [{"type": "text", "text": "recovered1731"}]}},
                    {"type": "result", "subtype": "success", "session_id": self.session},
                ):
                    output.write(json.dumps(frame) + "\n")
            self.env.update(PYRY_FAKE_CLAUDE_STREAM_JSON="1",
                            PYRY_FAKE_CLAUDE_STREAM_REPLAY_FIRST=str(reply),
                            PYRY_FAKE_CLAUDE_STDIN_LOG=str(self.home / "stdin"))
        elif os.environ.get("CLAUDE_CODE_OAUTH_TOKEN"):
            # Match the existing isolated real-Claude harness. Never retain this file.
            shutil.copyfile(Path.home() / ".claude.json", self.home / ".claude.json")
            (self.home / ".claude.json").chmod(0o600)
        elif not os.environ.get("ANTHROPIC_API_KEY"):
            raise RuntimeError("session_error_live_credential_missing")

    def alive(self):
        if self.child is None or self.child.poll() is not None:
            raise RuntimeError("owned_daemon_exited")

    def status(self):
        self.alive()
        with socket.socket(socket.AF_UNIX) as connection:
            connection.settimeout(1)
            connection.connect(str(self.home / ".pyry" / (self.name + ".sock")))
            connection.sendall(b'{"verb":"status"}\n')
            result = json.loads(connection.makefile("rb").readline(16384))
        status = result["status"]
        registry = json.loads((self.home / ".pyry" / self.name / "conversations.json").read_text())
        bound = next(row["current_session_id"] for row in registry["conversations"] if row["id"] == self.conversation)
        if bound != self.session:
            raise RuntimeError("bound_session_changed")
        return {**status, "daemon_pid": self.child.pid, "session_id": bound}

    def record(self, event, status):
        with (self.evidence / "control.jsonl").open("a") as writer:
            writer.write(json.dumps({"event": event, **status}) + "\n")

    def pairing(self, label):
        result = subprocess.run([self.args.daemon, "pair", "-pyry-name=" + self.name, "--name=" + label],
                                env=self.env, capture_output=True, timeout=15, check=True)
        for line in result.stdout.splitlines():
            try:
                payload = json.loads(base64.urlsafe_b64decode(line + b"=" * (-len(line) % 4)))
            except (ValueError, UnicodeError):
                continue
            if isinstance(payload, dict) and {"server", "token", "server_static_pubkey"} <= payload.keys():
                return payload
        raise RuntimeError("session_error_pairing_missing")

    def start(self):
        registry = self.home / ".pyry" / self.name
        registry.mkdir(parents=True, mode=0o700)
        (self.home / ".pyry" / "config.json").write_text('{"interactive_runner":"stream-json"}')
        (registry / "sessions.json").write_text(json.dumps({"version": 1, "sessions": [{
            "id": self.session, "label": "", "created_at": "2026-01-01T00:00:00Z",
            "last_active_at": "2026-01-01T00:00:00Z", "bootstrap": True, "lifecycle_state": "active"}]}))
        (registry / "conversations.json").write_text(json.dumps({"conversations": [{
            "id": self.conversation, "name": self.channel, "cwd": str(self.home),
            "current_session_id": self.session, "is_promoted": True, "last_used_at": "2026-01-01T00:00:00Z"}]}))
        failing = self.home / "failing-claude"
        write_failing_executable(failing)
        select_executable(self.selection, failing)
        self.log = (self.evidence / "daemon.log").open("wb")
        self.child = subprocess.Popen([self.args.daemon, "-pyry-name=" + self.name,
                                       "-pyry-workdir=" + str(self.home), "-pyry-claude=" + self.args.recovery,
                                       "-pyry-idle-timeout=0"], env=self.env, stdout=self.log, stderr=subprocess.STDOUT)

        def ready():
            self.alive()
            try:
                status = self.status()
                return status if Path(str(self.gate) + ".ready").exists() and b"relay: conn established" in (self.evidence / "daemon.log").read_bytes() else None
            except (OSError, KeyError):
                return None

        self.before = wait_state(ready, 20, "session_error_daemon_not_ready")
        self.record("start", self.before)
        phone, peer = self.pairing("phone"), self.pairing("observer")
        phone["relay"] = self.args.phone_relay
        code = base64.urlsafe_b64encode(json.dumps(phone).encode()).decode().rstrip("=")
        return {"pairCode": code, "peerToken": peer["token"], "serverId": peer["server"],
                "serverStaticPublicKey": peer["server_static_pubkey"], "relayUrl": self.args.phone_relay,
                "conversationId": self.conversation, "channel": self.channel,
                "sessionId": self.session, "heldMarker": self.held, "freshMarker": self.fresh}

    def action(self, action):
        if action == "ready":
            def ready():
                self.alive()
                return Path(str(self.gate) + ".ready").exists()
            wait_state(ready, 15, "failing_child_stdin_not_closed")
        elif action == "exit":
            self.gate.touch(mode=0o600)
        elif action == "release":
            assert_identity(self.before, self.status())
            select_executable(self.selection, Path(self.args.recovery))
            self.released = True
        elif action == "recovered":
            if not self.released:
                raise RuntimeError("release_required")
            def recovered():
                status = self.status()
                pid = status.get("child_pid", 0)
                if status["phase"] != "running" or not pid:
                    return None
                command = subprocess.run(["ps", "-p", str(pid), "-o", "command="],
                                         capture_output=True, text=True, timeout=3).stdout
                return status if self.args.recovery in command else None
            status = wait_state(recovered, 45, "automatic_child_recovery_missing")
            assert_identity(self.before, status)
            self.record(action, status)
            return status
        elif action == "complete":
            if not self.released:
                raise RuntimeError("release_required")
            expected = (1, 0) if self.arm == "retained" else (0, 1)
            def transcript():
                self.alive()
                if self.args.scripted:
                    paths = list(self.home.glob("stdin.*"))
                else:
                    paths = list((self.home / ".claude" / "projects").rglob("*.jsonl"))
                data = "\n".join(path.read_text() for path in paths if path.is_file() and not path.is_symlink())
                try:
                    counts = prompt_counts(data, self.held, self.fresh)
                except ValueError:
                    return None  # Writer may be midway through its final JSON line.
                if counts[0] > expected[0] or counts[1] > expected[1]:
                    raise RuntimeError("duplicate_or_dropped_prompt_in_child_transcript")
                return data if counts == expected else None
            data = wait_state(transcript, 15, "completed_child_prompt_missing")
            (self.evidence / "child-transcript.jsonl").write_text(data)
            self.completed = True
        elif action == "close":
            self.close()
            return {"closed": True}
        status = self.status()
        assert_identity(self.before, status)
        self.record(action, status)
        return status

    def close(self):
        if self.child is not None and self.child.poll() is None:
            self.child.terminate()
            self.child.wait(timeout=15)
        if self.log is not None:
            self.log.close()
        if not self.completed:
            paths = list(self.home.glob("stdin.*")) if self.args.scripted else list((self.home / ".claude" / "projects").rglob("*.jsonl"))
            for index, path in enumerate(paths):
                if path.is_file() and not path.is_symlink():
                    shutil.copyfile(path, self.evidence / ("incomplete-child-" + str(index) + ".jsonl"))
        shutil.rmtree(self.home)


def main():
    parser = argparse.ArgumentParser()
    for name in ("daemon", "recovery", "daemon-relay", "phone-relay", "port-file", "evidence"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--scripted", action="store_true")
    args = parser.parse_args()
    args.daemon = str(Path(args.daemon).resolve(strict=True))
    args.recovery = str(Path(args.recovery).resolve(strict=True))
    os.umask(0o077)
    evidence = Path(args.evidence)
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    metadata = subprocess.run(["go", "version", "-m", args.daemon], capture_output=True, text=True, timeout=5)
    revision = next((line.split("vcs.revision=", 1)[1].strip() for line in metadata.stdout.splitlines() if "vcs.revision=" in line), "unavailable")
    (evidence / "context.json").write_text(json.dumps({
        "daemon_revision": revision, "daemon_sha256": hashlib.sha256(Path(args.daemon).read_bytes()).hexdigest(),
        "build_tag": "e2e_realclaude", "scripted": args.scripted, "retained_give_up": "default", "dropped_give_up": "3s",
    }))
    secret = secrets.token_hex(32)
    cases = {}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_POST(self):
            if not authorized(self.headers.get("Authorization"), secret):
                self.send_error(403)
                return
            try:
                arm, action = parse_action(self.path)
            except ValueError:
                self.send_error(404)
                return
            try:
                if action == "start":
                    if arm in cases:
                        raise RuntimeError("case_already_started")
                    case = Case(args, arm)
                    cases[arm] = case
                    result = case.start()
                elif action == "close" and arm not in cases:
                    result = {"closed": True}
                else:
                    result = cases[arm].action(action)
                    if action == "close":
                        del cases[arm]
                payload = json.dumps(result).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except (OSError, RuntimeError, KeyError, ValueError, subprocess.SubprocessError) as error:
                evidence = Path(args.evidence) / arm
                evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
                # RuntimeErrors in this module contain static codes only.
                code = str(error) if type(error) is RuntimeError else type(error).__name__
                (evidence / "failure-code.txt").write_text(code + "\n")
                # No exception details: pairing and process errors can contain credentials.
                self.send_error(503, "session_error_fixture_failed; inspect private evidence")

    server = HTTPServer(("127.0.0.1", 0), Handler)
    Path(args.port_file).write_text(json.dumps({"port": server.server_port, "authorization": secret}))

    def stop(_signal, _frame):
        raise SystemExit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        for case in cases.values():
            case.close()


if __name__ == "__main__":
    main()
