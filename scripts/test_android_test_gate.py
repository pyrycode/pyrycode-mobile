import importlib.util
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("gate", Path(__file__).with_name("android-test-gate.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class AndroidGateTest(unittest.TestCase):
    def report(self, root, xml):
        path = root / "TEST-result.xml"
        path.write_text(xml)
        return path

    def test_auth_preflight_requires_a_successful_logged_in_status(self):
        for code, output, expected in [(0, '{"loggedIn":true}', True),
                                       (1, '{"loggedIn":true}', False),
                                       (0, '{"loggedIn":false}', False),
                                       (0, 'not json', False)]:
            with patch.object(gate.subprocess, "run", return_value=subprocess.CompletedProcess([], code, output, "")):
                self.assertEqual(gate.claude_authenticated({}), expected)
        with patch.object(gate.subprocess, "run", side_effect=FileNotFoundError):
            self.assertFalse(gate.claude_authenticated({}))

    def test_runner_reporting_matches_current_daemon_default(self):
        script = Path(__file__).with_name("e2e-emulator.sh").read_text()
        resolver = script[script.index("resolve_runner_from_config() {"):script.index("# report_interactive_runner")]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            for content, expected in [(None, "stream-json"), ("{}", "stream-json"),
                                      ('{"interactive_runner":"stream-json"}', "stream-json"),
                                      ('{"interactive_runner":"pty"}', "unrecognised")]:
                if content is not None:
                    path.write_text(content)
                result = subprocess.run(["bash", "-c", resolver + '\nresolve_runner_from_config "$1"', "bash", str(path)],
                                        capture_output=True, text=True, check=True)
                self.assertEqual(result.stdout.split("\t")[0], expected)

    def test_no_reports_and_all_skipped_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for files in [[], [self.report(root, '<testsuite tests="1" skipped="1"><testcase classname="C" name="x"><skipped/></testcase></testsuite>')]]:
                with self.assertRaises(ValueError):
                    gate.combine_reports(files, 1)

    def test_failed_case_stays_red_and_private_logs_are_not_exported(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = self.report(Path(tmp), '<testsuite tests="2" failures="1"><testcase classname="C" name="ok"/><testcase classname="C" name="bad"><failure>private details</failure></testcase><system-out>private logs</system-out></testsuite>')
            xml, passed, executed = gate.combine_reports([report], 1)
            self.assertFalse(passed)
            self.assertEqual(executed, 2)
            self.assertNotIn("private", xml)
            self.assertEqual(len(ET.fromstring(xml).findall(".//failure")), 1)

    def test_partial_or_malformed_report_is_not_a_pass(self):
        for xml in ['<testsuite tests="2"><testcase classname="C" name="ok"/></testsuite>', '<testsuite>', '<testsuite tests="1" errors="1"><testcase classname="C" name="ok"/></testsuite>']:
            with self.subTest(xml=xml), tempfile.TemporaryDirectory() as tmp:
                with self.assertRaises(ValueError):
                    gate.combine_reports([self.report(Path(tmp), xml)], 1)

    def test_gradle_managed_device_report_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = self.report(Path(tmp), '<testsuites tests="1" failures="0"><testsuite tests="1"><testcase classname="C" name="ok"/></testsuite></testsuites>')
            xml, passed, executed = gate.combine_reports([report], 1)
            self.assertTrue(passed)
            self.assertEqual(executed, 1)
            self.assertEqual(len(ET.fromstring(xml).findall(".//testcase")), 1)

    def test_stale_reports_are_excluded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.report(root, '<testsuite/>')
            start = time.time_ns()
            self.assertEqual(gate.fresh_reports(root, start), [])
            fresh = root / "TEST-new.xml"
            fresh.write_text('<testsuite/>')
            self.assertEqual(gate.fresh_reports(root, start), [fresh])

    def test_live_floor_and_expected_class_are_enforced(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = self.report(Path(tmp), '<testsuite tests="1"><testcase classname="C" name="ok"/></testsuite>')
            with self.assertRaises(ValueError):
                gate.combine_reports([report], 8)
            with self.assertRaises(ValueError):
                gate.combine_reports([report], 1, "LiveTest")
            _, passed, count = gate.combine_reports([report], 1, "C")
            self.assertTrue(passed)
            self.assertEqual(count, 1)


if __name__ == "__main__":
    unittest.main()
