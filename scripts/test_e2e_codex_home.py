import importlib.util
import os
import signal
import time
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


class CodexHomeLeaseTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('codex_home', Path(__file__).with_name('e2e-codex-home.py'))
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.operator = self.root / 'operator'
        self.operator.mkdir()
        self.home = self.root / 'dedicated home'
        self.home.mkdir(mode=0o700)
        self.login = self.home / 'auth.json'
        self.login.write_text('fixture-secret-do-not-copy')
        self.binary = self.root / 'fake codex'
        self.binary.write_text('#!/bin/sh\ncase "$1" in --version) echo "codex-cli 0.156.1";; login) exit 0;; esac\n')
        self.binary.chmod(0o700)
        self.env = dict(os.environ, HOME=str(self.operator), PYRY_NAME='e2e-fixture',
                        PYRY_CODEX_CAPTURE_HOME=str(self.home), PYRY_CODEX_CAPTURE_BIN=str(self.binary))
        self.env.pop('CODEX_HOME', None)
        self.link = self.operator / '.pyry/e2e-fixture/codex-home'

    def lease(self, env=None):
        return self.module.CodexHomeLease(env or self.env)

    def test_links_without_copying_and_preserves_source_login_after_failure(self):
        with self.assertRaisesRegex(RuntimeError, 'fixture-failure'):
            with self.lease() as lease:
                self.assertEqual('', lease.reason)
                self.assertEqual(self.home, self.link.resolve())
                self.assertEqual(self.binary, Path(lease.binary))
                raise RuntimeError('fixture-failure')
        self.assertFalse(self.link.is_symlink())
        self.assertEqual('fixture-secret-do-not-copy', self.login.read_text())
        with self.lease() as retry:
            self.assertEqual('', retry.reason)

    def test_real_second_process_and_alias_cannot_use_held_home(self):
        alias = self.root / 'alias'
        alias.symlink_to(self.home)
        with self.lease() as first:
            self.assertEqual('', first.reason)
            env = dict(self.env, PYRY_CODEX_CAPTURE_HOME=str(alias), PYRY_NAME='e2e-other')
            code = "import runpy; m=runpy.run_path(os.environ['HELPER']);\nwith m['CodexHomeLease'](os.environ) as l: print(l.reason)"
            result = subprocess.run([sys.executable, '-c', 'import os; '+code],
                                    env=dict(env, HELPER=str(Path(__file__).with_name('e2e-codex-home.py'))),
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('home_busy', result.stdout.strip())

    def test_refuses_personal_production_and_ambient_homes_including_symlinks(self):
        for path in (self.operator, self.operator / '.codex', self.operator / '.pyry/prod/codex-home',
                     self.operator / '.pyrycode/prod/codex-home'):
            path.mkdir(parents=True, exist_ok=True)
            alias = self.root / 'unsafe-alias'
            alias.symlink_to(path)
            with self.subTest(path=path), self.lease(dict(self.env, PYRY_CODEX_CAPTURE_HOME=str(alias))) as lease:
                self.assertEqual('unsafe_home', lease.reason)
            alias.unlink()
        with self.lease(dict(self.env, CODEX_HOME=str(self.home))) as lease:
            self.assertEqual('unsafe_home', lease.reason)

    def test_refuses_non_test_instance_and_existing_link_without_mutation(self):
        with self.lease(dict(self.env, PYRY_NAME='production')) as lease:
            self.assertEqual('unsafe_home', lease.reason)
        self.link.parent.mkdir(parents=True)
        self.link.mkdir()
        with self.lease() as lease:
            self.assertEqual('instance_home_exists', lease.reason)
        self.assertTrue(self.link.is_dir())
        self.assertFalse(self.link.is_symlink())

    def test_version_auth_and_missing_prerequisites_are_static_and_bounded(self):
        for script, reason in [('#!/bin/sh\necho fixture-secret; exit 1\n', 'binary_unavailable'),
                               ('#!/bin/sh\necho codex-cli 0.156.0\n', 'version_incompatible'),
                               ('#!/bin/sh\necho codex-cli 0.156.1-beta\n', 'version_incompatible'),
                               ('#!/bin/sh\nif [ "$1" = --version ]; then echo codex-cli 0.156.1; else echo fixture-secret >&2; exit 1; fi\n', 'auth_unavailable')]:
            self.binary.write_text(script)
            with self.subTest(reason=reason), self.lease() as lease:
                self.assertEqual(reason, lease.reason)
                self.assertFalse(self.link.is_symlink())
        with self.lease(dict(self.env, PYRY_CODEX_CAPTURE_HOME='')) as lease:
            self.assertEqual('configuration_missing', lease.reason)

    def test_version_probe_uses_empty_home_and_auth_uses_only_dedicated_home(self):
        record = self.root / 'probe-record'
        self.binary.write_text('#!/bin/sh\nprintf "%s\\t%s\\n" "$1" "$CODEX_HOME" >> "$RECORD"\nif [ "$1" = --version ]; then echo codex-cli 0.157.0-beta+fixture; fi\n')
        with self.lease(dict(self.env, RECORD=str(record))) as lease:
            self.assertEqual('', lease.reason)
        version, login = [line.split('\t') for line in record.read_text().splitlines()]
        self.assertNotEqual(str(self.home), version[1])
        self.assertFalse(Path(version[1]).exists())
        self.assertEqual(['login', str(self.home)], login)

    def test_wrapper_failure_and_termination_release_lease_and_owned_link(self):
        helper = str(Path(__file__).with_name('e2e-codex-home.py'))
        result = subprocess.run([sys.executable, helper, '/bin/sh', '-c', 'exit 7'], env=self.env,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(7, result.returncode)
        self.assertNotIn('fixture-secret', result.stdout + result.stderr)
        self.assertFalse(self.link.is_symlink())
        ready = self.root / 'ready'
        cleanup = self.root / 'cleaned'
        child = self.root / 'child.py'
        child.write_text("import signal, sys, time\nfrom pathlib import Path\n"
                         "def stop(*_):\n Path(sys.argv[2]).write_text('done'); sys.exit(0)\n"
                         "signal.signal(signal.SIGTERM, stop)\nPath(sys.argv[1]).write_text('ready')\n"
                         "while True: time.sleep(0.05)\n")
        process = subprocess.Popen([sys.executable, helper, sys.executable, str(child), str(ready), str(cleanup)],
                                   env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 10
            while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists())
            process.send_signal(signal.SIGTERM)
            stdout, stderr = process.communicate(timeout=10)
            self.assertEqual(0, process.returncode, stderr)
            self.assertTrue(cleanup.exists())
            self.assertFalse(self.link.is_symlink())
            self.assertNotIn('fixture-secret', stdout + stderr)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
        with self.lease() as retry:
            self.assertEqual('', retry.reason)


    def test_default_live_name_reaches_real_wrapper(self):
        script = Path(__file__).with_name('e2e-emulator.sh').read_text()
        start = script.index('# #2052:')
        block = script[start:script.index('\nGRADLEW=', start)]
        fake_bash = self.root / 'bash'
        fake_bash.write_text('#!/bin/sh\n[ "$PYRY_NAME" = e2e-live ] && [ -L "$HOME/.pyry/e2e-live/codex-home" ]\n')
        fake_bash.chmod(0o700)
        env = dict(self.env, REPO_ROOT=str(Path(__file__).resolve().parent.parent), LIVE='1',
                   DETERMINISTIC='', LIVE_TESTS='', PATH=str(self.root) + os.pathsep + os.environ['PATH'])
        env.pop('PYRY_NAME', None)
        env.pop('E2E_CODEX_LEASE', None)
        snippet = self.root / 'selection.sh'
        snippet.write_text('set -euo pipefail\nPYRY_NAME="${PYRY_NAME:-e2e-live}"\n' + block)
        result = subprocess.run(['/bin/bash', str(snippet)],
                                env=env, capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('outcome=ready', result.stdout)
        self.assertFalse((self.operator / '.pyry/e2e-live/codex-home').is_symlink())

    def test_shell_or_controller_failure_cannot_release_home_with_live_daemon(self):
        helper = str(Path(__file__).with_name('e2e-codex-home.py'))
        controller = str(Path(__file__).with_name('e2e-daemon-fault.py'))
        daemon = self.root / 'daemon.py'
        daemon.write_text("import os, signal, time\nfrom pathlib import Path\n"
                          "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                          "Path(os.environ['DAEMON_READY']).write_text(str(os.getpid()))\n"
                          "while True: time.sleep(0.05)\n")
        for failure in ('shell', 'controller', 'termination'):
            ready = self.root / ('ready-' + failure)
            shell = self.root / 'owned.sh'
            shell.write_text('set -eu\npython3 "$CONTROLLER" --port-file "$PORT_FILE" --log "$LOG_FILE" '
                             '--control-socket "$CONTROL_SOCKET" -- python3 "$DAEMON" &\ncontroller=$!\necho "$controller" > "$CONTROLLER_PID"\n'
                             'while [ ! -f "$DAEMON_READY" ]; do sleep 0.02; done\n'
                             + {'shell': 'exit 7\n', 'controller': 'kill -KILL "$controller"; wait "$controller"\n',
                                'termination': "trap 'kill \"$controller\"; wait \"$controller\"' EXIT\nwait \"$controller\"\n"}[failure])
            env = dict(self.env, CONTROLLER=controller, DAEMON=str(daemon), DAEMON_READY=str(ready),
                       PORT_FILE=str(self.root / 'port'), LOG_FILE=str(self.root / 'daemon.log'),
                       CONTROL_SOCKET=str(self.root / 'control.sock'), CONTROLLER_PID=str(self.root / 'controller-pid'))
            process = subprocess.Popen([sys.executable, helper, '/bin/bash', str(shell)], env=env,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                deadline = time.monotonic() + 10
                while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                if not ready.exists():
                    os.set_blocking(process.stderr.fileno(), False)
                    self.fail(os.read(process.stderr.fileno(), 8192).decode())
                pid = int(ready.read_text())
                with self.lease(dict(self.env, PYRY_NAME='e2e-contender')) as contender:
                    self.assertEqual('home_busy', contender.reason)
                self.assertTrue(self.link.is_symlink())
                if failure == 'termination':
                    process.terminate()
                process.communicate(timeout=25)
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
                self.assertFalse(self.link.is_symlink())
                self.assertEqual('fixture-secret-do-not-copy', self.login.read_text())
                with self.lease() as retry:
                    self.assertEqual('', retry.reason)
                if failure != 'termination':
                    self.assertNotEqual(0, process.returncode)
            finally:
                for record in (ready, self.root / 'controller-pid'):
                    if record.exists():
                        try:
                            os.kill(int(record.read_text()), signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                if process.poll() is None:
                    process.terminate()
                process.communicate(timeout=25)


    def test_wrapper_spawn_failure_releases_home(self):
        original = self.module.subprocess.Popen
        def launch(command, **kwargs):
            if command == ['/missing-fixture']:
                raise OSError('fixture')
            return original(command, **kwargs)
        with patch.dict(os.environ, self.env, clear=True), patch.object(self.module.subprocess, 'Popen', side_effect=launch):
            with self.assertRaises(OSError):
                self.module.main(['/missing-fixture'])
        self.assertFalse(self.link.is_symlink())
        with self.lease() as retry:
            self.assertEqual('', retry.reason)

    def test_codex_never_inherits_ambient_api_credentials(self):
        with self.lease(dict(self.env, OPENAI_API_KEY='fixture-secret', CODEX_API_KEY='fixture-secret',
                             OPENAI_BASE_URL='https://private.invalid')) as lease:
            self.assertEqual('', lease.reason)
            self.assertNotIn('OPENAI_API_KEY', lease.env)
            self.assertNotIn('CODEX_API_KEY', lease.env)
            self.assertNotIn('OPENAI_BASE_URL', lease.env)


    def test_probe_timeouts_release_resources_without_exposing_process_output(self):
        real_run = subprocess.run
        for timed_command, expected, timeout in [('--version', 'binary_unavailable', 10), ('login', 'auth_unavailable', 15)]:
            def run(argv, **kwargs):
                if argv[1] == timed_command:
                    self.assertEqual(timeout, kwargs['timeout'])
                    raise subprocess.TimeoutExpired(argv, timeout, output=b'fixture-secret', stderr=b'fixture-secret')
                return real_run(argv, **kwargs)
            with patch.object(self.module.subprocess, 'run', side_effect=run), self.lease() as lease:
                self.assertEqual(expected, lease.reason)
                self.assertFalse(self.link.is_symlink())
            with self.lease() as retry:
                self.assertEqual('', retry.reason)


    def test_refuses_symlink_lock_and_preserves_its_target(self):
        victim = self.root / 'victim'
        victim.write_text('keep')
        (self.home / '.mobile-e2e.lock').symlink_to(victim)
        with self.lease() as lease:
            self.assertEqual('unsafe_home', lease.reason)
        self.assertEqual('keep', victim.read_text())


class CodexShellIntegrationTest(unittest.TestCase):
    def test_only_selected_live_method_provisions_and_deterministic_is_independent(self):
        script = Path(__file__).with_name('e2e-emulator.sh').read_text()
        start = script.index('# #2052:')
        block = script[start:script.index('\nGRADLEW=', start)]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'scripts').mkdir()
            (root / 'scripts/e2e-codex-home.py').write_text('print("lease-wrapper-called")\n')
            for live, deterministic, tests, expected in [('1', '', '', True), ('1', '', 'other#method', False),
                    ('', '', '', False), ('1', '1', '', False), ('1', '', 'C#interactiveTurn_switchAgentFromModelPick_confirmsAndReplies', True)]:
                env = dict(os.environ, REPO_ROOT=str(root), LIVE=live, DETERMINISTIC=deterministic, LIVE_TESTS=tests)
                env.pop('E2E_CODEX_LEASE', None)
                snippet = root / 'selection.sh'
                snippet.write_text('set -euo pipefail\nPYRY_NAME=e2e-live\n'+block)
                result = subprocess.run(['/bin/bash', str(snippet)], env=env,
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(expected, 'lease-wrapper-called' in result.stdout)


if __name__ == '__main__':
    unittest.main()
