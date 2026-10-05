import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("pre_verify", Path(__file__).with_name("pre-verify.py"))
pv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pv)

PREFIX = pv.LIVE_CLASS + "#"

LIVE_SOURCE = textwrap.dedent("""\
    package de.pyryco.mobile.e2e

    class InteractiveStreamE2ETest {
        private val replyTimeoutMs = 30_000L

        @Test
        fun interactiveTurn_alpha() {
            openThread()
            send("ping")
        }

        @Test
        fun interactiveTurn_beta() {
            send("pong")
        }

        @Test
        fun interactiveTurn_gamma() = runBlocking {
            waitFor(replyTimeoutMs)
        }

        private fun send(text: String) {
            type(text)
        }

        private fun openThread() {
            // opens the first thread
            click("thread")
        }

        private fun waitFor(ms: Long) = Unit
    }
    """)


def run(*args, cwd):
    return subprocess.run(args, cwd=cwd, capture_output=True, text=True, check=True).stdout


def hunk_for(old, new):
    """A --unified=0 diff of two strings, as git prints it."""
    with tempfile.TemporaryDirectory() as tmp:
        a, b = Path(tmp) / "a", Path(tmp) / "b"
        a.write_text(old)
        b.write_text(new)
        result = subprocess.run(["git", "diff", "--no-index", "--unified=0", str(a), str(b)],
                                capture_output=True, text=True)
        return result.stdout


class LiveTestsSectionTest(unittest.TestCase):
    def test_reads_the_section_like_the_dispatcher(self):
        body = "## Summary\nx\n\n## Live tests\n- `A.B#one`\nA.B#two, A.B#one\n\n### Note\nA.B#three\n## Next\nA.B#four\n"
        # A deeper heading is read as an entry, as the dispatcher does.
        self.assertEqual(("list", ["A.B#one", "A.B#two", "### Note", "A.B#three"]), pv.parse_live_tests_section(body))

    def test_missing_empty_and_all(self):
        self.assertEqual(("missing", []), pv.parse_live_tests_section("## Summary\nx\n"))
        self.assertEqual(("missing", []), pv.parse_live_tests_section("## Live tests\n\n## Next\n"))
        self.assertEqual(("all", []), pv.parse_live_tests_section("## live tests\n- all\n"))

    def test_a_trailing_footer_is_read_as_a_name(self):
        kind, names = pv.parse_live_tests_section("## Live tests\nA.B#one\n\n🤖 Generated with [Claude Code](x)\n")
        self.assertEqual("list", kind)
        self.assertFalse(pv.JUNIT_NAME.match(names[-1]))


class SecurityReviewTest(unittest.TestCase):
    def test_missing_section(self):
        self.assertIn("no `## Security review`", pv.security_review_problem("# Plan\n## Design\nx\n"))

    def test_section_needs_verdict_and_findings(self):
        self.assertIsNotNone(pv.security_review_problem("## Security review\n\nTo do.\n"))
        self.assertIsNotNone(pv.security_review_problem("## Security review\n\n**Verdict:** PASS\n\nNone.\n"))
        good = "## Security review\n\n**Verdict:** PASS\n\n**Findings:**\n\n- [Tokens] No findings.\n## Revisions\n"
        self.assertIsNone(pv.security_review_problem(good))

    def test_a_table_counts_as_findings_and_a_nested_review_counts(self):
        table = "## Security review (`security-sensitive`)\nVerdict: PASS\n\n| Concern | Control |\n|---|---|\n"
        self.assertIsNone(pv.security_review_problem(table))
        nested = "## Revisions\n- x\n#### Security review (re-run)\n**Verdict:** FAIL\n- [Files] MUST FIX.\n"
        self.assertIsNone(pv.security_review_problem(nested))

    def test_a_heading_inside_a_code_fence_does_not_count(self):
        fenced = "## Design\n```markdown\n## Security review\n**Verdict:** PASS\n- x\n```\n"
        self.assertIsNotNone(pv.security_review_problem(fenced))

    def test_check_reads_the_ticket_plan(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(pv, "ROOT", Path(tmp)):
            plans = Path(tmp) / "docs/specs/architecture"
            plans.mkdir(parents=True)
            report = pv.Report()
            pv.check_security_review(report, 7, {"bug"})
            pv.check_security_review(report, 7, None)
            self.assertEqual([], report.failures)
            pv.check_security_review(report, 7, {"security-sensitive"})
            self.assertEqual(["security review"], report.failures)
            (plans / "7-thing.md").write_text("## Change\nx\n")
            (plans / "17-other.md").write_text("## Security review\n**Verdict:** PASS\n- ok\n")
            report = pv.Report()
            pv.check_security_review(report, 7, {"security-sensitive"})
            self.assertIn("7-thing.md has no", report.lines[0])
            (plans / "7-thing.md").write_text("## Security review\n**Verdict:** PASS\n- [Tokens] none\n")
            report = pv.Report()
            pv.check_security_review(report, 7, {"security-sensitive"})
            self.assertEqual([], report.failures)


class ThemeLiteralTest(unittest.TestCase):
    def diff(self, path, *added):
        body = "\n".join("+" + line for line in added)
        return f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n@@ -10,0 +11,{len(added)} @@\n{body}\n"

    def test_finds_the_verifier_scan_literals(self):
        diff = self.diff("app/src/main/java/de/pyryco/mobile/ui/X.kt",
                         "val a = Color(0xFF6750A4)",
                         "    .clip(RoundedCornerShape(6.dp))",
                         "style = TextStyle(fontSize = 11.sp)")
        found = pv.theme_literal_findings(diff)
        self.assertEqual(3, len(found))
        self.assertTrue(found[1].startswith("app/src/main/java/de/pyryco/mobile/ui/X.kt:12 adds `RoundedCornerShape(`"))

    def test_ignores_theme_package_lookalikes_comments_and_marked_lines(self):
        self.assertEqual([], pv.theme_literal_findings(self.diff(pv.THEME_DIR + "Shapes.kt", "val s = RoundedCornerShape(6.dp)")))
        self.assertEqual([], pv.theme_literal_findings(self.diff(
            "app/src/main/java/de/pyryco/mobile/ui/X.kt",
            "MarkdownTextStyle(MaterialTheme.typography.bodyMedium)",
            "ProvideTextStyle(MaterialTheme.typography.labelSmall) {",
            "// was RoundedCornerShape(6.dp)",
            "val brand = Color(0xFF7AB8E8) // theme-literal: splash brand colour, not a scheme role")))


class AffectedLiveMethodsTest(unittest.TestCase):
    live = {"interactiveTurn_alpha", "interactiveTurn_beta", "interactiveTurn_gamma"}

    def affected(self, old, new):
        return pv.affected_live_methods(new, hunk_for(old, new), self.live)

    def test_spans_cover_annotations_bodies_and_expression_functions(self):
        spans = {name: (first, last) for name, first, last in pv.function_spans(LIVE_SOURCE.splitlines())}
        self.assertEqual((6, 10), spans["interactiveTurn_alpha"])
        self.assertEqual((17, 20), spans["interactiveTurn_gamma"])
        self.assertEqual((31, 31), spans["waitFor"])

    def test_a_changed_test_body_affects_only_that_test(self):
        self.assertEqual(["interactiveTurn_beta"], self.affected(LIVE_SOURCE, LIVE_SOURCE.replace('send("pong")', 'send("pang")')))

    def test_a_changed_helper_affects_its_callers_transitively(self):
        new = LIVE_SOURCE.replace("type(text)", "type(text.trim())")
        self.assertEqual(["interactiveTurn_alpha", "interactiveTurn_beta"], self.affected(LIVE_SOURCE, new))

    def test_a_changed_property_affects_its_readers(self):
        new = LIVE_SOURCE.replace("30_000L", "60_000L")
        self.assertEqual(["interactiveTurn_gamma"], self.affected(LIVE_SOURCE, new))

    def test_comment_and_blank_changes_affect_nothing(self):
        new = LIVE_SOURCE.replace("// opens the first thread", "// opens the newest thread")
        self.assertEqual([], self.affected(LIVE_SOURCE, new))

    def test_a_removed_line_counts_where_it_was(self):
        new = LIVE_SOURCE.replace('        openThread()\n', "")
        self.assertEqual(["interactiveTurn_alpha"], self.affected(LIVE_SOURCE, new))

    def test_a_new_test_is_affected(self):
        new = LIVE_SOURCE.replace("    private fun send(", "    @Test\n    fun interactiveTurn_delta() {\n        openThread()\n    }\n\n    private fun send(")
        self.assertEqual(["interactiveTurn_delta"], pv.affected_live_methods(new, hunk_for(LIVE_SOURCE, new), self.live | {"interactiveTurn_delta"}))


class RepositoryTest(unittest.TestCase):
    """End to end in a throwaway repository, with GitHub and the curated list stubbed."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        run("git", "init", "-q", "-b", "main", cwd=self.root)
        run("git", "config", "user.email", "t@example.com", cwd=self.root)
        run("git", "config", "user.name", "T", cwd=self.root)
        (self.root / ".gitignore").write_text("/AGENTS.md\nlocal.properties\n")
        live = self.root / pv.LIVE_FILE
        live.parent.mkdir(parents=True)
        live.write_text(LIVE_SOURCE)
        run("git", "add", ".", cwd=self.root)
        run("git", "commit", "-q", "-m", "base", cwd=self.root)
        run("git", "checkout", "-q", "-b", "feature/42", cwd=self.root)
        self.patches = [patch.object(pv, "ROOT", self.root),
                        patch.object(pv, "curated_live_methods",
                                     return_value=["interactiveTurn_alpha", "interactiveTurn_beta", "interactiveTurn_gamma"])]
        for p in self.patches:
            p.start()

    def tearDown(self):
        for p in self.patches:
            p.stop()
        self.tmp.cleanup()

    def commit(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        run("git", "add", "-f", path, cwd=self.root)
        run("git", "commit", "-q", "-m", f"change {path}", cwd=self.root)

    def main(self, labels=frozenset(), body=None, argv=()):
        out = io.StringIO()
        with patch.object(pv, "read_github", return_value=(set(labels), body, [])), contextlib.redirect_stdout(out):
            code = pv.main(list(argv))
        return code, out.getvalue()

    def test_clean_branch_passes(self):
        self.commit("app/src/main/java/de/pyryco/mobile/ui/X.kt", "val a = MaterialTheme.colorScheme.primary\n")
        code, out = self.main()
        self.assertEqual(0, code, out)
        self.assertIn("pre-verify #42 against merge base", out)
        self.assertIn("pre-verify: passed", out)

    def test_an_ignored_operator_file_fails(self):
        self.commit("AGENTS.md", "# local instructions\n")
        code, out = self.main()
        self.assertEqual(1, code)
        self.assertIn("FAIL  operator files", out)
        self.assertIn("`AGENTS.md`", out)
        self.assertIn("git rm --cached", out)

    def test_a_theme_literal_fails_with_its_location(self):
        self.commit("app/src/main/java/de/pyryco/mobile/ui/X.kt", "val s = RoundedCornerShape(6.dp)\n")
        code, out = self.main()
        self.assertEqual(1, code)
        self.assertIn("ui/X.kt:1 adds `RoundedCornerShape(`", out)

    def test_live_list_must_be_curated_and_cover_changed_methods(self):
        self.commit(pv.LIVE_FILE, LIVE_SOURCE.replace("type(text)", "type(text.trim())"))
        code, out = self.main(body=f"## Live tests\n{PREFIX}interactiveTurn_alpha\nInteractiveStreamE2ETest#interactiveTurn_beta\n")
        self.assertEqual(1, code)
        self.assertIn("not curated live methods", out)
        self.assertIn("`InteractiveStreamE2ETest#interactiveTurn_beta`", out)
        self.assertIn(f"omits them: `{PREFIX}interactiveTurn_beta`", out)
        code, out = self.main(body=f"## Live tests\n{PREFIX}interactiveTurn_alpha\n{PREFIX}interactiveTurn_beta\n")
        self.assertEqual(0, code, out)
        code, out = self.main(body="## Live tests\nall\n")
        self.assertEqual(0, code, out)

    def test_always_run_tests_and_full_paths_need_no_listing(self):
        self.commit(pv.LIVE_FILE, LIVE_SOURCE.replace("type(text)", "type(text.trim())"))
        body = f"## Live tests\n{PREFIX}interactiveTurn_alpha\n"
        with patch.dict("os.environ", {"PYRY_REAL_CLAUDE_GATE_ALWAYS_TESTS": f"{PREFIX}interactiveTurn_beta"}):
            self.assertEqual(0, self.main(body=body)[0])
        with patch.dict("os.environ", {"PYRY_REAL_CLAUDE_GATE_FULL_PATHS": "app/src/androidTest/"}):
            code, out = self.main(body=body)
        self.assertEqual(0, code, out)
        self.assertIn("runs everything", out)

    def test_security_sensitive_ticket_needs_its_review(self):
        self.commit("docs/specs/architecture/42-thing.md", "## Change\nx\n")
        code, out = self.main(labels={"security-sensitive"})
        self.assertEqual(1, code)
        self.assertIn("FAIL  security review", out)
        self.commit("docs/specs/architecture/42-thing.md", "## Security review\n**Verdict:** PASS\n- [Tokens] none\n")
        self.assertEqual(0, self.main(labels={"security-sensitive"})[0])

    def test_unreadable_github_skips_rather_than_fails(self):
        out = io.StringIO()
        with patch.object(pv, "read_github", return_value=(None, None, ["issue #42: gh exited 1"])), \
                contextlib.redirect_stdout(out):
            code = pv.main([])
        self.assertEqual(0, code, out.getvalue())
        self.assertIn("skip  security review", out.getvalue())
        self.assertIn("note  issue #42: gh exited 1", out.getvalue())

    def test_needs_a_ticket_off_a_feature_branch(self):
        run("git", "checkout", "-q", "main", cwd=self.root)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            pv.main([])
        self.assertEqual(2, raised.exception.code)


if __name__ == "__main__":
    unittest.main()
