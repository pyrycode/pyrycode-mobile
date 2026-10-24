#!/usr/bin/env python3
"""Run #2338's controlled publisher probe without editing the daemon checkout.

The held-enumeration case is expected to fail on daemon f90990dd, demonstrating
lost same-generation progress. This diagnostic is outside Mobile test discovery.
It keeps the daemon's managed admission and executes only its named test.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--daemon-root", type=Path, default=Path(os.environ.get("PYRYCODE_SRC", "../pyrycode")))
    args = parser.parse_args()
    daemon = args.daemon_root.resolve()
    admission = daemon / "scripts/test-admission.py"
    if not admission.is_file():
        parser.error("--daemon-root must contain the daemon's scripts/test-admission.py")
    probe = Path(__file__).with_name("session-error-publisher-probe_test.go").resolve()
    virtual = daemon / "cmd/pyry/session_error_2338_probe_test.go"
    if virtual.exists():
        parser.error("diagnostic overlay target already exists; preserve it and choose a clean daemon checkout")
    revision = subprocess.check_output(["git", "-C", str(daemon), "rev-parse", "HEAD"], text=True).strip()
    print(f"publisher probe: daemon_revision={revision}", flush=True)
    with tempfile.TemporaryDirectory(prefix="session-error-publisher-") as scratch:
        overlay = Path(scratch) / "overlay.json"
        overlay.write_text(json.dumps({"Replace": {str(virtual): str(probe)}}), encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(admission), "--", "go", "test", "-timeout=5m", "-race", "-json",
             "-overlay", str(overlay), "-run", "^TestSessionError2338PublisherDelivery$", "./cmd/pyry"],
            cwd=daemon,
            check=False,
        ).returncode


if __name__ == "__main__":
    sys.exit(main())
