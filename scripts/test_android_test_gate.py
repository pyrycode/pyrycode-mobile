import importlib.util
import contextlib
import io
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("gate", Path(__file__).with_name("android-test-gate.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
REAL_BUILD_APKS = gate.build_apks


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


class DevAgentsAuthTest(unittest.TestCase):
    def test_fetches_login_only_into_child_environment(self):
        parent = {"OP_SERVICE_ACCOUNT_TOKEN": "restricted-fixture", "OP_SESSION_personal": "session-fixture", "PATH": "/bin"}
        with patch.object(gate.subprocess, "run", return_value=Mock(returncode=0, stdout="login-fixture\n")) as run:
            child = gate.live_claude_environment(parent)
        self.assertEqual(child["CLAUDE_CODE_OAUTH_TOKEN"], "login-fixture")
        self.assertNotIn("OP_SERVICE_ACCOUNT_TOKEN", child)
        self.assertNotIn("OP_SESSION_personal", child)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", parent)
        self.assertEqual(run.call_args.kwargs["env"]["OP_SERVICE_ACCOUNT_TOKEN"], "restricted-fixture")
        self.assertNotIn("OP_SESSION_personal", run.call_args.kwargs["env"])
        self.assertNotIn("restricted-fixture", str(run.call_args.args))

    def test_existing_login_needs_no_account_fetch(self):
        with patch.object(gate.subprocess, "run") as run:
            child = gate.live_claude_environment({"CLAUDE_CODE_OAUTH_TOKEN": "login-fixture", "OP_SERVICE_ACCOUNT_TOKEN": "restricted-fixture"})
        run.assert_not_called()
        self.assertNotIn("OP_SERVICE_ACCOUNT_TOKEN", child)

    def test_no_account_leaves_interactive_login_available(self):
        with patch.object(gate.subprocess, "run") as run:
            self.assertEqual(gate.live_claude_environment({"PATH": "/bin"}), {"PATH": "/bin"})
        run.assert_not_called()

    def hand_run(self, parent, result):
        with tempfile.TemporaryDirectory() as bin_dir:
            helper = Path(bin_dir) / "automation-access"
            helper.write_text("#!/bin/sh\nexit 1\n")
            helper.chmod(0o755)
            stderr = io.StringIO()
            with patch.object(gate.subprocess, "run", **({"side_effect": result} if isinstance(result, Exception)
                                                         else {"return_value": result})) as run, \
                    contextlib.redirect_stderr(stderr):
                child = gate.live_claude_environment({"PATH": bin_dir, **parent})
            return child, run, stderr.getvalue(), str(helper)

    def test_a_hand_run_fetches_the_long_term_login_through_automation_access(self):
        child, run, stderr, helper = self.hand_run({}, Mock(returncode=0, stdout="login-fixture"))
        self.assertEqual(child["CLAUDE_CODE_OAUTH_TOKEN"], "login-fixture")
        self.assertEqual(run.call_args.args[0], [helper, "op", "read", "--no-newline",
                                                 "op://Automation/Claude long term token/password"])
        self.assertIn("fetched the long-term Claude login", stderr)
        self.assertNotIn("login-fixture", stderr)

    def test_a_run_inside_the_dispatcher_never_fetches_the_automation_login(self):
        child, run, _, _ = self.hand_run({"AGENTS_REPO_PATH": "/agents"}, Mock(returncode=0, stdout="login-fixture"))
        run.assert_not_called()
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", child)

    def test_a_failed_hand_run_fetch_falls_back_to_the_shells_own_login(self):
        for result in [Mock(returncode=1, stdout="", stderr="login-fixture"), Mock(returncode=0, stdout=""),
                       FileNotFoundError("login-fixture"), subprocess.TimeoutExpired("login-fixture", 60)]:
            with self.subTest(result=type(result).__name__):
                child, _, stderr, _ = self.hand_run({}, result)
                self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", child)
                self.assertIn("using this shell's own login", stderr)
                self.assertNotIn("login-fixture", stderr)

    def test_missing_item_empty_login_missing_cli_and_timeout_are_sanitized_environment_errors(self):
        for result in [Mock(returncode=1, stdout="", stderr="restricted-fixture login-fixture"), Mock(returncode=0, stdout=""), FileNotFoundError("restricted-fixture"), subprocess.TimeoutExpired("login-fixture", 30)]:
            with self.subTest(result=type(result).__name__), patch.object(gate.subprocess, "run", **({"side_effect": result} if isinstance(result, Exception) else {"return_value": result})):
                with self.assertRaises(gate.ClaudeEnvironmentError) as raised:
                    gate.live_claude_environment({"OP_SERVICE_ACCOUNT_TOKEN": "restricted-fixture"})
                self.assertNotIn("restricted-fixture", str(raised.exception))
                self.assertNotIn("login-fixture", str(raised.exception))
                self.assertIn("Dev Agents", str(raised.exception))


class AndroidGateTest(unittest.TestCase):
    def setUp(self):
        # The APK build before the device hold has its own tests; elsewhere it succeeds without running.
        self.enterContext(patch.object(gate, "build_apks", return_value=0))

    def report(self, root, xml):
        path = root / "TEST-result.xml"
        path.write_text(xml)
        return path

    def test_live_gate_collects_only_fresh_reports_from_selected_device_path(self):
        baseline = live_report(gate.LIVE_MINIMUM)
        for device in ("pixel2Api33Atd", "connected"):
            for result in ("pass", "process_failure", "missing", "stale", "test_failure",
                           "below_floor", "below_floor_with_failure"):
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
                            elif result.startswith("below_floor"):
                                document = ET.fromstring(xml)
                                suite = document.find("testsuite")
                                cases = suite.findall("testcase")
                                for case in cases[-2:]:
                                    ET.SubElement(case, "skipped")
                                suite.set("skipped", "2")
                                if result == "below_floor_with_failure":
                                    ET.SubElement(cases[0], "failure").text = "private failure"
                                    suite.set("failures", "1")
                                xml = ET.tostring(document, encoding="unicode")
                            path = self.report(directory, xml)
                            stamp = started - 1 if profile == device and result == "stale" else started + 1
                            os.utime(path, ns=(stamp, stamp))
                        return subprocess.CompletedProcess(command, 7 if result == "process_failure" else 0)

                    stdout = io.StringIO()
                    with patch.object(gate, "ROOT", root), \
                            patch.dict(os.environ, {"DEVICE": device, "ANDROID_USER_HOME": tmp}, clear=True), \
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
                        if result.startswith("below_floor"):
                            self.assertEqual(len(ET.fromstring(stdout.getvalue()).findall(".//skipped")), 2)

    def test_selection_copy_is_registered_for_focused_and_all_scripted_runs(self):
        self.assertIn("selection-copy", gate.SCENARIOS)
        script = (gate.ROOT / "scripts/e2e-emulator.sh").read_text()
        start = script.index('    selection-copy)')
        arm = script[start:script.index('      ;;', start)]
        self.assertIn('interactiveTurn_seededChannel_systemCopyCopiesSelectedWord', arm)
        self.assertIn('selection-copy.jsonl', arm)
        fixture = gate.ROOT / "scripts/e2e-fixtures/selection-copy.jsonl"
        records = [json.loads(line) for line in fixture.read_text().splitlines()]
        self.assertEqual("amber cobalt jade", records[0]["message"]["content"][0]["text"])
        self.assertEqual("end_turn", records[0]["message"]["stop_reason"])
        self.assertEqual("success", records[-1]["subtype"])

    def test_live_floor_matches_the_curated_list(self):
        # #848: the floor is the curated list's size, so every listed method must execute.
        script = (Path(__file__).parent / "e2e-emulator.sh").read_text()
        live = script[script.index('elif [ -n "${LIVE}" ]; then\n  # LIVE curates'):]
        # The LIVE branch may build the list over several assignments, so count across all of them.
        branch = live[: live.index("\nelse\n")]
        targets = [line for line in branch.split("\n") if line.lstrip().startswith('TEST_TARGET="')]
        self.assertGreater(gate.LIVE_MINIMUM, 0)
        self.assertEqual(gate.LIVE_MINIMUM, sum(target.count("#interactiveTurn_") for target in targets))
        with tempfile.TemporaryDirectory() as tmp:
            short = self.report(Path(tmp), live_report(gate.LIVE_MINIMUM - 1))
            _, _, executed = gate.combine_reports([short])
            self.assertEqual(executed, gate.LIVE_MINIMUM - 1)

    def test_live_curated_list_matches_the_runnable_methods(self):
        # The list holds exactly the live class's @Test methods that are not @Ignored. A new method missing
        # from the list, or one dropped from it in a merge, reddens this before a live run does.
        source = (Path(__file__).parent.parent / "app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt").read_text()
        runnable = {
            match.group(2)
            for match in re.finditer(r"((?:\s*@\w+(?:\([^)]*\))?\s*)+)\s*fun\s+(\w+)\s*\(", source)
            if "@Test" in match.group(1) and "@Ignore" not in match.group(1)
        }
        listed = gate.curated_live_methods()
        self.assertEqual(len(listed), len(set(listed)), "a method is listed twice")
        self.assertEqual(sorted(runnable - set(listed)), [], "runnable live methods missing from the curated list")
        self.assertEqual(sorted(set(listed) - runnable), [], "listed methods that are not runnable live methods")

    def test_live_curated_list_excludes_ignored_methods(self):
        script = (Path(__file__).parent / "e2e-emulator.sh").read_text()
        live = script[script.index('elif [ -n "${LIVE}" ]; then\n  # LIVE curates'):]
        branch = live[: live.index("\nelse\n")]
        assignments = [line for line in branch.splitlines() if line.lstrip().startswith('TEST_TARGET="')]
        targets = set(re.findall(r"#(interactiveTurn_\w+)", "\n".join(assignments)))
        source = (Path(__file__).parent.parent / "app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt").read_text()
        lines = source.splitlines()
        ignored = {
            match.group(1)
            for index, line in enumerate(lines)
            if (match := re.search(r"fun (interactiveTurn_\w+)\(", line))
            and "@Ignore" in "\n".join(lines[max(0, index - 3):index])
        }
        self.assertEqual(set(), targets & ignored)

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

    def test_no_reports_fail_and_all_skipped_count_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaises(ValueError):
                gate.combine_reports([])
            skipped = self.report(root, '<testsuite tests="1" skipped="1"><testcase classname="C" name="x"><skipped/></testcase></testsuite>')
            _, passed, executed = gate.combine_reports([skipped])
            self.assertTrue(passed)
            self.assertEqual(executed, 0)

    def test_failed_case_stays_red_and_private_logs_are_not_exported(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = self.report(Path(tmp), '<testsuite tests="2" failures="1"><testcase classname="C" name="ok"/><testcase classname="C" name="bad"><failure>private details</failure></testcase><system-out>private logs</system-out></testsuite>')
            xml, passed, executed = gate.combine_reports([report])
            self.assertFalse(passed)
            self.assertEqual(executed, 2)
            self.assertNotIn("private", xml)
            self.assertEqual(len(ET.fromstring(xml).findall(".//failure")), 1)

    def test_partial_or_malformed_report_is_not_a_pass(self):
        for xml in ['<testsuite tests="2"><testcase classname="C" name="ok"/></testsuite>', '<testsuite>', '<testsuite tests="1" errors="1"><testcase classname="C" name="ok"/></testsuite>']:
            with self.subTest(xml=xml), tempfile.TemporaryDirectory() as tmp:
                with self.assertRaises(ValueError):
                    gate.combine_reports([self.report(Path(tmp), xml)])

    def test_gradle_managed_device_report_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = self.report(Path(tmp), '<testsuites tests="1" failures="0"><testsuite tests="1"><testcase classname="C" name="ok"/></testsuite></testsuites>')
            xml, passed, executed = gate.combine_reports([report])
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
        e2e = "app/src/androidTest/java/de/pyryco/mobile/e2e/"
        live, deterministic, peer = [e2e + name + ".kt" for name in
                                     ("InteractiveStreamE2ETest", "DeterministicInteractiveStreamE2ETest", "SecondClientPeer")]
        for paths in (["docs/knowledge/features/x.md"], ["README.md"], ["scripts/e2e-emulator.sh", live],
                      ["scripts/android-test-gate.py", peer, deterministic], [e2e + "PeerIdentityLifecycleTest.kt"]):
            with self.subTest(paths=paths):
                self.assertTrue(gate.ui_suite_skippable(paths))
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
                with patch.object(gate, "ROOT", Path(tmp)), patch.dict(os.environ, {**environment, "ANDROID_USER_HOME": tmp}, clear=True), \
                        patch("sys.argv", ["android-test-gate.py", "ui"]), \
                        patch.object(gate, "changed_paths", return_value=changed), \
                        patch.object(gate.subprocess, "run", run), contextlib.redirect_stderr(io.StringIO()):
                    result = gate.main()
                self.assertEqual(run.called, runs)
                # #1071: a skipped run never takes the device hold.
                self.assertEqual((Path(tmp) / "avd" / "pyrycode-device-gate.lock").exists(), runs)
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
        with patch.object(gate, "ROOT", root), patch.dict(os.environ, {**environment, "ANDROID_USER_HOME": str(root)}, clear=True), \
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
            self.assertIn("-Pandroid.testInstrumentationRunnerArguments.disableAnimations=true", command)
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
                # #955: the AVD left from the aosp-atd image has no Play services, so it is never booted.
                (home / "dev33_aosp_atd_arm64-v8a_Pixel_2.ini").write_text("")
                self.assertIsNone(gate.managed_avd("pixel2Api33Atd"))
                (home / "dev33_google_atd_arm64-v8a_Pixel_2.ini").write_text("")
                self.assertEqual(gate.managed_avd("pixel2Api33Atd"), (home, "dev33_google_atd_arm64-v8a_Pixel_2"))
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
                (reports / f"logcat-{expected}-{scenario}.txt").write_text(f"{scenario} lines\n")
                return subprocess.CompletedProcess(command, 1 if failure else 0)

            stderr, stdout = io.StringIO(), io.StringIO()
            with patch.object(gate, "ROOT", root), patch.object(gate, "managed_avd", return_value=None), \
                    patch.object(gate.subprocess, "run", run), patch.object(gate.signal, "signal"), \
                    contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(stdout):
                result = gate.run_scripted_all({"ANDROID_HOME": "/sdk"}, run_dir, "pixel2Api33Atd")
            # #1039: each scenario's logcat is kept under its own name before the next scenario runs.
            for scenario in gate.SCENARIOS:
                self.assertEqual((run_dir / f"{scenario}-0-logcat-{expected}-{scenario}.txt").read_text(),
                                 f"{scenario} lines\n")
        self.assertEqual(result, 1)
        self.assertEqual([s for s, _, _ in seen], list(gate.SCENARIOS))
        self.assertTrue(all(d == "1" and device == "pixel2Api33Atd" for _, d, device in seen))
        self.assertIn("scripted reconnect: FAIL", stderr.getvalue())
        self.assertIn("failed: reconnect", stderr.getvalue())
        self.assertEqual(stdout.getvalue().count("<testcase"), len(gate.SCENARIOS))

    def scripted_all_on_own_emulator(self, reset_results):
        """scripted-all with a booted emulator: the env each scenario saw and the order of resets and scenarios."""
        expected = gate.E2E_PACKAGE + ".DeterministicInteractiveStreamE2ETest"
        events, envs = [], []
        with tempfile.TemporaryDirectory() as tmp:
            root, run_dir = Path(tmp), Path(tmp) / "run"
            run_dir.mkdir()
            reports = root / "app/build/outputs/androidTest-results/connected/debug"
            reports.mkdir(parents=True)

            def run(command, **kwargs):
                scenario = kwargs["env"]["SCENARIO"]
                events.append(("scenario", scenario))
                envs.append(kwargs["env"])
                (reports / "TEST-installed.xml").write_text(
                    f'<testsuite tests="1"><testcase classname="{expected}" name="{scenario}"/></testsuite>')
                return subprocess.CompletedProcess(command, 0)

            resets = iter(reset_results)

            def reset(env, serial, granted):
                events.append(("reset", serial, tuple(granted)))
                return next(resets, True)

            with patch.object(gate, "ROOT", root), patch.object(gate, "managed_avd", return_value=(root, "avd")), \
                    patch.object(gate, "boot_emulator", return_value=("emulator-5600", None)), \
                    patch.object(gate, "install_once", return_value=["android.permission.CAMERA"]) as install, \
                    patch.object(gate, "reset_app", side_effect=reset), \
                    patch.object(gate.subprocess, "run", run), patch.object(gate.signal, "signal"), \
                    contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                result = gate.run_scripted_all({"ANDROID_HOME": "/sdk"}, run_dir, "pixel2Api33Atd")
        install.assert_called_once_with({"ANDROID_HOME": "/sdk"}, "emulator-5600")
        return result, events, envs

    def test_scripted_all_installs_once_and_clears_the_app_before_every_scenario(self):
        result, events, envs = self.scripted_all_on_own_emulator([])
        self.assertEqual(result, 0)
        reset = ("reset", "emulator-5600", ("android.permission.CAMERA",))
        self.assertEqual(events, [item for scenario in gate.SCENARIOS for item in (reset, ("scenario", scenario))])
        self.assertTrue(all(env["E2E_INSTALLED"] == "1" and env["ANDROID_SERIAL"] == "emulator-5600"
                            and env["DEVICE"] == "connected" for env in envs))

    def test_scripted_all_falls_back_to_gradle_installs_once_a_clear_fails(self):
        result, events, envs = self.scripted_all_on_own_emulator([True, False])
        self.assertEqual(result, 0)
        self.assertEqual([event[0] for event in events[:4]], ["reset", "scenario", "reset", "scenario"])
        self.assertEqual([event[0] for event in events[4:]], ["scenario"] * (len(gate.SCENARIOS) - 2))
        self.assertEqual([env.get("E2E_INSTALLED") for env in envs], ["1"] + [None] * (len(gate.SCENARIOS) - 1))

    def test_install_once_installs_both_apks_and_returns_the_granted_runtime_permissions(self):
        dump = ("    install permissions:\n      android.permission.INTERNET: granted=true\n"
                "    User 0: installed=true\n      runtime permissions:\n"
                "        android.permission.POST_NOTIFICATIONS: granted=true, flags=[ ]\n"
                "        android.permission.CAMERA: granted=true, flags=[ ]\n"
                "        android.permission.READ_CONTACTS: granted=false, flags=[ ]\n")
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, dump if "dumpsys" in command else "", "")

        root = Path("/tree")
        with patch.object(gate, "ROOT", root), patch.object(gate.subprocess, "run", run):
            granted = gate.install_once({"ANDROID_HOME": "/sdk"}, "emulator-5600")
        adb = ["/sdk/platform-tools/adb", "-s", "emulator-5600"]
        self.assertEqual(calls, [adb + ["install", "-r", "-t", "-g", str(root / gate.APKS[0])],
                                 adb + ["install", "-r", "-t", "-g", str(root / gate.APKS[1])],
                                 adb + ["shell", "dumpsys", "package", "de.pyryco.mobile"]])
        self.assertEqual(granted, ["android.permission.POST_NOTIFICATIONS", "android.permission.CAMERA"])
        failed = Mock(return_value=subprocess.CompletedProcess([], 1))
        with patch.object(gate.subprocess, "run", failed), contextlib.redirect_stderr(io.StringIO()):
            self.assertIsNone(gate.install_once({"ANDROID_HOME": "/sdk"}, "emulator-5600"))
        self.assertEqual(failed.call_count, 1)

    def test_reset_app_clears_the_data_then_grants_back(self):
        run = Mock(return_value=subprocess.CompletedProcess([], 0))
        with patch.object(gate.subprocess, "run", run):
            self.assertTrue(gate.reset_app({"ANDROID_HOME": "/sdk"}, "emulator-5600", ["p.A", "p.B"]))
        adb = ["/sdk/platform-tools/adb", "-s", "emulator-5600", "shell", "pm"]
        self.assertEqual([call.args[0] for call in run.call_args_list],
                         [adb + ["clear", "de.pyryco.mobile"], adb + ["grant", "de.pyryco.mobile", "p.A"],
                          adb + ["grant", "de.pyryco.mobile", "p.B"]])
        run = Mock(side_effect=subprocess.TimeoutExpired("adb", 300))
        with patch.object(gate.subprocess, "run", run), contextlib.redirect_stderr(io.StringIO()):
            self.assertFalse(gate.reset_app({"ANDROID_HOME": "/sdk"}, "emulator-5600", ["p.A"]))

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

    def test_live_tests_accepts_only_live_class_methods(self):
        method = gate.LIVE_CLASS + "#interactiveTurn_a"
        self.assertEqual(gate.parse_live_tests(f"{method}, {method},{gate.LIVE_CLASS}#b"), [method, gate.LIVE_CLASS + "#b"])
        for bad in ("", ",", "Other#a", gate.LIVE_CLASS, gate.LIVE_CLASS + "#a[0]", gate.LIVE_CLASS + "#a b",
                    f"{method},Other#a"):
            with self.subTest(bad=bad):
                self.assertIsNone(gate.parse_live_tests(bad))

    def test_live_tests_runs_the_subset_with_a_floor_of_one(self):
        method = gate.LIVE_CLASS + "#interactiveTurn_ping"
        seen = {}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = root / "app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd"
            started = 2_000_000_000

            def run(command, **kwargs):
                seen.update(kwargs["env"])
                directory.mkdir(parents=True)
                path = self.report(directory, f'<testsuite tests="1"><testcase classname="{gate.LIVE_CLASS}" '
                                              'name="interactiveTurn_ping"/></testsuite>')
                os.utime(path, ns=(started + 1, started + 1))
                return subprocess.CompletedProcess(command, 0)

            stdout = io.StringIO()
            with patch.object(gate, "ROOT", root), \
                    patch.dict(os.environ, {"LIVE_TESTS": "stale", "ANDROID_USER_HOME": tmp}, clear=True), \
                    patch("sys.argv", ["android-test-gate.py", "live", "--tests", method]), \
                    patch.object(gate, "claude_authenticated", return_value=True), \
                    patch.object(gate.time, "time_ns", return_value=started), \
                    patch.object(gate.subprocess, "run", side_effect=run), \
                    contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(gate.main(), 0)
        self.assertEqual(seen["LIVE_TESTS"], method)
        self.assertEqual(len(ET.fromstring(stdout.getvalue()).findall(".//testcase")), 1)

    def test_live_run_keeps_fresh_per_test_logcat_even_when_the_report_is_broken(self):
        # #1039: the managed device writes logcat-<class>-<method>.txt per test and the next run overwrites it,
        # so the gate copies each fresh one into the run's artifact directory. A broken report must not lose it.
        for report in ("pass", "malformed"):
            with self.subTest(report=report), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                directory = root / "app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd"
                started = 2_000_000_000

                def run(command, **kwargs):
                    device = directory / "emulator-5554"
                    device.mkdir(parents=True)
                    xml = baseline if report == "pass" else "<testsuite>"
                    path = self.report(directory, xml)
                    fresh = device / f"logcat-{gate.LIVE_CLASS}-interactiveTurn_ping.txt"
                    fresh.write_text("I RelayLog: event=transport_end end=peer_close code=1011\n")
                    stale = device / f"logcat-{gate.LIVE_CLASS}-interactiveTurn_old.txt"
                    stale.write_text("old run\n")
                    for item, stamp in ((path, started + 1), (fresh, started + 1), (stale, started - 1)):
                        os.utime(item, ns=(stamp, stamp))
                    return subprocess.CompletedProcess(command, 0)

                baseline = live_report(gate.LIVE_MINIMUM)
                stdout = io.StringIO()
                with patch.object(gate, "ROOT", root), patch.dict(os.environ, {"ANDROID_USER_HOME": tmp}, clear=True), \
                        patch("sys.argv", ["android-test-gate.py", "live"]), \
                        patch.object(gate, "claude_authenticated", return_value=True), \
                        patch.object(gate.time, "time_ns", return_value=started), \
                        patch.object(gate.subprocess, "run", side_effect=run), \
                        contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(gate.main(), 0 if report == "pass" else 1)
                run_dir = next((root / "build/dispatcher-tests").glob("live-*"))
                kept = sorted(path.name for path in run_dir.glob("*logcat-*"))
                self.assertEqual(kept, [f"0-logcat-{gate.LIVE_CLASS}-interactiveTurn_ping.txt"])
                self.assertIn("end=peer_close code=1011", (run_dir / kept[0]).read_text())
                self.assertNotIn("RelayLog", stdout.getvalue())

    def ui_run_with_logcats(self, root, report_xml, logcats):
        """Drive `ui` through main() with a fake Gradle run writing [report_xml] and fresh or stale logcats."""
        self.device_test(root, "de/pyryco/mobile/ui/KeyboardTest.kt")
        directory = root / "app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd"
        started = 2_000_000_000

        def run(command, **kwargs):
            device = directory / "emulator-5554"
            device.mkdir(parents=True)
            stamped = [(self.report(directory, report_xml), started + 1)]
            for name, (text, fresh) in logcats.items():
                path = device / f"logcat-C-{name}.txt"
                path.write_text(text)
                stamped.append((path, started + 1 if fresh else started - 1))
            for item, stamp in stamped:
                os.utime(item, ns=(stamp, stamp))
            return subprocess.CompletedProcess(command, 0)

        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(gate, "ROOT", root), patch.dict(os.environ, {"ANDROID_USER_HOME": str(root)}, clear=True), \
                patch("sys.argv", ["android-test-gate.py", "ui"]), \
                patch.object(gate, "changed_paths", return_value=["app/src/main/X.kt"]), \
                patch.object(gate.time, "time_ns", return_value=started), \
                patch.object(gate.subprocess, "run", side_effect=run), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = gate.main()
        return result, stdout.getvalue(), stderr.getvalue()

    def test_failing_device_test_prints_its_focus_record_on_stderr_only(self):
        # #1131: the device-side listener logs the window manager's focus state under tag FocusRecord when a test
        # fails; the dispatcher log keeps the gate's stderr after the worktree and its logcats are gone.
        record = ("test=C#bad focus=Window{1a u0 Application Not Responding: com.android.systemui} "
                  "focusedApp=ActivityRecord{2b u0 de.pyryco.mobile/.MainActivity t9} "
                  "anr=Application Not Responding: com.android.systemui")
        failed = '<testsuite tests="3" failures="2"><testcase classname="C" name="ok"/>' \
                 '<testcase classname="C" name="bad"><failure>x</failure></testcase>' \
                 '<testcase classname="C" name="broken"><failure>x</failure></testcase></testsuite>'
        with tempfile.TemporaryDirectory() as tmp:
            result, stdout, stderr = self.ui_run_with_logcats(Path(tmp), failed, {
                "ok": ("09-25 18:27:01.100  1842  1856 I TestRunner: started: ok(C)\n", True),
                "bad": (f"09-25 18:27:02.100  1842  1856 I TestRunner: started: bad(C)\n"
                        f"09-25 18:27:03.200  1842  1856 W FocusRecord: {record}\n"
                        f"09-25 18:27:03.300  1842  1856 I TestRunner: finished: bad(C)\n", True),
                "broken": ("09-25 18:27:04.200  1842  1856 W FocusRecord: test=C#broken error=SecurityException: denied\n",
                           True),
                "old": ("09-24 10:00:00.000  1842  1856 W FocusRecord: test=C#old focus=stale\n", False),
            })
        self.assertEqual(result, 1)
        self.assertIn("Android gate: focus record for C#bad: " + record, stderr)
        self.assertIn("Android gate: focus record for C#broken: test=C#broken error=SecurityException: denied", stderr)
        self.assertNotIn("C#old", stderr)
        self.assertNotIn("FocusRecord", stdout)
        self.assertNotIn("focus=", stdout)

    def test_passing_device_run_prints_no_focus_record(self):
        passed = '<testsuite tests="1"><testcase classname="C" name="ok"/></testsuite>'
        with tempfile.TemporaryDirectory() as tmp:
            result, _, stderr = self.ui_run_with_logcats(Path(tmp), passed, {
                "ok": ("09-25 18:27:01.100  1842  1856 I TestRunner: started: ok(C)\n", True)})
        self.assertEqual(result, 0)
        self.assertNotIn("focus record", stderr)

    def test_live_tests_is_refused_outside_live_and_when_malformed(self):
        for argv in (["ui", "--tests", gate.LIVE_CLASS + "#a"], ["live", "--tests", "Other#a"]):
            with self.subTest(argv=argv), patch("sys.argv", ["android-test-gate.py", *argv]), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                gate.main()

    def test_emulator_script_runs_live_tests_in_place_of_the_curated_list(self):
        script = (Path(__file__).parent / "e2e-emulator.sh").read_text()
        live = script[script.index('elif [ -n "${LIVE}" ]; then\n  # LIVE curates'):]
        branch = live[: live.index("\nelse\n")]
        self.assertTrue(branch.rstrip().endswith('if [ -n "${LIVE_TESTS:-}" ]; then TEST_TARGET="${LIVE_TESTS}"; fi'))

    def test_expected_class_is_enforced(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = self.report(Path(tmp), '<testsuite tests="1"><testcase classname="C" name="ok"/></testsuite>')
            with self.assertRaises(ValueError):
                gate.combine_reports([report], "LiveTest")
            _, passed, count = gate.combine_reports([report], "C")
            self.assertTrue(passed)
            self.assertEqual(count, 1)


# #1071: another gate run holding the host's device, as a separate process so the kernel lock is real.
HOLDER = """import fcntl, os, sys, time
fd = os.open(sys.argv[1], os.O_RDWR | os.O_CREAT)
fcntl.flock(fd, fcntl.LOCK_EX)
os.ftruncate(fd, 0)
os.write(fd, sys.argv[2].encode())
print("held", flush=True)
time.sleep(float(sys.argv[3]))
"""
RECORD = '{"mode": "live", "worktree": "/work/other-tree", "started": "2026-09-25T01:19:00Z", "pid": 1}'


# A waiter from this checkout: takes the device through device_hold and logs when it held it.
WAITER = """import importlib.util, sys, time
spec = importlib.util.spec_from_file_location("gate", sys.argv[1])
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
with gate.device_hold(sys.argv[2], 30):
    with open(sys.argv[3], "a") as log:
        log.write(f"{sys.argv[2]} start {time.time()}\\n")
    time.sleep(float(sys.argv[4]))
    with open(sys.argv[3], "a") as log:
        log.write(f"{sys.argv[2]} end {time.time()}\\n")
"""
# A waiter from a checkout made before the queue: it retries the lock once a second and never takes a ticket.
OLD_POLLER = """import fcntl, os, sys, time
fd = os.open(sys.argv[1], os.O_RDWR | os.O_CREAT)
while True:
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        break
    except BlockingIOError:
        time.sleep(1)
with open(sys.argv[2], "a") as log:
    log.write(f"old start {time.time()}\\n")
time.sleep(float(sys.argv[3]))
with open(sys.argv[2], "a") as log:
    log.write(f"old end {time.time()}\\n")
"""
# A waiter that joined the queue and is still waiting, or that died waiting once killed.
QUEUED = """import importlib.util, sys, time
from pathlib import Path
spec = importlib.util.spec_from_file_location("gate", sys.argv[1])
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
gate.take_ticket(Path(sys.argv[2]))
print("queued", flush=True)
time.sleep(30)
"""


class DeviceHoldTest(unittest.TestCase):
    def setUp(self):
        self.home = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.enterContext(patch.dict(os.environ, {"ANDROID_USER_HOME": str(self.home)}))
        self.build = self.enterContext(patch.object(gate, "build_apks", return_value=0))
        self.path = self.home / "avd" / "pyrycode-device-gate.lock"
        self.path.parent.mkdir(parents=True)

    def holder(self, seconds, record=RECORD):
        process = subprocess.Popen([sys.executable, "-c", HOLDER, str(self.path), record, str(seconds)],
                                   stdout=subprocess.PIPE, text=True)
        self.addCleanup(process.stdout.close)
        self.addCleanup(process.wait)
        self.addCleanup(process.kill)
        self.assertEqual(process.stdout.readline().strip(), "held")
        return process

    def held_elsewhere(self):
        fd = os.open(self.path, os.O_RDWR)
        try:
            gate.fcntl.flock(fd, gate.fcntl.LOCK_EX | gate.fcntl.LOCK_NB)
            return False
        except BlockingIOError:
            return True
        finally:
            os.close(fd)

    def test_hold_path_sits_beside_the_gradle_managed_avds(self):
        self.assertEqual(gate.device_hold_path(), self.path)

    def test_waiter_gives_up_naming_the_holder(self):
        self.holder(30)
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(gate.DeviceBusy) as busy:
            with gate.device_hold("ui", 0.3):
                self.fail("took a held device")
        self.assertIn("held by live from /work/other-tree since 2026-09-25T01:19:00Z", str(busy.exception))
        self.assertIn("waiting up to 0s", stderr.getvalue())

    def test_unreadable_record_names_an_unknown_holder(self):
        self.holder(30, record="")
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(gate.DeviceBusy) as busy:
            with gate.device_hold("ui", 0):
                pass
        self.assertIn("held by an unknown run", str(busy.exception))

    def test_waiter_takes_the_device_when_the_holder_finishes_and_says_how_long_it_waited(self):
        self.holder(0.5)
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), gate.device_hold("scripted", 30):
            self.assertTrue(self.held_elsewhere())
            record = json.loads(self.path.read_text())
        self.assertEqual((record["mode"], record["worktree"], record["pid"]), ("scripted", str(gate.ROOT), os.getpid()))
        self.assertRegex(stderr.getvalue(), r"device free after \d+s waiting")
        self.assertFalse(self.held_elsewhere())

    def test_a_killed_holder_never_leaves_the_device_busy(self):
        process = self.holder(30)
        process.kill()
        process.wait()
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), gate.device_hold("ui", 0):
            self.assertTrue(self.held_elsewhere())
        self.assertEqual(stderr.getvalue(), "")

    def test_an_exception_in_the_run_releases_the_hold(self):
        with self.assertRaises(KeyboardInterrupt), gate.device_hold("scripted-all", 0):
            raise KeyboardInterrupt
        self.assertFalse(self.held_elsewhere())

    # 2026-10-05: waiters are served in arrival order, not by whoever retries first after a release.
    def queue(self):
        return self.home / "avd" / "pyrycode-device-gate.queue"

    def tickets(self):
        return sorted(self.queue().glob("*.ticket"))

    def spawn(self, *args):
        process = subprocess.Popen([sys.executable, "-c", *args], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   text=True)
        self.addCleanup(process.stdout.close)
        self.addCleanup(process.wait)
        self.addCleanup(process.kill)
        return process

    def queued_elsewhere(self):
        process = self.spawn(QUEUED, str(Path(gate.__file__)), str(self.queue()))
        self.assertEqual(process.stdout.readline().strip(), "queued")
        return process

    def wait_for_tickets(self, count):
        deadline = time.monotonic() + 10
        while len(self.tickets()) < count:
            self.assertLess(time.monotonic(), deadline, f"never saw {count} tickets")
            time.sleep(0.02)

    def held_intervals(self, log):
        starts, intervals = {}, []
        for line in log.read_text().splitlines():
            name, event, at = line.split()
            if event == "start":
                starts[name] = float(at)
            else:
                intervals.append((starts.pop(name), float(at), name))
        return sorted(intervals)

    def test_waiters_take_the_device_in_the_order_they_arrived(self):
        holder = self.holder(1.5)
        log = self.home / "order.log"
        waiters = []
        for count, name in enumerate(("first", "second", "third"), start=1):
            waiters.append(self.spawn(WAITER, str(Path(gate.__file__)), name, str(log), "0.1"))
            self.wait_for_tickets(count)
        for waiter in waiters:
            self.assertEqual(waiter.wait(timeout=30), 0)
        holder.wait(timeout=30)
        self.assertEqual([name for _, _, name in self.held_intervals(log)], ["first", "second", "third"])
        self.assertEqual(self.tickets(), [])

    def test_a_newcomer_waits_behind_an_earlier_waiter_even_when_the_device_is_free(self):
        self.queued_elsewhere()
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(gate.DeviceBusy) as busy:
            with gate.device_hold("ui", 0.3):
                self.fail("went ahead of an earlier waiter")
        self.assertIn("1 earlier run queued ahead", str(busy.exception))
        self.assertIn("waiting up to 0s", stderr.getvalue())
        self.assertFalse(self.held_elsewhere())
        self.assertEqual(len(self.tickets()), 1)

    def test_a_waiter_that_died_never_blocks_the_queue(self):
        process = self.queued_elsewhere()
        process.kill()
        process.wait()
        self.assertEqual(len(self.tickets()), 1)
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), gate.device_hold("ui", 0):
            self.assertTrue(self.held_elsewhere())
            self.assertEqual(self.tickets(), [])
        self.assertEqual(stderr.getvalue(), "")

    def test_the_ticket_leaves_the_queue_whether_the_run_takes_the_device_or_gives_up(self):
        with gate.device_hold("ui", 0):
            self.assertEqual(self.tickets(), [])
        self.holder(30)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(gate.DeviceBusy):
            with gate.device_hold("ui", 0.3):
                pass
        self.assertEqual(self.tickets(), [])

    def test_tickets_keep_counting_past_a_lost_counter(self):
        first, second = (gate.take_ticket(self.queue()) for _ in range(2))
        (self.queue() / "counter").write_text("")
        third = gate.take_ticket(self.queue())
        self.assertLess(second[1].name, third[1].name)
        for fd, ticket in (first, second, third):
            gate.drop_ticket(fd, ticket)
        self.assertEqual(self.tickets(), [])

    def test_a_poller_from_an_older_checkout_and_queued_waiters_share_the_device_without_overlap(self):
        holder = self.holder(1.5)
        log = self.home / "mixed.log"
        old = self.spawn(OLD_POLLER, str(self.path), str(log), "0.3")
        new = [self.spawn(WAITER, str(Path(gate.__file__)), name, str(log), "0.3") for name in ("new1", "new2")]
        for process in (old, *new):
            self.assertEqual(process.wait(timeout=30), 0)
        holder.wait(timeout=30)
        intervals = self.held_intervals(log)
        self.assertEqual(sorted(name for _, _, name in intervals), ["new1", "new2", "old"])
        for (_, end, _), (start, _, _) in zip(intervals, intervals[1:]):
            self.assertLessEqual(end, start)
        self.assertEqual(self.tickets(), [])

    def run_main(self, argv, environment=None):
        seen = []

        self.envs = []

        def run(command, **kwargs):
            seen.append(self.held_elsewhere())
            self.assertEqual(kwargs["env"].get("E2E_APKS_BUILT"), "1")
            self.envs.append(kwargs["env"])
            return subprocess.CompletedProcess(command, 0)

        root = self.home / "tree"
        path = root / "app/src/androidTest/java/de/pyryco/mobile/data/StoreTest.kt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("@Test fun ok() {}")
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(gate, "ROOT", root), \
                patch.dict(os.environ, {"ANDROID_USER_HOME": str(self.home), **(environment or {})}, clear=True), \
                patch("sys.argv", ["android-test-gate.py", *argv]), \
                patch.object(gate, "changed_paths", return_value=["app/src/main/X.kt"]), \
                patch.object(gate, "claude_authenticated", return_value=True), \
                patch.object(gate.signal, "signal"), patch.object(gate.subprocess, "run", side_effect=run), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = gate.main()
        return result, seen, stdout.getvalue(), stderr.getvalue()

    def test_every_device_mode_drives_the_device_only_while_holding_it(self):
        for argv in (["ui"], ["scripted", "ping"], ["scripted-all"], ["live"]):
            with self.subTest(argv=argv):
                _, seen, _, _ = self.run_main(argv)
                self.assertTrue(seen)
                self.assertTrue(all(seen))
                self.assertFalse(self.held_elsewhere())

    def test_the_apks_are_built_before_the_device_is_taken(self):
        for argv in (["ui"], ["scripted", "ping"], ["scripted-all"], ["live"]):
            with self.subTest(argv=argv):
                held = []
                self.build.side_effect = lambda env, mode: held.append(
                    (mode, self.path.exists() and self.held_elsewhere())) or 0
                _, seen, _, _ = self.run_main(argv)
                self.assertEqual(held, [(argv[0], False)])
                self.assertTrue(seen)

    def test_animations_are_off_for_the_scripted_scenarios_but_not_the_live_run(self):
        for argv, expected in ((["scripted", "ping"], "1"), (["scripted-all"], "1"), (["live"], None)):
            with self.subTest(argv=argv):
                self.run_main(argv, {"E2E_DISABLE_ANIMATIONS": "stale"})
                self.assertTrue(self.envs)
                self.assertTrue(all(env.get("E2E_DISABLE_ANIMATIONS") == expected for env in self.envs))

    def test_a_failed_apk_build_never_takes_the_device(self):
        self.build.return_value = 3
        for argv in (["ui"], ["scripted-all"], ["live"]):
            with self.subTest(argv=argv):
                result, seen, stdout, stderr = self.run_main(argv)
                self.assertEqual(result, 1)
                self.assertEqual(seen, [])
                self.assertEqual(stdout, "")
                self.assertIn("APK build exited 3; the device was not taken", stderr)
                self.assertFalse(self.path.exists() and self.path.read_text())

    def test_the_apk_build_uses_each_modes_build_properties(self):
        # The e2e modes match scripts/e2e-emulator.sh's GRADLE_BUILD_ARGS, so its test task finds both APKs current.
        root = self.home / "tree"
        for mode, properties in (("ui", []), ("scripted", ["-PuseRelayRepository=true"]),
                                 ("scripted-all", ["-PuseRelayRepository=true"]), ("live", ["-PuseRelayRepository=true"])):
            with self.subTest(mode=mode):
                run = Mock(return_value=subprocess.CompletedProcess([], 4))
                with patch.object(gate, "ROOT", root), patch.object(gate.subprocess, "run", run):
                    self.assertEqual(REAL_BUILD_APKS({"K": "v"}, mode), 4)
                self.assertEqual(run.call_args.args[0], [str(root / "gradlew"), ":app:assembleDebug",
                                                         ":app:assembleDebugAndroidTest", *properties, "--console=plain"])
                self.assertEqual(run.call_args.kwargs["env"], {"K": "v"})
                self.assertEqual(run.call_args.kwargs["cwd"], root)

    def test_a_run_that_gives_up_starts_nothing_and_is_not_a_test_result(self):
        self.holder(30)
        for argv in (["ui"], ["scripted", "ping"], ["scripted-all"], ["live"]):
            with self.subTest(argv=argv):
                result, seen, stdout, stderr = self.run_main(argv, {"ANDROID_GATE_WAIT_SECONDS": "0"})
                self.assertEqual(result, gate.DEVICE_BUSY_EXIT)
                self.assertNotEqual(result, 0)
                self.assertEqual(seen, [])
                self.assertEqual(stdout, "")
                self.assertIn("device busy, not a test result", stderr)
                self.assertIn("held by live from /work/other-tree since 2026-09-25T01:19:00Z", stderr)

    def test_the_default_wait_fits_a_ui_run_inside_the_dispatchers_ten_minute_cap(self):
        # A device-only ui run took 1m39s-2m22s of Gradle time in the dispatcher's 2026-09 gate logs.
        self.assertLessEqual(gate.DEFAULT_DEVICE_WAIT_SECONDS + 3 * 60, 10 * 60)

    def test_a_malformed_wait_bound_is_refused(self):
        for value in ("soon", "-1", "nan", ""):
            with self.subTest(value=value), self.assertRaises(SystemExit):
                self.run_main(["ui"], {"ANDROID_GATE_WAIT_SECONDS": value})


if __name__ == "__main__":
    unittest.main()
