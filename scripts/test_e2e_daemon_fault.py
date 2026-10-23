"""Process ownership proof for the e2e Offline Retry fault controller."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from contextlib import contextmanager


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
    def test_stop_then_start_replaces_only_the_owned_child(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            controller = subprocess.Popen(
                [
                    sys.executable,
                    str(ROOT / "e2e-daemon-fault.py"),
                    "--port-file",
                    str(path / "port"),
                    "--log",
                    str(path / "child.log"),
                    "--control-socket",
                    str(path / "control.sock"),
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


class GenerationReadinessTest(unittest.TestCase):
    @contextmanager
    def controller(self, script, token="relay: conn established", executable=False, exit_after_registration=False):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            launch = [sys.executable, str(ROOT / "e2e-daemon-fault.py")]
            if exit_after_registration:
                # Trace the response snapshot after registration, then join the owned child before refresh.
                # This forces the boundary without relying on a short process lifetime or poll count.
                launch = [sys.executable, "-c", """import importlib.util, json, os, sys
from pathlib import Path
sys.path.insert(0, sys.argv.pop(1))
spec = importlib.util.spec_from_file_location('fault', Path(sys.path[0]) / 'e2e-daemon-fault.py')
fault = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fault)
def boundary(frame, event, arg):
    if (frame.f_code.co_name == 'evidence' and event == 'call'
            and frame.f_locals['action'] == 'start' and frame.f_locals['ready']):
        child = frame.f_locals['child']
        Path(os.environ['GENERATION_FIXTURE'], 'observed-ready.json').write_text(json.dumps({
            'ready': frame.f_locals['ready'], 'phase': frame.f_locals['phase'],
            'generation': frame.f_locals['generation'], 'running': child.poll() is None}))
        child.terminate()
        child.wait(timeout=5)
    return boundary
sys.settrace(boundary)
fault.main()
""", str(ROOT)]
            command = [sys.executable, "-c", script]
            if executable:
                child_script = path / "child"
                child_script.write_text(f"#!{sys.executable}\n" + script + "\n")
                child_script.chmod(0o700)
                command = [str(child_script)]
            with (path / "controller.log").open("wb") as output:
                process = subprocess.Popen([
                    *launch,
                    "--port-file", str(path / "port"), "--log", str(path / "child.log"),
                    "--control-socket", str(path / "control.sock"), "--ready-token", token,
                    "--ready-timeout", "0.25",
                    "--", *command,
                ], env={**os.environ, "GENERATION_FIXTURE": str(path)}, stdout=output, stderr=output)
                try:
                    deadline = time.monotonic() + 5
                    while not (path / "port").exists() and process.poll() is None and time.monotonic() < deadline:
                        time.sleep(0.02)
                    self.assertTrue((path / "port").exists(), "controller did not publish its port")
                    port = int((path / "port").read_text())

                    def request(action):
                        try:
                            response = urlopen(Request(f"http://127.0.0.1:{port}/{action}", data=b""), timeout=17)
                        except HTTPError as error:
                            response = error
                        with response:
                            body = response.read()
                            try:
                                payload = json.loads(body)
                            except ValueError:
                                payload = {}
                            return response.status, payload

                    try:
                        yield request, path
                    except AssertionError as failure:
                        child_output = (path / "child.log").read_bytes()
                        self.fail(f"{failure}\nchild_bytes={len(child_output)}, "
                                  f"complete_token={token.encode() in child_output}\n"
                                  + (path / "controller.log").read_text())
                finally:
                    process.terminate()
                    process.wait(timeout=12)

    def test_initial_unready_child_cannot_succeed(self):
        with self.controller("import time; time.sleep(120)") as (request, _):
            before = request("status")[1]
            self.assertTrue(before["running"])
            status, _ = request("start")
            self.assertEqual(503, status, "a living child is not relay readiness")
            self.assertEqual(before["pid"], request("status")[1]["pid"])

    def test_timeout_then_start_remains_unready_without_duplicate_child(self):
        with self.controller("import time; time.sleep(120)") as (request, _):
            request("stop")
            self.assertEqual(503, request("start")[0])
            first = request("status")[1]
            status, second = request("start")
            self.assertEqual(503, status)
            self.assertEqual(first["pid"], second["pid"])
            self.assertEqual(first["generation"], second["generation"])
            self.assertEqual("registration_timeout", second["phase"])
            self.assertFalse(second["ready"])

    def test_late_readiness_qualifies_only_the_same_living_generation(self):
        script = """import os,time
from pathlib import Path
release = Path(os.environ['GENERATION_FIXTURE']) / 'release'
while not release.exists(): time.sleep(0.01)
print('relay: conn established', flush=True)
time.sleep(120)
"""
        with self.controller(script) as (request, path):
            self.assertEqual(503, request("start")[0])
            first = request("status")[1]
            (path / "release").touch()
            status, ready = request("start")
            self.assertEqual(200, status)
            self.assertTrue(ready["ready"])
            self.assertEqual(first["pid"], ready["pid"])
            self.assertEqual(first["generation"], ready["generation"])

    def test_ready_start_is_idempotent(self):
        with self.controller("import time; print('relay: conn established', flush=True); time.sleep(120)") as (request, _):
            status, first = request("start")
            self.assertEqual(200, status)
            for _ in range(2):
                status, repeated = request("start")
                self.assertEqual(200, status)
                self.assertEqual(first["pid"], repeated["pid"])
                self.assertEqual(first["generation"], repeated["generation"])
                self.assertTrue(repeated["ready"])

    def first_generation_ready_script(self, exit_after_ready=False):
        return """import os,time
from pathlib import Path
path = Path(os.environ['GENERATION_FIXTURE'])
first = not (path / 'spawned').exists()
(path / 'spawned').touch()
if first: print('relay: conn established', flush=True)
if first and EXIT_AFTER_READY:
    while not (path / 'exit').exists(): time.sleep(0.01)
else: time.sleep(120)
""".replace("EXIT_AFTER_READY", repr(exit_after_ready))

    def test_stopped_generation_token_cannot_qualify_successor(self):
        with self.controller(self.first_generation_ready_script()) as (request, _):
            status, first = request("start")
            self.assertEqual(200, status)
            stopped = request("stop")[1]
            self.assertFalse(stopped["ready"])
            status, next_child = request("start")
            self.assertEqual(503, status)
            self.assertGreater(next_child["generation"], first["generation"])
            self.assertFalse(next_child["ready"])

    def test_exited_generation_token_cannot_qualify_successor(self):
        with self.controller(self.first_generation_ready_script(True)) as (request, path):
            self.assertEqual(200, request("start")[0])
            (path / "exit").touch()
            deadline = time.monotonic() + 3
            while request("status")[1]["running"] and time.monotonic() < deadline:
                time.sleep(0.02)
            exited = request("status")[1]
            self.assertFalse(exited["running"])
            self.assertFalse(exited["ready"])
            self.assertEqual("exited", exited["phase"])
            self.assertEqual(503, request("start")[0])

    def test_early_exit_reports_exit(self):
        with self.controller("import time; time.sleep(0.05)") as (request, _):
            status, payload = request("start")
            self.assertEqual(503, status)
            self.assertEqual("exited", payload["phase"])
            self.assertFalse(payload["running"])
            self.assertFalse(payload["ready"])

    def test_spawn_failure_is_not_an_exit_of_the_new_generation(self):
        script = "import time; print('relay: conn established', flush=True); time.sleep(120)"
        with self.controller(script, executable=True) as (request, path):
            # This proof starts from a registered predecessor. Process startup
            # is setup, not part of the controller's short readiness deadline.
            deadline = time.monotonic() + 5
            while b'relay: conn established' not in (path / 'child.log').read_bytes():
                self.assertLess(time.monotonic(), deadline, 'fixture child did not finish startup')
                time.sleep(0.01)
            status, first = request("start")
            self.assertEqual(200, status)
            self.assertTrue(first["ready"])
            self.assertEqual(200, request("stop")[0])
            (path / "child").unlink()
            status, failed = request("start")
            self.assertEqual(503, status)
            self.assertEqual(first["generation"] + 1, failed["generation"])
            self.assertEqual("spawn_failed", failed["phase"])
            self.assertFalse(failed["running"])
            self.assertFalse(failed["ready"])
            self.assertIsNone(failed["pid"])
            self.assertEqual("spawn_failed", request("status")[1]["phase"])
            records = [json.loads(line) for line in (path / "controller.log").read_text().splitlines()]
            transition = next(row for row in records if row["action"] == "start" and row["generation"] == failed["generation"])
            self.assertEqual(failed, {key: transition[key] for key in failed})
            spawn = next(row for row in records if row["action"] == "spawn" and row["generation"] == failed["generation"])
            self.assertEqual((503, "spawn_failed", False, False),
                             (spawn["http_status"], spawn["phase"], spawn["running"], spawn["ready"]))

    def test_success_snapshot_cannot_report_an_exited_or_unready_child(self):
        script = "import time; print('relay: conn established', flush=True); time.sleep(120)"
        with self.controller(script, exit_after_registration=True) as (request, path):
            status, payload = request("start")
            observed = json.loads((path / "observed-ready.json").read_text())
            self.assertEqual({"ready": True, "phase": "ready", "running": True,
                              "generation": payload["generation"]}, observed)
            self.assertEqual(503, status)
            self.assertEqual("exited", payload["phase"])
            self.assertFalse(payload["running"])
            self.assertFalse(payload["ready"])
            records = [json.loads(line) for line in (path / "controller.log").read_text().splitlines()]
            transition = records[-1]
            self.assertEqual("daemon_fault_transition", transition["event"])
            self.assertEqual(payload, {key: transition[key] for key in payload})

    def test_no_token_mode_preserves_owned_process_only(self):
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(120)"])
        try:
            with self.controller("import time; time.sleep(120)", token="") as (request, _):
                status, first = request("start")
                self.assertEqual(200, status)
                self.assertEqual("disabled", first["phase"])
                self.assertEqual(first["pid"], request("start")[1]["pid"])
                request("stop")
                self.assertIsNone(unrelated.poll())
                self.assertEqual(200, request("start")[0])
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=5)

    def test_registration_token_across_chunks(self):
        self.assert_chunked_registration()

    def test_registration_token_across_chunks_after_delayed_startup(self):
        self.assert_chunked_registration(startup_delay=0.75)

    def assert_chunked_registration(self, startup_delay=0):
        script = f"""import os,sys,time
from pathlib import Path
path = Path(os.environ['GENERATION_FIXTURE'])
time.sleep({startup_delay})
sys.stdout.write('x' * 4090 + 'relay:')
sys.stdout.flush()
while not (path / 'complete-token').exists(): time.sleep(0.01)
sys.stdout.write(' conn established')
sys.stdout.flush()
time.sleep(120)
"""
        with self.controller(script) as (request, path):
            def await_output(expected):
                # Child startup is setup; it must not consume the 250 ms scan budget.
                deadline = time.monotonic() + 5
                while (path / "child.log").stat().st_size < len(expected):
                    self.assertLess(time.monotonic(), deadline, "fixture output did not arrive")
                    time.sleep(0.01)
                self.assertEqual(expected, (path / "child.log").read_bytes())

            prefix = b"x" * 4090 + b"relay:"
            self.assertEqual(4096, len(prefix))
            await_output(prefix)
            status, partial = request("start")
            self.assertEqual(503, status, partial)
            self.assertEqual("registration_timeout", partial["phase"])
            self.assertTrue(partial["running"])
            self.assertFalse(partial["ready"])
            (path / "complete-token").touch()
            await_output(prefix + b" conn established")
            status, complete = request("start")
            self.assertEqual(200, status, complete)
            self.assertTrue(complete["running"])
            self.assertTrue(complete["ready"])
            self.assertEqual(partial["pid"], complete["pid"])
            self.assertEqual(partial["generation"], complete["generation"])


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
