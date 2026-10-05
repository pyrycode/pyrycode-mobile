#!/usr/bin/env python3
"""Turn `adb shell am instrument -r -w` output into a JUnit report; exit 0 only when every test passed.

scripts/e2e-emulator.sh runs a scripted scenario this way when scripts/android-test-gate.py has already
installed both APKs on its emulator (E2E_INSTALLED=1), in place of Gradle's device task, which installs and
removes both APKs on every call. Usage: instrument-report.py <raw output> <report.xml>

A test that started and never finished, a run without the runner's final OK code and a run that reported
no test at all are failures, so a crashed or unstarted run can never read as a pass.
"""
import sys
import xml.etree.ElementTree as ET

STATUS, STATUS_CODE, CODE = "INSTRUMENTATION_STATUS: ", "INSTRUMENTATION_STATUS_CODE: ", "INSTRUMENTATION_CODE: "
START, OK, FAILURE, ERROR, IGNORED, ASSUMPTION_FAILURE = 1, 0, -2, -1, -3, -4
RUN_OK = -1  # Activity.RESULT_OK: the runner reached the end of the run
UNFINISHED = "the test process ended before this test finished"


def parse(text):
    """The run's tests as dicts of class, name, outcome and detail, and whether the run completed."""
    tests, status, key, completed = [], {}, None, False
    for line in text.splitlines():
        if line.startswith(STATUS):
            key, _, value = line[len(STATUS):].partition("=")
            status[key] = value
        elif line.startswith(STATUS_CODE):
            record(tests, status, int(line[len(STATUS_CODE):].strip()))
            status, key = {}, None
        elif line.startswith(CODE):
            completed = int(line[len(CODE):].strip()) == RUN_OK
            key = None
        elif line.startswith("INSTRUMENTATION_"):
            key = None  # the run's closing summary or a failure to start; no test outcome in it
        elif key is not None:
            status[key] += "\n" + line
    for test in tests:
        if test["outcome"] is None:
            test.update(outcome="failure", detail=UNFINISHED)
    return tests, completed


def record(tests, status, code):
    identity = (status.get("class", ""), status.get("test", ""))
    if not all(identity):
        return  # a status about the run, not a test, such as the runner failing to start
    if code == START:
        tests.append({"class": identity[0], "name": identity[1], "outcome": None, "detail": ""})
        return
    outcome = {OK: "pass", IGNORED: "skipped", ASSUMPTION_FAILURE: "skipped"}.get(code, "failure")
    detail = status.get("stack", "") if outcome == "failure" else ""
    for test in reversed(tests):
        if (test["class"], test["name"]) == identity and test["outcome"] is None:
            test.update(outcome=outcome, detail=detail)
            return
    # A failure reported for no started test, such as a class that could not set up.
    tests.append({"class": identity[0], "name": identity[1], "outcome": outcome, "detail": detail})


def report(tests):
    count = lambda outcome: str(sum(test["outcome"] == outcome for test in tests))
    suite = ET.Element("testsuite", {"name": tests[0]["class"] if tests else "instrumentation",
                                     "tests": str(len(tests)), "failures": count("failure"), "errors": "0",
                                     "skipped": count("skipped")})
    for test in tests:
        case = ET.SubElement(suite, "testcase", {"classname": test["class"], "name": test["name"]})
        if test["outcome"] == "failure":
            ET.SubElement(case, "failure", {"message": test["detail"].split("\n", 1)[0]}).text = test["detail"]
        elif test["outcome"] == "skipped":
            ET.SubElement(case, "skipped")
    return ET.tostring(suite, encoding="unicode")


def main(raw_path, report_path):
    with open(raw_path, errors="replace") as raw:
        tests, completed = parse(raw.read())
    with open(report_path, "w") as out:
        out.write(report(tests) + "\n")
    passed = completed and bool(tests) and all(test["outcome"] != "failure" for test in tests)
    if not passed:
        print(f"instrument-report: {sum(t['outcome'] == 'failure' for t in tests)} failed of {len(tests)}; "
              f"run {'completed' if completed else 'did not complete'}; raw output in {raw_path}", file=sys.stderr)
    return 0 if passed else 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
