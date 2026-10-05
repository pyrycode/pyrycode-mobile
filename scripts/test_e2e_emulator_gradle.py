import os
import base64
import json
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from pathlib import Path
import subprocess
import tempfile
import unittest


TEST_RUN_END = "|| TEST_STATUS=$?\nfi\n"


def device_test_run(script):
    """The device test run, from its argument list to the end of the Gradle-or-installed choice."""
    start = script.index("GRADLE_TEST_ARGS=(")
    return script[start:script.index(TEST_RUN_END, start) + len(TEST_RUN_END)]


class BypassPairingLifecycleTest(unittest.TestCase):
    """The actual shell lifecycle must survive preceding scenarios longer than redemption."""

    def setUp(self):
        self.root = Path(__file__).resolve().parent.parent
        self.script = (self.root / "scripts/e2e-emulator.sh").read_text()
        self.tmp = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.clock = self.tmp / "clock"
        self.clock.write_text("0")
        self.calls = self.tmp / "calls"
        self.cli = self.tmp / "pyry"
        self.cli.write_text("""#!/usr/bin/env python3
import base64, json, os, sys
from pathlib import Path
now = int(Path(os.environ['FIXTURE_CLOCK']).read_text())
with open(os.environ['FIXTURE_CALLS'], 'a') as log:
    log.write(json.dumps({'at': now, 'privileged': '--allow-remote-permissions' in sys.argv,
                          'home': os.environ['HOME'], 'args': sys.argv[1:]}) + '\\n')
if os.environ.get('FIXTURE_FAILURE'):
    print('secret-token secret-key secret-code', file=sys.stderr)
    sys.exit(1)
payload = {'server': 'fixture-host', 'token': 'secret-token', 'server_static_pubkey': 'secret-key',
           'relay': 'ws://127.0.0.1:8888/v1/server', 'mintedAt': now}
if '--allow-remote-permissions' in sys.argv and os.environ.get('FIXTURE_MISMATCH'):
    payload['server'] = 'wrong-host'
print(base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip('='))
""")
        self.cli.chmod(0o700)

    def function(self, name):
        start = self.script.index(name + "() {")
        return self.script[start:self.script.index("\n}\n", start) + 3]

    def start_fixture(self, **extra):
        work = self.tmp / ("run-" + str(time.monotonic_ns()))
        work.mkdir()
        # Exercise the old eager lifecycle too, so its failure is an expired code, not a missing symbol.
        name = "start_bypass_pairing_fixture" if "start_bypass_pairing_fixture() {" in self.script else "mint_bypass_pairing"
        body = "set -euo pipefail\nlog() { :; }\nbypass_unmet() { exit 9; }\n"
        if name == "mint_bypass_pairing":
            body += self.function("phone_pair_code") + self.function("pair_token")
        body += self.function(name) + "\n" + name + "\n"
        body += """export BYPASS_FIXTURE_PID BYPASS_FIXTURE_PORT BYPASS_FIXTURE_AUTH PAIR_CODE_BYPASS BYPASS_PEER_TOKEN BYPASS_PEER_SERVER_STATIC_PUBKEY
python3 - <<'END'
import json, os
print(json.dumps({key: os.environ.get(key, '') for key in
                 ('BYPASS_FIXTURE_PID', 'BYPASS_FIXTURE_PORT', 'BYPASS_FIXTURE_AUTH', 'PAIR_CODE_BYPASS',
                  'BYPASS_PEER_TOKEN', 'BYPASS_PEER_SERVER_STATIC_PUBKEY')}))
END
"""
        env = dict(os.environ, REPO_ROOT=str(self.root), WORK_DIR=str(work), BYPASS_HOME=str(self.tmp),
                   PYRY_BIN=str(self.cli), PYRY_NAME_BYPASS="e2e-fixture-bypass", PAIR_NAME_BYPASS="phone",
                   PAIR_NAME_BYPASS_PEER="peer", DAEMON_RELAY_URL="ws://127.0.0.1:8888/v1/server",
                   PHONE_RELAY_URL="ws://10.0.2.2:8888", FIXTURE_CLOCK=str(self.clock), FIXTURE_CALLS=str(self.calls),
                   PAIR_BYPASS_OUT=str(self.tmp / "phone.out"), PAIR_BYPASS_PEER_OUT=str(self.tmp / "peer.out"),
                   SERVER_ID_BYPASS="", PAIR_CODE_BYPASS="", BYPASS_PEER_TOKEN="", BYPASS_PEER_SERVER_STATIC_PUBKEY="",
                   BYPASS_FIXTURE_PID="", BYPASS_FIXTURE_PORT="", BYPASS_FIXTURE_AUTH="", **extra)
        result = subprocess.run(["bash", "-c", body], env=env, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, "fixture setup failed")
        config = json.loads(result.stdout)
        config["work"] = str(work)
        if config.get("BYPASS_FIXTURE_PID"):
            self.addCleanup(self.stop_fixture, int(config["BYPASS_FIXTURE_PID"]))
        return config

    def stop_fixture(self, pid):
        import signal
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass

    def request(self, config, auth=None, path="/pair"):
        request = Request(f"http://127.0.0.1:{config['BYPASS_FIXTURE_PORT']}{path}", data=b"",
                          headers={"Authorization": "Bearer " + (config['BYPASS_FIXTURE_AUTH'] if auth is None else auth)})
        with urlopen(request, timeout=5) as response:
            return json.load(response)

    def assert_private_diagnostics(self, config, code=""):
        output = (Path(config["work"]) / "bypass-pairing.log").read_text()
        for secret in (config['BYPASS_FIXTURE_AUTH'], 'secret-token', 'secret-key', 'secret-code'):
            self.assertTrue(secret not in output, "fixture diagnostics exposed credential material")
        if code:
            self.assertTrue(code not in output, "fixture diagnostics exposed a pairing code")

    def test_pairing_is_redeemable_after_preceding_scenarios_exceed_the_window(self):
        config = self.start_fixture()
        self.clock.write_text(str(16 * 60))  # No sleep and no Claude turn.
        if config['BYPASS_FIXTURE_PORT']:
            self.assertFalse(self.calls.exists(), "fixture minted before scenario entry")
            fixture = self.request(config)
            code = fixture['pairCode']
            self.assertTrue(fixture['peerToken'] == 'secret-token', "peer pairing missing")
        else:
            code = config['PAIR_CODE_BYPASS']
        payload = json.loads(base64.urlsafe_b64decode(code + '=' * (-len(code) % 4)))
        self.assertLess(int(self.clock.read_text()) - payload['mintedAt'], 15 * 60,
                        "bypass phone code expired while waiting for its scenario")
        self.assertTrue(payload['relay'] == 'ws://10.0.2.2:8888', "phone relay rewrite missing")
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertEqual([False, True], [call['privileged'] for call in calls])
        self.assertTrue(all(call['home'] == str(self.tmp) for call in calls), "pairing escaped isolated HOME")
        self.assertEqual([16 * 60, 16 * 60], [call['at'] for call in calls])
        self.assert_private_diagnostics(config, code)
        self.assertEqual(0o600, (Path(config["work"]) / "bypass-pairing.json").stat().st_mode & 0o777)

    def test_harness_cleanup_stops_the_fixture(self):
        config = self.start_fixture()
        cleanup = self.function("cleanup")
        body = "set -euo pipefail\nlog() { :; }\n" + cleanup + "\ncleanup\n"
        env = dict(os.environ, BYPASS_FIXTURE_PID=config['BYPASS_FIXTURE_PID'], WORK_DIR=config['work'],
                   WATCHER_PID="", DAEMON_PID="", RELAY_PID="", ISO_HOME="", BYPASS_HOME="", ANSWER_HOME="")
        result = subprocess.run(["bash", "-c", body], env=env, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, "harness cleanup failed")
        self.assertFalse(Path(config['work']).exists())
        import socket
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                with socket.create_connection(('127.0.0.1', int(config['BYPASS_FIXTURE_PORT'])), timeout=0.1):
                    time.sleep(0.01)
            except OSError:
                break
        else:
            self.fail("fixture remained reachable after harness cleanup")

    def test_unauthorized_and_unknown_requests_cannot_mint_and_success_is_one_shot(self):
        config = self.start_fixture()
        for auth, path, status in (("wrong", "/pair", 403), (None, "/other", 404)):
            with self.assertRaises(HTTPError) as failure:
                self.request(config, auth=auth, path=path)
            self.assertEqual(status, failure.exception.code)
            failure.exception.close()
            self.assertFalse(self.calls.exists())
        fixture = self.request(config)
        with self.assertRaises(HTTPError) as failure:
            self.request(config)
        self.assertEqual(409, failure.exception.code)
        failure.exception.close()
        self.assertEqual(2, len(self.calls.read_text().splitlines()))
        self.assert_private_diagnostics(config, fixture["pairCode"])

    def test_failed_mint_or_host_mismatch_is_private_and_cannot_retry(self):
        for mode in ('FIXTURE_FAILURE', 'FIXTURE_MISMATCH'):
            with self.subTest(mode=mode):
                config = self.start_fixture(**{mode: '1'})
                with self.assertRaises(HTTPError) as failure:
                    self.request(config)
                self.assertEqual(503, failure.exception.code)
                self.assertTrue('secret' not in failure.exception.read().decode(), "HTTP error exposed credentials")
                failure.exception.close()
                count = len(self.calls.read_text().splitlines())
                with self.assertRaises(HTTPError) as failure:
                    self.request(config)
                self.assertEqual(409, failure.exception.code)
                failure.exception.close()
                self.assertEqual(count, len(self.calls.read_text().splitlines()))
                self.assert_private_diagnostics(config)


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

    def test_bypass_arguments_carry_the_unmet_code_or_the_fixture(self):
        # #687: the operator-bypass daemon passes nothing, only its unmet prerequisite, or its fixture and witness arguments.
        root = Path(__file__).resolve().parent.parent
        script = (root / "scripts/e2e-emulator.sh").read_text()
        invocation = device_test_run(script)
        prefix = "-Pandroid.testInstrumentationRunnerArguments."
        pairing = {"BYPASS_FIXTURE_PORT": "12345", "BYPASS_FIXTURE_AUTH": "private-capability",
                   "BYPASS_TOKEN_FILE": "/tmp/pyry-e2e-byp.x/outside/e2e687-1.txt", "BYPASS_WITNESS": "abc123"}
        cases = (
            ({}, []),
            ({"BYPASS_UNMET": "no_credential"}, [prefix + "bypassUnmet=no_credential"]),
            (pairing, [prefix + "bypassFixturePort=12345", prefix + "bypassFixtureAuthorization=private-capability",
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
        # Host A (isolated and real HOME), host B, the host A peer and two on the answer daemon.
        # Bypass pairing now waits for the scenario-entry request in e2e-bypass-pairing.py.
        self.assertEqual(6, len(mints))
        self.assertLess(build, min(mints))
        for name in ("start_bypass_pairing_fixture", "mint_answer_pairing"):
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
