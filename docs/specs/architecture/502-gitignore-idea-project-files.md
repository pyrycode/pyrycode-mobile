# Spec #502 — Untrack the leftover `.idea/.gitignore`

**Ticket:** https://github.com/pyrycode/pyrycode-mobile/issues/502
**Size:** XS (git-tracking change only — zero production source files, no tests, no UI)

## Files to read first

- `.gitignore:28` — the broad `.idea/` rule added by #503 (commit `51501f3`). This rule already ignores everything under `.idea/`. **Confirm it is present; do not edit, re-add, or duplicate it.**
- `.gitignore:4-9` — pre-existing scaffold rules (`/.idea/caches`, `/.idea/libraries`, `/.idea/modules.xml`, `/.idea/workspace.xml`, `/.idea/navEditor.xml`, `/.idea/assetWizardSettings.xml`). These are a redundant subset of line 28. **Out of scope — leave them untouched** ("don't refactor adjacent code while you're there").
- `.idea/.gitignore` — the single leftover tracked file. Committed in the initial scaffold `ddd9178`, before the `.idea/` rule existed. Contents are Android Studio's default ignored-files list; the physical file stays on disk and remains IDE-managed.

## Context

The Cross-Repo Code Review (2026-07-03) flagged eight `.idea/*` files as untracked noise. PR #503 fixed the *untracked-noise* symptom by adding `.idea/` to the root `.gitignore` (line 28), so `git status` on current `main` is already clean.

One residual gap remains: git keeps tracking files that were committed **before** a matching `.gitignore` rule existed. Verified against current `main`:

```
git ls-files .idea/   →   .idea/.gitignore     (exactly one entry)
```

That one tracked file contradicts the "ignore all of `.idea/`" policy. The original ticket's *"None tracked, so no `git rm --cached` needed"* note is **stale** — a `git rm --cached` **is** required for this file. (Confirmed during this architect run.)

## Design

There is no code, interface, state, or UI surface here. The entire change is one git index operation plus a commit.

**Operation:**

```bash
git rm --cached .idea/.gitignore
```

`--cached` removes the file from the git index only; the working-tree copy is left on disk untouched. After this, the line-28 `.idea/` rule keeps the (now-untracked) file — and everything else under `.idea/` — ignored, so it will not reappear as untracked noise in `git status`.

**No `.gitignore` edit is required.** The rule already exists (line 28). Editing it is out of scope and would violate the AC #3 "preserved, not re-added or duplicated" constraint.

## Verification (maps 1:1 to acceptance criteria)

Run each after the `git rm --cached` and stage/commit:

| AC | Command | Expected |
|----|---------|----------|
| #1 — untracked, file stays on disk | `git status --short` shows `D  .idea/.gitignore` staged **and** `test -f .idea/.gitignore` | staged deletion present; file still exists on disk |
| #2 — nothing left committed under `.idea/` | `git ls-files .idea/` | empty output (exit 0, no lines) |
| #3 — line-28 rule preserved | `grep -n '^\.idea/$' .gitignore` | prints `28:.idea/` (rule intact, not duplicated) |

Post-commit sanity: `git status` is clean (no `.idea/*` reappears as untracked, because line 28 ignores it).

## Testing strategy

No automated test. Git-tracking state is not exercised by `./gradlew test`, and adding an instrumented/unit test for it would be inappropriate. Verification is the three `git ls-files` / `git status` / `grep` commands above — the developer runs them and confirms output in the PR description.

## Error handling / edge cases

- If `git rm --cached .idea/.gitignore` errors with *"did not match any files"*, the file is already untracked — re-verify with `git ls-files .idea/` (should be empty) and treat AC #1/#2 as already satisfied; no commit needed for the removal.
- Do **not** use `git rm` without `--cached` — that deletes the working-tree copy, which the AC explicitly requires to remain on disk.

## Open questions

None. All ticket claims were verified against current `main` during this architect run.
