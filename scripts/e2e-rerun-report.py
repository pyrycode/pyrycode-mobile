#!/usr/bin/env python3
"""Read and merge the device test reports around scripts/e2e-emulator.sh's one retry after expired pairing codes.

  e2e-rerun-report.py failed <report dir>             print the failed tests as Class#method,Class#method
  e2e-rerun-report.py merge <first dir> <rerun dir>   fold the rerun's results into the first run's report

A long live run can redeem a pairing code after the daemon's 15-minute window, so the test that pairs fails for
a reason outside the app. The script then mints fresh codes and reruns only the failed tests, once. The merge
writes one report into the rerun's folder that holds every test of the first run, with each rerun test's result
in place of its first result, so the gate still counts the whole run. Totals are recounted from the testcases.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

OUTCOMES = ("failure", "error", "skipped")


def reports(directory):
    return sorted(Path(directory).glob("TEST-*.xml"))


def failed_tests(directory):
    names = []
    for path in reports(directory):
        for case in ET.parse(path).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                names.append(f"{case.get('classname')}#{case.get('name')}")
    return list(dict.fromkeys(names))


def recount(root):
    for container in root.iter():
        if container.tag not in ("testsuite", "testsuites"):
            continue
        cases = container.findall(".//testcase")
        container.set("tests", str(len(cases)))
        for outcome, attribute in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
            container.set(attribute, str(sum(case.find(outcome) is not None for case in cases)))


def merge(first_dir, rerun_dir):
    """Write the merged report over the rerun's reports. Returns the number of first-run results replaced."""
    reruns = reports(rerun_dir)
    rerun = {}
    for path in reruns:
        for case in ET.parse(path).getroot().iter("testcase"):
            rerun[(case.get("classname"), case.get("name"))] = case
    firsts = reports(first_dir)
    if not firsts:
        raise SystemExit("no first-run report to merge into")
    replaced = 0
    for index, path in enumerate(firsts):
        tree = ET.parse(path)
        for case in tree.getroot().iter("testcase"):
            again = rerun.get((case.get("classname"), case.get("name")))
            if again is None:
                continue
            for child in list(case):
                case.remove(child)
            case.extend(list(again))
            if again.get("time") is not None:
                case.set("time", again.get("time"))
            replaced += 1
        recount(tree.getroot())
        tree.write(Path(rerun_dir) / f"TEST-merged-{index}-{path.name[len('TEST-'):]}", encoding="UTF-8",
                   xml_declaration=True)
    for path in reruns:
        path.unlink()
    return replaced


def main(argv):
    if len(argv) == 3 and argv[1] == "failed":
        print(",".join(failed_tests(argv[2])))
        return 0
    if len(argv) == 4 and argv[1] == "merge":
        print(merge(argv[2], argv[3]))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
