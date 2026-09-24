import importlib.util
import contextlib
import io
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("gate", Path(__file__).with_name("android-test-gate.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def live_report(count):
    """The recorded eight-case live report, padded with synthetic curated cases to [count] (#848).

    The recorded fixture stays byte-for-byte; the padding stands in for the methods added to the curated
    list after it was captured.
    """
    baseline = (Path(__file__).parent / "fixtures/default-workspace-live/588.xml").read_text()
    root = ET.fromstring(baseline)
    suite = root.find("testsuite")
    for index in range(count - len(suite.findall("testcase"))):
        ET.SubElement(suite, "testcase", {"classname": suite.get("name"), "name": f"interactiveTurn_padded{index}"})
    suite.set("tests", str(len(suite.findall("testcase"))))
    return ET.tostring(root, encoding="unicode")


class AndroidGateTest(unittest.TestCase):
    def report(self, root, xml):
        path = root / "TEST-result.xml"
        path.write_text(xml)
        return path

    def test_live_gate_collects_only_fresh_reports_from_selected_device_path(self):
        baseline = live_report(gate.LIVE_MINIMUM)
        for device in ("pixel2Api33Atd", "connected"):
            for result in ("pass", "process_failure", "missing", "stale", "test_failure"):
                with self.subTest(device=device, result=result), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    results = root / "app/build/outputs/androidTest-results"
                    directories = {
                        "pixel2Api33Atd": results / "managedDevice/debug/pixel2Api33Atd",
                        "connected": results / "connected/debug",
                        "otherManaged": results / "managedDevice/debug/otherApi35",
                    }
                    started = 2_000_000_000

                    def run(command, **kwargs):
                        self.assertEqual(command, ["bash", str(root / "scripts/e2e-emulator.sh")])
                        self.assertEqual(kwargs["env"]["DEVICE"], device)
                        self.assertEqual(kwargs["env"]["LIVE"], "1")
                        self.assertEqual(kwargs["env"]["PYRY_FORCE_TEST_RUN"], "1")
                        for profile, directory in directories.items():
                            directory.mkdir(parents=True)
                            if profile == device and result == "missing":
                                continue
                            xml = baseline
                            if profile != device:
                                # Fresh wrong-path XML must never count or contaminate the selected run.
                                xml = xml.replace("interactiveTurn_", "wrongPath_")
                            elif result == "test_failure":
                                xml = xml.replace('failures="0"', 'failures="1"', 1)
                                xml = xml.replace(" />", "><failure>private failure</failure></testcase>", 1)
                            path = self.report(directory, xml)
                            stamp = started - 1 if profile == device and result == "stale" else started + 1
                            os.utime(path, ns=(stamp, stamp))
                        return subprocess.CompletedProcess(command, 7 if result == "process_failure" else 0)

                    stdout = io.StringIO()
                    with patch.object(gate, "ROOT", root), \
                            patch.dict(os.environ, {"DEVICE": device}, clear=True), \
                            patch("sys.argv", ["android-test-gate.py", "live"]), \
                            patch.object(gate, "claude_authenticated", return_value=True), \
                            patch.object(gate.time, "time_ns", return_value=started), \
                            patch.object(gate.subprocess, "run", side_effect=run), \
                            contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(io.StringIO()):
                        self.assertEqual(gate.main(), 0 if result == "pass" else 1)
                    if result in ("missing", "stale"):
                        self.assertEqual(stdout.getvalue(), "")
                        self.assertEqual(list(root.rglob("dispatcher.xml")), [])
                    else:
                        cases = ET.fromstring(stdout.getvalue()).findall(".//testcase")
                        self.assertEqual(len(cases), gate.LIVE_MINIMUM)
                        self.assertTrue(all(case.get("name").startswith("interactiveTurn_") for case in cases))
                        self.assertNotIn("private failure", stdout.getvalue())
                        self.assertEqual(len(list(root.rglob("dispatcher.xml"))), 1)

    def test_live_floor_matches_the_curated_list(self):
        # #848: the floor is the curated list's size, so a method dropped from the list reddens the gate.
        script = (Path(__file__).parent / "e2e-emulator.sh").read_text()
        live = script[script.index('elif [ -n "${LIVE}" ]; then\n  # LIVE curates'):]
        # The LIVE branch may build the list over several assignments, so count across all of them.
        branch = live[: live.index("\nelse\n")]
        targets = [line for line in branch.split("\n") if line.lstrip().startswith('TEST_TARGET="')]
        self.assertEqual(gate.LIVE_MINIMUM, sum(target.count("#interactiveTurn_") for target in targets))
        with tempfile.TemporaryDirectory() as tmp:
            short = self.report(Path(tmp), live_report(gate.LIVE_MINIMUM - 1))
            with self.assertRaises(ValueError):
                gate.combine_reports([short], gate.LIVE_MINIMUM)

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

    def test_ui_skip_covers_only_paths_the_suite_cannot_see(self):
        live, deterministic, peer = gate.E2E_ONLY_SOURCES
        for paths in (["docs/knowledge/features/x.md"], ["README.md"], ["scripts/e2e-emulator.sh", live],
                      ["scripts/android-test-gate.py", peer, deterministic]):
            with self.subTest(paths=paths):
                self.assertTrue(gate.ui_suite_skippable(paths))
        e2e = "app/src/androidTest/java/de/pyryco/mobile/e2e/"
        for paths in ([], None, ["app/src/main/java/de/pyryco/mobile/MainActivity.kt"], [e2e + "E2eTestApplication.kt"],
                      [e2e + "E2eInstrumentationRunner.kt"],
                      ["app/src/sharedTest/java/de/pyryco/mobile/e2e/UnrecognizedRowSentinel.kt"],
                      ["app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/SessionBoundaryAssertions.kt"],
                      ["app/build.gradle.kts"], ["gradle/libs.versions.toml"], ["docs/a.md", "app/src/main/X.kt"]):
            with self.subTest(paths=paths):
                self.assertFalse(gate.ui_suite_skippable(paths))

    def test_e2e_only_sources_stay_unused_by_the_ui_suite(self):
        # A UI test or the runner reaching one of these would make the skip hide a real regression.
        repo = Path(__file__).resolve().parents[1]
        names = [Path(path).stem for path in gate.E2E_ONLY_SOURCES]
        for path in gate.E2E_ONLY_SOURCES:
            self.assertTrue((repo / path).is_file(), path)
        pattern = re.compile(r"\b(" + "|".join(names) + r")\b")
        sources = [repo / "app/build.gradle.kts", *(repo / "app/src").rglob("*.kt")]
        for source in sources:
            if str(source.relative_to(repo)) in gate.E2E_ONLY_SOURCES:
                continue
            code = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", source.read_text(), flags=re.S))
            with self.subTest(source=source.name):
                self.assertIsNone(pattern.search(code))

    def test_ui_gate_skips_gradle_only_when_the_branch_cannot_affect_the_suite(self):
        docs_only, app = ["docs/a.md"], ["app/src/main/X.kt"]
        for changed, environment, runs in ((docs_only, {}, False), (docs_only, {"UI_GATE_FULL": "1"}, True),
                                           (app, {}, True), ([], {}, True), (None, {}, True)):
            with self.subTest(changed=changed, environment=environment), tempfile.TemporaryDirectory() as tmp:
                self.device_test(Path(tmp), "de/pyryco/mobile/data/StoreTest.kt")
                run = Mock(return_value=subprocess.CompletedProcess([], 0))
                with patch.object(gate, "ROOT", Path(tmp)), patch.dict(os.environ, environment, clear=True), \
                        patch("sys.argv", ["android-test-gate.py", "ui"]), \
                        patch.object(gate, "changed_paths", return_value=changed), \
                        patch.object(gate.subprocess, "run", run), contextlib.redirect_stderr(io.StringIO()):
                    result = gate.main()
                self.assertEqual(run.called, runs)
                if runs:
                    self.assertIn(":app:pixel2Api33AtdDebugAndroidTest", run.call_args.args[0])
                else:
                    self.assertEqual(result, 0)

    def device_test(self, root, relative, body="@Test fun ok() {}"):
        path = root / "app/src/androidTest/java" / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)

    def ui_command(self, root, environment):
        run = Mock(return_value=subprocess.CompletedProcess([], 0))
        with patch.object(gate, "ROOT", root), patch.dict(os.environ, environment, clear=True), \
                patch("sys.argv", ["android-test-gate.py", "ui"]), \
                patch.object(gate, "changed_paths", return_value=["app/src/main/X.kt"]), \
                patch.object(gate.subprocess, "run", run), contextlib.redirect_stderr(io.StringIO()):
            result = gate.main()
        return result, run.call_args.args[0] if run.called else None

    def test_ui_gate_runs_only_device_only_classes_unless_asked_for_all(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.device_test(root, "de/pyryco/mobile/ui/KeyboardTest.kt")
            self.device_test(root, "de/pyryco/mobile/data/StoreTest.kt")
            self.device_test(root, "de/pyryco/mobile/ui/Helper.kt", "fun helper() {}")
            self.device_test(root, "de/pyryco/mobile/e2e/LiveTest.kt")
            with patch.object(gate, "ROOT", root):
                self.assertEqual(gate.device_only_classes(),
                                 ["de.pyryco.mobile.data.StoreTest", "de.pyryco.mobile.ui.KeyboardTest"])
            _, command = self.ui_command(root, {})
            self.assertIn("-Pandroid.testInstrumentationRunnerArguments.class="
                          "de.pyryco.mobile.data.StoreTest,de.pyryco.mobile.ui.KeyboardTest", command)
            self.assertIn("-Pandroid.experimental.androidTest.numManagedDeviceShards=1", command)
            _, command = self.ui_command(root, {"UI_DEVICE_ALL": "1"})
            self.assertFalse(any("testInstrumentationRunnerArguments.class=" in part for part in command))
            self.assertIn("-Pandroid.experimental.androidTest.numManagedDeviceShards=2", command)

    def test_ui_gate_refuses_an_empty_device_only_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            result, command = self.ui_command(Path(tmp), {})
            self.assertEqual(result, 1)
            self.assertIsNone(command)

    def test_managed_avd_is_found_only_for_the_managed_device(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / "avd" / "gradle-managed"
            home.mkdir(parents=True)
            with patch.dict(os.environ, {"ANDROID_USER_HOME": tmp}):
                self.assertIsNone(gate.managed_avd("pixel2Api33Atd"))
                (home / "dev33_aosp_atd_arm64-v8a_Pixel_2.ini").write_text("")
                self.assertEqual(gate.managed_avd("pixel2Api33Atd"), (home, "dev33_aosp_atd_arm64-v8a_Pixel_2"))
                self.assertIsNone(gate.managed_avd("otherDevice"))

    def test_free_emulator_port_skips_a_busy_pair(self):
        port = gate.free_emulator_port()
        self.assertEqual(port % 2, 0)
        with socket.socket() as busy:
            busy.bind(("127.0.0.1", port))
            self.assertNotEqual(gate.free_emulator_port(), port)

    def test_scripted_all_runs_every_scenario_and_names_the_failures(self):
        # No AVD, so it falls back to the managed device per scenario; the report logic is the same.
        expected = gate.E2E_PACKAGE + ".DeterministicInteractiveStreamE2ETest"
        with tempfile.TemporaryDirectory() as tmp:
            root, run_dir = Path(tmp), Path(tmp) / "run"
            run_dir.mkdir()
            reports = root / "app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd"
            reports.mkdir(parents=True)
            seen = []

            def run(command, **kwargs):
                scenario = kwargs["env"]["SCENARIO"]
                seen.append((scenario, kwargs["env"]["DETERMINISTIC"], kwargs["env"]["DEVICE"]))
                failure = "<failure/>" if scenario == "reconnect" else ""
                (reports / "TEST-result.xml").write_text(
                    f'<testsuite tests="1" failures="{1 if failure else 0}">'
                    f'<testcase classname="{expected}" name="{scenario}">{failure}</testcase></testsuite>')
                return subprocess.CompletedProcess(command, 1 if failure else 0)

            stderr, stdout = io.StringIO(), io.StringIO()
            with patch.object(gate, "ROOT", root), patch.object(gate, "managed_avd", return_value=None), \
                    patch.object(gate.subprocess, "run", run), patch.object(gate.signal, "signal"), \
                    contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(stdout):
                result = gate.run_scripted_all({"ANDROID_HOME": "/sdk"}, run_dir, "pixel2Api33Atd")
        self.assertEqual(result, 1)
        self.assertEqual([s for s, _, _ in seen], list(gate.SCENARIOS))
        self.assertTrue(all(d == "1" and device == "pixel2Api33Atd" for _, d, device in seen))
        self.assertIn("scripted reconnect: FAIL", stderr.getvalue())
        self.assertIn("failed: reconnect", stderr.getvalue())
        self.assertEqual(stdout.getvalue().count("<testcase"), len(gate.SCENARIOS))

    def test_changed_paths_lists_branch_uncommitted_and_untracked_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            env = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
                   "GIT_COMMITTER_EMAIL": "t@t"}

            def git(*args):
                subprocess.run(["git", *args], cwd=root, env=env, check=True, capture_output=True)
            git("init", "-b", "main")
            for name in ("kept.md", "edited.kt"):
                (root / name).write_text("base\n")
            git("add", ".")
            git("commit", "-m", "base")
            git("checkout", "-b", "feature")
            (root / "docs").mkdir()
            (root / "docs/new.md").write_text("branch\n")
            git("add", ".")
            git("commit", "-m", "branch")
            git("checkout", "main")
            (root / "later.md").write_text("main moved on\n")
            git("add", ".")
            git("commit", "-m", "main")
            git("checkout", "feature")
            (root / "edited.kt").write_text("uncommitted\n")
            (root / "untracked.kt").write_text("new\n")
            with patch.object(gate, "ROOT", root):
                self.assertEqual(gate.changed_paths(), ["docs/new.md", "edited.kt", "untracked.kt"])
                self.assertIsNone(gate.changed_paths("no-such-branch"))

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
