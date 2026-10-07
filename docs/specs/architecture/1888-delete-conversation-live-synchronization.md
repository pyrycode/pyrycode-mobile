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

## Revisions

### 2026-10-07 — tested merge and connection-gap replay

The retained stderr identifies the actual #1854 test revision as `f77f772c8d56251c3e5da12c1c125d887d2ed872`, a merge of `3e059504f9` and `970d7c422f`. Its stack maps to the first uniquely renamed list-presence wait. The branch-head-only Channel info attribution in the issue and initial Context is incorrect: main added `interactiveTurn_replySuggestion_longPressSends` before this method. #1878 actually tested `9b55c94b107cc263443126396f842a9e72da5b1f` and still maps to post-confirmation `awaitChannelList`.

Retained daemon logs connect both targets to a tunnel teardown before the missing mutation: #1854's created conversation has no successful rename; #1878's renamed and re-opened conversation has no delete. In each case the create/read/mutation and teardown use the same hashed connection identity, followed by a new handshake. `StableConversationRepository` rejects one-shot mutations without a live delegate; `launchGuardedRepoCall` swallows that rejection. The scenario waits for connection only at entry, and its legacy `Connected` signal can also mean intentional idle.

Fresh unmodified-drive diagnostics at `5de4f4459` ran 64 live methods, 62 passed, two unrelated failures (#1870 archive presence and a daemon parser-gap sentinel); deletion passed with focused windows and a successful repository deletion. This does not establish a window/input defect. Replace the planned timing loop with a bounded replay of the observed connection gap before Save, then before Delete confirmation. Capture and rethrow the original failure. If that reproduces the missing mutation, use a test-only coroutine guard over the owning host's pump-gated `currentRepository`, immediately before each one-shot UI submit, and wait for the confirmed rename before Back.

Regression coverage will call the same readiness guard with a real `StableConversationRepository` over a controllable repository flow. It must hold Rename/Delete during a gap, deliver each once after a new delegate arrives, distinguish the target host from another ready host, and preserve timeout/cancellation. This is pure coroutine logic under JVM tests, with its reusable helper under sharedTest so the live test uses the same implementation. No production or visual change is planned.

### 2026-10-07 — repair contract and deterministic proof

The original drive reproduced both diagnosed conditions with one action submission each: Save-gap revision `f4ff81a73` failed first list presence (1 executed, 1 failed, 0 skipped; `build/dispatcher-tests/live-qnfag9ra`), and confirmation-gap revision `80d5e1b5a` closed the dialog but retained the discussion/thread (1 executed, 1 failed, 0 skipped; `build/dispatcher-tests/live-9b_iwrpu`). Content-free raw XML/logcat excerpts and historical source/log provenance are retained in `app/src/androidTest/assets/deletion-1888/`.

`awaitDeletionMutationReady` suspends on the owning host's `RelayRepositoryCoordinator.currentRepository` until its pump-gated delegate exists. The live drive bounds each wait with its existing thread timeout, immediately before Save and Delete confirmation. It then waits for the authoritative renamed thread header before Back, avoiding cancellation of a pending rename by navigation. No production code, selector, retry, or scenario sleep changes. Temporary reconnect injection and window diagnostics are removed from the final drive after verification.

`DeletionMutationReadinessTest` drives the real stable repository over controllable delegates: rename/delete each wait through a gap and submit once to the new delegate; another ready host cannot release the owner wait; a missing host retains the caller's timeout; cancellation prevents a later submit; an already ready host submits immediately once. The pre-repair immediate availability check produced 5 executed, 4 failed, 0 skipped; the repaired guard plus the additional host-isolation case produced 6 executed, 0 failed, 0 skipped. Existing Channel info and overflow tests add 27 passed, giving 33 focused tests with no failures/skips.

Both Open Questions are resolved: repository unavailability, rather than a measured window/input problem, connects the missing mutation to each stage; its deterministic regression is pure JVM coroutine coverage. The existing real-device scenario remains necessary for the real relay/daemon, background connection driver and Android navigation/dialog interaction. The diagnostic full live suite had deletion passed but two unrelated failures: mobile #1870 and daemon `pyrycode#2939`, filed separately. The required fresh passing full gate stays pending for the dispatcher after verification.

### 2026-10-07 — guarded real-device proof

Revision `e2525a93376c34ab69afce0395057dfd30b856ae` forced both connection gaps in one live deletion drive and passed: 1 executed, 1 passed, 0 failed, 0 skipped; raw evidence `build/dispatcher-tests/live-yln6f7vx`. The same actions were submitted once after each guard, with list presence and both final postconditions intact. Sanitized passing XML/logcat provenance is retained beside the negative controls. Temporary gap injection, its coroutine scope and failure window probe are removed; no production changes were necessary. Final written work remains below the 550-line estimate and every sizing boundary.
