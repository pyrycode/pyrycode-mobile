import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


TEST_RUN_END = "|| TEST_STATUS=$?\nfi\n"


class HostPromptDaemonPrerequisiteTest(unittest.TestCase):
    def setUp(self):
        self.script = (Path(__file__).resolve().parent / "e2e-emulator.sh").read_text()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.old = self.commit("before handlers")
        self.required = self.commit("host prompt handlers")
        self.current = self.commit("later daemon")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.DEVNULL, text=True).strip()

    def commit(self, message):
        self.git("commit", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD")

    def run_guard(self, revision, live="1", tests="", source=None):
        start = self.script.index("require_host_prompt_daemon() {")
        function = self.script[start:self.script.index("\n# resolve_runner_from_config", start)]
        body = 'set -euo pipefail\ndie() { printf "%s\\n" "$*" >&2; exit 1; }\n' + function
        body += '\nrequire_host_prompt_daemon "$REQUIRED_REVISION"\n'
        return subprocess.run(["bash", "-c", body], capture_output=True, text=True,
                              env=dict(os.environ, LIVE=live, LIVE_TESTS=tests, DAEMON_REVISION=revision,
                                       PYRYCODE_SRC=str(self.repo) if source is None else source,
                                       REQUIRED_REVISION=self.required))

    def test_full_live_suite_rejects_daemon_before_handlers(self):
        result = self.run_guard(self.old)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("pyrycode#2768", result.stderr)
        self.assertIn("rebuild", result.stderr)

    def test_handlers_and_later_daemon_are_accepted(self):
        for revision in (self.required, self.current):
            with self.subTest(revision=revision):
                self.assertEqual(0, self.run_guard(revision).returncode)

    def test_selected_host_prompt_method_requires_handlers(self):
        method = "de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_hostSystemPrompt_editsResetsAndCancels"
        self.assertNotEqual(0, self.run_guard(self.old, tests=method).returncode)

    def test_unrelated_subset_and_scripted_run_need_no_host_prompt_handlers(self):
        self.assertEqual(0, self.run_guard("", tests="de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_pingPrompt_streamsPingReplyIntoThread").returncode)
        self.assertEqual(0, self.run_guard("", live="").returncode)

    def test_missing_or_unknown_revision_and_source_fail_explicitly(self):
        for revision, source in (("", None), ("a" * 40, None), (self.current, ""), (self.current, str(self.repo / "missing"))):
            with self.subTest(revision=revision, source=source):
                result = self.run_guard(revision, source=source)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("pyrycode#2768", result.stderr)


def device_test_run(script):
    """The device test run, from its argument list to the end of the Gradle-or-installed choice."""
    start = script.index("GRADLE_TEST_ARGS=(")
    return script[start:script.index(TEST_RUN_END, start) + len(TEST_RUN_END)]


class EmulatorGradleTest(unittest.TestCase):
    def test_invocation_selects_real_without_editing_source(self):
        root = Path(__file__).resolve().parent.parent
        script = (root / "scripts/e2e-emulator.sh").read_text()
        invocation = device_test_run(script)
        tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).split(b"\0")
        before = {path: (root / os.fsdecode(path)).read_bytes() for path in tracked if path}
        with tempfile.TemporaryDirectory() as tmp:
            stub = Path(tmp) / "gradlew"
            stub.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
            stub.chmod(0o700)
            for rerun in ("", "1"):
                with self.subTest(rerun=rerun):
                    env = dict(os.environ, GRADLEW=str(stub), REPO_ROOT=str(root),
                               DEVICE="pixel2Api33Atd", TEST_TARGET="fixture.Class#method",
                               PHONE_RELAY_URL="ws://10.0.2.2:8888", TOKEN="stub-token",
                               SERVER_ID="stub-server", SERVER_STATIC_PUBKEY="stub-key",
                               PYRY_FORCE_TEST_RUN=rerun)
                    result = subprocess.run(["bash", "-c", "set -euo pipefail\n" + invocation],
                                            env=env, capture_output=True, text=True, check=True)
                    expected = ["-p", str(root), "pixel2Api33AtdDebugAndroidTest", "-PuseRelayRepository=true"]
                    if rerun:
                        expected.append("--rerun")
                    expected += [
                        "-Pandroid.testInstrumentationRunnerArguments.class=fixture.Class#method",
                        "-Pandroid.testInstrumentationRunnerArguments.relayUrl=ws://10.0.2.2:8888",
                        "-Pandroid.testInstrumentationRunnerArguments.token=stub-token",
                        "-Pandroid.testInstrumentationRunnerArguments.serverId=stub-server",
                        "-Pandroid.testInstrumentationRunnerArguments.serverStaticPublicKey=stub-key",
                        "--console=plain",
                    ]
                    self.assertEqual(expected, result.stdout.splitlines())
        self.assertEqual(before, {path: (root / os.fsdecode(path)).read_bytes() for path in before})

    def test_second_host_arguments_follow_the_rerun_flag(self):
        # #847: only a run that minted a second host passes its five arguments, after --rerun.
        root = Path(__file__).resolve().parent.parent
        script = (root / "scripts/e2e-emulator.sh").read_text()
        invocation = device_test_run(script)
        with tempfile.TemporaryDirectory() as tmp:
            stub = Path(tmp) / "gradlew"
            stub.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
            stub.chmod(0o700)
            env = dict(os.environ, GRADLEW=str(stub), REPO_ROOT=str(root), DEVICE="pixel2Api33Atd",
                       TEST_TARGET="fixture.Class#method", PHONE_RELAY_URL="ws://10.0.2.2:8888",
                       TOKEN="stub-token", SERVER_ID="stub-server", SERVER_STATIC_PUBKEY="stub-key",
                       PYRY_FORCE_TEST_RUN="1", SERVER_ID_B="srv-b", PAIR_CODE_B="code-b",
                       COLLISION_ID="conv-1", COLLISION_NAME_A="e2e847-a-1", COLLISION_NAME_B="e2e847-b-1")
            result = subprocess.run(["bash", "-c", "set -euo pipefail\n" + invocation],
                                    env=env, capture_output=True, text=True, check=True)
        lines = result.stdout.splitlines()
        prefix = "-Pandroid.testInstrumentationRunnerArguments."
        self.assertEqual(["--rerun", prefix + "serverIdB=srv-b", prefix + "pairCodeB=code-b",
                          prefix + "collisionConversationId=conv-1", prefix + "collisionNameA=e2e847-a-1",
                          prefix + "collisionNameB=e2e847-b-1", prefix + "class=fixture.Class#method"],
                         lines[lines.index("--rerun"):lines.index("--rerun") + 7])

    def test_animations_argument_is_passed_only_when_the_gate_asks(self):
        root = Path(__file__).resolve().parent.parent
        script = (root / "scripts/e2e-emulator.sh").read_text()
        invocation = device_test_run(script)
        with tempfile.TemporaryDirectory() as tmp:
            stub = Path(tmp) / "gradlew"
            stub.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
            stub.chmod(0o700)
            base = dict(os.environ, GRADLEW=str(stub), REPO_ROOT=str(root), DEVICE="pixel2Api33Atd",
                        TEST_TARGET="fixture.Class#method", PHONE_RELAY_URL="ws://10.0.2.2:8888",
                        TOKEN="stub-token", SERVER_ID="stub-server", SERVER_STATIC_PUBKEY="stub-key")
            arg = "-Pandroid.testInstrumentationRunnerArguments.disableAnimations=true"
            for value, expected in (("", False), ("1", True)):
                with self.subTest(value=value):
                    result = subprocess.run(["bash", "-c", "set -euo pipefail\n" + invocation],
                                            env=dict(base, E2E_DISABLE_ANIMATIONS=value),
                                            capture_output=True, text=True, check=True)
                    self.assertEqual(expected, arg in result.stdout.splitlines())

    def test_peer_token_is_passed_only_when_minted(self):
        # #848: the second-client peer's token rides its own block, after the two-host arguments.
        root = Path(__file__).resolve().parent.parent
        script = (root / "scripts/e2e-emulator.sh").read_text()
        invocation = device_test_run(script)
        with tempfile.TemporaryDirectory() as tmp:
            stub = Path(tmp) / "gradlew"
            stub.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
            stub.chmod(0o700)
            base = dict(os.environ, GRADLEW=str(stub), REPO_ROOT=str(root), DEVICE="pixel2Api33Atd",
                        TEST_TARGET="fixture.Class#method", PHONE_RELAY_URL="ws://10.0.2.2:8888",
                        TOKEN="stub-token", SERVER_ID="stub-server", SERVER_STATIC_PUBKEY="stub-key")
            arg = "-Pandroid.testInstrumentationRunnerArguments.peerToken=peer-token"
            for peer, expected in (("", False), ("peer-token", True)):
                with self.subTest(peer=peer):
                    env = dict(base, PEER_TOKEN=peer)
                    result = subprocess.run(["bash", "-c", "set -euo pipefail\n" + invocation],
                                            env=env, capture_output=True, text=True, check=True)
                    self.assertEqual(expected, arg in result.stdout.splitlines())

    def test_bypass_arguments_carry_the_unmet_code_or_the_pairing(self):
        # #687: the operator-bypass daemon passes nothing, only its unmet prerequisite, or its six arguments.
        root = Path(__file__).resolve().parent.parent
        script = (root / "scripts/e2e-emulator.sh").read_text()
        invocation = device_test_run(script)
        prefix = "-Pandroid.testInstrumentationRunnerArguments."
        pairing = {"SERVER_ID_BYPASS": "srv-byp", "PAIR_CODE_BYPASS": "code-byp",
                   "BYPASS_PEER_TOKEN": "peer-byp", "BYPASS_PEER_SERVER_STATIC_PUBKEY": "key-byp",
                   "BYPASS_TOKEN_FILE": "/tmp/pyry-e2e-byp.x/outside/e2e687-1.txt", "BYPASS_WITNESS": "abc123"}
        cases = (
            ({}, []),
            ({"BYPASS_UNMET": "no_credential"}, [prefix + "bypassUnmet=no_credential"]),
            (pairing, [prefix + "bypassServerId=srv-byp", prefix + "bypassPairCode=code-byp",
                       prefix + "bypassPeerToken=peer-byp", prefix + "bypassServerStaticPublicKey=key-byp",
                       prefix + "bypassTokenFile=/tmp/pyry-e2e-byp.x/outside/e2e687-1.txt",
                       prefix + "bypassToken=abc123"]),
        )
        with tempfile.TemporaryDirectory() as tmp:
            stub = Path(tmp) / "gradlew"
            stub.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
            stub.chmod(0o700)
            base = {k: v for k, v in os.environ.items() if not k.startswith("BYPASS_") and "_BYPASS" not in k}
            base.update(GRADLEW=str(stub), REPO_ROOT=str(root), DEVICE="pixel2Api33Atd",
                        TEST_TARGET="fixture.Class#method", PHONE_RELAY_URL="ws://10.0.2.2:8888",
                        TOKEN="stub-token", SERVER_ID="stub-server", SERVER_STATIC_PUBKEY="stub-key")
            for extra, expected in cases:
                with self.subTest(case=sorted(extra)):
                    result = subprocess.run(["bash", "-c", "set -euo pipefail\n" + invocation],
                                            env=dict(base, **extra), capture_output=True, text=True, check=True)
                    passed = [line for line in result.stdout.splitlines() if line.startswith(prefix + "bypass")]
                    self.assertEqual(expected, passed)


class EmulatorBuildBeforeMintTest(unittest.TestCase):
    """#993: the APKs are built before any pairing code is minted, and an expired code is named on failure.
    #1132: a failed run also names each unplanned relay-link drop."""

    def setUp(self):
        self.root = Path(__file__).resolve().parent.parent
        self.script = (self.root / "scripts/e2e-emulator.sh").read_text()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def stub(self, status=0):
        stub = Path(self.tmp.name) / f"gradlew-{status}"
        stub.write_text(f'#!/bin/bash\nprintf "%s\\n" "$@"\nexit {status}\n')
        stub.chmod(0o700)
        return stub

    def block(self, start_marker, end_marker):
        start = self.script.index(start_marker)
        return self.script[start:self.script.index(end_marker, start) + len(end_marker)]

    def run_block(self, body, **env):
        base = dict(os.environ, REPO_ROOT=str(self.root), DEVICE="pixel2Api33Atd",
                    TEST_TARGET="fixture.Class#method", PHONE_RELAY_URL="ws://10.0.2.2:8888",
                    TOKEN="stub-token", SERVER_ID="stub-server", SERVER_STATIC_PUBKEY="stub-key",
                    PYRY_FORCE_TEST_RUN="")
        return subprocess.run(["bash", "-c", "set -euo pipefail\n" + body], env=dict(base, **env),
                              capture_output=True, text=True)

    def build_block(self, **env):
        stubs = 'log() { :; }\ndie() { echo "died: $*" >&2; exit 9; }\n'
        return self.run_block(stubs + self.block("GRADLE_BUILD_ARGS=(", "\nfi\n"),
                              **{"GRADLEW": str(self.stub()), "E2E_APKS_BUILT": "", **env})

    def test_build_uses_the_test_invocations_build_properties(self):
        build = self.build_block()
        self.assertEqual(0, build.returncode, build.stderr)
        self.assertEqual(["-p", str(self.root), "assembleDebug", "assembleDebugAndroidTest",
                          "-PuseRelayRepository=true", "--console=plain"], build.stdout.splitlines())
        test = self.run_block(device_test_run(self.script), GRADLEW=str(self.stub()))
        build_properties = [arg for arg in build.stdout.splitlines() if arg.startswith("-P")]
        test_properties = [arg for arg in test.stdout.splitlines()
                           if arg.startswith("-P") and not arg.startswith("-Pandroid.testInstrumentationRunnerArguments.")]
        self.assertEqual(test_properties, build_properties)

    def test_build_is_skipped_when_the_gate_built_before_the_device_hold(self):
        built = self.build_block(E2E_APKS_BUILT="1")
        self.assertEqual(0, built.returncode, built.stderr)
        self.assertEqual("", built.stdout)

    def test_every_pairing_is_minted_after_the_build(self):
        build = self.script.index('"${GRADLEW}" -p "${REPO_ROOT}" assembleDebug')
        mints = [i for i in range(len(self.script)) if self.script.startswith(" pair -pyry-name=", i)]
        # Host A (isolated and real HOME), host B, the host A peer, and two each on the bypass and answer daemons.
        self.assertEqual(8, len(mints))
        self.assertLess(build, min(mints))
        for name in ("mint_bypass_pairing", "mint_answer_pairing"):
            with self.subTest(name=name):
                calls = [i for i in range(len(self.script)) if self.script.startswith(name, i)
                         and not self.script.startswith(name + "() {", i)]
                self.assertTrue(calls)
                self.assertLess(build, min(calls))

    def run_test_task(self, status, logs):
        function = (self.block("report_stale_pairing_codes() {", "\n}\n")
                    + self.block("report_relay_link_drops() {", "\n}\n"))
        invocation = self.block("GRADLE_TEST_ARGS=(", 'exit "${TEST_STATUS}"\nfi\n')
        paths = {}
        for variable, name in (("DAEMON_LOG", "daemon.log"), ("DAEMON_B_LOG", "daemon-b.log"),
                               ("DAEMON_BYPASS_LOG", "daemon-bypass.log"), ("DAEMON_ANSWER_LOG", "daemon-answer.log")):
            path = Path(self.tmp.name) / name
            if name in logs:
                path.write_text(logs[name])
            paths[variable] = str(path)
        return self.run_block(function + invocation, GRADLEW=str(self.stub(status)), PYRY_NAME="e2e-x",
                              PYRY_NAME_B="e2e-x-b", PYRY_NAME_BYPASS="e2e-x-bypass",
                              PYRY_NAME_ANSWER="e2e-x-answer", PAIR_CODE_B="stub-code-b", **paths)

    def test_failure_names_only_the_daemons_whose_codes_expired(self):
        expired = "level=WARN msg=v2.handshake.reject.redemption_window_elapsed token=log-secret close=4401\n"
        result = self.run_test_task(3, {"daemon.log": "level=INFO msg=up\n", "daemon-b.log": expired,
                                        "daemon-bypass.log": "level=INFO msg=up\n", "daemon-answer.log": expired})
        self.assertEqual(3, result.returncode)
        stale = [line for line in result.stderr.splitlines() if "pairing_codes_stale" in line]
        self.assertEqual(1, len(stale), result.stderr)
        self.assertIn("e2e-x-b (daemon-b.log)", stale[0])
        self.assertIn("e2e-x-answer (daemon-answer.log)", stale[0])
        self.assertNotIn("e2e-x (daemon.log)", stale[0])
        self.assertNotIn("e2e-x-bypass", stale[0])
        for secret in ("stub-code-b", "log-secret", "4401"):
            self.assertNotIn(secret, result.stderr)
        self.assertNotIn("stub-token", result.stderr)
        self.assertNotIn("stub-key", result.stderr)

    def test_failure_without_expired_codes_is_unchanged(self):
        result = self.run_test_task(3, {"daemon.log": "level=INFO msg=up\n", "daemon-b.log": "level=INFO msg=up\n"})
        self.assertEqual(3, result.returncode)
        self.assertEqual("", result.stderr)

    def test_success_does_not_scan(self):
        expired = "msg=v2.handshake.reject.redemption_window_elapsed\n"
        result = self.run_test_task(0, {"daemon.log": expired})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)

    # 2026-10-05: one retry of the failed tests with fresh codes after an expired pairing code.
    REPORT = """<?xml version="1.0" encoding="UTF-8"?>
<testsuites><testsuite name="device" tests="{tests}" failures="{failures}" errors="0" skipped="0">{cases}</testsuite></testsuites>
"""
    PASS = '<testcase classname="fixture.Class" name="{}" time="1"/>'
    FAIL = '<testcase classname="fixture.Class" name="{}" time="1"><failure>pairing rejected</failure></testcase>'
    # A Gradle stand-in: each call logs its arguments, writes that call's report and appends that call's daemon log line.
    GRADLE = """#!/bin/bash
call=$(( $(cat "$STUB_DIR/calls" 2>/dev/null || echo 0) + 1 ))
echo "$call" > "$STUB_DIR/calls"
printf '%s\\n' "$@" > "$STUB_DIR/args-$call"
results="$REPO_ROOT/app/build/outputs/androidTest-results/managedDevice/debug/$DEVICE"
mkdir -p "$results"
rm -f "$results"/TEST-*.xml
[ ! -f "$STUB_DIR/report-$call" ] || cp "$STUB_DIR/report-$call" "$results/TEST-$DEVICE-_app-.xml"
[ ! -f "$STUB_DIR/log-$call" ] || cat "$STUB_DIR/log-$call" >> "$DAEMON_BYPASS_LOG"
exit "$(cat "$STUB_DIR/status-$call")"
"""
    EXPIRED = "level=WARN msg=v2.handshake.reject.redemption_window_elapsed token=log-secret close=4401\n"
    FRESH_MINT = ('mint_pairings() { echo minted >> "$STUB_DIR/mints"; TOKEN=fresh-token; PAIR_CODE_B=fresh-code-b; '
                  'PEER_TOKEN=fresh-peer; PAIR_CODE_BYPASS=fresh-code-bypass; BYPASS_PEER_TOKEN=fresh-bypass-peer; '
                  'PAIR_CODE_ANSWER=fresh-code-answer; ANSWER_PEER_TOKEN=fresh-answer-peer; }\n')

    def report(self, *cases):
        return self.REPORT.format(tests=len(cases), failures=sum("<failure>" in case for case in cases),
                                  cases="".join(cases))

    def run_with_retry(self, calls, first_log=EXPIRED):
        """[calls]: per Gradle call, (status, report or None, daemon log line or None)."""
        stub_dir = Path(self.tmp.name) / "stub"
        repo = Path(self.tmp.name) / "repo"
        (repo / "scripts").mkdir(parents=True)
        (repo / "scripts/e2e-rerun-report.py").write_text((self.root / "scripts/e2e-rerun-report.py").read_text())
        stub_dir.mkdir()
        for number, (status, report, log) in enumerate(calls, start=1):
            (stub_dir / f"status-{number}").write_text(str(status))
            if report is not None:
                (stub_dir / f"report-{number}").write_text(report)
            if log is not None:
                (stub_dir / f"log-{number}").write_text(log)
        gradle = Path(self.tmp.name) / "gradlew-retry"
        gradle.write_text(self.GRADLE)
        gradle.chmod(0o700)
        bypass_log = Path(self.tmp.name) / "daemon-bypass.log"
        bypass_log.write_text("level=INFO msg=up\n")
        functions = (self.block("report_stale_pairing_codes() {", "\n}\n")
                     + self.block("retry_with_fresh_codes() {", "\n}\n")
                     + self.block("report_relay_link_drops() {", "\n}\n"))
        invocation = self.block("GRADLE_TEST_ARGS=(", 'exit "${TEST_STATUS}"\nfi\n')
        body = "log() { echo \"log: $*\" >&2; }\n" + self.FRESH_MINT + functions + invocation
        result = self.run_block(body, GRADLEW=str(gradle), REPO_ROOT=str(repo), STUB_DIR=str(stub_dir),
                                WORK_DIR=str(Path(self.tmp.name) / "work"), PYRY_NAME="e2e-x", PYRY_NAME_B="e2e-x-b",
                                PYRY_NAME_BYPASS="e2e-x-bypass", PYRY_NAME_ANSWER="e2e-x-answer",
                                DAEMON_LOG=str(Path(self.tmp.name) / "daemon.log"),
                                DAEMON_B_LOG=str(Path(self.tmp.name) / "daemon-b.log"),
                                DAEMON_BYPASS_LOG=str(bypass_log),
                                DAEMON_ANSWER_LOG=str(Path(self.tmp.name) / "daemon-answer.log"),
                                PAIR_CODE_B="stub-code-b", SERVER_ID_B="stub-server-b", COLLISION_ID="c",
                                COLLISION_NAME_A="a", COLLISION_NAME_B="b", PEER_TOKEN="stub-peer",
                                TEST_TARGET="fixture.Class", PYRY_FORCE_TEST_RUN="1")
        results = repo / "app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd"
        args = [(stub_dir / f"args-{n}").read_text().splitlines()
                for n in range(1, int((stub_dir / "calls").read_text()) + 1)]
        mints = (stub_dir / "mints").read_text().count("minted") if (stub_dir / "mints").exists() else 0
        return result, args, mints, sorted(results.glob("TEST-*.xml"))

    def combined(self, reports):
        spec = importlib.util.spec_from_file_location("gate", self.root / "scripts/android-test-gate.py")
        gate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(gate)
        return gate.combine_reports(reports, "fixture.Class")

    def test_an_expired_code_reruns_only_the_failed_tests_with_fresh_codes_once(self):
        first = self.report(self.PASS.format("ok"), self.FAIL.format("pairs"))
        result, args, mints, reports = self.run_with_retry(
            [(3, first, self.EXPIRED), (0, self.report(self.PASS.format("pairs")), None)])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(args))
        self.assertEqual(1, mints)
        retry = args[1]
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.class=fixture.Class#pairs", retry)
        for name, value in (("token", "fresh-token"), ("pairCodeB", "fresh-code-b"), ("peerToken", "fresh-peer")):
            self.assertIn(f"-Pandroid.testInstrumentationRunnerArguments.{name}={value}", retry)
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.serverIdB=stub-server-b", retry)
        self.assertEqual(1, retry.count("--rerun"))
        self.assertEqual(len(args[0]), len(retry))
        # The gate reads one report holding the whole run, with the rerun's result for the retried test.
        self.assertEqual(1, len(reports))
        _, passed, executed = self.combined(reports)
        self.assertTrue(passed)
        self.assertEqual(2, executed)
        self.assertEqual(1, sum("pairing_codes_stale" in line for line in result.stderr.splitlines()))
        for secret in ("fresh-", "stub-code-b", "stub-peer", "log-secret"):
            self.assertNotIn(secret, result.stderr)

    def test_a_retry_that_fails_again_fails_the_run_and_names_only_the_new_rejection(self):
        first = self.report(self.PASS.format("ok"), self.FAIL.format("pairs"))
        result, args, mints, reports = self.run_with_retry(
            [(3, first, self.EXPIRED), (4, self.report(self.FAIL.format("pairs")), self.EXPIRED)])
        self.assertEqual(4, result.returncode)
        self.assertEqual((2, 1), (len(args), mints))
        _, passed, executed = self.combined(reports)
        self.assertFalse(passed)
        self.assertEqual(2, executed)
        stale = [line for line in result.stderr.splitlines() if "pairing_codes_stale" in line]
        self.assertEqual(2, len(stale), result.stderr)
        self.assertTrue(all("e2e-x-bypass (daemon-bypass.log)" in line for line in stale))

    def test_a_retry_that_produces_no_report_keeps_the_first_runs_failures(self):
        first = self.report(self.PASS.format("ok"), self.FAIL.format("pairs"))
        result, args, _, reports = self.run_with_retry([(3, first, self.EXPIRED), (5, None, None)])
        self.assertEqual(5, result.returncode)
        _, passed, executed = self.combined(reports)
        self.assertFalse(passed)
        self.assertEqual(2, executed)

    def test_no_retry_without_a_failed_test_in_the_report(self):
        result, args, mints, _ = self.run_with_retry([(3, None, self.EXPIRED)])
        self.assertEqual(3, result.returncode)
        self.assertEqual((1, 0), (len(args), mints))

    def test_no_retry_without_an_expired_code(self):
        first = self.report(self.FAIL.format("pairs"))
        result, args, mints, _ = self.run_with_retry([(3, first, None)])
        self.assertEqual(3, result.returncode)
        self.assertEqual((1, 0), (len(args), mints))
        self.assertEqual("", result.stderr)

    # Relay-link ends as pyrycode's WSSClient logs them; `context canceled` is the teardown's own kill.
    CANCELED = ('time=2026-09-25T21:40:00.000+03:00 level=INFO msg="transport: disconnected" '
                'uptime=5m0s err="context canceled"\n')
    NO_ADDRESS = ('time=2026-09-25T21:46:00.712+03:00 level=INFO msg="transport: disconnected" uptime=1m2s '
                  'err="read tcp 192.168.50.123:50123->213.188.218.250:443: read: can\'t assign requested address"\n')
    PONG = ('time={} level=INFO msg="transport: disconnected" uptime=3m0s '
            'err="transport: pong timeout token=log-secret: context deadline exceeded"\n')

    def drops(self, result):
        return [line for line in result.stderr.splitlines() if "relay_link_dropped" in line]

    def test_failure_names_each_daemon_with_its_unplanned_drop_times(self):
        pong = (self.PONG.format("2026-09-25T21:46:03.100+03:00")
                + "time=2026-09-25T21:46:03.150+03:00 level=INFO msg=\"transport: connected\" attempt=1\n"
                + self.PONG.format("2026-09-25T21:52:10.000+03:00"))
        result = self.run_test_task(3, {"daemon.log": self.CANCELED + self.NO_ADDRESS, "daemon-b.log": pong,
                                        "daemon-bypass.log": "level=INFO msg=up\n", "daemon-answer.log": self.CANCELED})
        self.assertEqual(3, result.returncode)
        drops = self.drops(result)
        self.assertEqual(2, len(drops), result.stderr)
        self.assertIn("e2e-x (daemon.log)", drops[0])
        self.assertIn("2026-09-25T21:46:00.712+03:00", drops[0])
        self.assertIn("e2e-x-b (daemon-b.log)", drops[1])
        self.assertIn("2026-09-25T21:46:03.100+03:00, 2026-09-25T21:52:10.000+03:00", drops[1])
        for absent in ("21:40:00", "21:46:03.150", "e2e-x-bypass", "e2e-x-answer", "192.168", "213.188",
                       "assign", "pong", "log-secret", "deadline", "uptime", "err=", "stub-token", "stub-key"):
            self.assertNotIn(absent, result.stderr)

    def test_failure_with_only_the_teardowns_own_drops_prints_nothing(self):
        result = self.run_test_task(3, {"daemon.log": self.CANCELED, "daemon-answer.log": self.CANCELED})
        self.assertEqual(3, result.returncode)
        self.assertEqual("", result.stderr)

    def test_success_does_not_report_drops(self):
        result = self.run_test_task(0, {"daemon.log": self.NO_ADDRESS})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)


class InstalledRunTest(unittest.TestCase):
    """scripted-all's emulator already holds both APKs: the run calls the instrumentation itself, never Gradle."""

    FAKE_ADB = """#!/bin/bash
printf '%s\\n' "$*" >> "$ADB_LOG"
case "$1" in
  shell) cat "$RAW_FIXTURE" ;;
  logcat) [ "$2" = "-d" ] && echo "I TestRunner: started: ping" ;;
esac
"""
    PASSED = ("INSTRUMENTATION_STATUS: class=fixture.Class\nINSTRUMENTATION_STATUS: test=method\n"
              "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=fixture.Class\n"
              "INSTRUMENTATION_STATUS: test=method\nINSTRUMENTATION_STATUS_CODE: 0\n"
              "INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")

    def setUp(self):
        self.root = Path(__file__).resolve().parent.parent
        self.script = (self.root / "scripts/e2e-emulator.sh").read_text()
        self.tmp = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.repo = self.tmp / "repo"
        (self.repo / "scripts").mkdir(parents=True)
        (self.repo / "scripts/instrument-report.py").write_text((self.root / "scripts/instrument-report.py").read_text())
        adb = self.tmp / "sdk/platform-tools/adb"
        adb.parent.mkdir(parents=True)
        adb.write_text(self.FAKE_ADB)
        adb.chmod(0o700)
        self.gradle = self.tmp / "gradlew"
        self.gradle.write_text('#!/bin/bash\necho "gradle $*" >> "$ADB_LOG"\n')
        self.gradle.chmod(0o700)

    def run_scenario(self, raw, token="stub-token"):
        start = self.script.index("device_quote() {")
        functions = self.script[start:self.script.index("\n}\n", self.script.index("run_installed_instrumentation() {")) + 3]
        (self.tmp / "raw.txt").write_text(raw)
        body = "log() { :; }\n" + functions + device_test_run(self.script) + 'exit "${TEST_STATUS}"\n'
        env = dict(os.environ, ANDROID_HOME=str(self.tmp / "sdk"), REPO_ROOT=str(self.repo), GRADLEW=str(self.gradle),
                   E2E_INSTALLED="1", ADB_LOG=str(self.tmp / "adb.log"), RAW_FIXTURE=str(self.tmp / "raw.txt"),
                   DEVICE="connected", TEST_TARGET="fixture.Class#method", PHONE_RELAY_URL="ws://10.0.2.2:8888",
                   TOKEN=token, SERVER_ID="stub-server", SERVER_STATIC_PUBKEY="stub-key", PYRY_FORCE_TEST_RUN="1",
                   E2E_DISABLE_ANIMATIONS="1")
        result = subprocess.run(["bash", "-c", "set -euo pipefail\n" + body], env=env, capture_output=True, text=True)
        return result, (self.tmp / "adb.log").read_text().splitlines()

    def test_the_runner_arguments_reach_the_instrumentation_and_gradle_never_runs(self):
        result, calls = self.run_scenario(self.PASSED, token="it's")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(calls[0], "logcat -c")
        self.assertEqual(calls[1], "shell am instrument -w -r -e listener de.pyryco.mobile.e2e.FocusRecordListener "
                                   "-e disableAnimations 'true' -e class 'fixture.Class#method' "
                                   "-e relayUrl 'ws://10.0.2.2:8888' -e token 'it'\\''s' -e serverId 'stub-server' "
                                   "-e serverStaticPublicKey 'stub-key' "
                                   "de.pyryco.mobile.test/de.pyryco.mobile.e2e.E2eInstrumentationRunner")
        self.assertEqual(calls[2], "logcat -d")
        self.assertFalse(any(call.startswith("gradle") for call in calls))
        results = self.repo / "app/build/outputs/androidTest-results/connected/debug"
        self.assertIn('name="method"', (results / "TEST-installed.xml").read_text())
        self.assertIn("started: ping", (results / "logcat-fixture.Class-method.txt").read_text())

    def test_a_failed_or_crashed_instrumentation_fails_the_scenario(self):
        for raw in (self.PASSED.replace("STATUS_CODE: 0", "STATUS_CODE: -2"),
                    self.PASSED.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"), ""):
            with self.subTest(raw=raw[-40:]):
                result, _ = self.run_scenario(raw)
                self.assertEqual(1, result.returncode)

    def test_the_runner_and_listener_match_the_build(self):
        build = (self.root / "app/build.gradle.kts").read_text()
        self.assertIn('applicationId = "de.pyryco.mobile"', build)
        self.assertNotIn("testApplicationId", build)
        self.assertIn('testInstrumentationRunner = "de.pyryco.mobile.e2e.E2eInstrumentationRunner"', build)
        self.assertIn('testInstrumentationRunnerArguments["listener"] = "de.pyryco.mobile.e2e.FocusRecordListener"',
                      build)
        self.assertEqual(1, build.count("testInstrumentationRunnerArguments["))


if __name__ == "__main__":
    unittest.main()
