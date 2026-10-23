#!/usr/bin/env python3
"""Run the retained Agent proof and restart twin with independent scripted daemons."""

import os
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

CLASS = 'de.pyryco.mobile.e2e.DeterministicInteractiveStreamE2ETest'
METHODS = {
    'retained': 'interactiveTurn_seededChannel_backgroundAgentMovesAndSettles',
    'restart': 'interactiveTurn_seededChannel_earlierSessionAgent_survivesRestartAndSettlementReconnect',
}


def source_revision(path):
    if not path:
        return 'unavailable'
    result = subprocess.run(['git', '-C', str(path), 'rev-parse', 'HEAD'], capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else 'unavailable'


def retain_arm(root, reports, directory, case, exit_code, provenance):
    """Copy managed outputs before another arm can replace them; never turn failure into a pass."""
    if reports.is_dir():
        shutil.copytree(reports, directory / 'phone')
    metadata = {'case': case, 'method': CLASS + '#' + METHODS[case], 'exit_code': exit_code,
                'provenance': provenance, 'apk_sha256': {}}
    for path in (root / 'app/build/outputs/apk').rglob('*.apk'):
        metadata['apk_sha256'][str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    (directory / 'result.json').write_text(json.dumps(metadata, sort_keys=True) + '\n')
    hashes = {str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in directory.rglob('*') if path.is_file()}
    (directory / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in sorted(hashes.items())))
    for name, expected in hashes.items():
        if hashlib.sha256((directory / name).read_bytes()).hexdigest() != expected:
            raise RuntimeError('Agent evidence copy verification failed')
    for path in directory.rglob('*'):
        path.chmod(0o700 if path.is_dir() else 0o600)


def main():
    root = Path(__file__).resolve().parents[1]
    environment = os.environ.copy()
    provenance = {'mobile': source_revision(root), 'core': source_revision(environment.get('PYRYCODE_SRC')),
                  'relay': source_revision(environment.get('PYRYCODE_RELAY_SRC')), 'binary_sha256': {}}
    for name in ('PYRY_BIN', 'RELAY_BIN'):
        selected = shutil.which(environment.get(name, ''))
        provenance['binary_sha256'][name] = hashlib.sha256(Path(selected).read_bytes()).hexdigest() if selected else 'unavailable'
    admission = environment.get('PYRY_TEST_ADMISSION_FD')
    inherited = () if admission is None else (int(admission),)
    device = environment.get('DEVICE', 'pixel2Api33Atd')
    reports = root / 'app/build/outputs/androidTest-results'
    reports /= 'connected/debug' if device == 'connected' else 'managedDevice/debug/' + device
    status = 0
    retained = []
    evidence = Path(environment.get('PYRY_GATE_EVIDENCE_DIR', root / 'build')) / 'background-agent-evidence'
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    run = Path(tempfile.mkdtemp(prefix='run-', dir=evidence))
    print(f'Background Agent evidence: {run}', flush=True)
    with tempfile.TemporaryDirectory(prefix='builder-2246-scripted-') as directory:
        try:
            for case in ('retained', 'restart'):
                arm = run / case
                arm.mkdir(mode=0o700)
                child = {**environment, 'E2E_BACKGROUND_AGENT_CASE': case, 'E2E_FAULT_EVIDENCE_DIR': str(arm / 'harness')}
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
                result = None
                try:
                    result = subprocess.run(['bash', str(root / 'scripts/e2e-emulator.sh')], cwd=root, env=child, pass_fds=inherited)
                finally:
                    retain_arm(root, reports, arm, case, None if result is None else result.returncode, provenance)
                status = status or result.returncode
                current = list(reports.rglob('TEST-*.xml'))
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
