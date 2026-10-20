#!/usr/bin/env python3
"""Stop scenario only: give the canned roster explicitly synthetic creating work."""

import json
import os
from pathlib import Path
import signal
import subprocess
import sys


TASK_ID = "bybi8g8i8"


def main():
    creating = (Path(__file__).parent / "e2e-fixtures/stop-background-task-start.jsonl").read_bytes()
    child = subprocess.Popen(
        [os.environ["E2E_ROSTER_TASK_CHILD"], *sys.argv[1:]],
        stdin=sys.stdin, stdout=subprocess.PIPE,
    )

    def stop(signum, _frame):
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    supplied = False
    try:
        for line in child.stdout:
            try:
                event = json.loads(line)
                roster = (
                    isinstance(event, dict)
                    and event.get("type") == "system"
                    and event.get("subtype") == "background_tasks_changed"
                    and isinstance(event.get("tasks"), list)
                    and any(isinstance(task, dict) and task.get("task_id") == TASK_ID for task in event["tasks"])
                )
            except (ValueError, AttributeError):
                roster = False
            if roster and not supplied:
                sys.stdout.buffer.write(creating)
                supplied = True
            # Preserve every original byte, including the fake's actual Stop response and terminal notification.
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
