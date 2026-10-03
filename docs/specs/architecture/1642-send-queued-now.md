# Send now for queued thread messages (#1642)

## Files read

- `data/network/SessionSettingsPayloads.kt`: `SessionCapabilitiesDto` and `toSessionSettings` decode capability reports.
- `data/repository/ConversationRepository.kt`: `SessionCapabilities`, `SessionSettings`, and queue commands define the portable contract.
- `data/repository/MessageCommands.kt`: `dropQueuedMessage` demonstrates the fire-and-forget send and correlation ledger.
- `data/repository/RemoteConversationRepository.kt`: `onInbound`, queue settlement, and forwarding own one connection.
- `data/repository/StableConversationRepository.kt`: `dropQueuedMessage` forwards to the thread's owning host.
- `data/repository/ThreadProjection.kt`: `settleQueuedEchoes`, `appendLiveMessage`, and `observe` own echo ordering.
- `data/repository/CachingConversationRepository.kt`: `observeMessages` must carry awaiting-delivery suppression across the restored-row merge; `caching-conversation-repository.md` explains why unrelated cache-only rows must remain anchored.
- `ui/conversations/thread/ThreadViewModel.kt`: `sessionSettings`, `runConfig`, and `onDropQueued` provide current readings and cancellation-safe failure handling.
- `ui/conversations/thread/ThreadUiState.kt`: `ThreadRunConfig` holds session support.
- `ui/conversations/thread/ThreadScreen.kt`, `MainActivity.kt`: queued-row rendering and host-bound callbacks.
- `ui/conversations/components/QueuedMessageRow.kt`: dimmed bubble, waiting glyph, and independent drop button.
- `docs/knowledge/features/queued-backlog.md`: #1636 requires idle-queued echoes to retain their original position.
- `docs/knowledge/features/queued-backlog-section.md`, `thread-screen.md`: shared bubble geometry and destination host ownership.
- `docs/knowledge/features/development-verification-gates.md`, `docs/e2e-interactive-stream.md`: shared Compose tests and isolated real-Claude harness.
- `../pyrycode/docs/protocol-mobile.md`: Queue (v2), capabilities, and Security model are the wire source of truth (read from the canonical sibling checkout).
- Queue, projection, settings, ViewModel and live test sources: existing fakes and ordering assertions.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=696-4677

Inspected design context and screenshot: waiting schedule glyph (16 dp), user bubble at 60% opacity, bodyMedium text, on-primary container/text roles, and trailing close icon inside a 48 dp action. There is no separate Send now frame: per #1642, extend this row with a low-emphasis send icon immediately before drop, also 48 dp. Bound the bubble's width with row weight so wrapped text cannot consume either action. Preserve existing shared bubble tokens and inset.

## Context

The daemon has shipped send-now and delayed delivery placement. Backlog removal confirms the write, while the later user message establishes where Claude read it. The phone must separate those observations without changing ordinary idle drain or drop.

Sizing: forecast approximately 1000–1300 written lines including tests and this plan, one new internal DTO, no new exported types, one UI callback consumer requiring update, five acceptance criteria and fewer than ten decision branches. Existing defaulted capability constructors need no simultaneous migration. Codegraph returned no callers for these Compose/data symbols; repository search supplies the call-site inventory. Overlap: #1283 changes other ThreadScreen status/overlay blocks; edits here are local to the queued row and callback.

## Design

Add fail-closed `midTurnInput` (default false) to DTO/domain capability models. Expose Send now only from a fresh, nonempty-session reading explicitly reporting true. Replacements, null reports and held readings disable it. Add `sendQueuedNow(conversationId, queuedMessageId)` beside drop, with remote/stable forwarding, an encode-only payload, and a raw pump send without reply waiters.

Record a separate own-echo send-now intent before sending and withdraw it on failure. Correlate only through the existing minted-id ledger; never reuse pending drops or compare text. On queue removal, that own echo waits hidden until its live message arrives. A busy queued echo removed while its turn remains open also waits, covering another client's send-now action. Closed-turn removal retains ordinary drain's immediate settlement but leaves placement pending until the first delivered push, because a late peer control is indistinguishable from ordinary drain. That push corrects the own user echo's position once, preserving local attachment metadata, and consumes placement eligibility; duplicate pushes never relocate it again. Idle-queued echoes retain tap-time placement. Foreign live messages use normal id deduplication. Hidden pending echoes remain skipped by assistant-delta segmentation until delivery.

The screen binds the row's integer id to `ThreadViewModel.onSendQueuedNow`. The ViewModel uses the destination's repository, guards capability support, and mirrors drop's inert failure handling. No confirmation or optimistic queue mutation. Content-free structured send/failure events contain only static codes.

## State and concurrency model

Use the existing `viewModelScope`, cold session-settings subscription and StateFlow state; no additional subscriptions or jobs. Retain the last nonempty current-session selection in the shared conversation subscription, so an empty summary placeholder cannot undo a known replacement invalidation. Only a matching fresh settings reading restores support. Echo metadata remains connection-scoped in `ThreadProjection` with atomic StateFlow updates. Record intent before pump enqueue to cover an immediate inbound confirmation; rollback only on a refused send. Connection teardown drops the projection. Lifecycle background socket closure and stable host forwarding remain unchanged.

## Error handling

A disconnected control send throws the existing static IllegalStateException at the repository boundary; ViewModel catches it and RelayErrorException exactly like drop, while rethrowing CancellationException. A malformed capability frame cannot enable support. No message text, identifiers, payload or exception contents enter new logs.

## Testing strategy

Test first: capability true/false/omitted/absent decoding; queue-removal then tool events then own/foreign message delivery, reversed arrival order, duplicate pushes, failed-send rollback, ordinary drain/drop and idle-queued regression tests. Repository tests assert exact payload and no reply waiter, no optimistic mutation and conversation isolation. ViewModel tests cover replacement readings, failure and owning conversation. Shared Compose tests cover capability visibility, independent callbacks/accessibility and pointer taps on both 48 dp actions with wrapped text at narrow width; run existing QueuedBacklog and palette coverage.

Land `InteractiveStreamE2ETest.interactiveTurn_sendQueuedNow_reachesRunningTurn` and add it to the curated live list. Use a harness-owned release-file Bash hold, released only after the harness daemon logs send-now delivery. Assert backlog clearing, single user row after tool result, and the original turn's final marker reply. Host daemon/relay access is the device-only reason. Add a deterministic scripted twin where the fixture can hold delivery open. Run focused JVM tests, the relevant scripted scenario, lint, assembleDebug, androidTest compilation and forced spotlessCheck. Dispatcher owns full real-Claude execution and its executed/failed/skipped counts.

## Open Questions

None. Live execution remains explicitly pending for the dispatcher.

## Documentation handoff

Pending documentation stage: record the no-separate-frame Send now decision in `app/src/androidTest/assets/design-1220/README.md`, referencing queued-row frame `696:4677`. Update queue/thread feature documentation with capability gating, fire-and-forget control, and backlog removal versus delivered-message placement. Document live evidence after dispatcher execution.

Pending documentation stage: update `docs/knowledge/features/caching-conversation-repository.md`, "How the restore merges with live rows" and "The merge base moves at a connection boundary, not on every emission", with awaiting-push suppression and empty visible readings while delivery is pending.

## Security review

**Verdict:** PASS

- [Trust boundaries] Fail closed in `SessionCapabilitiesDto` and the fresh-session UI gate; only explicit true enables the control. MUST FIX resolved: `ThreadViewModel` retains the last nonempty selected session across an s1 → s2 → empty-summary sequence; s1 support remains invalid until a fresh s2 report arrives. Echo correlation in `ThreadProjection` requires locally minted ids and user role; foreign ids cannot delete or relocate unrelated rows. Existing text bounds and inert Text rendering are retained.
- [Tokens] No credential generation, storage or access changes; no credentials enter payloads or logs.
- [Files and storage] No production file paths or storage formats are introduced. The existing app-private, backup-excluded file cache writes the drawn thread without awaiting-push echoes, retaining unrelated restored history. Suppression remains connection-local and is not serialized. Live release files are test-harness minted inside its temporary directory.
- [Android attack surface] No exported components, intents, providers, WebViews or permissions change. Accessibility carries local action copy and the existing rendered user text.
- [Cryptography] Reuse existing Noise pump and TLS transport unchanged; no nonce, key or crypto changes.
- [Network and I/O] Raw fire-and-forget uses existing bounded transport and no reply timeout. Capability is advisory; daemon retains authorization. Relay delay/drop cannot cause optimistic delivery.
- [Errors and logs] Static lifecycle/error events only, no message text, ids, payloads, secrets or exception messages. Existing queue-drop user failure treatment stays inert.
- [Concurrency] MUST FIX resolved in design: record intent before send only for a minted echo already queued behind a turn, rollback refused sends, and retain deferred segmentation until the delivered push. Busy echoes removed while a turn is open also defer without local intent, because the control issuer can be another client. Closed-turn removal without local intent retains provisional ordinary settlement and first-push placement eligibility: turn end cannot distinguish a late peer Send now. That eligibility is spent by the first push; duplicates cannot reposition the row, dropped ids cannot move, and idle classification is never overridden. All metadata is connection-scoped and atomic.
- [Concurrency and restored state] MUST FIX resolved: `ThreadSnapshotSource` carries visible rows and suppressed own-user ids in one projection emission through the stable facade into the cache reader. The cache merge excludes only those user ids from its restored base; absent live rows alone never authorize deleting offline/history rows. Empty visible rows with pending suppression are not interpreted as a connection boundary. Delivery clears suppression only after establishing the live echo's position, so the restored copy cannot flash before its push. No scope, job, lock or separate metadata subscription is introduced.
- [Threat model] Compromised relay cannot decrypt controls; delayed frames are handled without trusting backlog removal as message placement. Hostile daemon flags fail closed and existing message decoding/text bounds remain. Rooted-device token theft and UI capture retain the existing Keystore and UI threat model; this change introduces no token surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-03

## Revisions

2026-10-03: `queue_state` has no delivery-kind discriminator, so inferring Send now from an open turn would change ordinary-drain settlement and break its existing contract. Defer only locally requested, minted echoes; foreign messages have no local echo to relocate and append once at their daemon-reported position. Preserve the previous ordinary-drain/idle tests unchanged. `forLiveSession` disables mid-turn support while a new current session is waiting for its settings reply.

2026-10-03: outbound send-now intent makes queue metadata a two-writer flow. Queue settlement now derives its candidate inside the atomic update rather than writing a stale metadata snapshot, preserving intents recorded concurrently with inbound settlement. This resolves the concurrency finding in the security review; no new job or lock is introduced.

2026-10-03: the malformed-capability test demonstrated that the Boolean serializer accepts a quoted `"true"`. The DTO now retains the optional raw token and `toSessionSettings` enables support only when it equals literal JSON true; malformed additions preserve the rest of the reading while failing closed. Security review [Trust boundaries]: MUST FIX resolved by this strict token check and regression test.

2026-10-03 (verifier rework): supersedes the local-intent-only settlement revision. The echo's origin and the client issuing Send now are independent: an own echo removed from an open turn's backlog now waits hidden for the delivered push even without local intent. Local intent still defers a busy echo if the turn closes before backlog removal. `recordSendNow` records only parked echoes, and `appendLiveMessage` moves only parked or awaiting-push echoes, preserving idle tap-time placement in either confirmation order. Security review [Concurrency]: both MUST FIX findings resolved by retaining the original busy/idle classification and using the daemon's push for mid-turn placement; minted-id and user-role guards remain unchanged. Other security categories are unchanged: no new I/O, logging, credentials, storage, jobs or attack surface.

2026-10-03 (verifier rework tests): add own-echo/peer-control repository and projection regressions, idle-intent tests in both confirmation orders, and a local-intent/turn-end race. Ordinary-drain projection fixtures now explicitly supply `turnOpen = false` after the turn ends (including the repeated-id fixture, which previously never ended its turn), with their final ordering assertions unchanged and a pre-push assertion added. Failed-send rollback now queues behind an open turn and then drains while idle, so it would expose a leaked intent. Move the misplaced MCP KDoc back to `mcpServersSupported` and document the fresh-session gate on `midTurnInputSupported`.

2026-10-04 (second verifier rework): retain `lastKnownSessionId` in the shared conversation subscription; empty summary placeholders cannot restore a previous session's support after replacement. Extend the capability regression with s1 → s2 → empty-summary, inert callback while invalidated, and restoration only by a fresh s2 report. A closed turn and absent local intent do not identify ordinary drain: `OwnEchoQueue.placementPending` now retains busy drained echoes until their first delivered push, while ordinary drain's immediate settlement remains unchanged. The first push corrects position and consumes eligibility even when removal followed turn end; idle echoes never acquire it, and duplicate pushes cannot relocate rows. Projection and repository regressions prove late peer delivery, attachment preservation, duplicate/repeated-id stability and conversation isolation. Preserve the original awaiting-push cleanup independently of minted placement eligibility, so a delivered push winning a drop race still renders once after the dropped id was spent; a regression exposed and guards this seam. The tests failed before the repair. Security review trust-boundary and concurrency findings above cover both changes; the other categories remain unchanged.

2026-10-04 (third verifier rework): the production cache merge can resurrect an awaiting-push echo after a same-connection thread reopen. Add the narrow `ThreadSnapshotSource` contract beside the cache consumer, with `ThreadSnapshot` holding visible rows and suppressed user ids together. Remote and stable repositories implement it; repositories without queue suppression keep their existing message-list fallback. `ThreadProjection.observe` remains the list-only consumer API, derived from the same snapshot projection. The caching reader filters only suppressed user ids from its once-read base before the existing merge, preserves the base for attachment hints on delivery, and rebases on an empty snapshot only when no suppression is active. Wrapper-level tests use Remote → Stable → Caching and a real file cache, proving local/peer controls, reopen, separated removal/tool/push, duplicates and offline history retention. Storage/concurrency security findings above cover restored state; the other review categories are unchanged. Overlap with #1677 is additive in separate remote/stable blocks. Forecast: under 1500 written lines total, two new exported types, three production consumers, five acceptance criteria and fewer than ten reject branches. Pending documentation also includes the owning cache topic's restored-echo behavior.
