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


if __name__ == "__main__":
    unittest.main()
