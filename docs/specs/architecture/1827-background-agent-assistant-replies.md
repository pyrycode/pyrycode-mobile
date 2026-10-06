# Attributed assistant replies in background Agent blocks

## Files read

- `ui/conversations/thread/BackgroundAgentBlocks.kt`: `foldBackgroundAgentBlocks` owns lifecycle joins, running placement, finish anchors and expansion carry.
- `ui/conversations/thread/ThreadRow.kt`: `foldToolRuns`, `toolNestingDepths` and `listKey` own collapse, depth and identity.
- `ui/conversations/thread/ThreadFold.kt`: `StreamingTurn`, `reduceDelta` and `render` create the transient assistant row.
- `ui/conversations/thread/ThreadViewModel.kt`: `threadItems` merges repository snapshots and live events through that fold.
- `ui/conversations/thread/ThreadScreen.kt`: queued/Agent/run projections, `MessageBubble` and `FollowNewestEnd` consume moved rows.
- `ui/conversations/components/MessageBubble.kt`: `AssistantMessage` already provides the assistant renderer and accepts a modifier.
- `data/repository/HistoryPageReducer.kt` and `ThreadProjection.kt`: #1826 retains parent hints by existing lane identity, including history overlap.
- `docs/knowledge/features/thread-screen.md` and `thread-screen-subagent-tool-rows.md`: #1783 separates join evidence, finished knowledge and finish position; do not invent a position from a roster.
- `docs/knowledge/features/remote-conversation-repository-assistant-reply-segments.md`: repository segments own replay dedup and lane separation.
- `docs/e2e-interactive-stream.md`, sections `What rung 3 is made of` and `Live mode`: existing bounded hold/release fixture and dispatcher ownership.
- `InteractiveStreamE2ETest`, `DeterministicInteractiveStreamE2ETest`, `scripts/e2e-emulator.sh` and background-agent fixtures: existing live and deterministic placement proofs.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: assistant lane attribution and Security model are the wire source of truth.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=795-7158

Inspected design context and screenshot for Agent block `795:7303` inside Running `795:7178`. Its Agent header sits at depth zero, with children inset 16 dp per level. Reuse the existing assistant renderer with this indentation, existing Material theme tokens, Agent marker/header and tool-run control; no new visual component or asset.

## Context

#1826 preserves assistant parents, but background placement currently claims only tool rows and the ViewModel synthetic loses the hint. Child prose can therefore appear as a main reply. Extend the existing display projection without altering repository order, cache or wire contracts. No decision record is needed.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1729, #1766 and #1830 touch the shared e2e file, selector or screen; their changes concern other methods/rendering arms. Keep edits local and additive.

## Design

- Retain a lane's first nonempty parent hint on `StreamingTurn` and copy it onto the synthetic `Message` in `ThreadFold.render`. Existing distinct wire lane ids continue to prevent concatenation across agents.
- Extend the memoised tool ownership lookup in `foldBackgroundAgentBlocks` to assistant rows via their nonempty parent. Only a chain reaching an existing joined background Agent block claims prose. Empty, missing, cyclic and untracked parents keep ordinary rendering. All claimed rows move once in original loaded order, preserving message ids and segment content. Root header status and placement remain #1783's contracts.
- `foldToolRuns` treats a contiguous joined block as one collapsible run, including assistant children between tools and a lone Agent with prose. Its `tools` remain tool messages only for count/status/expansion carry; its identity remains the first tool id. Ordinary runs retain their current two-tool threshold and non-tool boundaries. Expansion emits original delivered rows, collapse hides them without modifying content.
- `ThreadScreen` gives only claimed assistant rows 16 dp times their parent's tool depth plus one. Reuse `MessageBubble` unchanged. A block-child semantic tag identifies ownership for the real and deterministic proofs. Main assistant modifiers and renderer remain unchanged. Existing growth signature already includes all claimed delivered rows.

## State and concurrency model

Pure render-time folds add no jobs, flows, dispatchers or mutable repository state. `threadItems` remains conversation-scoped in `viewModelScope`; existing screen expansion remains conversation-scoped/saveable. Socket closure and reconnect remain owned by `LifecycleConnectionDriver` and the supervisor. History/reconnect snapshots rederive the projection from retained attribution and lifecycle evidence.

## Error handling

Unresolved attribution is ordinary assistant text, not an error. Parent hints are equality/grouping data only and never trigger an action. Existing repository key/delta guards handle duplicate/replayed wire rows; the display fold never mints a new prose identity. No new error branch, IO outcome or logging is introduced.

## Testing strategy

Test first: run new JVM fold/synthetic tests red before production edits. Probe each issue invariant through real projection functions: two agents and a main lane, tool/prose interleaving, empty/missing parents and tasks without Agent rows, running-to-finished, roster replacement, late parent joins, replay and history overlaps at start/middle/end, empty/one-row pages and reconnect-like replacement between steps. Assert content, block ownership and unique keys, avoiding total marker/header counts.

Compose coverage uses the real `ThreadScreen`: collapse on/off and toggling, prose without a child tool, 16 dp indentation, preserved main text and existing background/run/follow regressions. Run affected JVM/shared suites, lint, assemble and Android-test compilation.

Land `InteractiveStreamE2ETest.interactiveTurn_backgroundAgent_replyStaysUnderAgent` in the curated live selector. Reuse the bounded loopback hold: a real Agent emits identifiable prose before waiting, a main phone turn continues, and the test verifies parent attribution, nested UI ownership and closed/open/closed visibility. Real daemon/Claude and device IO justify androidTest. Extend the deterministic background-agent fixtures/scenario with attributed prose and an unmatched lane; run the focused scripted scenario. The dispatcher owns full live execution and fresh XML/counts proving the named method passed. No separate focused live run is required by this ticket.

Final checks after last main merge: whole `testDebugUnitTest`, `assembleDebug`, and `scripts/pre-verify.py --gradle` after pushing; forced Spotless included.

Sizing: forecast approximately 1050 written lines including this plan, tests, fixtures and scenario; no new exported declarations, no changed signatures requiring consumer migration, four acceptance criteria and fewer than ten fallback branches. One deliverable: attributed prose belongs to its existing block across all representations.

## Open Questions

None.

## Revisions

2026-10-06: The existing live fixture retains its release event for the full suite. The new scenario uses separate fixed `/hold-reply` and `/release-reply` endpoints with an independent bounded event, so #1783's earlier release cannot end this scenario's background hold. Existing endpoints and ownership remain unchanged.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/thread-screen.md` or owning linked topic, attribution/fallback and collapse behavior.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, new scenario and dispatcher-produced live evidence. Documentation records evidence and does not execute the live proof.

## Security review

**Verdict:** PASS

- [Trust boundaries] `foldBackgroundAgentBlocks` joins only loaded tool ids with existing local-agent/background evidence. Parent strings grant no authority. Missing/cyclic joins preserve text. `ThreadFold` carries the inert hint without changing wire validation or existing bounded assistant rendering.
- [Tokens] No credential generation, access, storage or lifecycle changes; fixture uses the existing isolated test stack.
- [Files/storage] No paths, cache keys, persistence, backup or file writes derive from parent ids. Cache migration is outside this ticket.
- [Android surface] No exported component, deep link, intent, provider or WebView changes. Child prose uses the existing assistant text renderer.
- [Cryptography] No change to Noise, key storage, nonce management or secret comparison. Equality compares public grouping hints only.
- [Network/IO] No production network change; frame caps, TLS, timeouts and reconnect backoff remain at their existing boundaries. Live fixture uses fixed bounded loopback hold/release endpoints.
- [Errors/logs] Pure folds introduce no logs. Parent ids, assistant bodies, tokens and decrypted frames must never be logged. Semantic tags expose only the existing public Agent id to tests/accessibility, not extra message content.
- [Concurrency] No new coroutine or shared state. Conversation-scoped fold/expansion cannot group another host/conversation's rows; reconnect reruns pure projection.
- [Threat model] Malicious relay delay/reorder is covered by replay/history probes and existing Noise protection. Hostile daemon parent cycles/missing joins terminate/fall back. Token theft and UI screenshot/accessibility/keyboard exposure retain existing protections and are unchanged by grouping; no additional credential sink is introduced.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-06
