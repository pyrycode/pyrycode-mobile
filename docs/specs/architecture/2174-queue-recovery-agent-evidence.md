# Queue, recovery and agent thread evidence (#2174)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_queuedPromptDoesNotSplitHeldReply` (three `observeMessages` sites: held prefix, still-held after queueing, same-reply completion), `interactiveTurn_seededChannel_sendQueuedNow_placesAfterToolResult` (one site), `interactiveTurn_seededChannel_backgroundAgentMovesAndSettles` (one site plus the raw `agent1783`/`child1783` header, child and run tag joins and the raw `parentToolUseId` family filter) and `interactiveTurn_seededChannel_missedEventsReplayInOrderAfterReconnect` (one site). Rendered, live-event and navigation steps stay as they are.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt` (#2169): `harnessThreadEvidence` is the single binding and still selects legacy; `ThreadEvidenceRecord` carries rendered `agentId`/`toolRunId`, the original message and the admitted fact; `settledReply` delegates legacy to `holdsSettledAssistantReply` and requires an authoritative item's own inactive `done`/`finished` facts; `userEchoCount` counts delivered exact echoes; `ThreadEvidenceOwner.matches` fences connection, host, conversation, epoch and generation. Records follow item order for legacy and projected row order for authoritative input.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RenderedReplyText.kt`: `hasCompletedReplay`/`assertOrderedReplay` over `List<ThreadItem>`, also used by `ScriptedThreadRenderTest`; they stay unchanged.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentRunNavigationProof.kt`: `verifyAgentRunNavigation` builds `background-agent:<agentId>` and `tool-run:<runId>` matchers and `msg:<id>` child keys from rendered identities.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: the header tag is `background-agent:<id>` only on the row whose `agentBlockId` equals its own message id; assistant children carry `background-agent-child:<agentBlockId>`; runs carry `tool-run:<runId>`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `foldToolRuns` names a run by its first tool row's message id.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread` keeps the highest revision per item, scopes identities by host/conversation/epoch/generation, keeps unordered queued users out of delivered rows, appends active background families after all placements and places a finished one at its `ended_order`.
- `app/src/test/java/de/pyryco/mobile/e2e/ThreadEvidenceTest.kt` and `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadFixtures.kt`: fixture shapes the probes mirror.
- `scripts/e2e-emulator.sh` and `scripts/e2e-fixtures/`: `send-now`, `background-agent` and `replay-order` are gate scenarios; `stream-queued-echo` selects the queued method but is not in the gate's scenario list (#2078 ran it through the harness directly).
- `docs/knowledge/features/development-verification-gates.md`, `thread-screen.md`: receipt is not delivery; rendered aliases are not wire identities.
- Sibling `../pyrycode/docs/protocol-mobile.md`, Daemon thread updates, Thread query messages, Session-scoped live state and Queue (v2): the governing facts; not restated.

## Context

#2169 landed shared evidence. These four scripted methods still read `observeMessages` directly at six sites and join raw agent, child and run IDs, so they would prove nothing on a daemon-built thread. This moves them onto `harnessThreadEvidence` (still legacy; no production route, capability or fixture change). One deliverable: about 700 written lines, one new type, nine consumer sites, four criteria. Overlaps: #2171 edits other methods in the same test file and makes `ThreadEvidence.admitted` readable; #1766, #2091 and #2104 edit other methods. Edits here stay local to the four methods; new helpers use names distinct from #2171's.

## Design

Add `app/src/sharedTest/java/de/pyryco/mobile/e2e/QueueRecoveryAgentEvidence.kt`, predicates over `ThreadEvidence` shared by the device test and the probes. Private helpers decide open/complete per record: authoritative facts by `active` and their own `done`/`finished` status, legacy by `isStreaming` or `ToolCallStatus.Done`; item identity is rendered message ID plus daemon item ID.

Queue/reconnect:

- `heldOpenReply(owner, prefix)`: the single assistant record, open, trimmed full text equal to `prefix`, owner-matched.
- `holdsOpen(owner, held)`: `held`'s item is still the open record.
- `settledHeldReply(owner, held, text)`: `held`'s item, settled through `settledReply` for its session/turn (legacy delta checks retained), full text exactly `text`, the only assistant record in that turn, and for daemon facts a strictly newer revision than `held`.
- `sendNowPlacementFailure(prompt, marker)`: null when the first tool is complete, exactly one record of `prompt` is a delivered user (`userEchoCount == 1`), and a settled reply containing `marker` follows; tool < user < reply in record order, and in daemon `order` too. Otherwise a content-free reason with indices.
- `ThreadEvidence.hasCompletedReplay(expected)`: legacy delegates to the list form; daemon facts need admitted, nonempty main assistant records, all settled, joined full text equal to `expected`.
- `authoritativeReplayFailure(expected, prompt)`: daemon facts only. One session/turn whose whole main family settles, distinct item IDs in strictly increasing `order`, joined text exact, one delivered user echo ordered before the last assistant item. No deltas are derived.
- `ComposeContentTestRule.assertOrderedReplay(evidence, expected, prompt)`: legacy delegates to the existing list assertion unchanged; daemon facts check rendered text then `authoritativeReplayFailure`.

Agent:

- `AgentFamilyEvidence(agentId, runId, root, children)` with `tools`/`childIds`.
- `agentFamily(owner)`: the single root record whose rendered block is its own row (the header tag rule); children are all other records in that block; `runId` is the first child tool's rendered run.
- `AgentFamilyEvidence.ownsSingleProse(text)`, `ThreadEvidence.holdsOutsideAgent(text, family)`.
- `settledAgentPrecedes(owner, family, text)`: same block identity as `family`, every tool complete in one run and carrying the root's turn, root complete, and every family record before the main reply `text`, which has no block.

Consumer mapping, one `harnessThreadEvidence(repository)` per method, owner from the first observation:

- Queued prompt: the three sites become `heldOpenReply`, `holdsOpen` and `settledHeldReply`; the content-free timeout shape reads records. `observeQueue` (queue route, not a thread observation) stays.
- Send now: `sendNowPlacementFailure` on one observation after the rendered marker.
- Background agent: after the existing running and raw live-event diagnosis, wait for `agentFamily` holding `1 + lateTools.size` tools, then derive header, child and run matchers from its rendered IDs. The raw live-event checks stay as fixture diagnosis. At the end wait for `settledAgentPrecedes`, assert the tool count, `ownsSingleProse("child1827-after")`, `holdsOutsideAgent("unmatched1827")`, and pass the family's IDs to `verifyAgentRunNavigation`. The raw `parentToolUseId` filter and `late.toolUseId == message.id` join go.
- Replay order: wait on `ThreadEvidence.hasCompletedReplay`, then `assertOrderedReplay(evidence, ...)`.

## State and concurrency model

No new jobs or dispatchers. Each wait stays a `first` inside its method's existing `withTimeout`; `harnessThreadEvidence` maps the repository's cold flow in the caller's scope. The agent method's event collector and its `finally` cancellation are unchanged. The reconnect method still takes the repository after `restoreLink`, so its evidence owner is the restored connection.

## State transitions and identity reuse

| Event | Probe |
| --- | --- |
| Held reply revisions while a queued user waits, then completion | `queuedFollowUpNeverSplitsOrSettlesTheHeldReply` |
| Older revision re-delivered after a newer one over recovery | `newerRevisionsSurviveRecoveryForEveryScenario` |
| Same item IDs from another host or retired connection | `wrongHostCollidingIdsSatisfyNoScenario` |
| Queued, pending or acknowledged-only send-now user | `sendNowNeedsDeliveredEchoAfterCompletedTool` |
| Replay missing, reordered, duplicated, split across turns or active | `replayRecoveryRequiresCompleteOrderedSettledItems` |
| Active background agent, running late tool, reply ahead of the block | `activeOrIncompleteAgentEvidenceNeverSettles` |
| Agent family across settlement keeps block/run identity | `agentFamilyUsesRenderedIdentitiesNotRawJoins` |
| Legacy shape for each predicate | `legacyEvidenceKeepsExistingChecks` |

## Error handling

Predicates return null/false or a content-free reason on incomplete or mismatched evidence; waits run to their existing deadlines and fail with their existing checkpoint messages.

## Testing strategy

Write `app/src/test/java/de/pyryco/mobile/e2e/QueueRecoveryAgentEvidenceTest.kt` first (fails to compile), then the helpers. The probes above build daemon snapshots through `authoritativeThreadEvidence` and legacy items through `legacyThreadEvidence`. Add one Robolectric screen probe, `app/src/sharedTest/java/de/pyryco/mobile/e2e/AuthoritativeScenarioScreenTest.kt`, that mounts daemon replay and finished-agent snapshots in `ThreadScreen` and runs `assertOrderedReplay(evidence, ...)` and the family-derived header/child/run matchers. Run them with `ThreadEvidenceTest`, `ScriptedThreadRenderTest` and `BackgroundAgentBlocksScreenTest`; compile androidTest. The device change edits existing scripted methods; the scripted harness is their only runner: `python3 scripts/android-test-gate.py scripted send-now`, `background-agent` and `replay-order`, and the isolated `SCENARIO=stream-queued-echo` harness run #2078 used, since the gate cannot select it. Record executed/failed/skipped per method. These prove the current legacy route only; authoritative behaviour is proved by the probes; negotiated activation is later.

## Open Questions

- Does the legacy root and every owned child row carry the rendered block ID the header tag uses, so `agentFamily` matches the old raw family? The `background-agent` run settles it. Resolved 2026-10-17: yes. The gated `scripted background-agent` run passed with header, child and run matchers and `verifyAgentRunNavigation` driven from `agentFamily`.

## Documentation handoff

- Pending for the documentation stage: `docs/e2e-interactive-stream.md`, scripted ladder: record that `stream-queued-echo`, `send-now`, `background-agent` and `replay-order` observe through `harnessThreadEvidence` (legacy route) with the named counted results from the PR, that rendered agent/run identities replace the raw joins, and that these passes do not prove negotiated production activation.

## Revisions

- 2026-10-17: A probe expecting owned prose between tools to split the Agent's run failed: inside an Agent block the folded run spans its owned prose too, which is why `verifyAgentRunNavigation` expects every child key to disappear when the run is closed. The case was a wrong expectation and is removed; `settledAgentPrecedes` keeps its one-run check, which still rejects a tool outside the owned run. Queued-prompt integrity is asserted on the completion snapshot itself (wait for the held item's completed text, then `settledHeldReply`), so a transient split row cannot be waited out.
- 2026-10-17: In `AuthoritativeScenarioScreenTest`, `verifyAgentRunNavigation` over the rendered daemon agent could not find the closed owned run's expansion control under Robolectric. Within the remaining budget the screen probe asserts the family-derived header tag and unmatched prose outside the block; the run, child and navigation proof over daemon facts stays with the JVM probes (`agentFamilyUsesRenderedIdentitiesNotRawJoins`) and the device method on the current legacy route. Diagnosing the daemon-rendered run control is left to activation (#1988).
- 2026-10-17: Diagnosed the screen-probe gap above. `ThreadScreen` renders `authoritative.rows` without `foldBackgroundAgentBlocks`/`foldToolRuns`, so a daemon-built Agent's owned tools appear as separate tool rows. There's no `tool-run:` "Using tools:" control to collapse. That is production presentation outside this test-only ticket, filed as #2238. The contract is unchanged: on daemon facts the collapse and navigation proof waits for #2238; on the current legacy route the device method keeps it.
