#!/usr/bin/env python3
"""Lease an explicitly provisioned test login; never read or copy its sign-in files."""
import fcntl
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile


class CodexHomeLease:
    def __init__(self, env):
        self.env = dict(env)
        for key in ('OPENAI_API_KEY', 'CODEX_API_KEY', 'OPENAI_BASE_URL'):
            self.env.pop(key, None)
        self.reason = ''
        self.binary = ''
        self.version = ''
        self.lock_fd = None
        self.link = None
        self.link_identity = None

    def __enter__(self):
        try:
            self.prepare()
        except OSError:
            self.reason = 'unsafe_home'
        if self.reason:
            self.close()
        return self

    def prepare(self):
        configured = self.env.get('PYRY_CODEX_CAPTURE_HOME', '')
        binary = self.env.get('PYRY_CODEX_CAPTURE_BIN', '')
        if not configured or not binary:
            self.reason = 'configuration_missing'
            return
        operator = Path(self.env['HOME']).resolve(strict=True)
        home = Path(configured).resolve(strict=True)
        forbidden = [operator / '.codex', operator / '.pyry', operator / '.pyrycode']
        if self.env.get('CODEX_HOME'):
            forbidden.append(Path(self.env['CODEX_HOME']).resolve())
        name = self.env.get('PYRY_NAME', '')
        info = home.stat()
        if (home == operator or any(home == root.resolve() or home.is_relative_to(root.resolve()) for root in forbidden)
                or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid()
                or info.st_mode & 0o077 or not re.fullmatch(r'e2e-[A-Za-z0-9_.-]+', name)):
            self.reason = 'unsafe_home'
            return
        executable = shutil.which(binary, path=self.env.get('PATH'))
        if not executable:
            self.reason = 'binary_unavailable'
            return
        self.binary = str(Path(executable).resolve())
        # The version command sees no login or personal Codex config.
        with tempfile.TemporaryDirectory(prefix='pyry-e2e-codex-version-') as empty:
            try:
                version = subprocess.run([self.binary, '--version'], env=dict(self.env, CODEX_HOME=empty),
                                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                self.reason = 'binary_unavailable'
                return
        if version.returncode:
            self.reason = 'binary_unavailable'
            return
        match = re.fullmatch(rb'codex-cli (\d+)\.(\d+)\.(\d+)(-[^+\s]+)?(\+[^\s]+)?\s*', version.stdout)
        if (not match or tuple(int(match[i]) for i in (1, 2, 3)) < (0, 156, 1)
                or tuple(int(match[i]) for i in (1, 2, 3)) == (0, 156, 1) and match[4]):
            self.reason = 'version_incompatible'
            return
        self.version = '.'.join(str(int(match[i])) for i in (1, 2, 3))
        self.lock_fd = os.open(home / '.mobile-e2e.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        lock_info = os.fstat(self.lock_fd)
        if not stat.S_ISREG(lock_info.st_mode) or lock_info.st_uid != os.getuid() or lock_info.st_mode & 0o077:
            self.reason = 'unsafe_home'
            return
        try:
            fcntl.flock(self.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.reason = 'home_busy'
            return
        try:
            authenticated = subprocess.run([self.binary, 'login', 'status'], env=dict(self.env, CODEX_HOME=str(home)),
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
        except (OSError, subprocess.TimeoutExpired):
            self.reason = 'auth_unavailable'
            return
        if authenticated.returncode:
            self.reason = 'auth_unavailable'
            return
        instance = operator / '.pyry' / name
        instance.mkdir(mode=0o700, parents=True, exist_ok=True)
        # An existing instance may not redirect this write into another instance.
        if instance.resolve() != instance or (operator / '.pyry').is_symlink():
            self.reason = 'unsafe_home'
            return
        link = instance / 'codex-home'
        if link.exists() or link.is_symlink():
            self.reason = 'instance_home_exists'
            return
        link.symlink_to(home, target_is_directory=True)
        self.link = link
        self.link_identity = link.lstat().st_ino

    def close(self):
        try:
            if self.link and self.link.is_symlink() and self.link.lstat().st_ino == self.link_identity:
                self.link.unlink()
        finally:
            if self.lock_fd is not None:
                os.close(self.lock_fd)
                self.lock_fd = None

    def __exit__(self, *_):
        self.close()


def main(command):
    with CodexHomeLease(os.environ) as lease:
        env = dict(lease.env, E2E_CODEX_LEASE='1', E2E_CODEX_UNMET=lease.reason)
        # The source home is host-only. Instrumentation receives only a fixed reason.
        if not lease.reason:
            env['E2E_CODEX_BIN'] = lease.binary
            print('event=codex_prerequisite outcome=ready', flush=True)
            print('codex revision: ' + lease.version, flush=True)
        else:
            env.pop('E2E_CODEX_BIN', None)
            print('event=codex_prerequisite outcome=' + lease.reason, flush=True)
        descriptors = () if lease.lock_fd is None else (lease.lock_fd,)
        child = None
        pending_signals = []

        def forward(received, _frame):
            if child is None:
                pending_signals.append(received)
            elif child.poll() is None:
                child.send_signal(received)

        previous = {sig: signal.signal(sig, forward) for sig in (signal.SIGTERM, signal.SIGINT)}
        try:
            child = subprocess.Popen(command, env=env, pass_fds=descriptors)
            for received in pending_signals:
                forward(received, None)
            status = child.wait()
        finally:
            # An exception cannot release the login while the shell still owns live daemons.
            if child is not None and child.poll() is None:
                child.terminate()
                child.wait()
            for sig, handler in previous.items():
                signal.signal(sig, handler)
        return status if status >= 0 else 128 - status


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
