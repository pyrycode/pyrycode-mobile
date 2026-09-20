from pathlib import Path
import os
import subprocess
import tempfile
import unittest


class EmulatorCleanupTest(unittest.TestCase):
    def test_cleanup_preserves_result_with_and_without_isolated_home(self):
        script = Path(__file__).with_name("e2e-emulator.sh").read_text()
        cleanup = script[script.index("cleanup() {"):script.index("trap cleanup EXIT INT TERM")]
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


if __name__ == "__main__":
    unittest.main()
