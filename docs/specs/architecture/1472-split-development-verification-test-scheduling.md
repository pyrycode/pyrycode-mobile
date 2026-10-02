# #1472 — Split `Test scheduling and harnesses` out of development-verification.md

## Files read

- `docs/knowledge/features/development-verification.md` — 50206 bytes on `main` at `0991516f`; the `## Test scheduling and harnesses` section is the move.
- `docs/knowledge/features/channel-list-viewmodel-testing.md` — the split-child shape to mirror (intro paragraph, heading kept verbatim).
- `docs/knowledge/CATALOG.md` — the `Development verification` entry under "Shared topics added by the knowledge migration".
- `scripts/docs-guard.sh` — the 50000-byte cap that fails on `main`.

`main` checked first: `origin/feature/1354` has not merged, and `scripts/docs-guard.sh` exits 1 on `main`, so the split is still needed.

## Change

Move the `## Test scheduling and harnesses` section verbatim into a new child,
`docs/knowledge/features/development-verification-test-scheduling.md`, with an intro paragraph in the
`channel-list-viewmodel-testing.md` shape. The section's links are sibling-relative
(`push-messaging-service.md#testing`, `app-preferences.md#testing`), so they resolve unchanged from the
child. In the parent, keep the `## Test scheduling and harnesses` heading with a short paragraph linking to
the child, so the seven inbound `development-verification.md#test-scheduling-and-harnesses` links still land.
Add a child line under the `Development verification` entry in `CATALOG.md`. No Kotlin changes; the
documentation-only ticket explicitly assigns these `docs/knowledge/` edits to this stage.

## Testing strategy

`scripts/docs-guard.sh` exits 0, both files under 50000 bytes, and a grep confirms every
`development-verification.md#<anchor>` in `docs/` and `CLAUDE.md` names a `##` heading still in the parent.
