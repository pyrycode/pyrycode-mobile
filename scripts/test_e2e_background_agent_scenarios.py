import importlib.util
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys
from unittest.mock import patch
import xml.etree.ElementTree as ET


spec = importlib.util.spec_from_file_location('agent_scenarios', Path(__file__).with_name('e2e-background-agent-scenarios.py'))
scenarios = importlib.util.module_from_spec(spec)
with patch.object(sys, 'path', [str(Path(__file__).parent), *sys.path]):
    spec.loader.exec_module(scenarios)


class AgentScenarioReportsTest(unittest.TestCase):
    def run_cases(self, outcomes, admission=None, report_mtimes=(3_000_000_000, 3_000_000_000), preexisting=False, artifacts=False,
                  early_restart=False, path_defaults=False, empty_overrides=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        reports = root / 'app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd'
        reports.mkdir(parents=True)
        apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
        if artifacts:
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'effective-test-apk')
        calls = []
        originals = []
        environment = {} if admission is None else {'PYRY_TEST_ADMISSION_FD': str(admission)}
        binaries = {}
        if path_defaults:
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            environment['PATH'] = str(bin_dir)
            for name, executable in (('PYRY_BIN', 'pyry'), ('RELAY_BIN', 'pyrycode-relay')):
                binary = bin_dir / executable
                binary.write_bytes(f'effective:{executable}'.encode())
                binary.chmod(0o700)
                binaries[name] = binary
                if empty_overrides:
                    environment[name] = ''

        def write_report(case, outcome, mtime):
            suite = ET.Element('testsuite')
            if outcome != 'zero':
                test = ET.SubElement(suite, 'testcase', {
                    'classname': 'wrong.Class' if outcome == 'wrong-class' else scenarios.CLASS,
                    'name': 'wrongMethod' if outcome == 'wrong-method' else scenarios.METHODS[case],
                })
                rejection = {'skip': 'skipped', 'error': 'error', 1: 'failure'}.get(outcome)
                if rejection:
                    ET.SubElement(test, rejection, {'message': f'original {case} outcome'})
            path = reports / 'TEST-run.xml'
            path.parent.mkdir(parents=True, exist_ok=True)
            ET.ElementTree(suite).write(path)
            os.utime(path, ns=(mtime, mtime))
            self.assertEqual(mtime, path.stat().st_mtime_ns)
            return path.read_bytes()

        if preexisting:
            write_report('retained', 0, 9_000_000_000)

        def launch(command, cwd=None, env=None, pass_fds=(), **kwargs):
            if command[0] == 'git':
                return subprocess.CompletedProcess(command, 0, stdout='a' * 40 + '\n')
            if admission is not None:
                self.assertIn(admission, pass_fds)
            case = env['E2E_BACKGROUND_AGENT_CASE']
            calls.append(case)
            if early_restart and case == 'restart':
                return subprocess.CompletedProcess(command, outcomes[1])
            for name, binary in binaries.items():
                self.assertEqual(str(binary), env.get(name), 'launch must use the hashed PATH selection')
            if artifacts:
                # Model the managed task replacing the exact same phone/UTP paths each arm.
                for name in ('logcat.txt', 'test-result.pb', 'utp/run.log'):
                    path = reports / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(f'{case}:{name}'.encode())
                harness = Path(env.get('E2E_FAULT_EVIDENCE_DIR', root / 'lost-harness'))
                harness.mkdir(parents=True, exist_ok=True)
                (harness / 'daemon.log').write_text(f'{case}:daemon')
                (harness / 'relay.log').write_text(f'{case}:relay')
            outcome = outcomes[len(calls) - 1]
            if isinstance(outcome, Exception):
                raise outcome
            if outcome is not None:
                originals.append(write_report(case, outcome, report_mtimes[len(calls) - 1]))
            return subprocess.CompletedProcess(command, outcome if isinstance(outcome, int) else 0)

        with patch.object(scenarios, '__file__', str(root / 'scripts/scenarios.py')), \
                patch.dict(scenarios.os.environ, environment, clear=True), \
                patch('time.time_ns', return_value=2_000_000_000), \
                patch.object(scenarios.subprocess, 'run', side_effect=launch):
            try:
                status = scenarios.main()
            except RuntimeError:
                status = 'interrupted'
        paths = list(reports.rglob('TEST-*.xml'))
        self.assertEqual(len(originals), len(paths))
        self.assertCountEqual(originals, [path.read_bytes() for path in paths])
        cases = [case for path in paths for case in ET.parse(path).getroot().iter('testcase')]
        if artifacts:
            bundles = list((root / 'build/background-agent-evidence').glob('*/retained'))
            self.assertEqual(1, len(bundles), 'the first arm must survive managed output replacement')
            for case in scenarios.METHODS:
                bundle = bundles[0].parent / case
                if early_restart and case == 'restart':
                    self.assertFalse((bundle / 'phone').exists(), 'early failure must not inherit first-arm diagnostics')
                    self.assertFalse((bundle / 'harness').exists())
                    self.assertEqual(outcomes[1], json.loads((bundle / 'result.json').read_text())['exit_code'])
                    continue
                for name in ('logcat.txt', 'test-result.pb', 'utp/run.log'):
                    self.assertEqual(f'{case}:{name}'.encode(), (bundle / 'phone' / name).read_bytes())
                for name in ('daemon.log', 'relay.log'):
                    self.assertEqual(f'{case}:{name.removesuffix(".log")}', (bundle / 'harness' / name).read_text())
                metadata = json.loads((bundle / 'result.json').read_text())
                self.assertEqual(outcomes[list(scenarios.METHODS).index(case)], metadata['exit_code'])
                self.assertEqual(scenarios.CLASS + '#' + scenarios.METHODS[case], metadata['method'])
                self.assertEqual('a' * 40, metadata['provenance']['mobile'])
                for name, binary in binaries.items():
                    self.assertEqual(hashlib.sha256(binary.read_bytes()).hexdigest(), metadata['provenance']['binary_sha256'][name])
                self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(),
                                 metadata['apk_sha256']['app/build/outputs/apk/debug/app-debug.apk'])
                for line in (bundle / 'SHA256SUMS').read_text().splitlines():
                    expected, name = line.split('  ', 1)
                    self.assertEqual(expected, hashlib.sha256((bundle / name).read_bytes()).hexdigest())
        return status, calls, cases

    def test_each_arm_keeps_phone_utp_and_correlated_harness_even_on_failure(self):
        for outcomes in ([1, 0], [0, 1], [0, 0]):
            with self.subTest(outcomes=outcomes):
                status, calls, cases = self.run_cases(outcomes, artifacts=True)
                self.assertEqual(int(any(outcomes)), status)
                self.assertEqual(['retained', 'restart'], calls)
                self.assertEqual(2, len(cases))

    def test_two_independent_outcomes_keep_the_first_failure(self):
        rglob = Path.rglob
        for reverse in (False, True):
            with self.subTest(reverse=reverse), patch.object(
                    Path, 'rglob', autospec=True,
                    side_effect=lambda path, pattern: iter(sorted(rglob(path, pattern), reverse=reverse))):
                status, calls, cases = self.run_cases([1, 0])
                self.assertEqual(1, status)
                self.assertEqual(['retained', 'restart'], calls)
                self.assertCountEqual(list(scenarios.METHODS.values()), [case.get('name') for case in cases])
                by_name = {case.get('name'): case for case in cases}
                self.assertIsNotNone(by_name[scenarios.METHODS['retained']].find('failure'))
                self.assertFalse(any(by_name[scenarios.METHODS['restart']].find(outcome) is not None
                                     for outcome in ('failure', 'error', 'skipped')))

    def test_early_second_failure_keeps_first_artifacts_without_misattribution(self):
        for first in (0, 1):
            with self.subTest(first=first):
                status, calls, cases = self.run_cases([first, 7], artifacts=True, early_restart=True)
                self.assertEqual(first or 7, status)
                self.assertEqual(['retained', 'restart'], calls)
                self.assertEqual([scenarios.METHODS['retained']], [case.get('name') for case in cases])
                self.assertEqual(first == 1, cases[0].find('failure') is not None)

    def test_path_defaults_are_frozen_for_launch_and_binary_provenance(self):
        for empty in (False, True):
            with self.subTest(empty=empty):
                status, calls, cases = self.run_cases([0, 0], artifacts=True, path_defaults=True, empty_overrides=empty)
                self.assertEqual(0, status)
                self.assertEqual(['retained', 'restart'], calls)
                self.assertEqual(2, len(cases))

    def test_missing_second_report_is_not_a_pass_or_duplicate_first_execution(self):
        status, _, cases = self.run_cases([0, None])
        self.assertEqual(1, status)
        self.assertEqual([scenarios.METHODS['retained']], [case.get('name') for case in cases])

    def test_interruption_preserves_the_already_counted_outcome(self):
        for mtime in (1_000_000_000, 3_000_000_000):
            with self.subTest(mtime=mtime):
                status, _, cases = self.run_cases([0, RuntimeError('interrupted')], report_mtimes=(mtime, mtime))
                self.assertEqual('interrupted', status)
                self.assertEqual([scenarios.METHODS['retained']], [case.get('name') for case in cases])

    def test_a_skipped_twin_cannot_count_as_success(self):
        status, _, cases = self.run_cases([0, 'skip'])
        self.assertEqual(1, status)
        self.assertEqual(2, len(cases))

    def test_earlier_report_mtime_keeps_both_independent_outcomes(self):
        rglob = Path.rglob
        for reverse in (False, True):
            for second in (0, 'skip'):
                for mtimes in ((1_000_000_000, 3_000_000_000), (1_000_000_000, 1_000_000_000)):
                    with self.subTest(reverse=reverse, second=second, mtimes=mtimes), patch.object(
                            Path, 'rglob', autospec=True,
                            side_effect=lambda path, pattern: iter(sorted(rglob(path, pattern), reverse=reverse))):
                        status, calls, cases = self.run_cases([0, second], report_mtimes=mtimes)
                        self.assertEqual(2, len(cases))
                        self.assertEqual(int(second == 'skip'), status)
                        self.assertEqual(['retained', 'restart'], calls)
                        self.assertCountEqual(list(scenarios.METHODS.values()), [case.get('name') for case in cases])
                        by_name = {case.get('name'): case for case in cases}
                        self.assertEqual([], list(by_name[scenarios.METHODS['retained']]))
                        self.assertEqual(
                            ['skipped'] if second == 'skip' else [],
                            [child.tag for child in by_name[scenarios.METHODS['restart']]])

    def test_future_dated_preexisting_report_cannot_satisfy_an_execution(self):
        status, _, cases = self.run_cases([None, 0], preexisting=True)
        self.assertEqual(1, status)
        self.assertEqual([scenarios.METHODS['restart']], [case.get('name') for case in cases])

    def test_invalid_report_cannot_count_as_success(self):
        for outcome in ('error', 'zero', 'wrong-method', 'wrong-class'):
            with self.subTest(outcome=outcome):
                status, _, _ = self.run_cases([0, outcome])
                self.assertEqual(1, status)

    def test_nonzero_child_status_cannot_count_as_success_despite_passing_xml(self):
        status, _, cases = self.run_cases([7, 0])
        self.assertEqual(7, status)
        self.assertEqual(2, len(cases))
        self.assertTrue(all(len(case) == 0 for case in cases))

    def test_nested_harness_keeps_the_original_admission_descriptor(self):
        reader, writer = os.pipe()
        try:
            status, calls, cases = self.run_cases([0, 0], admission=reader)
            self.assertEqual(0, status)
            self.assertEqual(['retained', 'restart'], calls)
            self.assertEqual(2, len(cases))
        finally:
            os.close(reader)
            os.close(writer)


if __name__ == '__main__':
    unittest.main()
