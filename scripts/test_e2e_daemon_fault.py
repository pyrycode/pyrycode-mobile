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
                    "--control-socket",
                    str(path / "control.sock"),
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
