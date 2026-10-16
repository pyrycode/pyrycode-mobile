#!/usr/bin/env python3
"""Capture original native scenarios, replay offline, or explicitly check fresh Claude compatibility."""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
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
QUERIES = legacy.QUERIES | {'backfill_since'}


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
        require(re.fullmatch(r'[A-Za-z0-9_]{1,180}', document['scenario']), 'Invalid scenario')
        require(document.get('readReceiptNormalization') in {None, 'coalesced-visible-history-v1'}, 'Unsupported receipt normalization')
        source = document['source']
        for key in ['mobileRevision', 'daemonRevision']:
            require(re.fullmatch('[a-f0-9]{40}', source[key]), 'Invalid source revision')
        for key in ['daemonBinarySha256', 'testApkSha256']:
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
            require(isinstance(frame, dict) and isinstance(frame['payload'], dict) and frame['type'] and frame['ts'], 'Invalid frame')
            require(frame['type'] not in {'hello', 'hello_ack', 'rekey_request', 'multipart', 'fragment'}, 'Handshake rejected')
            bucket = ids.setdefault((owner, direction), set())
            require(type(frame['id']) is int and frame['id'] >= 0 and frame['id'] not in bucket, 'Invalid frame identity')
            bucket.add(frame['id'])
            reply = frame.get('in_reply_to')
            if reply is not None:
                require(direction == 'daemon' and reply in ids.get((owner, 'client'), set()), 'Invalid reply correlation')
            if direction == 'client' and frame['type'] == 'register_push_token':
                require(frame['payload'].get('token') == PUSH_ADDRESS, 'Invalid test push address')
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
    return round(time.monotonic() - started, 3)


def replay(serial, path):
    data = path.read_bytes()
    document = json.loads(data)
    validate(document)
    legacy.adb(serial, 'shell', 'pm', 'clear', PACKAGE)
    name = 'recorded-' + uuid.uuid4().hex + '.json'
    legacy.adb(serial, 'shell', 'run-as', PACKAGE, 'sh', '-c',
               "'umask 077; mkdir -p files; cat > files/" + name + "'", data=data)
    elapsed = instrument(serial, document['scenario'], {'suiteFile': name, 'disableAnimations': 'true'})
    return {**COUNTS, 'seconds': elapsed, 'fixtureSha256': digest(data)}


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
    require(not legacy.run(['git', 'status', '--porcelain', '--untracked-files=no']).strip(), 'Commit source before capture')
    revision = legacy.run(['git', 'rev-parse', 'HEAD']).decode().strip()
    metadata = legacy.run(['go', 'version', '-m', str(pyry)]).decode()
    match = re.search(r'vcs.revision=([a-f0-9]{40})', metadata)
    require(match and 'vcs.modified=true' not in metadata, 'Clean daemon producer metadata required')
    started = time.time_ns()
    evidence = ROOT / 'build/recorded-suite' / method
    evidence.mkdir(parents=True, exist_ok=True)
    name = 'recorded-' + uuid.uuid4().hex + '.json'
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
        child.update(HOME=str(home), TMPDIR='/tmp/', ANDROID_SERIAL=serial, LIVE='1', E2E_INSTALLED='1', DEVICE='connected',
                     E2E_DISABLE_ANIMATIONS='1', PYRY_BIN=str(pyry), PYRY_NAME='recorded-mobile', PYRYCODE_SRC=str(daemon_source),
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
        require(result.returncode == 0, 'Native capture failed; inspect private host evidence')
        report = ROOT / 'app/build/outputs/androidTest-results/connected/debug/TEST-installed.xml'
        require(report.stat().st_mtime_ns >= started, 'Stale native report')
        tests = list(ET.parse(report).getroot().iter('testcase'))
        require(len(tests) == 1 and method in tests[0].get('name', '') and
                not any(test.find(key) is not None for test in tests for key in ['failure', 'error', 'skipped']), 'Native capture count failed')
        document = json.loads(legacy.adb(serial, 'exec-out', 'run-as', PACKAGE, 'cat', 'files/' + name))
        require(document['scenario'] == method, 'Wrong captured scenario')
        document['source'] = {'mobileRevision': revision, 'daemonRevision': match.group(1),
                              'daemonBinarySha256': digest(pyry.read_bytes()),
                              'testApkSha256': digest((ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk').read_bytes()),
                              'claudeVersion': legacy.run(['claude', '--version']).decode().strip(),
                              'capturedAt': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
                              'presentation': 'current-production',
                              'androidApi': legacy.adb(serial, 'shell', 'getprop', 'ro.build.version.sdk').decode().strip()}
        document = normalize_capture(document, home)
        validate(document)
        data = (json.dumps(document, separators=(',', ':'), ensure_ascii=False) + '\n').encode()
        for key in ['CLAUDE_CODE_OAUTH_TOKEN', 'ANTHROPIC_API_KEY']:
            value = os.environ.get(key, '').encode()
            require(not value or value not in data, 'Credential rejected')
        destination.parent.mkdir(parents=True, exist_ok=True)
        legacy.private_write(destination, data)
    return {**COUNTS, 'seconds': round((time.time_ns() - started) / 1e9, 3), 'fixtureSha256': digest(data), 'source': document['source']}


def contract(document):
    """Compare complete logical requests; only declared generated inputs receive aliases."""
    replacements = {str(value): 'clock-' + str(index) for index, value in enumerate(document['inputs']['clockReads'])}
    def clocks(value):
        if isinstance(value, dict): return {key: clocks(child) for key, child in value.items()}
        if isinstance(value, list): return [clocks(child) for child in value]
        if isinstance(value, str):
            for old, new in replacements.items(): value = value.replace(old, new)
        return value
    for key in ['backgroundAgentFixtureUrl', 'stopTaskFixtureUrl']:
        value = document['inputs']['arguments'].get(key)
        if value:
            replacements[value] = 'controlled-' + key
            replacements[value.replace('127.0.0.1', '10.0.2.2')] = 'controlled-' + key
    wire = []
    for step in document['steps']:
        if step['direction'] == 'client':
            frame = step['message']
            wire.append({'direction': 'client', 'message': {**frame, 'payload': {'owner': step['owner'], 'payload': clocks(frame['payload'])}}})
        elif step['direction'] in {'connect', 'close', 'external'}:
            wire.append({'direction': 'client', 'message': {'type': step['direction'] + ':' + step['owner'], 'payload': step['detail']}})
    # Legacy contract normalization preserves all values other than named generated identities.
    prior_queries = legacy.QUERIES
    try:
        legacy.QUERIES = QUERIES
        return legacy.normalized_contract({'wire': wire})
    finally:
        legacy.QUERIES = prior_queries


def verify(args):
    original = json.loads(args.fixture.read_text())
    validate(original)
    args.output.mkdir(parents=True, exist_ok=True)
    candidate = args.output / (original['scenario'] + '-fresh.json')
    captured = capture(args.serial, original['scenario'], candidate, args.pyry, args.daemon_source)
    reviewed_replay = replay(args.serial, args.fixture)
    fresh_replay = replay(args.serial, candidate)
    require(contract(original) == contract(json.loads(candidate.read_text())), 'Fresh Claude request contract changed; review candidate without replacing the recording')
    return {'scenario': original['scenario'], 'capture': captured, 'reviewedReplay': reviewed_replay, 'freshReplay': fresh_replay}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    for name in ['capture', 'replay', 'verify', 'capture-all']:
        command = sub.add_parser(name)
        command.add_argument('--serial', required=True)
        if name != 'replay':
            command.add_argument('--pyry', type=Path, required=True)
            command.add_argument('--daemon-source', type=Path, required=True)
            command.add_argument('--output', type=Path, required=True)
        if name in {'replay', 'verify'}: command.add_argument('--fixture', type=Path, required=True)
        if name == 'capture': command.add_argument('--method', required=True)
        if name == 'capture-all': command.add_argument('--stop-file', type=Path)
    args = parser.parse_args()
    if args.mode == 'replay': result = replay(args.serial, args.fixture)
    elif args.mode == 'capture': result = capture(args.serial, args.method, args.output, args.pyry, args.daemon_source)
    elif args.mode == 'verify': result = verify(args)
    else:
        result = {'passed': [], 'failed': []}
        args.output.mkdir(parents=True, exist_ok=True)
        stopped = False
        for row in json.loads(MANIFEST.read_text())['scenarios']:
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
