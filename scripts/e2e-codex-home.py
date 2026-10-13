#!/usr/bin/env python3
"""Lease an explicitly provisioned test login; never read or copy its sign-in files."""
import fcntl
import os
from pathlib import Path
import re
import shutil
import signal
import select
import stat
import subprocess
import sys
import tempfile
import time


class CodexHomeLease:
    PROBE_TIMEOUT = 15
    TERM_TIMEOUT = 12
    KILL_TIMEOUT = 5

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
        self.cleanup_confirmed = True
        self.locked = False
        self.cancelled = 0
        self.previous = {}

    def __enter__(self):
        self.previous = {sig: signal.signal(sig, self.cancel) for sig in (signal.SIGTERM, signal.SIGINT)}
        try:
            self.prepare()
        except OSError:
            self.reason = 'unsafe_home'
        return self

    def cancel(self, received, _frame):
        self.cancelled = received

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
        self.locked = True
        # A wrapper killed between phases must not permit reuse of an unfenced login.
        if os.read(self.lock_fd, 16):
            self.reason = 'cleanup_unconfirmed'
            self.cleanup_confirmed = False
            return
        os.write(self.lock_fd, b'active')
        # The version command sees no login or personal Codex config.
        with tempfile.TemporaryDirectory(prefix='pyry-e2e-codex-version-') as empty, tempfile.TemporaryFile() as output:
            version = self.run_owned([self.binary, '--version'], dict(self.env, CODEX_HOME=empty),
                                     timeout=min(10, self.PROBE_TIMEOUT), stdout=output)
            output.seek(0)
            match = re.fullmatch(rb'codex-cli (\d+)\.(\d+)\.(\d+)(-[^+\s]+)?(\+[^\s]+)?\s*', output.read(128))
        if version != 0:
            self.reason = 'binary_unavailable'
            return
        if (not match or tuple(int(match[i]) for i in (1, 2, 3)) < (0, 156, 1)
                or tuple(int(match[i]) for i in (1, 2, 3)) == (0, 156, 1) and match[4]):
            self.reason = 'version_incompatible'
            return
        self.version = '.'.join(str(int(match[i])) for i in (1, 2, 3))
        authenticated = self.run_owned([self.binary, 'login', 'status'], dict(self.env, CODEX_HOME=str(home)),
                                       timeout=self.PROBE_TIMEOUT, stdout=subprocess.DEVNULL)
        if authenticated != 0:
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
            if self.cleanup_confirmed and self.link and self.link.is_symlink() and self.link.lstat().st_ino == self.link_identity:
                self.link.unlink()
        finally:
            if self.lock_fd is not None:
                if self.locked and self.cleanup_confirmed:
                    os.ftruncate(self.lock_fd, 0)
                os.close(self.lock_fd)
                self.lock_fd = None
            for sig, handler in self.previous.items():
                signal.signal(sig, handler)
            self.previous = {}

    def __exit__(self, *_):
        self.close()

    def run_owned(self, command, env, *, timeout=None, stdout=None):
        """One exit fence for preparation, refusal and daemon execution."""
        if self.cancelled or not self.cleanup_confirmed:
            return 1
        lifetime_read, lifetime_write = os.pipe()
        env = dict(env, E2E_CODEX_LIFETIME_FD=str(lifetime_write))
        if self.lock_fd is not None:
            env['E2E_CODEX_LEASE_FD'] = str(self.lock_fd)
        descriptors = (lifetime_write,) + (() if self.lock_fd is None else (self.lock_fd,))
        child = None
        status = 1

        def witness_closed():
            return bool(select.select([lifetime_read], [], [], 0)[0]) and os.read(lifetime_read, 1) == b''

        def group_alive():
            # Inspect numeric ownership/state only, never command lines or environments.
            result = subprocess.run(['/bin/ps', '-axo', 'pgid=,stat='], capture_output=True,
                                    text=True, timeout=2, check=True)
            return any(int(group) == child.pid and not state.startswith('Z')
                       for group, state in (line.split() for line in result.stdout.splitlines()))

        def terminate_group(sig):
            try:
                os.killpg(child.pid, sig)
            except ProcessLookupError:
                pass

        def await_exit(bound):
            deadline = time.monotonic() + bound
            while time.monotonic() < deadline:
                child.poll()
                if not group_alive() and witness_closed():
                    return True
                time.sleep(0.05)
            return False

        try:
            child = subprocess.Popen(command, env=env, pass_fds=descriptors, start_new_session=True,
                                     stdout=stdout, stderr=subprocess.DEVNULL if stdout is not None else None)
            os.close(lifetime_write)
            lifetime_write = None
            deadline = None if timeout is None else time.monotonic() + timeout
            while child.poll() is None and not self.cancelled and (deadline is None or time.monotonic() < deadline):
                time.sleep(0.05)
            status = child.returncode if child.returncode is not None else 1
        except OSError:
            status = 1
        finally:
            if lifetime_write is not None:
                os.close(lifetime_write)
            try:
                if child is not None:
                    self.cleanup_confirmed = False
                    # Descriptor-closing agent descendants still belong to this group.
                    try:
                        if group_alive() or not witness_closed():
                            terminate_group(signal.SIGTERM)
                            self.cleanup_confirmed = await_exit(self.TERM_TIMEOUT)
                            if not self.cleanup_confirmed:
                                terminate_group(signal.SIGKILL)
                                self.cleanup_confirmed = await_exit(self.KILL_TIMEOUT)
                            print('event=codex_cleanup outcome=forced', flush=True)
                            status = status or 1
                        else:
                            self.cleanup_confirmed = True
                    except (OSError, subprocess.SubprocessError, ValueError):
                        try:
                            terminate_group(signal.SIGKILL)
                        except OSError:
                            pass
                    if not self.cleanup_confirmed:
                        print('event=codex_cleanup outcome=unconfirmed', flush=True)
                        status = 1
            finally:
                os.close(lifetime_read)
        return status if status >= 0 else 128 - status


def main(command):
    with CodexHomeLease(os.environ) as lease:
        if lease.cancelled:
            return 128 + lease.cancelled
        if not lease.cleanup_confirmed:
            print('event=codex_prerequisite outcome=cleanup_unconfirmed', flush=True)
            return 1
        env = dict(lease.env, E2E_CODEX_LEASE='1', E2E_CODEX_UNMET=lease.reason)
        # Instrumentation receives only a fixed reason, never the source login/home.
        if not lease.reason:
            env['E2E_CODEX_BIN'] = lease.binary
            print('event=codex_prerequisite outcome=ready', flush=True)
            print('codex revision: ' + lease.version, flush=True)
        else:
            env.pop('E2E_CODEX_BIN', None)
            print('event=codex_prerequisite outcome=' + lease.reason, flush=True)
        status = lease.run_owned(command, env)
        return 128 + lease.cancelled if lease.cancelled else status


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
