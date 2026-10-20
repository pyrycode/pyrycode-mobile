#!/usr/bin/env python3
"""Capture one controlled real permission flow, or replay without Claude or a real daemon."""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
CLASS = 'de.pyryco.mobile.recording.RecordedPermissionTest'
PACKAGE = 'de.pyryco.mobile'
ASSET = ROOT / 'app/src/androidTest/assets/recorded/permission-allow.json'
QUERIES = {'list_conversations', 'request_history', 'thread_catch_up', 'thread_page', 'request_session_settings',
           'request_context_usage', 'request_claude_usage', 'request_model_list', 'request_system_prompt', 'mcp_status_request'}
IDENTITIES = {'conversation_id', 'current_session_id', 'session_id', 'modal_id', 'message_id', 'device_id',
              'tool_use_id', 'parent_tool_use_id', 'turn_id', 'task_id', 'parent', 'epoch', 'answer_token'}


def run(args, *, env=None, data=None, timeout=180):
    result = subprocess.run(args, cwd=ROOT, env=env, input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'Test operation failed: {Path(str(args[0])).name}, status {result.returncode}')
    return result.stdout


def wait_for(predicate, timeout):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(.1)
    raise RuntimeError('Timed out waiting for controlled test evidence')


def adb(serial, *args, **kwargs):
    return run([os.environ.get('ADB', 'adb'), '-s', serial, *args], **kwargs)


def private_write(path, data):
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'wb') as output:
        output.write(data)


def require_safe(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if re.fullmatch(r'token|access_?token|refresh_?token|private_?key|secret|authorization', key, re.I):
                raise ValueError('Sensitive recording field')
            require_safe(child)
    elif isinstance(value, list):
        for child in value:
            require_safe(child)
    elif isinstance(value, str) and re.search(r'sk-ant-|gh[pousr]_|-----BEGIN .*PRIVATE KEY-----', value):
        raise ValueError('Sensitive recording content')


def validate_recording(document):
    """Reject malformed candidates before they can be written or sent to Android."""
    require_safe(document)
    if document.get('format') != 'pyrycode.mobile-exchange' or type(document.get('version')) is not int or document.get('version') != 1:
        raise ValueError('Unsupported recording format')
    source = document['source']
    for key in ['mobileRevision', 'daemonRevision']:
        if not re.fullmatch('[a-f0-9]{40}', source[key]): raise ValueError('Invalid source revision')
    for key in ['daemonBinarySha256', 'testApkSha256']:
        if not re.fullmatch('[a-f0-9]{64}', source[key]): raise ValueError('Invalid producer checksum')
    if not source['claudeVersion'] or not re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ', source['capturedAt']):
        raise ValueError('Invalid producer metadata')
    datetime.datetime.strptime(source['capturedAt'], '%Y-%m-%dT%H:%M:%SZ')
    if not isinstance(document['capabilities'], list) or 'interactive' not in document['capabilities']:
        raise ValueError('Missing permission capability')
    for key in ['prompt', 'expectedReply', 'allowLabel']:
        if not isinstance(document['scenario'][key], str) or not document['scenario'][key].strip():
            raise ValueError('Invalid scenario input')
    wire = document['wire']
    if not isinstance(wire, list) or not 1 <= len(wire) <= 5000:
        raise ValueError('Invalid recording bound')
    ids = {'client': set(), 'daemon': set()}
    for entry in wire:
        direction, frame = entry['direction'], entry['message']
        if direction not in ids or type(frame['id']) is not int or frame['id'] < 0 or frame['id'] in ids[direction]:
            raise ValueError('Invalid recording identity')
        ids[direction].add(frame['id'])
        if not frame['ts'] or not frame['type'] or frame['type'] in {'hello', 'hello_ack', 'rekey_request', 'multipart', 'fragment'} or not isinstance(frame['payload'], dict):
            raise ValueError('Invalid application frame')
        if frame.get('in_reply_to') is not None and (direction != 'daemon' or frame['in_reply_to'] not in ids['client']):
            raise ValueError('Invalid request correlation')
    if len(json.dumps(document, ensure_ascii=False).encode()) > 2_000_000:
        raise ValueError('Recording exceeds its bound')


def normalized_contract(recording):
    """Canonical request groups. Queries may permute inside a group, commands may not."""
    aliases = {}
    def normalize(value, parent='', root=False):
        if isinstance(value, dict):
            out = {}
            for key, child in value.items():
                if isinstance(child, str) and child and (key in IDENTITIES or key == 'id' and (root or parent in {'conversations', 'items', 'messages', 'item'})):
                    aliases.setdefault(child, f'identity-{len(aliases)}')
                out[key] = normalize(child, key)
            return out
        if isinstance(value, list):
            return [normalize(child, parent) for child in value]
        return aliases.get(value, value) if isinstance(value, str) else value
    # Assign aliases in the logical client request order below, rather than daemon timing order.
    groups, queries = [], []
    for entry in recording['wire']:
        if entry['direction'] != 'client':
            continue
        frame = entry['message']
        value = {'type': frame['type'], 'payload': frame['payload']}
        if frame['type'] in QUERIES:
            queries.append(value)
        else:
            groups.append([normalize(q) for q in sorted(queries, key=lambda q: (q['type'], json.dumps(q['payload'], sort_keys=True)))])
            queries = []
            groups.append(normalize(value))
    groups.append([normalize(q) for q in sorted(queries, key=lambda q: (q['type'], json.dumps(q['payload'], sort_keys=True)))])
    return groups


def execute(serial, arguments, progress=None):
    command = [os.environ.get('ADB', 'adb'), '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS]
    for key, value in arguments.items():
        command += ['-e', key, value]
    command += [PACKAGE + '.test/de.pyryco.mobile.e2e.E2eInstrumentationRunner']
    started = time.monotonic()
    test_env = {key: value for key, value in os.environ.items()
                if not key.startswith('OP_') and key not in {'CLAUDE_CODE_OAUTH_TOKEN', 'ANTHROPIC_API_KEY', 'PYRY_LIVE_CLAUDE_TOKEN', 'PYRY_DEV_AGENTS_TOKEN'}}
    # A private file avoids blocking the child when a failure prints more than a pipe can hold.
    diagnostic_output = tempfile.TemporaryFile()
    process = subprocess.Popen(command, env=test_env, stdout=diagnostic_output, stderr=subprocess.STDOUT)
    try:
        until = started + 240
        while process.poll() is None:
            if time.monotonic() > until:
                raise RuntimeError('Android recorded scenario timed out')
            if progress:
                progress()
            time.sleep(.1)
        diagnostic_output.seek(0)
        stdout = diagnostic_output.read()
        # A shell exit of zero alone does not establish any test execution.
        if process.returncode or b'OK (1 test)' not in stdout or b'FAILURES!!!' in stdout:
            # Keep the private Android diagnostic on the host, without printing pairing args or raw frames.
            log = ROOT / 'build/recorded-test-failure.log'
            log.parent.mkdir(parents=True, exist_ok=True)
            log.write_bytes(stdout)
            log.chmod(0o600)
            raise RuntimeError('Android recorded scenario did not execute and pass its one required test')
        return time.monotonic() - started
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
            adb(serial, 'shell', 'am', 'force-stop', PACKAGE)
        diagnostic_output.close()


def replay(serial, fixture):
    name = 'recorded-' + uuid.uuid4().hex + '.json'
    data = fixture.read_bytes()
    validate_recording(json.loads(data))
    adb(serial, 'shell', 'run-as', PACKAGE, 'sh', '-c', f"'umask 077; cat > files/{name}'", data=data)
    # Instrumentation gets no test credentials. This mode does not resolve daemon or Claude programs.
    elapsed = execute(serial, {'replayName': name})
    return {'executed': 1, 'failed': 0, 'skipped': 0, 'seconds': round(elapsed, 3), 'fixtureSha256': hashlib.sha256(data).hexdigest()}


def capture(serial, destination, pyry):
    if destination.exists():
        raise ValueError('Candidate already exists')
    if not (os.environ.get('CLAUDE_CODE_OAUTH_TOKEN') or os.environ.get('ANTHROPIC_API_KEY')):
        raise RuntimeError('Capture requires the configured Claude test login')
    revision = run(['git', 'rev-parse', 'HEAD']).decode().strip()
    if run(['git', 'status', '--porcelain', '--untracked-files=no']).strip():
        raise RuntimeError('Commit the source under test before capturing')
    metadata = run(['go', 'version', '-m', pyry]).decode()
    daemon_revision = re.search(r'vcs.revision=([a-f0-9]{40})', metadata)
    if not daemon_revision:
        raise RuntimeError('Daemon executable has no source revision')
    claude = shutil.which('claude')
    if not claude:
        raise RuntimeError('Claude executable unavailable')
    name = 'recorded-' + uuid.uuid4().hex + '.json'
    # Keep the daemon's Unix control socket below macOS's path-length limit.
    with tempfile.TemporaryDirectory(prefix='mobile-exchange-', dir='/tmp') as directory:
        private = Path(directory)
        home = private / 'home'
        work = home / 'work'
        home.mkdir(mode=0o700); work.mkdir(mode=0o700)
        (home / '.pyry').mkdir(mode=0o700)
        (home / '.pyry/config.json').write_text(json.dumps({'interactive_runner': 'stream-json', 'stdio_permission_prompt': True}))
        (home / '.claude').mkdir(mode=0o700)
        (home / '.claude/settings.json').write_text(json.dumps({'skipDangerousModePermissionPrompt': True}))
        configured = json.loads((Path.home() / '.claude.json').read_text())
        onboarding = {key: configured[key] for key in ['oauthAccount', 'hasCompletedOnboarding', 'lastOnboardingVersion', 'installMethod', 'userID'] if key in configured}
        onboarding['projects'] = {str(work.resolve()): {'hasTrustDialogAccepted': True}}
        private_write(home / '.claude.json', json.dumps(onboarding).encode())
        child_env = {key: value for key, value in os.environ.items() if not key.startswith('OP_')}
        child_env.update(HOME=str(home), PYRY_MOBILE_V2='1')
        relay = 'wss://' + os.environ.get('LIVE_RELAY_HOST', 'pyrycode-relay.pyryco.de')
        command = [pyry, '-pyry-name=recorded', '-pyry-claude=' + claude, '-pyry-idle-timeout=0', '-pyry-workdir=' + str(work),
                   '-pyry-relay=' + relay + '/v1/server', '--', '--model', 'haiku']
        with (private / 'daemon.log').open('wb') as log:
            daemon = subprocess.Popen(command, env=child_env, stdout=log, stderr=log, start_new_session=True)
            try:
                wait_for(lambda: (home / '.pyry/recorded.sock').exists() or daemon.poll() is not None, 20)
                if daemon.poll() is not None:
                    raise RuntimeError('Isolated test daemon did not start')
                pair = run([pyry, 'pair', '-pyry-name=recorded', '--name=mobile-recording', '--allow-remote-permissions'], env=child_env).decode()
                fields = None
                for line in pair.splitlines():
                    try:
                        decoded = json.loads(base64.urlsafe_b64decode(line.strip() + '=' * (-len(line.strip()) % 4)))
                        if all(k in decoded for k in ['server', 'token', 'server_static_pubkey']): fields = decoded; break
                    except (ValueError, UnicodeError): pass
                if fields is None:
                    raise RuntimeError('Test pairing unavailable')
                granted = False
                def progress():
                    nonlocal granted
                    result = subprocess.run([os.environ.get('ADB', 'adb'), '-s', serial, 'shell', 'run-as', PACKAGE, 'test', '-f', 'files/' + name + '.armed'], capture_output=True)
                    if not granted and result.returncode == 0:
                        if (work / 'mobile-recorded-witness.txt').exists():
                            raise RuntimeError('Tool executed before permission confirmation')
                        adb(serial, 'shell', 'run-as', PACKAGE, 'touch', 'files/' + name + '.confirm')
                        granted = True
                elapsed = execute(serial, {'recordExchange': 'true', 'recordingName': name, 'relayUrl': relay,
                    'serverId': fields['server'], 'token': fields['token'], 'serverStaticPublicKey': fields['server_static_pubkey']}, progress)
                if not granted or not (work / 'mobile-recorded-witness.txt').exists():
                    raise RuntimeError('Required permission file effect was not proved')
                document = json.loads(adb(serial, 'exec-out', 'run-as', PACKAGE, 'cat', 'files/' + name))
                document.update(format='pyrycode.mobile-exchange', version=1, source={
                    'mobileRevision': revision, 'daemonRevision': daemon_revision.group(1),
                    'daemonBinarySha256': hashlib.sha256(Path(pyry).read_bytes()).hexdigest(),
                    'testApkSha256': hashlib.sha256((ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk').read_bytes()).hexdigest(),
                    'claudeVersion': run([claude, '--version']).decode().strip(), 'capturedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                    'presentation': 'current-production',
                    'androidRelease': adb(serial, 'shell', 'getprop', 'ro.build.version.release').decode().strip(),
                    'androidApi': adb(serial, 'shell', 'getprop', 'ro.build.version.sdk').decode().strip(),
                    'deviceModel': adb(serial, 'shell', 'getprop', 'ro.product.model').decode().strip()})
                def clean(value):
                    if isinstance(value, dict): return {key: 'recorded-answer-token' if key == 'answer_token' else clean(child) for key, child in value.items()}
                    if isinstance(value, list): return [clean(child) for child in value]
                    return value.replace(str(work.resolve()), '/controlled/mobile-recording').replace(str(work), '/controlled/mobile-recording').replace(str(home.resolve()), '/controlled/mobile-home').replace(str(home), '/controlled/mobile-home') if isinstance(value, str) else value
                document = clean(document)
                validate_recording(document)
                output = json.dumps(document, ensure_ascii=False, separators=(',', ':')).encode()
                for key in ['CLAUDE_CODE_OAUTH_TOKEN', 'ANTHROPIC_API_KEY']:
                    secret = os.environ.get(key, '').encode()
                    if secret and secret in output: raise RuntimeError('Configured credential found in capture')
                if len(output) > 2_000_000: raise RuntimeError('Capture exceeds its bound')
                private_write(destination, output + b'\n')
                return {'executed': 1, 'failed': 0, 'skipped': 0, 'seconds': round(elapsed, 3), 'frames': len(document['wire']), 'source': document['source']}
            except Exception:
                log.flush()
                diagnostic = ROOT / 'build/recorded-daemon-failure.log'
                diagnostic.parent.mkdir(parents=True, exist_ok=True)
                diagnostic.write_bytes((private / 'daemon.log').read_bytes())
                diagnostic.chmod(0o600)
                raise
            finally:
                if daemon.poll() is None:
                    os.killpg(daemon.pid, signal.SIGTERM)
                    try: daemon.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(daemon.pid, signal.SIGKILL); daemon.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['capture', 'replay', 'verify'])
    parser.add_argument('--serial', required=True, help='An explicitly owned test device')
    parser.add_argument('--fixture', type=Path, default=ASSET)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--pyry', default=os.environ.get('PYRY_BIN', 'pyry'))
    args = parser.parse_args()
    if adb(args.serial, 'shell', 'getprop', 'sys.boot_completed').strip() != b'1':
        raise RuntimeError('Owned Android device is not booted')
    adb(args.serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb(args.serial, 'shell', 'wm', 'dismiss-keyguard')
    if args.mode == 'replay':
        print(json.dumps(replay(args.serial, args.fixture)))
    elif args.mode == 'capture':
        if args.output is None: parser.error('Capture requires a new --output candidate')
        print(json.dumps(capture(args.serial, args.output, args.pyry)))
    else:
        with tempfile.TemporaryDirectory(prefix='mobile-verify-') as directory:
            candidate = Path(directory) / 'candidate.json'
            live = capture(args.serial, candidate, args.pyry)
            committed, fresh = json.loads(args.fixture.read_text()), json.loads(candidate.read_text())
            if normalized_contract(committed) != normalized_contract(fresh): raise RuntimeError('Live request contract changed; review a new recording')
            print(json.dumps({'live': live, 'committedReplay': replay(args.serial, args.fixture), 'freshReplay': replay(args.serial, candidate)}))


if __name__ == '__main__':
    try: main()
    except (RuntimeError, ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
        # Error text from subprocesses can contain pairing material; only our fixed messages are safe.
        print(str(error) if type(error) in {RuntimeError, ValueError} else 'Recorded test setup failed', file=__import__('sys').stderr)
        raise SystemExit(1)
