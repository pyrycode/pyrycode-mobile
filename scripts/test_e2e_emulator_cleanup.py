from pathlib import Path
import hashlib
import json
import os
import stat
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
ALLOWLIST = ("daemon.log", "daemon-fault.log", "daemon-b.log", "daemon-answer.log", "daemon-bypass.log", "relay.log")


class EmulatorCleanupTest(unittest.TestCase):
    def run_cleanup(self, root, code=0, managed=True, destination="", body="", repo=REPO):
        script = (REPO / "scripts/e2e-emulator.sh").read_text()
        cleanup = script[script.index("cleanup() {"):script.index("trap cleanup EXIT")]
        env = dict(os.environ, WORK_DIR=str(root / "work"), ISO_HOME=str(root / "home"),
                   WATCHER_PID="", DAEMON_PID="", RELAY_PID="", REPO_ROOT=str(repo),
                   AGENTS_REPO_PATH=str(root / "agents") if managed else "", E2E_FAULT_EVIDENCE_DIR=destination,
                   MOBILE_REVISION="a" * 40, DAEMON_REVISION="b" * 40, PYRY_BIN=str(root / "binary"), PYRYCODE_SRC="",
                   BYPASS_HOME="", ANSWER_HOME="")
        return subprocess.run(["/bin/bash", "-c", 'set -euo pipefail\nlog() { printf "%s\\n" "$*"; }\n'
                               + cleanup + '\n' + body + '\ntrap cleanup EXIT\nexit ' + str(code)],
                              env=env, capture_output=True, text=True, timeout=15)

    def prepare(self, root):
        for name in ("work", "home", "agents"):
            (root / name).mkdir()
        (root / "binary").write_bytes(b"synthetic-binary")
        (root / "home/token").write_text("forbidden-credential")
        (root / "work/daemon.log").write_text("private-synthetic-log")

    def evidence(self, root):
        return list((root / "agents/logs/evidence").rglob("inputs.json"))

    def test_cleanup_preserves_result_with_and_without_isolated_home(self):
        for code in (0, 7):
            for isolated in (False, True):
                with self.subTest(code=code, isolated=isolated), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp).resolve()
                    self.prepare(root)
                    if not isolated:
                        (root / "home/token").unlink()
                        (root / "home").rmdir()
                    result = self.run_cleanup(root, code, managed=False)
                    self.assertEqual(code, result.returncode, result.stderr)
                    self.assertEqual(code != 0, (root / "work").exists())
                    self.assertEqual(isolated and code != 0, (root / "home").exists())
                    self.assertEqual([], self.evidence(root))

    def test_managed_success_retains_verified_private_allowlist(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            self.prepare(root)
            work = root / "work"
            for name in ("pair.out", "keys", "token", "transcript.jsonl"):
                (work / name).write_text("forbidden-private-data")
            (work / "daemon-b.log").symlink_to(work / "pair.out")
            (work / "daemon-answer.log").mkdir()
            result = self.run_cleanup(root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(work.exists())
            self.assertFalse((root / "home").exists())
            self.assertEqual(1, len(self.evidence(root)))
            saved = self.evidence(root)[0].parent
            self.assertEqual({"daemon.log", "inputs.json", "SHA256SUMS"}, {p.name for p in saved.iterdir()})
            metadata = json.loads((saved / "inputs.json").read_text())
            self.assertEqual(0, metadata["harness_exit_code"])
            self.assertEqual("complete", metadata["retention_status"])
            self.assertEqual("a" * 40, metadata["mobile_revision"])
            self.assertEqual("b" * 40, metadata["daemon_revision"])
            self.assertEqual(hashlib.sha256(b"synthetic-binary").hexdigest(), metadata["daemon_sha256"])
            self.assertEqual("unavailable", metadata["daemon_source_revision"])
            self.assertEqual(dict.fromkeys(ALLOWLIST, "unavailable") | {"daemon.log": "retained"}, metadata["availability"])
            for directory in (saved, saved.parent, saved.parent.parent, root / "agents/logs/evidence"):
                self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode))
            for path in saved.iterdir():
                self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            for line in (saved / "SHA256SUMS").read_text().splitlines():
                digest, name = line.split("  ")
                self.assertEqual(hashlib.sha256((saved / name).read_bytes()).hexdigest(), digest)
            self.assertNotIn("private-synthetic-log", result.stdout + result.stderr)
            self.assertNotIn("forbidden", result.stdout + result.stderr)
            self.assertIn("retention=complete", result.stdout)

    def test_managed_failure_preserves_result_and_removes_homes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            self.prepare(root)
            result = self.run_cleanup(root, 37)
            self.assertEqual(37, result.returncode, result.stderr)
            self.assertTrue((root / "work").exists())
            self.assertFalse((root / "home").exists())
            self.assertEqual(37, json.loads(self.evidence(root)[0].read_text())["harness_exit_code"])

    def test_retention_failure_preserves_original_failure_and_disposes_homes(self):
        for code in (0, 7, 143):
            for failure in ("absent-root", "unusable-destination", "missing-helper"):
                with self.subTest(code=code, failure=failure), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp).resolve()
                    self.prepare(root)
                    destination = ""
                    repo = REPO
                    if failure == "absent-root":
                        (root / "agents").rmdir()
                    elif failure == "unusable-destination":
                        (root / "blocked").write_text("not-a-directory")
                        destination = str(root / "blocked/child")
                    else:
                        repo = root
                    result = self.run_cleanup(root, code, destination=destination, repo=repo)
                    self.assertEqual(code or 1, result.returncode, result.stderr)
                    self.assertFalse((root / "home").exists())
                    self.assertEqual(code != 0, (root / "work").exists())
                    self.assertIn("retention=failed", result.stdout)
                    self.assertNotIn("Traceback", result.stderr)
                    if failure == "absent-root":
                        self.assertFalse((root / "agents").exists())

    def test_all_managed_credential_homes_are_removed_even_when_retention_fails(self):
        for code in (0, 23):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as tmp, \
                    tempfile.TemporaryDirectory(prefix="pyry-e2e-byp.", dir="/tmp") as bypass, \
                    tempfile.TemporaryDirectory(prefix="pyry-e2e-ans.", dir="/tmp") as answer:
                root = Path(tmp).resolve()
                self.prepare(root)
                for home in (Path(bypass), Path(answer)):
                    (home / "credential").write_text("synthetic-secret")
                    (home / "transcript.jsonl").write_text("synthetic-transcript")
                body = 'BYPASS_HOME="' + bypass + '"\nANSWER_HOME="' + answer + '"\n'
                body += 'keep_bypass_transcripts() { touch "' + str(root / "forbidden-copy") + '"; }'
                result = self.run_cleanup(root, code, destination=str(root / "binary/child"), body=body)
                self.assertEqual(code or 1, result.returncode, result.stderr)
                self.assertFalse(Path(bypass).exists())
                self.assertFalse(Path(answer).exists())
                self.assertFalse((root / "home").exists())
                self.assertFalse((root / "forbidden-copy").exists())

    def test_managed_detached_runs_have_unique_destinations(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            self.prepare(root)
            for run in range(2):
                if run:
                    (root / "work").mkdir()
                    (root / "home").mkdir()
                # Override git only to model a detached comparison, not the retention helper.
                result = self.run_cleanup(root, body='git() { :; }')
                self.assertEqual(0, result.returncode, result.stderr)
            evidence = self.evidence(root)
            self.assertEqual(2, len(evidence))
            self.assertNotEqual(evidence[0].parent, evidence[1].parent)
            self.assertTrue(all(p.parent.parent == root / "agents/logs/evidence/harness" for p in evidence))

    def test_children_are_reaped_before_capture_on_every_outcome(self):
        for code in (0, 9):
            for failure in (False, True):
                with self.subTest(code=code, failure=failure), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp).resolve()
                    self.prepare(root)
                    child = root / "child.py"
                    child.write_text('import os, signal, sys, time\nfrom pathlib import Path\n'
                                     'def stop(*_):\n    Path(sys.argv[1]).write_text("final-child-write")\n    sys.exit(0)\n'
                                     'signal.signal(signal.SIGTERM, stop)\nPath(sys.argv[2]).write_text(str(os.getpid()))\n'
                                     'while True: time.sleep(0.01)\n')
                    body = 'python3 "' + str(child) + '" "$WORK_DIR/daemon.log" "' + str(root / "ready") + '" &\n'
                    body += 'DAEMON_PID=$!\nwhile [ ! -f "' + str(root / "ready") + '" ]; do sleep 0.01; done\n'
                    result = self.run_cleanup(root, code, destination=str(root / "binary/child") if failure else "", body=body)
                    self.assertEqual(code or int(failure), result.returncode, result.stderr)
                    pid = (root / "ready").read_text()
                    state = subprocess.run(["ps", "-p", pid, "-o", "stat="], capture_output=True, text=True)
                    self.assertEqual("", state.stdout.strip(), "owned child was not reaped")
                    self.assertFalse((root / "home").exists())
                    if not failure:
                        self.assertEqual("final-child-write", (self.evidence(root)[0].parent / "daemon.log").read_text())


if __name__ == "__main__":
    unittest.main()
