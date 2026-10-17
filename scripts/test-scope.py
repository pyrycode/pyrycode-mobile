"""Apply the canonical Mobile selection policy before launcher side effects."""
import os
from pathlib import Path
import runpy
import sys


def check_selection(root, kind, args):
    root = Path(root).resolve()
    agents = Path(os.environ.get('AGENTS_REPO_PATH', root.parent / 'pyrycode-mobile-agents'))
    guard = agents / 'scripts/test-selection.py'
    if guard.is_file():
        runpy.run_path(str(guard))['check_selection'](root, kind, args)
        return
    # Old workers can merge product main before their supervised consumer update.
    # A managed binding can never use that compatibility path.
    git_dir = root / '.git'
    if git_dir.is_file():
        link = git_dir.read_text().strip()
        if not link.startswith('gitdir: '):
            sys.exit('Managed test context has an invalid Git worktree link.')
        git_dir = (root / link[8:]).resolve()
    if (git_dir / 'pyry-test-context').exists() or (git_dir / 'pyry-test-managed').exists():
        sys.exit('Managed test selection helper is unavailable; no test was admitted.')


if __name__ == '__main__':
    check_selection(Path(__file__).resolve().parents[1], sys.argv[1], sys.argv[2:])
