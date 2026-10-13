"""Private boundary capture must survive successful harness cleanup."""
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest


class FaultEvidenceTest(unittest.TestCase):
    def test_retention_is_allowlisted_private_and_hash_verified(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "daemon.log").write_text("private-daemon-fixture")
            (source / "daemon-fault.log").write_text("fixed-controller-fixture")
            (source / "pair.out").write_text("fixture-pairing-secret")
            (source / "daemon-b.log").symlink_to(source / "pair.out")
            binary = root / "binary"
            binary.write_bytes(b"test-binary")
            saved = module.retain(source, root / "private", "a" * 40, "b" * 40, binary)
            self.assertEqual({"daemon.log", "daemon-fault.log", "inputs.json", "SHA256SUMS"}, {p.name for p in saved.iterdir()})
            self.assertEqual(0o700, stat.S_IMODE(saved.stat().st_mode))
            for path in saved.iterdir():
                self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            metadata = json.loads((saved / "inputs.json").read_text())
            self.assertEqual("a" * 40, metadata["mobile_revision"])
            self.assertEqual("b" * 40, metadata["daemon_revision"])
            self.assertEqual(64, len(metadata["daemon_sha256"]))
            for line in (saved / "SHA256SUMS").read_text().splitlines():
                digest, name = line.split("  ")
                self.assertEqual(module.digest(saved / name), digest)
            self.assertFalse(any("fixture-pairing-secret" in p.read_text() for p in saved.iterdir()))

    def test_successful_harness_cleanup_retains_logs_before_disposal(self):
        harness = Path(__file__).with_name("e2e-emulator.sh").read_text()
        cleanup = harness[harness.index("cleanup() {"):harness.index("trap cleanup EXIT")]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "work"
            source.mkdir()
            (source / "daemon.log").write_text("synthetic-boundary")
            result = subprocess.run(["/bin/bash", "-c", 'log() { :; }\n' + cleanup + '\ncleanup'],
                env={**os.environ, "WORK_DIR": str(source), "REPO_ROOT": str(Path(__file__).resolve().parents[1]),
                     "E2E_FAULT_EVIDENCE_DIR": str(root / "private")}, capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(source.exists())
            saved = list((root / "private").glob("fault-*/daemon.log"))
            self.assertEqual(1, len(saved))
            self.assertEqual("synthetic-boundary", saved[0].read_text())

    def test_managed_feature_cleanup_defaults_to_private_ticket_evidence(self):
        harness = Path(__file__).with_name("e2e-emulator.sh").read_text()
        cleanup = harness[harness.index("cleanup() {"):harness.index("trap cleanup EXIT")]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "work"
            source.mkdir()
            (source / "daemon.log").write_text("managed-boundary")
            result = subprocess.run(["/bin/bash", "-c", 'log() { :; }\ngit() { printf "feature/2137\\n"; }\n' + cleanup + '\ncleanup'],
                env={**os.environ, "WORK_DIR": str(source), "REPO_ROOT": str(Path(__file__).resolve().parents[1]),
                     "E2E_FAULT_EVIDENCE_DIR": "", "AGENTS_REPO_PATH": str(root / "agents")},
                capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(source.exists())
            saved = list((root / "agents/logs/evidence/2137/harness").glob("fault-*/daemon.log"))
            self.assertEqual(1, len(saved))
            self.assertEqual("managed-boundary", saved[0].read_text())


if __name__ == "__main__":
    unittest.main()
