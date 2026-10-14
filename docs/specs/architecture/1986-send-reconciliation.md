# Sent and queued message reconciliation (#1986)

## Files read

- `data/repository/AuthoritativeThreadStore.kt`: `publish`, `merge`, `reset` own admitted revisions and replacement generations.
- `data/repository/AuthoritativeThreadQueries.kt`: supplied live/catch-up/page batches share store admission; certification remains unchanged.
- `data/repository/MessageCommands.kt`: `sendMessage` currently writes a legacy echo before awaiting receipt; legacy queue actions remain unchanged.
- `data/repository/RemoteConversationRepository.kt`: supplied host/store and capability boundary; add identity and command forwarding here.
- `data/repository/RelayRepositoryCoordinator.kt`: `onConnection` owns reconnect-spanning inputs.
- `di/RelayConnectionFactory.kt`: public Noise identity comes from the existing per-host `DeviceStaticKeyStore`.
- `data/repository/ConversationRepository.kt`, `StableConversationRepository.kt`, `CachingConversationRepository.kt`: additive command contract and forwarding, including delegated cache forwarding.
- `ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread` currently excludes unordered facts.
- `ui/conversations/thread/ThreadRow.kt`, `ThreadScreen.kt`, `ThreadUiState.kt`, `ThreadViewModel.kt`: retained row identity, queue callbacks and supplied presentation ownership.
- `ui/conversations/components/QueuedMessageRow.kt`: preserve existing waiting bubble and action geometry.
- `docs/knowledge/features/thread-screen.md`, `queued-backlog.md`, `remote-conversation-repository.md`, `device-static-keystore.md`: legacy queue disappearance is not authoritative delivery evidence; use public-key identity, never display name.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`: Full thread item, Daemon thread updates and Queue (v2) are the wire authority.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot. Preserve right-aligned user bubbles, bodyMedium text, primary/onPrimaryContainer roles, the existing composer and queue controls. Sending and Lost use the existing queue/status treatment with a visible label; no new assets or screen geometry.

## Context

Receipt is not delivery. An accepted item replaces provisional feedback using exact originating device and client message identity, including through recovery with no acknowledgement. Activation and combined live evidence belong to #1988. No new decision record is needed.

## Design

Add process-memory pending sends to the existing authoritative store snapshot, scoped by host/conversation. Register the minted message before sending. Store publication retires pending sends only against admitted `user_message` facts whose string `device_id` and `message_id` match exactly; hidden terminal facts also retire feedback. No acknowledgement ledger or query changes.

The supplied authoritative command route uses the sending host's authenticated public key as lowercase hex. Defaulted additive wiring preserves legacy callers. The coordinator owns the store across connection replacement; #1988 supplies activation. Public identity reads do not load or export private keys.

Projection appends shown unordered queued/lost user items sorted by acceptance ID, then unresolved sends in local order. Queue-to-delivered keeps the same scoped list key. Pending and Lost rows have no actions. Invalid handles render queued text without actions. Extend the existing queue row with optional identity, target and controlled status fields rather than adding a parallel screen model.

Add a bound queue target containing host, conversation, epoch, generation, acceptance item ID and exact uint64 handle. Capture it in a queue event through the existing screen event callback. The ViewModel checks supplied presentation ownership and Send now eligibility; the remote checks the current admitted item and scope immediately before sending. Both verbs encode `conversation_id`, numeric `queued_msg_id` and numeric `accepted_item_id`; neither updates lifecycle optimistically nor consults `queue_state`. Existing unbound methods stay unchanged.

Estimated written work: 1450 lines including plan, ~600 production/wiring and ~750 tests. New types: pending send, bound target, queue event (3); no consumer signature removal or mandatory migration. Five acceptance criteria; target rejection paths remain below ten. Overlap: #1947 edits a separate settings projection in `ThreadViewModel`; keep changes local.

## State and concurrency model

Store mutation remains synchronized and publishes one hot snapshot. Registration and admitted-item retirement use the same store lock. No late acknowledgement or error writes authoritative state. Pending sends survive background/socket close and connection replacement within the app process; reset preserves unresolved feedback while fencing old facts/actions by generation. No resend and no disk persistence. Commands run in `viewModelScope`; cancellation follows existing request/connection ownership. Key reads use the existing suspending store contract and dispatcher.

## State transitions and identity reuse

| Event | Probe |
| --- | --- |
| Send then ack, item then ack, duplicate input, late failure | `AuthoritativeSendReconciliationTest` receipt-order probes |
| Lost ack recovered through catch-up / page independently | repository fake-pump query probes |
| Peer/client-ID, host, conversation or missing-device collision | store identity probes |
| Older overlapping/partial page, empty batch | held revision and omission probes |
| Queue delivery, hidden drop, shown restart loss | presentation lifecycle/key probes and shared screen tests |
| Disconnect/replacement, epoch reset/reused generation | pending retention and stale action probes |
| Reused queue handle, terminal item, failed/no-op action | bound payload/retirement probes |
| Screen re-collection / receiver replacement | existing action publisher plus captured queue event tests |
| Sending, Waiting or Lost tail remains while viewed history gains a delivered reply | `sendingWaitingAndLostTailsPreserveExactHistoryReadRequirements` and the three `AuthoritativeReadViewportTest` tail probes |
| Process death | pending feedback intentionally excluded from cache; accepted items use existing cache contract |

## Error handling

Send failures leave provisional feedback; they cannot imply delivery/loss. Bound actions return `Result<Unit>` with static failure messages and content-free event codes. Invalid/currently retired targets send nothing, never an unbound fallback. A failed send or silent daemon no-op leaves held lifecycle unchanged.

## Testing strategy

Test first with focused store/repository/presentation unit probes and fake pumps; exercise real store admission and query input. Shared Robolectric screen tests prove Sending, Waiting and Lost text plus bound callbacks and absent terminal/invalid controls. Retain existing legacy queue, row identity, Send now and authoritative session/read tests. Run focused unit/shared tests, lint, assemble, Android-test compilation for shared tests, Spotless and final pre-verify. Report executed/failed/skipped counts. No device-only test or dependency is added. #1988 owns the rung-3 reconnect/combined queue proof and deterministic joined flow; this supplied-input slice does not activate an operator flow.

## Open Questions

None.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/thread-screen.md`, What it does: pending-send ownership, receipt-only acknowledgement, queued/delivered/hidden-drop/Lost identity and placement, and read-eligible history beneath waiting tails.
- `docs/knowledge/features/queued-backlog.md`, Dropping a queued entry / Sending a queued entry now: exact guarded payloads, current-session eligibility, malformed/terminal/stale rejection, failure/no-op behavior and retained legacy controls.
- `docs/knowledge/features/remote-conversation-repository-send-create-promote-rename.md`, sendMessage, and `remote-conversation-repository-reads-and-thread-store.md`, Authoritative daemon items alongside the raw-event projection: exact host/conversation/device/client correlation, admitted-revision/omission rules, process-memory/reconnect limits and no automatic resend.
- `docs/knowledge/features/device-static-keystore.md`, Wiring & usage: per-host public-key wiring without private-key access. Preserve #1988's ownership of activation and combined deterministic/live proof; that evidence remains pending.

## Revisions

- 2026-10-14: Preserve `QueuedMessageRow`'s public signature (eleven existing callers). Its body delegates to an internal `AuthoritativeSendRow` used by the thread; legacy defaults are unchanged. Four new types/composables total. Register provisional feedback before the suspending public identity read, then identify it under the store lock; this preserves immediate feedback even when key storage is delayed.
- 2026-10-14: Connection collector completion also retires its bound command source, even when a supplied test capability function remains true. Reconnect may forward a captured target only through the replacement repository and after current store validation.
- 2026-10-14: `retiredSendSourceCannotRegisterFeedbackInItsReplacementStore` failed because a caller retained a disconnected repository reference. Supplied host sources now reject a new send before provisional registration after collector completion; default legacy sources retain their existing path. Source refusal emits a static lifecycle/error code.
- 2026-10-14: Verifier finding 1 exposed that waiting tails became the newest read key despite carrying no ordered revision. `AuthoritativeThreadPresentation.newestReadKey` now selects the final delivered row with admitted shown revision evidence; both `ThreadScreen` candidate selection and `readCheckpoint` use it. All exact sight, summary and certified-coverage requirements remain in force, and no provisional or unordered tail can certify reads. Shared viewport probes cover each tail and a delivered reply while it remains; checkpoint probes also reject tail keys, stale/missing sight and incomplete coverage. No in-flight branch overlaps the rework files.

## Security review

**Verdict:** PASS

- Trust boundaries: MUST FIX addressed in design. Parse handle as canonical positive uint64 integer, reject strings/fractions/exponents/zero/overflow; require current shown active queued user item. Text remains bounded inert Compose text.
- Tokens/credentials: no new secrets. Read only the per-host public-key mirror; never substitute device name or regenerate keys. Do not log device identity, handles, accepted IDs or message text.
- Files/storage: pending sends stay in memory; existing authoritative cache retains its storage contract and never persists provisional feedback. No new filesystem path.
- Android attack surface: no component, Intent, provider or WebView changes.
- Cryptography: existing Noise variant and key custody remain unchanged; public identity equality is correlation, not secret comparison or authentication.
- Network/I/O: reuse envelope, pump and existing deadlines/caps; exact integers avoid floating-point conversion.
- Errors/logs: static action/settlement event codes only; redacted `toString` for new content-bearing types.
- Concurrency: MUST FIX addressed in design. Bound target revalidation and send have no suspension between them; generation fences old facts/actions, and acknowledgements cannot recreate feedback.
- Read-evidence rework: only delivered rows present in `shownRowRevisions` supply `newestReadKey`; a forged tail key or revision cannot qualify. Existing scope/connection ownership, exact revision sight and certified coverage still gate the ViewModel's write. No new I/O, secret, storage, rendering or coroutine boundary is introduced.
- Threat model: relay delay/reordering/drop is covered by recovery and revision probes. Hostile malformed content cannot create actionable targets. Existing Keystore wrapping handles token theft; UI screenshot/accessibility exposure remains existing platform behavior outside this slice (#1988 does not change it).

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-14
