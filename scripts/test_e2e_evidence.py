from pathlib import Path
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('e2e-evidence.py')
HARNESS = Path(__file__).with_name('e2e-emulator.sh')
UUID = '07acd65b-f57e-4a6e-8a23-a18879b3a9bb'


class EvidenceRetentionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.work = self.tmp / 'work'
        self.work.mkdir()
        self.root = self.tmp / 'persistent'
        self.repo = self.tmp / 'repo'
        self.repo.mkdir()
        self.home = self.tmp / 'home'
        history = self.home / '.pyry/e2e-auto-test/conversations' / UUID / 'history'
        history.mkdir(parents=True)
        (history / 'segment.jsonl').write_text(json.dumps({'type': 'send_accepted', 'timestamp': '2026-10-11T15:03:46Z',
            'payload': {'conversation_id': UUID, 'text': 'secret-content', 'attachment_ids': [UUID]}}) + '\n')
        (self.work / 'daemon.log').write_text('time=2026-10-11T15:03:46Z level=INFO msg="relay: send_message enqueued" conversation_id=' + UUID + '\n')
        results = self.repo / 'app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd'
        results.mkdir(parents=True)
        (results / 'logcat-target.txt').write_text('10-11 E2EEvidence: method=interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes stage=before_cleanup utc_ms=1 chat_x=' + UUID + ' peer_frames=0 phone_ready=false\n')
        (results / 'TEST-target.xml').write_text('<testsuite><testcase classname="de.pyryco.mobile.e2e.InteractiveStreamE2ETest" name="target"><failure>original assertion secret-content</failure></testcase></testsuite>')

    def run_cleanup(self, code, root=None, cancel=False):
        script = HARNESS.read_text()
        capture = ''
        if 'capture_run_evidence() {' in script:
            start = script.index('capture_run_evidence() {')
            capture = script[start:script.index('\n}\n', start) + 3]
        cleanup = script[script.index('cleanup() {'):script.index('trap cleanup EXIT')]
        body = 'set -euo pipefail\nlog() { :; }\n' + capture + cleanup + '\ntrap cleanup EXIT\n'
        if cancel:
            body += "trap 'exit 130' INT\ntrap 'exit 143' TERM\nkill -" + ('INT' if code == 130 else 'TERM') + ' $$\n'
        else:
            body += 'exit ' + str(code)
        return subprocess.run(['bash', '-c', body], capture_output=True, text=True,
            env=dict(os.environ, REPO_ROOT=str(SCRIPT.parent.parent), E2E_CAPTURE_REPO=str(self.repo),
                     E2E_EVIDENCE_ROOT=str(root or self.root), WORK_DIR=str(self.work), DAEMON_HOME=str(self.home),
                     PYRY_NAME='e2e-auto-test', ISO_HOME='', WATCHER_PID='', DAEMON_PID='', RELAY_PID='',
                     DEVICE='pixel2Api33Atd', E2E_CAPTURE_STARTED_NS='0'))

    def archive(self):
        self.assertTrue(self.root.is_dir(), 'no persistent archive survived cleanup')
        runs = list(self.root.iterdir())
        self.assertEqual(1, len(runs))
        return runs[0]

    def test_failure_archive_survives_temporary_removal(self):
        result = self.run_cleanup(7)
        self.assertEqual(7, result.returncode, result.stderr)
        archive = self.archive()
        shutil.rmtree(self.work)
        manifest = json.loads((archive / 'manifest.json').read_text())
        self.assertEqual(7, manifest['status'])
        self.assertEqual('complete', manifest['export'])
        for item in manifest['artifacts']:
            self.assertEqual(item['sha256'], hashlib.sha256((archive / item['name']).read_bytes()).hexdigest())
        events = (archive / 'history.jsonl').read_text()
        self.assertIn('send_accepted', events)
        self.assertIn(UUID, events)
        self.assertIn('before_cleanup', (archive / 'phone-peer.jsonl').read_text())
        self.assertEqual(1, json.loads((archive / 'methods.json').read_text())['failed'])

    def test_cancellation_preserves_status_and_archive(self):
        for code in (130, 143):
            with self.subTest(code=code):
                root = self.tmp / str(code)
                self.work = self.tmp / ('work-' + str(code))
                self.work.mkdir()
                result = self.run_cleanup(code, root, cancel=True)
                self.assertEqual(code, result.returncode, result.stderr)
                manifest = json.loads(next(root.iterdir()).joinpath('manifest.json').read_text())
                self.assertEqual(code, manifest['status'])

    def test_export_failure_keeps_staging_and_original_status(self):
        blocked = self.tmp / 'not-a-directory'
        blocked.write_text('owned fixture')
        for code in (0, 7):
            result = self.run_cleanup(code, blocked)
            self.assertEqual(code, result.returncode, result.stderr)
            self.assertTrue((self.work / 'evidence/manifest.json').is_file())
            self.assertIn('evidence_export_unavailable', result.stderr)

    def test_runs_have_distinct_private_archives(self):
        self.run_cleanup(7)
        # A fresh run can reuse the daemon identity without overwriting evidence.
        self.work = self.tmp / 'second-work'
        self.work.mkdir()
        self.run_cleanup(7)
        self.assertTrue(self.root.is_dir(), 'no persistent archive survived cleanup')
        runs = list(self.root.iterdir())
        self.assertEqual(2, len(runs))
        for run in [self.root, *runs]:
            self.assertEqual(0o700, run.stat().st_mode & 0o777)
        for run in runs:
            for file in run.iterdir():
                self.assertEqual(0o600, file.stat().st_mode & 0o777)

    def test_only_allowlisted_metadata_is_exported(self):
        for name in ('pair.out', 'pair-peer.out', '.credentials.json', 'devices.json', 'config.json'):
            (self.work / name).write_text('secret-token secret-key')
        (self.work / 'daemon.log').write_text('msg="pair: minted a pairing locally" token=secret-token\n'
            'msg="relay: send_message enqueued" text=secret-content error="token secret-token" conversation_id=' + UUID + '\n'
            'msg="secret-content secret-token"\n')
        (self.work / 'daemon-b.log').symlink_to(self.work / 'pair.out')
        result = self.run_cleanup(7)
        self.assertEqual(7, result.returncode)
        contents = '\n'.join(p.read_text() for p in self.archive().iterdir())
        for secret in ('secret-token', 'secret-key', 'secret-content', 'original assertion'):
            self.assertNotIn(secret, contents)
        self.assertIn('relay: send_message enqueued', contents)

    def test_interval_markers_retain_both_timestamps(self):
        path = self.repo / 'app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd/logcat-target.txt'
        with path.open('a') as log:
            log.write('E2EEvidence: method=interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes stage=interval start_ms=1 finish_ms=2\n')
        result = self.run_cleanup(7)
        self.assertEqual(7, result.returncode)
        archive = self.archive()
        rows = [json.loads(line) for line in (archive / 'phone-peer.jsonl').read_text().splitlines()]
        interval = next(row for row in rows if row.get('stage') == 'interval')
        self.assertEqual(('1', '2'), (interval['start_ms'], interval['finish_ms']))
        manifest = json.loads((archive / 'manifest.json').read_text())
        self.assertEqual('available', manifest['observations']['method_intervals']['state'])

    def test_unavailable_and_bounded_inputs_are_explicit(self):
        (self.work / 'daemon.log').write_bytes(b'x' * (2 * 1024 * 1024 + 10))
        result = self.run_cleanup(7)
        self.assertEqual(7, result.returncode)
        manifest = json.loads((self.archive() / 'manifest.json').read_text())
        self.assertTrue(manifest['observations']['daemon.log']['truncated'])
        self.assertEqual('unknown', manifest['observations']['daemon-b.log']['state'])
        self.assertLess(sum(p.stat().st_size for p in self.archive().iterdir()), 4 * 1024 * 1024)


if __name__ == '__main__':
    unittest.main()
