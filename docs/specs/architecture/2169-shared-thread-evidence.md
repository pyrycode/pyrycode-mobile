# Shared thread evidence (#2169)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: `firstOnLive` owns replacement and cancellation.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SettledAssistantReply.kt`: `holdsSettledAssistantReply` preserves contiguous deltas and exact lengths.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/FinishedSelectionReply.kt`: `awaitFinishedSelectionReply` accepts loaded main replies, including ordinary history messages.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/ReplySuggestionE2EAssertions.kt`: `assertReplySuggestionLongPress` requires one exact user echo and a later revisioned clear.
- `app/src/test/java/de/pyryco/mobile/e2e/FinishedSelectionReplyTest.kt`, `SettledAssistantReplyTest.kt`, `LiveConnectionReadsTest.kt`: existing compatibility probes.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: `AuthoritativeThreadSnapshot` carries admitted items and epoch/generation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread` supplies rendered identities and agent blocks.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `foldToolRuns` supplies run identity.
- `docs/knowledge/features/thread-screen.md`, `remote-conversation-repository-assistant-reply-segments.md`: receipt is not delivery; rendered aliases are not wire identities.
- `docs/specs/architecture/1029-reconnect-slash-commands-follow-live-connection.md`: replacing a connection must cancel its observation.
- `docs/protocol-mobile.md` in the sibling pyrycode repository: Daemon thread updates, Thread query messages, Session-scoped live state and Queue (v2) remain the wire contract.

## Context

Migration siblings need common evidence without activating either app route. This is one test-infrastructure deliverable, estimated at 650–850 written lines, five shared types, two observation consumers, four acceptance criteria and no production edits. No signature changes or consumer migration. #1879 overlaps the suggestion helper with local progress instrumentation; this adapter change is independent and local.

## Design

Add `ThreadEvidence.kt` under sharedTest. An explicit source kind, ownership record, per-item record, evidence snapshot and source binding are the five shared types. Ownership includes host (nullable only for existing repository-only signatures that do not report it), conversation, reference-identity connection token and authoritative epoch/generation. Explicit fixtures always supply a host; absent legacy attribution stays absent.

Legacy adapters retain original Message objects, session, segment turn and parent identity. Their strict settlement delegates to `holdsSettledAssistantReply` after session filtering. Loaded selection remains separate: ordinary nonblank, nonstreaming main history replies still qualify without fabricated deltas.

Authoritative adapters consume only admitted `AuthoritativeThreadSnapshot` facts. Deduplicate revisions like the production projector and join item identities to actual projected rows, using the projector's scope and item ID; derive agent block and tool-run IDs from `projectAuthoritativeThread` and `foldToolRuns`. Keep full daemon text separately from bounded rendered text. Strict settlement requires the requested session, turn and projected agent family, a shown ordered assistant item, nonblank full text, inactive state and its own `done`/`finished` status. A running parent does not prevent settlement. Repair/reset or missing epoch fails evidence admission. Receipt, pending sends and query checkpoints cannot settle an assistant item.

One `harnessThreadEvidence` binding function selects legacy observation today. A later activation changes that function to follow negotiation; consumers need no further migration. Explicit authoritative source factories support fixtures now and never open queries, advertise capabilities or change production bindings. Owner comparisons require connection reference identity plus host/conversation/epoch/generation equality.

Adapt only `awaitFinishedSelectionReply` and the suggestion helper's echo observation; preserve their signatures, loaded selection semantics, exact single delivered user echo and revisioned-clear observation.

## State and concurrency model

Cold mapping flows run in the caller's scope, with no jobs, dispatchers or global mutable binding. `firstOnLive` continues to switch connection collectors and reject retired sources. Explicit source bindings can themselves be held in a StateFlow and read with the same helper. Retained evidence must be checked against the current ownership record before strict settlement.

## State transitions and identity reuse

| Event | Probe |
| --- | --- |
| Disconnect/replacement under reused item IDs | `replacementCancelsOldEvidenceAndAcceptsOnlyCurrentSource` |
| Outer cancellation | `cancellationClosesEvidenceObservation` |
| Host/conversation collision, epoch or generation replacement | `ownershipRejectsReusedIdentities` |
| Partial to terminal assistant revision with parent still running | `authoritativeSettlementUsesTheAssistantItemsOwnCompletion` |
| Tool IDs collide across hosts or within an agent family | `authoritativeIdentitiesMatchProjectionAndToolRuns` |
| Repeated exact user echo | `userEchoCountRequiresExactlyOneDeliveredUser` |

## Error handling

Incomplete or mismatched evidence fails predicates and waits within existing caller timeouts. Flow exceptions and cancellation propagate unchanged. Unknown attribution is never inferred from an ID spelling, empty data or cache.

## Testing strategy

Write JVM `ThreadEvidenceTest` first, observe compilation failure for missing adapters, then implement. Cover both source factories, full-length/delta gaps, requested family, hidden/queued/blank/active items, retired ownership, highest revision, full versus bounded text and projection IDs. Run existing helper tests unchanged. Compile both test source sets. The androidTest change is an assertion helper, not a new device-only test; run the focused scripted reply-suggestion scenario if available, with live execution left to the dispatcher's gate. Run lint, assembleDebug, formatting and final pre-verify after merging main.

## Open Questions

None.

## Revisions

- 2026-10-15: `authoritativeSettlementUsesTheAssistantItemsOwnCompletion` exposed that an orphan child can retain a projected row without an agent block. A positive parent now requires a resolved block before settlement; it cannot qualify as main-thread evidence.
- 2026-10-15: `fullReplyTextSurvivesDisplayBounding` exposed a complete item whose bounded presentation is blank. Loaded selection now requires nonblank rendered text as well as full text, while strict item settlement retains the complete daemon text.
