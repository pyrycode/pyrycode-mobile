"""Linux lifecycle regressions; no emulator, credentials or live turns."""
import errno
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest


def load():
    spec = importlib.util.spec_from_file_location(
        "capture", Path(__file__).with_name("e2e-capture-supervisor.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@unittest.skipUnless(sys.platform == "linux", "requires Linux child subreaper")
class CaptureLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = load()
        self.config = {"root": str(self.root / "evidence"), "run_id": "regression",
                       "maximum_seconds": 3, "grace_seconds": .15,
                       "sample_seconds": .02, "collectors": [], "artifacts": []}

    def gate(self, code):
        self.config["command"] = [sys.executable, "-c", code]

    def receipt(self):
        return json.loads((self.root / "evidence" / "outcome.json").read_text())

    def test_eagain_does_not_abandon_delayed_gate_or_detached_descendant(self):
        marker = self.root / "last-write"
        descendant = f"import time; time.sleep(.25); open({str(marker)!r}, 'w').write('done')"
        self.gate(f"import subprocess,sys,time; subprocess.Popen([sys.executable,'-c',{descendant!r}],start_new_session=True); time.sleep(.1)")
        def exhausted():
            raise OSError(errno.EAGAIN, "injected observation spawn failure")
        result = self.module.capture(self.config, observe=exhausted)
        self.assertEqual(result, 0)
        self.assertTrue(marker.exists())
        receipt = self.receipt()
        self.assertEqual(receipt["gate_exit"], 0)
        self.assertTrue(receipt["subtree_complete"])
        self.assertIn("observation:11", receipt["errors"])
        self.assertGreaterEqual(len(receipt["reaped"]), 2)

    def test_collector_start_failure_keeps_gate_result_and_waits(self):
        self.config["collectors"] = [["missing-capture-collector"]]
        marker = self.root / "gate-finished"
        self.gate(f"import time; time.sleep(.1); open({str(marker)!r},'w').write('done'); raise SystemExit(7)")
        self.assertEqual(self.module.capture(self.config), 7)
        self.assertTrue(marker.exists())
        self.assertEqual(self.receipt()["gate_exit"], 7)
        self.assertTrue(any(e.startswith("collector:") for e in self.receipt()["errors"]))

    def test_export_failure_still_attempts_other_exports_before_receipt(self):
        source = self.root / "available"
        source.mkdir()
        (source / "raw.xml").write_text("<testsuite/>")
        self.config["artifacts"] = [str(self.root / "missing"), str(source)]
        self.gate("raise SystemExit(9)")
        self.assertEqual(self.module.capture(self.config), 9)
        self.assertEqual(self.receipt()["gate_exit"], 9)
        self.assertIn("export:2", self.receipt()["errors"])
        self.assertTrue((self.root / "evidence" / "artifacts" / "1" / "raw.xml").exists())
        self.assertTrue(self.receipt()["subtree_complete"])

    def test_timeout_kills_term_ignoring_child_and_reaps(self):
        self.config["maximum_seconds"] = .15
        self.gate("import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(10)")
        self.assertEqual(self.module.capture(self.config), 124)
        receipt = self.receipt()
        self.assertTrue(receipt["timed_out"])
        self.assertEqual(receipt["gate_exit"], -signal.SIGKILL)
        self.assertTrue(receipt["subtree_complete"])

    def test_cancel_file_cleans_detached_child_after_gate_exit(self):
        cancel = self.root / "evidence" / "cancel"
        self.gate(f"import subprocess,sys; subprocess.Popen([sys.executable,'-c',\"import time; time.sleep(10)\"],start_new_session=True); open({str(cancel)!r},'w').write('cancel')")
        self.assertEqual(self.module.capture(self.config), 130)
        self.assertTrue(self.receipt()["cancelled"])
        self.assertTrue(self.receipt()["subtree_complete"])

    def test_signal_cancel_and_repeated_signal_cannot_skip_cleanup(self):
        config_path = self.root / "config.json"
        self.gate("import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(10)")
        config_path.write_text(json.dumps(self.config))
        p = subprocess.Popen([sys.executable, str(Path(__file__).with_name("e2e-capture-supervisor.py")), str(config_path)])
        try:
            import time
            for _ in range(200):
                state = self.root / "evidence" / "supervisor.json"
                if state.exists() and json.loads(state.read_text()).get("gate_pid"):
                    break
                time.sleep(.01)
            else:
                self.fail("supervisor did not launch gate")
            p.send_signal(signal.SIGTERM)
            p.send_signal(signal.SIGTERM)
            self.assertEqual(p.wait(timeout=5), 130)
            self.assertTrue(self.receipt()["subtree_complete"])
        finally:
            if p.poll() is None:
                p.kill()
                p.wait()

    def test_gate_start_failure_is_durable_without_children(self):
        self.config["command"] = ["missing-capture-gate"]
        self.assertEqual(self.module.capture(self.config), 125)
        self.assertIsNone(self.receipt()["gate_exit"])
        self.assertTrue(self.receipt()["subtree_complete"])


if __name__ == "__main__":
    unittest.main()
