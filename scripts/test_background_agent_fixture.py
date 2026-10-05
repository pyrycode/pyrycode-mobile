"""The #1783 live fixture holds until an explicit release, with fixed endpoints."""
import subprocess
import sys
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import urlopen


class BackgroundAgentFixtureTest(unittest.TestCase):
    def test_hold_waits_for_release_and_unknown_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            port = Path(directory) / "port"
            process = subprocess.Popen([sys.executable, str(Path(__file__).with_name("background-agent-fixture.py")), str(port)])
            try:
                deadline = time.monotonic() + 5
                while not port.exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                base = "http://127.0.0.1:" + port.read_text()
                with self.assertRaises(HTTPError) as failure:
                    urlopen(base + "/unknown", timeout=2)
                self.assertEqual(failure.exception.code, 404)
                failure.exception.close()
                with ThreadPoolExecutor(max_workers=1) as pool:
                    held = pool.submit(lambda: urlopen(base + "/hold", timeout=5).read())
                    time.sleep(.1)
                    self.assertFalse(held.done())
                    self.assertEqual(urlopen(base + "/release", timeout=2).read(), b"background_agent_released")
                    self.assertEqual(held.result(timeout=2), b"background_agent_released")
            finally:
                process.terminate()
                process.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
