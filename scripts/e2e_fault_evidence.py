"""Retain only isolated fault-boundary logs, privately, before harness disposal."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile


def digest(path):
    with Path(path).open("rb") as reader:
        return hashlib.file_digest(reader, "sha256").hexdigest()


def retain(source, destination, mobile_revision, daemon_revision, binary, daemon_source=""):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    destination.chmod(0o700)
    saved = Path(tempfile.mkdtemp(prefix="fault-", dir=destination))
    for name in ("daemon.log", "daemon-fault.log", "daemon-b.log", "daemon-answer.log", "daemon-bypass.log", "relay.log"):
        path = Path(source) / name
        if not path.is_file() or path.is_symlink():
            continue
        # Files are created beneath an owner-only directory before any bytes are copied.
        with path.open("rb") as reader, (saved / name).open("xb") as writer:
            (saved / name).chmod(0o600)
            while chunk := reader.read(65536):
                writer.write(chunk)
        if digest(path) != digest(saved / name):
            raise RuntimeError("fault evidence verification failed")
    revision = lambda value: value if re.fullmatch(r"[0-9a-f]{40,64}", value) else "unavailable"
    metadata = {"mobile_revision": revision(mobile_revision), "daemon_revision": revision(daemon_revision),
                "daemon_sha256": digest(binary) if Path(binary).is_file() else "unavailable"}
    if daemon_source:
        result = subprocess.run(["git", "-C", daemon_source, "rev-parse", "HEAD"], capture_output=True, text=True)
        metadata["daemon_source_revision"] = revision(result.stdout.strip())
    (saved / "inputs.json").write_text(json.dumps(metadata) + "\n")
    (saved / "inputs.json").chmod(0o600)
    manifest = "".join(f"{digest(path)}  {path.name}\n" for path in sorted(saved.iterdir()))
    (saved / "SHA256SUMS").write_text(manifest)
    (saved / "SHA256SUMS").chmod(0o600)
    return saved


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--destination", required=True)
    parser.add_argument("--mobile-revision", default="")
    parser.add_argument("--daemon-revision", default="")
    parser.add_argument("--binary", default="")
    parser.add_argument("--daemon-source", default="")
    args = parser.parse_args()
    saved = retain(args.source, args.destination, args.mobile_revision, args.daemon_revision, args.binary, args.daemon_source)
    print(f"fault boundary evidence retained privately at {saved}")
