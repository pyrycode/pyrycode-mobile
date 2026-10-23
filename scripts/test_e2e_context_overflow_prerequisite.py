from pathlib import Path
import os
import subprocess
import tempfile
import unittest


class ContextOverflowPrerequisiteTest(unittest.TestCase):
    """Exercise binary ancestry, independently of source HEAD and without a device."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.source = Path(self.tmp.name)
        self.git("init", "--quiet")
        self.old = self.commit("old")
        self.required = self.commit("repair")
        self.new = self.commit("descendant")

    def git(self, *args):
        return subprocess.check_output(
            ["git", "-C", str(self.source), *args], text=True,
            env={**os.environ, "GIT_CONFIG_NOSYSTEM": "1"},
        ).strip()

    def commit(self, name):
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "commit", "--quiet", "--allow-empty", "-m", name)
        return self.git("rev-parse", "HEAD")

    def check_revision(self, revision, *, source=None, scenario="context-overflow", deterministic="1"):
        script = Path(__file__).with_name("e2e-emulator.sh").read_text()
        marker = "require_context_overflow_daemon() {"
        self.assertTrue(marker in script, "context-overflow accepts the pre-repair daemon")
        start = script.index(marker)
        function = script[start:script.index("\n}\n", start) + 3]
        # Also require the actual launch path to invoke the check, after reading binary metadata.
        discovery = script.index('DAEMON_REVISION=""')
        launch = script.index('log "starting pyry daemon', discovery)
        self.assertIn("require_context_overflow_daemon 3d5379ec4b778c569c27b7639a2ae495b32cee0d",
                      script[discovery:launch])
        return subprocess.run(
            ["bash", "-c", "set -euo pipefail\ndie() { echo \"$*\" >&2; exit 1; }\n" +
             function + '\nrequire_context_overflow_daemon "$REQUIRED"'],
            env={**os.environ, "PYRYCODE_SRC": str(source or self.source),
                 "DAEMON_REVISION": revision, "REQUIRED": self.required,
                 "SCENARIO": scenario, "DETERMINISTIC": deterministic},
            capture_output=True, text=True,
        )

    def assert_rejected(self, result):
        self.assertNotEqual(0, result.returncode)
        self.assertIn("context-overflow prerequisite Core #3725", result.stderr)

    def test_merged_repair_and_descendant_binaries_are_accepted(self):
        for revision in (self.required, self.new):
            with self.subTest(revision=revision):
                result = self.check_revision(revision)
                self.assertEqual(0, result.returncode, result.stderr)

    def test_old_binary_is_rejected_even_when_source_head_contains_repair(self):
        self.assert_rejected(self.check_revision(self.old))

    def test_unavailable_and_unknown_binary_revisions_are_rejected(self):
        for revision in ("", "unavailable", "f" * 40):
            with self.subTest(revision=revision):
                self.assert_rejected(self.check_revision(revision))

    def test_unverifiable_source_is_rejected(self):
        self.assert_rejected(self.check_revision(self.new, source=self.source / "missing"))

    def test_other_scenario_does_not_require_this_repair(self):
        self.assertEqual(0, self.check_revision("", scenario="ping").returncode)

    def test_non_scripted_mode_does_not_require_this_repair(self):
        self.assertEqual(0, self.check_revision("", deterministic="").returncode)


if __name__ == "__main__":
    unittest.main()
