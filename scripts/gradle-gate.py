#!/usr/bin/env python3
"""Compatibility entry point for a mobile agents pipeline tool."""
import os
from pathlib import Path
import sys

if __name__ == "__main__":
    os.execv(sys.executable, [sys.executable, str(Path(__file__).with_name("agent-tool.py")), Path(__file__).name, *sys.argv[1:]])
