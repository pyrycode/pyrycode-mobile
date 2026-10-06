#!/usr/bin/env python3
"""Fast checks for what the verifier would otherwise fail a pull request on.

Run it in a feature worktree, on the branch `feature/<ticket>`. It is the dispatcher's first verifier
gate and the builder's last step before handoff. Each check mirrors one the verifier applies by hand:

- security review: a `security-sensitive` ticket's plan has a `## Security review` with a verdict and
  findings (verifier/review-criteria.md, "Security-sensitive tickets");
- theme literals: no new `Color(0x`, `TextStyle(` or `RoundedCornerShape(` in changed main sources
  outside the theme package (the Material 3 tokens scan in the same file);
- live tests: the pull request's `## Live tests` names only curated live methods, and every live
  method the diff adds or changes, directly or through a helper (verifier/CLAUDE.md, "Live-Claude tests");
- operator files: the diff adds no file the repository ignores, such as `AGENTS.md`, which the
  dispatcher's automatic commit can pick up (#1439);
- with --gradle only: origin/main is merged, then Spotless and every Kotlin compile pass. The dispatcher
  runs those itself in its next gates, so its gate line leaves --gradle off.

Exit 0 when nothing fails, 1 when a check fails, 2 on bad usage. A check that cannot read what it needs,
such as GitHub being unreachable, is reported as skipped and does not fail the run: a gate that goes red
on an outage would stop every later gate.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO = "pyrycode/pyrycode-mobile"
LIVE_CLASS = "de.pyryco.mobile.e2e.InteractiveStreamE2ETest"
LIVE_FILE = "app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt"
THEME_DIR = "app/src/main/java/de/pyryco/mobile/ui/theme/"
LITERAL = re.compile(r"(?<![A-Za-z0-9_])(Color\(0x|TextStyle\(|RoundedCornerShape\()")
LITERAL_OK = re.compile(r"//\s*theme-literal:\s*\S")
OPERATOR_FILES = ("AGENTS.md",)
# The dispatcher's JUnit selection shape (agent-dispatcher gate-output.ts, buildJUnitBaselineFilter).
JUNIT_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_$]*(\.[A-Za-z_][A-Za-z0-9_$]*)*#[A-Za-z_][A-Za-z0-9_]*$")
GH_TIMEOUT = 60


class Report:
    def __init__(self):
        self.failures = []
        self.lines = []

    def ok(self, check, text):
        self.lines.append(f"ok    {check}: {text}")

    def skip(self, check, text):
        self.lines.append(f"skip  {check}: {text}")

    def fail(self, check, text):
        self.failures.append(check)
        self.lines.append(f"FAIL  {check}: {text}")


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout


def merge_base():
    for ref in ("origin/main", "main"):
        try:
            return git("merge-base", "HEAD", ref).strip()
        except subprocess.CalledProcessError:
            continue
    return None


def ticket_from_branch():
    try:
        branch = git("branch", "--show-current").strip()
    except subprocess.CalledProcessError:
        return None
    match = re.fullmatch(r"feature/(\d+)", branch)
    return int(match.group(1)) if match else None


def gh_json(*args):
    """Parsed JSON from a gh command, or (None, reason)."""
    try:
        result = subprocess.run(["gh", *args], capture_output=True, text=True, timeout=GH_TIMEOUT)
    except (OSError, subprocess.TimeoutExpired) as error:
        return None, f"gh could not run: {error}"
    if result.returncode != 0:
        return None, f"gh exited {result.returncode}: {result.stderr.strip()[:200]}"
    try:
        return json.loads(result.stdout), None
    except ValueError:
        return None, "gh returned unreadable JSON"


# --- security review -------------------------------------------------------------------------------

def markdown_sections(text, title):
    """Bodies of every heading at level 2 or deeper whose title starts with `title`, outside code fences."""
    lines = text.splitlines()
    sections, fenced = [], False
    for index, line in enumerate(lines):
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        match = None if fenced else re.match(r"^(#{2,6})\s+(.*)$", line)
        if not match or not match.group(2).strip().lower().startswith(title.lower()):
            continue
        level, body, inner = len(match.group(1)), [], False
        for later in lines[index + 1:]:
            if later.lstrip().startswith("```"):
                inner = not inner
            heading = None if inner else re.match(r"^(#{1,6})\s", later)
            if heading and len(heading.group(1)) <= level:
                break
            body.append(later)
        sections.append("\n".join(body))
    return sections


def security_review_problem(text):
    """None when the plan holds a usable security review, else what is missing."""
    sections = markdown_sections(text, "Security review")
    if not sections:
        return "has no `## Security review` section"
    for body in sections:
        verdict = re.search(r"verdict\W*\s*(PASS|FAIL)", body, re.IGNORECASE)
        findings = re.search(r"^\s*(([-*+]|\d+[.)])\s+\S|\|.*\|)", body, re.MULTILINE)
        if verdict and findings:
            return None
    return "has a `## Security review` heading but no `Verdict:` PASS or FAIL line with a findings list or table under it"


def check_security_review(report, ticket, labels):
    name = "security review"
    if labels is None:
        report.skip(name, "the issue's labels could not be read")
        return
    if "security-sensitive" not in labels:
        report.ok(name, "the issue is not labelled security-sensitive")
        return
    plans = sorted((ROOT / "docs/specs/architecture").glob(f"{ticket}-*.md"))
    if not plans:
        report.fail(name, f"#{ticket} is labelled security-sensitive, but there is no plan at "
                          f"docs/specs/architecture/{ticket}-*.md. Commit the plan with its security review "
                          f"(builder/security-review.md).")
        return
    problems = []
    for plan in plans:
        problem = security_review_problem(plan.read_text(encoding="utf-8", errors="replace"))
        if problem is None:
            report.ok(name, f"{plan.relative_to(ROOT)} has a security review with a verdict and findings")
            return
        problems.append(f"{plan.relative_to(ROOT)} {problem}")
    report.fail(name, f"#{ticket} is labelled security-sensitive. " + "; ".join(problems) +
                ". The verifier fails this on sight. Run builder/security-review.md on the plan and record "
                "it with a Revisions entry.")


# --- theme literals --------------------------------------------------------------------------------

def added_lines(diff):
    """(path, new line number, text) for each added line of a unified diff."""
    path, number = None, 0
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("@@"):
            match = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)", line)
            number = int(match.group(1)) if match else 0
        elif line.startswith("+") and path is not None:
            yield path, number, line[1:]
            number += 1
        elif line.startswith(" "):
            number += 1


def theme_literal_findings(diff):
    found = []
    for path, number, text in added_lines(diff):
        if not path.endswith(".kt") or path.startswith(THEME_DIR) or LITERAL_OK.search(text):
            continue
        match = LITERAL.search(text.split("//", 1)[0])
        if match:
            found.append(f"{path}:{number} adds `{match.group(1)}`: {text.strip()[:120]}")
    return found


def check_theme_literals(report, base):
    name = "theme literals"
    diff = git("diff", "--unified=0", base, "HEAD", "--", ":(glob)app/src/main/**/*.kt")
    found = theme_literal_findings(diff)
    if not found:
        report.ok(name, "no new colour, text style or corner shape literal outside the theme")
        return
    report.fail(name, "new literals where the verifier expects MaterialTheme.colorScheme, .typography or "
                      ".shapes (review-criteria.md, Material 3 tokens):\n      " + "\n      ".join(found) +
                "\n      Use the theme token, or add one under ui/theme/. If no token can apply, such as a "
                "brand colour the design names, end the line with `// theme-literal: <reason>`; the verifier "
                "still judges the reason.")


# --- live tests ------------------------------------------------------------------------------------

def parse_live_tests_section(body):
    """The dispatcher's reading of `## Live tests` (agent-dispatcher gate-selection.ts, parseLiveTestsSection)."""
    lines = re.split(r"\r?\n", body or "")
    start = next((i for i, line in enumerate(lines) if re.match(r"^##\s+live tests\s*$", line.strip(), re.I)), -1)
    if start == -1:
        return ("missing", [])
    names = []
    for line in lines[start + 1:]:
        if re.match(r"^#{1,2}\s", line.strip()):
            break
        for part in re.sub(r"^\s*[-*]\s*", "", line).split(","):
            name = re.sub(r"^`+|`+$", "", part.strip()).strip()
            if not name:
                continue
            if name.lower() == "all":
                return ("all", [])
            if name not in names:
                names.append(name)
    return ("list", names) if names else ("missing", [])


def curated_live_methods():
    spec = importlib.util.spec_from_file_location("android_test_gate", ROOT / "scripts" / "android-test-gate.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.curated_live_methods()


FUN = re.compile(r"^(\s*)(?:[\w@:()\".,= ]+\s+)?fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*[(<]")
VAL = re.compile(r"^\s*(?:[\w@:]+\s+)*(?:val|var)\s+(\w+)")


def indent(line):
    return len(line) - len(line.lstrip())


def function_spans(lines):
    """(name, first, last) for each outermost function, 1-based and inclusive, annotations included.

    Spotless keeps the file ktlint-formatted, so a block body closes with `}` at the declaration's
    indent, and an expression body ends before the next line at that indent or less.
    """
    spans, index = [], 0
    while index < len(lines):
        match = FUN.match(lines[index])
        if not match or lines[index].lstrip().startswith(("//", "*")):
            index += 1
            continue
        depth = indent(lines[index])
        first = index
        while first > 0 and indent(lines[first - 1]) == depth and lines[first - 1].lstrip().startswith("@"):
            first -= 1
        last = index
        for later in range(index + 1, len(lines)):
            text = lines[later]
            if not text.strip():
                continue
            if indent(text) < depth or (indent(text) == depth and not text.lstrip().startswith((")", "}"))):
                break
            last = later
            if indent(text) == depth and text.lstrip().startswith("}"):
                break
        spans.append((match.group(2), first + 1, last + 1))
        index = last + 1
    return spans


def changed_positions(diff):
    """New-side line numbers the diff touches, ignoring blank and comment-only changes.

    A pure deletion counts at the line it was removed before.
    """
    touched = set()

    def meaningful(text):
        stripped = text.strip()
        return stripped and not stripped.startswith(("//", "*", "/*"))

    lines = diff.splitlines()
    index = 0
    while index < len(lines):
        match = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", lines[index])
        index += 1
        if not match:
            continue
        start, count = int(match.group(1)), int(match.group(2) or "1")
        removed, added = [], []
        while index < len(lines) and not lines[index].startswith("@@"):
            if lines[index].startswith("-") and not lines[index].startswith("---"):
                removed.append(lines[index][1:])
            elif lines[index].startswith("+") and not lines[index].startswith("+++"):
                added.append(lines[index][1:])
            index += 1
        for offset, text in enumerate(added):
            if meaningful(text):
                touched.add(start + offset)
        if count == 0 and any(meaningful(text) for text in removed):
            touched.add(max(start, 1))
            touched.add(start + 1)
    return touched


def affected_live_methods(source, diff, live_methods):
    """Live methods the diff adds or changes, directly or through a helper or property in the same file."""
    lines = source.splitlines()
    spans = function_spans(lines)
    touched = changed_positions(diff)
    changed = set()
    for line in touched:
        owner = next((name for name, first, last in spans if first <= line <= last), None)
        if owner is not None:
            changed.add(owner)
        elif 1 <= line <= len(lines) and (match := VAL.match(lines[line - 1])):
            changed.add(match.group(1))
    bodies = {}
    for name, first, last in spans:
        bodies[name] = bodies.get(name, "") + "\n" + "\n".join(lines[first:last])
    affected, frontier = set(changed), set(changed)
    while frontier:
        reached = set()
        for name, body in bodies.items():
            if name in affected:
                continue
            if any(re.search(rf"(?<![\w$]){re.escape(symbol)}(?![\w$])", body) for symbol in frontier):
                reached.add(name)
        affected |= reached
        frontier = reached
    return sorted(name for name in affected if name in live_methods)


def split_env_list(name):
    return [part.strip() for part in os.environ.get(name, "").split(",") if part.strip()]


def check_live_tests(report, base, body):
    name = "live tests"
    if body is None:
        report.skip(name, "no open pull request body to read")
        return
    kind, names = parse_live_tests_section(body)
    if kind == "missing":
        report.ok(name, "no `## Live tests` list, so the live gate runs every curated method")
        return
    if kind == "all":
        report.ok(name, "`## Live tests` asks for all of them")
        return
    curated = curated_live_methods()
    prefix = LIVE_CLASS + "#"
    unshaped = [n for n in names if not JUNIT_NAME.match(n)]
    if unshaped:
        report.ok(name, "the `## Live tests` list holds an entry that is not a Class#method name, such as "
                        f"`{unshaped[0][:80]}`, so the dispatcher runs the full suite. Keep only names in the "
                        "section if a narrower run is wanted.")
        return
    wrong = [n for n in names if not n.startswith(prefix) or n[len(prefix):] not in curated]
    problems = []
    if wrong:
        problems.append("these entries are not curated live methods, so the selected live run cannot pass: " +
                        ", ".join(f"`{n}`" for n in wrong) + f". Each entry must be `{prefix}<method>` with the "
                        "method on the LIVE list in scripts/e2e-emulator.sh, not `@Ignore`d")
    paths = git("diff", "--name-only", base, "HEAD").split()
    full_paths = split_env_list("PYRY_REAL_CLAUDE_GATE_FULL_PATHS")
    shared = next((p for p in paths if any(p.startswith(prefix_) for prefix_ in full_paths)), None)
    if shared is None and LIVE_FILE in paths:
        diff = git("diff", "--unified=0", base, "HEAD", "--", LIVE_FILE)
        source = (ROOT / LIVE_FILE).read_text(encoding="utf-8")
        always = {n[len(prefix):] for n in split_env_list("PYRY_REAL_CLAUDE_GATE_ALWAYS_TESTS") if n.startswith(prefix)}
        listed = {n[len(prefix):] for n in names if n.startswith(prefix)}
        missing = [m for m in affected_live_methods(source, diff, set(curated)) if m not in listed | always]
        if missing:
            problems.append(f"the diff adds or changes these live methods, directly or through a helper they call, "
                            f"but `## Live tests` omits them: " + ", ".join(f"`{prefix}{m}`" for m in missing) +
                            ". List each, or write `all` when a shared helper changed")
    if problems:
        report.fail(name, "; ".join(problems) + ". Edit the pull request body (builder/device-tests.md, "
                                                "\"The real-Claude harness\").")
    elif shared is not None:
        report.ok(name, f"every name is curated; the branch changes `{shared}`, so the live gate runs everything")
    else:
        report.ok(name, f"{len(names)} curated name(s), covering every live method the diff changes")


# --- operator files --------------------------------------------------------------------------------

def check_operator_files(report, base):
    name = "operator files"
    status = git("diff", "--name-status", "--no-renames", base, "HEAD")
    paths = [line.split("\t")[-1] for line in status.splitlines() if line and line[0] in "AM"]
    bad = [p for p in paths if p in OPERATOR_FILES]
    if paths:
        result = subprocess.run(["git", "check-ignore", "--no-index", "--stdin"], cwd=ROOT,
                                input="\n".join(paths) + "\n", capture_output=True, text=True)
        bad += [p for p in result.stdout.splitlines() if p and p not in bad]
    if not bad:
        report.ok(name, "the diff adds no ignored or local assistant file")
        return
    report.fail(name, "the branch adds files the repository ignores or keeps local, usually through the "
                      "dispatcher's automatic commit: " + ", ".join(f"`{p}`" for p in bad) +
                ". Remove each from the branch with `git rm --cached <path>`, which keeps the local copy, "
                "then commit and push.")


# --- gradle ----------------------------------------------------------------------------------------

def check_gradle(report):
    name = "merge, format and compile"
    fetched = subprocess.run(["git", "fetch", "--quiet", "origin", "main"], cwd=ROOT, capture_output=True, text=True)
    if fetched.returncode != 0:
        print("pre-verify: could not fetch origin/main; checking against the local copy", file=sys.stderr)
    behind = subprocess.run(["git", "merge-base", "--is-ancestor", "origin/main", "HEAD"], cwd=ROOT)
    if behind.returncode != 0:
        report.fail(name, "origin/main has commits this branch lacks. Merge origin/main, then run the unit "
                          "suite and this script again, so the checks see what the verifier will.")
        return
    for command in (["./gradlew", "spotlessCheck", "--rerun-tasks", "--console=plain"],
                    ["./gradlew", "compileDebugKotlin", "compileDebugUnitTestKotlin",
                     "compileDebugAndroidTestKotlin", "--console=plain"]):
        # Output stays in the caller's result, so the dispatcher can credit Gradle's build-slot waits.
        if subprocess.run(command, cwd=ROOT).returncode != 0:
            report.fail(name, f"`{' '.join(command)}` failed on the merged branch; its output is above.")
            return
    report.ok(name, "origin/main is merged, Spotless is clean and every Kotlin source set compiles")


# --- main ------------------------------------------------------------------------------------------

def read_github(ticket):
    """(labels or None, PR body or None, notes)."""
    notes = []
    issue, why = gh_json("issue", "view", str(ticket), "--repo", REPO, "--json", "labels")
    labels = {label["name"] for label in issue["labels"]} if issue else None
    if why:
        notes.append(f"issue #{ticket}: {why}")
    prs, why = gh_json("pr", "list", "--repo", REPO, "--head", f"feature/{ticket}", "--state", "open",
                       "--json", "number,body", "--limit", "1")
    body = prs[0]["body"] if prs else None
    if why:
        notes.append(f"pull request for feature/{ticket}: {why}")
    return labels, body, notes


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ticket", type=int, help="issue number; defaults to the N in the feature/N branch")
    parser.add_argument("--body-file", type=Path,
                        help="read the pull request body from this file instead of GitHub, such as a draft "
                             "before `gh pr create`")
    parser.add_argument("--base", help="compare against this commit instead of the merge base with origin/main")
    parser.add_argument("--gradle", action="store_true",
                        help="also require origin/main merged, Spotless clean and every Kotlin compile green")
    args = parser.parse_args(argv)
    ticket = args.ticket or ticket_from_branch()
    if ticket is None:
        parser.error("not on a feature/<ticket> branch; pass --ticket N")
    base = git("rev-parse", "--verify", args.base + "^{commit}").strip() if args.base else merge_base()
    if base is None:
        parser.error("no merge base with origin/main or main")

    report = Report()
    labels, body, notes = read_github(ticket)
    if args.body_file is not None:
        body = args.body_file.read_text(encoding="utf-8")
    check_security_review(report, ticket, labels)
    check_theme_literals(report, base)
    check_live_tests(report, base, body)
    check_operator_files(report, base)
    if args.gradle:
        check_gradle(report)

    print(f"pre-verify #{ticket} against merge base {base[:12]}")
    for line in report.lines:
        print(line)
    for note in notes:
        print(f"note  {note}")
    if report.failures:
        print(f"pre-verify: {len(report.failures)} check(s) failed: {', '.join(report.failures)}. "
              "The verifier fails a pull request on each of these, so fix them before review.")
        return 1
    print("pre-verify: passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
