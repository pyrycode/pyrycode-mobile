# Earlier-session Agent regression on the negotiated thread

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocks.kt`, `foldBackgroundAgentBlocks`: legacy missing-terminal evidence promotes an old launch to a running newest-end block.
- `app/src/main/java/de/pyryco/mobile/data/repository/BackgroundTaskProjection.kt`, `FinishedBackgroundTasks`: finish memory is host-owned, conversation/task keyed and lost on process restart.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadPresentation.kt`, `projectAuthoritativeThread`: daemon session/activity/revision and ended order own status, nesting and placement.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadQueries.kt`, `accept`, `dispatch`: page and catch-up replies must pass actual request correlation and certification.
- `app/src/main/java/de/pyryco/mobile/data/cache/SavedAuthoritativeThread.kt`, `toSnapshot`: persisted authoritative facts carry lifecycle state without finish memory.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`, `ThreadWriter.schedule`, `observeAuthoritativeThread`: the reader owns a 100ms persistence timer and flushes on cancellation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`, `negotiatedPresentation`, `onDemandOlderHistory`: production destination selects authoritative content and requests older pages.
- `app/src/sharedTest/java/de/pyryco/mobile/di/SavedAuthoritativeDestination.kt`: reusable production registry/cache/destination fixture with controlled negotiated pumps.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadFixtures.kt`: complete daemon item builders.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/NegotiatedThreadScreenTest.kt`, `AuthoritativeThreadScreenTest.kt`: production state collection and Agent rendering assertions.
- `docs/knowledge/features/thread-screen.md`, `remote-conversation-repository.md`, `thread-screen-testing-background-agent-viewport.md`, `thread-screen-oldest-end-history-demand.md`: saved facts cannot negotiate support; historical placement and nested ownership require separate proof from running-tail geometry.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`, Thread query messages: authoritative wire contract; batches and final certification remain separate.
- `docs/e2e-interactive-stream.md`, Negotiated Agent restart and settlement (#2246): accepted Linux genuine restart/replay evidence has no page query or simultaneous active Agent B.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot on 2026-10-21 using figma-design-to-code. Preserve the dark thread, translucent header/composer, left assistant/right user bubbles, inline tool rows and rule/label/rule session boundary; existing Material 3 scheme and body/label typography remain. This ticket adds coverage without changing geometry, tokens or assets.

## Context

The legacy fold treats a launch without observed terminal evidence as running, even if the task ended in a previous session. Its conversation/task join cannot recover an unseen terminal from another transcript, and process restart discards finish memory. Production activation already replaced that content route with daemon-authored items. This ticket proves the remaining single behaviour: a historical terminal family never becomes a current running family during restoration, paging or reconnect. No separate legacy repair or new decision record is needed.

Forecast: about 650 written lines, no production files or exported production types, no consumer signature changes, three criteria and no new rejection state machine. Keep dedicated tests additive; no overlap was found in the two planned test paths. Refiner-observed #2334/#2367/#2373 touch adjacent infrastructure, not a causal prerequisite.

## Design

Add `EarlierSessionAgentScreenTest` under sharedTest, reusing `SavedAuthoritativeDestination`. Start Agent A in session A, finish it with nested tool facts through correlated negotiated replies, and wait for authoritative persistence. Dispose the destination/registry and create a fresh cache and fixture over the same on-disk root. Collect the real production ViewModel state into `ThreadScreen` without substituting a projector result.

Keep Agent B active in session B. Obtain an actual `thread_page` request from `onDemandOlderHistory`, reply with older history and overlapping older A/child revisions, then replace the connection and redeliver overlapping facts through its catch-up. Add a narrow fixture delivery hook only if needed to supply genuine live item envelopes between query boundaries. Assert exact canonical identities/revisions, session and parent ownership, terminal placement before B's session, one content row per item, finished launch marker, A without spinner, B with spinner and the one-task badge. Read stored facts again to prove terminal/child preservation on disk as well as in the projection and screen.

## State and concurrency model

Tests use the existing production connection child scopes, cache serialization and ViewModel scope with an injected `StandardTestDispatcher` and ordered Main-dispatcher rule. Advance its scheduler explicitly for the cache writer's 100ms timer, idle Android's main looper for Compose, and use an immediate frame boundary for controlled publication. Clear ViewModels and close fixtures before rebuilding; no state holder or finish-memory object is reused. Connection replacement must change the admitted `ThreadSupport.Supported` owner. The initial collector belongs to the ViewModel scope; cleanup waits for its job to complete before resetting Main.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| A starts and ends in session A with owned children | `EarlierSessionAgentScreenTest.terminalFamilySurvivesRestartOlderPageAndReplacementBesideRunningAgent` |
| Fresh cache/registry/ViewModel restores A without terminal memory | Same test, before session-B input |
| Session B starts Agent B while A remains terminal | Same test, exact state and UI assertions |
| Older page overlaps newer held A/child facts, adding older rows and preserving held neighbours | Same test, request/reply receipt and persisted-state assertions |
| Replacement connection redelivers overlapping facts in another order and duplicates them | Same test, changed connection owner, latest revisions and exactly-once assertions |
| Recollection or repeated screen checks | Same test, stable row keys/status and repeated rendered assertions |
| Prior-session launch has no terminal fact and no legacy roster | `EarlierSessionAgentScreenTest.missingTerminalNeverInfersCompletionFromLaterSessionOrAbsentRoster`: both Agents remain active, with two spinners and the two-task badge |

## Error handling

No new production error handling. Tests fail on missing/uncertified replies, wrong owner/session/parent, stale revisions, duplicate rows or incorrect rendered status; roster absence never supplies completion. Retain any failed check separately from later passes.

## Testing strategy

Write the integrated regression before considering production changes. The authoritative repair has already landed, so a green initial regression needs no speculative implementation. Exercise a controlled active-A negative control to demonstrate that the terminal-status oracle detects the original symptom, then run the correct fixture. Run the new shared class plus `BackgroundAgentBlocksScreenTest`, `ToolRunCollapseTest` and affected authoritative projection coverage on Linux. Run lint, assembleDebug, shared/instrumented Kotlin compilation and forced Spotless checks. Run the named recorded restart method through the focused UI gate and the focused scripted earlier-session restart scenario; dispatcher retains complete UI/scripted gates, including the background-Agent moves/settles outcome. Reuse #2246's accepted genuine proof with exact revisions and stated limits; no new live capture is required by this test-only ticket.

## Open Questions

None. An observed production regression will require a dated revision and scope reassessment before repair.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md`, Verification status, record #1963's Linux counts/revisions, page and replacement-connection receipts, raw evidence references and retained failures, relating them to #2246's accepted evidence without extending that evidence to paging or simultaneous active Agents. Other operating systems are outside verification scope.

## Revisions

2026-10-21: Initial probes timed out before observing a query. Add the fixture's read-only `hostReady` barrier before `open`; structural logs then established that query work was drained only during cleanup. Polling must idle Android's main looper, and persistence must advance the reader's 100ms coroutine timer explicitly through the injected scheduler. Keep the production destination/cache selection intact and control page demand through `ThreadViewModel.onDemandOlderHistory` independently of status-inspection scrolls. The no-terminal probe preserves two active Agents. Keep one held child beyond the older page's exclusive bound and an incoming child inside it, so preservation on both sides uses a coherent captured window. No production repair was required.
