import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('suite', Path(__file__).with_name('recorded-mobile-suite.py'))
suite = importlib.util.module_from_spec(spec)
spec.loader.exec_module(suite)


class SuiteDriverTest(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()
