"""Retain private later-reply evidence from the launcher's exact scenario HOME."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import time
import uuid

METHOD = 'de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_backgroundAgent_followsBottomUntilFinished'
LIMIT = 16 * 1024 * 1024


def identifier(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError('invalid identity')
    return value


def read_regular(home, relative):
    """Open each component without following symlinks, including swap races."""
    parts = Path(relative).parts
    if not parts or any(p in ('..', '/', '.') for p in parts):
        raise ValueError('invalid relative path')
    directory = os.open(home, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = child
        fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        with os.fdopen(fd, 'rb') as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size > LIMIT:
                raise ValueError('not bounded regular evidence')
            data = stream.read(LIMIT + 1)
            if len(data) > LIMIT:
                raise ValueError('evidence exceeds bound')
            return data
    finally:
        os.close(directory)


def regular_files(home, relative):
    start = home / relative
    if any(p.is_symlink() for p in [start, *start.parents] if p != home.parent):
        return
    for directory, dirs, files in os.walk(start, followlinks=False):
        dirs[:] = [d for d in dirs if not (Path(directory) / d).is_symlink()]
        for name in sorted(files):
            path = Path(directory) / name
            if stat.S_ISREG(path.lstat().st_mode):
                yield path.relative_to(home)


def capture(home, instance, root, started_ns, status):
    if home.is_symlink() or not re.fullmatch(r'e2e-[a-zA-Z0-9-]+', instance):
        raise ValueError('invalid launcher ownership')
    home = home.resolve(strict=True)
    capture_start = time.time_ns()
    base = Path('.pyry') / instance
    registry = json.loads(read_regular(home, base / 'conversations.json'))
    selected = [c for c in registry['conversations'] if isinstance(c.get('name'), str)
                and c['name'].startswith('e2e1783-agent-')]
    if len(selected) != 1:
        raise ValueError('scenario ownership unavailable or ambiguous')
    conversation = identifier(selected[0]['id'])
    sessions = {identifier(selected[0]['current_session_id'])}
    history = []
    for rel in regular_files(home, base / 'conversations' / conversation / 'history'):
        if re.fullmatch(r'segment-[0-9]{20}\.jsonl', rel.name):
            data = read_regular(home, rel)
            for line in data.splitlines():
                entry = json.loads(line)
                session = entry.get('session', {}).get('session_id')
                if session:
                    sessions.add(identifier(session))
            history.append((rel, data))
    if not history:
        raise ValueError('history unavailable')
    transcripts = []
    for rel in regular_files(home, Path('.claude/projects')):
        if rel.name in {s + '.jsonl' for s in sessions}:
            transcripts.append((rel, read_regular(home, rel)))
    repo = Path(__file__).resolve().parents[1]
    if root.resolve().is_relative_to(repo):
        raise ValueError('private evidence must remain outside checkout')
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if root.is_symlink() or root.stat().st_mode & 0o077:
        raise ValueError('destination must be private')
    destination = Path(tempfile.mkdtemp(prefix=instance + '-', dir=root))
    manifest = dict(method=METHOD, instance=instance, source_home=str(home),
                    conversation_id=conversation, session_ids=sorted(sessions),
                    launcher_started_ns=started_ns, capture_started_ns=capture_start,
                    original_exit=status, transcript_status='retained' if transcripts else 'unknown', files=[])
    for kind, items in [('history', history), ('transcripts', transcripts)]:
        for index, (rel, data) in enumerate(items):
            folder = destination / kind
            folder.mkdir(mode=0o700, exist_ok=True)
            target = folder / (str(index) + '-' + rel.name)
            with target.open('xb') as stream:
                os.fchmod(stream.fileno(), 0o600)
                stream.write(data)
            manifest['files'].append(dict(source=str(rel), retained=str(target.relative_to(destination)),
                                          bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    manifest['capture_ended_ns'] = time.time_ns()
    with (destination / 'manifest.json').open('x') as stream:
        os.fchmod(stream.fileno(), 0o600)
        json.dump(manifest, stream, indent=2)
        stream.write('\n')
    return destination


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--home', type=Path, required=True)
    parser.add_argument('--instance', required=True)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--started', type=int, required=True)
    parser.add_argument('--status', type=int, required=True)
    args = parser.parse_args()
    try:
        capture(args.home, args.instance, args.root, args.started, args.status)
    except (OSError, ValueError, KeyError, TypeError):
        print('later_reply_host_capture=failed')
        raise SystemExit(1)
    print('later_reply_host_capture=retained')
