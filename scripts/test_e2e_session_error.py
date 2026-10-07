import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

SPEC = importlib.util.spec_from_file_location(
    "session_error", Path(__file__).with_name("e2e-session-error.py")
)
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)


class SessionErrorControlTest(unittest.TestCase):
    def test_case_settings_are_private_and_give_up_is_dropped_only(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "PYRY_E2E_QUEUE_GIVE_UP_AFTER": "1ns",
            "PYRY_E2E_CLAUDE_BIN_FILE": "/inherited-selection",
            "PYRY_FAKE_CLAUDE_STREAM_REPLAY_FIRST": "/inherited-fixture",
            "CLAUDE_CODE_OAUTH_TOKEN": "test-credential",
        }):
            args = SimpleNamespace(evidence=directory, scripted=True, daemon_relay="ws://127.0.0.1:1")
            for arm in ("retained", "dropped"):
                case = fixture.Case(args, arm)
                try:
                    self.assertNotEqual(case.env["HOME"], os.environ.get("HOME"))
                    self.assertEqual(case.env["PYRY_E2E_CLAUDE_BIN_FILE"], str(case.selection))
                    self.assertEqual(case.env.get("PYRY_E2E_QUEUE_GIVE_UP_AFTER"), "3s" if arm == "dropped" else None)
                    reply = Path(case.env["PYRY_FAKE_CLAUDE_STREAM_REPLAY_FIRST"])
                    self.assertEqual(reply.parent, case.home)
                    self.assertEqual(reply.stat().st_mode & 0o777, 0o600)
                    assistant, result = [json.loads(line) for line in reply.read_text().splitlines()]
                    self.assertEqual(assistant["message"]["role"], "assistant")
                    self.assertEqual(assistant["message"]["content"], [{"type": "text", "text": "recovered1731"}])
                    self.assertEqual(result["subtype"], "success")
                    self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", case.env)
                finally:
                    case.close()
            self.assertEqual(os.environ["PYRY_E2E_QUEUE_GIVE_UP_AFTER"], "1ns")
            self.assertEqual(os.environ["PYRY_E2E_CLAUDE_BIN_FILE"], "/inherited-selection")

    def test_selection_is_private_atomic_and_absolute(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "selection"
            fixture.select_executable(path, Path("/bin/sh"))
            with path.open() as old:
                fixture.select_executable(path, Path("/bin/cat"))
                self.assertEqual(old.read(), "/bin/sh\n")
            self.assertEqual(path.read_text(), "/bin/cat\n")
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(ValueError):
                fixture.select_executable(path, Path("relative"))
            self.assertEqual(path.read_text(), "/bin/cat\n")

    def test_failing_child_closes_stdin_and_holds_first_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gate = root / "exit"
            executable = root / "fail"
            fixture.write_failing_executable(executable)
            child = subprocess.Popen(
                [str(executable)], stdin=subprocess.PIPE,
                env={**os.environ, "PYRY_E2E_EXIT_GATE": str(gate)},
            )
            try:
                fixture.wait_state(lambda: Path(str(gate) + ".ready").exists(), 3, "ready")
                self.assertIsNone(child.poll())
                with self.assertRaises(BrokenPipeError):
                    child.stdin.write(b"must not deliver\n")
                    child.stdin.flush()
                gate.touch(mode=0o600)
                self.assertEqual(child.wait(timeout=3), 1)
                self.assertEqual(subprocess.run(
                    [str(executable)], env={**os.environ, "PYRY_E2E_EXIT_GATE": str(gate)},
                    timeout=3,
                ).returncode, 1)
            finally:
                if child.poll() is None:
                    child.terminate()
                    child.wait(timeout=3)
                try:
                    child.stdin.close()
                except BrokenPipeError:
                    pass

    def test_state_wait_is_bounded_and_names_missing_state(self):
        before = time.monotonic()
        with self.assertRaisesRegex(RuntimeError, "missing_child"):
            fixture.wait_state(lambda: False, 0.05, "missing_child")
        self.assertLess(time.monotonic() - before, 1)

    def test_identity_rejects_runner_restart_and_session_rotation(self):
        before = {"daemon_pid": 123, "started_at": "start", "session_id": "session", "restart_count": 3}
        after = {**before, "restart_count": 4}
        fixture.assert_identity(before, after)
        for key in ("daemon_pid", "started_at", "session_id"):
            with self.subTest(key=key), self.assertRaisesRegex(RuntimeError, "identity_changed"):
                fixture.assert_identity(before, {**after, key: "changed"})
        with self.assertRaisesRegex(RuntimeError, "restart_counter_reset"):
            fixture.assert_identity(before, {**after, "restart_count": 0})

    def test_completed_transcript_counts_user_prompts_not_assistant_quotes(self):
        held, fresh = "marker=held-1731", "marker=fresh-1731"
        data = "\n".join(json.dumps(line) for line in [
            {"type": "user", "message": {"role": "user", "content": fresh}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": held}]}},
        ])
        self.assertEqual(fixture.prompt_counts(data, held, fresh), (0, 1))
        self.assertEqual(fixture.prompt_counts(data + "\n" + data, held, fresh), (0, 2))

    def test_authorization_and_allowlist_precede_mutation(self):
        self.assertFalse(fixture.authorized("wrong", "secret"))
        self.assertFalse(fixture.authorized(None, "secret"))
        self.assertTrue(fixture.authorized("Bearer secret", "secret"))
        for path in ("/retained/restart", "/production/release", "/retained/../release"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                fixture.parse_action(path)
        self.assertEqual(fixture.parse_action("/dropped/release"), ("dropped", "release"))

    def test_http_rejects_unauthorized_and_unknown_actions_without_starting_a_case(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence, port_file = root / "evidence", root / "port.json"
            controller = subprocess.Popen([
                sys.executable, str(Path(__file__).with_name("e2e-session-error.py")),
                "--daemon", "/bin/sh", "--recovery", "/bin/cat", "--scripted",
                "--daemon-relay", "ws://127.0.0.1:1", "--phone-relay", "ws://10.0.2.2:1",
                "--port-file", str(port_file), "--evidence", str(evidence),
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                fixture.wait_state(lambda: port_file.exists() and port_file.stat().st_size, 5, "control_start")
                config = json.loads(port_file.read_text())
                self.assertEqual(port_file.stat().st_mode & 0o777, 0o600)
                for path, authorization, status in (
                    ("/retained/start", "Bearer wrong", 403),
                    ("/retained/restart", "Bearer " + config["authorization"], 404),
                ):
                    request = Request(f'http://127.0.0.1:{config["port"]}{path}', data=b"",
                                      headers={"Authorization": authorization})
                    with self.subTest(path=path), self.assertRaises(HTTPError) as failure:
                        urlopen(request, timeout=3)
                    self.assertEqual(failure.exception.code, status)
                    failure.exception.close()
                self.assertFalse((evidence / "retained").exists())
            finally:
                controller.terminate()
                controller.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
