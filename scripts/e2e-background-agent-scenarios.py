#!/usr/bin/env python3
"""Run the retained Agent proof and restart twin with independent scripted daemons."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

CLASS = 'de.pyryco.mobile.e2e.DeterministicInteractiveStreamE2ETest'
METHODS = {
    'retained': 'interactiveTurn_seededChannel_backgroundAgentMovesAndSettles',
    'restart': 'interactiveTurn_seededChannel_earlierSessionAgent_survivesRestartAndSettlementReconnect',
}


def main():
    root = Path(__file__).resolve().parents[1]
    environment = os.environ.copy()
    device = environment.get('DEVICE', 'pixel2Api33Atd')
    reports = root / 'app/build/outputs/androidTest-results'
    reports /= 'connected/debug' if device == 'connected' else 'managedDevice/debug/' + device
    status = 0
    retained = []
    with tempfile.TemporaryDirectory(prefix='builder-2246-scripted-') as directory:
        try:
            for case in ('retained', 'restart'):
                child = {**environment, 'E2E_BACKGROUND_AGENT_CASE': case}
                # The shared gate owns this installed emulator and resets it between scenarios.
                # Each half likewise starts with an empty phone and its own daemon/fixture release.
                if environment.get('E2E_INSTALLED') == '1':
                    subprocess.run([
                        str(Path(environment['ANDROID_HOME']) / 'platform-tools/adb'),
                        '-s', environment['ANDROID_SERIAL'], 'shell', 'pm', 'clear', 'de.pyryco.mobile',
                    ], check=True)
                started = time.time_ns()
                result = subprocess.run(['bash', str(root / 'scripts/e2e-emulator.sh')], cwd=root, env=child)
                status = status or result.returncode
                current = [path for path in reports.rglob('TEST-*.xml') if path.stat().st_mtime_ns >= started]
                cases = [test for path in current for test in ET.parse(path).getroot().iter('testcase')]
                if len(cases) != 1 or cases[0].get('classname') != CLASS or cases[0].get('name') != METHODS[case] or any(
                        cases[0].find(key) is not None for key in ('failure', 'error', 'skipped')):
                    status = status or 1
                for index, path in enumerate(current):
                    saved = Path(directory) / f'TEST-background-agent-{case}-{index}.xml'
                    shutil.copyfile(path, saved)
                    retained.append(saved)
                    # Preserve the exact report before the next managed task replaces its outputs.
                    path.unlink()
        finally:
            reports.mkdir(parents=True, exist_ok=True)
            for saved in retained:
                shutil.copyfile(saved, reports / saved.name)
    return status


if __name__ == '__main__':
    raise SystemExit(main())
