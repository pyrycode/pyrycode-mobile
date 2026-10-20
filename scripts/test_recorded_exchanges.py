import argparse
import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('recorded_exchanges', Path(__file__).with_name('recorded-exchanges.py'))
recorded = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recorded)


def exchange(*requests):
    return {'wire': [{'direction': 'client', 'message': {'type': kind, 'payload': payload}} for kind, payload in requests]}


class RecordingContractTest(unittest.TestCase):
    def test_verification_retains_private_candidate_and_runs_both_replays_on_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'verification'
            args = argparse.Namespace(serial='owned', fixture=recorded.ASSET, output=output, pyry='isolated')
            fresh = json.loads(recorded.ASSET.read_text())
            fresh['wire'].append({'direction': 'client', 'message': {
                'id': 999999, 'ts': 'timestamp', 'type': 'request_claude_usage', 'payload': {}}})
            def capture(serial, candidate, pyry, **kwargs):
                recorded.private_write(candidate, json.dumps(fresh).encode())
                return {'executed': 1, 'failed': 0, 'skipped': 0}
            with mock.patch.object(recorded, 'capture', side_effect=capture), \
                 mock.patch.object(recorded, 'replay', side_effect=[RuntimeError('private detail'), {'executed': 1, 'failed': 0, 'skipped': 0}]) as replay:
                with self.assertRaises(RuntimeError): recorded.verify(args)
            result = json.loads((output / 'verification.json').read_text())
            self.assertEqual(['committedReplay', 'comparison'], result['failureStages'])
            self.assertEqual(2, replay.call_count)
            self.assertEqual(1, result['freshReplay']['executed'])
            self.assertFalse(result['comparison']['matched'])
            self.assertEqual(fresh, json.loads((output / 'candidate.json').read_text()))
            self.assertEqual(0o700, output.stat().st_mode & 0o777)
            for path in output.rglob('*'):
                if path.is_file(): self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertNotIn('private detail', (output / 'verification.json').read_text())
            self.assertFalse((output / 'receipt.json').exists())
            with self.assertRaises(FileExistsError): recorded.verify(args)

    def test_capture_failure_keeps_later_stages_unexecuted(self):
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(serial='owned', fixture=recorded.ASSET,
                                      output=Path(directory) / 'verification', pyry='isolated')
            with mock.patch.object(recorded, 'capture', side_effect=RuntimeError('private')), \
                 mock.patch.object(recorded, 'replay') as replay:
                with self.assertRaises(RuntimeError): recorded.verify(args)
            result = json.loads((args.output / 'verification.json').read_text())
            self.assertEqual(['capture'], result['failureStages'])
            for name in ['committedReplay', 'freshReplay', 'comparison']:
                self.assertEqual('unexecuted', result[name]['status'])
            replay.assert_not_called()

    def test_fresh_replay_failure_and_matching_contract_are_independent(self):
        for failed in [False, True]:
            with self.subTest(failed=failed), tempfile.TemporaryDirectory() as directory:
                args = argparse.Namespace(serial='owned', fixture=recorded.ASSET,
                                          output=Path(directory) / 'verification', pyry='isolated')
                counts = {'executed': 1, 'failed': 0, 'skipped': 0}
                def capture(serial, candidate, pyry, **kwargs):
                    recorded.private_write(candidate, recorded.ASSET.read_bytes())
                    return counts
                with mock.patch.object(recorded, 'capture', side_effect=capture), \
                     mock.patch.object(recorded, 'replay', side_effect=[counts, RuntimeError('private') if failed else counts]):
                    if failed:
                        with self.assertRaises(RuntimeError): recorded.verify(args)
                    else: recorded.verify(args)
                result = json.loads((args.output / 'verification.json').read_text())
                self.assertEqual(['freshReplay'] if failed else [], result['failureStages'])
                self.assertTrue(result['comparison']['matched'])
                self.assertEqual(not failed, (args.output / 'receipt.json').exists())

    def test_wrong_installed_package_prevents_native_replay(self):
        with mock.patch.object(recorded, 'adb', return_value=b'package:/unexpected.apk'), \
             mock.patch.object(recorded, 'execute') as execute:
            with self.assertRaises(RuntimeError): recorded.replay('owned', recorded.ASSET)
            execute.assert_not_called()

    def test_raw_native_reports_retain_pass_failure_skip_and_zero_execution(self):
        for code, summary, counts, passed in [
            (b'0', b'OK (1 test)', (1, 0, 0), True),
            (b'-2', b'FAILURES!!!', (1, 1, 0), False),
            (b'-3', b'OK (1 test)', (0, 0, 1), False),
            (None, b'no tests', (0, 0, 0), False),
        ]:
            with self.subTest(code=code), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                raw = (b'INSTRUMENTATION_STATUS_CODE: ' + code + b'\n' if code is not None else b'') + summary
                def start(*args, **kwargs):
                    kwargs['stdout'].write(raw)
                    kwargs['stdout'].flush()
                    return mock.Mock(poll=mock.Mock(return_value=0), returncode=0)
                with mock.patch.object(recorded, 'ROOT', root), mock.patch.object(recorded.subprocess, 'Popen', side_effect=start):
                    if passed: recorded.execute('owned', {}, evidence=root)
                    else:
                        with self.assertRaises(RuntimeError): recorded.execute('owned', {}, evidence=root)
                report = json.loads((root / 'native-counts.json').read_text())
                self.assertEqual(counts, tuple(report[k] for k in ['executed', 'failed', 'skipped']))
                self.assertEqual(raw, (root / 'instrumentation-private.log').read_bytes())

    def test_managed_permission_verification_releases_custody_after_each_failure(self):
        for failure in ['comparison', 'install', 'command']:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                args = argparse.Namespace(fixture=recorded.ASSET, output=Path(directory) / 'managed', pyry='isolated')
                gate = mock.MagicMock()
                gate.live_claude_environment.return_value = {}
                gate.build_apks.return_value = 0
                gate.managed_avd.return_value = ('avds', 'owned')
                gate.boot_emulator.return_value = ('emulator-owned', 'owned-process')
                gate.install_once.return_value = None if failure == 'install' else []
                gate.run_device_command.return_value.returncode = 1
                if failure == 'command': gate.run_device_command.side_effect = RuntimeError('command failed')
                with mock.patch.object(recorded, 'run', return_value=b''):
                    if failure == 'comparison': self.assertEqual(1, recorded.managed_verify(args, gate))
                    else:
                        with self.assertRaises(RuntimeError): recorded.managed_verify(args, gate)
                gate.check_selection.assert_called_once_with(recorded.ROOT, 'android', [
                    'ui', '--tests', recorded.CLASS + '#capturedPermissionReachesTheClientAndCompletes'])
                gate.stop_emulator.assert_called_once_with(mock.ANY, 'emulator-owned', 'owned-process')
                gate.device_hold.return_value.__exit__.assert_called_once()

    def test_allowance_is_a_strict_host_query_within_one_action(self):
        baseline = exchange(('list_conversations', {}), ('request_claude_usage', {}), ('send_message', {}))
        reordered = exchange(('request_claude_usage', {}), ('list_conversations', {}), ('send_message', {}))
        self.assertEqual(recorded.normalized_contract(baseline), recorded.normalized_contract(reordered))
        for requests in [
            [('list_conversations', {}), ('send_message', {})],
            [('list_conversations', {}), ('request_claude_usage', {}), ('request_claude_usage', {}), ('send_message', {})],
            [('list_conversations', {}), ('request_claude_usage', {'conversation_id': 'chat'}), ('send_message', {})],
            [('list_conversations', {}), ('send_message', {}), ('request_claude_usage', {})],
        ]:
            self.assertNotEqual(recorded.normalized_contract(baseline), recorded.normalized_contract(exchange(*requests)))

    def test_fresh_identities_and_query_order_preserve_contract(self):
        first = exchange(('request_context_usage', {'conversation_id': 'old'}), ('request_model_list', {}), ('send_message', {'conversation_id': 'old', 'message_id': 'm', 'text': 'controlled'}))
        second = exchange(('request_model_list', {}), ('request_context_usage', {'conversation_id': 'fresh'}), ('send_message', {'conversation_id': 'fresh', 'message_id': 'n', 'text': 'controlled'}))
        self.assertEqual(recorded.normalized_contract(first), recorded.normalized_contract(second))

    def test_changed_payload_choice_extra_field_or_recovery_fails(self):
        baseline = exchange(('send_message', {'text': 'controlled'}), ('modal_answer', {'option_id': 'allow_once'}))
        candidates = [
            exchange(('send_message', {'text': 'changed'}), ('modal_answer', {'option_id': 'allow_once'})),
            exchange(('send_message', {'text': 'controlled', 'extra': True}), ('modal_answer', {'option_id': 'allow_once'})),
            exchange(('send_message', {'text': 'controlled'}), ('modal_answer', {'option_id': 'reject_once'})),
            exchange(('send_message', {'text': 'controlled'}), ('thread_retry', {}), ('modal_answer', {'option_id': 'allow_once'})),
        ]
        for candidate in candidates:
            self.assertNotEqual(recorded.normalized_contract(baseline), recorded.normalized_contract(candidate))

    def test_query_cannot_cross_a_command(self):
        first = exchange(('request_model_list', {}), ('send_message', {}))
        second = exchange(('send_message', {}), ('request_model_list', {}))
        self.assertNotEqual(recorded.normalized_contract(first), recorded.normalized_contract(second))

    def test_candidate_validation_rejects_bad_correlation_and_handshake(self):
        document = {
            'format': 'pyrycode.mobile-exchange', 'version': 1,
            'source': {'mobileRevision': 'a' * 40, 'daemonRevision': 'b' * 40,
                       'daemonBinarySha256': 'c' * 64, 'testApkSha256': 'd' * 64,
                       'claudeVersion': 'test', 'capturedAt': '2026-10-16T00:00:00Z'},
            'capabilities': ['interactive'],
            'scenario': {'prompt': 'controlled', 'expectedReply': 'complete', 'allowLabel': 'Allow once'},
            'wire': [{'direction': 'client', 'message': {'id': 1, 'ts': 'timestamp', 'type': 'list_conversations', 'payload': {}}},
                     {'direction': 'daemon', 'message': {'id': 2, 'ts': 'timestamp', 'type': 'conversations', 'payload': {}, 'in_reply_to': 1}}],
        }
        recorded.validate_recording(document)
        for change in ['correlation', 'handshake', 'duplicate']:
            invalid = copy.deepcopy(document)
            if change == 'correlation': invalid['wire'][1]['message']['in_reply_to'] = 99
            if change == 'handshake': invalid['wire'][0]['message']['type'] = 'hello'
            if change == 'duplicate': invalid['wire'].append(invalid['wire'][0])
            with self.assertRaises(ValueError): recorded.validate_recording(invalid)

    def test_unauthenticated_capture_fails_without_writing_a_candidate(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(os.environ, {}, clear=True):
            candidate = Path(directory) / 'candidate.json'
            with self.assertRaisesRegex(RuntimeError, 'configured Claude test login'):
                recorded.capture('unused-device', candidate, 'unused-daemon')
            self.assertFalse(candidate.exists())

    def test_private_candidates_never_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'candidate.json'
            recorded.private_write(path, b'original')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError): recorded.private_write(path, b'replacement')
            self.assertEqual(path.read_bytes(), b'original')

    def test_credential_fields_and_values_are_rejected(self):
        for candidate in [{'payload': {'token': 'opaque'}}, {'wire': [{'text': 'sk-ant-not-a-real-key'}]}]:
            with self.assertRaises(ValueError): recorded.require_safe(candidate)


if __name__ == '__main__': unittest.main()
