#!/usr/bin/env python3
"""Private, bounded metadata export for one harness run. Never copy raw logs or homes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

LIMIT = 2 * 1024 * 1024
MAX_FILES = 128
UUID = re.compile(r'^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$')
SAFE = re.compile(r'^(?:true|false|unknown|[0-9]{1,19}|[0-9a-f]{64}|[0-9T:.Z+\-]{1,40})$')
METHOD = re.compile(r'^(?:de\.pyryco\.mobile\.e2e\.InteractiveStreamE2ETest#)?interactiveTurn_[A-Za-z0-9_]{1,160}$')
ENUMS = frozenset(('INFO', 'WARN', 'ERROR', 'DEBUG', 'Connected', 'Connecting', 'Offline', 'Idle',
    'DaemonAbsent', 'PairingRejected', 'UpdateRequired', 'Reconnecting', 'before_cleanup', 'chats_created',
    'before_send', 'after_send', 'turn_completed', 'start', 'finish', 'failure', 'interval'))
EVENTS = frozenset(('pyrycode starting', 'pyrycode stopped', 'spawning claude', 'claude exited',
    'relay: connecting', 'relay: conn established', 'transport: connected', 'transport: disconnected',
    'relay: v2 handshake accept', 'relay: v2 peer close teardown', 'relay: send_message enqueued',
    'relay: v2 attachment stored', 'relay: v2 attachment served', 'msgqueue: delivery failed, will retry',
    'control: channel.post accepted', 'control: channel.post not carried forward',
    'relay: stream-turn delivery held; conversation busy', 'sessions: in-band settings command not delivered',
    'streamsup: turn refused; new_session rotation in flight', 'thread shadow cache stopped'))
FIELDS = frozenset(('time', 'level', 'conversation_id', 'session_id', 'message_id', 'attachment_id',
    'server_id', 'conn_id', 'session', 'queued_msg_id', 'attachment_count', 'queued_at', 'uptime', 'ts', 'accepted_at', 'occurred_at', 'id', 'turn_id', 'accepted_entry_id', 'delivery_entry_id', 'pid', 'size', 'bytes', 'count', 'attempt', 'code', 'status', 'retryable', 'duration_ms'))
PHONE_FIELDS = frozenset(('method', 'stage', 'start_ms', 'finish_ms', 'utc_ms', 'chat_x', 'chat_y', 'phone_ready', 'phone_relay',
    'phone_selected', 'peer_frames', 'peer_delivery', 'peer_terminal', 'peer_modals', 'peer_open', 'phase'))
HISTORY_TYPES = frozenset(('send_accepted', 'send_delivered', 'send_failed', 'turn_end', 'message',
                          'main_turn', 'main_turn_opened', 'session_started', 'session_start', 'turn_started'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def regular(path):
    # Reject symlink ancestors too; open with O_NOFOLLOW protects the final component from a swap.
    return not any(p.is_symlink() for p in (path, *path.parents)) and path.is_file()


def read(path, observations, label, tail=False):
    try:
        if not regular(path):
            raise OSError()
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise OSError()
            size = os.fstat(stream.fileno()).st_size
            data = stream.read(LIMIT + 1)
            layout = 'prefix'
            if tail and size > LIMIT:
                head = data[:LIMIT // 2]
                stream.seek(-LIMIT // 2, os.SEEK_END)
                ending = stream.read(LIMIT // 2)
                data = head[:head.rfind(b'\n') + 1] + ending[ending.find(b'\n') + 1:]
                layout = 'head_tail'
        observations[label] = {'state': 'available', 'truncated': size > LIMIT, 'layout': layout,
                               'source_bytes': size, 'read_sha256': digest(data[:LIMIT])}
        return data[:LIMIT]
    except OSError:
        observations[label] = {'state': 'unknown', 'truncated': False}
        return b''


def write(path, data):
    encoded = data if isinstance(data, bytes) else data.encode()
    if len(encoded) > LIMIT:
        raise ValueError('output_limit')
    fd, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def json_bytes(value):
    return (json.dumps(value, sort_keys=True) + '\n').encode()


def safe_fields(record, fields):
    return {k: v for k, v in record.items() if k in fields and
            (isinstance(v, (int, bool)) or isinstance(v, str) and (SAFE.fullmatch(v) or UUID.fullmatch(v) or v in ENUMS or
                k == 'conn_id' and re.fullmatch(r'[0-9a-f]{8,64}', v) or
                k == 'uptime' and re.fullmatch(r'(?:[0-9]{1,8}(?:\.[0-9]{1,9})?[hms]){1,3}', v)))}


def log_records(data, phone=False):
    records = []
    for line in data.decode(errors='replace').splitlines():
        if len(line) > 8192:
            records.append({'oversized_record_sha256': digest(line.encode())})
            continue
        try:
            if phone:
                if 'E2EEvidence: ' not in line:
                    continue
                line = line.partition('E2EEvidence: ')[2]
            pairs = dict(part.split('=', 1) for part in shlex.split(line) if '=' in part)
        except ValueError:
            continue
        if phone:
            record = safe_fields(pairs, PHONE_FIELDS)
            if METHOD.fullmatch(pairs.get('method', '')):
                record['method'] = pairs['method']
        else:
            record = safe_fields(pairs, FIELDS)
            message = pairs.get('msg', '')
            if message in EVENTS:
                record['event'] = message
            elif message:
                record['event_sha256'] = digest(message.encode())
            for key in ('err', 'error', 'reason'):
                if key in pairs:
                    error = pairs[key].lower()
                    record['error_class'] = next((kind for kind in ('timeout', 'canceled', 'eof', 'closed', 'refused', 'signal')
                                                  if kind in error), 'unknown')
                    record['error_sha256'] = digest(error.encode())
        if record:
            records.append(record)
    return records


def command(args, cwd=None):
    try:
        result = subprocess.run(args, cwd=cwd, capture_output=True, timeout=5)
        return result.stdout.decode().strip() if result.returncode == 0 else 'unknown'
    except (OSError, subprocess.TimeoutExpired):
        return 'unknown'


def binary_hash(path):
    try:
        if not regular(path) or path.stat().st_size > 256 * 1024 * 1024:
            return 'unknown'
        value = hashlib.sha256()
        with path.open('rb') as stream:
            for chunk in iter(lambda: stream.read(65536), b''):
                value.update(chunk)
        return value.hexdigest()
    except OSError:
        return 'unknown'


def capture(args):
    observations, artifacts = {}, {}
    for name in ('daemon.log', 'daemon-b.log', 'daemon-bypass.log', 'daemon-answer.log', 'relay.log'):
        records = log_records(read(args.work / name, observations, name, tail=True))
        artifacts[name + '.jsonl'] = b''.join(json_bytes(row) for row in records)
    results = args.repo / 'app/build/outputs/androidTest-results'
    results = results / ('connected/debug' if args.device == 'connected' else 'managedDevice/debug/' + args.device)
    records, cases = [], []
    paths = sorted(results.rglob('*'))[:MAX_FILES] if results.is_dir() else []
    for index, path in enumerate(paths):
        if not regular(path) or path.stat().st_mtime_ns < args.started:
            continue
        if path.name.startswith('logcat-') and path.suffix == '.txt':
            records.extend(log_records(read(path, observations, 'logcat/' + str(index), tail=True), phone=True))
        elif path.name.startswith('TEST-') and path.suffix == '.xml':
            try:
                tree = ET.fromstring(read(path, observations, 'xml/' + str(index)))
                for case in tree.iter('testcase'):
                    name = case.get('name', '')
                    if re.fullmatch(r'[A-Za-z0-9_]{1,200}', name):
                        cases.append({'method': name, 'outcome': 'skipped' if case.find('skipped') is not None else
                                      'failed' if case.find('failure') is not None or case.find('error') is not None else 'passed',
                                      'seconds': case.get('time') if re.fullmatch(r'[0-9.]{1,20}', case.get('time', '')) else 'unknown'})
            except ET.ParseError:
                observations['xml/' + str(index)]['state'] = 'unknown'
    observations['phone_peer'] = {'state': 'available' if records else 'unknown'}
    observations['method_intervals'] = {'state': 'available' if any(r.get('stage') == 'interval' for r in records) else 'unknown'}
    artifacts['phone-peer.jsonl'] = b''.join(json_bytes(row) for row in records)
    artifacts['methods.json'] = json_bytes({'cases': cases, **{outcome: sum(c['outcome'] == outcome for c in cases)
                                      for outcome in ('passed', 'failed', 'skipped')},
                                      'executed': sum(c['outcome'] != 'skipped' for c in cases)})
    targets = {v for row in records for k, v in row.items() if k in ('chat_x', 'chat_y') and UUID.fullmatch(v)}
    histories = []
    if re.fullmatch(r'e2e-(?:auto-[a-z0-9]+|emulator|live)', args.instance):
        for target in sorted(targets):
            folder = args.home / '.pyry' / args.instance / 'conversations' / target / 'history'
            files = sorted(folder.glob('*.jsonl'))[:32]
            observations['history/' + target] = {'state': 'available' if files else 'unknown'}
            for index, path in enumerate(files):
                for line in read(path, observations, 'history/' + target + '/' + str(index), tail=True).splitlines()[:10000]:
                    try:
                        entry = json.loads(line)
                        kind = entry.get('type')
                        if kind not in HISTORY_TYPES:
                            continue
                        row = {'conversation_id': target, 'type': kind}
                        row.update(safe_fields(entry, FIELDS | {'timestamp'}))
                        if isinstance(entry.get('session'), dict):
                            row.update(safe_fields(entry['session'], {'session_id'}))
                        payload = entry.get('payload', {})
                        if isinstance(payload, dict):
                            row.update(safe_fields(payload, FIELDS))
                            ids = payload.get('attachment_ids')
                            if isinstance(ids, list):
                                row['attachment_ids'] = [i for i in ids[:32] if isinstance(i, str) and UUID.fullmatch(i)]
                            for key in ('outcome', 'terminal_reason', 'is_error', 'role'):
                                if payload.get(key) in ('success', 'completed', 'user', 'assistant', True, False):
                                    row[key] = payload[key]
                        histories.append(row)
                    except (ValueError, AttributeError, TypeError):
                        observations['history/' + target]['malformed'] = True
    if not targets:
        observations['target_history'] = {'state': 'unknown'}
    artifacts['history.jsonl'] = b''.join(json_bytes(row) for row in histories)
    sources = {}
    for label, folder in (('mobile', args.repo), ('daemon', Path(os.environ.get('PYRYCODE_SRC', '/nonexistent')))):
        revision = command(['git', 'rev-parse', 'HEAD'], folder)
        sources[label] = revision if re.fullmatch('[0-9a-f]{40}', revision) else 'unknown'
    binaries = {}
    for label, value in (('daemon', os.environ.get('PYRY_BIN', '')), ('claude', command(['which', 'claude']))):
        binaries[label] = binary_hash(Path(value)) if value else 'unknown'
    for path in sorted((args.repo / 'app/build/outputs/apk').rglob('*.apk'))[:4]:
        binaries['apk_' + str(len(binaries))] = binary_hash(path)
    source_files = {}
    for name in ('scripts/e2e-emulator.sh', 'scripts/e2e-evidence.py',
                 'app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt',
                 'app/src/androidTest/java/de/pyryco/mobile/e2e/FocusRecordListener.kt',
                 'app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveMethodIntervalRule.kt'):
        source_files[name] = binary_hash(args.repo / name)
    config = {'device': args.device, 'instance': args.instance, 'started_ns': args.started,
              'captured_ns': time.time_ns(), 'phase': args.phase, 'sources': sources, 'source_files': source_files, 'binaries': binaries}
    version = command(['claude', '--version'])
    config['claude_version'] = version if re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+ \(Claude Code\)', version) else 'unknown'
    daemon_info = command(['go', 'version', '-m', os.environ.get('PYRY_BIN', '')])
    revision = re.search(r'vcs.revision=([0-9a-f]{40})', daemon_info)
    config['daemon_binary_revision'] = revision[1] if revision else 'unknown'
    config_path = args.home / '.pyry/config.json'
    config_data = read(config_path, observations, 'daemon_config')
    config['daemon_config_sha256'] = digest(config_data) if config_data else 'unknown'
    try:
        daemon_config = json.loads(config_data)
        runner = daemon_config.get('interactive_runner')
        config['effective_runner'] = runner if runner in ('stream-json', 'tui') else 'default_or_unknown'
    except (ValueError, AttributeError):
        config['effective_runner'] = 'unknown'
    for key in ('LIVE', 'DETERMINISTIC', 'E2E_DISABLE_ANIMATIONS', 'E2E_APKS_BUILT', 'INTERACTIVE_RUNNER'):
        value = os.environ.get(key, '')
        config[key] = value if SAFE.fullmatch(value) or value == '' else 'unknown'
    selection = os.environ.get('E2E_CAPTURE_TARGET', '').split(',')
    config['selected_methods'] = [value for value in selection[:128] if METHOD.fullmatch(value)]
    artifacts['configuration.json'] = json_bytes(config)
    # Process metadata excludes command lines, which contain pairing/auth arguments.
    pids = os.environ.get('E2E_CAPTURE_PIDS', '')
    config_pids = [value for value in pids.split(',') if re.fullmatch(r'[0-9]{1,10}', value)]
    process = command(['ps', '-p', ','.join(config_pids), '-o', 'pid=,ppid=,stat=,etime=,time=,rss=']) if config_pids else 'unknown'
    process_data = json_bytes({'state': 'unknown' if process == 'unknown' else 'available', 'sample': process})
    artifacts['processes.json'] = process_data
    checkpoint = args.work / 'evidence/processes-before-teardown.json'
    artifacts['processes-before-teardown.json'] = process_data if args.phase == 'before-teardown' else read(checkpoint, observations, 'before_teardown_processes')
    stage = args.work / 'evidence'
    stage.mkdir(mode=0o700, exist_ok=True)
    if stage.is_symlink():
        raise OSError('symlink_stage')
    for name, data in artifacts.items():
        write(stage / name, data)
    manifest = {'status': args.status, 'phase': args.phase, 'export': 'pending', 'observations': observations,
                'artifacts': [{'name': name, 'sha256': digest(data), 'bytes': len(data)} for name, data in artifacts.items()]}
    write(stage / 'manifest.json', json_bytes(manifest))
    # A stable per-run destination is remembered only after a successful publication.
    marker = args.work / 'evidence-destination'
    args.root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if args.root.is_symlink() or args.root.stat().st_mode & 0o077:
        raise OSError('nonprivate_root')
    destination = Path(marker.read_text()) if marker.is_file() else Path(tempfile.mkdtemp(prefix='run-', dir=args.root))
    if destination.parent != args.root or destination.is_symlink():
        raise OSError('invalid_destination')
    # Publish manifest last; any partial copy remains explicitly incomplete.
    write(destination / 'manifest.json', json_bytes(manifest))
    for name, data in artifacts.items():
        write(destination / name, data)
    manifest['export'] = 'complete'
    write(destination / 'manifest.json', json_bytes(manifest))
    write(stage / 'manifest.json', json_bytes(manifest))
    write(marker, str(destination))
    print('e2e evidence: ' + str(destination))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('work', 'root', 'repo', 'home'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--instance', required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--status', type=int, required=True)
    parser.add_argument('--started', type=int, required=True)
    parser.add_argument('--phase', choices=('before-teardown', 'after-teardown'), required=True)
    args = parser.parse_args()
    for name in ("work", "root", "repo", "home"):
        path = getattr(args, name)
        if path.is_symlink():
            parser.error('symlink_' + name)
        setattr(args, name, path.resolve())
    import signal
    def deadline(signum, frame):
        raise TimeoutError()
    signal.signal(signal.SIGALRM, deadline)
    signal.alarm(30)
    try:
        capture(args)
    except (OSError, ValueError):
        print('evidence_export_unavailable; staging retained', file=__import__('sys').stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
