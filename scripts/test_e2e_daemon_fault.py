"""Process ownership proof for the e2e Offline Retry fault controller."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent


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
                    "--ready-token",
                    "relay: conn established",
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


if __name__ == "__main__":
    unittest.main()
