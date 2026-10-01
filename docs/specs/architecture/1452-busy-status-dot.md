# #1452: blink a chat's status dot while it is stalled, retrying, compacting or resetting

## Files read

- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `ConversationAttention.Running`, `resolveAttention` and `HostAttentionState` (`running`, `disconnected`, `resolve`). The fold that gains a busy set.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `launchAttention`, `updateAttention` and the `connection.repositories` collectors. Gains one busy collector.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `observeStall`, `observeApiRetry`, `observeCompacting`, `observeResetting` and their inert defaults. Gains `observeBusyConversations`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: the four projection fields, the live-session arm's `stallProjection.clear` and the `session_transition` arm's `resettingProjection.clear`. Implements the union.
- `StallProjection.kt`, `ApiRetryProjection.kt`, `CompactingProjection.kt`, `ResettingProjection.kt` under `data/repository/`: each private `MutableStateFlow` and its per-conversation `observe`. Each gains one host-wide read.
- `RelayConnectionRegistry.reconcile` and `RelayRepositoryCoordinator.currentRepository`: a host's `repositories` flow holds the concrete `RemoteConversationRepository` per connection, so `StableConversationRepository` is not on this path and needs no override. `CachingConversationRepository` delegates by `by delegate`. The demo host's `FakeConversationRepository` inherits the empty default.
- `docs/knowledge/features/stall-state.md`, `api-retry-status.md`, `compacting-state.md`, `resetting-state.md`: the edges this ticket reuses unchanged.

Overlap: #1361 (PR #1422) adds a sibling `collectLatest` collector in `launchAttention` and a method on `HostAttentionState`. Both edits here are additive next to it.

## Design source

Figma https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259 (Sidebar, "Blinking busy status dot"). No new visuals: a busy conversation resolves to the existing `ConversationAttention.Running`, which already draws the running blink. The verifier's visual check reduces to that mapping.

## Context

Desktop blinks a row's dot while `isWorking`: a running turn, or a stalled, API-retrying, compacting or resetting conversation. Mobile blinks only on `turn_state`, so the other four are invisible from the list. The repository already holds all four facts per connection for the thread's indicators; this ticket exposes their union host-wide and folds it into the attention state. No decision record needed.

## Design

- `ConversationRepository.observeBusyConversations(): Flow<Set<String>>`, default `flowOf(emptySet())`. The conversation ids that are stalled, retrying the API, compacting or resetting right now.
- Each projection gains `fun observeIds(): Flow<Set<String>>` over its existing state, with no change to `apply`, `clear` or `observe`:
  - `StallProjection`: the stalled set itself.
  - `ApiRetryProjection`: keys whose status is not `ApiRetryStatus.NotRetrying` (the falling edge is stored, not removed).
  - `CompactingProjection`: the compacting set itself.
  - `ResettingProjection`: the map's keys (only rising edges are stored; falling edge and `session_transition` remove).
- `RemoteConversationRepository.observeBusyConversations` = `combine` of the four `observeIds()` into their union, `distinctUntilChanged`.
- `HostAttentionState` gains `busy: Set<String> = emptySet()` and `withBusy(ids: Set<String>)`. `resolve` adds `busy` to the candidate ids and passes `running = id in running || id in busy`. `disconnected()` clears `busy` as well as `running`. `resolveAttention` and the precedence order are unchanged: waiting, running, unread, idle.
- `ConversationAttention.Running` KDoc names the four busy facts.
- `HostConversationSource.launchAttention` gains one collector: `connection.repositories.collectLatest { it?.observeBusyConversations()?.collect { ids -> updateAttention(entry) { attention = attention.withBusy(ids) } } }`. A null repository observes nothing; the existing null-repository collector's `disconnected()` clears the busy set.

Kept differences from desktop, as the ticket states: a stall clears on any decoded live event, and a reset also clears on the conversation's `session_transition`.

## State and concurrency model

The projections' `StateFlow`s are written only by the repository's inbound collector; the union is a cold `combine` over them. The new collector runs under `entry.job` on the source's dispatcher, so a replaced bundle cancels it with the others. `collectLatest` cancels the previous repository's subscription before observing the next, and `updateAttention` is non-suspending under the source's monitor with the `isCurrent` guard, so a retired generation cannot publish. A fresh repository's projections start empty, so a reconnect starts with no busy ids.

## Error handling

No new failure mode: the four projections already drop malformed frames, and the union reads in-memory `StateFlow`s that do not throw. Ids are equality keys only and are never logged.

## Testing strategy

- `ConversationAttentionTest` (unit): a busy conversation with no running turn resolves Running; an outstanding prompt or question batch outranks busy; busy outranks Unread; `disconnected()` clears busy; `withBusy` replaces the set.
- `HostConversationSourceAttentionTest` (unit): two hosts, each with a real `RemoteConversationRepository` over a channel-backed fake pump and two conversations. For each fact, drive its rising frame on one conversation of host `a` and assert only that row on that host is Running while no turn is running; then drive its existing clear edge (stall: a decoded live event; API retry: `active:false`; compacting: `active:false`; reset: falling edge, and separately `session_transition`) and assert it is Idle. A disconnect (`repositories` to null) clears the busy blink on that host only.
- No screen or device test: no UI changes; the existing Running blink is reused. Not an operator-facing flow change needing a real-Claude scenario: the dot reads state the thread's indicators already exercise.

## Open Questions

None.
