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

## Revisions

### 2026-10-02 — superseded by #1354 on `main`

PR #1470 (#1354) merged into `main` at `8cfd7c8e` after this plan was written. It split
`development-verification.md` four ways, into `development-verification-gates.md`,
`-compose-evidence.md`, `-test-scheduling.md` and `-emulator-evidence.md`, and reduced the parent to a map
that links to each child. The child this plan creates is one of the four, under the same name. Merging
`main` into `feature/1472` took `main`'s version of all three docs files, so this branch's diff against
`main` is now this plan alone.

New contract: the ticket's "check `main` first" branch applies, and this ticket makes no docs change.
`scripts/docs-guard.sh` exits 0 on `origin/main` (parent 1208 bytes, test-scheduling child 14487 bytes).
The parent's map links to `development-verification-test-scheduling.md`.

One inbound anchor no longer resolves in the parent: `CLAUDE.md`'s link to
`development-verification.md#where-a-screen-test-goes`. That heading now lives in
`development-verification-gates.md`. `CLAUDE.md` is outside the builder's writable files, so the anchor is
handed off rather than fixed here. #1354 repointed the inbound `#test-scheduling-and-harnesses` links under
`docs/` to the children, and a grep finds no other `development-verification.md#…` link in `docs/`.
