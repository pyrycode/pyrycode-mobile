# Delete-conversation live synchronization (#1888)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_deleteConversation_removesFromListAndClosesThread`, `awaitChannelList`, `hostRepository`; the live drive and its arrival guards.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `DeleteConfirmationDialog`, `ThreadScreen`; distinct Android dialog windows and the header menu host.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `onOverflowEvent`; Delete closes Channel info, confirmation invokes the repository before PopBack.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: `PyryNavHost`; destination-scoped event collection and navigation.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenChannelInfoTest.kt`: existing dialog and action routing assertions.
- `docs/knowledge/features/thread-screen.md`, `thread-screen-how-it-works-sheets.md`, `channel-info-sheet.md`, `thread-overflow-menu-wiring-tests-and-edge-cases.md`: current sheet/dialog lifecycle and scrolling requirements; historical sheet-behind-dialog commentary is stale against production.
- `docs/knowledge/features/channel-list-screen.md`: the list marker indicates composition, whereas rows are lazy and arrival is weaker than readiness.
- `docs/knowledge/features/development-verification.md`, `development-verification-gates.md`, `docs/e2e-interactive-stream.md`: shared regression placement and real-daemon harness.

## Context

Two stages failed on different tested trees: #1878 reports `awaitChannelList` after confirmation on feature head `9b55c94b10` with main `6efbbaffc1`; #1854 reports the Channel info item wait on feature head `3e059504f9` with main `970d7c422f`. Matching dispatcher stderr preserves the timeout stacks. The raw device artifacts in those removed gate worktrees are unavailable. Retained daemon logs alone cannot establish whether the correct UI action was delivered. A fresh diagnostic must precede a repair; no common cause is assumed.

This is one deliverable: stabilize the existing live deletion contract. Forecast: approximately 550 written lines including diagnostics, regression proof, repair and this plan; no new production types, no signature migration, four acceptance criteria. No decision record is needed.

In-flight changes in #1682, #1689, #1690, #1691, #1693, #1695, #1731, #1766, #1833, #1867, #1870 and #1879 share the live class but change other scenarios; keep this change local to deletion and its own helpers. #1870's off-screen archive presence defect does not establish either deletion failure's cause.

## Design

First instrument the deletion drive with content-free stage records and failure-only state: composition of list, composer, menu item and confirmation; owning-window focus/readiness where observable; and the uniquely named conversation's existence in the host repository. Preserve and rethrow the original exception. Capture a fresh diagnostic run if retained historical evidence remains insufficient.

Use that evidence to choose the smallest local synchronization or isolation repair, recorded under Revisions before implementation. Prefer a test-only helper shared with a deterministic regression that mounts the real affected composables. Do not change production behavior or selectors speculatively. The regression must force the diagnosed timing/state, fail on the old drive and pass with the repaired helper. A product defect requiring production changes will be documented here before repair; a sibling-repository defect will instead become a registered blocker.

The scenario continues to create and uniquely rename a discussion, observe it displayed on the active list, re-enter its thread, choose Channel info and Delete, confirm in the actual dialog, and independently prove return to the list and absence of the discussion. Keep the existing live selector and reporting. No retries, ignored assertions, longer sleeps or unrelated shared-harness changes.

## State and concurrency model

No new product state or jobs. Compose arrival and Android window/input readiness are distinct candidates to measure, as is completion of the asynchronous repository mutation. Test diagnostics read snapshots without mutating product state. Repository reads remain bounded; diagnostic failure must not replace the original failure.

## Error handling

Every scenario exception remains a failing test. Diagnostics use static stage codes and booleans/counts/bounds, never pairing payloads, credentials, daemon text or conversation names. Any failed diagnostic record is explicitly identified while preserving the scenario exception.

## Testing strategy

- Run a fresh diagnostic through the isolated live harness as needed to establish both stages; no passing diagnostic run substitutes for root-cause evidence.
- Write and execute a failing focused regression first, using production UI and the same repaired helper. Place Robolectric-capable coverage under `app/src/sharedTest`; use a focused managed-device test only if real Android window/input behavior is essential.
- Run existing Channel info/dialog and overflow coverage relevant to the repair, and compile androidTest after live/shared test changes.
- Run lint, assembleDebug, Spotless and the final whole unit/shared suite plus `scripts/pre-verify.py --gradle` after the last main merge.
- Dispatcher owns the fresh passing full post-verifier live gate: revision, artifact directory, executed/failed/skipped counts and explicit deletion-method pass remain pending at builder handoff.

## Open Questions

- What observable state connects each failed stage to a missed action, repository failure or navigation failure? Resolve with retained or fresh evidence, not the same-tree rerun alone.
- Can both failure conditions be forced under Robolectric, or does the regression require real Android input/window ownership?
