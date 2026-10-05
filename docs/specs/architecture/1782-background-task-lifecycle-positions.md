# Retain background-agent launch joins and finish positions (#1782)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/BackgroundTaskPayloads.kt`: `BackgroundTaskRowDto` and the scalar lifecycle DTOs define the decode boundary.
- `app/src/main/java/de/pyryco/mobile/data/model/BackgroundTask.kt`: `BackgroundTask` retains panel descriptions, update slots and progress.
- `app/src/main/java/de/pyryco/mobile/data/repository/BackgroundTaskProjection.kt`: `applyStarted`, `applyRoster` and `rowTask` implement replacement truth and started-description precedence.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `ThreadItem` is the ordered consumer contract.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `withHistoryEntry`, `mergeHistoryRows`, `joinIdentity`, `withAssistantDelta`, `withOnlyLastRowStreaming` and `withJoinedSegments` govern history order, overlap and ordinary assistant segments.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt`: `threadByConversation`, `observe` and `mergeHistoryPage` preserve atomic conversation-local folds.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: the background-task arm in `onInbound` owns capability gating.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt` and `FileConversationCache.kt`: `cacheableThreadRows` and `toRecord` define the unchanged persistence boundary.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt` and `ThreadScreen.kt`: `foldQueuedRows`, list keys, rendering and channel-info timestamps must ignore evidence.
- Existing `BackgroundTaskPayloadsTest`, `BackgroundTaskProjectionTest`, `RemoteConversationRepositoryBackgroundTaskTest`, `HistoryPageReducerTest` and `ThreadProjectionTest` supply decoder, projection and merge scaffolds.
- `docs/knowledge/features/remote-conversation-repository.md`, `remote-conversation-repository-reads-and-thread-store-history-paging.md`, `mobile-protocol-v2-wire-layer.md` and `conversation-cache.md`: history order is arrival/log order, never timestamp sorting; atomic reduction belongs inside the thread update, and cache exclusions precede serialization.
- Sibling `../pyrycode/docs/protocol-mobile.md`: background-task sections, Conversation history (v2) and Security model are the wire SSOT. The sibling checkout is at `/Users/juhanailmoniemi/Workspace/Projects/pyrycode` on this host. Daemon PR #2776, merge `9f75fa4c`, adds the roster string `tool_call_id`, empty when unknown, without a new capability.

## Context

A roster currently cannot identify the launching tool call, and history discards scalar task lifecycle frames. Retain their joins and ordered evidence independently of the replacing panel roster. This is one data contract; visible placement belongs to the UI child. No decision record is needed.

Scope forecast: approximately 1000 written lines including plan and tests, one new exported nested ThreadItem type, at most nine consumer edits, four acceptance criteria and fewer than ten reject branches. The wider file count than the refiner forecast comes from explicit render/cache exclusions. Additive local overlap with #1642 (repository/thread projection and contract), #1735, #1747 and #1753 (screen); the nonnumeric #1283 notice branch also touches the screen. None supplies a required dependency or restructures the new lifecycle fold.

## Design source

No visual change. Lifecycle evidence is excluded before render-row folding and from channel-info timestamps; existing panel layout and tool-parent links stay unchanged.

## Design

Add an optional default-empty `toolCallId` to `BackgroundTaskRowDto`; wrong-typed present values still fail decoding. Normalize empty ids to unknown at projection boundaries. Track whether a panel task has received a started frame independently of its join, since roster enrichment makes a non-null join insufficient to identify a started description. A known started join survives an unknown roster join, and started descriptions retain precedence. Roster replacement still removes omitted tasks, pending slots and finished marks exactly as before.

Add `ThreadItem.BackgroundTaskLifecycle`, an invisible ordered marker carrying task id, optional launch tool id/description/type/truncation report, timestamp and an optional terminal `BackgroundTaskUpdate`. Null terminal denotes launch; a non-null update denotes finish. Identity is the pair `(taskId, terminal != null)` within the conversation, not a list index, history id, replay id or timestamp. One marker of each kind is retained per task. Empty task ids produce no evidence. A terminal arriving first keeps its marker position; a later launch fills its unknown launch fields by task id without moving it. The tool id is a join to an Agent/Task tool row even when that row loads later; no parent links are rewritten.

Use the same pure lifecycle folds in live and history lanes. Live routing remains interactive-gated; history arms mirror the gate and route only to the requested conversation. Newest-first pages reduce in reverse order. History overlap skips duplicate marker identities and fills unknown joins in place, then completes launch-to-finish joins across page seams. Older pages prepend their fresh evidence alongside their ordinary entries. Replacement/empty rosters never remove lifecycle evidence or synthesize a finish.

The new markers are transparent to assistant-delta anchoring and assistant-segment joining, so hidden evidence cannot split ordinary text. Filter them from `foldQueuedRows` before grouping rendered tools, from channel-info creation timestamps and from `cacheableThreadRows` before row limits/serialization. Exhaustive serializers and render branches acknowledge the type without introducing persisted records or visible rows. No dependency or wire verb changes.

## State and concurrency model

No new jobs, dispatchers or observable streams. Connection-scoped evidence lives in the existing hot `threadByConversation` StateFlow and uses pure atomic update lambdas shared with history merges. Panel started-origin tracking is confined to its single inbound collector and pruned with each roster. Screen exit and background socket closure use existing repository/lifecycle ownership; reconnect reconstructs lifecycle evidence through history. Disk cache format stays unchanged.

## Error handling

Malformed DTOs silently cost their frame/entry, following existing IllegalArgumentException catches. Empty task ids cannot join lifecycle markers. Empty tool ids remain unknown. Mid-life updates add no finish marker; any non-empty terminal status, including an unknown status, does. No exceptions, payloads or daemon-authored text enter logs or UI error state.

## Testing strategy

Write decoder and panel regression tests first and observe the failure before production edits. Add pure lifecycle/history tests for terminal-before-start, roster and start order, duplicate replay, history/live overlap, older-page prepend, two task identities, empty/missing ids, malformed payloads, inert text, gated history and ordinary assistant/tool ordering. Add repository integration tests for live gating, conversation isolation, and empty/replacing roster retention; exercise history joins through ThreadProjection. Assert render folding and cache exclusions without an emulator. Run affected existing decoder/projection/history/cache/row tests, lint, assembleDebug, spotlessApply and forced spotlessCheck. No new device or real-Claude scenario: the ticket exposes data only and changes no operator-facing flow.

## Open Questions

None.

## Security review

**Verdict:** PASS

- [Trust boundaries] Typed DTO decoding remains the boundary for authenticated but untrusted daemon content. SHOULD FIX: reject empty task ids for lifecycle evidence; unknown tool ids must not erase known joins. Descriptions, patches and summaries remain inert, daemon-bounded text, never evaluated or used as marker identities.
- [Tokens, secrets and credentials] No credential generation, access or storage is added. Lifecycle DTOs carry no authentication material.
- [Files and storage] SHOULD FIX: exclude markers before cache limits and serialization, retaining the current private cache schema and avoiding new plaintext persistence. No filename is derived from an id or description.
- [Files and storage, rework] `FileConversationCache.writeThread` excludes lifecycle evidence from pre-limit trim accounting too, so authenticated lifecycle frames cannot clear a stored pagination cursor or completed-history position without actual cache trimming. Fresh-instance regression tests cover both saved positions.
- [Android attack surface] No component, intent, permission, provider or WebView is added. Render folding filters markers, so no new text sink exists.
- [Cryptography] Existing Noise_IK_25519_ChaChaPoly_BLAKE2s and Keystore ownership are unchanged; there is no new cryptographic operation.
- [Network and I/O] Existing envelope caps and transport deadlines remain. The additive optional roster field tolerates older daemons; wrong types fail silently. Both live and history use the negotiated interactive gate.
- [Errors, logs and telemetry] No logs are added to the no-logger task/history folds. Caught decode exceptions are discarded unread. Task ids, descriptions, summaries and patches never enter log or exception messages.
- [Trust boundaries, second rework] Lifecycle anchors use typed row identities and `(turnId, seq)` overlap only. Descriptions and summaries never influence anchor selection. Anchor lookup is indexed for each linear merge pass; no nested scan of retained history is introduced.
- [Trust boundaries, third rework] Reconnect lifecycle insertion shares the history neighbour fold after ordinary cache rows are placed. Leading evidence waits for its first overlapping neighbour, and later evidence cannot cross a previously visited retained anchor. This uses only typed identities, never text, timestamps or terminal status to sort rows; terminal-before-start remains valid. Ordinary cache placement, persistence exclusions and all log boundaries are unchanged.
- [Concurrency] Pure lifecycle reduction and backfill run inside atomic thread updates; no mutable panel-origin state is accessed from those folds. Requested conversation routing prevents cross-conversation joins.
- [Threat model] Malicious relay delay/replay is handled by existing Noise authentication and idempotent marker identities. Hostile daemon malformed entries cost only themselves; description text is never rendered or executed here. Rooted-device token theft and existing screenshot/keyboard exposure retain the established transport/Keystore/UI mitigations; this ticket adds no storage or UI exposure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-05

## Revisions

- 2026-10-05: Inspection of `CachingConversationRepository.observeMessages` showed reconnect also merges a retained in-memory thread with `mergeCachedRows`. Apply the same lifecycle backfill to that seam; disk persistence still excludes all markers. Inspection of `ThreadProjection.observeRowCounts` also showed its visible-growth signal must exclude evidence, so lifecycle-only updates do not count as rendered thread growth. Tests cover both contracts.
- 2026-10-05: Verifier rework on PR #1784 found lifecycle exclusion missing from cache trim accounting and fresh markers prepended ahead of overlapping ordinary backfill. Exclude markers from the pre-limit count in `FileConversationCache.writeThread`. In `mergeHistoryRows`, keep the existing ordinary-row prepend contract, but insert fresh lifecycle evidence after its preceding retained page neighbour, before the following neighbour for leading evidence, or at the front when the page has neither, preserving page order within each insertion slot and keeping retained marker positions/content. Regression tests combine ordinary backfill, live replay, a retained terminal and older-page prepend.

- 2026-10-05: Second verifier rework on PR #1784 exposed backward anchor movement after ordinary prepend and lost anchors for differently keyed assistant segments. Keep history insertion slots monotonic while traversing the page, and resolve assistant anchors by overlapping `(turnId, seq)` as well as row identity. Leading markers use the first overlapping retained row; following markers use the last. Reconnect merges retain their ordinary-row contract, but advance the anchor past overlapping text even when all incoming text is discarded or only an older prefix survives. Regression tests cover retained launches, full and partial segment overlap, replay and older-page prepend. No new exported type, job, log or persistence change.

- 2026-10-05: Main's queued-message work moved thread rows and echo bookkeeping into `ThreadProjection.ProjectionState`. Merge resolution routes scalar lifecycle writes through `updateThreads` and keeps `observeRowCounts` on that atomic state while excluding lifecycle evidence.
- 2026-10-05: Third verifier rework on PR #1784 found reconnect's leading evidence placed before older backfill and a backward ordinary anchor moving a finish across its retained launch. `mergeCachedRows` now places ordinary rows with its existing cache-neighbour contract, then inserts fresh lifecycle markers through `withHistoryLifecyclePositions`, sharing history's leading-neighbour and monotonic-anchor rules. Retained markers stay in place, including terminal-before-start order. Two indexed linear passes preserve bounded lookup cost without a nested history scan. Regressions cover both exact permutations, different-id assistant overlap, replay, older-page prepend and terminal-before-start. Security review remains PASS: no new text sink, log, storage, job or exported type.
- 2026-10-05: Fourth verifier review on PR #1784 found an assistant suffix segment anchoring lifecycle evidence to a row that the later whole-turn cleanup removes, so the finish fell to the front in both history and reconnect merges. Lifecycle insertion now anchors against the thread after whole-turn segments are removed, and a segment of a turn held by a whole-turn row resolves to that row. Regressions cover history and reconnect, with and without a retained launch, replay, older-page prepend and the reverse whole-over-segments shape. No new exported type, log or persistence change.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`, "History pages fold into the same thread": document `ThreadItem.BackgroundTaskLifecycle`, conversation-local task/phase identities, late launch backfill, terminal positions and ordinary-row anchors during history/replay overlap.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer-application-payloads.md`, "Application payloads (decoded on top of `Envelope`)": document enriched roster joins, started-description precedence, and replacing panel truth versus retained lifecycle evidence.
- `docs/knowledge/features/caching-conversation-repository.md`, "How the restore merges with live rows", and `docs/knowledge/features/conversation-cache.md`, "The thread document's two writers (#1354)": document in-memory retention across reconnects, render/row-count/timestamp exclusions, unchanged cache schema and exclusion from trim accounting.
- Record the regression lessons in these topics: cache exclusions must match trim accounting; overlap deduplication must retain ordinary-row anchors for new evidence.
