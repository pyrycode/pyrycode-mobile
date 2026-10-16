import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('recorded_exchanges', Path(__file__).with_name('recorded-exchanges.py'))
recorded = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recorded)


def exchange(*requests):
    return {'wire': [{'direction': 'client', 'message': {'type': kind, 'payload': payload}} for kind, payload in requests]}


class RecordingContractTest(unittest.TestCase):
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
