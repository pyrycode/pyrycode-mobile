#!/usr/bin/env python3
"""Compatibility entry point for a mobile agents pipeline tool."""
import os
from pathlib import Path
import sys

if __name__ == "__main__":
    os.execv(sys.executable, [sys.executable, str(Path(__file__).with_name("agent-tool.py")), Path(__file__).name, *sys.argv[1:]])
else:
    _root = Path(__file__).resolve().parents[1]
    _agents = Path(os.environ.get("AGENTS_REPO_PATH", _root.parent / "pyrycode-mobile-agents"))
    _script = _agents / "scripts" / Path(__file__).name
    if not _script.is_file():
        raise ImportError("Install pyrycode-mobile-agents beside this checkout or set AGENTS_REPO_PATH.")
    os.environ.setdefault("PYRY_MOBILE_REPO", str(_root))
    __file__ = str(_script)
    exec(compile(_script.read_bytes(), str(_script), "exec"), globals())
