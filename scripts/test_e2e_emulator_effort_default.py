import json
from pathlib import Path
import subprocess
import tempfile
import unittest


class EffortDefaultLaunchTest(unittest.TestCase):
    """Execute the primary launch construction without a daemon, login or model turn."""

    def launch(self, *, live="", deterministic="", codex=""):
        script = Path(__file__).with_name("e2e-emulator.sh").read_text()
        start = script.index("DAEMON_COMMAND=(env)")
        construction = script[start:script.index('\nFAULT_PORT_FILE=', start)]
        with tempfile.TemporaryDirectory() as tmp:
            operator = Path(tmp) / "operator"
            settings = operator / ".claude" / "settings.json"
            settings.parent.mkdir(parents=True)
            settings.write_text(json.dumps({"model": "haiku"}))
            capture = Path(tmp) / "pyry"
            capture.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "from pathlib import Path\n"
                "args = sys.argv[1:]\n"
                "home = os.environ['HOME']\n"
                "model = None\n"
                "if '--model' in args: model = args[args.index('--model') + 1]\n"
                "elif (Path(home) / '.claude/settings.json').exists():\n"
                "    model = json.loads((Path(home) / '.claude/settings.json').read_text())['model']\n"
                "print(json.dumps({'args': args, 'home': home, 'launch_model': model}))\n"
            )
            capture.chmod(0o700)
            env = {
                "PATH": "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin",
                "HOME": str(operator),
                "LIVE": live,
                "E2E_CODEX_BIN": codex,
                "DETERMINISTIC": deterministic,
                "PYRY_BIN": str(capture),
                "PYRY_NAME": "e2e-fixture",
                "DAEMON_RELAY_URL": "wss://relay.example.invalid",
                "ISO_HOME": "/fixture/scripted",
                "SCENARIO": "ping",
                "FAKE_BIN": "/fixture/fakeclaude",
                "FIXTURE_FILE": "/fixture/ping.jsonl",
                "FIXTURE_FILE_2": "",
            }
            result = subprocess.run(
                ["bash", "-c", 'set -euo pipefail\n' + construction + '\n"${DAEMON_COMMAND[@]}"'],
                env=env, capture_output=True, text=True, check=True,
            )
            return json.loads(result.stdout)

    def assert_effort_capable_default(self, launch):
        args = launch["args"]
        # The archived run inherited an ambient Haiku default despite a Sonnet default menu.
        # The test daemon must choose its own effort-capable base without saving a phone model.
        self.assertEqual("sonnet", launch["launch_model"], "real launch still inherits the operator's Haiku default")
        self.assertIn("--", args, "real launch still inherits the operator's Haiku default")
        passthrough = args[args.index("--") + 1:]
        self.assertEqual(["--model", "sonnet"], passthrough)
        self.assertEqual("operator", Path(launch["home"]).name)
        self.assertIn("-pyry-name=e2e-fixture", args)
        self.assertIn("-pyry-workdir=" + launch["home"], args)
        self.assertNotIn("--effort", args, "the phone must still recall and write the effort itself")

    def test_live_primary_uses_effort_capable_default_despite_operator_haiku(self):
        self.assert_effort_capable_default(self.launch(live="1"))

    def test_local_real_primary_uses_the_same_default(self):
        self.assert_effort_capable_default(self.launch())

    def test_live_codex_flag_precedes_claude_passthrough_and_scripted_ignores_it(self):
        binary = "/fixture/dedicated codex"
        for live in ("", "1"):
            launch = self.launch(live=live, codex=binary)
            self.assert_effort_capable_default(launch)
            self.assertLess(launch["args"].index("-pyry-codex=" + binary), launch["args"].index("--"))
        scripted = self.launch(deterministic="1", codex=binary)
        self.assertNotIn("-pyry-codex=" + binary, scripted["args"])


    def test_scripted_primary_keeps_its_isolated_fake_and_default(self):
        launch = self.launch(deterministic="1")
        self.assertEqual("/fixture/scripted", launch["home"])
        self.assertIn("-pyry-claude=/fixture/fakeclaude", launch["args"])
        self.assertNotIn("--model", launch["args"])
        self.assertNotIn("--", launch["args"])


if __name__ == "__main__":
    unittest.main()
