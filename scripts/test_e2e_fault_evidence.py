"""Private boundary capture must survive successful harness cleanup."""
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch


class FaultEvidenceTest(unittest.TestCase):
    def test_path_binary_retains_the_launched_executable_digest(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            binary = root / "pyry"
            binary.write_text("#!/bin/sh\nexit 0\n")
            binary.chmod(0o700)
            with patch.dict(os.environ, {"PATH": str(root) + os.pathsep + os.environ["PATH"]}):
                saved = module.retain(root, root / "private", "a" * 40, "b" * 40, "pyry")
            metadata = json.loads((saved / "inputs.json").read_text())
            self.assertEqual(module.digest(binary), metadata["daemon_sha256"])

    def test_retention_is_allowlisted_private_and_hash_verified(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            source = root / "source"
            source.mkdir()
            (source / "daemon.log").write_text("private-daemon-fixture")
            (source / "daemon-fault.log").write_text("fixed-controller-fixture")
            (source / "pair.out").write_text("fixture-pairing-secret")
            (source / "daemon-b.log").symlink_to(source / "pair.out")
            binary = root / "binary"
            binary.write_bytes(b"test-binary")
            saved = module.retain(source, root / "private/issue/harness", "a" * 40, "b" * 40, binary)
            for directory in [root / "private", root / "private/issue", root / "private/issue/harness"]:
                self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode))
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
            root = Path(temporary).resolve()
            (root / "agents").mkdir()
            source = root / "work"
            source.mkdir()
            (source / "daemon.log").write_text("synthetic-boundary")
            result = subprocess.run(["/bin/bash", "-c", 'set -euo pipefail\nlog() { :; }\n' + cleanup + '\ncleanup'],
                env={**os.environ, "WORK_DIR": str(source), "REPO_ROOT": str(Path(__file__).resolve().parents[1]),
                     "E2E_FAULT_EVIDENCE_DIR": str(root / "private"), "AGENTS_REPO_PATH": str(root / "agents"),
                     "WATCHER_PID": "", "DAEMON_PID": "", "RELAY_PID": "", "ISO_HOME": ""},
                capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(source.exists())
            saved = list((root / "private").glob("fault-*/daemon.log"))
            self.assertEqual(1, len(saved))
            self.assertEqual("synthetic-boundary", saved[0].read_text())

    def test_managed_feature_cleanup_defaults_to_private_ticket_evidence(self):
        harness = Path(__file__).with_name("e2e-emulator.sh").read_text()
        cleanup = harness[harness.index("cleanup() {"):harness.index("trap cleanup EXIT")]
        selection = harness[harness.index('command -v "${PYRY_BIN}"'):
                            harness.index('\ncommand -v python3', harness.index('command -v "${PYRY_BIN}"'))]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / "agents").mkdir()
            source = root / "work"
            source.mkdir()
            (source / "daemon.log").write_text("managed-boundary")
            binary = root / "pyry"
            binary.write_text("#!/bin/sh\nprintf 'selected\\n'\n")
            binary.chmod(0o700)
            decoy = root / "decoy"
            decoy.mkdir()
            (decoy / "pyry").write_text("#!/bin/sh\nprintf 'wrong\\n'\n")
            (decoy / "pyry").chmod(0o700)
            body = 'set -euo pipefail\nlog() { :; }\ndie() { exit 9; }\ngit() { printf "feature/2137\\n"; }\n'
            body += selection + '\nPATH="$DECOY_PATH:$PATH"\n"$PYRY_BIN" > "$BINARY_CAPTURE"\n' + cleanup + '\ncleanup'
            result = subprocess.run(["/bin/bash", "-c", body],
                env={**os.environ, "WORK_DIR": str(source), "REPO_ROOT": str(Path(__file__).resolve().parents[1]),
                     "E2E_FAULT_EVIDENCE_DIR": "", "AGENTS_REPO_PATH": str(root / "agents"),
                     "WATCHER_PID": "", "DAEMON_PID": "", "RELAY_PID": "", "ISO_HOME": "",
                     "PYRY_BIN": "pyry", "PATH": str(root) + os.pathsep + os.environ["PATH"],
                     "DECOY_PATH": str(decoy), "BINARY_CAPTURE": str(root / "launched")},
                capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(source.exists())
            saved = list((root / "agents/logs/evidence/2137/harness").glob("fault-*/daemon.log"))
            self.assertEqual(1, len(saved))
            self.assertEqual("managed-boundary", saved[0].read_text())
            self.assertEqual("selected\n", (root / "launched").read_text())
            import hashlib
            metadata = json.loads((saved[0].parent / "inputs.json").read_text())
            self.assertEqual(hashlib.sha256(binary.read_bytes()).hexdigest(), metadata["daemon_sha256"])


    def test_unknown_identities_and_configured_source_revision(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            repo = Path(__file__).resolve().parents[1]
            expected = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
            saved = module.retain(root, root / "private", "secret-revision", "", "missing-synthetic-binary", str(repo), 17)
            metadata = json.loads((saved / "inputs.json").read_text())
            self.assertEqual("unavailable", metadata["mobile_revision"])
            self.assertEqual("unavailable", metadata["daemon_revision"])
            self.assertEqual("unavailable", metadata["daemon_sha256"])
            self.assertEqual(expected, metadata["daemon_source_revision"])
            self.assertEqual(17, metadata["harness_exit_code"])
            self.assertEqual(dict.fromkeys(module.LOG_NAMES, "unavailable"), metadata["availability"])

    def test_all_logs_and_fifo_exclusion(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            source = root / "source"
            source.mkdir()
            for name in module.LOG_NAMES:
                (source / name).write_text("synthetic-" + name)
            saved = module.retain(source, root / "private", "", "", "")
            metadata = json.loads((saved / "inputs.json").read_text())
            self.assertEqual(dict.fromkeys(module.LOG_NAMES, "retained"), metadata["availability"])
            for name in module.LOG_NAMES:
                self.assertEqual((source / name).read_bytes(), (saved / name).read_bytes())
            (source / "relay.log").unlink()
            os.mkfifo(source / "relay.log")
            saved = module.retain(source, root / "private", "", "", "")
            self.assertEqual("unavailable", json.loads((saved / "inputs.json").read_text())["availability"]["relay.log"])
            self.assertFalse((saved / "relay.log").exists())

    def test_partial_hash_failure_records_availability_and_never_claims_complete(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / "daemon.log").write_text("private-synthetic")
            (root / "relay.log").write_text("synthetic-relay")
            real_digest = module.hashlib.file_digest
            # The first retained-file digest is daemon.log; avoid host-specific fd paths.
            calls = 0
            def changed_digest(reader, algorithm):
                nonlocal calls
                calls += 1
                return module.hashlib.sha256(b"wrong-copy") if calls == 1 else real_digest(reader, algorithm)
            with patch.object(module.hashlib, "file_digest", side_effect=changed_digest):
                with self.assertRaises(module.RetentionError) as caught:
                    module.retain(root, root / "private", "", "", "")
            saved = caught.exception.saved
            self.assertFalse((saved / "daemon.log").exists())
            metadata = json.loads((saved / "inputs.json").read_text())
            self.assertEqual("partial", metadata["retention_status"])
            self.assertEqual("failed", metadata["availability"]["daemon.log"])
            self.assertEqual("retained", metadata["availability"]["relay.log"])

    def assert_manifest_failure_is_incomplete(self, verification):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / "daemon.log").write_text("private-synthetic")
            real_write = module.write_private

            def fail_manifest(directory, name, content):
                if name == "SHA256SUMS":
                    with os.fdopen(os.open("inputs.json", module.FILE_FLAGS, dir_fd=directory), "rb") as reader:
                        self.assertEqual("retained", json.load(reader)["availability"]["daemon.log"])
                    if not verification:
                        raise OSError("private-write-failure")
                    content += b"corrupt-manifest\n"
                return real_write(directory, name, content)

            with patch.object(module, "write_private", side_effect=fail_manifest):
                with self.assertRaises(module.RetentionError) as caught:
                    module.retain(root, root / "private", "", "", "")
            saved = caught.exception.saved
            metadata = json.loads((saved / "inputs.json").read_text())
            self.assertEqual("partial", metadata["retention_status"])
            self.assertEqual("retained", metadata["availability"]["daemon.log"])
            self.assertEqual("private-synthetic", (saved / "daemon.log").read_text())
            self.assertNotIn("private-write-failure", str(caught.exception))

    def test_manifest_write_failure_after_metadata_never_claims_complete(self):
        self.assert_manifest_failure_is_incomplete(verification=False)

    def test_manifest_verification_failure_after_metadata_never_claims_complete(self):
        self.assert_manifest_failure_is_incomplete(verification=True)

    def test_completion_is_published_only_after_verified_manifest_and_metadata(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / "daemon.log").write_text("private-synthetic")
            real_replace = module.os.replace

            def publish(source, destination, **kwargs):
                directory = kwargs["src_dir_fd"]
                self.assertEqual(directory, kwargs["dst_dir_fd"])
                with os.fdopen(os.open("inputs.json", module.FILE_FLAGS, dir_fd=directory), "rb") as reader:
                    self.assertEqual("partial", json.load(reader)["retention_status"])
                with os.fdopen(os.open(source, module.FILE_FLAGS, dir_fd=directory), "rb") as reader:
                    self.assertEqual("complete", json.load(reader)["retention_status"])
                with os.fdopen(os.open("SHA256SUMS", module.FILE_FLAGS, dir_fd=directory), "r") as reader:
                    manifest = reader.read()
                for line in manifest.splitlines():
                    expected, name = line.split("  ")
                    with os.fdopen(os.open(source if name == "inputs.json" else name, module.FILE_FLAGS, dir_fd=directory), "rb") as reader:
                        self.assertEqual(expected, module.hashlib.file_digest(reader, "sha256").hexdigest())
                return real_replace(source, destination, **kwargs)

            with patch.object(module.os, "replace", side_effect=publish) as replacement:
                saved = module.retain(root, root / "private", "", "", "")
            self.assertEqual(1, replacement.call_count)
            self.assertEqual("complete", json.loads((saved / "inputs.json").read_text())["retention_status"])
            self.assertEqual({"daemon.log", "inputs.json", "SHA256SUMS"}, {p.name for p in saved.iterdir()})

    def test_completion_publication_failure_leaves_partial_metadata(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            with patch.object(module.os, "replace", side_effect=OSError("private-rename-failure")):
                with self.assertRaises(module.RetentionError) as caught:
                    module.retain(root, root / "private", "", "", "")
            metadata = json.loads((caught.exception.saved / "inputs.json").read_text())
            self.assertEqual("partial", metadata["retention_status"])

    def test_completed_metadata_write_and_verification_failures_leave_partial_status(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        real_write = module.write_private
        for verification in (False, True):
            with self.subTest(verification=verification), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()

                def fail_completion(directory, name, content):
                    if name == "inputs.pending.json":
                        if not verification:
                            raise OSError("private-metadata-failure")
                        content += b"corrupt-metadata\n"
                    return real_write(directory, name, content)

                with patch.object(module, "write_private", side_effect=fail_completion):
                    with self.assertRaises(module.RetentionError) as caught:
                        module.retain(root, root / "private", "", "", "")
                saved = caught.exception.saved
                self.assertTrue((saved / "SHA256SUMS").is_file())
                self.assertEqual("partial", json.loads((saved / "inputs.json").read_text())["retention_status"])

    def test_interruption_before_publication_leaves_partial_status(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            with patch.object(module.os, "replace", side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    module.retain(root, root / "private", "", "", "")
            saved, = (root / "private").glob("fault-*")
            self.assertEqual("partial", json.loads((saved / "inputs.json").read_text())["retention_status"])

    def test_cli_partial_failure_has_only_fixed_summary_and_escaped_destination(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        import io
        from contextlib import redirect_stdout
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / "daemon.log").write_text("secret-log")
            args = ["evidence", "--source", str(root), "--destination", str(root / "private\npath")]
            output = io.StringIO()
            with patch("sys.argv", args), patch.object(module, "write_private", side_effect=OSError("secret-error")), redirect_stdout(output):
                self.assertEqual(1, module.main())
            self.assertEqual(1, len(output.getvalue().splitlines()))
            self.assertIn("retention=partial", output.getvalue())
            self.assertIn("availability=incomplete", output.getvalue())
            self.assertNotIn("secret", output.getvalue())
            self.assertIn("private\\npath", output.getvalue())

    def test_existing_managed_evidence_ancestors_become_private(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            destination = root / "agents/logs/evidence/2145/harness"
            destination.mkdir(parents=True)
            for directory in (destination, destination.parent, destination.parent.parent):
                directory.chmod(0o755)
            module.retain(root, destination, "", "", "", agents_root=str(root / "agents"))
            for directory in (destination, destination.parent, destination.parent.parent):
                self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode))

    def test_destination_symlinks_are_rejected_without_writing_or_chmod(self):
        spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("e2e_fault_evidence.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / "outside").mkdir(mode=0o755)
            (root / "outside").chmod(0o755)  # Establish the fixture mode independently of umask.
            (root / "link").symlink_to(root / "outside")
            for destination in (root / "link", root / "link/child"):
                with self.subTest(destination=destination), self.assertRaises(module.RetentionError):
                    module.retain(root, destination, "", "", "")
            self.assertEqual([], list((root / "outside").iterdir()))
            self.assertEqual(0o755, stat.S_IMODE((root / "outside").stat().st_mode))

if __name__ == "__main__":
    unittest.main()
