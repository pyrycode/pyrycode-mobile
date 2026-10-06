"""The selected stream scenario cannot finish until the phone releases its held reply."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parent
HARNESS = (ROOT / "e2e-emulator.sh").read_text()


class StreamHoldTest(unittest.TestCase):
    def test_selected_stream_keeps_arrived_words_open_until_second_enqueue(self):
        arm = HARNESS[HARNESS.index("    stream)"):HARNESS.index("    reopen-stream)")]
        configured = subprocess.run(
            ["bash", "-c", 'set -eu\nFIXTURE_FILE=""\nFIXTURE_FILE_2=""\nDROP_B_FENCE=enqueue\ncase stream in\n'
             + arm + '\nesac\nprintf "%s\\n" "$TEST_METHOD" "$FIXTURE_FILE" "$FIXTURE_FILE_2" "$DROP_B_FENCE"'],
            env={**os.environ, "FIXTURES_DIR": str(ROOT / "e2e-fixtures")},
            capture_output=True, text=True, check=True,
        ).stdout.splitlines()
        method, initial, terminal, fence = configured
        self.assertEqual("interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread", method)
        self.assertTrue(terminal, "stream needs a causally held terminal fragment")
        self.assertEqual("enqueue", fence)
        opening = [json.loads(line) for line in Path(initial).read_text().splitlines()]
        ending = [json.loads(line) for line in Path(terminal).read_text().splitlines()]
        self.assertGreaterEqual(len(opening), 2)
        self.assertTrue(all(row["type"] == "assistant" and not row["message"].get("stop_reason") for row in opening))
        self.assertEqual("result", ending[-1]["type"])
        text = "".join(block["text"] for row in opening + ending if row["type"] == "assistant"
                       for block in row["message"]["content"])
        self.assertEqual("Hello, streamed world", text)

        watcher = HARNESS[HARNESS.index("# ---- 4b. release"):HARNESS.index("# ---- 4. run the managed-device")]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            log = directory / "daemon.log"
            log.write_text("send_message.enqueued\n")
            release = directory / "release"
            process = subprocess.Popen(
                ["bash", "-c", 'set -euo pipefail\nlog() { :; }\n' + watcher + '\nwait "$WATCHER_PID"'],
                env={**os.environ, "DETERMINISTIC": "1", "SCENARIO": "stream",
                     "FIXTURE_FILE_2": terminal, "DROP_B_FENCE": fence,
                     "DAEMON_LOG": str(log), "REPLAY_RELEASE": str(release)},
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
            try:
                time.sleep(0.6)
                self.assertIsNone(process.poll())
                self.assertFalse(release.exists(), "elapsed time cannot complete the held reply")
                log.write_text("send_message.enqueued\n" * 2)
                _, error = process.communicate(timeout=5)
                self.assertEqual(0, process.returncode, error)
                self.assertTrue(release.exists())
            finally:
                if process.poll() is None:
                    process.terminate()
                process.communicate(timeout=5)


if __name__ == "__main__":
    unittest.main()
