"""#2121: refuse the known-broken daemon before the attention peer's handshake."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


METHOD = "de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_otherConversationAttentionPills_waitingAndFinished"
MERGE = "47d00822cc702c896d6234b0447226096edab210"


class AttentionPeerDaemonPrerequisiteTest(unittest.TestCase):
    def setUp(self):
        self.script = Path(__file__).with_name("e2e-emulator.sh").read_text()
        self.tmp = self.enterContext(tempfile.TemporaryDirectory())
        self.repo = Path(self.tmp) / "source with spaces"
        self.repo.mkdir()
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.old = self.commit("before capacity repair")
        self.required = self.commit("capacity repair")
        self.current = self.commit("later daemon")
        self.git("switch", "--orphan", "unrelated")
        self.unrelated = self.commit("unrelated daemon")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args],
                                       stderr=subprocess.DEVNULL, text=True).strip()

    def commit(self, message):
        self.git("commit", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD")

    def run_admission(self, revision, live="1", tests="", source=None):
        # The old harness has no prerequisite: it admits every daemon. Exercise that
        # old admission so the regression fails for the missing rejection itself.
        marker = "require_attention_peer_daemon() {"
        function = "require_attention_peer_daemon() { :; }\n"
        if marker in self.script:
            start = self.script.index(marker)
            common = self.script.index("require_daemon_revision() {")
            function = self.script[common:self.script.index("\n}\n", common) + 3] + "\n"
            function += self.script[start:self.script.index("\n}\n", start) + 3]
        body = 'set -euo pipefail\ndie() { printf "%s\\n" "$*" >&2; exit 1; }\n'
        body += function + '\nrequire_attention_peer_daemon "$REQUIRED_REVISION"\n'
        return subprocess.run(["bash", "-c", body], capture_output=True, text=True,
                              env=dict(os.environ, LIVE=live, LIVE_TESTS=tests, DAEMON_REVISION=revision,
                                       PYRYCODE_SRC=str(self.repo) if source is None else source,
                                       REQUIRED_REVISION=self.required), timeout=5)

    def test_full_live_rejects_pre_repair_daemon(self):
        result = self.run_admission(self.old)
        self.assertNotEqual(0, result.returncode, "old daemon was admitted")
        self.assertIn("pyrycode#3214", result.stderr)
        self.assertIn("rebuild", result.stderr)

    def test_unrelated_daemon_is_rejected(self):
        self.assertNotEqual(0, self.run_admission(self.unrelated).returncode)

    def test_repair_and_descendant_are_admitted(self):
        for revision in (self.required, self.current):
            with self.subTest(revision=revision):
                result = self.run_admission(revision)
                self.assertEqual(0, result.returncode, result.stderr)

    def test_selected_attention_method_is_checked_in_any_list_position(self):
        other = "de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_pingPrompt_streamsPingReplyIntoThread"
        for selection in (METHOD, METHOD + "," + other, other + "," + METHOD):
            with self.subTest(selection=selection):
                self.assertNotEqual(0, self.run_admission(self.old, tests=selection).returncode)
                self.assertEqual(0, self.run_admission(self.current, tests=selection).returncode)

    def test_unrelated_subset_and_non_live_runs_need_no_capacity_prerequisite(self):
        # Neither a substring nor an unrelated selected method selects this scenario.
        for selection in (METHOD + "Extra", METHOD.replace("#", "."), "Other#method"):
            with self.subTest(selection=selection):
                self.assertEqual(0, self.run_admission("", tests=selection, source="").returncode)
        self.assertEqual(0, self.run_admission("", live="", tests=METHOD, source="").returncode)

    def test_unverifiable_revisions_and_source_are_rejected_without_echoing_input(self):
        for revision, source in (("", None), ("unavailable", None), ("a" * 40, None),
                                 ("secret-token;bad", None), (self.current, ""),
                                 (self.current, str(self.repo / "missing"))):
            with self.subTest(revision=revision, source=source):
                result = self.run_admission(revision, source=source)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("pyrycode#3214", result.stderr)
                self.assertNotIn("secret-token", result.stdout + result.stderr)

    def test_merged_repair_is_required_before_daemon_startup(self):
        call = "require_attention_peer_daemon " + MERGE
        self.assertTrue(call in self.script, "merged capacity prerequisite is missing")
        self.assertLess(self.script.index(call), self.script.index('log "starting pyry daemon'))


if __name__ == "__main__":
    unittest.main()
