#!/usr/bin/env python3
"""Run device tests and emit fresh, counted JUnit XML for the dispatcher.

Build/harness output goes to stderr. Full original reports remain in the build
directory; the dispatcher report contains test names and outcomes, not app logs.
"""
import argparse
import contextlib
import fcntl
import json
import math
import os
import re
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
E2E_PACKAGE = "de.pyryco.mobile.e2e"
SCENARIOS = ("ping", "stream", "spinner", "tool", "tool-failed", "tool-progress", "reconnect", "offline-retry", "replay-order", "tool-then-text", "refusal", "mcp-failed", "context-overflow")

def curated_live_methods():
    """The method names on scripts/e2e-emulator.sh's LIVE curated list, in list order."""
    script = (ROOT / "scripts" / "e2e-emulator.sh").read_text()
    live = script[script.index('elif [ -n "${LIVE}" ]; then\n  # LIVE curates'):]
    branch = live[: live.index("\nelse\n")]
    assignments = [line for line in branch.splitlines() if line.lstrip().startswith('TEST_TARGET="')]
    return re.findall(r"#(interactiveTurn_\w+)", "\n".join(assignments))


# The live gate's executed-test floor: the size of the curated list, counted rather than kept by hand. Every
# listed method must execute, so a selected method that silently skips reddens the gate. Until 2026-10-01 this
# was a running total with one line per ticket, and two tickets adding live methods at once always conflicted
# on it (#1332, #1337). test_live_curated_list_matches_the_runnable_methods keeps the list complete.
LIVE_MINIMUM = len(curated_live_methods())

LIVE_CLASS = E2E_PACKAGE + ".InteractiveStreamE2ETest"


def parse_live_tests(value):
    """The --tests list as Class#method names, or None when any entry is not a live-class method."""
    names = [name for name in (part.strip() for part in value.split(",")) if name]
    prefix = LIVE_CLASS + "#"
    for name in names:
        method = name[len(prefix):]
        if not name.startswith(prefix) or not method.isidentifier():
            return None
    return list(dict.fromkeys(names)) or None


def claude_authenticated(env):
    try:
        auth = subprocess.run(["claude", "auth", "status"], env=env, capture_output=True, text=True)
        return auth.returncode == 0 and json.loads(auth.stdout).get("loggedIn") is True
    except (OSError, ValueError, AttributeError):
        return False


def fresh_reports(directory, started_ns):
    return sorted(p for p in directory.rglob("TEST-*.xml") if p.stat().st_mtime_ns >= started_ns)


def fresh_logcats(directory, started_ns):
    """The per-test logcat files this run wrote. The next run overwrites them, so the gate keeps copies (#1039).

    They are artifacts only: nothing from them reaches the dispatcher report or stdout.
    """
    return sorted(p for p in directory.rglob("logcat-*.txt") if p.stat().st_mtime_ns >= started_ns)


FOCUS_RECORD_TAG = "FocusRecord: "


def print_focus_records(paths):
    """Print each failing test's focus record from its logcat to stderr, where the dispatcher log keeps it (#1131).

    FocusRecordListener logs one record per failing device test: the window manager's focused window, focused
    app and any ANR dialog at the moment of failure. A passing run logs none, so it prints nothing here.
    """
    seen = set()
    for path in paths:
        try:
            lines = path.read_text(errors="replace").splitlines()
        except OSError:
            continue
        for line in lines:
            _, tag, record = line.partition(FOCUS_RECORD_TAG)
            if not tag or record in seen:
                continue
            seen.add(record)
            test = record.split(" ", 1)[0].removeprefix("test=")
            print(f"Android gate: focus record for {test}: {record}", file=sys.stderr)


def combine_reports(paths, expected_class=None):
    if not paths:
        raise ValueError("No fresh Android test reports were produced")
    combined = ET.Element("testsuites")
    executed = failed = 0
    seen = set()
    for path in paths:
        raw = path.read_text()
        if "<!DOCTYPE" in raw.upper() or "<!ENTITY" in raw.upper():
            raise ValueError("Unexpected XML declarations in Android report")
        try:
            source = ET.fromstring(raw)
        except ET.ParseError as error:
            raise ValueError("Incomplete Android test report") from error
        if source.tag not in ("testsuite", "testsuites"):
            raise ValueError("Expected a JUnit suite report")
        # AGP wraps the per-class suites in one device-level <testsuites>.
        # Validate summaries too, before flattening the leaf suites.
        for container in source.iter():
            if container.tag not in ("testsuite", "testsuites"):
                continue
            cases = container.findall(".//testcase")
            errors = sum(t.find("error") is not None for t in cases)
            failures = sum(t.find("failure") is not None for t in cases)
            skipped = sum(t.find("skipped") is not None for t in cases)
            if (int(container.get("tests", len(cases))) != len(cases)
                    or int(container.get("failures", failures)) != failures
                    or int(container.get("errors", errors)) != errors
                    or int(container.get("skipped", skipped)) != skipped
                    or container.find("error") is not None):
                raise ValueError("Android suite totals do not match the testcase records")
        for leaf in source.iter("testsuite"):
            cases = leaf.findall("testcase")
            if not cases:
                continue
            suite = ET.SubElement(combined, "testsuite", {k: v for k, v in leaf.attrib.items() if k in ("name", "tests", "failures", "errors", "skipped", "time")})
            for case in cases:
                class_name, name = case.get("classname", ""), case.get("name", "")
                if not class_name or not name:
                    raise ValueError("Android testcase has no class or method name")
                if expected_class and class_name != expected_class:
                    raise ValueError("Android report contains a different suite than requested")
                identity = (class_name, name)
                if identity in seen:
                    raise ValueError("Duplicate Android testcase reports")
                seen.add(identity)
                output = ET.SubElement(suite, "testcase", {"classname": class_name, "name": name})
                bad = case.find("failure") is not None or case.find("error") is not None
                skip = case.find("skipped") is not None
                if bad:
                    ET.SubElement(output, "failure")
                    failed += 1
                elif skip:
                    ET.SubElement(output, "skipped", {"message": "Skipped by Android test runner"})
                if bad or not skip:
                    executed += 1
    return ET.tostring(combined, encoding="unicode"), failed == 0, executed


# The e2e files no UI-suite test or the shared instrumentation runner uses. The suite leaves the e2e package out
# at run time, but the runner, the test application and the unrecognised-row sentinel in that package serve every
# device test. Only the explicitly listed e2e-only sources are safe to change without re-running it;
# a guard test keeps that true, including the peer's graph-lifecycle regression.
E2E_ONLY_SOURCES = tuple(f"app/src/androidTest/java/de/pyryco/mobile/e2e/{name}.kt" for name in
                         ("InteractiveStreamE2ETest", "DeterministicInteractiveStreamE2ETest", "SecondClientPeer",
                          "PeerIdentityLifecycleTest"))


def ui_suite_skippable(paths):
    """True when the branch changes something, and nothing the UI suite builds or runs.

    Docs, Markdown, scripts and the e2e-only sources qualify. A change to this script's own ui command is then
    first exercised by the next branch that touches the app. Measured 2026-09-23: six of one night's 37 verifier
    passes re-ran the five-minute suite for tickets that changed only live e2e tests and scripts.
    """
    return bool(paths) and all(p.startswith(("docs/", "scripts/")) or p.endswith(".md") or p in E2E_ONLY_SOURCES
                               for p in paths)


def device_only_classes():
    """The test classes under app/src/androidTest, outside the e2e package, as fully qualified names.

    Screen tests live in app/src/sharedTest and run under Robolectric in `./gradlew check`; only what needs
    a real device stays in androidTest. A file's class is its path, so the folder a test sits in is the
    whole rule and nothing else has to be kept in step.
    """
    root = ROOT / "app/src/androidTest/java"
    names = (".".join(path.relative_to(root).with_suffix("").parts)
             for path in sorted(root.rglob("*.kt")) if "@Test" in path.read_text())
    return [name for name in names if not name.startswith(E2E_PACKAGE + ".")]


def changed_paths(base="main"):
    """Tracked and untracked paths that differ from where this branch left base, or None when git cannot say."""
    try:
        def git(*args):
            return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout
        fork = git("merge-base", "HEAD", base).strip()
        listed = git("diff", "--name-only", fork) + git("ls-files", "--others", "--exclude-standard")
        return sorted(set(listed.split()))
    except (OSError, subprocess.CalledProcessError):
        return None


# ---- scripted-all: the eight scenarios on one emulator this script boots ------------------------------
# Each `scripted <scenario>` run has Gradle boot and tear down its own managed emulator, and Gradle's own
# waits for the device cost about 10 of each scenario's 23 seconds (measured 2026-09-23). scripted-all boots
# the managed device's AVD once, read-only from its snapshot, and runs every scenario against it through
# the harness's `connected` device, each with its own daemon, relay and pairing as before. Both APKs are installed
# once; before each scenario the app's data is cleared and the runtime permissions the install granted are granted
# again, so no app state carries over and no scenario pays Gradle's install and removal (2026-10-05).

def avd_root():
    return Path(os.environ.get("ANDROID_USER_HOME") or Path.home() / ".android") / "avd"


def managed_avd(device):
    """The AVD Gradle created for the managed device, or None before the ui gate has ever made it."""
    if device != "pixel2Api33Atd":
        return None
    home = avd_root() / "gradle-managed"
    found = sorted(home.glob("dev33_google_atd_*_Pixel_2.ini"))
    return (home, found[0].stem) if found else None


APP_ID = "de.pyryco.mobile"
APKS = ("app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")


def adb_call(env, serial, *args, capture=False):
    adb = str(Path(env["ANDROID_HOME"]) / "platform-tools" / "adb")
    output = {"capture_output": True, "text": True} if capture else {"stdout": sys.stderr, "stderr": sys.stderr}
    try:
        return subprocess.run([adb, "-s", serial, *args], timeout=300, **output)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"Android gate: adb {' '.join(args[:2])} failed: {error}", file=sys.stderr)
        return None


def install_once(env, serial):
    """Install both APKs as Gradle's device task does, with test packages allowed and runtime permissions granted.

    Returns the runtime permissions the install granted, for reset_app to grant again, or None when the install
    failed, in which case each scenario installs for itself through Gradle as before.
    """
    for apk in APKS:
        installed = adb_call(env, serial, "install", "-r", "-t", "-g", str(ROOT / apk))
        if installed is None or installed.returncode != 0:
            return None
    dump = adb_call(env, serial, "shell", "dumpsys", "package", APP_ID, capture=True)
    if dump is None or dump.returncode != 0:
        return None
    granted = re.findall(r"^\s+([\w.]+): granted=true", dump.stdout.partition("runtime permissions:")[2], re.MULTILINE)
    return list(dict.fromkeys(granted))


def reset_app(env, serial, granted):
    """Clear the app's data and grant back what clearing revoked, so a scenario starts as on a fresh install."""
    for args in (("shell", "pm", "clear", APP_ID), *(("shell", "pm", "grant", APP_ID, name) for name in granted)):
        done = adb_call(env, serial, *args)
        if done is None or done.returncode != 0:
            return False
    return True


def free_emulator_port(start=5600, end=5680):
    """An even console port whose adb port is free too. The emulator refuses a port in use, which boot retries."""
    for port in range(start, end, 2):
        try:
            with socket.socket() as a, socket.socket() as b:
                a.bind(("127.0.0.1", port))
                b.bind(("127.0.0.1", port + 1))
            return port
        except OSError:
            continue
    return None


def boot_emulator(env, avd_home, avd, timeout=180):
    """Boot the AVD headless on a free port; returns (serial, process) or (None, None)."""
    adb = str(Path(env["ANDROID_HOME"]) / "platform-tools" / "adb")
    emulator = str(Path(env["ANDROID_HOME"]) / "emulator" / "emulator")
    for _ in range(3):
        port = free_emulator_port()
        if port is None:
            return None, None
        serial = f"emulator-{port}"
        # The flags Gradle's managed device uses (emu-launch-params.txt), plus a fixed port.
        process = subprocess.Popen(
            [emulator, f"@{avd}", "-no-window", "-no-boot-anim", "-no-audio", "-gpu", "auto-no-window",
             "-force-snapshot-load", "-read-only", "-no-snapshot-save", "-port", str(port)],
            env={**env, "ANDROID_AVD_HOME": str(avd_home)}, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline and process.poll() is None:
            booted = subprocess.run([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                                    capture_output=True, text=True, timeout=30)
            if booted.stdout.strip() == "1":
                return serial, process
            time.sleep(0.5)
        stop_emulator(env, serial, process)
    return None, None


def stop_emulator(env, serial, process):
    if process is None or process.poll() is not None:
        return
    adb = str(Path(env["ANDROID_HOME"]) / "platform-tools" / "adb")
    subprocess.run([adb, "-s", serial, "emu", "kill"], capture_output=True, timeout=30)
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=20)


# ---- the host-wide device hold (#1071) -------------------------------------------------------------------
# Two runs driving the Gradle-managed AVD at once, from any worktrees, fail the window-focus device tests.
# Every device-using mode holds one kernel lock beside that AVD while it drives an emulator. The descriptor
# is never passed to a child, so the kernel drops the lock whenever this process exits, SIGKILL included.

DEFAULT_DEVICE_WAIT_SECONDS = 300
DEVICE_BUSY_EXIT = 75  # EX_TEMPFAIL: no test ran, so it is not a test result


class DeviceBusy(Exception):
    pass


def device_hold_path():
    """Beside gradle-managed rather than in it, so nothing that cleans that folder deletes a held lock file."""
    return avd_root() / "pyrycode-device-gate.lock"


def device_holder(fd):
    try:
        record = json.loads(os.pread(fd, 4096, 0))
        return f"{record['mode']} from {record['worktree']} since {record['started']}"
    except (OSError, ValueError, TypeError, KeyError):
        return "an unknown run"


@contextlib.contextmanager
def device_hold(mode, wait):
    """Hold the host's managed device for the block, waiting up to [wait] seconds; raises DeviceBusy."""
    path = device_hold_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    except OSError as error:
        sys.exit(f"Android gate failed: cannot take the device hold at {path}: {error}")
    try:
        start, announced = time.monotonic(), False
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                waited = time.monotonic() - start
                if waited >= wait:
                    raise DeviceBusy(f"gave up after {waited:.0f}s; held by {device_holder(fd)}") from None
                if not announced:
                    print(f"Android gate: device held by {device_holder(fd)}; waiting up to {wait:.0f}s",
                          file=sys.stderr)
                    announced = True
                time.sleep(min(1.0, wait - waited))
        if announced:
            print(f"Android gate: device free after {time.monotonic() - start:.0f}s waiting", file=sys.stderr)
        started = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        os.ftruncate(fd, 0)
        os.pwrite(fd, json.dumps({"mode": mode, "worktree": str(ROOT), "started": started,
                                  "pid": os.getpid()}).encode(), 0)
        yield
    finally:
        os.close(fd)


def build_apks(env, mode):
    """Build the app and test APKs before queueing for the device, and return Gradle's exit code.

    A cold build took 40 seconds to 3 minutes, and every other device run waited behind it while it held the
    device (measured 2026-10-04). Now the hold covers only boot, install and run: the device task and the
    emulator script find both APKs up to date. The e2e modes build with the emulator script's -P properties
    (GRADLE_BUILD_ARGS there), so its test task's up-to-date check matches.
    """
    command = [str(ROOT / "gradlew"), ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--console=plain"]
    if mode != "ui":
        command.insert(3, "-PuseRelayRepository=true")
    return subprocess.run(command, cwd=ROOT, env=env, stdout=sys.stderr, stderr=sys.stderr).returncode


def raise_interrupt(*_):
    raise KeyboardInterrupt


def run_scripted_all(env, run_dir, device):
    """Run every scripted scenario, on one self-booted emulator when the AVD exists. Returns the exit code."""
    expected_class = E2E_PACKAGE + ".DeterministicInteractiveStreamE2ETest"
    avd = managed_avd(device) if env.get("ANDROID_HOME") else None
    serial = process = None
    if avd is not None:
        serial, process = boot_emulator(env, *avd)
    if serial is None:
        print("Android gate: scripted-all could not boot its own emulator; each scenario uses the managed device",
              file=sys.stderr)
    # The dispatcher ends a timed-out gate with SIGTERM to its process group. Turn it into an exception so
    # the finally below still stops the emulator; the emulator shares the group, so it gets the signal too.
    signal.signal(signal.SIGTERM, raise_interrupt)
    results = ROOT / "app/build/outputs/androidTest-results"
    target = "connected" if serial else device
    directory = results / "connected/debug" if target == "connected" else results / "managedDevice/debug" / target
    all_paths, failed = [], []
    try:
        granted = install_once(env, serial) if serial else None
        if serial and granted is None:
            print("Android gate: scripted-all could not install the APKs once; each scenario installs its own",
                  file=sys.stderr)
        for scenario in SCENARIOS:
            scenario_env = {**env, "DETERMINISTIC": "1", "SCENARIO": scenario, "DEVICE": target}
            if serial:
                scenario_env["ANDROID_SERIAL"] = serial
            if granted is not None and reset_app(env, serial, granted):
                scenario_env["E2E_INSTALLED"] = "1"
            elif granted is not None:
                # Gradle's device task removes the app when it finishes, so the rest install their own too.
                print(f"Android gate: scripted {scenario}: could not clear the app; it and the rest install their own",
                      file=sys.stderr)
                granted = None
            started = time.time_ns()
            outcome = subprocess.run(["bash", str(ROOT / "scripts" / "e2e-emulator.sh")], cwd=ROOT,
                                     env=scenario_env, stdout=sys.stderr, stderr=sys.stderr)
            paths = fresh_reports(directory, started)
            logcats = fresh_logcats(directory, started)
            for index, path in enumerate(logcats):
                shutil.copy2(path, run_dir / f"{scenario}-{index}-{path.name}")
            print_focus_records(logcats)
            try:
                _, passed, executed = combine_reports(paths, expected_class)
            except ValueError as error:
                passed, executed = False, 0
                print(f"Android gate: scripted {scenario}: {error}", file=sys.stderr)
            ok = passed and executed >= 1 and outcome.returncode == 0
            if not ok:
                failed.append(scenario)
            print(f"Android gate: scripted {scenario}: {'pass' if ok else 'FAIL'}, {executed} executed", file=sys.stderr)
            # Copied before the next scenario overwrites the same report file on the connected device.
            for index, path in enumerate(paths):
                copy = run_dir / f"{scenario}-{index}-{path.name}"
                shutil.copy2(path, copy)
                all_paths.append(copy)
    finally:
        stop_emulator(env, serial, process)
    try:
        xml, _, executed = combine_reports(all_paths, expected_class)
    except ValueError as error:
        print(f"Android gate failed: {error}", file=sys.stderr)
        return 1
    (run_dir / "dispatcher.xml").write_text(xml + "\n")
    print(xml)
    print(f"Android gate: scripted-all {executed} executed; failed: {', '.join(failed) or 'none'}", file=sys.stderr)
    if executed < len(SCENARIOS):
        print(f"Android gate failed: Only {executed} Android tests executed; "
              f"required at least {len(SCENARIOS)}", file=sys.stderr)
        return 1
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("ui", "scripted", "scripted-all", "live"))
    parser.add_argument("scenario", nargs="?", choices=SCENARIOS)
    parser.add_argument("--tests", help="live only: a comma-separated Class#method list to run instead of the "
                        "curated list. The dispatcher's flake re-run and main comparison pass the failed tests here.")
    args = parser.parse_args()
    if (args.mode == "scripted") != (args.scenario is not None):
        parser.error("scripted requires one scenario; ui and live take no scenario")
    live_tests = None
    if args.tests is not None:
        if args.mode != "live":
            parser.error("--tests applies to live only")
        live_tests = parse_live_tests(args.tests)
        if live_tests is None:
            parser.error(f"--tests must be a comma-separated list of {LIVE_CLASS}#method names")
    device = os.environ.get("DEVICE", "pixel2Api33Atd")
    if not device.isalnum():
        parser.error("DEVICE must be an alphanumeric Gradle device name")
    try:
        wait = float(os.environ.get("ANDROID_GATE_WAIT_SECONDS", DEFAULT_DEVICE_WAIT_SECONDS))
    except ValueError:
        wait = -1
    if not math.isfinite(wait) or wait < 0:
        parser.error("ANDROID_GATE_WAIT_SECONDS must be a non-negative number of seconds")
    # UI_GATE_FULL=1 runs the suite even on a branch that cannot affect it.
    if args.mode == "ui" and os.environ.get("UI_GATE_FULL") != "1" and ui_suite_skippable(changed_paths()):
        print("Android gate: ui skipped; the branch changes only docs, scripts and e2e-only tests", file=sys.stderr)
        return 0
    artifacts = ROOT / "build" / "dispatcher-tests"
    artifacts.mkdir(parents=True, exist_ok=True)
    run_dir = Path(tempfile.mkdtemp(prefix=f"{args.mode}-", dir=artifacts))
    env = os.environ.copy()
    env.pop("ANTHROPIC_API_KEY", None)
    env["PYRY_FORCE_TEST_RUN"] = "1"
    # Each invocation owns a test daemon identity, never the user's running daemon.
    identity = "e2e-auto-" + uuid.uuid4().hex[:8]
    env.update(PYRY_NAME=identity, PAIR_NAME=identity)
    minimum, expected_class = 1, None
    if args.mode == "ui":
        # UI_DEVICE_ALL=1 is the in-depth run: the shared screen tests on the emulator as well, split
        # across two instances booted side by side. Measured 2026-09-22 on the dispatcher's own gate
        # logs: one instance took 8m25s for the full suite, two took 4m43s. By default only the
        # device-only classes run, few enough for one instance. The scripted scenarios keep one.
        full = os.environ.get("UI_DEVICE_ALL") == "1"
        shards = os.environ.get("UI_SHARDS", "2" if full else "1")
        if not shards.isdigit() or int(shards) < 1:
            parser.error("UI_SHARDS must be a positive integer")
        # Animations off on the managed emulator for the run (E2eInstrumentationRunner), as for the scripted runs.
        command = [str(ROOT / "gradlew"), f":app:{device}DebugAndroidTest", "--rerun",
                   f"-Pandroid.testInstrumentationRunnerArguments.notPackage={E2E_PACKAGE}",
                   f"-Pandroid.experimental.androidTest.numManagedDeviceShards={shards}",
                   "-Pandroid.testInstrumentationRunnerArguments.disableAnimations=true", "--console=plain"]
        if not full:
            classes = device_only_classes()
            if not classes:
                print("Android gate: no device-only test classes found under app/src/androidTest", file=sys.stderr)
                return 1
            command.insert(3, "-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(classes))
    else:
        env.pop("LIVE", None)
        env.pop("LIVE_TESTS", None)
        env.pop("DETERMINISTIC", None)
        env.pop("E2E_DISABLE_ANIMATIONS", None)
        env.pop("E2E_INSTALLED", None)
        command = ["bash", str(ROOT / "scripts" / "e2e-emulator.sh")]
        if args.mode in ("scripted", "scripted-all"):
            # Animations off on the emulator for each scenario (E2eInstrumentationRunner). The live run keeps them.
            env["E2E_DISABLE_ANIMATIONS"] = "1"
        if args.mode == "scripted":
            env.update(DETERMINISTIC="1", SCENARIO=args.scenario)
            expected_class = E2E_PACKAGE + ".DeterministicInteractiveStreamE2ETest"
        elif args.mode == "live":
            env["LIVE"] = "1"
            minimum = LIVE_MINIMUM
            expected_class = LIVE_CLASS
            if live_tests:
                # A chosen subset: the dispatcher judges each named test itself, and on main a test the
                # branch added does not exist, so the curated floor does not apply.
                env["LIVE_TESTS"] = ",".join(live_tests)
                minimum = 1
    if args.mode == "live":
        # Missing login is an environment failure, not a suite of product regressions.
        if not claude_authenticated(env):
            print("Android gate: Claude authentication unavailable. Run through the dispatcher's 1Password environment or sign in to Claude.", file=sys.stderr)
            return 1
    # Build test-only binaries from the configured sibling checkouts. Go's cache
    # keeps this cheap, and production daemon executables are never replaced.
    # One fixed folder per checkout, not the run's own: Go then skips relinking an
    # unchanged binary, about 15 seconds across the seven scripted scenarios.
    if args.mode != "ui":
        for variable, source_variable, package, binary in (
            ("PYRY_BIN", "PYRYCODE_SRC", "./cmd/pyry", "pyry"),
            ("RELAY_BIN", "PYRYCODE_RELAY_SRC", "./cmd/pyrycode-relay", "pyrycode-relay"),
        ):
            if variable == "RELAY_BIN" and args.mode == "live":
                continue
            if env.get(variable) or not env.get(source_variable):
                continue
            destination = ROOT / "build" / "e2e-bin" / binary
            destination.parent.mkdir(parents=True, exist_ok=True)
            build = subprocess.run(["go", "build", "-o", str(destination), package],
                                   cwd=env[source_variable], env=env, stdout=sys.stderr, stderr=sys.stderr)
            if build.returncode:
                print(f"Android gate: failed to build {binary}", file=sys.stderr)
                return 1
            env[variable] = str(destination)
    built = build_apks(env, args.mode)
    if built != 0:
        print(f"Android gate failed: the app and test APK build exited {built}; the device was not taken",
              file=sys.stderr)
        return 1
    env["E2E_APKS_BUILT"] = "1"
    try:
        with device_hold(args.mode, wait):
            print(f"Android gate: {args.mode} {args.scenario or ''}; artifacts: {run_dir}", file=sys.stderr)
            if args.mode == "scripted-all":
                return run_scripted_all(env, run_dir, device)
            return run_on_device(command, env, run_dir, device, minimum, expected_class)
    except DeviceBusy as busy:
        print(f"Android gate: device busy, not a test result: {busy}", file=sys.stderr)
        return DEVICE_BUSY_EXIT


def run_on_device(command, env, run_dir, device, minimum, expected_class):
    started = time.time_ns()
    try:
        outcome = subprocess.run(command, cwd=ROOT, env=env, stdout=sys.stderr, stderr=sys.stderr)
        results = ROOT / "app/build/outputs/androidTest-results"
        directory = results / "connected/debug" if device == "connected" else results / "managedDevice/debug" / device
        paths = fresh_reports(directory, started)
        for index, path in enumerate(paths):
            shutil.copy2(path, run_dir / f"{index}-{path.name}")
        # Kept before the report is judged: a failing run is the one whose logcat someone needs to read.
        logcats = fresh_logcats(directory, started)
        for index, path in enumerate(logcats):
            shutil.copy2(path, run_dir / f"{index}-{path.name}")
        print_focus_records(logcats)
        xml, passed, executed = combine_reports(paths, expected_class)
        (run_dir / "dispatcher.xml").write_text(xml + "\n")
        print(xml)
        print(f"Android gate: {executed} executed; process exit {outcome.returncode}", file=sys.stderr)
        if executed < minimum:
            print(f"Android gate failed: Only {executed} Android tests executed; "
                  f"required at least {minimum}", file=sys.stderr)
            return 1
        return 0 if passed and outcome.returncode == 0 else 1
    except (ValueError, OSError) as error:
        print(f"Android gate failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
