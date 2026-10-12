"""Per-test session ownership, including failed teardown and shared fixtures."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('cleanup', Path(__file__).with_name('e2e_session_cleanup.py'))
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)

class SessionCleanupTest(unittest.TestCase):
    def setUp(self):
        self.rows = {'a': [{'id': 'shared', 'bootstrap': True}, {'id': 'fixture'}], 'b': []}
        self.removed = []
        self.fail = set()
        def request(path, body):
            if body['verb'] == 'sessions.list':
                return {'sessionsList': {'sessions': list(self.rows[path])}}
            sid = body['sessions']['id']
            self.assertNotIn('jsonlPolicy', body['sessions'])
            if sid in self.fail:
                raise RuntimeError('removal failed')
            self.removed.append((path, sid))
            self.rows[path] = [r for r in self.rows[path] if r['id'] != sid]
            return {'ok': True}
        self.owner = cleanup.TestSessions(lambda: list(self.rows), request)

    def test_removes_only_test_sessions_on_every_host(self):
        self.owner.begin()
        self.rows['a'].extend([{'id': 'new'}, {'id': 'rotated-bootstrap', 'bootstrap': True}])
        self.rows['b'].append({'id': 'other'})
        self.owner.end()
        self.assertEqual([('a', 'new'), ('b', 'other')], self.removed)
        self.assertEqual(['shared', 'fixture', 'rotated-bootstrap'], [r['id'] for r in self.rows['a']])

    def test_failure_still_cleans_other_sessions_and_is_reported(self):
        self.owner.begin()
        self.rows['a'].append({'id': 'bad'})
        self.rows['b'].append({'id': 'good'})
        self.fail.add('bad')
        with self.assertRaises(RuntimeError): self.owner.end()
        self.assertEqual([('b', 'good')], self.removed)
        with self.assertRaises(RuntimeError): self.owner.begin()

    def test_already_removed_session_and_new_host(self):
        self.owner.begin()
        self.rows['b'] = []
        self.rows['c'] = [{'id': 'new-host-child'}]
        self.owner.end()
        self.assertEqual([('c', 'new-host-child')], self.removed)

    def test_acknowledgement_without_removal_fails(self):
        owner = cleanup.TestSessions(lambda: ['a'], lambda path, body: {'sessionsList': {'sessions': list(self.rows['a'])}} if body['verb'] == 'sessions.list' else {'ok': True})
        owner.begin()
        self.rows['a'].append({'id': 'still-present'})
        with self.assertRaises(RuntimeError): owner.end()

    def test_end_without_begin_refuses_to_remove(self):
        with self.assertRaises(RuntimeError): self.owner.end()
        self.assertEqual([], self.removed)

if __name__ == '__main__': unittest.main()
