"""The live hold cannot finish until explicit cleanup; each scenario has its own key."""
import importlib.util
from pathlib import Path
import threading
import unittest
import urllib.request
from concurrent.futures import ThreadPoolExecutor

spec = importlib.util.spec_from_file_location("hold", Path(__file__).with_name("stop-task-fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class StopTaskFixtureTest(unittest.TestCase):
    def test_hold_arrives_stays_blocked_and_release_is_keyed_and_idempotent(self):
        server = fixture.server()
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"

        def get(path):
            with urllib.request.urlopen(base + path, timeout=3) as response:
                return response.read()

        try:
            with ThreadPoolExecutor() as pool:
                held = pool.submit(get, "/123/hold")
                self.assertTrue(server.holds.setdefault("123", fixture.Hold()).arrived.wait(2))
                self.assertEqual(get("/123/status"), b"held")
                get("/456/release")
                self.assertFalse(held.done())
                get("/123/release")
                self.assertEqual(held.result(2), b"released")
                get("/123/release")
                self.assertEqual(get("/123/status"), b"released")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)


if __name__ == "__main__":
    unittest.main()
