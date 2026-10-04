import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class EmulatorGradleTest(unittest.TestCase):
    def test_invocation_selects_real_without_editing_source(self):
        root = Path(__file__).resolve().parent.parent
        script = (root / "scripts/e2e-emulator.sh").read_text()
        start = script.index("GRADLE_TEST_ARGS=(")
        end = script.index("  --console=plain", start) + len("  --console=plain")
        invocation = script[start:end]
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
        start = script.index("GRADLE_TEST_ARGS=(")
        invocation = script[start:script.index("  --console=plain", start) + len("  --console=plain")]
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

    def test_peer_token_is_passed_only_when_minted(self):
        # #848: the second-client peer's token rides its own block, after the two-host arguments.
        root = Path(__file__).resolve().parent.parent
        script = (root / "scripts/e2e-emulator.sh").read_text()
        start = script.index("GRADLE_TEST_ARGS=(")
        invocation = script[start:script.index("  --console=plain", start) + len("  --console=plain")]
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
        start = script.index("GRADLE_TEST_ARGS=(")
        invocation = script[start:script.index("  --console=plain", start) + len("  --console=plain")]
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
        test = self.run_block(self.block("GRADLE_TEST_ARGS=(", "  --console=plain"), GRADLEW=str(self.stub()))
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


if __name__ == "__main__":
    unittest.main()
