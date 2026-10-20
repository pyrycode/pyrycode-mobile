import copy
import importlib.util
from pathlib import Path
import unittest
from unittest import mock
import argparse
import json
import tempfile

spec = importlib.util.spec_from_file_location('suite', Path(__file__).with_name('recorded-mobile-suite.py'))
suite = importlib.util.module_from_spec(spec)
spec.loader.exec_module(suite)


class SuiteDriverTest(unittest.TestCase):
    def test_allowance_queries_keep_payload_count_connection_and_checkpoint(self):
        baseline = self.fixture()
        first = baseline['steps'][0]
        first['message'].update(type='list_conversations', payload={})
        allowance = copy.deepcopy(first)
        allowance['message'].update(id=2, type='request_claude_usage')
        checkpoint = copy.deepcopy(first)
        checkpoint.update(owner='control')
        checkpoint['message'].update(type='test-control:checkpoint', payload={'body': 'ready'})
        baseline['steps'] = [first, allowance, checkpoint]
        logical = suite.contract(baseline)
        self.assertEqual(['test-control:checkpoint'], [row['type'] for row in logical['commands']])
        self.assertEqual(['list_conversations', 'request_claude_usage'],
                         sorted(row['type'] for row in logical['queryCheckpoints'][0]['queries']))
        reordered = copy.deepcopy(baseline)
        reordered['steps'][:2] = list(reversed(reordered['steps'][:2]))
        self.assertEqual(suite.contract(baseline), suite.contract(reordered))
        for change in ['payload', 'missing', 'duplicate', 'host', 'connection', 'checkpoint']:
            candidate = copy.deepcopy(baseline)
            if change == 'payload': candidate['steps'][1]['message']['payload'] = {'conversation_id': 'chat'}
            if change == 'missing': candidate['steps'].pop(1)
            if change == 'duplicate': candidate['steps'].insert(1, copy.deepcopy(allowance))
            if change == 'host': candidate['steps'][1]['owner'] = 'phone/other-host/0'
            if change == 'connection': candidate['steps'][1]['owner'] += '/1'
            if change == 'checkpoint': candidate['steps'][1:3] = list(reversed(candidate['steps'][1:3]))
            self.assertNotEqual(suite.contract(baseline), suite.contract(candidate), change)

    def test_managed_verification_rejects_unreviewed_input_before_credentials_or_device(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / 'unreviewed.json'
            fixture.write_text(json.dumps(self.fixture()))
            gate = mock.MagicMock()
            with mock.patch.object(suite, 'validate'):
                with self.assertRaises(ValueError):
                    suite.managed_verify(argparse.Namespace(fixture=fixture), gate)
            gate.live_claude_environment.assert_not_called()
            gate.device_hold.assert_not_called()

    def test_managed_verification_keeps_selection_and_custody_on_failed_comparison(self):
        fixture = suite.MANIFEST.parent / 'suite/interactiveTurn_createEditArchiveChannel_readsPromptBack.json'
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(fixture=fixture, output=Path(directory), pyry=Path('/test/pyry'),
                                      daemon_source=Path('/test/daemon'))
            gate = mock.MagicMock()
            gate.LIVE_CLASS = 'de.pyryco.mobile.e2e.InteractiveStreamE2ETest'
            gate.live_claude_environment.return_value = {'ANDROID_HOME': '/sdk'}
            gate.build_apks.return_value = 0
            gate.managed_avd.return_value = (Path('/avds'), 'managed')
            gate.boot_emulator.return_value = ('emulator-owned', 'owned-process')
            gate.install_once.return_value = []
            gate.run_device_command.return_value.returncode = 1
            with mock.patch.object(suite.legacy, 'run', return_value=b''):
                self.assertEqual(1, suite.managed_verify(args, gate))
            gate.check_selection.assert_called_once_with(suite.ROOT, 'android', [
                'live', '--tests', gate.LIVE_CLASS + '#interactiveTurn_createEditArchiveChannel_readsPromptBack'])
            command = gate.run_device_command.call_args.args[0]
            self.assertIn('verify', command)
            self.assertIn('emulator-owned', command)
            self.assertEqual(str(fixture), command[command.index('--fixture') + 1])
            gate.stop_emulator.assert_called_once_with(mock.ANY, 'emulator-owned', 'owned-process')
            gate.device_hold.return_value.__exit__.assert_called_once()

    def test_recording_commands_select_only_recorded_rows_and_reject_scripted_requests(self):
        method = 'interactiveTurn_sessionError_recoversDroppedAndRetainedBacklog'
        manifest = {'scenarios': [{'method': 'recorded', 'fixture': 'recorded.json'},
                    {'method': method, 'scripted': {'scenario': 'session-error',
                     'test': 'de.pyryco.mobile.e2e.DeterministicInteractiveStreamE2ETest#' + method}}]}
        self.assertEqual([row['method'] for row in suite.selected_rows(manifest, ready=True)], ['recorded'])
        with tempfile.TemporaryDirectory() as directory:
            methods = Path(directory) / 'methods.json'
            methods.write_text(json.dumps([method]))
            with self.assertRaises(ValueError): suite.selected_rows(manifest, methods, ready=True)
        manifest['scenarios'][1]['scripted']['scenario'] = 'ping'
        with self.assertRaises(ValueError): suite.selected_rows(manifest, ready=True)

    def test_stopping_full_verification_preserves_completed_cases_and_counts_the_rest_unrun(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'suite.json'
            manifest.write_text('{}')
            stop = root / 'STOP'
            args = argparse.Namespace(output=root / 'evidence', stop_file=stop, methods_file=None)
            rows = [{'method': name, 'fixture': name + '.json'} for name in ['first', 'second']]
            def verify(case):
                case.output.mkdir(parents=True)
                report = {'scenario': case.output.name, 'capture': suite.COUNTS,
                          'reviewedReplay': suite.COUNTS, 'freshReplay': suite.COUNTS,
                          'contractMatched': True, 'failureStages': []}
                (case.output / 'verification.json').write_text(json.dumps(report))
                stop.write_text('stop at completed boundary')
                return report
            with mock.patch.object(suite, 'MANIFEST', manifest), mock.patch.object(suite, 'all_ready_rows'), \
                 mock.patch.object(suite, 'selected_rows', return_value=rows), mock.patch.object(suite, 'verify', side_effect=verify) as call:
                with self.assertRaises(ValueError): suite.verify_all(args)
            summary = json.loads((args.output / 'summary.json').read_text())
            self.assertEqual(1, call.call_count)
            self.assertTrue(summary['stopped'])
            self.assertEqual(2, summary['required'])
            for stage in summary['stageCounts'].values():
                self.assertEqual({'passed': 1, 'failed': 0, 'notRun': 1}, stage)

    def test_presentation_read_model_is_explicit_and_unknown_models_fail(self):
        fixture = self.fixture()
        fixture['readReceiptNormalization'] = 'presented-visible-history-v4'
        suite.validate(fixture)
        fixture['readReceiptNormalization'] = 'accept-any-read'
        with self.assertRaises(ValueError): suite.validate(fixture)

    def test_download_queries_can_reorder_but_keep_the_complete_file_identity(self):
        import base64
        original = self.fixture()
        download = original['steps'][0]['message']
        download.update(type='request_attachment', payload={'attachment_id': 'file'})
        query = copy.deepcopy(original['steps'][0])
        query['message'].update(id=2, type='request_model_list', payload={'conversation_id': 'chat'})
        upload = copy.deepcopy(original['steps'][0])
        data = b'captured bytes'
        upload['owner'] = 'peer'
        upload['message'].update(type='attachment_chunk', payload={'attachment_id': 'file', 'index': 0, 'total_chunks': 1,
                                                                  'sha256': suite.digest(data), 'data': base64.b64encode(data).decode()})
        send = copy.deepcopy(query)
        send['message'].update(id=3, type='send_message', payload={'conversation_id': 'chat', 'message_id': 'user-send', 'text': 'unrelated later message'})
        original['steps'] = [upload, original['steps'][0], send, query]
        reordered = copy.deepcopy(original)
        reordered['steps'] = [reordered['steps'][0], reordered['steps'][2], reordered['steps'][3], reordered['steps'][1]]
        self.assertEqual(suite.contract(original), suite.contract(reordered))
        reordered['steps'][-1]['message']['payload']['attachment_id'] = 'substituted-file'
        self.assertNotEqual(suite.contract(original), suite.contract(reordered))

    def test_query_order_may_vary_inside_but_never_across_observation_checkpoints(self):
        original = self.fixture()
        first = original['steps'][0]
        first['message']['type'] = 'request_model_list'
        first['message']['payload'] = {'conversation_id': 'chat'}
        checkpoint = {'direction': 'client', 'owner': 'control', 'delayMs': 0, 'accepted': True,
                      'detail': {}, 'message': {'id': 1, 'type': 'test-control:checkpoint', 'ts': 'time',
                                               'payload': {'body': 'reply-visible'}}}
        later = copy.deepcopy(first)
        later['message']['type'] = 'request_context_usage'
        original['steps'] = [first, checkpoint, later]
        moved = copy.deepcopy(original)
        moved['steps'] = [moved['steps'][0], moved['steps'][2], moved['steps'][1]]
        self.assertNotEqual(suite.contract(original), suite.contract(moved))

    def test_verification_preserves_successful_replays_when_fresh_contract_drifts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original = root / 'reviewed.json'
            original.write_text(json.dumps(self.fixture()))
            fresh = self.fixture()
            fresh['steps'][0]['message']['payload']['device_name'] = 'changed phone'
            def capture(serial, method, candidate, pyry, source):
                candidate.write_text(json.dumps(fresh))
                return {**suite.COUNTS, 'fixtureSha256': suite.digest(candidate.read_bytes())}
            args = argparse.Namespace(serial='owned-device', fixture=original, output=root / 'verification', pyry=root / 'pyry', daemon_source=root)
            with mock.patch.object(suite, 'capture', side_effect=capture), mock.patch.object(suite, 'replay', return_value=suite.COUNTS), mock.patch.object(suite, 'suite_query_policy', return_value=None):
                with self.assertRaises(ValueError): suite.verify(args)
            report = json.loads((args.output / 'verification.json').read_text())
            self.assertEqual(report['capture']['failed'], 0)
            self.assertEqual(report['reviewedReplay']['failed'], 0)
            self.assertEqual(report['freshReplay']['failed'], 0)
            self.assertEqual(report['failureStages'], ['comparison'])
            self.assertFalse(report['contractMatched'])
            self.assertFalse(report['verified'])
            self.assertFalse((args.output / 'receipt.json').exists())

    def test_live_and_replay_failures_are_reported_as_different_stages(self):
        for failing in ['capture', 'reviewedReplay', 'freshReplay']:
            with self.subTest(stage=failing), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                original = root / 'reviewed.json'
                original.write_text(json.dumps(self.fixture()))
                def capture(serial, method, candidate, pyry, source):
                    if failing == 'capture': raise ValueError('private live diagnostic')
                    candidate.write_bytes(original.read_bytes())
                    return suite.COUNTS
                def replay(serial, path):
                    if (path == original) == (failing == 'reviewedReplay'):
                        raise ValueError('private replay diagnostic')
                    return suite.COUNTS
                args = argparse.Namespace(serial='owned-device', fixture=original, output=root / 'verification', pyry=root / 'pyry', daemon_source=root)
                with mock.patch.object(suite, 'capture', side_effect=capture), mock.patch.object(suite, 'replay', side_effect=replay), mock.patch.object(suite, 'suite_query_policy', return_value=None):
                    with self.assertRaises(ValueError): suite.verify(args)
                text = (args.output / 'verification.json').read_text()
                report = json.loads(text)
                self.assertEqual(report['failureStages'], [failing])
                self.assertNotIn('private', text)
                self.assertFalse(report['verified'])
                if failing != 'capture': self.assertTrue(report['contractMatched'])

    def fixture(self):
        frame = {'id': 1, 'type': 'register_push_token', 'ts': '2026-10-16T00:00:00Z',
                 'payload': {'platform': 'fcm', 'token': 'recorded-push-device-address', 'device_name': 'phone'}}
        return {'format': 'pyrycode.mobile-suite', 'version': 1, 'scenario': 'scenario',
                'inputs': {'arguments': {}, 'clockReads': [], 'hosts': []}, 'identities': [],
                'source': {'mobileRevision': 'a' * 40, 'daemonRevision': 'b' * 40,
                           'daemonBinarySha256': 'c' * 64, 'testApkSha256': 'd' * 64,
                           'claudeVersion': 'test producer', 'capturedAt': '2026-10-16T00:00:00Z'},
                'steps': [{'direction': 'client', 'owner': 'phone', 'message': frame,
                           'delayMs': 0, 'accepted': True, 'detail': {}}]}

    def test_normalized_push_address_is_the_only_allowed_token_field(self):
        document = self.fixture()
        suite.validate(document)
        for mutation in ['actual-address', 'wrong-type', 'extra-secret']:
            invalid = copy.deepcopy(document)
            frame = invalid['steps'][0]['message']
            if mutation == 'actual-address': frame['payload']['token'] = 'private routing value'
            if mutation == 'wrong-type': frame['type'] = 'send_message'
            if mutation == 'extra-secret': frame['payload']['authorization'] = 'private'
            with self.assertRaises(ValueError): suite.validate(invalid)

    def test_missing_metadata_invalid_reply_and_empty_run_are_rejected(self):
        for mutate in [lambda x: x.pop('source'), lambda x: x['steps'].clear(),
                       lambda x: x['steps'][0]['message'].update(in_reply_to=99)]:
            invalid = self.fixture()
            mutate(invalid)
            with self.assertRaises(ValueError): suite.validate(invalid)
        for text in ['OK (0 tests)', 'Tests run: 1, Failures: 0', 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -3']:
            with self.assertRaises(ValueError): suite.executed_one(text.encode())
        suite.executed_one(b'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)')

    def test_contract_comparison_retains_payload_and_action_order(self):
        original = self.fixture()
        changed = copy.deepcopy(original)
        changed['steps'][0]['message']['payload']['device_name'] = 'other phone'
        self.assertNotEqual(suite.contract(original), suite.contract(changed))
        changed = copy.deepcopy(original)
        changed['steps'][0]['delayMs'] = 123
        self.assertEqual(suite.contract(original), suite.contract(changed))

    def test_controlled_file_clocks_rebind_even_when_they_cross_upload_chunks(self):
        import base64
        import hashlib
        def document(stamp, suffix=b'z'):
            result = self.fixture()
            result['inputs']['clockReads'] = [stamp]
            data = b'aaaaaaa' + str(stamp).encode() + suffix
            parts = [data[:10], data[10:]]
            result['steps'] = []
            for index, part in enumerate(parts):
                payload = {'conversation_id': 'chat', 'attachment_id': 'upload', 'index': index, 'total_chunks': 2,
                           'filename': 'file-' + str(stamp), 'mime_type': 'text/plain', 'size': len(data),
                           'sha256': hashlib.sha256(data).hexdigest(), 'data': base64.b64encode(part).decode()}
                result['steps'].append({'direction': 'client', 'owner': 'phone', 'delayMs': 0, 'accepted': True,
                                        'detail': {}, 'message': {'id': index + 1, 'ts': 'time', 'type': 'attachment_chunk', 'payload': payload}})
            return result
        first, second = document(1760611309123), document(1760611409456)
        self.assertEqual(suite.contract(first), suite.contract(second))
        self.assertNotEqual(suite.contract(first), suite.contract(document(1760611409456, b'x')))

    def test_payloadless_debug_requests_remain_distinct_from_empty_objects(self):
        document = self.fixture()
        frame = document['steps'][0]['message']
        frame['type'] = 'request_debug_bundle'
        frame.pop('payload')
        suite.validate(document)
        absent = suite.contract(document)
        frame['payload'] = None
        suite.validate(document)
        self.assertEqual(absent, suite.contract(document))
        frame['payload'] = {}
        self.assertNotEqual(absent, suite.contract(document))
        document['producerIdentityMode'] = 'wildcard'
        with self.assertRaises(ValueError): suite.validate(document)

    def test_independent_connection_and_setup_query_order_can_commute(self):
        original = self.fixture()
        connect = {'direction': 'connect', 'owner': 'peer/host0/1', 'delayMs': 0, 'accepted': True, 'detail': {}}
        original['steps'].append(connect)
        changed = copy.deepcopy(original)
        changed['steps'].reverse()
        self.assertEqual(suite.contract(original), suite.contract(changed))
        original['steps'][0]['message']['type'] = 'send_message'
        changed = copy.deepcopy(original)
        changed['steps'].reverse()
        self.assertEqual(suite.contract(original), suite.contract(changed))
        original['steps'].append({**connect, 'direction': 'close'})
        changed = copy.deepcopy(original)
        changed['steps'][1:] = reversed(changed['steps'][1:])
        self.assertNotEqual(suite.contract(original), suite.contract(changed))

    def test_compressed_diagnostic_members_cannot_hide_a_credential(self):
        import base64
        import gzip
        document = self.fixture()
        document['steps'] = []
        def archive(data):
            payload = {'seq': 0, 'data': base64.b64encode(gzip.compress(data)).decode()}
            return {'direction': 'daemon', 'owner': 'phone', 'message': {'type': 'debug_bundle_chunk', 'payload': payload}}
        document['steps'].append(archive(b'controlled diagnostic'))
        suite.inspect_diagnostic_bytes(document, [b'controlled-private-value'])
        for data in [b'log controlled-private-value', b'log sk-ant-controlled-test-value']:
            document['steps'][0] = archive(data)
            with self.assertRaises(ValueError): suite.inspect_diagnostic_bytes(document, [b'controlled-private-value'])

    def test_only_a_redundant_reconnect_catalog_lookup_can_vary_in_fresh_contract(self):
        document = self.fixture()
        document['modelQueryNormalization'] = 'reconnect-catalog-race-v1'
        query = document['steps'][0]['message']
        query.update(type='request_history', payload={'conversation_id': 'chat'})
        catalog = {'direction': 'daemon', 'owner': 'phone', 'message': {'id': 2, 'type': 'model_list',
                   'ts': query['ts'], 'payload': {'conversation_id': 'chat', 'models': []}}, 'delayMs': 0, 'accepted': True, 'detail': {}}
        document['steps'].insert(0, catalog)
        changed = copy.deepcopy(document)
        lookup = copy.deepcopy(document['steps'][1])
        lookup['message'].update(id=3, type='request_model_list')
        changed['steps'].append(lookup)
        response = copy.deepcopy(catalog)
        response['message'].update(id=4, in_reply_to=3)
        changed['steps'].append(response)
        self.assertEqual(suite.contract(document), suite.contract(changed))
        changed['steps'][-2]['message']['payload']['conversation_id'] = 'other'
        self.assertNotEqual(suite.contract(document), suite.contract(changed))

    def test_poll_contract_varies_only_for_successful_recorded_settings_and_known_backfill_refusal(self):
        def document(kind, response, payload):
            result = self.fixture()
            result['steps'][0]['owner'] = 'phone/host/0'
            result['steps'][0]['message'].update(type=kind, payload={'conversation_id': 'chat'})
            reply = copy.deepcopy(result['steps'][0])
            reply.update(direction='daemon')
            reply['message'].update(id=2, type=response, payload=payload, in_reply_to=1)
            result['steps'].append(reply)
            result['settingsReadNormalization'] = 'recorded-snapshots-v1'
            result['backfillProbeNormalization'] = 'unsupported-legacy-probe-v1'
            return result
        for original in [document('request_session_settings', 'session_settings', {'permission_mode': 'default'}),
                         document('backfill_since', 'error', {'code': 'protocol.unknown_type'})]:
            repeated = copy.deepcopy(original)
            for step in copy.deepcopy(original['steps']):
                step['message']['id'] += 2
                if 'in_reply_to' in step['message']: step['message']['in_reply_to'] += 2
                repeated['steps'].append(step)
            self.assertEqual(suite.contract(original), suite.contract(repeated))
            if original['steps'][0]['message']['type'] == 'backfill_since':
                absent = copy.deepcopy(original)
                absent['steps'] = []
                self.assertEqual(suite.contract(original), suite.contract(absent))
            changed = copy.deepcopy(repeated)
            changed['steps'][-1]['message'].update(type='error', payload={'code': 'changed'})
            self.assertNotEqual(suite.contract(original), suite.contract(changed))

    def test_a_final_cancelled_settings_read_keeps_only_previously_observed_snapshots(self):
        document = self.fixture()
        document['settingsReadNormalization'] = 'recorded-snapshots-v1'
        request = document['steps'][0]
        request['owner'] = 'phone/host/0'
        request['message'].update(type='request_session_settings', payload={'conversation_id': 'chat'})
        response = copy.deepcopy(request)
        response['direction'] = 'daemon'
        response['message'].update(id=2, type='session_settings', payload={'effort': 'high'}, in_reply_to=1)
        document['steps'].append(response)
        cancelled = copy.deepcopy(request)
        cancelled['message']['id'] = 3
        document['steps'].extend([cancelled, {'direction': 'close', 'owner': request['owner']}])
        expected = copy.deepcopy(document)
        expected['steps'].remove(cancelled)
        self.assertEqual(suite.contract(expected), suite.contract(document))
        document['steps'].insert(-1, {**copy.deepcopy(cancelled), 'message': {**cancelled['message'], 'id': 4, 'type': 'send_message'}})
        self.assertTrue(suite.polling_groups(document) == {})

    def test_queries_may_cross_actions_but_full_query_counts_payloads_and_command_order_remain(self):
        original = self.fixture()
        command = copy.deepcopy(original['steps'][0])
        command['message'].update(id=2, type='send_message', payload={'text': 'hello'})
        original['steps'].append(command)
        moved = copy.deepcopy(original)
        moved['steps'].reverse()
        self.assertEqual(suite.contract(original), suite.contract(moved))
        moved['steps'].append(copy.deepcopy(original['steps'][0]))
        self.assertNotEqual(suite.contract(original), suite.contract(moved))
        original['steps'].append(copy.deepcopy(command))
        original['steps'][-1]['message']['payload']['text'] = 'next'
        moved = copy.deepcopy(original)
        moved['steps'][1:] = reversed(moved['steps'][1:])
        self.assertNotEqual(suite.contract(original), suite.contract(moved))

    def test_wrong_installed_apk_is_rejected_before_native_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'controlled built APK')
            recording = root / 'recording.json'
            recording.write_text(json.dumps(self.fixture()))
            installed = suite.digest(b'different installed APK').encode()
            with mock.patch.object(suite, 'ROOT', root), \
                 mock.patch.object(suite.legacy, 'adb', side_effect=[
                     b'package:/data/app/test/base.apk\n', installed + b'  /data/app/test/base.apk\n',
                 ]) as adb, mock.patch.object(suite, 'instrument') as instrument:
                with self.assertRaisesRegex(ValueError, '^Installed Android package differs from the built package$'):
                    suite.replay('emulator-5582', recording)
                self.assertEqual([
                    mock.call('emulator-5582', 'shell', 'pm', 'path', suite.PACKAGE),
                    mock.call('emulator-5582', 'shell', 'sha256sum', '/data/app/test/base.apk'),
                ], adb.call_args_list)
                instrument.assert_not_called()

    def test_old_recordings_compare_only_mutually_declared_polling_models(self):
        original = self.fixture()
        original['steps'][0]['owner'] = 'phone/host/0'
        original['steps'][0]['message'].update(type='request_session_settings', payload={'conversation_id': 'chat'})
        first = copy.deepcopy(original['steps'][0])
        first.update(direction='daemon')
        first['message'].update(id=2, type='session_settings', in_reply_to=1, payload={'effort': ''})
        repeated = copy.deepcopy(original['steps'][0])
        repeated['message']['id'] = 3
        second = copy.deepcopy(first)
        second['message'].update(id=4, in_reply_to=3)
        original['steps'].extend([first, repeated, second])
        fresh = copy.deepcopy(original)
        fresh['settingsReadNormalization'] = 'recorded-snapshots-v1'
        left, right, policy = suite.comparison_contracts(original, fresh)
        self.assertEqual(left, right)
        self.assertNotIn('settingsReadNormalization', policy)
        fresh['steps'] = fresh['steps'][:2]
        left, right, policy = suite.comparison_contracts(original, fresh)
        self.assertNotEqual(left, right)
        self.assertNotIn('settingsReadNormalization', original)

    def test_explicit_suite_policy_can_qualify_older_captures_without_rewriting_them(self):
        original = self.fixture()
        original['steps'][0]['owner'] = 'phone/host/0'
        original['steps'][0]['message'].update(type='request_session_settings', payload={'conversation_id':'chat'})
        reply = copy.deepcopy(original['steps'][0]);reply['direction']='daemon'
        reply['message'].update(id=2,type='session_settings',payload={'effort':'high'},in_reply_to=1)
        original['steps'].append(reply)
        fresh=copy.deepcopy(original)
        for step in copy.deepcopy(original['steps']):
            step['message']['id'] += 2
            if 'in_reply_to' in step['message']:step['message']['in_reply_to'] += 2
            fresh['steps'].append(step)
        before=copy.deepcopy(original)
        policy={'version':1,'modelQueryNormalization':'reconnect-catalog-race-v1',
                'settingsReadNormalization':'recorded-snapshots-v1','backfillProbeNormalization':'unsupported-legacy-probe-v1'}
        left,right,declared=suite.comparison_contracts(original,fresh,policy)
        self.assertEqual(left,right)
        self.assertEqual(original,before)
        self.assertEqual(declared['settingsReadNormalization'],'recorded-snapshots-v1')
        changed=copy.deepcopy(fresh);changed['steps'][0]['message']['payload']['conversation_id']='other'
        self.assertNotEqual(*suite.comparison_contracts(original,changed,policy)[:2])
        for invalid in [{**policy,'version':2},{**policy,'ignoreCommands':True},
                        {**policy,'settingsReadNormalization':'accept-anything'}]:
            with self.assertRaises(ValueError):suite.comparison_contracts(original,fresh,invalid)

    def cleared_fixture(self):
        doc = self.fixture()
        clear = copy.deepcopy(doc['steps'][0])
        clear.update(owner='phone/host/0', direction='daemon')
        clear['message'].update(id=10, type='session_settings', payload={}, conversation_id='chat', session_state_cleared=True)
        close = {'owner':clear['owner'],'direction':'close','delayMs':0,'accepted':True,'detail':{}}
        ask = copy.deepcopy(doc['steps'][0])
        ask.update(owner=clear['owner'],direction='client')
        ask['message'].update(id=20,type='request_session_settings',payload={'conversation_id':'chat'})
        doc['steps']=[clear,close]
        return doc, ask

    def version_two(self):
        return {'version':2,'modelQueryNormalization':'reconnect-catalog-race-v1',
                'settingsReadNormalization':'recorded-snapshots-v2','backfillProbeNormalization':'unsupported-legacy-probe-v1'}

    def test_v2_cancels_only_an_unanswered_poll_after_its_own_cleared_state(self):
        original,ask=self.cleared_fixture();fresh=copy.deepcopy(original);fresh['steps'].insert(1,ask)
        before=copy.deepcopy(fresh)
        self.assertEqual(*suite.comparison_contracts(original,fresh,self.version_two())[:2])
        self.assertEqual(before,fresh)
        v1={**self.version_two(),'version':1,'settingsReadNormalization':'recorded-snapshots-v1'}
        self.assertNotEqual(*suite.comparison_contracts(original,fresh,v1)[:2])

    def test_v2_keeps_other_owners_conversations_and_complete_payloads_strict(self):
        original,ask=self.cleared_fixture()
        for changed in [{**ask,'owner':'phone/other/0'},
                        {**ask,'message':{**ask['message'],'payload':{'conversation_id':'other'}}},
                        {**ask,'message':{**ask['message'],'payload':{'conversation_id':'chat','extra':True}}},
                        {**ask,'accepted':False}]:
            fresh=copy.deepcopy(original);fresh['steps'].insert(1,changed)
            self.assertNotEqual(*suite.comparison_contracts(original,fresh,self.version_two())[:2])

    def test_v2_requires_a_same_connection_end_and_no_fresh_state_or_intervening_write(self):
        original,ask=self.cleared_fixture()
        write=copy.deepcopy(ask);write['message'].update(id=21,type='set_session_settings')
        snapshot=copy.deepcopy(original['steps'][0]);snapshot['message'].update(id=21,payload={'effort':'high'},session_state_cleared=False)
        for suffix in [[],[write,original['steps'][-1]],[snapshot,original['steps'][-1]],
                       [{**original['steps'][-1],'owner':'phone/other/0'}]]:
            before=copy.deepcopy(original);before['steps']=[original['steps'][0]]+suffix
            fresh=copy.deepcopy(before);fresh['steps'].insert(1,ask)
            self.assertNotEqual(*suite.comparison_contracts(before,fresh,self.version_two())[:2])

    def test_v2_cannot_move_a_cancelled_poll_before_its_clear_or_after_its_end(self):
        original,ask=self.cleared_fixture()
        for rows in [[ask]+original['steps'],original['steps']+[ask]]:
            fresh=copy.deepcopy(original);fresh['steps']=rows
            self.assertNotEqual(*suite.comparison_contracts(original,fresh,self.version_two())[:2])


if __name__ == '__main__':
    unittest.main()
