"""A refused builder request must not enter host admission."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class ProductSelectionBoundaryTests(unittest.TestCase):
    def test_raw_gradlew_checks_policy_before_java_and_preserves_ordinary_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'scripts'
            scripts.mkdir()
            shutil.copy2(Path(__file__).parents[1] / 'gradlew', root / 'gradlew')
            shutil.copy2(Path(__file__).with_name('test-scope.py'), scripts / 'test-scope.py')
            agents = root / 'agents/scripts'
            agents.mkdir(parents=True)
            (agents / 'test-selection.py').write_text('def check_selection(root, kind, args):\n'
                                                     '    if args[0] == "check": raise SystemExit("focused selection required")\n')
            java = root / 'jdk/bin/java'
            java.parent.mkdir(parents=True)
            receipt = root / 'java-entered'
            java.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > ' + str(receipt) + '\n')
            java.chmod(0o755)
            env = {**os.environ, 'AGENTS_REPO_PATH': str(agents.parent), 'JAVA_HOME': str(java.parents[1])}
            denied = subprocess.run(['sh', str(root / 'gradlew'), 'check'], env=env,
                                    capture_output=True, text=True, timeout=5)
            self.assertNotEqual(denied.returncode, 0)
            self.assertIn('focused selection required', denied.stderr)
            self.assertFalse(receipt.exists())
            for args in [['assembleDebug'], ['spotlessApply'], ['--version'], ['--help']]:
                result = subprocess.run(['sh', str(root / 'gradlew'), *args], env=env,
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(receipt.read_text().splitlines()[-1], args[-1])

    def test_selection_precedes_admission_for_every_test_helper(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'scripts'
            scripts.mkdir()
            shutil.copy2(Path(__file__).with_name('agent-tool.py'), scripts / 'agent-tool.py')
            shutil.copy2(Path(__file__).with_name('test-scope.py'), scripts / 'test-scope.py')
            marker = root / 'admission-entered'
            (scripts / 'test-admission.py').write_text('from pathlib import Path\nPath(' + repr(str(marker)) + ').write_text("entered")\n')
            agents = root / 'agents'
            (agents / 'scripts').mkdir(parents=True)
            guard = agents / 'scripts/test-selection.py'
            guard.write_text('def check_selection(root, kind, args):\n    raise SystemExit("focused selection required: " + kind)\n')
            env = {**os.environ, 'AGENTS_REPO_PATH': str(agents)}
            for name, kind, args in [('android-test-gate.py', 'android', ['ui']),
                                     ('jvm-test-gate.py', 'jvm', []), ('gradle-gate.py', 'gradle', ['check'])]:
                with self.subTest(name=name):
                    (agents / 'scripts' / name).write_text('raise AssertionError("must not launch")\n')
                    result = subprocess.run([sys.executable, str(scripts / 'agent-tool.py'), name, *args],
                                            env=env, capture_output=True, text=True, timeout=5)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('focused selection required: ' + kind, result.stderr)
                    self.assertFalse(marker.exists())
            # The predecessor without the selection check entered admission.
            old = (scripts / 'agent-tool.py').read_text()
            old = '\n'.join(line for line in old.splitlines() if not line.startswith('    check'))
            (scripts / 'agent-tool.py').write_text(old + '\n')
            result = subprocess.run([sys.executable, str(scripts / 'agent-tool.py'), 'android-test-gate.py', 'ui'],
                                    env=env, capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(marker.exists())

    def test_missing_consumer_guard_is_legacy_only_and_never_admits_marked_worktree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'scripts'
            scripts.mkdir()
            shutil.copy2(Path(__file__).with_name('agent-tool.py'), scripts / 'agent-tool.py')
            shutil.copy2(Path(__file__).with_name('test-scope.py'), scripts / 'test-scope.py')
            receipt = root / 'admission-count'
            (scripts / 'test-admission.py').write_text('from pathlib import Path\np=Path(' + repr(str(receipt)) + ')\np.write_text(p.read_text()+"1" if p.exists() else "1")\n')
            agents = root / 'agents'
            (agents / 'scripts').mkdir(parents=True)
            (agents / 'scripts/android-test-gate.py').write_text('raise AssertionError("unused fixture")\n')
            env = {**os.environ, 'AGENTS_REPO_PATH': str(agents)}
            command = [sys.executable, str(scripts / 'agent-tool.py'), 'android-test-gate.py', 'ui']
            legacy = subprocess.run(command, env=env, capture_output=True, text=True, timeout=5)
            self.assertEqual(legacy.returncode, 0, legacy.stderr)
            metadata = root / 'metadata'
            (metadata / 'pyry-test-context').mkdir(parents=True)
            (root / '.git').write_text('gitdir: metadata\n')
            marked = subprocess.run(command, env=env, capture_output=True, text=True, timeout=5)
            self.assertNotEqual(marked.returncode, 0)
            self.assertIn('no test was admitted', marked.stderr)
            self.assertEqual(receipt.read_text(), '1')
            (metadata / 'pyry-test-context').rmdir()
            (metadata / 'pyry-test-managed').write_text('managed')
            deleted = subprocess.run(command, env=env, capture_output=True, text=True, timeout=5)
            self.assertNotEqual(deleted.returncode, 0)
            self.assertEqual(receipt.read_text(), '1')


if __name__ == '__main__':
    unittest.main()
