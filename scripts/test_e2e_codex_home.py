import importlib.util
import os
import signal
import shutil
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

    def run_refusal_launch(self, env, *, live="1", deterministic="", tests=""):
        """Real wrapper and shell admission, then the real primary launch with an inert daemon."""
        scripts = self.root / 'scripts'
        scripts.mkdir(exist_ok=True)
        shutil.copyfile(Path(__file__).with_name('e2e-codex-home.py'), scripts / 'e2e-codex-home.py')
        script = Path(__file__).with_name('e2e-emulator.sh').read_text()
        start = script.index('DAEMON_COMMAND=(env)')
        launch = script[start:script.index('\nFAULT_PORT_FILE=', start)]
        shell = scripts / 'e2e-emulator.sh'
        selection = script[script.index('if [ -n "${DETERMINISTIC}" ]; then\n  TEST_TARGET='):script.index('\nlog "running ${DEVICE}DebugAndroidTest') ]
        args = script[script.index('GRADLE_TEST_ARGS='):script.index('if [ -n "${STOP_TASK_URL:-}" ]; then')]
        shell.write_text(script[:script.index('\nWORK_DIR=')] + '\n' + launch + '\n' + selection + '\n' + args + '\n"${DAEMON_COMMAND[@]}"\n"${GRADLEW}" "${GRADLE_TEST_ARGS[@]}" class="${TEST_TARGET}"\n')
        gradle = self.root / 'gradlew'
        gradle.write_text('#!/usr/bin/env python3\nimport os, sys, json\nfrom pathlib import Path\n'
                          'Path(os.environ["GRADLE_RECORD"]).write_text(json.dumps(sys.argv[1:]))\nsys.exit(1 if any("codexPrerequisiteOnly=true" in a for a in sys.argv) else 0)\n')
        gradle.chmod(0o700)
        daemon = self.root / 'pyry'
        # Mirror daemon startup: prepareCodexHome follows the home before checking the binary.
        daemon.write_text('#!/usr/bin/env python3\nimport os\nfrom pathlib import Path\n'
                          'home = Path(os.environ["HOME"]) / ".pyry" / os.environ["PYRY_NAME"] / "codex-home"\n'
                          'home.mkdir(parents=True, exist_ok=True)\n(home / "config.toml").write_text("daemon-probed")\n'
                          'Path(os.environ["DAEMON_RECORD"]).write_text(__import__("json").dumps(__import__("sys").argv[1:]))\n')
        daemon.chmod(0o700)
        return subprocess.run(['/bin/bash', str(shell)], capture_output=True, text=True, timeout=20,
                              env=dict(env, LIVE=live, DETERMINISTIC=deterministic, LIVE_TESTS=tests, E2E_LOGIN_TRIED='1',
                                       PORT='12345', PYRY_BIN=str(daemon), GRADLE_RECORD=str(self.root / 'gradle.json'),
                                       DAEMON_RECORD=str(self.root / 'daemon.json'), ISO_HOME=str(self.operator),
                                       TEST_METHOD='scripted', SCENARIO='other', FAKE_BIN='/fixture/fake', FIXTURE_FILE='fixture', FIXTURE_FILE_2=''))

    def assert_counted_refusal(self, result, reason):
        import json
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn('outcome=' + reason, result.stdout)
        args = json.loads((self.root / 'gradle.json').read_text())
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.e2e.'
                      'InteractiveStreamE2ETest#interactiveTurn_switchAgentFromModelPick_confirmsAndReplies', args)
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.codexPrerequisite=' + reason, args)
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.codexPrerequisiteOnly=true', args)
        self.assertIn('--rerun', args)
        self.assertFalse(any('relayUrl=' in arg or 'token=' in arg for arg in args))
        self.assertNotIn('fixture-secret', result.stdout + result.stderr)

    def test_refused_existing_link_never_reaches_daemon_preparation(self):
        for target in (self.home, self.operator / '.codex', self.operator / '.pyry/production/codex-home'):
            with self.subTest(target=target):
                target.mkdir(parents=True, exist_ok=True)
                config = target / 'config.toml'
                config.write_text('unchanged')
                self.link.parent.mkdir(parents=True, exist_ok=True)
                self.link.symlink_to(target)
                try:
                    result = self.run_refusal_launch(self.env)
                    self.assertEqual('unchanged', config.read_text())
                    self.assertEqual(target, self.link.resolve())
                    self.assert_counted_refusal(result, 'instance_home_exists')
                finally:
                    self.link.unlink()
        self.assertEqual('fixture-secret-do-not-copy', self.login.read_text())

    def test_missing_prerequisites_leave_instance_clean_for_corrected_retry(self):
        result = self.run_refusal_launch(dict(self.env, PYRY_CODEX_CAPTURE_HOME=''))
        self.assertFalse(self.link.exists())
        self.assert_counted_refusal(result, 'configuration_missing')
        retry = self.run_refusal_launch(self.env)
        self.assertEqual(0, retry.returncode, retry.stderr)
        self.assertIn('outcome=ready', retry.stdout)
        self.assertEqual('daemon-probed', (self.home / 'config.toml').read_text())
        self.assertFalse(self.link.is_symlink())
        self.assertEqual('fixture-secret-do-not-copy', self.login.read_text())

    def test_shell_or_controller_failure_cannot_release_home_with_live_daemon(self):
        helper = str(Path(__file__).with_name('e2e-codex-home.py'))
        controller = str(Path(__file__).with_name('e2e-daemon-fault.py'))
        daemon = self.root / 'daemon.py'
        daemon.write_text("import os, signal, time\nfrom pathlib import Path\n"
                          "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                          "ready = Path(os.environ['DAEMON_READY'])\n"
                          "temporary = ready.with_suffix('.tmp')\n"
                          "temporary.write_text(str(os.getpid()))\ntemporary.replace(ready)\n"
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


    def test_descriptor_closing_agent_is_fenced_even_after_witness_eof(self):
        self.assert_owned_teardown(cancel=False)

    def test_cancellation_bounds_unresponsive_shell_and_foreground_agent(self):
        self.assert_owned_teardown(cancel=True)

    def test_ignoring_probe_descendant_requires_kill_before_home_reuse(self):
        self.assert_owned_teardown(cancel=True, phase="login", mode="kill")

    def assert_owned_teardown(self, cancel, phase="shell", mode="cancel"):
        helper = str(Path(__file__).with_name('e2e-codex-home.py'))
        ready = self.root / 'agent-ready'
        terminating = self.root / 'agent-terminating'
        release = self.root / 'agent-release'
        agent = self.root / 'agent.py'
        agent.write_text("import os, signal, time\nfrom pathlib import Path\n"
                         "for key in ('E2E_CODEX_LEASE_FD', 'E2E_CODEX_LIFETIME_FD'):\n if key in os.environ: os.close(int(os.environ[key]))\n"
                         "def stop(*_):\n Path(os.environ['TERMINATING']).touch()\n"
                         "signal.signal(signal.SIGTERM, stop)\nPath(os.environ['READY']).write_text(str(os.getpid()))\n"
                         + ("while True: time.sleep(0.02)\n" if mode == "kill" else "while not Path(os.environ['RELEASE']).exists(): time.sleep(0.02)\n"))
        shell = self.root / 'shell.py'
        shell.write_text("import os, signal, subprocess, sys, time\nfrom pathlib import Path\n"
                         "signal.signal(signal.SIGTERM, signal.SIG_IGN)\nPath(os.environ['PARENT_PID']).write_text(str(os.getpid()))\n"
                         "subprocess.Popen([sys.executable, os.environ['AGENT']], close_fds=False)\n"
                         "while not Path(os.environ['READY']).exists(): time.sleep(0.02)\n"
                         + ("while True: time.sleep(0.02)\n" if cancel else "sys.exit(7)\n"))
        if phase != 'shell':
            wait = "sys.exit(1)\n" if mode == 'refusal' else "while True: time.sleep(0.02)\n"
            self.binary.write_text('#!' + sys.executable + '\n'
                                   'import os, sys\nif sys.argv[1] != os.environ["PHASE"]:\n print("codex-cli 0.156.1"); sys.exit(0)\n'
                                   'print("fixture-secret"); print("fixture-secret", file=sys.stderr)\n'
                                   + shell.read_text().replace("sys.exit(7)\n", wait))
        driver = self.root / 'wrapper.py'
        driver.write_text("import runpy, sys\nm=runpy.run_path(sys.argv[1])\n"
                          + ("m['CodexHomeLease'].PROBE_TIMEOUT = 0.5\n" if mode == 'timeout' else '')
                          + ("m['CodexHomeLease'].TERM_TIMEOUT = 0.8\n" if phase != 'shell' else '')
                          + "sys.exit(m['main'](sys.argv[2:]))\n")
        env = dict(self.env, PHASE=phase, PARENT_PID=str(self.root / "parent-pid"), READY=str(ready), TERMINATING=str(terminating), RELEASE=str(release), AGENT=str(agent))
        command = [sys.executable, str(shell)] if phase == 'shell' else [sys.executable, '-c', 'import sys; sys.exit(23)']
        process = subprocess.Popen([sys.executable, str(driver), helper, *command], env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        pid = None
        try:
            deadline = time.monotonic() + 8
            while not ready.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists())
            pid = int(ready.read_text())
            if cancel:
                process.terminate()
            deadline = time.monotonic() + 4
            while not terminating.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(terminating.exists(), 'actual agent must be terminated even after pipe EOF')
            self.assertIsNone(process.poll())
            self.assertEqual(phase == 'shell', self.link.is_symlink())
            with self.lease(dict(self.env, PYRY_NAME='e2e-contender')) as contender:
                self.assertEqual('home_busy', contender.reason)
            if mode != 'kill': release.touch()
            stdout, stderr = process.communicate(timeout=20)
            self.assertNotEqual(0, process.returncode)
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)
            with self.assertRaises(ProcessLookupError):
                os.kill(int((self.root / 'parent-pid').read_text()), 0)
            self.assertFalse(self.link.is_symlink())
            self.assertEqual('fixture-secret-do-not-copy', self.login.read_text())
            self.assertNotIn('fixture-secret', stdout + stderr)
            if phase != 'shell' and not cancel:
                self.assertIn('outcome=' + ('auth_unavailable' if phase == 'login' else 'binary_unavailable'), stdout)
            self.binary.write_text('#!/bin/sh\necho codex-cli 0.156.1\n')
            with self.lease() as retry:
                self.assertEqual('', retry.reason)
        finally:
            release.touch()
            for record in (ready, self.root / 'parent-pid'):
                if record.exists():
                    try:
                        os.kill(int(record.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass
            for record in (ready, terminating, release):
                record.unlink(missing_ok=True)
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=5)
            if pid is not None:
                try:
                    os.kill(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def test_wrapper_spawn_failure_releases_home(self):
        original = self.module.subprocess.Popen
        def launch(command, **kwargs):
            if command == ['/missing-fixture']:
                raise OSError('fixture')
            return original(command, **kwargs)
        with patch.dict(os.environ, self.env, clear=True), patch.object(self.module.subprocess, 'Popen', side_effect=launch):
            self.assertEqual(1, self.module.main(['/missing-fixture']))
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


    def test_authentication_cancellation_keeps_exclusivity_through_teardown(self):
        self.assert_owned_teardown(cancel=True, phase='login')

    def test_probe_timeout_and_refusal_fence_descriptor_closing_descendants(self):
        for phase in ('login', '--version'):
            for mode in ('timeout', 'refusal'):
                with self.subTest(phase=phase, mode=mode):
                    self.assert_owned_teardown(cancel=False, phase=phase, mode=mode)

    def test_unconfirmed_exit_retains_link_and_refuses_reuse(self):
        with self.lease() as lease:
            with patch.object(self.module.subprocess, 'run', side_effect=subprocess.TimeoutExpired('ps', 2)):
                self.assertEqual(1, lease.run_owned([sys.executable, '-c', 'pass'], lease.env))
            self.assertFalse(lease.cleanup_confirmed)
        self.assertTrue(self.link.is_symlink())
        with self.lease() as retry:
            self.assertEqual('cleanup_unconfirmed', retry.reason)
        self.assertEqual('fixture-secret-do-not-copy', self.login.read_text())

    def test_refuses_symlink_lock_and_preserves_its_target(self):
        victim = self.root / 'victim'
        victim.write_text('keep')
        (self.home / '.mobile-e2e.lock').symlink_to(victim)
        with self.lease() as lease:
            self.assertEqual('unsafe_home', lease.reason)
        self.assertEqual('keep', victim.read_text())
    def test_effective_default_live_and_focused_selections_drive_provisioning_and_launch(self):
        import json
        cls = 'de.pyryco.mobile.e2e.InteractiveStreamE2ETest'
        switch = cls + '#interactiveTurn_switchAgentFromModelPick_confirmsAndReplies'
        for live, deterministic, tests, selected in [('', '', '', True), ('1', '', '', True),
                ('', '', switch, True), ('1', '', switch, True), ('1', '', cls, True),
                ('', '', cls+'#other', False), ('1', '', cls+'#other', False), ('1', '', switch+'Extra', False), ('', '1', '', False)]:
            with self.subTest(live=live, deterministic=deterministic, tests=tests):
                env = dict(self.env)
                if not tests and not deterministic: env.pop('PYRY_NAME', None)
                result = self.run_refusal_launch(env, live=live, deterministic=deterministic, tests=tests)
                if self.link.is_dir() and not self.link.is_symlink():
                    (self.link / "config.toml").unlink()
                    self.link.rmdir()
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(selected, 'outcome=ready' in result.stdout)
                daemon = json.loads((self.root / 'daemon.json').read_text())
                args = json.loads((self.root / 'gradle.json').read_text())
                self.assertEqual(selected, '-pyry-codex=' + str(self.binary) in daemon)
                if 'PYRY_NAME' not in env:
                    self.assertIn('-pyry-name=' + ('e2e-live' if live else 'e2e-emulator'), daemon)
                self.assertEqual(selected, '-Pandroid.testInstrumentationRunnerArguments.codexPrerequisite=' in args)
                if tests: self.assertIn('class='+tests, args)



if __name__ == '__main__':
    unittest.main()
