#!/usr/bin/env python3
"""Capture-only Linux supervisor. Invoke under ordinary managed admission.

The terminal receipt is written only after owned descendants exit and available
artifacts are exported. No terminal receipt means completion is unknown.
"""
import ctypes
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def durable(path, record):
    temporary = path.with_suffix(".pending")
    with temporary.open("w") as stream:
        json.dump(record, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)
    fd = os.open(path.parent, os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def processes():
    records = {}
    for path in Path("/proc").iterdir():
        if not path.name.isdigit():
            continue
        try:
            fields = (path / "stat").read_text().rsplit(")", 1)[1].split()
            records[int(path.name)] = {
                "pid": int(path.name), "ppid": int(fields[1]),
                "start_ticks": int(fields[19]), "threads": int(fields[17]),
                "state": fields[0], "exit_code": int(fields[49]),
                "producer": (path / "comm").read_text().strip(),
            }
        except (OSError, ValueError, IndexError):
            # A racing exit is unavailable evidence, never an inferred signal.
            continue
    return records


def descendants(records):
    parents = {os.getpid()}
    owned = {}
    while True:
        added = {pid: r for pid, r in records.items()
                 if r["ppid"] in parents and pid not in parents}
        if not added:
            return owned
        owned.update(added)
        parents.update(added)


def signal_owned(records, sig):
    # Recheck start identity immediately before signalling; do not kill reused PIDs.
    for pid, record in descendants(records).items():
        fresh = processes().get(pid)
        if fresh and fresh["start_ticks"] == record["start_ticks"]:
            try:
                os.kill(pid, sig)
            except ProcessLookupError:
                pass


def sample():
    ps = processes()
    producers = {}
    for record in ps.values():
        group = producers.setdefault(record["producer"], {"processes": 0, "threads": 0})
        group["processes"] += 1
        group["threads"] += record["threads"]
    counters = {}
    for name in ("pids.current", "pids.peak", "pids.max", "pids.events"):
        try:
            counters[name] = (Path("/sys/fs/cgroup") / name).read_text().strip()
        except OSError:
            counters[name] = "unknown"
    custody = {"state": "unknown"}
    path = Path.home() / ".android/avd/pyrycode-device-gate.lock"
    try:
        custody = json.loads(path.read_text()[:4096])
        inode = str(path.stat().st_ino)
        custody["kernel_locks"] = [line for line in Path("/proc/locks").read_text().splitlines()
                                   if any(word.rsplit(":", 1)[-1] == inode
                                          for word in line.split() if ":" in word)]
    except (OSError, ValueError):
        pass
    return {"utc": utc(), "processes": list(ps.values()), "producers": producers,
            "task_counters": counters, "custody": custody}


def export_available(config, errors):
    root = Path(config["root"])
    total = 0
    for index, name in enumerate(config.get("artifacts", [])):
        try:
            source = Path(name)
            if not source.is_dir():
                raise FileNotFoundError(2, "Artifact directory unavailable", name)
            for path in source.rglob("*"):
                if path.is_symlink() or not path.is_file():
                    continue
                size = path.stat().st_size
                if size > 64 * 1024 * 1024 or total + size > 512 * 1024 * 1024:
                    errors.add("export:size_bound")
                    continue
                target = root / "artifacts" / str(index) / path.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
                with target.open("rb") as stream:
                    os.fsync(stream.fileno())
                total += size
        except Exception as error:
            errors.add("export:" + str(getattr(error, "errno", None) or "unknown"))


def capture(config, observe=sample):
    if sys.platform != "linux":
        raise RuntimeError("Capture supervision requires Linux")
    # PR_SET_CHILD_SUBREAPER: setsid children reparent here instead of container init.
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), "Cannot establish child subreaper")
    os.umask(0o077)
    root = Path(config["root"])
    root.mkdir(parents=True, exist_ok=False)
    errors, reaped, children, collectors, streams = set(), [], {}, set(), []
    collector_outputs = {}
    state = {"run_id": config["run_id"], "pid": os.getpid(),
             "start_ticks": processes()[os.getpid()]["start_ticks"],
             "started": utc(), "maximum_seconds": config["maximum_seconds"]}
    durable(root / "supervisor.json", state)
    cancelled = False
    timed_out = False
    gate = None
    gate_exit = None
    stopping = None
    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}

    def cancel(sig, frame):
        nonlocal cancelled
        cancelled = True

    for sig in previous:
        signal.signal(sig, cancel)
    deadline = time.monotonic() + config["maximum_seconds"]
    collector_events = (root / "collector-events.jsonl").open("w")
    streams.append(collector_events)

    def drain_collectors():
        for pid, output in collector_outputs.items():
            pipe = children[pid].stdout
            while pipe is not None:
                try:
                    chunk = os.read(pipe.fileno(), 65536)
                except BlockingIOError:
                    break
                except OSError as error:
                    errors.add("collector_read:" + str(error.errno or "unknown"))
                    break
                if not chunk:
                    break
                offset = output.tell()
                if offset + len(chunk) > 32 * 1024 * 1024:
                    errors.add("collector:size_bound")
                    if children[pid].returncode is None:
                        children[pid].kill()
                    break
                output.write(chunk)
                output.flush()
                collector_events.write(json.dumps({"utc": utc(), "pid": pid,
                                                    "file": output.name, "offset": offset,
                                                    "bytes": len(chunk)}) + "\n")
                collector_events.flush()
    try:
        # All observers are started before the gate, never spawned by sampling.
        for index, command in enumerate(config.get("collectors", [])):
            try:
                output = (root / f"collector-{index}-private.log").open("wb")
                streams.append(output)
                child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                         start_new_session=True)
                children[child.pid] = child
                collectors.add(child.pid)
                collector_outputs[child.pid] = output
                os.set_blocking(child.stdout.fileno(), False)
            except Exception as error:
                errors.add("collector:" + str(getattr(error, "errno", None) or "unknown"))
        output = (root / "gate-private.log").open("wb")
        streams.append(output)
        try:
            gate = subprocess.Popen(config["command"], cwd=config.get("cwd"),
                                    env={**os.environ, **config.get("env", {})},
                                    stdout=output, stderr=output, start_new_session=True)
            children[gate.pid] = gate
            state["gate_pid"] = gate.pid
            durable(root / "supervisor.json", state)
        except Exception as error:
            errors.add("gate_start:" + str(getattr(error, "errno", None) or "unknown"))
        with (root / "availability.jsonl").open("w") as observations:
            initial_events = None
            while True:
                drain_collectors()
                while True:
                    try:
                        pid, status = os.waitpid(-1, os.WNOHANG)
                    except ChildProcessError:
                        pid = 0
                    if not pid:
                        break
                    code = os.waitstatus_to_exitcode(status)
                    reaped.append({"pid": pid, "exit": code, "utc": utc()})
                    if pid in children:
                        children[pid].returncode = code
                    if gate and pid == gate.pid:
                        gate_exit = code
                if time.monotonic() >= deadline:
                    timed_out = True
                if (root / "cancel").exists():
                    cancelled = True
                ps = processes()
                owned = descendants(ps)
                gate_done = gate is None or gate_exit is not None
                if gate_done:
                    for pid in collectors:
                        child = children[pid]
                        if child.returncode is None:
                            child.terminate()
                if cancelled or timed_out or gate_done:
                    if stopping is None:
                        stopping = time.monotonic()
                    if cancelled or timed_out:
                        signal_owned(ps, signal.SIGTERM if time.monotonic() - stopping < config.get("grace_seconds", 10) else signal.SIGKILL)
                    # Normal completion also bounds collector termination only.
                    elif time.monotonic() - stopping >= config.get("grace_seconds", 10):
                        for pid in collectors:
                            if children[pid].returncode is None:
                                children[pid].kill()
                if not owned and gate_done:
                    break
                try:
                    if observations.tell() < 64 * 1024 * 1024:
                        record = observe()
                        events = record.get("task_counters", {}).get("pids.events", "unknown")
                        if events != "unknown":
                            current = int(events.split()[1])
                            if initial_events is None:
                                initial_events = current
                            record["pids_max_events_delta"] = current - initial_events
                        observations.write(json.dumps(record) + "\n")
                        observations.flush()
                    else:
                        errors.add("observation:size_bound")
                except Exception as error:
                    classification = "observation:" + str(getattr(error, "errno", None) or "unknown")
                    errors.add(classification)
                    try:
                        observations.write(json.dumps({"utc": utc(), "state": "unknown",
                                                       "error": classification}) + "\n")
                        observations.flush()
                    except OSError:
                        # The terminal receipt still records the static failure.
                        pass
                for pid in collectors:
                    if collector_outputs[pid].tell() > 32 * 1024 * 1024 and children[pid].returncode is None:
                        errors.add("collector:size_bound")
                        children[pid].kill()
                time.sleep(config.get("sample_seconds", 2))
    finally:
        # Any non-observation failure still cancels and waits for the actual subtree.
        cleanup = time.monotonic()
        while descendants(processes()):
            signal_owned(processes(), signal.SIGTERM if time.monotonic() - cleanup < config.get("grace_seconds", 10) else signal.SIGKILL)
            try:
                pid, status = os.waitpid(-1, os.WNOHANG)
                if pid:
                    code = os.waitstatus_to_exitcode(status)
                    reaped.append({"pid": pid, "exit": code, "utc": utc()})
                    if pid in children:
                        children[pid].returncode = code
                    if gate and pid == gate.pid:
                        gate_exit = code
            except ChildProcessError:
                pass
            time.sleep(.02)
        drain_collectors()
        for pid in collectors:
            children[pid].stdout.close()
        for stream in streams:
            stream.flush()
            os.fsync(stream.fileno())
            stream.close()
        export_available(config, errors)
        result = 124 if timed_out else 130 if cancelled else gate_exit if gate_exit is not None else 125
        durable(root / "outcome.json", {**state, "finished": utc(), "gate_exit": gate_exit,
                                       "exit": result, "timed_out": timed_out,
                                       "cancelled": cancelled, "errors": sorted(errors),
                                       "subtree_complete": True, "reaped": reaped})
        manifest = []
        for path in root.rglob("*"):
            if path.is_file() and not path.is_symlink():
                manifest.append({"path": str(path.relative_to(root)), "bytes": path.stat().st_size,
                                 "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        durable(root / "manifest.json", manifest)
        # Host reads this last record, never an outcome that predates export/hash completion.
        durable(root / "complete.json", {"run_id": config["run_id"], "exit": result,
                                        "subtree_complete": True, "export_complete": not any(e.startswith("export:") for e in errors),
                                        "finished": utc()})
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    return result


if __name__ == "__main__":
    sys.exit(capture(json.loads(Path(sys.argv[1]).read_text())))
