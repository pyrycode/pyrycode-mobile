import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("with-claude-login.sh")
# The command the helper runs: says whether it received the fixture login, never printing the value.
PROBE = 'if [ "${CLAUDE_CODE_OAUTH_TOKEN:-}" = login-fixture ]; then echo fetched; else echo none; fi'


class WithClaudeLoginTest(unittest.TestCase):
    def run_helper(self, fake=None, **env):
        with tempfile.TemporaryDirectory() as bin_dir:
            if fake is not None:
                helper = Path(bin_dir) / "automation-access"
                helper.write_text("#!/bin/sh\n" + fake)
                helper.chmod(0o755)
            base = {"PATH": f"{bin_dir}:/usr/bin:/bin", "HOME": os.environ.get("HOME", "/tmp")}
            return subprocess.run([str(SCRIPT), "sh", "-c", PROBE], env={**base, **env},
                                  capture_output=True, text=True, timeout=30)

    def test_a_hand_run_gets_the_login_without_it_being_printed(self):
        result = self.run_helper('[ "$*" = "op read --no-newline op://Automation/Claude long term token/password" ] '
                                 '&& printf login-fixture\n')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), "fetched")
        self.assertIn("fetched the long-term Claude login", result.stderr)
        self.assertNotIn("login-fixture", result.stdout + result.stderr)

    def test_an_existing_login_is_kept(self):
        result = self.run_helper("echo called >&2; printf other\n", CLAUDE_CODE_OAUTH_TOKEN="login-fixture")
        self.assertEqual(result.stdout.strip(), "fetched")
        self.assertNotIn("called", result.stderr)

    def test_inside_the_dispatcher_nothing_is_fetched(self):
        result = self.run_helper("echo called >&2; printf login-fixture\n", AGENTS_REPO_PATH="/agents")
        self.assertEqual(result.stdout.strip(), "none")
        self.assertNotIn("called", result.stderr)

    def test_a_failed_or_missing_fetch_still_runs_the_command(self):
        for fake in ("echo login-fixture >&2; exit 1\n", "exit 0\n", None):
            with self.subTest(fake=fake):
                result = self.run_helper(fake)
                self.assertEqual(result.returncode, 0)
                self.assertEqual(result.stdout.strip(), "none")
                self.assertNotIn("login-fixture", result.stdout)

    def test_the_live_e2e_run_goes_through_the_helper(self):
        script = (SCRIPT.parent / "e2e-emulator.sh").read_text()
        self.assertIn('exec "${REPO_ROOT}/scripts/with-claude-login.sh" bash "${BASH_SOURCE[0]}" "$@"', script)


if __name__ == "__main__":
    unittest.main()
