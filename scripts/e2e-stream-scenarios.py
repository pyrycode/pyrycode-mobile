#!/usr/bin/env python3
"""Run ordinary and queued held-stream proofs with independent scripted daemons."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

CLASS = 'de.pyryco.mobile.e2e.DeterministicInteractiveStreamE2ETest'
METHODS = {
    'stream': 'interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread',
    'stream-queued-echo': 'interactiveTurn_seededChannel_queuedPromptDoesNotSplitHeldReply',
}


def main():
    root = Path(__file__).resolve().parents[1]
    environment = os.environ.copy()
    admission = environment.get('PYRY_TEST_ADMISSION_FD')
    inherited = () if admission is None else (int(admission),)
    device = environment.get('DEVICE', 'pixel2Api33Atd')
    reports = root / 'app/build/outputs/androidTest-results'
    reports /= 'connected/debug' if device == 'connected' else 'managedDevice/debug/' + device
    status = 0
    retained = []
    archive_root = root / 'build/dispatcher-tests/stream-methods'
    archive_root.mkdir(parents=True, exist_ok=True)
    archive_run = Path(tempfile.mkdtemp(prefix='run-', dir=archive_root))
    with tempfile.TemporaryDirectory(prefix='builder-2367-scripted-') as directory:
        try:
            for case in METHODS:
                child = {**environment, 'E2E_STREAM_CASE': '1', 'SCENARIO': case}
                # The shared gate owns this installed emulator and resets it between scenarios.
                # Each half likewise starts with an empty phone and its own daemon/fixture release.
                if environment.get('E2E_INSTALLED') == '1':
                    subprocess.run([
                        str(Path(environment['ANDROID_HOME']) / 'platform-tools/adb'),
                        '-s', environment['ANDROID_SERIAL'], 'shell', 'pm', 'clear', 'de.pyryco.mobile',
                    ], check=True)
                # Only this execution may supply XML, regardless of filesystem timestamps.
                for path in reports.rglob('TEST-*.xml'):
                    path.unlink()
                result = subprocess.run(['bash', str(root / 'scripts/e2e-emulator.sh')], cwd=root, env=child, pass_fds=inherited)
                status = status or result.returncode
                current = list(reports.rglob('TEST-*.xml'))
                cases = [test for path in current for test in ET.parse(path).getroot().iter('testcase')]
                if len(cases) != 1 or cases[0].get('classname') != CLASS or cases[0].get('name') != METHODS[case] or any(
                        cases[0].find(key) is not None for key in ('failure', 'error', 'skipped')):
                    status = status or 1
                for index, path in enumerate(current):
                    saved = Path(directory) / f'TEST-stream-{case}-{index}.xml'
                    shutil.copyfile(path, saved)
                    retained.append(saved)
                    # Preserve the exact report before the next managed task replaces its outputs.
                    path.unlink()
                archive = archive_run / case
                archive.mkdir(parents=True, exist_ok=True)
                for path in reports.rglob('logcat-*.txt'):
                    shutil.copyfile(path, archive / path.name)
                for saved in retained:
                    shutil.copyfile(saved, archive / saved.name)
        finally:
            reports.mkdir(parents=True, exist_ok=True)
            for saved in retained:
                shutil.copyfile(saved, reports / saved.name)
    return status


if __name__ == '__main__':
    raise SystemExit(main())
