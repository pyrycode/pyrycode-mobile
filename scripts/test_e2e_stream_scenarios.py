import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


spec = importlib.util.spec_from_file_location('stream_scenarios', Path(__file__).with_name('e2e-stream-scenarios.py'))
scenarios = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scenarios)


class StreamScenarioReportsTest(unittest.TestCase):
    def run_cases(self, outcomes, admission=None, report_mtimes=(3_000_000_000, 3_000_000_000), preexisting=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        reports = root / 'app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd'
        reports.mkdir(parents=True)
        calls = []

        def write_report(case, outcome, mtime):
            suite = ET.Element('testsuite')
            if outcome != 'zero':
                test = ET.SubElement(suite, 'testcase', {
                    'classname': 'wrong.Class' if outcome == 'wrong-class' else scenarios.CLASS,
                    'name': 'wrongMethod' if outcome == 'wrong-method' else scenarios.METHODS[case],
                })
                rejection = {'skip': 'skipped', 'error': 'error', 1: 'failure'}.get(outcome)
                if rejection:
                    ET.SubElement(test, rejection)
            path = reports / 'TEST-run.xml'
            ET.ElementTree(suite).write(path)
            os.utime(path, ns=(mtime, mtime))
            self.assertEqual(mtime, path.stat().st_mtime_ns)

        if preexisting:
            write_report('stream', 0, 9_000_000_000)

        def launch(command, cwd, env, pass_fds=()):
            if admission is not None:
                self.assertIn(admission, pass_fds)
            case = env['SCENARIO']
            calls.append(case)
            outcome = outcomes[len(calls) - 1]
            if isinstance(outcome, Exception):
                raise outcome
            if outcome is not None:
                write_report(case, outcome, report_mtimes[len(calls) - 1])
            return subprocess.CompletedProcess(command, outcome if isinstance(outcome, int) else 0)

        with patch.object(scenarios, '__file__', str(root / 'scripts/scenarios.py')), \
                patch.dict(scenarios.os.environ, {} if admission is None else {'PYRY_TEST_ADMISSION_FD': str(admission)}, clear=True), \
                patch('time.time_ns', return_value=2_000_000_000), \
                patch.object(scenarios.subprocess, 'run', side_effect=launch):
            try:
                status = scenarios.main()
            except RuntimeError:
                status = 'interrupted'
        cases = [case for path in reports.rglob('TEST-*.xml') for case in ET.parse(path).getroot().iter('testcase')]
        archives = sorted((root / 'build/dispatcher-tests/stream-methods').glob('run-*/*'))
        self.assertEqual(
            sorted(path.read_bytes() for path in reports.rglob('TEST-*.xml')),
            sorted(path.read_bytes() for path in archives[-1].glob('TEST-*.xml')))
        return status, calls, cases

    def test_two_independent_outcomes_keep_the_first_failure(self):
        rglob = Path.rglob
        for reverse in (False, True):
            with self.subTest(reverse=reverse), patch.object(
                    Path, 'rglob', autospec=True,
                    side_effect=lambda path, pattern: iter(sorted(rglob(path, pattern), reverse=reverse))):
                status, calls, cases = self.run_cases([1, 0])
                self.assertEqual(1, status)
                self.assertEqual(['stream', 'stream-queued-echo'], calls)
                self.assertCountEqual(list(scenarios.METHODS.values()), [case.get('name') for case in cases])
                by_name = {case.get('name'): case for case in cases}
                self.assertIsNotNone(by_name[scenarios.METHODS['stream']].find('failure'))
                self.assertFalse(any(by_name[scenarios.METHODS['stream-queued-echo']].find(outcome) is not None
                                     for outcome in ('failure', 'error', 'skipped')))

    def test_missing_second_report_is_not_a_pass_or_duplicate_first_execution(self):
        status, _, cases = self.run_cases([0, None])
        self.assertEqual(1, status)
        self.assertEqual([scenarios.METHODS['stream']], [case.get('name') for case in cases])

    def test_interruption_preserves_the_already_counted_outcome(self):
        for mtime in (1_000_000_000, 3_000_000_000):
            with self.subTest(mtime=mtime):
                status, _, cases = self.run_cases([0, RuntimeError('interrupted')], report_mtimes=(mtime, mtime))
                self.assertEqual('interrupted', status)
                self.assertEqual([scenarios.METHODS['stream']], [case.get('name') for case in cases])

    def test_a_skipped_twin_cannot_count_as_success(self):
        status, _, cases = self.run_cases([0, 'skip'])
        self.assertEqual(1, status)
        self.assertEqual(2, len(cases))

    def test_earlier_report_mtime_keeps_both_independent_outcomes(self):
        for second in (0, 'skip'):
            for mtimes in ((1_000_000_000, 3_000_000_000), (1_000_000_000, 1_000_000_000)):
                with self.subTest(second=second, mtimes=mtimes):
                    status, calls, cases = self.run_cases([0, second], report_mtimes=mtimes)
                    self.assertEqual(2, len(cases))
                    self.assertEqual(int(second == 'skip'), status)
                    self.assertEqual(['stream', 'stream-queued-echo'], calls)
                    self.assertCountEqual(list(scenarios.METHODS.values()), [case.get('name') for case in cases])
                    by_name = {case.get('name'): case for case in cases}
                    self.assertEqual([], list(by_name[scenarios.METHODS['stream']]))
                    self.assertEqual(
                        ['skipped'] if second == 'skip' else [],
                        [child.tag for child in by_name[scenarios.METHODS['stream-queued-echo']]])

    def test_future_dated_preexisting_report_cannot_satisfy_an_execution(self):
        status, _, cases = self.run_cases([None, 0], preexisting=True)
        self.assertEqual(1, status)
        self.assertEqual([scenarios.METHODS['stream-queued-echo']], [case.get('name') for case in cases])

    def test_invalid_report_cannot_count_as_success(self):
        for outcome in ('error', 'zero', 'wrong-method', 'wrong-class'):
            with self.subTest(outcome=outcome):
                status, _, _ = self.run_cases([0, outcome])
                self.assertEqual(1, status)

    def test_nested_harness_keeps_the_original_admission_descriptor(self):
        reader, writer = os.pipe()
        try:
            status, calls, cases = self.run_cases([0, 0], admission=reader)
            self.assertEqual(0, status)
            self.assertEqual(['stream', 'stream-queued-echo'], calls)
            self.assertEqual(2, len(cases))
        finally:
            os.close(reader)
            os.close(writer)


if __name__ == '__main__':
    unittest.main()
