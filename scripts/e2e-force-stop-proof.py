#!/usr/bin/env python3
"""External app-process death proof, on an emulator this command owns.

The real isolated daemon runs with a scripted child; channel posts spend no
Claude turns. Evidence is an allowlist of synthetic content and process facts.
Run with PYRYCODE_SRC and PYRYCODE_RELAY_SRC pointing at the sibling checkouts.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("gate", ROOT / "scripts/android-test-gate.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
APP = "de.pyryco.mobile"


def owned_worker(port, app_revision, daemon_revision, evidence):
    env = os.environ.copy()
    serial = env["ANDROID_SERIAL"]
    if env.get("EXTERNAL_FORCE_STOP_PROOF") != "1" or not re.fullmatch(r"emulator-\d+", serial):
        raise RuntimeError("missing owned emulator custody")

    def adb(*args):
        result = gate.adb_call(env, serial, *args, capture=True)
        if result is None or result.returncode:
            raise RuntimeError("owned device operation failed")
        return result.stdout.strip()

    def pid():
        result = gate.adb_call(env, serial, "shell", "pidof", APP, capture=True)
        if result is None or result.returncode not in (0, 1):
            raise RuntimeError("owned device process query failed")
        return result.stdout.strip()

    def control(action, body=None):
        request = urllib.request.Request(f"http://127.0.0.1:{int(port)}/{action}",
            data=json.dumps(body).encode() if body else b"", method="POST")
        with urllib.request.urlopen(request, timeout=30) as reply:
            if reply.status != 200:
                raise RuntimeError("owned daemon operation failed")

    fixture = json.loads(adb("shell", "run-as", APP, "cat", "files/e2e1833-proof.json"))
    name, prefix, conversation = fixture["channel"], fixture["prefix"], fixture["conversation_id"]
    if not re.fullmatch(r"e2e-stop-\d+", name) or not re.fullmatch(r"e2e1833-stop-\d+", prefix):
        raise RuntimeError("invalid synthetic proof fixture")

    def nodes():
        # Raw accessibility payload stays in memory; only fixture matches enter evidence.
        adb("shell", "uiautomator", "dump", "/data/local/tmp/e2e1833-ui.xml")
        return list(ET.fromstring(adb("shell", "cat", "/data/local/tmp/e2e1833-ui.xml")).iter("node"))

    def open_and_wait(text):
        adb("shell", "am", "start", "-n", APP + "/.MainActivity")
        deadline = time.monotonic() + 60
        tapped = False
        while time.monotonic() < deadline:
            tree = nodes()
            matches = [n for n in tree if n.get("text") == text]
            if matches:
                if len(matches) != 1:
                    raise RuntimeError("synthetic post rendered more than once")
                return
            if not tapped:
                row = next((n for n in tree if n.get("text") == name), None)
                if row is not None:
                    x1, y1, x2, y2 = map(int, re.findall(r"\d+", row.get("bounds")))
                    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                    tapped = True
            time.sleep(0.3)
        raise RuntimeError("synthetic post did not appear without scrolling")

    record = {"started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "device_sdk": adb("shell", "getprop", "ro.build.version.sdk"),
              "device_image": adb("shell", "getprop", "ro.build.fingerprint"),
              "app_revision": app_revision, "daemon_revision": daemon_revision,
              "daemon_version": subprocess.check_output([env["PYRY_BIN"], "version"], text=True).strip(),
              "conversation_id": conversation, "synthetic_post_id": prefix,
              "synthetic_post_text": prefix + "-new-000", "result": "FAIL"}
    try:
        open_and_wait(prefix + "-baseline-000")
        before = pid()
        if not before:
            raise RuntimeError("app process not running before force-stop")
        record["pid_before"] = before
        adb("shell", "am", "force-stop", APP)
        if pid():
            raise RuntimeError("app process survived force-stop")
        record["process_stopped"] = True
        record["stopped_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        control("posts", {"name": name, "prefix": prefix + "-new", "count": 1})
        control("stop")
        control("start")
        open_and_wait(record["synthetic_post_text"])
        after = pid()
        if not after or before == after:
            raise RuntimeError("app process did not relaunch")
        record.update(pid_after=after, process_relaunched=True, post_count=1, scrolling=False, result="PASS",
                      relaunched_utc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    finally:
        Path(evidence).write_text(json.dumps(record, indent=2) + "\n")
    print("Force-stop proof: PASS; actual process stopped/relaunched; synthetic post once; no scrolling")


def main():
    if sys.argv[1:2] == ["--owned-worker"]:
        owned_worker(*sys.argv[2:])
        return 0
    env = os.environ.copy()
    env.pop("ANTHROPIC_API_KEY", None)
    env.pop("CLAUDE_CODE_OAUTH_TOKEN", None)
    identity = "e2e-stop-" + uuid.uuid4().hex[:8]
    env.update(DETERMINISTIC="1", SCENARIO="ping", EXTERNAL_FORCE_STOP_PROOF="1",
               PYRY_NAME=identity, PAIR_NAME=identity, E2E_DISABLE_ANIMATIONS="1", PYRY_FORCE_TEST_RUN="1")
    (ROOT / "build/dispatcher-tests").mkdir(parents=True, exist_ok=True)
    folder = Path(tempfile.mkdtemp(prefix="force-stop-", dir=ROOT / "build/dispatcher-tests"))
    env["FORCE_STOP_EVIDENCE"] = str(folder / "evidence.json")
    for variable, source, package, binary in (
        ("PYRY_BIN", "PYRYCODE_SRC", "./cmd/pyry", "pyry"),
        ("RELAY_BIN", "PYRYCODE_RELAY_SRC", "./cmd/pyrycode-relay", "pyrycode-relay")):
        destination = ROOT / "build/e2e-bin" / binary
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not env.get(variable):
            subprocess.run(["go", "build", "-o", str(destination), package], cwd=env[source], check=True)
            env[variable] = str(destination)
    if gate.build_apks(env, "scripted"):
        return 1
    signal.signal(signal.SIGTERM, gate.raise_interrupt)
    with gate.device_hold("force-stop", 600):
        avd = gate.managed_avd("pixel2Api33Atd")
        if avd is None:
            raise RuntimeError("required managed AVD missing")
        serial, process = gate.boot_emulator(env, *avd)
        if serial is None:
            raise RuntimeError("owned emulator did not boot")
        try:
            granted = gate.install_once(env, serial)
            if granted is None or not gate.reset_app(env, serial, granted):
                raise RuntimeError("owned emulator installation failed")
            env.update(ANDROID_SERIAL=serial, DEVICE="connected", E2E_INSTALLED="1", E2E_APKS_BUILT="1")
            started = time.time_ns()
            result = subprocess.run(["bash", str(ROOT / "scripts/e2e-emulator.sh")], env=env, cwd=ROOT)
            reports = gate.fresh_reports(ROOT / "app/build/outputs/androidTest-results/connected/debug", started)
            xml, passed, executed = gate.combine_reports(reports, gate.E2E_PACKAGE + ".DeterministicInteractiveStreamE2ETest")
            (folder / "preparation.xml").write_text(xml + "\n")
            print(f"Force-stop preparation: {executed} executed; passed={passed}; process exit {result.returncode}")
            if executed != 1 or not passed:
                return 1
            print("Force-stop evidence: " + str(folder / "evidence.json"))
            return result.returncode
        finally:
            gate.stop_emulator(env, serial, process)


if __name__ == "__main__":
    sys.exit(main())
