"""The Stop scenario supplies creating evidence without simulating its Stop response."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class RosterTaskFixtureTest(unittest.TestCase):
    def run_adapter(self, payload):
        with tempfile.TemporaryDirectory() as directory:
            child = Path(directory) / "child.py"
            child.write_text("import sys\nsys.stdout.buffer.write(" + repr(payload) + ")\n")
            env = dict(os.environ, E2E_ROSTER_TASK_CHILD=sys.executable)
            result = subprocess.run(
                [sys.executable, str(Path(__file__).with_name("e2e-roster-task.py")), str(child)],
                env=env, input=b"", capture_output=True, timeout=5,
            )
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            return result.stdout

    def test_linked_work_precedes_first_canned_roster_only(self):
        roster = b'{"type":"system", "subtype":"background_tasks_changed", "tasks":[{"task_id":"bybi8g8i8","task_type":"local_bash","description":"cat $FIFO"}]}\n'
        terminal = b'{"type":"system","subtype":"task_notification","task_id":"bybi8g8i8","status":"stopped"}\n'
        empty = b'{"type":"system","subtype":"background_tasks_changed","tasks":[]}\n'
        payload = roster + terminal + empty + roster
        prefix = (Path(__file__).parent / "e2e-fixtures/stop-background-task-start.jsonl").read_bytes()
        actual = self.run_adapter(payload)
        self.assertEqual(actual, prefix + payload)
        events = [json.loads(line) for line in prefix.splitlines()]
        tool = events[0]["message"]["content"][0]
        self.assertEqual(tool["name"], "Bash")
        self.assertEqual(events[1]["tool_use_id"], tool["id"])
        self.assertEqual(events[1]["task_id"], "bybi8g8i8")
        self.assertEqual(events[2]["message"]["content"][0]["tool_use_id"], tool["id"])
        self.assertNotIn(b'task_notification', prefix)

    def test_empty_or_foreign_rosters_and_other_lines_are_unchanged(self):
        payload = b'not-json\n{"type":"system","subtype":"background_tasks_changed","tasks":[]}\n{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"foreign"}]}\n'
        self.assertEqual(self.run_adapter(payload), payload)


if __name__ == "__main__":
    unittest.main()
