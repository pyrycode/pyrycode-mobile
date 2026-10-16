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


if __name__ == '__main__':
    unittest.main()
