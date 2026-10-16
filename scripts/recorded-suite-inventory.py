#!/usr/bin/env python3
"""Account for every native real-Claude method before claiming complete recorded coverage."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt'
MANIFEST = ROOT / 'app/src/androidTest/assets/recorded/suite.json'


def methods(source):
    markers = list(re.finditer(r'(?m)^\s*@Test\b', source))
    found = []
    for index, marker in enumerate(markers):
        end = markers[index + 1].start() if index + 1 < len(markers) else len(source)
        match = re.search(r'\bfun\s+(\w+)\s*\(', source[marker.end():end])
        if match is None:
            raise ValueError('Annotated test has no method')
        found.append(match.group(1))
    if len(found) != len(set(found)):
        raise ValueError('Duplicate real test method')
    return set(found)


def curated(source):
    section = source.split('elif [ -n "${LIVE}" ]; then\n  # LIVE curates', 1)[1].split('\nelse\n', 1)[0]
    return set(re.findall(r'\$\{TEST_CLASS\}#(\w+)', section))


def audit(manifest, actual, selected, directory):
    rows = manifest['scenarios']
    named = [row['method'] for row in rows]
    if len(named) != len(set(named)) or set(named) != actual:
        raise ValueError('Recorded suite inventory differs from real test methods')
    if not selected <= actual:
        raise ValueError('Live selection contains unknown methods')
    if set(manifest['liveSelection']) != selected:
        raise ValueError('Recorded suite live selection changed')
    completed, pending, retired = [], [], []
    for row in rows:
        if row.get('retired'):
            if row['method'] not in {
                    'interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace',
                    'interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace'} or row.get('fixture'):
                raise ValueError('Unexpected retired scenario')
            retired.append(row['method'])
            continue
        fixture = row.get('fixture')
        if not fixture:
            pending.append(row['method'])
            continue
        relative = Path(fixture)
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('Invalid suite fixture path')
        path = directory / relative
        if not path.is_file():
            raise ValueError('Recorded suite fixture is missing')
        document = json.loads(path.read_text())
        if document.get('format') != 'pyrycode.mobile-suite' or document.get('scenario') != row['method']:
            raise ValueError('Recorded suite fixture belongs to another scenario')
        evidence = row.get('evidence', {})
        if evidence.get('replay') != {'executed': 1, 'failed': 0, 'skipped': 0} or not evidence.get('liveVerified'):
            raise ValueError('Recorded suite counted evidence is incomplete')
        completed.append(row['method'])
    return {'total': len(actual), 'liveSelected': len(selected), 'recorded': len(completed), 'retired': retired, 'pending': pending}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=MANIFEST)
    parser.add_argument('--report-only', action='store_true', help='Show pending work without claiming completion')
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    obsolete = {method: reason for reason, method in re.findall(r'@Test\s*@Ignore\("([^"\n]+)"\)\s*fun\s+(\w+)', SOURCE.read_text())}
    for row in manifest['scenarios']:
        if row.get('retired') and obsolete.get(row['method']) != row['retired']:
            raise ValueError('Retired test is no longer explicitly obsolete in its original source')
    report = audit(manifest, methods(SOURCE.read_text()),
                   curated((ROOT / 'scripts/e2e-emulator.sh').read_text()), args.manifest.parent)
    print(json.dumps(report))
    if report['pending'] and not args.report_only:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
