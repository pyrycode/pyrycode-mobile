#!/usr/bin/env python3
"""Reopen scenario only: forward fakeclaude stdout but fence terminal rendering."""

import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def main():
    release = Path(os.environ["E2E_HELD_RESULT_RELEASE"])
    child = subprocess.Popen(
        [os.environ["E2E_HELD_RESULT_CHILD"], *sys.argv[1:]],
        stdin=sys.stdin, stdout=subprocess.PIPE,
    )

    def stop(signum, _frame):
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        for line in child.stdout:
            try:
                terminal = json.loads(line).get("type") == "result"
            except (ValueError, AttributeError):
                terminal = False
            if terminal:
                while not release.exists():
                    time.sleep(0.05)
            # Preserve the raw fixture bytes; only the terminal line's delivery time changes.
            sys.stdout.buffer.write(line)
            sys.stdout.buffer.flush()
        return child.wait()
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
        child.stdout.close()


if __name__ == "__main__":
    sys.exit(main())
