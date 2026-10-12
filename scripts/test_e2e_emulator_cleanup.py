from pathlib import Path
import os
import subprocess
import tempfile
import unittest


class EmulatorCleanupTest(unittest.TestCase):
    def test_cleanup_preserves_result_with_and_without_isolated_home(self):
        script = Path(__file__).with_name("e2e-emulator.sh").read_text()
        cleanup = script[script.index("cleanup() {"):script.index("trap cleanup EXIT")]
        for code in (0, 7):
            for isolated in (False, True):
                with self.subTest(code=code, isolated=isolated), tempfile.TemporaryDirectory() as tmp:
                    work = Path(tmp) / "work"
                    home = Path(tmp) / "home"
                    work.mkdir()
                    if isolated:
                        home.mkdir()
                    env = dict(os.environ, WORK_DIR=str(work),
                               ISO_HOME=str(home) if isolated else "",
                               WATCHER_PID="", DAEMON_PID="", RELAY_PID="")
                    result = subprocess.run(
                        ["bash", "-c", "set -euo pipefail\nlog() { :; }\n" + cleanup
                         + "\ntrap cleanup EXIT\nexit " + str(code)],
                        env=env, capture_output=True, text=True,
                    )
                    self.assertEqual(code, result.returncode, result.stderr)
                    self.assertEqual(code != 0, work.exists())
                    if isolated:
                        self.assertEqual(code != 0, home.exists())

    def test_reconnect_footer_retains_daemon_evidence_before_cleanup(self):
        script = Path(__file__).with_name("e2e-emulator.sh").read_text()
        cleanup = script[script.index("cleanup() {"):script.index("trap cleanup EXIT")]
        method = "interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive"
        for code in (0, 7):
            for selected in (False, True):
                with self.subTest(code=code, selected=selected), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    work = root / "run"
                    work.mkdir()
                    (work / "daemon.log").write_text("operation evidence\n")
                    (work / "pair.out").write_text("pairing material must not be retained")
                    env = dict(os.environ, WORK_DIR=str(work), REPO_ROOT=str(root),
                               ISO_HOME="", WATCHER_PID="", DAEMON_PID="", RELAY_PID="",
                               LIVE="1", TEST_TARGET=method if selected else "otherMethod",
                               MOBILE_REVISION="mobile-revision", DAEMON_REVISION="daemon-revision")
                    result = subprocess.run(
                        ["bash", "-c", "set -euo pipefail\nlog() { :; }\n" + cleanup
                         + "\ntrap cleanup EXIT\nexit " + str(code)],
                        env=env, capture_output=True, text=True,
                    )
                    self.assertEqual(code, result.returncode, result.stderr)
                    evidence = root / "build/reconnect-footer-evidence/run"
                    self.assertEqual(selected, evidence.exists())
                    if selected:
                        self.assertEqual("operation evidence\n", (evidence / "daemon.log").read_text())
                        self.assertFalse((evidence / "pair.out").exists())
                        record = (evidence / "run.txt").read_text()
                        self.assertIn("mobile=mobile-revision", record)
                        self.assertIn("daemon=daemon-revision", record)
                        self.assertIn("exit=" + str(code), record)
                    self.assertEqual(code != 0, work.exists())


if __name__ == "__main__":
    unittest.main()
