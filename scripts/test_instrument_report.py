import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("instrument_report", Path(__file__).with_name("instrument-report.py"))
instrument_report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(instrument_report)

gate_spec = importlib.util.spec_from_file_location("gate", Path(__file__).with_name("android-test-gate.py"))
gate = importlib.util.module_from_spec(gate_spec)
gate_spec.loader.exec_module(gate)

CLASS = "de.pyryco.mobile.e2e.DeterministicInteractiveStreamE2ETest"


def status(test, code, **extra):
    lines = [f"INSTRUMENTATION_STATUS: class={CLASS}", "INSTRUMENTATION_STATUS: current=1",
             "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner", "INSTRUMENTATION_STATUS: numtests=1"]
    lines += [f"INSTRUMENTATION_STATUS: {key}={value}" for key, value in extra.items()]
    lines += ["INSTRUMENTATION_STATUS: stream=", f"INSTRUMENTATION_STATUS: test={test}",
              f"INSTRUMENTATION_STATUS_CODE: {code}"]
    return "\n".join(lines) + "\n"


END_OK = "INSTRUMENTATION_RESULT: stream=\n\nTime: 9.1\n\nOK (1 test)\n\n\nINSTRUMENTATION_CODE: -1\n"
STACK = "java.lang.AssertionError: no reply rendered\n\tat de.pyryco.mobile.e2e.X.ping(X.kt:12)\n"


class InstrumentReportTest(unittest.TestCase):
    def convert(self, raw):
        with tempfile.TemporaryDirectory() as tmp:
            raw_path, report_path = Path(tmp) / "raw.txt", Path(tmp) / "TEST-installed.xml"
            raw_path.write_text(raw)
            with contextlib.redirect_stderr(io.StringIO()):
                code = instrument_report.main(str(raw_path), str(report_path))
            return code, report_path.read_text(), report_path

    def judged(self, raw):
        """The exit code and what the device gate makes of the report."""
        with tempfile.TemporaryDirectory() as tmp:
            raw_path, path = Path(tmp) / "raw.txt", Path(tmp) / "TEST-installed.xml"
            raw_path.write_text(raw)
            with contextlib.redirect_stderr(io.StringIO()):
                code = instrument_report.main(str(raw_path), str(path))
            _, passed, executed = gate.combine_reports([path], CLASS)
        return code, passed, executed

    def test_a_passing_test_passes(self):
        code, xml, _ = self.convert(status("ping", 1) + status("ping", 0) + END_OK)
        self.assertEqual(code, 0)
        case = ET.fromstring(xml).find("testcase")
        self.assertEqual((case.get("classname"), case.get("name")), (CLASS, "ping"))
        self.assertEqual(list(case), [])
        self.assertEqual(self.judged(status("ping", 1) + status("ping", 0) + END_OK), (0, True, 1))

    def test_a_failing_test_fails_with_its_stack(self):
        raw = status("ping", 1) + status("ping", -2, stack=STACK) + END_OK.replace("OK (1 test)", "FAILURES!!!")
        code, xml, _ = self.convert(raw)
        self.assertEqual(code, 1)
        failure = ET.fromstring(xml).find("testcase/failure")
        self.assertEqual(failure.get("message"), "java.lang.AssertionError: no reply rendered")
        self.assertIn("X.kt:12", failure.text)
        self.assertEqual(self.judged(raw), (1, False, 1))

    def test_a_crash_mid_test_fails(self):
        raw = (status("ping", 1) + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n"
               "INSTRUMENTATION_CODE: 0\n")
        code, xml, _ = self.convert(raw)
        self.assertEqual(code, 1)
        self.assertEqual(ET.fromstring(xml).find("testcase/failure").get("message"), instrument_report.UNFINISHED)
        self.assertEqual(self.judged(raw), (1, False, 1))

    def test_a_run_that_never_completes_fails_even_when_its_tests_passed(self):
        raw = status("ping", 1) + status("ping", 0)
        self.assertEqual(self.convert(raw)[0], 1)

    def test_a_run_with_no_test_fails_and_counts_nothing(self):
        for raw in ("INSTRUMENTATION_FAILED: de.pyryco.mobile.test/de.pyryco.mobile.e2e.E2eInstrumentationRunner\n"
                    "INSTRUMENTATION_STATUS: Error=Unable to find instrumentation info\n"
                    "INSTRUMENTATION_STATUS_CODE: -1\n", END_OK, ""):
            with self.subTest(raw=raw[:30]):
                code, passed, executed = self.judged(raw)
                self.assertEqual(code, 1)
                self.assertEqual(executed, 0)

    def test_ignored_and_assumption_failures_are_skipped(self):
        raw = (status("a", 1) + status("a", -3) + status("b", 1) + status("b", -4, stack="AssumptionViolated")
               + status("c", 1) + status("c", 0) + END_OK)
        code, xml, _ = self.convert(raw)
        self.assertEqual(code, 0)
        self.assertEqual([case.find("skipped") is not None for case in ET.fromstring(xml).iter("testcase")],
                         [True, True, False])
        self.assertEqual(self.judged(raw), (0, True, 1))

    def test_a_failure_reported_without_a_start_still_fails(self):
        raw = status("initializationError", -2, stack=STACK) + END_OK
        self.assertEqual(self.judged(raw), (1, False, 1))


if __name__ == "__main__":
    unittest.main()
