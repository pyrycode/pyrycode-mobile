# Background-agent following evidence migration (#2173)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: `ThreadEvidence.settledReply`, `userEchoCount`, `ThreadEvidenceRecord`, `harnessThreadEvidence`, `legacyThreadEvidence`, `authoritativeThreadEvidence`. #2169's shared contract; `settledReply` needs a session and a source-specific family key (legacy raw parent, authoritative projected block).
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: `firstOnLive` accepts a value only from the still-current source.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentRunNavigationProof.kt`: `verifyAgentRunNavigation` keys the header, marker, run and child rows by the identities it is given; it needs rendered identities, not raw peer IDs.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_followsBottomUntilFinished`. Four legacy thread observations (newer reply, later reply, owned child reply, the later user id read), two raw joins (late child tools by `toolCall.parentToolUseId`, `runId` as the first raw child tool) and raw `agentId` in the header tag, marker lazy key, child tag and final `msg:` index.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread` scopes identities by host, conversation, epoch and generation; an agent item's block ID is its own row ID; the live progress join reads `content.tool_use_id`, the only correlation from a raw tool ID to an admitted item.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocks.kt`: `foldBackgroundAgentBlocks` gives the root and its children the root row's message ID as `agentBlockId` and uses it for `AgentStartMarker`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `foldToolRuns`, `listKey` (`msg:<row id>`, `agent-start:<block>`). `ThreadScreen` tags the header `background-agent:<root message id>` and children `background-agent-child:<agentBlockId>`.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt`: `ordinaryId` is a legacy row's wire identity behind a collision alias.
- `docs/specs/architecture/2169-shared-thread-evidence.md` and in-flight #2170's `ReplyEvidence.kt`: extension predicates in their own sharedTest file, no change to the shared contract beyond exposing `admitted`.
- `../pyrycode/docs/protocol-mobile.md`, "Full thread item", "Daemon thread updates", "Session-scoped live state" and `tool_use`: raw `tool_use_id` is a join key; the daemon `parent` is the agent item's ID.

## Context

Required-thread activation will switch `harnessThreadEvidence` once. This scenario still reads raw legacy lists and keys rendered rows by raw peer IDs, so on the authoritative route it would wait on nothing and look up rows under keys the screen never draws. This ticket moves its observations onto source-bound evidence with projected identities; the production binding stays legacy. Size: about 500 written lines, one new type, nine consumer sites in one method, four criteria. #2170 and #2171 change other methods in the same suite and make the identical `admitted` visibility edit in `ThreadEvidence.kt`; edits stay additive and local.

## Design

New sharedTest file `BackgroundAgentEvidence.kt`:

- `ThreadEvidenceRecord.rawToolUseId()`: the wire `tool_use_id` behind a tool record, for correlation only. Legacy: `message.ordinaryId` of a tool row. Authoritative: the string `content.tool_use_id` of a `tool_call`/`agent` fact.
- `ThreadEvidence.settledTurnReply(turn, rawAgent = "")`: strict settlement of one turn's assistant family without a caller-supplied session. The session is the single session holding that turn's assistant records (none or several fails). Main family: `""` on both sources. Agent family: resolved through `renderedAgent(rawAgent)`; legacy keys the raw parent, authoritative the projected block. Delegates to `settledReply(owner, …)`, so legacy keeps contiguous deltas and authoritative keeps the item's own terminal revision.
- `RenderedAgentFamily(owner, agentId, children)` from `ThreadEvidence.renderedAgent(rawAgent)`: the admitted root tool record whose raw ID matches and which owns its block (`agentId == message.id`); `children` are the other records in that block, in thread order. `childIds`, `runId` (the projected `toolRunId` of the first child tool) and `ownsTools(rawIds)` (each raw ID is a child tool of this block). Null when unadmitted or unmatched.
- `ThreadEvidence.deliveredUser(text)`: the single delivered user record with that full text, else null.

`ThreadEvidence.admitted` becomes readable, the same edit as #2170/#2171.

Scenario migration, all reads `liveRepositories.firstOnLive({ harnessThreadEvidence(it).observe(chat) }) { … }` under the existing timeouts:

1. Newer reply: wait for `settledTurnReply(newerTurn)` and an admitted `renderedAgent(agentId)` with the same diagnostics; the phase wait is unchanged. The header tag, marker lazy key and marker navigation use that rendered agent ID.
2. Later reply: wait for `settledTurnReply(laterTurn)`.
3. Owned child reply: wait for `settledTurnReply(childReplyTurn, agentId)` with `renderedAgent(agentId)`; the late-tool ownership assertion uses `ownsTools(lateTools)`; `verifyAgentRunNavigation` receives the family's rendered `agentId`, `runId` and `childIds`, and the child matcher tag uses the rendered ID.
4. Final placement: one current read that holds both `renderedAgent(agentId)` and `deliveredUser(later)`; the `msg:` indexes use their rendered IDs.

Peer-frame assertions, prompt handling, fixture release and cleanup stay unchanged.

## State and concurrency model

No new jobs or dispatchers. Every read is a `runBlocking` `firstOnLive` under its existing `THREAD_TIMEOUT_MS` deadline, so a replaced connection's flow is cancelled and cannot supply a value. Rendered identities are resolved from the snapshot each step reads, never carried into a later step's keys, because an authoritative epoch or generation change re-scopes them.

## State transitions and identity reuse

| Event | Probe |
| --- | --- |
| Child reply active then terminal revision; parent agent still running | `childReplySettlesOnlyOnItsOwnTerminalRevision` |
| Same raw tool IDs on another host | `wrongHostAndRetiredEvidenceCannotSatisfyWaits` |
| Repair, reset or missing epoch after admission | `wrongHostAndRetiredEvidenceCannotSatisfyWaits` |
| Connection replaced while the retired source later settles | `retiredSourceCannotSettleAWait` |
| Legacy collision alias on root and children | `legacyFamilyUsesRenderedAliasesAndRawCorrelation` |
| Same turn ID in two sessions | `mainReplySettlementNeedsOneSessionForTheTurn` |
| Later user echo repeated or only queued | `laterUserIsTheSingleDeliveredEcho` |

## Error handling

A missing, unadmitted, ambiguous or incomplete fact fails the predicate; the wait times out with its existing diagnostic, now built from evidence records. No identity is inferred from ID spelling.

## Testing strategy

JVM `BackgroundAgentEvidenceTest` under `app/src/test/java/de/pyryco/mobile/e2e/`, written first: authoritative families from scoped facts with active-to-terminal revisions, projected run/child IDs against `projectAuthoritativeThread` and `foldToolRuns`, wrong-host and retired evidence, the legacy aliased family, session ambiguity and the delivered user echo. `ThreadEvidenceTest` and existing legacy component probes run unchanged. Compile the androidTest source set. Recorded native coverage: `RecordedMobileSuiteTest#interactiveTurn_backgroundAgent_followsBottomUntilFinished` through `android-test-gate.py ui --tests`, with counts. No new device-only test, no live run (#2175 owns negotiated-route live proof), and no outgoing request change, so the recording baseline stays.

## Open Questions

None.

## Documentation handoff

- Pending for the documentation stage: `docs/e2e-interactive-stream.md`: record the migrated scope of `interactiveTurn_backgroundAgent_followsBottomUntilFinished`, the named recorded results and counts from the PR, and that the exercised route is the legacy harness binding; preparation passes do not prove negotiated production activation.
