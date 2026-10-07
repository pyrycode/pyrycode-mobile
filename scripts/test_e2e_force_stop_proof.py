"""External proof rejects missing process boundaries and duplicate synthetic rows."""
import importlib.util
import contextlib
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import subprocess
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("proof", Path(__file__).with_name("e2e-force-stop-proof.py"))
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class ForceStopProofTest(unittest.TestCase):
    ADB_FAILURES = (subprocess.TimeoutExpired("adb", 30), OSError("adb missing"), KeyboardInterrupt())

    def owned_emulator(self):
        child = subprocess.Popen(["sleep", "60"])
        self.addCleanup(lambda: child.poll() is None and (child.kill(), child.wait()))
        return child

    @staticmethod
    @contextlib.contextmanager
    def custody(events, child):
        try:
            yield
        finally:
            events.append(("custody released", child.returncode))

    def test_adb_failure_on_teardown_still_reaps_the_emulator(self):
        for failure in self.ADB_FAILURES:
            with self.subTest(failure=type(failure).__name__):
                child = self.owned_emulator()
                started = time.monotonic()
                with patch.object(proof.gate.subprocess, "run", side_effect=failure):
                    if isinstance(failure, KeyboardInterrupt):
                        with self.assertRaises(KeyboardInterrupt):
                            proof.gate.stop_emulator({"ANDROID_HOME": "/fixture-sdk"}, "emulator-5600", child)
                    else:
                        proof.gate.stop_emulator({"ANDROID_HOME": "/fixture-sdk"}, "emulator-5600", child)
                self.assertIsNotNone(child.returncode, "the owned emulator must be reaped")
                self.assertLess(time.monotonic() - started, 10, "an undelivered stop must not wait out the timeout")

    def test_cancel_during_boot_with_failing_adb_reaps_emulator_before_custody_release(self):
        for failure in self.ADB_FAILURES[:2]:
            with self.subTest(failure=type(failure).__name__):
                child = self.owned_emulator()
                events = []
                # The boot poll is cancelled, then the real teardown's ADB request fails too.
                with self.assertRaises(KeyboardInterrupt), self.custody(events, child), \
                     patch.object(proof.gate, "free_emulator_port", return_value=5600), \
                     patch.object(proof.gate.subprocess, "Popen", return_value=child), \
                     patch.object(proof.gate.subprocess, "run", side_effect=[KeyboardInterrupt(), failure]):
                    proof.gate.boot_emulator({"ANDROID_HOME": "/fixture-sdk"}, "/fixture-avd", "fixture")
                self.assertEqual(1, len(events))
                self.assertIsNotNone(events[0][1], "custody released while the emulator still ran")

    def test_proof_failure_with_adb_timeout_reaps_emulator_before_custody_release(self):
        booted = SimpleNamespace(returncode=0, stdout="1\n")
        for failure in self.ADB_FAILURES[:2]:
            with self.subTest(failure=type(failure).__name__), tempfile.TemporaryDirectory() as temp:
                child = self.owned_emulator()
                events = []
                previous = proof.signal.getsignal(proof.signal.SIGTERM)
                self.addCleanup(proof.signal.signal, proof.signal.SIGTERM, previous)
                env = {"ANDROID_HOME": "/fixture-sdk", "PYRY_BIN": "pyry", "RELAY_BIN": "relay"}
                # The owned device fails mid-proof, and the teardown's ADB request then fails as well.
                with patch.dict(os.environ, env), patch.object(proof, "ROOT", Path(temp)), \
                     patch.object(proof.gate, "build_apks", return_value=0), \
                     patch.object(proof.gate, "device_hold", side_effect=lambda *_: self.custody(events, child)), \
                     patch.object(proof.gate, "managed_avd", return_value=("/fixture-avd", "fixture")), \
                     patch.object(proof.gate, "free_emulator_port", return_value=5600), \
                     patch.object(proof.gate.subprocess, "Popen", return_value=child), \
                     patch.object(proof.gate.subprocess, "run", side_effect=[booted, failure]), \
                     patch.object(proof.gate, "install_once", return_value=None), \
                     self.assertRaisesRegex(RuntimeError, "installation failed"):
                    proof.main()
                self.assertEqual(1, len(events))
                self.assertIsNotNone(events[0][1], "custody released while the emulator still ran")

    def test_cancel_harness_runs_exit_cleanup_and_reaps_descendant_before_returning(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            script = folder / "harness.sh"
            traps = "\n".join(line for line in (proof.ROOT / "scripts/e2e-emulator.sh").read_text().splitlines()
                              if line.startswith("trap "))
            script.write_text('''#!/bin/bash
sleep 60 &
child=$!
cleanup() { kill "$child" 2>/dev/null; wait "$child" 2>/dev/null; echo cleaned >> "$PROOF_TEMP/cleaned"; }
''' + traps + '''
echo "$child" > "$PROOF_TEMP/child"
wait "$child"
''')
            real_popen = subprocess.Popen
            processes = []

            def launch(*args, **kwargs):
                process = real_popen(*args, **kwargs)
                processes.append(process)
                wait = process.wait
                first = True

                def cancel_once(*args, **kwargs):
                    nonlocal first
                    if first:
                        first = False
                        deadline = time.monotonic() + 5
                        while not (folder / "child").exists() and time.monotonic() < deadline:
                            time.sleep(0.01)
                        self.assertTrue((folder / "child").exists(), "harness never started")
                        raise KeyboardInterrupt
                    return wait(*args, **kwargs)

                process.wait = cancel_once
                return process

            try:
                with patch.object(proof, "ROOT", folder), \
                     patch.object(proof.subprocess, "Popen", side_effect=launch), \
                     self.assertRaises(KeyboardInterrupt):
                    (folder / "scripts").mkdir()
                    script.rename(folder / "scripts/e2e-emulator.sh")
                    proof.run_harness({**os.environ, "PROOF_TEMP": temp})
                self.assertEqual("cleaned\n", (folder / "cleaned").read_text(), "EXIT cleanup must finish once")
                self.assertIsNotNone(processes[0].poll())
                with self.assertRaises(ProcessLookupError):
                    os.kill(int((folder / "child").read_text()), 0)
            finally:
                for process in processes:
                    if process.poll() is None:
                        process.terminate()
                        process.wait(timeout=5)

    def execute(self, duplicate=False, survives=False):
        fixture = {"channel": "e2e-stop-123", "prefix": "e2e1833-stop-123", "conversation_id": "fixture-id"}
        calls = []
        state = {"stopped": False, "running": True}

        def adb(_env, _serial, *args, **_kwargs):
            calls.append(args)
            text, code = "", 0
            if args[:3] == ("shell", "run-as", proof.APP):
                text = json.dumps(fixture)
            elif args[:2] == ("shell", "pidof"):
                text = ("78" if state["stopped"] else "77") if state["running"] else ""
                code = 0 if text else 1
            elif args[:3] == ("shell", "am", "force-stop"):
                state.update(stopped=True, running=survives)
            elif args[:3] == ("shell", "am", "start"):
                state["running"] = True
            elif args[:2] == ("shell", "cat"):
                message = fixture["prefix"] + ("-new-000" if state["stopped"] else "-baseline-000")
                node = '<node text="' + message + '" bounds="[0,0][100,100]"/>'
                text = '<hierarchy>' + node * (2 if duplicate and state["stopped"] else 1) + '</hierarchy>'
            return SimpleNamespace(returncode=code, stdout=text)

        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *_args): pass

        def request(req, **_kwargs):
            calls.append((req.full_url.rsplit('/', 1)[-1],))
            return Response()

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp) / "proof.json"
            with patch.dict(os.environ, {"ANDROID_SERIAL":"emulator-5600", "EXTERNAL_FORCE_STOP_PROOF":"1", "PYRY_BIN":"fixture"}), \
                 patch.object(proof.gate, "adb_call", side_effect=adb), \
                 patch.object(proof.urllib.request, "urlopen", side_effect=request), \
                 patch.object(proof.subprocess, "check_output", return_value="test-version"):
                if duplicate or survives:
                    with self.assertRaises(RuntimeError):
                        proof.owned_worker("1234", "app-rev", "daemon-rev", str(evidence))
                else:
                    proof.owned_worker("1234", "app-rev", "daemon-rev", str(evidence))
            return json.loads(evidence.read_text()), calls

    def test_actual_stop_post_restart_relaunch_and_sanitized_allowlisted_evidence(self):
        record, calls = self.execute()
        self.assertEqual("PASS", record["result"])
        self.assertTrue(record["process_stopped"] and record["process_relaunched"])
        self.assertEqual(1, record["post_count"])
        self.assertIn("stopped_utc", record)
        self.assertIn("relaunched_utc", record)
        self.assertEqual("test-version", record["daemon_version"])
        self.assertFalse(record["scrolling"])
        self.assertLess(calls.index(("shell", "am", "force-stop", proof.APP)), calls.index(("posts",)))
        self.assertLess(calls.index(("posts",)), calls.index(("stop",)))
        self.assertNotIn("payload", json.dumps(record))
        self.assertFalse(any("swipe" in call or "clear" in call for call in calls))

    def test_surviving_process_is_a_failed_proof(self):
        record, calls = self.execute(survives=True)
        self.assertEqual("FAIL", record["result"])
        self.assertNotIn(("posts",), calls)

    def test_duplicate_post_is_a_failed_proof(self):
        record, _ = self.execute(duplicate=True)
        self.assertEqual("FAIL", record["result"])
