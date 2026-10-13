"""Process ownership proof for the e2e Offline Retry fault controller."""

import json
import importlib.util
import os
import signal
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent


class EmulatorControllerLaunchTest(unittest.TestCase):
    def check_launch(self, deterministic, live, scenario, fence, controls):
        harness = (ROOT / "e2e-emulator.sh").read_text()
        launch = harness[harness.index("DAEMON_COMMAND=(env)"):harness.index("# The second test daemon")]
        with tempfile.TemporaryDirectory(prefix="e2e controller ") as temporary:
            directory = Path(temporary)
            captured = directory / "controller-arguments"
            isolated = directory / "isolated home" if deterministic else ""
            environment = {
                **os.environ, "DETERMINISTIC": deterministic, "LIVE": live,
                "SCENARIO": scenario, "DROP_B_FENCE": fence,
                "WORK_DIR": temporary, "REPO_ROOT": str(ROOT.parent),
                "DAEMON_LOG": str(directory / "daemon.log"), "ISO_HOME": str(isolated),
                "PYRY_NAME": "e2e-launch-probe", "PYRY_BIN": "/synthetic pyry",
                "DAEMON_RELAY_URL": "ws://127.0.0.1:1", "FAKE_BIN": "/synthetic fakeclaude",
                "FIXTURE_FILE": "/synthetic opening", "FIXTURE_FILE_2": "/synthetic ending" if controls else "",
                "REPLAY_RELEASE": str(directory / "release-second-fragment"),
                "CONTROLLER_CAPTURE": str(captured),
            }
            # Run the real top-level launch with macOS's Bash 3.2, without a daemon or credentials.
            # The stub records argument boundaries and acknowledges controller readiness only.
            result = subprocess.run(
                ["/bin/bash", "-c", """set -euo pipefail
python3() {
  printf '%s\\0' "$@" > "$CONTROLLER_CAPTURE"
  printf '12345' > "$FAULT_PORT_FILE"
}
die() { printf '%s\\n' "$1" >&2; exit 1; }
""" + launch + '\nwait "$DAEMON_PID"'],
                env=environment, capture_output=True, text=True, timeout=15,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            arguments = captured.read_bytes().decode().split("\0")[:-1]
            delimiter = arguments.index("--")
            controller_home = str(isolated) if deterministic else os.environ["HOME"]
            expected = [
                str(ROOT / "e2e-daemon-fault.py"), "--port-file", str(directory / "daemon-fault-port"),
                "--log", str(directory / "daemon.log"), "--ready-token", "relay: conn established",
                "--control-socket", str(Path(controller_home) / ".pyry/e2e-launch-probe.sock"),
                "--cleanup-sockets-file", str(directory / "test-session-sockets.json"),
            ]
            if controls:
                expected += ["--stream-release-file", str(directory / "release-second-fragment")]
                if scenario == "reopen-stream":
                    expected += ["--stream-result-release-file", str(directory / "release-terminal-result")]
            self.assertEqual(expected, arguments[:delimiter])
            daemon = arguments[delimiter + 1:]
            self.assertEqual("env", daemon[0])
            self.assertIn("/synthetic pyry", daemon)
            self.assertIn("-pyry-name=e2e-launch-probe", daemon)
            self.assertEqual(not live, "PYRY_ALLOW_INSECURE_RELAY=1" in daemon)

    def test_scripted_ping_launches_without_optional_stream_controls(self):
        self.check_launch("1", "", "ping", "enqueue", False)

    def test_default_real_claude_launches_without_optional_stream_controls(self):
        self.check_launch("", "", "ping", "enqueue", False)

    def test_live_real_claude_launches_without_optional_stream_controls(self):
        self.check_launch("", "1", "ping", "enqueue", False)

    def test_stream_launch_preserves_suffix_release_argument(self):
        self.check_launch("1", "", "stream", "phone-checkpoint", True)

    def test_reopen_stream_launch_preserves_both_release_arguments(self):
        self.check_launch("1", "", "reopen-stream", "phone-checkpoint", True)


class DaemonFaultTest(unittest.TestCase):
    def test_authored_completion_requires_daemon_recorded_successor_and_agent(self):
        spec = importlib.util.spec_from_file_location('fault', ROOT / 'e2e-daemon-fault.py')
        module = importlib.util.module_from_spec(spec)
        sys.path.insert(0, str(ROOT))
        try:
            spec.loader.exec_module(module)
        finally:
            sys.path.pop(0)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            socket = root / 'e2e-fixture.sock'
            history = root / 'e2e-fixture/conversations/channel/history'
            history.mkdir(parents=True)
            entry = {'type': 'turn_end', 'payload': {'turn_id': 'authored'},
                     'session': {'kind': 'codex', 'session_id': 'successor'}}
            (history / 'segment-00000000000000000001.jsonl').write_text(json.dumps(entry) + '\n')
            body = {'conversation': 'channel', 'turn': 'authored', 'session': 'successor', 'agent': 'codex'}
            self.assertTrue(module.turn_matches_session(socket, body))
            for key, value in [('session', 'predecessor'), ('agent', 'claude'), ('turn', 'handover')]:
                self.assertFalse(module.turn_matches_session(socket, dict(body, **{key: value})))
            for value in ('../production', '', 'x' * 129):
                with self.assertRaises(ValueError):
                    module.turn_matches_session(socket, dict(body, conversation=value))
            segment = history / 'segment-00000000000000000001.jsonl'
            segment.write_text(json.dumps(entry) + '\n' + json.dumps(entry) + '\n')
            self.assertFalse(module.turn_matches_session(socket, body))
            segment.unlink()
            victim = root / 'private-login'
            victim.write_text('fixture-secret')
            segment.symlink_to(victim)
            with self.assertRaises(ValueError):
                module.turn_matches_session(socket, body)
            self.assertEqual('fixture-secret', victim.read_text())
            segment.unlink()
            entry.pop('session')
            (history / 'segment-00000000000000000001.jsonl').write_text(json.dumps(entry) + '\n')
            self.assertFalse(module.turn_matches_session(socket, body))


    def test_termination_kills_and_joins_daemon_that_ignores_term(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            ready = path / "daemon-pid"
            controller = subprocess.Popen([
                sys.executable, str(ROOT / "e2e-daemon-fault.py"),
                "--port-file", str(path / "port"), "--log", str(path / "log"),
                "--control-socket", str(path / "socket"), "--", sys.executable, "-c",
                "import os,signal,time; from pathlib import Path; "
                "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                "Path(os.environ['READY']).write_text(str(os.getpid())); time.sleep(120)",
            ], env=dict(os.environ, READY=str(ready)), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            try:
                deadline = time.monotonic() + 5
                while not ready.exists() and controller.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(ready.exists())
                pid = int(ready.read_text())
                controller.terminate()
                controller.wait(timeout=15)
                self.assertEqual(0, controller.returncode)
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
            finally:
                if ready.exists():
                    try:
                        os.kill(int(ready.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                if controller.poll() is None:
                    controller.kill()
                controller.communicate(timeout=5)

    def test_stop_then_start_replaces_only_the_owned_child(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp).resolve()
            controller = subprocess.Popen(
                [
                    sys.executable,
                    str(ROOT / "e2e-daemon-fault.py"),
                    "--port-file",
                    str(path / "port"),
                    "--log",
                    str(path / "child.log"),
                    "--control-socket",
                    str(path / "e2e-fixture.sock"),
                    "--ready-token",
                    "relay: conn established",
                    "--stream-release-file",
                    str(path / "suffix-release"),
                    "--stream-result-release-file",
                    str(path / "result-release"),
                    "--",
                    sys.executable,
                    "-c",
                    "import time; time.sleep(0.3); print('relay: conn established', flush=True); time.sleep(120)",
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
            try:
                deadline = time.monotonic() + 5
                while not (path / "port").exists() and time.monotonic() < deadline:
                    time.sleep(0.05)
                self.assertTrue(
                    (path / "port").exists(),
                    f"controller did not publish its port: {controller.stderr.read().decode() if controller.poll() is not None else 'still running'}",
                )
                port = int((path / "port").read_text())

                def request(action):
                    with urlopen(Request(f"http://127.0.0.1:{port}/{action}", data=b""), timeout=5) as response:
                        self.assertEqual(response.status, 200)
                        return json.load(response)

                first = request("status")
                self.assertTrue(first["running"])
                history = path / "e2e-fixture/conversations/channel/history"
                history.mkdir(parents=True)
                entry = {"type": "turn_end", "payload": {"turn_id": "authored"},
                         "session": {"kind": "codex", "session_id": "successor"}}
                (history / "segment-00000000000000000001.jsonl").write_text(json.dumps(entry) + "\n")
                for session, matched in (("successor", True), ("predecessor", False)):
                    body = {"conversation": "channel", "turn": "authored", "session": session, "agent": "codex"}
                    with urlopen(Request(f"http://127.0.0.1:{port}/turn-provenance", data=json.dumps(body).encode()), timeout=5) as response:
                        self.assertEqual(200, response.status)
                        self.assertEqual({"matches": matched}, json.load(response))
                self.assertFalse((path / "suffix-release").exists())
                self.assertFalse((path / "result-release").exists())
                self.assertEqual(first["pid"], request("stream-release")["pid"])
                self.assertEqual(b"", (path / "suffix-release").read_bytes())
                self.assertFalse((path / "result-release").exists())
                self.assertEqual(first["pid"], request("stream-finish")["pid"])
                self.assertEqual(b"", (path / "result-release").read_bytes())
                request("stop")
                self.assertFalse(request("status")["running"])
                restarted_at = time.monotonic()
                request("start")
                self.assertGreaterEqual(time.monotonic() - restarted_at, 0.25)
                second = request("status")
                self.assertTrue(second["running"])
                self.assertNotEqual(first["pid"], second["pid"])
            finally:
                controller.terminate()
                controller.wait(timeout=5)
                controller.stderr.close()


import importlib.util
import socket
import threading

spec = importlib.util.spec_from_file_location("fault", Path(__file__).with_name("e2e-daemon-fault.py"))
fault = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fault)


class ChannelPostTest(unittest.TestCase):
    def test_posts_only_bounded_synthetic_content_to_the_fixed_owned_socket(self):
        with tempfile.TemporaryDirectory(prefix="e2e1833-") as root:
            path = str(Path(root) / "control.sock")
            seen = []
            with socket.socket(socket.AF_UNIX) as server:
                server.bind(path)
                server.listen()
                def receive():
                    for _ in range(2):
                        with server.accept()[0] as client:
                            seen.append(json.loads(client.makefile("rb").readline()))
                            client.sendall(b'{"ok":true}\n')
                worker = threading.Thread(target=receive)
                worker.start()
                fault.post_batch(path, {"name": "e2e-gap", "prefix": "e2e1833-test", "count": 2})
                worker.join(5)
                self.assertFalse(worker.is_alive())
            self.assertEqual(["e2e1833-test-000", "e2e1833-test-001"], [x["channelPost"]["text"] for x in seen])
            self.assertTrue(all(x["verb"] == "channel.post" for x in seen))

    def test_invalid_controls_fail_before_dial_without_echoing_input(self):
        for body in [{"name":"production", "prefix":"secret", "count":1},
                     {"name":"e2e-gap", "prefix":"e2e1833-test", "count":201},
                     {"name":"e2e-gap", "prefix":"e2e1833-test", "count":True}]:
            with self.assertRaisesRegex(ValueError, "invalid synthetic post control"):
                fault.post_batch("/not/a/socket", body)


if __name__ == "__main__":
    unittest.main()
