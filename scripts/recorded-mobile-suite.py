#!/usr/bin/env python3
"""Capture original native scenarios, replay offline, or explicitly check fresh Claude compatibility."""
import argparse
import base64
import datetime
import hashlib
import gzip
import io
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('permission_recording', ROOT / 'scripts/recorded-exchanges.py')
legacy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(legacy)
CLASS = 'de.pyryco.mobile.recording.RecordedMobileSuiteTest'
PACKAGE = legacy.PACKAGE
MANIFEST = ROOT / 'app/src/androidTest/assets/recorded/suite.json'
COUNTS = {'executed': 1, 'failed': 0, 'skipped': 0}
PUSH_ADDRESS = 'recorded-push-device-address'
QUERIES = legacy.QUERIES | {'backfill_since', 'register_push_token'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def validate(document):
    """Same bounds, sensitive-field scope and request correlation used by the Android reader."""
    try:
        require(document['format'] == 'pyrycode.mobile-suite' and type(document['version']) is int and document['version'] == 1,
                'Unsupported suite format')
        require(document.get('checkpointPolicy') in {None, 'test-observations-v1'}, 'Unsupported test checkpoint policy')
        require(re.fullmatch(r'[A-Za-z0-9_]{1,180}', document['scenario']), 'Invalid scenario')
        require(document.get('readReceiptNormalization') in {None, 'coalesced-visible-history-v2', 'coalesced-visible-history-v3'}, 'Unsupported receipt normalization')
        require(document.get('producerIdentityMode') in {None, 'literal', 'rebind'}, 'Unsupported producer identity mode')
        require(document.get('modelQueryNormalization') in {None, 'reconnect-catalog-race-v1'}, 'Unsupported model lookup normalization')
        require(document.get('settingsReadNormalization') in {None, 'recorded-snapshots-v1'}, 'Unsupported settings read normalization')
        require(document.get('backfillProbeNormalization') in {None, 'unsupported-legacy-probe-v1'}, 'Unsupported backfill probe normalization')
        source = document['source']
        for key in ['mobileRevision', 'daemonRevision']:
            require(re.fullmatch('[a-f0-9]{40}', source[key]), 'Invalid source revision')
        for key in ['daemonBinarySha256', 'testApkSha256'] + (['appApkSha256'] if 'appApkSha256' in source else []):
            require(re.fullmatch('[a-f0-9]{64}', source[key]), 'Invalid producer checksum')
        require(source['claudeVersion'].strip(), 'Missing producer version')
        datetime.datetime.strptime(source['capturedAt'], '%Y-%m-%dT%H:%M:%SZ')
        require(isinstance(document['inputs']['arguments'], dict), 'Invalid arguments')
        require(isinstance(document['inputs']['clockReads'], list) and isinstance(document['inputs']['hosts'], list), 'Invalid inputs')
        require(isinstance(document['identities'], list), 'Invalid identities')
        steps = document['steps']
        require(1 <= len(steps) <= 50000, 'Invalid suite bounds')
        require(len(json.dumps(document, ensure_ascii=False).encode()) <= 20_000_000, 'Invalid suite bounds')
        legacy.require_safe({key: value for key, value in document.items() if key != 'steps'})
        ids = {}
        for step in steps:
            direction, owner = step['direction'], step['owner']
            require(direction in {'client', 'daemon', 'connect', 'open', 'down', 'close', 'transport_down', 'minimum', 'inbound_end', 'external'},
                    'Invalid suite event')
            require(re.fullmatch(r'[A-Za-z0-9_./:-]{1,160}', owner), 'Invalid suite owner')
            require(type(step['delayMs']) is int and 0 <= step['delayMs'] <= 3600000, 'Invalid event timing')
            require(type(step['accepted']) is bool and isinstance(step['detail'], dict), 'Invalid event detail')
            legacy.require_safe({key: value for key, value in step.items() if key != 'message'})
            if direction == 'external':
                require(owner == 'android' and step['detail'] in [{'event': 'push-token'}, {'event': 'push-wake'}], 'Invalid external event')
            frame = step.get('message')
            if direction not in {'client', 'daemon'}:
                require(frame is None, 'Unexpected lifecycle payload')
                continue
            require(isinstance(frame, dict) and (frame.get('payload') is None or isinstance(frame['payload'], dict)) and frame['type'] and frame['ts'], 'Invalid frame')
            require(frame['type'] not in {'hello', 'hello_ack', 'rekey_request', 'multipart', 'fragment'}, 'Handshake rejected')
            bucket = ids.setdefault((owner, direction), set())
            require(type(frame['id']) is int and frame['id'] >= 0 and frame['id'] not in bucket, 'Invalid frame identity')
            bucket.add(frame['id'])
            reply = frame.get('in_reply_to')
            if reply is not None:
                require(direction == 'daemon' and reply in ids.get((owner, 'client'), set()), 'Invalid reply correlation')
            if direction == 'client' and frame['type'] == 'register_push_token':
                require(isinstance(frame.get('payload'), dict) and frame['payload'].get('token') == PUSH_ADDRESS, 'Invalid test push address')
                legacy.require_safe({**frame, 'payload': {k: v for k, v in frame['payload'].items() if k != 'token'}})
            else:
                legacy.require_safe(frame)
    except (KeyError, TypeError, AttributeError, OverflowError) as error:
        raise ValueError('Malformed recorded suite') from None


def executed_one(output):
    require(b'OK (1 test)' in output and b'FAILURES!!!' not in output and
            b'INSTRUMENTATION_STATUS_CODE: -3' not in output and b'INSTRUMENTATION_STATUS_CODE: -4' not in output,
            'Native scenario did not execute and pass exactly one test')


def instrument(serial, method, arguments):
    command = [os.environ.get('ADB', 'adb'), '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS + '#' + method]
    for key, value in arguments.items(): command += ['-e', key, str(value)]
    command += [PACKAGE + '.test/de.pyryco.mobile.e2e.E2eInstrumentationRunner']
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith('OP_') and key not in {'CLAUDE_CODE_OAUTH_TOKEN', 'ANTHROPIC_API_KEY', 'PYRY_LIVE_CLAUDE_TOKEN', 'PYRY_DEV_AGENTS_TOKEN'}}
    started = time.monotonic()
    result = subprocess.run(command, cwd=ROOT, env=environment, capture_output=True, timeout=1200)
    if result.returncode or b'OK (1 test)' not in result.stdout:
        diagnostic = ROOT / 'build/recorded-suite' / method / 'replay-private.log'
        diagnostic.parent.mkdir(parents=True, exist_ok=True)
        diagnostic.write_bytes(result.stdout + result.stderr)
        diagnostic.chmod(0o600)
    executed_one(result.stdout)
    require(result.returncode == 0, 'Instrumentation process failed')
    timing = re.search(rb'(?m)^Time: (\d+(?:\.\d+)?)\r?$', result.stdout)
    require(timing is not None, 'Native timing report missing')
    return {'seconds': round(time.monotonic() - started, 3), 'nativeSeconds': float(timing.group(1))}


def installed_apks(serial):
    result = {}
    for package, key, relative in [(PACKAGE, 'appApkSha256', 'debug/app-debug.apk'),
                                   (PACKAGE + '.test', 'testApkSha256', 'androidTest/debug/app-debug-androidTest.apk')]:
        location = legacy.adb(serial, 'shell', 'pm', 'path', package).decode().strip()
        require(re.fullmatch(r'package:/data/app/[A-Za-z0-9_./=+~-]+/base\.apk', location), 'Unexpected installed package path')
        installed = legacy.adb(serial, 'shell', 'sha256sum', location.removeprefix('package:')).decode().split()[0]
        expected = digest((ROOT / 'app/build/outputs/apk' / relative).read_bytes())
        require(installed == expected, 'Installed Android package differs from the built package')
        result[key] = installed
    return result


def replay(serial, path):
    data = path.read_bytes()
    document = json.loads(data)
    validate(document)
    packages = installed_apks(serial)
    legacy.adb(serial, 'shell', 'pm', 'clear', PACKAGE)
    name = 'recorded-' + uuid.uuid4().hex + '.json'
    legacy.adb(serial, 'shell', 'run-as', PACKAGE, 'sh', '-c',
               "'umask 077; mkdir -p files; cat > files/" + name + "'", data=data)
    elapsed = instrument(serial, document['scenario'], {'suiteFile': name, 'disableAnimations': 'true'})
    return {**COUNTS, **elapsed, 'fixtureSha256': digest(data), **packages}


def inspect_diagnostic_bytes(document, credentials):
    """Inspect opaque diagnostics after bounded decompression; never alter archive evidence."""
    archives = {}
    for step in document['steps']:
        frame = step.get('message') or {}
        if frame.get('type') == 'debug_bundle_chunk' and step['direction'] == 'daemon':
            payload = frame['payload']
            parts = archives.setdefault(step['owner'], {})
            seq = payload['seq']
            require(type(seq) is int and seq >= 0 and seq not in parts, 'Invalid diagnostic sequence')
            parts[seq] = base64.b64decode(payload['data'], validate=True)
            require(sum(map(len, parts.values())) <= 20_000_000, 'Diagnostic archive exceeds recording bound')
    for parts in archives.values():
        require(set(parts) == set(range(len(parts))), 'Incomplete diagnostic sequence')
        compressed = b''.join(parts[index] for index in range(len(parts)))
        with gzip.GzipFile(fileobj=io.BytesIO(compressed)) as stream:
            data = stream.read(20_000_001)
        require(len(data) <= 20_000_000, 'Decoded diagnostic exceeds recording bound')
        require(not any(value and value in data for value in credentials), 'Credential rejected from diagnostic archive')
        legacy.require_safe(data.decode('utf-8', errors='replace'))


def capture_credentials(home):
    values = [os.environ.get(key, '').encode() for key in ['CLAUDE_CODE_OAUTH_TOKEN', 'ANTHROPIC_API_KEY']]
    def walk(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if isinstance(child, str) and re.search(r'token|private.?key|public.?key|pair.?code', key, re.I):
                    values.append(child.encode())
                else: walk(child)
        elif isinstance(value, list):
            for child in value: walk(child)
    for path in (home / '.pyry').glob('*.json'):
        try: walk(json.loads(path.read_text()))
        except (UnicodeError, json.JSONDecodeError): continue
    return values


def normalize_capture(document, home):
    def walk(value):
        if isinstance(value, dict):
            return {key: 'recorded-answer-token' if key == 'answer_token' else walk(child) for key, child in value.items()}
        if isinstance(value, list): return [walk(child) for child in value]
        if isinstance(value, str):
            return value.replace(str(home.resolve()), '/controlled/mobile-home').replace(str(home), '/controlled/mobile-home')
        return value
    return walk(document)


def capture(serial, method, destination, pyry, daemon_source):
    require(re.fullmatch(r'[A-Za-z0-9_]{1,180}', method), 'Invalid method')
    require(not destination.exists(), 'Candidate already exists')
    require(os.environ.get('CLAUDE_CODE_OAUTH_TOKEN') or os.environ.get('ANTHROPIC_API_KEY'), 'Configured Claude login required')
    require(not legacy.run(['git', 'status', '--porcelain']).strip(), 'Commit source before capture')
    packages = installed_apks(serial)
    revision = legacy.run(['git', 'rev-parse', 'HEAD']).decode().strip()
    metadata = legacy.run(['go', 'version', '-m', str(pyry)]).decode()
    match = re.search(r'vcs.revision=([a-f0-9]{40})', metadata)
    require(match and 'vcs.modified=true' not in metadata, 'Clean daemon producer metadata required')
    started = time.time_ns()
    name = 'recorded-' + uuid.uuid4().hex + '.json'
    evidence = ROOT / 'build/recorded-suite' / method / name.removesuffix('.json')
    evidence.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='mobile-suite-', dir='/tmp') as directory:
        home = Path(directory) / 'home'
        home.mkdir(mode=0o700)
        (home / '.pyry').mkdir()
        legacy.private_write(home / '.pyry/config.json', json.dumps({'interactive_runner': 'stream-json', 'stdio_permission_prompt': True}).encode())
        (home / '.claude').mkdir()
        legacy.private_write(home / '.claude/settings.json', b'{"skipDangerousModePermissionPrompt":true}')
        configured = Path.home() / '.claude.json'
        onboarding = json.loads(configured.read_text()) if configured.is_file() else {}
        onboarding = {key: value for key, value in onboarding.items()
                      if key in {'oauthAccount', 'hasCompletedOnboarding', 'lastOnboardingVersion', 'installMethod', 'userID'}}
        onboarding['projects'] = {str(home.resolve()): {'hasTrustDialogAccepted': True}}
        legacy.private_write(home / '.claude.json', json.dumps(onboarding).encode())
        child = {key: value for key, value in os.environ.items() if not key.startswith('OP_')}
        child.update(HOME=str(home), TMPDIR='/tmp/', ANDROID_SERIAL=serial, LIVE='1', E2E_INSTALLED='1', E2E_APKS_BUILT='1', DEVICE='connected',
                     E2E_DISABLE_ANIMATIONS='1', PYRY_BIN=str(pyry), PYRY_NAME='e2e-recorded-mobile', PYRYCODE_SRC=str(daemon_source),
                     LIVE_TESTS=CLASS + '#' + method, RECORDED_SUITE_MODE='capture', RECORDED_SUITE_FILE=name,
                     E2E_FAULT_EVIDENCE_DIR=str(evidence / 'private'))
        # Gradle, if a fixture needs it, must use the configured cache rather than the isolated Claude home.
        child.setdefault('GRADLE_USER_HOME', str(Path.home() / '.gradle'))
        legacy.adb(serial, 'shell', 'pm', 'clear', PACKAGE)
        legacy.adb(serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        legacy.adb(serial, 'shell', 'wm', 'dismiss-keyguard')
        with (evidence / 'host-private.log').open('wb') as log:
            result = subprocess.run(['bash', str(ROOT / 'scripts/e2e-emulator.sh')], cwd=ROOT, env=child,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=1200)
        report_dir = ROOT / 'app/build/outputs/androidTest-results/connected/debug'
        for filename in ['TEST-installed.xml', 'instrument-raw.txt']:
            artifact = report_dir / filename
            if artifact.is_file() and artifact.stat().st_mtime_ns >= started:
                (evidence / filename).write_bytes(artifact.read_bytes())
        if result.returncode != 0:
            try:
                failed = legacy.adb(serial, 'exec-out', 'run-as', PACKAGE, 'cat', 'files/' + name + '.failed')
                require(not any(value and value in failed for value in capture_credentials(home)), 'Credential rejected from failed-run diagnostics')
                legacy.private_write(evidence / 'failed-protocol.json', failed)
            except (subprocess.CalledProcessError, OSError):
                pass
        require(result.returncode == 0, 'Native capture failed; inspect private host evidence')
        report = report_dir / 'TEST-installed.xml'
        require(report.stat().st_mtime_ns >= started, 'Stale native report')
        tests = list(ET.parse(report).getroot().iter('testcase'))
        require(len(tests) == 1 and method in tests[0].get('name', '') and
                not any(test.find(key) is not None for test in tests for key in ['failure', 'error', 'skipped']), 'Native capture count failed')
        document = json.loads(legacy.adb(serial, 'exec-out', 'run-as', PACKAGE, 'cat', 'files/' + name))
        require(document['scenario'] == method, 'Wrong captured scenario')
        document['source'] = {'mobileRevision': revision, 'daemonRevision': match.group(1),
                              'daemonBinarySha256': digest(pyry.read_bytes()),
                              **packages,
                              'claudeVersion': legacy.run(['claude', '--version']).decode().strip(),
                              'capturedAt': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
                              'presentation': 'current-production',
                              'androidApi': legacy.adb(serial, 'shell', 'getprop', 'ro.build.version.sdk').decode().strip()}
        document = normalize_capture(document, home)
        validate(document)
        inspect_diagnostic_bytes(document, capture_credentials(home))
        data = (json.dumps(document, separators=(',', ':'), ensure_ascii=False) + '\n').encode()
        for key in ['CLAUDE_CODE_OAUTH_TOKEN', 'ANTHROPIC_API_KEY']:
            value = os.environ.get(key, '').encode()
            require(not value or value not in data, 'Credential rejected')
        destination.parent.mkdir(parents=True, exist_ok=True)
        legacy.private_write(destination, data)
    raw = (evidence / 'instrument-raw.txt').read_bytes()
    timing = re.search(rb'(?m)^Time: (\d+(?:\.\d+)?)\r?$', raw)
    require(timing is not None, 'Native timing report missing')
    return {**COUNTS, 'seconds': round((time.time_ns() - started) / 1e9, 3), 'nativeSeconds': float(timing.group(1)),
            'fixtureSha256': digest(data), 'source': document['source']}


def redundant_catalog_query(document, index):
    step = document['steps'][index]
    frame = step.get('message') or {}
    if document.get('modelQueryNormalization') != 'reconnect-catalog-race-v1' or not step['owner'].startswith('phone'):
        return False
    if step['direction'] != 'client' or frame.get('type') != 'request_model_list' or not step['accepted']:
        return False
    payload = frame.get('payload')
    if not isinstance(payload, dict) or set(payload) != {'conversation_id'}: return False
    requests = [row for row in document['steps'] if row['direction'] == 'client' and row['owner'] == step['owner'] and
                row['message']['type'] == frame['type'] and row['message'].get('payload') == payload]
    if len(requests) != 1: return False
    start = 0
    end = len(document['steps'])
    for position, row in enumerate(document['steps']):
        if row['direction'] == 'client' and row['message']['type'] not in QUERIES:
            if position < index: start = position + 1
            elif position > index:
                end = position
                break
    responses = [row['message'] for row in document['steps'][index + 1:end] if row['direction'] == 'daemon' and
                 row['owner'] == step['owner'] and row['message'].get('in_reply_to') == frame['id']]
    if len(responses) != 1 or responses[0]['type'] != 'model_list': return False
    response = responses[0]
    if response.get('payload', {}).get('conversation_id') != payload['conversation_id']: return False
    if response.get('event_id') is not None or response.get('history_entry_id') is not None: return False
    return any(row['direction'] == 'daemon' and row['owner'] == step['owner'] and row['message']['type'] == 'model_list' and
               row['message'].get('in_reply_to') is None and row['message'].get('payload') == response.get('payload')
               for row in document['steps'][start:end])


def polling_groups(document):
    groups, replies = {}, {}
    for step in document['steps']:
        frame = step.get('message') or {}
        if step['direction'] == 'daemon' and frame.get('in_reply_to') is not None:
            replies.setdefault((step['owner'], frame['in_reply_to']), []).append(frame)
        if step['direction'] == 'client' and step['owner'].startswith('phone/'):
            enabled = (frame['type'] == 'request_session_settings' and document.get('settingsReadNormalization') == 'recorded-snapshots-v1' or
                       frame['type'] == 'backfill_since' and document.get('backfillProbeNormalization') == 'unsupported-legacy-probe-v1')
            if enabled:
                key = (step['owner'], frame['type'], json.dumps(frame.get('payload'), sort_keys=True))
                groups.setdefault(key, []).append(step)
    normalized = {}
    for key, requests in groups.items():
        responses = [replies.get((key[0], step['message']['id']), []) for step in requests]
        def qualifies(rows):
            if len(rows) != 1: return False
            frame = rows[0]
            if frame.get('event_id') is not None or frame.get('history_entry_id') is not None: return False
            if key[1] == 'request_session_settings':
                return set(requests[0]['message'].get('payload') or {}) == {'conversation_id'} and frame['type'] == 'session_settings'
            return frame['type'] == 'error' and frame.get('payload', {}).get('code') == 'protocol.unknown_type'
        def cancelled(step, rows):
            if rows or key[1] != 'request_session_settings': return False
            following = next((row for row in document['steps'][document['steps'].index(step) + 1:]
                              if row['owner'] == key[0] and row['direction'] in {'client', 'close', 'down', 'inbound_end'}), None)
            return following is not None and following['direction'] in {'close', 'down', 'inbound_end'}
        if any(responses) and all(step['accepted'] for step in requests) and all(
                qualifies(rows) or cancelled(step, rows) for step, rows in zip(requests, responses)):
            for step in requests: normalized[key[0], step['message']['id']] = key[1]
    return normalized


def contract(document):
    """Logical full requests. Recorded clocks, producer identities and opaque cursors may rebind."""
    replacements = {str(value): str(index).zfill(len(str(value)))
                    for index, value in enumerate(document['inputs']['clockReads'])}
    for key in ['backgroundAgentFixtureUrl', 'stopTaskFixtureUrl', 'sendNowReleasePath', 'bypassTokenFile', 'bypassToken']:
        value = document['inputs']['arguments'].get(key)
        if value:
            replacements[value] = 'controlled-' + key
            replacements[value.replace('127.0.0.1', '10.0.2.2')] = 'controlled-' + key
    aliases, counters = {}, {}
    identity_keys = legacy.IDENTITIES | {'attachment_id', 'question_batch_id', 'question_id'}
    def bind(value, kind):
        if not isinstance(value, str) or not value or value in aliases: return
        if kind in {'conversation_id', 'current_conversation_id', 'id'}: kind = 'conversation'
        if kind in {'session_id', 'current_session_id'}: kind = 'session'
        index = counters.get(kind, 0)
        counters[kind] = index + 1
        aliases[value] = kind + '-' + str(index)
    for key, value in sorted(document['inputs']['arguments'].items()):
        if key.endswith('Id') or key == 'serverIdB': bind(value, key)
    for step in document['steps']:
        message = step.get('message', {})
        if step['direction'] == 'daemon' and message.get('type') == 'conversation_created':
            bind(message['payload'].get('id'), 'conversation')
    def prime(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key in identity_keys: bind(child, key)
                prime(child)
        elif isinstance(value, list):
            for child in value: prime(child)
    # Query timing cannot choose aliases: bind action-owned identifiers before sorting query groups.
    for step in document['steps']:
        message = step.get('message', {})
        if step['direction'] == 'client' and message.get('type') not in QUERIES:
            prime(message.get('payload', {}))
    times, cursors = set(), set()
    def producer_values(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key in {'ts', 'timestamp'} and isinstance(child, str): times.add(child)
                if key == 'cursor' and isinstance(child, str) and child: cursors.add(child)
                producer_values(child)
        elif isinstance(value, list):
            for child in value: producer_values(child)
    for step in document['steps']:
        if step['direction'] == 'daemon': producer_values(step.get('message', {}))
    def clocks_bytes(data):
        for old, new in replacements.items():
            # Generated file labels contain clock reads. Resource addresses stay text-only.
            if old.isdigit(): data = data.replace(old.encode(), new.encode())
        return data
    chunks, hashes, canonical_parts = {}, {}, {}
    for step in document['steps']:
        message = step.get('message', {})
        if step['direction'] == 'client' and message.get('type') == 'attachment_chunk':
            payload = message['payload']
            key = (step['owner'], payload['attachment_id'])
            part = base64.b64decode(payload['data'], validate=True)
            row = chunks.setdefault(key, {'parts': {}, 'total': payload['total_chunks'], 'sha256': payload['sha256']})
            require(row['parts'].get(payload['index'], part) == part, 'Conflicting captured upload chunk')
            row['parts'][payload['index']] = part
    for row in chunks.values():
        if set(row['parts']) == set(range(row['total'])):
            data = b''.join(row['parts'][index] for index in range(row['total']))
            require(digest(data) == row['sha256'], 'Captured upload checksum differs from its bytes')
            canonical = clocks_bytes(data)
            hashes[row['sha256']] = digest(canonical)
            offset = 0
            for index in range(row['total']):
                length = len(row['parts'][index])
                canonical_parts[row['sha256'], index] = canonical[offset:offset + length]
                offset += length
    def normalized(value, key='', attachment=False):
        if isinstance(value, dict):
            prime(value)
            return {aliases.get(name, name): normalized(child, name, attachment) for name, child in value.items()}
        if isinstance(value, list): return [normalized(child, key, attachment) for child in value]
        if not isinstance(value, str): return value
        if attachment and key == 'data':
            return base64.b64encode(clocks_bytes(base64.b64decode(value, validate=True))).decode()
        if key == 'since_ts' and value in times: bind(value, 'timestamp')
        if key == 'cursor' and value in cursors: bind(value, 'cursor')
        if value in hashes: return hashes[value]
        for old, new in sorted(replacements.items(), key=lambda item: -len(item[0])): value = value.replace(old, new)
        for old, new in sorted(aliases.items(), key=lambda item: -len(item[0])): value = value.replace(old, new)
        return value
    result, pending, query_checkpoints = [], [], []
    lifecycle, modeled = {}, set()
    polling = polling_groups(document)
    for index, step in enumerate(document['steps']):
        frame = step.get('message', {})
        if step['direction'] == 'client':
            if step['owner'] == 'control' and frame['type'] == 'test-control:checkpoint':
                query_checkpoints.append({'checkpoint': frame['payload']['body'],
                                          'queries': sorted(pending, key=lambda value: json.dumps(value, sort_keys=True))})
                pending = []
            if redundant_catalog_query(document, index): continue
            if frame['type'] == 'mark_conversation_read' and step['owner'].startswith('phone') and document.get('readReceiptNormalization'):
                continue
            payload = frame.get('payload')
            if frame['type'] == 'attachment_chunk':
                canonical = canonical_parts.get((payload['sha256'], payload['index']))
                if canonical is not None: payload = {**payload, 'data': base64.b64encode(canonical).decode()}
            value = {'owner': step['owner'], 'type': frame['type'], 'accepted': step['accepted'],
                     'payload': normalized(payload, attachment=frame['type'] == 'attachment_chunk')}
            poll = polling.get((step['owner'], frame['id']))
            if poll == 'backfill_since': continue
            if poll:
                key = json.dumps(value, sort_keys=True)
                if key in modeled: continue
                modeled.add(key)
            if frame['type'] in QUERIES: pending.append(value)
            else:
                result.append(value)
        elif step['direction'] in {'connect', 'close'}:
            family = step['owner'].rsplit('/', 1)[0]
            lifecycle.setdefault(family, []).append({'lifecycle': step['direction'], 'owner': step['owner']})
        elif step['direction'] == 'external' and step['detail']['event'] != 'push-token':
            result.append({'external': step['detail']['event'], 'owner': step['owner']})
    result = {'commands': result, 'queries': sorted(pending, key=lambda value: json.dumps(value, sort_keys=True)),
              'lifecycle': lifecycle}
    if query_checkpoints: result['queryCheckpoints'] = query_checkpoints
    return result


QUERY_POLICY = {'version': 1, 'modelQueryNormalization': 'reconnect-catalog-race-v1',
                'settingsReadNormalization': 'recorded-snapshots-v1',
                'backfillProbeNormalization': 'unsupported-legacy-probe-v1'}


def suite_query_policy():
    policy = json.loads(MANIFEST.read_text()).get('queryPolicy')
    if policy is not None:
        require(policy == QUERY_POLICY and type(policy.get('version')) is int, 'Unsupported suite query policy')
    return policy


def comparison_contracts(reviewed, fresh, suite_policy=None):
    if suite_policy is not None:
        require(suite_policy == QUERY_POLICY and type(suite_policy.get('version')) is int, 'Unsupported suite query policy')
        fields = {key: value for key, value in suite_policy.items() if key != 'version'}
        reviewed, fresh = {**reviewed, **fields}, {**fresh, **fields}
    # Standalone assets keep strict counts unless both declare the same model.
    fields = ['modelQueryNormalization', 'settingsReadNormalization', 'backfillProbeNormalization']
    policy = {key: reviewed[key] for key in fields if reviewed.get(key) and reviewed.get(key) == fresh.get(key)}
    def document(value):
        result = dict(value)
        for key in fields:
            if key not in policy: result.pop(key, None)
        return result
    return contract(document(reviewed)), contract(document(fresh)), policy


def verify(args):
    original = json.loads(args.fixture.read_text())
    validate(original)
    args.output.mkdir(parents=True, exist_ok=True)
    candidate = args.output / (original['scenario'] + '-fresh.json')
    result = {'scenario': original['scenario'], 'verified': False, 'failureStages': []}

    def stage(name, run):
        try:
            result[name] = run()
            return True
        except Exception as error:
            # Raw instrumentation and producer logs remain private. Reports contain no payloads.
            result[name] = {'status': 'failed', 'category': type(error).__name__}
            result['failureStages'].append(name)
            return False

    if stage('capture', lambda: capture(args.serial, original['scenario'], candidate, args.pyry, args.daemon_source)):
        stage('reviewedReplay', lambda: replay(args.serial, args.fixture))
        stage('freshReplay', lambda: replay(args.serial, candidate))
        try:
            reviewed_contract, fresh_contract, policy = comparison_contracts(original, json.loads(candidate.read_text()), suite_query_policy())
            result['contractMatched'] = reviewed_contract == fresh_contract
            result['comparisonNormalizations'] = policy
            if not result['contractMatched']:
                result['failureStages'].append('comparison')
                legacy.private_write(args.output / 'comparison-private.json',
                                     (json.dumps({'reviewed': reviewed_contract, 'fresh': fresh_contract}, indent=2) + '\n').encode())
        except Exception as error:
            result['comparison'] = {'status': 'failed', 'category': type(error).__name__}
            result['failureStages'].append('comparison')
    result['verified'] = not result['failureStages']
    legacy.private_write(args.output / 'verification.json', (json.dumps(result, indent=2) + '\n').encode())
    require(result['verified'], 'Verification failed; inspect the separately reported live, replay and comparison stages')
    legacy.private_write(args.output / 'receipt.json', (json.dumps(result, indent=2) + '\n').encode())
    return result


def selected_rows(manifest, methods_file=None, ready=False):
    rows = [row for row in manifest['scenarios'] if not row.get('retired')]
    if methods_file is not None:
        chosen = json.loads(methods_file.read_text())
        require(isinstance(chosen, list) and chosen and len(chosen) == len(set(chosen)), 'Invalid selected method list')
        known = {row['method'] for row in rows}
        require(set(chosen) <= known, 'Selected method is absent or retired')
        rows = [row for row in rows if row['method'] in chosen]
    require(rows, 'Suite selected no methods')
    if ready: require(all(row.get('fixture') for row in rows), 'Complete suite contains missing recordings')
    return rows


def all_ready_rows():
    legacy.run([sys.executable, str(ROOT / 'scripts/recorded-suite-inventory.py')])
    return selected_rows(json.loads(MANIFEST.read_text()), ready=True)


def replay_all(args):
    rows = all_ready_rows()
    results, failures = [], []
    for row in rows:
        try:
            result = replay(args.serial, MANIFEST.parent / row['fixture'])
            results.append({'scenario': row['method'], **result})
            print(json.dumps({'scenario': row['method'], 'result': 'passed', **result}), flush=True)
        except Exception as error:
            failures.append(row['method'])
            print(json.dumps({'scenario': row['method'], 'result': 'failed', 'category': type(error).__name__}), flush=True)
    require(not failures and len(results) == len(rows), 'Complete offline suite failed; inspect private scenario reports')
    return {'required': len(rows), 'executed': len(results), 'failed': 0, 'skipped': 0, 'results': results}


def verify_all(args):
    all_ready_rows()
    rows = selected_rows(json.loads(MANIFEST.read_text()), getattr(args, 'methods_file', None), ready=True)
    require(not args.output.exists(), 'Use a new verification directory; existing candidates are preserved')
    results, failures, attempts = [], [], []
    args.output.mkdir(parents=True)
    for row in rows:
        scenario_args = argparse.Namespace(**vars(args))
        scenario_args.fixture = MANIFEST.parent / row['fixture']
        scenario_args.output = args.output / row['method']
        try:
            result = verify(scenario_args)
            results.append(result)
            print(json.dumps({'scenario': row['method'], 'result': 'passed'}), flush=True)
        except Exception as error:
            failures.append(row['method'])
            print(json.dumps({'scenario': row['method'], 'result': 'failed', 'category': type(error).__name__}), flush=True)
        report = scenario_args.output / 'verification.json'
        if report.is_file(): attempts.append(json.loads(report.read_text()))
    summary = {'required': len(rows), 'verified': len(results), 'failures': failures, 'results': results,
               'attempts': attempts,
               'stageFailures': {stage: [attempt['scenario'] for attempt in attempts if stage in attempt['failureStages']]
                                 for stage in ['capture', 'reviewedReplay', 'freshReplay', 'comparison']}}
    summary['stageCounts'] = {}
    for stage in ['capture', 'reviewedReplay', 'freshReplay', 'comparison']:
        passed = sum(attempt.get('contractMatched') is True if stage == 'comparison'
                     else all(attempt.get(stage, {}).get(key) == value for key, value in COUNTS.items())
                     for attempt in attempts)
        failed = len(summary['stageFailures'][stage])
        summary['stageCounts'][stage] = {'passed': passed, 'failed': failed, 'notRun': len(rows) - passed - failed}
    legacy.private_write(args.output / 'summary.json', (json.dumps(summary, indent=2) + '\n').encode())
    require(not failures and len(results) == len(rows), 'Complete fresh compatibility gate failed; candidates were preserved')
    return summary


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    for name in ['capture', 'replay', 'verify', 'capture-all', 'replay-all', 'verify-all']:
        command = sub.add_parser(name)
        command.add_argument('--serial', required=True)
        if name not in {'replay', 'replay-all'}:
            command.add_argument('--pyry', type=Path, required=True)
            command.add_argument('--daemon-source', type=Path, required=True)
            command.add_argument('--output', type=Path, required=True)
        if name in {'replay', 'verify'}: command.add_argument('--fixture', type=Path, required=True)
        if name == 'capture': command.add_argument('--method', required=True)
        if name == 'capture-all':
            command.add_argument('--stop-file', type=Path)
            command.add_argument('--methods-file', type=Path)
        if name == 'verify-all': command.add_argument('--methods-file', type=Path)
    args = parser.parse_args()
    if args.mode in {'capture', 'capture-all', 'verify', 'verify-all'}:
        require(os.environ.get('CLAUDE_CODE_OAUTH_TOKEN') or os.environ.get('ANTHROPIC_API_KEY'), 'Configured Claude login required')
    if args.mode == 'replay-all': result = replay_all(args)
    elif args.mode == 'verify-all': result = verify_all(args)
    elif args.mode == 'replay': result = replay(args.serial, args.fixture)
    elif args.mode == 'capture': result = capture(args.serial, args.method, args.output, args.pyry, args.daemon_source)
    elif args.mode == 'verify': result = verify(args)
    else:
        result = {'passed': [], 'failed': []}
        args.output.mkdir(parents=True, exist_ok=True)
        stopped = False
        for row in selected_rows(json.loads(MANIFEST.read_text()), args.methods_file):
            if args.stop_file and args.stop_file.exists():
                stopped = True
                break
            method = row['method']
            if row.get('retired'):
                continue
            candidate = args.output / (method + '.json')
            evidence = args.output / (method + '.evidence.json')
            captured_path = args.output / (method + '.capture.json')
            if candidate.exists() and evidence.exists():
                prior = json.loads(evidence.read_text())
                require(prior['replay']['fixtureSha256'] == digest(candidate.read_bytes()), 'Candidate evidence hash changed')
                result['passed'].append(method)
                continue
            try:
                if candidate.exists():
                    require(captured_path.is_file(), 'Existing candidate has no capture receipt')
                    captured = json.loads(captured_path.read_text())
                    require(captured['fixtureSha256'] == digest(candidate.read_bytes()), 'Capture receipt hash changed')
                else:
                    captured = capture(args.serial, method, candidate, args.pyry, args.daemon_source)
                    legacy.private_write(captured_path, (json.dumps(captured) + '\n').encode())
                replayed = replay(args.serial, candidate)
                legacy.private_write(evidence, (json.dumps({'scenario': method, 'capture': captured, 'replay': replayed}) + '\n').encode())
                result['passed'].append(method)
                print(json.dumps({'scenario': method, 'result': 'passed', 'captureSeconds': captured['seconds'], 'replaySeconds': replayed['seconds']}), flush=True)
            except Exception as error:
                result['failed'].append(method)
                print(json.dumps({'scenario': method, 'result': 'failed', 'category': type(error).__name__}), flush=True)
        print(json.dumps(result), flush=True)
        require(not stopped, 'Capture stopped at a completed method boundary')
        require(not result['failed'], 'Complete capture and replay failed for some original methods')
        return
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
