from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


def run_guard(files):
    """Run the real guard over a throwaway features tree, so the repo's own docs are never read.

    The script resolves its own directory and cds to the parent, so a copy under
    <tmp>/scripts scans <tmp>/docs/knowledge/features regardless of the caller's cwd.
    """
    guard = Path(__file__).with_name("docs-guard.sh")
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "scripts").mkdir()
        shutil.copy2(guard, root / "scripts" / guard.name)
        features = root / "docs/knowledge/features"
        features.mkdir(parents=True)
        for name, body in files.items():
            (features / name).write_bytes(body)
        result = subprocess.run(["bash", str(root / "scripts" / guard.name)],
                                capture_output=True, text=True)
        return result.returncode, result.stderr


class DocsGuardMiscFormatTest(unittest.TestCase):
    """The two conditions format(\"misc\") in the root build.gradle.kts would rewrite."""

    def test_file_ending_in_exactly_one_newline_passes(self):
        code, err = run_guard({"ok.md": b"# Ok\n\nOne trailing newline, no trailing blanks.\n"})
        self.assertEqual(0, code, err)
        self.assertEqual("", err)

    def test_end_of_file_spotless_would_rewrite_fails(self):
        for name, body, told in (
            ("blank.md", b"# Blank\n\nEnds in a blank line.\n\n", "delete the blank line"),
            ("blanks.md", b"# Blanks\n\nEnds in three.\n\n\n", "delete the 2 blank lines"),
            ("bare.md", b"# Bare\n\nEnds without a newline.", "does not end in a newline"),
            ("empty.md", b"", "does not end in a newline"),
        ):
            with self.subTest(name=name):
                code, err = run_guard({name: body})
                self.assertEqual(1, code, err)
                self.assertIn(name, err)
                self.assertIn(told, err)
                self.assertIn("1 problem(s)", err)

    def test_trailing_whitespace_fails_naming_every_line(self):
        code, err = run_guard({"ws.md": b"# Ws\n\nspace \ntab\t\nclean\n"})
        self.assertEqual(1, code, err)
        self.assertIn("ws.md:3", err)
        self.assertIn("ws.md:4", err)
        self.assertIn("trailing whitespace", err)
        self.assertIn("2 problem(s)", err)


class DocsGuardExistingRulesTest(unittest.TestCase):
    def test_one_run_reports_every_problem_across_all_four_rules(self):
        code, err = run_guard({
            "big.md": b"# Big\n" + b"x" * 50000 + b"\n",
            "heading.md": b"# Heading\n\n#754 wrapped onto its own line.\n",
            "ws.md": b"# Ws\n\ntrailing \n",
            "blank.md": b"# Blank\n\nEnds in a blank line.\n\n",
        })
        self.assertEqual(1, code, err)
        for expected in ("big.md: 50007 bytes", "heading.md:3", "ws.md:3", "blank.md"):
            self.assertIn(expected, err)
        self.assertIn("4 problem(s)", err)


if __name__ == "__main__":
    unittest.main()
