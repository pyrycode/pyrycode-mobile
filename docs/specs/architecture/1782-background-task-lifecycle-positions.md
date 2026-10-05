# Retain background-agent launch joins and finish positions (#1782)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/BackgroundTaskPayloads.kt`: `BackgroundTaskRowDto` and the scalar lifecycle DTOs define the decode boundary.
- `app/src/main/java/de/pyryco/mobile/data/model/BackgroundTask.kt`: `BackgroundTask` retains panel descriptions, update slots and progress.
- `app/src/main/java/de/pyryco/mobile/data/repository/BackgroundTaskProjection.kt`: `applyStarted`, `applyRoster` and `rowTask` implement replacement truth and started-description precedence.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `ThreadItem` is the ordered consumer contract.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `withHistoryEntry`, `mergeHistoryRows`, `joinIdentity`, `withAssistantDelta` and `withJoinedSegments` govern history order, overlap and ordinary assistant segments.
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
- [Android attack surface] No component, intent, permission, provider or WebView is added. Render folding filters markers, so no new text sink exists.
- [Cryptography] Existing Noise_IK_25519_ChaChaPoly_BLAKE2s and Keystore ownership are unchanged; there is no new cryptographic operation.
- [Network and I/O] Existing envelope caps and transport deadlines remain. The additive optional roster field tolerates older daemons; wrong types fail silently. Both live and history use the negotiated interactive gate.
- [Errors, logs and telemetry] No logs are added to the no-logger task/history folds. Caught decode exceptions are discarded unread. Task ids, descriptions, summaries and patches never enter log or exception messages.
- [Concurrency] Pure lifecycle reduction and backfill run inside atomic thread updates; no mutable panel-origin state is accessed from those folds. Requested conversation routing prevents cross-conversation joins.
- [Threat model] Malicious relay delay/replay is handled by existing Noise authentication and idempotent marker identities. Hostile daemon malformed entries cost only themselves; description text is never rendered or executed here. Rooted-device token theft and existing screenshot/keyboard exposure retain the established transport/Keystore/UI mitigations; this ticket adds no storage or UI exposure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-05
