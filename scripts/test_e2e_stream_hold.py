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
    def test_replay_order_ignores_automatic_disconnect_until_explicit_offline_release(self):
        arm = HARNESS[HARNESS.index("    replay-order)"):HARNESS.index("    refusal)")]
        configured = subprocess.run(
            ["bash", "-c", 'set -eu\nFIXTURE_FILE=""\nFIXTURE_FILE_2=""\nDROP_B_FENCE=enqueue\ncase replay-order in\n'
             + arm + '\nesac\nprintf "%s\\n" "$TEST_METHOD" "$FIXTURE_FILE_2" "$DROP_B_FENCE"'],
            env={**os.environ, "FIXTURES_DIR": str(ROOT / "e2e-fixtures")},
            capture_output=True, text=True, check=True,
        ).stdout.splitlines()
        method, terminal, fence = configured
        self.assertEqual("interactiveTurn_seededChannel_missedEventsReplayInOrderAfterReconnect", method)
        watcher = HARNESS[HARNESS.index("# ---- 4b. release"):HARNESS.index("# ---- 4. run the managed-device")]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            daemon = directory / "daemon.log"
            relay = directory / "relay.log"
            ready = directory / "armed"
            release = directory / "release"
            daemon.write_text("send_message.enqueued\n")
            relay.write_text("")
            process = subprocess.Popen(
                ["bash", "-c", 'set -euo pipefail\nlog() { touch "$ARMED"; }\n' + watcher + '\nwait "$WATCHER_PID"'],
                env={**os.environ, "DETERMINISTIC": "1", "SCENARIO": "replay-order",
                     "FIXTURE_FILE_2": terminal, "DROP_B_FENCE": fence, "ARMED": str(ready),
                     "DAEMON_LOG": str(daemon), "DISCONNECT_LOG": str(relay),
                     "DISCONNECT_TOKEN": "phone_unregistered", "REPLAY_RELEASE": str(release)},
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
            try:
                deadline = time.monotonic() + 5
                while not ready.exists() and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(ready.exists(), "actual watcher must arm")
                # Give the old watcher time to establish its baseline before reproducing the
                # retained producer-gap -> unregister -> automatic redial sequence twice.
                time.sleep(0.3)
                for _ in range(2):
                    with relay.open("a") as output:
                        output.write("phone_unregistered close_code=4414\nphone_registered\n")
                    with daemon.open("a") as output:
                        output.write("send_message.enqueued\n")
                    time.sleep(0.3)
                    self.assertFalse(release.exists(), "automatic reconnect cannot release the held thinking turn")
                    self.assertIsNone(process.poll())
                # Represents the fixed control action, called only after the phone proves close.
                release.touch()
                _, error = process.communicate(timeout=5)
                self.assertEqual(0, process.returncode, error)
            finally:
                if process.poll() is None:
                    process.terminate()
                process.communicate(timeout=5)

    def test_selected_stream_waits_for_phone_display_checkpoint_before_release(self):
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
        self.assertEqual("phone-checkpoint", fence)
        opening = [json.loads(line) for line in Path(initial).read_text().splitlines()]
        ending = [json.loads(line) for line in Path(terminal).read_text().splitlines()]
        self.assertGreaterEqual(len(opening), 2)
        self.assertEqual("user", opening[0]["type"])
        self.assertTrue(opening[0]["isReplay"])
        self.assertEqual("hello", opening[0]["message"]["content"][0]["text"])
        self.assertTrue(all(row["type"] == "assistant" and not row["message"].get("stop_reason") for row in opening[1:]))
        reopened = [json.loads(line) for line in (ROOT / "e2e-fixtures/reopen-stream-open.jsonl").read_text().splitlines()]
        self.assertEqual(opening[0], reopened[0])
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
                time.sleep(0.3)
                self.assertIsNone(process.poll())
                self.assertFalse(release.exists(), "daemon enqueue cannot acknowledge the phone's display checkpoint")
                release.touch()
                _, error = process.communicate(timeout=5)
                self.assertEqual(0, process.returncode, error)
                self.assertTrue(release.exists())
            finally:
                if process.poll() is None:
                    process.terminate()
                process.communicate(timeout=5)


if __name__ == "__main__":
    unittest.main()
