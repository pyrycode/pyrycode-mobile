#!/usr/bin/env python3
"""Run device tests and emit fresh, counted JUnit XML for the dispatcher.

Build/harness output goes to stderr. Full original reports remain in the build
directory; the dispatcher report contains test names and outcomes, not app logs.
"""
import argparse
import json
import os
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
SCENARIOS = ("ping", "stream", "spinner", "tool", "tool-failed", "tool-progress", "reconnect", "replay-order")
# The live gate's executed-test floor: the size of scripts/e2e-emulator.sh's LIVE curated list (#848),
# so a method silently dropped from that list reddens the gate. Raise it with the list.
# 20 while #977 keeps the #687 bypass method out of the list; #981 restores it and 21.
# #965 adds the stop method on top: 21 while #687 stays out, 22 once #981 restores it.
LIVE_MINIMUM = 21


def claude_authenticated(env):
    try:
        auth = subprocess.run(["claude", "auth", "status"], env=env, capture_output=True, text=True)
        return auth.returncode == 0 and json.loads(auth.stdout).get("loggedIn") is True
    except (OSError, ValueError, AttributeError):
        return False


def fresh_reports(directory, started_ns):
    return sorted(p for p in directory.rglob("TEST-*.xml") if p.stat().st_mtime_ns >= started_ns)


def combine_reports(paths, minimum, expected_class=None):
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
    if executed < minimum:
        raise ValueError(f"Only {executed} Android tests executed; required at least {minimum}")
    return ET.tostring(combined, encoding="unicode"), failed == 0, executed


# The e2e files no UI-suite test or the shared instrumentation runner uses. The suite leaves the e2e package out
# at run time, but the runner, the test application and the unrecognised-row sentinel in that package serve every
# device test, so only these three are safe to change without re-running it. A guard test keeps that true.
E2E_ONLY_SOURCES = tuple(f"app/src/androidTest/java/de/pyryco/mobile/e2e/{name}.kt" for name in
                         ("InteractiveStreamE2ETest", "DeterministicInteractiveStreamE2ETest", "SecondClientPeer"))


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
# the harness's `connected` device, each with its own daemon, relay and pairing as before. The app is
# reinstalled per scenario, so no app state carries over.

def managed_avd(device):
    """The AVD Gradle created for the managed device, or None before the ui gate has ever made it."""
    if device != "pixel2Api33Atd":
        return None
    home = Path(os.environ.get("ANDROID_USER_HOME") or Path.home() / ".android") / "avd" / "gradle-managed"
    found = sorted(home.glob("dev33_aosp_atd_*_Pixel_2.ini"))
    return (home, found[0].stem) if found else None


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
        for scenario in SCENARIOS:
            scenario_env = {**env, "DETERMINISTIC": "1", "SCENARIO": scenario, "DEVICE": target}
            if serial:
                scenario_env["ANDROID_SERIAL"] = serial
            started = time.time_ns()
            outcome = subprocess.run(["bash", str(ROOT / "scripts" / "e2e-emulator.sh")], cwd=ROOT,
                                     env=scenario_env, stdout=sys.stderr, stderr=sys.stderr)
            paths = fresh_reports(directory, started)
            try:
                _, passed, executed = combine_reports(paths, 1, expected_class)
            except ValueError as error:
                passed, executed = False, 0
                print(f"Android gate: scripted {scenario}: {error}", file=sys.stderr)
            ok = passed and outcome.returncode == 0
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
        xml, _, executed = combine_reports(all_paths, len(SCENARIOS), expected_class)
    except ValueError as error:
        print(f"Android gate failed: {error}", file=sys.stderr)
        return 1
    (run_dir / "dispatcher.xml").write_text(xml + "\n")
    print(xml)
    print(f"Android gate: scripted-all {executed} executed; failed: {', '.join(failed) or 'none'}", file=sys.stderr)
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("ui", "scripted", "scripted-all", "live"))
    parser.add_argument("scenario", nargs="?", choices=SCENARIOS)
    args = parser.parse_args()
    if (args.mode == "scripted") != (args.scenario is not None):
        parser.error("scripted requires one scenario; ui and live take no scenario")
    device = os.environ.get("DEVICE", "pixel2Api33Atd")
    if not device.isalnum():
        parser.error("DEVICE must be an alphanumeric Gradle device name")
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
        command = [str(ROOT / "gradlew"), f":app:{device}DebugAndroidTest", "--rerun",
                   f"-Pandroid.testInstrumentationRunnerArguments.notPackage={E2E_PACKAGE}",
                   f"-Pandroid.experimental.androidTest.numManagedDeviceShards={shards}", "--console=plain"]
        if not full:
            classes = device_only_classes()
            if not classes:
                print("Android gate: no device-only test classes found under app/src/androidTest", file=sys.stderr)
                return 1
            command.insert(3, "-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(classes))
    else:
        env.pop("LIVE", None)
        env.pop("DETERMINISTIC", None)
        command = ["bash", str(ROOT / "scripts" / "e2e-emulator.sh")]
        if args.mode == "scripted":
            env.update(DETERMINISTIC="1", SCENARIO=args.scenario)
            expected_class = E2E_PACKAGE + ".DeterministicInteractiveStreamE2ETest"
        elif args.mode == "live":
            env["LIVE"] = "1"
            minimum = LIVE_MINIMUM
            expected_class = E2E_PACKAGE + ".InteractiveStreamE2ETest"
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
    print(f"Android gate: {args.mode} {args.scenario or ''}; artifacts: {run_dir}", file=sys.stderr)
    if args.mode == "scripted-all":
        return run_scripted_all(env, run_dir, device)
    started = time.time_ns()
    try:
        outcome = subprocess.run(command, cwd=ROOT, env=env, stdout=sys.stderr, stderr=sys.stderr)
        results = ROOT / "app/build/outputs/androidTest-results"
        directory = results / "connected/debug" if device == "connected" else results / "managedDevice/debug" / device
        paths = fresh_reports(directory, started)
        for index, path in enumerate(paths):
            shutil.copy2(path, run_dir / f"{index}-{path.name}")
        xml, passed, executed = combine_reports(paths, minimum, expected_class)
        (run_dir / "dispatcher.xml").write_text(xml + "\n")
        print(xml)
        print(f"Android gate: {executed} executed; process exit {outcome.returncode}", file=sys.stderr)
        return 0 if passed and outcome.returncode == 0 else 1
    except (ValueError, OSError) as error:
        print(f"Android gate failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
