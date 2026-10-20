import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


spec = importlib.util.spec_from_file_location('agent_scenarios', Path(__file__).with_name('e2e-background-agent-scenarios.py'))
scenarios = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scenarios)


class AgentScenarioReportsTest(unittest.TestCase):
    def run_cases(self, outcomes):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        reports = root / 'app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd'
        reports.mkdir(parents=True)
        calls = []

        def launch(command, cwd, env):
            case = env['E2E_BACKGROUND_AGENT_CASE']
            calls.append(case)
            outcome = outcomes[len(calls) - 1]
            if isinstance(outcome, Exception):
                raise outcome
            if outcome is not None:
                failure = '<skipped/>' if outcome == 'skip' else '<failure/>' if outcome else ''
                (reports / 'TEST-run.xml').write_text(
                    f'<testsuite><testcase classname="{scenarios.CLASS}" name="{scenarios.METHODS[case]}">{failure}</testcase></testsuite>')
            return subprocess.CompletedProcess(command, 0 if outcome == 'skip' else outcome or 0)

        with patch.object(scenarios, '__file__', str(root / 'scripts/scenarios.py')), \
                patch.dict(scenarios.os.environ, {}, clear=True), \
                patch.object(scenarios.subprocess, 'run', side_effect=launch):
            try:
                status = scenarios.main()
            except RuntimeError:
                status = 'interrupted'
        cases = [case for path in reports.rglob('TEST-*.xml') for case in ET.parse(path).getroot().iter('testcase')]
        return status, calls, cases

    def test_two_independent_outcomes_keep_the_first_failure(self):
        status, calls, cases = self.run_cases([1, 0])
        self.assertEqual(1, status)
        self.assertEqual(['retained', 'restart'], calls)
        self.assertEqual(list(scenarios.METHODS.values()), [case.get('name') for case in cases])
        self.assertIsNotNone(cases[0].find('failure'))
        self.assertIsNone(cases[1].find('failure'))

    def test_missing_second_report_is_not_a_pass_or_duplicate_first_execution(self):
        status, _, cases = self.run_cases([0, None])
        self.assertEqual(1, status)
        self.assertEqual([scenarios.METHODS['retained']], [case.get('name') for case in cases])

    def test_interruption_preserves_the_already_counted_outcome(self):
        status, _, cases = self.run_cases([0, RuntimeError('interrupted')])
        self.assertEqual('interrupted', status)
        self.assertEqual([scenarios.METHODS['retained']], [case.get('name') for case in cases])

    def test_a_skipped_twin_cannot_count_as_success(self):
        status, _, cases = self.run_cases([0, 'skip'])
        self.assertEqual(1, status)
        self.assertEqual(2, len(cases))


if __name__ == '__main__':
    unittest.main()
