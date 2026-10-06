"""The reopen fixture exposes its suffix before an explicitly released terminal result."""

import os
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import time
import unittest


SCRIPT = Path(__file__).with_name("e2e-held-result.py")
SUFFIX = b'{"type":"assistant","message":{"content":[{"type":"text","text":" suffix"}]}}\n'
RESULT = b'{"type":"result","subtype":"success"}\n'
CHILD = """
import os, sys
from pathlib import Path
Path(sys.argv[1]).write_text(str(os.getpid()))
sys.stdout.buffer.write(bytes.fromhex(sys.argv[2]) + bytes.fromhex(sys.argv[3]))
sys.stdout.buffer.flush()
sys.stdin.readline()
"""


class HeldResultTest(unittest.TestCase):
    def start(self, directory):
        release = directory / "release"
        pid_file = directory / "pid"
        process = subprocess.Popen(
            [sys.executable, str(SCRIPT), "-c", CHILD, str(pid_file), SUFFIX.hex(), RESULT.hex()],
            env={**os.environ, "E2E_HELD_RESULT_CHILD": sys.executable,
                 "E2E_HELD_RESULT_RELEASE": str(release)},
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )

        def cleanup():
            if process.poll() is None:
                process.terminate()
            process.communicate(timeout=5)

        self.addCleanup(cleanup)
        return process, release, pid_file

    def read_suffix(self, process):
        output = b""
        deadline = time.monotonic() + 5
        while len(output) < len(SUFFIX) and time.monotonic() < deadline:
            if select.select([process.stdout], [], [], 0.1)[0]:
                chunk = os.read(process.stdout.fileno(), 4096)
                if not chunk:
                    break
                output += chunk
        self.assertEqual(SUFFIX, output)

    def test_suffix_is_forwarded_but_completion_requires_release(self):
        with tempfile.TemporaryDirectory() as temporary:
            process, release, _ = self.start(Path(temporary))
            self.read_suffix(process)
            self.assertEqual([], select.select([process.stdout], [], [], 0.2)[0])
            self.assertIsNone(process.poll())
            release.touch()
            remaining, error = process.communicate(input=b"finish\n", timeout=5)
            self.assertEqual(RESULT, remaining)
            self.assertEqual(b"", error)
            self.assertEqual(0, process.returncode)

    def test_termination_while_completion_is_held_reaps_owned_child(self):
        with tempfile.TemporaryDirectory() as temporary:
            process, _, pid_file = self.start(Path(temporary))
            self.read_suffix(process)
            child_pid = int(pid_file.read_text())
            process.terminate()
            process.communicate(timeout=5)
            with self.assertRaises(ProcessLookupError):
                os.kill(child_pid, 0)

    def test_host_releases_suffix_on_second_send_and_result_on_third(self):
        harness = Path(__file__).with_name("e2e-emulator.sh").read_text()
        block = harness[harness.index('# ---- 4b. release'):harness.index('# ---- 4. run the managed-device')]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            log = directory / "daemon.log"
            log.write_text("send_message.enqueued\n")
            suffix = directory / "suffix-release"
            result = directory / "result-release"
            process = subprocess.Popen(
                ["bash", "-c", 'set -euo pipefail\nlog() { :; }\n' + block + '\nwait "$WATCHER_PID"'],
                env={**os.environ, "DETERMINISTIC": "1", "FIXTURE_FILE_2": "fixture",
                     "DROP_B_FENCE": "enqueue", "SCENARIO": "reopen-stream", "DAEMON_LOG": str(log),
                     "REPLAY_RELEASE": str(suffix), "E2E_HELD_RESULT_RELEASE": str(result)},
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
            try:
                time.sleep(0.3)
                self.assertFalse(suffix.exists())
                self.assertFalse(result.exists())
                log.write_text("send_message.enqueued\n" * 2)
                deadline = time.monotonic() + 5
                while not suffix.exists() and time.monotonic() < deadline:
                    time.sleep(0.05)
                self.assertTrue(suffix.exists())
                self.assertFalse(result.exists())
                log.write_text("send_message.enqueued\n" * 3)
                _, error = process.communicate(timeout=5)
                self.assertEqual(0, process.returncode, error)
                self.assertTrue(result.exists())
            finally:
                if process.poll() is None:
                    process.terminate()
                process.communicate(timeout=5)


if __name__ == "__main__":
    unittest.main()
