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
        self.assertEqual({'total': 1, 'liveSelected': 1, 'recorded': 0, 'retired': [], 'pending': ['a']}, report)

    def test_wrong_scenario_or_skipped_replay_cannot_count(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / 'scenario.json'
            row = {'method': 'a', 'fixture': fixture.name, 'evidence': {'liveVerified': True, 'replay': {'executed': 1, 'failed': 0, 'skipped': 1}}}
            manifest = {'scenarios': [row], 'liveSelection': ['a']}
            for scenario in ['a', 'b']:
                fixture.write_text(json.dumps({'format': 'pyrycode.mobile-suite', 'scenario': scenario}))
                with self.assertRaises(ValueError): inventory.audit(manifest, {'a'}, {'a'}, root)

    def test_recording_count_requires_hash_bound_capture_and_replay(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'scenario.json'
            document = {'format': 'pyrycode.mobile-suite', 'scenario': 'a', 'source': {'mobileRevision': 'a' * 40}}
            path.write_text(json.dumps(document))
            checksum = hashlib.sha256(path.read_bytes()).hexdigest()
            count = {'executed': 1, 'failed': 0, 'skipped': 0, 'fixtureSha256': checksum}
            evidence = {'fixtureSha256': checksum, 'capture': {**count, 'source': document['source']}, 'replay': count.copy()}
            row = {'method': 'a', 'fixture': path.name, 'evidence': evidence}
            manifest = {'scenarios': [row], 'liveSelection': ['a']}
            self.assertEqual(1, inventory.audit(manifest, {'a'}, {'a'}, root)['recorded'])
            evidence['replay']['skipped'] = 1
            with self.assertRaises(ValueError): inventory.audit(manifest, {'a'}, {'a'}, root)
            evidence['replay']['skipped'] = 0
            path.write_text(json.dumps({**document, 'changed': True}))
            with self.assertRaises(ValueError): inventory.audit(manifest, {'a'}, {'a'}, root)

    def test_runner_must_cover_every_nonretired_method_without_ignores(self):
        inventory.audit_runner('@Test fun a() {}', {'a', 'old'}, {'old'})
        for source in ['@Test fun a() {}\n@Ignore @Test fun b() {}', '@Test fun old() {}', '@Test fun a() {}\n@Test fun b() {}']:
            with self.assertRaises(ValueError): inventory.audit_runner(source, {'a', 'old'}, {'old'})

    def test_fresh_evidence_is_external_hash_bound_complete_and_uses_the_same_installed_packages(self):
        import copy
        source = {'mobileRevision': 'a' * 40, 'daemonRevision': 'b' * 40, 'daemonBinarySha256': 'c' * 64,
                  'testApkSha256': 'd' * 64, 'appApkSha256': 'e' * 64, 'claudeVersion': 'test', 'androidApi': '33'}
        def counted(checksum): return {'executed': 1, 'failed': 0, 'skipped': 0, 'fixtureSha256': checksum,
                                      'testApkSha256': source['testApkSha256'], 'appApkSha256': source['appApkSha256']}
        row = {'method': 'a', 'evidence': {'fixtureSha256': '1' * 64}}
        receipt = {'scenario': 'a', 'capture': {**counted('2' * 64), 'source': source},
                   'reviewedReplay': counted('1' * 64), 'freshReplay': counted('2' * 64), 'contractMatched': True}
        manifest = {'scenarios': [row]}
        summary = {'required': 1, 'verified': 1, 'failures': [], 'results': [receipt]}
        inventory.audit_live(manifest, summary)
        mutations = [lambda x: x.update(verified=0), lambda x: x['results'].clear(),
                     lambda x: x['results'].append(copy.deepcopy(receipt)),
                     lambda x: x['results'][0]['freshReplay'].update(skipped=1),
                     lambda x: x['results'][0]['reviewedReplay'].update(fixtureSha256='3' * 64),
                     lambda x: x['results'][0]['freshReplay'].update(testApkSha256='f' * 64),
                     lambda x: x['results'][0].update(contractMatched=False)]
        for mutate in mutations:
            changed = copy.deepcopy(summary)
            mutate(changed)
            with self.assertRaises(ValueError): inventory.audit_live(manifest, changed)


if __name__ == '__main__':
    unittest.main()
