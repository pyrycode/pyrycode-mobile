#!/usr/bin/env python3
"""Launch pipeline tools from the mobile agents checkout."""
import os
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
agents = Path(os.environ.get("AGENTS_REPO_PATH", root.parent / "pyrycode-mobile-agents"))
name = sys.argv[1]
if name not in {"pre-verify.py", "android-test-gate.py", "design-compare.py", "docs-guard.sh"}:
    sys.exit("Unknown pipeline tool")
script = agents / "scripts" / name
if not script.is_file():
    sys.exit("Install pyrycode-mobile-agents beside this checkout or set AGENTS_REPO_PATH.")
os.environ["PYRY_MOBILE_REPO"] = str(root)
os.chdir(root)
runtime = "bash" if name.endswith(".sh") else sys.executable
os.execvp(runtime, [runtime, str(script), *sys.argv[2:]])
