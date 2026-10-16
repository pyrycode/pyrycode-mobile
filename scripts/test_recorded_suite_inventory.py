import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('inventory', Path(__file__).with_name('recorded-suite-inventory.py'))
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class SuiteInventoryTest(unittest.TestCase):
    def test_annotation_modifiers_and_negative_controls_are_included(self):
        source = '@Test\n@OptIn(ExperimentalApi::class)\nfun normal() {}\n@Test\nfun negativeControl_never() {}'
        self.assertEqual({'normal', 'negativeControl_never'}, inventory.methods(source))

    def test_missing_duplicate_or_extra_methods_fail(self):
        for rows in [[], [{'method': 'a'}, {'method': 'a'}], [{'method': 'a'}, {'method': 'b'}]]:
            with self.assertRaises(ValueError):
                inventory.audit({'scenarios': rows, 'liveSelection': ['a']}, {'a'}, {'a'}, Path('/unused'))

    def test_pending_work_is_reported_and_never_counted_as_converted(self):
        report = inventory.audit({'scenarios': [{'method': 'a', 'fixture': None}], 'liveSelection': ['a']}, {'a'}, {'a'}, Path('/unused'))
        self.assertEqual({'total': 1, 'liveSelected': 1, 'recorded': 0, 'pending': ['a']}, report)

    def test_wrong_scenario_or_skipped_replay_cannot_count(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / 'scenario.json'
            row = {'method': 'a', 'fixture': fixture.name, 'evidence': {'liveVerified': True, 'replay': {'executed': 1, 'failed': 0, 'skipped': 1}}}
            manifest = {'scenarios': [row], 'liveSelection': ['a']}
            for scenario in ['a', 'b']:
                fixture.write_text(json.dumps({'format': 'pyrycode.mobile-suite', 'scenario': scenario}))
                with self.assertRaises(ValueError): inventory.audit(manifest, {'a'}, {'a'}, root)


if __name__ == '__main__':
    unittest.main()
