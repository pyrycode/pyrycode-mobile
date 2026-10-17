import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('inventory', Path(__file__).with_name('recorded-suite-inventory.py'))
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class SuiteInventoryTest(unittest.TestCase):
    def test_current_inventory_accounts_for_67_recordings_three_scripted_owners_and_two_obsolete_methods(self):
        manifest = json.loads(inventory.MANIFEST.read_text())
        actual = inventory.methods(inventory.SOURCE.read_text())
        live = inventory.curated(inventory.HARNESS.read_text())
        report = inventory.audit(manifest, actual, live, inventory.MANIFEST.parent)
        self.assertEqual((report['total'], report['liveSelected'], report['recorded']), (72, 65, 67))
        self.assertEqual(set(report['scripted']), set(inventory.SCRIPTED_OWNERS))
        self.assertTrue(set(report['scripted']) <= live)
        self.assertEqual(len(report['retired']), 2)
        self.assertEqual(report['pending'], [])
        inventory.audit_runner((inventory.ROOT / 'app/src/androidTest/java/de/pyryco/mobile/recording/RecordedMobileSuiteTest.kt').read_text(),
                               actual, set(report['retired']) | set(report['scripted']))
        self.assertEqual(len(inventory.recorded_rows(manifest)), 67)
    def test_only_reviewed_scripted_owners_replace_routine_recordings_without_claiming_captures(self):
        method = 'interactiveTurn_sessionError_recoversDroppedAndRetainedBacklog'
        owner = {'scenario': 'session-error',
                 'test': 'de.pyryco.mobile.e2e.DeterministicInteractiveStreamE2ETest#' + method}
        row = {'method': method, 'scripted': owner}
        manifest = {'scenarios': [row], 'liveSelection': [method]}
        report = inventory.audit(manifest, {method}, {method}, Path('/unused'))
        self.assertEqual(report['scripted'], {method: owner})
        self.assertEqual(report['recorded'], 0)
        self.assertEqual(report['pending'], [])
        self.assertEqual(report['retired'], [])
        for change in [{'method': 'unreviewed'}, {'scripted': {**owner, 'scenario': 'ping'}},
                       {'scripted': {**owner, 'test': owner['test'] + 'Renamed'}},
                       {'fixture': 'still-recorded.json'}, {'evidence': {'capture': {'executed': 1}}},
                       {'retired': 'not an obsolete test'}]:
            changed = {**row, **change}
            with self.subTest(change=change), self.assertRaises(ValueError):
                inventory.audit({'scenarios': [changed], 'liveSelection': [changed['method']]},
                                {changed['method']}, {changed['method']}, Path('/unused'))

    def test_scripted_ownership_requires_enabled_annotated_method_and_original_scenario_mapping(self):
        method = 'interactiveTurn_sessionError_recoversDroppedAndRetainedBacklog'
        row = {'method': method, 'scripted': {'scenario': 'session-error',
               'test': 'de.pyryco.mobile.e2e.DeterministicInteractiveStreamE2ETest#' + method}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'scripted.kt'
            harness = root / 'harness.sh'
            harness.write_text('    session-error)\n      TEST_METHOD="' + method + '"\n      ;;\n')
            with patch.object(inventory, 'SCRIPTED_SOURCE', source), patch.object(inventory, 'HARNESS', harness):
                for body in ['', 'fun ' + method + '() {}', '@Test\n@Ignore("blocked")\nfun ' + method + '() {}',
                             '@Ignore("blocked")\n@Test\nfun ' + method + '() {}']:
                    source.write_text(body)
                    with self.subTest(body=body), self.assertRaises(ValueError):
                        inventory.recorded_rows({'scenarios': [row]})
                source.write_text('@Test\nfun ' + method + '() {}')
                self.assertEqual(inventory.recorded_rows({'scenarios': [row]}), [])
                harness.write_text(harness.read_text().replace(method, 'renamed'))
                with self.assertRaises(ValueError): inventory.recorded_rows({'scenarios': [row]})

    def test_annotation_modifiers_and_negative_controls_are_included(self):
        source = '@Test\n@OptIn(ExperimentalApi::class)\nfun normal() {}\n@Test\nfun negativeControl_never() {}'
        self.assertEqual({'normal', 'negativeControl_never'}, inventory.methods(source))

    def test_missing_duplicate_or_extra_methods_fail(self):
        for rows in [[], [{'method': 'a'}, {'method': 'a'}], [{'method': 'a'}, {'method': 'b'}]]:
            with self.assertRaises(ValueError):
                inventory.audit({'scenarios': rows, 'liveSelection': ['a']}, {'a'}, {'a'}, Path('/unused'))

    def test_pending_work_is_reported_and_never_counted_as_converted(self):
        report = inventory.audit({'scenarios': [{'method': 'a', 'fixture': None}], 'liveSelection': ['a']}, {'a'}, {'a'}, Path('/unused'))
        self.assertEqual({'total': 1, 'liveSelected': 1, 'recorded': 0, 'scripted': {}, 'retired': [], 'pending': ['a']}, report)

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

    def test_unknown_read_policy_cannot_disable_read_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = {'format': 'pyrycode.mobile-suite', 'scenario': 'a', 'source': {}}
            data = json.dumps(fixture).encode()
            Path(directory, 'a.json').write_bytes(data)
            checksum = hashlib.sha256(data).hexdigest()
            counts = {'executed': 1, 'failed': 0, 'skipped': 0, 'fixtureSha256': checksum}
            row = {'method': 'a', 'fixture': 'a.json', 'evidence': {'fixtureSha256': checksum,
                   'capture': {**counts, 'source': {}}, 'replay': counts}}
            manifest = {'scenarios': [row], 'liveSelection': ['a'], 'readReceiptPolicy': 'presented-visible-history-v4'}
            inventory.audit(manifest, {'a'}, {'a'}, Path(directory))
            manifest['readReceiptPolicy'] = 'ignore-everything'
            with self.assertRaises(ValueError): inventory.audit(manifest, {'a'}, {'a'}, Path(directory))

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
        method = 'interactiveTurn_sessionError_recoversDroppedAndRetainedBacklog'
        manifest['scenarios'].append({'method': method, 'scripted': inventory.SCRIPTED_OWNERS[method]})
        # Scripted ownership supplies no fabricated capture and does not relax the retained recording.
        inventory.audit_live(manifest, summary)
        mutations = [lambda x: x.update(verified=0), lambda x: x['results'].clear(),
                     lambda x: x['results'].append(copy.deepcopy(receipt)),
                     lambda x: x['results'][0]['freshReplay'].update(skipped=1),
                     lambda x: x['results'][0]['reviewedReplay'].update(fixtureSha256='3' * 64),
                     lambda x: x['results'][0]['freshReplay'].update(testApkSha256='f' * 64),
                     lambda x: x['results'][0].update(contractMatched=False),
                     lambda x: x.update(required=2, verified=2, results=[receipt, {**receipt, 'scenario': method}])]
        for mutate in mutations:
            changed = copy.deepcopy(summary)
            mutate(changed)
            with self.assertRaises(ValueError): inventory.audit_live(manifest, changed)


if __name__ == '__main__':
    unittest.main()
