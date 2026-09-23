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


if __name__ == "__main__":
    unittest.main()
