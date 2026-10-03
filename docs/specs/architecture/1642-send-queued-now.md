# Send now for queued thread messages (#1642)

## Files read

- `data/network/SessionSettingsPayloads.kt`: `SessionCapabilitiesDto` and `toSessionSettings` decode capability reports.
- `data/repository/ConversationRepository.kt`: `SessionCapabilities`, `SessionSettings`, and queue commands define the portable contract.
- `data/repository/MessageCommands.kt`: `dropQueuedMessage` demonstrates the fire-and-forget send and correlation ledger.
- `data/repository/RemoteConversationRepository.kt`: `onInbound`, queue settlement, and forwarding own one connection.
- `data/repository/StableConversationRepository.kt`: `dropQueuedMessage` forwards to the thread's owning host.
- `data/repository/ThreadProjection.kt`: `settleQueuedEchoes`, `appendLiveMessage`, and `observe` own echo ordering.
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

Record a separate own-echo send-now intent before sending and withdraw it on failure. Correlate only through the existing minted-id ledger; never reuse pending drops or compare text. On queue removal, that own echo waits hidden until its live message arrives. A busy queued echo removed while its turn remains open also waits, covering another client's send-now action. Ordinary closed-turn drain retains its existing settlement; idle-queued echoes retain tap-time placement. The live push moves a deferred own user echo to the current end once, preserving local attachment metadata, and releases the pending state. Foreign live messages use normal id deduplication. Pending echoes remain skipped by assistant-delta segmentation until delivery.

The screen binds the row's integer id to `ThreadViewModel.onSendQueuedNow`. The ViewModel uses the destination's repository, guards capability support, and mirrors drop's inert failure handling. No confirmation or optimistic queue mutation. Content-free structured send/failure events contain only static codes.

## State and concurrency model

Use the existing `viewModelScope`, cold session-settings subscription and StateFlow state; no additional subscriptions or jobs. Echo metadata remains connection-scoped in `ThreadProjection` with atomic StateFlow updates. Record intent before pump enqueue to cover an immediate inbound confirmation; rollback only on a refused send. Connection teardown drops the projection. Lifecycle background socket closure and stable host forwarding remain unchanged.

## Error handling

A disconnected control send throws the existing static IllegalStateException at the repository boundary; ViewModel catches it and RelayErrorException exactly like drop, while rethrowing CancellationException. A malformed capability frame cannot enable support. No message text, identifiers, payload or exception contents enter new logs.

## Testing strategy

Test first: capability true/false/omitted/absent decoding; queue-removal then tool events then own/foreign message delivery, reversed arrival order, duplicate pushes, failed-send rollback, ordinary drain/drop and idle-queued regression tests. Repository tests assert exact payload and no reply waiter, no optimistic mutation and conversation isolation. ViewModel tests cover replacement readings, failure and owning conversation. Shared Compose tests cover capability visibility, independent callbacks/accessibility and pointer taps on both 48 dp actions with wrapped text at narrow width; run existing QueuedBacklog and palette coverage.

Land `InteractiveStreamE2ETest.interactiveTurn_sendQueuedNow_reachesRunningTurn` and add it to the curated live list. Use a harness-owned release-file Bash hold, released only after the harness daemon logs send-now delivery. Assert backlog clearing, single user row after tool result, and the original turn's final marker reply. Host daemon/relay access is the device-only reason. Add a deterministic scripted twin where the fixture can hold delivery open. Run focused JVM tests, the relevant scripted scenario, lint, assembleDebug, androidTest compilation and forced spotlessCheck. Dispatcher owns full real-Claude execution and its executed/failed/skipped counts.

## Open Questions

None. Live execution remains explicitly pending for the dispatcher.

## Documentation handoff

Pending documentation stage: record the no-separate-frame Send now decision in `app/src/androidTest/assets/design-1220/README.md`, referencing queued-row frame `696:4677`. Update queue/thread feature documentation with capability gating, fire-and-forget control, and backlog removal versus delivered-message placement. Document live evidence after dispatcher execution.

## Security review

**Verdict:** PASS

- [Trust boundaries] Fail closed in `SessionCapabilitiesDto` and the fresh-session UI gate; only explicit true enables the control. Echo correlation in `ThreadProjection` requires locally minted ids and user role; foreign ids cannot delete or relocate unrelated rows. Existing text bounds and inert Text rendering are retained.
- [Tokens] No credential generation, storage or access changes; no credentials enter payloads or logs.
- [Files and storage] No production file paths or storage are introduced. Live release files are test-harness minted inside its temporary directory.
- [Android attack surface] No exported components, intents, providers, WebViews or permissions change. Accessibility carries local action copy and the existing rendered user text.
- [Cryptography] Reuse existing Noise pump and TLS transport unchanged; no nonce, key or crypto changes.
- [Network and I/O] Raw fire-and-forget uses existing bounded transport and no reply timeout. Capability is advisory; daemon retains authorization. Relay delay/drop cannot cause optimistic delivery.
- [Errors and logs] Static lifecycle/error events only, no message text, ids, payloads, secrets or exception messages. Existing queue-drop user failure treatment stays inert.
- [Concurrency] MUST FIX resolved in design: record intent before send, rollback refused sends, and retain deferred segmentation until the delivered push; all metadata is connection-scoped and atomic.
- [Threat model] Compromised relay cannot decrypt controls; delayed frames are handled without trusting backlog removal as message placement. Hostile daemon flags fail closed and existing message decoding/text bounds remain. Rooted-device token theft and UI capture retain the existing Keystore and UI threat model; this change introduces no token surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-03
