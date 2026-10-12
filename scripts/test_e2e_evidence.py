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

    def test_late_interval_and_cleanup_survive_noisy_bounded_logs(self):
        path = self.repo / 'app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd/logcat-target.txt'
        ending = ('E2EEvidence: method=interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes stage=interval start_ms=1 finish_ms=2\n'
                  'E2EEvidence: method=interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes stage=before_cleanup chat_x=' + UUID + '\n')
        for noise in ('irrelevant\n' * 10001, 'x' * (2 * 1024 * 1024) + '\n'):
            with self.subTest(noise_bytes=len(noise)):
                path.write_text(noise + ending)
                result = self.run_cleanup(7)
                self.assertEqual(7, result.returncode)
                rows = [json.loads(line) for line in (self.archive() / 'phone-peer.jsonl').read_text().splitlines()]
                self.assertTrue(any(row.get('stage') == 'interval' for row in rows))
                self.assertTrue(any(row.get('stage') == 'before_cleanup' for row in rows))

    def test_unavailable_and_bounded_inputs_are_explicit(self):
        (self.work / 'daemon.log').write_bytes(b'x' * (2 * 1024 * 1024 + 10))
        result = self.run_cleanup(7)
        self.assertEqual(7, result.returncode)
        manifest = json.loads((self.archive() / 'manifest.json').read_text())
        self.assertTrue(manifest['observations']['daemon.log']['truncated'])
        self.assertEqual('unknown', manifest['observations']['daemon-b.log']['state'])
        self.assertLess(sum(p.stat().st_size for p in self.archive().iterdir()), 4 * 1024 * 1024)

    def report(self, trace):
        import xml.etree.ElementTree as ET
        path = self.repo / 'app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd/TEST-target.xml'
        suite = ET.Element('testsuite')
        case = ET.SubElement(suite, 'testcase', classname='de.pyryco.mobile.e2e.InteractiveStreamE2ETest', name='target')
        ET.SubElement(case, 'failure').text = trace
        path.write_bytes(ET.tostring(suite))

    def exceptions(self):
        return json.loads((self.archive() / 'exceptions.json').read_text())['cases'][0]['exceptions']

    def test_nested_stack_survives_cancellation_and_report_removal_without_messages(self):
        self.report('java.util.concurrent.ExecutionException: secret-token\n'
            '\tat androidx.test.espresso.Espresso.onIdle(Espresso.java:42)\n'
            '\tSuppressed: java.lang.IllegalStateException: secret-key\n'
            '\t\tat de.pyryco.mobile.e2e.InteractiveStreamE2ETest.evidence(InteractiveStreamE2ETest.kt:4749)\n'
            '\tCaused by: java.lang.RuntimeException: secret-content\n'
            '\t\tat androidx.compose.ui.platform.AndroidComposeView.measureAndLayout(AndroidComposeView.android.kt:100)\n'
            'Caused by: java.lang.IllegalArgumentException: performMeasureAndLayout called during measure layout\n'
            '\tat androidx.compose.ui.node.MeasureAndLayoutDelegate.measureAndLayout(MeasureAndLayoutDelegate.kt:400)\n')
        result = self.run_cleanup(143, cancel=True)
        self.assertEqual(143, result.returncode, result.stderr)
        shutil.rmtree(self.repo)
        shutil.rmtree(self.work)
        graph = self.exceptions()
        self.assertEqual('available', graph['state'])
        nodes = graph['nodes']
        self.assertEqual(['root', 'suppressed', 'cause', 'cause'], [n['relation'] for n in nodes])
        self.assertEqual([None, 0, 1, 0], [n['parent'] for n in nodes])
        self.assertEqual('java.lang.IllegalArgumentException', nodes[-1]['class'])
        self.assertEqual('androidx.compose.ui.node.MeasureAndLayoutDelegate.measureAndLayout', nodes[-1]['frames'][0]['symbol'])
        for secret in ('secret-token', 'secret-key', 'secret-content', 'performMeasureAndLayout called during measure layout'):
            self.assertNotIn(secret, '\n'.join(p.read_text() for p in self.archive().iterdir()))

    def test_empty_nested_stack_is_explicitly_unavailable(self):
        self.report('')
        self.assertEqual(7, self.run_cleanup(7).returncode)
        self.assertEqual('unavailable', self.exceptions()['state'])

    def test_stack_frames_and_nodes_are_bounded_with_explicit_markers(self):
        trace = 'java.lang.IllegalArgumentException: secret-token\n'
        trace += '\tat androidx.compose.ui.node.MeasureAndLayoutDelegate.measureAndLayout(MeasureAndLayoutDelegate.kt:400)\n' * 1000
        trace += 'Caused by: java.lang.IllegalStateException: secret-token\n' * 100
        self.report(trace)
        self.assertEqual(7, self.run_cleanup(7).returncode)
        graph = self.exceptions()
        self.assertTrue(graph['truncated'])
        self.assertLessEqual(len(graph['nodes']), 16)
        self.assertLessEqual(sum(len(n['frames']) for n in graph['nodes']), 128)

    def test_query_and_individually_timed_flow_records_are_retained(self):
        path = self.repo / 'app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd/logcat-target.txt'
        path.write_text('AttentionPillProbe: stage=peer_received query=waiting_label phase=before utc_ms=1 thread_id=7 looper=none virtual_ms=16\n'
            'AttentionPillProbe: stage=peer_received sample=attention phase=sample utc_ms=2 thread_id=7 looper=none virtual_ms=16 target_waiting=true waiting_count=1\n'
            'AttentionPillProbe: stage=peer_received query=waiting_label phase=failed utc_ms=3 thread_id=7 looper=none virtual_ms=32\n')
        self.assertEqual(7, self.run_cleanup(7).returncode)
        rows = [json.loads(line) for line in (self.archive() / 'attention-probe.jsonl').read_text().splitlines()]
        self.assertEqual(['before', 'sample', 'failed'], [row['phase'] for row in rows])
        self.assertEqual(['1', '2', '3'], [row['utc_ms'] for row in rows])
        self.assertEqual(['16', '16', '32'], [row['virtual_ms'] for row in rows])
        self.assertEqual('true', rows[1]['target_waiting'])
        self.assertEqual('waiting_label', rows[0]['query'])

    def test_probe_projection_rejects_credentials_and_wrong_field_types(self):
        path = self.repo / 'app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd/logcat-target.txt'
        path.write_text('AttentionPillProbe: stage=peer_received query=secret-token phase=before looper=secret-key thread_id=none virtual_ms=-1 token=secret-token target_waiting=123\n')
        self.assertEqual(7, self.run_cleanup(7).returncode)
        rows = [json.loads(line) for line in (self.archive() / 'attention-probe.jsonl').read_text().splitlines()]
        self.assertEqual([{'stage': 'peer_received', 'phase': 'before'}], rows)
        for secret in ('secret-token', 'secret-key'):
            self.assertNotIn(secret, '\n'.join(p.read_text() for p in self.archive().iterdir()))


if __name__ == '__main__':
    unittest.main()
