# Streaming and reopen thread evidence (#2171)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_directShareShortcut_stagesBeforeExplicitSend` (one `observeMessages` site), `interactiveTurn_seededChannel_reopenOngoingReplyShowsArrivedPrefixImmediately` with its local `assistantRows`/`assertOpenReply` (four sites) and `interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread` (four sites). The rendered, 128 ms reveal, turn-phase and side-message checks stay as they are.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt` (#2169): `harnessThreadEvidence` is the single binding; `ThreadEvidence.settledReply` delegates legacy settlement to `holdsSettledAssistantReply` and requires the authoritative item's own inactive `done`/`finished` facts; `userEchoCount` counts delivered exact echoes; `ThreadEvidenceOwner.matches` compares connection reference, host, conversation, epoch and generation. `admitted` is private.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SettledAssistantReply.kt`: `holdsSettledAssistantReply` is the legacy delta sequence and length check.
- `app/src/test/java/de/pyryco/mobile/e2e/ThreadEvidenceTest.kt`: the `item`/`snapshot`/`evidence` fixture shape the new probes mirror.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread` scopes row identities by host, conversation, epoch and generation, and sets `isStreaming` from an assistant fact's `active`.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: legacy streaming rows carry `AssistantSegment` deltas keyed by turn; the reply keeps its segment-key id through completion.
- `scripts/e2e-emulator.sh`: scenarios `direct-share`, `reopen-stream` and `stream` select the three methods; `scripts/e2e-fixtures/stream.jsonl` and `stream-end.jsonl` give three assistant deltas.
- `docs/knowledge/features/development-verification-gates.md`, "Live Claude reply assertions": receipt is not settlement; rendered identities are not wire identities.

## Context

#2169 landed shared evidence and migrated two consumers. These three scripted methods still read `ConversationRepository.observeMessages` directly at nine sites, so they would prove nothing on daemon-built threads. This ticket moves them onto `harnessThreadEvidence`, which still selects the legacy source; no production route, capability or fixture changes. One deliverable; about 450 written lines, no new types, nine consumer sites, four acceptance criteria.

Overlaps: #1766, #2091 and #2104 edit other methods and the rule setup in the same test file; edits here stay local to the three methods.

## Design

Add `app/src/sharedTest/java/de/pyryco/mobile/e2e/StreamingReplyEvidence.kt` with predicates over `ThreadEvidence`, so the device test and JVM probes run the same assertions. Make `ThreadEvidence.admitted` readable (drop `private`); no other change to `ThreadEvidence.kt`.

- `ThreadEvidenceRecord.isOpen`: legacy `message.isStreaming`; authoritative `fact.active` or a status other than `done`/`finished`.
- `ThreadEvidence.holdsNoUserMessage()`: admitted and no user record of any status. Unadmitted evidence is never an empty thread.
- `ThreadEvidence.ownedUserEcho(owner, text)`: the one user record, when evidence is admitted, owned by `owner`, has exactly one delivered exact echo of `text` and no other user record.
- `ThreadEvidence.assistantRecords()`: admitted assistant records in thread order.
- `ThreadEvidence.openReply(owner)`: the single assistant record, still open, with nonblank full text, from `owner`.
- `ThreadEvidenceRecord.advancesFrom(earlier)`: same rendered identity and daemon item ID, full text strictly longer and starting with the earlier text, and for authoritative facts a strictly higher revision. Legacy has no revision; its sequence proof stays in settlement. No delta is ever derived for authoritative input.
- `ThreadEvidence.settledFormOf(owner, item)`: the record with `item`'s identity, when `settledReply(owner, its session, its turn)` holds a family containing it. Legacy keeps the contiguous delta and length checks; authoritative requires the item's own completion facts.

Consumer mapping, all through one `harnessThreadEvidence(repository)` per method, with the owner taken from the first observation:

- Direct Share: before the tap, `holdsNoUserMessage`; after the rendered reply, wait (existing reply deadline) for `ownedUserEcho(owner, SEND_PROMPT)`, then assert the same evidence still holds exactly one user record. The rendered staging and reply checks are unchanged.
- Reopen: `assistantRows`/`assertOpenReply` become `openReply(owner)` with exact full text; the arrived wait captures the open prefix record; the combined wait requires an open record that `advancesFrom` the prefix; the completion wait requires `settledFormOf` with the combined text, which `advancesFrom` the prefix; the final count asserts one settled combined assistant record and no other assistant record.
- Multi-delta: the held reply is `openReply(owner)` with trimmed prefix text; the sent message is `ownedUserEcho(owner, SEND_PROMPT)`; the user count check uses it; completion waits for `settledFormOf(owner, held)` with `STREAMED_REPLY` that `advancesFrom(held)`, then the existing idle-phase wait. The side-message checks receive the records' rendered `Message`s, as before.

Text advances: the reopen fixture yields prefix (open), combined (still open, advanced) and settled combined; the stream fixture yields the open two-arrival prefix and the settled three-delta reply that advanced from it, with legacy settlement checking all three deltas.

## State and concurrency model

No new jobs or dispatchers. Each wait stays a `first` inside the method's existing `withTimeout`, on the repository snapshot the method already holds; `harnessThreadEvidence` maps that repository's cold flow in the caller's scope. Turn-phase reads still use `observeTurnPhase` directly; they are not thread observations.

## State transitions and identity reuse

| Event | Probe |
| --- | --- |
| Same raw item ID and text growth from another host | `collidingHostIdentityCannotAdvanceOrSettleTheOwnedReply` |
| Evidence from a retired connection or replaced epoch/generation | `retiredConnectionEvidenceCannotSatisfyOpenEchoOrSettlement` |
| Reply revisions while open, then completion | `authoritativeReplyAdvancesByRevisionAndSettlesOnItsOwnFacts` |
| Active, running, blank or unadmitted final reply | `incompleteOrActiveRepliesNeverSettle` |
| Reopen re-observes the same reply | `reopenedPrefixStaysTheOneOpenReply` |
| Direct Share before and after the tap, repeated or pending echo | `directShareRequiresNoUserBeforeAndExactlyOneOwnedEchoAfter` |
| Legacy delta gap at completion | `legacySettlementKeepsDeltaSequenceChecks` |

## Error handling

Predicates return null or false on incomplete or mismatched evidence, so waits continue to the existing deadline and fail there; direct assertions fail with their existing messages. No exception is caught.

## Testing strategy

Write `app/src/test/java/de/pyryco/mobile/e2e/StreamingReplyEvidenceTest.kt` first (fails to compile), then the helpers; the probes above use authoritative snapshots through `authoritativeThreadEvidence` and legacy messages through `legacyThreadEvidence`. Run it with `ThreadEvidenceTest` and the existing settlement tests. Compile androidTest. The device change edits existing scripted methods; the scripted gate is their only runner. Run `python3 scripts/android-test-gate.py scripted direct-share`, `reopen-stream` and `stream`, and record executed/failed/skipped counts per method. These prove the current legacy route only; authoritative behaviour is proved by the JVM probes, and negotiated activation is later.

## Open Questions

- Does the legacy scripted reply keep a segment with contiguous deltas through completion? The settled wait depends on it; the `stream` and `reopen-stream` runs settle it.

## Documentation handoff

- Pending for the documentation stage: `docs/e2e-interactive-stream.md`, the scripted ladder: record that `direct-share`, `reopen-stream` and `stream` observe through `harnessThreadEvidence` (legacy route), with the counted results from the PR, and that these passes do not prove negotiated production activation.

## Revisions

- 2026-10-17: Open question resolved. The legacy scripted reply keeps its segment through completion: `stream`, `reopen-stream` and `direct-share` each executed and passed one test at `037ddbdf6`, so the settled waits use `settledFormOf` unchanged. Probe fixtures use the daemon's `running` status for open assistant items, because an unknown status projects as a fallback row, and a `failed` user item for an undelivered send. `isSameItem` is a shared helper, used by the reopen identity check, `advancesFrom` and `settledFormOf`.
