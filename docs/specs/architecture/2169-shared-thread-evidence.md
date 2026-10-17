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
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocks.kt`: `foldBackgroundAgentBlocks` supplies legacy rendered Agent ownership before tool-run folding, including aliased roots and attributed prose.
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
| Unsupported item or `turn_end` alongside a complete assistant item | `unsupportedAuthoritativeKindsCannotSettleReplies`, `turnEndAndUnknownItemsDoNotJoinAnAuthoritativeAssistantFamily` |
| Legacy background block with intervening prose and collision aliases | `legacyBackgroundIdentitiesFollowPresentationAcrossProseAndAliases`, `legacySettlementKeepsRawParentAttributionForRenderedAliases` |
| Authoritative main parent absent or explicitly cleared to zero, versus resolved or unresolved positive parents | `authoritativeLoadedMainReplyAcceptsAbsentAndClearedParentsButRejectsAgentChildren` |

## Error handling

Incomplete or mismatched evidence fails predicates and waits within existing caller timeouts. Flow exceptions and cancellation propagate unchanged. Unknown attribution is never inferred from an ID spelling, empty data or cache.

## Testing strategy

Write JVM `ThreadEvidenceTest` first, observe compilation failure for missing adapters, then implement. Cover both source factories, full-length/delta gaps, requested family, hidden/queued/blank/active items, retired ownership, highest revision, full versus bounded text and projection IDs. Run existing helper tests unchanged. Compile both test source sets. The androidTest change is an assertion helper, not a new device-only test; run the focused scripted reply-suggestion scenario if available, with live execution left to the dispatcher's gate. Run lint, assembleDebug, formatting and final pre-verify after merging main.

## Open Questions

None.

## Documentation handoff

- Pending for the documentation stage: `docs/knowledge/features/development-verification-gates.md`, “Live Claude reply assertions”: document evidence factories, ownership checks, loaded selection versus strict settlement, rendered identities versus raw attribution, and the legacy-only harness binding/future activation seam. Carry forward the orphan-parent and bounded-rendered-text lessons, and distinguish a daemon parent cleared to zero from its nonempty scoped presentation identity.

## Revisions

- 2026-10-15: `authoritativeSettlementUsesTheAssistantItemsOwnCompletion` exposed that an orphan child can retain a projected row without an agent block. A positive parent now requires a resolved block before settlement; it cannot qualify as main-thread evidence.
- 2026-10-15: `fullReplyTextSurvivesDisplayBounding` exposed a complete item whose bounded presentation is blank. Loaded selection now requires nonblank rendered text as well as full text, while strict item settlement retains the complete daemon text.
- 2026-10-15 (verifier finding 1): Only daemon `assistant_message` facts join authoritative settlement families. Unknown-kind fallback rows cannot settle replies, and `turn_end` facts cannot prevent an admitted assistant item from settling.
- 2026-10-15 (verifier finding 2): Legacy rendered Agent and tool-run identities come from `foldQueuedRows`, `foldBackgroundAgentBlocks` and `foldToolRuns`, with loaded lifecycle evidence. Original messages retain raw parent/segment attribution; legacy strict settlement continues to select by raw parent and delegate to `holdsSettledAssistantReply`. A missing rendered block never establishes main-thread attribution for loaded selection. Four regression probes add about 120 lines; the complete ticket remains below 850 written lines, five shared types and two observation consumers. No in-flight branch overlaps either evidence file.
- 2026-10-15 (native validation return): Managed task `ffa7e4529efbdc290d7728ab17d66643a868f51431b55679926dd690aa21cc45` for repair candidate `33c652a94` failed with `Native execution failed: RuntimeError. Private logs retained.` It returned no artifacts or test counts. The content-free result is retained in `app/src/androidTest/assets/thread-evidence-2169/native-check.txt`; this is unverified native execution, not a test pass. No implementation change is indicated by the returned evidence.
- 2026-10-16 (completed native validation return): Managed task `0f91c9feeeb8f212148c7cb305c261925eb4cb8396827bdab255b630bbbcb6f1` ran the focused scripted `reply-suggestion` and `selection-copy` methods on Linux/Android at pushed candidate `09e1a604f`; each executed and passed one test with zero skips and exit 0. The six returned artifact hashes, counts and supporting revisions were verified; private raw evidence is preserved under `/work/Projects/pyrycode-mobile-agents/logs/evidence/2169/builder-native-0f91c9-20261016/`, with a content-free summary in `native-check.txt`. The earlier failed attempt remains failed. No implementation or recording change is needed; this test-only ticket requires no new real-Claude proof. Full dispatcher gates and the documentation handoff remain later-stage work.
- 2026-10-17 (verifier finding 1 on `14de75414`): Loaded main-reply selection checks raw `Message.parentToolUseId` only for legacy evidence. Authoritative selection uses the daemon parent (absent or zero) together with no projected Agent block; explicit zero clears ownership even though its projected parent identity is nonempty. The regression named above preserves rejection of unresolved positive parents and resolved Agent children, with all existing admission, text and completion checks retained. Pending documentation handoff also covers this distinction between cleared daemon ownership and scoped presentation identity.
- 2026-10-17 (native rework return): Task `434c643e69ff0186e7a61051af900cd42d092ccea5bc3d6a29802a0b580e2bde` for pushed repair `5472a815f` failed before APK building or device execution because the isolated launcher omitted `AGENTS_REPO_PATH`; the empty XML proves no passing tests. This matches open [dispatcher #294](https://github.com/pyrycode/agent-dispatcher/issues/294). All four returned artifacts were hash-verified and retained at `/work/Projects/pyrycode-mobile-agents/logs/evidence/2169/builder-native-434c643e-20261017/`, with a content-free update in `native-check.txt`. No Mobile code change is indicated. Fresh focused `reply-suggestion` and `selection-copy` evidence remains pending that launcher repair; earlier failed attempts remain failed and the previous counted pass remains historical.
- 2026-10-17 (native return after the launcher repair): Task `1d6ed25136523c7eca559348818315688ab62fb71e8b369883f46e8bede10576` at `ac5ab7cba`, which contains repair `5472a815f`, passed `reply-suggestion` (1 executed/passed, zero failed/skipped, exit 0). `selection-copy` exited 1 before any device test because the host's shared Gradle journal cache lock was held by another process; it produced zero counts and remains unverified. Artifacts are hash-verified at `/work/Projects/pyrycode-mobile-agents/logs/evidence/2169/builder-native-1d6ed2-20261017/` and summarised in `native-check.txt`. No Mobile code change is indicated.
