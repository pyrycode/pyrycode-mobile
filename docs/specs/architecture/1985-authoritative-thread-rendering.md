# Authoritative thread rendering (#1985)

## Files read

- `data/network/ThreadUpdatePayloads.kt`: `ThreadItemDto` retains complete inert facts and optional attribution.
- `data/repository/AuthoritativeThreadStore.kt`: `AuthoritativeThreadSnapshot` is the complete held snapshot; rendering must not mutate its items or checkpoint.
- `ui/conversations/thread/ThreadViewModel.kt`: `TaskControls` and `sendBackgroundTaskStop` own capability checks, pending attempts and the outbound repository action.
- `ui/conversations/thread/ThreadUiState.kt`: `ThreadUiState` gets one optional presentation carrier without migrating existing constructors.
- `ui/conversations/thread/ThreadRow.kt`: `Delivered`, `AgentStartMarker`, keys and tool-run folds support the existing presentation route.
- `ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen` selects supplied authoritative rows before the legacy folds and reuses message, attachment, tool, notice and boundary components.
- `ui/conversations/thread/ThreadListFollow.kt`: `ThreadListViewport.relocationFor` requires roots with `msg:<agentId>` keys and markers whose finished/destination facts change.
- `data/model/Message.kt`: `Message` and `ToolCall` are presentation adapters only on this route.
- `data/model/BackgroundTask.kt` and `ui/conversations/thread/BackgroundTaskPanel.kt`: `isFinished` controls grouping; task actions use the recorded task id.
- `docs/knowledge/features/thread-screen.md`, `thread-screen-subagent-tool-rows.md`, `thread-screen-how-it-works-list-and-status-row.md` and `thread-screen-testing-background-agent-viewport.md`: preserve the shared relocation and saved expansion ownership, including stationary history anchors.
- Daemon `docs/protocol-mobile.md`, Full thread item, and `docs/knowledge/decisions/042-daemon-built-thread.md`, Ordering and Agents: authoritative contract. Current published daemon `internal/thread/{fold,main,agent,boundaries,sends,shell}.go` supplies the kind-specific JSON shapes.

## Design source

Figma: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8
Agent references: nodes 795:7158, 795:7178, 795:7331 and 795:7484. Panel: node 568:877.
The existing dark thread uses message bubbles, outlined Agent headers with indented tool children, launch markers with a dot and Go to agent, and running/finished task groups. Reuse those components and their Material typography, primary/primaryContainer and surface tokens; this ticket changes their input facts, not their styling. Design contexts and screenshots were read before planning.

## Context

The legacy history/roster join can attribute a finished historical agent to a later running session. The daemon already records identity, parentage, placement and lifecycle, so this additive route presents those facts directly. Production negotiation and store observation stay with #1988; no capability is advertised here. No in-flight feature branch overlaps the planned existing files. No decision record is needed.

## Design

Add a pure `projectAuthoritativeThread(host, conversation, snapshot)` and an optional `AuthoritativeThreadPresentation` on `ThreadUiState`. The carrier contains scoped identity, chronological rows, their display items/depths and an authoritative task roster. The screen accepts it through its existing state parameter, so supplied-snapshot tests exercise the renderer #1988 will activate.

Deduplicate held items by id and newest revision, then retain the complete map for parent/task interpretation. Only shown positive-order items become content rows. Sort by daemon order; do not promote missing or zero order to history. Resolve parent chains against all held items, with bounded cycle detection, and emit each child once within its recorded agent. Hidden parents supply attribution without a content row.

Only Agent/Task items with a recorded background task link are bottom-pinned while active, stacking in launch order. Their launch markers stay at the original order. Inactive blocks settle at positive `ended_order`, otherwise launch order. Foreground agents and background shells remain chronological. Adapt lifecycle from status/active/cause, never from launch result, session state or a roster.

Each content key is `msg:<length-prefixed host/conversation/epoch + item id>`; marker keys refer to that same scoped id. Nonmessage adapters carry an explicit key override. Keys never include revision, status, order or index. This preserves shared tool expansion, metadata and scroll bookkeeping while preventing epoch state reuse. Existing tool-run folding remains presentation-only.

Map supported content into existing `ThreadItem`/`Message`/`ToolCall` components. Unknown kinds/statuses/subtypes or structurally unsupported content carry bounded plain summary text on the delivered row, rendered with selectable Text rather than markdown. Known messages preserve markdown/reveal and recorded attachment ids. Recognized notices and abnormal turn ends reuse current components; shown normal endings and shown info notices must still render, falling back to summary when the legacy component intentionally hides them.

Task rows come only from recorded links on agent/tool items, deduplicated by task id. `isFinished = !active` even for unknown status. Missing task ids provide no invented panel entry or action. The screen uses the authoritative roster/count instead of legacy fields. Authoritative panel expansion is local and scope-keyed; Stop remains capability-gated and dispatches the recorded task id through the owning screen callback.

## State and concurrency model

Projection is pure synchronous presentation work over an immutable supplied snapshot. It does not observe the store, start jobs, modify revisions, evict held facts or advance checkpoints. Existing composition-owned navigation/follow scopes retain their cancellation paths. No new ViewModel jobs or StateFlows; #1988 owns collection and production binding.

## State transitions and identity reuse

| Event | Proof |
| --- | --- |
| Same-id revision/status/order update | `AuthoritativeThreadProjectionTest` stable keys and authoritative lifecycle probes; screen expansion update probe |
| Repeated or reordered complete snapshot | projection replay/permutation probes and screen reload probe |
| Active to terminal / ended-with-session | projection settlement/prior-session probes and screen follower/history-anchor settlement probes and `terminalBlockAlreadyAtItsRecordedEndDoesNotRelocateItsReader` |
| Historical or active parent outside page; hidden parent | projection complete-parent probes and screen nested tools/prose probe |
| Two active backgrounds; foreground and shell neighbours | projection pinning probes and screen marker/navigation probe |
| Epoch, host or conversation changes with reused item ids | projection identity probe and screen epoch expansion reset probe |
| Reconnect/recollection in same epoch | supplied reload preserves keys and expansion; store remains unchanged |
| Rotation / re-created composition | existing saveable keyed expansion and existing legacy restoration coverage remain in use; same-scope action facts retain pending attempts |
| Stop tap/repeat, refusal, item replacement, terminal facts, capability loss and foreign owner | `ThreadViewModelTaskStopTest.authoritativeStopUsesRecordedFactsAndSurvivesLegacyRosterUpdates`, `authoritativeStopRejectsForeignOwnersStaleIdentityAndLostCapability`; `AuthoritativeThreadScreenTest.recordedStopReachesOwningRepositoryWithoutLegacyRosterOrExpansion` |

## Error handling

Malformed or unsupported semantic content falls back to the supplied summary. Optional missing timestamps use a stable display-only epoch timestamp, never placement. Unknown status cannot imply activity; active is direct authority. Parent cycles/missing parents never invent attribution or duplicate rows. No network or storage failure modes are introduced. Content-free projection diagnostics report only static event/code and counts.

## Testing strategy

Write projection tests first and observe failure before production implementation. Unit invariant probes cover all eight kinds, hidden/unknown rows, positive order only, permutations/replays/duplicates, complete parent chains, two active agents, foreground/shell placement, session-ended agents, settlement anchors, scoped identity and snapshot immutability. Supplied-snapshot shared Compose tests mount real `ThreadScreen` for content/attachments, nesting, expansion/reload/epoch reset, navigation, panel grouping/Stop and reader/follower settlement. Run existing background agent, tool, task panel and follow coverage. No device-only test or scripted scenario is changed. #1988 owns the named rung-3 activated-route scenarios and prior-session regression; live daemon #3077 is not needed here.

Focused JVM checks, lint, assembleDebug, shared-test Android compilation, Spotless, then post-main-merge assembleDebug and `scripts/pre-verify.py --gradle` are required. Record executed/failed/skipped counts from XML.

## Open Questions

None. Kind-specific content is read from the daemon fold rather than guessed from raw events.

## Security review

**Verdict:** PASS

- Trust boundaries: MUST FIX addressed in design. `projectAuthoritativeThread` bounds display strings and validates semantic shape. Unknown summaries render only selectable plain Text; supported messages reuse the established markdown and attachment boundaries. No JSON evaluation or refolding.
- Tokens: no token/key generation, storage or handling is introduced; content and adapter representations must never be logged.
- Files/storage: presentation does not write files or construct paths. Recorded attachment ids use the existing guarded attachment pipeline. Sync/cache remains #1984.
- Android attack surface: no component, intent, provider or WebView is added.
- Cryptography: no changes; existing Noise transport remains owner.
- Network/I/O: no new I/O; codec/store validation and assembly limits remain #2042/#2043, production negotiation #1988.
- Errors/logs: only static codes/counts in projection diagnostics; summaries, content, task descriptions, ids and session data never enter logs. Carrier `toString` is redacted.
- Concurrency: pure projection cannot alter store state; all UI jobs retain existing composition cancellation. Epoch scopes reset interaction owners.
- Threat model: delayed/reordered/replayed hostile data is deduplicated and ordered by supplied facts. Oversized text is bounded for display, unsupported content is inert, and cycles terminate. Relay authentication, rooted-disk token theft and screenshot/accessibility leakage remain existing transport/storage/platform responsibilities, unaffected by this supplied-input route.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-11

Sizing: approximately 1450 total written lines including this plan, one new exported presentation type, no required constructor/call-site migration, five acceptance criteria, no new state machine. The one renderer is one independently verifiable deliverable.

## Revisions

- 2026-10-11: Daemon `applyAgent` retains a durable task link without a type and can retain an entire roster-origin link. Link presence proves background association; resolve a recorded roster row inside that item only, and use Agent kind solely for its display label when type is absent. Tests probe both concrete shapes. Nearest-agent ownership is flattened iteratively to keep nested children under their own parent. Projection diagnostics run at the screen boundary so the pure unit projection remains Android-independent. Recorded agent attribution is carried to attributed notice/stop components; missing attribution falls back to summary.

- 2026-10-11: The hidden nested-parent invariant probe exposed a dropped shown descendant. Keep hidden and unordered agents in the structural child graph, while emitting only shown positive-order content. An unordered structural root uses its first shown descendant's real order solely to place that descendant block; it never acquires a delivered row.

- 2026-10-11: The unsupported-agent-content probe confirms that recorded `agent` kind plus task link, rather than a supported launch-content name, owns background placement. Unsupported content still displays the inert summary while authoritative activity controls placement and task grouping.

- 2026-10-11: The panel identity probe found expansion following a reused task id onto a different item in the same epoch. Store local expansion by scoped item identity and adapt it to recorded task ids only at the panel boundary; revisions preserve expansion, replacement items and epoch changes reset it.

- 2026-10-11 (verifier rework): Separate structural background placements from nearest-agent ownership. Every recorded background agent, including a nested one, owns its own launch marker, pinned block and ended-order settlement. The moving-key set covers its complete rendered subtree while excluding independently placed background children. Hidden roots carry lifecycle metadata without a displayed marker or root. Supplied-screen rendered-frame probes cover hidden roots and nested foreground children with a reader inside the departing subtree and a stationary history row.

- 2026-10-11 (verifier rework): Resolve parent traversal termination explicitly: a missing ancestor retains the nearest held agent; only a repeated-node cycle removes ownership. Main-turn activity selects positive-order, shown, active, parentless tools without recorded task links before visual pinning; thinking and working therefore keep their existing status ladder.

- 2026-10-11 (verifier rework/security amendment): The screen publishes complete presentation facts to the existing event callback solely for action authorization, without binding production rendering or observing the store. Stop carries scoped item identity; the ViewModel accepts only its own host/conversation, matching current epoch/item/task, active facts and current capability, with duplicate sends suppressed. Authoritative eligibility and pending cleanup ignore legacy roster/expansion. Terminal updates, item replacement, scope change, refusal, send failure and capability loss retire attempts. Unit guards and a real screen-to-ViewModel outbound Stop probe cover this boundary. Security review remains PASS: no new I/O implementation, credential handling, Android surface, storage or content logs; the existing ViewModel scope owns sends and existing repository capability checks remain in place.

- 2026-10-11 (rework sizing): Required regression repairs take the final branch above the original 1,600-line estimate boundary (approximately 2,000 inserted/deleted lines after formatting). The handoff floor applies: separating action/viewport projection from its single supplied renderer consumer would produce a slice usable only by its sibling, rather than an independently verifiable deliverable. Keep this one renderer ticket together; no new product deliverable or production activation is added.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md`, supplied authoritative rendering and agent settlement sections: document complete held-parent interpretation, structural moving-subtree identity, hidden roots, independent nested background placement and foreground status selection. `docs/knowledge/features/thread-screen-how-it-works-overlays-and-app-bar.md`, background-task panel: document scoped item expansion, authoritative grouping and guarded recorded-task Stop dispatch. `docs/knowledge/features/thread-screen-testing.md` and `thread-screen-testing-background-agent-viewport.md`: record counted supplied-snapshot and rendered-frame regression coverage, and retain #1988's production activation/live proof handoff.
