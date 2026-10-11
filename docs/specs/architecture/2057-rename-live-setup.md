# Locate the live rename scenario’s host control (#2057)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_renameConversation_relabelsTopBarAndListRow`, `awaitChannelList`, `awaitConnected`, `createChat`, and `awaitHostChatAddControl` establish the pre-rename timeout and composed-node-only wait.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`: `ConversationTree` and `treeHost` put Channels before Chats in a lazy list and omit pluses for disconnected hosts.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `HostConversationSnapshot` supplies target-host readiness and row counts without network requests.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ArchiveRoundTripChat.kt`: `awaitArchiveRoundTripChat` demonstrates bounded lazy-list scrolling and selective handling of missing-node assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/EmptyHostChannelSetupTest.kt`: connected host fixtures and real screen rendering.
- `docs/knowledge/features/channel-list-screen.md` and `channel-list-screen-tree-and-controls.md`: an offscreen lazy row cannot establish absence; the arrival marker does not establish readiness.
- `docs/knowledge/features/development-verification-test-scheduling.md`: capture evidence before cleanup; launcher focus afterwards cannot establish a lifecycle cause.
- `docs/e2e-interactive-stream.md`: preserve the real daemon create/rename round-trip and two title surfaces.

## Context

The retained original stderr in `/work/Projects/pyrycode-mobile-agents/logs/2026-10-10T14-06-03-204Z_real-claude-gate_#1896.stderr.log` fails in `awaitHostChatAddControl` before rename. Original counts are 65 executed, 19 failed, 0 skipped; the failed-method rerun has 19 executed, 4 failed, 0 skipped and rename passes. The original worktree’s per-test logcats are no longer present. These artifacts cannot distinguish target-host disconnection from an offscreen control.

The code has a concrete setup defect: a composed-node-only wait cannot locate Chats below a long Channels section. Reproduce that condition on the real list screen with a connected, loaded host and retained before/after evidence. Do not claim historical cleanup focus or aggregate daemon logs prove the original trigger. If this condition does not reproduce, revise the design before repairing anything. No decision record is needed.

## Design

Add a test-only `awaitRenameChatAddControl` extension under shared tests, used only by the rename scenario. Wait for the harness’s own host to be connected and loaded and the list’s scroll container to exist, then locate its tagged Chats plus through `performScrollToNode`. Only a missing-node assertion from a pending projection remains in the existing bounded wait; unrelated assertions propagate. Return a displayed node; create exactly once through its UI click.

Emit bounded setup observations at entry, readiness, successful location and failure before cleanup: fixed stage names, numeric timestamp, target-host presence/readiness booleans, capped channel/chat counts and whether the target plus is composed. No host IDs, labels, titles, payloads, credentials, arbitrary errors or semantics dumps enter evidence. Failure retains the original cause. Observations are snapshots, not atomic proof of ordering. The successful scroll exposes the offscreen control without retrying creation, skipping a missing control, changing production or increasing deadlines.

Keep runtime-unique naming, absence before submission, real dialog submit, top-bar title after dialog closure and the same title on its list row. Use the existing scrolling row locator for the final list assertion so it remains valid with a long tree.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879 and #1896 edit other methods in the live class. Inspected hunks do not restructure this scenario or its helpers. Keep this edit local.

Sizing: one deliverable (reliable rename setup), approximately 450 written lines including plan, evidence and regressions; zero production edits, two new test-only declarations, one existing consumer migration, three acceptance criteria and fewer than ten wait/error branches. Both sketch and written plan meet the limits.

## State and concurrency model

No new jobs, flows or persistent state. The instrumentation/test thread owns the bounded wait and snapshots. Read the existing host source only; no new network probes. Existing Compose rule and app lifecycle own cleanup.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Connected, loaded target has offscreen Chats after many Channels | `connectedTargetBelowChannelsIsLocatedAndClickedOnce` |
| Target becomes ready later while another host is already connected | `anotherConnectedHostCannotSatisfyTargetReadiness` |
| Target is disconnected or absent | `unavailableTargetFailsBeforeCreationWithBoundedEvidence` |
| Later projection connects the target | `anotherConnectedHostCannotSatisfyTargetReadiness` |
| Connected target's list has not loaded, despite a composed control | `composedControlDoesNotEstablishLoadedTargetReadiness` |
| Source is ready before the screen projection draws the control | `pendingScreenProjectionKeepsMissingControlWithinBoundedWait` |
| Cancellation or a diagnostic read fails | `cancellationPassesThrough`, `failureEvidenceCannotReplaceOriginalFailure` |
| Fresh invocation of the locator | Every screen regression constructs a fresh rule/fixture; helper retains no state |

## Error handling

Keep the existing list deadline. Missing target readiness remains a timeout with content-free setup evidence, never a skip. Missing lazy-list nodes may reflect a pending projection; only that known assertion is tolerated. Diagnostic reads must not replace the original failure. Cancellation passes through. No creation retry is added.

## Testing strategy

First add the real-screen regression and a helper with the former semantics-only algorithm; observe the connected/loaded offscreen case fail, capturing observations before rule teardown. Then implement scrolling and run all setup cases green, plus existing lazy archive and empty-host setup coverage. Retain red/green reports under test resources after sanitizing them to content-free failure summaries. The live method remains device-only because it needs a real daemon/relay. Fresh full live acceptance belongs to the dispatcher: it must retain tested commit, the rename method executed/passed and executed/failed/skipped counts. Historical rerun and compilation do not satisfy it.

Run focused shared tests, lint, assembleDebug, androidTest compilation, Spotless apply/forced check and final main-merge assemble/pre-verify. No visual change is requested.

## Open Questions

The historical run lacks per-test setup state; the controlled regression can prove the helper defect but cannot retroactively prove that specific run’s host state. Fresh live stage observations must distinguish any continuing readiness failure from lazy composition. Record controlled evidence and its limits on the ticket.

## Revisions

2026-10-11: resumed the saved plan and failing-test scaffold after the previous builder exited. The controlled connected/loaded 30-channel fixture failed with the former locator: 1 executed, 1 failed, 0 skipped, with `plus_composed=false` retained before teardown. The readiness regression also failed against the former locator because it clicked a composed control before `rowsLoaded`. Scrolling and target-specific readiness then passed all 8 setup regressions and 6 existing archive/empty-host regressions. Sanitized evidence is retained in `app/src/test/resources/rename-setup-2057/red.txt` and `green.txt`; green shows `plus_composed=false` at readiness and `true` after location, followed by one creation event.

Failure observations also report whether the list scroll container is composed, using a boolean. This distinguishes unavailable destination composition from absent host readiness without dumping semantics. The top-bar assertion now explicitly requires dialog closure before accepting its title, preserving the real round-trip proof. Count caps and omission of host content are covered by `observationsCapCountsAndOmitHostContent`. No production, wire or shared live setup changes were needed. Current overlap diffs still affect other methods only.

The historical trigger remains unproven because its per-test setup state was not retained. The controlled evidence establishes a concrete helper defect and its correction; fresh live observations will distinguish any continuing target-readiness failure. Full dispatcher live acceptance remains pending, including this method executed/passed, tested commit and suite executed/failed/skipped counts.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, rename scenario and Verification status: document the target-specific readiness/scrolling setup, link controlled evidence and the eventual dispatcher full-live result, and preserve the distinction between reproduced helper defect and unknown historical trigger.
