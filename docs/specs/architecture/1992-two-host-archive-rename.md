# #1992 — Synchronize the two-host Archive scenario's discussion rename

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_twoHostsArchive_staysPerHost`, `renameOpenThread`, `createChatOn` and cleanup helpers; preserve the real archive/restore and host-isolation assertions.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RenameDialog.kt`: the Name field has its own content description; focus arrives from a dialog-local effect.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `Rename` opens the dialog through asynchronously combined state; `RenameSubmit` dismisses it and launches the repository write.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationCommands.kt`: `rename` confirms the daemon reply before updating the title projection.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ArchiveRoundTripChatTest.kt`: precedent for a shared helper and deterministic fixture that drive the same live-test operation.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: historical focus records after Activity destruction cannot establish focus during the failed action; preserve partial-setup cleanup.
- `docs/knowledge/features/rename-dialog.md`, `thread-screen.md`, `remote-conversation-repository.md`, and `archived-discussions-screen.md`: dialog focus, confirmed rename, host-owned Archive and restore-destination lifetime.
- Retained #1968 gate stderr/XML and isolated `pyry-e2e.6txisD/daemon.log`: original title wait timed out; original phone logcat is unavailable.

## Design source

N/A — test synchronization and diagnostics only; no product UI change.

## Context

At tested mobile revision `730064e531` (main `5d339ab241`), host A's initial rename timed out at the final new-name observation, before archive checks. Original gate: 65 executed, 3 failed, 0 errors/skipped; same-tree rerun: 3 executed, 2 failed, this method passed. These establish the failing stage, not causality. The helper's generic focused editable selector can match the underlying composer before the Rename dialog becomes visible. Reproduce that condition deterministically before accepting the repair; historical attribution remains an inference without the original phone logcat. No decision record is needed.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1725, #1766, #1879, #1888, #1900 and #1973 edit other scenarios in the live class; none changes this helper or scenario. Keep the change local and additive.

## Design

Introduce a shared test-only `ComposeTestRule` discussion-rename driver used only by this scenario. Wait for the Rename dialog's labelled editable field, replace its text, submit its enabled Save, wait for dialog disappearance, then require the renamed title outside the dialog. A field value alone must never satisfy rename completion. Keep the existing helper and its other callers unchanged to bound this ticket's scope.

Add fixed stage labels and content-free diagnostics to this operation. A timeout retains its original cause and deadline, identifying whether field readiness, Save readiness, dialog dismissal or title observation failed. Live diagnostics report only owning-host connection/repository presence and booleans for confirmed rename and composer contamination, never titles, pairing material or payloads. Diagnostic collection must not replace the original error.

## State and concurrency model

No product state or jobs change. Tests run synchronous Compose operations; the fixture holds dialog appearance while the existing composer remains focused, then releases it through the Compose clock. The live write remains owned by the thread ViewModel and its host's repository.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Dialog opens after a focused composer is already available | `DiscussionRenameTest.delayedDialogDoesNotReplaceComposer` |
| Save leaves a matching field visible before confirmed title | `DiscussionRenameTest.matchingFieldDoesNotProveRename` |
| Reopen for a second rename | `DiscussionRenameTest.reopenedDialogSubmitsEachNameOnce` |
| Timeout diagnostics themselves fail | `DiscussionRenameTest.diagnosticFailurePreservesOriginalTimeout` |
| Partial setup, archive, restore and B isolation | Existing live `interactiveTurn_twoHostsArchive_staysPerHost`, with unchanged cleanup and assertions |

## Error handling

Missing dialog, disabled Save, failed dismissal or absent confirmed title fails at the original 30-second bound. No retries, ignores, timeout increases or removed assertions. Existing guaranteed cleanup removes created conversations and B's pairing even after partial setup. No wire or sibling-repository change is planned.

## Testing strategy

First execute the delayed-dialog regression against the old focused-field drive and read the failing XML. Then repair the shared test driver and run its full Robolectric class plus existing `RenameDialogTest`. Run the affected live method through the isolated live gate and inspect fresh counted XML/logcat. The existing live method remains device-only because it needs real daemon/socket/background work and pairing storage; new deterministic tests belong in `sharedTest`. Run lint, assembleDebug, androidTest compilation and forced Spotless; after the last main merge run assembleDebug and `scripts/pre-verify.py --gradle`.

Dispatcher handoff: after verification run a fresh full live gate, explicitly confirm this method passed, and retain mobile/daemon revisions, artifact location and executed/failed/error/skipped counts. A focused pass or same-tree retry does not fulfill that acceptance criterion.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, “Live mode (rung 3: live relay)” and “Verification status”, with established cause, historical attribution limits, unchanged two-host archive contract, and revision-linked counted deterministic/focused/full-live evidence supplied by builder and dispatcher.

## Open Questions

- Does delayed dialog appearance reproduce composer replacement with the old selector? Resolve with red/green counted regression evidence; revise the design if it does not.
- Does the fresh live baseline reveal another failure layer? Keep historical and fresh findings separate.
