import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

SPEC = importlib.util.spec_from_file_location('capture', Path(__file__).with_name('later-reply-host-evidence.py'))
capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capture)
CONV = '11111111-1111-4111-8111-111111111111'
SESSION = '22222222-2222-4222-8222-222222222222'
INSTANCE = 'e2e-auto-test'


class LaterReplyHostEvidenceTest(unittest.TestCase):
    def fixture(self, home):
        instance = home / '.pyry' / INSTANCE
        history = instance / 'conversations' / CONV / 'history'
        history.mkdir(parents=True)
        (instance / 'conversations.json').write_text(json.dumps({'conversations': [
            {'id': CONV, 'name': 'e2e1783-agent-test', 'current_session_id': SESSION},
            {'id': SESSION, 'name': 'unrelated', 'current_session_id': CONV},
        ]}))
        (history / 'segment-00000000000000000001.jsonl').write_text(json.dumps({
            'type': 'send_accepted', 'session': {'session_id': SESSION},
            'payload': {'conversation_id': CONV, 'text': 'Reply exactly later1783.'},
        }) + '\n')
        project = home / '.claude/projects/project'
        project.mkdir(parents=True)
        (project / (SESSION + '.jsonl')).write_text('{"type":"assistant"}\n')
        (project / (CONV + '.jsonl')).write_text('unrelated transcript')
        (home / '.claude.json').write_text('credential sentinel')
        (instance / 'devices.json').write_text('pairing sentinel')
        return history, project

    def test_old_owned_home_is_selected_instead_of_unrelated_new_home(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = root / 'owned'
            self.fixture(home)
            self.fixture(root / 'unrelated')
            os.utime(home, (1, 1))
            retained = capture.capture(home, INSTANCE, root / 'out', time.time_ns(), 0)
            manifest = json.loads((retained / 'manifest.json').read_text())
            self.assertEqual(CONV, manifest['conversation_id'])
            self.assertEqual([SESSION], manifest['session_ids'])
            self.assertEqual(2, len(manifest['files']))
            self.assertTrue(all('unrelated' not in f['source'] for f in manifest['files']))
            self.assertEqual(0o700, retained.stat().st_mode & 0o777)
            for p in retained.rglob('*'):
                if p.is_file():
                    self.assertEqual(0o600, p.stat().st_mode & 0o777)
                    self.assertNotIn('sentinel', p.read_text())

    def test_symlink_leaves_and_directories_never_supply_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            history, project = self.fixture(root / 'owned')
            secret = root / 'secret'
            secret.write_text('credential sentinel')
            (history / 'segment-00000000000000000002.jsonl').symlink_to(secret)
            (project / (SESSION + '.jsonl')).unlink()
            (project / (SESSION + '.jsonl')).symlink_to(secret)
            (root / 'owned/.claude/projects/linked').symlink_to(project, target_is_directory=True)
            retained = capture.capture(root / 'owned', INSTANCE, root / 'out', 1, 7)
            self.assertEqual(1, len(json.loads((retained / 'manifest.json').read_text())['files']))
            (root / 'owned/.pyry' / INSTANCE / 'conversations.json').unlink()
            (root / 'owned/.pyry' / INSTANCE / 'conversations.json').symlink_to(secret)
            with self.assertRaises(OSError):
                capture.capture(root / 'owned', INSTANCE, root / 'out', 1, 0)

    def test_symlink_history_ancestor_and_ambiguous_registry_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = root / 'owned'
            history, project = self.fixture(home)
            history.rename(root / 'outside-history')
            history.symlink_to(root / 'outside-history', target_is_directory=True)
            with self.assertRaises(ValueError):
                capture.capture(home, INSTANCE, root / 'out', 1, 0)
            registry = home / '.pyry' / INSTANCE / 'conversations.json'
            data = json.loads(registry.read_text())
            data['conversations'][1]['name'] = 'e2e1783-agent-other'
            registry.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                capture.capture(home, INSTANCE, root / 'out', 1, 0)

    def test_cleanup_retains_before_removal_and_preserves_result_when_capture_fails(self):
        script = Path(__file__).with_name('e2e-emulator.sh').read_text()
        cleanup = script[script.index('cleanup() {'):script.index('trap cleanup EXIT')]
        for code in (0, 7):
            for fail in (False, True):
                with self.subTest(code=code, fail=fail), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    # cleanup accepts only HOME directories minted under this exact prefix.
                    home = Path(tempfile.mkdtemp(prefix='pyry-e2e-ans.', dir='/tmp'))
                    self.fixture(home)
                    work = root / 'work'
                    work.mkdir()
                    out = root / 'out'
                    if fail:
                        out.write_text('invalid destination')
                    env = dict(os.environ, WORK_DIR=str(work), ANSWER_HOME=str(home),
                               DAEMON_HOME=str(home), PYRY_NAME=INSTANCE, LATER_REPLY_EVIDENCE_ROOT=str(out),
                               LATER_REPLY_CAPTURE_STARTED_NS='1', LIVE='1',
                               TEST_TARGET=capture.METHOD, REPO_ROOT=str(Path(__file__).resolve().parents[1]),
                               ISO_HOME='', WATCHER_PID='', DAEMON_PID='', RELAY_PID='')
                    result = subprocess.run(['bash', '-c', 'set -euo pipefail\nlog() { :; }\n' +
                                             cleanup + '\ntrap cleanup EXIT\nexit ' + str(code)],
                                            env=env, capture_output=True, text=True)
                    self.assertEqual(code, result.returncode, result.stderr)
                    self.assertFalse(home.exists())
                    self.assertEqual(code != 0, work.exists())
                    if not fail:
                        self.assertEqual(1, len(list(out.glob('*/manifest.json'))))
                        self.assertEqual(1, len(list(out.glob('*/history/*.jsonl'))))
                    else:
                        self.assertNotIn('invalid destination', result.stderr)


if __name__ == '__main__':
    unittest.main()
