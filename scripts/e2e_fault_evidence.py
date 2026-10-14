"""Retain only allowlisted private diagnostics after owned harness children exit."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import subprocess
import sys


LOG_NAMES = ("daemon.log", "daemon-fault.log", "daemon-b.log", "daemon-answer.log", "daemon-bypass.log", "relay.log")
DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
FILE_FLAGS = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK


def digest(path):
    with Path(path).open("rb") as reader:
        return hashlib.file_digest(reader, "sha256").hexdigest()


def open_directory(path, create=False, private_root=None):
    """Pin each ancestor without following a substituted evidence symlink."""
    path = Path(os.path.abspath(path))
    descriptor = os.open(path.anchor, DIRECTORY_FLAGS)
    current = Path(path.anchor)
    try:
        for name in path.parts[1:]:
            current = current / name
            created = False
            if create:
                try:
                    os.mkdir(name, mode=0o700, dir_fd=descriptor)
                    created = True
                except FileExistsError:
                    pass
            entry = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
            if stat.S_ISLNK(entry.st_mode) and entry.st_uid == 0 and current.parent == Path(path.anchor):
                child = open_directory(Path(path.anchor, name).resolve())
            else:
                child = os.open(name, DIRECTORY_FLAGS, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
            if created or (private_root is not None and current.is_relative_to(private_root)):
                os.fchmod(descriptor, 0o700)
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def revision(value):
    return value if re.fullmatch(r"[0-9a-f]{40,64}", value) else "unavailable"


def binary_digest(binary):
    binary = str(binary)
    selected = binary if "/" in binary else shutil.which(binary)
    if not selected:
        return "unavailable"
    try:
        descriptor = os.open(Path(selected).resolve(), FILE_FLAGS)
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            os.close(descriptor)
            return "unavailable"
        with os.fdopen(descriptor, "rb") as reader:
            return hashlib.file_digest(reader, "sha256").hexdigest()
    except OSError:
        return "unavailable"


def source_revision(source):
    if not source:
        return "unavailable"
    try:
        result = subprocess.run(["git", "-C", source, "rev-parse", "HEAD"], capture_output=True, text=True, timeout=10)
        return revision(result.stdout.strip()) if result.returncode == 0 else "unavailable"
    except (OSError, subprocess.SubprocessError):
        return "unavailable"


def write_private(directory, name, content):
    with os.fdopen(os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                           0o600, dir_fd=directory), "wb") as writer:
        os.fchmod(writer.fileno(), 0o600)
        writer.write(content)


class RetentionError(Exception):
    def __init__(self, saved=None):
        super().__init__("private evidence retention failed")
        self.saved = saved


def retain(source, destination, mobile_revision, daemon_revision, binary, daemon_source="", harness_exit_code=0, agents_root=""):
    saved = None
    try:
        if not destination:
            raise RetentionError()
        if agents_root:
            os.close(open_directory(agents_root))  # Never create a substitute persistent root.
        private_root = Path(os.path.abspath(agents_root)) / "logs/evidence" if agents_root else None
        destination_fd = open_directory(destination, create=True, private_root=private_root)
        try:
            os.fchmod(destination_fd, 0o700)
            name = "fault-" + secrets.token_hex(16)
            os.mkdir(name, mode=0o700, dir_fd=destination_fd)
            saved = Path(os.path.abspath(destination)) / name
            run_fd = os.open(name, DIRECTORY_FLAGS, dir_fd=destination_fd)
        finally:
            os.close(destination_fd)
        try:
            os.fchmod(run_fd, 0o700)
            availability = dict.fromkeys(LOG_NAMES, "unavailable")
            hashes = {}
            failed = False
            source_fd = open_directory(source)
            try:
                for name in LOG_NAMES:
                    try:
                        try:
                            descriptor = os.open(name, FILE_FLAGS, dir_fd=source_fd)
                        except FileNotFoundError:
                            continue
                        except OSError:
                            # A symlink is unavailable; other I/O errors make capture partial.
                            if stat.S_ISLNK(os.stat(name, dir_fd=source_fd, follow_symlinks=False).st_mode):
                                continue
                            raise
                        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
                            os.close(descriptor)
                            continue
                        with os.fdopen(descriptor, "rb") as reader:
                            expected = hashlib.sha256()
                            with os.fdopen(os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                                   0o600, dir_fd=run_fd), "wb") as writer:
                                os.fchmod(writer.fileno(), 0o600)
                                while chunk := reader.read(65536):
                                    expected.update(chunk)
                                    writer.write(chunk)
                            with os.fdopen(os.open(name, FILE_FLAGS, dir_fd=run_fd), "rb") as copied:
                                actual = hashlib.file_digest(copied, "sha256").hexdigest()
                            if expected.hexdigest() != actual:
                                raise RetentionError(saved)
                            hashes[name] = actual
                            availability[name] = "retained"
                    except (OSError, RetentionError):
                        failed = True
                        availability[name] = "failed"
                        try:
                            os.unlink(name, dir_fd=run_fd)
                        except FileNotFoundError:
                            pass
            finally:
                os.close(source_fd)
            metadata = {"mobile_revision": revision(mobile_revision), "daemon_revision": revision(daemon_revision),
                        "daemon_sha256": binary_digest(binary), "daemon_source_revision": source_revision(daemon_source),
                        "harness_exit_code": harness_exit_code, "availability": availability,
                        "retention_status": "partial" if failed else "complete"}
            content = (json.dumps(metadata, sort_keys=True) + "\n").encode()
            write_private(run_fd, "inputs.json", content)
            hashes["inputs.json"] = hashlib.sha256(content).hexdigest()
            with os.fdopen(os.open("inputs.json", FILE_FLAGS, dir_fd=run_fd), "rb") as copied:
                if hashlib.file_digest(copied, "sha256").hexdigest() != hashes["inputs.json"]:
                    raise RetentionError(saved)
            manifest = "".join(f"{value}  {name}\n" for name, value in sorted(hashes.items())).encode()
            write_private(run_fd, "SHA256SUMS", manifest)
            with os.fdopen(os.open("SHA256SUMS", FILE_FLAGS, dir_fd=run_fd), "rb") as copied:
                if hashlib.file_digest(copied, "sha256").digest() != hashlib.sha256(manifest).digest():
                    raise RetentionError(saved)
            if failed:
                raise RetentionError(saved)
        finally:
            os.close(run_fd)
        return saved
    except (OSError, RetentionError) as error:
        raise RetentionError(saved) from error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--destination", required=True)
    parser.add_argument("--mobile-revision", default="")
    parser.add_argument("--daemon-revision", default="")
    parser.add_argument("--binary", default="")
    parser.add_argument("--daemon-source", default="")
    parser.add_argument("--harness-exit-code", type=int, default=0)
    parser.add_argument("--agents-root", default="")
    args = parser.parse_args()
    try:
        saved = retain(args.source, args.destination, args.mobile_revision, args.daemon_revision,
                       args.binary, args.daemon_source, args.harness_exit_code, args.agents_root)
        metadata = json.loads((saved / "inputs.json").read_text())
        summary = " ".join(f"{name}={metadata['availability'][name]}" for name in LOG_NAMES)
    except Exception as error:
        saved = error.saved if isinstance(error, RetentionError) else None
        status = "partial" if saved else "failed"
        print(f"retention={status} availability=incomplete" + (" destination=" + json.dumps(str(saved), ensure_ascii=True) if saved else ""))
        return 1
    print("retention=complete destination=" + json.dumps(str(saved), ensure_ascii=True) + " " + summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
